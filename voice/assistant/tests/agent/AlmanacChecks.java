package xyz.erapi0neer.xingyi.agent;

import java.util.Calendar;
import java.util.Collections;
import java.util.TimeZone;
import org.json.JSONObject;

/** The Chinese calendar (Almanac, CalendarTools): spot checks; AlmanacVerify compares every day of 1950-2100 with
 * lunar_python. Dates checked against lunar_python 1.4.9 and the State Council's 2026 and 2027 schedules. */
final class AlmanacChecks {
    private AlmanacChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static void run() {
        try {
            lunar();
            festivals();
            holidays();
            answers();
            tool();
        } catch (Exception e) {
            check(false, "almanac checks threw " + e);
        }
    }

    static void lunar() {
        Almanac.Lunar l = Almanac.lunar(2026, 11, 6);
        check(l != null && l.year == 2026 && l.month == 9 && l.day == 28 && !l.leap, "2026-11-06 is 九月廿八");
        check("九月廿八".equals(l.text()) && "丙午马年".equals(l.yearText()), "spoken: " + l.text() + " " + l.yearText());
        Almanac.Lunar leap = Almanac.lunar(2025, 7, 25);
        check(leap != null && leap.leap && leap.month == 6 && leap.day == 1 && "闰六月初一".equals(leap.text()), "2025 has 闰六月: " + (leap == null ? null : leap.text()));
        check("正月初一".equals(Almanac.lunar(2027, 2, 6).text()) && "丁未羊年".equals(Almanac.lunar(2027, 2, 6).yearText()), "2027 春节 starts 丁未羊年");
        check("丙午马年".equals(Almanac.lunar(2027, 2, 5).yearText()) && "腊月廿九".equals(Almanac.lunar(2027, 2, 5).text())
                && Almanac.festivals(2027, 2, 5).contains("除夕"), "除夕 is still the old year, on 廿九 when 腊月 is short");
        check(Almanac.lunar(1900, 1, 30) == null && Almanac.lunar(2101, 6, 1) == null, "outside the table: null");
        check("初十".equals(Almanac.dayText(10)) && "二十".equals(Almanac.dayText(20)) && "廿一".equals(Almanac.dayText(21))
                && "十五".equals(Almanac.dayText(15)) && "三十".equals(Almanac.dayText(30)), "day names");
        check("立冬".equals(Almanac.term(2026, 11, 7)) && Almanac.term(2026, 11, 6) == null && "冬至".equals(Almanac.term(2026, 12, 22)), "terms");
        long d = Almanac.days(2026, 11, 6);
        int[] c = Almanac.civil(d);
        check(c[0] == 2026 && c[1] == 11 && c[2] == 6 && "星期五".equals(Almanac.weekdayText(d)), "day numbers round-trip");
    }

    static void festivals() {
        check(Almanac.festivals(2026, 9, 25).contains("中秋节"), "2026 中秋 9-25");
        check(Almanac.festivals(2026, 2, 16).contains("除夕") && Almanac.festivals(2026, 2, 17).contains("春节"), "2026 除夕/春节");
        check(Almanac.festivals(2026, 5, 10).contains("母亲节") && Almanac.festivals(2026, 6, 21).contains("父亲节")
                && Almanac.festivals(2026, 11, 26).contains("感恩节"), "floating festivals");
        check(Almanac.festivals(2026, 4, 5).contains("清明节"), "清明节 is the term's day");
        check(Almanac.festivals(2025, 7, 31).isEmpty(), "闰六月初七 (2025-07-31) is not 七夕");
        check(Almanac.festivals(2025, 8, 29).contains("七夕节"), "2025 七夕 is 08-29, after the leap month");
        long today = Almanac.days(2026, 11, 6);
        check(Almanac.find("过年", today) == Almanac.days(2027, 2, 6), "过年 → 2027-02-06");
        check(Almanac.find("中秋", today) == Almanac.days(2027, 9, 15), "中秋 → 2027-09-15");
        check(Almanac.find("冬至", today) == Almanac.days(2026, 12, 22), "冬至 → 2026-12-22");
        check(Almanac.find("小年", today) == Almanac.days(2027, 1, 30), "小年 → 腊月廿三 2027-01-30");
        check(Almanac.find("不存在的节", today) == -1, "unknown name");
    }

