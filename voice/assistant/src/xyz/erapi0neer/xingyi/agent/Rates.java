package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exchange rates (2026-11-06): the RMB's central parity, published by the China Foreign Exchange Trade System at 9:15
 * on working days (the rate the news quotes), and, for currencies it leaves out or pairs without the RMB, a market
 * rate updated daily (open.er-api.com). Two web searches for 「一美元换多少人民币」 gave 6.83 and 6.72.
 */
public final class Rates {
    private Rates() { }

    public static final String PARITY = "https://www.chinamoney.com.cn/r/cms/www/chinamoney/data/fx/ccpr.json";
    public static final String MARKET = "https://open.er-api.com/v6/latest/USD";

    private static final String[][] NAMES = {
            {"人民币", "CNY"}, {"元", "CNY"}, {"块", "CNY"}, {"块钱", "CNY"}, {"RMB", "CNY"},
            {"美元", "USD"}, {"美金", "USD"}, {"刀", "USD"}, {"欧元", "EUR"}, {"日元", "JPY"}, {"日币", "JPY"},
            {"港币", "HKD"}, {"港元", "HKD"}, {"英镑", "GBP"}, {"澳元", "AUD"}, {"澳币", "AUD"}, {"新西兰元", "NZD"}, {"纽币", "NZD"},
            {"新加坡元", "SGD"}, {"新币", "SGD"}, {"瑞士法郎", "CHF"}, {"法郎", "CHF"}, {"加元", "CAD"}, {"加币", "CAD"},
            {"澳门元", "MOP"}, {"澳门币", "MOP"}, {"林吉特", "MYR"}, {"马币", "MYR"}, {"卢布", "RUB"}, {"兰特", "ZAR"},
            {"韩元", "KRW"}, {"韩币", "KRW"}, {"迪拉姆", "AED"}, {"里亚尔", "SAR"}, {"福林", "HUF"}, {"兹罗提", "PLN"},
            {"丹麦克朗", "DKK"}, {"瑞典克朗", "SEK"}, {"挪威克朗", "NOK"}, {"里拉", "TRY"}, {"墨西哥比索", "MXN"}, {"比索", "MXN"},
            {"泰铢", "THB"}, {"新台币", "TWD"}, {"台币", "TWD"}, {"越南盾", "VND"}, {"印度卢比", "INR"}, {"卢比", "INR"},
            {"印尼盾", "IDR"}, {"菲律宾比索", "PHP"}};

    /** 「美元」「美金」「usd」 → USD; null when it is not a currency known here. */
    public static String code(String name) {
        String n = name == null ? "" : name.trim().replaceAll("[\\s　]", "");
        if (n.matches("[A-Za-z]{3}")) return n.toUpperCase(Locale.ROOT);
        for (String[] p : NAMES) if (p[0].equalsIgnoreCase(n)) return p[1];
        return null;
    }

    static String nameOf(String code) {
        if ("CNY".equals(code)) return "人民币";
        for (String[] p : NAMES) if (p[1].equals(code)) return p[0];
        return code;
    }

    /** The central parity: CNY per unit of each currency, how the RMB moved against it since the last working day (in
     * basis points of the published pair, positive = the RMB stronger), and when it was published. */
    public static final class Parity {
        public final Map<String, Double> cnyPer = new HashMap<>();
        final Map<String, Double> rmbMove = new HashMap<>();
        final Map<String, String> published = new HashMap<>();
        public String date = "";
    }

    private static final Pattern PER = Pattern.compile("^(\\d+)([A-Z]{3})$");

