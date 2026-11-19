package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DEBUG 测试用的「敏感词绕过」发布方式工具：
 *  - 把 EditText 内容切成「用户输入」和「引用块」两段
 *  - 用户输入转成竖排诗歌风格文本
 *  - 用户输入渲染成 JPG 图片（作为附件发送）
 *
 * 引用块格式（ComposePostActivity 生成）：
 *   \n\n【 在 XXX 的大作中提到: 】\n: 引用行1\n: 引用行2\n
 * 分界线就是含「大作中提到」的那一行。
 */
public final class PublishMethodHelper {
  private static final String TAG = "PublishMethod";
  private static final String QUOTE_MARKER = "大作中提到";

  private PublishMethodHelper() {}

  /** 文本切分结果 */
  public static class SplitResult {
    public String userText;   // 用户输入（分界线之前，去掉尾部空行）
    public String quotePart;  // 引用块（含分界线本身及其后所有内容，保留前导换行）
    public boolean hasQuote;
  }

  /**
   * 按「大作中提到」行把编辑器内容切成两段。
   * 未找到分界线时，整段都算用户输入。
   */
  public static SplitResult split(String content) {
    SplitResult r = new SplitResult();
    if (content == null) {
      r.userText = "";
      r.quotePart = "";
      r.hasQuote = false;
      return r;
    }
    int marker = content.indexOf(QUOTE_MARKER);
    if (marker < 0) {
      r.userText = content;
      r.quotePart = "";
      r.hasQuote = false;
      return r;
    }
    // 找到分界线所在行的行首
    int lineStart = marker;
    while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') lineStart--;
    r.userText = stripTrailingNewlines(content.substring(0, lineStart));
    r.quotePart = content.substring(lineStart);
    r.hasQuote = true;
    return r;
  }

  private static String stripTrailingNewlines(String s) {
    int end = s.length();
    while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
    return s.substring(0, end);
  }

  /**
   * 竖排诗歌风格：把文本转成「每字一格、从上往下、从左到右」的排列（现代顺序，非古文从右往左）。
   *
   * 做法：
   *  - 按每列 maxCharsPerColumn 个字把文本分成若干列
   *  - 列从左到右排列（最左列是原文开头），每列从上往下读
   *  - 每行用 ASCII 制表符 │ 包裹成格子
   *
   * 例：原文 "床前明月光疑是地上霜"，每列5字 →
   *   │床│霜│
   *   │前│上│
   *   │明│地│
   *   │月│是│
   *   │光│疑│
   * （竖着读：最左列 床前明月光，右列 疑是地上霜）
   */
  public static String toVerticalPoem(String text, int maxCharsPerColumn) {
    if (text == null || text.trim().isEmpty()) return text;
    if (maxCharsPerColumn < 2) maxCharsPerColumn = 5;

    // 只处理非换行字符；原文换行视为段落分隔，转成竖排时用分隔符
    String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
    // 去掉换行，作为连续字符流（竖排里段落感用空列体现较弱，这里简化）
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (c != '\n') sb.append(c);
    }
    String chars = sb.toString();
    if (chars.isEmpty()) return text;

    int colCount = (chars.length() + maxCharsPerColumn - 1) / maxCharsPerColumn;
    List<String> columns = new ArrayList<>();
    for (int c = 0; c < colCount; c++) {
      int start = c * maxCharsPerColumn;
      int end = Math.min(start + maxCharsPerColumn, chars.length());
      columns.add(chars.substring(start, end));
    }
    // 竖排从右往左读 → 输出列顺序反转（最右侧原文列排在显示最右，纯文本从左到右输出时放最后）
    // 为了让第一列出现在视觉最右，输出时先输出后面的列
    int maxRows = 0;
    for (String col : columns) maxRows = Math.max(maxRows, col.length());

