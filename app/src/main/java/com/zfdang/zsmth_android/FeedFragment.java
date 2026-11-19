package com.zfdang.zsmth_android;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.scwang.smart.refresh.layout.SmartRefreshLayout;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.scwang.smart.refresh.layout.listener.OnRefreshListener;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.helpers.BlockListManager;
import com.zfdang.zsmth_android.helpers.ReadProgressManager;
import com.zfdang.zsmth_android.helpers.StringUtils;
import com.zfdang.zsmth_android.models.Board;
import com.zfdang.zsmth_android.models.Topic;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ResponseBody;

/**
 * 信息流：把若干个收藏版面第一页的帖子合并到一起，按发布时间倒序展示。
 *
 * 抓取结果放在内存里，切页面回来不会重复请求；只有下拉刷新、改了来源版面、
 * 或超过 {@link #CACHE_TTL} 才会重新抓。
 */
public class FeedFragment extends Fragment {
  private static final String TAG = "FeedFragment";

  /** 内存缓存：切换标签页时不重复请求 */
  private static final List<Topic> FEED_TOPICS = new ArrayList<>();
  private static long sLastLoadTime = 0L;
  private static String sCachedSignature = "";
  private static final long CACHE_TTL = 10 * 60 * 1000L;
  /** 同时请求的版面数量，别把水木打挂了 */
  private static final int MAX_CONCURRENCY = 4;
  /** 单个版面智能预读的页数上限 */
  private static final int FEED_SMART_LOAD_MAX_PAGES = 50;
  /** 智能预读的时间范围：最近 N 天（今天=0），超出即停止加载 */
  private static final int FEED_SMART_LOAD_DAYS = 2;

