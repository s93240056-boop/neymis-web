package com.neymis.web;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Закладки, история и настройки — всё локально, в SharedPreferences. */
final class BrowserStore {

    static final class Entry {
        final String title;
        final String url;
        final long time;

        Entry(String title, String url, long time) {
            this.title = title;
            this.url = url;
            this.time = time;
        }
    }

    private final SharedPreferences prefs;
    final List<Entry> bookmarks = new ArrayList<>();
    final List<Entry> history = new ArrayList<>();

    BrowserStore(Context context) {
        prefs = context.getSharedPreferences("web", Context.MODE_PRIVATE);
        load("bookmarks", bookmarks);
        load("history", history);
    }

    private void load(String key, List<Entry> target) {
        try {
            String raw = prefs.getString(key, "");
            if (raw.isEmpty()) return;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                target.add(new Entry(item.optString("title", ""),
                        item.optString("url", ""),
                        item.optLong("time", 0)));
            }
        } catch (Exception ignored) {
        }
    }

    private void save(String key, List<Entry> source) {
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : source) {
                JSONObject item = new JSONObject();
                item.put("title", entry.title);
                item.put("url", entry.url);
                item.put("time", entry.time);
                array.put(item);
            }
            prefs.edit().putString(key, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    boolean hasBookmark(String url) {
        for (Entry entry : bookmarks) if (entry.url.equals(url)) return true;
        return false;
    }

    void addBookmark(String title, String url) {
        if (url == null || url.isEmpty() || hasBookmark(url)) return;
        bookmarks.add(0, new Entry(title, url, System.currentTimeMillis()));
        save("bookmarks", bookmarks);
    }

    void removeBookmark(String url) {
        for (int i = bookmarks.size() - 1; i >= 0; i--) {
            if (bookmarks.get(i).url.equals(url)) bookmarks.remove(i);
        }
        save("bookmarks", bookmarks);
    }

    void addHistory(String title, String url) {
        if (url == null || url.isEmpty() || url.startsWith("about:")) return;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).url.equals(url)) history.remove(i);
        }
        history.add(0, new Entry(title, url, System.currentTimeMillis()));
        while (history.size() > 200) history.remove(history.size() - 1);
        save("history", history);
    }

    void removeHistory(String url) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).url.equals(url)) history.remove(i);
        }
        save("history", history);
    }

    void clearHistory() {
        history.clear();
        save("history", history);
    }

    boolean desktop() {
        return prefs.getBoolean("desktop", false);
    }

    void setDesktop(boolean value) {
        prefs.edit().putBoolean("desktop", value).apply();
    }

    boolean eruda() {
        return prefs.getBoolean("eruda", false);
    }

    void setEruda(boolean value) {
        prefs.edit().putBoolean("eruda", value).apply();
    }

    String home() {
        return prefs.getString("home", "https://www.google.com");
    }

    void setHome(String url) {
        prefs.edit().putString("home", url).apply();
    }
}
