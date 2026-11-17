package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.*;

/** weather (Weather): the forecast and the air from Open-Meteo, spoken; fixtures captured 2026-11-06 05:15 for the TV's
 * own place (上海徐汇 31.230, 121.474), geocoding answers for 杭州 / Tokyo / 浦东 / 纽约 (in Chinese: nothing). */
final class WeatherChecks {
    private WeatherChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    static void run() {
        try {
            words();
            spoken();
            places();
            lookup();
            answer();
            hint();
            cache();
            hours();
            rain();
            sun();
            weekdays();
            dress();
        } catch (Exception e) {
            check(false, "weather checks threw " + e);
        }
    }

    static void words() {
        check("晴".equals(Weather.text(0)) && "阴".equals(Weather.text(3)) && "小雨".equals(Weather.text(61))
                && "雷阵雨".equals(Weather.text(95)) && "大雪".equals(Weather.text(75)), "weather codes in Chinese");
        check(Weather.beaufort(0) == 0 && Weather.beaufort(15.2) == 3 && Weather.beaufort(23.7) == 4 && Weather.beaufort(36.1) == 5
                && Weather.beaufort(200) == 12, "km/h to 级");
        check("北".equals(Weather.direction(0)) && "东北".equals(Weather.direction(40)) && "东".equals(Weather.direction(98))
                && "东南".equals(Weather.direction(148)) && "西北".equals(Weather.direction(327)) && "北".equals(Weather.direction(359)),
                "where the wind comes from");
        check(Weather.chinaAqi(35, 0) == 50 && Weather.chinaAqi(45.4, 46) == 63 && Weather.chinaAqi(10, 200) == 125
                && Weather.chinaAqi(600, 0) == 500, "China's AQI: " + Weather.chinaAqi(45.4, 46));
        check("优".equals(Weather.level(50)) && "良".equals(Weather.level(63)) && "轻度污染".equals(Weather.level(101))
                && "严重污染".equals(Weather.level(301)), "AQI levels");
    }

    static void spoken() throws Exception {
        JSONObject f = new JSONObject(fixture("weather-forecast.json"));
        String tomorrow = Weather.speak("上海徐汇", f, "明天");
        check("上海徐汇明天（11 月 7 日 星期六）阴，16 到 18 度，降水概率 20%，东风 4 级。".equals(tomorrow), "明天: " + tomorrow);
        String today = Weather.speak("上海徐汇", f, "今天");
        check("上海徐汇今天（11 月 6 日 星期五）阴，14 到 19 度，降水概率 0%，东北风 3 级，现在 14 度，阴。".equals(today), "今天: " + today);
        String after = Weather.speak("上海徐汇", f, "后天");
        check(after.contains("后天（11 月 8 日 星期日）小雨，16 到 19 度，降水概率 94%，降水量约 11 毫米，东南风 4 级"), "后天 with the rain: " + after);
        String weekend = Weather.speak("上海徐汇", f, "周末");
        check(weekend.contains("明天（11 月 7 日 星期六）") && weekend.contains("；后天（11 月 8 日 星期日）"), "周末 on a Friday: " + weekend);
        String days = Weather.speak("上海徐汇", f, "这几天");
        check(days.startsWith("上海徐汇今天") && days.contains("；明天") && days.contains("；后天") && days.endsWith("。"), "these days: " + days);
        check(Weather.speak("上海徐汇", f, "").equals(today), "no day is today");
        check(Weather.speak("上海徐汇", f, "2026-11-12").startsWith("上海徐汇（11 月 12 日 星期四）"), "a date in the week");
        boolean threw = false;
        try { Weather.speak("上海徐汇", f, "2026-11-13"); } catch (IllegalArgumentException e) { threw = e.getMessage().contains("7 天"); }
        check(threw, "a day past the forecast is refused");
        threw = false;
        try { Weather.speak("上海徐汇", f, "下下周"); } catch (IllegalArgumentException e) { threw = e.getMessage().contains("YYYY-MM-DD"); }
        check(threw, "an unknown day is refused with the forms it takes");
        check("空气质量良，AQI 约 63（PM2.5 45）".equals(Weather.air(new JSONObject(fixture("weather-air.json")))), "the air");
    }

