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
    /** 信息流4路并发刷新期间开启：完全不接受服务端对UTMP三件套的写入，防并发写乱。
     *  仅刷新期间开启，退出登录等场景仍放行(退出依赖服务端Set-Cookie改guest完成登出)。 */
    private static volatile boolean sBlockUtmpWrite = false;
    public static void setBlockUtmpWrite(boolean block) {
        sBlockUtmpWrite = block;
        android.util.Log.w(TAG, "COOKIE-PROTECT setBlockUtmpWrite=" + block);
    }

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        String urlString = url.toString();

        // ===== 实验版：拦截服务端把登录态重置为游客的 UTMP cookie 写入 =====
        // 条件：本地当前是登录态(UTMPUSERID 非 guest)，且服务端响应要写 UTMPUSERID=guest
        // → 拦截本批所有 UTMP 认证cookie(三件套配套，不能只拦其中一个)，其余cookie照常写。
        // 目的：验证 vivo 掉线是"服务端误下发guest cookie"(情况A)还是"会话真被作废"(情况B)。
        String currentUserId = readUtmpUserId();
        // 信息流刷新期间：无条件拦截所有UTMP三件套写入(服务端任何改动都不接受)
        // 非刷新期间：仅当"本地已登录 且 服务端要写guest"时拦截(防意外的会话重置)
        boolean serverResetsToGuest = false;
        if (currentUserId != null && !"guest".equalsIgnoreCase(currentUserId)) {
          for (Cookie c : cookies) {
            if ("main[UTMPUSERID]".equals(c.name()) && "guest".equalsIgnoreCase(c.value())) {
              serverResetsToGuest = true;
              break;
            }
          }
        }
        boolean blockUtmp = sBlockUtmpWrite || serverResetsToGuest;
        if (blockUtmp) {
          Log.w(TAG, "COOKIE-BLOCK >>> blockUtmp=" + blockUtmp + " (feedRefresh=" + sBlockUtmpWrite
              + ", serverResetGuest=" + serverResetsToGuest + ") current=" + currentUserId
              + " | from=" + urlString + " | caller=" + getCallerTag());
        }

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
            // 拦截UTMP三件套：信息流刷新期间全拦；否则只拦"写guest"
            if (blockUtmp && cookie.name().contains("UTMP")) {
              Log.w(TAG, "COOKIE-BLOCK >>> BLOCKED " + cookie.name() + "=" + cookie.value()
                  + " (keep local login as " + currentUserId + ")");
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