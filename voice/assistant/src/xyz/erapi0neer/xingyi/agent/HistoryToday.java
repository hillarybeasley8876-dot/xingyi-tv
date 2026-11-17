package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「历史上的今天」 (2026-11-07): Baidu Baike's day-by-day calendar of events, births and deaths (one file a month), the
 * entries it marks as notable first. A classic voice-assistant answer; a web search took 7-9 s for it.
 */
public final class HistoryToday {
    private HistoryToday() { }

    public static String url(int month) { return "https://baike.baidu.com/cms/home/eventsOnHistory/" + String.format("%02d", month) + ".json"; }

    public static final class Entry {
        public final String year, title, desc;
        final boolean notable;

        Entry(String year, String title, String desc, boolean notable) { this.year = year; this.title = title; this.desc = desc; this.notable = notable; }
    }

    /** The day's entries, in the file's (year) order. */
    public static List<Entry> entries(String json, int month, int day) throws JSONException {
        // 05.json is {"05": {"0531": [...], …}}, 11.json {"11": {"1107": […]}}
        String m = String.format("%02d", month), key = m + String.format("%02d", day);
        JSONObject months = new JSONObject(json).optJSONObject(m);
        JSONArray list = months == null ? null : months.optJSONArray(key);
        List<Entry> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            out.add(new Entry(News.str(e, "year"), News.text(News.str(e, "title")), cut(News.text(News.str(e, "desc")), 120), e.optBoolean("recommend")));
        }
        return out;
    }

    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** The notable entries first (in year order), then the rest, n of them. */
    public static List<Entry> pick(List<Entry> all, int n) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (e.notable && out.size() < n) out.add(e);
        for (Entry e : all) if (!e.notable && out.size() < n) out.add(e);
        out.sort((a, b) -> {
            try { return Integer.compare(Integer.parseInt(a.year.replaceAll("[^0-9-]", "")), Integer.parseInt(b.year.replaceAll("[^0-9-]", ""))); }
            catch (NumberFormatException e) { return 0; }
        });
        return out;
    }

    /** 「历史上的今天（11 月 7 日）：1867 年，被称为居里夫人的玛丽亚·斯克沃多夫斯卡·居里出生；1901 年，…」. */
    public static String speak(List<Entry> picked, int month, int day) {
        if (picked.isEmpty()) return "历史上的今天这会儿没有查到";
        StringBuilder s = new StringBuilder("历史上的今天（" + month + " 月 " + day + " 日）：");
        for (int i = 0; i < picked.size(); i++) s.append(i == 0 ? "" : "；").append(picked.get(i).year).append(" 年，").append(picked.get(i).title);
        return s.toString();
    }

    public static JSONObject readOut(List<Entry> picked) throws JSONException {
        JSONArray items = new JSONArray();
        for (int i = 0; i < picked.size(); i++)
            items.put(new JSONObject().put("n", i + 1).put("title", picked.get(i).year + " 年，" + picked.get(i).title).put("summary", picked.get(i).desc));
        return new JSONObject().put("what", "历史上的今天").put("items", items)
                .put("note", "刚读给用户的条目：用户问“第二个是谁”“第一个讲讲”先用它的 summary 说，要更多再自己讲或用 web_search");
    }
}
