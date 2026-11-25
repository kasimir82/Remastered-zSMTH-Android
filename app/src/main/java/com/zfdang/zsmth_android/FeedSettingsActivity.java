package com.zfdang.zsmth_android;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.models.Board;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 信息流设置：从收藏夹挑选作为信息来源的版面（信息流已写死常开）。
 */
public class FeedSettingsActivity extends SMTHBaseActivity {
  private static final String TAG = "FeedSettings";

  private TextView mEnableSummary;
  private RecyclerView mRecyclerView;
  private TextView mEmptyHint;
  private View mLoading;

  private final List<Board> mBoards = new ArrayList<>();
  private BoardAdapter mAdapter;
  private volatile boolean mLoadingBoards = false;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_feed_settings);

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setTitle("信息流设置");
    }

    setupBottomNavigation();

    mEnableSummary = findViewById(R.id.feed_enable_summary);
    mLoading = findViewById(R.id.feed_loading);
    mEmptyHint = findViewById(R.id.feed_empty_hint);

    updateSummary();

    findViewById(R.id.feed_reload).setOnClickListener(v -> loadBoards());

    mRecyclerView = findViewById(R.id.feed_board_list);
    mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    mRecyclerView.addItemDecoration(new DividerItemDecoration(this, LinearLayoutManager.VERTICAL));
    mAdapter = new BoardAdapter();
    mRecyclerView.setAdapter(mAdapter);

    loadBoards();
  }

  private void updateSummary() {
    FeedConfig config = FeedConfig.getInstance();
    mEnableSummary.setText("信息流已开启，来源版面 " + config.getBoardCount() + " 个。刷新信息流时会汇总这些版面的新帖。");
  }

  @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void loadBoards() {
    if (mLoadingBoards) {
      return;
    }
    if (!SMTHApplication.isValidUser()) {
      Toast.makeText(this, "请先登录后再导入收藏夹版面", Toast.LENGTH_SHORT).show();
      updateEmptyState();
      return;
    }

    mLoadingBoards = true;
    mLoading.setVisibility(View.VISIBLE);
    mEmptyHint.setVisibility(View.GONE);

    new Thread(new Runnable() {
      @Override public void run() {
        final List<Board> boards = collectFavoriteBoards();
        runOnUiThread(new Runnable() {
          @Override public void run() {
            mLoadingBoards = false;
            mLoading.setVisibility(View.GONE);
            mBoards.clear();
            mBoards.addAll(boards);
            mAdapter.notifyDataSetChanged();
            updateEmptyState();
          }
        });
      }
    }).start();
  }

  private void updateEmptyState() {
    if (mBoards.isEmpty()) {
      mEmptyHint.setVisibility(View.VISIBLE);
    } else {
      mEmptyHint.setVisibility(View.GONE);
    }
  }

  /** 收藏夹可能有一层目录，这里把根层版面 + 目录里的一层版面都收进来 */
  private List<Board> collectFavoriteBoards() {
    List<Board> result = new ArrayList<>();
    Set<String> added = new HashSet<>();

    try {
      List<Board> roots = SMTHHelper.LoadFavoriteBoardsInFolder("");
      if (roots == null || roots.isEmpty()) {
        return result;
      }

      for (Board board : roots) {
        if (board != null && board.isBoard() && board.getBoardEngName() != null && added.add(board.getBoardEngName())) {
          result.add(board);
        }
      }

      for (Board board : roots) {
        if (board == null) {
          continue;
        }
        List<Board> children = null;
        try {
          if (board.isFolder()) {
            children = SMTHHelper.LoadFavoriteBoardsInFolder(board.getFolderID());
          } else if (board.isSection()) {
            children = SMTHHelper.LoadFavoriteBoardsInSection(board.getSectionID());
          }
        } catch (Exception e) {
          Log.e(TAG, "collectFavoriteBoards: " + Log.getStackTraceString(e));
        }
        if (children == null) {
          continue;
        }
        for (Board child : children) {
          if (child != null && child.isBoard() && child.getBoardEngName() != null && added.add(child.getBoardEngName())) {
            result.add(child);
          }
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "collectFavoriteBoards: " + Log.getStackTraceString(e));
    }

    Collections.sort(result, new Comparator<Board>() {
      @Override public int compare(Board lhs, Board rhs) {
        String a = lhs.getBoardChsName();
        String b = rhs.getBoardChsName();
        if (TextUtils.isEmpty(a)) {
          a = lhs.getBoardEngName();
        }
        if (TextUtils.isEmpty(b)) {
          b = rhs.getBoardEngName();
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
      }
    });
    return result;
  }

  private class BoardAdapter extends RecyclerView.Adapter<BoardAdapter.Holder> {

    class Holder extends RecyclerView.ViewHolder {
      final CheckBox check;
      final TextView chs;
      final TextView eng;

      Holder(View itemView) {
        super(itemView);
        check = itemView.findViewById(R.id.feed_board_check);
        chs = itemView.findViewById(R.id.feed_board_chs);
        eng = itemView.findViewById(R.id.feed_board_eng);
      }

      void bind(final Board board) {
        String chsName = board.getBoardChsName();
        chs.setText(TextUtils.isEmpty(chsName) ? board.getBoardEngName() : chsName);
        eng.setText(board.getBoardEngName());
        check.setChecked(FeedConfig.getInstance().contains(board.getBoardEngName()));

        itemView.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View v) {
            String engName = board.getBoardEngName();
            if (FeedConfig.getInstance().contains(engName)) {
              FeedConfig.getInstance().removeBoard(engName);
            } else {
              FeedConfig.getInstance().addBoard(board);
            }
            notifyItemChanged(getAdapterPosition());
            updateSummary();
          }
        });
      }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      View view = getLayoutInflater().inflate(R.layout.item_feed_board, parent, false);
      return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
      holder.bind(mBoards.get(position));
    }

    @Override public int getItemCount() {
      return mBoards.size();
    }
  }
}
