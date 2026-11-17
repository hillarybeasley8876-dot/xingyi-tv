package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/**
 * 「早上好」「今天有什么安排」: the day in one answer, from what 星仪 already knows — the date and the lunar date, the
 * weather (Weather.hint), a festival, term or day off (Almanac.hint), what is booked for today and what is still on the
 * 随手记 — as Alexa's and Google's 「tell me about my day」 do (2026-11-06 overnight pass). Pure; the host fetches the
 * weather line (null when it could not).
 */
public final class Brief {
    private Brief() { }

    public static String today(long now, TimeZone tz, String weather, JSONObject schedules, JSONObject notes) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        int y = c.get(Calendar.YEAR), m = c.get(Calendar.MONTH) + 1, d = c.get(Calendar.DAY_OF_MONTH), h = c.get(Calendar.HOUR_OF_DAY);
        StringBuilder s = new StringBuilder(h < 11 ? "早上好。" : h < 13 ? "中午好。" : h < 18 ? "下午好。" : "晚上好。");
        s.append(QuickCommand.clockText("date", c)).append("。");
        if (weather != null && !weather.trim().isEmpty()) s.append(weather.trim()).append("。");
        String day = Almanac.hint(y, m, d);
        if (day != null) s.append(day).append("。");
        // what is booked from now to midnight
        long midnight = now + (24 - h) * 3_600_000L - c.get(Calendar.MINUTE) * 60_000L;
        List<String> booked = new ArrayList<>();
        for (JSONObject item : schedules == null ? Collections.<JSONObject>emptyList() : Schedules.all(schedules)) {
            long at = item.optLong("at", Long.MAX_VALUE);
            if (at > now && at < midnight && booked.size() < 3) booked.add(Schedules.describe(item, now, tz));
        }
        s.append(booked.isEmpty() ? "今天没有预约" : "今天的预约：" + String.join("；", booked));
        JSONArray live = notes == null ? new JSONArray() : Notes.live(notes, now);
        if (live.length() == 1) s.append("；随手记：").append(live.optJSONObject(0) == null ? "" : live.optJSONObject(0).optString("text"));
        else if (live.length() > 1) s.append("；随手记里有 ").append(live.length()).append(" 件没划掉");
        return s.append("。").toString();
    }
}
