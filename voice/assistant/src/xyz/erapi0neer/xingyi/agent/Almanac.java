package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/**
 * The Chinese calendar for the TV's questions: 农历几号, 什么节气, 什么节日, 放不放假, 还有几天过年. In 星仪接管模式 XiaoAi no
 * longer answers them and a model guesses lunar dates badly, so the answers come from data (AlmanacData, generated from
 * lunar_python by tests/agent/almanac_gen.py and checked day by day against it for 1950-2100: AlmanacVerify):
 * the lunar months of 1900-2100, the 24 solar terms of 1950-2100 in Beijing time, and the State Council's holiday
 * schedules through 2027 (published 2026-11-05). 2026-11-06 overnight pass.
 */
public final class Almanac {
    private Almanac() { }

    static final String[] TERMS = {"小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
            "小暑", "大暑", "立秋", "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"};
    private static final String[] MONTHS = {"正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"};
    private static final String STEMS = "甲乙丙丁戊己庚辛壬癸", BRANCHES = "子丑寅卯辰巳午未申酉戌亥", ANIMALS = "鼠牛虎兔龙蛇马羊猴鸡狗猪";
    private static final String DIGITS = "一二三四五六七八九十";
    private static final Map<Integer, String> LUNAR_DAYS = new HashMap<>(), SOLAR_DAYS = new HashMap<>();
    static {
        String[] lunar = {"101春节", "115元宵节", "202龙抬头", "505端午节", "707七夕节", "715中元节", "815中秋节", "909重阳节",
                "1208腊八节", "1223小年（北方）", "1224小年（南方）"};
        String[] solar = {"101元旦", "214情人节", "308妇女节", "312植树节", "401愚人节", "501劳动节", "504青年节", "601儿童节",
                "701建党节", "801建军节", "910教师节", "1001国庆节", "1224平安夜", "1225圣诞节"};
        for (String s : lunar) put(LUNAR_DAYS, s);
        for (String s : solar) put(SOLAR_DAYS, s);
    }

    private static void put(Map<Integer, String> m, String s) {
        int i = 0;
        while (Character.isDigit(s.charAt(i))) i++;
        m.put(Integer.parseInt(s.substring(0, i)), s.substring(i));
    }

    /** What people call a day by, for find: 过年 is 春节, 十一 is 国庆节. */
    private static final String[][] ALIASES = {{"过年", "春节"}, {"大年初一", "春节"}, {"新春", "春节"}, {"大年三十", "除夕"},
            {"年三十", "除夕"}, {"五一", "劳动节"}, {"十一", "国庆节"}, {"国庆", "国庆节"}, {"六一", "儿童节"}, {"三八", "妇女节"},
            {"七一", "建党节"}, {"八一", "建军节"}, {"圣诞", "圣诞节"}, {"元宵", "元宵节"}, {"端午", "端午节"}, {"中秋", "中秋节"},
            {"重阳", "重阳节"}, {"七夕", "七夕节"}, {"腊八", "腊八节"}, {"元旦节", "元旦"}, {"新年", "元旦"}};

    // ---- days ------------------------------------------------------------------------------------

