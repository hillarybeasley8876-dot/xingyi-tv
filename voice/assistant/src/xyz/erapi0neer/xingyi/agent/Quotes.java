package xyz.erapi0neer.xingyi.agent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stock and index quotes from Tencent's public quote service (2026-11-06): 「茅台股价多少」「今天大盘怎么样」 took a web
 * search, 7-9 s, sometimes with an old number. Numbers only: no advice.
 */
public final class Quotes {
    private Quotes() { }

    public static final String QT = "https://qt.gtimg.cn/q=";
    public static final String SEARCH = "https://smartbox.gtimg.cn/s3/?v=2&t=all&q=";

    private static final String A_SHARES = "sh000001,sz399001,sz399006";
    /** What people call the indices; 大盘 is the three A-share ones. */
    private static final String[][] ALIASES = {
            {"大盘", A_SHARES}, {"A股", A_SHARES}, {"股市", A_SHARES}, {"沪深", A_SHARES}, {"今天大盘", A_SHARES},
            {"上证", "sh000001"}, {"上证指数", "sh000001"}, {"沪指", "sh000001"}, {"上证综指", "sh000001"},
            {"深证", "sz399001"}, {"深证成指", "sz399001"}, {"深成指", "sz399001"}, {"深指", "sz399001"},
            {"创业板", "sz399006"}, {"创业板指", "sz399006"}, {"沪深300", "sh000300"}, {"科创50", "sh000688"}, {"科创板", "sh000688"},
            {"恒生", "hkHSI"}, {"恒指", "hkHSI"}, {"恒生指数", "hkHSI"}, {"港股", "hkHSI"},
            {"纳斯达克", "usIXIC"}, {"纳指", "usIXIC"}, {"道琼斯", "usDJI"}, {"道指", "usDJI"}, {"标普", "usINX"}, {"标普500", "usINX"},
            {"美股", "usDJI,usIXIC,usINX"}};
    private static final Pattern SIX = Pattern.compile("\\d{6}");
    private static final Pattern MARKET_CODE = Pattern.compile("(?i)(sh|sz|bj)(\\d{6})|(hk)(\\d{5})|(us)([A-Za-z.]{1,8})");

    /** The quote codes a query names without a search (an index by its common name, or a code), or null. */
    public static List<String> known(String query) {
        String q = query == null ? "" : query.trim().replaceAll("[\\s　]", "");
        if (q.isEmpty()) q = "大盘";
        String bare = q.endsWith("指数") && q.length() > 2 ? q.substring(0, q.length() - 2) : q;
        for (String[] a : ALIASES) if (a[0].equalsIgnoreCase(q) || a[0].equalsIgnoreCase(bare)) return Arrays.asList(a[1].split(","));
        if (SIX.matcher(q).matches()) {
            // 6xxxxx / 5xxxxx / 9xxxxx on Shanghai, 8xxxxx / 4xxxxx / 92xxxx on Beijing, the rest on Shenzhen
            String m = q.startsWith("92") || q.startsWith("8") || q.startsWith("4") ? "bj" : "569".indexOf(q.charAt(0)) >= 0 ? "sh" : "sz";
            return Collections.singletonList(m + q);
        }
        Matcher m = MARKET_CODE.matcher(q);
        if (m.matches()) {
            if (m.group(1) != null) return Collections.singletonList(m.group(1).toLowerCase(Locale.ROOT) + m.group(2));
            if (m.group(3) != null) return Collections.singletonList("hk" + m.group(4));
            return Collections.singletonList("us" + m.group(6).toUpperCase(Locale.ROOT));
        }
        return null;
    }

