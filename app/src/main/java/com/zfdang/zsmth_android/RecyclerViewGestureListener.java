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

  @Override public boolean onSingleTapUp(MotionEvent e) {
    // 先判断是否点在引文的三角上：点是则展开/收起引文，不再翻页。
    // 引文 TextView 的触摸被 RecyclerView 拦截，所以统一在这里按坐标判断。
    if (handleQuoteTriangleTap(e)) {
      return true;
    }
    // 点在「本贴结束」空白区域 → 返回上一界面（仅末楼已顶到标题时）
    if (handleEndMarkerTap(e)) {
      return true;
    }
    int touchY = (int) e.getRawY();
    //        Log.d("Gesture", "onSingleTapUp: " + String.format("%d / %d = %f", touchY, mScreenHeight, touchY * 1.0 / mScreenHeight));
    if (touchY < mScreenHeight * 0.40) {
      scrollToAdjacentPost(false);
      return true;
    } else if (touchY > mScreenHeight * 0.60) {
      scrollToAdjacentPost(true);
      return true;
    }

    return super.onSingleTapUp(e);
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
      return;
    }
    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
    int itemCount = lm.getItemCount();
    if (itemCount == 0) {
      return;
    }

    int first = lm.findFirstVisibleItemPosition();
    if (first == RecyclerView.NO_POSITION) {
      return;
    }

    int viewportHeight = recyclerView.getHeight();
    if (next) {
      // 先看视口里是否已经露出「本贴结束」标记：任何位置看到它，都收敛到「最后一楼顶对齐」，
      // 不做这个判断的话，倒数第二楼露着尾巴时 first 会指到它，把错的楼顶到上面。
      int lastVisible = lm.findLastVisibleItemPosition();
      for (int p = first; p <= lastVisible && p < itemCount; p++) {
        View v = lm.findViewByPosition(p);
        if (v != null && isEndMarker(v)) {
          int lastPost = p - 1; // 结束标记的前一项 = 真正的最后一楼
          if (lastPost >= 0) {
            snapToTop(lastPost);
          }
          return;
        }
      }

      int target = first + 1;
      if (target >= itemCount) {
        snapToTop(first);
        return;
      }
      View nextView = lm.findViewByPosition(target);
      if (nextView != null && nextView.getTop() < viewportHeight) {
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
        snapToTop(target);
      } else if (first - 1 >= 0) {
        target = first - 1;
        View prevView = lm.findViewByPosition(target);
        if (prevView != null && prevView.getBottom() > 0) {
          // 上一楼底部还在视口内 → 对齐上一楼顶部
          snapToTop(target);
        } else {
          recyclerView.smoothScrollBy(0, -(int) (viewportHeight * 0.8));
        }
      }
    }
  }

  /**
   * 点击是否落在引文行的三角上。遍历可见的引文 TextView，把点击坐标转成 view 局部坐标，
   * 判断 x 是否落在开头三角字符范围内、y 是否落在该行。命中则触发展开/收起并返回 true。
   */
  private boolean handleQuoteTriangleTap(MotionEvent e) {
    // 复用 QuoteTextHelper 的通用判断，邮件页也用同一套逻辑
    return com.zfdang.zsmth_android.helpers.QuoteTextHelper.handleTapQuote(
        recyclerView, e.getRawX(), e.getRawY());
  }

  /**
   * 点「本贴结束」空白区域返回，但仅当「最后一楼已经顶到标题（滚到头）」时才返回。
   * 末楼还没顶到顶时点空白，交给后面的翻页逻辑把末楼顶上去（不返回）。
   */
  private boolean handleEndMarkerTap(MotionEvent e) {
    if (recyclerView == null || mListener == null) return false;
    RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
    if (!(lm instanceof LinearLayoutManager)) return false;
    int itemCount = lm.getItemCount();
    if (itemCount < 2) return false;

    // 前提：最后一楼（结束标记的前一项）顶部已对齐视口上沿 = 滚到头了
    View lastPost = lm.findViewByPosition(itemCount - 2);
    if (lastPost == null) return false;
    int tolerance = Math.round(8 * recyclerView.getResources().getDisplayMetrics().density);
    if (lastPost.getTop() > recyclerView.getPaddingTop() + tolerance) {
      return false; // 末楼还没顶到标题，本次点击不返回，交给翻页把末楼顶上去
    }

    int[] loc = new int[2];
    recyclerView.getLocationOnScreen(loc);
    float localX = e.getRawX() - loc[0];
    float localY = e.getRawY() - loc[1];
    View v = recyclerView.findChildViewUnder(localX, localY);
    if (v != null && isEndMarker(v)) {
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
