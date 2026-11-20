package com.zfdang.zsmth_android;

import android.app.Activity;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * 桌面版登录链路:
 *   https://www.newsmth.net/index.html                       (JS 写出 frameset)
 *     -> frame: https://www.newsmth.net/indexpages/default/  (表单 name=id / name=passwd)
 *       -> POST https://www.newsmth.net/bbslogin1203.php     (无验证码)
 *
 * 登录成功与否通过 Cookie main[UTMPUSERID] 是否为 guest 判断,
 * 因为该页面在已登录状态下依然显示登录表单, 无法用页面内容或跳转 URL 判定.
 */
public class WebviewLoginClient extends WebViewClient {

    private static final String TAG = "WebviewLoginClient";
    private static final String COOKIE_URL = "https://www.newsmth.net/";
    private static final String COOKIE_USER_KEY = "main[UTMPUSERID]=";
    private static final int POLL_INTERVAL_MS = 1000;
    private static final int MAX_POLL = 25;

    private final Activity activity;
    private final String username;
    private final String password;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pollCount;
    private boolean polling;
    private boolean done;

    public WebviewLoginClient(Activity activity, String username, String password) {
        this.activity = activity;
        this.username = username;
        this.password = password;
    }

    /** 读取当前 Cookie 里的登录用户 ID, 未登录时返回 "guest" 或 null */
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

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return false;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        if (url.contains("ads")) {
            return new WebResourceResponse("text/javascript", "UTF-8", null);
        }
        return null;
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        if (done || url == null || !url.contains("newsmth.net")) {
            return;
        }
        injectCredentials(view);
        startPolling();
    }

    /**
     * 注入到顶层文档, 用轮询等待 frameset 的子框架加载完成后再填表并提交.
     * 因为 frameset 页面本身没有 body, 表单位于 mainFrame 子文档中.
     */
    private void injectCredentials(WebView view) {
        if (username == null || password == null) {
            Log.w(TAG, "username/password is null, skip auto fill");
            return;
        }

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
                + "})();";

        try {
            if (Build.VERSION.SDK_INT >= 19) {
                view.evaluateJavascript(js, null);
            } else {
                view.loadUrl(js);
            }
        } catch (Throwable t) {
            Log.e(TAG, "inject credentials failed", t);
        }
    }

    private void startPolling() {
        if (polling || done) return;
        polling = true;
        pollCount = 0;
        handler.post(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (done) return;

            String userId = readUserId();
            if (userId != null && userId.length() > 0 && !"guest".equalsIgnoreCase(userId)) {
                Log.i(TAG, "login success, UTMPUSERID=" + userId);
                finishWithResultOk();
                return;
            }

            if (++pollCount > MAX_POLL) {
                // 轮询结束仍未登录成功：密码错误/账号异常/网络失败都会走到这里。
                // 必须结束当前页面并返回，否则用户卡在 WebView 错误页无法重新输入密码。
                Log.w(TAG, "login not confirmed after " + MAX_POLL + "s, back to login form");
                done = true;
                polling = false;
                handler.removeCallbacks(pollRunnable);
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Runnable doFail = () -> {
                            if (activity instanceof com.zfdang.zsmth_android.WebviewLoginActivity) {
                                ((com.zfdang.zsmth_android.WebviewLoginActivity) activity).failAndFinish(
                                    com.zfdang.zsmth_android.LoginActivity.ERR_TYPE_OTHER, "login timeout");
                            } else {
                                activity.finish();
                            }
                        };
                        try {
                            new android.app.AlertDialog.Builder(activity)
                                .setTitle("登录失败")
                                .setMessage("未能登录，请返回重新检查账号密码或稍后重试。")
                                .setPositiveButton("返回", (dialog, which) -> doFail.run())
                                .setOnCancelListener(dialog -> doFail.run())
                                .setCancelable(false)
                                .show();
                        } catch (Throwable t) {
                            Log.e(TAG, "show login failed dialog error", t);
                            doFail.run();
                        }
                    }
                });
                return;
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void finishWithResultOk() {
        if (done) return;
        done = true;
        polling = false;
        handler.removeCallbacks(pollRunnable);
        try {
            Intent resultIntent = new Intent();
            activity.setResult(Activity.RESULT_OK, resultIntent);
            activity.finish();
        } catch (Throwable t) {
            Log.e(TAG, "finish activity failed", t);
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.e(TAG, "WebView error: " + error.getDescription() + " for URL: " + request.getUrl());
        }
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        Log.e(TAG, "WebView error: " + description + " (code: " + errorCode + ") for URL: " + failingUrl);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        try {
            String host = (error != null && error.getUrl() != null) ? error.getUrl() : null;
            if (host == null && view != null && view.getUrl() != null) {
                host = view.getUrl();
            }
            Log.e(TAG, "onReceivedSslError for host: " + host + ", primaryError=" + (error != null ? error.getPrimaryError() : "null"));
            if (host != null && host.contains("newsmth.net")) {
                handler.proceed();
                Log.w(TAG, "Proceeding SSL error for trusted host: " + host);
            } else {
                handler.cancel();
                Log.w(TAG, "Cancelled SSL for host: " + host);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception in onReceivedSslError", e);
            try {
                handler.cancel();
            } catch (Exception ignored) {}
        }
    }
}
