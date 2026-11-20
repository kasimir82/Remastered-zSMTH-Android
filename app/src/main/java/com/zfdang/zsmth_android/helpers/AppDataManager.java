package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * App 数据导入/导出：把「设置 + 黑名单 + 时间缓存库」打包成一个 JSON 文件。
 * 使用系统文件选择器(SAF)决定保存/读取位置，应用不需要存储权限。
 */
public final class AppDataManager {
  private static final String TAG = "AppDataManager";
  private static final int FORMAT_VERSION = 1;

  private AppDataManager() {}

  /** 导出数据的摘要 */
  public static class ExportInfo {
    public int settingCount;
    public int timeRecordCount;
    public boolean hasBlockList;
  }

  /**
   * 导出到给定的输出流，返回导出摘要。
   * 结构: {version, exportTime, settings:{...}, blockList:"...", topicTimes:[...]}
   */
  public static ExportInfo exportTo(OutputStream out) throws Exception {
    Context ctx = SMTHApplication.getAppContext();
    ExportInfo info = new ExportInfo();

    JSONObject root = new JSONObject();
    root.put("version", FORMAT_VERSION);
    root.put("exportTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));

    // 1. 设置：ZSMTH_Config 的所有键值对
    JSONObject settings = new JSONObject();
    SharedPreferences sp = ctx.getSharedPreferences("ZSMTH_Config", Context.MODE_PRIVATE);
    Map<String, ?> all = sp.getAll();
    for (Map.Entry<String, ?> e : all.entrySet()) {
      String key = e.getKey();
      Object v = e.getValue();
      if (v instanceof Boolean) settings.put(key, (Boolean) v);
      else if (v instanceof String) settings.put(key, (String) v);
      else if (v instanceof Integer) settings.put(key, (Integer) v);
      else if (v instanceof Long) settings.put(key, (Long) v);
      else if (v instanceof Float) settings.put(key, (Float) v);
      info.settingCount++;
    }
    root.put("settings", settings);

    // 2. 黑名单：复用 BlockListManager 的 JSON
    String blockJson = BlockListManager.getInstance().exportJson();
    root.put("blockList", blockJson == null ? "" : blockJson);
    info.hasBlockList = blockJson != null && blockJson.length() > 2;

    // 3. 时间缓存库
    JSONArray times = new JSONArray(TopicTimeCache.getInstance().exportJson());
    root.put("topicTimes", times);
    info.timeRecordCount = times.length();

    OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
    writer.write(root.toString(2));
    writer.flush();
    Log.i(TAG, "export: settings=" + info.settingCount + " times=" + info.timeRecordCount);
    return info;
  }

  /** 导入结果摘要 */
  public static class ImportInfo {
    public int settingCount;
    public int timeRecordCount;
    public boolean hasBlockList;
  }

  /** 从输入流导入，返回导入摘要 */
  public static ImportInfo importFrom(InputStream in) throws Exception {
    Context ctx = SMTHApplication.getAppContext();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) sb.append(line);
    JSONObject root = new JSONObject(sb.toString());

    int version = root.optInt("version", 0);
    if (version <= 0 || version > FORMAT_VERSION) {
      throw new IllegalArgumentException("不支持的数据格式版本: " + version);
    }
    ImportInfo info = new ImportInfo();

    // 1. 设置
    JSONObject settings = root.optJSONObject("settings");
    if (settings != null) {
      SharedPreferences sp = ctx.getSharedPreferences("ZSMTH_Config", Context.MODE_PRIVATE);
      SharedPreferences.Editor ed = sp.edit();
      java.util.Iterator<String> keys = settings.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object v = settings.get(key);
        if (v instanceof Boolean) ed.putBoolean(key, (Boolean) v);
        else if (v instanceof String) ed.putString(key, (String) v);
        else if (v instanceof Integer) ed.putInt(key, (Integer) v);
        else if (v instanceof Long) ed.putLong(key, (Long) v);
        else if (v instanceof Float) ed.putFloat(key, (Float) v);
        info.settingCount++;
      }
      ed.apply();
    }

    // 2. 黑名单
    String blockJson = root.optString("blockList", "");
    if (blockJson != null && blockJson.length() > 2) {
      BlockListManager.getInstance().importJson(blockJson);
      info.hasBlockList = true;
    }

    // 3. 时间缓存库
    JSONArray times = root.optJSONArray("topicTimes");
    if (times != null) {
      info.timeRecordCount = TopicTimeCache.getInstance().importJson(times.toString());
    }

    // 重新加载 Settings 内存缓存，让导入的设置立即生效
    Settings.getInstance().reload();

    Log.i(TAG, "import: settings=" + info.settingCount + " times=" + info.timeRecordCount);
    return info;
  }

  /** 默认导出文件名 */
  public static String defaultFileName() {
    return "RzSMTH_Backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json";
  }
}
