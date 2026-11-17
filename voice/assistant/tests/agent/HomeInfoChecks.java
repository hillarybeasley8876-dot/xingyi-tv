package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.TimeZone;
import org.json.*;

/** home-info (2026-11-08): what the home screen shows from 星仪 — Almanac.homeLine, Weather.homeLine and HomeNotes
 * (the line under a paused app). The forecast is the fixture the weather checks use (its first day is 2026-11-06,
 * a Friday); the dates are the almanac's own, checked day by day against lunar_python (AlmanacVerify). */
final class HomeInfoChecks {
    private HomeInfoChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    private static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    /** The wall clock of a local hour in a zone, for homeLine's now. */
    private static long at(TimeZone tz, int y, int m, int d, int h) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, m - 1, d, h, 0, 0);
        return c.getTimeInMillis();
    }

    /** A forecast of one day, for the rounding and the odd cases. */
    private static JSONObject day(String date, int code, Object lo, Object hi) throws JSONException {
        return new JSONObject().put("daily", new JSONObject().put("time", new JSONArray().put(date))
                .put("weather_code", new JSONArray().put(code))
                .put("temperature_2m_min", new JSONArray().put(lo))
                .put("temperature_2m_max", new JSONArray().put(hi)));
    }

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "home info checks threw " + e);
        }
    }

    static void checks() throws Exception {
        lunar();
        weather();
        notes();
    }

    static void lunar() {
        check("农历九月廿八".equals(Almanac.homeLine(2026, 11, 6)), "an ordinary day: " + Almanac.homeLine(2026, 11, 6));
        check("农历九月三十".equals(Almanac.homeLine(2026, 11, 8)), "the design's own day: " + Almanac.homeLine(2026, 11, 8));
        check("农历九月廿九　·　立冬".equals(Almanac.homeLine(2026, 11, 7)), "a term: " + Almanac.homeLine(2026, 11, 7));
        check("农历冬月十四　·　冬至".equals(Almanac.homeLine(2026, 12, 22)), "another term: " + Almanac.homeLine(2026, 12, 22));
        check("农历腊月廿九　·　除夕".equals(Almanac.homeLine(2027, 2, 5)), "除夕: " + Almanac.homeLine(2027, 2, 5));
        check("农历正月初一　·　春节".equals(Almanac.homeLine(2027, 2, 6)), "春节: " + Almanac.homeLine(2027, 2, 6));
        check("农历八月十五　·　中秋节".equals(Almanac.homeLine(2026, 9, 25)), "a lunar festival: " + Almanac.homeLine(2026, 9, 25));
        check("农历八月廿一　·　国庆节".equals(Almanac.homeLine(2026, 10, 1)), "a solar festival: " + Almanac.homeLine(2026, 10, 1));
        check("农历闰六月初一".equals(Almanac.homeLine(2025, 7, 25)), "a leap month: " + Almanac.homeLine(2025, 7, 25));
        check("农历十月初一".equals(Almanac.homeLine(2026, 11, 9)), "十月初一 (寒衣节 is not in the calendar's table): "
                + Almanac.homeLine(2026, 11, 9));
        check("".equals(Almanac.homeLine(2101, 6, 1)) && "".equals(Almanac.homeLine(1900, 1, 30)), "a year outside the table: nothing");
    }

    static void weather() throws Exception {
        JSONObject f = new JSONObject(fixture("weather-forecast.json"));
        String morning = Weather.homeLine(f, at(SH, 2026, 11, 6, 9), SH);
        check("阴 14° – 19°".equals(morning), "today: " + morning);
        String evening = Weather.homeLine(f, at(SH, 2026, 11, 6, 19), SH);
        check(morning.equals(evening), "still today at 19:00: " + evening);
        String night = Weather.homeLine(f, at(SH, 2026, 11, 6, 20), SH);
        check("明天 阴 16° – 18°".equals(night), "from 20:00 tomorrow's: " + night);
        check(night.equals(Weather.homeLine(f, at(SH, 2026, 11, 6, 23), SH)), "23:00 is tomorrow's too");
        String next = Weather.homeLine(f, at(SH, 2026, 11, 7, 8), SH);
        check("阴 16° – 18°".equals(next), "the next morning is that day: " + next);
        String nextNight = Weather.homeLine(f, at(SH, 2026, 11, 7, 20), SH);
        check("明天 小雨 16° – 19°".equals(nextNight), "the rain the evening before: " + nextNight);
        check("".equals(Weather.homeLine(f, at(SH, 2026, 11, 13, 9), SH)), "a day the forecast does not cover");
        check("".equals(Weather.homeLine(f, at(SH, 2026, 11, 5, 9), SH)), "a day before the forecast");
        // the forecast's days are the place's own: 2026-11-07 03:00 in Shanghai is still the 6th in UTC
        long instant = at(SH, 2026, 11, 7, 3);
        check("阴 14° – 19°".equals(Weather.homeLine(f, instant, TimeZone.getTimeZone("UTC"))), "the zone is the place's: "
                + Weather.homeLine(f, instant, TimeZone.getTimeZone("UTC")));
        check(next.equals(Weather.homeLine(f, instant, SH)), "the same instant in Shanghai is the 7th: " + Weather.homeLine(f, instant, SH));
        JSONObject d = f.getJSONObject("daily");
        JSONObject one = day(d.getJSONArray("time").getString(0), d.getJSONArray("weather_code").getInt(0),
                d.getJSONArray("temperature_2m_min").getDouble(0), d.getJSONArray("temperature_2m_max").getDouble(0));
        check("阴 14° – 19°".equals(Weather.homeLine(one, at(SH, 2026, 11, 6, 9), SH)), "a forecast of one day: "
                + Weather.homeLine(one, at(SH, 2026, 11, 6, 9), SH));
        check("".equals(Weather.homeLine(one, at(SH, 2026, 11, 6, 21), SH)), "one day and the evening: no tomorrow to show");
        String up = Weather.homeLine(day("2026-11-06", 2, 13.5, 19.4), at(SH, 2026, 11, 6, 9), SH);
        check("多云 14° – 19°".equals(up), "13.5 rounds up: " + up);
        String zero = Weather.homeLine(day("2026-11-06", 2, -0.4, 2.6), at(SH, 2026, 11, 6, 9), SH);
        check("多云 0° – 3°".equals(zero), "−0.4 is zero, 2.6 is 3: " + zero);
        String cold = Weather.homeLine(day("2026-11-06", 71, -2.6, -0.4), at(SH, 2026, 11, 6, 9), SH);
        check("小雪 -3° – 0°".equals(cold), "below zero: " + cold);
        String odd = Weather.homeLine(day("2026-11-06", 4, 1.0, 2.0), at(SH, 2026, 11, 6, 9), SH);
        check("天气不明 1° – 2°".equals(odd), "a code the table does not know: " + odd);
        String bare = Weather.homeLine(day("2026-11-06", 0, JSONObject.NULL, JSONObject.NULL), at(SH, 2026, 11, 6, 9), SH);
        check("晴".equals(bare), "no temperatures: the sky alone: " + bare);
        check("".equals(Weather.homeLine(new JSONObject("{}"), at(SH, 2026, 11, 6, 9), SH)), "a forecast with no days at all");
        check("".equals(Weather.homeLine(null, at(SH, 2026, 11, 6, 9), SH)), "no forecast: nothing");
        check(Weather.homeLine(f, at(SH, 2026, 11, 6, 9), null) != null, "no zone: the TV's own");
    }

    static void notes() {
        check(HomeNotes.music("com.netease.cloudmusic.tv") && HomeNotes.music("com.netease.cloudmusic")
                && HomeNotes.music("com.tencent.qqmusictv") && HomeNotes.music("com.kugou.android.tv"), "the music apps");
        check(!HomeNotes.music("tv.danmaku.bili") && !HomeNotes.music("com.gitvdemo.video") && !HomeNotes.music("com.tencent.qqlivepad")
                && !HomeNotes.music("") && !HomeNotes.music(null), "everything else is watched, not listened to");
        check("上次在听《江南》林俊杰".equals(HomeNotes.paused("com.netease.cloudmusic.tv", "江南", "林俊杰")), "网易云: "
                + HomeNotes.paused("com.netease.cloudmusic.tv", "江南", "林俊杰"));
        check("上次在听《江南》".equals(HomeNotes.paused("com.tencent.qqmusictv", "江南", "")), "QQ音乐 without an artist: "
                + HomeNotes.paused("com.tencent.qqmusictv", "江南", ""));
        check("上次在听《江南》".equals(HomeNotes.paused("com.kugou.android.tv", " 江南 ", null)), "酷狗, spaces trimmed");
        check("上次在看《影视飓风》".equals(HomeNotes.paused("tv.danmaku.bili", "影视飓风", "影视飓风")), "B站 is watched: "
                + HomeNotes.paused("tv.danmaku.bili", "影视飓风", "影视飓风"));
        check("上次在看《三体》".equals(HomeNotes.paused("com.gitvdemo.video", "三体", "刘慈欣")), "a video app names no artist");
        check("".equals(HomeNotes.paused("tv.danmaku.bili", "", "x")) && "".equals(HomeNotes.paused(null, null, null)), "no title, no line");
    }
}
