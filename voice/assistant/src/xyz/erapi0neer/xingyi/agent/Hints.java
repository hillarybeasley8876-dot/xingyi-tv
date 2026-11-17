package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** What 星仪 says when nobody asked: the quiet card it puts up by itself when the screen comes on — what is coming up,
 * what is still on the 随手记, and what the user usually does at this hour. No sound, three lines, then it fades.
 * Pure logic: the TV side decides when a card is allowed ({@link #allowed}) and hands the lines to the HUD. Nothing
 * here ever throws — a broken or missing file only makes a shorter card. */
public final class Hints {
    /** At most one card every three hours: it is a hint, not a companion that keeps talking. */
    public static final long MIN_GAP_MS = 3 * 3_600_000L;
    /** The card is a glance, not a page. */
    private static final int MAX_LINES = 3, MAX_LINE_CHARS = 40;
    private static final long AHEAD_MS = 12 * 3_600_000L, HABIT_MS = 30L * 86400_000L, DAY = 24L * 60 * 60 * 1000;
    /** Only the kinds a hint can offer a way to repeat: a volume or an input is not something to suggest out loud. */
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList("artist", "bili", "app"));

    private Hints() { }

    /** Up to 3 lines for the card, most useful first; empty = nothing worth showing (then no card at all). */
    public static List<String> card(JSONObject habits, JSONObject notes, JSONObject schedules, long now, TimeZone tz) {
        return card(habits, notes, schedules, now, tz, null);
    }

    /** weather: the day's weather in one line (Weather.hint), first on the card — the glance a TV's home screen gives
     * (2026-11-06 overnight pass); null when it could not be had. */
    public static List<String> card(JSONObject habits, JSONObject notes, JSONObject schedules, long now, TimeZone tz, String weather) {
        TimeZone zone = tz == null ? TimeZone.getDefault() : tz;
        List<String> lines = new ArrayList<>();
        add(lines, () -> weather == null || weather.trim().isEmpty() ? null : cut(weather.trim()));
        add(lines, () -> today(now, zone));
        add(lines, () -> coming(schedules, now, zone));
        add(lines, () -> note(notes, now));
        add(lines, () -> habit(habits, now, zone));
        return lines;
    }

    private interface Line { String get(); }

    /** One line, or nothing: a file that cannot be read only costs its own line, never the whole card. */
    private static void add(List<String> out, Line line) {
        if (out.size() >= MAX_LINES) return;
        String s;
        try { s = line.get(); } catch (RuntimeException broken) { return; }
        if (s != null && !s.trim().isEmpty()) out.add(s);
    }

    /** Whether a card may show now: not between 1:00 and 5:59 (TV local time), and MIN_GAP_MS after the last one. */
    public static boolean allowed(long lastShown, long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(now);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (hour >= 1 && hour < 6) return false;
        return lastShown <= 0 || now - lastShown >= MIN_GAP_MS;
    }

    // ---- was this sentence asking for the card to be switched? ---------------------------------------

    /** What the sentence says about the card, one way or the other. */
    private static final Pattern CARD = Pattern.compile("提示");
    /** 「别弹提示了」「关掉提示」「把亮屏提示关了」: stop showing it. Longest first inside one alternation. */
    private static final Pattern CARD_OFF = Pattern.compile(
            "别弹|不要弹|不用弹|别再弹|关掉|关闭|关了|取消|不要|别给我|停掉");
    /** 「打开提示」「恢复亮屏提示」: show it again. */
    private static final Pattern CARD_ON = Pattern.compile("打开|开启|恢复|重新弹|继续弹|要弹");
    /** 2026-11-11 on the TV: 「现在就是因为没有提示我看不到我的输入你懂吗」 and 「你看我现在每次给你提示提示提示提示提示」
     * were taken for the card's switch — the user was talking about 星仪's own panel the whole time. A sentence that
     * asks why, or says the user cannot see something, is never a switch. */
    private static final Pattern NOT_A_SWITCH = Pattern.compile("为什么|怎么|看不到|没有提示|每次|我的输入");

    /**
     * +1: the sentence asks for the screen-on card to be shown; -1: asks for it to stop; 0: neither.
     *
     * <p>0 whenever the sentence asks why or how, or says the user cannot see their own input: those are about 星仪's
     * panel, not about this card. The two lists are checked in this order — 「不要弹」 contains 「要弹」, so the off
     * words have to be tried first.
     */
    public static int switchAsked(String utterance) {
        if (utterance == null) return 0;
        String s = utterance.trim();
        if (s.isEmpty() || !CARD.matcher(s).find() || NOT_A_SWITCH.matcher(s).find()) return 0;
        if (CARD_OFF.matcher(s).find()) return -1;
        return CARD_ON.matcher(s).find() ? 1 : 0;
    }

    // ---- the three lines ------------------------------------------------------------------------------

    /** A festival, a solar term, a day off or a moved working day, today or tomorrow (Almanac.hint): 「明天立冬」. */
    private static String today(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        return Almanac.hint(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** The soonest thing in the book that is still ahead and close enough to matter: 「今天 20:00 提醒你：该吃药了」. */
    private static String coming(JSONObject schedules, long now, TimeZone tz) {
        if (schedules == null) return null;
        for (JSONObject item : Schedules.all(schedules)) {
            long at = item.optLong("at", Long.MAX_VALUE);
            if (at > now && at - now <= AHEAD_MS) return cut(Schedules.describe(item, now, tz));
        }
        return null;
    }

    /** One pending note is worth naming; several are only worth counting. */
    private static String note(JSONObject notes, long now) {
        JSONArray live = Notes.live(notes, now);
        int n = live.length();
        if (n == 0) return null;
        if (n > 1) return "随手记里有 " + n + " 件没划掉";
        JSONObject it = live.optJSONObject(0);
        String text = it == null ? "" : it.optString("text").trim();
        return text.isEmpty() ? null : cut("随手记：" + text);
    }

    /** What this user does around this hour, when it happened often enough and on more than one day to be a habit. */
    private static String habit(JSONObject habits, long now, TimeZone tz) {
        JSONArray all = habits == null ? null : habits.optJSONArray("events");
        int hour = hour(now, tz);
        Map<String, Group> groups = new LinkedHashMap<>();
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e == null) continue;
            long t = e.optLong("t");
            String kind = e.optString("kind", ""), value = e.optString("value", "").trim();
            int h = e.optInt("h", -1);
            if (t < now - HABIT_MS || !KINDS.contains(kind) || value.isEmpty()) continue;
            if (h < 0 || h > 23 || !near(h, hour)) continue;
            String key = kind + "\n" + value;
            Group g = groups.get(key);
            if (g == null) { g = new Group(kind, value); groups.put(key, g); }
            g.n++;
            g.last = Math.max(g.last, t);
            g.days.add(day(t, tz));
        }
        Group best = null;
        for (Group g : groups.values()) {
            if (g.n < 3 || g.days.size() < 2) continue;
            if (best == null || g.n > best.n || (g.n == best.n && g.last > best.last)) best = g;
        }
        return best == null ? null : line(best);
    }

    private static final class Group {
        final String kind, value;
        final Set<Long> days = new HashSet<>();
        int n;
        long last;
        Group(String kind, String value) { this.kind = kind; this.value = value; }
    }

    /** The line says what happens and how to have it again, so a long name is cut and the sentence is kept (no 「就行」:
     * with it a three-character name already wrapped onto a second line by itself, 2026-11-05 render). */
    private static String line(Group g) {
        if ("app".equals(g.kind)) return cut("这个时候你常开" + g.value);
        boolean artist = "artist".equals(g.kind);
        String head = artist ? "这个时候你常听" : "这个时候你常在B站看";
        String mid = artist ? "，说「放首" : "，说「B站搜";
        String tail = artist ? "的歌」" : "」";
        int room = (MAX_LINE_CHARS - head.length() - mid.length() - tail.length()) / 2;
        String v = g.value.length() <= room ? g.value : g.value.substring(0, Math.max(1, room - 1)) + "…";
        return head + v + mid + v + tail;
    }

    // ---- helpers --------------------------------------------------------------------------------------

    /** 23 and 0 are neighbours: the hour before or after this one, around midnight too. */
    private static boolean near(int h, int hour) {
        int d = Math.abs(h - hour);
        return d <= 1 || d >= 23;
    }

    private static int hour(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    /** The local calendar day an instant falls on, as a plain number, so two events can be compared by date. */
    private static long day(long ms, TimeZone tz) {
        return Math.floorDiv(ms + tz.getOffset(ms), DAY);
    }

    private static String cut(String s) {
        return s.length() <= MAX_LINE_CHARS ? s : s.substring(0, MAX_LINE_CHARS - 1) + "…";
    }
}
