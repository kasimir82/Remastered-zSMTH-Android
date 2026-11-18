package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
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
    if (running) {
      Log.d(TAG, "login already running, skip");
      return;
    }
    String username = Settings.getInstance().getUsername();
    String password = Settings.getInstance().getPassword();
    if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
      Log.w(TAG, "no saved credentials, skip silent login");
      callback.onFailed("no_credentials");
      return;
    }
    // 已经有有效登录态，无需登录
    String current = readUserId();
    if (current != null && !current.isEmpty() && !"guest".equalsIgnoreCase(current)) {
      Log.i(TAG, "already logged in: " + current);
      callback.onSuccess(current);
      return;
    }
    // 冷却期内不提交，避免触发水木限制
    if (LoginThrottle.inCooldown()) {
      Log.w(TAG, "in cooldown, skip silent login");
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
          // 页面加载完后注入账号密码并提交
          injectCredentials(view, username, password);
          // 检测是否出现验证码页面
          detectCaptcha(view, () -> {
            if (done[0]) return;
            finish(holder, callback, done, false);
            callback.onCaptchaRequired();
          });
          pollRunnable[0].run();
        }
      });

      pollRunnable[0] = new Runnable() {
        @Override public void run() {
          if (done[0]) return;
          String userId = readUserId();
          if (userId != null && !userId.isEmpty() && !"guest".equalsIgnoreCase(userId)) {
            Log.i(TAG, "silent login success: " + userId);
            LoginThrottle.onLoginSuccess();
            finish(holder, callback, done, true);
            callback.onSuccess(userId);
            return;
          }
          if (++pollCount[0] > MAX_POLL) {
            Log.w(TAG, "silent login timeout, fallback to user interaction");
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
      if (cookies == null) return null;
      for (String part : cookies.split(";")) {
        String p = part.trim();
        if (p.startsWith(COOKIE_USER_KEY)) {
          return p.substring(COOKIE_USER_KEY.length());
        }
      }
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