    /** Days since 1970-01-01 of a Gregorian date. */
    static long days(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** The Gregorian date {y, m, d} of a day number. */
    static int[] civil(long z) {
        z += 719468;
        long era = (z >= 0 ? z : z - 146096) / 146097;
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        int d = (int) (doy - (153 * mp + 2) / 5 + 1), m = (int) (mp < 10 ? mp + 3 : mp - 9);
        return new int[]{(int) (yoe + era * 400 + (m <= 2 ? 1 : 0)), m, d};
    }

    /** 0 = Sunday … 6 = Saturday. */
    static int weekday(long day) { return (int) Math.floorMod(day + 4, 7L); }

    static String weekdayText(long day) { return "星期" + "日一二三四五六".charAt(weekday(day)); }

    // ---- the lunar calendar ----------------------------------------------------------------------

    private static final long LUNAR_BASE = days(1900, 1, 31); // 1900 正月初一

    /** A day in the lunar calendar. */
    public static final class Lunar {
        public final int year, month, day, monthDays;
        public final boolean leap;
        Lunar(int year, int month, int day, boolean leap, int monthDays) {
            this.year = year; this.month = month; this.day = day; this.leap = leap; this.monthDays = monthDays;
        }
        /** 「九月廿八」, 「闰六月初一」, 「腊月三十」. */
        public String text() { return (leap ? "闰" : "") + MONTHS[month - 1] + "月" + dayText(day); }
        /** 「丙午马年」: the year by its stem and branch, and its animal (it changes at 春节). */
        public String yearText() {
            int i = Math.floorMod(year - 4, 60);
            return "" + STEMS.charAt(i % 10) + BRANCHES.charAt(i % 12) + ANIMALS.charAt(i % 12) + "年";
        }
    }

    static String dayText(int d) {
        if (d == 10) return "初十";
        if (d == 20) return "二十";
        if (d == 30) return "三十";
        String unit = String.valueOf(DIGITS.charAt((d - 1) % 10));
        return (d < 10 ? "初" : d < 20 ? "十" : "廿") + unit;
    }

    private static int months(int info) { return ((info >> 13) & 0xF) == 0 ? 12 : 13; }

    private static int yearDays(int info) {
        int n = 0;
        for (int i = 0, c = months(info); i < c; i++) n += (info >> i & 1) == 1 ? 30 : 29;
        return n;
    }

    /** The lunar date of a Gregorian one, 1900-01-31 to 2100-12-31; null outside. */
    public static Lunar lunar(int y, int m, int d) { return lunar(days(y, m, d)); }

    static Lunar lunar(long day) {
        long off = day - LUNAR_BASE;
        if (off < 0) return null;
        int year = 1900;
        while (true) {
            if (year - 1900 >= AlmanacData.LUNAR.length) return null;
            int len = yearDays(AlmanacData.LUNAR[year - 1900]);
            if (off < len) break;
            off -= len;
            year++;
        }
        int info = AlmanacData.LUNAR[year - 1900], leap = (info >> 13) & 0xF;
        for (int i = 0, c = months(info); i < c; i++) {
            int len = (info >> i & 1) == 1 ? 30 : 29;
            if (off < len) {
                if (leap == 0 || i < leap) return new Lunar(year, i + 1, (int) off + 1, false, len);
                if (i == leap) return new Lunar(year, leap, (int) off + 1, true, len);
                return new Lunar(year, i, (int) off + 1, false, len);
            }
            off -= len;
        }
        return null;
    }

    // ---- solar terms -----------------------------------------------------------------------------

    /** The day of the month of term i (0 = 小寒 … 23 = 冬至) in year y, or 0 when y is outside the table. */
    static int termDay(int y, int i) {
        int row = y - AlmanacData.TERMS_FROM;
        if (row < 0 || row >= AlmanacData.TERMS.length) return 0;
        String s = AlmanacData.TERMS[row];
        return Integer.parseInt(s.substring(i * 2, i * 2 + 2));
    }

    /** The term that falls on this day, or null. */
    public static String term(int y, int m, int d) {
        for (int i = (m - 1) * 2; i < m * 2; i++) if (termDay(y, i) == d) return TERMS[i];
        return null;
    }

    /** The day number of the first term on or after day (after when strictly), with its name: {day, index}. */
    static long[] nextTerm(long day, boolean strictly) {
        int[] c = civil(day);
        for (int y = c[0]; y <= c[0] + 1; y++)
            for (int i = 0; i < 24; i++) {
                int td = termDay(y, i);
                if (td == 0) return null;
                long t = days(y, i / 2 + 1, td);
                if (strictly ? t > day : t >= day) return new long[]{t, i};
            }
        return null;
    }

    /** The day number and index of the last term before day (on it when inclusive). */
    static long[] lastTerm(long day, boolean inclusive) {
        int[] c = civil(day);
        for (int y = c[0]; y >= c[0] - 1; y--)
            for (int i = 23; i >= 0; i--) {
                int td = termDay(y, i);
                if (td == 0) return null;
                long t = days(y, i / 2 + 1, td);
                if (inclusive ? t <= day : t < day) return new long[]{t, i};
            }
        return null;
    }

    // ---- festivals ----------------------------------------------------------------------------------

    /** The festivals of a day: lunar ones (not in a leap month), 除夕, the fixed solar ones, 母亲节/父亲节/感恩节, 清明节. */
    public static List<String> festivals(int y, int m, int d) { return festivals(days(y, m, d)); }

    static List<String> festivals(long day) {
        int[] c = civil(day);
        List<String> out = new ArrayList<>();
        Lunar l = lunar(day);
        if (l != null && !l.leap) {
            String f = LUNAR_DAYS.get(l.month * 100 + l.day);
            if (f != null) out.add(f);
            if (l.month == 12 && l.day == l.monthDays) out.add("除夕");
        }
        String s = SOLAR_DAYS.get(c[1] * 100 + c[2]);
        if (s != null) out.add(s);
        int wd = weekday(day), nth = (c[2] - 1) / 7 + 1;
        if (c[1] == 5 && wd == 0 && nth == 2) out.add("母亲节");
        if (c[1] == 6 && wd == 0 && nth == 3) out.add("父亲节");
        if (c[1] == 11 && wd == 4 && nth == 4) out.add("感恩节");
        if ("清明".equals(term(c[0], c[1], c[2]))) out.add("清明节");
        return out;
    }

    // ---- holidays -----------------------------------------------------------------------------------

    private static final Map<Long, String[]> HOLIDAY = new HashMap<>();
    private static final Set<Integer> SCHEDULED = new HashSet<>();
    static {
        for (String h : AlmanacData.HOLIDAYS) {
            int y = Integer.parseInt(h.substring(0, 4));
            // the schedules' 「元旦节」 is said 「元旦」
            String name = h.substring(9).equals("元旦节") ? "元旦" : h.substring(9);
            HOLIDAY.put(days(y, Integer.parseInt(h.substring(4, 6)), Integer.parseInt(h.substring(6, 8))),
                    new String[]{h.substring(8, 9), name});
            SCHEDULED.add(y);
        }
    }

    /** Whether the State Council's schedule for year y is known. */
    public static boolean scheduled(int y) { return SCHEDULED.contains(y); }

    /** 「放假（国庆节）」, 「调休上班（国庆节）」, 「周末」 or 「工作日」 (the last two without a schedule are only the weekday). */
    public static String status(int y, int m, int d) { return status(days(y, m, d)); }

    static String status(long day) {
        String[] h = HOLIDAY.get(day);
        if (h != null) return ("休".equals(h[0]) ? "放假（" : "调休上班（") + h[1] + "）";
        int wd = weekday(day);
        return wd == 0 || wd == 6 ? "周末" : "工作日";
    }

    /** Whether the status is out of the ordinary (a holiday or a moved working day). */
    static boolean notable(String status) { return status.startsWith("放假") || status.startsWith("调休"); }

    /** The holiday block named like name around or after day (the year's schedule): {name, from, to, days, work}. */
    static JSONObject holiday(String name, long from) throws JSONException {
        String n = base(name);
        if (n.isEmpty()) return null;
        long first = -1, last = -1;
        String called = null;
        List<Long> sorted = new ArrayList<>(HOLIDAY.keySet());
        Collections.sort(sorted);
        for (long day : sorted) {
            String[] h = HOLIDAY.get(day);
            if (!"休".equals(h[0]) || !matches(h[1], n)) continue;
            if (first < 0) {
                if (day < from - 10) continue;     // a block already over (a week's grace: 「国庆放了几天」 on the 5th)
                first = day; last = day; called = h[1];
            } else if (day == last + 1 && h[1].equals(called)) last = day;
            else break;
        }
        if (first < 0) return null;
        JSONArray work = new JSONArray();
        for (long day : sorted) {
            String[] h = HOLIDAY.get(day);
            if ("班".equals(h[0]) && h[1].equals(called) && Math.abs(day - first) < 30) work.put(date(day) + "（" + weekdayText(day) + "）");
        }
        return new JSONObject().put("name", called).put("from", date(first) + "（" + weekdayText(first) + "）")
                .put("to", date(last) + "（" + weekdayText(last) + "）").put("days", last - first + 1).put("work_days", work)
                .put("first_day", first);
    }

    // ---- finding a day by name ----------------------------------------------------------------------

    /** 「中秋」→「中秋节」, 「过年」→「春节」; the name a day is matched by. */
    static String base(String name) {
        String n = name == null ? "" : name.replaceAll("[\\s　，。？！?!的]", "");
        for (String[] a : ALIASES) if (n.equals(a[0])) return a[1];
        return n;
    }

    private static boolean matches(String called, String n) {
        if (called.equals(n) || called.startsWith(n)) return true;
        String c = called.endsWith("节") ? called.substring(0, called.length() - 1) : called;
        String m = n.endsWith("节") ? n.substring(0, n.length() - 1) : n;
        return !m.isEmpty() && (c.equals(m) || c.contains(m) && m.length() >= 2);
    }

    /** The first day on or after from (within 400 days) that is the named festival or term, or -1. */
    static long find(String name, long from) {
        String n = base(name);
        if (n.isEmpty()) return -1;
        for (long day = from; day < from + 400; day++) {
            for (String f : festivals(day)) if (matches(f, n)) return day;
            int[] c = civil(day);
            String t = term(c[0], c[1], c[2]);
            if (t != null && matches(t, n)) return day;
        }
        return -1;
    }

    // ---- what the model and the user are told -------------------------------------------------------

    static String date(long day) {
        int[] c = civil(day);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c[0], c[1], c[2]);
    }

