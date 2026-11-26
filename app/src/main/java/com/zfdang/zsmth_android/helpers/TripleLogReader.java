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

    Triple(String userId, String key, String num) {
      this.userId = userId;
      this.key = key;
      this.num = num;
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

  /**
   * 读取日志中最后一条有效三件套。
   * 只认成功登录类日志(SUCCESS_KEY_CHANGED / ManualLogin SUCCESS / LOGIN_PROTECT_ON)，
   * 不会误读服务端下发的 guest 三件套。
   * @return 三件套；找不到返回 null
   */
  public static Triple readLastValidTriple() {
    File dir = com.zfdang.SMTHApplication.getAppContext().getFilesDir();
    File logFile = new File(dir, DiagLogger.FILE_NAME);
    if (!logFile.exists()) return null;

    Triple last = null;
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
        if (m.find()) {
          Triple t = new Triple(m.group(1), m.group(2), m.group(3));
          if (t.isValid()) last = t;
        }
      }
    } catch (Throwable t) {
      DiagLogger.log("TripleReader", "READ_FAIL", DiagLogger.FAIL, String.valueOf(t));
    } finally {
      if (reader != null) {
        try { reader.close(); } catch (Throwable ignore) {}
      }
    }
    return last;
  }
}
