package com.zfdang.zsmth_android.models;

/**
 * Created by zfdang on 2016-3-14.
 */

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 */
public class Topic implements Parcelable {

  static private final int POST_PER_PAGE = 10;

  // 分隔符，只有一个category的名称
  public boolean isCategory;
  private String category;

  // 正常的主题
  private String boardEngName;
  private String boardChsName;

  private String topicID;
  private String title;
  private String author;
  private String publishDate;
  private String replier;
  private String replyDate;

  // 发表时间排序键的缓存补齐结果（每个 Topic 只查一次库，排序比较会调用多次）
  private boolean publishKeyCacheResolved = false;
  private long publishKeyFromCache = 0;

  // 是否是置顶的主题
  public boolean isSticky;

  // status of topic
  private String score = null;
  private String likes = null;
  private String replyCounts = null;
  private boolean hasAttach;

  private int totalPageNo = 0;
  private int totalPostNo = 0;

  // this is actually a category, used in guidance fragment to seperate hot topics
  public Topic(String category) {
    isCategory = true;
    this.category = category;
  }

  public Topic() {
    isCategory = false;
    this.boardChsName = "";
    this.boardEngName = "";
    this.author = "";
  }

  public void setTotalPostNoFromString(String totalPostNoString) {
    try {
      this.totalPostNo = Integer.parseInt(totalPostNoString);
      if (this.totalPostNo % Topic.POST_PER_PAGE == 0) {
        this.totalPageNo = this.totalPostNo / Topic.POST_PER_PAGE;
      } else {
        this.totalPageNo = this.totalPostNo / Topic.POST_PER_PAGE + 1;
      }
    } catch (Exception e) {
      Log.d("Topic", Log.getStackTraceString(e));
    }
  }

  public int getTotalPageNo() {
    return totalPageNo;
  }

  public String getTotalPostNoAsStr() {
    if (this.totalPostNo == 0) return "";

    String result = String.format("%d", this.totalPostNo);
    return result;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  public String getBoardEngName() {
    return boardEngName;
  }

  public String getTopicURL() {
    return boardEngName;
  }

  public void setBoardEngName(String boardEngName) {
    this.boardEngName = boardEngName;
  }

  public String getBoardChsName() {
    return boardChsName;
  }

  public void setBoardChsName(String boardChsName) {
    this.boardChsName = boardChsName;
  }

  public String getBoardName() {
    if (boardChsName != null && boardChsName.length() > 0) {
      return boardChsName + " [" + boardEngName + "]";
    } else {
      return boardEngName;
    }
  }

  public String getTopicID() {
    return topicID;
  }

  public void setTopicID(String topicID) {
    this.topicID = topicID;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getPublishDate() {
    return publishDate;
  }

  public void setPublishDate(String publishDate) {
    this.publishDate = publishDate;
  }

  private static final java.util.regex.Pattern DATE_FULL =
      java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
  private static final java.util.regex.Pattern DATE_SHORT =
      java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})");
  private static final java.util.regex.Pattern DATE_TIME_SHORT =
      java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
  private static final java.util.regex.Pattern TIME_ONLY =
      java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

  /** 排序键统一成「日期 * 100000 + 当天秒数」，值越大越新 */
  private static final long SORT_KEY_SCALE = 100000L;
  /** 只给时刻时代表今天的占位日期；昨天占位比它小 1 */
  private static final long SORT_KEY_TODAY = 99999999L;
  private static final long SORT_KEY_YESTERDAY = 99999998L;

  private static long secondsOf(java.util.regex.Matcher matcher, int hourGroup, int minuteGroup, int secondGroup) {
    long seconds = Long.parseLong(matcher.group(hourGroup)) * 3600L
        + Long.parseLong(matcher.group(minuteGroup)) * 60L;
    if (matcher.group(secondGroup) != null) {
      seconds += Long.parseLong(matcher.group(secondGroup));
    }
    return seconds;
  }

