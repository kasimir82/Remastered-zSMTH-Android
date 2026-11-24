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
    /** 仅信息流4路并发刷新期间拦截服务端下发的guest cookie（防并发写乱）。
     *  非刷新期一律放行：让服务端能正常改cookie(注销/会话失效)，状态判断统一交给状态机。 */
    private static volatile boolean sBlockGuestDuringFeed = false;
    public static void setBlockGuestDuringFeed(boolean block) {
        sBlockGuestDuringFeed = block;
    }
    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        String urlString = url.toString();

        // 仅信息流刷新期间拦截服务端下发的guest(防4路并发写乱cookie)。
        // 其它时候一律放行：注销/会话失效时服务端需要正常把cookie改成guest，
        // 登录态判断由 LoginStateManager 状态机负责，不再靠"拦cookie"维持。
        boolean blockUtmp = false;
        if (sBlockGuestDuringFeed) {
          for (Cookie c : cookies) {
            if ("main[UTMPUSERID]".equals(c.name()) && "guest".equalsIgnoreCase(c.value())) {
              blockUtmp = true;
              break;
            }
          }
          if (blockUtmp) {
            Log.i(TAG, "COOKIE-BLOCK: feed refresh, blocked server guest");
          }
        }

        for (Cookie cookie : cookies) {
            if (blockUtmp && cookie.name().contains("UTMP")) {
              continue; // 刷新期跳过guest三件套写入
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