    static String spokenDate(long day) {
        int[] c = civil(day);
        return c[1] + " 月 " + c[2] + " 日";
    }

    /** 「今天」「明天」「后天」 or 「11 月 26 日，还有 20 天」. */
    static String when(long day, long today) {
        long n = day - today;
        if (n == 0) return "就是今天";
        if (n == 1) return "就是明天（" + spokenDate(day) + "）";
        if (n == 2) return "就是后天（" + spokenDate(day) + "）";
        return spokenDate(day) + "，还有 " + n + " 天";
    }

    /** Everything about one day, for the calendar tool. */
    static JSONObject describe(long day, long today) throws JSONException {
        int[] c = civil(day);
        JSONObject o = new JSONObject().put("date", date(day)).put("weekday", weekdayText(day));
        // 「今天是今年第几天」「今年还剩几天」: arithmetic a model gets wrong
        long jan1 = days(c[0], 1, 1), next = days(c[0] + 1, 1, 1);
        o.put("day_of_year", day - jan1 + 1).put("days_left_in_year", next - day - 1);
        Lunar l = lunar(day);
        if (l != null) o.put("lunar", "农历" + l.yearText() + l.text());
        String t = term(c[0], c[1], c[2]);
        if (t != null) o.put("term", t);
        List<String> f = festivals(day);
        if (!f.isEmpty()) o.put("festivals", new JSONArray(f));
        o.put("status", status(day));
        if (!scheduled(c[0])) o.put("schedule_note", c[0] + " 年的放假安排还没有数据：法定假日和调休按不知道说，别猜");
        if (day != today) o.put("days_from_today", day - today);
        return o;
    }