  /**
   * 把列表里各种时间写法统一成可比较的键：
   * yyyy-MM-dd、MM-dd HH:mm(:ss)、MM-dd、HH:mm(:ss)、「今天 / 昨天」，认不出来时退回 fallback。
   */
  private static long parseSortKey(String raw, long fallback) {
    String date = raw == null ? "" : raw.trim();
    if (date.length() == 0) {
      return fallback;
    }

    java.util.regex.Matcher full = DATE_FULL.matcher(date);
    if (full.find()) {
      long key = (Long.parseLong(full.group(1)) * 10000L
          + Long.parseLong(full.group(2)) * 100L
          + Long.parseLong(full.group(3))) * SORT_KEY_SCALE;
      // 若后面还跟着时刻(如拼出来的 "yyyy-MM-dd HH:mm:ss")，加上当天秒数以区分同日帖子
      java.util.regex.Matcher tail = TIME_ONLY.matcher(date.substring(full.end()));
      if (tail.find()) {
        key += secondsOf(tail, 1, 2, 3);
      }
      return key;
    }

    java.util.regex.Matcher shortDateTime = DATE_TIME_SHORT.matcher(date);
    if (shortDateTime.find()) {
      int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
      return (year * 10000L + Long.parseLong(shortDateTime.group(1)) * 100L
          + Long.parseLong(shortDateTime.group(2))) * SORT_KEY_SCALE
          + secondsOf(shortDateTime, 3, 4, 5);
    }

    if (date.startsWith("今天")) {
      return SORT_KEY_TODAY * SORT_KEY_SCALE;
    }
    if (date.startsWith("昨天")) {
      return SORT_KEY_YESTERDAY * SORT_KEY_SCALE;
    }

    java.util.regex.Matcher time = TIME_ONLY.matcher(date);
    if (time.find()) {
      // 只有时刻，说明就是当天；精确到秒，否则同一分钟内的帖子会并列
      return SORT_KEY_TODAY * SORT_KEY_SCALE + secondsOf(time, 1, 2, 3);
    }

    java.util.regex.Matcher shortDate = DATE_SHORT.matcher(date);
    if (shortDate.find()) {
      int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
      return (year * 10000L + Long.parseLong(shortDate.group(1)) * 100L
          + Long.parseLong(shortDate.group(2))) * SORT_KEY_SCALE;
    }

    return fallback;
  }

  /** 「按发表时间排序」用的排序键（老方法，不查缓存） */
  public long getPublishSortKey() {
    long key = parseSortKey(publishDate, 0L);
    android.util.Log.d("FEEDDBG", "getPublishSortKey id=" + topicID + " raw='" + publishDate
        + "' -> key=" + key + " day=" + (key / SORT_KEY_SCALE));
    return key;
  }

