package com.zfdang.zsmth_android;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import com.mikepenz.aboutlibraries.LibsBuilder;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.fresco.WrapContentDraweeView;
import com.zfdang.zsmth_android.listeners.OnBoardFragmentInteractionListener;
import com.zfdang.zsmth_android.listeners.OnMailInteractionListener;
import com.zfdang.zsmth_android.listeners.OnTopicFragmentInteractionListener;
import com.zfdang.zsmth_android.listeners.OnVolumeUpDownListener;
import com.zfdang.zsmth_android.models.Board;
import com.zfdang.zsmth_android.models.Mail;
import com.zfdang.zsmth_android.models.MailListContent;
import com.zfdang.zsmth_android.models.Topic;
import com.zfdang.zsmth_android.newsmth.AjaxResponse;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;
import com.zfdang.zsmth_android.services.KeepAliveService;
import com.zfdang.zsmth_android.services.MaintainUserStatusWorker;
import com.zfdang.zsmth_android.services.UserStatusReceiver;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class MainActivity extends SMTHBaseActivity
    implements NavigationView.OnNavigationItemSelectedListener, View.OnClickListener, OnTopicFragmentInteractionListener,
    OnBoardFragmentInteractionListener, OnMailInteractionListener {
  // used by startActivityForResult
  static final int LOGIN_ACTIVITY_REQUEST_CODE = 9527;  // The request code
  private static final String TAG = "MainActivity";

  // guidance fragment: display hot topics
  // this fragment is using RecyclerView to show all hot topics
  HotTopicFragment hotTopicFragment = null;
  FeedFragment feedFragment = null;
  FavoriteBoardFragment favoriteBoardFragment = null;
  AllBoardFragment allBoardFragment = null;
  MailListFragment mailListFragment = null;
  MineFragment mineFragment = null;
  MyPreferenceFragment preferenceFragment = null;
  Fragment aboutFragment = null;
  private WrapContentDraweeView mAvatar = null;
  private TextView mUsername = null;

  private DrawerLayout mDrawer = null;
  private ActionBarDrawerToggle mToggle = null;

  private UserStatusReceiver mReceiver;
  // press BACK in 2 seconds, app will quit
  private boolean mDoubleBackToExit = false;
  private Handler mHandler = null;
  private NavigationView mNavigationView;
  private BottomNavigationView mBottomNav;

  /** 双击信息流标签刷新：记录上一次点击的时间戳 */
  private long mLastFeedTabTap = 0;
  private static final long FEED_DOUBLE_TAP_INTERVAL = 500L;

  private static final int notificationID = 273;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() { handleBackAction(); }
    });
    setContentView(R.layout.activity_main);
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    // how to adjust the height of toolbar
    // http://stackoverflow.com/questions/17439683/how-to-change-action-bar-size
    // zsmth_actionbar_size @ dimen ==> ThemeOverlay.ActionBar @ styles ==> theme @ app_bar_main.xml

    // init bottom navigation bar (replaces the old floating action menu)
    mBottomNav = findViewById(R.id.bottom_nav);
    setupBottomNavigation();
    applyTargetTabFromIntent(getIntent());
    // 恢复上次停留的标签高亮，建 Activity 时底栏默认会选中第一个
    MenuItem currentTab = mBottomNav.getMenu().findItem(SMTHApplication.currentTabId);
    if (currentTab != null) {
      currentTab.setChecked(true);
    }

    mDrawer = findViewById(R.id.drawer_layout);
    mToggle = new ActionBarDrawerToggle(this, mDrawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
    mDrawer.addDrawerListener(mToggle);
    mToggle.syncState();

    mNavigationView = findViewById(R.id.nav_view);
    mNavigationView.setNavigationItemSelectedListener(this);
    mNavigationView.setCheckedItem(R.id.nav_guidance);

    // http://stackoverflow.com/questions/33161345/android-support-v23-1-0-update-breaks-navigationview-get-find-header
    View headerView = mNavigationView.getHeaderView(0);
    mAvatar = headerView.findViewById(R.id.nav_user_avatar);
    mAvatar.setOnClickListener(this);

    mUsername = headerView.findViewById(R.id.nav_user_name);
    mUsername.setOnClickListener(this);

    // http://stackoverflow.com/questions/27097126/marquee-title-in-toolbar-actionbar-in-android-with-lollipop-sdk
    TextView titleTextView;
    try {
      Field f = toolbar.getClass().getDeclaredField("mTitleTextView");
      f.setAccessible(true);
      titleTextView = (TextView) f.get(toolbar);
      assert titleTextView != null;
      titleTextView.setEllipsize(TextUtils.TruncateAt.START);
    } catch (NoSuchFieldException e) {
    } catch (IllegalAccessException e) {
    }

    // init all fragments
    initFragments();

    FragmentManager fm = getSupportFragmentManager();
    // 字体大小调节等操作会 recreate 当前 Activity，这里恢复上次停留的标签，避免总是跳回首页
    int initialTab = SMTHApplication.currentTabId;
    if (initialTab == R.id.nav_mine || initialTab == R.id.nav_all_boards) {
      onNavigationItemID(initialTab);
    } else if (Settings.getInstance().isLaunchHotTopic()) {
      Fragment initialFragment = FeedConfig.getInstance().isEnabled() ? feedFragment : hotTopicFragment;
      fm.beginTransaction().replace(R.id.content_frame, initialFragment).commit();
      setTitle(SMTHApplication.App_Title_Prefix + "信息流");
    } else {
      fm.beginTransaction().replace(R.id.content_frame, favoriteBoardFragment).commit();
      setTitle(SMTHApplication.App_Title_Prefix + "收藏夹");
    }

    getSupportFragmentManager().addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
      public void onBackStackChanged() {
        //Enable Up button only  if there are entries in the back stack
        boolean canback = getSupportFragmentManager().getBackStackEntryCount() > 0;
        if (canback) {
          mToggle.setDrawerIndicatorEnabled(false);
          ActionBar bar = getSupportActionBar();
          if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setHomeButtonEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
          }
        } else {
          ActionBar bar = getSupportActionBar();
          if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(false);
          }
          mToggle.setDrawerIndicatorEnabled(true);
          mDrawer.addDrawerListener(mToggle);
        }
      }
    });

    WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(MaintainUserStatusWorker.class.getName());
    // setup receiver to receive user status update from periodical background service
    setupUserStatusReceiver();

    // schedule the periodical background service
    Data.Builder inputData = new Data.Builder();
    inputData.putBoolean(MaintainUserStatusWorker.REPEAT, true);
    OneTimeWorkRequest userStatusWorkRequest =
            new OneTimeWorkRequest.Builder(MaintainUserStatusWorker.class)
                    .setInitialDelay(SMTHApplication.INTERVAL_TO_CHECK_MESSAGE, TimeUnit.MINUTES)
                    .setInputData(inputData.build())
                    .build();
    WorkManager.getInstance(getApplicationContext())
            .enqueueUniqueWork(MaintainUserStatusWorker.WORKER_ID, ExistingWorkPolicy.KEEP,userStatusWorkRequest);

    // run the background service now
    updateUserStatusNow();
    UpdateNavigationViewHeader();

    if (!AnalyticsPrivacy.hasChoice(this)) {
      showAnalyticsPrivacyDialog();
    } else if (Settings.getInstance().isFirstRun()) {
      // show info dialog after 5 seconds for the first run
      final Handler handler = new Handler();
      handler.postDelayed(new Runnable() {
        @Override public void run() {
          showInfoDialog();
        }
      }, 1000);
    }

  }

  private void showAnalyticsPrivacyDialog() {
    new AlertDialog.Builder(this)
        .setTitle("隐私与可选统计")
        .setMessage("本应用使用友盟统计 SDK 分析启动次数和使用时长。只有您选择同意后才会初始化并向友盟发送设备标识、设备与网络信息、IP 地址和使用统计；不向友盟发送水木账号、密码、帖子或私信内容。\n\n统计为可选功能，拒绝后仍可正常使用论坛。您可随时从菜单‘隐私与统计’修改选择，更改选择后请重启应用。")
        .setPositiveButton("同意统计", (dialog, which) -> AnalyticsPrivacy.saveChoice(this, true))
        .setNegativeButton("不使用统计", (dialog, which) -> AnalyticsPrivacy.saveChoice(this, false))
        .setNeutralButton("隐私政策", (dialog, which) -> {
          startActivity(new Intent(Intent.ACTION_VIEW,
              android.net.Uri.parse("https://zsmth.zfdang.com/privacy.html")));
        })
        .setCancelable(false)
        .show();
  }

  // triger the background service right now
  private void updateUserStatusNow() {
    // run worker immediately for once
    WorkRequest userStatusWorkRequest =
            new OneTimeWorkRequest.Builder(MaintainUserStatusWorker.class).build();
    WorkManager.getInstance(getApplicationContext()).enqueue(userStatusWorkRequest);
  }

  private void setupUserStatusReceiver() {
    mReceiver = new UserStatusReceiver(new Handler());
    mReceiver.setReceiver(new UserStatusReceiver.Receiver() {
      @Override public void onReceiveResult(int resultCode, Bundle resultData) {
        if (resultCode == RESULT_OK) {
          //Log.d(TAG, "onReceiveResult: " + "to update navigationview" + SMTHApplication.activeUser.toString());
          UpdateNavigationViewHeader();

          // show notification if necessary
          String message = resultData.getString(SMTHApplication.SERVICE_NOTIFICATION_MESSAGE);
          //Log.d(TAG, "OnReceiveResult" + message);
          if (message != null && message.contains(SMTHApplication.NOTIFICATION_LOGIN_CAPTCHA)) {
            // 后台静默登录遇到验证码：直接弹可见 WebView 让用户处理，不走通知栏
            launchCaptchaLoginPage();
          } else if (message != null) {
            showNotification(message);
          }
        }
      }

      @Override
      public void onServerFailed() {
        KeepAliveService.stop(MainActivity.this);
        SMTHApplication.activeUser = null;
        SMTHApplication.displayedUserId = "guest";
        runOnUiThread(() -> {
          mUsername.setText(getString(R.string.nav_header_click_to_login));
          mAvatar.setImageResource(R.drawable.ic_person_black_48dp);
        });
      }
    });
    SMTHApplication.mUserStatusReceiver = mReceiver;
  }

  private void init_keep_alive_service() {
    KeepAliveService.start(this);
  }

  private void showNotification(String text) {
    if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      return;
    }
    // http://stackoverflow.com/questions/13602190/java-lang-securityexception-requires-vibrate-permission-on-jelly-bean-4-2
    try {
      Intent notificationIntent = new Intent(MainActivity.this, MainActivity.class);
      notificationIntent.putExtra(SMTHApplication.SERVICE_NOTIFICATION_MESSAGE, text);
      // http://stackoverflow.com/questions/26608627/how-to-open-fragment-page-when-pressed-a-notification-in-android
      notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      // notification will be handled by MainActivity.onNewIntent
      PendingIntent resultPendingIntent =
          PendingIntent.getActivity(MainActivity.this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

      Notification.Builder mBuilder = new Notification.Builder(this).setSmallIcon(R.drawable.ic_launcher)
          .setContentTitle("zSMTH提醒")
          .setWhen(System.currentTimeMillis())
          .setAutoCancel(true)
          .setOnlyAlertOnce(true)
          .setDefaults(Notification.DEFAULT_VIBRATE)
          .setContentText(text)
          .setContentIntent(resultPendingIntent);

      NotificationManager mNotifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        mBuilder.setChannelId(getPackageName()); //必须添加（Android 8.0） 【唯一标识】
        NotificationChannel channel = new NotificationChannel(
                getPackageName(),
                "zSMTH通知消息",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        mNotifyMgr.createNotificationChannel(channel);
      }

      Notification notification = mBuilder.build();
      mNotifyMgr.notify(notificationID, notification);
    } catch (Exception se) {
      Log.e(TAG, "showNotification: " + se);
    }
  }

  @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (Settings.getInstance().isVolumeKeyScroll() && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
      Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
      if (fragment instanceof OnVolumeUpDownListener) {
        OnVolumeUpDownListener frag = (OnVolumeUpDownListener) fragment;
        return frag.onVolumeUpDown(keyCode);
      }
      return false;
    }

    return super.onKeyDown(keyCode, event);
  }

  // http://stackoverflow.com/questions/4500354/control-volume-keys
  @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
    // disable the beep sound when volume up/down is pressed
    if (Settings.getInstance().isVolumeKeyScroll() && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  @Override protected void onPause() {
    super.onPause();
    AnalyticsPrivacy.onPause(this);
  }

  /** 从其他页面的底栏跳转回来时，切到指定的标签 */
  private void applyTargetTabFromIntent(Intent intent) {
    if (intent == null || mNavigationView == null) {
      return;
    }
    int targetTab = intent.getIntExtra(EXTRA_TARGET_TAB, 0);
    if (targetTab != 0) {
      onNavigationItemID(targetTab);
      mNavigationView.setCheckedItem(targetTab);
      if (mBottomNav != null) {
        mBottomNav.setSelectedItemId(targetTab);
      }
      intent.removeExtra(EXTRA_TARGET_TAB);
    }
  }

  @Override protected void onBottomNavItemSelected(int itemId) {
    onNavigationItemID(itemId);
    mNavigationView.setCheckedItem(itemId);
  }

  /**
   * 重复点击已选中的标签。双击「信息流」时刷新当前页——信息流开着就重新抓取所选版面，
   * 关着（显示十大导读）就刷新导读。单击不处理，避免误触。
   */
  @Override protected void onBottomNavItemReselected(int itemId) {
    if (itemId != R.id.nav_guidance || mNavigationView == null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - mLastFeedTabTap < FEED_DOUBLE_TAP_INTERVAL) {
      mLastFeedTabTap = 0;
      refreshCurrentPage();
    } else {
      mLastFeedTabTap = now;
    }
  }

  @Override protected void onNewIntent(Intent intent) {
    // this method will be triggered by showNotification(message);
    super.onNewIntent(intent);
    applyTargetTabFromIntent(intent);
    FragmentManager fm = getSupportFragmentManager();
    Bundle bundle = intent.getExtras();
    if (bundle != null) {
      // this activity is launched by notification, show mail fragment now
      // http://www.androiddesignpatterns.com/2013/08/fragment-transaction-commit-state-loss.html
      // http://stackoverflow.com/questions/7575921/illegalstateexception-can-not-perform-this-action-after-onsaveinstancestate-wit
      // java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState
      String message = bundle.getString(SMTHApplication.SERVICE_NOTIFICATION_MESSAGE);
      if (message != null) {
        if (message.contains(SMTHApplication.NOTIFICATION_LOGIN_CAPTCHA)) {
          // 验证码场景：点通知栏进来时也直接拉可见 WebView
          launchCaptchaLoginPage();
        } else if (message.contains(SMTHApplication.NOTIFICATION_LOGIN_LOST)) {
          // login status lost, show login menu
          onLogin();
        } else {
          // find the actual folder for the new message
          if (message.contains(SMTHApplication.NOTIFICATION_NEW_MAIL)) {
            mailListFragment.setCurrentFolder(MailListFragment.INBOX_LABEL);
          } else if (message.contains(SMTHApplication.NOTIFICATION_NEW_LIKE)) {
            mailListFragment.setCurrentFolder(MailListFragment.LIKE_LABEL);
          } else if (message.contains(SMTHApplication.NOTIFICATION_NEW_AT)) {
            mailListFragment.setCurrentFolder(MailListFragment.AT_LABEL);
          } else if (message.contains(SMTHApplication.NOTIFICATION_NEW_REPLY)) {
            mailListFragment.setCurrentFolder(MailListFragment.REPLY_LABEL);
          }
          // force mail fragment to reload
          MailListContent.clear();

          fm.beginTransaction().replace(R.id.content_frame, mailListFragment).commitAllowingStateLoss();
          // switch title of mainActivity
          setTitle(SMTHApplication.App_Title_Prefix + "邮件");
          // 收到新消息通知，同步刷新「我的」tab 红点
          updateReplyBadge();
        }
      }
    }
  }

  @Override protected void onResume() {
    super.onResume();
    AnalyticsPrivacy.onResume(this);
    syncGuidanceFragment();
    updateReplyBadge();
  }

  /**
   * 底部「我的」tab 红点：服务端 new_reply 有未读回复时显示纯红点（不显示数字）。
   * 用户点了「回复我的」里的「全部已读」后会调 clearReplyBadge() 立即清掉。
   */
  public void updateReplyBadge() {
    if (mBottomNav == null) return;
    boolean hasUnread = SMTHApplication.isValidUser()
        && SMTHApplication.activeUser != null
        && SMTHApplication.activeUser.hasNewReply();
    // getOrCreateBadge 要求 MaterialComponents 主题，本项目是 AppCompat 主题，
    // 调用会抛 IllegalArgumentException。这里兜底捕获：主题不兼容时跳过红点，
    // 绝不能让它崩掉主流程（登录成功后刷新 UI 会走到这里）。
    try {
      if (hasUnread) {
        BadgeDrawable badge = mBottomNav.getOrCreateBadge(R.id.nav_mine);
        badge.clearNumber(); // 不显示数字，只显示纯红点
        badge.setVisible(true);
      } else {
        BadgeDrawable badge = mBottomNav.getBadge(R.id.nav_mine);
        if (badge != null) badge.setVisible(false);
      }
    } catch (Throwable t) {
      android.util.Log.w(TAG, "updateReplyBadge skipped (theme incompatible): " + t.getMessage());
    }
  }

  /** 全部已读后本地清掉未读标记并隐藏红点（不必等服务端的 2 分钟轮询） */
  public void clearReplyBadge() {
    if (SMTHApplication.isValidUser() && SMTHApplication.activeUser != null) {
      SMTHApplication.activeUser.setNew_reply("0");
    }
    updateReplyBadge();
  }

  /** 信息流开关可能刚从设置页改过，回到该标签时切到对应的页面 */
  private void syncGuidanceFragment() {
    if (SMTHApplication.currentTabId != R.id.nav_guidance || hotTopicFragment == null || feedFragment == null) {
      return;
    }
    Fragment current = getSupportFragmentManager().findFragmentById(R.id.content_frame);
    // 用户可能正停在设置/关于等抽屉页面，这时不要抢着换掉
    if (current != hotTopicFragment && current != feedFragment) {
      return;
    }
    boolean feedEnabled = FeedConfig.getInstance().isEnabled();
    if (feedEnabled && current != feedFragment) {
      getSupportFragmentManager().beginTransaction()
          .replace(R.id.content_frame, feedFragment)
          .commitAllowingStateLoss();
      setTitle(SMTHApplication.App_Title_Prefix + "信息流");
    } else if (!feedEnabled && current == feedFragment) {
      getSupportFragmentManager().beginTransaction()
          .replace(R.id.content_frame, hotTopicFragment)
          .commitAllowingStateLoss();
      setTitle(SMTHApplication.App_Title_Prefix + "信息流");
    }
  }

  protected void initFragments() {
    hotTopicFragment = new HotTopicFragment();
    feedFragment = new FeedFragment();

    // following initilization can be delayed
    favoriteBoardFragment = new FavoriteBoardFragment();
    allBoardFragment = new AllBoardFragment();
    mailListFragment = new MailListFragment();
    mineFragment = new MineFragment();

    preferenceFragment = new MyPreferenceFragment();
    aboutFragment = new LibsBuilder().supportFragment();
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
//    Log.d(TAG, "receive login result, requestCode = " + requestCode);
    if (requestCode == LOGIN_ACTIVITY_REQUEST_CODE) {
//      Log.d(TAG, "receive login result, resultCode = " + resultCode);
      updateUserStatusNow();
    }
  }

  @Override public boolean onPrepareOptionsMenu(Menu menu) {
    MenuItem login = menu.findItem(R.id.main_action_login);
    MenuItem logout = menu.findItem(R.id.main_action_logout);
    if (SMTHApplication.isValidUser()) {
      login.setVisible(false);
      logout.setVisible(true);
    } else {
      login.setVisible(true);
      logout.setVisible(false);
    }
    return super.onPrepareOptionsMenu(menu);
  }

  // update header view in navigation header
  public void UpdateNavigationViewHeader() {
    // update optionMenu
    getWindow().invalidatePanelMenu(Window.FEATURE_OPTIONS_PANEL);

    if (SMTHApplication.isValidUser()) {
      // update user to logined user
      mUsername.setText(SMTHApplication.activeUser.getId());
      String faceURL = SMTHApplication.activeUser.getFace_url();
      if (faceURL != null) {
        mAvatar.setImageFromStringURL(faceURL);
      }
      SMTHApplication.displayedUserId = SMTHApplication.activeUser.getId();
      init_keep_alive_service();
    } else {
      // when user is invalid, set notice to login
      mUsername.setText(getString(R.string.nav_header_click_to_login));
      mAvatar.setImageResource(R.drawable.ic_person_black_48dp);
      SMTHApplication.displayedUserId = "guest";
      KeepAliveService.stop(this);
    }

    // 「我的」页面也在展示用户信息，同步刷新
    if (mineFragment != null) {
      mineFragment.refreshUserInfo();
    }
    updateReplyBadge();
  }

  private void handleBackAction() {
    if (mDrawer.isDrawerOpen(GravityCompat.START)) {
      mDrawer.closeDrawer(GravityCompat.START);
      return;
    }

    // handle back button for all fragment
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
    if (fragment instanceof FavoriteBoardFragment) {
      if (!favoriteBoardFragment.isAtRoot()) {
        favoriteBoardFragment.popPath();
        favoriteBoardFragment.RefreshFavoriteBoards();
        return;
      }
    }

    if (fragment != hotTopicFragment) {
      // 优先回到当前底栏标签对应的页面（例如从「我的」进入站内信/关于后返回，应回到「我的」）
      Fragment target = hotTopicFragment;
      String title = "首页导读";
      int tab = SMTHApplication.currentTabId;
      if (tab == R.id.nav_mine && fragment != mineFragment) {
        target = mineFragment;
        title = "我的";
      } else if (tab == R.id.nav_all_boards && fragment != allBoardFragment) {
        target = allBoardFragment;
        title = "所有版面";
      } else if (tab == R.id.nav_favorite && fragment != favoriteBoardFragment) {
        target = favoriteBoardFragment;
        title = "收藏夹";
      } else if (tab == R.id.nav_guidance && FeedConfig.getInstance().isEnabled() && fragment != feedFragment) {
        target = feedFragment;
        title = "信息流";
      }

      FragmentManager fm = getSupportFragmentManager();
      fm.beginTransaction().replace(R.id.content_frame, target).commit();
      setTitle(SMTHApplication.App_Title_Prefix + title);
      return;
    }

    // for other cases, double back to exit app
    DoubleBackToExit();
  }

  private void DoubleBackToExit() {
    if (mDoubleBackToExit) {
      // if mDoubleBackToExit is true, exit now
      quitNow();
    } else {
      // set mDoubleBackToExit = true, and set delayed task to
      // reset it to false
      mDoubleBackToExit = true;
      if (mHandler == null) {
        mHandler = new Handler();
      }
      // reset will be run after 2000 ms
      mHandler.postDelayed(new PendingDoubleBackToExit(), 2000);
      Toast.makeText(this, "再按一次退出zSMTH", Toast.LENGTH_SHORT).show();
    }
  }

  private void quitNow() {
    // stop keep alive service
    KeepAliveService.stop(this);

    // quit
    finish();
    android.os.Process.killProcess(android.os.Process.myPid());
    System.exit(0);
  }

  // show information dialog, called by first run
  private void showInfoDialog() {
    // read version info from androidmanifest.xml
    String versionName = "unknown";
    int versionCode = 0;
    PackageManager pm = getPackageManager();
    try {
      PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
      versionName = pi.versionName;
      versionCode = pi.versionCode;
    } catch (PackageManager.NameNotFoundException e) {
      Log.e(TAG, "showInfoDialog: " + Log.getStackTraceString(e));
    }

    // generate about_content with version from manifest
    String content_with_version = getString(R.string.about_content, versionName, versionCode);

    // linkify
    final SpannableString msg = new SpannableString(content_with_version);
    Linkify.addLinks(msg, Linkify.WEB_URLS);

    final AlertDialog dlg =
        new AlertDialog.Builder(this).setIcon(R.drawable.ic_launcher).setTitle(R.string.about_title).setMessage(msg).create();

    dlg.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.about_close), new DialogInterface.OnClickListener() {

      @Override public void onClick(DialogInterface dialog, int which) {
        // do nothing here
      }
    });

    dlg.show();

    // Make the textview clickable. Must be called after show()
    ((TextView) dlg.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());
  }

  @Override public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.main_menu, menu);
    return true;
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    int id = item.getItemId();
    if (id == R.id.main_action_refresh) {
      refreshCurrentPage();
      return true;
    } else if (id == R.id.main_action_notifications) {
      if (Build.VERSION.SDK_INT >= 33) {
        androidx.core.app.ActivityCompat.requestPermissions(this,
            new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1028);
      } else {
        Toast.makeText(this, "请在系统应用设置中管理消息通知", Toast.LENGTH_SHORT).show();
      }
      return true;
    } else if (id == R.id.main_action_privacy) {
      showAnalyticsPrivacyDialog();
      return true;
    } else if (id == R.id.main_action_child_safety) {
      startActivity(new Intent(this, ChildSafetyActivity.class));
      return true;
    } else if (id == R.id.main_action_login) {
      onLogin();
      return true;
    } else if (id == R.id.main_action_logout) {
      onLogout();
      return true;
    } else if (id == android.R.id.home) {
      this.handleBackAction();
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  /** 标题栏的刷新按钮：按当前停留的页面分别刷新 */
  private void refreshCurrentPage() {
    Fragment current = getSupportFragmentManager().findFragmentById(R.id.content_frame);
    if (current == null) {
      return;
    }
    if (current == feedFragment) {
      feedFragment.refreshFromMenu();
    } else if (current == hotTopicFragment) {
      hotTopicFragment.RefreshGuidanceFromWWW();
    } else if (current == favoriteBoardFragment) {
      favoriteBoardFragment.RefreshFavoriteBoardsWithCache();
    } else if (current == allBoardFragment) {
      allBoardFragment.LoadAllBoardsWithoutCache();
    } else if (current == mineFragment) {
      mineFragment.refreshUserInfo();
    }
  }

  public void onLogin() {
    // still use the previous login method
    Intent intent = new Intent(this, LoginActivity.class);
    startActivityForResult(intent, LOGIN_ACTIVITY_REQUEST_CODE);
  }

  /**
   * 后台静默登录遇到验证码时调用：直接用已保存的账号密码拉起可见的 WebView 登录页，
   * 页面会自动填表，用户只需过验证码。登录成功后回到本页刷新状态。
   */
  private void launchCaptchaLoginPage() {
    String username = Settings.getInstance().getUsername();
    String password = Settings.getInstance().getPassword();
    if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
      // 没有保存的账号，退回普通登录页让用户输入
      onLogin();
      return;
    }
    try {
      Intent intent = new Intent(this, WebviewLoginActivity.class);
      intent.putExtra(LoginActivity.USERNAME, username);
      intent.putExtra(LoginActivity.PASSWORD, password);
      startActivityForResult(intent, LOGIN_ACTIVITY_REQUEST_CODE);
    } catch (Throwable t) {
      android.util.Log.e(TAG, "launch captcha login page failed", t);
      onLogin();
    }
  }

  public void onLogout() {
    Settings.getInstance().setUserOnline(false);
    if (SMTHApplication.activeUser != null) {
      SMTHApplication.activeUser.setId("guest");
    }
    UpdateNavigationViewHeader();

    SMTHHelper helper = SMTHHelper.getInstance();
    helper.wService.logout()
        .observeOn(AndroidSchedulers.mainThread())
        .subscribeOn(Schedulers.io())
        .subscribe(new Observer<AjaxResponse>() {
          @Override public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override public void onNext(@NonNull AjaxResponse ajaxResponse) {
            Toast.makeText(MainActivity.this, ajaxResponse.getAjax_msg(), Toast.LENGTH_LONG).show();
          }

          @Override public void onError(@NonNull Throwable e) {
            Toast.makeText(MainActivity.this, "退出登录失败!\n" + e.toString(), Toast.LENGTH_LONG).show();
          }

          @Override public void onComplete() {

          }
        });
  }

  @SuppressWarnings("StatementWithEmptyBody") @Override public boolean onNavigationItemSelected(MenuItem item) {
    int id = item.getItemId();
    return onNavigationItemID(id);
  }

  public boolean onNavigationItemID(int menuID) {
    // Handle navigation view item clicks here.
    Fragment fragment = null;
    String title = "";

    if (menuID == R.id.nav_guidance) {
      // 信息流开关关闭时仍然显示老的十大导读
      if (FeedConfig.getInstance().isEnabled()) {
        fragment = feedFragment;
      } else {
        fragment = hotTopicFragment;
      }
      title = "信息流";
    } else if (menuID == R.id.nav_favorite) {
      fragment = favoriteBoardFragment;
      title = "收藏夹";
    } else if (menuID == R.id.nav_all_boards) {
      fragment = allBoardFragment;
      title = "所有版面";
    } else if (menuID == R.id.nav_mail) {
      fragment = mailListFragment;
      title = "邮件";
    } else if (menuID == R.id.nav_mine) {
      fragment = mineFragment;
      title = "我的";
    } else if (menuID == R.id.nav_setting) {
      //            fragment = settingFragment;
      fragment = preferenceFragment;
      title = "设置";
    } else if (menuID == R.id.nav_block) {
      // 屏蔽设置是独立页面, 不切换 fragment
      startActivity(new Intent(this, BlockSettingsActivity.class));
    } else if (menuID == R.id.nav_about) {
      fragment = aboutFragment;
      title = "关于";
    } else if(menuID == R.id.nav_night_mode) {
      boolean bNightMode = Settings.getInstance().isNightMode();
      Settings.getInstance().setNightMode(!bNightMode);
      setApplicationNightMode();
    }

    // switch fragment
    if (fragment != null) {
      FragmentManager fm = getSupportFragmentManager();
      fm.beginTransaction().replace(R.id.content_frame, fragment).commit();

      // favorite board will manage the title by itself. its title will vary depending on folder path
      if (fragment != favoriteBoardFragment) {
        setTitle(SMTHApplication.App_Title_Prefix + title);
      }
    }

    // 底栏上的四个主标签，切换时同步高亮，避免抽屉和底栏状态不一致
    // 注意：站内信(nav_mail)已经从底栏移到「我的」页面里，不再作为底栏标签
    boolean isBottomTab = menuID == R.id.nav_guidance || menuID == R.id.nav_all_boards
        || menuID == R.id.nav_favorite || menuID == R.id.nav_mine;
    if (isBottomTab) {
      SMTHApplication.currentTabId = menuID;
    }
    if (mBottomNav != null && isBottomTab) {
      Menu bottomMenu = mBottomNav.getMenu();
      MenuItem target = bottomMenu.findItem(menuID);
      if (target != null && !target.isChecked()) {
        target.setChecked(true);
      }
    }

    mDrawer.closeDrawer(GravityCompat.START);
    return true;
  }

  public void setApplicationNightMode() {
    boolean bNightMode = Settings.getInstance().isNightMode();
    if (bNightMode) {
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    } else {
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }

    Activity activity = this;
    Intent intent = new Intent(activity.getApplicationContext(), MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    startActivity(intent);
    activity.finish();
  }

  public void testCodes() {
  }

  @Override public void onClick(View v) {
    int id = v.getId();
    if (id == R.id.nav_user_avatar || id == R.id.nav_user_name) {
      // 点击图标或者文字，都弹出登录对话框或者profiel对话框
      mDrawer.closeDrawer(GravityCompat.START);
      if (SMTHApplication.activeUser != null && !SMTHApplication.activeUser.getId().equals("guest")) {
        Intent intent = new Intent(this, QueryUserActivity.class);
        intent.putExtra(SMTHApplication.QUERY_USER_INFO, SMTHApplication.activeUser.getId());
        startActivity(intent);
      } else {
        onLogin();
      }
    }
  }

  @Override public void onTopicFragmentInteraction(Topic item) {
    // will be triggered in HotTopicFragment
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
    if (fragment == hotTopicFragment) {
      if (item.isCategory) return;
      Intent intent = new Intent(this, PostListActivity.class);
      intent.putExtra(SMTHApplication.TOPIC_OBJECT, item);
      intent.putExtra(SMTHApplication.FROM_BOARD, SMTHApplication.FROM_BOARD_HOT);
      startActivity(intent);
    }
  }

  @Override public void onMailInteraction(Mail item, int position) {
    if (item.isCategory) return;

    // mark item as readed
    mailListFragment.markMailAsReaded(position);
    // MailListFragment
    Intent intent = new Intent(this, MailContentActivity.class);
    intent.putExtra(SMTHApplication.MAIL_OBJECT, item);
    startActivity(intent);
  }

  @Override public void onBoardFragmentInteraction(Board item) {
    // shared by FavoriteBoard & AllBoard fragment
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
    Log.d(TAG, item.toString());
    if (fragment == favoriteBoardFragment) {
      // favorite fragment, we might enter a folder or section
      if (item.isFolder() || item.isSection()) {
          favoriteBoardFragment.pushPath(item);
          favoriteBoardFragment.RefreshFavoriteBoards();
          return;
      }
    }

    // if it's a normal board, show postlist in the board
    if(item.isBoard()) {
      startBoardTopicActivity(item);
    }
  }

  @Override public void onBoardLongClick(final Board board) {
    // shared by FavoriteBoard & AllBoard fragment
    // long click to remove board from favorite
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
    if (fragment == favoriteBoardFragment) {
      // favorite fragment, remove the board
      if (board.isBoard()) {
        // confirm dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        String title = String.format("将版面\"%s\"从收藏夹中删除么？", board.getBoardName());
        builder.setTitle("收藏夹操作").setMessage(title);

        builder.setPositiveButton("删除", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();

            SMTHHelper helper = SMTHHelper.getInstance();
            helper.wService.manageFavoriteBoard(favoriteBoardFragment.getCurrentPathInString(), "db", board.getBoardEngName())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<AjaxResponse>() {
                  @Override public void onSubscribe(@NonNull Disposable disposable) {
                  }

                  @Override public void onNext(@NonNull AjaxResponse ajaxResponse) {
                    Log.d(TAG, "onNext: " + ajaxResponse.toString());
                    if (ajaxResponse.getAjax_st() == AjaxResponse.AJAX_RESULT_OK) {
                      Toast.makeText(MainActivity.this, ajaxResponse.getAjax_msg() + "\n" + "请刷新收藏夹！", Toast.LENGTH_SHORT).show();
                    } else {
                      Toast.makeText(MainActivity.this, ajaxResponse.toString(), Toast.LENGTH_LONG).show();
                    }
                  }

                  @Override public void onError(@NonNull Throwable e) {
                    Toast.makeText(MainActivity.this, "删除收藏版面失败！\n" + e.toString(), Toast.LENGTH_LONG).show();
                  }

                  @Override public void onComplete() {
                  }
                });
          }
        });
        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();
          }
        });
        AlertDialog noticeDialog = builder.create();
        noticeDialog.show();
      }
    }
  }

  public void startBoardTopicActivity(Board board) {
    Intent intent = new Intent(this, BoardTopicActivity.class);
    intent.putExtra(SMTHApplication.BOARD_OBJECT, (Parcelable) board);
    startActivity(intent);
  }

  class PendingDoubleBackToExit implements Runnable {
    public void run() {
      mDoubleBackToExit = false;
    }
  }
}