    /** The line for environment.calendar: the lunar date, a term or festival today or tomorrow, a holiday or a moved
     * working day today or tomorrow. 「农历九月廿八（丙午马年）；明天立冬」. */
    public static String environment(int y, int m, int d) {
        long today = days(y, m, d);
        Lunar l = lunar(today);
        if (l == null) return null;
        StringBuilder s = new StringBuilder("农历").append(l.text()).append("（").append(l.yearText()).append("）");
        String onToday = marks(today), onTomorrow = marks(today + 1);
        if (!onToday.isEmpty()) s.append("；今天").append(onToday);
        else if (!onTomorrow.isEmpty()) s.append("；明天").append(onTomorrow);
        String st = status(today), sn = status(today + 1);
        if (notable(st)) s.append("；今天").append(st);
        if (notable(sn) && !sn.equals(st)) s.append("；明天").append(sn);
        return s.toString();
    }

    /**
     * The home screen's line under the date (Orrery 「轴」): 「农历九月三十」, and after a full-width dot the one thing
     * the day is called by — its solar term first, else the first festival the calendar knows —
     * 「农历九月廿九　·　立冬」「农历正月初一　·　春节」. "" for a year the lunar table does not cover.
     */
    public static String homeLine(int y, int m, int d) {
        long today = days(y, m, d);
        Lunar l = lunar(today);
        if (l == null) return "";
        String mark = term(y, m, d);
        if (mark == null) {
            List<String> f = festivals(today);
            if (!f.isEmpty()) mark = f.get(0);
        }
        return "农历" + l.text() + (mark == null ? "" : "　·　" + mark);
    }

