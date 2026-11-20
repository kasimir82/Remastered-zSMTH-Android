package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * DEBUG 测试用的「敏感词绕过」发布方式工具：
 *  - 把 EditText 内容切成「用户输入」和「引用块」两段
 *  - 用户输入转成竖排诗歌风格文本
 *  - 用户输入渲染成 PNG 图片（作为附件发送）
 *
 * 引用块格式（ComposePostActivity 生成）：
 *   \n\n【 在 XXX 的大作中提到: 】\n: 引用行1\n: 引用行2\n
 * 分界线就是含「大作中提到」的那一行。
 */
public final class PublishMethodHelper {
  private static final String TAG = "PublishMethod";
  public static final String QUOTE_MARKER = "大作中提到";

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
   * 压缩引用块：只保留「【 在 xxx 的大作中提到: 】」这一行，其后的引用正文用「…」省略。
   * 目的：回帖被 0405 拦截时，引用内容本身可能含敏感词，去掉引用正文避免二次拦截。
   */
  public static String abbreviateQuote(String quotePart) {
    if (quotePart == null || quotePart.trim().isEmpty()) return quotePart;
    String[] lines = quotePart.replace("\r\n", "\n").replace("\r", "\n").split("\n");
    for (String line : lines) {
      if (line.contains(QUOTE_MARKER)) {
        return line.trim() + "\n…";
      }
    }
    return quotePart;
  }

  /**
   * 竖排诗歌风格：现代顺序，从左上角开始写，列从左往右，每列从上往下读。
   *
   * 做法：
   *  - 按每列 maxCharsPerColumn 个字把文本分成若干列
   *  - 最左列是原文开头；纯文本从左到右输出时列序与原文一致（原文开头的列放每行最前/视觉最左）
   *  - 每行用汉字「丨」(全角等宽) 作为格子间分隔符，两端不加
   *
   * 例：原文 "床前明月光疑是地上霜"，每列5字 →
   *   床丨疑
   *   前丨是
   *   明丨地
   *   月丨上
   *   光丨霜
   * （竖着从左往右读：左列 床前明月光，右列 疑是地上霜）
   */
  public static String toVerticalPoem(String text, int maxCharsPerColumn) {
    if (text == null || text.trim().isEmpty()) return text;
    if (maxCharsPerColumn < 2) maxCharsPerColumn = 5;

    // 只处理非换行字符；原文换行视为段落分隔，转成竖排时用分隔符
    String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
    // 去掉换行，并把半角字符(英文/数字/标点/空格)转成全角，
    // 让每个字符都占一个中文宽度，丨格子才能对齐
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (c != '\n') sb.append(toFullWidth(c));
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
    // 现代顺序从左到右读 → 输出列顺序与原文一致（第一列出现在视觉最左）
    int maxRows = 0;
    for (String col : columns) maxRows = Math.max(maxRows, col.length());

    StringBuilder out = new StringBuilder();
    // 用汉字「丨」(U+4E28)作为格子之间的分隔符：字丨字丨字（两端不加分隔符）。
    // 它是全角等宽的 CJK 汉字，形状为竖线，比原制表符 │(半角) 更能保证格子对齐
    String cell = "丨";
    for (int row = 0; row < maxRows; row++) {
      List<String> rowCells = new ArrayList<>();
      // 现代顺序：从左上角开始，列从左往右。最左列是原文开头(cols[0])。
      // 纯文本从左到右输出，列序与原文一致：原开头的列放在每行最前(视觉最左)。
      for (int c = 0; c < colCount; c++) {
        String col = columns.get(c);
        rowCells.add(row < col.length() ? String.valueOf(col.charAt(row)) : "　");
      }
      // 去掉行尾的空单元格
      while (!rowCells.isEmpty() && "　".equals(rowCells.get(rowCells.size() - 1))) {
        rowCells.remove(rowCells.size() - 1);
      }
      if (rowCells.isEmpty()) continue;
      // 只在格子之间放分隔符，左右两端不加
      out.append(String.join(cell, rowCells));
      if (row < maxRows - 1) out.append("\n");
    }
    return out.toString();
  }

  /**
   * 半角字符转全角：ASCII 空格(0x20)→全角空格(U+3000)，其它可打印 ASCII(0x21~0x7E)→+0xFEE0。
   * 中文、全角、其它字符原样返回。用于竖排时让英文/数字/标点/空格与中文等宽。
   */
  private static char toFullWidth(char c) {
    if (c == ' ') return '\u3000';
    if (c >= 0x21 && c <= 0x7E) return (char) (c + 0xFEE0);
    return c;
  }

