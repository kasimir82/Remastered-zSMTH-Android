package com.zfdang.zsmth_android;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.core.ImagePipeline;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.helpers.BlockListManager;
import com.zfdang.zsmth_android.helpers.FileLess;
import com.zfdang.zsmth_android.helpers.FileSizeUtil;
import com.zfdang.zsmth_android.helpers.TripleLogReader;
import com.zfdang.zsmth_android.models.MailListContent;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ResponseBody;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「我的」页面：聚合用户信息、收藏与站内信入口、本机屏蔽以及大部分常用设置。
 */
public class MineFragment extends Fragment {
  private static final String TAG = "MineFragment";

  private RecyclerView recyclerView;
  private MineAdapter adapter;
  private final List<MineItem> items = new ArrayList<>();

  private String imageCacheSummary = "点击清除";
  private String httpCacheSummary = "点击清除";
  private int mailUnreadCount = 0; // 站内信未读数(进入本页时从收件箱第一页统计)

  @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_mine, container, false);

    recyclerView = view.findViewById(R.id.mine_recycler_view);
    recyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
    adapter = new MineAdapter(items, this::onItemClicked);
    recyclerView.setAdapter(adapter);

    buildItems();
    updateCacheSummary(0);
    return view;
  }

  @Override public void onResume() {
    super.onResume();
    // 从屏蔽设置等页面返回、或登录状态变化后，重新渲染
    buildItems();
    // 进入本页时统计一次站内信未读数(只请求收件箱第一页，不做后台轮询)
    refreshMailUnreadCount();
  }

  /** 请求收件箱第一页，统计 no-read 行数作为站内信未读数 */
  private void refreshMailUnreadCount() {
    if (!SMTHApplication.isValidUser() || getActivity() == null) return;
    SMTHHelper.getInstance().wService.getUserMails("inbox", "1")
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe((ResponseBody body) -> {
          try {
            int count = SMTHHelper.countUnreadMails(body.string());
            if (count >= 0 && count != mailUnreadCount) {
              mailUnreadCount = count;
              buildItems();
            }
          } catch (Exception e) {
            Log.w(TAG, "count unread mails failed", e);
          }
        }, e -> Log.w(TAG, "fetch mail list failed", e));
  }

  /** 由 MainActivity 在用户状态更新后调用（可能来自后台回调，此时 Fragment 未必 attached） */
  public void refreshUserInfo() {
    // 登录状态变化时 MainActivity 会同步刷新「我的」页，但该回调可能发生在
    // Fragment 尚未 attached / 已 detached 时（如正在切 tab、页面未创建）。
    // 此时调用 getResources() 会抛 "Fragment not attached to a context" 崩溃。
    if (!isAdded() || getActivity() == null) return;
    buildItems();
  }

  private void buildItems() {
    if (adapter == null) {
      return;
    }
    Settings settings = Settings.getInstance();

    items.clear();
    items.add(MineItem.userCard());

    items.add(MineItem.section("我的内容"));
    items.add(MineItem.rowWithBadge("mail", R.drawable.ic_mine_mail, "站内信", null, mailUnreadCount));
    // 回复我的：有未读时在标题后显示红字 (N)
    int replyBadge = 0;
    if (SMTHApplication.isValidUser() && SMTHApplication.activeUser != null
        && SMTHApplication.activeUser.hasNewReply()) {
      String raw = SMTHApplication.activeUser.getNew_reply();
      if (raw != null) {
        try {
          replyBadge = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
          replyBadge = 1; // "true" 等非数字：按 1 条算
        }
      }
      if (replyBadge <= 0) replyBadge = 1;
    }
    items.add(MineItem.rowWithBadge("reply_to_me", R.drawable.ic_mine_bell, "回复我的", null, replyBadge));
    items.add(MineItem.row("draft", R.drawable.ic_mine_draft, "发帖草稿", draftSummary()));
    items.add(MineItem.row("feed_settings", R.drawable.ic_mine_nav, "信息流设置", feedSummary()));

    items.add(MineItem.section("屏蔽与外观"));
    items.add(MineItem.row("block", R.drawable.ic_mine_block, "本机屏蔽", blockSummary()));
    items.add(MineItem.toggle("night", R.drawable.ic_mine_night, "夜间模式", null, settings.isNightMode()));
    items.add(MineItem.row("font", R.drawable.ic_mine_font, "字体大小", fontSummary()));

    items.add(MineItem.section("阅读"));
    items.add(MineItem.toggle("entry_hot", R.drawable.ic_mine_home, "启动页面",
        settings.isLaunchHotTopic() ? "信息流" : "收藏夹", settings.isLaunchHotTopic()));
    items.add(MineItem.toggle("volume", R.drawable.ic_mine_volume, "音量键翻页", null, settings.isVolumeKeyScroll()));
    items.add(MineItem.toggle("vibrate", R.drawable.ic_mine_volume, "翻页震动", null, settings.isPageTurnVibrate()));
    if (settings.isPageTurnVibrate()) {
      items.add(MineItem.row("vibrate_ms", R.drawable.ic_mine_volume, "翻页震动时长", settings.getPageTurnVibrateMs() + " 毫秒"));
    }
    // 「隐藏置顶」= 不显示置顶帖；与 isShowSticky 相反，默认开启(即默认隐藏置顶)
    items.add(MineItem.toggle("hide_sticky", R.drawable.ic_mine_star, "隐藏置顶帖", null, !settings.isShowSticky()));
    items.add(MineItem.toggle("board_master", R.drawable.ic_mine_shield, "版主专用功能", null, settings.isBoardMasterOnly()));

    items.add(MineItem.section("图片"));
    items.add(MineItem.toggle("img_cdn", R.drawable.ic_mine_image, "从 CDN 加载图片", null, settings.isImageSourceCDN()));

    items.add(MineItem.section("发帖"));
    items.add(MineItem.toggle("signature", R.drawable.ic_mine_signature, "添加设备签名", null, settings.bUseSignature()));
    items.add(MineItem.row("signature_content", R.drawable.ic_mine_signature, "设备型号", settings.getSignature()));

    items.add(MineItem.section("存储与关于"));
    items.add(MineItem.row("app_data", R.drawable.ic_mine_info, "App数据管理", "导入/导出/清理"));
    items.add(MineItem.row("clear_image", R.drawable.ic_mine_clean, "清除图片缓存", imageCacheSummary));
    items.add(MineItem.row("clear_http", R.drawable.ic_mine_clean, "清除帖子缓存", httpCacheSummary));
    items.add(MineItem.row("about", R.drawable.ic_mine_info, "关于 RzSMTH", versionSummary()));
    items.add(MineItem.section("调试"));
    items.add(MineItem.row("restore_utmp_logout", R.drawable.ic_mine_info,
        "恢复UTMP并注销", "从日志读取最后三件套"));

    Log.d(TAG, "buildItems: " + items.size() + " items, about=" + versionSummary());
    adapter.notifyDataSetChanged();
  }

  private String draftSummary() {
    String cache = Settings.getInstance().getPostCache();
    return TextUtils.isEmpty(cache) ? "无" : "有未发送的内容";
  }

  private String feedSummary() {
    FeedConfig config = FeedConfig.getInstance();
    if (config.isEnabled()) {
      return "已开启 · " + config.getBoardCount() + " 个版面";
    }
    return "未开启";
  }

  private String blockSummary() {    int userCount = 0;
    int keywordCount = 0;
    for (BlockListManager.Entry entry : BlockListManager.getInstance().getEntries()) {
      if (entry.isUser()) {
        userCount++;
      } else {
        keywordCount++;
      }
    }
    if (userCount == 0 && keywordCount == 0) {
      return "未设置";
    }
    return userCount + " 用户 / " + keywordCount + " 关键词";
  }

  private String fontSummary() {
    // 用 application context 的 resources，不依赖 Fragment 是否 attached
    String[] names = SMTHApplication.getAppContext().getResources().getStringArray(R.array.font_size_names);
    int index = Settings.getInstance().getFontIndex();
    if (index < 0 || index >= names.length) {
      index = 1;
    }
    return names[index];
  }

  private String versionSummary() {
    try {
      PackageInfo info = SMTHApplication.getAppContext().getPackageManager()
          .getPackageInfo(SMTHApplication.getAppContext().getPackageName(), 0);
      return "v" + info.versionName;
    } catch (Exception e) {
      return "";
    }
  }

  /** delayMs>0 时延迟测量：清除缓存(Fresco/文件删除)是异步的，立即测量会读到旧值 */
  private void updateCacheSummary(long delayMs) {
    Runnable measure = () -> doUpdateCacheSummary();
    if (delayMs > 0 && recyclerView != null) {
      recyclerView.postDelayed(measure, delayMs);
    } else {
      measure.run();
    }
  }

  private void doUpdateCacheSummary() {
    final File imageCache = new File(SMTHApplication.getAppContext().getCacheDir(), "image_cache");
    final File httpCache = new File(SMTHApplication.getAppContext().getCacheDir(), "Responses");
    new Thread(() -> {
      final String imageSize;
      final String httpSize;
      try {
        imageSize = FileSizeUtil.getAutoFileOrFolderSize(imageCache.getAbsolutePath());
        httpSize = FileSizeUtil.getAutoFileOrFolderSize(httpCache.getAbsolutePath());
      } catch (Exception e) {
        Log.e(TAG, "updateCacheSummary: " + Log.getStackTraceString(e));
        return;
      }
      if (getActivity() == null) {
        return;
      }
      getActivity().runOnUiThread(() -> {
        imageCacheSummary = "缓存 " + imageSize;
        httpCacheSummary = "缓存 " + httpSize;
        buildItems();
      });
    }).start();
  }

  /** 恢复UTMP并注销：从日志读最后三件套→写cookie→LOGGED_IN→弹注销确认 */
  private void handleRestoreUtmpAndLogout(MainActivity activity) {
    TripleLogReader.Triple triple = TripleLogReader.readLastValidTriple();
    if (triple == null || !triple.isValid()) {
      Toast.makeText(activity, "日志中未找到有效的三件套", Toast.LENGTH_SHORT).show();
      com.zfdang.zsmth_android.helpers.DiagLogger.log("MineFragment", "RESTORE_UTMP",
          com.zfdang.zsmth_android.helpers.DiagLogger.FAIL, "no valid triple in log");
      return;
    }
    // 写cookie + 转LOGGED_IN
    com.zfdang.zsmth_android.WebviewCookieHandler.writeTriple(
        triple.userId, triple.key, triple.num);
    com.zfdang.zsmth_android.helpers.LoginStateManager.onLoginSuccess(triple.userId);
    Toast.makeText(activity, "已恢复: " + triple, Toast.LENGTH_LONG).show();

    // 弹"是否强制注销"对话框
    new AlertDialog.Builder(activity)
        .setTitle("是否强制注销")
        .setMessage("UTMP已恢复为 " + triple.userId + "，是否强制注销？")
        .setPositiveButton("注销", (dialog, which) -> activity.onLogout())
        .setNegativeButton("取消", null)
        .show();
  }

  private void onItemClicked(MineItem item) {
    if (!(getActivity() instanceof MainActivity)) {
      return;
    }
    MainActivity activity = (MainActivity) getActivity();
    Settings settings = Settings.getInstance();

    switch (item.key) {
      case "user_card":
        if (SMTHApplication.isValidUser()) {
          Intent intent = new Intent(activity, QueryUserActivity.class);
          intent.putExtra(SMTHApplication.QUERY_USER_INFO, SMTHApplication.activeUser.getId());
          startActivity(intent);
        } else {
          activity.onLogin();
        }
        break;

      case "user_logout":
        // 按钮显示"注销"时点击：弹注销确认框
        new AlertDialog.Builder(activity)
            .setTitle("退出登录")
            .setMessage("确定要退出当前帐号吗？")
            .setPositiveButton("退出", (dialog, which) -> activity.onLogout())
            .setNegativeButton("取消", null)
            .show();
        break;

      case "restore_utmp_logout":
        // 调试：从本地日志读取最后成功三件套→写cookie→转LOGGED_IN→弹"是否强制注销"
        handleRestoreUtmpAndLogout(activity);
        break;

      case "user_login":
        // 按钮显示"登录/重试"时点击：无条件走真登录流程，不看isValidUser瞬时状态
        activity.onLogin();
        break;

      case "mail":
        activity.onNavigationItemID(R.id.nav_mail);
        break;

      case "reply_to_me":
        // 直达站内信的「回复」tab（复用已有的 refer/reply 接口与列表）
        if (!SMTHApplication.isValidUser()) {
          activity.onLogin();
          break;
        }
        activity.mailListFragment.setCurrentFolder(MailListFragment.REPLY_LABEL);
        MailListContent.clear();
        activity.onNavigationItemID(R.id.nav_mail);
        break;

      case "draft":
        onDraftClicked(activity);
        break;

      case "block":
        startActivity(new Intent(activity, BlockSettingsActivity.class));
        break;

      case "feed_settings":
        startActivity(new Intent(activity, FeedSettingsActivity.class));
        break;

      case "app_data":
        startActivity(new Intent(activity, AppDataActivity.class));
        break;

      case "night":
        settings.setNightMode(!settings.isNightMode());
        activity.setApplicationNightMode();
        break;

      case "font":
        showFontDialog(activity);
        break;

      case "vibrate_ms":
        showVibrateMsDialog(activity);
        break;

      case "about":
        activity.onNavigationItemID(R.id.nav_about);
        break;

      case "clear_image":
        ImagePipeline imagePipeline = Fresco.getImagePipeline();
        imagePipeline.clearDiskCaches(); // 异步清除
        Toast.makeText(activity, "图片缓存已清除", Toast.LENGTH_SHORT).show();
        imageCacheSummary = "清除中…";
        buildItems();
        updateCacheSummary(1200); // 等异步清除完成后再测量，否则读到旧值
        break;

      case "clear_http":
        File cache = new File(SMTHApplication.getAppContext().getCacheDir(), "Responses");
        FileLess.$del(cache);
        if (!cache.exists()) {
          cache.mkdirs();
        }
        Toast.makeText(activity, "帖子缓存已清除", Toast.LENGTH_SHORT).show();
        httpCacheSummary = "清除中…";
        buildItems();
        updateCacheSummary(1200);
        break;

      case "signature_content":
        showSignatureDialog(activity);
        break;

      default:
        onToggleClicked(item, settings, activity);
        break;
    }
  }

  private void onToggleClicked(MineItem item, Settings settings, MainActivity activity) {
    boolean value = !item.checked;
    switch (item.key) {
      case "entry_hot":
        settings.setLaunchHotTopic(value);
        break;
      case "volume":
        settings.setVolumeKeyScroll(value);
        break;
      case "vibrate":
        settings.setPageTurnVibrate(value);
        break;
      case "hide_sticky":
        // value=true(隐藏置顶开启) → showSticky=false
        settings.setShowSticky(!value);
        break;
      case "board_master":
        settings.setBoardMasterOnly(value);
        break;
      case "img_cdn":
        settings.setImageSourceCDN(value);
        break;
      case "signature":
        settings.setUseSignature(value);
        break;
      default:
        return;
    }
    buildItems();
  }

  private void showVibrateMsDialog(MainActivity activity) {
    final int[] values = new int[]{10, 20, 30, 50};
    final String[] names = new String[]{"10 毫秒(极短)", "20 毫秒(轻微)", "30 毫秒(适中)", "50 毫秒(明显)"};
    int current = 0;
    int now = Settings.getInstance().getPageTurnVibrateMs();
    for (int i = 0; i < values.length; i++) {
      if (values[i] == now) { current = i; break; }
    }
    new AlertDialog.Builder(activity)
        .setTitle("翻页震动时长")
        .setSingleChoiceItems(names, current, (dialog, which) -> {
          dialog.dismiss();
          Settings.getInstance().setPageTurnVibrateMs(values[which]);
          buildItems();
        })
        .setNegativeButton("取消", null)
        .show();
  }

  private void showFontDialog(MainActivity activity) {
    final String[] names = activity.getResources().getStringArray(R.array.font_size_names);
    final int[] values = activity.getResources().getIntArray(R.array.font_size_values_int);
    int current = 0;
    int fontIndex = Settings.getInstance().getFontIndex();
    for (int i = 0; i < values.length; i++) {
      if (values[i] == fontIndex) {
        current = i;
        break;
      }
    }

    new AlertDialog.Builder(activity)
        .setTitle("字体大小")
        .setSingleChoiceItems(names, current, (dialog, which) -> {
          dialog.dismiss();
          if (values[which] == Settings.getInstance().getFontIndex()) {
            return;
          }
          Settings.getInstance().setFontIndex(values[which]);
          activity.recreate();
        })
        .setNegativeButton("取消", null)
        .show();
  }

  private void showSignatureDialog(MainActivity activity) {
    final EditText input = new EditText(activity);
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    input.setText(Settings.getInstance().getSignature());
    input.setSelection(input.getText().length());
    input.setHint("例如：Mi 11 Pro");

    new AlertDialog.Builder(activity)
        .setTitle("设备型号")
        .setView(input, 24, 8, 24, 0)
        .setPositiveButton("保存", (dialog, which) -> {
          Settings.getInstance().setSignature(input.getText().toString().trim());
          buildItems();
        })
        .setNegativeButton("取消", null)
        .show();
  }

  private void onDraftClicked(MainActivity activity) {
    String cache = Settings.getInstance().getPostCache();
    if (TextUtils.isEmpty(cache)) {
      Toast.makeText(activity, "没有未发送的草稿", Toast.LENGTH_SHORT).show();
      return;
    }
    new AlertDialog.Builder(activity)
        .setTitle("发帖草稿")
        .setMessage("检测到上次未发送的内容，是否清空？")
        .setPositiveButton("清空", (dialog, which) -> {
          Settings.getInstance().setPostCache("");
          buildItems();
          Toast.makeText(activity, "草稿已清空", Toast.LENGTH_SHORT).show();
        })
        .setNegativeButton("保留", null)
        .show();
  }
}
