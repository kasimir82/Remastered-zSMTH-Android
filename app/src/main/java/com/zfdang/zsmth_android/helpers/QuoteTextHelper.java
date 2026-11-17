package com.zfdang.zsmth_android.helpers;

import android.app.Activity;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

import com.klinker.android.link_builder.LinkBuilder;
import com.zfdang.zsmth_android.R;
import com.zfdang.zsmth_android.models.ContentSegment;

/**
 * 帖子/邮件正文文本段的统一绑定：引文（冒号开头）默认折叠成一行提示，点击展开全文、再点收起。
 */
public class QuoteTextHelper {
  private static final int QUOTE_HINT_COLOR = 0xFF888888;

  /**
   * @param tv      要绑定的 TextView
   * @param content 文本段
   * @param activity 用于给普通文本加链接
   * @param addLinks 普通段是否加链接
   */
  public static void bind(final TextView tv, final ContentSegment content, Activity activity, boolean addLinks) {
    if (content.isQuote()) {
      final Spanned full = content.getSpanned();
      final int lineCount = countQuoteLines(full);
      tv.setTag(R.id.quote_expanded, Boolean.FALSE);
      tv.setText(hint(lineCount));
      tv.setOnClickListener(v -> {
        boolean expanded = Boolean.TRUE.equals(tv.getTag(R.id.quote_expanded));
        if (expanded) {
          tv.setTag(R.id.quote_expanded, Boolean.FALSE);
          tv.setText(hint(lineCount));
        } else {
          tv.setTag(R.id.quote_expanded, Boolean.TRUE);
          tv.setText(full);
        }
      });
    } else {
      // 首段 TextView 在 RecyclerView 里复用，清掉引文留下的点击监听和 tag
      tv.setOnClickListener(null);
      tv.setTag(R.id.quote_expanded, null);
      tv.setText(content.getSpanned());
      if (addLinks) {
        LinkBuilder.on(tv).addLinks(ActivityUtils.getPostSupportedLinks(activity)).build();
      }
    }
  }

  private static CharSequence hint(int lineCount) {
    SpannableString s = new SpannableString("展开引文（" + lineCount + " 行）");
    s.setSpan(new ForegroundColorSpan(QUOTE_HINT_COLOR), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return s;
  }

  /** Spanned 以 \n 分隔，去掉末尾换行产生的空行 */
  private static int countQuoteLines(Spanned spanned) {
    String[] lines = spanned.toString().split("\n");
    int count = 0;
    for (String line : lines) {
      if (line.trim().length() > 0) {
        count++;
      }
    }
    return Math.max(1, count);
  }
}
