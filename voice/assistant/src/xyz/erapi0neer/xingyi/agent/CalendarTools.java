package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.function.LongSupplier;
import org.json.*;

/** calendar: the Chinese calendar from data (Almanac) instead of the model's arithmetic — 农历, 节气, 节日, 放假和调休,
 * 「还有几天过年」「国庆放几天」. Read-gated, works without a plan. 2026-11-06 overnight pass. */
public final class CalendarTools {
    private CalendarTools() { }

    public static List<Tool> create(LongSupplier clock, TimeZone tz) {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool() {
            @Override public String name() { return "calendar"; }
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String f = a.optString("find").trim(), d = a.optString("date").trim();
                return "查日历" + (!f.isEmpty() ? "：" + f : !d.isEmpty() ? "：" + d : "");
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception { return look(a, today(clock, tz)); }
        });
        return tools;
    }

    static long today(LongSupplier clock, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(clock.getAsLong());
        return Almanac.days(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** The answer for args {date?, find?} on the day today (a day number). */
    static JSONObject look(JSONObject a, long today) throws JSONException {
        String find = a.optString("find").trim(), date = a.optString("date").trim();
        JSONObject r = new JSONObject().put("ok", true).put("status", "observed").put("today", Almanac.describe(today, today));
        List<String> said = new ArrayList<>();
        if (!date.isEmpty()) {
            long day = parse(date, today);
            if (day == Long.MIN_VALUE || Almanac.lunar(day) == null)
                return new JSONObject().put("ok", false).put("status", "rejected")
                        .put("message", "date 要写成 YYYY-MM-DD（1950 到 2100 年），或者今天、明天、后天");
            JSONObject d = Almanac.describe(day, today);
            r.put("day", d);
            said.add(d.getString("date") + "（" + d.getString("weekday") + "）是" + d.optString("lunar")
                    + (d.has("term") ? "，" + d.getString("term") : "") + (d.has("festivals") ? "，" + join(d.getJSONArray("festivals")) : "")
                    + "，" + d.getString("status") + (day == today ? "" : "，" + distance(day - today)));
        }
        if (!find.isEmpty()) {
            JSONObject h = Almanac.holiday(find, today);
            // during (or just after) the holiday, 「国庆放几天」 means this one: its festival is looked for from its first day
            long from = h != null ? Math.min(today, h.getLong("first_day")) : today;
            long day = Almanac.find(find, from);
            if (h != null) h.remove("first_day");
            if (day >= 0) {
                JSONObject d = Almanac.describe(day, today);
                r.put("found", d);
                said.add(find + "是 " + d.getString("date") + "（" + d.getString("weekday") + "，" + d.optString("lunar") + "），"
                        + (day == today ? "就是今天" : distance(day - today)));
            }
            if (h != null) {
                r.put("holiday", h);
                said.add(h.getString("name") + "放假 " + h.getString("from") + " 到 " + h.getString("to") + "，共 " + h.getLong("days") + " 天"
                        + (h.getJSONArray("work_days").length() > 0 ? "；调休上班：" + join(h.getJSONArray("work_days")) : "；不调休"));
            } else if (day >= 0) {
                int year = Almanac.civil(day)[0];
                if (!Almanac.scheduled(year)) said.add(year + " 年的放假安排还没有数据，放不放假、放几天按不知道说");
            }
            if (day < 0 && h == null) {
                r.put("found", JSONObject.NULL);
                said.add("一年之内没有叫「" + find + "」的节日、节气或法定假日");
            }
        }
        if (find.isEmpty() && date.isEmpty()) {
            // festivals first: 「下一个节日是什么」 was answered with the next term when the term led (2026-11-06)
            for (long day = today + 1; day < today + 400; day++) {
                List<String> f = Almanac.festivals(day);
                if (!f.isEmpty()) { said.add("下一个节日" + String.join("、", f) + "，" + Almanac.date(day) + "，" + distance(day - today)); break; }
            }
            for (long day = today + 1; day < today + 400; day++) {
                String s = Almanac.status(day);
                if (s.startsWith("放假")) {
                    JSONObject h = Almanac.holiday(s.substring(3, s.length() - 1), day);
                    if (h != null) { h.remove("first_day"); r.put("next_holiday", h); said.add("下一个法定假日" + h.getString("name") + "，" + h.getString("from") + " 起放 " + h.getLong("days") + " 天"); }
                    break;
                }
            }
            long[] term = Almanac.nextTerm(today, true);
            if (term != null) said.add("下一个节气" + Almanac.TERMS[(int) term[1]] + "，" + Almanac.date(term[0]) + "，" + distance(term[0] - today));
        }
        r.put("message", said.isEmpty() ? r.getJSONObject("today").toString() : String.join("；", said));
        return r;
    }

    private static String distance(long n) { return n > 0 ? "还有 " + n + " 天" : "已经过去 " + (-n) + " 天"; }

    private static String join(JSONArray a) {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return String.join("、", s);
    }

    /** YYYY-MM-DD (also with / or 年月日), 今天, 明天, 后天, 大后天, 昨天, 前天; Long.MIN_VALUE otherwise. */
    static long parse(String s, long today) {
        switch (s) {
            case "今天": return today;
            case "明天": return today + 1;
            case "后天": return today + 2;
            case "大后天": return today + 3;
            case "昨天": return today - 1;
            case "前天": return today - 2;
            default:
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*[日号]?$").matcher(s);
        if (!m.matches()) return Long.MIN_VALUE;
        int y = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(m.group(3));
        if (mo < 1 || mo > 12 || d < 1 || d > 31) return Long.MIN_VALUE;
        long day = Almanac.days(y, mo, d);
        int[] back = Almanac.civil(day);
        return back[1] == mo && back[2] == d ? day : Long.MIN_VALUE; // 2 月 30 日 is not a day
    }
}
