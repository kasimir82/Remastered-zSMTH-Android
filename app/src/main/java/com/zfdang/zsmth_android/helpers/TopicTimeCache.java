package com.zfdang.zsmth_android.helpers;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.zfdang.SMTHApplication;

/**
 * 帖子发帖/回复时间的本地缓存。
 *
 * 水木版面列表对「今天」的时间返回纯时刻(HH:mm:ss)，对更早的只返回日期(yyyy-MM-dd)。
 * 纯时刻里的精确时间在帖子过了今天后服务端就不再给，所以趁它还是今天时存下来。
 * 每条记录存 5 项：topicID / 发帖日期 / 发帖时间 / 最后回复日期 / 最后回复时间。
 * 时间字段在服务端只给日期时为空串。
 *
 * 只做存储/查询，不参与任何排序逻辑——排序侧如何引用由后续决定。
 */
public final class TopicTimeCache {
  private static final String TAG = "TopicTimeCache";

  private static final String DB_NAME = "topic_time_cache.db";
  private static final int DB_VERSION = 2;
  private static final String TABLE = "topic_time";
  private static final String COL_ID = "topic_id";
  private static final String COL_PUB_DATE = "publish_date";
  private static final String COL_PUB_TIME = "publish_time";
  private static final String COL_REPLY_DATE = "reply_date";
  private static final String COL_REPLY_TIME = "reply_time";

  private static class Holder {
    private static final TopicTimeCache INSTANCE = new TopicTimeCache();
  }

  public static TopicTimeCache getInstance() {
    return Holder.INSTANCE;
  }

  private final DbHelper dbHelper;

  private TopicTimeCache() {
    dbHelper = new DbHelper(SMTHApplication.getAppContext());
  }

