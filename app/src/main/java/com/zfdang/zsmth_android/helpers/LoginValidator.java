package com.zfdang.zsmth_android.helpers;

import android.util.Log;

import com.zfdang.zsmth_android.newsmth.SMTHHelper;

/**
 * 登录态验证：全 App 唯一验证入口。
 *
 * 判定"真的登录了"必须同时满足两个条件：
 *  1. WebView cookie 里有有效用户名(非 guest)——readUserId()
 *  2. 实际访问版面成功(服务端会话真的建立)——SMTHHelper.isSessionValidByBoard()
 *
 * 只验第1条会"假成功"：水木窗口过多时 cookie 会被短暂写入用户名，
 * 但会话没建立，紧接着就被踢出来。所有自动登录的成功判定都必须走这里。
 */
public final class LoginValidator {
  private LoginValidator() {}

  /** 后台线程池执行验证(isSessionValidByBoard 是同步阻塞的 HTTP 调用) */
  private static final java.util.concurrent.ExecutorService EXECUTOR =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  /** 验证结果回调，运行在主线程 */
  public interface Callback {
    /** @param userId 验证通过的用户ID；null 表示登录态无效 */
    void onResult(String userId);
  }

  /**
   * 验证当前登录态（同步阻塞版本，调用方必须在后台线程调用）。
   * @return 有效的 userId；null = cookie 无效 或 版面验证失败
   */
  public static String verifySync() {
    String userId = SilentLoginHelper.readUserId();
    if (userId == null || userId.isEmpty() || "guest".equalsIgnoreCase(userId)) {
      Log.d(LoginThrottle.TAG_DBG, "LoginVerify: cookie invalid (userId=" + userId + ")");
      return null;
    }
    boolean boardValid = SMTHHelper.isSessionValidByBoard();
    Log.d(LoginThrottle.TAG_DBG, "LoginVerify: userId=" + userId + " boardValid=" + boardValid);
    if (!boardValid) {
      // cookie有用户名但版面验证失败 = 假会话(水木窗口过多时会假写入cookie)，
      // 统一清掉假cookie，避免后续所有判定都被它误导成"已登录"。
      clearFakeCookie();
      return null;
    }
    return userId;
  }

  /** 清掉假的 UTMPUSERID cookie(设为guest)，让下次验证不再被假cookie骗 */
  private static void clearFakeCookie() {
    try {
      String setCookieValue = "main[UTMPUSERID]=guest; path=/";
      android.webkit.CookieManager.getInstance().setCookie(
          SilentLoginHelper.COOKIE_URL, setCookieValue);
      Log.w("CookieJar", "COOKIE-WRITE[APP] writer=LoginValidator.clearFakeCookie"
          + " value=" + setCookieValue + " url=" + SilentLoginHelper.COOKIE_URL
          + " reason=board verify failed (fake session)");
    } catch (Throwable t) {
      Log.w(LoginThrottle.TAG_DBG, "clearFakeCookie failed", t);
    }
  }

  /**
   * 异步验证（后台线程执行，结果 post 回主线程）。
   * 所有自动登录的成功判定统一用这个，保证 cookie + 版面都验证过。
   */
  public static void verify(final Callback callback) {
    EXECUTOR.execute(() -> {
      final String userId = verifySync();
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onResult(userId));
    });
  }
}
