package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.*;

/** The book of things 星仪 does later (files/agent/schedules.json): 「明天早上7点放新闻联播」「每天晚上11点关电视」
 * 「8点提醒我吃药」. Pure logic — the TV side loads and saves the JSONObject and fires whatever is due; everything here
 * works on the book in place. All calendar maths runs in the TimeZone handed in (the TV's own, Asia/Shanghai), never
 * in the machine's default, so the checks can pin it. */
public final class Schedules {
    public static final int MAX_ITEMS = 20, MAX_WHAT = 60, MAX_DAYS_AHEAD = 400, MAX_MINUTES = 1440;
    /** 「8点」 said at 7:59:30 still means today, so a once item needs a whole minute of headroom. */
    private static final long MIN_AHEAD = 60_000L;
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Set<String> REPEATS = new HashSet<>(Arrays.asList("once", "daily", "weekdays", "weekends"));
    private static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    private Schedules() { }

    /**
     * Books one item. @return the confirmation to speak, or 「已经预约过了：…」 when the very same thing is already in.
     * @throws IllegalArgumentException whose message is the Chinese reason the model gets back.
     */
    public static String add(JSONObject book, JSONObject args, long now, TimeZone tz) throws JSONException {
        String kind = args.optString("kind").trim();
        if (!kind.equals("task") && !kind.equals("remind")) throw bad("没说清是到点去做，还是到点提醒");
        String what = clean(args.optString("what"));
        if (what.isEmpty()) throw bad("没说清到点要做什么");
        if (what.length() > MAX_WHAT) throw bad("这件事太长了，说短一点");
        boolean hasTime = given(args, "time"), hasIn = given(args, "in_minutes");
        if (hasTime == hasIn) throw bad("要说清几点，或者多少分钟以后");
        String date = args.optString("date").trim();
        if (!date.isEmpty() && !hasTime) throw bad("说了哪天，也要说几点");
        String repeat = args.optString("repeat").trim();
        if (repeat.isEmpty()) repeat = "once";
        if (!REPEATS.contains(repeat)) throw bad("重复方式只能是 once、daily、weekdays 或 weekends");

        long at;
        int hour, minute;
        if (hasIn) {
            if (!repeat.equals("once")) throw bad("多少分钟以后只能约一次");
            int mins = minutes(args);
            if (mins < 1 || mins > MAX_MINUTES) throw bad("分钟数要在 1 到 " + MAX_MINUTES + " 之间");
            at = now + mins * 60_000L;
            Calendar c = cal(at, tz);
            hour = c.get(Calendar.HOUR_OF_DAY);
            minute = c.get(Calendar.MINUTE);
        } else {
            if (!date.isEmpty() && !repeat.equals("once")) throw bad("重复的预约不用指定哪天");
            int[] hm = parseTime(args.optString("time").trim());
            hour = hm[0];
            minute = hm[1];
            at = date.isEmpty() ? firstAt(hour, minute, repeat, now, tz) : atDate(date, hour, minute, now, tz);
        }

        JSONArray items = items(book);
        for (int i = 0; i < items.length(); i++) {
            JSONObject old = items.optJSONObject(i);
            if (same(old, kind, what, hour, minute, repeat, at, tz)) return "已经预约过了：" + describe(old, now, tz);
        }
        if (items.length() >= MAX_ITEMS) throw bad("预约已经有 " + MAX_ITEMS + " 个了，先取消一些");
        int id = Math.max(1, book.optInt("next_id", 1));
        JSONObject item = new JSONObject().put("id", id).put("kind", kind).put("what", what).put("repeat", repeat)
                .put("hour", hour).put("minute", minute).put("at", at).put("created", now);
        items.put(item);
        book.put("next_id", id + 1);
        return "好的，" + (hasIn ? inWords(item, now, tz) : describe(item, now, tz)) + "。";
    }

    /** 「明天 7:00 播放新闻联播」「每天 23:00 关电视」「今天 20:00 提醒你：该吃药了」: also what list and cancel say. */
    public static String describe(JSONObject item, long now, TimeZone tz) {
        return when(item, now, tz) + " " + body(item);
    }

