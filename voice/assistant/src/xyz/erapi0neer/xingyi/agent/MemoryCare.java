package xyz.erapi0neer.xingyi.agent;

import java.util.*;

/**
 * 清理内存 (2026-11-07): 「清理电视的内存」 had 星仪 press 电视管家's 加速, which killed 星仪's own process along with the
 * rest, and the TV had less memory free afterwards than before (478 → 414 MB: what it stopped came straight back). Here
 * only apps the user installed are stopped, and only from the background: never 星仪's own packages (the bridge that is
 * its engine, the app, the home screen), never the app in front, never one that plays or holds a paused session. The
 * system then drops the processes it counts as cached. On this 2 GB TV the one that matters is B站's phone client:
 * 1.3 GB in four processes (main, web, download, push).
 *
 * <p>The memory guard runs the same choice on its own when the TV runs short, more carefully: only apps out of sight for
 * a few minutes, only big ones. Pure: the host reads {@code ps}, {@code pm list packages -3}, the media sessions, when
 * each app was last in front, and the memory figure.
 */
public final class MemoryCare {
    private MemoryCare() { }

    /** 星仪's own packages: the bridge (its engine), the app, the home screen. */
    static final String OURS = "xyz.erapi0neer.";
    /** An app smaller than this is not worth stopping by hand (a 20 MB helper frees nothing anyone feels). */
    static final long MIN_KB = 30 * 1024;
    /** The guard acts below this much available memory… */
    public static final long GUARD_BELOW_MB = 350;
    /** …on apps at least this big… */
    static final long GUARD_MIN_KB = 80 * 1024;
    /** …that have been out of sight at least this long. */
    public static final long GUARD_IDLE_MS = 3 * 60_000L;
    /** The standby park waits this long after the screen goes off: nobody is watching, and nothing is played at once. */
    public static final long PARK_SCREEN_OFF_MS = 30 * 60_000L;
    /** …and closes a media app only after it has been paused or stopped this long: someone may still come back to it. */
    public static final long PARK_PAUSED_MS = 2 * 3_600_000L;
    /** An app the system brought straight back after the park closed it is left alone this long before another try. */
    public static final long PARK_BACKOFF_MS = 6 * 3_600_000L;

    /** Resident memory per package in KB from {@code ps -A -o RSS,NAME}: a package's "pkg" and "pkg:sub" processes add up. */
    public static Map<String, Long> byPackage(String ps) {
        Map<String, Long> out = new HashMap<>();
        if (ps == null) return out;
        for (String line : ps.split("\\r?\\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 2 || !f[0].matches("\\d{1,9}")) continue;
            String name = f[1];
            int colon = name.indexOf(':');
            String pkg = colon > 0 ? name.substring(0, colon) : name;
            if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) continue;
            Long had = out.get(pkg);
            out.put(pkg, (had == null ? 0 : had) + Long.parseLong(f[0]));
        }
        return out;
    }

