package com.zfdang.zsmth_android.helpers;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.zfdang.zsmth_android.BoardRecyclerViewAdapter;

/**
 * 收藏夹栏拖拽排序：通过 item 右侧的拖拽把手(而非长按，长按仍是删除版面)触发拖动。
 */
public final class BoardDragHelper {
  private BoardDragHelper() {}

  public static ItemTouchHelper create(final BoardRecyclerViewAdapter adapter,
      final Runnable onDragFinished) {
    ItemTouchHelper.Callback callback = new ItemTouchHelper.Callback() {
      @Override public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder viewHolder) {
        // 只允许上下拖动，不支持左右滑动
        return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
      }

      @Override public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
        adapter.moveItem(from.getAdapterPosition(), to.getAdapterPosition());
        return true;
      }

      @Override public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
      }

      @Override public boolean isLongPressDragEnabled() {
        // 关闭长按拖动：长按仍是原来的"删除版面"功能，拖动只由把手触发
        return false;
      }

      @Override public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
        super.onSelectedChanged(viewHolder, actionState);
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
          viewHolder.itemView.setAlpha(0.7f);
        }
      }

      @Override public void clearView(RecyclerView rv, RecyclerView.ViewHolder viewHolder) {
        super.clearView(rv, viewHolder);
        viewHolder.itemView.setAlpha(1.0f);
        if (onDragFinished != null) onDragFinished.run();
      }
    };
    return new ItemTouchHelper(callback);
  }
}
