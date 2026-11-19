package com.zfdang.zsmth_android.helpers;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.klinker.android.link_builder.LinkBuilder;
import com.zfdang.zsmth_android.R;
import com.zfdang.zsmth_android.models.ContentSegment;

/**
 * 帖子/邮件正文文本段的统一绑定：引文（冒号开头）默认折叠成一行提示，
 * 折叠提示左边有一个 ▶ 小三角；只有点三角才展开（由 RecyclerViewGestureListener
 * 统一判断点击坐标后调用 toggleQuote），点文字区域不展开、不误触翻页。
 */
public class QuoteTextHelper {
  // 折叠引文提示的灰色；与「大作中提到」标题行共用同一色值(见 Post.QUOTE_HEADER_HTML_COLOR)
  private static final int QUOTE_HINT_COLOR = 0xFFAAAAAA;
  private static final String TRIANGLE = "▶";

  public static void bind(final TextView tv, final ContentSegment content, Activity activity, boolean addLinks) {
    if (content.isQuote()) {
      final Spanned full = content.getSpanned();
      final int lineCount = countQuoteLines(full);
      tv.setTag(R.id.quote_expanded, Boolean.FALSE);
      // 保存原文与行数到 tag，供 toggleQuote 使用
      tv.setTag(R.id.quote_full_text, full);
      tv.setTag(R.id.quote_line_count, lineCount);
      tv.setText(hint(tv, lineCount));
      // 三角点击由父容器统一处理(帖子页 RecyclerViewGestureListener / 邮件页 MailContentActivity)，
      // 共用 handleTapQuote：因为父容器的 OnTouchListener 会吞掉子 TextView 的点击事件。
    } else {
      tv.setTag(R.id.quote_expanded, null);
      tv.setTag(R.id.quote_full_text, null);
      tv.setText(content.getSpanned());
      if (addLinks) {
        LinkBuilder.on(tv).addLinks(ActivityUtils.getPostSupportedLinks(activity)).build();
      }
    }
  }

  private static long sLastToggleTime = 0;

  /** 切换引文展开/折叠。返回 true 表示这次点击被处理了 */
  public static boolean toggleQuote(TextView tv) {
    if (tv == null) return false;
    // 防抖：帖子页 TextView 自身 OnTouch 与 RecyclerView 手势可能都识别到同一次点击，
    // 300ms 内只处理一次，避免展开后立刻又被收起。
    long now = System.currentTimeMillis();
    if (now - sLastToggleTime < 300) return true;
    sLastToggleTime = now;
    Object expandedTag = tv.getTag(R.id.quote_expanded);
    if (!(expandedTag instanceof Boolean)) return false; // 不是引文 TextView
    Object full = tv.getTag(R.id.quote_full_text);
    Object lineCount = tv.getTag(R.id.quote_line_count);
    if (full == null || !(lineCount instanceof Integer)) return false;

    boolean expanded = (Boolean) expandedTag;
    if (expanded) {
      tv.setTag(R.id.quote_expanded, Boolean.FALSE);
      tv.setText(hint(tv, (Integer) lineCount));
    } else {
      tv.setTag(R.id.quote_expanded, Boolean.TRUE);
      tv.setText((CharSequence) full);
    }
    return true;
  }

  /** 判断这个 TextView 当前是不是折叠状态 */
  public static boolean isQuoteCollapsed(TextView tv) {
    return tv != null && Boolean.FALSE.equals(tv.getTag(R.id.quote_expanded));
  }

  /**
   * 通用：判断一次点击(rawX/rawY 为屏幕坐标)是否落在 root 下某个引文的三角上，命中则展开/收起。
   * 供父容器(RecyclerView/ScrollView)因自身 OnTouchListener 吞掉子 View 事件时调用。
   * @return 命中并处理返回 true，否则 false。
   */
  public static boolean handleTapQuote(View root, float rawX, float rawY) {
    if (root == null) return false;
    java.util.List<TextView> quotes = new java.util.ArrayList<>();
    findQuoteTextViews(root, quotes);
    for (TextView tv : quotes) {
      int[] loc = new int[2];
      tv.getLocationOnScreen(loc);
      float localX = rawX - loc[0] - tv.getPaddingLeft();
      float localY = rawY - loc[1] - tv.getPaddingTop();
      if (localY < 0 || localY > tv.getHeight()) continue;
      android.text.Layout layout = tv.getLayout();
      if (layout == null || localY >= layout.getHeight()) continue;
      try {
        int line = layout.getLineForVertical((int) localY);
        int charIndex = layout.getOffsetForHorizontal(line, Math.max(0, localX));
        if (charIndex <= 0) {
          return toggleQuote(tv);
        }
      } catch (IndexOutOfBoundsException e) {
        continue;
      }
    }
    return false;
  }

  /** 递归查找 view 树里所有引文 TextView（带 quote_expanded tag） */
  private static void findQuoteTextViews(View view, java.util.List<TextView> out) {
    if (view instanceof TextView && view.getTag(R.id.quote_expanded) instanceof Boolean) {
      out.add((TextView) view);
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        findQuoteTextViews(group.getChildAt(i), out);
      }
    }
  }

  /** 折叠提示：三角 ImageSpan + 灰色文字 */
  private static CharSequence hint(TextView tv, int lineCount) {
    SpannableString s = new SpannableString(TRIANGLE + " 展开引文（" + lineCount + " 行）");
    Drawable d = ContextCompat.getDrawable(tv.getContext(), R.drawable.ic_quote_expand);
    if (d != null) {
      int size = Math.round(tv.getTextSize() * 1.1f);
      d.setBounds(0, 0, size, size);
      s.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), 0, TRIANGLE.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    s.setSpan(new ForegroundColorSpan(QUOTE_HINT_COLOR), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return s;
  }

  private static int countQuoteLines(Spanned spanned) {
    String[] lines = spanned.toString().split("\n");
    int count = 0;
    for (String line : lines) {
      if (line.trim().length() > 0) count++;
    }
    return Math.max(1, count);
  }
}
