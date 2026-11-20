package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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
  private static final int FORMAT_VERSION = 2; // v2: 带 settingTypes 类型标记，修正 Long 被解析成 Integer 的问题

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
    // 记录每个键的原始类型，避免 org.json 把 Long(如时间戳)解析成 Integer 导致恢复后 getLong 崩溃
    JSONObject settingTypes = new JSONObject();
    SharedPreferences sp = ctx.getSharedPreferences("ZSMTH_Config", Context.MODE_PRIVATE);
    Map<String, ?> all = sp.getAll();
    for (Map.Entry<String, ?> e : all.entrySet()) {
      String key = e.getKey();
      // 密码不备份(用户明确要求)；COMPOSE_POST_CACHE 是编辑草稿，也没必要备份
      if ("password".equals(key) || "COMPOSE_POST_CACHE".equals(key)) continue;
      Object v = e.getValue();
      if (v instanceof Boolean) { settings.put(key, (Boolean) v); settingTypes.put(key, "B"); }
      else if (v instanceof String) { settings.put(key, (String) v); settingTypes.put(key, "S"); }
      else if (v instanceof Integer) { settings.put(key, (Integer) v); settingTypes.put(key, "I"); }
      else if (v instanceof Long) { settings.put(key, (Long) v); settingTypes.put(key, "L"); }
      else if (v instanceof Float) { settings.put(key, (Float) v); settingTypes.put(key, "F"); }
      else continue;
      info.settingCount++;
    }
    root.put("settings", settings);
    root.put("settingTypes", settingTypes);

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
      JSONObject types = root.optJSONObject("settingTypes"); // 原始类型标记(B/S/I/L/F)
      SharedPreferences sp = ctx.getSharedPreferences("ZSMTH_Config", Context.MODE_PRIVATE);
      SharedPreferences.Editor ed = sp.edit();
      java.util.Iterator<String> keys = settings.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object v = settings.get(key);
        String type = types != null ? types.optString(key, "") : "";
        if ("L".equals(type)) ed.putLong(key, ((Number) v).longValue());       // 强制恢复为 Long
        else if ("I".equals(type)) ed.putInt(key, ((Number) v).intValue());
        else if ("F".equals(type)) ed.putFloat(key, ((Number) v).floatValue());
        else if ("B".equals(type)) ed.putBoolean(key, (Boolean) v);
        else if ("S".equals(type)) ed.putString(key, (String) v);
        // 无类型标记(兼容)：按 JSON 解析出的类型推断
        else if (v instanceof Boolean) ed.putBoolean(key, (Boolean) v);
        else if (v instanceof String) ed.putString(key, (String) v);
        else if (v instanceof Number) ed.putLong(key, ((Number) v).longValue());
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

  /** ZIP 内的文件命名 */
  private static final String ZIP_BACKUP = "backup.json";
  private static final String ZIP_MANIFEST = "manifest.json";

  /**
   * 导出为 ZIP：含 backup.json(设置/黑名单/时间缓存) + manifest.json(备份内容描述)。
   */
  public static ExportInfo exportZip(OutputStream out) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    ExportInfo info = exportTo(buffer); // 复用现有 JSON 生成(已剔除密码)
    byte[] backupBytes = buffer.toByteArray();

    JSONObject manifest = new JSONObject();
    manifest.put("app", "RzSMTH");
    manifest.put("format", "rzsmth-backup");
    manifest.put("version", FORMAT_VERSION);
    manifest.put("exportTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
    manifest.put("description", "应用设置(不含密码) + 本机黑名单 + 帖子时间缓存");
    JSONObject contents = new JSONObject();
    contents.put("settings", info.settingCount);
    contents.put("blockList", info.hasBlockList);
    contents.put("topicTimes", info.timeRecordCount);
    manifest.put("contents", contents);
    byte[] manifestBytes = manifest.toString(2).getBytes("UTF-8");

    ZipOutputStream zos = new ZipOutputStream(out);
    zos.putNextEntry(new ZipEntry(ZIP_MANIFEST));
    zos.write(manifestBytes);
    zos.closeEntry();
    zos.putNextEntry(new ZipEntry(ZIP_BACKUP));
    zos.write(backupBytes);
    zos.closeEntry();
    zos.finish();
    zos.flush();
    Log.i(TAG, "exportZip: settings=" + info.settingCount + " times=" + info.timeRecordCount);
    return info;
  }

  /** 从 ZIP 导入：读 manifest 校验格式，读 backup.json 恢复 */
  public static ImportInfo importZip(InputStream in) throws Exception {
    ZipInputStream zis = new ZipInputStream(in);
    ZipEntry entry;
    byte[] backupBytes = null;
    while ((entry = zis.getNextEntry()) != null) {
      if (ZIP_MANIFEST.equals(entry.getName())) {
        // 读取 manifest 做基本校验(确认是本 app 的备份)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = zis.read(buf)) > 0) bos.write(buf, 0, len);
        try {
          JSONObject mf = new JSONObject(bos.toString("UTF-8"));
          String app = mf.optString("app", "");
          String format = mf.optString("format", "");
          if (!"RzSMTH".equals(app) || !"rzsmth-backup".equals(format)) {
            throw new IllegalArgumentException("不是 RzSMTH 备份包");
          }
        } catch (org.json.JSONException je) {
          throw new IllegalArgumentException("备份描述文件损坏");
        }
      } else if (ZIP_BACKUP.equals(entry.getName())) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = zis.read(buf)) > 0) bos.write(buf, 0, len);
        backupBytes = bos.toByteArray();
      }
      zis.closeEntry();
    }
    if (backupBytes == null) throw new IllegalArgumentException("备份包中缺少 " + ZIP_BACKUP);
    return importFrom(new java.io.ByteArrayInputStream(backupBytes));
  }

  /** 默认导出文件名(ZIP) */
  public static String defaultFileName() {
    return "RzSMTH_Backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".zip";
  }
}
