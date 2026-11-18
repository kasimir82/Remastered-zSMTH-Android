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

  /** 判断一个 item view 是否为「本贴结束」标记 */
  private boolean isEndMarker(View view) {
    return view != null
        && view.findViewById(com.zfdang.zsmth_android.R.id.post_list_end_text) != null;
  }

  /** 带动画滚到目标楼层，SNAP_TO_START 让它的顶部精确对齐 RecyclerView 顶部（标题栏下沿） */
  private void snapToTop(int position) {
    RecyclerView.SmoothScroller scroller =
        new androidx.recyclerview.widget.LinearSmoothScroller(recyclerView.getContext()) {
          @Override public int getVerticalSnapPreference() {
            return androidx.recyclerview.widget.LinearSmoothScroller.SNAP_TO_START;
          }
        };
    scroller.setTargetPosition(position);
    recyclerView.getLayoutManager().startSmoothScroll(scroller);
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