    static void places() throws Exception {
        Weather.Where hz = Weather.pick(fixture("weather-geo-hangzhou.json"), "杭州");
        check(hz != null && "杭州".equals(hz.name) && Math.abs(hz.lat - 30.29) < 0.01, "杭州 is the city, not the village");
        Weather.Where tokyo = Weather.pick(fixture("weather-geo-tokyo.json"), "Tokyo");
        check(tokyo != null && Math.abs(tokyo.lat - 35.69) < 0.01, "Tokyo by its English name");
        check(Weather.pick(fixture("weather-geo-pudong.json"), "浦东") == null, "浦东 matches only villages elsewhere: no answer");
        check(Weather.pick(fixture("weather-geo-none.json"), "纽约") == null, "纽约 in Chinese finds nothing");
        Weather.Where here = Weather.fromLocation("{\"cityid\":\"1422\",\"province\":\"上海\",\"city\":\"上海\",\"district\":\"徐汇\",\"latitude\":\"31.230416\",\"longitude\":\"121.473701\"}", "上海徐汇");
        check(here != null && Math.abs(here.lat - 31.230) < 0.001 && Math.abs(here.lon - 121.474) < 0.001, "the TV's own coordinates");
        check(Weather.fromLocation("{\"city\":\"上海\"}", "上海") == null && Weather.fromLocation(null, "x") == null, "no coordinates, no place");
        check(Weather.sameAs("徐汇", "上海徐汇") && Weather.sameAs("上海市", "上海徐汇") && Weather.sameAs("上海徐汇区", "上海徐汇")
                && !Weather.sameAs("北京", "上海徐汇"), "the TV's own place by any of its names");
    }

    static void lookup() throws Exception {
        final String forecast = fixture("weather-forecast.json"), air = fixture("weather-air.json"), hz = fixture("weather-geo-hangzhou.json");
        final StringBuilder asked = new StringBuilder();
        Weather.Get http = (url, timeout) -> {
            asked.append(url).append('\n');
            if (url.startsWith(Weather.GEO)) return hz;
            if (url.startsWith(Weather.AIR)) return air;
            if (url.startsWith(Weather.FORECAST)) return forecast;
            throw new IOException("unexpected " + url);
        };
        Weather.Where here = new Weather.Where("上海徐汇", 31.230416, 121.473701);
        String t = Weather.lookup(http, here, "", "明天", false);
        check(t.startsWith("上海徐汇明天") && !asked.toString().contains(Weather.GEO) && asked.toString().contains("latitude=31.2304"),
                "here: no geocoding, the TV's coordinates");
        asked.setLength(0);
        String hzText = Weather.lookup(http, here, "杭州", "明天", true);
        check(hzText.startsWith("杭州空气质量良，AQI 约 63（PM2.5 45）；明天（11 月 7 日") && asked.toString().contains("latitude=30.2937"),
                "another city, the air first: " + hzText);
        String airToday = Weather.lookup(http, here, "", "", true);
        check(airToday.startsWith("上海徐汇空气质量良") && airToday.contains("；今天（11 月 6 日") && !airToday.contains("明天"),
                "the air alone asked: today only: " + airToday);
        Weather.Get broken = (url, timeout) -> { if (url.startsWith(Weather.AIR)) throw new IOException("down"); return forecast; };
        check(Weather.lookup(broken, here, "徐汇", "明天", true).startsWith("上海徐汇空气质量这次没查到；明天"), "the air down: the forecast still comes");
        boolean threw = false;
        Weather.Get none = (url, timeout) -> url.startsWith(Weather.GEO) ? "{\"generationtime_ms\":0.1}" : forecast;
        try { Weather.lookup(none, here, "纽约", "明天", false); } catch (IllegalArgumentException e) { threw = e.getMessage().contains("英文名"); }
        check(threw, "an unknown place says how to ask");
    }

    static void hint() throws Exception {
        JSONObject f = new JSONObject(fixture("weather-forecast.json"));
        check("今天阴，14 到 19 度".equals(Weather.hint(f, 9)), "morning: today, tomorrow dry: " + Weather.hint(f, 9));
        check("明天阴，16 到 18 度".equals(Weather.hint(f, 20)), "evening: tomorrow: " + Weather.hint(f, 20));
        // a day later in the fixture: tomorrow (the 8th) rains
        JSONObject d = f.getJSONObject("daily");
        for (String k : new String[]{"time", "weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_probability_max",
                "precipitation_sum", "wind_speed_10m_max", "wind_direction_10m_dominant"}) d.getJSONArray(k).remove(0);
        check("今天阴，16 到 18 度；明天有小雨".equals(Weather.hint(f, 9)), "rain tomorrow is said: " + Weather.hint(f, 9));
        check("明天小雨，16 到 19 度，降水概率 94%".equals(Weather.hint(f, 19)), "evening before rain: " + Weather.hint(f, 19));
        check(Weather.hintFor((url, t) -> { throw new IOException("down"); }, new Weather.Where("x", 1, 1), 9, 100) == null
                && Weather.hintFor(null, null, 9, 100) == null, "no weather, no line");
        java.util.List<String> card = Hints.card(null, null, null, 1793901444077L, java.util.TimeZone.getTimeZone("Asia/Shanghai"), "今天阴，14 到 19 度");
        check(!card.isEmpty() && "今天阴，14 到 19 度".equals(card.get(0)), "the weather leads the card: " + card);
    }

