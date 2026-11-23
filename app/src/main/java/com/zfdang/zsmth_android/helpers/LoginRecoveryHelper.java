package com.zfdang.zsmth_android.helpers;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.Settings;
import com.zfdang.zsmth_android.WebviewLoginActivity;

/**
 * 「服务端返回未登录/需阅读验证」的统一恢复入口。
 * 点帖子(PostListActivity)与点版面(BoardTopicActivity)都走这里，避免两条路径行为不一致。
 *
 * 方案C：
 *  - user_online=false(之前登录失败已停自动登录)：直接弹手动登录框，不再做注定被挡的静默登录
 *  - user_online=true：先后台静默重登，成功则调用 onReload 无感恢复；失败/需验证码才转手动登录
 */
public final class LoginRecoveryHelper {
  private LoginRecoveryHelper() {}

  private static long sLastAttempt = 0;
  private static final long DEBOUNCE_MS = 30 * 1000;
  public static final int LOGIN_REQUEST_CODE = 9101;

  /** 登录成功后由各 Activity 的 onActivityResult 调用，转发到这里统一刷新由调用方自行处理 */
  public static boolean isLoginResult(int requestCode) {
    return requestCode == LOGIN_REQUEST_CODE;
  }

  /**
   * @param activity   触发的 Activity（用于弹框/跳转/生命周期判断）
   * @param where      日志标签，区分来源(post/board)
   * @param onReload   静默登录成功后刷新页面的回调（会在 UI 线程执行）
   */
  public static void handleGuestError(final Activity activity, final String where, final Runnable onReload) {
    LoginThrottle.dumpState("LoginRecovery[" + where + "]-enter");
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
      android.util.Log.w(LoginThrottle.TAG_DBG, "LoginRecovery[" + where + "] skipped: activity gone");
      return;
    }
    LoginThrottle.onCookieLost(); // cookie失效：清静默成功锁

    // 防抖：多个请求同时失败时 30 秒内只走一次
    long now = System.currentTimeMillis();
    if (now - sLastAttempt < DEBOUNCE_MS) {
      android.util.Log.d(LoginThrottle.TAG_DBG, "LoginRecovery[" + where + "] SKIP debounce " + (now - sLastAttempt) + "ms");
      return;
    }
    sLastAttempt = now;

    // 方案C核心：已停自动登录 → 静默登录必被挡，直接弹手动登录框
    if (!Settings.getInstance().isUserOnline()) {
      android.util.Log.d(LoginThrottle.TAG_DBG, "LoginRecovery[" + where + "] user_online=false -> direct manual login dialog");
      showLoginDialog(activity, "登录已过期，请重新登录");
      return;
    }

    // user_online=true → 先静默重登
    android.util.Log.d(LoginThrottle.TAG_DBG, "LoginRecovery[" + where + "] user_online=true -> try silent login");
    Toast.makeText(activity.getApplicationContext(), "正在重新登录...", Toast.LENGTH_SHORT).show();
    SilentLoginHelper.getInstance().login(new SilentLoginHelper.Callback() {
      @Override public void onSuccess(String userId) {
        activity.runOnUiThread(() -> {
          android.util.Log.d(LoginThrottle.TAG_DBG, "LoginRecovery[" + where + "] silent SUCCESS " + userId);
          if (!activity.isFinishing() && !activity.isDestroyed()) {
            Toast.makeText(activity.getApplicationContext(), "重新登录成功", Toast.LENGTH_SHORT).show();
          }
          // 同步全局登录态
          SMTHApplication.displayedUserId = userId;
          if (SMTHApplication.activeUser == null) {
            SMTHApplication.activeUser = new com.zfdang.zsmth_android.newsmth.UserStatus();
          }
          SMTHApplication.activeUser.setId(userId);
          SMTHApplication.activeUser.setIs_login(true);
          if (onReload != null) onReload.run();
        });
      }
      @Override public void onCaptchaRequired() {
        activity.runOnUiThread(() -> {
          android.util.Log.d(LoginThrottle.TAG_DBG, "LoginRecovery[" + where + "] captcha required");
          LoginThrottle.onAnyLoginFailure("captcha");
          launchLoginPage(activity);
        });
      }
      @Override public void onFailed(String reason) {
        activity.runOnUiThread(() -> {
          android.util.Log.e(LoginThrottle.TAG_DBG, "LoginRecovery[" + where + "] onFailed " + reason);
          if ("cooldown".equals(reason) || "no_credentials".equals(reason)) {
            showLoginDialog(activity, "登录已过期，请重新登录");
            return;
          }
          LoginThrottle.onAnyLoginFailure(reason);
          showLoginDialog(activity, "登录已失效，请手动登录后重试");
        });
      }
    });
  }

  private static void showLoginDialog(Activity activity, String message) {
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
    try {
      new androidx.appcompat.app.AlertDialog.Builder(activity)
          .setTitle("需要登录")
          .setMessage(message)
          .setPositiveButton("去登录", (dialog, which) -> launchLoginPage(activity))
          .setNegativeButton("取消", null)
          .show();
    } catch (android.view.WindowManager.BadTokenException e) {
      android.util.Log.w(LoginThrottle.TAG_DBG, "showLoginDialog BadToken ignored", e);
    }
  }

  private static void launchLoginPage(Activity activity) {
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
    String username = Settings.getInstance().getUsername();
    String password = Settings.getInstance().getPassword();
    Intent intent = new Intent(activity, WebviewLoginActivity.class);
    // LoginActivity.USERNAME/PASSWORD 是包级私有(helpers子包访问不到)，用相同字面量
    intent.putExtra("USERNAME", username);
    intent.putExtra("PASSWORD", password);
    try {
      activity.startActivityForResult(intent, LOGIN_REQUEST_CODE);
    } catch (Throwable t) {
      Toast.makeText(activity.getApplicationContext(), "无法打开登录页: " + t.getMessage(), Toast.LENGTH_LONG).show();
    }
  }
}
