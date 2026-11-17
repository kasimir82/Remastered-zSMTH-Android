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

  /** 正常两次自动登录之间的最小间隔：6 小时。cookie 一般能活过这个时间。 */
  public static final long NORMAL_INTERVAL_MS = 6L * 60 * 60 * 1000;
  /** 被判频繁登录后的冷却：12 小时 */
  public static final long PENALTY_COOLDOWN_MS = 12L * 60 * 60 * 1000;

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

  /** 登录成功：记录时间并清除冷却 */
  public static void onLoginSuccess() {
    Settings s = Settings.getInstance();
    s.setLastLoginTime(System.currentTimeMillis());
    if (s.getLoginCooldownUntil() != 0) {
      s.setLoginCooldownUntil(0);
    }
    Log.i(TAG, "onLoginSuccess: recorded login time");
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
    if (msg == null) {
      return false;
    }
    return msg.contains("频繁")
        || msg.contains("账号过多")
        || msg.contains("帐号过多")
        || msg.contains("重复登录")
        || msg.contains("踢掉");
  }

  /** 被判频繁登录：进入冷却，停止自动登录 */
  public static void onTooFrequent(String reason) {
    Settings s = Settings.getInstance();
    long until = System.currentTimeMillis() + PENALTY_COOLDOWN_MS;
    s.setLoginCooldownUntil(until);
    Log.w(TAG, "onTooFrequent: " + reason + ", cooldown until " + until);
  }

  /** WebView 登录页出现"账号过多/踢掉多余窗口"弹窗时调用 */
  public static boolean isTooFrequentDialog(String message) {
    if (message == null) {
      return false;
    }
    return message.contains("账号过多")
        || message.contains("帐号过多")
        || message.contains("重复登录")
        || message.contains("踢掉")
        || message.contains("频繁");
  }
}