    /** The packages in {@code pm list packages -3} ("package:tv.danmaku.bili" lines). */
    public static Set<String> packages(String list) {
        Set<String> out = new HashSet<>();
        if (list == null) return out;
        for (String line : list.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("package:") && t.length() > 8) out.add(t.substring(8).trim());
        }
        return out;
    }

    /** A paused session counts as someone coming back to it for this long after it paused. */
    static final long PAUSED_FOR_MS = 30 * 60_000L;

    /**
     * The packages whose media session plays, or paused within {@link #PAUSED_FOR_MS} ({@code nowElapsed}: the device's
     * elapsed-time clock, which the session's {@code updated} is on): someone means to come back to them. A song paused
     * an hour ago no longer holds NetEase's 150 MB.
     */
    public static Set<String> listening(List<MediaSessions.Session> sessions, long nowElapsed) {
        Set<String> out = new HashSet<>();
        if (sessions == null) return out;
        for (MediaSessions.Session s : sessions) {
            boolean recent = s.updated <= 0 || nowElapsed - s.updated < PAUSED_FOR_MS;
            if ("playing".equals(s.state) || "buffering".equals(s.state) || ("paused".equals(s.state) && recent)) out.add(s.pkg);
        }
        return out;
    }

    /** The user's apps holding the most memory, at least 100 MB each, the biggest first (tv_status: 「为什么内存这么小」). */
    public static List<String> hogs(Map<String, Long> rss, Set<String> thirdParty, int max) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : rss.entrySet())
            // not 星仪's own: the home screen is no app to blame (2026-11-07: 「…网易云音乐 145 MB 和桌面 47 MB」)
            if (e.getValue() >= 100 * 1024 && thirdParty.contains(e.getKey()) && !e.getKey().startsWith(OURS)) out.add(e.getKey());
        Collections.sort(out, (a, b) -> Long.compare(rss.get(b), rss.get(a)));
        return out.size() > max ? out.subList(0, max) : out;
    }

    /** Usage events (API 28): an activity came to the front, or left it. */
    static final int TO_FRONT = 1, TO_BACK = 2;
    /** Windows over the app in front that are not where the user is: XiaoAi's panel, the system bars. */
    static final Set<String> OVERLAYS = new HashSet<>(Arrays.asList("com.xiaomi.voicecontrol", "com.xiaomi.wakeupservice",
            "com.android.systemui", "com.xiaomi.mitv.systemui", "android"));

    /** When each package was last in front, from usage events in time order; one still in front counts as {@code now}. */
    public static Map<String, Long> lastFront(List<Usage.Event> events, long now) {
        Map<String, Long> at = new HashMap<>();
        Set<String> in = new HashSet<>();
        for (Usage.Event e : events) {
            if (e.type != TO_FRONT && e.type != TO_BACK) continue;
            at.put(e.pkg, e.at);
            if (e.type == TO_FRONT) in.add(e.pkg); else in.remove(e.pkg);
        }
        for (String p : in) at.put(p, now);
        return at;
    }

    /**
     * The app the user is in: the last to come to the front that is not an overlay. 「小爱同学，清理内存」 said over B站
     * puts XiaoAi's panel in front, and B站 is still the one being watched.
     */
    public static String realFront(List<Usage.Event> events) {
        String front = "";
        for (Usage.Event e : events) if (e.type == TO_FRONT && !OVERLAYS.contains(e.pkg)) front = e.pkg;
        return front;
    }

    /**
     * What a 清理内存 stops, the biggest first: running, installed by the user, at least {@link #MIN_KB}, not 星仪's, not
     * one in {@code keep} (the app in front, one that plays or is paused).
     */
    public static List<String> toStop(Map<String, Long> rss, Set<String> thirdParty, Set<String> keep) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : rss.entrySet()) {
            String p = e.getKey();
            if (e.getValue() < MIN_KB || !thirdParty.contains(p) || p.startsWith(OURS) || (keep != null && keep.contains(p))) continue;
            out.add(p);
        }
        Collections.sort(out, (a, b) -> Long.compare(rss.get(b), rss.get(a)));
        return out;
    }

    /**
     * What the guard stops on its own, or nothing: only while available memory is under {@link #GUARD_BELOW_MB}, and of
     * {@link #toStop}'s apps only those of at least {@link #GUARD_MIN_KB} that have not been in front for
     * {@link #GUARD_IDLE_MS} ({@code lastFront}: when each package was last in front, absent = not in the last hour).
     */
    public static List<String> guardStops(long availableMb, Map<String, Long> rss, Set<String> thirdParty, Set<String> keep,
            Map<String, Long> lastFront, long now) {
        List<String> out = new ArrayList<>();
        if (availableMb >= GUARD_BELOW_MB) return out;
        for (String p : toStop(rss, thirdParty, keep)) {
            Long seen = lastFront == null ? null : lastFront.get(p);
            if (rss.get(p) >= GUARD_MIN_KB && (seen == null || now - seen >= GUARD_IDLE_MS)) out.add(p);
        }
        return out;
    }

    /**
     * Proportional memory (PSS, KB) summed over a package's processes, from {@code dumpsys meminfo -s <pkg>}: one TOTAL
     * line per process. {@code ps}'s resident size counts the framework every app shares in full (2026-11-07: NetEase
     * RSS 242 MB, PSS 148 MB; the bridge 101 MB against 20 MB), fine for ranking, wrong to say aloud.
     */
    public static long pss(String meminfo) {
        long sum = 0;
        if (meminfo == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*TOTAL:?\\s+(\\d+)").matcher(meminfo);
        while (m.find()) sum += Long.parseLong(m.group(1));
        return sum;
    }

    /**
     * The answer to 「清理内存」: what was stopped, and what that freed in the system's own figures (available memory
     * before and after, a couple of seconds apart); what was left alone on purpose ({@code kept}: 「正在放的网易云音乐」);
     * the app in front when it holds much of the memory ({@code frontKb}: its PSS, 0 when not measured).
     */
    public static String say(long beforeBytes, long afterBytes, List<String> stopped, List<String> kept, String front, long frontKb) {
        StringBuilder s = new StringBuilder();
        if (stopped.isEmpty()) s.append("后台没有要关的应用，可用内存 ").append(DeviceFacts.size(afterBytes)).append("。");
        else {
            s.append("清理好了：关掉了后台的");
            for (int i = 0; i < stopped.size() && i < 4; i++) s.append(i == 0 ? "" : "、").append(stopped.get(i));
            if (stopped.size() > 4) s.append("等 ").append(stopped.size()).append(" 个应用");
            s.append("，可用内存从 ").append(DeviceFacts.size(beforeBytes)).append(" 到 ").append(DeviceFacts.size(afterBytes)).append("。");
        }
        if (kept != null && !kept.isEmpty()) s.append(String.join("、", kept)).append("没动。");
        // the app in front holding much of the memory: say so, rather than leave 「才这么点」 unexplained
        if (front != null && !front.isEmpty() && frontKb >= 300 * 1024)
            s.append("前台的").append(front).append("自己占了 ").append(DeviceFacts.size(frontKb * 1024)).append("，正在用的应用清不掉。");
        return s.toString();
    }

    /**
     * What the standby park closes (2026-11-09 09:48, NetEase paused since the night before): the TV keeps its SoC on
     * while it sleeps (XiaoAi listens), so an app left paused for hours keeps decoding silence — NetEase 20.1 % of a
     * core itself plus media.codec 12.4 % and the audio HAL 8.6 %, about 0.4 of a core for nothing, and 279 MB of 2 GB.
     * A media STOP key does not change that (measured), and the guard above only acts when memory runs short, which it
     * never does here.
     *
     * <p>Only with the screen off for {@link #PARK_SCREEN_OFF_MS} (nobody is watching) and a session paused or stopped
     * for {@link #PARK_PAUSED_MS} (nobody is coming back to it): never one that plays or buffers, never 星仪's own, never
     * one in {@code keep}. A session whose update time was not printed is left alone — how long it has been like that
     * is not known. Sorted, no duplicates.
     */
    public static List<String> parkStops(List<MediaSessions.Session> sessions, long nowElapsed, long screenOffForMs,
            Set<String> thirdParty, Set<String> keep) {
        Set<String> out = new TreeSet<>();
        if (sessions == null || screenOffForMs < PARK_SCREEN_OFF_MS) return new ArrayList<>();
        for (MediaSessions.Session s : sessions) {
            if (s.pkg == null || s.pkg.isEmpty() || !thirdParty.contains(s.pkg) || s.pkg.startsWith(OURS)) continue;
            if (keep != null && keep.contains(s.pkg)) continue;
            if (!"paused".equals(s.state) && !"stopped".equals(s.state)) continue;
            if (s.updated <= 0 || nowElapsed - s.updated < PARK_PAUSED_MS) continue;
            out.add(s.pkg);
        }
        return new ArrayList<>(out);
    }

    /**
     * The line the guard records and 星仪 explains later:
     * 「电视待机中，网易云音乐暂停了 19 小时：关掉了它（暂停时它还在耗电、占着内存）」.
     */
    public static String parkSays(List<String> labels, long pausedMs) {
        return parkSays(labels, Collections.<String>emptyList(), pausedMs);
    }

    /**
     * The same line when the park read the processes again afterwards and the system had already started some of them
     * back (2026-11-09 11:53: 95 ms after the force-stop, 「Start proc 6426:com.netease.cloudmusic.tv … for activity
     * MainActivity」 — it was the top activity when the TV went to standby). Those are never said to be closed:
     * 「电视待机中，网易云音乐暂停了 19 小时：网易云音乐关掉后又被系统拉起来了」.
     */
    public static String parkSays(List<String> closed, List<String> cameBack, long pausedMs) {
        List<String> all = new ArrayList<>(closed);
        all.addAll(cameBack);
        long hours = pausedMs / 3_600_000L;
        String howLong = hours > 0 ? hours + " 小时" : Math.max(1, pausedMs / 60_000L) + " 分钟";
        StringBuilder s = new StringBuilder("电视待机中，").append(String.join("、", all)).append("暂停了 ").append(howLong).append("：");
        if (!closed.isEmpty()) {
            String them = closed.size() > 1 ? "它们" : "它";
            s.append("关掉了").append(them).append("（暂停时").append(them).append("还在耗电、占着内存）");
        }
        if (!cameBack.isEmpty()) {
            if (!closed.isEmpty()) s.append("；");
            // with nothing closed there is nothing to tell them apart from, so the name is not said twice
            if (!closed.isEmpty()) s.append(String.join("、", cameBack));
            s.append("关掉后又被系统拉起来了");
        }
        return s.toString();
    }

    /**
     * The packages the park must not touch yet: the last try at closing them ended with the system starting them
     * straight back, and another force-stop would only do that again. {@code untilAt}: when each package may be tried
     * again, on the elapsed clock.
     */
    public static Set<String> parkCooling(Map<String, Long> untilAt, long now) {
        Set<String> out = new HashSet<>();
        if (untilAt == null) return out;
        for (Map.Entry<String, Long> e : untilAt.entrySet())
            if (e.getKey() != null && e.getValue() != null && e.getValue() > now) out.add(e.getKey());
        return out;
    }

    /**
     * Who surfaced at the moment the screen went off — this is the whole criterion (2026-11-11 20:06, 2026-11-12 10:05
     * and 10:11, three accidents with the same shape): the package of the last {@link Usage#FOREGROUND} event in
     * {@code events} whose time is within {@code [offAt - 1000, offAt + 10000]}; {@code ""} when there is none.
     *
     * <p>Not 「who was in front when the screen went off」: on 2026-11-12 10:11 the version that read the front inside
     * the ACTION_SCREEN_OFF broadcast got {@code com.phlox.tvwebbrowser} — the firmware had already killed the desktop
     * and let the browser up before the broadcast arrived, so the signal itself was late and no sampling rate would
     * have helped. The system's own usage log has the exact order (10:05:20 the desktop to the front, 10:05:23 the
     * browser to the front, 10:05:23 SCREEN_NON_INTERACTIVE); that is where the answer is read from.
     *
     * <p>{@code -1000} because the system may write 「the desktop came to the front」 and 「the screen went off」 in
     * either order within the same second; {@code +10000} because what surfaces is sometimes logged a few seconds
     * late. The last one and not the first: the last package to come to the front in that window is what the user sees
     * when the TV comes back on.
     */
    public static String surfacedAfter(List<Usage.Event> events, long offAt) {
        String out = "";
        if (events == null) return out;
        for (Usage.Event e : events)
            if (e.type == Usage.FOREGROUND && e.at >= offAt - 1_000L && e.at <= offAt + 10_000L) out = e.pkg;
        return out;
    }

    /**
     * Has the home screen to be put back on top? What surfaced at screen-off is not the desktop. When the user was
     * watching a video and switched the TV off, the firmware kills the video and the system brings the desktop back —
     * the first package to the front is then the desktop, and nothing is done. A false positive is worse than the bug:
     * taking the desktop over something being watched is worse than coming back on to the browser.
     */
    public static boolean homeBack(String surfaced, String home) {
        return surfaced != null && !surfaced.isEmpty() && home != null && !home.isEmpty() && !surfaced.equals(home);
    }

    /**
     * When the last {@link Usage#SCREEN_OFF} event in {@code events} was, in ms; 0 when there is none. The screen is
     * off now, so the last screen-off in the hour's window is where this standby period starts. It is here and not in
     * the guard because the {@code agent.tv} package cannot read {@link Usage.Event}'s fields.
     */
    public static long lastScreenOffAt(List<Usage.Event> events) {
        long at = 0;
        if (events == null) return at;
        for (Usage.Event e : events) if (e.type == Usage.SCREEN_OFF) at = e.at;
        return at;
    }

    /** Which package wrote that screen-off event ({@code android} on this TV) — only for the debug field to show. */
    public static String lastScreenOffPkg(List<Usage.Event> events) {
        String pkg = "";
        if (events == null) return pkg;
        for (Usage.Event e : events) if (e.type == Usage.SCREEN_OFF) pkg = e.pkg;
        return pkg;
    }

    /** The guard's note: 「待机时哔哩哔哩浮到了最上面，已经把桌面放回去」. */
    public static String homeBackSays(String label) {
        return "待机时" + label + "浮到了最上面，已经把桌面放回去";
    }

    /** The guard's line for the log and for 星仪 to explain later: 「内存只剩 280 MB，关掉了后台 3 分钟以上没用的哔哩哔哩，可用回到 1.5 GB」. */
    public static String guardSays(long beforeBytes, List<String> stopped, long idleMinutes, long afterBytes) {
        StringBuilder s = new StringBuilder("内存只剩 ").append(DeviceFacts.size(beforeBytes)).append("，关掉了后台");
        if (idleMinutes > 0) s.append(" ").append(idleMinutes).append(" 分钟以上没用的");
        s.append(String.join("、", stopped));
        if (afterBytes > beforeBytes) s.append("，可用回到 ").append(DeviceFacts.size(afterBytes));
        return s.toString();
    }
}
