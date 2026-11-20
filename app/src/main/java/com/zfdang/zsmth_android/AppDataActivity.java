package com.zfdang.zsmth_android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import com.zfdang.zsmth_android.helpers.AppDataManager;
import com.zfdang.zsmth_android.helpers.BlockListManager;
import com.zfdang.zsmth_android.helpers.TopicTimeCache;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** App 数据管理：导出/导入(设置+黑名单+时间缓存)、清理1个月前的时间缓存 */
public class AppDataActivity extends SMTHBaseActivity {
  private static final String TAG = "AppDataActivity";
  private static final int REQ_EXPORT = 1001;
  private static final int REQ_IMPORT = 1002;

  private TextView statsView;
  private TextView dbSizeView;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_app_data);

    Toolbar toolbar = findViewById(R.id.app_data_toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    statsView = findViewById(R.id.app_data_stats);
    dbSizeView = findViewById(R.id.app_data_db_size);

    findViewById(R.id.btn_export).setOnClickListener(v -> startExport());
    findViewById(R.id.btn_import).setOnClickListener(v -> startImport());
    findViewById(R.id.btn_clean).setOnClickListener(v -> cleanOld());
  }

  @Override protected void onResume() {
    super.onResume();
    refreshStats();
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  /** 后台线程统计数据量，避免操作(尤其导入后)看到旧值 */
  private void refreshStats() {
    new Thread(() -> {
      final String text;
      final String sizeText;
      try {
        int timeCount = TopicTimeCache.getInstance().count();
        BlockListManager bm = BlockListManager.getInstance();
        // 黑名单条数用导出 JSON 长度粗略反映，避免依赖内部解析
        String blockJson = bm.exportJson();
        String blockInfo = (blockJson == null || blockJson.length() <= 2) ? "无" : "已设置";
        text = "当前数据：\n"
            + "· 帖子时间缓存：" + timeCount + " 条\n"
            + "· 本机黑名单：" + blockInfo + "\n"
            + "· 应用设置：" + getSharedPreferences("ZSMTH_Config", MODE_PRIVATE).getAll().size() + " 项";

        java.io.File dbFile = getDatabasePath("topic_time_cache.db");
        long dbBytes = (dbFile != null && dbFile.exists()) ? dbFile.length() : 0;
        sizeText = "时间缓存数据库：" + com.zfdang.zsmth_android.helpers.FileSizeUtil.FormatFileSize(dbBytes);
      } catch (Exception e) {
        Log.e(TAG, "refreshStats error", e);
        return;
      }
      mainHandler.post(() -> {
        statsView.setText(text);
        dbSizeView.setText(sizeText);
      });
    }).start();
  }

  private void startExport() {
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("application/json");
    intent.putExtra(Intent.EXTRA_TITLE, AppDataManager.defaultFileName());
    try {
      startActivityForResult(intent, REQ_EXPORT);
    } catch (Exception e) {
      Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
    }
  }

  private void startImport() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    try {
      startActivityForResult(intent, REQ_IMPORT);
    } catch (Exception e) {
      Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (requestCode == REQ_EXPORT) {
      doExport(uri);
    } else if (requestCode == REQ_IMPORT) {
      doImport(uri);
    }
  }

  private void doExport(Uri uri) {
    new Thread(() -> {
      try (OutputStream out = getContentResolver().openOutputStream(uri)) {
        if (out == null) throw new Exception("无法写入文件");
        AppDataManager.ExportInfo info = AppDataManager.exportTo(out);
        mainHandler.post(() -> Toast.makeText(this,
            "导出成功：设置 " + info.settingCount + " 项，时间缓存 " + info.timeRecordCount + " 条",
            Toast.LENGTH_LONG).show());
      } catch (Exception e) {
        Log.e(TAG, "export failed", e);
        final String msg = e.getMessage();
        mainHandler.post(() -> Toast.makeText(this, "导出失败：" + msg, Toast.LENGTH_LONG).show());
      }
    }).start();
  }

  private void doImport(Uri uri) {
    new Thread(() -> {
      try (InputStream in = getContentResolver().openInputStream(uri)) {
        if (in == null) throw new Exception("无法读取文件");
        AppDataManager.ImportInfo info = AppDataManager.importFrom(in);
        mainHandler.post(() -> {
          Toast.makeText(this,
              "导入成功：设置 " + info.settingCount + " 项，时间缓存 " + info.timeRecordCount + " 条",
              Toast.LENGTH_LONG).show();
          refreshStats();
        });
      } catch (Exception e) {
        Log.e(TAG, "import failed", e);
        final String msg = e.getMessage();
        mainHandler.post(() -> Toast.makeText(this, "导入失败：" + msg, Toast.LENGTH_LONG).show());
      }
    }).start();
  }

  private void cleanOld() {
    // 计算1个月前(30天)的日期作为分界线
    java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.add(java.util.Calendar.DAY_OF_MONTH, -30);
    String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    final String finalCutoff = cutoff;
    new Thread(() -> {
      int deleted = TopicTimeCache.getInstance().cleanBefore(finalCutoff);
      mainHandler.post(() -> {
        Toast.makeText(this, "已清除 " + deleted + " 条早于 " + finalCutoff + " 的缓存",
            Toast.LENGTH_LONG).show();
        refreshStats();
      });
    }).start();
  }
}