    /** One line for the hint card when the screen comes on, or null on an ordinary day: today's festival or term and
     * whether it is a day off or a moved working day, else tomorrow's. 「今天立冬」「明天中秋节，放假」「今天调休上班」. */
    public static String hint(int y, int m, int d) {
        long today = days(y, m, d);
        if (lunar(today) == null) return null;
        for (long day = today; day <= today + 1; day++) {
            String marks = marks(day), st = status(day);
            boolean off = st.startsWith("放假"), moved = st.startsWith("调休");
            if (marks.isEmpty() && !off && !moved) continue;
            String when = day == today ? "今天" : "明天";
            if (marks.isEmpty()) return when + (off ? st : "调休上班");
            return when + marks + (off ? "，放假" : moved ? "，要调休上班" : "");
        }
        return null;
    }

    private static String marks(long day) {
        int[] c = civil(day);
        List<String> all = new ArrayList<>(festivals(day));
        String t = term(c[0], c[1], c[2]);
        if (t != null && !all.contains(t + "节")) all.add(t);
        return String.join("、", all);
    }

    /** The spoken answer for 「今天农历几号」. */
    public static String lunarAnswer(int y, int m, int d) {
        Lunar l = lunar(y, m, d);
        return l == null ? null : "今天是农历" + l.text();
    }

    /** The spoken answer for 「明天上班吗」: in days ahead (0-2), by the State Council's schedule where it is known. */
    public static String workAnswer(int y, int m, int d, int in) {
        long day = days(y, m, d) + in;
        int[] c = civil(day);
        String when = in == 0 ? "今天" : in == 1 ? "明天" : "后天";
        String week = weekdayText(day), st = status(day);
        if (st.startsWith("放假")) return when + st.substring(3, st.length() - 1) + "放假，不用上班";
        if (st.startsWith("调休")) return when + "要上班，是" + st.substring(5, st.length() - 1) + "的调休（" + week + "）";
        String unknown = scheduled(c[0]) ? "" : "（" + c[0] + " 年的放假安排还没有数据，按平常算）";
        return st.equals("周末") ? when + "是" + week + "，不用上班" + unknown : when + "是" + week + "，要上班" + unknown;
    }

    /** The spoken answer for 「今天什么节气」. */
    public static String termAnswer(int y, int m, int d) {
        long today = days(y, m, d);
        String t = term(y, m, d);
        long[] next = nextTerm(today, true), last = lastTerm(today, false);
        if (next == null || last == null) return null;
        if (t != null) return "今天就是" + t + "。下一个节气是" + TERMS[(int) next[1]] + "，" + when(next[0], today);
        return "今天不是节气。上一个是" + TERMS[(int) last[1]] + "（" + spokenDate(last[0]) + "），下一个是"
                + TERMS[(int) next[1]] + "，" + when(next[0], today);
    }

    /** The spoken answer for 「下一个节日是什么」: the next festival and the next days off. */
    public static String nextFestivalAnswer(int y, int m, int d) {
        long today = days(y, m, d);
        StringBuilder s = new StringBuilder();
        for (long day = today + 1; day < today + 400; day++) {
            List<String> g = festivals(day);
            if (!g.isEmpty()) { s.append("下一个节日是").append(String.join("、", g)).append("，").append(when(day, today)); break; }
        }
        for (long day = today + 1; day < today + 400; day++) {
            String st = status(day);
            if (!st.startsWith("放假")) continue;
            try {
                JSONObject h = holiday(st.substring(3, st.length() - 1), day);
                if (h != null) s.append(s.length() == 0 ? "" : "；").append("下一个法定假日是").append(h.getString("name"))
                        .append("，").append(spokenDate(day)).append("起放 ").append(h.getLong("days")).append(" 天");
            } catch (JSONException ignored) { }
            break;
        }
        return s.length() == 0 ? null : s.toString();
    }

