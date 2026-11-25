package com.zfdang.zsmth_android.helpers;

import android.util.Log;

import com.zfdang.SMTHApplication;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一诊断日志：所有调试日志双写 logcat + 单个本地文件，防止 logcat 被冲走后无法追溯。
 *
 * 文件路径：/data/data/<包名>/files/zsmth_debug.log
 * 取回：adb shell run-as com.zfdang.zsmth_android cat files/zsmth_debug.log
 *
 * 行格式：时间 [模块名][代码/PASS,FAIL] 详细内容
 *   例：2026-11-25 10:12:36.123 [SilentLogin][TIMEOUT/FAIL] timeout after 20s
 *
 * 轮转策略：文件超过 MAX_BYTES(默认100MB) 时只保留最后 KEEP_LINES(默认100) 行。
 */
public final class DiagLogger {
  /** 统一日志文件（所有模块共用） */
  public static final String FILE_NAME = "zsmth_debug.log";
  private static final long MAX_BYTES = 100L * 1024 * 1024; // 100MB
  private static final int KEEP_LINES = 100;

  private static final java.text.SimpleDateFormat TIME_FMT =
      new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault());

  /** 状态代码 */
  public static final String PASS = "PASS";
  public static final String FAIL = "FAIL";

  private DiagLogger() {}

  /**
   * 统一写一条日志：双写 logcat + 本地文件。
   * @param module 模块名（如 SilentLogin / Session / Publish）
   * @param code   信息/错误代码（如 TIMEOUT / HTTP_ERROR / PAGE_START）
   * @param status PASS 或 FAIL
   * @param detail 详细内容
   */
  public static synchronized void log(String module, String code, String status, String detail) {
    String tag = "[" + module + "]";
    String line = TIME_FMT.format(new java.util.Date())
        + " [" + module + "][" + code + "/" + status + "] "
        + (detail == null ? "" : detail);

    // logcat：保持可读，级别按状态区分
    if (FAIL.equals(status)) {
      Log.e(tag, code + " | " + detail);
    } else {
      Log.d(tag, code + " | " + detail);
    }
    writeToFile(line);
  }

  /** 兼容旧调用：d(tag,msg) → 归到 [Diag][DEBUG/PASS] */
  public static void d(String tag, String msg) {
    log("Diag", "DEBUG", PASS, tag + " | " + msg);
  }

  /** 兼容旧调用：w(tag,msg) → 归到 [Diag][WARN/FAIL] */
  public static void w(String tag, String msg) {
    log("Diag", "WARN", FAIL, tag + " | " + msg);
  }

  /** 兼容旧调用：e(tag,msg) → 归到 [Diag][ERROR/FAIL] */
  public static void e(String tag, String msg) {
    log("Diag", "ERROR", FAIL, tag + " | " + msg);
  }

  private static void writeToFile(String line) {
    try {
      File dir = SMTHApplication.getAppContext().getFilesDir();
      if (dir == null) return;
      File file = new File(dir, FILE_NAME);
      if (file.exists() && file.length() > MAX_BYTES) {
        trimToLastLines(file, KEEP_LINES);
      }
      BufferedWriter w = new BufferedWriter(new FileWriter(file, true));
      try {
        w.write(line);
        w.newLine();
      } finally {
        w.close();
      }
    } catch (Throwable t) {
      Log.w("DiagLogger", "write failed: " + t.getMessage());
    }
  }

  /** 只保留文件最后 keep 行（环形数组，避免 O(n^2)） */
  private static void trimToLastLines(File file, int keep) {
    BufferedReader r = null;
    try {
      String[] ring = new String[keep];
      int count = 0, next = 0;
      r = new BufferedReader(new FileReader(file));
      String line;
      while ((line = r.readLine()) != null) {
        ring[next] = line;
        next = (next + 1) % keep;
        if (count < keep) count++;
      }
      r.close();
      r = null;

      BufferedWriter w = new BufferedWriter(new FileWriter(file, false));
      try {
        for (int i = 0; i < count; i++) {
          int idx = (next - count + i + keep) % keep;
          w.write(ring[idx]);
          w.newLine();
        }
      } finally {
        w.close();
      }
      Log.w("DiagLogger", "trimmed to last " + keep + " lines");
    } catch (Throwable t) {
      Log.w("DiagLogger", "trim failed: " + t.getMessage());
    } finally {
      if (r != null) {
        try { r.close(); } catch (Throwable ignore) {}
      }
    }
  }
}
