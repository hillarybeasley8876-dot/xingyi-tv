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
 * 「CCTV5 现在在放什么」「今晚湖南卫视有什么节目」「新闻联播几点开始」 (2026-11-06): the listings of CCTV's channels and the
 * main satellite channels from CCTV's own programme guide (api.cntv.cn, public, by channel and day). Beijing time.
 */
public final class TvGuide {
    private TvGuide() { }

    public static final TimeZone BEIJING = TimeZone.getTimeZone("Asia/Shanghai");

    /** Satellite channels by the province people say: {name, code}. */
    private static final String[][] SATELLITE = {
            {"湖南", "hunan"}, {"东方", "dongfang"}, {"上海", "dongfang"}, {"浙江", "zhejiang"}, {"江苏", "jiangsu"}, {"北京", "btv1"},
            {"天津", "tianjin"}, {"山东", "shandong"}, {"安徽", "anhui"}, {"广东", "guangdong"}, {"湖北", "hubei"}, {"东南", "dongnan"},
            {"福建", "dongnan"}, {"黑龙江", "heilongjiang"}, {"辽宁", "liaoning"}, {"重庆", "chongqing"}, {"四川", "sichuan"},
            {"河北", "hebei"}, {"河南", "henan"}, {"江西", "jiangxi"}, {"广西", "guangxi"}, {"深圳", "shenzhen"}};
    /** CCTV's channels by their number, and what each is called. */
    private static final String[][] CCTV = {
            {"1", "cctv1", "CCTV-1 综合"}, {"2", "cctv2", "CCTV-2 财经"}, {"3", "cctv3", "CCTV-3 综艺"}, {"4", "cctv4", "CCTV-4 中文国际"},
            {"5", "cctv5", "CCTV-5 体育"}, {"5+", "cctv5plus", "CCTV-5+ 体育赛事"}, {"6", "cctv6", "CCTV-6 电影"}, {"7", "cctv7", "CCTV-7 国防军事"},
            {"8", "cctv8", "CCTV-8 电视剧"}, {"9", "cctvjilu", "CCTV-9 纪录"}, {"10", "cctv10", "CCTV-10 科教"}, {"11", "cctv11", "CCTV-11 戏曲"},
            {"12", "cctv12", "CCTV-12 社会与法"}, {"13", "cctv13", "CCTV-13 新闻"}, {"14", "cctvchild", "CCTV-14 少儿"}, {"15", "cctv15", "CCTV-15 音乐"},
            {"16", "cctv16", "CCTV-16 奥林匹克"}, {"17", "cctv17", "CCTV-17 农业农村"}};
    /** The CCTV channels people name by what they show. */
    private static final String[][] TOPICS = {
            {"综合频道", "1"}, {"财经频道", "2"}, {"综艺频道", "3"}, {"中文国际", "4"}, {"体育赛事频道", "5+"}, {"体育频道", "5"},
            {"电影频道", "6"}, {"军事频道", "7"}, {"电视剧频道", "8"}, {"纪录频道", "9"}, {"科教频道", "10"}, {"戏曲频道", "11"},
            {"社会与法", "12"}, {"新闻频道", "13"}, {"少儿频道", "14"}, {"音乐频道", "15"}, {"奥林匹克频道", "16"}, {"农业农村", "17"}};

    private static final Pattern CCTV_NO = Pattern.compile("(?i)(?:cctv|央视|中央台|中央电视台)[-－\\s]*(?:第)?([0-9一二三四五六七八九十]{1,3})(\\+|加|plus)?(?:套|台|频道)?");
    private static final Pattern CCTV_NO2 = Pattern.compile("(?:央视|中央)([0-9一二三四五六七八九十]{1,3})(\\+|加)?(?:套|台)");

    /** {code, name} for 「CCTV5」「央视五套」「中央5台」「CCTV-5+」「体育频道」「湖南卫视」, or null. */
    public static String[] channel(String said) {
        String s = said == null ? "" : said.trim().replaceAll("[\\s　]", "");
        if (s.isEmpty()) return null;
        for (String[] t : SATELLITE) if (s.contains(t[0] + "卫视") || s.equals(t[0] + "台")) return new String[]{t[1], t[0] + "卫视"};
        Matcher m = CCTV_NO2.matcher(s);
        if (!m.find()) { m = CCTV_NO.matcher(s); if (!m.find()) m = null; }
        if (m != null) {
            int n = number(m.group(1));
            String key = n + (m.group(2) != null && n == 5 ? "+" : "");
            for (String[] c : CCTV) if (c[0].equals(key)) return new String[]{c[1], c[2]};
            return null;
        }
        for (String[] t : TOPICS) if (s.contains(t[0])) for (String[] c : CCTV) if (c[0].equals(t[1])) return new String[]{c[1], c[2]};
        return null;
    }

    /** 这台电视上没有能看电视直播的应用（2026-11-11 校准）：放不了这个台，后面接 speakNow 说的现在在播什么。 */
    public static String cannotWatch(String name, String now) {
        String said = "这台电视上没有能看电视直播的应用，放不了" + name + "。";
        if (now == null || now.isEmpty()) return said;
        // the channel was just named: 「它现在在播…」, not its name a second time
        return said + (now.startsWith(name) ? "它" + now.substring(name.length()) : now);
    }