    /** Whether name is a festival or a term the almanac knows (「过年」「国庆」「冬至」), for the quick countdown. */
    public static boolean knows(String name) {
        String n = base(name);
        return n.length() >= 2 && find(n, days(2026, 1, 1)) >= 0;
    }

    /** The festivals that are days off by law, for 「…的放假安排还没发布」. */
    private static final Set<String> LEGAL = new HashSet<>(Arrays.asList("春节", "清明节", "清明", "劳动节", "端午节", "中秋节", "国庆节", "元旦"));

    /**
     * The spoken answer for 「还有几天过年」「离国庆还有多少天」「冬至还有几天」 (2026-11-06 overnight pass: two model rounds
     * before): the day, how far, its lunar date, and its days off when the State Council gave them —
     * 「离春节还有 92 天（2027 年 2 月 6 日，星期六，农历正月初一）；2 月 5 日到 13 日放假，共 9 天」.
     */
    public static String countdownAnswer(int y, int m, int d, String name) {
        long today = days(y, m, d);
        String n = base(name);
        long day = find(n, today);
        if (day < 0) return null;
        String called = n;
        int[] c = civil(day);
        for (String f : festivals(day)) if (matches(f, n)) { called = f; break; }
        String t = term(c[0], c[1], c[2]);
        if (called.equals(n) && t != null && matches(t, n)) called = t;
        Lunar l = lunar(day);
        String about = "（" + (c[0] != y ? c[0] + " 年 " : "") + spokenDate(day) + "，" + weekdayText(day)
                + (l == null ? "" : "，农历" + l.text()) + "）";
        long n2 = day - today;
        StringBuilder s = new StringBuilder(n2 == 0 ? "今天就是" + called : n2 == 1 ? called + "就是明天" + about
                : n2 == 2 ? called + "就是后天" + about : "离" + called + "还有 " + n2 + " 天" + about);
        try {
            JSONObject h = holiday(n, today);
            if (h != null && Math.abs(h.getLong("first_day") - day) <= 10) s.append("；").append(daysOff(h));
            else if (LEGAL.contains(called) && !scheduled(c[0])) s.append("；").append(c[0]).append(" 年的放假安排还没发布");
        } catch (JSONException ignored) { }
        return s.toString();
    }

    /** 「2 月 5 日到 13 日放假，共 9 天，2 月 14 日（星期日）调休上班」. */
    private static String daysOff(JSONObject h) throws JSONException {
        long first = h.getLong("first_day"), last = first + h.getLong("days") - 1;
        int[] a = civil(first), b = civil(last);
        StringBuilder s = new StringBuilder(first == last ? spokenDate(first) + "放假 1 天"
                : spokenDate(first) + "到 " + (a[1] == b[1] ? b[2] + " 日" : spokenDate(last)) + "放假，共 " + h.getLong("days") + " 天");
        JSONArray work = h.optJSONArray("work_days");
        if (work != null && work.length() > 0) {
            List<String> w = new ArrayList<>();
            for (int i = 0; i < work.length(); i++) {
                String iso = work.getString(i); // 「2027-02-14（星期日）」
                w.add(Integer.parseInt(iso.substring(5, 7)) + " 月 " + Integer.parseInt(iso.substring(8, 10)) + " 日" + iso.substring(10));
            }
            s.append("，").append(String.join("、", w)).append("调休上班");
        }
        return s.toString();
    }

