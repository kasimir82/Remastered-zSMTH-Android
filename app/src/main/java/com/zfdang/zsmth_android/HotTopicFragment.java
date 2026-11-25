package com.zfdang.zsmth_android;

import android.content.Context;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SearchView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.helpers.RecyclerViewUtil;
import com.zfdang.zsmth_android.listeners.OnTopicFragmentInteractionListener;
import com.zfdang.zsmth_android.listeners.OnVolumeUpDownListener;
import com.zfdang.zsmth_android.models.Topic;
import com.zfdang.zsmth_android.models.TopicListContent;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import java.util.List;
import okhttp3.ResponseBody;

/**
 * A fragment representing a list of Items.
 * <p/>
 * Activities containing this fragment MUST implement the {@link OnTopicFragmentInteractionListener}
 * interface.
 */
public class HotTopicFragment extends Fragment implements OnVolumeUpDownListener {

  private final String TAG = "HotTopicFragment";

  private OnTopicFragmentInteractionListener mListener;

  private RecyclerView mRecyclerView = null;
  private SwipeRefreshLayout mRefreshLayout = null;
  /** 需要抓取真实回帖数的帖子队列(十大标题括号是热度分不是回帖数) */
  private final java.util.List<Topic> mReplyFetchQueue = new java.util.ArrayList<>();
  private io.reactivex.disposables.Disposable mReplyFetchDisposable = null;
  /** 单条刷新：用户点进某帖返回时只刷这一条 */
  public void refreshSingleTopicReplyCount(Topic topic) {
    if (topic == null || topic.isCategory) return;
    synchronized (mReplyFetchQueue) {
      mReplyFetchQueue.clear();
      mReplyFetchQueue.add(topic);
    }
    startReplyCountFetch();
  }