  /**
   * 「按发表时间排序」用的排序键（会查本地时间缓存）。
   * 仅当发帖时间不是当天（服务端只给了 yyyy-MM-dd 日期、没有时刻）时，
   * 用 topicID 去缓存查精确发帖日期+时间，查到就按完整时间算 key；
   * 查不到或本来就是当天的时刻，一律走老方法不变。
   * 每个 Topic 只解析/查库一次，结果缓存起来（排序比较会反复调用）。
   */
  public long getPublishSortKeyCached() {
    if (publishKeyCacheResolved) {
      return publishKeyFromCache != 0 ? publishKeyFromCache : getPublishSortKey();
    }
    publishKeyCacheResolved = true;
    publishKeyFromCache = 0;

    com.zfdang.zsmth_android.helpers.TopicTimeCache.DateTime dt =
        com.zfdang.zsmth_android.helpers.TopicTimeCache.parseDateTime(publishDate);
    // dt.time 为空 = 服务端只给了日期 = 非当天，才尝试查缓存
    if (!dt.date.isEmpty() && dt.time.isEmpty() && topicID != null && !topicID.isEmpty()) {
      com.zfdang.zsmth_android.helpers.TopicTimeCache.TopicTime cached =
          com.zfdang.zsmth_android.helpers.TopicTimeCache.getInstance().get(topicID);
      if (cached != null && cached.publishDate != null && !cached.publishDate.isEmpty()
          && cached.publishTime != null && !cached.publishTime.isEmpty()) {
        // 查到精确发帖日期+时间：拼成 "yyyy-MM-dd HH:mm:ss" 走完整解析
        publishKeyFromCache = parseSortKey(cached.publishDate + " " + cached.publishTime, 0L);
        android.util.Log.d("FEEDDBG", "getPublishSortKeyCached id=" + topicID + " CACHE HIT raw='" + publishDate
            + "' cached='" + cached.publishDate + " " + cached.publishTime + "' -> key=" + publishKeyFromCache
            + " day=" + (publishKeyFromCache / SORT_KEY_SCALE));
      } else {
        android.util.Log.d("FEEDDBG", "getPublishSortKeyCached id=" + topicID + " CACHE MISS raw='" + publishDate + "'");
      }
    }
    long result = publishKeyFromCache != 0 ? publishKeyFromCache : getPublishSortKey();
    android.util.Log.d("FEEDDBG", "sortKey id=" + topicID + " title='" + (title==null?"":title.substring(0,Math.min(12,title.length())))
        + "' raw='" + publishDate + "' FINAL=" + result + " day=" + (result / SORT_KEY_SCALE));
    return result;
  }

  /**
   * 该帖的时间是否属于「今天」——用于全局排序时判断是否可以提前停止加载。
   * byReply=true 看回复时间，false 看发帖时间。
   * 纯时刻(如 00:27)与「今天」开头的字符串都会被 parseSortKey 映射到 SORT_KEY_TODAY。
   */
  /**
   * 帖子的对应时间是否落在最近 days 天内（今天=第0天，昨天=第1天）。
   * 用于智能预读：连续遇到超出范围的时间即可停止加载。
   */
  public boolean isWithinLastDays(int days, boolean byReply) {
    long key = byReply ? getReplySortKey() : getPublishSortKey();
    long day = key / SORT_KEY_SCALE;
    // 纯时刻或「今天」映射到 SORT_KEY_TODAY
    if (day >= SORT_KEY_TODAY) return true;
    // 「昨天」占位
    if (day == SORT_KEY_YESTERDAY) return days >= 2;
    // day 形如 yyyyMMdd，转成日历算与今天相差的天数
    int year = (int) (day / 10000);
    int month = (int) ((day % 10000) / 100);
    int dom = (int) (day % 100);
    if (year < 2000 || month < 1 || month > 12 || dom < 1 || dom > 31) {
      return true; // 解析异常，保守当作在范围内
    }
    java.util.Calendar post = java.util.Calendar.getInstance();
    post.clear();
    post.set(year, month - 1, dom);
    java.util.Calendar today = java.util.Calendar.getInstance();
    today.clear();
    today.set(today.get(java.util.Calendar.YEAR), today.get(java.util.Calendar.MONTH),
        today.get(java.util.Calendar.DAY_OF_MONTH));
    long diffDays = (today.getTimeInMillis() - post.getTimeInMillis()) / (24L * 60 * 60 * 1000);
    return diffDays >= 0 && diffDays < days;
  }

  /**
   * 「按最后回复排序」用的排序键，拿不到回复时间时退回发布时间。
   *
   * 水木列表里回复时间常只给纯时刻(如 21:38:58)，省略日期。此时若直接当成"今天"，
   * 17 号的 01:02 会因为时刻数值大于 18 号的 00:01 而排到前面（只比了时分秒）。
   * 纯时刻回复与发帖在同一行，省略日期意味着和发帖同一天，所以用发帖日期拼上回复时刻，
   * 让排序键带上正确的年月日。
   */
  public long getReplySortKey() {
    String reply = replyDate == null ? "" : replyDate.trim();
    java.util.regex.Matcher timeOnly = TIME_ONLY.matcher(reply);
    if (timeOnly.matches()) {
      String publishDay = extractFullDate(publishDate);
      if (publishDay != null) {
        // 拼成 "yyyy-MM-dd HH:mm:ss" 再走完整解析
        return parseSortKey(publishDay + " " + reply, getPublishSortKey());
      }
    }
    return parseSortKey(replyDate, getPublishSortKey());
  }

