package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** tv_usage (Usage): the TV's own usage log as a spoken summary. The events copy the evening of 2026-11-05 as
 * `dumpsys usagestats` showed it (screen on 20:58:57, B站, home, screen off 21:03:26, on again 21:52 …). */
final class UsageChecks {
    private UsageChecks() { }

    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static long at(int day, int h, int m, int s) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, day, h, m, s);
        return c.getTimeInMillis();
    }

    static void run() {
        try {
            windows();
            evening();
            edges();
        } catch (Exception e) {
            check(false, "usage checks threw " + e);
        }
    }

    static void windows() {
        long now = at(6, 5, 50, 0);
        long[] w = Usage.window("昨天", "晚上", now, TZ);
        check(w[0] == at(5, 18, 0, 0) && w[1] == at(6, 0, 0, 0), "昨天晚上 is 18:00–24:00 of the 5th");
        long[] n = Usage.window("昨晚", "", now, TZ);
        check(n[0] == at(5, 18, 0, 0) && n[1] == at(6, 5, 0, 0), "昨晚 runs into the small hours");
        long[] early = Usage.window("昨晚", "", at(6, 1, 30, 0), TZ);
        check(early[1] == at(6, 1, 30, 0), "and not past now");
        long[] t = Usage.window("今天", "", now, TZ);
        check(t[0] == at(6, 0, 0, 0) && t[1] == now, "today so far");
        long[] r = Usage.window("", "刚才", now, TZ);
        check(r[1] - r[0] == 3_600_000L && r[1] == now, "刚才 is the last hour");
        long[] d = Usage.window("2026-11-04", "下午", now, TZ);
        check(d[0] == at(4, 12, 0, 0) && d[1] == at(4, 18, 0, 0), "a date's afternoon");
        boolean threw = false;
        try { Usage.window("今天", "晚上", now, TZ); } catch (IllegalArgumentException e) { threw = e.getMessage().contains("还没到"); }
        check(threw, "tonight has not come yet at 05:50");
        threw = false;
        try { Usage.window("上个月", "", now, TZ); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "only the last days");
        check("昨天晚上到今天凌晨".equals(Usage.what("昨晚", "")) && "今天".equals(Usage.what("", "")) && "昨天下午".equals(Usage.what("昨天", "下午")),
                "how the window is said");
    }

    private static List<Usage.Event> events() {
        String bili = "tv.danmaku.bili", home = "xyz.erapi0neer.orrery";
        return Arrays.asList(
                new Usage.Event(at(5, 17, 40, 0), Usage.FOREGROUND, home),
                new Usage.Event(at(5, 17, 41, 0), Usage.SCREEN_OFF, "android"),
                new Usage.Event(at(5, 20, 58, 57), Usage.SCREEN_ON, "android"),
                new Usage.Event(at(5, 20, 58, 57), Usage.FOREGROUND, bili),
                new Usage.Event(at(5, 20, 58, 58), Usage.FOREGROUND, bili),
                new Usage.Event(at(5, 20, 59, 11), Usage.FOREGROUND, home),
                new Usage.Event(at(5, 20, 59, 54), Usage.FOREGROUND, bili),
                new Usage.Event(at(5, 21, 3, 26), Usage.FOREGROUND, home),
                new Usage.Event(at(5, 21, 3, 26), Usage.SCREEN_OFF, "android"),
                new Usage.Event(at(5, 21, 52, 0), Usage.SCREEN_ON, "android"),
                new Usage.Event(at(5, 21, 52, 0), Usage.FOREGROUND, home),
                new Usage.Event(at(5, 23, 37, 34), Usage.FOREGROUND, bili),
                new Usage.Event(at(5, 23, 38, 56), Usage.FOREGROUND, home),
                new Usage.Event(at(6, 0, 30, 0), Usage.SCREEN_OFF, "android"));
    }

    static void evening() throws Exception {
        Usage.Labels labels = p -> "tv.danmaku.bili".equals(p) ? "哔哩哔哩" : "xyz.erapi0neer.orrery".equals(p) ? "Orrery" : p;
        JSONObject s = Usage.summary(events(), at(5, 18, 0, 0), at(6, 0, 0, 0), "昨天晚上", labels, TZ);
        String m = s.getString("message");
        check("昨天晚上电视开了 2 小时 12 分（亮着的是晚上 8 点 58 分到 9 点 3 分、晚上 9 点 52 分到凌晨 0 点）：哔哩哔哩 5 分钟（晚上 8 点 59 分到 9 点 3 分等），其余 2 小时 7 分在桌面上。".equals(m), "the evening: " + m);
        check(s.getLong("on_minutes") == 132 && s.getJSONArray("apps").length() == 1
                && s.getJSONArray("apps").getJSONObject(0).getJSONArray("at").length() == 2, "on time and B站's stretches: " + s);
        // the window starts while the TV is on: the stretch counts from the window's start
        JSONObject late = Usage.summary(events(), at(5, 23, 0, 0), at(6, 0, 0, 0), "昨天十一点以后", labels, TZ);
        check(late.getLong("on_minutes") == 60 && late.getString("message").contains("哔哩哔哩 1 分钟"), "a window that opens mid-way: " + late);
    }

    static void edges() throws Exception {
        Usage.Labels labels = p -> p;
        JSONObject none = Usage.summary(new ArrayList<Usage.Event>(), at(5, 8, 0, 0), at(5, 12, 0, 0), "昨天上午", labels, TZ);
        check("昨天上午电视没开（没有使用记录）。".equals(none.getString("message")), "nothing in the window");
        List<Usage.Event> homeOnly = Arrays.asList(new Usage.Event(at(5, 9, 0, 0), Usage.SCREEN_ON, "android"),
                new Usage.Event(at(5, 9, 0, 0), Usage.FOREGROUND, "xyz.erapi0neer.orrery"),
                new Usage.Event(at(5, 9, 30, 0), Usage.SCREEN_OFF, "android"));
        check(Usage.summary(homeOnly, at(5, 8, 0, 0), at(5, 12, 0, 0), "昨天上午", labels, TZ).getString("message")
                .equals("昨天上午电视开了 30 分钟（亮着的是上午 9 点到 9 点 30 分），主要停在桌面上，没有打开别的应用。"), "only the home screen");
        // standby: apps that come forward with the screen off are not TV time (11-06 03:01 home, 05:40 B站 for 0 s)
        List<Usage.Event> night = Arrays.asList(new Usage.Event(at(6, 2, 50, 0), Usage.FOREGROUND, "com.netease.cloudmusic.tv"),
                new Usage.Event(at(6, 2, 59, 41), Usage.SCREEN_OFF, "android"),
                new Usage.Event(at(6, 3, 1, 55), Usage.FOREGROUND, "xyz.erapi0neer.orrery"),
                new Usage.Event(at(6, 5, 40, 10), Usage.FOREGROUND, "tv.danmaku.bili"));
        JSONObject n = Usage.summary(night, at(6, 0, 0, 0), at(6, 5, 57, 0), "今天", labels, TZ);
        check(n.getLong("on_minutes") == 180 && n.getString("message").contains("com.netease.cloudmusic.tv 10 分钟")
                && !n.getString("message").contains("tv.danmaku.bili"), "the night in standby is not TV time: " + n);
        // no screen event at all (read up to now): the state throughout is the screen now, whatever came forward
        List<Usage.Event> ghosts = Arrays.asList(new Usage.Event(at(5, 9, 0, 0), Usage.FOREGROUND, "tv.danmaku.bili"),
                new Usage.Event(at(5, 21, 0, 0), Usage.FOREGROUND, "xyz.erapi0neer.orrery"));
        check(Usage.summary(ghosts, at(5, 18, 0, 0), at(6, 5, 0, 0), "昨晚", labels, TZ, false).getLong("on_minutes") == 0,
                "standby all along: no TV time, however many apps came forward");
        check(Usage.summary(ghosts, at(5, 18, 0, 0), at(5, 19, 0, 0), "昨晚", labels, TZ, true).getLong("on_minutes") == 60,
                "on all along (the screen is on now and never went off): the whole hour");
        // a screen event only after the window tells the state before it
        List<Usage.Event> later = Arrays.asList(new Usage.Event(at(6, 8, 0, 0), Usage.SCREEN_ON, "android"));
        check(Usage.summary(later, at(5, 18, 0, 0), at(6, 5, 0, 0), "昨晚", labels, TZ, true).getLong("on_minutes") == 0,
                "the screen came on this morning: it was off last night");
        check("59 分钟".equals(Usage.duration(59 * 60_000L)) && "1 小时".equals(Usage.duration(60 * 60_000L))
                && "2 小时 5 分".equals(Usage.duration(125 * 60_000L)), "durations");
    }
}
