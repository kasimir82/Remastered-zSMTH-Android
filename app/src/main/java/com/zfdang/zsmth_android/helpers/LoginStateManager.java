package com.zfdang.zsmth_android.helpers;

import android.util.Log;

import com.zfdang.zsmth_android.Settings;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;

/**
 * 登录状态机（唯一入口）。
 *
 * 三态语义：
 *   LOGGED_IN       - 会话有效（NewExpress 验证通过）
 *   USER_LOGGED_OUT - 用户主动注销，禁止一切自动登录，只能手动恢复
 *   SESSION_LOST    - 会话失效/被踢/未登录，允许自动登录（受限流约束）
 *
 * 设计要点：
 *  - getState() 同步读缓存，供 UI 秒显
 *  - refreshState() 异步访问 NewExpress 实际验证并修正状态（判定已登录的权威方式）
 *  - 所有"自动登录资格判断"统一走 canAutoLogin()
 *  - 冷却/锁/节流等时间驱动参数作为状态机上下文统一管理，时间到自动解除
 */
public final class LoginStateManager {
    private static final String TAG = "LoginStateMgr";
    public static final String TAG_DBG = "LOGINDBG";

    /** 静默登录成功后的1分钟锁，防并发重复登录 */
    private static final long SILENT_SUCCESS_LOCK_MS = 1L * 60 * 1000;
    /** 距上次登录至少1分钟才允许重登，避免"窗口过多"循环 */
    private static final long MIN_RELOGIN_INTERVAL_MS = 1L * 60 * 1000;

    private LoginStateManager() {}

    // ============ 状态读取 ============

    /** 同步读当前状态（缓存，供UI秒显） */
    public static LoginState getState() {
        return LoginState.fromString(Settings.getInstance().getLoginState());
    }

    public static boolean isLoggedIn() {
        return getState() == LoginState.LOGGED_IN;
    }

    /** 是否处于"用户主动退出"态（禁止自动登录） */
    public static boolean isUserLoggedOut() {
        return getState() == LoginState.USER_LOGGED_OUT;
    }

    // ============ 状态转换（事件驱动） ============

    /** 登录成功（真提交表单并拿到新会话）→ LOGGED_IN，记录时间、清限流 */
    public static void onLoginSuccess(String userId) {
        Log.d(TAG_DBG, "onLoginSuccess ENTER userId=" + userId);
        Settings s = Settings.getInstance();
        s.setLoginState(LoginState.LOGGED_IN.name());
        s.setLastLoginTime(System.currentTimeMillis());
        s.setLoginCooldownUntil(0);
        s.setAutoLoginLockedUntil(0);
        s.setLoginRestrictionReason("");
        // 兼容旧字段：同步user_online/last_login_success，供尚未迁移的读取点使用
        s.setUserOnline(true);
        s.setLastLoginSuccess(true);
        Log.i(TAG, "onLoginSuccess -> LOGGED_IN, " + userId);
    }

    /** 静默登录成功：转 LOGGED_IN + 上1分钟锁 */
    public static void onSilentLoginSuccess(String userId) {
        onLoginSuccess(userId);
        Settings.getInstance().setAutoLoginLockedUntil(System.currentTimeMillis() + SILENT_SUCCESS_LOCK_MS);
        Log.i(TAG, "onSilentLoginSuccess -> LOGGED_IN, lock 1min");
    }

    /** 用户主动注销 → USER_LOGGED_OUT，禁止自动登录 */
    public static void onUserLogout() {
        Log.d(TAG_DBG, "onUserLogout ENTER");
        Settings s = Settings.getInstance();
        s.setLoginState(LoginState.USER_LOGGED_OUT.name());
        s.setLoginCooldownUntil(0);
        s.setAutoLoginLockedUntil(0);
        s.setLastLoginTime(0);
        s.setUserOnline(false);
        s.setLastLoginSuccess(false);
        clearActiveUser();
        Log.i(TAG, "onUserLogout -> USER_LOGGED_OUT (auto login disabled)");
    }

    /** 任何登录失败 → SESSION_LOST（区别于用户主动退出！） */
    public static void onLoginFailed(String reason) {
        Log.e(TAG_DBG, "onLoginFailed ENTER reason=" + reason);
        Settings s = Settings.getInstance();
        // 若用户已主动退出，失败不改变其"主动退出"态
        if (s.getLoginState().equals(LoginState.USER_LOGGED_OUT.name())) {
            Log.i(TAG, "onLoginFailed skipped: already USER_LOGGED_OUT");
            return;
        }
        s.setLoginState(LoginState.SESSION_LOST.name());
        s.setUserOnline(true);  // 保留自动登录意愿：SESSION_LOST 应允许自动重登
        s.setLastLoginSuccess(false);
        s.setAutoLoginLockedUntil(0);
        clearActiveUser();
        Log.w(TAG, "onLoginFailed -> SESSION_LOST, reason=" + reason);
    }

