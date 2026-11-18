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

        for (Cookie cookie : cookies) {
            webviewCookieManager.setCookie(urlString, cookie.toString());
        }
        // 诊断用：只打 cookie 名和域，不打印值，避免泄露登录凭据
        if (!cookies.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Cookie c : cookies) {
                names.append(c.name()).append('@').append(c.domain()).append(' ');
            }
            Log.d(TAG, "save " + cookies.size() + " cookies: " + names + " from " + url.host());
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