  private static class DbHelper extends SQLiteOpenHelper {
    DbHelper(Context context) {
      super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
      db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
          + COL_ID + " TEXT PRIMARY KEY, "
          + COL_PUB_DATE + " TEXT DEFAULT '', "
          + COL_PUB_TIME + " TEXT DEFAULT '', "
          + COL_REPLY_DATE + " TEXT DEFAULT '', "
          + COL_REPLY_TIME + " TEXT DEFAULT '')");
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_topic_time_pubdate ON " + TABLE + "(" + COL_PUB_DATE + ")");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      db.execSQL("DROP TABLE IF EXISTS " + TABLE);
      onCreate(db);
    }
  }

  /** 日期+时间的解析结果 */
  public static class DateTime {
    public final String date; // yyyy-MM-dd
    public final String time; // HH:mm:ss，纯时刻时有值；只有日期时为空串

    DateTime(String date, String time) {
      this.date = date;
      this.time = time;
    }
  }

  /**
   * 解析服务端原始时间字符串，分离日期与时间。
   * - 纯时刻 HH:mm:ss / HH:mm → 日期=今天，时间=该时刻
   * - 日期 yyyy-MM-dd → 日期=该值，时间=空串
   * - 其它/空 → date 与 time 均为空串
   */
  public static DateTime parseDateTime(String raw) {
    if (raw == null || raw.isEmpty()) return new DateTime("", "");
    String text = raw.trim();

    // 纯时刻：服务端对「今天」的时间就返回这种
    java.util.regex.Matcher tm = java.util.regex.Pattern
        .compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matcher(text);
    if (tm.find()) {
      String date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
          .format(java.util.Calendar.getInstance().getTime());
      String time;
      if (tm.group(3) != null) {
        time = String.format(java.util.Locale.US, "%02d:%02d:%02d",
            Integer.parseInt(tm.group(1)), Integer.parseInt(tm.group(2)), Integer.parseInt(tm.group(3)));
      } else {
        time = String.format(java.util.Locale.US, "%02d:%02d:00",
            Integer.parseInt(tm.group(1)), Integer.parseInt(tm.group(2)));
      }
      return new DateTime(date, time);
    }

    // 日期：yyyy-MM-dd
    java.util.regex.Matcher dm = java.util.regex.Pattern
        .compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(text);
    if (dm.find()) {
      String date = String.format(java.util.Locale.US, "%04d-%02d-%02d",
          Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(3)));
      return new DateTime(date, "");
    }

    return new DateTime("", "");
  }

  /**
   * 写入/更新一条帖子的时间。
   * 发帖日期+时间一旦存下就不再覆盖（不会变）；
   * 回复日期每次更新，回复时间仅在新值有时刻时更新（服务端只给日期时保留旧的精确时刻）。
   */
  public synchronized void save(String topicID, String publishRaw, String replyRaw) {
    if (topicID == null || topicID.isEmpty()) return;
    DateTime pub = parseDateTime(publishRaw);
    DateTime rep = parseDateTime(replyRaw);
    if (pub.date.isEmpty() && pub.time.isEmpty() && rep.date.isEmpty() && rep.time.isEmpty()) {
      return; // 两个时间都解析不出来，不写
    }
    try {
      SQLiteDatabase db = dbHelper.getWritableDatabase();
      Cursor c = db.query(TABLE,
          new String[] { COL_PUB_DATE, COL_PUB_TIME, COL_REPLY_DATE, COL_REPLY_TIME },
          COL_ID + "=?", new String[] { topicID }, null, null, null);

      String pubDate = pub.date, pubTime = pub.time;
      String repDate = rep.date, repTime = rep.time;
      boolean exists = c != null && c.moveToFirst();
      if (exists) {
        String oldPubDate = c.getString(c.getColumnIndexOrThrow(COL_PUB_DATE));
        String oldPubTime = c.getString(c.getColumnIndexOrThrow(COL_PUB_TIME));
        String oldRepDate = c.getString(c.getColumnIndexOrThrow(COL_REPLY_DATE));
        String oldRepTime = c.getString(c.getColumnIndexOrThrow(COL_REPLY_TIME));
        // 发帖不变：已有值就保留（尤其已存过精确时刻，不要被后来的纯日期覆盖）
        if (oldPubDate != null && !oldPubDate.isEmpty()) pubDate = oldPubDate;
        if (oldPubTime != null && !oldPubTime.isEmpty()) pubTime = oldPubTime;
        // 回复：日期以新值为准；新值没时刻时，若日期没变则保留旧的精确时刻
        if (rep.time.isEmpty() && repDate.equals(oldRepDate) && oldRepTime != null && !oldRepTime.isEmpty()) {
          repTime = oldRepTime;
        }
        c.close();
      }

      ContentValues values = new ContentValues();
      values.put(COL_ID, topicID);
      values.put(COL_PUB_DATE, pubDate);
      values.put(COL_PUB_TIME, pubTime);
      values.put(COL_REPLY_DATE, repDate);
      values.put(COL_REPLY_TIME, repTime);
      db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    } catch (Throwable t) {
      Log.w(TAG, "save failed for " + topicID + ": " + t.getMessage());
    }
  }

  /** 一条缓存记录 */
  public static class TopicTime {
    public final String publishDate;
    public final String publishTime;
    public final String replyDate;
    public final String replyTime;

    TopicTime(String publishDate, String publishTime, String replyDate, String replyTime) {
      this.publishDate = publishDate;
      this.publishTime = publishTime;
      this.replyDate = replyDate;
      this.replyTime = replyTime;
    }
  }

  /** 读取某帖记录，没有返回 null */
  public synchronized TopicTime get(String topicID) {
    if (topicID == null || topicID.isEmpty()) return null;
    SQLiteDatabase db = dbHelper.getReadableDatabase();
    Cursor c = db.query(TABLE,
        new String[] { COL_PUB_DATE, COL_PUB_TIME, COL_REPLY_DATE, COL_REPLY_TIME },
        COL_ID + "=?", new String[] { topicID }, null, null, null);
    if (c != null && c.moveToFirst()) {
      TopicTime t = new TopicTime(
          c.getString(c.getColumnIndexOrThrow(COL_PUB_DATE)),
          c.getString(c.getColumnIndexOrThrow(COL_PUB_TIME)),
          c.getString(c.getColumnIndexOrThrow(COL_REPLY_DATE)),
          c.getString(c.getColumnIndexOrThrow(COL_REPLY_TIME)));
      c.close();
      return t;
    }
    if (c != null) c.close();
    return null;
  }

  /** 当前缓存的记录数（便于调试/统计） */
  public synchronized int count() {
    SQLiteDatabase db = dbHelper.getReadableDatabase();
    Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
    int n = 0;
    if (c != null && c.moveToFirst()) {
      n = c.getInt(0);
      c.close();
    }
    return n;
  }

  /** 导出全部记录为 JSON 数组字符串 */
  public synchronized String exportJson() {
    SQLiteDatabase db = dbHelper.getReadableDatabase();
    Cursor c = db.query(TABLE,
        new String[] { COL_ID, COL_PUB_DATE, COL_PUB_TIME, COL_REPLY_DATE, COL_REPLY_TIME },
        null, null, null, null, null);
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    if (c != null) {
      while (c.moveToNext()) {
        if (!first) sb.append(",");
        first = false;
        sb.append("{\"id\":")
            .append(org.json.JSONObject.quote(nullToEmpty(c.getString(0))))
            .append(",\"pd\":").append(org.json.JSONObject.quote(nullToEmpty(c.getString(1))))
            .append(",\"pt\":").append(org.json.JSONObject.quote(nullToEmpty(c.getString(2))))
            .append(",\"rd\":").append(org.json.JSONObject.quote(nullToEmpty(c.getString(3))))
            .append(",\"rt\":").append(org.json.JSONObject.quote(nullToEmpty(c.getString(4))))
            .append("}");
      }
      c.close();
    }
    sb.append("]");
    return sb.toString();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  /** 从 JSON 数组导入记录（覆盖同 id），返回导入条数 */
  public synchronized int importJson(String json) {
    if (json == null || json.isEmpty()) return 0;
    int imported = 0;
    try {
      org.json.JSONArray arr = new org.json.JSONArray(json);
      SQLiteDatabase db = dbHelper.getWritableDatabase();
      db.beginTransaction();
      try {
        for (int i = 0; i < arr.length(); i++) {
          org.json.JSONObject o = arr.getJSONObject(i);
          String id = o.optString("id", "");
          if (id.isEmpty()) continue;
          ContentValues values = new ContentValues();
          values.put(COL_ID, id);
          values.put(COL_PUB_DATE, o.optString("pd", ""));
          values.put(COL_PUB_TIME, o.optString("pt", ""));
          values.put(COL_REPLY_DATE, o.optString("rd", ""));
          values.put(COL_REPLY_TIME, o.optString("rt", ""));
          db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
          imported++;
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
      Log.i(TAG, "importJson: " + imported + " records");
    } catch (Throwable t) {
      Log.w(TAG, "importJson failed: " + t.getMessage());
      return 0;
    }
    return imported;
  }

  /**
   * 清理 publish_date 早于 cutoffDate(yyyy-MM-dd) 的记录。
   * 返回删除条数。
   */
  public synchronized int cleanBefore(String cutoffDate) {
    if (cutoffDate == null || cutoffDate.isEmpty()) return 0;
    try {
      SQLiteDatabase db = dbHelper.getWritableDatabase();
      int deleted = db.delete(TABLE, COL_PUB_DATE + " < ? AND " + COL_PUB_DATE + " != ''",
          new String[] { cutoffDate });
      Log.i(TAG, "cleanBefore " + cutoffDate + ": deleted " + deleted);
      return deleted;
    } catch (Throwable t) {
      Log.w(TAG, "cleanBefore failed: " + t.getMessage());
      return 0;
    }
  }
}