    /**
     * The spoken answer for 「还有几天放假」「下个假期是什么时候」: the next block of days off by law —
     * 「下一个假期是元旦：1 月 1 日到 3 日放假，共 3 天，还有 56 天」; in the middle of one, when it ends. Null when the
     * schedule has no next block (the model says so).
     */
    public static String nextHolidayAnswer(int y, int m, int d) {
        long today = days(y, m, d);
        try {
            String st = status(today);
            if (st.startsWith("放假")) {
                JSONObject h = holiday(st.substring(3, st.length() - 1), today);
                if (h != null) {
                    long last = h.getLong("first_day") + h.getLong("days") - 1;
                    return "现在正放" + h.getString("name") + "假，" + (last == today ? "今天是最后一天" : spokenDate(last) + "结束，还有 " + (last - today) + " 天");
                }
            }
            for (long day = today + 1; day < today + 400; day++) {
                String s = status(day);
                if (!s.startsWith("放假")) continue;
                JSONObject h = holiday(s.substring(3, s.length() - 1), day);
                if (h == null) return null;
                long n = day - today;
                int[] c = civil(day);
                return "下一个假期是" + h.getString("name") + "：" + (c[0] != y ? c[0] + " 年 " : "") + daysOff(h)
                        + "，" + (n == 1 ? "明天就开始" : "还有 " + n + " 天");
            }
        } catch (JSONException ignored) { }
        return null;
    }

    /** 「今天是 2026 年的第 310 天，今年还剩 55 天」, or with left the days left first (2026-11-06: the model, asked
     * this, wrote 「农历九月廿28」 beside the right numbers). */
    public static String yearDayAnswer(int y, int m, int d, boolean left) {
        long day = days(y, m, d), n = day - days(y, 1, 1) + 1, rest = days(y + 1, 1, 1) - day - 1;
        return left ? "今年还剩 " + rest + " 天（今天是第 " + n + " 天）" : "今天是 " + y + " 年的第 " + n + " 天，今年还剩 " + rest + " 天";
    }

    /** Whether name is a festival that is a day off by law (春节, 国庆, 五一…). */
    public static boolean legal(String name) {
        String n = base(name);
        if (LEGAL.contains(n)) return true;
        for (String l : LEGAL) if (matches(l, n)) return true;
        return false;
    }

    /**
     * The spoken answer for 「明年清明放几天假」「国庆放几天」「春节放假安排」: the State Council's block for that year
     * (今年 / 明年 / 去年, or the next one when no year is said) — 「2027 年清明节：4 月 3 日到 5 日放假，共 3 天，还有 148
     * 天」 — or that the year's schedule is not out yet. Null for anything else (the model takes it).
     */
    public static String holidayAnswer(int y, int m, int d, String name, String when) {
        if (!legal(name)) return null;
        long today = days(y, m, d);
        String n = base(name), w = when == null ? "" : when;
        int year = "明年".equals(w) ? y + 1 : "去年".equals(w) ? y - 1 : "今年".equals(w) ? y : 0;
        long from = year == 0 ? today : days(year, 1, 1);
        try {
            JSONObject h = holiday(n, from);
            int found = h == null ? 0 : civil(h.getLong("first_day"))[0];
            if (h == null || (year != 0 && found != year)) {
                int which = year != 0 ? year : civil(Math.max(find(n, today), today))[0];
                return scheduled(which) ? null : which + " 年的放假安排还没发布";
            }
            long first = h.getLong("first_day"), last = first + h.getLong("days") - 1;
            String tail = last < today ? "（已经过了）" : first > today ? "，还有 " + (first - today) + " 天" : "（正在放）";
            return (found == y ? "今年" : found + " 年") + h.getString("name") + "：" + daysOff(h) + tail;
        } catch (JSONException e) {
            return null;
        }
    }

    /** The spoken answer for 「今天是什么节日」. */
    public static String festivalAnswer(int y, int m, int d) {
        long today = days(y, m, d);
        List<String> f = festivals(today);
        String st = status(today);
        if (!f.isEmpty()) return "今天是" + String.join("、", f) + (st.startsWith("放假") ? "，放假" : "");
        for (long day = today + 1; day < today + 400; day++) {
            List<String> g = festivals(day);
            if (!g.isEmpty()) return "今天没有什么节日。下一个是" + String.join("、", g) + "，" + when(day, today);
        }
        return null;
    }
}