  private SmartRefreshLayout mRefreshLayout;
  private RecyclerView mRecyclerView;
  private TextView mEmptyHint;
  private FeedAdapter mAdapter;
  private boolean mLoading = false;
  private String mDisplayedSignature = "";
  /** 当前列表实际使用的排序方式，用于判断缓存是否还能直接复用 */
  private int mAppliedSortMode = -1;

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setHasOptionsMenu(true);
  }

  @Override public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
    super.onCreateOptionsMenu(menu, inflater);
    inflater.inflate(R.menu.feed_menu, menu);
  }

  @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.feed_action_sort) {
      showSortDialog();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void showSortDialog() {
    final String[] modes = { "最后回复", "发表时间" };
    int checked = Settings.getInstance().getFeedSortMode();
    if (checked < 0 || checked >= modes.length) {
      checked = Settings.SORT_BY_PUBLISH_TIME;
    }

    new AlertDialog.Builder(getActivity())
        .setTitle("排序方式")
        .setSingleChoiceItems(modes, checked, new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();
            if (which == Settings.getInstance().getFeedSortMode()) {
              return;
            }
            Settings.getInstance().setFeedSortMode(which);
            sortTopics(FEED_TOPICS);
            mAppliedSortMode = which;
            if (mAdapter != null) {
              mAdapter.notifyDataSetChanged();
            }
            if (mRecyclerView != null) {
              mRecyclerView.scrollToPosition(0);
            }
          }
        })
        .setNegativeButton("取消", null)
        .show();
  }

  /** 按当前选择的排序方式重排，最后回复用回复时间，发表时间用发布时间 */
  private void sortTopics(List<Topic> topics) {
    final int mode = Settings.getInstance().getFeedSortMode();
    Collections.sort(topics, new Comparator<Topic>() {
      @Override public int compare(Topic lhs, Topic rhs) {
        if (mode == Settings.SORT_BY_LAST_REPLY) {
          return Long.compare(rhs.getReplySortKey(), lhs.getReplySortKey());
        }
        return Long.compare(rhs.getPublishSortKey(), lhs.getPublishSortKey());
      }
    });
    mAppliedSortMode = mode;
  }

  @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View rootView = inflater.inflate(R.layout.fragment_feed, container, false);

    mRefreshLayout = rootView.findViewById(R.id.feed_refresh_layout);
    mRefreshLayout.setEnableLoadMore(false);
    mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
      @Override public void onRefresh(RefreshLayout refreshLayout) {
        loadFeed(true);
      }
    });

    mEmptyHint = rootView.findViewById(R.id.feed_topic_empty);
    mRecyclerView = rootView.findViewById(R.id.feed_recycler_view);
    mRecyclerView.addItemDecoration(new DividerItemDecoration(getActivity(), LinearLayoutManager.VERTICAL, 0));
    mRecyclerView.setLayoutManager(new WrapContentLinearLayoutManager(getActivity()));
    mAdapter = new FeedAdapter();
    mRecyclerView.setAdapter(mAdapter);

    return rootView;
  }

  @Override public void onResume() {
    super.onResume();
    if (!FeedConfig.getInstance().isEnabled()) {
      return;
    }
    loadFeed(false);
  }

  /** 标题栏的刷新按钮调用 */
  public void refreshFromMenu() {
    // 主动显示刷新头，和手动下拉的反馈一致（autoRefresh 会回调 OnRefreshListener）。
    // 若当前页面不在信息流状态（比如没在刷新布局里），直接强制重抓兜底。
    if (mRefreshLayout != null && !mRefreshLayout.isRefreshing()) {
      mRefreshLayout.autoRefresh();
    } else {
      loadFeed(true);
    }
  }

  private String signatureOf(List<Board> boards) {
    StringBuilder builder = new StringBuilder();
    for (Board board : boards) {
      builder.append(board.getBoardEngName()).append(',');
    }
    return builder.toString();
  }

  private void finishRefresh() {
    if (mRefreshLayout != null && mRefreshLayout.isRefreshing()) {
      mRefreshLayout.finishRefresh();
    }
  }

  private void loadFeed(boolean force) {
    if (mLoading) {
      finishRefresh();
      return;
    }

    final List<Board> boards = FeedConfig.getInstance().getBoards();
    final String signature = signatureOf(boards);

    if (boards.isEmpty()) {
      FEED_TOPICS.clear();
      sCachedSignature = signature;
      mDisplayedSignature = signature;
      if (mAdapter != null) {
        mAdapter.notifyDataSetChanged();
      }
      showEmpty("还没有选择信息来源\n请到「我的 → 设置 → 信息流设置」里勾选要汇总的版面");
      finishRefresh();
      return;
    }

    boolean cacheValid = !force && signature.equals(sCachedSignature) && !FEED_TOPICS.isEmpty()
        && System.currentTimeMillis() - sLastLoadTime < CACHE_TTL;
    if (cacheValid) {
      // 排序方式可能刚改过，缓存里的顺序要跟着变
      if (mAppliedSortMode != Settings.getInstance().getFeedSortMode()) {
        sortTopics(FEED_TOPICS);
      }
      mDisplayedSignature = signature;
      mAdapter.notifyDataSetChanged();
      // 缓存分支主要是从帖子返回时的 onResume 自动加载：
      // 这里不能滚到顶，要保留用户之前的浏览位置（只有双击刷新才归零）。
      hideEmpty();
      finishRefresh();
      return;
    }

    mLoading = true;
    if (mEmptyHint != null) {
      mEmptyHint.setVisibility(View.GONE);
    }

    Observable.fromIterable(boards)
        .flatMap(new Function<Board, ObservableSource<List<Topic>>>() {
          @Override public ObservableSource<List<Topic>> apply(Board board) {
            return fetchBoardTopics(board);
          }
        }, MAX_CONCURRENCY)
        .toList()
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Consumer<List<List<Topic>>>() {
          @Override public void accept(List<List<Topic>> parts) {
            mLoading = false;

            List<Topic> merged = new ArrayList<>();
            for (List<Topic> part : parts) {
              if (part != null && part.size() > 0) {
                for (Topic topic : part) {
                  // 信息流直接合并了抓取结果，没走 TopicListContent.addBoardTopic，
                  // 所以要在这里手动套用同一套作者/关键词屏蔽规则
                  if (topic != null && !BlockListManager.getInstance()
                      .isBlocked(topic.getAuthor(), topic.getTitle())) {
                    merged.add(topic);
                  }
                }
              }
            }
            // 按当前选择的排序方式合并，最新的在最上面
            sortTopics(merged);

            FEED_TOPICS.clear();
            FEED_TOPICS.addAll(merged);
            sLastLoadTime = System.currentTimeMillis();
            sCachedSignature = signature;
            mDisplayedSignature = signature;

            if (mAdapter != null) {
              mAdapter.notifyDataSetChanged();
            }
            // 只有主动刷新(双击信息流/下拉/刷新按钮)才滚到顶，避免新排到顶部的帖子超出窗口上边缘。
            // 首次进入(force=false)不滚，保留浏览位置。
            // post 到下一帧，等 notifyDataSetChanged 的布局完成后再滚，确保位置生效。
            if (force && mRecyclerView != null) {
              mRecyclerView.post(() -> mRecyclerView.scrollToPosition(0));
            }
            if (merged.isEmpty()) {
              showEmpty("所选版面暂时没有取到内容，下拉可以重试");
            } else {
              hideEmpty();
            }
            finishRefresh();
          }
        }, new Consumer<Throwable>() {
          @Override public void accept(Throwable throwable) {
            mLoading = false;
            Log.e(TAG, "loadFeed: " + Log.getStackTraceString(throwable));
            showEmpty("信息流加载失败，下拉可以重试");
            finishRefresh();
          }
        });
  }

  /** 单个版面：按智能预读策略串行加载多页（含当天就继续，到上限或无当天就停） */
  private ObservableSource<List<Topic>> fetchBoardTopics(final Board board) {
    final boolean byReply = Settings.getInstance().getFeedSortMode() == Settings.SORT_BY_LAST_REPLY;
    return fetchBoardPagesRecursive(board, 1, new ArrayList<Topic>(), byReply)
        .onErrorReturn(new Function<Throwable, List<Topic>>() {
          @Override public List<Topic> apply(Throwable throwable) {
            Log.e(TAG, "fetch " + board.getBoardEngName() + ": " + throwable.getMessage());
            return new ArrayList<>();
          }
        });
  }

  /** 递归串行加载第 page 页，累积到 accumulated，按「是否含当天」与上限决定是否继续 */
  private Observable<List<Topic>> fetchBoardPagesRecursive(final Board board, final int page,
      final List<Topic> accumulated, final boolean byReply) {
    return SMTHHelper.getInstance().wService.getBoardTopicsByPage(board.getBoardEngName(), String.valueOf(page))
        .subscribeOn(Schedulers.io())
        .flatMap(new Function<ResponseBody, ObservableSource<List<Topic>>>() {
          @Override public ObservableSource<List<Topic>> apply(ResponseBody responseBody) throws Exception {
            List<Topic> pageTopics = SMTHHelper.ParseBoardTopicsFromWWW(responseBody.string());
            boolean hasToday = false;
            int added = 0;
            for (Topic topic : pageTopics) {
              if (topic.isCategory || topic.isSticky) continue; // 舍弃分页符与置顶
              topic.setBoardEngName(board.getBoardEngName());
              topic.setBoardChsName(board.getBoardChsName());
              accumulated.add(topic);
              added++;
              if (topic.isWithinLastDays(FEED_SMART_LOAD_DAYS, byReply)) hasToday = true;
            }
            Log.d(TAG, "fetch " + board.getBoardEngName() + " page " + page
                + ": added=" + added + " hasToday=" + hasToday + " byReply=" + byReply);
            if (page < FEED_SMART_LOAD_MAX_PAGES && hasToday) {
              return fetchBoardPagesRecursive(board, page + 1, accumulated, byReply);
            }
            Log.d(TAG, "fetch " + board.getBoardEngName() + " stop at page " + page
                + " total=" + accumulated.size());
            return Observable.just(accumulated);
          }
        });
  }

  private void showEmpty(String message) {
    if (mEmptyHint == null) {
      return;
    }
    mEmptyHint.setText(message);
    mEmptyHint.setVisibility(View.VISIBLE);
  }

  private void hideEmpty() {
    if (mEmptyHint != null) {
      mEmptyHint.setVisibility(View.GONE);
    }
  }

  private class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.Holder> {

    class Holder extends RecyclerView.ViewHolder {
      final TextView title;
      final TextView board;
      final TextView author;
      final TextView reply;
      final TextView date;

      Holder(View itemView) {
        super(itemView);
        title = itemView.findViewById(R.id.feed_topic_title);
        board = itemView.findViewById(R.id.feed_topic_board);
        author = itemView.findViewById(R.id.feed_topic_author);
        reply = itemView.findViewById(R.id.feed_topic_reply);
        date = itemView.findViewById(R.id.feed_topic_date);
      }

      void bind(final Topic topic) {
        title.setText(topic.getTitle());

        String boardName = topic.getBoardChsName();
        if (TextUtils.isEmpty(boardName)) {
          boardName = topic.getBoardEngName();
        }
        board.setText(boardName);

        author.setText(topic.getAuthor());

        reply.setText(ReadProgressManager.replySpannable(topic, "回复 "));

        // 按什么排序就显示哪个时间，否则看起来像乱序
        if (Settings.getInstance().getFeedSortMode() == Settings.SORT_BY_LAST_REPLY
            && !TextUtils.isEmpty(topic.getReplyDate())) {
          date.setText(StringUtils.formatTopicDate(topic.getReplyDate()));
        } else {
          date.setText(StringUtils.formatTopicDate(topic.getPublishDate()));
        }

        itemView.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View v) {
            if (getActivity() == null) {
              return;
            }
            Intent intent = new Intent(getActivity(), PostListActivity.class);
            intent.putExtra(SMTHApplication.TOPIC_OBJECT, topic);
            intent.putExtra(SMTHApplication.FROM_BOARD, SMTHApplication.FROM_BOARD_BOARD);
            startActivity(intent);
          }
        });
      }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_feed_topic, parent, false);
      return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
      holder.bind(FEED_TOPICS.get(position));
    }

    @Override public int getItemCount() {
      return FEED_TOPICS.size();
    }
  }
}