    /**
     * 密码错误 → SESSION_LOST 但撤销自动登录意愿。
     * 用错误密码自动重试没有意义，必须等用户手动改密码登录。
     * 状态仍是 SESSION_LOST（不是 USER_LOGGED_OUT，因为不是用户主动退出），
     * 但 user_online=false 让 canAutoLogin() 返回 false，暂停自动重试。
     */
    public static void onPasswordError() {
        Log.w(TAG_DBG, "onPasswordError -> SESSION_LOST, auto login PAUSED");
        Settings s = Settings.getInstance();
        if (s.getLoginState().equals(LoginState.USER_LOGGED_OUT.name())) return;
        s.setLoginState(LoginState.SESSION_LOST.name());
        s.setUserOnline(false);  // 撤销自动登录意愿：错误密码不能自动重试
        s.setLastLoginSuccess(false);
        s.setAutoLoginLockedUntil(0);
        clearActiveUser();
    }

    /** 会话被确认失效（版面验证失败）→ SESSION_LOST，不改 user_online（仍允许自愈重登） */
    public static void onSessionLost(String reason) {
        Settings s = Settings.getInstance();
        // USER_LOGGED_OUT 态不被后台验证覆盖
        if (s.getLoginState().equals(LoginState.USER_LOGGED_OUT.name())) return;
        s.setLoginState(LoginState.SESSION_LOST.name());
        s.setUserOnline(true);  // 被动失效：保持自动登录意愿
        // 关键：清掉锁死的旧cookie用户名，让下次登录能走真登录(提交表单)而非SHORTCUT死循环
        clearStaleUtmpCookie();
        Log.w(TAG, "onSessionLost -> SESSION_LOST, reason=" + reason + ", stale UTMP cleared");
    }

