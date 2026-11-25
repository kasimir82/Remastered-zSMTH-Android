package com.zfdang.zsmth_android;

import androidx.recyclerview.widget.RecyclerView;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;
import com.zfdang.zsmth_android.listeners.OnBoardFragmentInteractionListener;
import com.zfdang.zsmth_android.models.Board;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link RecyclerView.Adapter} that can display a {@link Board} and makes a call to the
 * specified {@link OnBoardFragmentInteractionListener}.
 */
public class BoardRecyclerViewAdapter extends RecyclerView.Adapter<BoardRecyclerViewAdapter.ViewHolder> implements Filterable {

  private final List<Board> mBoards;
  private final OnBoardFragmentInteractionListener mListener;
  private BoardFilter mFilter = null;

  /** 拖拽排序回调：仅收藏夹页设置，其它页(全部版面)为 null 不启用拖拽 */
  public interface OnBoardDragListener {
    void onBoardMoved(int fromPosition, int toPosition);
  }
  private OnBoardDragListener mDragListener;
  // 拖拽模式(收藏夹页)：长按交给 ItemTouchHelper 做排序，禁用原来的"长按删除"回调
  private boolean mDragMode = false;

  private androidx.recyclerview.widget.ItemTouchHelper mItemTouchHelper;

  public void setDragMode(boolean enabled) {
    mDragMode = enabled;
  }

  /** 由 Fragment 注入 ItemTouchHelper，把手按下时调用 startDrag 触发拖动 */
  public void setItemTouchHelper(androidx.recyclerview.widget.ItemTouchHelper helper) {
    mItemTouchHelper = helper;
  }

  /** 供 ItemTouchHelper 拖动结束后交换数据源中的元素 */
  public void moveItem(int from, int to) {
    if (from < 0 || to < 0 || from >= mBoards.size() || to >= mBoards.size()) return;
    Board item = mBoards.remove(from);
    mBoards.add(to, item);
    notifyItemMoved(from, to);
  }

  public BoardRecyclerViewAdapter(List<Board> items, OnBoardFragmentInteractionListener listener) {
    mBoards = items;
    mListener = listener;
  }

