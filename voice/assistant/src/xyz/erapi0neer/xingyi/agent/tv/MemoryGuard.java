package xyz.erapi0neer.xingyi.agent.tv;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;
import xyz.erapi0neer.xingyi.LocalAdb;
import xyz.erapi0neer.xingyi.agent.AppCompile;
import xyz.erapi0neer.xingyi.agent.FrontApp;
import xyz.erapi0neer.xingyi.agent.MediaSessions;
import xyz.erapi0neer.xingyi.agent.MemoryCare;
import xyz.erapi0neer.xingyi.agent.Usage;

/**
 * The memory guard (2026-11-07, 「内存优化问题很大」). The bridge's 20-second heartbeat calls {@link #tick()}; when the
 * system reports less than {@link MemoryCare#GUARD_BELOW_MB} MB available, apps the user installed that have been out of
 * sight for three minutes or more and hold 80 MB or more are stopped (B站's phone client holds 1.3 GB in four processes):
 * never 星仪's own, never the app in front, never one that plays or is paused. Left alone, the kernel's low-memory
 * killer gets to them only after swap is full and everything crawls, and it takes XiaoAi's and 星仪's processes first
 * whenever the big app is the one in front. What it did is kept in files/agent/memory-guard.json for tv_status, so
 * 「B站怎么重新加载了」 has an answer. Off with the file memory-guard-off (AgentDebug MEMGUARD). Nothing here may throw.
 *
 * <p>The same heartbeat also parks a media app the standby left running for nothing (2026-11-09): with the screen off for
 * half an hour, an app paused for two hours or more is closed and what it was playing is kept in files/agent/parked.json,
 * so 「继续播放」 puts the song back (TvTools media_control play). It does not wait for memory to run short: NetEase paused
 * since the night before used 0.4 of a core and 279 MB with 1 GB still free. The home screen is put on top before the
 * apps are stopped and the processes are read again afterwards: an app the system started straight back was never
 * parked, so it is not recorded as closed and is left alone for the next six hours.
 *
 * <p>And it compiles in standby an app the system never managed to compile (2026-11-10, B站: 61 s to its first page
 * while it ran from its APK): one package per check, on a thread of its own, with what it did in files/agent/compiled.json
 * so the same code is not ground at again for a week. {@link #compileOnDemand} runs that same path once for a package
 * the PC names, so it can be tried on the TV without waiting for the next app that needs it.
 */
public final class MemoryGuard {
    private static final long EVERY_MS = 60_000L, USERS_FOR_MS = 3_600_000L;
    /** The standby park looks at the sessions at most this often (they change slowly, and each read is an ADB call). */
    private static final long PARK_EVERY_MS = 5 * 60_000L;
    /** How long a parked song is worth replaying for: 继续播放 the next evening is not about last night's song. */
    static final long PARKED_FOR_MS = 24 * 3_600_000L;
    /** How long the home screen is given to come to the front before the apps are stopped. */
    private static final long PARK_HOME_MS = 1_500L;
    /** How long the stopped apps are given to die before the park reads the processes again to see whether they did. */
    private static final long PARK_CHECK_MS = 3_000L;
    /** The standby compile only starts once the screen has been off this long: it takes minutes and warms the TV. */
    private static final long COMPILE_OFF_MS = 30 * 60_000L;
    /** …and at most this often, however many apps there are to do (one package per check). */
    private static final long COMPILE_EVERY_MS = 6 * 3_600_000L;
    /** …and only with this much memory available: dex2oat of a 263 MB app needs room of its own. */
    private static final long COMPILE_AVAIL_MB = 600;
    /** How long the screen must have been off before the home screen is put back on top: what surfaces behind the
     * desktop takes a moment to come to the front, and the first heartbeat can still be before it does. */
    private static final long HOME_BACK_OFF_MS = 3_000L;
    private static MemoryGuard instance;