  /** 渲染文字为图片的参数 */
  public static class ImageResult {
    public List<File> files = new ArrayList<>();
  }

  /**
   * 把文本渲染成 PNG 图片（横排正常语序，不竖排、不加制表符），存到 cache/attachments 目录。
   * 每行最多 MAX_COLS 个字符（宽度上限），图片尺寸按实际行数/字宽自适应，正好包住文字。
   * PNG 无损压缩(避免 JPG 有损压缩导致文字模糊/上传异常)。超长文本分页，每页自适应。
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

      // 加 15% 椒盐噪点：随机把像素点变成纯黑或纯白，干扰图片敏感词识别，人眼仍可读
      applySaltPepperNoise(bitmap, 0.15f);

      // 文件名必须全局唯一：站方按文件名去重，同名附件第二次上传会报 1205
      File file = new File(dir, "text-image-" + java.util.UUID.randomUUID().toString().replace("-", "") + "-" + p + ".png");
      try (FileOutputStream out = new FileOutputStream(file)) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
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

  /**
   * Captcha 风格图片（中度扭曲）：逐字符随机旋转/缩放 + 黑体楷体混排 + 干扰曲线 + 噪点，
   * 每行最多 CAPTCHA_COLS 个中文字，JPEG quality=10 压缩。用于绕过站方图片文字识别。
   */
  private static final int CAPTCHA_COLS = 7;
  private static final int CAPTCHA_FONT = 44;
  private static final int CAPTCHA_PAD = 20;
  private static final int CAPTCHA_LINE_H = CAPTCHA_FONT + 26; // 与 Java 原型一致

  public static ImageResult textToCaptchaImages(Context context, String text) {
    ImageResult result = new ImageResult();
    if (text == null || text.trim().isEmpty()) return result;

    // 按每行 CAPTCHA_COLS 个中文字切行
    List<String> lines = new ArrayList<>();
    String flat = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "");
    for (int i = 0; i < flat.length(); i += CAPTCHA_COLS) {
      lines.add(flat.substring(i, Math.min(i + CAPTCHA_COLS, flat.length())));
    }
    if (lines.isEmpty()) return result;

    Random rnd = new Random(text.hashCode()); // 同文本可复现

    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setTextSize(CAPTCHA_FONT);
    // 统一用一种字体：混排 serif 会让部分字实际占宽>charW 导致重叠
    Typeface font = Typeface.create("sans-serif", Typeface.NORMAL);
    paint.setTypeface(font);

    float charW = paint.measureText("测");

    int w = CAPTCHA_PAD * 2 + (int) (charW * CAPTCHA_COLS) + 12;
    int h = CAPTCHA_PAD * 2 + CAPTCHA_LINE_H * lines.size();

