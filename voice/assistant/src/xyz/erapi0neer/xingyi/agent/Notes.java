package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** The 随手记 (files/agent/notes.json): the one-off things the user asks 星仪 to remember — 「明天买牛奶」. Unlike the
 * profile (Memory) these expire and are meant to be crossed off. Pure logic; the TV side only loads and saves the
 * JSONObject. Everything is capped: the pending list is read into every prompt under 「关于用户」. */
public final class Notes {
    public static final int MAX_ITEMS = 50, MAX_CHARS = 80, MAX_AGE_DAYS = 60, SUMMARY_ITEMS = 10, SUMMARY_CHARS = 40;
    private static final long DAY = 24L * 60 * 60 * 1000;
    /** Never stored: a note can be read back and spoken, so anything that unlocks something stays out (as in Memory). */
    private static final Pattern SECRET = Pattern.compile("密码|口令|验证码|支付密码|银行卡|卡号|身份证");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{6,}");

    private Notes() { }

    /** @return 「记下了：…」 or 「已经记着了：…」; throws IllegalArgumentException with the sentence to speak when the
     * note is empty, too long or a secret. Expired notes go and the list is capped on every add. */
    public static String add(JSONObject notes, String text, long now) throws JSONException {
        String t = clean(text);
        if (t.isEmpty() || Memory.norm(t).isEmpty()) throw new IllegalArgumentException("要记什么？");
        if (t.length() > MAX_CHARS) throw new IllegalArgumentException("太长了，挑重点说一句");
        if (secret(t)) throw new IllegalArgumentException("这类信息（密码、验证码、证件号）不记");
        JSONArray items = items(notes);
        expire(items, now);
        String n = Memory.norm(t);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            String old = it == null ? "" : it.optString("text"), e = Memory.norm(old);
            // a broken entry without text normalizes to "", which every note "contains"
            if (!e.isEmpty() && (e.equals(n) || e.contains(n) || n.contains(e))) return "已经记着了：" + old;
        }
        items.put(new JSONObject().put("id", nextId(notes)).put("text", t).put("at", now));
        while (items.length() > MAX_ITEMS) items.remove(0);
        return "记下了：" + t;
    }

    /** 「随手记里有 2 件：1 号，明天买牛奶（今天 10:05 记的）；4 号，给妈妈打电话（昨天 21:40 记的）。」 oldest first. */
    public static String list(JSONObject notes, long now, TimeZone tz) {
        JSONArray items = live(notes, now);
        if (items.length() == 0) return "随手记里没有东西。";
        StringBuilder b = new StringBuilder("随手记里有 " + items.length() + " 件：");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            b.append(i == 0 ? "" : "；").append(it.optInt("id")).append(" 号，").append(it.optString("text"))
                    .append("（").append(when(it.optLong("at"), now, tz)).append(" 记的）");
        }
        return b.append("。").toString();
    }

    /** Crosses off by id, by a word of the text, or everything; @return the texts that went, oldest first. */
    public static List<String> done(JSONObject notes, String which) {
        List<String> removed = new ArrayList<>();
        String w = clean(which);
        if (w.isEmpty()) return removed;
        JSONArray items = notes == null ? null : notes.optJSONArray("items");
        if (items == null) return removed;
        boolean all = w.equals("all") || w.equals("全部") || w.equals("所有");
        Integer id = number(w);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String text = it.optString("text");
            boolean hit = all || (id != null ? it.optInt("id") == id : text.contains(w) || w.contains(text));
            if (hit) removed.add(text); else kept.put(it);
        }
        try { notes.put("items", kept); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return removed;
    }

    /** The newest pending notes as lines for 「关于用户」, oldest first, each at most SUMMARY_CHARS chars, date kept. */
    public static List<String> summary(JSONObject notes, long now, TimeZone tz) {
        JSONArray items = live(notes, now);
        List<String> lines = new ArrayList<>();
        for (int i = Math.max(0, items.length() - SUMMARY_ITEMS); i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String suffix = "（" + date(it.optLong("at"), tz) + "记）";
            lines.add(cut(it.optString("text"), Math.max(1, SUMMARY_CHARS - suffix.length())) + suffix);
        }
        return lines;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Trimmed, inner whitespace collapsed: the recognizer pads, the user does not mean it. */
    static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").trim();
    }

    /** 「今天 10:05」/「昨天 21:40」/「前天 09:00」/「11月3日 08:12」 in the TV's own time zone. */
    static String when(long at, long now, TimeZone tz) {
        int days = daysAgo(at, now, tz);
        String day = days == 0 ? "今天" : days == 1 ? "昨天" : days == 2 ? "前天" : date(at, tz);
        return day + " " + time(at, tz);
    }

    /** 「11月3日」 in the TV's own time zone. */
    static String date(long at, TimeZone tz) {
        Calendar c = calendar(at, tz);
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
    }

    /** 「9:05」/「22:58」: the hour without a leading zero, the minutes with one, as the user would say it. */
    static String time(long at, TimeZone tz) {
        Calendar c = calendar(at, tz);
        int m = c.get(Calendar.MINUTE);
        return c.get(Calendar.HOUR_OF_DAY) + ":" + (m < 10 ? "0" + m : String.valueOf(m));
    }

    private static Calendar calendar(long at, TimeZone tz) {
        Calendar c = new GregorianCalendar(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(at);
        return c;
    }

    /** Whole calendar days between two instants in one zone, so 23:50 → 00:10 is one day, not twenty minutes. */
    private static int daysAgo(long at, long now, TimeZone tz) {
        TimeZone z = tz == null ? TimeZone.getDefault() : tz;
        long a = Math.floorDiv(at + z.getOffset(at), DAY), b = Math.floorDiv(now + z.getOffset(now), DAY);
        return (int) (b - a);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean secret(String t) {
        return SECRET.matcher(t).find() || DIGITS.matcher(t).find();
    }

    private static JSONArray items(JSONObject notes) throws JSONException {
        JSONArray a = notes.optJSONArray("items");
        if (a == null) { a = new JSONArray(); notes.put("items", a); }
        return a;
    }

    private static int nextId(JSONObject notes) throws JSONException {
        int id = notes.optInt("next_id", 1);
        if (id < 1) id = 1;
        notes.put("next_id", id + 1);
        return id;
    }

    /** The pending notes that have not expired yet, oldest first; a broken file simply has none. */
    static JSONArray live(JSONObject notes, long now) {
        JSONArray items = notes == null ? null : notes.optJSONArray("items");
        if (items == null) return new JSONArray();
        JSONArray kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && now - it.optLong("at") <= MAX_AGE_DAYS * DAY) kept.put(it);
        }
        return kept;
    }

    private static void expire(JSONArray items, long now) {
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || now - it.optLong("at") > MAX_AGE_DAYS * DAY) items.remove(i);
        }
    }

    private static Integer number(String w) {
        for (int i = 0; i < w.length(); i++) if (!Character.isDigit(w.charAt(i))) return null;
        try { return Integer.valueOf(w); } catch (NumberFormatException tooBig) { return null; }
    }
}
