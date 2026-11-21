package com.zfdang.zsmth_android;

import android.content.Context;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * This gesturelistener is for PostListActivity
 * Created by zfdang on 2016-5-5.
 */
public class RecyclerViewGestureListener extends GestureDetector.SimpleOnGestureListener {

  public interface OnItemLongClickListener {
    boolean onItemLongClicked(int position, View v);

    /** 长按落在帖内图片上时调用，交给宿主弹「查看大图 / 分享」菜单 */
    default boolean onImageLongClicked(int position, int imageIndex) {
      return false;
    }

    /** 点击「本贴结束」空白区域，交给宿主返回上一界面 */
    default boolean onEndMarkerClicked() {
      return false;
    }
  }

  private OnItemLongClickListener mListener;
  private RecyclerView recyclerView;
  private int mScreenHeight;

  public RecyclerViewGestureListener() {
  }

  public RecyclerViewGestureListener(OnItemLongClickListener listener, RecyclerView recyclerView) {
    this.mListener = listener;
    this.recyclerView = recyclerView;

    WindowManager wm = (WindowManager) this.recyclerView.getContext().getSystemService(Context.WINDOW_SERVICE);
    mScreenHeight = wm.getDefaultDisplay().getHeight();
  }

  private static final String TAG_TURN = "PageTurn";

  @Override public boolean onSingleTapUp(MotionEvent e) {
    int touchY = (int) e.getRawY();
    android.util.Log.d(TAG_TURN, "TAP x=" + (int) e.getRawX() + " y=" + touchY
        + " screenH=" + mScreenHeight + " ratio=" + String.format("%.2f", touchY * 1.0f / mScreenHeight));
    // 先判断是否点在引文的三角上：点是则展开/收起引文，不再翻页。
    // 引文 TextView 的触摸被 RecyclerView 拦截，所以统一在这里按坐标判断。
    if (handleQuoteTriangleTap(e)) {
      android.util.Log.d(TAG_TURN, "  -> HIT quote triangle, return (no page turn)");
      return true;
    }
    // 点在「本贴结束」空白区域 → 返回上一界面（仅末楼已顶到标题时）
    if (handleEndMarkerTap(e)) {
      vibrateOnPageTurn(); // 返回也震一下，与翻页反馈一致
      android.util.Log.d(TAG_TURN, "  -> HIT end marker, return to list + vibrate");
      return true;
    }
    if (touchY < mScreenHeight * 0.40) {
      android.util.Log.d(TAG_TURN, "  -> PAGE UP (top zone)");
      vibrateOnPageTurn();
      scrollToAdjacentPost(false);
      return true;
    } else if (touchY > mScreenHeight * 0.60) {
      android.util.Log.d(TAG_TURN, "  -> PAGE DOWN (bottom zone)");
      vibrateOnPageTurn();
      scrollToAdjacentPost(true);
      return true;
    }
    android.util.Log.d(TAG_TURN, "  -> MIDDLE ZONE, no action (super)");
    return super.onSingleTapUp(e);
  }

