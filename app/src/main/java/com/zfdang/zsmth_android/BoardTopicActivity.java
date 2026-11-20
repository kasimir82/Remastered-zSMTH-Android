package com.zfdang.zsmth_android;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;

import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.jude.swipbackhelper.SwipeBackHelper;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.helpers.RecyclerViewUtil;
import com.zfdang.zsmth_android.listeners.EndlessRecyclerOnScrollListener;
import com.zfdang.zsmth_android.listeners.OnTopicFragmentInteractionListener;
import com.zfdang.zsmth_android.models.Board;
import com.zfdang.zsmth_android.models.ComposePostContext;
import com.zfdang.zsmth_android.models.Topic;
import com.zfdang.zsmth_android.models.TopicListContent;
import com.zfdang.zsmth_android.newsmth.AjaxResponse;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;

import io.reactivex.ObservableSource;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import okhttp3.ResponseBody;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

/**
 * An activity representing a list of Topics. This activity
 * has different presentations for handset and tablet-size devices. On
 * handsets, the activity presents a list of items, which when touched,
 * lead to a {@link PostListActivity} representing
 * item details. On tablets, the activity presents the list of items and
 * item details side-by-side using two vertical panes.
 */
public class BoardTopicActivity extends SMTHBaseActivity
        implements OnTopicFragmentInteractionListener, SwipeRefreshLayout.OnRefreshListener, PopupSearchWindow.SearchInterface {

    /**
     * Whether or not the activity is in two-pane mode, i.e. running on a tablet
     * device.
     */

    private final String TAG = "BoardTopicActivity";

    private Board mBoard = null;

    private int mCurrentPageNo = 1;
    private int LOAD_MORE_THRESHOLD = 1;

    // 全局排序：串行加载页的上限，防止超大版面(如616页)请求过多
    private static final int MAX_PAGES_FOR_GLOBAL_SORT = 50;
    // 智能预读的时间范围：最近 N 天（今天=0），超出即停止加载
    private static final int SMART_LOAD_DAYS = 2;
    private boolean mLoadingAllForSort = false;
    private boolean mTotalPagesReachedLimit = false;

    private SwipeRefreshLayout mSwipeRefreshLayout = null;
    private EndlessRecyclerOnScrollListener mScrollListener = null;
    private RecyclerView mRecyclerView = null;

    private Settings mSetting;

    private boolean isSearchMode = false;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SwipeBackHelper.onDestroy(this);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        SwipeBackHelper.onPostCreate(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ComposePostActivity.COMPOSE_ACTIVITY_REQUEST_CODE) {
            // returned from compose activity
            // ideally, we should also check the resultCode
            refreshBoard();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleBackAction() {
        if (isSearchMode) {
            onRefresh();
        } else {
            finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBackAction(); }
        });
        SwipeBackHelper.onCreate(this);

        setContentView(R.layout.activity_board_topic);
        setupBottomNavigation();

        Toolbar toolbar = (Toolbar) findViewById(R.id.board_topic_toolbar);
        setSupportActionBar(toolbar);
        assert toolbar != null;
        toolbar.setTitle(getTitle());

        mSetting = Settings.getInstance();

        // enable pull down to refresh
        mSwipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipeRefreshLayout);
        if (mSwipeRefreshLayout == null) throw new AssertionError();
        mSwipeRefreshLayout.setOnRefreshListener(this);

        mRecyclerView = (RecyclerView) findViewById(R.id.board_topic_list);
        assert mRecyclerView != null;
        mRecyclerView.setItemAnimator(new DefaultItemAnimator());
        mRecyclerView.addItemDecoration(new DividerItemDecoration(this, LinearLayoutManager.VERTICAL, 0));
        LinearLayoutManager linearLayoutManager = new WrapContentLinearLayoutManager(this);
        mRecyclerView.setLayoutManager(linearLayoutManager);
        mRecyclerView.setAdapter(new BoardTopicRecyclerViewAdapter(TopicListContent.BOARD_TOPICS, this));

        // enable endless loading
        mScrollListener = new EndlessRecyclerOnScrollListener(linearLayoutManager) {
            @Override
            public void onLoadMore(int current_page) {
                // do something...
                loadMoreItems();
            }
        };
        mRecyclerView.addOnScrollListener(mScrollListener);

        // Show the Up button in the action bar.
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        // get Board information from launcher
        Intent intent = getIntent();
        Board board = intent.getParcelableExtra(SMTHApplication.BOARD_OBJECT);
        assert board != null;
        if (mBoard == null || !mBoard.getBoardEngName().equals(board.getBoardEngName())) {
            mBoard = board;
            TopicListContent.clearBoardTopics();
            mCurrentPageNo = 1;
        }

        updateTitle();

        if (TopicListContent.BOARD_TOPICS.size() == 0) {
            // only load boards on the first time
            // 按回复排序：服务端已按最后回复倒序，直接普通分页加载即可
            // 按发帖排序：服务端是按回复排的，才需要预读多页后本地全局重排
            if (!isSearchMode && mSetting.getTopicSortMode() == Settings.SORT_BY_PUBLISH_TIME) {
                loadAllPagesAndSortGlobally();
            } else {
                RefreshBoardTopicsWithoutClear();
            }
        }
    }

    public void updateTitle() {
        String title = mBoard.getBoardChsName();
        setTitle(title + " - 主题列表");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.board_topic_action_sticky) {
            mSetting.toggleShowSticky();
            this.refreshBoard();
        } else if (id == R.id.board_topic_action_refresh) {
            this.refreshBoard();
        } else if (id == R.id.board_topic_action_newpost) {
            ComposePostContext postContext = new ComposePostContext();
            postContext.setBoardEngName(mBoard.getBoardEngName());
            postContext.setComposingMode(ComposePostContext.MODE_NEW_POST);

            Intent intent = new Intent(this, ComposePostActivity.class);
            intent.putExtra(SMTHApplication.COMPOSE_POST_CONTEXT, postContext);
            startActivityForResult(intent, ComposePostActivity.COMPOSE_ACTIVITY_REQUEST_CODE);
        } else if (id == R.id.board_topic_action_sort) {
            showSortModeDialog();
        } else if (id == R.id.board_topic_action_search) {
            PopupSearchWindow popup = new PopupSearchWindow();
            popup.initPopupWindow(this);
            popup.showAtLocation(mRecyclerView, Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL, 0, 0);
        } else if (id == R.id.board_topic_action_favorite) {
            SMTHHelper helper = SMTHHelper.getInstance();
            helper.wService.manageFavoriteBoard("0", "ab", this.mBoard.getBoardEngName())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<AjaxResponse>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable disposable) {

                        }

                        @Override
                        public void onNext(@NonNull AjaxResponse ajaxResponse) {
                            Log.d(TAG, "onNext: " + ajaxResponse.toString());
                            if (ajaxResponse.getAjax_st() == AjaxResponse.AJAX_RESULT_OK) {
                                Toast.makeText(BoardTopicActivity.this, ajaxResponse.getAjax_msg() + "\n请手动刷新收藏夹！", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(BoardTopicActivity.this, ajaxResponse.toString(), Toast.LENGTH_LONG).show();
                            }

                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            Toast.makeText(BoardTopicActivity.this, "收藏版面失败！\n" + e.toString(), Toast.LENGTH_LONG).show();

                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.board_topic_menu, menu);
        return true;
    }

    public void clearLoadingHints() {
        dismissProgress();

        if (mSwipeRefreshLayout != null && mSwipeRefreshLayout.isRefreshing()) {
            mSwipeRefreshLayout.setRefreshing(false);  // This hides the spinner
        }

        if (mScrollListener != null) {
            mScrollListener.setLoading(false);
        }
    }

    // load topics from next page, without alert
    public void loadMoreItems() {
        if (isSearchMode || mSwipeRefreshLayout.isRefreshing() || pDialog.isShowing()) {
            return;
        }
        // 按发帖排序是一次性全局排序(无分页符)，禁用分页加载；
        // 按回复排序走普通分页，允许上拉加载下一页
        if (mLoadingAllForSort || mGlobalSortMode) {
            return;
        }

        mCurrentPageNo += 1;
        // Log.d(TAG, mCurrentPageNo + " page is loading now...");
        LoadBoardTopics();
    }

    @Override
    public void onRefresh() {
        // this method is slightly different with RefreshBoardTopicFromPageOne
        // this method does not alert since it's triggered by SwipeRefreshLayout
        if (!isSearchMode && mSetting.getTopicSortMode() == Settings.SORT_BY_PUBLISH_TIME) {
            // 按发帖排序才需要预读+全局重排
            mTotalPagesReachedLimit = false;
            loadAllPagesAndSortGlobally();
            if (mSwipeRefreshLayout.isRefreshing()) mSwipeRefreshLayout.setRefreshing(false);
            return;
        }
        mCurrentPageNo = 1;
        TopicListContent.clearBoardTopics();
        mRecyclerView.getAdapter().notifyDataSetChanged();
        LoadBoardTopics();
    }

    /** 按当前排序模式刷新：回复=普通分页重取第一页；发帖=预读全局重排 */
    private void refreshBoard() {
        if (!isSearchMode && mSetting.getTopicSortMode() == Settings.SORT_BY_PUBLISH_TIME) {
            mTotalPagesReachedLimit = false;
            loadAllPagesAndSortGlobally();
        } else {
            RefreshBoardTopicFromPageOne();
        }
    }

    public void RefreshBoardTopicFromPageOne() {
        showProgress("刷新版面文章...");

        TopicListContent.clearBoardTopics();
        mRecyclerView.getAdapter().notifyDataSetChanged();

        mCurrentPageNo = 1;
        LoadBoardTopics();
    }

    public void RefreshBoardTopicsWithoutClear() {
        showProgress("加载版面文章...");

        LoadBoardTopics();
    }

    public void LoadBoardTopics() {

        isSearchMode = false;
        mGlobalSortMode = false; // 普通分页加载，恢复分页符
        final SMTHHelper helper = SMTHHelper.getInstance();

        helper.wService.getBoardTopicsByPage(mBoard.getBoardEngName(), Integer.toString(mCurrentPageNo))
                .flatMap(new Function<ResponseBody, ObservableSource<Topic>>() {
                    @Override
                    public ObservableSource<Topic> apply(@NonNull ResponseBody responseBody) throws Exception {
                        try {
                            String response = responseBody.string();
                            List<Topic> topics = SMTHHelper.ParseBoardTopicsFromWWW(response);
                            // add page separator
                            Topic separator = new Topic(String.format("第 %d 页", mCurrentPageNo));
                            separator.isCategory = true;
                            topics.add(0, separator);
                            // return
                            return Observable.fromIterable(topics);
                        } catch (Exception e) {
                            Log.e(TAG, "call: " + Log.getStackTraceString(e));
                            return null;
                        }
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Topic>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable disposable) {
                    }

                    @Override
                    public void onNext(@NonNull Topic topic) {
                        // Log.d(TAG, topic.toString());
                        if (!topic.isSticky || mSetting.isShowSticky()) {
                            if (TopicListContent.addBoardTopic(topic, mBoard.getBoardEngName())) {
                                mRecyclerView.post(new Runnable() {
                                    public void run() {
                                        // There is no need to use notifyDataSetChanged()
                                        mRecyclerView.getAdapter().notifyItemInserted(TopicListContent.BOARD_TOPICS.size() - 1);
                                    }
                                });
                            }
                        }
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        clearLoadingHints();

                        Toast.makeText(SMTHApplication.getAppContext(), String.format("获取第%d页的帖子失败!\n", mCurrentPageNo) + e.toString(),
                                Toast.LENGTH_LONG).show();
                        mCurrentPageNo -= 1;
                    }

                    @Override
                    public void onComplete() {
                        clearLoadingHints();
                        // 按发表时间模式走 loadAllPagesAndSortByPublishTime 全局排序，
                        // 这里的普通分页加载不再做分段排序（该模式下不触发分页加载）
                    }
                });
    }

    /**
     * 按发表时间全局排序：串行加载全部页(有上限)，舍弃分页符，全局按发帖时间倒序。
     * 先加载第1页拿到总页数，再逐页追加到临时列表，全部完成后一次性替换列表。
     */
    // 加载全部页全局排序用的状态
    private List<Topic> mSortAllTopics;
    private int mSortTotalPages;
    private boolean mSortByReply; // true=按回复时间, false=按发帖时间
    private boolean mSortPageHasToday; // 本页是否含「当天」帖子，用于智能停止
    private boolean mGlobalSortMode = false; // 当前列表是否为全局排序(无分页符)结果

    /**
     * 全局排序入口：串行加载页（有上限 + 智能停止），舍弃分页符，按当前排序模式全局排序。
     * 智能停止：某一页已不含「当天」的对应时间（发帖排序看发帖时间，回复排序看回复时间）时停止。
     */
    private void loadAllPagesAndSortGlobally() {
        if (mLoadingAllForSort || isSearchMode || mBoard == null) return;
        mLoadingAllForSort = true;
        mGlobalSortMode = true;
        mTotalPagesReachedLimit = false;
        mSortByReply = mSetting.getTopicSortMode() == Settings.SORT_BY_LAST_REPLY;
        mSortAllTopics = new ArrayList<>();
        mSortTotalPages = 1;
        showProgress("加载帖子 0/1 页...");
        loadSortPage(1);
    }

    /** 串行加载第 page 页，完成后根据「是否含当天帖子」和上限决定是否继续 */
    private void loadSortPage(final int page) {
        SMTHHelper helper = SMTHHelper.getInstance();
        helper.wService.getBoardTopicsByPage(mBoard.getBoardEngName(), Integer.toString(page))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(new Observer<ResponseBody>() {
                @Override public void onSubscribe(@NonNull Disposable d) {}
                @Override public void onNext(@NonNull ResponseBody body) {
                    try {
                        String response = body.string();
                        if (page == 1) {
                            int tp = SMTHHelper.parseBoardTotalPages(response);
                            mTotalPagesReachedLimit = tp > MAX_PAGES_FOR_GLOBAL_SORT;
                            mSortTotalPages = Math.min(tp, MAX_PAGES_FOR_GLOBAL_SORT);
                        }
                        List<Topic> pageTopics = SMTHHelper.ParseBoardTopicsFromWWW(response);
                        boolean hasToday = false;
                        int added = 0;
                        for (Topic t : pageTopics) {
                            if (t.isCategory) continue; // 舍弃分页符
                            if (TopicListContent.isTopicBlocked(t, mBoard.getBoardEngName())) continue;
                            mSortAllTopics.add(t);
                            added++;
                            if (t.isWithinLastDays(SMART_LOAD_DAYS, mSortByReply)) hasToday = true;
                        }
                        // 本页一条都没加进来（解析失败/全被屏蔽），也视为无当天数据
                        mSortPageHasToday = hasToday && added > 0;
                        Log.d(TAG, "sort load page " + page + ": added=" + added
                            + " hasToday=" + hasToday + " byReply=" + mSortByReply);
                    } catch (Exception e) {
                        Log.e(TAG, "loadAllForSort page " + page + " failed", e);
                        mSortPageHasToday = false;
                    }
                }
                @Override public void onError(@NonNull Throwable e) {
                    Log.e(TAG, "loadAllForSort page " + page + " error", e);
                    finishGlobalSort(mSortAllTopics);
                }
                @Override public void onComplete() {
                    // 停止条件：已到上限，或本页不含当天对应时间（说明再往后都是更旧的）
                    boolean reachedLimit = page >= mSortTotalPages;
                    boolean noMoreToday = !mSortPageHasToday;
                    if (!reachedLimit && !noMoreToday) {
                        showProgress("加载帖子 " + page + "/" + mSortTotalPages + " 页...");
                        loadSortPage(page + 1);
                    } else {
                        Log.d(TAG, "sort load stop at page " + page
                            + " reachedLimit=" + reachedLimit + " noMoreToday=" + noMoreToday);
                        finishGlobalSort(mSortAllTopics);
                    }
                }
            });
    }

    /** 全局排序完成：置顶帖在前，其余按当前排序模式全局倒序，一次性替换列表 */
    private void finishGlobalSort(List<Topic> allTopics) {
        dismissProgress();
        mLoadingAllForSort = false;
        if (allTopics.isEmpty()) return;

        List<Topic> sticky = new ArrayList<>();
        List<Topic> normal = new ArrayList<>();
        boolean showSticky = mSetting.isShowSticky();
        for (Topic t : allTopics) {
            if (t.isSticky) {
                if (showSticky) sticky.add(t); // 隐藏置顶时不加入列表
            } else {
                normal.add(t);
            }
        }
        final boolean byReply = mSortByReply;
        Collections.sort(normal, new Comparator<Topic>() {
            @Override public int compare(Topic lhs, Topic rhs) {
                long lk = byReply ? lhs.getReplySortKey() : lhs.getPublishSortKeyCached();
                long rk = byReply ? rhs.getReplySortKey() : rhs.getPublishSortKeyCached();
                return Long.compare(rk, lk);
            }
        });

        List<Topic> finalList = new ArrayList<>();
        finalList.addAll(sticky);
        finalList.addAll(normal);

        TopicListContent.clearBoardTopics();
        TopicListContent.BOARD_TOPICS.addAll(finalList);
        if (mRecyclerView.getAdapter() != null) {
            mRecyclerView.getAdapter().notifyDataSetChanged();
        }
        String hint = (mTotalPagesReachedLimit ? "（已达加载上限）" : "");
        String modeName = mSortByReply ? "回复时间" : "发表时间";
        Toast.makeText(this, "已加载 " + finalList.size() + " 帖并按" + modeName + "排序" + hint, Toast.LENGTH_SHORT).show();
    }

    private void sortTopicsRange(List<Topic> topics, int from, int to) {
        if (to - from < 2) {
            return;
        }
        List<Topic> sticky = new ArrayList<>();
        List<Topic> normal = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Topic topic = topics.get(i);
            if (topic.isSticky) {
                sticky.add(topic);
            } else {
                normal.add(topic);
            }
        }
        // Collections.sort 是稳定排序，同一天的帖子保持服务端返回的先后顺序
        Collections.sort(normal, new Comparator<Topic>() {
            @Override public int compare(Topic lhs, Topic rhs) {
                return Long.compare(rhs.getPublishSortKeyCached(), lhs.getPublishSortKeyCached());
            }
        });
        int index = from;
        for (Topic topic : sticky) {
            topics.set(index++, topic);
        }
        for (Topic topic : normal) {
            topics.set(index++, topic);
        }
    }

    private void showSortModeDialog() {
        if (isSearchMode) {
            Toast.makeText(this, "搜索结果本身就是按时间倒序的", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] modes = { "最后回复", "发表时间" };
        int checked = mSetting.getTopicSortMode();
        if (checked < 0 || checked >= modes.length) {
            checked = Settings.SORT_BY_LAST_REPLY;
        }

        new AlertDialog.Builder(this)
            .setTitle("排序方式")
            .setSingleChoiceItems(modes, checked, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    if (which == mSetting.getTopicSortMode()) {
                        return;
                    }
                    mSetting.setTopicSortMode(which);
                    if (which == Settings.SORT_BY_PUBLISH_TIME) {
                        // 按发帖排序：预读多页后本地全局重排
                        mTotalPagesReachedLimit = false;
                        loadAllPagesAndSortGlobally();
                    } else {
                        // 切回最后回复：服务端已按回复倒序，重新取第一页恢复分页即可
                        RefreshBoardTopicFromPageOne();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Settings.getInstance().isVolumeKeyScroll() && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            RecyclerViewUtil.ScrollRecyclerViewByKey(mRecyclerView, keyCode);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // http://stackoverflow.com/questions/4500354/control-volume-keys
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // disable the beep sound when volume up/down is pressed
        if (Settings.getInstance().isVolumeKeyScroll() && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onTopicFragmentInteraction(Topic item) {
        if (item.isCategory) return;
        Intent intent = new Intent(this, PostListActivity.class);
        item.setBoardEngName(mBoard.getBoardEngName());
        item.setBoardChsName(mBoard.getBoardChsName());
        intent.putExtra(SMTHApplication.TOPIC_OBJECT, item);
        intent.putExtra(SMTHApplication.FROM_BOARD, SMTHApplication.FROM_BOARD_BOARD);
        startActivity(intent);
    }

    @Override
    public void OnSearchAction(String keyword, String author, boolean elite, boolean attachment) {
        Log.d(TAG, "OnSearchAction: " + keyword + author + elite + attachment);

        isSearchMode = true;
        showProgress("加载搜索结果...");

        TopicListContent.BOARD_TOPICS.clear();
        mRecyclerView.getAdapter().notifyDataSetChanged();

        String eliteStr = null;
        if (elite) eliteStr = "on";

        String attachmentStr = null;
        if (attachment) attachmentStr = "on";

        SMTHHelper helper = SMTHHelper.getInstance();
        helper.wService.searchTopicInBoard(keyword, author, eliteStr, attachmentStr, this.mBoard.getBoardEngName())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap(new Function<ResponseBody, ObservableSource<Topic>>() {
                    @Override
                    public ObservableSource<Topic> apply(@NonNull ResponseBody responseBody) throws Exception {
                        try {
                            String response = responseBody.string();
                            List<Topic> topics = SMTHHelper.ParseSearchResultFromWWW(response);
                            Topic topic = new Topic("搜索模式 - 下拉或按返回键退出搜索模式");
                            topics.add(0, topic);
                            return Observable.fromIterable(topics);
                        } catch (Exception e) {
                            Log.d(TAG, Log.getStackTraceString(e));
                            return null;
                        }
                    }
                })
                .subscribe(new Observer<Topic>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable disposable) {

                    }

                    @Override
                    public void onNext(@NonNull Topic topic) {
                        if (TopicListContent.addBoardTopic(topic, mBoard.getBoardEngName())) {
                            mRecyclerView.getAdapter().notifyItemInserted(TopicListContent.BOARD_TOPICS.size() - 1);
                        }
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Toast.makeText(SMTHApplication.getAppContext(), "加载搜索结果失败!\n" + e.toString(), Toast.LENGTH_LONG).show();

                    }

                    @Override
                    public void onComplete() {
                        dismissProgress();
                    }
                });
    }
}
