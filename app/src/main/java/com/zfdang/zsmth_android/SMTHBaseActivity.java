package com.zfdang.zsmth_android;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.zfdang.SMTHApplication;

/**
 * Created by zfdang on 2016-5-10.
 */
public class SMTHBaseActivity extends AppCompatActivity {

  /** 底栏点击后回主页要切换到的标签 id */
  public static final String EXTRA_TARGET_TAB = "rzsmth_target_tab";

  protected ProgressDialog pDialog = null;

  private ViewTreeObserver.OnGlobalLayoutListener mKeyboardListener = null;
  private boolean mKeyboardVisible = false;

  /**
   * 绑定通用底部导航栏。页面布局 include 了 @layout/bottom_nav_bar 后调用即可。
   * 点击任意标签会回到 MainActivity 并切到对应页面。
   */
  protected void setupBottomNavigation() {
    BottomNavigationView nav = findViewById(R.id.bottom_nav);
    if (nav == null) {
      return;
    }
    nav.setOnNavigationItemSelectedListener(item -> {
      onBottomNavItemSelected(item.getItemId());
      return true;
    });
    // 重复点击当前已选中的标签不会走上面的 Selected 回调，而是走这里
    nav.setOnNavigationItemReselectedListener(item -> {
      onBottomNavItemReselected(item.getItemId());
    });

    // 子页面保持主页当前的标签高亮，不要一进来就跳回最左边
    if (!(this instanceof MainActivity)) {
      MenuItem current = nav.getMenu().findItem(SMTHApplication.currentTabId);
      if (current != null) {
        current.setChecked(true);
      }
    }

    hideBottomNavigationWhenKeyboardShows(nav);
  }

  /**
   * 软键盘弹出时窗口会被压缩，bottom gravity 的底栏会跟着浮到键盘上方。
   * 这里监听可见区域的变化，键盘出现就把底栏滑出屏幕，收起后再滑回来。
   */
  private void hideBottomNavigationWhenKeyboardShows(final BottomNavigationView nav) {
    final View content = getWindow().getDecorView().findViewById(android.R.id.content);
    if (content == null) {
      return;
    }

    mKeyboardListener = new ViewTreeObserver.OnGlobalLayoutListener() {
      @Override public void onGlobalLayout() {
        if (nav.getHeight() == 0) {
          return;
        }
        Rect visibleFrame = new Rect();
        content.getWindowVisibleDisplayFrame(visibleFrame);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int coveredHeight = screenHeight - visibleFrame.bottom;
        // 底部导航条本身占掉的高度远小于屏幕的 15%，只有键盘才够得着这个阈值
        boolean keyboardVisible = coveredHeight > screenHeight * 0.15f;
        if (keyboardVisible == mKeyboardVisible) {
          return;
        }
        mKeyboardVisible = keyboardVisible;

        nav.animate().cancel();
        nav.animate()
            .translationY(keyboardVisible ? nav.getHeight() : 0)
            .setDuration(160)
            .start();
      }
    };
    content.getViewTreeObserver().addOnGlobalLayoutListener(mKeyboardListener);
  }

  private void releaseKeyboardListener() {
    if (mKeyboardListener == null) {
      return;
    }
    View content = getWindow().getDecorView().findViewById(android.R.id.content);
    if (content != null) {
      content.getViewTreeObserver().removeOnGlobalLayoutListener(mKeyboardListener);
    }
    mKeyboardListener = null;
  }

  /** 默认行为：回主页并切到对应标签。MainActivity 会重写此方法直接切换 fragment。 */
  protected void onBottomNavItemSelected(int itemId) {
    Intent intent = new Intent(this, MainActivity.class);
    intent.putExtra(EXTRA_TARGET_TAB, itemId);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(intent);
  }

  /** 重复点击当前已选中的标签。默认无操作，MainActivity 会用它实现双击信息流刷新。 */
  protected void onBottomNavItemReselected(int itemId) {
  }

  // http://stackoverflow.com/questions/22924825/view-not-attached-to-window-manager-crash
  public void showProgress(String message) {
    if (pDialog == null) {
      pDialog = new ProgressDialog(this, R.style.PDialog_MyTheme);
      pDialog.setCancelable(true);
      pDialog.setCanceledOnTouchOutside(false);
    }
    pDialog.setMessage(message);
    pDialog.show();
  }

  public void dismissProgress() {
    if (pDialog != null && pDialog.isShowing()) {
      pDialog.dismiss();
    }
  }

  @Override protected void onDestroy() {
    super.onDestroy();

    releaseKeyboardListener();
    dismissProgress();
  }

  @Override public Resources getResources() {
    Resources res = super.getResources();
    Configuration config = res.getConfiguration();
    config.fontScale = Settings.getInstance().getFontSizeFloatValue();
    res.updateConfiguration(config, res.getDisplayMetrics());

    //        Log.d("SMTHBaseActivity", "getResources: " + config.fontScale);
    return res;
  }
}
