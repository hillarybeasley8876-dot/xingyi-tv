package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/**
 * tv_usage: when the TV was on and which apps were in front, from the system's own usage log (UsageStatsManager —
 * every app, not only what 星仪 opened): 「昨天晚上电视开了 1 小时 10 分：哔哩哔哩 45 分钟（20:58 起）…」. recall_history
 * knows only the requests made to 星仪, and 「昨晚我看了什么」 came back 「没查到」 (2026-11-06 overnight sweep). Pure:
 * the host reads the events (foreground switches, screen on/off) and supplies app labels.
 */
public final class Usage {
    private Usage() { }

    /** One usage event: when, what kind, which package. */
    public static final class Event {
        final long at;
        final int type;
        final String pkg;
        public Event(long at, int type, String pkg) { this.at = at; this.type = type; this.pkg = pkg == null ? "" : pkg; }
    }

    /** UsageEvents.Event types this reads (API 28): an activity came to the front; the screen came on / went off. */
    public static final int FOREGROUND = 1, SCREEN_ON = 15, SCREEN_OFF = 16;

    /** Apps whose time is not worth telling: the home screen, XiaoAi's panels, the system's own windows, 星仪. */
    static final Set<String> QUIET = new HashSet<>(Arrays.asList("xyz.erapi0neer.orrery", "com.xiaomi.voicecontrol",
            "com.android.systemui", "com.mitv.tvhome", "com.xiaomi.mitv.tvhome", "xyz.erapi0neer.xingyi",
            "xyz.erapi0neer.xiaoaibridge", "android", "com.android.tv.settings"));

    /** Labels an app's package; the package itself when it has none. */
    public interface Labels { String of(String pkg); }