  /** 翻页时按用户设置震动一下(开关关闭或时长<=0则不震) */
  private void vibrateOnPageTurn() {
    try {
      com.zfdang.zsmth_android.Settings st = com.zfdang.zsmth_android.Settings.getInstance();
      if (st == null || !st.isPageTurnVibrate() || st.getPageTurnVibrateMs() <= 0) return;
      android.os.Vibrator v = (android.os.Vibrator) recyclerView.getContext()
          .getSystemService(android.content.Context.VIBRATOR_SERVICE);
      if (v == null || !v.hasVibrator()) return;
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        v.vibrate(android.os.VibrationEffect.createOneShot(st.getPageTurnVibrateMs(),
            android.os.VibrationEffect.DEFAULT_AMPLITUDE));
      } else {
        v.vibrate(st.getPageTurnVibrateMs());
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * 点屏幕下半翻到下一楼：
   * - 若下一楼顶部已经在视口内（短帖子，一眼能看到下一楼的开头）→ 精确对齐到视口上沿；
   * - 若下一楼还没露头（当前楼很长/带大图）→ 正常滚一屏，反复点直到它露头，再点就对齐。
   *
   * 上半屏往上翻是对称逻辑。
   */
  public void scrollToAdjacentPost(boolean next) {
    if (recyclerView == null || !(recyclerView.getLayoutManager() instanceof LinearLayoutManager)) {
      android.util.Log.d(TAG_TURN, "scroll skip: recyclerView null or not LinearLayoutManager");
      return;
    }
    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
    int itemCount = lm.getItemCount();
    if (itemCount == 0) {
      android.util.Log.d(TAG_TURN, "scroll skip: itemCount=0");
      return;
    }

    int first = lm.findFirstVisibleItemPosition();
    if (first == RecyclerView.NO_POSITION) {
      android.util.Log.d(TAG_TURN, "scroll skip: first=NO_POSITION");
      return;
    }

    int viewportHeight = recyclerView.getHeight();
    android.util.Log.d(TAG_TURN, "scroll " + (next ? "NEXT" : "PREV") + ": itemCount=" + itemCount
        + " first=" + first + " firstTop=" + firstTop(lm, first)
        + " lastVisible=" + lm.findLastVisibleItemPosition() + " viewportH=" + viewportHeight);
    if (next) {
      // 以「顶部最贴近视口上沿的楼」为基准翻下一楼，而不是 findFirstVisibleItemPosition+1。
      // 原因：高楼对齐到顶后，前一楼的尾巴仍露在视口顶部，first 会一直停在前一楼，
      // 导致 target 永远相同、反复 snap 到同一位置卡住。
      int lastVisible = lm.findLastVisibleItemPosition();
      int anchor = first;
      int bestTop = Integer.MAX_VALUE;
      for (int p = first; p <= lastVisible && p < itemCount; p++) {
        View pv = lm.findViewByPosition(p);
        if (pv == null || isEndMarker(pv)) continue;
        int t = Math.abs(pv.getTop());
        if (t < bestTop) { bestTop = t; anchor = p; }
      }
      int target = anchor + 1;
      android.util.Log.d(TAG_TURN, "  NEXT anchor=" + anchor + " (topOffset=" + bestTop
          + ") target=" + target + " endMarkerPos=" + (itemCount - 1));
      if (target >= itemCount) {
        android.util.Log.d(TAG_TURN, "  NEXT target>=itemCount, snapToTop(anchor=" + anchor + ")");
        snapToTop(anchor);
        return;
      }

      // 无条件把 EndMarker 高度写死为视口高(不再用 视口-末楼高 动态算)：
      // 动态算依赖末楼高度测量，图片加载前后 lastH 会变，导致偶发倒数第二楼对不齐。
      // 写死视口高后列表底部空间恒定充足，任何楼都能顶到视口上沿。
      int endMarkerPos = itemCount - 1;
      View endView = lm.findViewByPosition(endMarkerPos);
      if (endView != null && isEndMarker(endView)) {
        int viewport = recyclerView.getHeight() - recyclerView.getPaddingTop()
            - recyclerView.getPaddingBottom();
        if (viewport > 0) {
          int markerH = viewport * 7 / 10; // 固定视口高的70%
          RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) endView.getLayoutParams();
          android.util.Log.d(TAG_TURN, "  END-MARKER fix: viewport=" + viewport
              + " markerH=" + markerH + " oldH=" + (lp == null ? "null" : lp.height));
          if (lp != null && lp.height != markerH) {
            lp.height = markerH;
            endView.setLayoutParams(lp);
          }
        }
      }

      View nextView = lm.findViewByPosition(target);
      int nextTop = nextView == null ? Integer.MIN_VALUE : nextView.getTop();
      android.util.Log.d(TAG_TURN, "  NEXT decision: nextView=" + (nextView == null ? "null" : "pos" + target)
          + " nextTop=" + nextTop + " viewportH=" + viewportHeight
          + " -> " + (nextView != null && nextTop < viewportHeight ? "snapToTop" : "smoothScroll 0.8屏"));
      if (nextView != null && nextTop < viewportHeight) {
        // 下一楼已经露头 → 对齐它的顶部到视口上沿
        snapToTop(target);
      } else {
        // 下一楼还没出现 → 正常滚一屏，直到它露头
        recyclerView.smoothScrollBy(0, (int) (viewportHeight * 0.8));
      }
    } else {
      View firstView = lm.findViewByPosition(first);
      int top = firstView == null ? 0 : firstView.getTop();
      int target;
      if (top > viewportHeight / 3) {
        // 当前楼顶部还离视口上沿很远，先回到本楼顶部
        target = first;
        android.util.Log.d(TAG_TURN, "  PREV decision: top=" + top + " > 1/3屏, snapToTop(first=" + target + ")");
        snapToTop(target);
      } else if (first - 1 >= 0) {
        target = first - 1;
        View prevView = lm.findViewByPosition(target);
        int prevBottom = prevView == null ? Integer.MIN_VALUE : prevView.getBottom();
        android.util.Log.d(TAG_TURN, "  PREV decision: target=" + target + " prevBottom=" + prevBottom
            + " -> " + (prevView != null && prevBottom > 0 ? "snapToTop" : "smoothScroll -0.8屏"));
        if (prevView != null && prevBottom > 0) {
          // 上一楼底部还在视口内 → 对齐上一楼顶部
          snapToTop(target);
        } else {
          recyclerView.smoothScrollBy(0, -(int) (viewportHeight * 0.8));
        }
      } else {
        android.util.Log.d(TAG_TURN, "  PREV decision: first=0, at top, no action");
      }
    }
  }

  /**
   * 点击是否落在引文行的三角上。遍历可见的引文 TextView，把点击坐标转成 view 局部坐标，
   * 判断 x 是否落在开头三角字符范围内、y 是否落在该行。命中则触发展开/收起并返回 true。
   */
  private boolean handleQuoteTriangleTap(MotionEvent e) {
    // 复用 QuoteTextHelper 的通用判断，邮件页也用同一套逻辑
    boolean hit = com.zfdang.zsmth_android.helpers.QuoteTextHelper.handleTapQuote(
        recyclerView, e.getRawX(), e.getRawY());
    if (hit) android.util.Log.d(TAG_TURN, "quoteTap: HIT at y=" + (int) e.getRawY());
    return hit;
  }

  /** 取某个 position 的 view 顶部相对 recyclerView 的偏移，仅用于日志 */
  private static int firstTop(LinearLayoutManager lm, int pos) {
    View v = lm.findViewByPosition(pos);
    return v == null ? Integer.MIN_VALUE : v.getTop();
  }

  /**
   * 点「本贴结束」空白区域返回，但仅当「最后一楼已经顶到标题（滚到头）」时才返回。
   * 末楼还没顶到顶时点空白，交给后面的翻页逻辑把末楼顶上去（不返回）。
   */
  private boolean handleEndMarkerTap(MotionEvent e) {
    if (recyclerView == null || mListener == null) {
      android.util.Log.d(TAG_TURN, "endTap skip: rv/listener null");
      return false;
    }
    RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
    if (!(lm instanceof LinearLayoutManager)) return false;
    int itemCount = lm.getItemCount();
    if (itemCount < 2) return false;

    // 前提：最后一楼（结束标记的前一项）顶部已对齐视口上沿 = 滚到头了
    View lastPost = lm.findViewByPosition(itemCount - 2);
    if (lastPost == null) {
      android.util.Log.d(TAG_TURN, "endTap: lastPost(pos=" + (itemCount-2) + ") NULL, itemCount=" + itemCount);
      return false;
    }
    int tolerance = Math.round(8 * recyclerView.getResources().getDisplayMetrics().density);
    int lastTop = lastPost.getTop();
    int padTop = recyclerView.getPaddingTop();
    android.util.Log.d(TAG_TURN, "endTap: itemCount=" + itemCount + " lastPostTop=" + lastTop
        + " padTop=" + padTop + " tol=" + tolerance + " rawY=" + (int) e.getRawY());
    if (lastTop > padTop + tolerance) {
      android.util.Log.d(TAG_TURN, "endTap: REJECT (last not pinned)");
      return false; // 末楼还没顶到标题，本次点击不返回，交给翻页把末楼顶上去
    }

    int[] loc = new int[2];
    recyclerView.getLocationOnScreen(loc);
    float localX = e.getRawX() - loc[0];
    float localY = e.getRawY() - loc[1];
    View v = recyclerView.findChildViewUnder(localX, localY);
    boolean isEnd = v != null && isEndMarker(v);
    android.util.Log.d(TAG_TURN, "endTap: localX=" + (int) localX + " localY=" + (int) localY
        + " viewUnder=" + (v == null ? "null" : (isEnd ? "END_MARKER" : "other")));
    if (isEnd) {
      return mListener.onEndMarkerClicked();
    }
    return false;
  }

  /** 判断一个 item view 是否为「本贴结束」标记 */
  private boolean isEndMarker(View view) {
    return view != null
        && view.findViewById(com.zfdang.zsmth_android.R.id.post_list_end_text) != null;
  }

  /**
   * 滚到目标楼层并让它的顶部精确对齐视口上沿（标题栏下沿）。
   *
   * 用 scrollToPositionWithOffset 直接定位，不用 LinearSmoothScroller 的 SNAP_TO_START：
   * 当目标楼层是超高楼层(如单楼长帖，高度好几屏)、且当前已滚到列表底部时，
   * smooth scroller 计算的大跨度滚动会被列表边界限制而失效，点屏幕没反应。
   * scrollToPositionWithOffset 精确定位到「该 item 顶部 = 视口顶部」，不受边界影响。
   */
  private void snapToTop(int position) {
    android.util.Log.d(TAG_TURN, "  >> snapToTop(" + position + ")");
    if (recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
      ((LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(position, 0);
    }
  }

  @Override public void onLongPress(MotionEvent e) {
    float x = e.getRawX();
    float y = e.getRawY();
    int[] location = new int[2];
    recyclerView.getLocationOnScreen(location);

    float localX = x - location[0];
    float localY = y - location[1];
    View targetView = recyclerView.findChildViewUnder(localX, localY);
    int position = recyclerView.getChildAdapterPosition(targetView);

    // 触点是否落在帖内图片上（图片带 image_tag）。是则弹图片菜单，否则弹楼层菜单。
    View imageUnderFinger = targetView == null ? null : findViewWithTagUnder(targetView, localX, localY, location);
    if (imageUnderFinger != null && mListener != null) {
      Object tag = imageUnderFinger.getTag(com.zfdang.zsmth_android.R.id.image_tag);
      if (tag instanceof Integer) {
        mListener.onImageLongClicked(position, (Integer) tag);
        return;
      }
    }

    //        Log.d("Gesture", "onLongPress: " + String.format("position = %d", position));
    if (mListener != null) {
      mListener.onItemLongClicked(position, targetView);
    }

    super.onLongPress(e);
  }

  /** 在楼层 view 里递归查找触点下带 image_tag 的子 view；x/y 是相对 recyclerView 的坐标 */
  private View findViewWithTagUnder(View view, float x, float y, int[] rvLocation) {
    if (view.getTag(com.zfdang.zsmth_android.R.id.image_tag) != null) {
      return view;
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      // 从最上层子 view 开始（后绘制的在上），命中即返回
      for (int i = group.getChildCount() - 1; i >= 0; i--) {
        View child = group.getChildAt(i);
        if (child.getVisibility() != View.VISIBLE) {
          continue;
        }
        int[] loc = new int[2];
        child.getLocationOnScreen(loc);
        float cx = loc[0] - rvLocation[0];
        float cy = loc[1] - rvLocation[1];
        if (x >= cx && x <= cx + child.getWidth() && y >= cy && y <= cy + child.getHeight()) {
          View found = findViewWithTagUnder(child, x, y, rvLocation);
          if (found != null) {
            return found;
          }
        }
      }
    }
    return null;
  }
}
