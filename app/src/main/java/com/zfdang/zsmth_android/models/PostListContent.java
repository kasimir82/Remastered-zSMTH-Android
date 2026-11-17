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
    if (BlockListManager.getInstance().isBlocked(item.getAuthor(), item.getTitle())) {
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