    static void holidays() throws Exception {
        check("放假（国庆节）".equals(Almanac.status(2026, 10, 3)) && "调休上班（国庆节）".equals(Almanac.status(2026, 10, 10)), "2026 国庆");
        check("工作日".equals(Almanac.status(2026, 11, 6)) && "周末".equals(Almanac.status(2026, 11, 7)), "an ordinary Friday and Saturday");
        check("放假（中秋节）".equals(Almanac.status(2027, 9, 15)) && "工作日".equals(Almanac.status(2027, 9, 16)), "2027 中秋 one day");
        check(Almanac.scheduled(2027) && !Almanac.scheduled(2028), "schedules known through 2027");
        long today = Almanac.days(2026, 11, 6);
        JSONObject spring = Almanac.holiday("春节", today);
        check(spring != null && spring.getString("from").startsWith("2027-02-05") && spring.getString("to").startsWith("2027-02-13")
                && spring.getLong("days") == 9 && spring.getJSONArray("work_days").length() == 2, "2027 春节 block: " + spring);
        JSONObject national = Almanac.holiday("国庆", today);
        check(national != null && national.getString("from").startsWith("2027-10-01") && national.getLong("days") == 7, "the next 国庆 is 2027's");
        JSONObject during = Almanac.holiday("国庆", Almanac.days(2026, 10, 5));
        check(during != null && during.getString("from").startsWith("2026-10-01"), "during 国庆 it is this one");
        JSONObject combined = Almanac.holiday("中秋", Almanac.days(2025, 9, 1));
        check(combined != null && "国庆中秋".equals(combined.getString("name")) && combined.getLong("days") == 8, "2025 国庆中秋 together");
    }

