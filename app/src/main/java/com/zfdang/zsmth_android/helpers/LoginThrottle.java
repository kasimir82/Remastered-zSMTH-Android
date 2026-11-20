package com.zfdang.zsmth_android.helpers;

import android.util.Log;

import com.zfdang.zsmth_android.Settings;
import com.zfdang.zsmth_android.newsmth.AjaxResponse;

/**
 * 登录节流。
 *
 * 水木服务端对同一 IP 的频繁登录有限制：短时间内反复用账号密码登录会返回
 * "请勿频繁登录"(0105) 或弹出"您已登录的账号过多，无法重复登录"。
 * cookie 本身由 WebView CookieManager 持久化、有效期由登录时的 CookieDate 决定，
 * 所以只要 cookie 还在，就不该再登录一次。这里做两件事：
 *   1. 成功登录后记录时间，COOLDOWN 内不再自动登录；
 *   2. 一旦识别到"频繁登录/账号过多"，进入更长的 PENALTY 冷却并停止自动登录。
 */
public class LoginThrottle {
  private static final String TAG = "LoginThrottle";

  /**
   * 后台 Worker 两次自动登录之间的最小间隔：30 分钟。
   * 太长（如 6 小时）会让 cookie 失效后迟迟不重登；配合下面的"频繁登录"5 分钟冷却，
   * 30 分钟间隔不会触发水木的账号过多限制。
   */
  public static final long NORMAL_INTERVAL_MS = 30L * 60 * 1000;
  /** 被判频繁登录后的冷却：5 分钟 */
  public static final long PENALTY_COOLDOWN_MS = 5L * 60 * 1000;

