package com.zfdang.zsmth_android.newsmth;

import org.jsoup.nodes.Document;

/** The forum returned a verification form instead of the requested articles. */
public final class GuestReadVerificationException extends IllegalStateException {
  public GuestReadVerificationException() {
    super("水木社区要求游客完成阅读验证，请登录已有账号后重试。");
  }

  public static void check(Document document) {
    if (document == null) return;
    // 情况1：游客阅读验证页面（带 .guest-read-challenge 元素）
    if (!document.select(".guest-read-challenge").isEmpty()) {
      throw new GuestReadVerificationException();
    }
    // 情况2：服务端直接返回「您未登录,请登录后继续操作」的提示页（约2KB）。
    // 这种页面没有 guest-read-challenge class，之前会被当成正文显示给用户。
    // 限定：页面文本很短(错误提示页)才判定，避免正文里引用这句话被误判。
    String text = document.text();
    if (text != null && text.length() < 500
        && (text.contains("您未登录") || text.contains("请登录后继续操作"))) {
      throw new GuestReadVerificationException();
    }
  }
}
