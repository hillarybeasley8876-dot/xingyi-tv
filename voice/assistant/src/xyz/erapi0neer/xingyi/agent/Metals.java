package xyz.erapi0neer.xingyi.agent;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「今天金价多少」 (2026-11-06): the Shanghai Gold Exchange's Au99.99 in yuan a gram and London spot gold in dollars an
 * ounce, from Sina's public quotes (a web search took 7-9 s and gave a 「基准价」 from some other day).
 */
public final class Metals {
    private Metals() { }

    public static final String GOLD = "https://hq.sinajs.cn/list=gds_AU9999,hf_XAU";

    /** One quote: 「var hq_str_gds_AU9999="872.40,0,872.40,872.41,887.00,870.10,10:09:19,884.09,…,2026-11-06,沪金99";」. */
    static final class Quote {
        final double last, prev;
        final String time, date;

        Quote(double last, double prev, String time, String date) { this.last = last; this.prev = prev; this.time = time; this.date = date; }
    }

    static Quote quote(String text, String key) {
        Matcher m = Pattern.compile("hq_str_" + key + "=\"([^\"]*)\"").matcher(text == null ? "" : text);
        if (!m.find()) return null;
        String[] f = m.group(1).split(",", -1);
        if (f.length < 13) return null;
        try {
            double last = Double.parseDouble(f[0]), prev = Double.parseDouble(f[7]);
            return last > 0 ? new Quote(last, prev, f[6], f[12]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 「国内金价（上海黄金交易所 Au99.99）872.40 元一克，比上个交易日跌 1.32%；国际金价（伦敦金）4065.34 美元一盎司，跌 0.57%
     * （10 点 9 分）。金店的首饰金价比这个高，以各家牌价为准」; null when neither came.
     */
    public static String speakGold(String text, Calendar now) {
        Quote sge = quote(text, "gds_AU9999"), london = quote(text, "hf_XAU");
        if (sge == null && london == null) return null;
        StringBuilder s = new StringBuilder();
        if (sge != null) s.append("国内金价（上海黄金交易所 Au99.99）").append(fmt(sge.last)).append(" 元一克，比上个交易日").append(move(sge));
        if (london != null) s.append(s.length() > 0 ? "；" : "").append("国际金价（伦敦金）").append(fmt(london.last)).append(" 美元一盎司，")
                .append(sge != null ? "" : "比上个交易日").append(move(london));
        Quote at = sge != null ? sge : london;
        String when = when(at, now);
        if (!when.isEmpty()) s.append("（").append(when).append("）");
        return s.append("。金店的首饰金价比这个高，以各家牌价为准").toString();
    }

    private static String move(Quote q) {
        if (q.prev <= 0 || q.last == q.prev) return "持平";
        double pct = (q.last - q.prev) / q.prev * 100;
        return (pct > 0 ? "涨 " : "跌 ") + String.format(Locale.ROOT, "%.2f", Math.abs(pct)) + "%";
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    /** 「10 点 9 分」 today, 「11 月 5 日」 another day. */
    static String when(Quote q, Calendar now) {
        Matcher d = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(q.date == null ? "" : q.date);
        Matcher t = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(q.time == null ? "" : q.time);
        boolean today = d.find() && Integer.parseInt(d.group(2)) == now.get(Calendar.MONTH) + 1
                && Integer.parseInt(d.group(3)) == now.get(Calendar.DAY_OF_MONTH);
        if (!today) return d.find(0) ? Integer.parseInt(d.group(2)) + " 月 " + Integer.parseInt(d.group(3)) + " 日" : "";
        if (!t.find()) return "";
        int h = Integer.parseInt(t.group(1)), mi = Integer.parseInt(t.group(2));
        return h + " 点" + (mi == 0 ? "整" : " " + mi + " 分");
    }
}