    public static Parity parity(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Parity p = new Parity();
        JSONObject data = o.optJSONObject("data");
        p.date = data == null ? "" : data.optString("lastDate");
        JSONArray records = o.getJSONArray("records");
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.getJSONObject(i);
            String[] ab = r.optString("vrtEName").split("/");
            double price;
            try { price = Double.parseDouble(r.optString("price")); } catch (NumberFormatException e) { continue; }
            if (ab.length != 2 || price <= 0) continue;
            String a = ab[0], b = ab[1];
            double per = 1;
            Matcher m = PER.matcher(a);
            if (m.matches()) { per = Double.parseDouble(m.group(1)); a = m.group(2); }
            double bp = r.optDouble("bpDouble", 0);
            if ("CNY".equals(b)) {         // 「美元/人民币 6.7168」: up is the RMB weaker
                p.cnyPer.put(a, price / per);
                p.rmbMove.put(a, -bp);
                p.published.put(a, (per == 1 ? "1 " : (long) per + " ") + nameOf(a) + "兑 " + r.optString("price") + " 元人民币");
            } else if ("CNY".equals(a)) {  // 「人民币/泰铢 5.0038」: up is the RMB stronger
                p.cnyPer.put(b, 1 / price);
                p.rmbMove.put(b, bp);
                p.published.put(b, "1 元人民币兑 " + r.optString("price") + " " + nameOf(b));
            }
        }
        if (p.cnyPer.isEmpty()) throw new JSONException("中间价数据是空的");
        return p;
    }

    /** USD-based market rates (units of each currency per US dollar). */
    public static Map<String, Double> market(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!"success".equals(o.optString("result"))) throw new JSONException("汇率服务没有返回结果");
        JSONObject rates = o.getJSONObject("rates");
        Map<String, Double> out = new HashMap<>();
        for (Iterator<String> it = rates.keys(); it.hasNext(); ) {
            String k = it.next();
            out.put(k, rates.getDouble(k));
        }
        return out;
    }

    /** Whether the answer needs the market rates (a pair without the RMB, or a currency the parity leaves out). */
    public static boolean needsMarket(String from, String to, Parity p) {
        if ("CNY".equals(from)) return p == null || !p.cnyPer.containsKey(to);
        if ("CNY".equals(to)) return p == null || !p.cnyPer.containsKey(from);
        return true;
    }

    /**
     * The sentence to speak: 「今天人民币汇率中间价：1 美元兑 6.7168 元人民币，人民币比上个交易日调贬 12 个基点；100 美元约合
     * 671.68 元人民币（11 月 6 日 9:15 公布）」, or with the market rate 「1 美元约合 157.84 日元（国际市场参考价，每天更新）」.
     */
    public static String answer(String from, String to, double amount, Parity p, Map<String, Double> market) {
        if (from.equals(to)) return "这是同一种货币";
        double a = amount > 0 ? amount : 1;
        if (p != null && !needsMarket(from, to, p)) {
            String other = "CNY".equals(from) ? to : from;
            double cny = p.cnyPer.get(other), result = "CNY".equals(from) ? a / cny : a * cny;
            StringBuilder s = new StringBuilder("今天人民币汇率中间价：").append(p.published.get(other));
            Double move = p.rmbMove.get(other);
            if (move != null && Math.abs(move) >= 0.5) s.append("，人民币比上个交易日调").append(move > 0 ? "升 " : "贬 ").append(number(Math.abs(move))).append(" 个基点");
            if (a != 1 || "CNY".equals(from)) s.append("；").append(number(a)).append(" ").append(unitOf(from)).append("约合 ")
                    .append(money(result)).append(" ").append(unitOf(to));
            String when = spokenDate(p.date);
            return s.append(when.isEmpty() ? "" : "（" + when + "公布）").toString();
        }
        if (market == null || !market.containsKey(from) || !market.containsKey(to)) return null;
        double rate = market.get(to) / market.get(from);
        return number(a) + " " + unitOf(from) + "约合 " + money(a * rate) + " " + unitOf(to) + "（国际市场参考价，每天更新一次）";
    }

    private static String unitOf(String code) { return "CNY".equals(code) ? "元人民币" : nameOf(code); }

    /** 「2026-11-06 9:15」→「11 月 6 日 9 点 15 分」. */
    static String spokenDate(String date) {
        Matcher m = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})").matcher(date == null ? "" : date);
        if (!m.find()) return "";
        int mi = Integer.parseInt(m.group(5));
        return Integer.parseInt(m.group(2)) + " 月 " + Integer.parseInt(m.group(3)) + " 日 " + Integer.parseInt(m.group(4)) + " 点"
                + (mi == 0 ? "" : " " + mi + " 分");
    }

    /** 671.6800 → 「671.68」, 0.0436 → 「0.0436」, 1000 → 「1000」. */
    static String money(double v) {
        if (v >= 1) return trimZeros(String.format(Locale.ROOT, "%.2f", v));
        return trimZeros(String.format(Locale.ROOT, "%.4f", v));
    }

    static String number(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : trimZeros(String.format(Locale.ROOT, "%.2f", v)); }

    private static String trimZeros(String s) {
        if (s.indexOf('.') < 0) return s;
        String t = s.replaceAll("0+$", "");
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }
}
