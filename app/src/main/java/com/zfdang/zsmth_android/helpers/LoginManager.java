package com.zfdang.zsmth_android.helpers;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.newsmth.UserStatus;

/**
 * 登录统一门面（自动登录唯一入口）。
 *
 * 自动登录的两类触发源——后台 Worker 定时检查、前台点帖子/版面收到未登录——
 * 都走 autoLogin()，不再各自实现判断/Toast/全局态同步，避免逻辑重复和行为不一致。
 *
 * 手动登录（WebviewLoginActivity）暂不在此，仍由各入口自行拉起。
 */
public final class LoginManager {
  private LoginManager() {}

  /** 自动登录回调：成功时调用方刷新页面（如帖子列表、版面列表） */
  public interface AutoLoginCallback {
    void onSuccess(String userId);
  }

  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

  private static void toast(String text) {
    MAIN_HANDLER.post(() -> Toast.makeText(SMTHApplication.getAppContext(), text, Toast.LENGTH_SHORT).show());
  }

  /**
   * 自动登录唯一入口。内部统一：cookie失效清锁 → 资格判断 → Toast提示 →
   * 调 SilentLoginHelper → 成功同步全局登录态+Toast、失败Toast。
   *
   * @param reason   日志标签，区分来源(worker/post/board)
   * @param callback 成功回调(可为null)；运行在主线程，调用方在此刷新页面
   */
  public static void autoLogin(final String reason, final AutoLoginCallback callback) {
    LoginThrottle.dumpState("autoLogin[" + reason + "]-enter");

    // cookie失效：清静默成功锁，允许立即重登
    LoginThrottle.onCookieLost();

    // 资格判断：user_online / 锁 / 冷却 / 60秒保护
    if (!LoginThrottle.canRetryAfterCookieLost()) {
      android.util.Log.d(LoginThrottle.TAG_DBG, "autoLogin[" + reason + "] SKIP by canRetryAfterCookieLost");
      return;
    }

    android.util.Log.d(LoginThrottle.TAG_DBG, "autoLogin[" + reason + "] start silent login");
    toast("正在重新登录...");

    SilentLoginHelper.getInstance().login(new SilentLoginHelper.Callback() {
      @Override public void onSuccess(String userId) {
        android.util.Log.d(LoginThrottle.TAG_DBG, "autoLogin[" + reason + "] SUCCESS " + userId);
        MAIN_HANDLER.post(() -> {
          // 同步全局登录态
          SMTHApplication.displayedUserId = userId;
          if (SMTHApplication.activeUser == null) {
            SMTHApplication.activeUser = new UserStatus();
          }
          SMTHApplication.activeUser.setId(userId);
          SMTHApplication.activeUser.setIs_login(true);
          toast("登录成功");
          if (callback != null) callback.onSuccess(userId);
        });
      }
      @Override public void onCaptchaRequired() {
        android.util.Log.d(LoginThrottle.TAG_DBG, "autoLogin[" + reason + "] captcha required");
        LoginThrottle.onAnyLoginFailure("captcha");
        toast("登录需要验证码，请手动登录");
      }
      @Override public void onFailed(String reason2) {
        android.util.Log.e(LoginThrottle.TAG_DBG, "autoLogin[" + reason + "] onFailed " + reason2);
        // 只要弹过"正在重新登录"，任何结果都必须给提示，不能让用户悬着。
        // cooldown(窗口过多/频繁)、no_credentials(没存账号)过去被当"预期跳过"静默处理，
        // 但它们其实是真实失败，需要明确告知用户。
        if ("cooldown".equals(reason2)) {
          // 窗口过多=服务端限流：不改登录态(冷却已由onTooFrequent设置)，但要提示结果
          toast("登录受限（窗口过多），请稍后再试");
          return;
        }
        if ("no_credentials".equals(reason2)) {
          toast("未保存账号，请手动登录");
          return;
        }
        LoginThrottle.onAnyLoginFailure(reason2);
        toast("登录失败，请手动登录");
      }
    });
  }
}
