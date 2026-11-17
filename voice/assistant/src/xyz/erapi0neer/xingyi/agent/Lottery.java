package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「双色球开奖结果」「大乐透开奖号码」 (2026-11-06): the latest draw from the lotteries' own sites — the China Welfare
 * Lottery (双色球, 福彩3D, 七乐彩, 快乐8) and the China Sports Lottery (大乐透, 排列三, 排列五, 七星彩). Its numbers, the
 * day, and the first prize.
 */
public final class Lottery {
    private Lottery() { }

    /** {what people say, the game's name, the source: cwl:<name> or tc:<gameNo>}. */
    private static final String[][] GAMES = {
            {"双色球", "双色球", "cwl:ssq"}, {"福彩3D", "福彩 3D", "cwl:3d"}, {"3D", "福彩 3D", "cwl:3d"}, {"福彩三D", "福彩 3D", "cwl:3d"},
            {"七乐彩", "七乐彩", "cwl:qlc"}, {"快乐8", "快乐 8", "cwl:kl8"}, {"快乐八", "快乐 8", "cwl:kl8"},
            {"大乐透", "大乐透", "tc:85"}, {"超级大乐透", "大乐透", "tc:85"}, {"排列三", "排列三", "tc:35"}, {"排列3", "排列三", "tc:35"},
            {"排三", "排列三", "tc:35"}, {"排列五", "排列五", "tc:350133"}, {"排列5", "排列五", "tc:350133"}, {"排五", "排列五", "tc:350133"},
            {"七星彩", "七星彩", "tc:04"}, {"7星彩", "七星彩", "tc:04"}};

    /** {name, source} for what was said, or null. */
    public static String[] game(String said) {
        String s = said == null ? "" : said.trim().replaceAll("[\\s　]", "");
        for (String[] g : GAMES) if (g[0].equalsIgnoreCase(s)) return new String[]{g[1], g[2]};
        for (String[] g : GAMES) if (s.contains(g[0])) return new String[]{g[1], g[2]};
        return null;
    }

    public static String url(String source) {
        if (source.startsWith("cwl:"))
            return "https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice?name=" + source.substring(4) + "&issueCount=1";
        return "https://webapi.sporttery.cn/gateway/lottery/getHistoryPageListV1.qry?gameNo=" + source.substring(3)
                + "&provinceId=0&pageSize=1&isVerify=1&pageNo=1";
    }

    /** The latest draw said: 「双色球第 2026127 期（11 月 5 日 周四）：红球 13、14、19、25、29、32，蓝球 12；一等奖 3 注，每注
     * 1000 万元」. */
    public static String speak(String name, String source, String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (source.startsWith("cwl:")) {
            JSONArray rs = o.optJSONArray("result");
            if (rs == null || rs.length() == 0) throw new JSONException("福彩官网没有返回开奖结果：" + News.str(o, "message"));
            JSONObject r = rs.getJSONObject(0);
            List<String> red = split(News.str(r, "red"), ","), blue = split(News.str(r, "blue"), ",");
            String nums;
            switch (source) {
                case "cwl:ssq": nums = "红球 " + String.join("、", red) + "，蓝球 " + String.join("、", blue); break;
                case "cwl:qlc": nums = "基本号 " + String.join("、", red) + "，特别号 " + String.join("、", blue); break;
                default: nums = String.join("、", red);
            }
            String prize = "";
            JSONArray grades = r.optJSONArray("prizegrades");
            JSONObject first = grades == null || grades.length() == 0 ? null : grades.optJSONObject(0);
            if (first != null && !"cwl:3d".equals(source) && !"cwl:kl8".equals(source) && !News.str(first, "typenum").isEmpty())
                prize = "；一等奖 " + News.str(first, "typenum") + " 注" + ("0".equals(News.str(first, "typenum")) ? ""
                        : "，每注 " + money(News.str(first, "typemoney")));
            return name + "第 " + News.str(r, "code") + " 期（" + day(News.str(r, "date")) + "）：" + nums + prize;
        }
        if (!o.optBoolean("success")) throw new JSONException("体彩官网没有返回开奖结果：" + News.str(o, "errorMessage"));
        JSONArray list = o.getJSONObject("value").getJSONArray("list");
        if (list.length() == 0) throw new JSONException("体彩官网没有开奖记录");
        JSONObject x = list.getJSONObject(0);
        List<String> n = split(News.str(x, "lotteryDrawResult"), " ");
        String nums = "tc:85".equals(source) && n.size() == 7
                ? "前区 " + String.join("、", n.subList(0, 5)) + "，后区 " + String.join("、", n.subList(5, 7)) : String.join("、", n);
        String prize = "";
        JSONArray levels = x.optJSONArray("prizeLevelList");
        JSONObject first = levels == null || levels.length() == 0 ? null : levels.optJSONObject(0);
        if (first != null && ("tc:85".equals(source) || "tc:04".equals(source))) {
            String count = News.str(first, "stakeCount").replace(",", "");
            prize = "；一等奖 " + count + " 注" + ("0".equals(count) ? "" : "，每注 " + money(News.str(first, "stakeAmount").replace(",", "")));
        }
        return name + "第 " + News.str(x, "lotteryDrawNum") + " 期（" + day(News.str(x, "lotteryDrawTime")) + "）：" + nums + prize;
    }

    private static List<String> split(String s, String by) {
        List<String> out = new ArrayList<>();
        for (String p : (s == null ? "" : s.trim()).split(by.equals(" ") ? "\\s+" : by)) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** 10000000 → 「1000 万元」, 434572 → 「43.5 万元」, 1040 → 「1040 元」. */
    static String money(String raw) {
        long v;
        try { v = Math.round(Double.parseDouble(raw.trim())); } catch (NumberFormatException e) { return raw + " 元"; }
        if (v >= 100_000_000) return String.format(Locale.ROOT, "%.2f 亿元", v / 1e8).replace(".00 ", " ");
        if (v >= 10_000) return v % 10_000 == 0 ? v / 10_000 + " 万元" : String.format(Locale.ROOT, "%.1f 万元", v / 1e4);
        return v + " 元";
    }

    /** 「2026-11-05(四)」 or 「2026-11-04」 → 「11 月 5 日 周四」. */
    static String day(String raw) {
        Matcher m = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(raw == null ? "" : raw);
        if (!m.find()) return raw == null ? "" : raw;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)));
        return Integer.parseInt(m.group(2)) + " 月 " + Integer.parseInt(m.group(3)) + " 日 周" + "日一二三四五六".charAt(c.get(Calendar.DAY_OF_WEEK) - 1);
    }
}
