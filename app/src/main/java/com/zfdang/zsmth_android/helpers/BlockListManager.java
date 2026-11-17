package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zfdang.SMTHApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 屏蔽规则管理: 屏蔽用户 + 屏蔽标题关键词
 *
 * 存储在 SharedPreferences 的单个 JSON 字符串里, 便于整体导入导出:
 * {"version":1,"users":["foo"],"keywords":["关键字"]}
 *
 * 匹配规则:
 *   - 用户: 与作者名不区分大小写完全相等
 *   - 关键词: 标题不区分大小写包含该关键词
 */
public class BlockListManager {

    private static final String TAG = "BlockListManager";
    private static final String PREF_NAME = "ZSMTH_BlockList";
    private static final String KEY_DATA = "data";
    private static final int VERSION = 1;

    public static final int TYPE_USER = 0;
    public static final int TYPE_KEYWORD = 1;

    private static BlockListManager instance;

    private final Set<String> users = new LinkedHashSet<>();
    private final Set<String> keywords = new LinkedHashSet<>();
    private final SharedPreferences pref;

    public static synchronized BlockListManager getInstance() {
        if (instance == null) {
            instance = new BlockListManager();
        }
        return instance;
    }

    private BlockListManager() {
        pref = SMTHApplication.getAppContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        load();
    }

    /** 供列表页调用: 作者命中屏蔽用户 或 标题命中关键词 */
    public boolean isBlocked(String author, String title) {
        return isUserBlocked(author) || isTitleBlocked(title);
    }

    public boolean isUserBlocked(String author) {
        if (author == null || users.isEmpty()) return false;
        String key = normalize(author);
        return !key.isEmpty() && users.contains(key);
    }

    public boolean isTitleBlocked(String title) {
        if (title == null || keywords.isEmpty()) return false;
        String text = title.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (!keyword.isEmpty() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return users.isEmpty() && keywords.isEmpty();
    }

    public int getCount() {
        return users.size() + keywords.size();
    }

    /** 新增一条规则, 返回 true 表示确实是新的 */
    public boolean add(int type, String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return false;

        boolean added;
        if (type == TYPE_USER) {
            added = users.add(normalized);
        } else {
            added = keywords.add(normalized);
        }
        if (added) save();
        return added;
    }

    public void remove(int type, String value) {
        String normalized = normalize(value);
        boolean removed = (type == TYPE_USER) ? users.remove(normalized) : keywords.remove(normalized);
        if (removed) save();
    }

    /** 按「用户在前、关键词在后」的顺序列出, 供界面展示 */
    public List<Entry> getEntries() {
        List<Entry> entries = new ArrayList<>(users.size() + keywords.size());
        for (String user : users) {
            entries.add(new Entry(TYPE_USER, user));
        }
        for (String keyword : keywords) {
            entries.add(new Entry(TYPE_KEYWORD, keyword));
        }
        return entries;
    }

    public String exportJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("version", VERSION);
            root.put("users", new JSONArray(users));
            root.put("keywords", new JSONArray(keywords));
        } catch (Exception e) {
            Log.e(TAG, "exportJson failed", e);
        }
        return root.toString();
    }

    /**
     * 导入 JSON. 兼容两种格式:
     *   {"version":1,"users":[],"keywords":[]}
     *   ["单个列表"]  (无法区分类型时会全部当成关键词)
     *
     * @return int[2]: {新增用户数, 新增关键词数}
     */
    public int[] importJson(String json) {
        int addedUsers = 0;
        int addedKeywords = 0;
        if (json == null) return new int[] {0, 0};

        String trimmed = json.trim();
        try {
            if (trimmed.startsWith("[")) {
                JSONArray array = new JSONArray(trimmed);
                for (int i = 0; i < array.length(); i++) {
                    if (keywords.add(normalize(array.optString(i, "")))) addedKeywords++;
                }
            } else {
                JSONObject root = new JSONObject(trimmed);
                JSONArray userArray = root.optJSONArray("users");
                if (userArray != null) {
                    for (int i = 0; i < userArray.length(); i++) {
                        String v = normalize(userArray.optString(i, ""));
                        if (!v.isEmpty() && users.add(v)) addedUsers++;
                    }
                }
                JSONArray keywordArray = root.optJSONArray("keywords");
                if (keywordArray != null) {
                    for (int i = 0; i < keywordArray.length(); i++) {
                        String v = normalize(keywordArray.optString(i, ""));
                        if (!v.isEmpty() && keywords.add(v)) addedKeywords++;
                    }
                }
            }
            if (addedUsers > 0 || addedKeywords > 0) save();
        } catch (Exception e) {
            Log.e(TAG, "importJson failed", e);
            return new int[] {-1, -1};
        }
        return new int[] {addedUsers, addedKeywords};
    }

    private void load() {
        users.clear();
        keywords.clear();
        String data = pref.getString(KEY_DATA, null);
        if (data == null) return;
        try {
            JSONObject root = new JSONObject(data);
            JSONArray userArray = root.optJSONArray("users");
            if (userArray != null) {
                for (int i = 0; i < userArray.length(); i++) {
                    String v = normalize(userArray.optString(i, ""));
                    if (!v.isEmpty()) users.add(v);
                }
            }
            JSONArray keywordArray = root.optJSONArray("keywords");
            if (keywordArray != null) {
                for (int i = 0; i < keywordArray.length(); i++) {
                    String v = normalize(keywordArray.optString(i, ""));
                    if (!v.isEmpty()) keywords.add(v);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "load block list failed", e);
        }
    }

    private void save() {
        try {
            pref.edit().putString(KEY_DATA, exportJson()).apply();
        } catch (Exception e) {
            Log.e(TAG, "save block list failed", e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 界面展示用的一条规则 */
    public static class Entry {
        public final int type;
        public final String value;

        public Entry(int type, String value) {
            this.type = type;
            this.value = value;
        }

        public boolean isUser() {
            return type == TYPE_USER;
        }
    }
}
