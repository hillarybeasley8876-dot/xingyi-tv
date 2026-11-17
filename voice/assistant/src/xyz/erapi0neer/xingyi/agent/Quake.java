package xyz.erapi0neer.xingyi.agent;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 「刚才是不是地震了」「最近哪里地震了」 (2026-11-07): the China Earthquake Networks Center's bulletins (中国地震台网 — the
 * automatic reports within minutes, then the reviewed ones), through the wolfx.jp relay of its list, with how far each
 * is from the TV. A web search has nothing in the first minutes.
 */
public final class Quake {
    private Quake() { }

    public static final String CENC = "https://api.wolfx.jp/cenc_eqlist.json";
    static final TimeZone BEIJING = TimeZone.getTimeZone("Asia/Shanghai");

    public static final class Event {
        public final long at;
        public final String place;
        public final double magnitude, depth, lat, lon;
        public final boolean reviewed;

        Event(long at, String place, double magnitude, double depth, double lat, double lon, boolean reviewed) {
            this.at = at; this.place = place; this.magnitude = magnitude; this.depth = depth; this.lat = lat; this.lon = lon;
            this.reviewed = reviewed;
        }
    }

    /** The list, newest first (its keys are No1, No2, …). */
    public static List<Event> events(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        List<Event> out = new ArrayList<>();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        f.setTimeZone(BEIJING);
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject e = o.optJSONObject(k);
            if (e == null || !k.startsWith("No")) continue;
            long at;
            try { at = f.parse(News.str(e, "time")).getTime(); } catch (ParseException x) { continue; }
            out.add(new Event(at, (!News.str(e, "placeName").isEmpty() ? News.str(e, "placeName") : News.str(e, "location")).trim(), e.optDouble("magnitude", 0), e.optDouble("depth", 0),
                    e.optDouble("latitude", 0), e.optDouble("longitude", 0), "reviewed".equals(News.str(e, "type"))));
        }
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    /** Great-circle distance in km. */
    static double km(double lat1, double lon1, double lat2, double lon2) {
        double p = Math.PI / 180, a = Math.sin((lat2 - lat1) * p / 2), b = Math.sin((lon2 - lon1) * p / 2);
        double h = a * a + Math.cos(lat1 * p) * Math.cos(lat2 * p) * b * b;
        return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    static String when(long at, long now) {
        Calendar c = Calendar.getInstance(BEIJING), n = Calendar.getInstance(BEIJING);
        c.setTimeInMillis(at);
        n.setTimeInMillis(now);
        boolean today = c.get(Calendar.YEAR) == n.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return (today ? "今天" : (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日") + " " + h + " 点" + (m == 0 ? "" : " " + m + " 分");
    }

    static String line(Event e, long now, double hereLat, double hereLon, boolean here) {
        StringBuilder s = new StringBuilder(when(e.at, now)).append(e.place).append(" ")
                .append(String.format(Locale.ROOT, "%.1f", e.magnitude)).append(" 级，震源深度 ").append(Math.round(e.depth)).append(" 公里");
        if (here) s.append("，离这里约 ").append(Math.round(km(hereLat, hereLon, e.lat, e.lon) / 10) * 10).append(" 公里");
        if (!e.reviewed) s.append("（自动测定，之后可能修正）");
        return s.toString();
    }

    /**
     * 「刚才是不是地震了」: an event in the last hours within reach of the TV (a magnitude 3 felt a hundred kilometres off, a
     * 6 several hundred) is a yes; otherwise a no, and the latest one anywhere. hereLat/hereLon NaN: the TV's place
     * is not known, the latest is said.
     */
    public static String nearby(List<Event> events, long now, double hereLat, double hereLon, int hours) {
        boolean here = !Double.isNaN(hereLat) && !Double.isNaN(hereLon);
        if (events.isEmpty()) return "中国地震台网这会儿没有返回地震记录";
        if (here) for (Event e : events) {
            if (now - e.at > hours * 3_600_000L) break;
            double reach = e.magnitude >= 6 ? 800 : e.magnitude >= 5 ? 400 : e.magnitude >= 4 ? 200 : 100;
            if (km(hereLat, hereLon, e.lat, e.lon) <= reach)
                return "是的，中国地震台网测定：" + line(e, now, hereLat, hereLon, true);
        }
        String latest = line(events.get(0), now, hereLat, hereLon, here);
        return "中国地震台网最近 " + hours + " 小时在这附近没有测到地震。全国最近一次是" + (Character.isDigit(latest.charAt(0)) ? " " : "") + latest;
    }

    /** 「中国地震台网最近 3 次：…；…；…」, optionally only those whose place contains a word (四川, 日本). */
    public static String latest(List<Event> events, long now, double hereLat, double hereLon, String where, int n) {
        boolean here = !Double.isNaN(hereLat) && !Double.isNaN(hereLon);
        List<Event> picked = new ArrayList<>();
        for (Event e : events) if (where == null || where.isEmpty() || e.place.contains(where)) { picked.add(e); if (picked.size() >= n) break; }
        if (picked.isEmpty()) return "中国地震台网最近的记录里没有" + where + "的地震";
        StringBuilder s = new StringBuilder("中国地震台网" + (where == null || where.isEmpty() ? "" : "记录的" + where) + "最近 " + picked.size() + " 次：");
        for (int i = 0; i < picked.size(); i++) s.append(i == 0 ? "" : "；").append(line(picked.get(i), now, hereLat, hereLon, here));
        return s.toString();
    }
}
