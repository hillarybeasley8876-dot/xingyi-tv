package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** weather_alert (2026-11-07): the National Meteorological Center's warnings and typhoon track, as saved that night. */
final class AlertChecks {
    private AlertChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    /** 2026-11-07 02:00 in Beijing. */
    private static final long NOW = 1793988000000L;

    /** The TV, in 上海徐汇. */
    private static final double LAT = 31.23, LON = 121.47;

    static void run() {
        try {
            checks();
            fetches();
        } catch (Exception e) {
            check(false, "alert checks threw " + e);
        }
    }

    /**
     * 「最近有台风吗」 took 7.5 s with the storm list, its track and the province's warnings fetched one after another
     * (2026-11-10 sweep), although it is a quick command with no model round. They go out together now, and each answer
     * is kept ten minutes, so a second ask in that time costs nothing.
     */
    static void fetches() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<String> seen = java.util.Collections.synchronizedList(new ArrayList<String>());
        // each fetch takes 300 ms and waits for the one before it: three of them one after another take 900 ms
        final Object lock = new Object();
        Feeds.Fetch slow = (url, cs, t) -> {
            synchronized (lock) {
                try { lock.wait(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            calls.incrementAndGet();
            seen.add(url);
            return "body:" + url;
        };
        long[] clock = {1000};
        Alerts.Fetches f = new Alerts.Fetches(slow, 600_000, () -> clock[0]);
        List<String> urls = java.util.Arrays.asList(Alerts.TYPHOONS, Alerts.alarmUrl("上海"), Alerts.trackUrl(2618));
        long start = System.currentTimeMillis();
        List<String> bodies = f.many(urls, 8000);
        long took = System.currentTimeMillis() - start;
        check(bodies.size() == 3 && bodies.get(0).equals("body:" + Alerts.TYPHOONS) && bodies.get(2).equals("body:" + Alerts.trackUrl(2618)),
                "three addresses, their answers in the order they were asked for: " + bodies);
        check(took < 800, "at the same time, not one after another: " + took + " ms");
        check(calls.get() == 3 && seen.size() == 3, "each of them went out once: " + calls.get());

        // a second ask within the ten minutes: the same answers, nothing fetched
        List<String> again = f.many(urls, 8000);
        check(again.equals(bodies) && calls.get() == 3, "asked again in the ten minutes: kept, no second fetch: " + calls.get());
        check(f.one(Alerts.TYPHOONS, 8000).equals("body:" + Alerts.TYPHOONS) && calls.get() == 3, "one address, kept too: " + calls.get());

        // after the ten minutes they are fetched again
        clock[0] += 600_001;
        f.many(urls, 8000);
        check(calls.get() == 6, "ten minutes later: fetched again: " + calls.get());

        // one of them fails: the answer is an error, not a half sentence, and the others are not left hanging
        Feeds.Fetch broken = (url, cs, t) -> {
            if (url.contains("findAlarm")) throw new IOException("HTTP 500");
            return "body:" + url;
        };
        boolean threw = false;
        try { new Alerts.Fetches(broken).many(java.util.Arrays.asList(Alerts.TYPHOONS, Alerts.alarmUrl("上海")), 8000); }
        catch (IOException e) { threw = true; }
        check(threw, "one of them failing is an error");

        // a hung fetch is given its timeout and a moment more, then said so (the tool says 中央气象台没连上)
        Feeds.Fetch hangs = (url, cs, t) -> {
            try { Thread.sleep(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "never";
        };
        long began = System.currentTimeMillis();
        boolean late = false;
        try { new Alerts.Fetches(hangs).many(java.util.Collections.singletonList(Alerts.TYPHOONS), 1000); }
        catch (IOException e) { late = true; }
        check(late && System.currentTimeMillis() - began < 5000, "a fetch that never answers is an error after its timeout: "
                + (System.currentTimeMillis() - began) + " ms");
    }

    static void checks() throws Exception {
        check(Alerts.alarmUrl("上海").endsWith("province=%E4%B8%8A%E6%B5%B7%E5%B8%82") && Alerts.alarmUrl("广西").contains("%E5%A3%AE%E6%97%8F"), "the center's names: 上海市, 广西壮族自治区");
        check(Alerts.speakAlarms(Alerts.alarms(fixture("nmc-alarm-shanghai.json"), "上海"), "上海", "徐汇", NOW).equals("上海现在没有生效的气象预警"), "none");

        List<Alerts.Alarm> yn = Alerts.alarms(fixture("nmc-alarm-yunnan.json"), "云南");
        String s = Alerts.speakAlarms(yn, "云南", "", NOW);
        check(yn.size() >= 20 && yn.get(0).issuer.equals("普洱市景谷傣族彝族自治县") && yn.get(0).signal.equals("雷电黄色"), "issuer without the province, the kind: " + yn.get(0).issuer);
        check(s.startsWith("云南现在有 " + yn.size() + " 条气象预警：普洱市景谷傣族彝族自治县发布了雷电黄色预警（今天凌晨 2 点 51 分）；") && s.contains("；另外还有 ")
                && s.contains("条，多是") && s.endsWith("预警"), "a province asked about: the newest three, the rest counted by kind: " + s);

        String sh = "{\"msg\":\"success\",\"code\":0,\"data\":{\"page\":{\"list\":["
                + "{\"issuetime\":\"2026/11/07 01:30\",\"title\":\"上海市浦东新区气象台发布雷电黄色预警信号\"},"
                + "{\"issuetime\":\"2026/11/07 01:20\",\"title\":\"上海市徐汇区气象台发布大风蓝色预警信号\"},"
                + "{\"issuetime\":\"2026/11/06 20:10\",\"title\":\"上海中心气象台发布大风蓝色预警信号\"},"
                + "{\"issuetime\":\"2026/11/06 20:00\",\"title\":\"上海中心气象台发布大风蓝色预警信号\"}]}}}";
        String home = Alerts.speakAlarms(Alerts.alarms(sh, "上海"), "上海", "徐汇", NOW);
        check(home.equals("上海现在有 3 条气象预警：徐汇区发布了大风蓝色预警（今天凌晨 1 点 20 分）；上海中心气象台发布了大风蓝色预警（昨天晚上 8 点 10 分）；另外还有 1 条，是雷电预警"),
                "at home: the district's and the city's first, the same warning once, the rest counted: " + home);

        List<Alerts.Storm> storms = Alerts.storms(fixture("nmc-typhoons.txt"));
        check(storms.size() == 34 && storms.get(0).name.equals("烟花") && !storms.get(0).active, "the typhoon list, none active that night: " + storms.size());
        Alerts.Point p = Alerts.last(fixture("nmc-typhoon-infa.txt"));
        check(p.grade.equals("STS") && Math.abs(p.lat - 28.8) < 0.01 && p.speed == 63, "the track's last point");
        String ty = Alerts.speakStorm(storms.get(0), p, LAT, LON, NOW);
        check(ty.startsWith("第 31 号台风「烟花」（强热带风暴级）现在在北纬 28.8 度、东经 140.4 度，离这里约 ") && ty.contains(" 公里；中心附近最大风力 10 级（28 米每秒），中心气压 982 百帕，"
                + "正以每小时 63 公里向东北移动（11 月 5 日上午 11 点）"), "a storm: " + ty);
        long km = Math.round(Quake.km(LAT, LON, p.lat, p.lon) / 10) * 10;
        check(km > 1700 && km < 2000, "its distance from the TV: " + km);
        check(Alerts.force(28) == 10 && Alerts.force(50) == 15 && Alerts.force(70) == 18, "wind force to 17");
        String depression = Alerts.speakStorm(Alerts.storms(fixture("nmc-typhoons.txt")).get(6), p, Double.NaN, Double.NaN, NOW);
        check(depression.startsWith("一个强热带风暴现在在北纬") && !depression.contains("离这里"), "a nameless one is not numbered; no TV place, no distance: " + depression);
    }
}
