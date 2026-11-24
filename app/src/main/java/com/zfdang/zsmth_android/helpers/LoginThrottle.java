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
  /** TEMP DEBUG: 登录诊断统一tag，一次 adb logcat -s LOGINDBG 抓全部关键节点 */
  public static final String TAG_DBG = "LOGINDBG";

  /** TEMP DEBUG: 打印全部登录状态标志快照，供定位登录失效/重登被挡问题 */
  public static void dumpState(String where) {
    LoginStateManager.dumpState(where);
  }

  /**
   * 后台 Worker 两次自动登录之间的最小间隔：30 分钟。
   * 太长（如 6 小时）会让 cookie 失效后迟迟不重登；配合下面的"频繁登录"5 分钟冷却，
   * 30 分钟间隔不会触发水木的账号过多限制。
   */
  public static final long NORMAL_INTERVAL_MS = 30L * 60 * 1000;
  /** 被判频繁登录后的冷却：1 分钟。所有登录锁止统一不超过1分钟。 */
  public static final long PENALTY_COOLDOWN_MS = 1L * 60 * 1000;

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
          // 服务端说"请30分钟后再试"也最多只等1分钟：所有登录锁止统一上限1分钟，
          // 避免长时间卡住无法自动恢复(代价是可能更早重试撞限制，但用户明确要求)。
          if (mins > 0) return Math.min(mins, 1) * 60000L;
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
    return LoginStateManager.inCooldown();
  }

  /** 冷却剩余毫秒数，<=0 表示已解除 */
  public static long getRemainingCooldownMs() {
    return LoginStateManager.getRemainingCooldownMs();
  }

  /** 是否允许自动登录（Worker 用）。用户未在线意愿(登录失败后暂停)、冷却期内、或距上次成功登录太近，都不允许。 */
  public static boolean canAutoLogin() {
    return LoginStateManager.canAutoLogin();
  }

  /**
   * cookie 已失效时的重登判断。与 canAutoLogin 不同：
   * 这里只受「冷却期」和「距上次真实登录至少 1 分钟」限制，
   * 不用 30 分钟节流——因为 cookie 失效说明必须重新登录了，
   * 不能被「最近登录过」挡住，否则用户会长时间卡在未登录状态。
   */
  /** 静默登录成功后的锁定期：1分钟。期间禁止自动登录，只做cookie检查。
   *  从2分钟缩短到1分钟：所有登录锁止不超过1分钟，避免cookie失效后长时间卡住。
   *  cookie一旦真失效，onCookieLost会立即清掉此锁，不必等锁到期。*/
  private static final long SILENT_SUCCESS_LOCK_MS = 1L * 60 * 1000;

  /** 静默登录成功后调用：记录登录时间 + 锁闭自动登录1分钟，避免短时间内二次登录塞满服务端会话。 */
  public static void onSilentLoginSuccess(String userId) {
    LoginStateManager.onSilentLoginSuccess(userId);
  }

  /**
   * 检测到 cookie 失效时调用。
   * 关键：不清1分钟成功锁、不绕过60秒保护——cookie失效往往就是"窗口过多"被踢，
   * 此时立即重登只会再被踢，形成恶性循环(实测vivo会话只活20-27秒)。
   * 让锁自然到期间隔≥1分钟，打破循环。本方法只记录日志，不改任何状态。
   */
  public static void onCookieLost() {
    // 状态机下cookie失效不再直接改状态；状态由refreshState()验证后决定
    LoginStateManager.dumpState("onCookieLost");
  }

  /** 是否处于静默登录成功后的锁定期（自动登录入口用；cookie检查不受此影响） */
  public static boolean isAutoLoginLocked() {
    return LoginStateManager.isLocked();
  }

  public static boolean canRetryAfterCookieLost() {
    return LoginStateManager.canAutoLogin();
  }

  /** 登录成功：记录时间、清除冷却与受限状态，并恢复在线意愿（重新开启自动登录/掉线检测） */
  public static void onLoginSuccess() {
    LoginStateManager.onLoginSuccess(Settings.getInstance().getUsername());
  }

  /**
   * 后台确认「会话有效」（json 已登录 + board 验证通过）时调用：
   * 恢复在线标志与成功标志，清残留受限状态，但不刷新 lastLoginTime
   * （避免影响 30 分钟自动登录节流的节奏）。用于自愈之前误判遗留的 user_online=false。
   */
  public static void markSessionValid() {
    LoginStateManager.onSessionValid();
  }

  /**
   * 任何登录失败（密码错/账号过多/踢出/超时/验证码）都调用：
   * 完全退出登录状态（等价于用户点「注销」），并暂停自动登录/掉线检测，
   * 直到用户手动成功完成一次登录（onLoginSuccess）才恢复。
   *
   * 手动登录入口不受影响——用户随时可以再去登录页输密码。
   */
  public static void onAnyLoginFailure(String reason) {
    LoginStateManager.onLoginFailed(reason);
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
    LoginStateManager.onTooFrequent(reason);
  }

  /** 当前是否处于受限态：只看时间戳，时间到自动解除，不受残留 reason 影响 */
  public static boolean isRestricted() {
    return LoginStateManager.isRestricted();
  }

  /**
   * 生成给「我的」页账号卡片展示的受限提示文案。
   * 等待时间一律按「时间戳 - 当前时间」动态计算，退到后台再回来会自动递减。
   * 返回两行：第一行原因，第二行剩余等待时间。
   */
  public static String[] getRestrictionHint() {
    return LoginStateManager.getRestrictionHint();
  }

  /**
   * 静默登录失败时记录受限原因(把内部错误码映射成用户能看懂的中文)。
   * 不设冷却时长——只有服务端明确判定频繁登录(onTooFrequent)才进入长时间冷却；
   * 这里仅让「我的」页能显示「服务器未能登录：原因」。
   */
  public static void onLoginFailed(String reason) {
    LoginStateManager.onLoginFailed(reason);
    // 网络类失败不写受限文案；其它写友好文案（保留原UI行为）
    if (reason == null || (!reason.startsWith("timeout") && !reason.startsWith("error:"))) {
      String friendly = "captcha".equals(reason) ? "需要验证码，请手动登录"
          : (reason == null ? "登录失败" : "登录失败（" + reason + "）");
      Settings.getInstance().setLoginRestrictionReason(friendly);
    }
  }

  /**
   * app冷启动时清理「记住的受限文案」。
   * 关键：不清 cooldownUntil 时间戳——那是服务端"窗口过多/请N分钟后再试"的强制冷却，
   * 存在 SharedPreferences 里本就该跨重启生效。之前冷启动把它清0，导致重启后立刻
   * 绕开冷却去登录、又撞窗口过多，形成"反复弹正在重新登录"的死循环(实测小米)。
   */
  public static void clearRestriction() {
    LoginStateManager.clearRestrictionText();
  }

  /** WebView 登录页出现"账号过多/踢掉多余窗口/窗口过多"弹窗时调用 */
  public static boolean isTooFrequentDialog(String message) {
    return matchesTooFrequentText(message);
  }
}
