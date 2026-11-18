package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.models.Topic;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阅读进度存储：记录每个帖子看到哪一楼、退出时的回帖数快照、楼层所在页。
 *
 * 仿照 BlockListManager，用 SharedPreferences 里一条 JSON 存储，格式：
 * {"version":1,"items":{"WorkLife_12345":{"floor":15,"reply":20,"page":2}}}
 */
public class ReadProgressManager {
  private static final String TAG = "ReadProgress";
  private static final String PREF_NAME = "ZSMTH_ReadProgress";
  private static final String KEY_DATA = "data";
  private static final int VERSION = 1;

  private static ReadProgressManager instance;
  private final SharedPreferences pref;
  private JSONObject items;

  public static class Progress {
    public int floor;          // 离开时看到的楼层（1楼=楼主）
    public int replySnapshot;  // 退出时的回帖数快照
    public int page;           // 楼层所在页（每页10楼）
  }

  public static synchronized ReadProgressManager getInstance() {
    if (instance == null) instance = new ReadProgressManager();
    return instance;
  }

  private ReadProgressManager() {
    pref = SMTHApplication.getAppContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    load();
  }

  private String key(String board, String topicID) {
    return board + "_" + topicID;
  }

  public void save(String board, String topicID, int floor, int replySnapshot, int page) {
    if (board == null || topicID == null) return;
    try {
      JSONObject entry = new JSONObject();
      entry.put("floor", floor);
      entry.put("reply", replySnapshot);
      entry.put("page", page);
      items.put(key(board, topicID), entry);
      savePref();
    } catch (Exception e) {
      Log.e(TAG, "save failed", e);
    }
  }

  /** 没有记录返回 null */
  public Progress get(String board, String topicID) {
    if (board == null || topicID == null) return null;
    try {
      JSONObject entry = items.optJSONObject(key(board, topicID));
      if (entry == null) return null;
      Progress p = new Progress();
      p.floor = entry.optInt("floor", 0);
      p.replySnapshot = entry.optInt("reply", 0);
      p.page = entry.optInt("page", 1);
      return p;
    } catch (Exception e) {
      return null;
    }
  }

  /** 只取回帖数快照，供列表显示 AA+BB；没有记录返回 0 */
  public int getReplySnapshot(String board, String topicID) {
    Progress p = get(board, topicID);
    return p == null ? 0 : p.replySnapshot;
  }

  public void remove(String board, String topicID) {
    try {
      items.remove(key(board, topicID));
      savePref();
    } catch (Exception e) {
      Log.e(TAG, "remove failed", e);
    }
  }

  private void savePref() {
    try {
      JSONObject root = new JSONObject();
      root.put("version", VERSION);
      root.put("items", items);
      pref.edit().putString(KEY_DATA, root.toString()).apply();
    } catch (Exception e) {
      Log.e(TAG, "savePref failed", e);
    }
  }

  /**
   * 从 Topic 里取回帖数字，和列表显示口径保持一致：优先 replyCounts（列表页的"回复 N"），
   * 没有才用 totalPostNo（详情页"贴数"含楼主，比列表口径大1，优先用会凭空多1）。
   */
  public static int replyCountOf(Topic topic) {
    if (topic == null) return 0;
    int reply = firstNumber(topic.getReplyCounts());
    if (reply > 0) return reply;
    return firstNumber(topic.getTotalPostNoAsStr());
  }

  /**
   * 列表用回复数文案：
   * - 没看过此帖 → "回复 CC"（CC 为当前回帖数）
   * - 看过且有新增 → "回复 AA+BB"（AA=退出时快照，BB=当前-快照）
   * - 看过但无新增 → "回复 AA"
   */
  private static final int COLOR_ZERO = 0xFFE53935;   // 回复 0 标红
  private static final int COLOR_READ = 0xFF1976D2;   // 看过的帖子回复数标蓝

  /**
   * 带颜色的回复数文案：
   * - 回复数为 0 → 数字标红
   * - 看过的帖子（有快照，含 AA / AA+BB）→ 数字部分标蓝
   * prefix 如 "回复 " / "回复: " / ""（热门用纯数字）
   */
  public static SpannableString replySpannable(Topic topic, String prefix) {
    if (prefix == null) prefix = "";
    if (topic == null) return new SpannableString(prefix);

    int current = replyCountOf(topic);
    int snapshot = getInstance().getReplySnapshot(topic.getBoardEngName(), topic.getTopicID());
    String numberText;
    int color = -1; // -1 表示不着色

    if (snapshot > 0) {
      int added = current - snapshot;
      numberText = added > 0 ? snapshot + "+" + added : String.valueOf(snapshot);
      color = COLOR_READ;
    } else {
      numberText = String.valueOf(current);
      if (current == 0) color = COLOR_ZERO;
    }

    String full = prefix + numberText;
    SpannableString sp = new SpannableString(full);
    if (color != -1 && numberText.length() > 0) {
      sp.setSpan(new ForegroundColorSpan(color), prefix.length(), full.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return sp;
  }

  public static String formatReplyLabel(Topic topic) {
    int current = replyCountOf(topic);
    if (topic == null) return "";
    int snapshot = getInstance().getReplySnapshot(topic.getBoardEngName(), topic.getTopicID());
    if (snapshot > 0) {
      int added = current - snapshot;
      if (added > 0) {
        return "回复 " + snapshot + "+" + added;
      }
      return "回复 " + snapshot;
    }
    return "回复 " + current;
  }

  /** 热门列表用纯数字格式：看过的有新增显示 "AA+BB"，否则显示当前数字 */
  public static String formatReplyNumber(Topic topic) {
    int current = replyCountOf(topic);
    if (topic == null) return "";
    int snapshot = getInstance().getReplySnapshot(topic.getBoardEngName(), topic.getTopicID());
    if (snapshot > 0) {
      int added = current - snapshot;
      if (added > 0) return snapshot + "+" + added;
      return String.valueOf(snapshot);
    }
    return String.valueOf(current);
  }

  /** 提取字符串里第一段数字，没有返回 0 */
  public static int firstNumber(String s) {
    if (s == null) return 0;
    Matcher m = Pattern.compile("\\d+").matcher(s);
    if (m.find()) {
      try {
        return Integer.parseInt(m.group());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }

  private void load() {
    items = new JSONObject();
    String data = pref.getString(KEY_DATA, null);
    if (data == null) return;
    try {
      JSONObject root = new JSONObject(data);
      JSONObject saved = root.optJSONObject("items");
      if (saved != null) items = saved;
    } catch (Exception e) {
      Log.e(TAG, "load failed", e);
    }
  }
}