  /**
   * Mandatory empty constructor for the fragment manager to instantiate the
   * fragment (e.g. upon screen orientation changes).
   */
  public HotTopicFragment() {
  }

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // http://stackoverflow.com/questions/8308695/android-options-menu-in-fragment
    setHasOptionsMenu(true);
  }

  @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View rootView = inflater.inflate(R.layout.fragment_hot_topic, container, false);

    // http://sapandiwakar.in/pull-to-refresh-for-android-recyclerview-or-any-other-vertically-scrolling-view/
    // pull to refresh for android recyclerview
    mRefreshLayout = (SwipeRefreshLayout) rootView.findViewById(R.id.swipeRefreshLayout);
    mRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
      @Override public void onRefresh() {
        RefreshGuidanceFromWWW();
      }
    });

    // http://blog.csdn.net/lmj623565791/article/details/45059587
    // 你想要控制Item间的间隔（可绘制），请通过ItemDecoration
    // 你想要控制Item增删的动画，请通过ItemAnimator
    // 你想要控制点击、长按事件，请自己写
    // item被按下的时候的highlight,这个是通过guidance item的backgroun属性来实现的 (android:background="@drawable/recyclerview_item_bg")
    mRecyclerView = (RecyclerView) rootView.findViewById(R.id.guidance_recycler_view);
    // Set the adapter
    if (mRecyclerView != null) {
      mRecyclerView.addItemDecoration(new DividerItemDecoration(getActivity(), LinearLayoutManager.VERTICAL, 0));
      Context context = rootView.getContext();
      mRecyclerView.setLayoutManager(new WrapContentLinearLayoutManager(context));
      mRecyclerView.setItemAnimator(new DefaultItemAnimator());
      mRecyclerView.setAdapter(new HotTopicRecyclerViewAdapter(TopicListContent.HOT_TOPICS, mListener));
    }

    getActivity().setTitle(SMTHApplication.App_Title_Prefix + "版面");

    // 搜索横条：提交后跳到"所有版面"页并带关键词过滤
    SearchView searchView = (SearchView) rootView.findViewById(R.id.hot_board_search);
    if (searchView != null) {
      searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
        @Override public boolean onQueryTextSubmit(String query) {
          if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openAllBoardsWithSearch(query);
          }
          searchView.clearFocus();
          return true;
        }
        @Override public boolean onQueryTextChange(String newText) {
          return false;
        }
      });
    }

    if (TopicListContent.HOT_TOPICS.size() == 0) {
      RefreshGuidance();
    }
    return rootView;
  }

  @Override public void onResume() {
    super.onResume();
    // 回复数更新由 refreshSingleTopicReplyCount() 的抓取队列完成，这里不再简单重bind
  }

  public void showLoadingHints() {
    MainActivity activity = (MainActivity) getActivity();
    if (activity != null) activity.showProgress("获取导读信息...");
  }

  public void clearLoadingHints() {
    // disable progress bar
    MainActivity activity = (MainActivity) getActivity();
    if (activity != null) {
      activity.dismissProgress();
    }

    // 收起原生转圈
    if(mRefreshLayout.isRefreshing()) {
      mRefreshLayout.setRefreshing(false);
    }
  }

  public void RefreshGuidance() {
    // called by onCreate & refresh menu item
    showLoadingHints();
    RefreshGuidanceFromWWW();
  }

  public void RefreshGuidanceFromWWW() {
    final SMTHHelper helper = SMTHHelper.getInstance();

    helper.wService.getAllHotTopics().flatMap(new Function<ResponseBody, ObservableSource<Topic>>() {
      @Override public ObservableSource<Topic> apply(@NonNull ResponseBody responseBody) throws Exception {
        try {
          String response = responseBody.string();
          List<Topic> results = SMTHHelper.ParseHotTopicsFromWWW(response);
          return Observable.fromIterable(results);
        } catch (Exception e) {
          Log.d(TAG, Log.getStackTraceString(e));
        }
        return null;
      }
    }).subscribeOn(Schedulers.computation()).observeOn(AndroidSchedulers.mainThread()).subscribe(new Observer<Topic>() {
      @Override public void onSubscribe(@NonNull Disposable disposable) {
        // clearHotTopics current hot topics
        TopicListContent.clearHotTopics();
        mRecyclerView.getAdapter().notifyDataSetChanged();
      }

      @Override public void onNext(@NonNull Topic topic) {
        //                        Log.d(TAG, topic.toString());
        if (TopicListContent.addHotTopic(topic)) {
          mRecyclerView.getAdapter().notifyItemInserted(TopicListContent.HOT_TOPICS.size() - 1);
        }
      }

      @Override public void onError(@NonNull Throwable e) {
        clearLoadingHints();
        Toast.makeText(SMTHApplication.getAppContext(), "获取热帖失败!\n" + e.toString(), Toast.LENGTH_LONG).show();
      }

      @Override public void onComplete() {
        Topic topic = new Topic("-- END --");
        if (TopicListContent.addHotTopic(topic)) {
          mRecyclerView.getAdapter().notifyItemInserted(TopicListContent.HOT_TOPICS.size() - 1);
        }

        clearLoadingHints();
        // 十大标题括号是热度分不是回帖数：批量、每帖间隔1秒抓详情页真实回帖数，
        // 这样看过+新增(蓝色AA+BB)才能正确显示。手工下拉刷新也走这里。
        enqueueAllTopicsForReplyCount();
      }
    });
  }

  // http://stackoverflow.com/questions/32604552/onattach-not-called-in-fragment
  // If you run your application on a device with API 23 (marshmallow) then onAttach(Context) will be called.
  // On all previous Android Versions onAttach(Activity) will be called.
  @Override public void onAttach(Context context) {
    super.onAttach(context);
    if (context instanceof OnTopicFragmentInteractionListener) {
      mListener = (OnTopicFragmentInteractionListener) context;
    } else {
      throw new RuntimeException(context.toString() + " must implement OnTopicFragmentInteractionListener");
    }
  }

  @Override public void onDetach() {
    super.onDetach();
    mListener = null;
  }

  /**
   * 串行抓取队列里每个帖子的真实回帖数，每个请求间隔1秒，避免太频繁被服务端判失败。
   * 抓到后写入topic.replyCounts(优先于title括号的假数字)，并刷新对应行。
   */
  private void startReplyCountFetch() {
    java.util.List<Topic> batch;
    synchronized (mReplyFetchQueue) {
      if (mReplyFetchDisposable != null && !mReplyFetchDisposable.isDisposed()) {
        mReplyFetchDisposable.dispose(); // 有新的刷新请求，取消旧的
      }
      batch = new java.util.ArrayList<>(mReplyFetchQueue);
      mReplyFetchQueue.clear();
    }
    if (batch.isEmpty()) return;

    final SMTHHelper helper = SMTHHelper.getInstance();
    // 每个请求之间间隔1秒(concatMap串行 + delay)，避免太频繁被服务端判失败。
    // 单条失败用 onErrorResumeNext 发一个假Topic占位(RxJava2不允许null)，保证队列继续。
    mReplyFetchDisposable = io.reactivex.Observable.fromIterable(batch)
        .concatMap(topic -> io.reactivex.Observable
            .timer(1, java.util.concurrent.TimeUnit.SECONDS) // 请求前先等1秒，真正间隔
            .flatMap(ignored -> helper.wService.getPostListByPage(
                topic.getBoardEngName(), topic.getTopicID(), 1, null))
            .map(resp -> {
              String html = resp.string();
              int real = SMTHHelper.parseRealReplyCount(html);
              if (real >= 0) {
                topic.setReplyCounts(String.valueOf(real));
              } else {
                // 记录解析失败(如返回guest-read-challenge验证页/限流页)，htmlBody前80字
                String head = html.length() > 80 ? html.substring(0, 80) : html;
                com.zfdang.zsmth_android.helpers.DiagLogger.log("HotTopic", "REPLY_PARSE_FAIL",
                    com.zfdang.zsmth_android.helpers.DiagLogger.FAIL,
                    "board=" + topic.getBoardEngName() + " id=" + topic.getTopicID()
                        + " httpLen=" + html.length() + " head=" + head.replace("\n", " "));
              }
              return topic;
            })
            .onErrorResumeNext(e -> {
              // 429限流：等2秒重试一次；其它错误直接记日志跳过
              String errMsg = e.getMessage() == null ? "" : e.getMessage();
              if (errMsg.contains("429")) {
                Log.w(TAG, "fetch reply 429, retry after 2s: " + topic.getTopicID());
                return io.reactivex.Observable.timer(2, java.util.concurrent.TimeUnit.SECONDS)
                    .flatMap(ignored2 -> helper.wService.getPostListByPage(
                        topic.getBoardEngName(), topic.getTopicID(), 1, null))
                    .map(resp -> {
                      int real = SMTHHelper.parseRealReplyCount(resp.string());
                      if (real >= 0) topic.setReplyCounts(String.valueOf(real));
                      return topic;
                    })
                    .onErrorResumeNext(e2 -> {
                      Log.w(TAG, "fetch reply retry FAILED: " + topic.getTopicID() + " : " + e2.getMessage());
                      return io.reactivex.Observable.just(new Topic("__FETCH_FAILED__"));
                    });
              }
              Log.w(TAG, "fetch reply FAILED board=" + topic.getBoardEngName()
                  + " id=" + topic.getTopicID() + " : " + errMsg);
              com.zfdang.zsmth_android.helpers.DiagLogger.log("HotTopic", "REPLY_FETCH",
                  com.zfdang.zsmth_android.helpers.DiagLogger.FAIL,
                  "board=" + topic.getBoardEngName() + " id=" + topic.getTopicID() + " err=" + errMsg);
              return io.reactivex.Observable.just(new Topic("__FETCH_FAILED__"));
            }))
        .subscribeOn(io.reactivex.schedulers.Schedulers.io())
        .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
        .subscribe(topic -> {
          if (!"__FETCH_FAILED__".equals(topic.getCategory())
              && mRecyclerView != null && mRecyclerView.getAdapter() != null) {
            mRecyclerView.getAdapter().notifyDataSetChanged();
          }
        }, e -> Log.w(TAG, "fetch reply queue error: " + e.getMessage()));
  }

  /** 只把"本日十大热门话题"的10条加入队列(其余板块热帖太多，请求会被429限流) */
  private void enqueueAllTopicsForReplyCount() {
    synchronized (mReplyFetchQueue) {
      boolean inTop10 = false;
      int count = 0;
      for (Topic t : TopicListContent.HOT_TOPICS) {
        if (t == null) continue;
        if (t.isCategory) {
          if ("本日十大热门话题".equals(t.getCategory()) || "本日十大热门话题".equals(t.getTitle())) {
            inTop10 = true; // 分隔符之后就是top10
          } else if (inTop10) {
            break; // 遇到下一个板块分隔符，top10结束
          }
          continue;
        }
        if (inTop10 && t.getTopicID() != null && count < 10) {
          mReplyFetchQueue.add(t);
          count++;
        }
      }
    }
    startReplyCountFetch();
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.main_action_refresh) {
      RefreshGuidance();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override public boolean onVolumeUpDown(int keyCode) {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
      RecyclerViewUtil.ScrollRecyclerViewByKey(mRecyclerView, keyCode);
    } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
      RecyclerViewUtil.ScrollRecyclerViewByKey(mRecyclerView, keyCode);
    }
    return true;
  }
}
