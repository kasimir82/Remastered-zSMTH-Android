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
   * 太长（如 6 小时）会让 cookie 失效后迟迟不重登；配合下面的"频繁登录"12 小时冷却，
   * 30 分钟间隔不会触发水木的账号过多限制。
   */
  public static final long NORMAL_INTERVAL_MS = 30L * 60 * 1000;
  /** 被判频繁登录后的冷却：5 分钟 */
  public static final long PENALTY_COOLDOWN_MS = 5L * 60 * 1000;

  /** 当前是否处于水木的登录限制冷却期 */
  public static boolean inCooldown() {
    return System.currentTimeMillis() < Settings.getInstance().getLoginCooldownUntil();
  }

  /** 是否允许自动登录（Worker 用）。冷却期内、或距上次成功登录太近，都不允许。 */
  public static boolean canAutoLogin() {
    Settings s = Settings.getInstance();
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

  /** 登录成功：记录时间并清除冷却与受限状态 */
  public static void onLoginSuccess() {
    Settings s = Settings.getInstance();
    s.setLastLoginTime(System.currentTimeMillis());
    if (s.getLoginCooldownUntil() != 0) {
      s.setLoginCooldownUntil(0);
    }
    if (s.getLoginRestrictionReason() != null && !s.getLoginRestrictionReason().isEmpty()) {
      s.setLoginRestrictionReason("");
    }
    Log.i(TAG, "onLoginSuccess: recorded login time, restriction cleared");
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

  /** 被判频繁登录：进入冷却，停止自动登录，并记录受限原因供 UI 展示 */
  public static void onTooFrequent(String reason) {
    Settings s = Settings.getInstance();
    long until = System.currentTimeMillis() + PENALTY_COOLDOWN_MS;
    s.setLoginCooldownUntil(until);
    // 记录服务端原文作为受限原因；UI 会据此展示「问题是：XXX」
    s.setLoginRestrictionReason(reason == null ? "登录受限" : reason);
    Log.w(TAG, "onTooFrequent: " + reason + ", cooldown until " + until);
  }

  /** 当前是否处于受限态（冷却中 或 有未清除的受限原因），供「我的」页展示 */
  public static boolean isRestricted() {
    Settings s = Settings.getInstance();
    if (inCooldown()) return true;
    String reason = s.getLoginRestrictionReason();
    return reason != null && !reason.isEmpty();
  }

  /**
   * 生成给「我的」页账号卡片展示的受限提示文案。
   * 优先用服务端原文里的等待时间(如「请 5 分钟后再试」)，提取不到则显示通用建议。
   * 返回两行：第一行原因，第二行建议等待时间。
   */
  public static String[] getRestrictionHint() {
    Settings s = Settings.getInstance();
    String reason = s.getLoginRestrictionReason();
    if (reason == null || reason.isEmpty()) reason = "服务器拒绝了本次登录";

    // 从服务端文案里提取「X 分钟/分/X min」；没有则用冷却剩余时间估算
    String waitText = null;
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("(\\d+)\\s*(分钟|分|min|minutes?)").matcher(reason);
    if (m.find()) {
      try {
        int mins = Integer.parseInt(m.group(1));
        if (mins > 0) waitText = "建议等待 " + mins + " 分钟后再试";
      } catch (NumberFormatException ignored) {
      }
    }
    if (waitText == null) {
      // 服务端文案没给具体时间：
      // - 窗口过多/频繁登录这类是临时限制，给一个务实的短建议(5分钟)，
      //   惩罚性冷却本身也已设为 5 分钟
      // - 其它失败(超时等)用冷却剩余时间兜底
      if (matchesTooFrequentText(reason)) {
        waitText = "建议等待约 5 分钟后再试";
      } else {
        long remainMs = s.getLoginCooldownUntil() - System.currentTimeMillis();
        if (remainMs > 0) {
          long remainMin = (remainMs + 59999) / 60000;
          waitText = "建议等待约 " + remainMin + " 分钟后再试";
        } else {
          waitText = "建议稍后重试，或点右侧「重试」手动登录";
        }
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
