package com.zfdang.zsmth_android;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.Menu;
import android.view.MenuItem;
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

    // 子页面保持主页当前的标签高亮，不要一进来就跳回最左边
    if (!(this instanceof MainActivity)) {
      MenuItem current = nav.getMenu().findItem(SMTHApplication.currentTabId);
      if (current != null) {
        current.setChecked(true);
      }
    }
  }

  /** 默认行为：回主页并切到对应标签。MainActivity 会重写此方法直接切换 fragment。 */
  protected void onBottomNavItemSelected(int itemId) {
    Intent intent = new Intent(this, MainActivity.class);
    intent.putExtra(EXTRA_TARGET_TAB, itemId);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(intent);
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