    /** 清掉本地UTMP三件套(写guest+过期)，使下次登录必须走真提交、服务端下发新cookie */
    private static void clearStaleUtmpCookie() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String url = "https://www.newsmth.net/";
            // 写成guest值(水木判未登录的标准值)，让readUserId()返回guest
            cm.setCookie(url, "main[UTMPUSERID]=guest; Domain=.newsmth.net; Path=/");
            cm.setCookie(url, "main[UTMPKEY]=; Domain=.newsmth.net; Path=/; Max-Age=0");
            cm.setCookie(url, "main[UTMPNUM]=; Domain=.newsmth.net; Path=/; Max-Age=0");
            cm.flush();
            Log.i(TAG, "clearStaleUtmpCookie done");
        } catch (Throwable t) {
            Log.w(TAG, "clearStaleUtmpCookie failed", t);
        }
    }

    /** 会话被确认有效 → LOGGED_IN（后台自愈，但不刷新lastLoginTime以免打乱节流） */
    public static void onSessionValid() {
        Settings s = Settings.getInstance();
        if (s.getLoginState().equals(LoginState.USER_LOGGED_OUT.name())) return;
        s.setLoginState(LoginState.LOGGED_IN.name());
        s.setUserOnline(true);
        s.setLastLoginSuccess(true);
        if (s.getLoginCooldownUntil() != 0) s.setLoginCooldownUntil(0);
        s.setLoginRestrictionReason("");
        Log.i(TAG, "onSessionValid -> LOGGED_IN");
    }

    private static void clearActiveUser() {
        com.zfdang.SMTHApplication.activeUser = null;
        com.zfdang.SMTHApplication.displayedUserId = "guest";
    }

    // ============ 会话验证（判定已登录的权威方式） ============

    /**
     * 同步验证（必须在IO线程调用，因内部有网络请求）。
     * 访问 NewExpress 版面验证会话，按结果修正状态并返回。
     */
    public static LoginState refreshStateSync() {
        LoginState before = getState();
        if (before == LoginState.USER_LOGGED_OUT) {
            Log.d(TAG_DBG, "refreshStateSync SKIP: USER_LOGGED_OUT");
            return before;
        }
        String userId = LoginValidator.verifySync();
        LoginState after;
        if (userId != null) {
            onSessionValid();
            after = LoginState.LOGGED_IN;
        } else {
            onSessionLost("board verify failed");
            after = LoginState.SESSION_LOST;
        }
        Log.d(TAG_DBG, "refreshStateSync: " + before + " -> " + after);
        return after;
    }

    /**
     * 异步访问 NewExpress 版面实际验证会话，按结果修正状态。
     * getState() 读缓存供UI秒显；本方法在检查点调用以保证准确。
     */
    public static void refreshState(final StateCallback callback) {
        final LoginState before = getState();
        // 用户主动退出态：不做网络验证，直接返回（避免cookie残留被误判成已登录）
        if (before == LoginState.USER_LOGGED_OUT) {
            Log.d(TAG_DBG, "refreshState SKIP: USER_LOGGED_OUT");
            if (callback != null) callback.onResult(before);
            return;
        }
        LoginValidator.verify(userId -> {
            LoginState after;
            if (userId != null) {
                onSessionValid();
                after = LoginState.LOGGED_IN;
            } else {
                onSessionLost("board verify failed");
                after = LoginState.SESSION_LOST;
            }
            Log.d(TAG_DBG, "refreshState: " + before + " -> " + after);
            if (callback != null) callback.onResult(after);
        });
    }

    public interface StateCallback {
        void onResult(LoginState state);
    }

    // ============ 自动登录资格判断 ============

    /**
     * 自动登录唯一资格判断。
     * 只有 SESSION_LOST 态才允许自动登录；USER_LOGGED_OUT 永远禁止；LOGGED_IN 不需要。
     * 受限流上下文约束：冷却期、静默成功锁、距上次登录<1分钟。
     */
    public static boolean canAutoLogin() {
        LoginState state = getState();
        Settings s = Settings.getInstance();
        dumpState("canAutoLogin");

        if (state == LoginState.USER_LOGGED_OUT) {
            Log.i(TAG_DBG, "canAutoLogin=false: USER_LOGGED_OUT");
            return false;
        }
        if (state == LoginState.LOGGED_IN) {
            Log.d(TAG_DBG, "canAutoLogin=false: already LOGGED_IN");
            return false;
        }
        // SESSION_LOST：检查自动登录意愿(密码错误等场景会撤销)
        if (!Settings.getInstance().isUserOnline()) {
            Log.i(TAG_DBG, "canAutoLogin=false: user_online=false (auto login paused)");
            return false;
        }
        // SESSION_LOST：检查限流
        if (inCooldown()) {
            Log.w(TAG_DBG, "canAutoLogin=false: in cooldown");
            return false;
        }
        if (isLocked()) {
            Log.i(TAG_DBG, "canAutoLogin=false: silent success lock");
            return false;
        }
        long last = s.getLastLoginTime();
        if (last > 0 && System.currentTimeMillis() - last < MIN_RELOGIN_INTERVAL_MS) {
            Log.i(TAG_DBG, "canAutoLogin=false: logged in <1min ago");
            return false;
        }
        Log.d(TAG_DBG, "canAutoLogin=TRUE (SESSION_LOST, no limits)");
        return true;
    }

    // ============ 限流上下文（时间驱动，时间到自动解除） ============

    public static boolean inCooldown() {
        return System.currentTimeMillis() < Settings.getInstance().getLoginCooldownUntil();
    }

    public static boolean isLocked() {
        return System.currentTimeMillis() < Settings.getInstance().getAutoLoginLockedUntil();
    }

    public static long getRemainingCooldownMs() {
        return Math.max(0, Settings.getInstance().getLoginCooldownUntil() - System.currentTimeMillis());
    }

    /** 服务端判定"窗口过多/频繁登录"：设冷却时间戳 */
    public static void onTooFrequent(String reason) {
        long cooldownMs = 1L * 60 * 1000; // 所有锁止上限1分钟
        if (reason != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*(分钟|分|min)").matcher(reason);
            if (m.find()) {
                try {
                    int mins = Integer.parseInt(m.group(1));
                    if (mins > 0) cooldownMs = Math.min(mins, 1) * 60000L;
                } catch (NumberFormatException ignored) {}
            }
        }
        Settings s = Settings.getInstance();
        s.setLoginCooldownUntil(System.currentTimeMillis() + cooldownMs);
        s.setLoginRestrictionReason(reason == null ? "登录受限" : reason);
        Log.w(TAG, "onTooFrequent: cooldown " + cooldownMs + "ms");
    }

    /** 受限原因文案（供UI显示） */
    public static String[] getRestrictionHint() {
        Settings s = Settings.getInstance();
        String reason = s.getLoginRestrictionReason();
        if (reason == null || reason.isEmpty()) reason = "服务器拒绝了本次登录";
        long remain = getRemainingCooldownMs();
        String wait;
        if (remain <= 0) wait = "限制已解除，可点「重试」重新登录";
        else wait = "还需等待约 " + ((remain + 59999) / 60000) + " 分钟（剩余 " + (remain / 1000) + " 秒）";
        return new String[] { reason, wait };
    }

    public static boolean isRestricted() {
        return inCooldown();
    }

    /** 冷启动清受限文案，但保留cooldown时间戳（跨重启生效） */
    public static void clearRestrictionText() {
        Settings.getInstance().setLoginRestrictionReason("");
    }

    public static void dumpState(String where) {
        Settings s = Settings.getInstance();
        long now = System.currentTimeMillis();
        long last = s.getLastLoginTime();
        long lock = s.getAutoLoginLockedUntil();
        long cd = s.getLoginCooldownUntil();
        Log.d(TAG_DBG, "STATE[" + where + "] state=" + getState()
            + " lastLoginAgo=" + (last > 0 ? ((now - last) / 1000) + "s" : "never")
            + " lockRemain=" + (lock > now ? ((lock - now) / 1000) + "s" : "none")
            + " cooldownRemain=" + (cd > now ? ((cd - now) / 1000) + "s" : "none")
            + " restriction='" + s.getLoginRestrictionReason() + "'"
            + " cookieUser='" + SilentLoginHelper.readUserId() + "'");
    }
}