    private static final Pattern ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    /**
     * The best match from the search's answer (「v_hint="sh~600519~贵州茅台~gzmt~GP-A^…"」): the name first (the exact name,
     * then one that starts with the query), then a stock before an index (unless the query says 指数), A shares before
     * Hong Kong before the US; funds, ETFs, warrants and leveraged or inverse products are skipped, and so is Hong Kong's
     * RMB counter (腾讯控股r). 腾讯 → hk00700, 比亚迪 → sz002594, 苹果 → usAAPL, 英伟达 → usNVDA (2026-11-06 on the TV:
     * the market preference had put 「南方2倍做空英伟达」 in Hong Kong before NVIDIA itself).
     */
    public static String pick(String searchText, String query) {
        List<String[]> items = items(searchText);
        if (items == null) return null;
        String q = query == null ? "" : query.trim();
        boolean index = q.contains("指数");
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < items.size(); i++) {
            String[] f = items.get(i);
            String market = f[0], code = f[1], name = f[2];
            int kind = kind(market, code, name, f[4], index);
            if (kind < 0) continue;
            String bare = SUFFIX.matcher(name).replaceAll("");
            // the whole name in either case, but only the whole name: a case-blind prefix would take AMD for the WEEK
            // ETF's ticker and ARM for armada acquisition corp (2026-11-16, 57/63/45 → 64/64/63 of 69 English names)
            int rank = (bare.equalsIgnoreCase(q) ? 0 : bare.startsWith(q) ? 1 : 2) * 10_000 + kind * 100 + Math.min(i, 9);
            if (rank < bestRank) { bestRank = rank; best = qtCode(market, code); }
        }
        return best;
    }

    /** A search result found by how its name sounds. */
    public static final class Hit {
        public final String code, name;

        public Hit(String code, String name) { this.code = code; this.name = name; }
    }

    /**
     * The stock or index in a search answer (the answer for Pinyin.spell(query) or Pinyin.initials(query)) whose name
     * sounds like the query, or null. Rank: the bare name (SUFFIX removed, as in pick) has the same sound as the query
     * → best; it starts with a stretch of that sound → next; it has one inside → last; a name with no such stretch is
     * never taken. Within a rank the order and the exclusions are exactly pick's (stock before index unless the query
     * has 指数; A shares, then Hong Kong, then the US; funds, ETFs, warrants, leveraged or inverse products, US OTC and
     * the RMB counter skipped). 「质朴」 + the answer for "zhipu" → hk02513 智谱 (2026-11-07).
     */
    public static Hit bySound(String searchText, String query) {
        List<String[]> items = items(searchText);
        if (items == null) return null;
        String q = query == null ? "" : query.trim();
        boolean index = q.contains("指数");
        Hit best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < items.size(); i++) {
            String[] f = items.get(i);
            String market = f[0], code = f[1], name = f[2];
            int kind = kind(market, code, name, f[4], index);
            if (kind < 0) continue;
            String bare = SUFFIX.matcher(name).replaceAll("");
            int fit;
            if (Pinyin.sameSound(bare, q)) fit = 0;
            else {
                int at = Pinyin.soundIndex(q, bare);
                if (at < 0) continue;
                fit = at == 0 ? 1 : 2;
            }
            int rank = fit * 10_000 + kind * 100 + Math.min(i, 9);
            if (rank < bestRank) { bestRank = rank; best = new Hit(qtCode(market, code), name); }
        }
        return best;
    }

    /** Well-known companies whose main entity is not listed, with the names people use for them: 「<what to say>」 and
     * 「<names, comma separated>」. Listed companies are never here (小米、腾讯、美团、比亚迪、宁德时代…), and neither are ones with a listing on the
     * way (荣耀、小红书 had IPO news): a stale 「没有上市」 is worse than the search finding nothing. */
    private static final String[][] NOT_LISTED = {
            {"华为", "华为技术,华为公司"}, {"海思", ""},
            {"字节跳动", "字节,抖音,今日头条"}, {"大疆", ""}, {"蚂蚁集团", "蚂蚁金服,支付宝"},
            {"OPPO", ""}, {"vivo", ""}, {"米哈游", ""}, {"娃哈哈", ""}, {"老干妈", ""},
            {"国家电网", ""}, {"中国烟草", ""}};

    /**
     * 「华为没有上市，没有股价」 for a well-known company that is not listed, or null. The by-sound retry must not answer
     * 「华为股价多少」 with 华微电子's price just because its sound starts with huawei (2026-11-09, said twice on the TV).
     * A name speech recognition wrote with other characters counts too (「华维」「花为」 are 华为), the whole name only,
     * never a part of it — 「质朴」→ 智谱 and 「毛台」→ 贵州茅台 are mishearings, not these.
     */
    public static String unlisted(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return null;
        for (String[] e : NOT_LISTED) if (matches(n, e, false)) return said(e[0]);
        for (String[] e : NOT_LISTED) if (matches(n, e, true)) return said(e[0]);
        return null;
    }

    /** Whether the name is this company's own name or one of its other names, exactly (sound false) or by sound. */
    private static boolean matches(String name, String[] entry, boolean sound) {
        if (sound ? Pinyin.sameSound(name, entry[0]) : name.equalsIgnoreCase(entry[0])) return true;
        if (entry[1].isEmpty()) return false;
        for (String other : entry[1].split(",")) if (sound ? Pinyin.sameSound(name, other) : name.equalsIgnoreCase(other)) return true;
        return false;
    }

    private static String said(String name) {
        return name + "没有上市，没有股价";
    }

    private static final Pattern JOIN = Pattern.compile("[和跟与]");

    /**
     * The names in a query: 「华为 小米」「华为、小米」 and, where one side is a name known without a search (an index
     * alias or a company that is not listed), 「华为和小米」 too. 和/跟/与 are part of company names as well (天和防务),
     * so a query is never split on them alone.
     */
    public static List<String> parts(String query) {
        List<String> out = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        for (String part : segments(q)) if (!part.isEmpty()) split(part, out);
        return out;
    }

    /** The query cut at 、,，;； and spaces — except a space between two ASCII letters or digits, which stays inside one
     * name: 「MINI MAX」 is one stock, not MINI and MAX (2026-11-16, it was split and then looked up twice). */
    private static List<String> segments(String q) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < q.length(); ) {
            char c = q.charAt(i);
            if (!separator(c)) { cur.append(c); i++; continue; }
            int end = i;
            while (end < q.length() && separator(q.charAt(end))) end++;
            if (Character.isWhitespace(c) && glued(q, i, end)) cur.append(' ');
            else if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            i = end;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static boolean separator(char c) {
        return c == '、' || c == ',' || c == '，' || c == ';' || c == '；' || Character.isWhitespace(c);
    }

    /** Whether the space from i to end sits between two ASCII letters or digits: 「MINI MAX」, 「AAPL 200」. */
    private static boolean glued(String q, int i, int end) {
        return i > 0 && end < q.length() && asciiWord(q.charAt(i - 1)) && asciiWord(q.charAt(end));
    }

    private static boolean asciiWord(char c) { return c < 128 && Character.isLetterOrDigit(c); }

    private static void split(String part, List<String> out) {
        Matcher m = JOIN.matcher(part);
        while (m.find()) {
            String left = part.substring(0, m.start()), right = part.substring(m.end());
            if (left.isEmpty() || right.isEmpty()) continue;
            if (known(left) == null && unlisted(left) == null && known(right) == null && unlisted(right) == null) continue;
            split(left, out);
            split(right, out);
            return;
        }
        out.add(part);
    }

    /** What a request's names came to: the codes to ask for, and the names that are not listed, were not found, or came
     * after the six a request looks up. */
    public static final class Found {
        public final Set<String> codes = new LinkedHashSet<>();
        /** 「华为没有上市，没有股价」 for each company that is not listed. */
        public final List<String> notListed = new ArrayList<>();
        /** The names no search found, as heard. */
        public final List<String> notFound = new ArrayList<>();
        /** The names past the sixth, which this request does not look up. */
        public final List<String> skipped = new ArrayList<>();
        /** The names in notFound a by-sound retry found after all: they are looked up, so they are no longer missing. */
        public final List<String> foundBySound = new ArrayList<>();
    }

    private static final int MOST = 6;

    /**
     * The codes a request's names come to, looked up with the given feed. The whole request is tried first as one name —
     * 「MINI MAX」, 「标普 500」, 「苹果 AAPL」 are one thing before they are two — and only when that finds nothing is it
     * cut into names (parts). A name no search finds is left out and recorded in notFound instead of failing the request
     * (2026-11-16: 「MINI 和 QQZZ 的股价」 used to fail whole and say nothing about MINI). Codes are deduplicated: 「苹果
     * AAPL」 is AAPL once, not read out twice. At most six names are looked up; the rest are recorded in skipped rather
     * than dropped, and a company that is not listed is still said so after six (its sentence needs no lookup).
     */
    public static Found resolve(String query, Feeds.Fetch feed) throws IOException {
        Found r = new Found();
        String q = query == null ? "" : query.trim();
        String whole = q.replaceAll("[\\s　]", "");
        String noQuote = unlisted(whole);
        if (noQuote != null) { r.notListed.add(noQuote); return r; }
        if (!whole.isEmpty()) {
            List<String> known = known(whole);
            if (known != null) { r.codes.addAll(known); return r; }
            String hit = pick(search(whole, feed), whole);
            if (hit != null) { r.codes.add(hit); return r; }
        }
        List<String> names = parts(q);
        if (names.isEmpty()) names = Collections.singletonList("大盘");
        for (String part : names) if (!part.isEmpty()) look(part, r, feed);
        return r;
    }

    /** One name of a request into the codes or the lists. */
    private static void look(String part, Found r, Feeds.Fetch feed) throws IOException {
        // 华为 is not listed: say so, instead of searching by sound and reading out 华微电子's price
        String noQuote = unlisted(part);
        if (noQuote != null) { r.notListed.add(noQuote); return; }
        if (r.codes.size() >= MOST) { r.skipped.add(part); return; }
        List<String> known = known(part);
        if (known != null) { r.codes.addAll(known); return; }
        String hit = pick(search(part, feed), part);
        if (hit != null) r.codes.add(hit);
        else r.notFound.add(part);
    }

    private static String search(String text, Feeds.Fetch feed) throws IOException {
        return feed.get(SEARCH + java.net.URLEncoder.encode(text, "UTF-8"), "UTF-8", 6000);
    }

    /** The search answer's items as 「market, code, name, initials, type」, or null when the search found nothing
     * (v_hint="N";): the backslash-u escapes are undone, as the TV receives them. */
    private static List<String[]> items(String searchText) {
        String body = searchText == null ? "" : searchText;
        int eq = body.indexOf('=');
        if (eq < 0) return null;
        String hint = body.substring(eq + 1).trim();
        if (hint.endsWith(";")) hint = hint.substring(0, hint.length() - 1);
        if (hint.startsWith("\"")) hint = hint.substring(1);
        if (hint.endsWith("\"")) hint = hint.substring(0, hint.length() - 1);
        Matcher u = ESCAPE.matcher(hint);
        StringBuffer sb = new StringBuffer();
        while (u.find()) u.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(u.group(1), 16))));
        u.appendTail(sb);
        hint = sb.toString();
        if (hint.isEmpty() || "N".equals(hint)) return null;
        List<String[]> out = new ArrayList<>();
        String[] items = hint.split("\\^");
        for (int i = 0; i < items.length; i++) {
            String[] f = items[i].split("~");
            if (f.length < 5) continue;
            out.add(f);
        }
        return out;
    }

    /** How well an item fits pick's order: lower is better, -1 when it is never taken (a fund, an ETF, a warrant, a
     * leveraged or inverse product, a US OTC listing, Hong Kong's RMB counter). */
    private static int kind(String market, String code, String name, String type, boolean index) {
        int kind = type.startsWith("GP-A") ? 0 : "GP".equals(type) ? ("hk".equals(market) ? 1 : 2) : "ZS".equals(type) ? 3 : -1;
        // OTC listings in the US (.ps) are rarely what anyone means: 小米集团 is Hong Kong's 01810, not XIACF
        if (kind < 0 || LEVERED.matcher(name).find() || ("us".equals(market) && code.endsWith(".ps"))) return -1;
        if (index) kind = kind == 3 ? 0 : kind + 10;
        else if (kind == 3) kind = 5;
        if (name.endsWith("r") || name.endsWith("R")) kind += 20;
        return kind;
    }

    private static final Pattern LEVERED = Pattern.compile("做空|做多|[0-9两三]倍|杠杆|反向|期权|收益增强");
    /** Hong Kong's share-class marks (小米集团w, 腾讯音乐sw) and 「(adr)」, left out when the name is compared. */
    private static final Pattern SUFFIX = Pattern.compile("(?i)(-?s?w|\\(adr\\)|-s)$");

    static String qtCode(String market, String code) {
        if ("us".equals(market)) {
            int dot = code.indexOf('.');
            return "us" + (dot > 0 ? code.substring(0, dot) : code).toUpperCase(Locale.ROOT);
        }
        return market + code;
    }

    public static final class Quote {
        public final String market, code, name, time, price, change, pct;
        final double changeValue;

        Quote(String market, String code, String name, String time, String price, String change, String pct) {
            this.market = market; this.code = code; this.name = name; this.time = time; this.price = price; this.change = change;
            this.pct = pct;
            double c;
            try { c = Double.parseDouble(change); } catch (NumberFormatException e) { c = 0; }
            this.changeValue = c;
        }

        public boolean index() {
            return ("sh".equals(market) && code.startsWith("000")) || ("sz".equals(market) && code.startsWith("399"))
                    || ("hk".equals(market) && !code.matches("\\d+")) || ("us".equals(market) && code.startsWith("."));
        }

        String unit() { return index() ? "点" : "us".equals(market) ? "美元" : "hk".equals(market) ? "港元" : "元"; }

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("name", name).put("code", market + code).put("price", price).put("change", change)
                    .put("percent", pct).put("unit", unit()).put("time", time);
        }
    }

    /** Tencent's answer, one line per code: 「v_sh600519="1~贵州茅台~600519~1453.08~1452.00~…"」. Unknown codes
     * (v_pv_none_match) are left out. */
    public static List<Quote> parse(String text) {
        List<Quote> out = new ArrayList<>();
        for (String raw : (text == null ? "" : text).split("\n")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (!line.startsWith("v_") || eq < 0) continue;
            String key = line.substring(2, eq), body = line.substring(eq + 1).trim();
            if (body.endsWith(";")) body = body.substring(0, body.length() - 1);
            if (body.startsWith("\"")) body = body.substring(1);
            if (body.endsWith("\"")) body = body.substring(0, body.length() - 1);
            String[] f = body.split("~", -1);
            if (f.length < 35 || key.length() < 3 || f[3].isEmpty()) continue;
            String market = key.substring(0, 2).toLowerCase(Locale.ROOT);
            if (!Arrays.asList("sh", "sz", "bj", "hk", "us").contains(market)) continue;
            double price;
            try { price = Double.parseDouble(f[3]); } catch (NumberFormatException e) { continue; }
            if (price <= 0) continue;
            out.add(new Quote(market, f[2], spokenName(f[1]), f[30], trim(f[3]), trim(f[31]), trim(f[32])));
        }
        return out;
    }

    /** 「五 粮 液」→「五粮液」 (three-character names come padded), 「阿里巴巴-W」「蔚来-SW」「谷歌-C」 without the share
     * class, which would be read out as letters. */
    static String spokenName(String name) {
        String n = name == null ? "" : name.trim();
        n = n.replaceAll("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])", "");
        return n.replaceAll("-(?:SW|W|S|C|R|U)$", "");
    }

    /** 「423.000」→「423.00」, 「86.3」→「86.30」, 「-1.440」→「-1.44」: two decimals unless more say something. */
    static String trim(String s) {
        String t = s == null ? "" : s.trim();
        int dot = t.indexOf('.');
        if (dot < 0) return t.isEmpty() ? "0" : t;
        while (t.length() > dot + 3 && t.endsWith("0")) t = t.substring(0, t.length() - 1);
        while (t.length() < dot + 3) t += "0";
        return t;
    }

    /**
     * 「上证指数 3636.80 点，跌 1.44%，跌了 53.11 点；贵州茅台 1453.08 元，涨 0.07%（9 点 49 分）」: the quote's own time
     * after each market's last quote — today's time, 「今天收盘」, the day it closed, or New York time for the US.
     */
    public static String speak(List<Quote> quotes, Calendar now) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < quotes.size(); i++) {
            Quote q = quotes.get(i);
            if (s.length() > 0) s.append("；");
            s.append(q.name).append(" ").append(q.price).append(" ").append(q.unit()).append("，");
            String pct = q.pct.startsWith("-") ? q.pct.substring(1) : q.pct, ch = q.change.startsWith("-") ? q.change.substring(1) : q.change;
            if (q.changeValue > 0) s.append("涨 ").append(pct).append("%");
            else if (q.changeValue < 0) s.append("跌 ").append(pct).append("%");
            else s.append("和上个交易日收盘持平");
            if (q.index() && q.changeValue != 0) s.append("，").append(q.changeValue > 0 ? "涨了 " : "跌了 ").append(ch).append(" 点");
            String when = when(q, now);
            boolean last = i == quotes.size() - 1 || !when.equals(when(quotes.get(i + 1), now));
            if (last && !when.isEmpty()) s.append("（").append(when).append("）");
        }
        return s.toString();
    }

    private static final Pattern STAMP = Pattern.compile("(\\d{4})[-/]?(\\d{2})[-/]?(\\d{2})[ T]?(\\d{2}):?(\\d{2})");

    /** When the quote is from: 「9 点 49 分」「今天收盘」「11 月 5 日收盘」「美东时间 11 月 5 日收盘」. */
    static String when(Quote q, Calendar now) {
        Matcher m = STAMP.matcher(q.time == null ? "" : q.time);
        if (!m.find()) return "";
        int mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(m.group(3)), h = Integer.parseInt(m.group(4)), mi = Integer.parseInt(m.group(5));
        int close = "hk".equals(q.market) ? 16 : "us".equals(q.market) ? 16 : 15;
        boolean closed = h >= close;
        if ("us".equals(q.market)) return "美东时间 " + mo + " 月 " + d + " 日" + (closed ? "收盘" : " " + h + " 点 " + mi + " 分");
        boolean today = mo == now.get(Calendar.MONTH) + 1 && d == now.get(Calendar.DAY_OF_MONTH);
        if (!today) return mo + " 月 " + d + " 日收盘";
        return closed ? "今天收盘" : h + " 点" + (mi == 0 ? "整" : " " + mi + " 分");
    }
}
