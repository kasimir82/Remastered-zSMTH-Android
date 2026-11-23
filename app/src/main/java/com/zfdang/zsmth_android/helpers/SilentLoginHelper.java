package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.Settings;

import org.json.JSONObject;

/**
 * 后台静默登录：用离屏 WebView 加载桌面版登录页、自动填账号密码提交，
 * 全程不显示界面。水木登录页本身无验证码，绝大多数情况可无感完成。
 *
 * 结果通过 {@link Callback} 回传：
 * - success：cookie 里 UTMPUSERID 变成有效用户
 * - captchaRequired：页面出现验证码（需要用户交互，交回页面层弹可见 WebView）
 * - failed：其它失败（密码错/网络/超时），静默结束不打扰用户
 *
 * WebView 必须在主线程创建与销毁；单例串行执行，避免并发多个登录。
 */
public class SilentLoginHelper {
  private static final String TAG = "SilentLogin";
  private static final String LOGIN_URL = "https://www.newsmth.net/index.html";
  private static final String COOKIE_URL = "https://www.newsmth.net/";
  private static final String COOKIE_USER_KEY = "main[UTMPUSERID]=";
  private static final int POLL_INTERVAL_MS = 1000;
  private static final int MAX_POLL = 20; // 最多等 20 秒

  public interface Callback {
    void onSuccess(String userId);
    void onCaptchaRequired();
    void onFailed(String reason);
  }

  private static SilentLoginHelper instance;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private boolean running = false;

  public static synchronized SilentLoginHelper getInstance() {
    if (instance == null) instance = new SilentLoginHelper();
    return instance;
  }

  /** 在后台静默登录。调用方可在任意线程调用，内部切主线程。 */
  public void login(final Callback callback) {
    mainHandler.post(() -> doLogin(callback));
  }

  private void doLogin(final Callback callback) {
    LoginThrottle.dumpState("SilentLogin.doLogin-enter");
    android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin.doLogin thread=" + Thread.currentThread().getName());
    if (running) {
      android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin SKIP: running=true");
      Log.d(TAG, "login already running, skip");
      return;
    }
    String username = Settings.getInstance().getUsername();
    String password = Settings.getInstance().getPassword();
    if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
      Log.w(TAG, "no saved credentials, skip silent login");
      android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin SKIP: no saved credentials");
      LoginFailureLogger.log("no_credentials", "saved username/password missing");
      callback.onFailed("no_credentials");
      return;
    }
    // 已经有有效登录态，无需登录
    String current = readUserId();
    if (current != null && !current.isEmpty() && !"guest".equalsIgnoreCase(current)) {
      Log.i(TAG, "already logged in: " + current);
      android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin SHORTCUT: cookie says logged in='" + current + "' -> onSuccess WITHOUT real login (潜在bug点:cookie假有效)");
      callback.onSuccess(current);
      return;
    }
    // 冷却期内不提交，避免触发水木限制
    if (LoginThrottle.inCooldown()) {
      Log.w(TAG, "in cooldown, skip silent login");
      android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin SKIP: in cooldown");
      LoginFailureLogger.log("cooldown", "silent login skipped, in penalty cooldown");
      callback.onFailed("cooldown");
      return;
    }

    running = true;
    final Context appContext = SMTHApplication.getAppContext();
    final WebView[] holder = new WebView[1];
    final int[] pollCount = {0};
    final boolean[] done = {false};
    final Runnable[] pollRunnable = new Runnable[1];

    try {
      WebView webView = new WebView(appContext);
      holder[0] = webView;
      WebSettings settings = webView.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      settings.setDatabaseEnabled(true);
      settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
      settings.setSavePassword(false);
      settings.setSaveFormData(false);
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
      }
      String ua = settings.getUserAgentString();
      if (ua == null || !ua.contains("Mobile")) {
        settings.setUserAgentString((ua == null ? "" : ua) + " Mobile-SMTH-App");
      }

