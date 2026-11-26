package com.zfdang.zsmth_android;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

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
    private static final CookieManager webviewCookieManager = CookieManager.getInstance();
    /** 仅信息流4路并发刷新期间拦截服务端下发的guest cookie（防并发写乱）。
     *  非刷新期一律放行：让服务端能正常改cookie(注销/会话失效)，状态判断统一交给状态机。 */
    private static volatile boolean sBlockGuestDuringFeed = false;
    public static void setBlockGuestDuringFeed(boolean block) {
        sBlockGuestDuringFeed = block;
    }
    /** 登录成功后的三件套保护锁(截止时间戳)：2分钟内拦截服务端下发的guest三件套，
     *  防止刚建立的会话被版面检查等请求踢回guest。 */
    private static volatile long sLoginProtectUntil = 0;
    private static final long LOGIN_PROTECT_MS = 2L * 60 * 1000;
    /** 登录成功时的真三件套快照（用于WebView被踢后写回） */
    private static volatile String[] sProtectedTriple = null; // {userid, key, num}
    /** 登录成功(拿到非guest完整三件套)时调用，开启2分钟保护并快照真三件套 */
    public static void onLoginSuccessProtect(String userId, String key, String num) {
        sProtectedTriple = new String[]{userId, key, num};
        sLoginProtectUntil = System.currentTimeMillis() + LOGIN_PROTECT_MS;
        com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar", "LOGIN_PROTECT_ON",
            com.zfdang.zsmth_android.helpers.DiagLogger.PASS,
            "protect UTMP write for 2min | saved triple: USERID=" + userId + " KEY=" + key + " NUM=" + num);
        // 启动周期检查线程：每1秒检查cookie是否被WebView写成guest，是则写回(true三件套)。
        // 不依赖特定WebView的onPageFinished(app内多个WebView都会写cookie)。
        new Thread(() -> {
            while (System.currentTimeMillis() < sLoginProtectUntil && sProtectedTriple != null) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                restoreTripleIfNeeded(null); // null = 不依赖具体WebView
            }
        }, "utmp-protect").start();
    }
    /** 兼容旧调用(无三件套参数)：从当前cookie读取 */
    public static void onLoginSuccessProtect() {
        String[] t = readTripleFromWebView();
        onLoginSuccessProtect(t[0], t[1], t[2]);
    }
    /** 读取当前WebView cookie里的UTMP三件套 */
    private static String[] readTripleFromWebView() {
        String uid = null, key = null, num = null;
        try {
            String cookies = webviewCookieManager.getCookie("https://www.newsmth.net/");
            if (cookies != null) {
                for (String part : cookies.split(";")) {
                    String p = part.trim();
                    if (p.startsWith("main[UTMPUSERID]=")) uid = p.substring("main[UTMPUSERID]=".length());
                    else if (p.startsWith("main[UTMPKEY]=")) key = p.substring("main[UTMPKEY]=".length());
                    else if (p.startsWith("main[UTMPNUM]=")) num = p.substring("main[UTMPNUM]=".length());
                }
            }
        } catch (Throwable t) {}
        return new String[]{uid, key, num};
    }
    /** 手动写入完整三件套到WebView cookie（供"恢复UTMP"功能用） */
    public static void writeTriple(String userId, String key, String num) {
        try {
            String domain = ".newsmth.net";
            webviewCookieManager.setCookie(domain, "main[UTMPUSERID]=" + userId + "; Path=/; Domain=" + domain);
            webviewCookieManager.setCookie(domain, "main[UTMPKEY]=" + key + "; Path=/; Domain=" + domain);
            webviewCookieManager.setCookie(domain, "main[UTMPNUM]=" + num + "; Path=/; Domain=" + domain);
            webviewCookieManager.flush();
            com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar", "MANUAL_WRITE_TRIPLE",
                com.zfdang.zsmth_android.helpers.DiagLogger.PASS,
                "USERID=" + userId + " KEY=" + key + " NUM=" + num);
        } catch (Throwable t) {
            com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar", "MANUAL_WRITE_FAIL",
                com.zfdang.zsmth_android.helpers.DiagLogger.FAIL, String.valueOf(t));
        }
    }

    /**
     * 清除本地已失效的 UTMP 三件套（把三个 cookie 置为过期）。
     *
     * <p>只在「cookie 里还有用户名，但服务端已经不认这个会话」时使用：例如账号在别处登录
     * 被顶号、或会话过期后服务端一直按 guest 响应。这种情况下本地的三件套是"假登录"，
     * 必须先清掉，否则登录页和自动登录会一直以为是已登录状态，永远无法真正重建会话。
     *
     * <p>注意：这不是伪造登录态，只是删除自己的失效凭据，真正的三件套仍由服务端下发。
     */
    public static void clearUtmpTriple(String reason) {
        try {
            String domain = ".newsmth.net";
            String expire = "=; Path=/; Domain=" + domain
                + "; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT";
            webviewCookieManager.setCookie(domain, "main[UTMPUSERID]" + expire);
            webviewCookieManager.setCookie(domain, "main[UTMPKEY]" + expire);
            webviewCookieManager.setCookie(domain, "main[UTMPNUM]" + expire);
            webviewCookieManager.flush();
            com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar", "CLEAR_UTMP",
                com.zfdang.zsmth_android.helpers.DiagLogger.PASS, "reason=" + reason);
        } catch (Throwable t) {
            com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar", "CLEAR_UTMP_FAIL",
                com.zfdang.zsmth_android.helpers.DiagLogger.FAIL, String.valueOf(t));
        }
    }

    /**
     * WebView页面加载完成后调用：保护期内若cookie被WebView写成guest，
     * 立即写回登录时的真三件套（WebView写cookie不经过OkHttp拦截器，保护锁拦不到）。
     */
    public static void restoreTripleIfNeeded(WebView view) {
        if (System.currentTimeMillis() >= sLoginProtectUntil || sProtectedTriple == null) return;
        try {
            String cookies = webviewCookieManager.getCookie("https://www.newsmth.net/");
            if (cookies == null) return;
            boolean isGuest = false;
            for (String part : cookies.split(";")) {
                String p = part.trim();
                if (p.startsWith("main[UTMPUSERID]=")) {
                    String v = p.substring("main[UTMPUSERID]=".length());
                    isGuest = v == null || v.isEmpty() || "guest".equalsIgnoreCase(v) || "deleted".equalsIgnoreCase(v);
                }
            }
            if (!isGuest) return; // 还是真登录态，不用写回
            // 写回真三件套
            String[] t = sProtectedTriple;
            String domain = ".newsmth.net";
            webviewCookieManager.setCookie(domain, "main[UTMPUSERID]=" + t[0] + "; Path=/; Domain=" + domain);
            webviewCookieManager.setCookie(domain, "main[UTMPKEY]=" + t[1] + "; Path=/; Domain=" + domain);
            webviewCookieManager.setCookie(domain, "main[UTMPNUM]=" + t[2] + "; Path=/; Domain=" + domain);
            webviewCookieManager.flush();
            com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar", "WEBVIEW_RESTORE",
                com.zfdang.zsmth_android.helpers.DiagLogger.PASS,
                "WebView wrote guest, restored: USERID=" + t[0] + " KEY=" + t[1] + " NUM=" + t[2]);
            Log.i(TAG, "WEBVIEW_RESTORE: restored UTMP triple after WebView guest write");
        } catch (Throwable t) {
            com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar", "WEBVIEW_RESTORE_FAIL",
                com.zfdang.zsmth_android.helpers.DiagLogger.FAIL, String.valueOf(t));
        }
    }
    /** 注销时清除保护(否则用户注销后guest写不进去) */
    public static void clearLoginProtect() {
        sLoginProtectUntil = 0;
        sProtectedTriple = null;
        com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar", "LOGIN_PROTECT_OFF",
            com.zfdang.zsmth_android.helpers.DiagLogger.PASS, "cleared");
    }
    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        String urlString = url.toString();

        // 仅信息流刷新期间拦截服务端下发的guest(防4路并发写乱cookie)。
        // 其它时候一律放行：注销/会话失效时服务端需要正常把cookie改成guest，
        // 登录态判断由 LoginStateManager 状态机负责，不再靠"拦cookie"维持。
        boolean blockUtmp = false;
        boolean inLoginProtect = System.currentTimeMillis() < sLoginProtectUntil;

        // 先看本次下发的是不是 guest 三件套
        boolean hasGuestUtmp = false;
        boolean hasRealUtmp = false;
        for (Cookie c : cookies) {
            if (c.name() == null) continue;
            if ("main[UTMPUSERID]".equals(c.name())) {
                String v = c.value();
                if (v == null || v.isEmpty() || "guest".equalsIgnoreCase(v) || "deleted".equalsIgnoreCase(v)) {
                    hasGuestUtmp = true;
                } else {
                    hasRealUtmp = true;
                }
            }
        }

        // ★锁期间服务端下发非guest真实三件套：允许写入，并把锁续期2分钟
        // (说明会话被服务端刷新成另一个有效用户，重新保护这个新会话)
        if (inLoginProtect && hasRealUtmp) {
            sLoginProtectUntil = System.currentTimeMillis() + LOGIN_PROTECT_MS;
            com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar", "LOGIN_PROTECT_RENEW",
                com.zfdang.zsmth_android.helpers.DiagLogger.PASS,
                "server sent real UTMP (non-guest), allow write & renew lock 2min from=" + urlString);
            Log.i(TAG, "COOKIE: login protect renewed (real UTMP received)");
        }

        // 拦截判定：①登录保护期 或 ②信息流刷新期，都只拦 guest 三件套
        if ((inLoginProtect || sBlockGuestDuringFeed) && hasGuestUtmp) {
            blockUtmp = true;
            Log.i(TAG, "COOKIE-BLOCK: " + (inLoginProtect ? "login protect" : "feed refresh")
                + ", blocked guest UTMP");
        }

        // 诊断：记录服务端本次下发的UTMP三件套完整值，便于追踪登录态变化
        // (之前只有poll读到用户名时才记，看不到服务端实际下发/清空cookie的时刻)
        StringBuilder utmp = new StringBuilder();
        for (Cookie c : cookies) {
            String n = c.name();
            if (n != null && n.contains("UTMP")) {
                if (utmp.length() > 0) utmp.append(" ");
                utmp.append(n).append('=').append(c.value());
            }
        }
        if (utmp.length() > 0) {
            com.zfdang.zsmth_android.helpers.DiagLogger.log("CookieJar",
                "SERVER_UTMP", blockUtmp ? com.zfdang.zsmth_android.helpers.DiagLogger.FAIL
                                         : com.zfdang.zsmth_android.helpers.DiagLogger.PASS,
                "from=" + urlString + " | " + utmp + (blockUtmp ? (inLoginProtect ? " | BLOCKED(LOGIN_PROTECT)" : " | BLOCKED(FEED)") : "") + (inLoginProtect && hasRealUtmp ? " | RENEWED" : ""));
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