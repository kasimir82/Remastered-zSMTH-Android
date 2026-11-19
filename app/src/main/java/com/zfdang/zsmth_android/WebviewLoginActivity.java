package com.zfdang.zsmth_android;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class WebviewLoginActivity extends SMTHBaseActivity {

    private static final String TAG = "WebviewLoginActivity";
    private WebView mWebView;
    // 桌面版登录页: index.html 由 JS 写出 frameset, 真正表单在 indexpages/default/ 子框架内
    // 该页面无验证码, 表单 POST 到 /bbslogin1203.php
    private String url = "https://www.newsmth.net/index.html";

    private String username;
    private String password;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview_login);
        setupBottomNavigation();
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.webview_login_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        // get username & password
        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            username = extras.getString(LoginActivity.USERNAME);
            password = extras.getString(LoginActivity.PASSWORD);
        }

        // cookie 里已经有有效登录用户时，不要重复打开登录页提交表单——
        // 水木对短时间内重复登录有限制（"账号过多/请勿频繁登录"）。
        String existingUser = WebviewLoginClient.readUserId();
        if (existingUser != null && !existingUser.isEmpty()
                && !"guest".equalsIgnoreCase(existingUser)) {
            Log.i(TAG, "already logged in as " + existingUser + ", skip login form");
            com.zfdang.zsmth_android.helpers.LoginThrottle.onLoginSuccess();
            setResult(Activity.RESULT_OK);
            finish();
            return;
        }

        // 处于登录频繁冷却期，直接拒绝，不再向水木提交登录
        if (com.zfdang.zsmth_android.helpers.LoginThrottle.inCooldown()) {
            Log.w(TAG, "in login cooldown, skip webview login");
            new android.app.AlertDialog.Builder(this)
                    .setTitle("登录受限")
                    .setMessage(com.zfdang.zsmth_android.helpers.LoginThrottle.getCooldownMessage())
                    .setPositiveButton("知道了", (dialog, which) -> finish())
                    .setOnCancelListener(dialog -> finish())
                    .show();
            return;
        }

        mWebView = (WebView) findViewById(R.id.webview_login);

        try {
            WebSettings webSettings = mWebView.getSettings();
            webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
            webSettings.setGeolocationEnabled(false);
            webSettings.setSaveFormData(false);
            webSettings.setSavePassword(false);
            webSettings.setJavaScriptEnabled(true); ///------- 设置javascript 可用

            // https://stackoverflow.com/questions/9602124/enable-horizontal-scrolling-in-a-webview
            webSettings.setLoadWithOverviewMode(true);
            webSettings.setUseWideViewPort(true);
//        mWebView.setInitialScale(120);
//        mWebView.getSettings().setTextZoom(200);

            //支持屏幕缩放
            webSettings.setSupportZoom(true);
            webSettings.setBuiltInZoomControls(true);

            // Android 16兼容性修复：启用必要的WebView功能
            // Fix for Android 16: Enable necessary WebView features
            webSettings.setDomStorageEnabled(true);  // 启用DOM存储，现代网页必需
            webSettings.setDatabaseEnabled(true);     // 启用数据库存储

            // 允许混合内容 (需要 API 21+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                webSettings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            // Android 16兼容性修复：确保WebView渲染器不会因为错误而白屏
            // Fix for Android 16: Ensure WebView renderer doesn't crash
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                webSettings.setSafeBrowsingEnabled(true);
            }

            // Ensure user agent has a modern token to avoid some sites returning mobile-empty pages
            String ua = webSettings.getUserAgentString();
            if (ua == null || !ua.contains("Mobile")) {
                webSettings.setUserAgentString(ua + " Mobile-SMTH-App");
            }

            // Only set hardware layer on API 17+ to be safe
            if (android.os.Build.VERSION.SDK_INT >= 17) {
                mWebView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
            }

        } catch (Throwable t) {
            Log.e(TAG, "Exception while configuring WebView settings", t);
        }

        mWebView.setWebChromeClient(new WebChromeClient()
        {
            @Override
            public void onProgressChanged(WebView view, int newProgress)
            {
                super.onProgressChanged(view, newProgress);
                view.requestFocus();
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.i(TAG, "Console: " + consoleMessage.message() + " -- " + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber());
                return super.onConsoleMessage(consoleMessage);
            }

            /**
             * 水木在登录过于频繁时会用 JS 弹窗提示"账号过多/无法重复登录/是否踢掉多余窗口"。
             * 识别到就进入冷却、停止后续自动登录，并给出明确提示，而不是让用户反复点确定重试。
             */
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final android.webkit.JsResult result) {
                // 记录所有 JS 弹窗原文，便于定位登录页返回的具体错误
                Log.w(TAG, "JS alert: " + message + " (url=" + url + ")");
                com.zfdang.zsmth_android.helpers.LoginFailureLogger.log("js_alert", message + " (url=" + url + ")");
                if (com.zfdang.zsmth_android.helpers.LoginThrottle.isTooFrequentDialog(message)) {
                    com.zfdang.zsmth_android.helpers.LoginThrottle.onTooFrequent(message);
                    com.zfdang.zsmth_android.helpers.LoginFailureLogger.log("too_frequent", message);
                    Log.w(TAG, "too frequent login dialog: " + message);
                    new android.app.AlertDialog.Builder(WebviewLoginActivity.this)
                            .setTitle("登录受限")
                            .setMessage(com.zfdang.zsmth_android.helpers.LoginThrottle.getCooldownMessage("水木提示登录过于频繁（" + message + "）"))
                            .setPositiveButton("知道了", (dialog, which) -> {
                                result.confirm();
                                finish();
                            })
                            .setOnCancelListener(dialog -> result.cancel())
                            .show();
                    return true;
                }
                return super.onJsAlert(view, url, message, result);
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final android.webkit.JsResult result) {
                // 记录所有 JS confirm 原文（含「踢掉多余窗口」类提示）
                Log.w(TAG, "JS confirm: " + message + " (url=" + url + ")");
                com.zfdang.zsmth_android.helpers.LoginFailureLogger.log("js_confirm", message + " (url=" + url + ")");
                if (com.zfdang.zsmth_android.helpers.LoginThrottle.isTooFrequentDialog(message)) {
                    com.zfdang.zsmth_android.helpers.LoginThrottle.onTooFrequent(message);
                    com.zfdang.zsmth_android.helpers.LoginFailureLogger.log("too_frequent", message);
                    Log.w(TAG, "too frequent login confirm: " + message);
                    // 用户点的"踢掉多余窗口"会挤掉其它设备的登录，这里直接取消并结束
                    new android.app.AlertDialog.Builder(WebviewLoginActivity.this)
                            .setTitle("登录受限")
                            .setMessage(com.zfdang.zsmth_android.helpers.LoginThrottle.getCooldownMessage("水木提示：" + message))
                            .setPositiveButton("知道了", (dialog, which) -> {
                                result.cancel();
                                finish();
                            })
                            .setOnCancelListener(dialog -> result.cancel())
                            .show();
                    return true;
                }
                return super.onJsConfirm(view, url, message, result);
            }
        });

        // 创建自定义的 WebViewClient 并添加渲染器崩溃处理
        WebviewLoginClient webViewClient = new WebviewLoginClient(this, username, password) {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    Log.e(TAG, "WebView渲染器进程崩溃 - didCrash: " + detail.didCrash());
                    // 清理当前的 WebView
                    if (mWebView != null) {
                        mWebView.destroy();
                        mWebView = null;
                    }
                    // 通知用户并关闭活动
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(WebviewLoginActivity.this,
                            "WebView加载失败，请重试",
                            android.widget.Toast.LENGTH_LONG).show();
                        finish();
                    });
                    return true; // 表示我们已经处理了崩溃
                }
                return super.onRenderProcessGone(view, detail);
            }
        };

        mWebView.setWebViewClient(webViewClient);

        try {
            mWebView.loadUrl(url);
        } catch (Throwable t) {
            Log.e(TAG, "Exception while loading URL", t);
            // Show a simple fallback message instead of a white screen
            if (mWebView != null) {
                mWebView.loadData("<html><body><h3>加载失败，请检查网络或重试</h3></body></html>", "text/html", "UTF-8");
            }
        }
    }

    @Override
    protected void onDestroy() {
        // 正确清理 WebView 以防止内存泄漏和崩溃
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.setWebViewClient(null);
            mWebView.setWebChromeClient(null);
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }
}
