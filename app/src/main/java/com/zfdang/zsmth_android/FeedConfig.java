package com.zfdang.zsmth_android;

import android.content.SharedPreferences;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.models.Board;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 信息流的配置：总开关，以及作为信息来源的版面（从收藏夹里挑选）。
 * 存在独立的 SharedPreferences 文件里，格式与屏蔽列表保持一致。
 */
public class FeedConfig {
  private static final String PREF_NAME = "ZSMTH_Feed";
  private static final String KEY_CONFIG = "feed_config";
  private static final int VERSION = 1;

  private static FeedConfig sInstance;

  private final SharedPreferences mPreference;
  private boolean mEnabled = false;
  private final List<Board> mBoards = new ArrayList<>();

  public static synchronized FeedConfig getInstance() {
    if (sInstance == null) {
      sInstance = new FeedConfig();
    }
    return sInstance;
  }

  private FeedConfig() {
    mPreference = SMTHApplication.getAppContext().getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE);
    load();
  }

  public boolean isEnabled() {
    // 信息流写死常开（2026-11-24）：去掉开关后永远启用
    return true;
  }

  public void setEnabled(boolean enabled) {
    if (this.mEnabled == enabled) {
      return;
    }
    this.mEnabled = enabled;
    save();
  }

  /** 返回副本，避免调用方直接改到内部列表 */
  public List<Board> getBoards() {
    return new ArrayList<>(mBoards);
  }

  public int getBoardCount() {
    return mBoards.size();
  }

  public boolean contains(String boardEngName) {
    if (boardEngName == null) {
      return false;
    }
    for (Board board : mBoards) {
      if (boardEngName.equals(board.getBoardEngName())) {
        return true;
      }
    }
    return false;
  }

  public void addBoard(Board board) {
    if (board == null || board.getBoardEngName() == null || contains(board.getBoardEngName())) {
      return;
    }
    Board item = new Board();
    item.initAsBoard(board.getBoardEngName(), board.getBoardChsName(), board.getCategoryName(), board.getModerator());
    mBoards.add(item);
    save();
  }

  public void removeBoard(String boardEngName) {
    if (boardEngName == null) {
      return;
    }
    for (int i = mBoards.size() - 1; i >= 0; i--) {
      if (boardEngName.equals(mBoards.get(i).getBoardEngName())) {
        mBoards.remove(i);
      }
    }
    save();
  }

  public void setBoards(List<Board> boards) {
    mBoards.clear();
    if (boards != null) {
      for (Board board : boards) {
        if (board != null && board.getBoardEngName() != null && !contains(board.getBoardEngName())) {
          Board item = new Board();
          item.initAsBoard(board.getBoardEngName(), board.getBoardChsName(), board.getCategoryName(), board.getModerator());
          mBoards.add(item);
        }
      }
    }
    save();
  }

  public void clearBoards() {
    mBoards.clear();
    save();
  }

  private void load() {
    String raw = mPreference.getString(KEY_CONFIG, "");
    if (raw == null || raw.length() == 0) {
      return;
    }
    try {
      JSONObject root = new JSONObject(raw);
      mEnabled = root.optBoolean("enabled", false);
      JSONArray array = root.optJSONArray("boards");
      mBoards.clear();
      if (array != null) {
        for (int i = 0; i < array.length(); i++) {
          JSONObject object = array.optJSONObject(i);
          if (object == null) {
            continue;
          }
          String eng = object.optString("eng", "");
          if (eng.length() == 0) {
            continue;
          }
          Board board = new Board();
          board.initAsBoard(eng, object.optString("chs", eng), object.optString("category", ""), "");
          mBoards.add(board);
        }
      }
    } catch (Exception e) {
      // 配置损坏时退回默认值，不影响启动
      mEnabled = false;
      mBoards.clear();
    }
  }

  private void save() {
    try {
      JSONObject root = new JSONObject();
      root.put("version", VERSION);
      root.put("enabled", mEnabled);
      JSONArray array = new JSONArray();
      for (Board board : mBoards) {
        JSONObject object = new JSONObject();
        object.put("eng", board.getBoardEngName());
        object.put("chs", board.getBoardChsName());
        object.put("category", board.getCategoryName());
        array.put(object);
      }
      root.put("boards", array);

      mPreference.edit().putString(KEY_CONFIG, root.toString()).apply();
    } catch (Exception e) {
      // ignore
    }
  }
}