    static int number(String s) {
        if (s.matches("[0-9]+")) return Integer.parseInt(s);
        int ten = s.indexOf('十');
        String digits = "零一二三四五六七八九";
        if (ten < 0) return digits.indexOf(s.charAt(0));
        int high = ten == 0 ? 1 : digits.indexOf(s.charAt(0)), low = ten == s.length() - 1 ? 0 : digits.indexOf(s.charAt(ten + 1));
        return high * 10 + low;
    }

    public static String url(String code, Calendar day) {
        return "https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=" + code + "&serviceId=tvcctv&d="
                + String.format(Locale.ROOT, "%04d%02d%02d", day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
    }

    static final class Program {
        final String title;
        final long start, end;

        Program(String title, long start, long end) { this.title = title; this.start = start; this.end = end; }
    }

    private static final Pattern EPISODE_CODE = Pattern.compile("-\\d{4}-\\d{1,4}$");

    /** 「今日说法-2026-202」→「今日说法」; recaps (前情提要) are left out of the list. */
    static String clean(String title) {
        String t = (title == null ? "" : title).replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
        return EPISODE_CODE.matcher(t).replaceAll("").trim();
    }

    static List<Program> programs(String json, String code) throws JSONException {
        JSONObject data = new JSONObject(json).optJSONObject("data");
        JSONObject ch = data == null ? null : data.optJSONObject(code);
        if (ch == null) throw new JSONException("节目单里没有这个频道");
        JSONArray list = ch.optJSONArray("list");
        List<Program> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject p = list.getJSONObject(i);
            String t = clean(News.str(p, "title"));
            if (t.isEmpty() || t.startsWith("前情提要")) continue;
            out.add(new Program(t, p.optLong("startTime") * 1000, p.optLong("endTime") * 1000));
        }
        return out;
    }

    static String hm(long at) {
        Calendar c = Calendar.getInstance(BEIJING);
        c.setTimeInMillis(at);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return h + " 点" + (m == 0 ? "" : " " + m + " 分");
    }

    /** 「CCTV-5 体育现在在播《实况录像-…》（12 点 55 分开始，14 点 25 分结束）；接下来 14 点 25 分《体育新闻》，…」. */
    public static String speakNow(String name, String json, String code, long now) throws JSONException {
        return speakNow(name, json, code, now, 3);
    }

    /** The same with at most `upcoming` programmes after the one that is on: a channel that cannot be watched here is
     * told with one (2026-11-11 on the TV: three long sports titles after 「放不了」 were fifteen seconds of talk). */
    public static String speakNow(String name, String json, String code, long now, int upcoming) throws JSONException {
        List<Program> ps = programs(json, code);
        Program on = null;
        int at = -1;
        for (int i = 0; i < ps.size(); i++) if (ps.get(i).start <= now && now < ps.get(i).end) { on = ps.get(i); at = i; }
        StringBuilder s = new StringBuilder();
        if (on != null) s.append(name).append("现在在播《").append(on.title).append("》（").append(hm(on.start)).append("开始，")
                .append(hm(on.end)).append("结束）");
        else s.append(name).append("这会儿节目单上没有在播的节目");
        int shown = 0;
        for (int i = at + 1; i < ps.size() && shown < upcoming; i++) {
            if (ps.get(i).start < now) continue;
            s.append(shown == 0 ? "；接下来 " : "，").append(hm(ps.get(i).start)).append("《").append(ps.get(i).title).append("》");
            shown++;
        }
        return s.toString();
    }

    /** The programmes starting between from and to (hours, Beijing time) that day: 「今晚 CCTV-1 综合：19 点《新闻联播》；…」. */
    public static String speakPart(String name, String json, String code, int fromHour, int toHour, String label) throws JSONException {
        List<Program> ps = programs(json, code);
        StringBuilder s = new StringBuilder();
        int shown = 0, more = 0;
        for (Program p : ps) {
            Calendar c = Calendar.getInstance(BEIJING);
            c.setTimeInMillis(p.start);
            int h = c.get(Calendar.HOUR_OF_DAY);
            if (h < fromHour || h >= toHour) continue;
            if (shown >= 8) { more++; continue; }
            s.append(shown == 0 ? "" : "；").append(hm(p.start)).append("《").append(p.title).append("》");
            shown++;
        }
        if (shown == 0) return label + name + "的节目单上没有节目";
        return label + name + "：" + s + (more > 0 ? "；之后还有 " + more + " 个节目" : "");
    }

    /**
     * A programme by name that day: 「《新闻联播》今天在CCTV-1 综合播出：19 点到 19 点 39 分」 — the airings still to come (or
     * on now); the reruns already over only when nothing is left (「今天已经播完了：…」). Null when not found.
     */
    public static String find(String name, String json, String code, String query, String dayWord, long now) throws JSONException {
        String q = query.replaceAll("[《》\\s]", "");
        List<String> coming = new ArrayList<>(), over = new ArrayList<>();
        for (Program p : programs(json, code)) {
            if (!p.title.replaceAll("\\s", "").contains(q)) continue;
            String when = hm(p.start) + "到 " + hm(p.end);
            if (p.end <= now) over.add(when);
            else coming.add(when + (p.start <= now ? "（正在播）" : ""));
        }
        if (coming.isEmpty() && over.isEmpty()) return null;
        if (coming.isEmpty())
            return "《" + q + "》" + dayWord + "在" + name + "已经播完了：" + String.join("；", over.subList(Math.max(0, over.size() - 2), over.size()));
        return "《" + q + "》" + dayWord + "在" + name + "播出：" + String.join("；", coming.subList(0, Math.min(3, coming.size())));
    }
}
