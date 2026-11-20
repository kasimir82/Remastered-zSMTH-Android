package com.zfdang.zsmth_android.helpers;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.zfdang.SMTHApplication;

/**
 * 帖子精确发帖/回复时间的本地缓存。
 *
 * 水木版面列表对「今天」的帖子返回精确时分秒(HH:mm:ss)，对更早的帖子只返回日期(yyyy-MM-dd)。
 * 本帖子的精确发帖时间只在它还是「今天」的窗口内可得，过后服务端就不再给。
 * 本缓存趁帖子是今天的时候把精确时间存下来(topicID -> publish_ms/reply_ms)，
 * 后续即使服务端只给日期，也能从本地取回精确时间用于排序。
 *
 * 只做存储/查询，不参与任何排序逻辑——排序侧如何引用由后续决定。
 */
public final class TopicTimeCache {
  private static final String TAG = "TopicTimeCache";

  private static final String DB_NAME = "topic_time_cache.db";
  private static final int DB_VERSION = 1;
  private static final String TABLE = "topic_time";
  private static final String COL_ID = "topic_id";
  private static final String COL_PUBLISH = "publish_ms";
  private static final String COL_REPLY = "reply_ms";

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
          + COL_PUBLISH + " INTEGER DEFAULT 0, "
          + COL_REPLY + " INTEGER DEFAULT 0)");
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_topic_time_publish ON " + TABLE + "(" + COL_PUBLISH + ")");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      db.execSQL("DROP TABLE IF EXISTS " + TABLE);
      onCreate(db);
    }
  }

  /**
   * 写入/更新一条帖子的精确时间。
   * 发帖时间一旦存下就不再覆盖（它不会变）；回复时间每次遇到都更新为最新值。
   *
   * @param topicID    帖子唯一ID
   * @param publishMs  精确发帖时间(毫秒)；0 表示本次没有精确值，不覆盖已有值
   * @param replyMs    精确最后回复时间(毫秒)；0 表示本次没有精确值，不覆盖已有值
   */
  public synchronized void save(String topicID, long publishMs, long replyMs) {
    if (topicID == null || topicID.isEmpty() || (publishMs <= 0 && replyMs <= 0)) {
      return;
    }
    try {
      SQLiteDatabase db = dbHelper.getWritableDatabase();
      // 先读已有值：发帖时间取"旧的优先"(已有非0则保留)，回复时间取"新的优先"
      long finalPublish = publishMs;
      long finalReply = replyMs;
      Cursor c = db.query(TABLE, new String[] { COL_PUBLISH, COL_REPLY },
          COL_ID + "=?", new String[] { topicID }, null, null, null);
      if (c != null && c.moveToFirst()) {
        long oldPublish = c.getLong(c.getColumnIndexOrThrow(COL_PUBLISH));
        if (oldPublish > 0) finalPublish = oldPublish; // 发帖时间已被记录过，保留
        c.close();
      }
      ContentValues values = new ContentValues();
      values.put(COL_ID, topicID);
      values.put(COL_PUBLISH, finalPublish);
      values.put(COL_REPLY, finalReply > 0 ? finalReply : 0);
      db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    } catch (Throwable t) {
      Log.w(TAG, "save failed for " + topicID + ": " + t.getMessage());
    }
  }

  /** 读取某帖的精确发帖时间(毫秒)，没有记录返回 0 */
  public synchronized long getPublishMs(String topicID) {
    if (topicID == null || topicID.isEmpty()) return 0;
    return queryOne(topicID, COL_PUBLISH);
  }

  /** 读取某帖的精确最后回复时间(毫秒)，没有记录返回 0 */
  public synchronized long getReplyMs(String topicID) {
    if (topicID == null || topicID.isEmpty()) return 0;
    return queryOne(topicID, COL_REPLY);
  }

  private long queryOne(String topicID, String column) {
    SQLiteDatabase db = dbHelper.getReadableDatabase();
    Cursor c = db.query(TABLE, new String[] { column },
        COL_ID + "=?", new String[] { topicID }, null, null, null);
    long result = 0;
    if (c != null && c.moveToFirst()) {
      result = c.getLong(c.getColumnIndexOrThrow(column));
      c.close();
    }
    return result;
  }

  /**
   * 把服务端返回的原始时间字符串转成今天的精确毫秒时间戳。
   * 只有纯时刻(HH:mm:ss 或 HH:mm)才算精确——这种格式服务端只在帖子时间属于「今天」时返回；
   * 日期格式(yyyy-MM-dd)返回 0，表示没有精确值。
   */
  public static long parseTodayMillis(String raw) {
    if (raw == null || raw.isEmpty()) return 0;
    String text = raw.trim();
    // 纯时刻：HH:mm:ss 或 HH:mm（服务端对今天的时间就返回这种）
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matcher(text);
    if (!m.find()) return 0;
    try {
      int hour = Integer.parseInt(m.group(1));
      int minute = Integer.parseInt(m.group(2));
      int second = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
      java.util.Calendar cal = java.util.Calendar.getInstance();
      cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
      cal.set(java.util.Calendar.MINUTE, minute);
      cal.set(java.util.Calendar.SECOND, second);
      cal.set(java.util.Calendar.MILLISECOND, 0);
      return cal.getTimeInMillis();
    } catch (Exception e) {
      return 0;
    }
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
}
