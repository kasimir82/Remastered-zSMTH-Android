package com.zfdang.zsmth_android.helpers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从本地诊断日志(zsmth_debug.log)中读取最后一次成功登录记录的完整UTMP三件套。
 *
 * 识别的日志行(按出现顺序，取最后一条匹配的)：
 *  - [SilentLogin][SUCCESS_KEY_CHANGED/PASS] ... | USERID=xxx KEY=xxx NUM=xxx
 *  - [ManualLogin][SUCCESS/PASS] userId=xxx | USERID=xxx KEY=xxx NUM=xxx
 *  - [CookieJar][LOGIN_PROTECT_ON/PASS] ... saved triple: USERID=xxx KEY=xxx NUM=xxx
 */
public final class TripleLogReader {
  private TripleLogReader() {}

  public static class Triple {
    public final String userId;
    public final String key;
    public final String num;
    /** 该三件套在日志里的时间戳(毫秒)。解析失败为 0，视为"很久以前"。 */
    public final long timestampMs;

    Triple(String userId, String key, String num) {
      this(userId, key, num, 0L);
    }

    Triple(String userId, String key, String num, long timestampMs) {
      this.userId = userId;
      this.key = key;
      this.num = num;
      this.timestampMs = timestampMs;
    }

    public boolean isValid() {
      return userId != null && !userId.isEmpty() && !"guest".equalsIgnoreCase(userId)
          && key != null && !key.isEmpty()
          && num != null && !num.isEmpty();
    }

    @Override public String toString() {
      return "USERID=" + userId + " KEY=" + key + " NUM=" + num;
    }
  }

  /** 三件套字段：USERID=xxx KEY=xxx NUM=xxx */
  private static final Pattern TRIPLE_PATTERN = Pattern.compile(
      "USERID=([^\\s]+)\\s+KEY=([^\\s]+)\\s+NUM=([^\\s]+)");

  /** 日志行首时间戳：2026-11-27 04:50:31.880 */
  private static final java.text.SimpleDateFormat LOG_TS_FORMAT =
      new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US);

  /** 解析日志行首的时间戳；解析不了返回 0 */
  private static long parseLineTimestamp(String line) {
    if (line == null || line.length() < 23) return 0L;
    try {
      synchronized (LOG_TS_FORMAT) {
        return LOG_TS_FORMAT.parse(line.substring(0, 23)).getTime();
      }
    } catch (Throwable t) {
      return 0L;
    }
  }

  /**
   * 读取日志中「所有」非 guest 的三件套，按出现顺序返回（同一组 KEY+NUM 只保留一条）。
   * 用于「恢复UTMP并注销」的批量清理：前面的历史会话逐个写回cookie后注销，
   * 最后一条才是要恢复的当前会话。
   */
  public static java.util.List<Triple> readAllValidTriples() {
    java.util.List<Triple> triples = new java.util.ArrayList<>();
    File dir = com.zfdang.SMTHApplication.getAppContext().getFilesDir();
    File logFile = new File(dir, DiagLogger.FILE_NAME);
    if (!logFile.exists()) return triples;

    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new FileReader(logFile));
      String line;
      while ((line = reader.readLine()) != null) {
        // 只认成功登录/保护锁开启这几类日志，避免误读 SERVER_UTMP 的 guest
        boolean isLoginLog = line.contains("[SUCCESS_KEY_CHANGED/PASS]")
            || line.contains("[ManualLogin][SUCCESS/")
            || line.contains("saved triple:");
        if (!isLoginLog) continue;
        Matcher m = TRIPLE_PATTERN.matcher(line);
        if (!m.find()) continue;
        Triple t = new Triple(m.group(1), m.group(2), m.group(3), parseLineTimestamp(line));
        if (!t.isValid()) continue;
        // 同一次登录会同时写 SUCCESS_KEY_CHANGED 和 LOGIN_PROTECT_ON(可能两行)，按 KEY+NUM 去重
        boolean duplicated = false;
        for (Triple exist : triples) {
          if (exist.key.equals(t.key) && exist.num.equals(t.num)) {
            duplicated = true;
            break;
          }
        }
        if (!duplicated) triples.add(t);
      }
    } catch (Throwable t) {
      DiagLogger.log("TripleReader", "READ_FAIL", DiagLogger.FAIL, String.valueOf(t));
    } finally {
      if (reader != null) {
        try { reader.close(); } catch (Throwable ignore) {}
      }
    }
    return triples;
  }

  /**
   * 只返回「时间窗口内」下发的三件套（按时间顺序、去重）。
   * 用于批量清理历史会话时限定范围，避免把很久以前的记录也拿去注销。
   */
  public static java.util.List<Triple> readValidTriplesWithin(long windowMs) {
    long cutoff = System.currentTimeMillis() - windowMs;
    java.util.List<Triple> recent = new java.util.ArrayList<>();
    for (Triple t : readAllValidTriples()) {
      if (t.timestampMs >= cutoff) recent.add(t);
    }
    return recent;
  }

  /**
   * 读取日志中最后一条有效三件套。
   * 只认成功登录类日志(SUCCESS_KEY_CHANGED / ManualLogin SUCCESS / LOGIN_PROTECT_ON)，
   * 不会误读服务端下发的 guest 三件套。
   * @return 三件套；找不到返回 null
   */
  public static Triple readLastValidTriple() {
    java.util.List<Triple> all = readAllValidTriples();
    return all.isEmpty() ? null : all.get(all.size() - 1);
  }
}
