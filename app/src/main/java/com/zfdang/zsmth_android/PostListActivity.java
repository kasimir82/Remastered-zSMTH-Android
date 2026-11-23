package com.zfdang.zsmth_android;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jude.swipbackhelper.SwipeBackHelper;
import com.scwang.smart.refresh.footer.ClassicsFooter;
import com.scwang.smart.refresh.header.ClassicsHeader;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.scwang.smart.refresh.layout.listener.OnLoadMoreListener;
import com.scwang.smart.refresh.layout.listener.OnRefreshListener;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.helpers.BlockListManager;
import com.zfdang.zsmth_android.helpers.ReadProgressManager;
import com.zfdang.zsmth_android.helpers.ShareImageHelper;
import com.zfdang.zsmth_android.helpers.SilentLoginHelper;
import com.zfdang.zsmth_android.helpers.TextBombDialog;
import com.zfdang.zsmth_android.models.Attachment;
import com.zfdang.zsmth_android.models.Board;
import com.zfdang.zsmth_android.models.ComposePostContext;
import com.zfdang.zsmth_android.models.Post;
import com.zfdang.zsmth_android.models.PostActionAlertDialogItem;
import com.zfdang.zsmth_android.models.PostListContent;
import com.zfdang.zsmth_android.models.Topic;
import com.zfdang.zsmth_android.newsmth.AjaxResponse;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ResponseBody;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

/**
 * An activity representing a single Topic detail screen. This
 * activity is only used narrow width devices. On tablet-size devices,
 * item details are presented side-by-side with a list of items
 * in a {@link BoardTopicActivity}.
 */
