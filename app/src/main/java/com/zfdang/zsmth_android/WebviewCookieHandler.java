package com.zfdang.zsmth_android;

import android.util.Log;
import android.webkit.CookieManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * Provides a synchronization point between the webview cookie store and okhttp3.OkHttpClient cookie store
 */
// https://gist.github.com/scitbiz/8cb6d8484bb20e47d241cc8e117fa705
// https://stackoverflow.com/questions/12731211/pass-cookies-from-httpurlconnection-java-net-cookiemanager-to-webview-android/18070681#18070681
// 为了使webview和okhttp3使用相同的cookies, 把webview的cookiemanager做了一层包装，使得okhttp3可以直接使用
// 这样就避免了cookie同步的问题

public final class WebviewCookieHandler implements CookieJar {
    private static final String TAG = "CookieJar";
    private CookieManager webviewCookieManager = CookieManager.getInstance();

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        String urlString = url.toString();

        // TEMP DEBUG: 详细记录"谁写的cookie、写了什么"。
        // 来源=HTTP响应(服务端Set-Cookie)，调用栈定位是哪个请求触发的。
        if (!cookies.isEmpty()) {
          for (Cookie c : cookies) {
            // TEMP DEBUG: value做脱敏——UTMPUSERID/UTMPKEY/UTMPNUM等认证cookie打完整值以外，
            // 其它cookie只打前8字符，避免打印可能的敏感内容
            String value = c.value();
            boolean authCookie = c.name().contains("UTMP");
            String shownValue = authCookie ? value : (value == null ? "" :
                (value.length() > 8 ? value.substring(0, 8) + "..." : value));
            Log.w(TAG, "COOKIE-WRITE[HTTP] name=" + c.name()
                + " value=" + shownValue
                + " domain=" + c.domain() + " path=" + c.path()
                + " expiresAt=" + c.expiresAt()
                + " hostOnly=" + c.hostOnly() + " secure=" + c.secure()
                + " | responseFrom=" + urlString
                + " | caller=" + getCallerTag());
          }
        }

        for (Cookie cookie : cookies) {
            webviewCookieManager.setCookie(urlString, cookie.toString());
        }
        // setCookie 是异步的，登录后立即 flush 落盘，避免进程被系统回收后
        // cookie 丢失、下次启动又触发一次登录（水木对频繁登录有限制）
        if (!cookies.isEmpty()) {
            try {
                webviewCookieManager.flush();
            } catch (Throwable t) {
                // 部分老版本 WebView 不支持，忽略
            }
        }
    }

    /** TEMP DEBUG: 从调用栈里提取发起网络请求的类，判断cookie是被哪个业务写入的 */
    private static String getCallerTag() {
      try {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : stack) {
          String cls = e.getClassName();
          if (cls.startsWith("com.zfdang.") && !cls.contains("WebviewCookieHandler")
              && !cls.contains("okhttp") && !cls.contains("retrofit")) {
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            if (sb.length() > 0) sb.append("<-");
            sb.append(simple).append(":").append(e.getLineNumber());
          }
        }
        return sb.length() > 0 ? sb.toString() : "unknown";
      } catch (Throwable t) {
        return "stack-error";
      }
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        String urlString = url.toString();
        String cookiesString = webviewCookieManager.getCookie(urlString);

        // 诊断用：只对登录态相关请求打 cookie 名（不含值），避免刷屏和泄露凭据
        String path = url.encodedPath();
        if (path.contains("ajax_session") || path.contains("ajax_login") || path.contains("article")) {
            if (cookiesString != null && !cookiesString.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (String h : cookiesString.split(";")) {
                    names.append(h.split("=")[0].trim()).append(' ');
                }
                Log.d(TAG, "send " + path + " cookies: " + names);
            } else {
                Log.d(TAG, "send " + path + " : NO COOKIE");
            }
        }

        // TEMP DEBUG: 盯UTMPUSERID读出值，定位何时变guest
        if (cookiesString != null && cookiesString.contains("UTMPUSERID")) {
          for (String part : cookiesString.split(";")) {
            if (part.trim().startsWith("main[UTMPUSERID]")) {
              Log.w(TAG, "LOAD UTMPUSERID='" + part.trim() + "' for " + path);
              break;
            }
          }
        }
        if (cookiesString != null && !cookiesString.isEmpty()) {
          //We can split on the ';' char as the cookie manager only returns cookies
          //that match the url and haven't expired, so the cookie attributes aren't included
          String[] cookieHeaders = cookiesString.split(";");
            List<Cookie> cookies = new ArrayList<>(cookieHeaders.length);

            for (String header : cookieHeaders) {
                cookies.add(Cookie.parse(url, header));
            }

            return cookies;
        }

        return Collections.emptyList();
    }
}