    File dir = new File(context.getCacheDir(), "attachments");
    if (!dir.exists() && !dir.mkdirs()) {
      Log.e(TAG, "cannot create captcha attachments dir");
      return result;
    }

    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Color.WHITE);

    for (int li = 0; li < lines.size(); li++) {
      String line = lines.get(li);
      float baselineY = CAPTCHA_PAD + CAPTCHA_FONT * 0.85f + li * CAPTCHA_LINE_H;
      float x = CAPTCHA_PAD;
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        float rot = (rnd.nextFloat() * 2 - 1) * 60f;      // ±60°(测试)
        float scale = 1f + (rnd.nextFloat() * 2 - 1) * 0.12f;
        float dy = (rnd.nextFloat() * 2 - 1) * 4f;

        // 用 canvas 原生 rotate/scale（绕字符中心），避免 Matrix.postXXX 与 AffineTransform 顺序差异导致错位
        canvas.save();
        canvas.translate(x + charW / 2f, baselineY + dy);
        canvas.rotate(rot);
        canvas.scale(scale, scale);
        int shade = 20 + rnd.nextInt(40);
        paint.setColor(Color.rgb(shade, shade, shade));
        // 与原型一致：drawText 的 y 是基线，字画在其上方；x 左移半字宽使字符绕中心旋转
        canvas.drawText(String.valueOf(c), -charW / 2f, 0, paint);
        canvas.restore();

        x += charW * (0.95f + rnd.nextFloat() * 0.12f); // 与原型一致的字距波动
      }
    }

    // 3 条二次贝塞尔干扰曲线
    Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    linePaint.setStyle(Paint.Style.STROKE);
    linePaint.setStrokeWidth(1.4f);
    android.graphics.Path path = new android.graphics.Path();
    // 8 条干扰线，颜色更深(alpha 200~235, 深灰到黑)
    for (int i = 0; i < 8; i++) {
      int y1 = rnd.nextInt(h), y2 = rnd.nextInt(h), yc = rnd.nextInt(h);
      path.reset();
      path.moveTo(CAPTCHA_PAD, y1);
      path.quadTo(w / 2f, yc, w - CAPTCHA_PAD, y2);
      int shade = rnd.nextInt(90); // 0~90 深灰
      linePaint.setColor(Color.argb(200 + rnd.nextInt(36), shade, shade, shade));
      canvas.drawPath(path, linePaint);
    }

    // 3000 个噪点，画成 2x2 小块(JPG Q10 会吃掉单像素)，全黑/深灰
    Paint noisePaint = new Paint();
    for (int i = 0; i < 3000; i++) {
      int px = rnd.nextInt(w), py = rnd.nextInt(h);
      int shade = rnd.nextInt(110); // 0~110 深灰到黑
      noisePaint.setColor(Color.rgb(shade, shade, shade));
      canvas.drawRect(px, py, px + 2, py + 2, noisePaint);
    }

    File file = new File(dir, "captcha-" + java.util.UUID.randomUUID().toString().replace("-", "") + ".jpg");
    try (FileOutputStream out = new FileOutputStream(file)) {
      bitmap.compress(Bitmap.CompressFormat.JPEG, 10, out); // Q10
      out.flush();
      result.files.add(file);
      Log.d(TAG, "captcha image: " + w + "x" + h + " lines=" + lines.size() + " size=" + file.length());
    } catch (Exception e) {
      Log.e(TAG, "save captcha failed", e);
    }
    bitmap.recycle();
    return result;
  }

  /**
   * 在每个汉字之间插入 1~2 个全角下划线 ＿(U+FF3F)，打断敏感词的连续匹配。
   * 下划线与汉字同宽；用文本 hashCode 作随机种子，保证同一文本多次生成结果一致
   * （这样弹窗时写入剪贴板的文本与随后发布的正文完全相同）。
   */
  public static String insertFillers(String text) {
    if (text == null || text.trim().isEmpty()) return text;
    String normalized = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "");
    if (normalized.isEmpty()) return text;
    java.util.Random random = new java.util.Random(normalized.hashCode());
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < normalized.length(); i++) {
      sb.append(normalized.charAt(i));
      if (i < normalized.length() - 1) {
        int count = 1 + random.nextInt(2); // 1 或 2 个
        for (int j = 0; j < count; j++) sb.append('\uFF3F');
      }
    }
    return sb.toString();
  }

  /**
   * 竖排文本生成（不再插入下划线填充）。
   * 之前会在字间插入全角下划线试图打断敏感词，但实际无用（竖排的丨格子本身已隔开字符），
   * 反而让文本变长难读，故去掉。保留原方法名以免改动调用点。
   */
  public static String toVerticalWithFillers(String text) {
    if (text == null || text.trim().isEmpty()) return text;
    int charCount = text.replace("\n", "").replace("\r", "").length();
    // 最宽 7 列（不含分隔符）：每列字数 = ceil(总字数/7)。文本短时列数自然更少。
    int perCol = Math.max(3, (int) Math.ceil(charCount / 7.0));
    return toVerticalPoem(text, perCol);
  }

  /**
   * 给 bitmap 加椒盐噪点：以 density 概率把像素随机变成纯黑或纯白。
   * 用于干扰图片敏感词识别；density=0.15 时人眼仍可正常阅读。
   */
  private static void applySaltPepperNoise(Bitmap bitmap, float density) {
    int w = bitmap.getWidth();
    int h = bitmap.getHeight();
    int[] pixels = new int[w * h];
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
    java.util.Random random = new java.util.Random();
    float half = density / 2f;
    for (int i = 0; i < pixels.length; i++) {
      float r = random.nextFloat();
      if (r < half) {
        pixels[i] = 0xFF000000; // pepper 黑
      } else if (r > 1f - half) {
        pixels[i] = 0xFFFFFFFF; // salt 白
      }
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
  }
}