    StringBuilder out = new StringBuilder();
    // 用 ASCII 制表符包裹每一行：│字│字│字│ ，模拟参考图里的格子边框
    String cell = "│";
    for (int row = 0; row < maxRows; row++) {
      List<String> rowCells = new ArrayList<>();
      // 从左上开始(现代顺序，非古文)：最左列是原文开头，列从左到右排列，
      // 每列从上往下读，读完一列接下一列。
      for (int c = 0; c < colCount; c++) {
        String col = columns.get(c);
        rowCells.add(row < col.length() ? String.valueOf(col.charAt(row)) : "　");
      }
      // 去掉行尾的空单元格
      while (!rowCells.isEmpty() && "　".equals(rowCells.get(rowCells.size() - 1))) {
        rowCells.remove(rowCells.size() - 1);
      }
      if (rowCells.isEmpty()) continue;
      out.append(cell).append(String.join(cell, rowCells)).append(cell);
      if (row < maxRows - 1) out.append("\n");
    }
    return out.toString();
  }

  /** 渲染文字为图片的参数 */
  public static class ImageResult {
    public List<File> files = new ArrayList<>();
  }

  /**
   * 把文本渲染成 JPG 图片（横排正常语序，不竖排、不加制表符），存到 cache/attachments 目录。
   * 每行最多 MAX_COLS 个字符（宽度上限），图片尺寸按实际行数/字宽自适应，正好包住文字。
   * JPEG 采用最大压缩(quality=10)以减小体积。超长文本分页，每页自适应。
   * @param maxLinesPerPage 每页最多行数，防止单张图片过高
   */
  public static ImageResult textToImages(Context context, String text, int maxLinesPerPage) {
    ImageResult result = new ImageResult();
    if (text == null || text.trim().isEmpty()) return result;

    final int MAX_COLS = 14;   // 每行最多14个字符（用户实测手机可承受的横向宽度）
    final int paddingH = 20;
    final int paddingV = 16;
    final int fontSize = 34;
    final int lineHeight = 52;
    final int jpegQuality = 10; // 最大压缩

    TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(Color.rgb(30, 30, 30));
    paint.setTextSize(fontSize);

    // 按原文顺序切成每行最多 MAX_COLS 个字符（保留原有换行）
    java.util.List<String> allLines = new java.util.ArrayList<>();
    String[] paras = text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
    for (String para : paras) {
      if (para.isEmpty()) {
        allLines.add("");
        continue;
      }
      for (int i = 0; i < para.length(); i += MAX_COLS) {
        allLines.add(para.substring(i, Math.min(i + MAX_COLS, para.length())));
      }
    }
    if (allLines.isEmpty()) return result;

    // 每页最多 maxLinesPerPage 行
    if (maxLinesPerPage < 1) maxLinesPerPage = 20;
    int pages = (allLines.size() + maxLinesPerPage - 1) / maxLinesPerPage;

    float charW = paint.measureText("国"); // 中文近似字宽
    int imgW = Math.max(120, (int) Math.ceil(charW * MAX_COLS) + paddingH * 2);

    File dir = new File(context.getCacheDir(), "attachments");
    if (!dir.exists() && !dir.mkdirs()) {
      Log.e(TAG, "cannot create attachments dir");
      return result;
    }

    for (int p = 0; p < pages; p++) {
      int from = p * maxLinesPerPage;
      int to = Math.min(from + maxLinesPerPage, allLines.size());
      java.util.List<String> lines = allLines.subList(from, to);

      // 高度 = 首行字高 + 行间距 + 上下边距，正好包住
      int imgH = fontSize + (lines.size() - 1) * lineHeight + paddingV * 2;

      Bitmap bitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.RGB_565);
      Canvas canvas = new Canvas(bitmap);
      canvas.drawColor(Color.WHITE);

      float y = paddingV + fontSize;
      for (String line : lines) {
        canvas.drawText(line, paddingH, y, paint);
        y += lineHeight;
      }

      // 文件名必须全局唯一：站方按文件名去重，同名附件第二次上传会报 1205
      File file = new File(dir, "text-image-" + java.util.UUID.randomUUID().toString().replace("-", "") + "-" + p + ".jpg");
      try (FileOutputStream out = new FileOutputStream(file)) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
        out.flush();
        result.files.add(file);
        Log.d(TAG, "text image " + p + ": " + imgW + "x" + imgH + " lines=" + lines.size()
            + " size=" + file.length());
      } catch (Exception e) {
        Log.e(TAG, "save image failed", e);
      }
      bitmap.recycle();
    }
    return result;
  }
}
