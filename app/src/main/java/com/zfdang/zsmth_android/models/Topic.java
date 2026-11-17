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
  /** 只给了时刻说明是当天，用比任何真实日期都大的日子占位 */
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
      return (Long.parseLong(full.group(1)) * 10000L
          + Long.parseLong(full.group(2)) * 100L
          + Long.parseLong(full.group(3))) * SORT_KEY_SCALE;
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

  /** 「按发表时间排序」用的排序键 */
  public long getPublishSortKey() {
    return parseSortKey(publishDate, 0L);
  }

  /** 「按最后回复排序」用的排序键，拿不到回复时间时退回发布时间 */
  public long getReplySortKey() {
    return parseSortKey(replyDate, getPublishSortKey());
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