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

        String currentUserId = readUtmpUserId();
        // 新策略：服务端下发的 UTMPUSERID 是 guest 时，一律不写入(拦整批UTMP三件套)；
        // 只有服务端下发非guest(真实用户名)才更新cookie。
        // 理由：服务端反复下发guest会把本地登录态清掉导致掉线/反复登录；
        // 不写guest就能保住本地的有效会话cookie。
        boolean serverSendsGuest = false;
        for (Cookie c : cookies) {
          if ("main[UTMPUSERID]".equals(c.name()) && "guest".equalsIgnoreCase(c.value())) {
            serverSendsGuest = true;
            break;
          }
        }
        boolean blockUtmp = serverSendsGuest;
        if (blockUtmp) {
          Log.i(TAG, "COOKIE-BLOCK: server sends guest, keep local=" + currentUserId);
        }

        for (Cookie cookie : cookies) {
            // 拦截UTMP三件套：信息流刷新期间全拦；否则只拦"写guest"
            if (blockUtmp && cookie.name().contains("UTMP")) {
              continue; // 跳过写入，保留本地登录态
            }
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

    /** 实验版：读取当前cookie里的UTMPUSERID(登录用户名)，用于判断本地是否已登录 */
    private static String readUtmpUserId() {
        try {
            String cookies = CookieManager.getInstance().getCookie("https://www.newsmth.net/");
            if (cookies == null) return null;
            for (String part : cookies.split(";")) {
                String p = part.trim();
                if (p.startsWith("main[UTMPUSERID]=")) {
                    return p.substring("main[UTMPUSERID]=".length());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "readUtmpUserId failed", t);
        }
        return null;
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        String urlString = url.toString();
        String cookiesString = webviewCookieManager.getCookie(urlString);

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