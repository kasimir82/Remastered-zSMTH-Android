package com.zfdang.zsmth_android;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.zfdang.zsmth_android.helpers.AppDataManager;
import com.zfdang.zsmth_android.helpers.BlockListManager;
import com.zfdang.zsmth_android.helpers.TopicTimeCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** App 数据管理：导出/导入(设置+黑名单+时间缓存)、清理1个月前的时间缓存 */
public class AppDataActivity extends SMTHBaseActivity {
  private static final String TAG = "AppDataActivity";
  private static final int REQ_IMPORT = 1002;
  private static final int REQ_WRITE_STORAGE = 1003;

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

  /** 导出到 Download 目录（照抄本机屏蔽页的 MediaStore 方式） */
  private void startExport() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this,
              new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
      return;
    }
    doExport();
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQ_WRITE_STORAGE) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        doExport();
      } else {
        Toast.makeText(this, "需要存储权限才能导出到 Download", Toast.LENGTH_LONG).show();
      }
    }
  }

  private void startImport() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    intent.putExtra(Intent.EXTRA_MIME_TYPES,
            new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
              Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
    }
    try {
      startActivityForResult(intent, REQ_IMPORT);
    } catch (Exception e) {
      Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
    doImport(data.getData());
  }

  private void doExport() {
    new Thread(() -> {
      String fileName = AppDataManager.defaultFileName();
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ContentValues values = new ContentValues();
          values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
          values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
          values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
          Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
          if (uri == null) throw new Exception("无法创建文件");
          try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            AppDataManager.exportZip(out);
          }
        } else {
          File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
          if (!dir.exists()) dir.mkdirs();
          File file = new File(dir, fileName);
          try (FileOutputStream out = new FileOutputStream(file)) {
            AppDataManager.exportZip(out);
          }
          android.media.MediaScannerConnection.scanFile(this,
                  new String[] {file.getAbsolutePath()}, new String[] {"application/zip"}, null);
        }
        mainHandler.post(() -> Toast.makeText(this, "已导出到 Download/" + fileName, Toast.LENGTH_LONG).show());
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
        AppDataManager.ImportInfo info = AppDataManager.importZip(in);
        mainHandler.post(() -> {
          // 全量恢复设置后，很多界面在启动时才读入内存，需重启才能完全生效
          new androidx.appcompat.app.AlertDialog.Builder(this)
              .setTitle("导入成功")
              .setMessage("已恢复设置 " + info.settingCount + " 项、时间缓存 " + info.timeRecordCount
                  + " 条。\n\n部分设置需要重启应用才能生效，是否立即重启？")
              .setPositiveButton("立即重启", (d, w) -> restartApp())
              .setNegativeButton("稍后", (d, w) -> refreshStats())
              .setCancelable(false)
              .show();
        });
      } catch (Exception e) {
        Log.e(TAG, "import failed", e);
        final String msg = e.getMessage();
        mainHandler.post(() -> Toast.makeText(this, "导入失败：" + msg, Toast.LENGTH_LONG).show());
      }
    }).start();
  }

  /** 重启应用：用 AlarmManager 延迟拉起启动页，再杀掉当前进程，确保设置全部重新加载 */
  private void restartApp() {
    Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (launch != null) {
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
          this, 0, launch, android.app.PendingIntent.FLAG_UPDATE_CURRENT
              | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
      android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
      if (am != null) {
        am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 500, pi);
      }
    }
    android.os.Process.killProcess(android.os.Process.myPid());
    System.exit(0);
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
