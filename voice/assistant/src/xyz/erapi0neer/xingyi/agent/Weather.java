package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.util.*;
import org.json.*;

/**
 * weather: the forecast from Open-Meteo (public, no key; the ECMWF/GFS/CMA models) as a sentence to speak —
 * 「上海徐汇明天（11 月 7 日 星期六）阴，16 到 18 度，降水概率 20%，东风 4 级」 — in about a second, where web_search took
 * 6–8 s and read out which of its sources disagreed (2026-11-06 overnight pass: the most asked question on a TV, and
 * every mainstream assistant answers it from its own weather source). Air quality on request: China's AQI worked out
 * from the hour's PM2.5 and PM10 with the HJ 633 breakpoints. Typhoons, warnings and official notices stay with
 * web_search. Pure: the host does the HTTP (Get).
 */
public final class Weather {
    private Weather() { }

    /** One GET; the body, or an IOException. */
    public interface Get { String get(String url, int timeoutMs) throws IOException; }

    /** GET over HTTPS with the platform's certificate checks; a body over 256 KB or an HTTP error is an IOException. */
    public static Get https() {
        return (url, timeoutMs) -> {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            boolean read = false;
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(Math.min(6000, timeoutMs));
                c.setReadTimeout(timeoutMs);
                c.setRequestProperty("Accept", "application/json");
                int status = c.getResponseCode();
                if (status != 200) throw new IOException("HTTP " + status);
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) != -1; ) {
                        out.write(buf, 0, n);
                        if (out.size() > 256 * 1024) throw new IOException("天气服务的回复太大");
                    }
                    read = true;
                    return new String(out.toByteArray(), "UTF-8");
                }
            } finally {
                // a fully read answer leaves the socket to the pool (the next question skips TCP and TLS); else close
                if (!read) c.disconnect();
            }
        };
    }

    /** Answers kept for a while: a forecast or the air for 10 minutes (the models update every quarter hour), a
     * place's coordinates for a day. warm() fills it while XiaoAi is still listening, so 「天气怎么样」 is answered from
     * here without waiting on the network. At most 32 answers. */
    private static final Map<String, Object[]> CACHE = new LinkedHashMap<String, Object[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) { return size() > 32; }
    };

    static long ttl(String url) { return url.startsWith(GEO) ? 86_400_000L : 600_000L; }

    /** http with the cache in front; failures are not kept. */
    public static Get cached(Get http) { return cached(http, System::currentTimeMillis); }

    static Get cached(Get http, java.util.function.LongSupplier clock) {
        return (url, timeoutMs) -> {
            long now = clock.getAsLong();
            synchronized (CACHE) {
                Object[] hit = CACHE.get(url);
                if (hit != null && now - (Long) hit[0] < ttl(url)) return (String) hit[1];
            }
            String body = http.get(url, timeoutMs);
            synchronized (CACHE) { CACHE.put(url, new Object[]{now, body}); }
            return body;
        };
    }

    /** For the checks: forget every kept answer. */
    static void clearCache() { synchronized (CACHE) { CACHE.clear(); } }

    private static final java.util.concurrent.atomic.AtomicLong WARMED = new java.util.concurrent.atomic.AtomicLong();

    /** XiaoAi is still listening: open the forecast's connection now, so a weather question finds it ready (2026-11-06:
     * the first forecast of a while took 1.6 s on the TV, a warm one 0.3 s). At most every 2 minutes, off the caller's
     * thread, never throws. The request is the TV's own forecast, which the hint card and 天气 questions want anyway. */
    public static void warm(Where here) {
        long now = System.currentTimeMillis(), last = WARMED.get();
        if (here == null || now - last < 120_000 || !WARMED.compareAndSet(last, now)) return;
        Thread t = new Thread(() -> {
            try { cached(https()).get(forecastUrl(here.lat, here.lon), 6000); } catch (Exception ignored) { }
        }, "xingyi-weather-warm");
        t.setDaemon(true);
        t.start();
    }

    static final String FORECAST = "https://api.open-meteo.com/v1/forecast";
    static final String GEO = "https://geocoding-api.open-meteo.com/v1/search";
    static final String AIR = "https://air-quality-api.open-meteo.com/v1/air-quality";

    /** A place with its coordinates and the name it is said by. */
    public static final class Where {
        public final String name;
        public final double lat, lon;
        public Where(String name, double lat, double lon) { this.name = name; this.lat = lat; this.lon = lon; }
    }

    static String forecastUrl(double lat, double lon) {
        return FORECAST + "?latitude=" + coord(lat) + "&longitude=" + coord(lon) + "&timezone=auto&forecast_days=7"
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,"
                + "wind_speed_10m_max,wind_direction_10m_dominant,sunrise,sunset"
                + "&forecast_hours=48&hourly=temperature_2m,precipitation_probability,weather_code";
    }

    static String airUrl(double lat, double lon) {
        return AIR + "?latitude=" + coord(lat) + "&longitude=" + coord(lon) + "&timezone=auto&current=pm2_5,pm10";
    }

    static String geoUrl(String name) {
        try {
            return GEO + "?name=" + java.net.URLEncoder.encode(name, "UTF-8") + "&count=10&language=zh&format=json";
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String coord(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    // ---- where -------------------------------------------------------------------------------------------

    /** Settings.System mitv_location's coordinates ("latitude":"31.230416"…) with the TV's place name, or null. */
    public static Where fromLocation(String json, String name) {
        if (json == null || name == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            double lat = Double.parseDouble(o.optString("latitude", "x")), lon = Double.parseDouble(o.optString("longitude", "x"));
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) return null;
            return new Where(name, lat, lon);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Map<String, Integer> RANK = new HashMap<>();
    static {
        RANK.put("PPLC", 5); RANK.put("PPLA", 4); RANK.put("PPLA2", 3); RANK.put("PPLA3", 2); RANK.put("PPLA4", 1);
    }

    /**
     * The place a geocoding answer means: the capital or the seat of the highest level, then the most people. A village
     * that only shares the name is no answer (「浦东」 matched villages in Zhejiang and Jiangsu; 「东京」 in Chinese two
     * villages in China): null, and the tool says to ask by the city's name, or a foreign city's English name.
     */
    static Where pick(String json, String asked) throws JSONException {
        JSONArray results = new JSONObject(json).optJSONArray("results");
        JSONObject best = null;
        int bestRank = -1;
        long bestPeople = -1;
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            int rank = RANK.containsKey(r.optString("feature_code")) ? RANK.get(r.optString("feature_code")) : 0;
            long people = r.optLong("population", 0);
            if (rank > bestRank || (rank == bestRank && people > bestPeople)) { best = r; bestRank = rank; bestPeople = people; }
        }
        if (best == null || (bestRank < 2 && bestPeople < 100_000)) return null;
        boolean chinese = false;
        for (int i = 0; asked != null && i < asked.length(); i++) if (asked.charAt(i) >= 0x4E00 && asked.charAt(i) <= 0x9FFF) chinese = true;
        String name = chinese ? asked.trim() : best.optString("name", asked);
        return new Where(name, best.getDouble("latitude"), best.getDouble("longitude"));
    }

    // ---- the forecast as speech ----------------------------------------------------------------------------

    /**
     * The spoken forecast for day: 今天 or "" (with the weather now), 明天, 后天, 大后天, 周末, 这几天 (today and the next
     * two days), or YYYY-MM-DD within the seven days. today is the place's own date (the forecast's first day).
     * @throws IllegalArgumentException with the Chinese reason, for a day the forecast does not cover
     */
    public static String speak(String place, JSONObject forecast, String day) throws JSONException {
        String hours = hoursOf(day == null ? "" : day.trim());
        if (hours != null) return place + hourly(forecast, hours) + "。";
        JSONObject d = forecast.getJSONObject("daily");
        JSONArray times = d.getJSONArray("time");
        if (times.length() == 0) throw new IllegalArgumentException("天气预报是空的");
        long today = parse(times.getString(0));
        List<Integer> which = pickDays(day == null ? "" : day.trim(), today, times.length());
        StringBuilder s = new StringBuilder(place);
        boolean first = true;
        for (int i : which) {
            if (!first) s.append("；");
            first = false;
            long at = today + i;
            s.append(label(i)).append("（").append(Almanac.spokenDate(at)).append(" ").append(Almanac.weekdayText(at)).append("）")
                    .append(dayLine(d, i));
            if (i == 0 && forecast.optJSONObject("current") != null) s.append("，").append(now(forecast.getJSONObject("current")));
        }
        return s.append("。").toString();
    }

    /** 今晚 / 明早 / 明晚 / 今天下午 / 一会儿: a part of a day, from the hourly forecast (48 hours from now). */
    private static String hoursOf(String day) {
        switch (day) {
            case "今晚": case "今天晚上": case "今夜": return "今晚";
            case "明早": case "明天早上": case "明天上午": case "明晨": return "明早";
            case "明晚": case "明天晚上": return "明晚";
            case "下午": case "今天下午": return "今天下午";
            case "一会儿": case "等会儿": case "待会儿": case "接下来": case "接下来几个小时": case "过一会儿": return "接下来几个小时";
            default: return null;
        }
    }

    /** 「今晚（18 点到 24 点）晴，17 到 17 度，降水概率最高 0%」: the hours of a part of a day that the forecast still
     * covers; the worst weather of those hours, the range of temperatures, the highest chance of rain. */
    static String hourly(JSONObject forecast, String which) throws JSONException {
        JSONObject h = forecast.optJSONObject("hourly");
        if (h == null) throw new IllegalArgumentException("这次没拿到逐小时的预报：day 写今天或明天");
        JSONArray times = h.getJSONArray("time");
        if (times.length() == 0) throw new IllegalArgumentException("逐小时预报是空的");
        String first = times.getString(0);
        long day0 = parse(first.substring(0, 10));
        int hour0 = Integer.parseInt(first.substring(11, 13));
        long dayFrom; int from, to;
        switch (which) {
            case "今晚": dayFrom = day0; from = 18; to = 24; break;
            case "明早": dayFrom = day0 + 1; from = 6; to = 10; break;
            case "明晚": dayFrom = day0 + 1; from = 18; to = 24; break;
            case "今天下午": dayFrom = day0; from = 12; to = 18; break;
            default: dayFrom = day0; from = hour0; to = hour0 + 3;
        }
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, rain = -1, worst = -1, n = 0;
        JSONArray temp = h.getJSONArray("temperature_2m"), pp = h.optJSONArray("precipitation_probability"), code = h.getJSONArray("weather_code");
        for (int i = 0; i < times.length(); i++) {
            String t = times.getString(i);
            long day = parse(t.substring(0, 10));
            int hour = Integer.parseInt(t.substring(11, 13)) + (int) (day - dayFrom) * 24;
            if (hour < from || hour >= to) continue;
            n++;
            int c = (int) Math.round(temp.optDouble(i));
            lo = Math.min(lo, c); hi = Math.max(hi, c);
            if (pp != null && !pp.isNull(i)) rain = Math.max(rain, pp.optInt(i));
            worst = Math.max(worst, code.optInt(i, -1));
        }
        if (n == 0) throw new IllegalArgumentException("预报里已经没有" + which + "了");
        String span = "接下来几个小时".equals(which) ? "" : "（" + from + " 点到 " + to + " 点）";
        StringBuilder s = new StringBuilder(which).append(span).append(text(worst)).append("，")
                .append(lo == hi ? lo + " 度" : lo + " 到 " + hi + " 度");
        if (rain >= 0) s.append("，降水概率最高 ").append(rain).append("%");
        return s.toString();
    }

    private static final java.util.regex.Pattern WEEKDAY = java.util.regex.Pattern.compile("^(这个|这|本|下个|下)?(?:周|星期|礼拜)([一二三四五六日天])$");

    private static List<Integer> pickDays(String day, long today, int n) {
        List<Integer> out = new ArrayList<>();
        switch (day) {
            case "这几天": case "最近": case "近几天": case "最近几天": out.add(0); out.add(1); out.add(2); break;
            case "": case "今天": out.add(0); break; // 「天气怎么样」 is today's: three days are too long to listen to
            case "明天": out.add(1); break;
            case "后天": out.add(2); break;
            case "大后天": out.add(3); break;
            case "周末": case "这周末": case "这个周末": {
                int wd = Almanac.weekday(today); // 0 Sunday … 6 Saturday
                int sat = (6 - wd + 7) % 7;
                if (wd == 0) out.add(0);
                else { out.add(sat); out.add(sat + 1); }
                break;
            }
            default: {
                // 「周六」「这周六」「下周一」: weeks start on Monday; nothing said is the coming one
                java.util.regex.Matcher wk = WEEKDAY.matcher(day);
                if (wk.matches()) {
                    int want = "一二三四五六日天".indexOf(wk.group(2)) + 1, now = (Almanac.weekday(today) + 6) % 7 + 1; // Monday 1 … Sunday 7
                    if (want == 8) want = 7;
                    String pre = wk.group(1) == null ? "" : wk.group(1);
                    int i = pre.startsWith("下") ? 7 - now + want : pre.isEmpty() ? Math.floorMod(want - now, 7) : want - now;
                    if (i < 0) throw new IllegalArgumentException(day + "已经过去了");
                    if (i >= n) throw new IllegalArgumentException("天气预报只有今天起 " + n + " 天，" + day + " 查不到");
                    out.add(i);
                    break;
                }
                long at = parse(day);
                if (at == Long.MIN_VALUE) throw new IllegalArgumentException("day 写今天、明天、后天、大后天、周末、这几天、今晚、明早、明晚、一会儿、周六、下周一，或者 YYYY-MM-DD");
                int i = (int) (at - today);
                if (i < 0 || i >= n) throw new IllegalArgumentException("天气预报只有今天起 " + n + " 天，" + day + " 查不到");
                out.add(i);
            }
        }
        for (int i : out) if (i >= n) throw new IllegalArgumentException("天气预报只有今天起 " + n + " 天");
        return out;
    }

    private static String label(int i) {
        return i == 0 ? "今天" : i == 1 ? "明天" : i == 2 ? "后天" : "";
    }

    private static String dayLine(JSONObject d, int i) throws JSONException {
        StringBuilder s = new StringBuilder(text(d.getJSONArray("weather_code").optInt(i, -1)));
        double lo = d.getJSONArray("temperature_2m_min").optDouble(i), hi = d.getJSONArray("temperature_2m_max").optDouble(i);
        if (!Double.isNaN(lo) && !Double.isNaN(hi)) s.append("，").append(Math.round(lo)).append(" 到 ").append(Math.round(hi)).append(" 度");
        JSONArray pp = d.optJSONArray("precipitation_probability_max");
        double p = pp == null ? Double.NaN : pp.optDouble(i);
        double rain = d.optJSONArray("precipitation_sum") == null ? Double.NaN : d.getJSONArray("precipitation_sum").optDouble(i);
        if (!Double.isNaN(p)) s.append("，降水概率 ").append(Math.round(p)).append("%");
        else if (!Double.isNaN(rain) && rain >= 0.1) s.append("，可能有雨");
        if (!Double.isNaN(rain) && rain >= 10) s.append("，降水量约 ").append(Math.round(rain)).append(" 毫米");
        double wind = d.getJSONArray("wind_speed_10m_max").optDouble(i), dir = d.getJSONArray("wind_direction_10m_dominant").optDouble(i);
        if (!Double.isNaN(wind) && !Double.isNaN(dir)) s.append("，").append(direction(dir)).append("风 ").append(beaufort(wind)).append(" 级");
        return s.toString();
    }

    private static String now(JSONObject c) {
        double t = c.optDouble("temperature_2m"), feels = c.optDouble("apparent_temperature");
        StringBuilder s = new StringBuilder("现在 ").append(Math.round(t)).append(" 度");
        if (!Double.isNaN(feels) && Math.abs(feels - t) >= 3) s.append("，体感 ").append(Math.round(feels)).append(" 度");
        int code = c.optInt("weather_code", -1);
        if (code >= 0) s.append("，").append(text(code));
        return s.toString();
    }

    /** WMO weather codes in the words a Chinese forecast uses. */
    static String text(int code) {
        switch (code) {
            case 0: case 1: return "晴";
            case 2: return "多云";
            case 3: return "阴";
            case 45: case 48: return "有雾";
            case 51: case 53: case 61: return "小雨";
            case 55: case 63: return "中雨";
            case 65: return "大雨";
            case 56: case 57: case 66: case 67: return "冻雨";
            case 71: case 77: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 80: case 81: return "阵雨";
            case 82: return "强阵雨";
            case 85: case 86: return "阵雪";
            case 95: return "雷阵雨";
            case 96: case 99: return "雷阵雨伴有冰雹";
            default: return "天气不明";
        }
    }

    /** km/h → the Beaufort force a Chinese forecast says (「3 级」). */
    static int beaufort(double kmh) {
        double ms = kmh / 3.6;
        double[] top = {0.2, 1.5, 3.3, 5.4, 7.9, 10.7, 13.8, 17.1, 20.7, 24.4, 28.4, 32.6};
        for (int b = 0; b < top.length; b++) if (ms <= top[b]) return b;
        return 12;
    }

    /** Where the wind comes from: 「东北」. */
    static String direction(double deg) {
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return names[(int) Math.floor(((deg % 360 + 360) % 360 + 22.5) / 45) % 8];
    }

    /** One line for the hint card: today's weather, or tomorrow's in the evening (from 18:00), with rain tomorrow said
     * by day: 「今天阴，14 到 19 度；明天有小雨」「明天小雨，16 到 19 度，降水概率 94%」. */
    public static String hint(JSONObject forecast, int hour) throws JSONException {
        JSONObject d = forecast.getJSONObject("daily");
        if (d.getJSONArray("time").length() < 2) return null;
        boolean evening = hour >= 18;
        int i = evening ? 1 : 0;
        StringBuilder s = new StringBuilder(evening ? "明天" : "今天").append(text(d.getJSONArray("weather_code").optInt(i, -1)));
        double lo = d.getJSONArray("temperature_2m_min").optDouble(i), hi = d.getJSONArray("temperature_2m_max").optDouble(i);
        if (!Double.isNaN(lo) && !Double.isNaN(hi)) s.append("，").append(Math.round(lo)).append(" 到 ").append(Math.round(hi)).append(" 度");
        JSONArray pp = d.optJSONArray("precipitation_probability_max");
        if (evening) {
            double p = pp == null ? Double.NaN : pp.optDouble(1);
            if (!Double.isNaN(p) && p >= 50) s.append("，降水概率 ").append(Math.round(p)).append("%");
        } else if (wet(d.getJSONArray("weather_code").optInt(1, -1)) || (pp != null && pp.optDouble(1, 0) >= 60)) {
            s.append("；明天有").append(wet(d.getJSONArray("weather_code").optInt(1, -1)) ? text(d.getJSONArray("weather_code").optInt(1, -1)) : "雨");
        }
        return s.toString();
    }

    /** Drizzle, rain, snow, showers, thunder (WMO 51 and up); fog is not. */
    private static boolean wet(int code) { return code >= 51; }

    /**
     * The home screen's weather (Orrery 「轴」), from the forecast already in the cache: today's 「多云 13° – 19°」, and
     * from 20:00 tomorrow's — the evening is about the next day — 「明天 小雨 12° – 17°」. now is the wall clock in ms,
     * tz the place's zone (the forecast's days are its own local dates, which is what Open-Meteo answers in with
     * timezone=auto). "" when the forecast does not cover that day (a cold or stale cache: the caller warms it).
     */
    public static String homeLine(JSONObject forecast, long now, java.util.TimeZone tz) {
        if (forecast == null) return "";
        try {
            JSONObject d = forecast.getJSONObject("daily");
            JSONArray times = d.getJSONArray("time");
            Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
            c.setTimeInMillis(now);
            String today = String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH));
            int at = -1;
            for (int i = 0; i < times.length(); i++) if (today.equals(times.optString(i))) { at = i; break; }
            if (at < 0) return "";
            boolean evening = c.get(Calendar.HOUR_OF_DAY) >= 20;
            int day = evening ? at + 1 : at;
            if (day >= times.length()) return "";
            String sky = text(d.getJSONArray("weather_code").optInt(day, -1));
            double lo = d.getJSONArray("temperature_2m_min").optDouble(day), hi = d.getJSONArray("temperature_2m_max").optDouble(day);
            String temps = Double.isNaN(lo) || Double.isNaN(hi) ? "" : " " + Math.round(lo) + "° – " + Math.round(hi) + "°";
            return (evening ? "明天 " : "") + sky + temps;
        } catch (JSONException e) {
            return "";
        }
    }

    /** The hint line for a place, fetched within timeoutMs; null on any failure (the card goes up without it). */
    public static String hintFor(Get http, Where here, int hour, int timeoutMs) {
        if (here == null) return null;
        try {
            return hint(new JSONObject(http.get(forecastUrl(here.lat, here.lon), timeoutMs)), hour);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 「要带伞吗」「会下雨吗」「外面在下雨吗」 answered as asked (2026-11-06 overnight pass: two model rounds before):
     * 现在 from the weather now; a part of a day from its hours; a day from its chance of rain and its weather —
     * 「明天应该不下雨，降水概率 20%，不用带伞（阴，16 到 18 度）」「后天有小雨，降水概率 94%，出门带伞（16 到 19 度）」.
     */
    static String rainVerdict(JSONObject forecast, String day) throws JSONException {
        String d = day == null ? "" : day.trim();
        if ("现在".equals(d) || "外面".equals(d)) {
            JSONObject c = forecast.getJSONObject("current");
            int code = c.optInt("weather_code", -1);
            long t = Math.round(c.optDouble("temperature_2m"));
            boolean snow = code >= 71 && code <= 77 || code == 85 || code == 86;
            return code >= 51 ? "现在在下" + (snow ? "雪" : "雨") + "（" + text(code) + "，" + t + " 度）" : "现在没下雨，" + text(code) + "，" + t + " 度";
        }
        String hours = hoursOf(d);
        if (hours != null) {
            if (forecast.optJSONObject("hourly") == null) throw new IllegalArgumentException("这次没拿到逐小时的预报：day 写今天或明天");
            // 「今晚（18 点到 24 点）晴，17 度，降水概率最高 0%」 → its span, weather and chance
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^" + java.util.regex.Pattern.quote(hours)
                    + "(?:（([^）]*)）)?(.*?)，降水概率最高 (\\d+)%$").matcher(hourly(forecast, hours));
            if (!m.matches()) return hours + hourly(forecast, hours).substring(hours.length());
            int p = Integer.parseInt(m.group(3));
            String weather = m.group(2), span = m.group(1) == null ? "" : m.group(1) + "，";
            return verdict(hours, p, weather.contains("雨") || weather.contains("雪"), weather.split("，")[0], "（" + span + weather + "）");
        }
        JSONObject daily = forecast.getJSONObject("daily");
        long today = parse(daily.getJSONArray("time").getString(0));
        StringBuilder out = new StringBuilder();
        for (int i : pickDays(d, today, daily.getJSONArray("time").length())) {
            JSONArray pp = daily.optJSONArray("precipitation_probability_max");
            int p = pp == null || pp.isNull(i) ? -1 : (int) Math.round(pp.optDouble(i));
            int code = daily.getJSONArray("weather_code").optInt(i, -1);
            String label = i == 0 ? "今天" : i == 1 ? "明天" : i == 2 ? "后天" : Almanac.spokenDate(today + i);
            String temps = Math.round(daily.getJSONArray("temperature_2m_min").optDouble(i)) + " 到 "
                    + Math.round(daily.getJSONArray("temperature_2m_max").optDouble(i)) + " 度";
            boolean wet = code >= 51;
            if (out.length() > 0) out.append("；");
            out.append(verdict(label, p, wet, text(code), wet ? "（" + temps + "）" : "（" + text(code) + "，" + temps + "）"));
        }
        return out.toString();
    }

    /** 「今天日出 6:13，日落 17:01」: a day's sunrise and sunset, local time (2026-11-06 overnight pass: 「几点天黑」 went to
     * a web search). */
    static String sunTimes(JSONObject forecast, String day) throws JSONException {
        JSONObject daily = forecast.getJSONObject("daily");
        JSONArray rise = daily.optJSONArray("sunrise"), set = daily.optJSONArray("sunset");
        if (rise == null || set == null) throw new IllegalArgumentException("这次的预报里没有日出日落");
        long today = parse(daily.getJSONArray("time").getString(0));
        String d = day == null || day.trim().isEmpty() || "今晚".equals(day.trim()) ? "今天" : "明早".equals(day.trim()) || "明晚".equals(day.trim()) ? "明天" : day.trim();
        int i = pickDays(d, today, daily.getJSONArray("time").length()).get(0);
        String label = i == 0 ? "今天" : i == 1 ? "明天" : i == 2 ? "后天" : Almanac.spokenDate(today + i);
        return label + "日出" + clock(rise.optString(i)) + "，日落" + clock(set.optString(i));
    }

    /**
     * 「明天 16 到 18 度，穿长袖加一件薄外套；可能有小雨，带把伞稳妥」: what to wear by the day's low (mornings and evenings
     * are what catch people out), the way weather apps' 穿衣 advice goes, and the rain verdict's umbrella when there is
     * one (2026-11-06 overnight pass: two model rounds before).
     */
    static String dressAdvice(JSONObject forecast, String day) throws JSONException {
        JSONObject daily = forecast.getJSONObject("daily");
        long today = parse(daily.getJSONArray("time").getString(0));
        String d = day == null || day.trim().isEmpty() || "今晚".equals(day.trim()) || "现在".equals(day.trim()) ? "今天"
                : "明早".equals(day.trim()) || "明晚".equals(day.trim()) ? "明天" : day.trim();
        int i = pickDays(d, today, daily.getJSONArray("time").length()).get(0);
        String label = i == 0 ? "今天" : i == 1 ? "明天" : i == 2 ? "后天" : Almanac.spokenDate(today + i);
        long lo = Math.round(daily.getJSONArray("temperature_2m_min").optDouble(i)), hi = Math.round(daily.getJSONArray("temperature_2m_max").optDouble(i));
        String wear = lo >= 26 ? "穿短袖就行" : lo >= 20 ? (hi >= 28 ? "短袖或薄长袖，白天热" : "短袖或薄长袖，早晚加件薄外套")
                : lo >= 15 ? "穿长袖加一件薄外套" : lo >= 10 ? "穿外套或卫衣，早晚凉" : lo >= 5 ? "毛衣加厚外套"
                : lo >= 0 ? "穿厚外套或羽绒服" : "穿羽绒服，注意保暖";
        JSONArray pp = daily.optJSONArray("precipitation_probability_max");
        int p = pp == null || pp.isNull(i) ? -1 : (int) Math.round(pp.optDouble(i));
        boolean wet = daily.getJSONArray("weather_code").optInt(i, -1) >= 51;
        String umbrella = (wet && p >= 0 && p < 50) || (!wet && p >= 25 && p < 50) ? "；可能有雨，带把伞稳妥" : wet || p >= 50 ? "；有雨，出门带伞" : "";
        return label + " " + lo + " 到 " + hi + " 度，" + wear + umbrella;
    }

    /** 「2026-11-06T06:13」→「早上 6 点 13 分」: in words, the way the clock answer says the time (「6:13」 might be read
     * 「六比十三」). */
    private static String clock(String iso) {
        int t = iso.indexOf('T');
        if (t < 0 || iso.length() < t + 6) return iso;
        int h, m;
        try { h = Integer.parseInt(iso.substring(t + 1, t + 3)); m = Integer.parseInt(iso.substring(t + 4, t + 6)); }
        catch (NumberFormatException e) { return iso.substring(t + 1, t + 6); }
        String part = h < 5 ? "凌晨" : h < 8 ? "早上" : h < 11 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
        return part + " " + (h > 12 ? h - 12 : h) + " 点" + (m == 0 ? "整" : " " + m + " 分");
    }

    /** 「明天有小雨，降水概率 94%，出门带伞（16 到 19 度）」 / 「…可能有零星小雨…带把伞稳妥」 / 「…应该不下雨…不用带伞」. */
    private static String verdict(String label, int p, boolean wet, String weather, String extra) {
        String chance = p >= 0 ? "降水概率 " + p + "%，" : "";
        // the day's weather is its worst hour: 「小雨」 with a 24% chance is a maybe, not a 「出门带伞」 (2026-11-06 sweep)
        if (wet && p >= 0 && p < 50) return label + "可能有" + weather + "，" + chance + "带把伞稳妥" + extra;
        if (wet || p >= 50) return label + "有" + (wet ? weather : "雨") + "，" + chance + "出门带伞" + extra;
        if (p >= 25) return label + "可能有零星小雨，" + chance + "带把伞稳妥" + extra;
        return label + "应该不下雨，" + chance + "不用带伞" + extra;
    }

    // ---- the forecast as a card ---------------------------------------------------------------------------

    /**
     * The panel's card for the day a forecast answer is about: {type: weather, place, day, sky, lo, hi, rain, hours}.
     * The day is the one {@link #speak} would talk about (empty = 今天; several days asked → the first), the hours are
     * the forecast's own for that day, eight of them three hours apart (today: from the next whole hour, so the strip
     * never shows hours that have gone). now is the wall clock in ms: the place's own hour comes from the forecast's
     * utc_offset_seconds, which is what Open-Meteo answers in.
     */
    public static JSONObject card(String place, JSONObject forecast, String day, long now) throws JSONException {
        JSONObject daily = forecast.getJSONObject("daily");
        JSONArray times = daily.getJSONArray("time");
        if (times.length() == 0) throw new IllegalArgumentException("天气预报是空的");
        long today = parse(times.getString(0));
        int i = pickDays(cardDay(day), today, times.length()).get(0);
        double lo = daily.getJSONArray("temperature_2m_min").optDouble(i), hi = daily.getJSONArray("temperature_2m_max").optDouble(i);
        JSONArray pp = daily.optJSONArray("precipitation_probability_max");
        JSONObject c = new JSONObject().put("type", "weather").put("place", place == null ? "" : place)
                .put("day", dayLabel(i, today + i)).put("sky", text(daily.getJSONArray("weather_code").optInt(i, -1)));
        if (!Double.isNaN(lo)) c.put("lo", Math.round(lo));
        if (!Double.isNaN(hi)) c.put("hi", Math.round(hi));
        double rain = pp == null ? Double.NaN : pp.optDouble(i);
        if (!Double.isNaN(rain)) c.put("rain", Math.round(rain));
        JSONArray hours = hours(forecast, times.getString(i), i == 0 ? nextHour(forecast, now, today) : 0);
        if (hours != null) c.put("hours", hours);
        return c;
    }

    /** 「今天 11月7日 周六」: the day as the panel heads the card with it. */
    private static String dayLabel(int i, long at) {
        int[] civil = Almanac.civil(at);
        return (label(i) + " " + civil[1] + "月" + civil[2] + "日 周" + "日一二三四五六".charAt(Almanac.weekday(at))).trim();
    }

    /** A part of a day (今晚, 明早, 一会儿…) is a day for the card: the same day its answer was about. */
    private static String cardDay(String day) {
        String d = day == null ? "" : day.trim();
        switch (d) {
            case "明早": case "明天早上": case "明天上午": case "明晨": case "明晚": case "明天晚上": return "明天";
            case "现在": case "外面": return "今天";
            default: return hoursOf(d) == null ? d : "今天";
        }
    }

    /** The next whole hour at the place (24: the day has none left), or 0 when the forecast is not today's (a stale one
     * shows the whole day). */
    private static int nextHour(JSONObject forecast, long now, long today) {
        long local = now + forecast.optLong("utc_offset_seconds", 0) * 1000L;
        if (Math.floorDiv(local, 86_400_000L) != today) return 0;
        int hour = (int) Math.floorMod(local / 3_600_000L, 24);
        return local % 3_600_000L == 0 ? hour : hour + 1;
    }

    /**
     * The day's hours, three apart from {@code from}, as {h, t (one decimal), p}: the ones the forecast does not have
     * are left out, and fewer than two left means no strip at all (the panel draws a line, not a dot).
     */
    private static JSONArray hours(JSONObject forecast, String date, int from) throws JSONException {
        JSONObject h = forecast.optJSONObject("hourly");
        if (h == null || from > 23) return null;
        JSONArray times = h.optJSONArray("time"), temp = h.optJSONArray("temperature_2m");
        if (times == null || temp == null) return null;
        JSONArray pp = h.optJSONArray("precipitation_probability");
        JSONArray out = new JSONArray();
        for (int k = 0; k < 8; k++) {
            int hour = from + 3 * k;
            if (hour > 23) break;
            int at = indexOf(times, date, hour);
            if (at < 0 || temp.isNull(at)) continue;
            JSONObject e = new JSONObject().put("h", hour).put("t", Math.round(temp.optDouble(at) * 10) / 10.0);
            if (pp != null && !pp.isNull(at)) e.put("p", pp.optInt(at));
            out.put(e);
        }
        return out.length() < 2 ? null : out;
    }

    /** Where the hourly forecast has that date and hour (「2026-11-07T09:00」), or -1. */
    private static int indexOf(JSONArray times, String date, int hour) {
        String want = date + "T" + (hour < 10 ? "0" : "") + hour + ":";
        for (int i = 0; i < times.length(); i++) if (times.optString(i).startsWith(want)) return i;
        return -1;
    }

    // ---- air -------------------------------------------------------------------------------------------------

    private static final double[] AQI = {0, 50, 100, 150, 200, 300, 400, 500};
    private static final double[] PM25 = {0, 35, 75, 115, 150, 250, 350, 500};
    private static final double[] PM10 = {0, 50, 150, 250, 350, 420, 500, 600};

    /** China's AQI from PM2.5 and PM10 (µg/m³, HJ 633 breakpoints), the larger of the two sub-indexes. */
    static int chinaAqi(double pm25, double pm10) {
        return (int) Math.round(Math.max(iaqi(pm25, PM25), iaqi(pm10, PM10)));
    }

    private static double iaqi(double c, double[] bp) {
        if (Double.isNaN(c) || c < 0) return 0;
        for (int i = 1; i < bp.length; i++)
            if (c <= bp[i]) return AQI[i - 1] + (AQI[i] - AQI[i - 1]) * (c - bp[i - 1]) / (bp[i] - bp[i - 1]);
        return 500;
    }

    static String level(int aqi) {
        return aqi <= 50 ? "优" : aqi <= 100 ? "良" : aqi <= 150 ? "轻度污染" : aqi <= 200 ? "中度污染" : aqi <= 300 ? "重度污染" : "严重污染";
    }

    /** 「空气质量良，AQI 约 63（PM2.5 45）」 from the air-quality answer. */
    public static String air(JSONObject a) throws JSONException {
        JSONObject c = a.getJSONObject("current");
        double pm25 = c.optDouble("pm2_5"), pm10 = c.optDouble("pm10");
        if (Double.isNaN(pm25) && Double.isNaN(pm10)) throw new JSONException("no pollutants");
        int aqi = chinaAqi(pm25, pm10);
        return "空气质量" + level(aqi) + "，AQI 约 " + aqi + (Double.isNaN(pm25) ? "" : "（PM2.5 " + Math.round(pm25) + "）");
    }

    // ---- dates -------------------------------------------------------------------------------------------------

    private static long parse(String ymd) {
        if (ymd == null || !ymd.matches("\\d{4}-\\d{2}-\\d{2}")) return Long.MIN_VALUE;
        return Almanac.days(Integer.parseInt(ymd.substring(0, 4)), Integer.parseInt(ymd.substring(5, 7)), Integer.parseInt(ymd.substring(8, 10)));
    }

    // ---- the whole lookup ------------------------------------------------------------------------------------------

    /**
     * The spoken answer for a place (null or empty: here, the TV's own place) and a day, with the air when asked.
     * @throws IllegalArgumentException with the Chinese reason (no such place, a day out of range)
     */
    public static String lookup(Get http, Where here, String place, String day, boolean air) throws IOException, JSONException {
        return lookup(http, here, place, day, air, false);
    }

    /** rain: the answer to 「要带伞吗」「会下雨吗」 — a verdict first (rainVerdict), not the whole forecast. */
    public static String lookup(Get http, Where here, String place, String day, boolean air, boolean rain)
            throws IOException, JSONException {
        return lookup(http, here, place, day, air, rain, false);
    }

    /** sun: the answer to 「几点天黑」「明天几点日出」 — sunrise and sunset (sunTimes). */
    public static String lookup(Get http, Where here, String place, String day, boolean air, boolean rain, boolean sun)
            throws IOException, JSONException {
        return lookup(http, here, place, day, air, rain, sun, false);
    }

    /** dress: the answer to 「明天穿什么」「要不要穿外套」 (dressAdvice). */
    public static String lookup(Get http, Where here, String place, String day, boolean air, boolean rain, boolean sun, boolean dress)
            throws IOException, JSONException {
        return answer(http, here, place, day, air, rain, sun, dress).text;
    }

    /** A lookup's spoken answer with the place and the forecast it came from, so the caller can draw the day's card
     * from the very forecast that was fetched (never a second request). */
    public static final class Answer {
        public final String text, place;
        public final JSONObject forecast;

        Answer(String text, String place, JSONObject forecast) {
            this.text = text; this.place = place; this.forecast = forecast;
        }
    }

    /** {@link #lookup}, with the forecast it used. */
    public static Answer answer(Get http, Where here, String place, String day, boolean air, boolean rain, boolean sun, boolean dress)
            throws IOException, JSONException {
        Where where = where(http, here, place);
        if (rain || sun || dress) {
            JSONObject f = forecast(http, where);
            String said = rain ? rainVerdict(f, day) : sun ? sunTimes(f, day) : dressAdvice(f, day);
            return new Answer(where.name + said + "。", where.name, f);
        }
        // asked about the air, it comes first, with today's forecast after it (not three days of it)
        String d = air && (day == null || day.trim().isEmpty()) ? "今天" : day;
        JSONObject f = forecast(http, where);
        String text = speak(where.name, f, d);
        if (air) {
            String a;
            try { a = air(new JSONObject(http.get(airUrl(where.lat, where.lon), 9000))); }
            catch (IOException | JSONException e) { a = "空气质量这次没查到"; }
            text = where.name + a + "；" + text.substring(where.name.length());
        }
        return new Answer(text, where.name, f);
    }

    /** The place asked for: here (the TV's own), or the one the geocoding answer means. */
    private static Where where(Get http, Where here, String place) throws IOException, JSONException {
        Where where = here;
        String p = place == null ? "" : place.trim();
        if (!p.isEmpty() && (here == null || !sameAs(p, here.name))) {
            where = pick(http.get(geoUrl(p), 8000), p);
            if (where == null) throw new IllegalArgumentException("没找到「" + p + "」：用城市的名字再查（如“杭州”“上海”），国外的城市用英文名（如“Tokyo”“New York”）");
        }
        if (where == null) throw new IllegalArgumentException("不知道电视在哪里：请说出城市名");
        return where;
    }

    private static JSONObject forecast(Get http, Where where) throws IOException, JSONException {
        return new JSONObject(http.get(forecastUrl(where.lat, where.lon), 8000));
    }

    /** 「徐汇」「上海」「上海徐汇」 all mean the TV's own place 「上海徐汇」. */
    static boolean sameAs(String asked, String here) {
        String a = asked.replaceAll("[市区县省\\s]", ""), h = here.replaceAll("[市区县省\\s]", "");
        return !a.isEmpty() && (h.contains(a) || a.contains(h));
    }
}
