package com.zfdang.zsmth_android.helpers;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

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
    if (LoginStateManager.isUserLoggedOut()) {
      android.util.Log.d(LoginThrottle.TAG_DBG, "LoginRecovery[" + where + "] USER_LOGGED_OUT -> direct manual login dialog");
      showLoginDialog(activity, "登录已过期，请重新登录");
      return;
    }

    // 统一走 LoginManager.autoLogin： Toast / 判断 / 全局态同步都在那里，
    // 这里只保留"成功后刷新当前页面"的业务回调(点帖子/版面专属)。
    LoginManager.autoLogin(where, userId -> {
      if (activity.isFinishing() || activity.isDestroyed()) return;
      if (onReload != null) onReload.run();
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
    // 用户主动点"去登录"→强制真登录，绕过WebviewLoginActivity的"cookie有用户名就短路"检查
    // (cookie被"拦guest"策略锁死时，不强制就会被短路成假成功，版面还是打不开)
    intent.putExtra("force_login", true);
    try {
      activity.startActivityForResult(intent, LOGIN_REQUEST_CODE);
    } catch (Throwable t) {
      Toast.makeText(activity.getApplicationContext(), "无法打开登录页: " + t.getMessage(), Toast.LENGTH_LONG).show();
    }
  }
}