      webView.setWebViewClient(new WebViewClient() {
        @Override public void onPageFinished(WebView view, String url) {
          super.onPageFinished(view, url);
          if (done[0]) return;
          Log.d(TAG, "onPageFinished: " + url);
          // 页面加载完后注入账号密码并提交
          injectCredentials(view, username, password);
          // 检测是否出现验证码页面
          detectCaptcha(view, () -> {
            if (done[0]) return;
            Log.w(TAG, "captcha required");
            android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin CAPTCHA required");
            LoginFailureLogger.log("captcha", "captcha detected on login page");
            finish(holder, callback, done, false);
            callback.onCaptchaRequired();
          });
          pollRunnable[0].run();
        }

        @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
          super.onReceivedError(view, errorCode, description, failingUrl);
          String detail = "code=" + errorCode + " desc=" + description + " url=" + failingUrl;
          Log.e(TAG, "WebView onReceivedError: " + detail);
          LoginFailureLogger.log("webview_error", detail);
        }

        @Override public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request,
            android.webkit.WebResourceResponse errorResponse) {
          super.onReceivedHttpError(view, request, errorResponse);
          if (errorResponse != null) {
            String detail = "status=" + errorResponse.getStatusCode()
                + " reason=" + errorResponse.getReasonPhrase()
                + " url=" + (request == null ? "null" : request.getUrl());
            Log.e(TAG, "WebView HTTP error: " + detail);
            LoginFailureLogger.log("http_error", detail);
          }
        }
      });

      // 静默登录的离屏 WebView 之前没设 WebChromeClient，JS 弹窗(如「登录太多窗口/账号过多」)
      // 被 WebView 默认吞掉，只能看到 timeout，无法知道真实原因。这里拦截并记录所有弹窗。
      webView.setWebChromeClient(new WebChromeClient() {
        @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
          Log.w(TAG, "JS alert: " + message + " (url=" + url + ")");
          android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin JS_ALERT: " + message);
          LoginFailureLogger.log("js_alert", message + " (url=" + url + ")");
          if (LoginThrottle.isTooFrequentDialog(message)) {
            // 窗口过多：立即终止本次静默登录，不再让页面反复弹窗(实测cancel后服务端每0.5s重弹7次，
            // 白等20秒超时且可能加剧服务端限制)。不写本地受限状态、不弹可见页、不点确定(避免踢掉其它设备)。
            LoginFailureLogger.log("too_frequent_silent", message);
            android.util.Log.e(LoginThrottle.TAG_DBG, "SilentLogin ABORT immediately: too frequent windows, cooldown set");
            // 窗口过多=服务端限流：设5分钟冷却(由服务端文案提取)，阻止短时间内再次提交登录。
            // reason 用 "cooldown" 让调用方跳过 onAnyLoginFailure(不误判为掉线登出)。
            LoginThrottle.onTooFrequent(message);
            result.cancel();
            finish(holder, callback, done, false);
            callback.onFailed("cooldown");
            return true;
          } else {
            result.confirm(); // 普通提示：自动确认，避免静默流程卡住
          }
          return true;
        }

        @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
          Log.w(TAG, "JS confirm: " + message + " (url=" + url + ")");
          android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin JS_CONFIRM: " + message);
          LoginFailureLogger.log("js_confirm", message + " (url=" + url + ")");
          if (LoginThrottle.isTooFrequentDialog(message)) {
            LoginFailureLogger.log("too_frequent_silent", message);
            android.util.Log.e(LoginThrottle.TAG_DBG, "SilentLogin ABORT immediately: too frequent windows (confirm), cooldown set");
            LoginThrottle.onTooFrequent(message);
            result.cancel();
            finish(holder, callback, done, false);
            callback.onFailed("cooldown");
            return true;
          }
          // 其它确认：静默模式下取消
          result.cancel();
          return true;
        }

        @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
            android.webkit.JsPromptResult result) {
          Log.w(TAG, "JS prompt: " + message + " default=" + defaultValue + " (url=" + url + ")");
          LoginFailureLogger.log("js_prompt", message + " default=" + defaultValue);
          result.cancel();
          return true;
        }
      });

      pollRunnable[0] = new Runnable() {
        @Override public void run() {
          if (done[0]) return;
          String userId = readUserId();
          // 每次 poll 都记录读到的登录 ID（guest/null/真实用户名），便于定位为何判定未登录
          Log.d(TAG, "poll #" + pollCount[0] + " userId=" + (userId == null ? "null" : "'" + userId + "'"));
          if (userId != null && !userId.isEmpty() && !"guest".equalsIgnoreCase(userId)) {
            Log.i(TAG, "silent login success: " + userId);
            android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin SUCCESS userId=" + userId + " poll#" + pollCount[0]);
            // 静默登录成功 → 上锁2分钟，阻止两条路径(Worker周期/点帖子)短时间内重复登录塞满服务端会话
            LoginThrottle.onSilentLoginSuccess(userId);
            finish(holder, callback, done, true);
            callback.onSuccess(userId);
            return;
          }
          if (++pollCount[0] > MAX_POLL) {
            String detail = "timeout after " + MAX_POLL + "s, last userId="
                + (userId == null ? "null" : "'" + userId + "'");
            Log.w(TAG, "silent login " + detail + ", fallback to user interaction");
            android.util.Log.d(LoginThrottle.TAG_DBG, "SilentLogin TIMEOUT: " + detail);
            LoginFailureLogger.log("timeout", detail);
            finish(holder, callback, done, false);
            callback.onFailed("timeout");
            return;
          }
          mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
      };

      webView.loadUrl(LOGIN_URL);
    } catch (Throwable t) {
      Log.e(TAG, "silent login error", t);
      LoginFailureLogger.log("exception", "silent login error: " + t);
      running = false;
      destroyWebView(holder[0]);
      callback.onFailed("error: " + t.getMessage());
    }
  }

  /** 结束：停轮询、销毁 WebView、复位运行状态 */
  private void finish(WebView[] holder, Callback callback, boolean[] done, boolean success) {
    if (done[0]) return;
    done[0] = true;
    running = false;
    mainHandler.removeCallbacksAndMessages(null);
    destroyWebView(holder[0]);
  }

  private void destroyWebView(WebView webView) {
    if (webView == null) return;
    try {
      webView.stopLoading();
      webView.setWebViewClient(null);
      webView.destroy();
    } catch (Throwable t) {
      Log.w(TAG, "destroy webview failed", t);
    }
  }

  /** 读取 cookie 中的登录用户 ID */
  public static String readUserId() {
    try {
      String cookies = CookieManager.getInstance().getCookie(COOKIE_URL);
      if (cookies == null) {
        android.util.Log.d(LoginThrottle.TAG_DBG, "readUserId: cookies=NULL");
        return null;
      }
      for (String part : cookies.split(";")) {
        String p = part.trim();
        if (p.startsWith(COOKIE_USER_KEY)) {
          String uid = p.substring(COOKIE_USER_KEY.length());
          android.util.Log.d(LoginThrottle.TAG_DBG, "readUserId=" + uid + " (from local WebView cookie)");
          return uid;
        }
      }
      android.util.Log.d(LoginThrottle.TAG_DBG, "readUserId: no UTMPUSERID in cookies (len=" + cookies.length() + ")");
    } catch (Throwable t) {
      Log.e(TAG, "readUserId failed", t);
    }
    return null;
  }

  /** 注入账号密码并提交表单（与可见登录页一致的桌面版链路） */
  private void injectCredentials(WebView view, String username, String password) {
    final String js = "javascript:(function(){"
        + "var user=" + JSONObject.quote(username) + ";"
        + "var pass=" + JSONObject.quote(password) + ";"
        + "var filled=false,ticks=0;"
        + "var timer=setInterval(function(){"
        + "  ticks++;"
        + "  var d=null;"
        + "  try{ if(window.frames&&window.frames['mainFrame']&&window.frames['mainFrame'].document){d=window.frames['mainFrame'].document;} }catch(e){}"
        + "  if(!d){d=document;}"
        + "  var ids=[],pws=[];"
        + "  try{ ids=d.getElementsByName('id'); pws=d.getElementsByName('passwd'); }catch(e){}"
        + "  if(!filled&&ids.length>0&&pws.length>0){"
        + "    ids[0].value=user; pws[0].value=pass;"
        + "    var f=null;"
        + "    try{ f=d.getElementById('form1'); }catch(e){}"
        + "    if(!f){ f=ids[0].form; }"
        + "    if(f){ f.submit(); filled=true; }"
        + "  }"
        + "  if(filled||ticks>40){ clearInterval(timer); }"
        + "},300);"
        + "})()";
    try {
      view.evaluateJavascript(js, null);
    } catch (Throwable t) {
      Log.e(TAG, "inject credentials failed", t);
    }
  }

  /**
   * 检测页面是否出现验证码。水木风控时登录页可能要求输入验证码，
   * 此时离屏 WebView 过不去，需要回调让页面层弹可见页面给用户。
   */
  private void detectCaptcha(WebView view, final Runnable onCaptcha) {
    final String js = "(function(){"
        + "var d=document;"
        + "try{ if(window.frames&&window.frames['mainFrame']&&window.frames['mainFrame'].document){d=window.frames['mainFrame'].document;} }catch(e){}"
        + "var t=(d.body?(d.body.innerText||d.body.textContent):'');"
        + "var h=(d.body?d.body.innerHTML:'');"
        + "var hit = /验证码|校验码|captcha|vcode/i.test(t) || /captcha|vcode|verifycode/i.test(h);"
        + "return hit?'1':'0';"
        + "})()";
    try {
      view.evaluateJavascript(js, value -> {
        if ("1".equals(value)) {
          Log.w(TAG, "captcha detected on login page");
          onCaptcha.run();
        }
      });
    } catch (Throwable t) {
      Log.w(TAG, "detect captcha failed", t);
    }
  }
}