  /**
   * 从服务端文案里提取等待分钟数（如「请 30 分钟后再试」→ 30 分钟），
   * 提取不到则用默认冷却时长。提取到的时长直接写进时间戳，保证显示与实际一致。
   */
  private static long cooldownMsFromReason(String reason) {
    if (reason != null) {
      java.util.regex.Matcher m = java.util.regex.Pattern
          .compile("(\\d+)\\s*(分钟|分|min|minutes?)").matcher(reason);
      if (m.find()) {
        try {
          int mins = Integer.parseInt(m.group(1));
          if (mins > 0) return mins * 60000L;
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return PENALTY_COOLDOWN_MS;
  }

  /** 受限弹窗文案（无服务端原文）：显示默认冷却时长 */
  public static String getCooldownMessage() {
    return getCooldownMessage(null);
  }

  /** 受限弹窗文案：显示本次冷却的总时长（从服务端文案提取或默认值） */
  public static String getCooldownMessage(String serverMessage) {
    long minutes = cooldownMsFromReason(serverMessage) / 60000L;
    return (serverMessage == null ? "由于此前登录过于频繁，水木暂时限制了登录" : serverMessage)
        + "。已暂停自动登录 " + minutes + " 分钟，请稍后再试。";
  }

  /** 当前是否处于水木的登录限制冷却期：完全基于时间戳，时间到自动解除 */
  public static boolean inCooldown() {
    return System.currentTimeMillis() < Settings.getInstance().getLoginCooldownUntil();
  }

  /** 冷却剩余毫秒数，<=0 表示已解除 */
  public static long getRemainingCooldownMs() {
    long remain = Settings.getInstance().getLoginCooldownUntil() - System.currentTimeMillis();
    return Math.max(0, remain);
  }

  /** 是否允许自动登录（Worker 用）。用户未在线意愿(登录失败后暂停)、冷却期内、或距上次成功登录太近，都不允许。 */
  public static boolean canAutoLogin() {
    Settings s = Settings.getInstance();
    if (!s.isUserOnline()) {
      Log.i(TAG, "canAutoLogin=false: user offline (auto login paused after failure)");
      return false;
    }
    if (inCooldown()) {
      Log.w(TAG, "canAutoLogin=false: in cooldown until " + s.getLoginCooldownUntil());
      return false;
    }
    long last = s.getLastLoginTime();
    if (last > 0 && System.currentTimeMillis() - last < NORMAL_INTERVAL_MS) {
      Log.i(TAG, "canAutoLogin=false: logged in recently, skip to preserve cookie");
      return false;
    }
    return true;
  }

  /**
   * cookie 已失效时的重登判断。与 canAutoLogin 不同：
   * 这里只受「冷却期」和「距上次真实登录至少 1 分钟」限制，
   * 不用 30 分钟节流——因为 cookie 失效说明必须重新登录了，
   * 不能被「最近登录过」挡住，否则用户会长时间卡在未登录状态。
   */
  public static boolean canRetryAfterCookieLost() {
    Settings s = Settings.getInstance();
    if (!s.isUserOnline()) {
      Log.i(TAG, "canRetryAfterCookieLost=false: user offline (auto login paused)");
      return false;
    }
    if (inCooldown()) {
      Log.w(TAG, "canRetryAfterCookieLost=false: in cooldown");
      return false;
    }
    long last = s.getLastLoginTime();
    if (last > 0 && System.currentTimeMillis() - last < 60L * 1000) {
      Log.i(TAG, "canRetryAfterCookieLost=false: logged in <1min ago");
      return false;
    }
    return true;
  }

  /** 登录成功：记录时间、清除冷却与受限状态，并恢复在线意愿（重新开启自动登录/掉线检测） */
  public static void onLoginSuccess() {
    Settings s = Settings.getInstance();
    s.setLastLoginTime(System.currentTimeMillis());
    s.setLastLoginSuccess(true);
    if (s.getLoginCooldownUntil() != 0) {
      s.setLoginCooldownUntil(0);
    }
    if (s.getLoginRestrictionReason() != null && !s.getLoginRestrictionReason().isEmpty()) {
      s.setLoginRestrictionReason("");
    }
    // 成功完成一次登录后，重新开启自动登录/掉线检测
    if (!s.isUserOnline()) {
      s.setUserOnline(true);
    }
    Log.i(TAG, "onLoginSuccess: recorded login time, restriction cleared, auto login resumed");
  }

  /**
   * 任何登录失败（密码错/账号过多/踢出/超时/验证码）都调用：
   * 完全退出登录状态（等价于用户点「注销」），并暂停自动登录/掉线检测，
   * 直到用户手动成功完成一次登录（onLoginSuccess）才恢复。
   *
   * 手动登录入口不受影响——用户随时可以再去登录页输密码。
   */
  public static void onAnyLoginFailure(String reason) {
    Settings s = Settings.getInstance();
    // 关闭在线意愿 → canAutoLogin/canRetryAfterCookieLost 立即返回 false
    if (s.isUserOnline()) s.setUserOnline(false);
    s.setLastLoginSuccess(false);
    s.setLastLoginTime(0);
    s.setLoginCooldownUntil(0);
    s.setLoginRestrictionReason("");

    // 清全局登录态
    com.zfdang.SMTHApplication.activeUser = null;
    com.zfdang.SMTHApplication.displayedUserId = "guest";

    // 停掉线检测/保活
    try {
      com.zfdang.zsmth_android.services.KeepAliveService.stop(
          com.zfdang.SMTHApplication.getAppContext());
    } catch (Throwable t) {
      Log.w(TAG, "stop keepalive failed: " + t.getMessage());
    }

    // 通知 UI 刷新为未登录态
    if (com.zfdang.SMTHApplication.mUserStatusReceiver != null) {
      try {
        com.zfdang.SMTHApplication.mUserStatusReceiver.onServiceFailed();
      } catch (Throwable t) {
        Log.w(TAG, "notify UI failed: " + t.getMessage());
      }
    }
    Log.w(TAG, "onAnyLoginFailure: " + reason + " -> logged out, auto login paused");
  }

  /**
   * 判断登录响应是否属于"登录太频繁/账号过多"。
   * 0105 = 请勿频繁登录；其它情况看文案里是否含关键词。
   */
  public static boolean isTooFrequent(AjaxResponse response) {
    if (response == null) {
      return false;
    }
    if ("0105".equals(response.getAjax_code())) {
      return true;
    }
    String msg = response.getAjax_msg();
    return matchesTooFrequentText(msg);
  }

  /**
   * 统一的「登录过多/频繁」文案识别。
   * 水木实际弹窗文案有多种：
   * - 「您已登录的账号过多，无法重复登录」
   * - 「你登录的窗口过多，是否踢出多余的窗口？」(确定/取消)
   * - 「请勿频繁登录」
   * 关键词必须覆盖「窗口过多」「踢出」，否则这类弹窗识别不到、会反复弹出。
   */
  private static boolean matchesTooFrequentText(String msg) {
    if (msg == null) {
      return false;
    }
    return msg.contains("频繁")
        || msg.contains("账号过多")
        || msg.contains("帐号过多")
        || msg.contains("重复登录")
        || msg.contains("踢掉")
        || msg.contains("踢出")
        || msg.contains("窗口过多")
        || msg.contains("登录过多")
        || msg.contains("登录的窗口");
  }

  /** 被判频繁登录：记录受限时间戳与原因，之后一律用「当前时间 vs 时间戳」判断 */
  public static void onTooFrequent(String reason) {
    Settings s = Settings.getInstance();
    long cooldownMs = cooldownMsFromReason(reason);
    long until = System.currentTimeMillis() + cooldownMs;
    s.setLoginCooldownUntil(until);
    s.setLoginRestrictionReason(reason == null ? "登录受限" : reason);
    Log.w(TAG, "onTooFrequent: " + reason + ", cooldown until " + until
        + " (" + cooldownMs + "ms)");
  }

  /** 当前是否处于受限态：只看时间戳，时间到自动解除，不受残留 reason 影响 */
  public static boolean isRestricted() {
    return inCooldown();
  }

  /**
   * 生成给「我的」页账号卡片展示的受限提示文案。
   * 等待时间一律按「时间戳 - 当前时间」动态计算，退到后台再回来会自动递减。
   * 返回两行：第一行原因，第二行剩余等待时间。
   */
  public static String[] getRestrictionHint() {
    Settings s = Settings.getInstance();
    String reason = s.getLoginRestrictionReason();
    if (reason == null || reason.isEmpty()) reason = "服务器拒绝了本次登录";

    long remainMs = getRemainingCooldownMs();
    String waitText;
    if (remainMs <= 0) {
      waitText = "限制已解除，可点右侧「重试」重新登录";
    } else {
      long remainMin = (remainMs + 59999) / 60000;
      // 不足1分钟时显示秒，让倒计时到末尾仍然准确
      if (remainMin >= 1) {
        waitText = "还需等待约 " + remainMin + " 分钟（剩余 " + (remainMs / 1000) + " 秒）";
      } else {
        waitText = "即将解除，剩余 " + (remainMs / 1000) + " 秒";
      }
    }
    return new String[] { reason, waitText };
  }

  /**
   * 静默登录失败时记录受限原因(把内部错误码映射成用户能看懂的中文)。
   * 不设冷却时长——只有服务端明确判定频繁登录(onTooFrequent)才进入长时间冷却；
   * 这里仅让「我的」页能显示「服务器未能登录：原因」。
   */
  public static void onLoginFailed(String reason) {
    if (reason == null) reason = "unknown";
    // 纯网络类失败（超时/连接错误）不是"受限"，不写 reason，避免误导。
    // 也不设时间戳——isRestricted 只看时间戳，这些失败本就不该显示受限卡片。
    if (reason.startsWith("timeout") || reason.startsWith("error:")) {
      Log.w(TAG, "onLoginFailed: network failure, ignore (not a restriction): " + reason);
      return;
    }
    String friendly;
    if (reason.startsWith("timeout")) {
      friendly = "登录超时，服务器响应过慢";
    } else if (reason.startsWith("error:")) {
      friendly = "登录出错：" + reason.substring(Math.min(reason.length(), 6));
    } else if ("captcha".equals(reason)) {
      friendly = "需要验证码，请手动登录";
    } else {
      friendly = "登录失败（" + reason + "）";
    }
    Settings.getInstance().setLoginRestrictionReason(friendly);
    Log.w(TAG, "onLoginFailed: " + reason + " -> " + friendly);
  }

  /** 登录成功后清除受限状态 */
  public static void clearRestriction() {
    Settings s = Settings.getInstance();
    s.setLoginRestrictionReason("");
    s.setLoginCooldownUntil(0);
  }

  /** WebView 登录页出现"账号过多/踢掉多余窗口/窗口过多"弹窗时调用 */
  public static boolean isTooFrequentDialog(String message) {
    return matchesTooFrequentText(message);
  }
}
