package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** 简单的自动换行容器，用于「大爆炸」词条布局 */
public class FlowLayout extends ViewGroup {
  public FlowLayout(Context context) {
    super(context);
  }

  public FlowLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int paddingL = getPaddingLeft(), paddingR = getPaddingRight();
    int paddingT = getPaddingTop(), paddingB = getPaddingBottom();
    int available = width - paddingL - paddingR;
    int lineWidth = 0, lineHeight = 0, totalHeight = 0;
    int gap = 6;

    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      measureChild(child, widthMeasureSpec, heightMeasureSpec);
      int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
      if (lineWidth > 0 && lineWidth + cw + gap > available) {
        totalHeight += lineHeight;
        lineWidth = 0;
        lineHeight = 0;
      }
      lineWidth += cw + gap;
      lineHeight = Math.max(lineHeight, ch);
    }
    totalHeight += lineHeight;
    setMeasuredDimension(width, totalHeight + paddingT + paddingB);
  }

  @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
    int paddingL = getPaddingLeft(), paddingT = getPaddingTop();
    int available = getWidth() - paddingL - getPaddingRight();
    int x = paddingL, y = paddingT, lineHeight = 0;
    int gap = 6;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
      if (x > paddingL && x + cw + gap > paddingL + available) {
        x = paddingL;
        y += lineHeight;
        lineHeight = 0;
      }
      child.layout(x, y, x + cw, y + ch);
      x += cw + gap;
      lineHeight = Math.max(lineHeight, ch);
    }
  }
}
