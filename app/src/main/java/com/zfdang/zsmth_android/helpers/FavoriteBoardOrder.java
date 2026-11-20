package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.models.Board;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收藏夹栏目的本地拖拽排序：按当前收藏夹路径保存每个条目的顺序。
 * key = 条目的唯一标识(folderID/sectionID/boardEngName)，value = 顺序下标。
 */
public final class FavoriteBoardOrder {
  private static final String PREF = "fav_board_order";
  private FavoriteBoardOrder() {}

  private static SharedPreferences pref() {
    return SMTHApplication.getAppContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
  }

  /** 条目的唯一标识 */
  private static String keyOf(Board board) {
    if (board == null) return "";
    if (board.isFolder() && board.getFolderID() != null) return "f:" + board.getFolderID();
    if (board.isSection() && board.getSectionID() != null) return "s:" + board.getSectionID();
    if (board.getBoardEngName() != null) return "b:" + board.getBoardEngName();
    return "";
  }

  /** 保存当前列表顺序（path 用于区分收藏夹的不同目录层级） */
  public static void save(String path, List<Board> boards) {
    StringBuilder sb = new StringBuilder();
    for (Board b : boards) {
      String k = keyOf(b);
      if (!k.isEmpty()) {
        if (sb.length() > 0) sb.append(",");
        sb.append(k);
      }
    }
    pref().edit().putString(path, sb.toString()).apply();
  }

  /** 按保存的顺序重排列表；未记录的条目保持原相对顺序排在后面 */
  public static void applyOrder(String path, List<Board> boards) {
    String saved = pref().getString(path, "");
    if (saved == null || saved.isEmpty() || boards.size() < 2) return;
    String[] keys = saved.split(",");
    final Map<String, Integer> order = new HashMap<>();
    for (int i = 0; i < keys.length; i++) order.put(keys[i], i);

    // 用稳定排序：有保存顺序的按序排，没有的排末尾（保持原顺序）
    final int maxIndex = keys.length;
    List<Board> sorted = new ArrayList<>(boards);
    Collections.sort(sorted, (a, b) -> {
      Integer ia = order.get(keyOf(a));
      Integer ib = order.get(keyOf(b));
      int va = ia == null ? maxIndex : ia;
      int vb = ib == null ? maxIndex : ib;
      return Integer.compare(va, vb);
    });
    boards.clear();
    boards.addAll(sorted);
  }
}
