package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「今天宜什么忌什么」「明天适合搬家吗」「下个月哪天适合结婚」 (2026-11-07): the almanac's 宜 and 忌 by day, from Baidu's
 * calendar card (its data is 51wnl's; GBK; three months around the one asked for in one answer). The calendar tool
 * (Almanac) has the lunar date, terms and holidays offline; 宜忌 follow tables too long to carry.
 */
public final class Huangli {
    private Huangli() { }

    /** The months around year-month (the card answers the one before and the one after too). */
    public static String url(int year, int month) {
        try {
            return "https://opendata.baidu.com/api.php?tn=wisetpl&format=json&resource_id=39043&query="
                    + java.net.URLEncoder.encode(year + "年" + month + "月", "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Day {
        public final int year, month, day;
        public final String lunar, ganzhi, term;
        public final List<String> good, bad;

        Day(int year, int month, int day, String lunar, String ganzhi, String term, List<String> good, List<String> bad) {
            this.year = year; this.month = month; this.day = day; this.lunar = lunar; this.ganzhi = ganzhi; this.term = term;
            this.good = good; this.bad = bad;
        }
    }

    private static List<String> items(String s) {
        List<String> out = new ArrayList<>();
        for (String x : (s == null ? "" : s).split("[.、，,\\s]+")) if (!x.trim().isEmpty() && !out.contains(x.trim())) out.add(x.trim());
        return out;
    }

    public static List<Day> days(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!"0".equals(o.optString("status"))) throw new JSONException("黄历没有返回结果");
        JSONArray data = o.optJSONArray("data");
        JSONArray a = data == null || data.length() == 0 ? null : data.getJSONObject(0).optJSONArray("almanac");
        List<Day> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject d = a.getJSONObject(i);
            String lunar = "农历" + (News.str(d, "lMonth").isEmpty() ? "" : News.str(d, "lMonth") + "月") + News.str(d, "lDate");
            out.add(new Day(d.optInt("year"), d.optInt("month"), d.optInt("day"), lunar, News.str(d, "gzDate"), News.str(d, "term"),
                    items(News.str(d, "suit")), items(News.str(d, "avoid"))));
        }
        return out;
    }

    public static Day on(List<Day> days, int y, int m, int d) {
        for (Day x : days) if (x.year == y && x.month == m && x.day == d) return x;
        return null;
    }

    /** What people say for what the almanac writes: 搬家 is also 入宅, 移徙, 搬新房; 结婚 is 嫁娶. */
    private static final String[][] SAME = {{"搬家", "搬新房", "入宅", "移徙", "入伙"}, {"结婚", "嫁娶"}, {"订婚", "订盟", "纳采", "合婚订婚"},
            {"开业", "开市", "开张", "立券", "交易"}, {"装修", "动土", "修造", "破土", "竖柱", "上梁"}, {"出行", "旅游", "出门"},
            {"理发", "剃头", "整容"}, {"安床", "买床"}, {"签约", "签合同", "立券"}, {"祭祀", "祭拜", "上坟", "扫墓"}, {"安葬", "入殓", "下葬"},
            {"提车", "买车", "纳车"}, {"领证", "登记结婚", "结婚登记"}};

    static List<String> words(String thing) {
        String t = thing == null ? "" : thing.trim();
        for (String[] g : SAME) if (Arrays.asList(g).contains(t)) return Arrays.asList(g);
        return t.isEmpty() ? new ArrayList<String>() : Arrays.asList(t);
    }

    private static String found(List<String> list, List<String> words) {
        for (String x : list) for (String w : words) if (x.contains(w) || w.contains(x) && x.length() >= 2) return x;
        return null;
    }

    private static String list(List<String> l, int n) {
        List<String> out = l.subList(0, Math.min(n, l.size()));
        return String.join("、", out) + (l.size() > n ? "等" : "");
    }

    private static String bad(List<String> l) {
        if (l.size() == 1 && ("馀事勿取".equals(l.get(0)) || "余事勿取".equals(l.get(0)) || "诸事不宜".equals(l.get(0))))
            return l.get(0) + "（其余的事都不宜）";
        return list(l, 8);
    }

    /** 「今天（11 月 7 日，农历九月廿九，乙酉日，立冬）」. */
    private static String head(Day d, String when) {
        return when + "（" + d.month + " 月 " + d.day + " 日，" + d.lunar + (d.ganzhi.isEmpty() ? "" : "，" + d.ganzhi + "日")
                + (d.term.isEmpty() ? "" : "，" + d.term) + "）";
    }

    /**
     * 「今天（…）宜打扫；忌馀事勿取（其余的事都不宜）」; with a thing: 「明天（…）忌搬家」「…宜结婚（宜嫁娶）」, or neither said:
     * 「…黄历上没说宜不宜搬家：宜…；忌…」.
     */
    public static String speak(Day d, String when, String thing) {
        String all = "宜" + (d.good.isEmpty() ? "的没有写" : list(d.good, 8)) + "；忌" + (d.bad.isEmpty() ? "的没有写" : bad(d.bad));
        if (thing == null || thing.trim().isEmpty()) return head(d, when) + all;
        List<String> w = words(thing.trim());
        String no = found(d.bad, w), yes = found(d.good, w);
        if (no != null) return head(d, when) + "忌" + thing.trim() + (no.equals(thing.trim()) ? "" : "（黄历写的是忌" + no + "）") + "；这天宜" + (d.good.isEmpty() ? "的没有写" : list(d.good, 6));
        if (yes != null) return head(d, when) + "宜" + thing.trim() + (yes.equals(thing.trim()) ? "" : "（黄历写的是宜" + yes + "）");
        if (d.bad.size() == 1 && bad(d.bad).contains("其余的事都不宜")) return head(d, when) + "宜" + list(d.good, 6) + "，忌馀事勿取：" + thing.trim() + "不宜";
        return head(d, when) + "黄历上没说宜不宜" + thing.trim() + "：" + all;
    }

    /** A day said (今天, 明天, 2026-11-20…) → {year, month, day, days from today}, or null. */
    public static int[] dayOf(String said, long now) {
        java.util.Calendar c = java.util.Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(now);
        long today = Almanac.days(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
        long day = said == null || said.trim().isEmpty() ? today : CalendarTools.parse(said.trim(), today);
        if (day == Long.MIN_VALUE) return null;
        int[] ymd = Almanac.civil(day);
        return new int[]{ymd[0], ymd[1], ymd[2], (int) (day - today)};
    }

    /** 「这个月」「下个月」「12 月」 → {year, month}; null when it is not a month. */
    public static int[] monthOf(String said, long now) {
        java.util.Calendar c = java.util.Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(now);
        int y = c.get(java.util.Calendar.YEAR), m = c.get(java.util.Calendar.MONTH) + 1;
        String s = said == null ? "" : said.trim();
        if (s.isEmpty() || s.contains("这个月") || s.contains("本月") || s.contains("这月")) return new int[]{y, m};
        if (s.contains("下个月") || s.contains("下月")) return m == 12 ? new int[]{y + 1, 1} : new int[]{y, m + 1};
        // 「2027年3月」 (not 20 as a month), 「明年3月」, 「今年12月」, else the month nearest ahead
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("(?:([0-9]{4})年)?([0-9]{1,2})[ ]*月?").matcher(s);
        if (x.find()) {
            int want = Integer.parseInt(x.group(2));
            if (want < 1 || want > 12) return null;
            if (x.group(1) != null) return new int[]{Integer.parseInt(x.group(1)), want};
            if (s.contains("明年")) return new int[]{y + 1, want};
            if (s.contains("今年")) return new int[]{y, want};
            return new int[]{want < m - 6 ? y + 1 : y, want};
        }
        return null;
    }

    /** The month's days, from today on when it is this month (2026-11-07 review: on the 25th the eight days read were all
     * past, and the two still ahead hid behind 「等」). */
    public static List<Day> month(List<Day> all, int y, int m, long now) {
        java.util.Calendar c = java.util.Calendar.getInstance(Sports.BEIJING);
        c.setTimeInMillis(now);
        boolean current = c.get(java.util.Calendar.YEAR) == y && c.get(java.util.Calendar.MONTH) + 1 == m;
        List<Day> out = new ArrayList<>();
        for (Day d : all) if (d.year == y && d.month == m && (!current || d.day >= c.get(java.util.Calendar.DAY_OF_MONTH))) out.add(d);
        return out;
    }

    /** 「下个月宜结婚的日子：12 月 3 日（周四）、12 月 9 日（周三）…」 among the days given (a month), at most 8. */
    public static String goodDays(List<Day> days, String what, String thing) {
        List<String> w = words(thing), out = new ArrayList<>();
        java.util.Calendar c = java.util.Calendar.getInstance(Sports.BEIJING);
        String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        for (Day d : days) {
            if (found(d.good, w) == null || found(d.bad, w) != null) continue;
            c.clear();
            c.set(d.year, d.month - 1, d.day);
            out.add(d.month + " 月 " + d.day + " 日（" + week[c.get(java.util.Calendar.DAY_OF_WEEK) - 1] + "）");
        }
        if (out.isEmpty()) return what + "黄历上没有宜" + thing + "的日子";
        return what + "宜" + thing + "的日子有 " + out.size() + " 天：" + String.join("、", out.subList(0, Math.min(8, out.size()))) + (out.size() > 8 ? "等" : "");
    }
}
