package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** Checks for Schedules, ScheduleTools and the book of things 星仪 does later (job schedules). Every moment is built
 * with a Calendar in Asia/Shanghai, so nothing here depends on the machine's own zone or clock. */
final class ScheduleChecks {
    private ScheduleChecks() { }

    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    /** 2026-11-05 is a Thursday: the weekday maths below is read off that. */
    private static final long NOW = at(5, 20, 0);

    static void run() throws Exception {
        validation();
        whenItRuns();
        descriptions();
        duplicatesAndCap();
        dueAndFired();
        listing();
        cancelling();
        tools();
        loopRound();
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static void eq(String got, String want, String label) {
        check(want.equals(got), label + ": got [" + got + "] want [" + want + "]");
    }

    private static long at(int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static long atDay(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    /** The confirmation a booking speaks, or null when it was refused. */
    private static String conf(JSONObject args, long now) throws Exception {
        return Schedules.add(new JSONObject(), args, now, TZ);
    }

    /** The one item a booking put in a fresh book. */
    private static JSONObject one(JSONObject args, long now) throws Exception {
        JSONObject book = new JSONObject();
        Schedules.add(book, args, now, TZ);
        return book.getJSONArray("items").getJSONObject(0);
    }

    /** The Chinese reason a booking was refused, or null when it was accepted. */
    private static String why(JSONObject args, long now) throws Exception {
        try {
            Schedules.add(new JSONObject(), args, now, TZ);
            return null;
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    private static void refused(JSONObject args, String reason, String label) throws Exception {
        eq(why(args, NOW), reason, label);
    }

    /** Books one item into a book, always in the checks' own zone. */
    private static String add(JSONObject book, JSONObject args, long now) throws Exception {
        return Schedules.add(book, args, now, TZ);
    }

    private static JSONObject task(String what, String time) throws Exception {
        return AgentCoreTest.o("kind", "task", "what", what, "time", time);
    }

    private static String repeat(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('字');
        return b.toString();
    }

    // ---- what a booking refuses ------------------------------------------------------------------

    static void validation() throws Exception {
        refused(AgentCoreTest.o("what", "播放新闻联播", "time", "7:00"), "没说清是到点去做，还是到点提醒",
                "no kind refused");
        refused(AgentCoreTest.o("kind", "alarm", "what", "播放新闻联播", "time", "7:00"),
                "没说清是到点去做，还是到点提醒", "an unknown kind refused");
        refused(task("  ", "7:00"), "没说清到点要做什么", "a blank what refused");
        refused(task(repeat(61), "7:00"), "这件事太长了，说短一点", "61 characters refused");
        check(conf(task(repeat(60), "7:00"), NOW) != null, "60 characters still fit");

        refused(AgentCoreTest.o("kind", "task", "what", "关电视"), "要说清几点，或者多少分钟以后",
                "neither a time nor minutes refused");
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "in_minutes", 30),
                "要说清几点，或者多少分钟以后", "both a time and minutes refused");
        refused(task("关电视", "7"), "几点要写成 HH:mm，比如 07:00", "a time without minutes refused");
        refused(task("关电视", "七点"), "几点要写成 HH:mm，比如 07:00", "a Chinese numeral refused");
        refused(task("关电视", "24:00"), "没有这个时间：24:00", "hour 24 refused");
        refused(task("关电视", "7:60"), "没有这个时间：7:60", "minute 60 refused");
        check(conf(task("关电视", "7:00"), NOW) != null, "H:mm accepted");
        check(conf(task("关电视", "07:00"), NOW) != null, "HH:mm accepted");

        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "in_minutes", 30, "date", "2026-11-06"),
                "说了哪天，也要说几点", "a date with minutes refused");
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "date", "2026/11/06"),
                "哪天要写成 YYYY-MM-DD，比如 2026-11-08", "a date in the wrong shape refused");
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "date", "2026-11-04"),
                "这个日子已经过了", "yesterday refused");
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "date", "2027-12-11"),
                "最多只能约 400 天以内的事", "401 days ahead refused");
        check(conf(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "date", "2027-12-10"), NOW) != null,
                "400 days ahead still fits");
        // a festival months away (calendar → schedule_task): the year is said, so a wrong one would be heard
        String eve = conf(AgentCoreTest.o("kind", "remind", "what", "贴春联", "time", "9:00", "date", "2027-02-05"), NOW);
        check(eve != null && eve.contains("2027年2月5日"), "除夕 next year is said with its year: " + eve);
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "date", "2026-11-05"),
                "这个时间已经过了", "today at a time already gone refused");
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "date", "2026-11-06", "repeat", "daily"),
                "重复的预约不用指定哪天", "a date on a repeating item refused");

        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "in_minutes", 0), "分钟数要在 1 到 1440 之间",
                "0 minutes refused");
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "in_minutes", 1441), "分钟数要在 1 到 1440 之间",
                "1441 minutes refused");
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "in_minutes", "半小时"), "分钟数要在 1 到 1440 之间",
                "minutes that are not a number refused");
        check(conf(AgentCoreTest.o("kind", "task", "what", "关电视", "in_minutes", 1), NOW) != null, "1 minute accepted");
        check(conf(AgentCoreTest.o("kind", "task", "what", "关电视", "in_minutes", 1440), NOW) != null,
                "1440 minutes accepted");
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "in_minutes", 30, "repeat", "daily"),
                "多少分钟以后只能约一次", "minutes on a repeating item refused");
        refused(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "repeat", "weekly"),
                "重复方式只能是 once、daily、weekdays 或 weekends", "an unknown repeat refused");
        check(conf(task("  播放   新闻联播  ", "7:00"), NOW) != null, "a what with spaces is accepted");
        eq(one(task("  播放   新闻联播  ", "7:00"), NOW).getString("what"), "播放 新闻联播",
                "the what is trimmed and its inner spaces collapsed");
    }

    // ---- when it runs ------------------------------------------------------------------------------

    static void whenItRuns() throws Exception {
        check(one(task("播放新闻联播", "21:00"), NOW).getLong("at") == at(5, 21, 0), "a time still ahead today is today");
        check(one(task("播放新闻联播", "19:00"), NOW).getLong("at") == at(6, 19, 0), "a time already gone today is tomorrow");
        check(one(task("播放新闻联播", "20:00"), NOW).getLong("at") == at(6, 20, 0), "exactly now is tomorrow, not a minute ahead");
        check(one(task("播放新闻联播", "20:01"), NOW).getLong("at") == at(5, 20, 1), "one minute ahead is still today");
        check(one(task("播放新闻联播", "20:00"), NOW + 30_000).getLong("at") == at(6, 20, 0),
                "half a minute is not enough: tomorrow");
        check(one(task("播放新闻联播", "7:00"), at(5, 0, 0)).getLong("at") == at(5, 7, 0),
                "early this morning, 7:00 is still to come");

        JSONObject daily = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "repeat", "daily"), NOW);
        check(daily.getLong("at") == at(5, 23, 0), "daily today counts when the time is ahead");
        JSONObject dailyGone = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "19:00", "repeat", "daily"), NOW);
        check(dailyGone.getLong("at") == at(6, 19, 0), "daily moves to tomorrow once today's time has gone");

        JSONObject work = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "repeat", "weekdays"),
                at(6, 20, 0));
        check(work.getLong("at") == at(9, 7, 0), "weekdays said on a Friday evening is Monday morning");
        JSONObject workToday = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "repeat", "weekdays"), NOW);
        check(workToday.getLong("at") == at(5, 23, 0), "weekdays includes today when it is still a weekday and ahead");
        JSONObject sat = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "9:00", "repeat", "weekends"),
                at(9, 10, 0));
        check(sat.getLong("at") == at(14, 9, 0), "weekends said on a Monday is the coming Saturday");
        JSONObject satToday = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "9:00", "repeat", "weekends"),
                at(14, 8, 0));
        check(satToday.getLong("at") == at(14, 9, 0), "weekends said on a Saturday morning is today");
        JSONObject sunday = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "9:00", "repeat", "weekends"),
                at(14, 10, 0));
        check(sunday.getLong("at") == at(15, 9, 0), "and the next day when Saturday's time has gone");

        // 工作日 follow the State Council's schedule: no 国庆 days off, the moved Saturday in
        JSONObject beforeHoliday = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "repeat", "weekdays"),
                atDay(2026, 9, 30, 20, 0));
        check(beforeHoliday.getLong("at") == atDay(2026, 10, 8, 7, 0), "工作日 on the eve of 国庆 next fire after the holiday");
        JSONObject moved = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "repeat", "weekdays"),
                atDay(2026, 10, 9, 20, 0));
        check(moved.getLong("at") == atDay(2026, 10, 10, 7, 0), "the moved working Saturday counts");
        JSONObject spring = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "repeat", "weekdays"),
                atDay(2027, 2, 4, 20, 0));
        check(spring.getLong("at") == atDay(2027, 2, 14, 7, 0), "nine days of 春节 skipped, the moved Sunday kept");
        JSONObject dated = one(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "date", "2026-11-20"), NOW);
        check(dated.getLong("at") == at(20, 7, 0), "a date two weeks ahead is that date at that time");
        JSONObject mins = one(AgentCoreTest.o("kind", "task", "what", "暂停", "in_minutes", 30), NOW);
        check(mins.getLong("at") == NOW + 30 * 60_000L, "30 minutes later is 30 minutes later");
        eq(mins.getInt("hour") + ":" + mins.getInt("minute"), "20:30", "the item keeps the clock time it lands on");
    }

    // ---- how it reads ------------------------------------------------------------------------------

    static void descriptions() throws Exception {
        eq(conf(task("播放新闻联播", "21:00"), NOW), "好的，今天 21:00 播放新闻联播。", "today's confirmation");
        eq(conf(task("播放新闻联播", "19:00"), NOW), "好的，明天 19:00 播放新闻联播。", "tomorrow's confirmation");
        eq(conf(AgentCoreTest.o("kind", "task", "what", "播放新闻联播", "time", "8:00", "date", "2026-11-07"), NOW),
                "好的，后天 8:00 播放新闻联播。", "the day after tomorrow");
        eq(conf(AgentCoreTest.o("kind", "task", "what", "播放新闻联播", "time", "8:00", "date", "2026-11-08"), NOW),
                "好的，11月8日周日 8:00 播放新闻联播。", "a later date says the month, the day and the weekday");
        eq(conf(task("播放新闻联播", "7:00"), NOW), "好的，明天 7:00 播放新闻联播。", "the hour has no leading zero");
        eq(conf(task("播放新闻联播", "23:30"), NOW), "好的，今天 23:30 播放新闻联播。", "23:30 keeps both digits");

        eq(conf(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "repeat", "daily"), NOW),
                "好的，每天 23:00 关电视。", "a daily item says 每天");
        eq(conf(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "repeat", "weekdays"), NOW),
                "好的，工作日 23:00 关电视。", "a weekdays item says 工作日");
        eq(conf(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "9:00", "repeat", "weekends"), NOW),
                "好的，周末 9:00 关电视。", "a weekends item says 周末");
        eq(conf(AgentCoreTest.o("kind", "remind", "what", "该吃药了", "time", "21:00"), NOW),
                "好的，今天 21:00 提醒你：该吃药了。", "a reminder says 提醒你");
        eq(conf(AgentCoreTest.o("kind", "remind", "what", "该吃药了", "time", "8:00", "repeat", "daily"), NOW),
                "好的，每天 8:00 提醒你：该吃药了。", "a daily reminder says 每天 and 提醒你");
        eq(conf(AgentCoreTest.o("kind", "task", "what", "暂停", "in_minutes", 30), NOW),
                "好的，30 分钟后（20:30）暂停。", "minutes later read as a count and the clock time");
        eq(conf(AgentCoreTest.o("kind", "remind", "what", "该吃药了", "in_minutes", 45), NOW),
                "好的，45 分钟后（20:45）提醒你：该吃药了。", "and a reminder in minutes reads the same way");

        JSONObject item = one(AgentCoreTest.o("kind", "task", "what", "暂停", "in_minutes", 30), NOW);
        eq(Schedules.describe(item, NOW, TZ), "今天 20:30 暂停", "later the same item reads as a clock time");
        eq(Schedules.when(item, NOW, TZ), "今天 20:30", "the time half is there on its own for list_schedules");
        JSONObject book = new JSONObject();
        add(book, task("播放新闻联播", "19:00"), NOW);
        eq(Schedules.describe(book.getJSONArray("items").getJSONObject(0), NOW, TZ), "明天 19:00 播放新闻联播",
                "booked tonight it reads 明天");
        eq(Schedules.describe(book.getJSONArray("items").getJSONObject(0), NOW + 86_400_000L, TZ), "今天 19:00 播放新闻联播",
                "今天 and 明天 are relative to the moment asked, not to when it was booked");
    }

    // ---- booked twice, and a book that is full --------------------------------------------------

    static void duplicatesAndCap() throws Exception {
        JSONObject book = new JSONObject();
        add(book, task("播放新闻联播", "7:00"), NOW);
        String again = add(book, task("播放新闻联播", "7:00"), NOW);
        eq(again, "已经预约过了：明天 7:00 播放新闻联播", "the same booking twice says so instead of booking it again");
        check(book.getJSONArray("items").length() == 1, "and nothing was added");
        String dated = add(book,
                AgentCoreTest.o("kind", "task", "what", "播放新闻联播", "time", "07:00", "date", "2026-11-06"), NOW);
        check(dated.startsWith("已经预约过了：") && book.getJSONArray("items").length() == 1,
                "the same thing said with a date is still the same thing: " + dated);
        add(book, AgentCoreTest.o("kind", "remind", "what", "播放新闻联播", "time", "7:00"), NOW);
        add(book, task("播放新闻联播", "8:00"), NOW);
        add(book, AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "repeat", "daily"), NOW);
        check(book.getJSONArray("items").length() == 4, "a different kind, time, what or repeat is a different booking");
        eq(add(book, AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "repeat", "daily"), NOW),
                "已经预约过了：每天 7:00 关电视", "a repeating booking is not doubled either");
        check(book.getInt("next_id") == 5, "ids are handed out once each: " + book.getInt("next_id"));

        // the same thing on another day is another booking (lead review: 明天7点 and 后天7点 were taken as one)
        JSONObject days = new JSONObject();
        add(days, task("播放新闻联播", "7:00"), NOW);
        String later = add(days, AgentCoreTest.o("kind", "task", "what", "播放新闻联播", "time", "07:00", "date", "2026-11-07"), NOW);
        check(days.getJSONArray("items").length() == 2 && later.startsWith("好的，"),
                "明天 7:00 and 后天 7:00 are two bookings: " + later);

        JSONObject full = new JSONObject();
        for (int i = 1; i <= Schedules.MAX_ITEMS; i++)
            add(full, AgentCoreTest.o("kind", "task", "what", "事" + i, "in_minutes", i), NOW);
        check(full.getJSONArray("items").length() == Schedules.MAX_ITEMS, "20 bookings fit");
        String over = null;
        try {
            add(full, AgentCoreTest.o("kind", "task", "what", "第21件事", "in_minutes", 21), NOW);
        } catch (IllegalArgumentException refused) {
            over = refused.getMessage();
        }
        eq(over, "预约已经有 20 个了，先取消一些", "the 21st booking is refused with a way out");
        check(full.getJSONArray("items").length() == Schedules.MAX_ITEMS, "and the book stays at 20");
        String dup = add(full, AgentCoreTest.o("kind", "task", "what", "事1", "in_minutes", 1), NOW);
        check(dup.startsWith("已经预约过了："), "a duplicate is still answered when the book is full: " + dup);
    }

    // ---- the clock: what is due, what ran, what was missed ---------------------------------------

    static void dueAndFired() throws Exception {
        JSONObject book = new JSONObject();
        add(book, task("播放新闻联播", "19:00"), NOW);   // tomorrow 19:00, id 1
        add(book, task("关电视", "23:00"), NOW);         // today 23:00, id 2
        add(book, AgentCoreTest.o("kind", "remind", "what", "该吃药了", "time", "21:00"), NOW); // id 3
        check(Schedules.due(book, NOW).isEmpty(), "nothing is due the moment they are booked");

        List<JSONObject> due = Schedules.due(book, at(5, 23, 30));
        check(due.size() == 2 && due.get(0).getInt("id") == 3 && due.get(1).getInt("id") == 2,
                "two are due, oldest first: " + due.size());
        check(Schedules.due(book, at(5, 21, 0)).size() == 1, "the moment itself counts as due");

        Schedules.fired(book, 3, at(5, 21, 0), TZ);
        check(book.getJSONArray("items").length() == 2, "a once item leaves the book once it has run");
        check(Schedules.due(book, at(5, 23, 30)).size() == 1, "and is not due again");

        JSONObject daily = new JSONObject();
        add(daily, AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "repeat", "daily"), NOW);
        JSONObject item = daily.getJSONArray("items").getJSONObject(0);
        check(item.getLong("at") == at(5, 23, 0), "the daily item is due tonight");
        Schedules.fired(daily, item.getInt("id"), at(5, 23, 0), TZ);
        check(daily.getJSONArray("items").length() == 1, "a repeating item stays in the book");
        check(item.getLong("at") == at(6, 23, 0), "and moves to tomorrow, strictly after the moment it ran");
        Schedules.fired(daily, item.getInt("id"), at(9, 23, 0), TZ);
        check(item.getLong("at") == at(10, 23, 0), "fired late (the TV was busy for days) it still lands on the next day");

        JSONObject work = new JSONObject();
        add(work, AgentCoreTest.o("kind", "task", "what", "关电视", "time", "7:00", "repeat", "weekdays"),
                at(6, 20, 0));
        JSONObject friday = work.getJSONArray("items").getJSONObject(0);
        check(friday.getLong("at") == at(9, 7, 0), "booked on a Friday evening it is Monday");
        Schedules.fired(work, friday.getInt("id"), at(9, 7, 0), TZ);
        check(friday.getLong("at") == at(10, 7, 0), "Monday's run moves it to Tuesday");
        Schedules.fired(work, friday.getInt("id"), at(10, 7, 0), TZ);
        check(friday.getLong("at") == at(11, 7, 0), "Tuesday's to Wednesday");
        Schedules.fired(work, friday.getInt("id"), at(13, 7, 0), TZ);
        check(friday.getLong("at") == at(16, 7, 0), "Friday's run skips the weekend to Monday");

        JSONObject missed = new JSONObject();
        long early = at(5, 6, 0);
        add(missed, AgentCoreTest.o("kind", "task", "what", "播放新闻联播", "time", "7:00",
                "date", "2026-11-05"), early);                                              // id 1, this morning 07:00
        add(missed, AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "repeat", "daily"),
                early);                                                                     // id 2, tonight 23:00
        List<JSONObject> skipped = Schedules.skipMissed(missed, at(5, 20, 0), 10 * 60_000L, TZ);
        check(skipped.size() == 1 && skipped.get(0).getString("what").equals("播放新闻联播"),
                "one item is more than the grace period late: " + skipped.size());
        check(skipped.get(0).getLong("at") == at(5, 7, 0), "the skipped item is reported as it was");
        check(missed.getJSONArray("items").length() == 1, "a missed once item is dropped");
        JSONObject still = missed.getJSONArray("items").getJSONObject(0);
        check(still.getLong("at") == at(5, 23, 0), "tonight's item is not late yet");
        skipped = Schedules.skipMissed(missed, at(6, 12, 0), 10 * 60_000L, TZ);
        check(skipped.size() == 1 && still.getLong("at") == at(6, 23, 0),
                "a missed repeating item moves past now instead of running: " + still.getLong("at"));
        check(Schedules.skipMissed(missed, at(6, 12, 0), 10 * 60_000L, TZ).isEmpty(), "and it is not skipped twice");
    }

    // ---- listing and cancelling ------------------------------------------------------------------

    static void listing() throws Exception {
        eq(Schedules.list(new JSONObject(), NOW, TZ), "现在没有预约。", "an empty book says so");
        JSONObject book = new JSONObject();
        add(book, task("播放新闻联播", "7:00"), NOW);                                       // id 1, tomorrow 07:00
        add(book, AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00"), NOW); // id 2, tonight
        add(book, AgentCoreTest.o("kind", "remind", "what", "该吃药了", "time", "21:00"), NOW); // id 3
        eq(Schedules.list(book, NOW, TZ),
                "现在有 3 个预约：3 号，今天 21:00 提醒你：该吃药了；2 号，今天 23:00 关电视；1 号，明天 7:00 播放新闻联播。",
                "the list is in the order they will run, with their numbers");
        Schedules.cancel(book, "2", NOW, TZ);
        eq(Schedules.list(book, NOW, TZ), "现在有 2 个预约：3 号，今天 21:00 提醒你：该吃药了；1 号，明天 7:00 播放新闻联播。",
                "the count follows what is left");
    }

    static void cancelling() throws Exception {
        JSONObject book = new JSONObject();
        add(book, task("播放新闻联播", "7:00"), NOW);
        add(book, AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "repeat", "daily"), NOW);
        add(book, AgentCoreTest.o("kind", "remind", "what", "该吃药了", "time", "21:00"), NOW);

        List<String> gone = Schedules.cancel(book, "1", NOW, TZ);
        check(gone.size() == 1 && gone.get(0).equals("明天 7:00 播放新闻联播"), "cancelling by number: " + gone);
        check(book.getJSONArray("items").length() == 2, "and it is gone from the book");
        check(Schedules.cancel(book, "1", NOW, TZ).isEmpty(), "cancelling the same number again finds nothing");
        check(Schedules.cancel(book, "9", NOW, TZ).isEmpty(), "a number nobody has finds nothing");
        check(Schedules.cancel(book, "", NOW, TZ).isEmpty() && Schedules.cancel(book, null, NOW, TZ).isEmpty(),
                "an empty which cancels nothing");

        List<String> word = Schedules.cancel(book, "  吃药 ", NOW, TZ);
        check(word.size() == 1 && word.get(0).equals("今天 21:00 提醒你：该吃药了"),
                "a word out of what it says finds it: " + word);
        check(book.getJSONArray("items").length() == 1, "one left");
        check(Schedules.cancel(book, "新闻", NOW, TZ).isEmpty(), "a word nothing says finds nothing");
        List<String> rest = Schedules.cancel(book, "all", NOW, TZ);
        check(rest.size() == 1 && rest.get(0).equals("每天 23:00 关电视"), "all takes what is left: " + rest);
        check(book.getJSONArray("items").length() == 0, "the book is empty");
        check(Schedules.cancel(book, "all", NOW, TZ).isEmpty(), "all on an empty book cancels nothing");

        // the time words work too (TV 2026-11-05: 「把11月30日那个预约取消」 found nothing by the date)
        JSONObject times = new JSONObject();
        add(times, task("播放新闻联播", "7:00"), NOW);
        add(times, AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00", "repeat", "daily"), NOW);
        add(times, AgentCoreTest.o("kind", "task", "what", "播放新闻联播", "time", "07:00", "date", "2026-11-30"), NOW);
        List<String> tomorrow = Schedules.cancel(times, "明天", NOW, TZ);
        check(tomorrow.size() == 1 && tomorrow.get(0).equals("明天 7:00 播放新闻联播"), "a day word finds its booking: " + tomorrow);
        List<String> dated = Schedules.cancel(times, "11月30日", NOW, TZ);
        check(dated.size() == 1 && dated.get(0).startsWith("11月30日"), "a date finds its booking: " + dated);
        check(Schedules.cancel(times, "23:00", NOW, TZ).size() == 1 && times.getJSONArray("items").length() == 0,
                "and a time: the daily one goes");

        JSONObject two = new JSONObject();
        add(two, task("播放新闻联播", "7:00"), NOW);
        add(two, AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00"), NOW);
        List<String> all = Schedules.cancel(two, "全部", NOW, TZ);
        check(all.size() == 2, "全部 takes everything: " + all);
        JSONObject three = new JSONObject();
        add(three, task("播放新闻联播", "7:00"), NOW);
        add(three, task("播放新闻联播", "8:00"), NOW);
        check(Schedules.cancel(three, "所有", NOW, TZ).size() == 2, "所有 takes everything too");
        JSONObject many = new JSONObject();
        add(many, task("播放新闻联播", "7:00"), NOW);
        add(many, AgentCoreTest.o("kind", "remind", "what", "看新闻联播", "time", "8:00"), NOW);
        List<String> both = Schedules.cancel(many, "新闻联播", NOW, TZ);
        check(both.size() == 2, "one word can take two bookings at once: " + both);
    }

    // ---- the three tools ---------------------------------------------------------------------------

    /** The book file, in memory: load hands out a copy, so a tool that does not save really changes nothing. */
    static final class Book implements ScheduleTools.Store {
        JSONObject book = new JSONObject();
        int saves;
        Exception failure;
        boolean onSave;
        @Override public JSONObject load() throws Exception {
            if (failure != null && !onSave) throw failure;
            return new JSONObject(book.toString());
        }
        @Override public void save(JSONObject b) throws Exception {
            if (failure != null && onSave) throw failure;
            saves++;
            book = new JSONObject(b.toString());
        }
    }

    private static Tool tool(List<Tool> tools, String name) {
        for (Tool t : tools) if (t.name().equals(name)) return t;
        return null;
    }

    private static JSONObject news() throws Exception {
        return AgentCoreTest.o("kind", "task", "what", "播放新闻联播", "time", "7:00");
    }

    static void tools() throws Exception {
        Book store = new Book();
        List<Tool> tools = ScheduleTools.create(store, () -> NOW, TZ);
        check(tools.size() == 3 && tool(tools, "schedule_task") != null && tool(tools, "list_schedules") != null
                && tool(tools, "cancel_schedule") != null, "three tools: schedule_task, list_schedules, cancel_schedule");
        Tool schedule = tool(tools, "schedule_task"), list = tool(tools, "list_schedules"),
                cancel = tool(tools, "cancel_schedule");
        check(schedule.gate(new JSONObject(), null) == Tool.Gate.act(), "schedule_task acts");
        check(list.gate(new JSONObject(), null) == Tool.Gate.read(), "list_schedules only reads");
        check(cancel.gate(new JSONObject(), null) == Tool.Gate.act(), "cancel_schedule acts");
        eq(schedule.describe(AgentCoreTest.o("what", " 播放新闻联播 "), null), "预约：播放新闻联播",
                "schedule_task describes itself");
        eq(cancel.describe(AgentCoreTest.o("which", "新闻联播"), null), "取消预约：新闻联播", "cancel_schedule describes itself");

        JSONObject booked = schedule.run(news(), null);
        check(booked.getBoolean("ok") && booked.getString("status").equals("scheduled"), "schedule_task books it");
        eq(booked.getString("message"), "好的，明天 7:00 播放新闻联播。", "and says when in one sentence");
        check(booked.getInt("id") == 1 && booked.getLong("at") == at(6, 7, 0), "the id and the moment come back");
        check(store.saves == 1, "the book was written once");

        JSONObject dup = schedule.run(news(), null);
        check(dup.getBoolean("ok") && dup.getString("status").equals("exists"), "booking it again reports exists");
        eq(dup.getString("message"), "已经预约过了：明天 7:00 播放新闻联播", "and says so in Chinese");
        check(dup.getInt("id") == 1 && store.saves == 1, "a duplicate is not written to the file");

        JSONObject refused = schedule.run(AgentCoreTest.o("kind", "task", "what", "关电视"), null);
        check(!refused.getBoolean("ok") && refused.getString("status").equals("rejected"), "a booking with no time is refused");
        eq(refused.getString("message"), "要说清几点，或者多少分钟以后", "and the model is told why");
        check(store.saves == 1, "nothing refused was written");

        JSONObject shown = list.run(new JSONObject(), null);
        check(shown.getBoolean("ok") && shown.getString("status").equals("observed"), "list_schedules reads the book");
        eq(shown.getString("message"), "现在有 1 个预约：1 号，明天 7:00 播放新闻联播。", "and speaks the list");
        JSONObject row = shown.getJSONArray("items").getJSONObject(0);
        check(shown.getJSONArray("items").length() == 1 && row.getInt("id") == 1
                && row.getString("kind").equals("task") && row.getString("what").equals("播放新闻联播")
                && row.getString("when").equals("明天 7:00"), "each booking comes back as id, kind, what and when: " + row);
        check(store.saves == 1, "reading writes nothing");

        JSONObject dropped = cancel.run(AgentCoreTest.o("which", "1"), null);
        check(dropped.getBoolean("ok") && dropped.getString("status").equals("cancelled"), "cancel_schedule drops it");
        eq(dropped.getString("message"), "取消了：明天 7:00 播放新闻联播。", "and says what went");
        check(store.saves == 2, "the book was written again");
        JSONObject missing = cancel.run(AgentCoreTest.o("which", "1"), null);
        check(!missing.getBoolean("ok") && missing.getString("status").equals("not_found"), "cancelling nothing is not_found");
        eq(missing.getString("message"), "没有找到要取消的预约。现在没有预约。", "and says what is left instead");
        check(store.saves == 2, "nothing found, nothing written");

        store.failure = new java.io.IOException("磁盘满了");
        JSONObject broken = schedule.run(news(), null);
        check(!broken.getBoolean("ok") && broken.getString("status").equals("error"), "a broken file is reported, not thrown");
        check(broken.getString("message").startsWith("预约没存上：") && broken.getString("message").contains("磁盘满了"),
                "the error is speakable: " + broken.getString("message"));
        check(list.run(new JSONObject(), null).getString("status").equals("error"), "list_schedules reports it too");
        store.onSave = true;
        JSONObject unsaved = schedule.run(news(), null);
        check(!unsaved.getBoolean("ok") && unsaved.getString("status").equals("error"), "a failed write is reported as well");
        store.failure = null;
        store.onSave = false;
        JSONObject after = schedule.run(news(), null);
        check(after.getBoolean("ok") && store.saves == 3, "and it works again once the file is back");
        store.failure = new java.io.IOException("磁盘满了");
        store.onSave = true;
        check(cancel.run(AgentCoreTest.o("which", "all"), null).getString("status").equals("error"),
                "cancel_schedule reports a failed write instead of throwing");
        store.failure = null;
        store.onSave = false;
        JSONObject all = cancel.run(AgentCoreTest.o("which", "全部"), null);
        check(all.getBoolean("ok") && store.saves == 4 && store.book.getJSONArray("items").length() == 0,
                "and cancelling everything empties the book");

        Book ordered = new Book();
        List<Tool> more = ScheduleTools.create(ordered, () -> NOW, TZ);
        tool(more, "schedule_task").run(news(), null);
        tool(more, "schedule_task").run(AgentCoreTest.o("kind", "task", "what", "关电视", "time", "23:00"), null);
        JSONObject both = tool(more, "list_schedules").run(new JSONObject(), null);
        JSONArray rows = both.getJSONArray("items");
        check(rows.length() == 2 && rows.getJSONObject(0).getInt("id") == 2
                && rows.getJSONObject(0).getString("what").equals("关电视") && rows.getJSONObject(1).getInt("id") == 1,
                "list_schedules reports them in the order they will run: " + rows);
        eq(both.getString("message"), "现在有 2 个预约：2 号，今天 23:00 关电视；1 号，明天 7:00 播放新闻联播。",
                "and the spoken list is in that same order");
    }

    // ---- one round through the loop ----------------------------------------------------------------

    /** 「明天早上7点放新闻联播」: no plan, one model round, and the tool's own confirmation is the answer. */
    static void loopRound() throws Exception {
        Book store = new Book();
        List<Tool> tools = ScheduleTools.create(store, () -> NOW, TZ);
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls("schedule_task", news()).text("这一轮不该被问到");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, tools.toArray(new Tool[0]))
                .run(new AgentLoop.Request("明天早上7点放新闻联播", "test"));
        check(m.seen.size() == 1, "booking needs no plan and no second model round: " + m.seen.size());
        check(t.state == Ledger.State.ANSWERED, "the task is answered");
        // the time said as 点 (Speech.clocks, 2026-11-07)
        eq(t.reply, "好的，明天 7 点播放新闻联播。", "the confirmation is the whole answer");
        check(store.saves == 1 && store.book.getJSONArray("items").length() == 1, "and the booking is in the file");
    }
}
