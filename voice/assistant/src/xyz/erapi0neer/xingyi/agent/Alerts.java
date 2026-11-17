package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「现在有什么天气预警」「有台风吗」「台风离我们多远」 (2026-11-07): the National Meteorological Center's warnings by province
 * (every county's) and its typhoon list with each storm's track, without a key. The weather tool's forecast
 * (Open-Meteo) has no Chinese warnings; a web search took 7-9 s and often read old ones.
 */
public final class Alerts {
    private Alerts() { }

    public static String alarmUrl(String province) {
        try {
            return "https://www.nmc.cn/rest/findAlarm?pageNo=1&pageSize=50&signaltype=&signallevel=&province="
                    + java.net.URLEncoder.encode(full(province), "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final String TYPHOONS = "https://typhoon.nmc.cn/weatherservice/typhoon/jsons/list_default";

    public static String trackUrl(long id) { return "https://typhoon.nmc.cn/weatherservice/typhoon/jsons/view_" + id; }

    /**
     * The addresses one answer needs, fetched at the same time and kept ten minutes (2026-11-10 sweep: 「最近有台风吗」
     * took 7.5 s with the storm list, its track and the province's alarms one after another; a quick command it is, with
     * no model round). Each fetch keeps the timeout it had on its own, and a second ask within the ten minutes goes out
     * for nothing.
     */
    public static final class Fetches {
        /** How long an answer is kept. */
        public static final long KEEP = 10 * 60_000L;

        private final Feeds.Fetch f;

        public Fetches(Feeds.Fetch raw) { this(raw, KEEP, System::currentTimeMillis); }

        Fetches(Feeds.Fetch raw, long keepMs, java.util.function.LongSupplier clock) { f = Feeds.cached(raw, keepMs, clock); }

        /** One address. */
        public String one(String url, int timeoutMs) throws IOException { return f.get(url, "UTF-8", timeoutMs); }

        /**
         * These addresses at the same time, in the order they were given. The first failure is thrown once they have all
         * ended (or once the slowest has had its timeout and a moment more), so a hang is an error and not a wait.
         */
        public List<String> many(List<String> urls, final int timeoutMs) throws IOException {
            final String[] bodies = new String[urls.size()];
            final IOException[] failed = new IOException[1];
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                final int k = i;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try { bodies[k] = f.get(urls.get(k), "UTF-8", timeoutMs); }
                        catch (IOException e) { synchronized (failed) { if (failed[0] == null) failed[0] = e; } }
                    }
                });
                t.setDaemon(true);
                t.start();
                threads.add(t);
            }
            for (Thread t : threads) {
                try { t.join(timeoutMs + 2000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("查气象预警被打断了"); }
                if (t.isAlive()) throw new IOException("中央气象台没连上");
            }
            synchronized (failed) { if (failed[0] != null) throw failed[0]; }
            List<String> out = new ArrayList<>();
            for (String b : bodies) out.add(b);
            return out;
        }
    }

    /** 上海 → 上海市, 广西 → 广西壮族自治区, 浙江 → 浙江省 (the names the center files them under). */
    static String full(String province) {
        switch (province) {
            case "北京": case "天津": case "上海": case "重庆": return province + "市";
            case "内蒙古": case "西藏": return province + "自治区";
            case "广西": return "广西壮族自治区";
            case "宁夏": return "宁夏回族自治区";
            case "新疆": return "新疆维吾尔自治区";
            default: return province + "省";
        }
    }

    public static final class Alarm {
        /** Who issued it without the province (徐汇区, 普洱市景谷傣族彝族自治县; 上海中心气象台 for the province's own). */
        public final String issuer, signal;
        public final long at;

        Alarm(String issuer, String signal, long at) { this.issuer = issuer; this.signal = signal; this.at = at; }
    }

    private static final java.util.regex.Pattern TITLE = java.util.regex.Pattern.compile("^(.*?)气象台发布(.+?)预警信号?$");

    /** The warnings in force, newest first, one per issuer and kind. */
    public static List<Alarm> alarms(String json, String province) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (o.optInt("code", -1) != 0) throw new JSONException("中央气象台的预警没有返回结果：" + o.optString("msg"));
        JSONObject page = o.getJSONObject("data").optJSONObject("page");
        JSONArray list = page == null ? null : page.optJSONArray("list");
        List<Alarm> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String fullName = full(province);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.ROOT);
        f.setTimeZone(Sports.BEIJING);
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            java.util.regex.Matcher m = TITLE.matcher(News.str(a, "title").trim());
            if (!m.matches()) continue;
            String who = m.group(1).trim(), what = m.group(2).trim();
            String local = who.startsWith(fullName) ? who.substring(fullName.length()) : who.startsWith(province) ? who.substring(province.length()) : who;
            String issuer = local.isEmpty() || "中心".equals(local) || "省".equals(local) ? who + "气象台" : local;
            if (!seen.add(issuer + "|" + what)) continue;
            long at;
            try { at = f.parse(News.str(a, "issuetime")).getTime(); } catch (java.text.ParseException e) { at = 0; }
            out.add(new Alarm(issuer, what, at));
        }
        return out;
    }

    /** The kinds of warnings among these, most first: 「暴雨、雷电」. */
    private static String kinds(List<Alarm> as) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (Alarm a : as) {
            String k = a.signal.replaceAll("(红|橙|黄|蓝|白)色$", "");
            n.put(k, n.containsKey(k) ? n.get(k) + 1 : 1);
        }
        List<Map.Entry<String, Integer>> e = new ArrayList<>(n.entrySet());
        e.sort((x, y) -> y.getValue() - x.getValue());
        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(3, e.size()); i++) top.add(e.get(i).getKey());
        return String.join("、", top);
    }

    /**
     * 「上海现在没有生效的气象预警」; 「上海现在有 3 条气象预警：徐汇区发布了暴雨黄色预警（今天凌晨 2 点 51 分）；…；另外还有 1 条，是雷电预警」.
     * local: the place's own (the TV's district, a city asked about) read first with the province's own; the rest counted.
     */
    public static String speakAlarms(List<Alarm> all, String province, String local, long now) {
        if (all.isEmpty()) return province + "现在没有生效的气象预警";
        List<Alarm> first = new ArrayList<>(), rest = new ArrayList<>();
        for (Alarm a : all) {
            boolean own = a.issuer.endsWith("气象台") || (!local.isEmpty() && a.issuer.contains(local));
            (own ? first : rest).add(a);
        }
        // nothing of the place's own, or no place: the newest few too, so something is said
        if (first.isEmpty() || local.isEmpty()) while (first.size() < 3 && !rest.isEmpty()) first.add(rest.remove(0));
        StringBuilder s = new StringBuilder(province + "现在有 " + all.size() + " 条气象预警：");
        for (int i = 0; i < Math.min(4, first.size()); i++) {
            Alarm a = first.get(i);
            s.append(i == 0 ? "" : "；").append(a.issuer).append("发布了").append(a.signal).append("预警");
            if (a.at > 0) s.append("（").append(Sports.when(a.at, now)).append("）");
        }
        int more = all.size() - Math.min(4, first.size());
        if (more > 0) {
            List<Alarm> others = new ArrayList<>(rest);
            for (int i = 4; i < first.size(); i++) others.add(first.get(i));
            s.append("；另外还有 ").append(more).append(" 条，").append(more == 1 ? "是" : "多是").append(kinds(others)).append("预警");
        }
        return s.toString();
    }

    public static final class Storm {
        public final long id;
        public final String number, name;
        public final boolean active;

        Storm(long id, String number, String name, boolean active) { this.id = id; this.number = number; this.name = name; this.active = active; }
    }

    /** typhoon_jsons_list_default(({…})) → the JSON inside. */
    private static String unwrap(String jsonp) {
        String s = jsonp == null ? "" : jsonp.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : s;
    }

    public static List<Storm> storms(String jsonp) throws JSONException {
        JSONArray list = new JSONObject(unwrap(jsonp)).getJSONArray("typhoonList");
        List<Storm> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONArray t = list.getJSONArray(i);
            out.add(new Storm(t.getLong(0), t.optString(3), t.optString(2).trim(), !"stop".equals(t.optString(7))));
        }
        return out;
    }

    public static final class Point {
        public final long at;
        public final String grade, direction;
        public final double lon, lat, wind;
        public final int pressure, speed;

        Point(long at, String grade, double lon, double lat, int pressure, double wind, String direction, int speed) {
            this.at = at; this.grade = grade; this.lon = lon; this.lat = lat; this.pressure = pressure; this.wind = wind;
            this.direction = direction; this.speed = speed;
        }
    }

    /** A storm's latest position from its track. */
    public static Point last(String jsonp) throws JSONException {
        JSONArray t = new JSONObject(unwrap(jsonp)).getJSONArray("typhoon"), points = t.getJSONArray(8);
        JSONArray p = points.getJSONArray(points.length() - 1);
        return new Point(p.getLong(2), p.optString(3), p.optDouble(4), p.optDouble(5), p.optInt(6), p.optDouble(7), p.optString(8), p.optInt(9));
    }

    private static final Map<String, String> GRADES = new LinkedHashMap<>();
    private static final Map<String, String> WAYS = new LinkedHashMap<>();
    static {
        GRADES.put("TD", "热带低压"); GRADES.put("TS", "热带风暴"); GRADES.put("STS", "强热带风暴"); GRADES.put("TY", "台风");
        GRADES.put("STY", "强台风"); GRADES.put("SuperTY", "超强台风");
        String[][] w = {{"N", "北"}, {"NNE", "北偏东"}, {"NE", "东北"}, {"ENE", "东偏北"}, {"E", "东"}, {"ESE", "东偏南"}, {"SE", "东南"}, {"SSE", "南偏东"},
                {"S", "南"}, {"SSW", "南偏西"}, {"SW", "西南"}, {"WSW", "西偏南"}, {"W", "西"}, {"WNW", "西偏北"}, {"NW", "西北"}, {"NNW", "北偏西"}};
        for (String[] x : w) WAYS.put(x[0], x[1]);
    }

    /** Metres a second → the force a Chinese bulletin says, to 17 (GB/T 19201). */
    static int force(double ms) {
        double[] top = {0.2, 1.5, 3.3, 5.4, 7.9, 10.7, 13.8, 17.1, 20.7, 24.4, 28.4, 32.6, 36.9, 41.4, 46.1, 50.9, 56.0, 61.2};
        for (int b = 0; b < top.length; b++) if (ms <= top[b]) return b;
        return 18;
    }

    /**
     * 「第 31 号台风「烟花」（强台风级）现在在北纬 15.6 度、东经 158.3 度，离这里约 4100 公里；中心附近最大风力 15 级（48 米每秒），
     * 中心气压 945 百帕，正以每小时 15 公里向西偏北移动（今天凌晨 2 点）」.
     */
    public static String speakStorm(Storm s, Point p, double lat, double lon, long now) {
        // 2631 is the year's 31st; a nameless depression (20260027) is not numbered when spoken
        String no = s.number.matches("[0-9]{4}") ? String.valueOf(Integer.parseInt(s.number.substring(2))) : "";
        String grade = GRADES.getOrDefault(p.grade, p.grade);
        boolean named = !no.isEmpty() && !s.name.isEmpty() && !"热带低压".equals(s.name);
        StringBuilder b = new StringBuilder(named ? "第 " + no + " 号台风「" + s.name + "」（" + grade + "级）" : "一个" + grade)
                .append("现在在北纬 ").append(String.format(java.util.Locale.ROOT, "%.1f", p.lat)).append(" 度、东经 ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", p.lon)).append(" 度");
        if (!Double.isNaN(lat) && !Double.isNaN(lon)) b.append("，离这里约 ").append(Math.round(Quake.km(lat, lon, p.lat, p.lon) / 10) * 10).append(" 公里");
        int f = force(p.wind);
        b.append("；中心附近最大风力 ").append(f > 17 ? "17 级以上" : f + " 级").append("（").append(Math.round(p.wind)).append(" 米每秒），中心气压 ")
                .append(p.pressure).append(" 百帕");
        String way = WAYS.get(p.direction);
        if (way != null && p.speed > 0) b.append("，正以每小时 ").append(p.speed).append(" 公里向").append(way).append("移动");
        return b.append("（").append(Sports.when(p.at, now)).append("）").toString();
    }
}
