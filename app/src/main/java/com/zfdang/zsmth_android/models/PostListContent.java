package com.zfdang.zsmth_android.models;

import com.zfdang.zsmth_android.helpers.BlockListManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class PostListContent {

  public static final List<Post> POSTS = new ArrayList<Post>();

  public static final Map<String, Post> POST_MAP = new HashMap<String, Post>();

  /**
   * @return true 表示已加入列表; false 表示命中屏蔽规则被丢弃
   */
  public static boolean addItem(Post item) {
    if (item == null) return false;
    // 用 getRawAuthor()（纯用户ID）匹配黑名单：getAuthor() 在有昵称时返回 "ID(昵称)"，
    // 会导致黑名单里存的纯ID匹配不上，回帖列表里仍显示被屏蔽用户。
    if (BlockListManager.getInstance().isBlocked(item.getRawAuthor(), item.getTitle())) {
      return false;
    }
    POSTS.add(item);
    POST_MAP.put(item.getPostID(), item);
    return true;
  }

  public static void clear() {
    POSTS.clear();
    POST_MAP.clear();
  }
}
