package com.zfdang;

import android.app.Application;
import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.backends.okhttp3.OkHttpImagePipelineConfigFactory;
import com.facebook.imagepipeline.core.ImagePipelineConfig;
import com.zfdang.zsmth_android.R;
import com.zfdang.zsmth_android.Settings;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;
import com.zfdang.zsmth_android.newsmth.UserStatus;
import com.zfdang.zsmth_android.services.UserStatusReceiver;

import okhttp3.OkHttpClient;
import androidx.multidex.MultiDex;

/**
 * Created by zfdang on 2016-3-18.
 */
public class SMTHApplication extends Application {
  // http://blog.csdn.net/lieren666/article/details/7598288
  // Android Application的作用
  private static Context context;
  // 顶部标题只显示页面名（信息流/收藏夹/邮件等），不再加 "RzSMTH - " 前缀
  public static String App_Title_Prefix = "";

  /** 当前主页标签 id，供子页面底栏保持同一个高亮项 */
  public static int currentTabId = R.id.nav_guidance;

  public static final String FROM_BOARD = "From_Board";
  public static final String FROM_BOARD_HOT = "FROM_HOTTOPICS";
  public static final String FROM_BOARD_BOARD = "FROM_BOARDTOPICS";
  public static final String ATTACHMENT_URLS = "ATTACHMENT_URLS";
  public static final String ATTACHMENT_CURRENT_POS = "ATTACHMENT_CURRENT_POS";
  public static final String QUERY_USER_INFO = "QUERY_USER_ID";
  public static final String BOARD_OBJECT = "BOARD_OBJECT";
  public static final String TOPIC_OBJECT = "TOPIC_OBJECT";

  public static final String MAIL_OBJECT = "MAIL_OBJECT";

  // MaintainUserStatusService to UserStatusReceiver, to onNewIntent
  public static final String SERVICE_NOTIFICATION_MESSAGE = "SERVICE_NOTIFICATION_MESSAGE";

  public static final String USER_SERVICE_RECEIVER = "USER_SERVICE_RECEIVER";

  public static final String COMPOSE_POST_CONTEXT = "Compose_Post_Context";

  public static final String NOTIFICATION_NEW_MAIL = "你有新邮件!";
  public static final String NOTIFICATION_NEW_AT = "你有新@!";
  public static final String NOTIFICATION_NEW_REPLY = "你有新回复!";
  public static final String NOTIFICATION_NEW_LIKE = "你有新Like!";
  public static final String NOTIFICATION_LOGIN_LOST = "登录已过期！请重新登录...";
  // 后台静默登录遇到验证码：页面层收到后弹可见 WebView 让用户处理
  public static final String NOTIFICATION_LOGIN_CAPTCHA = "需要验证码，请完成验证...";

  public static final int INTERVAL_TO_CHECK_MESSAGE = 2; // 2 minutes for interval to check messages
  public static UserStatusReceiver mUserStatusReceiver = null;

  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);
    MultiDex.install(this);
  }

  // current logined user
  public static UserStatus activeUser;
  public static String displayedUserId = "guest";
  public static boolean isValidUser() {
    return activeUser != null && !activeUser.getId().equals("guest");
  }


  public void onCreate() {
    super.onCreate();
    SMTHApplication.context = getApplicationContext();

    // 全新启动 app（进程新建）时清除本地保存的登录受限状态，让本次可以直接登录。
    // 只在冷启动走这里一次；切后台再回来不会重新执行 onCreate。
    // 真正的限制仍由水木服务端判定，这里只清本地的记住状态。
    com.zfdang.zsmth_android.helpers.LoginThrottle.clearRestriction();

    // ===== TEMP CAPTCHA TEST: 保留代码，if(false)禁用；需要测试时改成 true =====
    if (false) {
      new Thread(() -> {
        try {
          com.zfdang.zsmth_android.helpers.PublishMethodHelper.ImageResult r =
              com.zfdang.zsmth_android.helpers.PublishMethodHelper.textToCaptchaImages(
                  getApplicationContext(),
                  "赢个鸡毛，要翻白眼了还赢吴泳铭阿里2032年全球数据中心规模超20GW目标或下调");
          if (!r.files.isEmpty()) {
            java.io.File src = r.files.get(0);
            java.io.File dst = new java.io.File(getCacheDir(), "captcha-test.jpg");
            java.io.InputStream in = new java.io.FileInputStream(src);
            java.io.OutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.close();
            android.util.Log.i("CAPTCHA-TEST", "saved " + dst.getAbsolutePath() + " size=" + dst.length());
          }
        } catch (Throwable t) {
          android.util.Log.e("CAPTCHA-TEST", "failed", t);
        }
      }).start();
    }
    // ===== END TEMP =====

    // 已移除本地IP属地库(qqwry.db)：删除历版 SQLiteAssetHelper 解压在 databases 目录的旧库(约77MB)，
    // 避免老用户升级后白色占用空间。只删这一个固定文件，不影响其它数据库。
    try {
      java.io.File oldGeoDb = getDatabasePath("qqwry.db");
      if (oldGeoDb != null && oldGeoDb.exists() && !oldGeoDb.delete()) {
        android.util.Log.w("SMTHApplication", "cannot delete legacy qqwry.db");
      }
    } catch (Throwable ignored) {
    }

    com.zfdang.zsmth_android.AnalyticsPrivacy.initialize(this);

    // init Fresco
    // Set<RequestListener> requestListeners = new HashSet<>();
    // requestListeners.add(new RequestLoggingListener());
    OkHttpClient httpClient = SMTHHelper.getInstance().mHttpClient;
    ImagePipelineConfig config = OkHttpImagePipelineConfigFactory.newBuilder(context, httpClient)
        //                .setRequestListeners(requestListeners)
                        .setDownsampleEnabled(false)
        .build();
    Fresco.initialize(context, config);
    // FLog.setMinimumLoggingLevel(FLog.VERBOSE);

    boolean bNightMode = Settings.getInstance().isNightMode();
    if (bNightMode) {
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    } else {
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }
  }

  public static Context getAppContext() {
    return SMTHApplication.context;
  }
}