    static void answers() {
        check("今天是农历九月廿八".equals(Almanac.lunarAnswer(2026, 11, 6)), "lunar answer");
        String term = Almanac.termAnswer(2026, 11, 6);
        check("今天不是节气。上一个是霜降（10 月 23 日），下一个是立冬，就是明天（11 月 7 日）".equals(term), "term answer: " + term);
        check(Almanac.termAnswer(2026, 11, 7).startsWith("今天就是立冬。下一个节气是小雪，11 月 22 日，还有 15 天"), "on the day: " + Almanac.termAnswer(2026, 11, 7));
        String fest = Almanac.festivalAnswer(2026, 11, 6);
        check("今天没有什么节日。下一个是感恩节，11 月 26 日，还有 20 天".equals(fest), "festival answer: " + fest);
        check("今天是国庆节，放假".equals(Almanac.festivalAnswer(2026, 10, 1)), "国庆 answer: " + Almanac.festivalAnswer(2026, 10, 1));
        check("明天立冬".equals(Almanac.hint(2026, 11, 6)) && "今天立冬".equals(Almanac.hint(2026, 11, 7)), "hint: a term");
        check("明天中秋节，放假".equals(Almanac.hint(2026, 9, 24)), "hint: a festival and a day off: " + Almanac.hint(2026, 9, 24));
        check("今天调休上班".equals(Almanac.hint(2026, 10, 10)), "hint: a moved working day: " + Almanac.hint(2026, 10, 10));
        check(Almanac.hint(2026, 11, 10) == null, "hint: an ordinary day says nothing");
        String env = Almanac.environment(2026, 11, 6);
        check("农历九月廿八（丙午马年）；明天立冬".equals(env), "environment: " + env);
        String eve = Almanac.environment(2026, 9, 30);
        check(eve.contains("明天国庆节") && eve.contains("明天放假（国庆节）"), "the day before 国庆: " + eve);
        check(Almanac.environment(2026, 10, 10).contains("今天调休上班（国庆节）"), "a moved working day: " + Almanac.environment(2026, 10, 10));
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 6, 3, 25, 0);
        check("今天是 11 月 6 日，星期五，农历九月廿八".equals(QuickCommand.clockText("date", c)), "date with 农历: " + QuickCommand.clockText("date", c));
        check("今天是农历九月廿八".equals(QuickCommand.clockText("lunar", c)), "clock lunar");
        for (String s : new String[]{"今天农历几号", "小爱同学，今天阴历几号", "农历几号"})
            check(q(s) != null && "lunar".equals(q(s).args.optString("kind")), s + " → lunar");
        for (String s : new String[]{"今天什么节气", "下一个节气是什么"})
            check(q(s) != null && "term".equals(q(s).args.optString("kind")), s + " → term");
        for (String s : new String[]{"今天是什么节日", "小爱同学，今天有什么节日"})
            check(q(s) != null && "festival".equals(q(s).args.optString("kind")), s + " → festival");
        // 「还有几天过年」 is the almanac's countdown since the overnight pass; anything with more to it still goes to the model
        check(q("还有几天过年") != null && "countdown".equals(q("还有几天过年").args.optString("kind")), "还有几天过年 → countdown");
        // 「明年春节放几天假」 is the schedule's own answer since 08:20 (quick); with more to it, the model and calendar
        check(q("明年春节放几天假") != null && "holiday".equals(q("明年春节放几天假").args.optString("kind")), "明年春节放几天假 → holiday");
        check(q("明年国庆放几天假的话出去玩合适吗") == null, "a question about a holiday with more to it goes to the model and calendar");
        // 「明天上班吗」 by the schedule
        check("明天国庆节放假，不用上班".equals(Almanac.workAnswer(2026, 9, 30, 1)), "the eve of 国庆: " + Almanac.workAnswer(2026, 9, 30, 1));
        check("明天要上班，是国庆节的调休（星期六）".equals(Almanac.workAnswer(2026, 10, 9, 1)), "a moved Saturday: " + Almanac.workAnswer(2026, 10, 9, 1));
        check("明天是星期六，不用上班".equals(Almanac.workAnswer(2026, 11, 6, 1)), "an ordinary Saturday: " + Almanac.workAnswer(2026, 11, 6, 1));
        check("今天是星期五，要上班".equals(Almanac.workAnswer(2026, 11, 6, 0)), "an ordinary Friday");
        check(Almanac.workAnswer(2028, 3, 1, 0).contains("2028 年的放假安排还没有数据"), "a year without a schedule says so");
        for (String s : new String[]{"明天上班吗", "明天要上班吗", "明天放假吗", "后天休息吗", "今天是工作日吗", "明天要不要上班", "小爱同学，明天用上班吗"})
            check(q(s) != null && "workday".equals(q(s).args.optString("kind")), s + " → workday");
        check(q("明天上班吗").args.optInt("in") == 1 && q("后天放假吗").args.optInt("in") == 2 && q("今天上班吗").args.optInt("in") == 0, "the day asked about");
        check(q("明天上班要穿什么") == null, "a longer question goes to the model");
        Calendar fri = Calendar.getInstance();
        fri.set(2026, Calendar.NOVEMBER, 6, 8, 0, 0);
        check("明天是星期六，不用上班".equals(QuickCommand.clockText("workday", 1, fri)), "through the clock");
    }

    private static QuickCommand q(String s) { return QuickCommand.match(s, Collections.<String>emptySet()); }

    static void tool() throws Exception {
        long today = Almanac.days(2026, 11, 6);
        JSONObject r = CalendarTools.look(new JSONObject().put("find", "过年"), today);
        String m = r.getString("message");
        check(r.getBoolean("ok") && m.contains("2027-02-06（星期六，农历丁未羊年正月初一），还有 92 天") && m.contains("共 9 天")
                && m.contains("调休上班：2027-01-31（星期日）、2027-02-14（星期日）"), "还有几天过年: " + m);
        r = CalendarTools.look(new JSONObject().put("date", "明天"), today);
        check(r.getString("message").contains("2026-11-07（星期六）是农历丙午马年九月廿九，立冬，周末，还有 1 天"), "明天: " + r.getString("message"));
        JSONObject todayInfo = CalendarTools.look(new JSONObject(), today).getJSONObject("today");
        check(todayInfo.getLong("day_of_year") == 310 && todayInfo.getLong("days_left_in_year") == 55, "11-06 is day 310, 55 left: " + todayInfo);
        r = CalendarTools.look(new JSONObject().put("date", "2026-02-30"), today);
        check(!r.getBoolean("ok"), "2 月 30 日 is refused");
        r = CalendarTools.look(new JSONObject().put("find", "国庆"), Almanac.days(2026, 10, 3));
        check(r.getString("message").contains("国庆是 2026-10-01") && r.getString("message").contains("已经过去 2 天")
                && r.getString("message").contains("放假 2026-10-01"), "during 国庆 it is this year's: " + r.getString("message"));
        r = CalendarTools.look(new JSONObject().put("find", "端午"), today);
        check(r.getString("message").contains("2027-06-09") && r.getString("message").contains("共 1 天；不调休"), "端午 2027: " + r.getString("message"));
        r = CalendarTools.look(new JSONObject(), today);
        check(r.getString("message").startsWith("下一个节日感恩节") && r.getString("message").contains("下一个法定假日元旦")
                && r.getString("message").contains("下一个节气立冬"), "nothing asked: the festival first: " + r.getString("message"));
        String next = Almanac.nextFestivalAnswer(2026, 11, 6);
        check("下一个节日是感恩节，11 月 26 日，还有 20 天；下一个法定假日是元旦，1 月 1 日起放 3 天".equals(next), "next festival: " + next);
        check(q("下一个节日是什么") != null && "nextfestival".equals(q("下一个节日是什么").args.optString("kind")), "下一个节日是什么 → quick");
        r = CalendarTools.look(new JSONObject().put("find", "2028年春节"), today);
        check(r.isNull("found"), "a name the calendar does not know");
        r = CalendarTools.look(new JSONObject().put("find", "中秋"), Almanac.days(2027, 10, 1));
        check(r.getString("message").contains("2028 年的放假安排还没有数据"), "2028 has no schedule yet: " + r.getString("message"));
        long t = CalendarTools.today(() -> 1793901444077L, TimeZone.getTimeZone("Asia/Shanghai"));
        int[] c = Almanac.civil(t);
        check(c[0] == 2026 && c[1] == 11 && c[2] == 6, "today from the clock");
    }
}
