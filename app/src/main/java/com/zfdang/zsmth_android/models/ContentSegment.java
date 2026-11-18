package com.zfdang.zsmth_android.models;

import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;

/**
 * Post content will finally be processed as a list of ContentSegment
 * It might represent one piece of text, or one attached image
 * Created by zfdang on 2016-3-31.
 */
public class ContentSegment {
  public static final int SEGMENT_TEXT = 292;
  public static final int SEGMENT_IMAGE = 165;

  private int type;
  private Spanned spanned;
  private String url;
  // image index among all attachments, it will be used for full screen image viewer
  private int imgIndex;
  // true 表示这段是冒号开头的引文，界面上默认折叠、点击展开
  private boolean quote;

  public int getImgIndex() {
    return imgIndex;
  }

  public void setImgIndex(int imgIndex) {
    this.imgIndex = imgIndex;
  }

  public ContentSegment(int type, String content) {
    this.type = type;
    if (this.type == SEGMENT_TEXT) {
      this.setText(content);
    } else if (this.type == SEGMENT_IMAGE) {
      this.setUrl(content);
    }
  }

  public void setText(String text) {
    // http://stackoverflow.com/questions/4793347/jsoup-not-translating-ampersand-in-links-in-html
    // Html.fromHtml has bugs to parse &mid: only &mid; should be converted, but it convert &mid wrongly
    String tempText = text.replaceAll("&mid([^;])", "&amp;mid$1");

    // 来源/修改行被 Post 用 SMALL_MARK 成对包裹，去掉标记并加半号字体 span
    String mark = Post.SMALL_MARK;
    if (tempText.contains(mark)) {
      SpannableStringBuilder builder = new SpannableStringBuilder();
      int cursor = 0;
      while (true) {
        int start = tempText.indexOf(mark, cursor);
        if (start < 0) {
          // 剩余普通文本
          if (cursor < tempText.length()) {
            builder.append(Html.fromHtml(tempText.substring(cursor)));
          }
          break;
        }
        // 标记前的普通文本
        if (start > cursor) {
          builder.append(Html.fromHtml(tempText.substring(cursor, start)));
        }
        int end = tempText.indexOf(mark, start + mark.length());
        if (end < 0) {
          builder.append(Html.fromHtml(tempText.substring(start + mark.length())));
          break;
        }
        // 标记内的文本：转 HTML 后加半号字体
        CharSequence small = Html.fromHtml(tempText.substring(start + mark.length(), end));
        int spanStart = builder.length();
        builder.append(small);
        builder.setSpan(new RelativeSizeSpan(0.5f), spanStart, builder.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        cursor = end + mark.length();
      }
      this.spanned = builder;
    } else {
      this.spanned = Html.fromHtml(tempText);
    }
  }

  public Spanned getSpanned() {
    return spanned;
  }

  public int getType() {
    return type;
  }

  public boolean isQuote() {
    return quote;
  }

  public void setQuote(boolean quote) {
    this.quote = quote;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }
}