public class PostListActivity extends SMTHBaseActivity
    implements View.OnClickListener, OnTouchListener, RecyclerViewGestureListener.OnItemLongClickListener,
    PopupLikeWindow.OnLikeInterface,
    PopupForwardWindow.OnForwardInterface, PopupBanWindow.OnBanIDInterface {
  private static final int LOGIN_REQUEST_CODE = 130;
  // 被踢下线时，加载失败会反复回调；用防抖避免重复弹「正在重新登录」和重复触发登录
  private static long sLastSilentLoginAttempt = 0;
  private static final long SILENT_LOGIN_DEBOUNCE_MS = 30 * 1000;

  // 长按菜单操作标识（代替脆弱的下标）
  private static final int ACT_REPLY_POST = 1;
  private static final int ACT_LIKE = 2;
  private static final int ACT_QUERY_AUTHOR = 3;
  private static final int ACT_FILTER_AUTHOR = 4;
  private static final int ACT_COPY_CONTENT = 5;
  private static final int ACT_FORWARD = 6;
  private static final int ACT_VIEW_IN_BROWSER = 7;
  private static final int ACT_DELETE_POST = 9;
  private static final int ACT_EDIT_POST = 10;
  private static final int ACT_BLOCK_AUTHOR = 11;
  private static final int ACT_TOPIC_DELETE = 12;
  private static final int ACT_BAN_ID = 13;
  private static final int ACT_MARK_M = 14;
  private static final int ACT_TOPIC_READONLY = 15;

  private static final String TAG = "PostListActivity";
  public RecyclerView mRecyclerView = null;
  private PostRecyclerViewAdapter mAdapter = null;
  private TextView mTitle = null;
  private EditText mPageNo = null;

  public int mCurrentPageNo = 1;
  private String mFilterUser = null;
  /** 结束标记高度校准的防重入标记（滚动停止时校准，避免改高度触发布局循环） */
  private boolean mEndMarkerAdjusting = false;

  /** 自动加载下一页时防止重复触发 */
  private boolean mLoadingMore = false;

  private Topic mTopic = null;

  // 阅读进度恢复：目标楼层 > 1 时，加载完对应页后滚动过去
  private int mRestoreFloor = 0;
  private boolean mRestoreDone = false;  // 本次进帖是否已处理过恢复，避免翻页重复滚动

  static {
    ClassicsHeader.REFRESH_HEADER_PULLING = "下拉可以刷新";
    ClassicsHeader.REFRESH_HEADER_REFRESHING = "正在刷新...";
    ClassicsHeader.REFRESH_HEADER_LOADING = "正在加载...";
    ClassicsHeader.REFRESH_HEADER_RELEASE = "释放立即刷新";
    ClassicsHeader.REFRESH_HEADER_FINISH = "刷新完成";
    ClassicsHeader.REFRESH_HEADER_FAILED = "刷新失败";
    ClassicsHeader.REFRESH_HEADER_UPDATE = "上次更新 M-d HH:mm";

    ClassicsFooter.REFRESH_FOOTER_PULLING = "上拉可以翻页";
    ClassicsFooter.REFRESH_FOOTER_RELEASE = "释放立即翻页";
    ClassicsFooter.REFRESH_FOOTER_LOADING = "正在加载下一页...";
    ClassicsFooter.REFRESH_FOOTER_REFRESHING = "正在刷新...";
    ClassicsFooter.REFRESH_FOOTER_FINISH = "";
    ClassicsFooter.REFRESH_FOOTER_FAILED = "";
    ClassicsFooter.REFRESH_FOOTER_NOTHING = "";
  }

  private SmartRefreshLayout mRefreshLayout;
  private String mFrom;

  private GestureDetector mGestureDetector;
  private RecyclerViewGestureListener mGestureListener;
  private LinearLayoutManager linearLayoutManager;

  // used for captureViewInternal
  View capView1;
  View capView2;
  String capPostID;
  private final static int RC_READ_WRITE_STORAGE = 1245;

  @Override
  protected void onPause() {
    super.onPause();
    saveReadingProgress();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    SwipeBackHelper.onDestroy(this);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    // Forward results to EasyPermissions
    EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    SwipeBackHelper.onPostCreate(this);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == LOGIN_REQUEST_CODE && resultCode == RESULT_OK) {
      reloadPostList();
    }
    if (requestCode == ComposePostActivity.COMPOSE_ACTIVITY_REQUEST_CODE) {
      // returned from Compose activity, refresh current post
      // TODO: check resultCode
      reloadPostList();
    }
    super.onActivityResult(requestCode, resultCode, data);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    SwipeBackHelper.onCreate(this);

    setContentView(R.layout.activity_post_list);
    setupBottomNavigation();
    Toolbar toolbar = (Toolbar) findViewById(R.id.post_list_toolbar);
    setSupportActionBar(toolbar);

    // Show the Up button in the action bar.
    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
    }

    mTitle = (TextView) findViewById(R.id.post_list_title);
    assert mTitle != null;
    mPageNo = (EditText) findViewById(R.id.post_list_page_no);
    assert mPageNo != null;

    // define swipe refresh function
    mRefreshLayout = (SmartRefreshLayout) findViewById(R.id.post_list_swipe_refresh_layout);
    mRefreshLayout.setEnableAutoLoadMore(false);
    mRefreshLayout.setEnableScrollContentWhenLoaded(false);
    mRefreshLayout.setEnableOverScrollBounce(false);
    mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
      @Override
      public void onRefresh(RefreshLayout refreshLayout) {
        // reload current page
        reloadPostListWithoutAlert();
      }
    });
    mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
      @Override
      public void onLoadMore(RefreshLayout refreshLayout) {
        // 上拉加载更多与自动加载一样，采用追加模式
        if (mLoadingMore) {
          mRefreshLayout.finishLoadMore(100);
          return;
        }
        if (mTopic != null && mCurrentPageNo >= mTopic.getTotalPageNo()) {
          mRefreshLayout.finishLoadMore(100);
          return;
        }
        loadNextPageAppend();
      }
    });

    mRecyclerView = (RecyclerView) findViewById(R.id.post_list);
    assert mRecyclerView != null;
    mRecyclerView.addItemDecoration(
        new DividerItemDecoration(this, LinearLayoutManager.VERTICAL, R.drawable.recyclerview_divider));
    linearLayoutManager = new WrapContentLinearLayoutManager(this);
    mRecyclerView.setLayoutManager(linearLayoutManager);
    mAdapter = new PostRecyclerViewAdapter(PostListContent.POSTS, this);
    mRecyclerView.setAdapter(mAdapter);

    // 滚动到距末尾约 5 帖时，自动追加下一页（末页则不处理）
    mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
        // 点按翻页用 scrollToPositionWithOffset 瞬间跳转，产生的 onScrolled dy=0；
        // 若只认 dy>0 会导致翻到页尾不自动加载下一页。这里只排除真正的向上滚动(dy<0)，
        // 允许 dy==0：向上滚时 last 变小、remaining 变大，不会误触发。
        if (dy < 0 || mLoadingMore || mTopic == null || PostListContent.POSTS.isEmpty()) {
          return;
        }
        // 用真实帖子数算剩余，不含末尾的「本贴结束」标记
        int postCount = PostListContent.POSTS.size();
        int last = linearLayoutManager.findLastVisibleItemPosition();
        int remaining = postCount - 1 - last;
        if (remaining <= 5 && mCurrentPageNo < mTopic.getTotalPageNo()) {
          loadNextPageAppend();
        }
      }

      @Override public void onScrollStateChanged(RecyclerView rv, int newState) {
        // 滚动停止后再校准一次结束标记高度：图片加载完成后末楼高度会变，
        // 只在插入时算一次会导致末楼顶不到标题。IDLE 时校准 + 防重入标记，避免布局循环。
        if (newState == RecyclerView.SCROLL_STATE_IDLE && mAdapter != null
            && mAdapter.isShowingEndMarker() && !mEndMarkerAdjusting) {
          mEndMarkerAdjusting = true;
          rv.post(() -> {
            mAdapter.adjustEndMarkerHeightNow();
            mEndMarkerAdjusting = false;
          });
        }
      }
    });

    // 手势监听挂在 RecyclerView 本身，覆盖 item 之间的灰色分割线区域：
    // 之前挂在单个 item view 上，点在分割线上不会触发翻页。
    // 长按定位仍由 RecyclerViewGestureListener 用 findChildViewUnder 完成，不受影响。
    mGestureListener = new RecyclerViewGestureListener(this, mRecyclerView);
    mGestureDetector = new GestureDetector(SMTHApplication.getAppContext(), mGestureListener);
    if (mRecyclerView != null) {
      mRecyclerView.setOnTouchListener(this);
    }

    // get Board information from launcher
    Intent intent = getIntent();
    Topic topic = intent.getParcelableExtra(SMTHApplication.TOPIC_OBJECT);
    assert topic != null;
    mFrom = intent.getStringExtra(SMTHApplication.FROM_BOARD);
    // now onCreateOptionsMenu(...) is called again
    // invalidateOptionsMenu();
    // Log.d(TAG, String.format("Load post list for topic = %s, source = %s",
    // topic.toString(), mFrom));

    // set onClick Lisetner for page navigator buttons
    findViewById(R.id.post_list_first_page).setOnClickListener(this);
    findViewById(R.id.post_list_last_page).setOnClickListener(this);
    findViewById(R.id.post_list_go_page).setOnClickListener(this);

    // 翻页栏按底栏实测高度留边距（右侧快速导航箭头已移除）
    applyBottomNavSpacing();

    if (mTopic == null || !mTopic.getTopicID().equals(topic.getTopicID()) || PostListContent.POSTS.size() == 0) {
      // new topic, different topic, or no post loaded
      mTopic = topic;
      mFilterUser = null;

      // 读取上次阅读进度：若有记录，直接从目标楼层所在页开始加载，加载完滚动过去
      mRestoreFloor = 0;
      mRestoreDone = false;
      ReadProgressManager.Progress progress = ReadProgressManager.getInstance()
          .get(mTopic.getBoardEngName(), mTopic.getTopicID());
      // 注意：此时 totalPageNo 还没从帖子内容解析出来（为0），不能拿它校验 page，
      // page 是否越界放到加载完成后的 onComplete 里判断。
      if (progress != null && progress.floor > 1 && progress.page > 0) {
        mRestoreFloor = progress.floor;
        mCurrentPageNo = progress.page;
      } else {
        mCurrentPageNo = 1;
      }
      reloadPostList();

      setTitle(mTopic.getBoardChsName() + " - 阅读文章");
    }
  }

  /** 让翻页栏底边距等于底栏实测高度，兼容各厂商字体度量 */
  private void applyBottomNavSpacing() {
    final View bottomNav = findViewById(R.id.bottom_nav);
    final View pageNav = findViewById(R.id.post_list_page_nav);
    if (bottomNav == null) {
      return;
    }
    bottomNav.post(() -> {
      int navHeight = bottomNav.getHeight();
      if (navHeight <= 0) {
        return;
      }
      if (pageNav != null && pageNav.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) pageNav.getLayoutParams();
        lp.bottomMargin = navHeight;
        pageNav.setLayoutParams(lp);
      }
    });
  }

  public void clearLoadingHints() {
    dismissProgress();

    if (mRefreshLayout.isRefreshing()) {
      mRefreshLayout.finishRefresh(100);
    }
    if (mRefreshLayout.isLoading()) {
      mRefreshLayout.finishLoadMore(100);
    }
  }

  public void reloadPostListWithoutAlert() {
    PostListContent.clear();
    mLoadingMore = false;
    if (mAdapter != null) {
      mAdapter.setShowEndMarker(false);
    }
    mRecyclerView.getAdapter().notifyDataSetChanged();

    loadPostListByPages();
  }

  /** 自动加载下一页：直接追加，不清空已有帖子 */
  private void loadNextPageAppend() {
    if (mLoadingMore || mTopic == null || mCurrentPageNo >= mTopic.getTotalPageNo()) {
      return;
    }
    mLoadingMore = true;
    mCurrentPageNo += 1;
    loadPostListByPages();
  }

  public void reloadPostList() {
    showProgress("加载文章中, 请稍候...");

    reloadPostListWithoutAlert();
  }

  public void loadPostListByPages() {
    final SMTHHelper helper = SMTHHelper.getInstance();
    helper.wService.getPostListByPage(mTopic.getTopicURL(), mTopic.getTopicID(), mCurrentPageNo, mFilterUser)
        .flatMap(new Function<ResponseBody, Observable<Post>>() {
          @Override
          public Observable<Post> apply(@NonNull ResponseBody responseBody) throws Exception {
            String response = responseBody.string();
            List<Post> posts = SMTHHelper.ParsePostListFromWWW(response, mTopic);
            return Observable.fromIterable(posts);
          }
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Observer<Post>() {
          @Override
          public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override
          public void onNext(@NonNull Post post) {
            // Log.d(TAG, post.toString());
            if (PostListContent.addItem(post)) {
              mRecyclerView.getAdapter().notifyItemInserted(PostListContent.POSTS.size() - 1);
            }
          }

          @Override
          public void onError(@NonNull Throwable e) {
            mLoadingMore = false;
            clearLoadingHints();
            if (e instanceof com.zfdang.zsmth_android.newsmth.GuestReadVerificationException) {
              android.util.Log.e(com.zfdang.zsmth_android.helpers.LoginThrottle.TAG_DBG, "PostList GOT GuestReadVerificationException (服务端说未登录/需验证) -> trySilentLoginThenReload");
              // 服务端返回「您未登录」或要求阅读验证：先在后台静默重登，
              // 成功则自动重载本页（用户无感）；需要验证码才弹可见 WebView 让用户处理。
              trySilentLoginThenReload();
              return;
            }
            Toast.makeText(SMTHApplication.getAppContext(), "加载失败！\n" + e.toString(), Toast.LENGTH_LONG).show();
          }

          @Override
          public void onComplete() {
            mLoadingMore = false;
            // 恢复时若记录的页码已超出实际页数导致本页为空，回退到第 1 页重新加载
            if (mRestoreFloor > 0 && !mRestoreDone && PostListContent.POSTS.isEmpty() && mCurrentPageNo > 1) {
              mCurrentPageNo = 1;
              mRestoreFloor = 0; // 放弃恢复，正常从头加载
              reloadPostListWithoutAlert();
              return;
            }
            String title = String.format("[%d/%d] %s", mCurrentPageNo, mTopic.getTotalPageNo(), mTopic.getTitle());
            mTitle.setText(title);
            mPageNo.setText(String.format("%d", mCurrentPageNo));

            // 已加载到最后一页 → 列表末尾显示「本贴结束」
            if (mAdapter != null) {
              mAdapter.setShowEndMarker(mCurrentPageNo >= mTopic.getTotalPageNo());
            }

            // 阅读进度恢复：目标页加载完成后滚动到上次离开的楼层
            if (mRestoreFloor > 0 && !mRestoreDone && !PostListContent.POSTS.isEmpty()) {
              int targetIndex = findFloorIndex(mRestoreFloor);
              if (targetIndex >= 0) {
                linearLayoutManager.scrollToPositionWithOffset(targetIndex, 0);
                mRestoreDone = true;
              } else {
                // 楼层不在本页：优先按 floor 算出它真正所在的页(每页10楼)。
                // 兼容旧版本存错的 page(如 floor=2 却存了 page=2)，直接跳到正确页重载。
                int correctPage = (mRestoreFloor + 9) / 10;
                if (correctPage != mCurrentPageNo && correctPage >= 1) {
                  mCurrentPageNo = correctPage;
                  reloadPostListWithoutAlert();
                  return; // 重载后 onComplete 会再走一次恢复逻辑
                } else if (mCurrentPageNo < mTopic.getTotalPageNo()) {
                  // 页对了但仍找不到(黑名单过滤等)，继续追加下一页再找
                  loadNextPageAppend();
                } else {
                  mRestoreDone = true; // 找不到就放弃，保持当前位置
                }
              }
            }

            // 点进帖子即算看过：第一次加载完成就落一条进度，列表 reply 数随之变蓝
            markAsReadOnEnter();

            clearLoadingHints();
          }
        });
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // http://stackoverflow.com/questions/10692755/how-do-i-hide-a-menu-item-in-the-actionbar
    getMenuInflater().inflate(R.menu.post_list_menu, menu);

    MenuItem item = menu.findItem(R.id.post_list_action_enter_board);
    if (SMTHApplication.FROM_BOARD_BOARD.equals(mFrom)) {
      // from BoardTopicActivity
      item.setVisible(false);
    } else if (SMTHApplication.FROM_BOARD_HOT.equals(mFrom)) {
      // from HotTopicFragment
      item.setVisible(true);
    }
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == android.R.id.home) {
      // This ID represents the Home or Up button. In the case of this
      // activity, the Up button is shown. Use NavUtils to allow users
      // to navigate up one level in the application structure. For
      // more details, see the Navigation pattern on Android Design:
      //
      // http://developer.android.com/design/patterns/navigation.html#up-vs-back
      //
      onBackPressed();
      return true;
    } else if (id == R.id.post_list_action_refresh) {
      reloadPostList();
    } else if (id == R.id.post_list_action_enter_board) {
      Board board = new Board();
      board.initAsBoard(mTopic.getBoardEngName(), mTopic.getBoardChsName(), "", "");
      Intent intent = new Intent(this, BoardTopicActivity.class);
      intent.putExtra(SMTHApplication.BOARD_OBJECT, (Parcelable) board);
      startActivity(intent);
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onClick(View v) {
    // page navigation buttons
    switch (v.getId()) {
      case R.id.post_list_first_page:
        if (mCurrentPageNo == 1) {
          Toast.makeText(PostListActivity.this, "已在首页！", Toast.LENGTH_SHORT).show();
        } else {
          mCurrentPageNo = 1;
          reloadPostList();
        }
        break;
      case R.id.post_list_last_page:
        if (mCurrentPageNo == mTopic.getTotalPageNo()) {
          Toast.makeText(PostListActivity.this, "已在末页！", Toast.LENGTH_SHORT).show();
        } else {
          mCurrentPageNo = mTopic.getTotalPageNo();
          reloadPostList();
        }
        break;
      case R.id.post_list_go_page:
        int pageNo;
        try {
          pageNo = Integer.parseInt(mPageNo.getText().toString());
          if (mCurrentPageNo == pageNo) {
            Toast.makeText(PostListActivity.this, String.format("已在第%d页！", pageNo), Toast.LENGTH_SHORT).show();
          } else if (pageNo >= 1 && pageNo <= mTopic.getTotalPageNo()) {
            mCurrentPageNo = pageNo;
            // turn off keyboard
            mPageNo.clearFocus();
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(mPageNo.getWindowToken(), 0);
            // jump now
            reloadPostList();
          } else {
            Toast.makeText(PostListActivity.this, "非法页码！", Toast.LENGTH_SHORT).show();
          }
        } catch (Exception e) {
          Toast.makeText(PostListActivity.this, "非法输入！", Toast.LENGTH_SHORT).show();
        }
        break;
    }
  }

  public void goToNextPage() {
    if (mTopic != null && mCurrentPageNo >= mTopic.getTotalPageNo()) {
      Toast.makeText(PostListActivity.this, "已在末页！", Toast.LENGTH_SHORT).show();
      clearLoadingHints();
    } else {
      loadNextPageAppend();
    }
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (Settings.getInstance().isVolumeKeyScroll()
        && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
      // 音量键翻页与点击翻页统一走楼层对齐逻辑（短帖对齐下一楼，长帖滚一屏）
      if (mGestureListener != null) {
        mGestureListener.scrollToAdjacentPost(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN);
      }
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  // http://stackoverflow.com/questions/4500354/control-volume-keys
  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    // disable the beep sound when volume up/down is pressed
    if (Settings.getInstance().isVolumeKeyScroll()
        && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  /**
   * 直接拉起 WebView 登录页（自动填已保存的账号密码，用户只需处理验证码）。
   * 登录成功 setResult(OK)，onActivityResult 收到后会自动重载本页。
   */
  /**
   * 服务端返回「您未登录」时：先在后台静默重登，成功就自动重载本页，用户无感。
   * 只有需要验证码或失败时才弹可见登录页/提示。
   */
  private void trySilentLoginThenReload() {
    android.util.Log.d(com.zfdang.zsmth_android.helpers.LoginThrottle.TAG_DBG, "trySilentLoginThenReload ENTER, activityFinishing=" + isFinishing() + " destroyed=" + isDestroyed());
    com.zfdang.zsmth_android.helpers.LoginThrottle.dumpState("PostList.trySilentLogin-enter");
    // 点帖子收到「未登录/阅读验证」= 检测到 cookie 失效，立即清登录成功锁，允许重登
    com.zfdang.zsmth_android.helpers.LoginThrottle.onCookieLost();
    // 防抖：被踢下线时多个请求/多个页面可能同时失败，30 秒内只走一次登录流程，
    // 避免「正在重新登录」提示排队弹出很多次、以及重复提交登录。
    long now = System.currentTimeMillis();
    if (now - sLastSilentLoginAttempt < SILENT_LOGIN_DEBOUNCE_MS) {
      android.util.Log.d(com.zfdang.zsmth_android.helpers.LoginThrottle.TAG_DBG, "trySilentLoginThenReload SKIP: debounce " + (now - sLastSilentLoginAttempt) + "ms < " + SILENT_LOGIN_DEBOUNCE_MS + "ms");
      return;
    }
    sLastSilentLoginAttempt = now;

    Toast.makeText(getApplicationContext(), "正在重新登录...", Toast.LENGTH_SHORT).show();
    SilentLoginHelper.getInstance().login(new SilentLoginHelper.Callback() {
      @Override public void onSuccess(String userId) {
        android.util.Log.d(com.zfdang.zsmth_android.helpers.LoginThrottle.TAG_DBG, "PostList SilentLogin.onSuccess userId=" + userId + ", activityFinishing=" + isFinishing());
        runOnUiThread(() -> {
          // 同步登录态到全局，避免其它页面仍认为未登录
          SMTHApplication.displayedUserId = userId;
          if (SMTHApplication.activeUser == null) {
            SMTHApplication.activeUser = new com.zfdang.zsmth_android.newsmth.UserStatus();
          }
          SMTHApplication.activeUser.setId(userId);
          SMTHApplication.activeUser.setIs_login(true);
          reloadPostList();
        });
      }
      @Override public void onCaptchaRequired() {
        android.util.Log.d(com.zfdang.zsmth_android.helpers.LoginThrottle.TAG_DBG, "PostList SilentLogin.onCaptchaRequired");
        runOnUiThread(() -> {
          // 需要验证码=静默登录过不去：设app未登录态，停掉后台自动登录，弹可见WebView让用户手工处理
          com.zfdang.zsmth_android.helpers.LoginThrottle.onAnyLoginFailure("captcha");
          launchLoginPage();
        });
      }
      @Override public void onFailed(String reason) {
        android.util.Log.e(com.zfdang.zsmth_android.helpers.LoginThrottle.TAG_DBG, "PostList SilentLogin.onFailed reason=" + reason + ", activityFinishing=" + isFinishing());
        runOnUiThread(() -> {
          // cooldown/no_credentials 是预期内跳过(非真实登录失败)，只提示、不改登录态
          if ("cooldown".equals(reason) || "no_credentials".equals(reason)) {
            showLoginRequiredDialog("登录已过期，请重新登录");
            return;
          }
          // 真实失败：设app为未登录状态(停自动登录/清登录态)，退回手动登录
          com.zfdang.zsmth_android.helpers.LoginThrottle.onAnyLoginFailure(reason);
          showLoginRequiredDialog("登录已失效，请手动登录后重试");
        });
      }
    });
  }

  private void launchLoginPage() {
    // 异步回调可能在 Activity 已销毁后到达，此时 startActivity 会抛异常
    if (isFinishing() || isDestroyed()) {
      android.util.Log.w("PostListActivity", "launchLoginPage skipped: activity destroyed");
      return;
    }
    // 底部短暂提示：正在自动重新登录（Toast 用 app context，跳到登录页后仍会显示）
    Toast.makeText(getApplicationContext(), "正在重新登录...", Toast.LENGTH_LONG).show();

    String username = Settings.getInstance().getUsername();
    String password = Settings.getInstance().getPassword();
    Intent intent = new Intent(this, WebviewLoginActivity.class);
    intent.putExtra(LoginActivity.USERNAME, username);
    intent.putExtra(LoginActivity.PASSWORD, password);
    try {
      startActivityForResult(intent, LOGIN_REQUEST_CODE);
    } catch (Throwable t) {
      // 万一登录页起不来，退回手动登录弹窗
      showLoginRequiredDialog("请登录水木账号后重试");
    }
  }

  /** 静默重登也救不回来时，回退到手动登录弹窗 */
  private void showLoginRequiredDialog(String message) {
    // 静默登录是异步回调(WebView onPageFinished)，可能在 Activity 已退出/切后台后才返回。
    // 此时弹 Dialog 会因 window token 失效抛 BadTokenException 崩溃(实测07:58崩溃堆栈)。
    if (isFinishing() || isDestroyed()) {
      android.util.Log.w("PostListActivity", "showLoginRequiredDialog skipped: activity destroyed");
      return;
    }
    try {
      new androidx.appcompat.app.AlertDialog.Builder(PostListActivity.this)
          .setTitle("需要登录或阅读验证")
          .setMessage(message)
          .setPositiveButton("登录", (dialog, which) ->
              startActivityForResult(new Intent(PostListActivity.this, LoginActivity.class), LOGIN_REQUEST_CODE))
          .setNegativeButton("取消", null)
          .show();
    } catch (android.view.WindowManager.BadTokenException e) {
      android.util.Log.w("PostListActivity", "showLoginRequiredDialog BadToken ignored", e);
    }
  }

  /**
   * 长按落在帖内图片上：弹「查看大图 / 分享图片」菜单。
   * 图片短按不拦截事件，交给楼层手势层做翻页。
   */
  @Override public boolean onImageLongClicked(final int position, final int imageIndex) {
    if (position == RecyclerView.NO_POSITION || position >= PostListContent.POSTS.size()) {
      return false;
    }
    final Post post = PostListContent.POSTS.get(position);
    new AlertDialog.Builder(this)
        .setTitle("图片操作")
        .setItems(new String[]{"查看大图", "分享图片"}, (dialog, which) -> {
          if (which == 0) {
            Intent intent = new Intent(this, FSImageViewerActivity.class);
            ArrayList<String> urls = new ArrayList<>();
            List<Attachment> attaches = post.getAttachFiles();
            if (attaches != null) {
              for (Attachment attach : attaches) {
                urls.add(attach.getOriginalImageSource());
              }
            }
            if (urls.isEmpty()) {
              return;
            }
            int idx = imageIndex;
            if (idx < 0 || idx >= urls.size()) {
              idx = 0;
            }
            intent.putStringArrayListExtra(SMTHApplication.ATTACHMENT_URLS, urls);
            intent.putExtra(SMTHApplication.ATTACHMENT_CURRENT_POS, idx);
            startActivity(intent);
          } else {
            List<Attachment> attaches = post.getAttachFiles();
            if (imageIndex >= 0 && attaches != null && imageIndex < attaches.size()) {
              ShareImageHelper.shareImage(this, attaches.get(imageIndex).getOriginalImageSource());
            }
          }
        })
        .setNegativeButton("取消", null)
        .show();
    return true;
  }

  @Override
  public boolean onEndMarkerClicked() {
    // 点「本贴结束」空白区域 → 返回进帖子前的界面（信息流或版面帖子列表）
    onBackPressed();
    return true;
  }

  public boolean onItemLongClicked(final int position, View v) {
    if (position == RecyclerView.NO_POSITION || position >= PostListContent.POSTS.size())
      return false;
    // Log.d(TAG, String.format("Post by %s is long clicked",
    // PostListContent.POSTS.get(position).getAuthor()));
    List<PostActionAlertDialogItem> menuItemsArray = new ArrayList<PostActionAlertDialogItem>();
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_reply_post), R.drawable.ic_reply_black_48dp, ACT_REPLY_POST));
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_like_post), R.drawable.like_black, ACT_LIKE));
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_query_author), R.drawable.ic_person_black_48dp, ACT_QUERY_AUTHOR));
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_filter_author), R.drawable.ic_find_in_page_black_48dp, ACT_FILTER_AUTHOR));
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_copy_content), R.drawable.ic_content_copy_black_48dp, ACT_COPY_CONTENT));
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_foward), R.drawable.ic_send_black_48dp, ACT_FORWARD));
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_view_in_browser), R.drawable.ic_open_in_browser_black_48dp, ACT_VIEW_IN_BROWSER));
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_delete_post), R.drawable.ic_delete_black_48dp, ACT_DELETE_POST));
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_edit_post), R.drawable.ic_edit_black_48dp, ACT_EDIT_POST));
    // 把发帖人加入黑名单（图标复用白色 block，用 tint 染成主题色）
    menuItemsArray.add(new PostActionAlertDialogItem(
        getString(R.string.post_block_author), R.drawable.ic_mine_block, ACT_BLOCK_AUTHOR));

    if (Settings.getInstance().isBoardMasterOnly()) {
      menuItemsArray.add(new PostActionAlertDialogItem(getString(R.string.post_topic_delete),
          R.drawable.baseline_admin_panel_settings_black_48, ACT_TOPIC_DELETE));
      menuItemsArray.add(new PostActionAlertDialogItem(getString(R.string.post_ban_id),
          R.drawable.baseline_admin_panel_settings_black_48, ACT_BAN_ID));
      menuItemsArray.add(new PostActionAlertDialogItem(getString(R.string.post_mark_m),
          R.drawable.baseline_admin_panel_settings_black_48, ACT_MARK_M));
      menuItemsArray.add(new PostActionAlertDialogItem(getString(R.string.post_topic_readonly),
          R.drawable.baseline_admin_panel_settings_black_48, ACT_TOPIC_READONLY));
    }

    PostActionAlertDialogItem[] menuItems = new PostActionAlertDialogItem[menuItemsArray.size()];
    menuItemsArray.toArray(menuItems);

    ListAdapter adapter = new ArrayAdapter<PostActionAlertDialogItem>(getApplicationContext(),
        R.layout.post_popup_menu_item, menuItems) {
      ViewHolder holder;

      public View getView(int position, View convertView, ViewGroup parent) {
        final LayoutInflater inflater = (LayoutInflater) getApplicationContext()
            .getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        if (convertView == null) {
          convertView = inflater.inflate(R.layout.post_popup_menu_item, null);

          holder = new ViewHolder();
          holder.mIcon = (ImageView) convertView.findViewById(R.id.post_popupmenu_icon);
          holder.mTitle = (TextView) convertView.findViewById(R.id.post_popupmenu_title);
          convertView.setTag(holder);
        } else {
          // view already defined, retrieve view holder
          holder = (ViewHolder) convertView.getTag();
        }

        holder.mTitle.setText(menuItems[position].text);
        holder.mIcon.setImageResource(menuItems[position].icon);
        return convertView;
      }

      class ViewHolder {
        ImageView mIcon;
        TextView mTitle;
      }
    };

    AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.post_alert_title))
        .setAdapter(adapter, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            onPostPopupMenuItem(position, menuItems[which].action);
          }
        })
        .create();
    dialog.setCanceledOnTouchOutside(true);
    dialog.setCancelable(true);

    dialog.show();
    return true;
  }

  private void onPostPopupMenuItem(int position, int action) {
    if (position >= PostListContent.POSTS.size()) {
      Log.e(TAG, "onPostPopupMenuItem: " + "Invalid Post index" + position);
      return;
    }

    Post post = PostListContent.POSTS.get(position);
    if (action == ACT_REPLY_POST) {
      ComposePostContext postContext = new ComposePostContext();
      postContext.setBoardEngName(mTopic.getBoardEngName());
      postContext.setPostId(post.getPostID());
      postContext.setPostTitle(mTopic.getTitle());
      postContext.setPostAuthor(post.getRawAuthor());
      postContext.setPostContent(post.getRawContent());
      postContext.setComposingMode(ComposePostContext.MODE_REPLY_POST);
      Intent intent = new Intent(this, ComposePostActivity.class);
      intent.putExtra(SMTHApplication.COMPOSE_POST_CONTEXT, postContext);
      startActivityForResult(intent, ComposePostActivity.COMPOSE_ACTIVITY_REQUEST_CODE);
    } else if (action == ACT_LIKE) {
      PopupLikeWindow popup = new PopupLikeWindow();
      popup.initPopupWindow(this);
      popup.showAtLocation(mRecyclerView, Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL, 0, 100);
    } else if (action == ACT_QUERY_AUTHOR) {
      Intent intent = new Intent(this, QueryUserActivity.class);
      intent.putExtra(SMTHApplication.QUERY_USER_INFO, post.getRawAuthor());
      startActivity(intent);
    } else if (action == ACT_FILTER_AUTHOR) {
      if (mFilterUser == null) {
        Toast.makeText(PostListActivity.this, "只看此ID! 再次选择将查看所有文章.", Toast.LENGTH_SHORT).show();
        mFilterUser = post.getRawAuthor();
      } else {
        Toast.makeText(PostListActivity.this, "查看所有文章!", Toast.LENGTH_SHORT).show();
        mFilterUser = null;
      }
      mCurrentPageNo = 1;
      reloadPostList();
    } else if (action == ACT_COPY_CONTENT) {
      // 展示标题+完整正文，用户长按用系统文字选择器自由复制
      if (post == null || TextUtils.isEmpty(post.getRawContent())) {
        Toast.makeText(PostListActivity.this, "复制失败！", Toast.LENGTH_SHORT).show();
        return;
      }
      // 去掉内部富文本标记(###ZSMTH_QUOTE###/###ZSMTH_SMALL###)，不暴露给用户
      String body = post.getRawContent()
          .replace("###ZSMTH_QUOTE###", "")
          .replace("###ZSMTH_SMALL###", "");
      // 过滤掉「※ 来源:」「※ 修改:」行（兼容全角冒号），并去掉行内首尾空白
      StringBuilder filtered = new StringBuilder();
      for (String line : body.split("\\r?\\n")) {
        String trimmed = line.trim();
        if (trimmed.startsWith("※ 来源:") || trimmed.startsWith("※ 来源：")
            || trimmed.startsWith("※ 修改:") || trimmed.startsWith("※ 修改：")) {
          continue;
        }
        if (trimmed.isEmpty() && filtered.length() == 0) continue;
        if (filtered.length() > 0) filtered.append('\n');
        filtered.append(trimmed);
      }
      // 连续换行压缩为最多一个，避免复制出大量空行
      body = filtered.toString().replaceAll("[\\r\\n]+", "\n").trim();
      // 只有楼主(第1楼)带标题，其余楼层只复制正文
      int floor = com.zfdang.zsmth_android.helpers.ReadProgressManager.firstNumber(post.getPosition());
      String fullText;
      if (floor <= 1) {
        String title = post.getTitle();
        if (TextUtils.isEmpty(title) && mTopic != null) title = mTopic.getTitle();
        if (!TextUtils.isEmpty(title)) {
          fullText = title + "\n" + body;
        } else {
          fullText = body;
        }
      } else {
        fullText = body;
      }
      try {
        new com.zfdang.zsmth_android.helpers.CopyTextDialog(PostListActivity.this, fullText).show();
      } catch (Throwable t) {
        // 兜底：选择框起不来就整段复制到剪贴板
        copyTextToClipboard(fullText);
        Toast.makeText(PostListActivity.this, "帖子内容已复制到剪贴板", Toast.LENGTH_SHORT).show();
      }
    } else if (action == ACT_FORWARD) {
      PopupForwardWindow popup = new PopupForwardWindow();
      popup.initPopupWindow(this, post);
      popup.showAtLocation(mRecyclerView, Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL, 0, 100);
    } else if (action == ACT_VIEW_IN_BROWSER) {
      // 「查询本帖IP」：从帖子正文的 FROM 行提取 IP(水木隐藏最后一位)，末位补1后用 ip138 查询
      String raw = post.getRawContent();
      java.util.regex.Matcher m = java.util.regex.Pattern
          .compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.(\\d{1,3}|\\*)")
          .matcher(raw == null ? "" : raw);
      String ip = null;
      if (m.find()) {
        ip = m.group(1) + ".1"; // 末位隐藏，补1
      }
      if (ip != null) {
        String url = "https://www.ip138.com/iplookup.asp?ip=" + ip + "&action=2";
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
      } else {
        Toast.makeText(this, "本帖未找到 IP 信息（可能不是本站发表的帖子）", Toast.LENGTH_SHORT).show();
      }
    } else if (action == ACT_DELETE_POST) {
      deletePost(post);
    } else if (action == ACT_EDIT_POST) {
      ComposePostContext postContext = new ComposePostContext();
      postContext.setBoardEngName(mTopic.getBoardEngName());
      postContext.setPostId(post.getPostID());
      postContext.setPostTitle(mTopic.getTitle());
      postContext.setPostAuthor(post.getRawAuthor());
      postContext.setPostContent(post.getRawContent());
      postContext.setComposingMode(ComposePostContext.MODE_EDIT_POST);
      Intent intent = new Intent(this, ComposePostActivity.class);
      intent.putExtra(SMTHApplication.COMPOSE_POST_CONTEXT, postContext);
      startActivity(intent);
    } else if (action == ACT_BLOCK_AUTHOR) {
      String author = post.getRawAuthor();
      if (TextUtils.isEmpty(author)) {
        Toast.makeText(this, "无法获取发帖人", Toast.LENGTH_SHORT).show();
        return;
      }
      BlockListManager block = BlockListManager.getInstance();
      boolean added = block.add(BlockListManager.TYPE_USER, author);
      if (added) {
        Toast.makeText(this, "已把 " + author + " 加入黑名单", Toast.LENGTH_SHORT).show();
      } else {
        Toast.makeText(this, author + " 已在黑名单中", Toast.LENGTH_SHORT).show();
      }
    } else if (action == ACT_TOPIC_DELETE) {
      deleteTopic(post);
    } else if (action == ACT_BAN_ID) {
      PopupBanWindow popup = new PopupBanWindow();
      popup.initPopupWindow(this, post);
      popup.showAtLocation(mRecyclerView, Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL, 0, 100);
    } else if (action == ACT_MARK_M) {
      markPost(post);
    } else if (action == ACT_TOPIC_READONLY) {
      readonlyTopic(post);
    }
  }

  /**
   * 根据楼层号找它在 POSTS 里的 index。
   * 用 Post.position("第N楼")精确匹配，避免黑名单过滤导致列表 index 与楼层号错位。
   */
  private int findFloorIndex(int floor) {
    for (int i = 0; i < PostListContent.POSTS.size(); i++) {
      Post p = PostListContent.POSTS.get(i);
      if (p != null && ReadProgressManager.firstNumber(p.getPosition()) == floor) {
        return i;
      }
    }
    return -1;
  }

  /** 当前屏幕顶部可见的楼层号（0 表示取不到） */
  private int currentVisibleFloor() {
    if (linearLayoutManager == null) return 0;
    int first = linearLayoutManager.findFirstVisibleItemPosition();
    if (first < 0 || first >= PostListContent.POSTS.size()) return 0;
    return ReadProgressManager.firstNumber(PostListContent.POSTS.get(first).getPosition());
  }

  /**
   * 首次加载完成时调用：只要点进帖子就算"看过"。
   * 若该帖还没有进度记录，立即记一条（楼层兜底 1=楼主层，快照=当前回帖数），
   * 这样即使只看楼主层就返回，列表里也会变蓝。已有记录（恢复场景）不覆盖。
   */
  private void markAsReadOnEnter() {
    if (mTopic == null) return;
    ReadProgressManager manager = ReadProgressManager.getInstance();
    if (manager.get(mTopic.getBoardEngName(), mTopic.getTopicID()) != null) {
      return; // 已看过/有恢复进度，不动
    }
    int replyCount = ReadProgressManager.replyCountOf(mTopic);
    manager.save(mTopic.getBoardEngName(), mTopic.getTopicID(), 1, replyCount, 1);
  }

  /** 退出时保存阅读进度：楼层 + 当前回帖数快照 + 所在页 */
  private void saveReadingProgress() {
    if (mTopic == null || mCurrentPageNo <= 0) return;
    int floor = currentVisibleFloor();
    if (floor <= 0) floor = 1; // 停在楼主层或取不到时按第1楼记，保证退出也算看过
    int replyCount = ReadProgressManager.replyCountOf(mTopic);
    // page 必须按楼层号推导（每页10楼），不能用 mCurrentPageNo：
    // 自动加载下一页后 mCurrentPageNo 会+1，但用户可能还在看上一页的楼层，
    // 存成 mCurrentPageNo 会导致恢复时按错误的页加载、找不到目标楼层。
    int page = (floor + 9) / 10;
    ReadProgressManager.getInstance().save(mTopic.getBoardEngName(), mTopic.getTopicID(),
        floor, replyCount, page);
  }

  /** 整段文字复制到剪贴板（大爆炸选择框的兜底路径） */
  private void copyTextToClipboard(String content) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      android.content.ClipboardManager cm =
          (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      cm.setPrimaryClip(android.content.ClipData.newPlainText("PostContent", content));
    } else {
      android.text.ClipboardManager cm =
          (android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      cm.setText(content);
    }
  }

  void captureView(View v1, View v2, String postID) {
    capView1 = v1;
    capView2 = v2;
    capPostID = postID;
    captureViewInternal();
  }

  // @AfterPermissionGranted(RC_READ_WRITE_STORAGE)
  void captureViewInternal() {
    // String[] perms = {Manifest.permission.READ_EXTERNAL_STORAGE,
    // Manifest.permission.WRITE_EXTERNAL_STORAGE};
    // if (EasyPermissions.hasPermissions(this, perms)) {
    // Already have permission, do the thing
    // Create a Bitmap with the same dimensions
    Bitmap image = Bitmap.createBitmap(capView1.getWidth(), capView1.getHeight() + capView2.getHeight(),
        Bitmap.Config.RGB_565);
    // Draw the view inside the Bitmap
    Canvas canvas = new Canvas(image);

    if (Settings.getInstance().isNightMode()) {
      canvas.drawColor(Color.BLACK);
    } else {
      canvas.drawColor(Color.WHITE);
    }
    capView1.draw(canvas);
    canvas.translate(0, capView1.getHeight());
    capView2.draw(canvas);
    canvas.save();

    String IMAGE_FILE_PREFIX = "post-";
    String IMAGE_FILE_SUFFIX = ".jpg";
    String fileName = IMAGE_FILE_PREFIX + capPostID + IMAGE_FILE_SUFFIX;

    com.zfdang.zsmth_android.helpers.FileSaveUtils.saveBitmapToGallery(this, image, fileName);

    // } else {
    // // Do not have permissions, request them now
    // EasyPermissions.requestPermissions(this,
    // getString(R.string.read_write_storage_rationale),
    // RC_READ_WRITE_STORAGE, perms);
    // }
  }

  public void deletePost(Post post) {
    SMTHHelper helper = SMTHHelper.getInstance();

    helper.wService.deletePost(mTopic.getBoardEngName(), post.getPostID()).map(new Function<ResponseBody, String>() {
      @Override
      public String apply(@NonNull ResponseBody responseBody) throws Exception {
        try {
          String response = SMTHHelper.DecodeResponseFromWWW(responseBody.bytes());
          return SMTHHelper.parseDeleteResponse(response);
        } catch (Exception e) {
          Log.e(TAG, "call: " + Log.getStackTraceString(e));
        }
        return null;
      }
    }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(new Observer<String>() {
      @Override
      public void onSubscribe(@NonNull Disposable disposable) {

      }

      @Override
      public void onNext(@NonNull String s) {
        Toast.makeText(PostListActivity.this, s, Toast.LENGTH_LONG).show();
      }

      @Override
      public void onError(@NonNull Throwable e) {
        Toast.makeText(PostListActivity.this, "删除帖子失败！\n" + e.toString(), Toast.LENGTH_LONG).show();

      }

      @Override
      public void onComplete() {

      }
    });
  }

  public void deleteTopic(Post post) {
    SMTHHelper helper = SMTHHelper.getInstance();

    helper.wService.deleteTopic(mTopic.getBoardEngName(), post.getPostID(), mTopic.getTopicID(), "d")
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Observer<AjaxResponse>() {
          @Override
          public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override
          public void onNext(@NonNull AjaxResponse ajaxResponse) {
            if (ajaxResponse.getAjax_st() == AjaxResponse.AJAX_RESULT_OK) {
              Toast.makeText(PostListActivity.this, ajaxResponse.getAjax_msg(), Toast.LENGTH_SHORT).show();
            } else {
              Toast.makeText(PostListActivity.this, ajaxResponse.toString(), Toast.LENGTH_LONG).show();
            }
          }

          @Override
          public void onError(@NonNull Throwable e) {
            Toast.makeText(PostListActivity.this, "删除主题失败！\n" + e.toString(), Toast.LENGTH_LONG).show();

          }

          @Override
          public void onComplete() {

          }
        });
  }

  public void OnBanIDAction(Post post, String banReason, Integer day) {
    SMTHHelper helper = SMTHHelper.getInstance();

    helper.wService.banID(mTopic.getBoardEngName(), post.getPostID(), banReason, day)
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Observer<AjaxResponse>() {
          @Override
          public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override
          public void onNext(@NonNull AjaxResponse ajaxResponse) {
            if (ajaxResponse.getAjax_st() == AjaxResponse.AJAX_RESULT_OK) {
              Toast.makeText(PostListActivity.this, ajaxResponse.getAjax_msg(), Toast.LENGTH_SHORT).show();
            } else {
              Toast.makeText(PostListActivity.this, ajaxResponse.toString(), Toast.LENGTH_LONG).show();
            }
          }

          @Override
          public void onError(@NonNull Throwable e) {
            Toast.makeText(PostListActivity.this, "封禁ID失败！\n" + e.toString(), Toast.LENGTH_LONG).show();

          }

          @Override
          public void onComplete() {

          }
        });
  }

  public void sharePost(Post post) {
    String title = String.format("[%s] %s @ 水木社区", mTopic.getBoardChsName(), mTopic.getTitle());
    String postURL = String.format(SMTHHelper.SMTH_MOBILE_URL + "/article/%s/%s?p=%d",
        mTopic.getBoardEngName(), mTopic.getTopicID(), mCurrentPageNo);
    String content = String.format("[%s]在大作中写到: %s", post.getAuthor(), post.getRawContent());
    if (content.length() > 110) {
      content = content.substring(0, 110) + "…";
    }
    final String summary = content;
    final String text = title + "\n" + summary + "\n" + postURL;
    new AlertDialog.Builder(this)
        .setTitle("分享帖子")
        .setItems(new String[]{"微信好友", "微信朋友圈", "短信", "邮件"}, (dialog, which) -> {
          if (which < 2) {
            WeChatShare.share(this, title, summary, postURL, which == 1);
            return;
          }
          Intent intent;
          if (which == 2) {
            intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"));
            intent.putExtra("sms_body", text);
          } else {
            intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:?subject="
                + Uri.encode(title) + "&body=" + Uri.encode(text)));
            intent.putExtra(Intent.EXTRA_SUBJECT, title);
            intent.putExtra(Intent.EXTRA_TEXT, text);
          }
          try {
            startActivity(intent);
          } catch (android.content.ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, which == 2 ? "未找到可用的短信应用" : "未找到可用的邮件应用",
                Toast.LENGTH_LONG).show();
          }
        }).show();
  }

  @Override
  public boolean onTouch(View v, MotionEvent event) {
    mGestureDetector.onTouchEvent(event);
    return false;
  }

  @Override
  public void OnLikeAction(String score, String msg) {
    // Log.d(TAG, "OnLikeAction: " + score + msg);

    SMTHHelper helper = SMTHHelper.getInstance();
    helper.wService.addLike(mTopic.getBoardEngName(), mTopic.getTopicID(), score, msg, "")
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Observer<AjaxResponse>() {
          @Override
          public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override
          public void onNext(@NonNull AjaxResponse ajaxResponse) {
            // Log.d(TAG, "onNext: " + ajaxResponse.toString());
            if (ajaxResponse.getAjax_st() == AjaxResponse.AJAX_RESULT_OK) {
              Toast.makeText(PostListActivity.this, ajaxResponse.getAjax_msg(), Toast.LENGTH_SHORT).show();
              reloadPostList();
            } else {
              Toast.makeText(PostListActivity.this, ajaxResponse.toString(), Toast.LENGTH_LONG).show();
            }
          }

          @Override
          public void onError(@NonNull Throwable e) {
            Toast.makeText(PostListActivity.this, "增加Like失败!\n" + e.toString(), Toast.LENGTH_LONG).show();
          }

          @Override
          public void onComplete() {

          }
        });
  }

  @Override
  public void OnForwardAction(Post post, String target, boolean threads, boolean noref, boolean noatt) {
    // Log.d(TAG, "OnForwardAction: ");

    String strThreads = null;
    if (threads)
      strThreads = "on";
    String strNoref = null;
    if (noref)
      strNoref = "on";
    String strNoatt = null;
    if (noatt)
      strNoatt = "on";
    String strNoansi = null;
    if (target != null && target.contains("@"))
      strNoansi = "on";

    SMTHHelper helper = SMTHHelper.getInstance();
    helper.wService
        .forwardPost(mTopic.getBoardEngName(), post.getPostID(), target, strThreads, strNoref, strNoatt, strNoansi)
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Observer<AjaxResponse>() {
          @Override
          public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override
          public void onNext(@NonNull AjaxResponse ajaxResponse) {
            // Log.d(TAG, "onNext: " + ajaxResponse.toString());
            if (ajaxResponse.getAjax_st() == AjaxResponse.AJAX_RESULT_OK) {
              Toast.makeText(PostListActivity.this, ajaxResponse.getAjax_msg(), Toast.LENGTH_SHORT).show();
            } else {
              Toast.makeText(PostListActivity.this, ajaxResponse.toString(), Toast.LENGTH_LONG).show();
            }
          }

          @Override
          public void onError(@NonNull Throwable e) {
            Toast.makeText(PostListActivity.this, "转寄失败！\n" + e.toString(), Toast.LENGTH_LONG).show();
          }

          @Override
          public void onComplete() {

          }
        });
  }

  @Override
  public void OnRePostAction(Post post, String target, String outgo) {
    SMTHHelper helper = SMTHHelper.getInstance();
    helper.wService.repostPost(mTopic.getBoardEngName(), post.getPostID(), target, outgo)
        .map(new Function<ResponseBody, String>() {
          @Override
          public String apply(@NonNull ResponseBody responseBody) throws Exception {
            try {
              String response = SMTHHelper.DecodeResponseFromWWW(responseBody.bytes());
              return SMTHHelper.parseRepostResponse(response);
            } catch (Exception e) {
              Log.e(TAG, "call: " + Log.getStackTraceString(e));
            }
            return null;
          }
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(new Observer<String>() {
          @Override
          public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override
          public void onNext(@NonNull String s) {
            Toast.makeText(SMTHApplication.getAppContext(), s, Toast.LENGTH_SHORT).show();
          }

          @Override
          public void onError(@NonNull Throwable e) {
            Toast.makeText(SMTHApplication.getAppContext(), e.toString(), Toast.LENGTH_LONG).show();
          }

          @Override
          public void onComplete() {

          }
        });
  }

  public void markPost(Post post) {
    SMTHHelper helper = SMTHHelper.getInstance();

    helper.wService.markPost(mTopic.getBoardEngName(), post.getPostID(), mTopic.getTopicID(), "m")
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Observer<AjaxResponse>() {
          @Override
          public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override
          public void onNext(@NonNull AjaxResponse ajaxResponse) {
            if (ajaxResponse.getAjax_st() == AjaxResponse.AJAX_RESULT_OK) {
              Toast.makeText(PostListActivity.this, ajaxResponse.getAjax_msg(), Toast.LENGTH_SHORT).show();
            } else {
              Toast.makeText(PostListActivity.this, ajaxResponse.toString(), Toast.LENGTH_LONG).show();
            }
          }

          @Override
          public void onError(@NonNull Throwable e) {
            Toast.makeText(PostListActivity.this, "设置/取消m标记！\n" + e.toString(), Toast.LENGTH_LONG).show();

          }

          @Override
          public void onComplete() {

          }
        });
  }

  public void readonlyTopic(Post post) {
    SMTHHelper helper = SMTHHelper.getInstance();

    helper.wService.readonlyTopic(mTopic.getBoardEngName(), post.getPostID(), mTopic.getTopicID(), ";")
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Observer<AjaxResponse>() {
          @Override
          public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override
          public void onNext(@NonNull AjaxResponse ajaxResponse) {
            if (ajaxResponse.getAjax_st() == AjaxResponse.AJAX_RESULT_OK) {
              Toast.makeText(PostListActivity.this, ajaxResponse.getAjax_msg(), Toast.LENGTH_SHORT).show();
            } else {
              Toast.makeText(PostListActivity.this, ajaxResponse.toString(), Toast.LENGTH_LONG).show();
            }
          }

          @Override
          public void onError(@NonNull Throwable e) {
            Toast.makeText(PostListActivity.this, "设置同主题不可回复！\n" + e.toString(), Toast.LENGTH_LONG).show();

          }

          @Override
          public void onComplete() {

          }
        });
  }
}