  /** 从字符串里提取 yyyy-MM-dd；没有完整年月日则返回 null */
  private static String extractFullDate(String raw) {
    if (raw == null) {
      return null;
    }
    java.util.regex.Matcher m = DATE_FULL.matcher(raw);
    if (m.find()) {
      return m.group(1) + "-" + m.group(2) + "-" + m.group(3);
    }
    return null;
  }

  public String getReplier() {
    return replier;
  }

  public void setReplier(String replier) {
    this.replier = replier;
  }

  public String getReplyDate() {
    return replyDate;
  }

  public void setReplyDate(String replyDate) {
    this.replyDate = replyDate;
  }

  public boolean hasAttach() {
    return hasAttach;
  }

  public void setHasAttach(boolean hasAttach) {
    this.hasAttach = hasAttach;
  }

  public String getLikes() {
    return likes;
  }

  public void setLikes(String likes) {
    this.likes = likes;
  }

  public String getReplyCounts() {
    return replyCounts;
  }

  public void setReplyCounts(String replyCounts) {
    this.replyCounts = replyCounts;
  }

  public String getScore() {
    return score;
  }

  public void setScore(String score) {
    this.score = score;
  }

  public String getStatusSummary() {
    String result = String.format("回复: %-4s", replyCounts);
    if (likes != null && likes.length() > 0) {
      result += String.format("    Likes: %-4s    评分: %-4s", likes, score);
    }
    return result;
  }

  @Override public String toString() {
    if (isCategory) {
      return "Category " + this.category;
    } else {
      if (isSticky) {
        return String.format("置顶: (%s) %s by %s, %s @ %s", this.topicID, this.title, this.author, this.publishDate, this.boardEngName);
      } else {
        return String.format("(%s) %s by %s, %s @ %s", this.topicID, this.title, this.author, this.publishDate, this.boardEngName);
      }
    }
  }

  @Override public int describeContents() {
    return 0;
  }

  @Override public void writeToParcel(Parcel dest, int flags) {
    dest.writeByte(isCategory ? (byte) 1 : (byte) 0);
    dest.writeString(this.category);
    dest.writeString(this.boardEngName);
    dest.writeString(this.boardChsName);
    dest.writeString(this.topicID);
    dest.writeString(this.title);
    dest.writeString(this.author);
    dest.writeString(this.publishDate);
    dest.writeString(this.replier);
    dest.writeString(this.replyDate);
    dest.writeByte(isSticky ? (byte) 1 : (byte) 0);
    dest.writeString(this.score);
    dest.writeString(this.likes);
    dest.writeString(this.replyCounts);
    dest.writeByte(hasAttach ? (byte) 1 : (byte) 0);
    dest.writeInt(this.totalPageNo);
    dest.writeInt(this.totalPostNo);
  }

  protected Topic(Parcel in) {
    this.isCategory = in.readByte() != 0;
    this.category = in.readString();
    this.boardEngName = in.readString();
    this.boardChsName = in.readString();
    this.topicID = in.readString();
    this.title = in.readString();
    this.author = in.readString();
    this.publishDate = in.readString();
    this.replier = in.readString();
    this.replyDate = in.readString();
    this.isSticky = in.readByte() != 0;
    this.score = in.readString();
    this.likes = in.readString();
    this.replyCounts = in.readString();
    this.hasAttach = in.readByte() != 0;
    this.totalPageNo = in.readInt();
    this.totalPostNo = in.readInt();
  }

  public static final Creator<Topic> CREATOR = new Creator<Topic>() {
    @Override public Topic createFromParcel(Parcel source) {
      return new Topic(source);
    }

    @Override public Topic[] newArray(int size) {
      return new Topic[size];
    }
  };
}