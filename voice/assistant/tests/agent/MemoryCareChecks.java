package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** 清理内存 and the memory guard (2026-11-07: 电视管家's 加速 took 星仪 down with the rest and freed nothing lasting). */
final class MemoryCareChecks {
    private MemoryCareChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static final String PS = "  RSS NAME\n182548 system_server\n438195 tv.danmaku.bili\n369569 tv.danmaku.bili:download\n"
            + "355997 tv.danmaku.bili:web\n179544 tv.danmaku.bili:pushservice\n47500 xyz.erapi0neer.xiaoaibridge\n"
            + "162584 xyz.erapi0neer.orrery\n51628 com.xiaomi.voicecontrol\n60000 com.netease.cloudmusic.tv\n"
            + "20000 com.netease.cloudmusic.tv:play\n120000 com.ktcp.video\n9000 com.spocky.projengmenu\n3000 [kthreadd]\n"
            + "0 init\n";
    static final String USERS = "package:xyz.erapi0neer.orrery\npackage:xyz.erapi0neer.xingyi\npackage:com.spocky.projengmenu\n"
            + "package:com.ktcp.video\npackage:xyz.erapi0neer.xiaoaibridge\npackage:com.netease.cloudmusic.tv\npackage:tv.danmaku.bili\n";

    static void run() {
        try {
            reading();
            choosing();
            guard();
            parking();
            front();
            sentences();
            parkSentences();
            parkBack();
            surfaced();
            usageLog();
            wiring();
            compiling();
        } catch (Exception e) {
            check(false, "memory checks threw " + e);
        }
    }

    static void reading() {
        Map<String, Long> rss = MemoryCare.byPackage(PS);
        check(rss.get("tv.danmaku.bili") == 438195L + 369569 + 355997 + 179544, "B站's four processes add up: " + rss.get("tv.danmaku.bili"));
        check(rss.get("com.netease.cloudmusic.tv") == 80000L && !rss.containsKey("[kthreadd]") && !rss.containsKey("RSS"),
                "a package's :sub processes count, kernel threads and the header do not");
        Set<String> users = MemoryCare.packages(USERS);
        check(users.size() == 7 && users.contains("tv.danmaku.bili") && !users.contains("com.xiaomi.voicecontrol"), "pm list packages -3");
        MediaSessions.Session playing = new MediaSessions.Session(), paused = new MediaSessions.Session(), stopped = new MediaSessions.Session();
        playing.pkg = "com.netease.cloudmusic.tv"; playing.state = "playing";
        paused.pkg = "com.ktcp.video"; paused.state = "paused";
        stopped.pkg = "tv.danmaku.bili"; stopped.state = "stopped";
        Set<String> l = MemoryCare.listening(Arrays.asList(playing, paused, stopped), 0);
        check(l.equals(new HashSet<>(Arrays.asList("com.netease.cloudmusic.tv", "com.ktcp.video"))), "playing and paused are kept, stopped is not: " + l);
        paused.updated = 1_000_000L;
        check(MemoryCare.listening(Arrays.asList(paused), 1_000_000L + 10 * 60_000L).contains("com.ktcp.video")
                && !MemoryCare.listening(Arrays.asList(paused), 1_000_000L + 40 * 60_000L).contains("com.ktcp.video"),
                "paused ten minutes ago still counts, forty minutes ago no longer");
        List<MediaSessions.Session> dumped = MediaSessions.parse("    MediaSession com.netease.cloudmusic.tv/MediaSession (userId=0)\n"
                + "      package=com.netease.cloudmusic.tv\n      state=PlaybackState {state=2, position=0, buffered position=0, speed=1.0, "
                + "updated=90329189, actions=822, custom actions=[], active item id=-1, error=null}\n");
        check(dumped.get(0).updated == 90329189L && "paused".equals(dumped.get(0).state), "the session's update time is read (the TV's format)");
    }

    static void choosing() {
        Map<String, Long> rss = MemoryCare.byPackage(PS);
        Set<String> users = MemoryCare.packages(USERS);
        List<String> home = MemoryCare.toStop(rss, users, new HashSet<>(Arrays.asList("xyz.erapi0neer.orrery", "com.netease.cloudmusic.tv")));
        check(home.equals(Arrays.asList("tv.danmaku.bili", "com.ktcp.video")),
                "on the home screen with NetEase playing: B站 then 云视听极光, biggest first; not NetEase, not 星仪's, not a 9 MB app: " + home);
        List<String> watching = MemoryCare.toStop(rss, users, new HashSet<>(Collections.singletonList("tv.danmaku.bili")));
        check(!watching.contains("tv.danmaku.bili") && watching.contains("com.netease.cloudmusic.tv"), "B站 in front is kept: " + watching);
        check(MemoryCare.toStop(rss, users, null).size() == 3 && !MemoryCare.toStop(rss, users, null).contains("xyz.erapi0neer.xiaoaibridge")
                && !MemoryCare.toStop(rss, users, null).contains("xyz.erapi0neer.orrery"), "星仪's own packages never, even unasked");
        List<String> hogs = MemoryCare.hogs(rss, users, 3);
        check(hogs.equals(Arrays.asList("tv.danmaku.bili", "com.ktcp.video")), "hogs: the user's apps of 100 MB and more: " + hogs);
    }

    static void guard() {
        Map<String, Long> rss = MemoryCare.byPackage(PS);
        Set<String> users = MemoryCare.packages(USERS);
        long now = 10_000_000L;
        Set<String> keep = new HashSet<>(Collections.singletonList("xyz.erapi0neer.orrery"));
        Map<String, Long> seen = new HashMap<>();
        seen.put("tv.danmaku.bili", now - 60_000L);
        check(MemoryCare.guardStops(500, rss, users, keep, seen, now).isEmpty(), "enough memory: the guard does nothing");
        List<String> recent = MemoryCare.guardStops(300, rss, users, keep, seen, now);
        check(recent.equals(Arrays.asList("com.ktcp.video")),
                "B站 left a minute ago stays, NetEase (78 MB) is under the guard's size, 云视听极光 unseen this hour goes: " + recent);
        seen.put("tv.danmaku.bili", now - 5 * 60_000L);
        check(MemoryCare.guardStops(300, rss, users, keep, seen, now).get(0).equals("tv.danmaku.bili"), "five minutes out of sight: B站 goes first");
        keep.add("tv.danmaku.bili");
        check(!MemoryCare.guardStops(100, rss, users, keep, seen, now).contains("tv.danmaku.bili"), "in front or playing: never");
    }

    /** The standby park (2026-11-09: NetEase paused 19 h with the screen off burned 0.4 of a core and held 279 MB). */
    static MediaSessions.Session park(String pkg, String state, long updated) {
        MediaSessions.Session s = new MediaSessions.Session();
        s.pkg = pkg; s.state = state; s.updated = updated;
        return s;
    }

    static void parking() {
        Set<String> users = MemoryCare.packages(USERS);
        Set<String> none = Collections.<String>emptySet();
        long now = 100_000_000L, off = MemoryCare.PARK_SCREEN_OFF_MS + 10 * 60_000L;
        long paused3h = now - 3 * 3_600_000L;
        List<MediaSessions.Session> netease = Arrays.asList(park("com.netease.cloudmusic.tv", "paused", paused3h));
        check(MemoryCare.parkStops(netease, now, 20 * 60_000L, users, none).isEmpty(),
                "the screen off 20 minutes: nobody parks anything yet");
        check(MemoryCare.parkStops(netease, now, off, users, none).equals(Arrays.asList("com.netease.cloudmusic.tv")),
                "screen off 40 minutes and paused 3 hours: NetEase goes");
        check(MemoryCare.parkStops(Arrays.asList(park("com.netease.cloudmusic.tv", "paused", now - 3_600_000L)), now, off, users, none).isEmpty(),
                "paused an hour: someone may still come back to it, it stays");
        check(MemoryCare.parkStops(Arrays.asList(park("com.netease.cloudmusic.tv", "playing", paused3h)), now, off, users, none).isEmpty(),
                "playing is never parked, however long the screen has been off");
        check(MemoryCare.parkStops(Arrays.asList(park("com.netease.cloudmusic.tv", "buffering", paused3h)), now, off, users, none).isEmpty(),
                "buffering is never parked either");
        check(MemoryCare.parkStops(Arrays.asList(park("xyz.erapi0neer.xiaoaibridge", "paused", paused3h)), now, off, users, none).isEmpty(),
                "星仪's own packages are never parked, the bridge is its engine");
        check(MemoryCare.parkStops(Arrays.asList(park("com.xiaomi.voicecontrol", "paused", paused3h)), now, off, users, none).isEmpty(),
                "a package the user did not install is not parked");
        check(MemoryCare.parkStops(Arrays.asList(park("com.netease.cloudmusic.tv", "paused", 0)), now, off, users, none).isEmpty(),
                "a session with no update time is left alone: how long it paused is not known");
        check(MemoryCare.parkStops(netease, now, off, users, new HashSet<>(Arrays.asList("com.netease.cloudmusic.tv"))).isEmpty(),
                "a package in keep is not parked");
        check(MemoryCare.parkStops(Arrays.asList(park("tv.danmaku.bili", "stopped", paused3h),
                park("com.netease.cloudmusic.tv", "paused", paused3h)), now, off, users, none)
                        .equals(Arrays.asList("com.netease.cloudmusic.tv", "tv.danmaku.bili")),
                "two parked apps come back sorted: " + MemoryCare.parkStops(Arrays.asList(park("tv.danmaku.bili", "stopped", paused3h),
                        park("com.netease.cloudmusic.tv", "paused", paused3h)), now, off, users, none));
        check(MemoryCare.parkStops(Arrays.asList(park("com.netease.cloudmusic.tv", "paused", paused3h),
                park("com.netease.cloudmusic.tv", "stopped", now - 5 * 3_600_000L)), now, off, users, none).size() == 1,
                "one package with two sessions is parked once");
        check(MemoryCare.parkStops(null, now, off, users, none).isEmpty(), "no sessions read: nothing parked, no throw");
    }

    static void front() {

        long t = 1_000_000L;
        List<Usage.Event> ev = Arrays.asList(new Usage.Event(t, 1, "tv.danmaku.bili"), new Usage.Event(t + 1000, 2, "tv.danmaku.bili"),
                new Usage.Event(t + 1000, 1, "com.xiaomi.voicecontrol"));
        check("tv.danmaku.bili".equals(MemoryCare.realFront(ev)), "XiaoAi's panel over B站: B站 is where the user is");
        Map<String, Long> seen = MemoryCare.lastFront(ev, t + 5000);
        check(seen.get("tv.danmaku.bili") == t + 1000 && seen.get("com.xiaomi.voicecontrol") == t + 5000, "last in front: left at, or now while in front");
        List<Usage.Event> home = new ArrayList<>(ev);
        home.add(new Usage.Event(t + 2000, 2, "com.xiaomi.voicecontrol"));
        home.add(new Usage.Event(t + 3000, 1, "xyz.erapi0neer.orrery"));
        check("xyz.erapi0neer.orrery".equals(MemoryCare.realFront(home)), "back on the home screen, B站 is in the background");
        check(MemoryCare.realFront(Collections.<Usage.Event>emptyList()).isEmpty(), "no usage access: no answer, not a guess");
    }

    static void sentences() {
        long mb = 1024L * 1024;
        String done = MemoryCare.say(478 * mb, 1640 * mb, Arrays.asList("哔哩哔哩"), Arrays.asList("正在放的网易云音乐"), "", 0);
        check(done.equals("清理好了：关掉了后台的哔哩哔哩，可用内存从 478 MB 到 1.6 GB。正在放的网易云音乐没动。"), done);
        String none = MemoryCare.say(400 * mb, 410 * mb, new ArrayList<String>(), new ArrayList<String>(), "哔哩哔哩", 1343305);
        check(none.equals("后台没有要关的应用，可用内存 410 MB。前台的哔哩哔哩自己占了 1.3 GB，正在用的应用清不掉。"), none);
        check(!MemoryCare.say(400 * mb, 410 * mb, new ArrayList<String>(), new ArrayList<String>(), "网易云音乐", 0).contains("前台"),
                "an app in front that was not measured is not talked about");
        check(!done.contains("星仪") && !none.contains("星仪"), "the answer never speaks of 星仪's own process");
        String g = MemoryCare.guardSays(280 * mb, Arrays.asList("哔哩哔哩"), 3, 1530 * mb);
        check(g.equals("内存只剩 280 MB，关掉了后台 3 分钟以上没用的哔哩哔哩，可用回到 1.5 GB"), g);
        // dumpsys meminfo -s for one package: a TOTAL line per process (the device's format, 2026-11-07)
        String meminfo = "Applications Memory Usage (in Kilobytes):\n** MEMINFO in pid 6695 [tv.danmaku.bili] **\n"
                + "                       Pss(KB)\n               TOTAL:   438195       TOTAL SWAP PSS:       56\n"
                + "** MEMINFO in pid 9593 [tv.danmaku.bili:download] **\n               TOTAL:   369569       TOTAL SWAP PSS:       10\n";
        check(MemoryCare.pss(meminfo) == 438195L + 369569 && MemoryCare.pss("") == 0, "PSS adds up a package's processes: " + MemoryCare.pss(meminfo));
    }

    static void parkSentences() {
        String one = MemoryCare.parkSays(Arrays.asList("网易云音乐"), 19 * 3_600_000L);
        check(one.equals("电视待机中，网易云音乐暂停了 19 小时：关掉了它（暂停时它还在耗电、占着内存）"), one);
        check(one.contains("网易云音乐") && one.contains("19 小时") && one.contains("待机"),
                "the record names the app and how long it sat paused: " + one);
        String two = MemoryCare.parkSays(Arrays.asList("网易云音乐", "哔哩哔哩"), 5 * 3_600_000L);
        check(two.contains("网易云音乐、哔哩哔哩") && two.contains("5 小时") && two.contains("它们"),
                "two apps read as two: " + two);
        check(MemoryCare.parkSays(Arrays.asList("网易云音乐"), 90 * 60_000L).contains("1 小时"),
                "an hour and a half is not rounded up to two: " + MemoryCare.parkSays(Arrays.asList("网易云音乐"), 90 * 60_000L));
    }

    /**
     * A parked app the system started straight back (2026-11-09 11:53: 「Force stopping com.netease.cloudmusic.tv」,
     * then 95 ms later 「Start proc 6426 … for activity MainActivity」): it was never closed, so it is not recorded as
     * closed, and it is left alone for hours instead of being force-stopped again every five minutes.
     */
    static void parkBack() {
        long paused19h = 19 * 3_600_000L;
        String back = MemoryCare.parkSays(new ArrayList<String>(), Arrays.asList("网易云音乐"), paused19h);
        check(back.equals("电视待机中，网易云音乐暂停了 19 小时：关掉后又被系统拉起来了"), back);
        check(!back.contains("关掉了"), "an app that came back is never said to be closed: " + back);
        String mixed = MemoryCare.parkSays(Arrays.asList("网易云音乐"), Arrays.asList("哔哩哔哩"), paused19h);
        check(mixed.equals("电视待机中，网易云音乐、哔哩哔哩暂停了 19 小时：关掉了它（暂停时它还在耗电、占着内存）；哔哩哔哩关掉后又被系统拉起来了"), mixed);
        check(MemoryCare.parkSays(Arrays.asList("网易云音乐", "哔哩哔哩"), new ArrayList<String>(), paused19h)
                .equals(MemoryCare.parkSays(Arrays.asList("网易云音乐", "哔哩哔哩"), paused19h)),
                "nothing came back: the line is the one from before, word for word");
        check(MemoryCare.PARK_BACKOFF_MS == 6 * 3_600_000L, "a package that came back is left alone for six hours");
        long now = 100_000_000L;
        Map<String, Long> until = new HashMap<>();
        until.put("com.netease.cloudmusic.tv", now + MemoryCare.PARK_BACKOFF_MS);
        until.put("tv.danmaku.bili", now - 1);
        check(MemoryCare.parkCooling(until, now).equals(new HashSet<>(Arrays.asList("com.netease.cloudmusic.tv"))),
                "still cooling stays out, a cooldown that ran out is tried again: " + MemoryCare.parkCooling(until, now));
        until.put("com.ktcp.video", null);
        check(MemoryCare.parkCooling(until, now).size() == 1 && MemoryCare.parkCooling(null, now).isEmpty(),
                "an entry with no time and no map at all: no throw, nothing cooling");
        Set<String> users = MemoryCare.packages(USERS);
        List<MediaSessions.Session> netease = Arrays.asList(park("com.netease.cloudmusic.tv", "paused", now - 3 * 3_600_000L));
        check(MemoryCare.parkStops(netease, now, MemoryCare.PARK_SCREEN_OFF_MS + 10 * 60_000L, users,
                MemoryCare.parkCooling(until, now)).isEmpty(), "a package in cooldown is not force-stopped again");
    }

    /**
     * What surfaced at the moment the screen went off (2026-11-11 20:06, 2026-11-12 10:05 and 10:11 — the same accident
     * three times): an app left behind the desktop comes to the front while the TV sleeps, so the TV comes back on
     * showing it. What decides is the system's own usage log, not the front read at the screen-off broadcast: at 10:11
     * the broadcast already saw the browser, the firmware having killed the desktop before it arrived.
     *
     * <p>OFF is the screen-off event of the 10:05 sequence; the events around it are the TV's own, in the order and at
     * the distances {@code dumpsys usagestats} printed them (types: 1 = foreground, 2 = background, 16 = screen off).
     */
    static final long OFF = 1_789_000_006_000L;
    static final String HOME = "xyz.erapi0neer.orrery", BROWSER = "com.phlox.tvwebbrowser", BILI = "com.ktcp.video";

    /** 10:05:20 the desktop to the front after 云视听极光, 10:05:23 the browser to the front and the screen off in the
     * same second, then the browser back out again. */
    static List<Usage.Event> at1005() {
        return Arrays.asList(new Usage.Event(OFF - 6_000, 2, BILI), new Usage.Event(OFF - 3_000, 1, HOME),
                new Usage.Event(OFF, 1, BROWSER), new Usage.Event(OFF, Usage.SCREEN_OFF, "android"),
                new Usage.Event(OFF, 2, BROWSER));
    }

    static void surfaced() {
        // 10:05: the desktop was in front, and the browser came up in the very second the screen went off
        check(BROWSER.equals(MemoryCare.surfacedAfter(at1005(), OFF)),
                "10:05: what surfaced at screen-off is the browser, not the desktop that was in front before it");
        check(MemoryCare.homeBack(MemoryCare.surfacedAfter(at1005(), OFF), HOME),
                "10:05: the browser surfaced behind the desktop: the desktop goes back on top");
        // the side where a false positive would be worse than the bug: the user was watching, the firmware killed the
        // video and the system brought the desktop back — the first package to the front is then the desktop
        List<Usage.Event> video = Arrays.asList(new Usage.Event(OFF - 3_000, 1, BILI), new Usage.Event(OFF, 2, BILI),
                new Usage.Event(OFF, Usage.SCREEN_OFF, "android"), new Usage.Event(OFF + 2_000, 1, HOME));
        check(HOME.equals(MemoryCare.surfacedAfter(video, OFF)) && !MemoryCare.homeBack(MemoryCare.surfacedAfter(video, OFF), HOME),
                "a video in front and the system bringing the desktop back: the desktop is what surfaced, nothing is put back");
        check(!MemoryCare.homeBack(HOME, HOME), "the desktop is what surfaced: nothing to do");
        // the window: a second before, ten seconds after
        List<Usage.Event> early = Arrays.asList(new Usage.Event(OFF - 5_000, 1, BROWSER), new Usage.Event(OFF, Usage.SCREEN_OFF, "android"));
        check(MemoryCare.surfacedAfter(early, OFF).isEmpty(),
                "an app that came to the front five seconds before the screen went off is not what surfaced at screen-off");
        check(BROWSER.equals(MemoryCare.surfacedAfter(Arrays.asList(new Usage.Event(OFF - 1_000, 1, BROWSER),
                        new Usage.Event(OFF, Usage.SCREEN_OFF, "android")), OFF)),
                "a second of tolerance before screen-off: the system writes those two events in either order");
        check(BROWSER.equals(MemoryCare.surfacedAfter(Arrays.asList(new Usage.Event(OFF, Usage.SCREEN_OFF, "android"),
                        new Usage.Event(OFF + 9_000, 1, BROWSER)), OFF)),
                "nine seconds after screen-off still counts: what surfaces is sometimes logged late");
        check(BROWSER.equals(MemoryCare.surfacedAfter(Arrays.asList(new Usage.Event(OFF, Usage.SCREEN_OFF, "android"),
                        new Usage.Event(OFF + 10_000, 1, BROWSER)), OFF)),
                "ten seconds is the edge of the window and it is in it");
        check(MemoryCare.surfacedAfter(Arrays.asList(new Usage.Event(OFF, Usage.SCREEN_OFF, "android"),
                        new Usage.Event(OFF + 11_000, 1, BROWSER)), OFF).isEmpty(),
                "eleven seconds after screen-off is outside the window: not this period's answer");
        // what counts as an event at all
        List<Usage.Event> onlyScreen = Arrays.asList(new Usage.Event(OFF, Usage.SCREEN_OFF, "android"));
        check(MemoryCare.surfacedAfter(onlyScreen, OFF).isEmpty(),
                "no app came to the front around screen-off: no answer, and the screen-off event itself is never the answer");
        check(!MemoryCare.homeBack(MemoryCare.surfacedAfter(onlyScreen, OFF), HOME),
                "nothing surfaced: no guess, nothing put back");
        List<Usage.Event> back = Arrays.asList(new Usage.Event(OFF - 800, 1, HOME), new Usage.Event(OFF, 2, BROWSER),
                new Usage.Event(OFF, Usage.SCREEN_OFF, "android"));
        check(HOME.equals(MemoryCare.surfacedAfter(back, OFF)) && !MemoryCare.homeBack(MemoryCare.surfacedAfter(back, OFF), HOME),
                "only a background event for the browser: the desktop is what is in front, nothing to do");
        List<Usage.Event> two = Arrays.asList(new Usage.Event(OFF - 500, 1, BILI), new Usage.Event(OFF, Usage.SCREEN_OFF, "android"),
                new Usage.Event(OFF + 1_500, 1, BROWSER));
        check(BROWSER.equals(MemoryCare.surfacedAfter(two, OFF)),
                "two apps come to the front in the window: the last one is what the user will see");
        check(BILI.equals(MemoryCare.surfacedAfter(Arrays.asList(new Usage.Event(OFF - 500, 1, BILI),
                        new Usage.Event(OFF, Usage.SCREEN_OFF, "android"), new Usage.Event(OFF + 1_500, 1, BILI)), OFF)),
                "the same package twice: it is the answer once");
        // 20:06: the HDMI source page came up behind the desktop
        List<Usage.Event> hdmi = Arrays.asList(new Usage.Event(OFF - 3_000, 1, HOME),
                new Usage.Event(OFF, Usage.SCREEN_OFF, "android"), new Usage.Event(OFF + 500, 1, "com.xiaomi.mitv.tvplayer"));
        check(MemoryCare.homeBack(MemoryCare.surfacedAfter(hdmi, OFF), HOME),
                "20:06: the HDMI source page surfaced behind the desktop: the desktop goes back on top");
        // 10:11: the broadcast's own answer was already the browser — the log's order says the same thing here
        check(MemoryCare.homeBack(BROWSER, HOME),
                "10:11: the broadcast read the browser as the front at screen-off, and the log agrees — the desktop goes back");
        // a false positive is worse than the bug: whatever surfaced that is not the desktop goes back behind it
        for (String app : new String[]{BILI, "com.netease.cloudmusic.tv", BROWSER, "com.mitv.tvhome"})
            check(MemoryCare.homeBack(app, HOME), "surfaced at screen-off and not the desktop: " + app + " goes back behind it");
        check(MemoryCare.homeBack("XYZ.ERAPI0NEER.ORRERY", HOME), "a package that only looks like the desktop is not the desktop");
        check(MemoryCare.homeBack(HOME + ":sub", HOME), "a process name with :sub is not the desktop's package");
        check(MemoryCare.homeBack("com.xiaomi.voicecontrol", HOME),
                "the rule has no overlay list: whatever surfaced and is not the desktop goes back behind it");
        // nothing to go on, either side
        check(!MemoryCare.homeBack(BROWSER, "") && !MemoryCare.homeBack(BROWSER, null), "no home screen resolved: nothing to put back");
        check(!MemoryCare.homeBack("", HOME) && !MemoryCare.homeBack(null, HOME), "nothing surfaced: nothing put back, no throw");
        check(!MemoryCare.homeBack(null, null) && !MemoryCare.homeBack("", ""), "both unreadable: no throw, nothing done");
        check(MemoryCare.surfacedAfter(null, OFF).isEmpty() && MemoryCare.surfacedAfter(Collections.<Usage.Event>emptyList(), OFF).isEmpty(),
                "no usage events at all: no answer, no throw");
        // an hour of events can hold more than one standby period
        List<Usage.Event> hour = new ArrayList<>(at1005());
        hour.add(new Usage.Event(OFF + 600_000, 1, HOME));
        hour.add(new Usage.Event(OFF + 600_000, Usage.SCREEN_OFF, "android"));
        hour.add(new Usage.Event(OFF + 601_000, 1, BILI));
        check(BILI.equals(MemoryCare.surfacedAfter(hour, OFF + 600_000)) && BROWSER.equals(MemoryCare.surfacedAfter(hour, OFF)),
                "two standby periods in one hour: each is read around its own screen-off");
        // where the period starts: the last screen-off in the hour
        check(MemoryCare.lastScreenOffAt(at1005()) == OFF, "the screen-off event's time is where the period starts");
        check(MemoryCare.lastScreenOffAt(hour) == OFF + 600_000, "two of them in the hour: the last one is this period's start");
        check(MemoryCare.lastScreenOffAt(Arrays.asList(new Usage.Event(OFF, 1, HOME))) == 0,
                "no screen-off event in the hour: nothing says where the period started");
        check(MemoryCare.lastScreenOffAt(null) == 0 && MemoryCare.lastScreenOffPkg(null).isEmpty(),
                "no events at all: no throw, no answer");
        check("android".equals(MemoryCare.lastScreenOffPkg(at1005())), "the screen-off event is the system's own (android)");
        check(MemoryCare.lastScreenOffPkg(Arrays.asList(new Usage.Event(OFF, 1, HOME))).isEmpty(),
                "no screen-off event: no package for it either");
        // the shape of the two: one rule, two package names, and the broadcast's frontAtOff is gone
        int forms = 0;
        boolean names = true;
        for (java.lang.reflect.Method m : MemoryCare.class.getDeclaredMethods()) {
            if (!"homeBack".equals(m.getName())) continue;
            forms++;
            if (m.getParameterTypes().length != 2) names = false;
            for (Class<?> t : m.getParameterTypes()) if (t != String.class) names = false;
        }
        check(forms == 1 && names, "one homeBack, two package names: the old three-argument form and frontAtOff are gone");
        boolean read = false;
        for (java.lang.reflect.Method m : MemoryCare.class.getDeclaredMethods())
            if ("surfacedAfter".equals(m.getName()) && m.getParameterTypes().length == 2
                    && m.getParameterTypes()[0] == List.class && m.getParameterTypes()[1] == long.class) read = true;
        check(read, "surfacedAfter takes the events and the moment the screen went off");
        String says = MemoryCare.homeBackSays("哔哩哔哩");
        check(says.equals("待机时哔哩哔哩浮到了最上面，已经把桌面放回去"), says);
        check(says.contains("哔哩哔哩") && says.contains("桌面"), "the note names the app that surfaced and says the desktop is back: " + says);
    }

    /**
     * The guard side of the same rule: what decides is read from the system's own usage log in the first heartbeat
     * after the screen went off. The screen-off broadcast is gone — at 2026-11-12 10:11 it arrived with the browser
     * already in front ({@code front_at_off} said so), the firmware having killed the desktop and let the browser up
     * before it, so the signal itself was late and no read taken at that moment could be right. The guard is
     * Android-side and cannot run here, so its source is read.
     */
    static void usageLog() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("src/xyz/erapi0neer/xingyi/agent/tv/MemoryGuard.java")),
                StandardCharsets.UTF_8);
        String care = new String(Files.readAllBytes(Paths.get("src/xyz/erapi0neer/xingyi/agent/MemoryCare.java")),
                StandardCharsets.UTF_8);
        broadcastGone(src);
        switchesReads(src);
        beatReads(src);
        dryRun(src);
        debugFields(src);
        ruleReads(care);
    }

    /** The whole broadcast chain is gone from the guard: it was measured wrong on the TV three times. */
    static void broadcastGone(String src) {
        check(!src.contains("screenReceiver"), "the screen on/off receiver is gone: what it read was already too late (10:11)");
        check(!src.contains("frontAtOff"), "and frontAtOff, the field it filled, is gone with it");
        check(!src.contains("registerReceiver") && !src.contains("unregisterReceiver"), "nothing is registered for a broadcast any more");
        check(!src.contains("BroadcastReceiver") && !src.contains("IntentFilter"), "and neither import is left behind");
        check(!src.contains("screenChanged"), "screenChanged(Intent) is gone: there is no broadcast to handle");
        check(!src.contains("readFrontAtOff"), "the window manager read that ran after the broadcast is gone too");
        check(!src.contains("FRONT_READ_MS"), "and how long such a read still counted is gone with it");
        check(!src.contains("Intent.ACTION_SCREEN_OFF") && !src.contains("Intent.ACTION_SCREEN_ON"),
                "the guard does not listen for the screen at all any more");
        check(!src.contains("xingyi-screen-off-front"), "the thread that read the front after the broadcast is gone");
        check(!src.contains("dispose"), "dispose() unregistered the receiver, so it is gone with the receiver");
        int ctor = src.indexOf("private MemoryGuard(Context c)"), get = src.indexOf("public static synchronized MemoryGuard get(");
        check(ctor >= 0 && get > ctor && !src.substring(ctor, get).contains("Receiver"),
                "the constructor makes the record files and nothing else: no receiver is built or registered there");
    }

    /** What the guard reads now: the hour's events, with the screen going off among them. */
    static void switchesReads(String src) {
        int from = src.indexOf("static List<Usage.Event> switches(Context c, long now)");
        int to = src.indexOf("interface Read<T>", from);
        String body = from >= 0 && to > from ? src.substring(from, to) : "";
        check(!body.isEmpty(), "switches(Context, long) is still there to read: " + from);
        check(body.contains("UsageEvents.Event.MOVE_TO_FOREGROUND"), "the app coming to the front is still collected");
        check(body.contains("UsageEvents.Event.MOVE_TO_BACKGROUND"), "and the app leaving it");
        check(body.contains("type == Usage.SCREEN_OFF"),
                "the screen going off (16, SCREEN_NON_INTERACTIVE) is collected too: that is where a standby starts");
        check(body.contains("usm.queryEvents(now - 3_600_000L, now)"),
                "an hour of them: the screen is off now, so the last screen-off in it is this period's start");
        check(body.contains("new Usage.Event(e.getTimeStamp(), type, e.getPackageName())"),
                "the events keep their own time, type and package — the order the system wrote them in");
    }

    /** The heartbeat side: it asks the usage log who surfaced at the last screen-off, once per period. */
    static void beatReads(String src) {
        int from = src.indexOf("private void homeBack(long now, long offFor)"), to = src.indexOf("private String homePackage()");
        String body = from >= 0 && to > from ? src.substring(from, to) : "";
        check(!body.isEmpty(), "homeBack(long, long) is still there to read: " + from);
        check(body.contains("if (offFor < HOME_BACK_OFF_MS) {") && body.contains("homeBackDone = false"),
                "the lit branch still starts the next period over");
        int lit = body.indexOf("if (offFor < HOME_BACK_OFF_MS)"), done = body.indexOf("if (homeBackDone) return;");
        check(lit >= 0 && done > lit, "once per screen-off period, at the first heartbeat after the threshold");
        check(body.contains("switches(app, wall)") && body.contains("long wall = System.currentTimeMillis()"),
                "the heartbeat reads the system's own usage log itself, on the wall clock the events are stamped with");
        check(body.contains("MemoryCare.lastScreenOffAt(sw)"),
                "and takes the last screen-off in it as where this standby period starts");
        check(body.contains("if (offAt == 0) { homeBackDone = true; return; }"),
                "no screen-off event in the hour: nothing is known about this period, so nothing is put back");
        int none = body.indexOf("if (offAt == 0)"), rule = body.indexOf("MemoryCare.homeBack(");
        check(none >= 0 && rule > none, "and that is given up on before the rule is asked, not after");
        check(body.contains("MemoryCare.surfacedAfter(sw, offAt)"),
                "the rule is asked which package came to the front around that moment");
        check(body.contains("MemoryCare.homeBack(surfaced, home)"),
                "and whether the desktop has to go back on top — two package names, nothing about what is in front now");
        int front = body.indexOf("LocalAdb::focusedApp");
        check(front > rule, "the window manager is read only after the rule said yes: it is no longer the criterion");
        check(body.contains("if (focusedNow.isEmpty() || focusedNow.equals(home)) { homeBackDone = true; return; }"),
                "and only to make sure the desktop is not on top already, or that the read said anything at all");
        int reads = 0;
        for (int i = body.indexOf("LocalAdb::focusedApp"); i >= 0; i = body.indexOf("LocalAdb::focusedApp", i + 1)) reads++;
        check(reads == 1, "the front is read once, and only when the desktop is about to go back: " + reads);
        check(body.contains("homeOnTop();") && body.contains("MemoryCare.homeBackSays(label(app, surfaced))"),
                "then the desktop is put on top and the note names what surfaced");
        check(body.lastIndexOf("homeBackDone = true;") > body.indexOf("homeOnTop();"),
                "the period is done either way, so the next heartbeat does not look again");
        int calls = 0;
        for (int i = src.indexOf("MemoryCare.homeBack("); i >= 0; i = src.indexOf("MemoryCare.homeBack(", i + 1)) {
            calls++;
            check(src.startsWith("MemoryCare.homeBack(surfaced", i) || src.startsWith("MemoryCare.homeBack(MemoryCare.surfacedAfter", i),
                    "call " + calls + " of the rule passes what surfaced, not what is in front now");
        }
        check(calls == 2, "the heartbeat and the dry run, and nothing else, ask the rule: " + calls);
    }

    /** The dry run: the same rule on the same events, deciding nothing. */
    static void dryRun(String src) {
        int from = src.indexOf("private boolean homeBackWould("), to = src.indexOf("private static String surfacedDebug(", from);
        String body = from >= 0 && to > from ? src.substring(from, to) : "";
        check(!body.isEmpty(), "homeBackWould is still there to read: " + from);
        check(src.contains("private boolean homeBackWould(List<Usage.Event> sw)"),
                "it is given the events the plan already read, so the dry run asks about the same hour");
        check(body.contains("if (screenOn()) return false;"), "the dry run does nothing while the screen is lit");
        check(body.contains("MemoryCare.lastScreenOffAt(sw)"), "the same last screen-off");
        check(body.contains("MemoryCare.surfacedAfter(sw, offAt)"), "and the same rule, on the same events");
        check(body.contains("MemoryCare.homeBack(") && !body.contains("LocalAdb::focusedApp"),
                "asked exactly as the heartbeat asks it, with no read of its own: " + body.length());
    }

    /** The state the lead reads on the TV: what the rule found, and when the screen went off. */
    static void debugFields(String src) {
        int from = src.indexOf("public synchronized JSONObject plan()"), to = src.indexOf("private String compileWould(", from);
        String body = from >= 0 && to > from ? src.substring(from, to) : "";
        check(!body.isEmpty(), "plan() is still there to read: " + from);
        check(body.contains("\"surfaced_after_off\""), "the state carries what the rule found at the last screen-off");
        check(body.contains("\"screen_off_at_s_ago\""),
                "and how many seconds ago that screen-off was: at is a millisecond number, it cannot be read aloud");
        check(body.contains("\"last_screen_off\""), "and which package wrote the screen-off event");
        check(!body.contains("\"front_at_off\"") && !body.contains("\"home_back_done\""),
                "and neither field that came from the broadcast is left in the state");
        check(body.contains("surfacedDebug(sw)") && body.contains("screenOffAgo(sw, wall)"),
                "both are worked out from the same events the rule used, not from a second read");
        check(body.contains("homeBackWould(sw)"), "the dry run in the same plan is given those same events");
        check(src.contains("private static String surfacedDebug(List<Usage.Event> sw)")
                        && src.contains("private static long screenOffAgo(List<Usage.Event> sw, long wall)"),
                "the two debug fields have a method each, so the plan stays one line per field");
    }

    /** The rule itself, read from the pure side: the window, and what is no longer an input to it. */
    static void ruleReads(String care) {
        check(care.contains("public static String surfacedAfter(List<Usage.Event> events, long offAt)"),
                "the rule takes the events and the moment the screen went off");
        check(care.contains("public static boolean homeBack(String surfaced, String home)"), "and the answer is two package names");
        check(care.contains("offAt - 1_000L") && care.contains("offAt + 10_000L"),
                "the window is a second before and ten seconds after, written down rather than guessed at");
        check(care.contains("e.type == Usage.FOREGROUND"), "only a package coming to the front counts as surfacing");
        check(care.contains("public static long lastScreenOffAt(List<Usage.Event> events)")
                        && care.contains("public static String lastScreenOffPkg(List<Usage.Event> events)"),
                "the last screen-off is read on the pure side: the Android package cannot see Usage.Event's fields");
        check(!care.contains("frontAtOff"), "frontAtOff is gone from the rule too, not only from the guard");
        check(!care.contains("focusedNow"), "and what is in front now is no longer an input to it");
        int forms = 0;
        for (int i = care.indexOf("public static boolean homeBack("); i >= 0; i = care.indexOf("public static boolean homeBack(", i + 1)) forms++;
        check(forms == 1, "one homeBack is declared: no second criterion left beside the new one: " + forms);
        check(!care.contains("import android"), "and the rule stays pure — no android import");
    }

    static void wiring() throws Exception {
        Contract c = AgentCoreTest.contract;
        check(c.names().contains("free_memory") && !c.gated().contains("free_memory"), "free_memory is in every request's list");
        String sys = c.systemFor(new HashSet<String>());
        check(sys.contains("free_memory") && sys.contains("电视管家"), "and the rule that keeps 电视管家's 加速 away, in every system prompt");
        for (String s : new String[]{"清理内存", "小爱同学，清理一下内存", "帮我清理内存吧", "把内存清理一下", "释放内存", "清理电视的内存", "关掉后台应用"}) {
            QuickCommand q = QuickCommand.match(s, Collections.<String>emptySet());
            check(q != null && "free_memory".equals(q.tool), s + " → free_memory");
        }
        QuickCommand quick = QuickCommand.match("清理内存", Collections.<String>emptySet());
        String said = quick.reply(AgentCoreTest.o("ok", true, "status", "observed", "message", "后台没有要关的应用，可用内存 950 MB。"));
        check(said.equals("后台没有要关的应用，可用内存 950 MB。"), "the quick 清理内存 says what it did, not 好的 (device, 13:40): " + said);
        for (String s : new String[]{"加速", "为什么内存这么小", "内存优化问题很大"}) {
            QuickCommand q = QuickCommand.match(s, Collections.<String>emptySet());
            check(q == null || !"free_memory".equals(q.tool), s + " is not a quick 清理内存");
        }
        // 「清理电视的内存」: one direct call, its sentence is the answer
        AgentCoreTest.Fake free = new AgentCoreTest.Fake("free_memory", Tool.Gate.act());
        free.reply = AgentCoreTest.o("ok", true, "status", "observed", "message", "清理好了：关掉了后台的哔哩哔哩（1.3 GB），可用内存从 478 MB 到 1.6 GB。");
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls("free_memory", new JSONObject()).text("（不该到这一轮）");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, free).run(new AgentLoop.Request("清理电视的内存", "test"));
        check(free.runs.size() == 1 && t.reply.startsWith("清理好了") && m.next == 1, "no plan, one round, the tool's sentence is the reply: " + t.reply);
        JSONArray tools = m.toolsSeen.get(0);
        check(tools.toString().contains("\"free_memory\""), "sent in the first round");
    }

    /** The base APK B站 had while its code was still never compiled (dumpsys package dexopt, 2026-11-10). */
    static final String BILI_PATH = "/data/app/tv.danmaku.bili-2h0Myns6YBr_VhoBdfuxvQ==/base.apk";

    static String dexopt() throws Exception {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/dexopt-before.txt")), StandardCharsets.UTF_8);
    }

    /**
     * The standby compile (2026-11-10): B站's code had never been compiled since it was installed on 11-04, so every
     * start unpacked and verified 38 dex files (263 MB) — 61 s from the link to the first page, 15 s once compiled.
     */
    static void compiling() throws Exception {
        dexoptReads();
        picking();
        recording();
        compileSays();
        onDemand();
    }

    /**
     * The on-demand compile (2026-11-11): the PC names a package and the standby compile's path runs for it at once, so
     * what that path does on the TV can be tried without waiting for the next app the system never compiled. The name
     * comes from outside, so it is checked before it can reach the TV's shell.
     */
    static void onDemand() {
        check(AppCompile.validPackage("tv.danmaku.bili"), "B站's package is a name to compile");
        check(AppCompile.validPackage("com.example_app2.tv"), "underscores and digits are part of one");
        check(!AppCompile.validPackage("bili"), "no dot: not an installed package name");
        check(!AppCompile.validPackage("tv.danmaku.bili ") && !AppCompile.validPackage("tv.danmaku bili"),
                "a space is not a package name");
        check(!AppCompile.validPackage("tv.danmaku.bili;rm -rf /"), "a semicolon never reaches the TV's shell");
        check(!AppCompile.validPackage("tv.danmaku.bili$1") && !AppCompile.validPackage("com.example.$x"),
                "a dollar sign is refused");
        check(!AppCompile.validPackage("/data/app/tv.danmaku.bili/base.apk") && !AppCompile.validPackage("com.example/a"),
                "a path is not a package name");
        StringBuilder longName = new StringBuilder("com.");
        while (longName.length() < 201) longName.append('a');
        check(longName.length() == 201 && !AppCompile.validPackage(longName.toString()), "201 characters: refused");
        check(AppCompile.validPackage(longName.substring(0, 200)), "200 characters: still a name");
        check(!AppCompile.validPackage(null) && !AppCompile.validPackage(""), "nothing at all: refused, no throw");
        check(!AppCompile.validPackage(".") && !AppCompile.validPackage("com..example") && !AppCompile.validPackage(".com.example")
                && !AppCompile.validPackage("com.example."), "a dot with no part beside it is not a name");
        check(!AppCompile.validPackage("com.example.哔哩") && !AppCompile.validPackage("com.exa mple"), "only letters and digits of them");
    }

    static void dexoptReads() throws Exception {
        Map<String, String> u = AppCompile.uncompiled(dexopt());
        check(BILI_PATH.equals(u.get("tv.danmaku.bili")), "B站's base APK is the one that was never compiled: " + u.get("tv.danmaku.bili"));
        check(u.containsKey("com.android.bluetooth") && "/system/app/Bluetooth/Bluetooth.apk".equals(u.get("com.android.bluetooth")),
                "every run-from-apk package is read, system apps too (next is what keeps them): " + u.get("com.android.bluetooth"));
        List<String> keys = new ArrayList<>(u.keySet());
        check(keys.get(0).equals("com.droidlogic.tvinput") && keys.indexOf("tv.danmaku.bili") > keys.indexOf("com.android.bluetooth"),
                "in the order the dump has them: " + keys);
        check(u.size() == 9, "the dump's nine run-from-apk packages and nothing else (no 依赖 line read as a package): " + keys);
        check(!u.containsKey("com.ktcp.video") && !u.containsKey("xyz.erapi0neer.orrery") && !u.containsKey("com.xiaomi.account"),
                "speed-profile and verify are compiled code, they are left alone: " + keys);
        check(!u.containsKey("android"), "a package block with no path at all is not a package to compile");
        check(!u.containsKey("com.mitv.tvhome"), "a package's secondary dex files are not its own path");
        check(AppCompile.uncompiled("").isEmpty() && AppCompile.uncompiled(null).isEmpty(), "nothing read: nothing to compile, no throw");
        check(AppCompile.uncompiled("error: device offline\n").isEmpty(), "a failed read is not a package to compile");
        String splits = "  [com.example.splits]\n    path: /data/app/com.example.splits-AAAA==/base.apk\n"
                + "      arm: [status=quicken] [reason=install]\n"
                + "    path: /data/app/com.example.splits-AAAA==/split_config.zh.apk\n"
                + "      arm: [status=run-from-apk] [reason=unknown]\n";
        check("/data/app/com.example.splits-AAAA==/split_config.zh.apk".equals(AppCompile.uncompiled(splits).get("com.example.splits")),
                "two paths, only the second never compiled: that one is the answer: " + AppCompile.uncompiled(splits));
        String both = "  [com.example.both]\n    path: /data/app/com.example.both-AAAA==/split_config.arm.apk\n"
                + "      arm: [status=run-from-apk] [reason=unknown]\n"
                + "    path: /data/app/com.example.both-AAAA==/base.apk\n"
                + "      arm: [status=run-from-apk] [reason=unknown]\n";
        check("/data/app/com.example.both-AAAA==/base.apk".equals(AppCompile.uncompiled(both).get("com.example.both")),
                "two paths both never compiled: the base APK is the package's code: " + AppCompile.uncompiled(both));
        check(AppCompile.uncompiled("      arm: [status=run-from-apk] [reason=unknown]\n").isEmpty(),
                "a status line with no package and no path is not read as one");
    }

    static void picking() throws Exception {
        Map<String, String> u = AppCompile.uncompiled(dexopt());
        Set<String> users = MemoryCare.packages(USERS);
        long now = 1_800_000_000_000L, day = 86_400_000L;
        check("tv.danmaku.bili".equals(AppCompile.next(u, users, new JSONObject(), now)),
                "B站 is the user's and was never compiled: it is the one to compile: " + AppCompile.next(u, users, new JSONObject(), now));
        check(AppCompile.next(u, new HashSet<String>(), new JSONObject(), now) == null,
                "a system app is never touched, however long it has run from its APK");
        check(AppCompile.next(u, users, AppCompile.tried(new JSONObject(), "tv.danmaku.bili", BILI_PATH, now - 3 * day, false), now) == null,
                "the same code tried three days ago: left alone for the week");
        check("tv.danmaku.bili".equals(AppCompile.next(u, users,
                AppCompile.tried(new JSONObject(), "tv.danmaku.bili", BILI_PATH, now - 8 * day, false), now)),
                "eight days: tried again, whatever came of the last try");
        check("tv.danmaku.bili".equals(AppCompile.next(u, users,
                AppCompile.tried(new JSONObject(), "tv.danmaku.bili", "/data/app/tv.danmaku.bili-NEW==/base.apk", now - day, false), now)),
                "another path is an update: tried at once, not in seven days");
        JSONObject done = AppCompile.tried(new JSONObject(), "tv.danmaku.bili", BILI_PATH, now - 3 * day, true);
        check(AppCompile.next(u, users, done, now - 3 * day + AppCompile.RETRY_MS - 1) == null,
                "a successful compile of the same code is not repeated within the week");
        check("tv.danmaku.bili".equals(AppCompile.next(u, users, done, now - 3 * day + AppCompile.RETRY_MS)),
                "seven days to the millisecond: it goes");
        Map<String, String> ours = new LinkedHashMap<>();
        ours.put("xyz.erapi0neer.orrery", "/data/app/xyz.erapi0neer.orrery-daft1FO9IkhDyJ3ToVQFuw==/base.apk");
        check(AppCompile.next(ours, users, new JSONObject(), now) == null, "星仪's own packages are never compiled by the guard");
        Map<String, String> two = new LinkedHashMap<>();
        two.put("com.ktcp.video", "/data/app/com.ktcp.video-928==/base.apk");
        two.put("tv.danmaku.bili", BILI_PATH);
        check("com.ktcp.video".equals(AppCompile.next(two, users, new JSONObject(), now)), "two of the user's: the first in the dump's order");
        check("tv.danmaku.bili".equals(AppCompile.next(two, users, AppCompile.tried(new JSONObject(), "com.ktcp.video",
                "/data/app/com.ktcp.video-928==/base.apk", now - day, false), now)), "the first one cooling: the next one goes");
        check(AppCompile.next(null, users, new JSONObject(), now) == null && AppCompile.next(two, null, null, now) == null,
                "nothing read at all: no package, no throw");
    }

    static void recording() throws Exception {
        long now = 1_800_000_000_000L;
        JSONObject one = AppCompile.tried(new JSONObject(), "tv.danmaku.bili", BILI_PATH, now, true);
        check(one.length() == 1 && BILI_PATH.equals(one.getJSONObject("tv.danmaku.bili").getString("path"))
                && one.getJSONObject("tv.danmaku.bili").getLong("at") == now
                && one.getJSONObject("tv.danmaku.bili").getBoolean("ok"), "the try is written down: " + one);
        JSONObject again = AppCompile.tried(one, "tv.danmaku.bili", BILI_PATH, now + 1000, false);
        check(again.length() == 1 && !again.getJSONObject("tv.danmaku.bili").getBoolean("ok")
                && again.getJSONObject("tv.danmaku.bili").getLong("at") == now + 1000, "the same package again replaces its entry: " + again);
        check(one.getJSONObject("tv.danmaku.bili").getBoolean("ok"), "and the record it came from is left as it was");
        JSONObject many = new JSONObject();
        for (int i = 0; i < 25; i++) many = AppCompile.tried(many, "com.example.p" + i, "/data/app/p" + i + "/base.apk", now + i, false);
        check(many.length() == 20, "at most twenty packages are remembered: " + many.length());
        check(!many.has("com.example.p0") && !many.has("com.example.p4") && many.has("com.example.p5") && many.has("com.example.p24"),
                "the oldest tries are the ones dropped: " + many);
        check(AppCompile.tried(null, "tv.danmaku.bili", BILI_PATH, now, false).length() == 1, "no record yet: the first try starts one");
    }

    static void compileSays() {
        String ok = AppCompile.line("哔哩哔哩", true, 254_000L);
        check(ok.equals("给哔哩哔哩做了一次系统代码优化（用了 4 分钟），以后打开它会快很多"), ok);
        check(AppCompile.line("哔哩哔哩", true, 30_000L).contains("（用了不到 1 分钟）"), "under a minute: " + AppCompile.line("哔哩哔哩", true, 30_000L));
        check(AppCompile.line("哔哩哔哩", true, 90_000L).contains("2 分钟") && AppCompile.line("哔哩哔哩", true, 240_000L).contains("4 分钟"),
                "minutes are rounded: " + AppCompile.line("哔哩哔哩", true, 90_000L));
        String no = AppCompile.line("哔哩哔哩", false, 254_000L);
        check(no.equals("想给哔哩哔哩做系统代码优化，没成功（7 天后再试）"), no);
        check(AppCompile.RETRY_MS == 7 * 86_400_000L, "a week before the same code is tried again");
    }
}