  @Override public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.board_item, parent, false);
    return new ViewHolder(view);
  }

  @Override public void onBindViewHolder(final ViewHolder holder, int position) {
    holder.mItem = mBoards.get(position);
    Board board = holder.mItem;

    if (board.isFolder()) {
      holder.mCategoryView.setText("[" + board.getCategoryName() + "]");
      holder.mModeratorView.setVisibility(View.GONE);
      holder.mNameView.setText(board.getFolderName());
    } else if (board.isSection()) {
      holder.mCategoryView.setText("[" + board.getCategoryName() + "]");
      holder.mModeratorView.setVisibility(View.GONE);
      holder.mNameView.setText(board.getSectionName());
    } else if(board.isBoard()){
      holder.mCategoryView.setText("[" + board.getCategoryName() + "]");
      holder.mModeratorView.setVisibility(View.VISIBLE);
      holder.mModeratorView.setText(board.getModerator());
      holder.mNameView.setText(board.getBoardName());
    } else if(board.isInvalid()) {
      holder.mCategoryView.setText("[" + board.getCategoryName() + "]");
      holder.mModeratorView.setVisibility(View.GONE);
      holder.mNameView.setText(board.getBoardName());
    }

    holder.mView.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        if (null != mListener) {
          // Notify the active callbacks interface (the activity, if the
          // fragment is attached to one) that an item has been selected.
          mListener.onBoardFragmentInteraction(holder.mItem);
        }
      }
    });

    if (mDragMode) {
      // 收藏夹页：去掉红色X按钮，整行长按按x坐标分流
      //   左80% → 删除(原长按逻辑)；右20% → 拖动排序
      holder.mDeleteBtn.setVisibility(View.GONE);
      holder.mDeleteBtn.setOnClickListener(null);

      // 右侧把手仅作视觉提示，不再单独拦截触摸（让整行长按统一处理）
      holder.mDragHandle.setVisibility(View.VISIBLE);
      holder.mDragHandle.setOnTouchListener(null);

      final int[] downX = {0};
      holder.mView.setOnTouchListener((v, event) -> {
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
          downX[0] = (int) event.getX(); // 相对于整行的x坐标
        }
        return false; // 不消费，保证长按检测正常触发
      });
      holder.mView.setOnLongClickListener(v -> {
        int w = v.getWidth();
        if (downX[0] < w * 0.8) {
          // 左侧80%：删除
          if (mListener != null) mListener.onBoardLongClick(holder.mItem);
        } else {
          // 右侧20%：拖动排序
          if (mItemTouchHelper != null) mItemTouchHelper.startDrag(holder);
        }
        return true; // 消费长按
      });
    } else {
      // 全部版面页：无删除/把手，保留原长按(添加收藏)
      holder.mDeleteBtn.setVisibility(View.GONE);
      holder.mDragHandle.setVisibility(View.GONE);
      holder.mView.setOnLongClickListener(new View.OnLongClickListener() {
        @Override public boolean onLongClick(View v) {
          if (null != mListener) mListener.onBoardLongClick(holder.mItem);
          return false;
        }
      });
    }
  }

  @Override public int getItemCount() {
    return mBoards.size();
  }

  @Override public Filter getFilter() {
    // 每次过滤都基于全量数据源ALL_BOARDS重建filter，不要用mBoards(可能已是上次过滤结果)，
    // 也不要缓存mFilter(其originalList是构造时的快照，数据异步加载后会过时)。
    mFilter = new BoardFilter(this, com.zfdang.zsmth_android.models.BoardListContent.ALL_BOARDS);
    return mFilter;
  }

  // https://stackoverflow.com/questions/29792187/add-a-search-filter-on-recyclerview-with-cards/29792313#29792313
  private static class BoardFilter extends Filter {
    private final BoardRecyclerViewAdapter adapter;
    private final List<Board> originalList;
    private final List<Board> filteredList;

    private BoardFilter(BoardRecyclerViewAdapter adapter, List<Board> originalList) {
      super();
      this.adapter = adapter;
      this.originalList = new LinkedList<>(originalList);
      this.filteredList = new ArrayList<>();
    }

    @Override protected FilterResults performFiltering(CharSequence constraint) {
      filteredList.clear();
      final FilterResults results = new FilterResults();

      if (constraint.length() == 0) {
        filteredList.addAll(originalList);
      } else {
        final String filterPattern = constraint.toString().trim();

        for (final Board board : originalList) {
          // this matching method can be further improved
          String boardLabel = String.format("%s-%s-%s", board.getBoardChsName(), board.getBoardEngName(), board.getCategoryName());
          if (Pattern.compile(Pattern.quote(filterPattern), Pattern.CASE_INSENSITIVE).matcher(boardLabel).find()) {
            filteredList.add(board);
          }
        }
      }
      results.values = filteredList;
      results.count = filteredList.size();
      return results;
    }

    @Override protected void publishResults(CharSequence constraint, FilterResults results) {
      adapter.mBoards.clear();
      adapter.mBoards.addAll((ArrayList<Board>) results.values);
      adapter.notifyDataSetChanged();
    }
  }

  public class ViewHolder extends RecyclerView.ViewHolder {
    public Board mItem;
    public final View mView;
    public final TextView mCategoryView;
    public final TextView mModeratorView;
    public final TextView mNameView;
    public final View mDragHandle;
    public final View mDeleteBtn;

    public ViewHolder(View view) {
      super(view);
      mView = view;
      mCategoryView = (TextView) view.findViewById(R.id.CategoryName);
      mModeratorView = (TextView) view.findViewById(R.id.ModeratorID);
      mNameView = (TextView) view.findViewById(R.id.BoardName);
      mDragHandle = view.findViewById(R.id.board_drag_handle);
      mDeleteBtn = view.findViewById(R.id.board_delete_btn);
    }

    @Override public String toString() {
      return mNameView.getText().toString();
    }
  }
}
