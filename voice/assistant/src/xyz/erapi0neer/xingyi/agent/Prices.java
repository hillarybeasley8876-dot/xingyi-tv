package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 「今天油价多少」「92 号汽油多少钱」 (2026-11-07): the retail ceiling prices of a province from Eastmoney's data centre
 * (the national adjustments every ten working days), and fund net values from Eastmoney's fund pages (「易方达蓝筹今天
 * 涨了多少」). Both took a web search.
 */
public final class Prices {
    private Prices() { }

    private static final String[] PROVINCES = {"北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏", "浙江", "安徽",
            "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州", "云南", "西藏", "陕西", "甘肃", "青海",
            "宁夏", "新疆"};
    /** Cities people name, by their province. */
    private static final String[][] CITIES = {{"广州", "广东"}, {"深圳", "广东"}, {"东莞", "广东"}, {"佛山", "广东"}, {"杭州", "浙江"}, {"宁波", "浙江"},
            {"温州", "浙江"}, {"南京", "江苏"}, {"苏州", "江苏"}, {"无锡", "江苏"}, {"成都", "四川"}, {"武汉", "湖北"}, {"长沙", "湖南"},
            {"西安", "陕西"}, {"郑州", "河南"}, {"济南", "山东"}, {"青岛", "山东"}, {"合肥", "安徽"}, {"福州", "福建"}, {"厦门", "福建"},
            {"南昌", "江西"}, {"沈阳", "辽宁"}, {"大连", "辽宁"}, {"长春", "吉林"}, {"哈尔滨", "黑龙江"}, {"石家庄", "河北"}, {"太原", "山西"},
            {"呼和浩特", "内蒙古"}, {"南宁", "广西"}, {"海口", "海南"}, {"三亚", "海南"}, {"贵阳", "贵州"}, {"昆明", "云南"}, {"拉萨", "西藏"},
            {"兰州", "甘肃"}, {"西宁", "青海"}, {"银川", "宁夏"}, {"乌鲁木齐", "新疆"}};

    /** The province of a place (「上海徐汇」→ 上海, 「杭州」→ 浙江, 「广东省」→ 广东), or null. */
    public static String province(String place) {
        String p = place == null ? "" : place.trim();
        for (String x : PROVINCES) if (p.startsWith(x)) return x;
        for (String[] c : CITIES) if (p.startsWith(c[0])) return c[1];
        return null;
    }

    public static String fuelUrl(String province) {
        try {
            return "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPTA_WEB_YJ_JH&columns=ALL&filter=(CITYNAME%3D%22"
                    + java.net.URLEncoder.encode(province, "UTF-8") + "%22)&sortColumns=DIM_DATE&sortTypes=-1&pageNumber=1&pageSize=1";
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 「上海油价（10 月 30 日调价后）：92 号汽油 8.85 元一升，95 号 9.41 元，0 号柴油 8.57 元；这次每升涨了 0.28 元（92 号）」. */
    public static String speakFuel(String json) throws JSONException {
        JSONObject r = new JSONObject(json).optJSONObject("result");
        JSONArray data = r == null ? null : r.optJSONArray("data");
        if (data == null || data.length() == 0) throw new JSONException("油价数据是空的");
        JSONObject d = data.getJSONObject(0);
        String date = News.str(d, "DIM_DATE");
        String day = date.length() >= 10 ? Integer.parseInt(date.substring(5, 7)) + " 月 " + Integer.parseInt(date.substring(8, 10)) + " 日" : "";
        double change = d.optDouble("ZDE92", 0);
        String move = change == 0 ? "" : "；这次每升" + (change > 0 ? "涨了 " : "降了 ") + money(Math.abs(change)) + " 元（92 号）";
        return News.str(d, "CITYNAME") + "油价（" + day + "调价后）：92 号汽油 " + money(d.optDouble("V92")) + " 元一升，95 号 "
                + money(d.optDouble("V95")) + " 元，0 号柴油 " + money(d.optDouble("V0")) + " 元" + move;
    }

    private static String money(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    // ---- funds ------------------------------------------------------------------------------------------------

    public static String fundSearchUrl(String query) {
        try {
            return "https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=1&key=" + java.net.URLEncoder.encode(query, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String fundNavUrl(String code) { return "https://api.fund.eastmoney.com/f10/lsjz?fundCode=" + code + "&pageIndex=1&pageSize=1"; }

    /** {code, name} of the first fund the search found, or null. */
    public static String[] pickFund(String json) throws JSONException {
        JSONArray d = new JSONObject(json).optJSONArray("Datas");
        for (int i = 0; d != null && i < d.length(); i++) {
            JSONObject f = d.getJSONObject(i);
            if (News.str(f, "CODE").matches("\\d{6}")) return new String[]{News.str(f, "CODE"), News.str(f, "NAME")};
        }
        return null;
    }

    /** 「易方达蓝筹精选混合 11 月 6 日净值 1.4023，当天跌 1.00%」. */
    public static String speakFund(String name, String json) throws JSONException {
        JSONObject o = new JSONObject(json), data = o.optJSONObject("Data");
        JSONArray list = data == null ? null : data.optJSONArray("LSJZList");
        if (list == null || list.length() == 0)
            throw new JSONException("基金净值数据是空的" + (o.has("ErrCode") ? "（ErrCode " + o.opt("ErrCode") + "）" : ""));
        JSONObject n = list.getJSONObject(0);
        String date = News.str(n, "FSRQ"), pct = News.str(n, "JZZZL");
        String day = date.length() >= 10 ? Integer.parseInt(date.substring(5, 7)) + " 月 " + Integer.parseInt(date.substring(8, 10)) + " 日" : date;
        String move;
        try {
            double p = Double.parseDouble(pct);
            move = p > 0 ? "，当天涨 " + pct + "%" : p < 0 ? "，当天跌 " + pct.substring(1) + "%" : "，当天持平";
        } catch (NumberFormatException e) {
            move = "";
        }
        return name + " " + day + "净值 " + News.str(n, "DWJZ") + move;
    }
}