    /** The time a window covers, for the day said and a part of it: {from, to} in ms. */
    public static long[] window(String day, String part, long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        String p = part == null ? "" : part.trim();
        if ("刚才".equals(p) || "最近一小时".equals(p)) return new long[]{now - 3_600_000L, now};
        int offset;
        String d = day == null ? "" : day.trim();
        switch (d) {
            case "": case "今天": offset = 0; break;
            case "昨天": offset = -1; break;
            case "昨晚": case "昨天晚上": offset = -1; break;
            case "前天": offset = -2; break;
            default: {
                if (!d.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("day 写今天、昨天、前天或 YYYY-MM-DD");
                Calendar t = Calendar.getInstance(tz);
                t.clear();
                t.set(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(5, 7)) - 1, Integer.parseInt(d.substring(8, 10)));
                Calendar today = (Calendar) c.clone();
                today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0); today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0);
                offset = (int) Math.round((t.getTimeInMillis() - today.getTimeInMillis()) / 86_400_000.0);
                if (offset > 0 || offset < -7) throw new IllegalArgumentException("只能查最近 7 天");
            }
        }
        // 昨晚 runs from yesterday evening into the small hours (asked at 6 in the morning, 1:30 is still 昨晚)
        if ("昨晚".equals(d) || "昨天晚上".equals(d)) {
            Calendar y = (Calendar) c.clone();
            y.add(Calendar.DAY_OF_MONTH, -1);
            y.set(Calendar.HOUR_OF_DAY, 18); y.set(Calendar.MINUTE, 0); y.set(Calendar.SECOND, 0); y.set(Calendar.MILLISECOND, 0);
            return new long[]{y.getTimeInMillis(), Math.min(y.getTimeInMillis() + 11 * 3_600_000L, now)};
        }
        int[] hours;
        switch (p) {
            case "早上": hours = new int[]{5, 9}; break;
            case "上午": hours = new int[]{8, 12}; break;
            case "中午": hours = new int[]{11, 14}; break;
            case "下午": hours = new int[]{12, 18}; break;
            case "晚上": hours = new int[]{18, 24}; break;
            case "夜里": case "半夜": case "凌晨": hours = new int[]{0, 5}; break;
            case "": case "全天": case "一天": hours = new int[]{0, 24}; break;
            default: throw new IllegalArgumentException("part 写早上、上午、中午、下午、晚上、夜里、全天或刚才");
        }
        Calendar from = (Calendar) c.clone();
        from.add(Calendar.DAY_OF_MONTH, offset);
        from.set(Calendar.HOUR_OF_DAY, 0); from.set(Calendar.MINUTE, 0); from.set(Calendar.SECOND, 0); from.set(Calendar.MILLISECOND, 0);
        long start = from.getTimeInMillis() + hours[0] * 3_600_000L, end = from.getTimeInMillis() + hours[1] * 3_600_000L;
        if (start >= now) throw new IllegalArgumentException("这个时段还没到");
        return new long[]{start, Math.min(end, now)};
    }

    /** One stretch of an app in front with the screen on. */
    static final class Stretch {
        final String pkg; final long from, to;
        Stretch(String pkg, long from, long to) { this.pkg = pkg; this.from = from; this.to = to; }
    }

    /**
     * The stretches within [from, to): events before from only set the state at its start (the TV may have been on).
     * Only the screen events say whether the screen is on: this firmware logs apps coming to the front in standby too
     * (2026-11-06: the home screen at 03:01 and B站 for no time at 05:40, with the screen off since 02:59), which made a
     * night in standby count as five hours of TV.
     */
    static List<Stretch> stretches(List<Event> events, long from, long to) { return stretches(events, from, to, false); }

    /**
     * @param onNow whether the screen is on now: the state throughout when the events (read up to now) hold no screen
     *              event at all — apps coming forward say nothing, this firmware logs them in standby (review 2026-11-06:
     *              a day in standby with such events counted as eleven hours of TV)
     */
    static List<Stretch> stretches(List<Event> events, long from, long to, boolean onNow) {
        List<Event> sorted = new ArrayList<>(events);
        Collections.sort(sorted, (a, b) -> Long.compare(a.at, b.at));
        List<Stretch> out = new ArrayList<>();
        // before the first screen event (in the window or after it): on if that event turns it off
        Boolean first = null;
        for (Event e : sorted) {
            if (e.type == SCREEN_ON || e.type == SCREEN_OFF) { first = e.type == SCREEN_OFF; break; }
        }
        boolean on = first != null ? first : onNow;
        String app = null;
        long since = from; // on from the start: counted from the window's start
        for (Event e : sorted) {
            if (e.at >= to) break;
            long t = Math.max(e.at, from);
            boolean wasCounting = on; // with the app unknown (none came forward yet) the time still counts as on
            if (e.type == SCREEN_OFF) {
                if (wasCounting && t > since && e.at >= from) out.add(new Stretch(app == null ? "" : app, since, t));
                on = false;
            } else if (e.type == SCREEN_ON) {
                on = true;
                since = t;
            } else if (e.type == FOREGROUND && !e.pkg.isEmpty()) {
                if (!e.pkg.equals(app)) {
                    if (wasCounting && t > since && e.at >= from) out.add(new Stretch(app == null ? "" : app, since, t));
                    app = e.pkg;
                    since = t;
                }
            }
        }
        if (on && to > Math.max(since, from)) out.add(new Stretch(app == null ? "" : app, Math.max(since, from), to));
        return out;
    }

    /** {"on_minutes", "apps":[{app, minutes, at:[HH:mm–HH:mm…]}], "message"} for the window. */
    public static JSONObject summary(List<Event> events, long from, long to, String what, Labels labels, TimeZone tz) throws JSONException {
        return summary(events, from, to, what, labels, tz, false);
    }

    /** onNow: whether the screen is on now (see stretches); the events should run up to now. */
    public static JSONObject summary(List<Event> events, long from, long to, String what, Labels labels, TimeZone tz,
            boolean onNow) throws JSONException {
        List<Stretch> all = stretches(events, from, to, onNow);
        long onMs = 0;
        Map<String, Long> byApp = new LinkedHashMap<>();
        Map<String, List<Stretch>> parts = new HashMap<>();
        for (Stretch s : all) {
            long ms = s.to - s.from;
            onMs += ms;
            if (s.pkg.isEmpty() || QUIET.contains(s.pkg)) continue;
            byApp.put(s.pkg, (byApp.containsKey(s.pkg) ? byApp.get(s.pkg) : 0L) + ms);
            if (!parts.containsKey(s.pkg)) parts.put(s.pkg, new ArrayList<Stretch>());
            parts.get(s.pkg).add(s);
        }
        List<Map.Entry<String, Long>> ranked = new ArrayList<>(byApp.entrySet());
        Collections.sort(ranked, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        JSONArray apps = new JSONArray();
        StringBuilder said = new StringBuilder(what);
        // when the screen was on, as times: 「几点关的电视」 is answered from these, not worked out from the total
        // (2026-11-06: the total and the first start gave 「2 点 11 分」 for a screen that went off at 2:59)
        List<long[]> spans = new ArrayList<>();
        for (Stretch st : all) {
            long[] last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
            if (last != null && st.from - last[1] <= 60_000L) last[1] = Math.max(last[1], st.to);
            else spans.add(new long[]{st.from, st.to});
        }
        JSONArray on = new JSONArray();
        List<String> onSaid = new ArrayList<>();
        for (long[] sp : spans) if (sp[1] - sp[0] >= 60_000L) { on.put(hhmm(sp[0], tz) + "–" + hhmm(sp[1], tz)); onSaid.add(span(sp[0], sp[1], tz)); }
        if (onMs < 60_000L) {
            said.append("电视没开（没有使用记录）");
        } else {
            said.append("电视开了 ").append(duration(onMs));
            if (on.length() > 0) {
                StringBuilder at = new StringBuilder();
                for (int i = 0; i < Math.min(4, onSaid.size()); i++) at.append(i == 0 ? "" : "、").append(onSaid.get(i));
                if (on.length() > 4) at.append("等 ").append(on.length()).append(" 段");
                // 「…凌晨 0 点 亮着」 would be heard 「点亮着」
                said.append("（亮着的是").append(at).append("）");
            }
            int listed = 0;
            for (Map.Entry<String, Long> e : ranked) {
                if (e.getValue() < 60_000L || listed >= 5) break;
                String label = labels == null ? e.getKey() : labels.of(e.getKey());
                JSONArray at = new JSONArray();
                String first = "";
                for (Stretch s : parts.get(e.getKey())) if (s.to - s.from >= 60_000L && at.length() < 3) {
                    if (at.length() == 0) first = span(s.from, s.to, tz);
                    at.put(hhmm(s.from, tz) + "–" + hhmm(s.to, tz));
                }
                apps.put(new JSONObject().put("app", label).put("minutes", Math.round(e.getValue() / 60_000.0)).put("at", at));
                said.append(listed == 0 ? "：" : "，").append(label).append(" ").append(duration(e.getValue()));
                if (at.length() > 0) said.append("（").append(first).append(at.length() > 1 ? "等" : "").append("）");
                listed++;
            }
            if (listed == 0) said.append("，主要停在桌面上，没有打开别的应用");
            else {
                // the rest was the home screen or XiaoAi's panels (music may have played in the background): said, not
                // left for the model to call 「没在用」
                long inApps = 0;
                for (Map.Entry<String, Long> e : ranked) inApps += e.getValue();
                if (onMs - inApps >= 10 * 60_000L) said.append("，其余 ").append(duration(onMs - inApps)).append("在桌面上");
            }
        }
        return new JSONObject().put("on_minutes", Math.round(onMs / 60_000.0)).put("on", on).put("apps", apps)
                .put("message", said.append("。").toString());
    }

    static String duration(long ms) {
        long m = Math.round(ms / 60_000.0);
        if (m < 60) return m + " 分钟";
        return (m / 60) + " 小时" + (m % 60 == 0 ? "" : " " + (m % 60) + " 分");
    }

    /** A stretch as said (2026-11-07: 「20:58–21:03」 may be read 「二十比五十八」): 「晚上 8 点 58 分到 9 点 3 分」, the part of
     * the day said once when both ends share it. */
    static String span(long from, long to, TimeZone tz) {
        String a = clock(from, tz), b = clock(to, tz);
        String pa = a.substring(0, 2), pb = b.substring(0, 2);
        return a + "到" + (pa.equals(pb) ? b.substring(2) : b);
    }

    /** 「晚上 8 点 58 分」「凌晨 0 点」. */
    static String clock(long at, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(at);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        String part = h < 5 ? "凌晨" : h < 8 ? "早上" : h < 12 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
        return part + " " + (h > 12 ? h - 12 : h) + " 点" + (m == 0 ? "" : " " + m + " 分");
    }

    private static String hhmm(long at, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(at);
        return String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** 「昨天晚上（18:00–24:00）」: how the window is said. */
    public static String what(String day, String part) {
        String d = day == null || day.trim().isEmpty() ? "今天" : day.trim();
        String p = part == null ? "" : part.trim();
        if ("刚才".equals(p) || "最近一小时".equals(p)) return "最近一小时";
        if ("昨晚".equals(d) || "昨天晚上".equals(d)) return "昨天晚上到今天凌晨";
        return d + (p.isEmpty() || "全天".equals(p) || "一天".equals(p) ? "" : p);
    }
}