    /** The time half of a description, without the thing to do: 「明天 7:00」「每天 23:00」「11月8日周六」. */
    public static String when(JSONObject item, long now, TimeZone tz) {
        String hm = hhmm(item, tz), repeat = item.optString("repeat", "once");
        if (repeat.equals("daily")) return "每天 " + hm;
        if (repeat.equals("weekdays")) return "工作日 " + hm;
        if (repeat.equals("weekends")) return "周末 " + hm;
        return day(item.optLong("at"), now, tz) + " " + hm;
    }

    private static String body(JSONObject item) {
        String what = item.optString("what");
        return "remind".equals(item.optString("kind")) ? "提醒你：" + what : what;
    }

    /** 「30 分钟后（14:35）暂停」: only the confirmation reads this way — a minute count means nothing tomorrow. */
    private static String inWords(JSONObject item, long now, TimeZone tz) {
        return ((item.optLong("at") - now) / 60_000L) + " 分钟后（" + hhmm(item, tz) + "）" + body(item);
    }

    /** What the clock must run now, oldest first: the items whose time has come. */
    public static List<JSONObject> due(JSONObject book, long now) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray items = book.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optLong("at", Long.MAX_VALUE) <= now) out.add(item);
        }
        Collections.sort(out, BY_AT);
        return out;
    }

    /** One item has run: a once item leaves the book, a repeating one moves to its next occurrence after now. */
    public static void fired(JSONObject book, int id, long now, TimeZone tz) throws JSONException {
        JSONArray items = book.optJSONArray("items");
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || item.optInt("id", -1) != id) continue;
            if (item.optString("repeat", "once").equals("once")) items.remove(i);
            else item.put("at", nextAfter(item.optInt("hour"), item.optInt("minute"), item.optString("repeat"), now, tz));
            return;
        }
    }

    /** The TV was off (or the process dead) when these were due: drop them, or move a repeating one past now.
     * @return the skipped items as they were, so the caller can say nothing about them or log them. */
    public static List<JSONObject> skipMissed(JSONObject book, long now, long graceMs, TimeZone tz) throws JSONException {
        List<JSONObject> skipped = new ArrayList<>();
        JSONArray items = book.optJSONArray("items");
        if (items == null) return skipped;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (item.optLong("at", Long.MAX_VALUE) >= now - graceMs) { kept.put(item); continue; }
            skipped.add(new JSONObject(item.toString()));
            if (!item.optString("repeat", "once").equals("once")) kept.put(advance(item, now, tz));
        }
        book.put("items", kept);
        return skipped;
    }

    /** 「现在有 2 个预约：1 号，明天 7:00 播放新闻联播；3 号，每天 23:00 关电视。」, soonest first. */
    public static String list(JSONObject book, long now, TimeZone tz) {
        List<JSONObject> all = all(book);
        if (all.isEmpty()) return "现在没有预约。";
        StringBuilder s = new StringBuilder("现在有 " + all.size() + " 个预约：");
        for (int i = 0; i < all.size(); i++) {
            JSONObject item = all.get(i);
            s.append(i == 0 ? "" : "；").append(item.optInt("id")).append(" 号，").append(describe(item, now, tz));
        }
        return s.append("。").toString();
    }

    /** all / 全部 / 所有, a number (the id list and cancel use), or any word of its description (what or when).
     * @return the descriptions that went; empty when nothing matched. */
    public static List<String> cancel(JSONObject book, String which, long now, TimeZone tz) throws JSONException {
        List<String> gone = new ArrayList<>();
        String w = which == null ? "" : which.trim();
        JSONArray items = book.optJSONArray("items");
        if (w.isEmpty() || items == null) return gone;
        boolean everything = w.equals("all") || w.equals("全部") || w.equals("所有");
        Integer id = number(w);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            // a word of the description, so the time works too: 「把11月30日那个取消」「明天的」 (TV 2026-11-05: the
            // date alone found nothing and the model had to cancel by id in a second round)
            boolean hit = everything || (id != null ? item.optInt("id", -1) == id : describe(item, now, tz).contains(w));
            if (hit) gone.add(describe(item, now, tz)); else kept.put(item);
        }
        book.put("items", kept);
        return gone;
    }

    // ---- when things happen ------------------------------------------------------------------------

    /** Where a new item starts: a once item takes today when a whole minute is still left, else tomorrow; a repeating
     * one takes the next matching day whose time is still ahead (「每天」 today counts, 「工作日」 on a Friday evening
     * does not). */
    private static long firstAt(int hour, int minute, String repeat, long now, TimeZone tz) {
        if (!repeat.equals("once")) return nextAfter(hour, minute, repeat, now, tz);
        long mid = midnight(now, tz), today = atTime(mid, hour, minute, tz);
        return today >= now + MIN_AHEAD ? today : atTime(plusDay(mid, 1, tz), hour, minute, tz);
    }

    /** The next firing strictly after now — what a repeating item moves to once it has run. A holiday can hold the
     * next working day off for more than a week (春节: nine days), so up to three weeks are looked at. */
    private static long nextAfter(int hour, int minute, String repeat, long now, TimeZone tz) {
        long mid = midnight(now, tz);
        for (int d = 0; d <= 21; d++) {
            long day = plusDay(mid, d, tz), at = atTime(day, hour, minute, tz);
            if (at > now && matches(repeat, cal(day, tz))) return at;
        }
        return atTime(plusDay(mid, 1, tz), hour, minute, tz);
    }

    private static JSONObject advance(JSONObject item, long now, TimeZone tz) throws JSONException {
        return item.put("at", nextAfter(item.optInt("hour"), item.optInt("minute"), item.optString("repeat"), now, tz));
    }

    /** 工作日 are China's working days where the State Council's schedule for the year is known (Almanac: no 国庆 or
     * 春节 days off, and the Saturdays or Sundays moved to work are in), Monday to Friday otherwise (2026-11-06
     * overnight pass: a 「工作日早上7点」 used to fire through the national holidays). 周末 stays Saturday and Sunday. */
    private static boolean matches(String repeat, Calendar c) {
        int dayOfWeek = c.get(Calendar.DAY_OF_WEEK);
        if (repeat.equals("weekdays")) {
            int y = c.get(Calendar.YEAR);
            if (Almanac.scheduled(y)) {
                String status = Almanac.status(y, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
                return status.equals("工作日") || status.startsWith("调休上班");
            }
            return dayOfWeek >= Calendar.MONDAY && dayOfWeek <= Calendar.FRIDAY;
        }
        if (repeat.equals("weekends")) return dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY;
        return true;
    }

    /** A date the user said out loud: today or later, at most MAX_DAYS_AHEAD days ahead (a festival's reminder months
     * away — 「除夕早上9点提醒我贴春联」 — was refused at 30 until 2026-11-06), and still in the future with the time. */
    private static long atDate(String date, int hour, int minute, long now, TimeZone tz) {
        Matcher m = DATE.matcher(date);
        if (!m.matches()) throw bad("哪天要写成 YYYY-MM-DD，比如 2026-11-08");
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.setLenient(false);
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)), hour, minute, 0);
        long at;
        try { at = c.getTimeInMillis(); } catch (IllegalArgumentException noDay) { throw bad("没有这一天：" + date); }
        int ahead = days(midnight(now, tz), midnight(at, tz), tz);
        if (ahead < 0) throw bad("这个日子已经过了");
        if (ahead > MAX_DAYS_AHEAD) throw bad("最多只能约 " + MAX_DAYS_AHEAD + " 天以内的事");
        if (at <= now) throw bad("这个时间已经过了");
        return at;
    }

    private static int[] parseTime(String time) {
        Matcher m = TIME.matcher(time);
        if (!m.matches()) throw bad("几点要写成 HH:mm，比如 07:00");
        int hour = Integer.parseInt(m.group(1)), minute = Integer.parseInt(m.group(2));
        if (hour > 23 || minute > 59) throw bad("没有这个时间：" + time);
        return new int[]{hour, minute};
    }

    // ---- calendar in the TV's own zone ---------------------------------------------------------------

    private static Calendar cal(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return c;
    }

    private static long midnight(long ms, TimeZone tz) {
        Calendar c = cal(ms, tz);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long atTime(long midnight, int hour, int minute, TimeZone tz) {
        Calendar c = cal(midnight, tz);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return c.getTimeInMillis();
    }

    /** Adding days rather than milliseconds: a day the zone shifts by an hour is still one day. */
    private static long plusDay(long midnight, int days, TimeZone tz) {
        Calendar c = cal(midnight, tz);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    private static int days(long fromMidnight, long toMidnight, TimeZone tz) {
        Calendar c = cal(fromMidnight, tz);
        int n = 0;
        // bounded a little past MAX_DAYS_AHEAD, so a date beyond it still counts as beyond it
        while (c.getTimeInMillis() < toMidnight && n < MAX_DAYS_AHEAD + 10) { c.add(Calendar.DAY_OF_MONTH, 1); n++; }
        while (c.getTimeInMillis() > toMidnight && n > -MAX_DAYS_AHEAD - 10) { c.add(Calendar.DAY_OF_MONTH, -1); n--; }
        return n;
    }

    private static String hhmm(JSONObject item, TimeZone tz) {
        Calendar c = cal(item.optLong("at"), tz);
        return c.get(Calendar.HOUR_OF_DAY) + ":" + (c.get(Calendar.MINUTE) < 10 ? "0" : "") + c.get(Calendar.MINUTE);
    }

    private static String day(long at, long now, TimeZone tz) {
        int diff = days(midnight(now, tz), midnight(at, tz), tz);
        if (diff == 0) return "今天";
        if (diff == 1) return "明天";
        if (diff == 2) return "后天";
        Calendar c = cal(at, tz);
        // a day in another year says which: a reminder for 除夕 is months away, and a wrong year must be heard
        String year = c.get(Calendar.YEAR) != cal(now, tz).get(Calendar.YEAR) ? c.get(Calendar.YEAR) + "年" : "";
        return year + (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日" + WEEK[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    // ---- the book ------------------------------------------------------------------------------------

    private static final Comparator<JSONObject> BY_AT =
            (a, b) -> Long.compare(a.optLong("at", Long.MAX_VALUE), b.optLong("at", Long.MAX_VALUE));

    /** A missing or broken book counts as empty; the array is created on the first booking. */
    private static JSONArray items(JSONObject book) throws JSONException {
        JSONArray items = book.optJSONArray("items");
        if (items == null) book.put("items", items = new JSONArray());
        return items;
    }

    /** Every item in the book, soonest first; a missing or broken book is simply empty. */
    public static List<JSONObject> all(JSONObject book) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray items = book.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) out.add(item);
        }
        Collections.sort(out, BY_AT);
        return out;
    }

    /** 「明天7点放新闻联播」 said twice must not book it twice, however the second one was worded; but 明天7点 and
     * 后天7点 are two bookings, so a once item must also fall on the same day. */
    private static boolean same(JSONObject item, String kind, String what, int hour, int minute, String repeat, long at,
                                TimeZone tz) {
        return item != null && item.optString("kind").equals(kind) && item.optString("what").equals(what)
                && item.optInt("hour", -1) == hour && item.optInt("minute", -1) == minute
                && item.optString("repeat", "once").equals(repeat)
                && (!repeat.equals("once") || midnight(item.optLong("at"), tz) == midnight(at, tz));
    }

    /** Present and not blank: the model fills in what it has and leaves the rest out. */
    private static boolean given(JSONObject args, String key) {
        if (!args.has(key)) return false;
        Object v = args.opt(key);
        return v != null && v != JSONObject.NULL && !String.valueOf(v).trim().isEmpty();
    }

    private static int minutes(JSONObject args) {
        Object v = args.opt("in_minutes");
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static Integer number(String which) {
        if (!which.matches("\\d+")) return null;
        try { return Integer.valueOf(which); } catch (NumberFormatException tooBig) { return null; }
    }

    static String clean(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " "); }

    private static IllegalArgumentException bad(String reason) { return new IllegalArgumentException(reason); }
}