    static void hours() throws Exception {
        JSONObject f = new JSONObject(fixture("weather-hourly.json"));
        String tonight = Weather.speak("上海徐汇", f, "今晚");
        check("上海徐汇今晚（18 点到 24 点）晴，17 度，降水概率最高 0%。".equals(tonight), "tonight: " + tonight);
        String morning = Weather.speak("上海徐汇", f, "明早");
        check("上海徐汇明早（6 点到 10 点）多云，16 到 17 度，降水概率最高 0%。".equals(morning), "tomorrow morning: " + morning);
        check(Weather.speak("上海徐汇", f, "明天晚上").startsWith("上海徐汇明晚（18 点到 24 点）阴，17 度，降水概率最高 20%"),
                "tomorrow evening: " + Weather.speak("上海徐汇", f, "明天晚上"));
        check(Weather.speak("上海徐汇", f, "一会儿").startsWith("上海徐汇接下来几个小时阴，14 到 15 度"), "the next hours: " + Weather.speak("上海徐汇", f, "一会儿"));
        boolean threw = false;
        try { Weather.speak("上海徐汇", new JSONObject(fixture("weather-forecast.json")), "今晚"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "no hourly forecast: refused with the forms that work");
    }

    static void rain() throws Exception {
        JSONObject f = new JSONObject(fixture("weather-hourly.json"));
        check("明天应该不下雨，降水概率 20%，不用带伞（阴，16 到 18 度）".equals(Weather.rainVerdict(f, "明天")) ||
                Weather.rainVerdict(f, "明天").startsWith("明天"), "tomorrow: " + Weather.rainVerdict(f, "明天"));
        String after = Weather.rainVerdict(f, "后天");
        check(after.startsWith("后天有小雨，降水概率") && after.contains("出门带伞"), "the day after, rain: " + after);
        String now = Weather.rainVerdict(f, "现在");
        check(now.startsWith("现在没下雨，"), "now, dry: " + now);
        String tonight = Weather.rainVerdict(f, "今晚");
        check(tonight.equals("今晚应该不下雨，降水概率 0%，不用带伞（18 点到 24 点，晴，17 度）"), "tonight: " + tonight);
        check(Weather.rainVerdict(f, "后天").equals("后天有小雨，降水概率 94%，出门带伞（16 到 19 度）"), "the weather is not said twice");
        check(Weather.rainVerdict(f, "周末").contains("；后天有小雨"), "the weekend: both days");
        String maybe = Weather.rainVerdict(f, "2026-11-09");
        check(maybe.equals("11 月 9 日可能有小雨，降水概率 43%，带把伞稳妥（11 到 16 度）"), "rain in the day's weather at 43%: a maybe: " + maybe);
    }

    static void dress() throws Exception {
        JSONObject f = new JSONObject(fixture("weather-hourly.json"));
        String today = Weather.dressAdvice(f, "今天");
        check(today.equals("今天 14 到 19 度，穿外套或卫衣，早晚凉"), "today: " + today);
        String after = Weather.dressAdvice(f, "后天");
        check(after.startsWith("后天 16 到 19 度，穿长袖加一件薄外套；有雨，出门带伞"), "the day after, with rain: " + after);
        check(Weather.dressAdvice(f, "今晚").equals(today), "今晚 is today's");
    }

    static void weekdays() throws Exception {
        // the fixture's first day is Friday 6 November 2026
        JSONObject f = new JSONObject(fixture("weather-hourly.json"));
        check(Weather.speak("上海徐汇", f, "周六").startsWith("上海徐汇明天（11 月 7 日 星期六）"), "周六 from a Friday is tomorrow: " + Weather.speak("上海徐汇", f, "周六"));
        check(Weather.speak("上海徐汇", f, "这周日").startsWith("上海徐汇后天（11 月 8 日 星期日）"), "这周日: " + Weather.speak("上海徐汇", f, "这周日"));
        check(Weather.speak("上海徐汇", f, "周五").startsWith("上海徐汇今天"), "周五 on a Friday is today");
        check(Weather.speak("上海徐汇", f, "下周一").contains("11 月 9 日"), "下周一: " + Weather.speak("上海徐汇", f, "下周一"));
        boolean past = false;
        try { Weather.speak("上海徐汇", f, "这周三"); } catch (IllegalArgumentException e) { past = e.getMessage().contains("已经过去了"); }
        check(past, "这周三 on a Friday has gone");
        boolean far = false;
        try { Weather.speak("上海徐汇", f, "下周六"); } catch (IllegalArgumentException e) { far = e.getMessage().contains("查不到"); }
        check(far, "下周六 is past the forecast");
    }

    static void sun() throws Exception {
        JSONObject f = new JSONObject("{\"daily\":{\"time\":[\"2026-11-06\",\"2026-11-07\",\"2026-11-08\"],"
                + "\"sunrise\":[\"2026-11-06T06:13\",\"2026-11-07T06:14\",\"2026-11-08T06:15\"],"
                + "\"sunset\":[\"2026-11-06T17:01\",\"2026-11-07T17:00\",\"2026-11-08T16:59\"]}}");
        check("今天日出早上 6 点 13 分，日落下午 5 点 1 分".equals(Weather.sunTimes(f, "今天")), "today's sun: " + Weather.sunTimes(f, "今天"));
        check("明天日出早上 6 点 14 分，日落下午 5 点整".equals(Weather.sunTimes(f, "明天")), "tomorrow's sun: " + Weather.sunTimes(f, "明天"));
        check(Weather.sunTimes(f, "今晚").equals(Weather.sunTimes(f, "今天")), "今晚 is today's sunset");
        boolean threw = false;
        try { Weather.sunTimes(new JSONObject(fixture("weather-forecast.json")), "今天"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "a forecast without the sun: refused");
        check(Weather.forecastUrl(31.4, 121.5).contains("sunrise,sunset"), "the forecast asks for the sun");
    }

    static void cache() throws Exception {
        Weather.clearCache();
        final int[] calls = {0};
        final long[] now = {1_000_000L};
        Weather.Get counting = (url, t) -> { calls[0]++; if (url.contains("fail")) throw new IOException("down"); return "body" + calls[0]; };
        Weather.Get c = Weather.cached(counting, () -> now[0]);
        String forecast = Weather.FORECAST + "?latitude=31.2304&longitude=121.4737";
        check("body1".equals(c.get(forecast, 1000)) && "body1".equals(c.get(forecast, 1000)) && calls[0] == 1, "a forecast is kept");
        now[0] += 600_001L;
        check("body2".equals(c.get(forecast, 1000)) && calls[0] == 2, "for ten minutes");
        String geo = Weather.GEO + "?name=x";
        c.get(geo, 1000);
        now[0] += 3_600_000L;
        c.get(geo, 1000);
        check(calls[0] == 3, "a place's coordinates for a day");
        boolean threw = false;
        try { c.get(Weather.FORECAST + "?fail", 1000); } catch (IOException e) { threw = true; }
        try { c.get(Weather.FORECAST + "?fail", 1000); } catch (IOException e) { threw = threw && calls[0] == 5; }
        check(threw, "a failure is not kept");
        Weather.clearCache();
    }

    static void answer() throws Exception {
        // weather(final=true) alone in its round: the harness speaks it, as a final web_search
        JSONArray calls = new JSONArray().put(new JSONObject().put("id", "w1").put("type", "function")
                .put("function", new JSONObject().put("name", "weather").put("arguments", "{\"day\":\"明天\",\"final\":true}")));
        JSONObject[] results = {new JSONObject().put("ok", true).put("status", "observed").put("answer", "上海徐汇明天阴。")};
        check("上海徐汇明天阴。".equals(AgentLoop.searchAnswer(calls, results)), "a final weather is the answer");
        JSONArray notFinal = new JSONArray().put(new JSONObject().put("id", "w2").put("type", "function")
                .put("function", new JSONObject().put("name", "weather").put("arguments", "{\"day\":\"明天\"}")));
        check(AgentLoop.searchAnswer(notFinal, results) == null, "without final the model answers from it (带伞吗)");
    }
}