    private final Context app;
    private final File record, off, parked, compiled;
    private long checkedAt = -EVERY_MS, usersAt, parkedAt = -PARK_EVERY_MS, compileAt = -COMPILE_EVERY_MS;
    /** Whether a compile runs on the thread of its own; the heartbeat never waits for it. */
    private volatile boolean compiling;
    /** When the screen was first seen off, on the elapsed clock; 0 while it is on. */
    private long screenOffSince;
    /** Whether this screen-off period's look at what surfaced is done; cleared whenever the screen is seen on. */
    private boolean homeBackDone;
    /** When each package the system started straight back may be parked again, on the elapsed clock (in memory only). */
    private final Map<String, Long> againAt = new HashMap<>();
    private Set<String> users;

    private MemoryGuard(Context c) {
        app = c;
        File dir = new File(c.getFilesDir(), "agent");
        dir.mkdirs();
        record = new File(dir, "memory-guard.json");
        off = new File(dir, "memory-guard-off");
        parked = new File(dir, "parked.json");
        compiled = new File(dir, "compiled.json");
    }

    public static synchronized MemoryGuard get(Context context) {
        if (instance == null) instance = new MemoryGuard(context.getApplicationContext());
        return instance;
    }

    /** Available memory in bytes as the system counts it; 0 when unreadable. */
    static long available(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.availMem;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * The last hour's events, oldest first: the switches of the app in front and the screen going off (needs
     * PACKAGE_USAGE_STATS; empty without). The screen-off events are what says where a standby period starts — the
     * system's own record of the order things happened in, which the ACTION_SCREEN_OFF broadcast was too late for
     * (2026-11-12 10:11: the front was already the browser when the broadcast arrived).
     */
    static List<Usage.Event> switches(Context c, long now) {
        List<Usage.Event> out = new ArrayList<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usm.queryEvents(now - 3_600_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                int type = e.getEventType();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == UsageEvents.Event.MOVE_TO_BACKGROUND
                        || type == Usage.SCREEN_OFF)
                    out.add(new Usage.Event(e.getTimeStamp(), type, e.getPackageName()));
            }
        } catch (RuntimeException ignored) {
            // no usage access: the window manager and accessibility below still name the app in front
        }
        return out;
    }

    interface Read<T> { T run(LocalAdb a) throws IOException; }

    /** One command on a connection of its own. A stream the device ends early (the focused window is read with
     * grep -m1) can leave a packet behind that the next command on the same connection takes for a wrong channel
     * (seen 2026-11-07 on the first dry run: 「电视 ADB 返回了错误的通道」). */
    static <T> T once(Context c, Read<T> f) throws IOException {
        LocalAdb a = new LocalAdb(c);
        try { return f.run(a); } finally { a.cancel(); }
    }

    /** The apps the user is in or may be: the last real app in front (not XiaoAi's panel), and what accessibility and
     * the window manager name. All of them are kept. */
    static Set<String> front(Context c, List<Usage.Event> switches) {
        Set<String> out = new HashSet<>();
        String usage = MemoryCare.realFront(switches);
        if (!usage.isEmpty()) out.add(usage);
        String eyes = Eyes.foregroundPackage();
        if (eyes != null && !eyes.isEmpty()) out.add(eyes);
        try {
            // mFocusedApp: the focused window can be one of XiaoAi's overlays, the focused app never is
            String pkg = FrontApp.pkg(once(c, LocalAdb::focusedApp));
            if (!pkg.isEmpty()) out.add(pkg);
        } catch (IOException ignored) {
            // the other two still count
        }
        return out;
    }

    /** The heartbeat's check: a cheap memory read every time, the rest at most once a minute and only when short. */
    public synchronized void tick() {
        try {
            if (off.exists()) return;
            long now = SystemClock.elapsedRealtime();
            long offFor = screenOffFor(now);
            low(now);
            homeBack(now, offFor);
            park(now, offFor);
            compile(now, offFor);
        } catch (Exception | LinkageError ignored) {
            // the heartbeat must survive whatever happens here
        }
    }

    /** The memory side: only while the system reports less than {@link MemoryCare#GUARD_BELOW_MB} MB available. */
    private void low(long now) throws Exception {
        long avail = available(app);
        if (avail <= 0 || avail >= MemoryCare.GUARD_BELOW_MB << 20) return;
        if (now - checkedAt < EVERY_MS) return;
        checkedAt = now;
        long wall = System.currentTimeMillis();
        List<Usage.Event> sw = switches(app, wall);
        Set<String> keep = front(app, sw);
        keep.addAll(MemoryCare.listening(MediaSessions.parse(once(app, LocalAdb::mediaSessions)), SystemClock.elapsedRealtime()));
        Map<String, Long> rss = MemoryCare.byPackage(once(app, LocalAdb::processes));
        List<String> stop = MemoryCare.guardStops(avail >> 20, rss, users(), keep, MemoryCare.lastFront(sw, wall), wall);
        if (stop.isEmpty()) return;
        once(app, a -> { a.stopApps(stop); return null; });
        List<String> labels = new ArrayList<>();
        for (String p : stop) labels.add(label(app, p));
        Thread.sleep(2500); // the system's figure settles over a couple of seconds
        note(wall, MemoryCare.guardSays(avail, labels, MemoryCare.GUARD_IDLE_MS / 60_000L, available(app)));
    }

    /**
     * The home screen put back on top after standby (2026-11-11 20:07): an app left behind the desktop comes to the
     * front while the TV sleeps, so the next time it is switched on the user is looking at that app instead of the
     * desktop they left (the browser, 2026-11-12 03:16 — with the desktop's process still alive, which the old rule
     * took for 「nothing to do」).
     *
     * <p>What decides is {@link MemoryCare#surfacedAfter}: which package first came to the front around the moment the
     * screen went off, read from the system's own usage log. Not the front at the screen-off broadcast — that signal is
     * too late (2026-11-12 10:11: {@code front_at_off} was already {@code com.phlox.tvwebbrowser} when the broadcast
     * arrived, the firmware having killed the desktop and let the browser up before it), so the whole receiver is gone.
     * Once per screen-off period, at the first heartbeat with the screen off for {@link #HOME_BACK_OFF_MS}. No
     * screen-off event in the last hour means nothing is known about this period, so nothing is put back — a false
     * positive would take the desktop over something being watched. The window manager is read once at the end, and
     * only to make sure the desktop is not already on top; it is no longer an input to the rule. An ADB read that fails
     * leaves it for the next tick; nothing here may stop {@link #park} and {@link #compile} from running.
     */
    private void homeBack(long now, long offFor) {
        try {
            // offFor is 0 on every tick while the screen is lit, so this is where the next period's look starts over
            if (offFor < HOME_BACK_OFF_MS) {
                homeBackDone = false;
                return;
            }
            if (homeBackDone) return;
            long wall = System.currentTimeMillis();
            List<Usage.Event> sw = switches(app, wall);
            long offAt = MemoryCare.lastScreenOffAt(sw);
            // no screen-off event in the hour: where this period started is not known, so nothing is put back
            // (a miss is better than taking the desktop over something being watched)
            if (offAt == 0) { homeBackDone = true; return; }
            String home = homePackage(), surfaced = MemoryCare.surfacedAfter(sw, offAt);
            if (!MemoryCare.homeBack(surfaced, home)) { homeBackDone = true; return; }
            // the usage log is the criterion; this only makes sure the desktop is not on top already (no longer an input)
            String focusedNow = FrontApp.pkg(once(app, LocalAdb::focusedApp));
            if (focusedNow.isEmpty() || focusedNow.equals(home)) { homeBackDone = true; return; }
            homeOnTop();
            note(wall, MemoryCare.homeBackSays(label(app, surfaced)));
            homeBackDone = true;
        } catch (IOException | RuntimeException | JSONException ignored) {
            // the TV would not say what is on top or what runs: the next tick looks again, and the park goes on
        }
    }

    /** The package the HOME key lands on: what the home intent resolves to (the desktop, Orrery on this TV). */
    private String homePackage() {
        try {
            android.content.pm.ResolveInfo r = app.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            return r == null || r.activityInfo == null || r.activityInfo.packageName == null ? "" : r.activityInfo.packageName;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Whether the home screen would be put back on top right now, doing nothing (the dry run's
     * {@code home_back_would}): the same rule on the same {@code switches}, false when the screen is lit or the hour's
     * events hold no screen-off at all.
     */
    private boolean homeBackWould(List<Usage.Event> sw) {
        try {
            if (screenOn()) return false;
            long offAt = MemoryCare.lastScreenOffAt(sw);
            if (offAt == 0) return false;
            return MemoryCare.homeBack(MemoryCare.surfacedAfter(sw, offAt), homePackage());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** What the rule found at the last screen-off in {@code sw}, {@code ""} when it found nothing ({@code surfaced_after_off}). */
    private static String surfacedDebug(List<Usage.Event> sw) {
        long offAt = MemoryCare.lastScreenOffAt(sw);
        return offAt == 0 ? "" : MemoryCare.surfacedAfter(sw, offAt);
    }

    /** How many seconds ago the last screen-off was, -1 when the hour holds none ({@code screen_off_at_s_ago}: the
     * event's {@code at} is a millisecond number, it cannot be read aloud). */
    private static long screenOffAgo(List<Usage.Event> sw, long wall) {
        long offAt = MemoryCare.lastScreenOffAt(sw);
        return offAt == 0 ? -1 : (wall - offAt) / 1000;
    }

    /**
     * The standby park (2026-11-09): with the screen off for half an hour, a media app left paused for two hours or more
     * is closed — it keeps decoding silence all night (NetEase 20.1 % of a core itself, media.codec 12.4 %, the audio HAL
     * 8.6 %, 279 MB of 2 GB) and a media STOP key does not stop that. What each app was playing is written to
     * files/agent/parked.json first, so 「继续播放」 the next day can put the song back (TvTools media_control play).
     * Independent of the memory figure, behind the same off switch, at most every five minutes.
     *
     * <p>The home screen is put on top first, and the processes are read again afterwards: the app that was on top when
     * the TV went to standby is started again by the system the moment it is force-stopped (2026-11-09 11:53:04.180
     * 「Force stopping com.netease.cloudmusic.tv」, 11:53:04.275 「Start proc 6426 … for activity MainActivity」), so
     * closing it without that did nothing at all and it burned its 0.4 of a core for two more hours.
     */
    private void park(long now, long offFor) throws Exception {
        if (offFor < MemoryCare.PARK_SCREEN_OFF_MS || now - parkedAt < PARK_EVERY_MS) return;
        parkedAt = now;
        List<MediaSessions.Session> sessions = MediaSessions.parse(once(app, LocalAdb::mediaSessions));
        // the screen has been off for half an hour: nothing is in front for the user, so nothing is kept but what the
        // last try at closing could not keep down
        List<String> stop = MemoryCare.parkStops(sessions, now, offFor, users(), MemoryCare.parkCooling(againAt, now));
        if (stop.isEmpty()) return;
        homeOnTop();
        Thread.sleep(PARK_HOME_MS);
        long wall = System.currentTimeMillis(), paused = 0;
        Map<String, JSONObject> song = new LinkedHashMap<>();
        for (String p : stop) {
            String title = "", artist = "";
            for (MediaSessions.Session s : sessions) if (p.equals(s.pkg)) {
                paused = Math.max(paused, now - s.updated);
                if (title.isEmpty() && !s.title.isEmpty()) { title = s.title; artist = s.artist; }
            }
            song.put(p, new JSONObject().put("pkg", p).put("title", title).put("artist", artist).put("at", wall));
        }
        once(app, a -> { a.stopApps(stop); return null; });
        Thread.sleep(PARK_CHECK_MS);
        Set<String> back = stillRunning(stop);
        List<String> closed = new ArrayList<>(), cameBack = new ArrayList<>();
        for (String p : stop) {
            if (back.contains(p)) {
                // it was never parked: 继续播放 would replay a song that is still sitting there, and another force-stop
                // five minutes later would be started back the same way
                cameBack.add(p);
                againAt.put(p, now + MemoryCare.PARK_BACKOFF_MS);
            } else {
                closed.add(p);
            }
        }
        JSONArray kept = new JSONArray();
        JSONArray was = load(parked);
        for (int i = 0; i < was.length(); i++) {
            JSONObject o = was.optJSONObject(i);
            // an app that runs again was not parked, so what it was playing is not what 继续播放 should put back
            if (o != null && wall - o.optLong("at") < PARKED_FOR_MS && !back.contains(o.optString("pkg"))) kept.put(o);
        }
        for (String p : closed) kept.put(song.get(p));
        write(parked, kept);
        note(wall, MemoryCare.parkSays(labels(closed), labels(cameBack), paused));
    }

    /**
     * The standby compile (2026-11-10): an app the system never managed to compile (B站: 61 s from its link to its first
     * page, every start unpacking and verifying 38 dex files of 263 MB) is compiled once, in standby, with the step that
     * keeps dex2oat running out of address space turned off. Minutes of work — it runs on a thread of its own and the
     * heartbeat returns at once. Only with the screen off for {@link #COMPILE_OFF_MS}, at most every
     * {@link #COMPILE_EVERY_MS}, one package per check, and never the same code twice within a week.
     */
    private void compile(long now, long offFor) {
        if (compiling || offFor < COMPILE_OFF_MS || now - compileAt < COMPILE_EVERY_MS) return;
        if (available(app) < COMPILE_AVAIL_MB << 20) return;
        compileAt = now;
        compiling = true;
        new Thread(new Runnable() {
            @Override public void run() { compileOne(null); }
        }, "xingyi-app-compile").start();
    }

    /**
     * The standby compile's path run once on demand (2026-11-11, AgentDebug COMPILE): the PC names a package and the
     * same work is done for it at once, whatever its status, so what that path does on the TV — a call that lasts
     * minutes over the loopback ADB link, then the record — can be tried without waiting for the next app the system
     * never compiled. Returns at once: {@code "started"}, {@code "busy"} while a compile runs, or {@code "rejected"}
     * for anything that is not an installed package name ({@link AppCompile#validPackage}) — the name goes into a
     * command on the TV, so it is checked before it can reach one.
     */
    public String compileOnDemand(final String pkg) {
        if (!AppCompile.validPackage(pkg)) return "rejected";
        synchronized (this) {
            if (compiling) return "busy";
            compiling = true;
        }
        new Thread(new Runnable() {
            @Override public void run() { compileOne(pkg); }
        }, "xingyi-app-compile").start();
        return "started";
    }

    /**
     * One compile, on the thread of its own: the guard's lock is never held while it runs. {@code asked} is the package
     * the on-demand compile was given, or null for the standby one, which picks it with {@link AppCompile#next}. What
     * came of it is written to files/agent/compiled.json and said in the guard's note, exactly the same either way —
     * the path the dexopt state gives for the package, or none when it has none. Nothing here may throw: a failure is
     * remembered as a failed try, so the same code is not ground at again every night.
     */
    private void compileOne(final String asked) {
        try {
            JSONObject tried = loadObject(compiled);
            String pkg = asked, path = "";
            boolean ok = false;
            long started = System.currentTimeMillis();
            try {
                Map<String, String> want = AppCompile.uncompiled(once(app, LocalAdb::dexoptStates));
                if (pkg == null) pkg = AppCompile.next(want, users(), tried, started);
                if (pkg != null && want.get(pkg) != null) path = want.get(pkg);
            } catch (Exception | LinkageError ignored) {
                // the TV would not say what was never compiled: the standby compile has nothing to go on and does
                // nothing, an on-demand one goes ahead with no path to record
            }
            if (pkg == null) return;
            try {
                final String one = pkg;
                Boolean done = once(app, a -> a.compileApp(one));
                ok = done != null && done;
            } catch (Exception | LinkageError ignored) {
                // a compile that ended any other way than Success counts as not done
            }
            try {
                // the record files are the guard's: written under its lock (held for these two writes only, never
                // while the compile runs), so a park or a memory note at the same moment is not lost
                synchronized (this) {
                    write(compiled, AppCompile.tried(tried, pkg, path, started, ok));
                    note(System.currentTimeMillis(), AppCompile.line(label(app, pkg), ok, System.currentTimeMillis() - started));
                }
            } catch (Exception | LinkageError ignored) {
                // the record is a note for later; losing it must not take the bridge down
            }
        } finally {
            compiling = false;
        }
    }

    /** Put the home screen on top before force-stopping. Android 9 lets a background app start an activity, and in
     * standby this does not light the screen; no key events and no power key are sent. */
    private void homeOnTop() {
        try {
            app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // nothing came on top: the park goes on, and the read afterwards tells whether it worked
        }
    }

    /** Which of {@code stop} runs again a few seconds after the force-stop: the system started it back, so it was never
     * parked. A {@code ps} that cannot be read counts as closed — the park worked unless the TV says otherwise. */
    private Set<String> stillRunning(List<String> stop) {
        Set<String> out = new HashSet<>();
        Map<String, Long> rss;
        try {
            rss = MemoryCare.byPackage(once(app, LocalAdb::processes));
        } catch (IOException e) {
            return out;
        }
        for (String p : stop) if (rss.containsKey(p)) out.add(p);
        return out;
    }

    private List<String> labels(List<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (String p : pkgs) out.add(label(app, p));
        return out;
    }

    /** How long the screen has been off on the elapsed clock, 0 while it is on. The count starts when the heartbeat
     * first sees it off, so a switch it slept through errs on the short side. */
    private long screenOffFor(long now) {
        if (screenOn()) { screenOffSince = 0; return 0; }
        if (screenOffSince == 0) screenOffSince = now;
        return now - screenOffSince;
    }

    /** Whether the screen is lit; unreadable counts as lit, so nothing is ever parked on a guess. */
    private boolean screenOn() {
        try {
            android.os.PowerManager p = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
            return p == null || p.isInteractive();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The apps the user installed, read again every hour. */
    private Set<String> users() throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (users == null || now - usersAt > USERS_FOR_MS) {
            users = MemoryCare.packages(once(app, LocalAdb::userPackages));
            usersAt = now;
        }
        return users;
    }

    /**
     * What 清理内存 and the guard would stop right now, stopping nothing (AgentDebug MEMGUARD --es mode plan): the PC
     * checks the choice on the real TV, B站 open in the background or in front, without killing anything.
     */
    public synchronized JSONObject plan() throws IOException, JSONException {
        long avail = available(app), wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        long offFor = screenOffFor(elapsed);
        List<Usage.Event> sw = switches(app, wall);
        Set<String> keep = front(app, sw);
        List<MediaSessions.Session> sessions = MediaSessions.parse(once(app, LocalAdb::mediaSessions));
        Set<String> listening = MemoryCare.listening(sessions, elapsed);
        JSONObject out = new JSONObject().put("available_mb", avail >> 20).put("enabled", enabled())
                .put("real_front", MemoryCare.realFront(sw)).put("front", new JSONArray(keep)).put("listening", new JSONArray(listening));
        keep.addAll(listening);
        Map<String, Long> rss = MemoryCare.byPackage(once(app, LocalAdb::processes));
        Set<String> mine = users();
        Map<String, Long> seen = MemoryCare.lastFront(sw, wall);
        JSONObject apps = new JSONObject();
        for (String p : MemoryCare.hogs(rss, mine, 8))
            apps.put(p, (rss.get(p) >> 10) + " MB" + (seen.containsKey(p) ? "，" + (wall - seen.get(p)) / 1000 + " 秒前在前台" : ""));
        return out.put("user_apps", apps).put("would_stop", new JSONArray(MemoryCare.toStop(rss, mine, keep)))
                .put("guard_would_stop", new JSONArray(MemoryCare.guardStops(avail >> 20, rss, mine, keep, seen, wall)))
                .put("screen_off_minutes", offFor / 60_000L)
                .put("park_would_stop", new JSONArray(MemoryCare.parkStops(sessions, elapsed, offFor, mine, MemoryCare.parkCooling(againAt, elapsed))))
                .put("home_back_would", homeBackWould(sw))
                // 诊断 (2026-11-12 10:05/10:11: what the broadcast read as the front at screen-off was already the
                // browser, which is why the criterion moved to the usage log): what the rule found at the last
                // screen-off, which package wrote that event, and how many seconds ago it was
                .put("surfaced_after_off", surfacedDebug(sw))
                .put("last_screen_off", MemoryCare.lastScreenOffPkg(sw))
                .put("screen_off_at_s_ago", screenOffAgo(sw, wall))
                .put("guard_notes", new JSONArray(recent(86_400_000L)))
                .put("compile_would", compileWould(mine, wall))
                .put("compiling", compiling)
                .put("recent", new JSONArray(recent(86_400_000L)));
    }

    /** Which package the standby compile would take on right now, compiling nothing (the dry run's {@code compile_would}). */
    private String compileWould(Set<String> mine, long wall) {
        try {
            Map<String, String> want = AppCompile.uncompiled(once(app, LocalAdb::dexoptStates));
            String pkg = AppCompile.next(want, mine, loadObject(compiled), wall);
            return pkg == null ? "" : pkg;
        } catch (IOException | RuntimeException ignored) {
            // the TV would not say: the rest of the plan is still worth printing
            return "";
        }
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg;
        }
    }

    public synchronized boolean enabled() { return !off.exists(); }

    public synchronized void setEnabled(boolean on) throws IOException {
        if (on) { if (off.exists() && !off.delete()) throw new IOException("开不了内存守护"); }
        else if (!off.exists()) new FileOutputStream(off).close();
    }

    /** What the guard did within the last {@code withinMs}, newest last: 「12:31 内存只剩 280 MB，关掉了…」. */
    public synchronized List<String> recent(long withinMs) {
        List<String> out = new ArrayList<>();
        JSONArray all = load(record);
        long since = System.currentTimeMillis() - withinMs;
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.CHINA);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optLong("at") >= since) out.add(hm.format(new Date(o.optLong("at"))) + " " + o.optString("line"));
        }
        return out;
    }

    private void note(long at, String line) throws IOException, JSONException {
        JSONArray all = load(record), kept = new JSONArray();
        for (int i = Math.max(0, all.length() - 9); i < all.length(); i++) kept.put(all.get(i));
        kept.put(new JSONObject().put("at", at).put("line", line));
        write(record, kept);
    }

    /** What the park closed for {@code pkg} within the last {@code withinMs}: the newest entry, or null (TvTools
     * media_control play reads this to put the song back). */
    static JSONObject parkedRecent(Context c, String pkg, long withinMs) {
        long since = System.currentTimeMillis() - withinMs;
        JSONObject best = null;
        JSONArray all = load(parkedFile(c));
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null || !pkg.equals(o.optString("pkg")) || o.optLong("at") < since) continue;
            if (best == null || o.optLong("at") >= best.optLong("at")) best = o;
        }
        return best;
    }

    /** Drop {@code pkg}'s entries once its song plays again, so 继续播放 does not replay it a second time. */
    static synchronized void parkedForget(Context c, String pkg) throws IOException {
        File f = parkedFile(c);
        if (!f.exists()) return;
        JSONArray all = load(f), kept = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && !pkg.equals(o.optString("pkg"))) kept.put(o);
        }
        write(f, kept);
    }

    static File parkedFile(Context c) {
        File dir = new File(c.getFilesDir(), "agent");
        dir.mkdirs();
        return new File(dir, "parked.json");
    }

    /** Written through a temporary file: a half-written record would be read as no record at all. */
    private static void write(File to, JSONArray kept) throws IOException {
        write(to, kept.toString(), "内存守护的记录写不进去");
    }

    /** The same for the compile's record, which is an object of packages rather than a list of what happened. */
    private static void write(File to, JSONObject kept) throws IOException {
        write(to, kept.toString(), "代码优化的记录写不进去");
    }

    private static void write(File to, String text, String complaint) throws IOException {
        File tmp = new File(to.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(to)) throw new IOException(complaint);
    }

    private static JSONArray load(File f) {
        if (!f.exists()) return new JSONArray();
        try { return new JSONArray(text(f)); } catch (IOException | JSONException e) { return new JSONArray(); }
    }

    /** A record that is not there, or cannot be read, is no record: the compile simply starts from nothing. */
    private static JSONObject loadObject(File f) {
        if (!f.exists()) return new JSONObject();
        try { return new JSONObject(text(f)); } catch (IOException | JSONException e) { return new JSONObject(); }
    }

    private static String text(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
