package xyz.erapi0neer.xingyi.agent;

import java.util.Calendar;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The data answers the floating panel draws (HudCards): a few quotes, a list, a day's weather (Weather.card). The tools
 * hand one of these to the loop on their result and the panel draws it under the steps, so the numbers are on screen
 * and the spoken answer can stay short (cards-panel, 2026-11-07). Nothing here knows the panel: {@link #valid} is
 * HudCards' own rules written down, so a card that could not be drawn is never put on a result at all.
 */
public final class Cards {
    private Cards() { }

    /** How many quotes the panel draws (its rows are 52 units tall: four fill the card, more would not be read). */
    private static final int ROWS = 4;
    /** How many list items the panel draws. */
    private static final int ITEMS = 5;
    /** How much of a summary fits under an item as its meta line. */
    private static final int SUMMARY = 28;

    /**
     * {type: stocks, rows: [{name, price, unit, pct, when}]} for at most four quotes: pct as Quote gives it (signed),
     * when as {@link Quotes#when} says it for that quote (「9 点 49 分」「今天收盘」「11 月 6 日收盘」). A quote the panel
     * could not draw (no name, no price, a percentage that is not a number) is left out rather than sinking the whole
     * card, which is what HudCards does with one bad row. Null when there is nothing to draw.
     */
    public static JSONObject stocks(List<Quotes.Quote> quotes, Calendar now) {
        if (quotes == null || quotes.isEmpty()) return null;
        JSONArray rows = new JSONArray();
        for (Quotes.Quote q : quotes) {
            if (rows.length() >= ROWS) break;
            if (q == null || q.name == null || q.name.isEmpty() || q.price == null || q.price.isEmpty()) continue;
            if (Double.isNaN(pct(q.pct))) continue;
            try {
                rows.put(new JSONObject().put("name", q.name).put("price", q.price).put("unit", q.unit())
                        .put("pct", q.pct).put("when", Quotes.when(q, now)));
            } catch (JSONException impossible) { return null; }
        }
        if (rows.length() == 0) return null;
        try { return new JSONObject().put("type", "stocks").put("rows", rows); }
        catch (JSONException impossible) { return null; }
    }

    /**
     * {type: list, title, items: [{title, meta}]} from a read-out list ({what, items: [{n, title, …}]}) or movie_list's
     * rows: at most five items, the meta from whatever the row carries. Null when the title is empty or nothing has a
     * title (the panel draws neither).
     */
    public static JSONObject list(String title, JSONArray items) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || items == null) return null;
        JSONArray out = new JSONArray();
        for (int i = 0; i < items.length() && out.length() < ITEMS; i++) {
            JSONObject e = items.optJSONObject(i);
            String name = str(e, "title");
            if (name.isEmpty()) continue;
            try {
                JSONObject row = new JSONObject().put("title", name);
                String meta = meta(e);
                if (!meta.isEmpty()) row.put("meta", meta);
                out.put(row);
            } catch (JSONException impossible) { return null; }
        }
        if (out.length() == 0) return null;
        try { return new JSONObject().put("type", "list").put("title", t).put("items", out); }
        catch (JSONException impossible) { return null; }
    }

    /**
     * The line under an item's title, from the fields the row has: 「影视飓风 · 昨天」 (a B站 video), 「林俊杰」 (a song),
     * 「第 199 话（已更新）」 (a bangumi episode), 「豆瓣 8.5 · 肖战、倪大红」 (a movie_list row), the start of a summary
     * (news, 历史上的今天), else nothing.
     */
    static String meta(JSONObject e) {
        if (e == null) return "";
        String up = str(e, "up"), date = str(e, "date");
        if (!up.isEmpty() || !date.isEmpty()) return join(up, date);
        String artist = str(e, "artist");
        if (!artist.isEmpty()) return artist;
        if (e.has("episode") && e.has("out")) {
            String ep = Bangumi.index(str(e, "episode"));
            return ep.isEmpty() ? "" : ep + (e.optBoolean("out") ? "（已更新）" : "");
        }
        String rating = rating(e);
        if (!rating.isEmpty() || !str(e, "cast").isEmpty()) return join(rating, str(e, "cast"));
        return cut(str(e, "summary"));
    }

    /** 「豆瓣 8.5」 from a movie_list row's rating; "" when it has none (a film nobody has rated yet). */
    private static String rating(JSONObject e) {
        Object v = e.opt("rating");
        if (!(v instanceof Number)) return "";
        double r = ((Number) v).doubleValue();
        return r > 0 ? "豆瓣 " + String.format(java.util.Locale.ROOT, "%.1f", r) : "";
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        return b.isEmpty() ? a : a + " · " + b;
    }

    /** A summary as a meta line: its first 28 characters, with 「…」 when the rest was cut. */
    private static String cut(String s) {
        return s.length() <= SUMMARY ? s : s.substring(0, SUMMARY) + "…";
    }

    /** Whether the panel can draw it (HudCards' rules): a known type with its required fields. */
    public static boolean valid(JSONObject card) {
        if (card == null) return false;
        String type = str(card, "type");
        if ("weather".equals(type)) return weather(card);
        if ("stocks".equals(type)) return stocksOk(card);
        if ("list".equals(type)) return listOk(card);
        return false;
    }

    private static boolean weather(JSONObject o) {
        return !str(o, "place").isEmpty() && !str(o, "day").isEmpty() && !str(o, "sky").isEmpty()
                && !Double.isNaN(num(o, "lo")) && !Double.isNaN(num(o, "hi"));
    }

    private static boolean stocksOk(JSONObject o) {
        JSONArray rows = o.optJSONArray("rows");
        if (rows == null || rows.length() == 0) return false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null || str(r, "name").isEmpty() || str(r, "price").isEmpty() || Double.isNaN(pct(str(r, "pct")))) return false;
        }
        return true;
    }

    private static boolean listOk(JSONObject o) {
        if (str(o, "title").isEmpty()) return false;
        JSONArray items = o.optJSONArray("items");
        if (items == null || items.length() == 0) return false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject e = items.optJSONObject(i);
            if (e == null || str(e, "title").isEmpty()) return false;
        }
        return true;
    }

    /** A string field, "" for a missing or JSON-null one: Android's org.json gives "null" for a JSON null. */
    private static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        String s = o.optString(key, "");
        return "null".equals(s) ? "" : s;
    }

    /** A number field; NaN when it is missing or not a number. */
    private static double num(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
    }

    /** 「-10.46」 → -10.46, as HudCards reads a row's percentage; a trailing % and a leading + are allowed. */
    private static double pct(String s) {
        String t = s == null ? "" : s.trim();
        if (t.endsWith("%")) t = t.substring(0, t.length() - 1).trim();
        if (t.startsWith("+")) t = t.substring(1);
        try { return Double.parseDouble(t); }
        catch (NumberFormatException e) { return Double.NaN; }
    }
}
