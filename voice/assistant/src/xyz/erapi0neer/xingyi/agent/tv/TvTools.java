package xyz.erapi0neer.xingyi.agent.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.*;
import xyz.erapi0neer.xingyi.GatewayConfig;
import xyz.erapi0neer.xingyi.Legacy;
import xyz.erapi0neer.xingyi.LocalAdb;
import xyz.erapi0neer.xingyi.ModelRouter;
import xyz.erapi0neer.xingyi.SleepTimer;
import xyz.erapi0neer.xingyi.agent.*;

/** Every tool the agent can call on this TV. One instance per task; close() releases the ADB link. */
final class TvTools implements AutoCloseable {
    static final String BILI = "tv.danmaku.bili", NETEASE = "com.netease.cloudmusic.tv";
    /** 电视管家: its 加速 stops 星仪's own process with the rest (2026-11-07), so memory goes through free_memory. */
    static final String TV_MANAGER = "com.xiaomi.mitv.tvmanager";
    static final String NOT_TV_MANAGER = "不要用电视管家清理内存：它的加速会把星仪的后台一起关掉（这个任务会被打断），关掉的应用马上又起来，"
            + "实测清理后可用内存反而更少。清理内存用 free_memory，一步完成。";
    /** switch_input 的闸门（2026-11-11 校准：「我想看CCTV5」被当成了「切到 HDMI1」，用户对着「无信号」）。 */
    static final String NO_INPUT_NAMED = "用户这句话没有提到信号源或外接的设备（HDMI、机顶盒、游戏机、电脑）：不要切换信号源，"
            + "这台电视的 HDMI 口上不一定接着东西，切过去是一片无信号。"
            + "要看电视台直播而电视上没有直播应用时，用 tv_guide 查它现在在播什么，如实告诉用户放不了。";
    private static final java.util.regex.Pattern MEMORY_WORDS = java.util.regex.Pattern.compile("内存|加速|清理|释放|垃圾|卡顿|太卡|好卡|很卡|优化");
    private static final java.util.regex.Pattern SPEED_UP = java.util.regex.Pattern.compile("加速|清理|释放|一键|扫描|优化");
    /** 云视听极光, Tencent Video's TV app: its search takes com.tencent.qqlivetv.open with tenvideo2://?action=9 (a plain
     * VIEW of the same link resolves to nothing on this TV, `cmd package resolve-activity`, 2026-11-06). */
    static final String JIGUANG = "com.ktcp.video", JIGUANG_OPEN = "com.tencent.qqlivetv.open";
    /** 别的平台在电视上是哪个应用（2026-11-10，装没装在 apps() 的名字里找）；这几个以外的平台电视上没有。 */
    private static final Map<String, String> PLATFORM_APPS = new HashMap<>();
    static {
        PLATFORM_APPS.put("爱奇艺", "银河奇异果");
        PLATFORM_APPS.put("优酷", "酷喵");
        PLATFORM_APPS.put("芒果TV", "芒果TV");
        PLATFORM_APPS.put("哔哩哔哩", "哔哩哔哩");
        PLATFORM_APPS.put("央视频", "央视频");
    }
    private static final ComponentName NETEASE_VOICE = new ComponentName(NETEASE, "com.netease.cloudmusic.tv.activity.TvRedirectActivity");
    private static final ComponentName SETTINGS = new ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity");
    private static final String[][] ALIASES = {
            {BILI, "b站", "bilibili", "哔哩哔哩", "哔哩", "bili", "哔哩哔哩官方"},
            {NETEASE, "网易云", "网易云音乐", "云音乐", "网易音乐"},
            {"com.ktcp.video", "云视听极光", "极光", "腾讯视频"},
            {"com.xiaomi.mitv.appstore", "应用商店", "小米应用商店", "商店"},
            {"com.xiaomi.mitv.settings", "设置", "电视设置", "系统设置"},
    };
    private static final Set<String> BLOCKED_SCHEMES = new HashSet<>(Arrays.asList(
            "file", "content", "javascript", "data", "intent", "about", "android-app", "tel", "sms", "smsto", "mailto", "package"));
    private static final Map<String, Integer> KEYS = new LinkedHashMap<>();
    private static final Map<String, String> KEY_NAMES = new HashMap<>();
    /** MiTV settings pages that resolve on this TV (checked 2026-11-04 with cmd package resolve-activity). */
    private static final Map<String, String[]> SETTINGS_PAGES = new HashMap<>();
    private static final Map<String, String> SETTINGS_NAMES = new HashMap<>();
    static {
        String x = "com.xiaomi.mitv.settings.";
        String[][] pages = {{"home", x + "SETTINGS", "设置首页"}, {"wifi", x + "WIFI_SETTINGS", "无线网络"},
                {"ethernet", x + "ETHERNET_SETTINGS", "有线网络"},
                {"speed_test", x + "NETWORK_SPEED_TEST", "网速测试", "com.xiaomi.mitv.tvmanager"},
                {"about", x + "ABOUT_SETTINGS", "关于本机"}, {"security", x + "SECURITY_SETTINGS", "安全"}};
        // Each opened live on 2026-11-04. Left out because they crash MiTV's settings app when opened directly:
        // bluetooth (BLUETOOTH_SETTINGS, BLUETOOTH_ENTRY, RC: NullPointerException), NETWORKINFO_SETTINGS
        // (class missing), GENERAL_SETTINGS, DISPLAYSOUND_SETTINGS, DISKINFO_SETTINGS.
        for (String[] p : pages) { SETTINGS_PAGES.put(p[0], new String[]{p[1], p.length > 3 ? p[3] : SETTINGS.getPackageName()}); SETTINGS_NAMES.put(p[0], p[2]); }
    }
    static {
        String[][] k = {{"up", "19", "上"}, {"down", "20", "下"}, {"left", "21", "左"}, {"right", "22", "右"}, {"center", "23", "确认"},
                {"back", "4", "返回"}, {"home", "3", "主页"}, {"menu", "82", "菜单"}, {"play_pause", "85", "播放/暂停"},
                {"next", "87", "下一个"}, {"previous", "88", "上一个"}, {"volume_up", "24", "音量+"}, {"volume_down", "25", "音量-"},
                {"mute", "164", "静音"}, {"enter", "66", "回车"}};
        for (String[] e : k) { KEYS.put(e[0], Integer.parseInt(e[1])); KEY_NAMES.put(e[0], e[2]); }
    }

    /** Not private: {@link SelfRepair} needs it for the component it rebinds and for WRITE_SECURE_SETTINGS. */
    final Context context;
    private final PackageManager pm;
    private final AudioManager audio;
    final Adb adb;
    final List<Tool> tools = new ArrayList<>();
    /** XiaoAi's own instructions for the request this task came from (empty otherwise). In 星仪接管模式 XiaoAi still
     * acts on its basic functions: a relative action it already took is not repeated (a second 下一首 or 调大). */
    volatile Set<String> xiaoaiDid = Collections.emptySet();
    /** When this request reached 星仪 (elapsed-time clock, 0 = unknown) and, for a sentence XiaoAi switched tracks for
     * itself, what was playing then: media_control reads both to tell whether XiaoAi's skip landed (requestStarted). */
    volatile long requestElapsed;
    volatile MediaSessions.Session atRequest;
    /** What find_installer already looked up in this task: the model plans and then searches the same app again
     * (2026-11-08: two searches, ~10 s each), and the second one finds exactly what the first did. */
    private final InstallerCache installers = new InstallerCache();

    /** The public data feeds (Feeds, 2026-11-06): a quote for 15 s, a name's code for a day, the exchange rates for 10
     * minutes, the hot lists for 3. */
    private static final Feeds.Fetch QUOTE_FEED = Feeds.cached(Feeds.https(), 15_000);
    private static final Feeds.Fetch NAME_FEED = Feeds.cached(Feeds.https(), 24 * 3_600_000L);
    private static final Feeds.Fetch RATE_FEED = Feeds.cached(Feeds.https(), 10 * 60_000L);
    private static final Feeds.Fetch HOT_FEED = Feeds.cached(Feeds.https(), 3 * 60_000L);
    /** Gold for a minute; a lottery's last draw for half an hour (they draw three times a week, in the evening). */
    private static final Feeds.Fetch GOLD_FEED = Feeds.cached(Feeds.https(), 60_000);
    private static final Feeds.Fetch LOTTERY_FEED = Feeds.cached(Feeds.https(), 30 * 60_000L);
    /** The list last read out (hot_list) for 「放第二个」, kept 10 minutes; not for the PC's test and debug sources. */
    private static volatile JSONObject lastList;
    private static volatile long lastListAt;

    /** The show tencent_video_open last opened in this process, and when (elapsedRealtime): 云视听极光 keeps no media
     * session, so what is on is only known from what was asked for. Six hours — this evening's show is still on. */
    private static volatile String openedTitle = "";
    private static volatile long openedAt;

    /** 「（刚才打开的是《…》）」 for media_now_playing, or nothing when nothing was opened lately. */
    private static String openedShow() {
        String title = openedTitle;
        return title.isEmpty() || SystemClock.elapsedRealtime() - openedAt >= 6 * 3_600_000L
                ? "" : "（刚才打开的是《" + title + "》）";
    }

    public static JSONObject lastList(long now) {
        JSONObject l = lastList;
        return l != null && now - lastListAt < 10 * 60_000L ? l : null;
    }

    private static void rememberList(Tool.Turn t, JSONObject list) {
        if ("test".equals(t.source()) || "debug".equals(t.source())) return;
        lastList = list;
        lastListAt = System.currentTimeMillis();
    }

    /** The result with the panel's card for the list that was just read out: the five items the panel draws, with
     * their meta lines. Nothing to draw (no title, no items) and the result is left as it was. */
    private static JSONObject withList(JSONObject result, JSONObject list) throws JSONException {
        JSONObject card = Cards.list(list == null ? "" : list.optString("what"), list == null ? null : list.optJSONArray("items"));
        if (Cards.valid(card)) result.put("card", card);
        return result;
    }

    /** Earthquake bulletins for a minute, fuel prices for an hour, fund values for 10 minutes, the history calendar a day. */
    private static final Feeds.Fetch QUAKE_FEED = Feeds.cached(Feeds.https(), 60_000);
    private static final Feeds.Fetch PRICE_FEED = Feeds.cached(Feeds.https(), 10 * 60_000L);
    private static final Feeds.Fetch HISTORY_FEED = Feeds.cached(Feeds.https(), 24 * 3_600_000L);

    /** Programme guides for 10 minutes, headlines for 5. */
    private static final Feeds.Fetch GUIDE_FEED = Feeds.cached(Feeds.https(), 10 * 60_000L);
    private static final Feeds.Fetch NEWS_FEED = Feeds.cached(Feeds.https(), 5 * 60_000L);
    /** Where a programme is looked for when no channel is said: CCTV's main channels and the big satellite ones. */
    private static final String[] GUIDE_DEFAULT = {"cctv1", "cctv13", "cctv5", "cctv8", "cctv6", "cctv3", "cctv5plus", "hunan", "dongfang",
            "zhejiang", "jiangsu", "btv1"};

    /** NBA scores for 30 s (a game on changes by the minute). */
    private static final Feeds.Fetch SPORTS_FEED = Feeds.cached(Feeds.https(), 30_000);
    /** The almanac for a day (its days do not change). */
    private static final Feeds.Fetch ALMANAC_FEED = Feeds.cached(Feeds.https(), 24 * 3_600_000L);
    /** Weather warnings and typhoon tracks for 10 minutes (the center updates them about hourly), fetched together. */
    private static final Alerts.Fetches ALERT_FEED = new Alerts.Fetches(Feeds.https());
    /** B站's anime timelines for 10 minutes (an episode coming out flips to 已更新). */
    private static final Feeds.Fetch BANGUMI_FEED = Feeds.cached(Feeds.https(), 10 * 60_000L);
    /** A B站 episode by its web address, which the app takes like its own links. */
    private static final java.util.regex.Pattern BILI_EPISODE = java.util.regex.Pattern.compile("^https?://(?:www|m)\\.bilibili\\.com/bangumi/play/ep\\d+");
    /** Douban's ratings for 6 hours (they move slowly). */
    private static final Feeds.Fetch MOVIE_FEED = Feeds.cached(Feeds.https(), 6 * 3_600_000L);
    /** B站's searches for 2 minutes (「放第二个」 right after a list does not search again; the site rations searches). */
    private static final Feeds.Fetch BILI_FEED = Feeds.cached(Feeds.https(), 2 * 60_000L);
    /** 腾讯视频自己的搜索（POST），十分钟：同一个片名十分钟内不再问一次。回复有 0.1–1.1 MB，缓存的是切小以后的那份。 */
    private static final Feeds.Post TENCENT_SLIM =
            (url, body, ms) -> TencentVideo.slim(Feeds.httpsPost().post(url, body, ms));
    private static final Feeds.Post TENCENT_FEED = Feeds.cached(TENCENT_SLIM, 10 * 60_000L);
    /** 腾讯视频的选集列表（POST），十分钟：同一部片子的同一页十分钟内不再问一次。回复切小以后才进缓存。 */
    private static final Feeds.Post EPISODE_SLIM =
            (url, body, ms) -> TencentVideo.slimEpisodes(Feeds.httpsPost().post(url, body, ms));
    private static final Feeds.Post EPISODE_FEED = Feeds.cached(EPISODE_SLIM, 10 * 60_000L);

    private boolean xiaoaiDid(String... prefixes) {
        for (String n : xiaoaiDid) for (String p : prefixes) if (n.startsWith(p)) return true;
        return false;
    }

    /** XiaoAi's instructions that switch tracks or episodes. */
    private static final String[] SKIPS = {"PlaybackController.", "AudioPlayer.", "VideoPlayer."};
    /** How long after the request XiaoAi's own skip may still land: 星仪's key waits that long before it is pressed. */
    static final long SKIP_GRACE_MS = 4000;
    /** The least the press waits regardless, for a request whose model round already took longer than SKIP_GRACE_MS. */
    static final long SKIP_LATE_MS = 1500;

    /** The engine calls this as a task starts, after xiaoaiDid: one read of the session, only when XiaoAi switched. */
    void requestStarted(long elapsed) {
        requestElapsed = elapsed;
        if (xiaoaiDid(SKIPS)) atRequest = nowPlaying();
    }

    /** XiaoAi switched tracks for this sentence and the player shows it did not (MediaSessions.xiaoaiSkip): then 星仪's
     * key is the only skip there is. Never in 云视听极光, which keeps no session to tell by. */
    private boolean skipMissed(MediaSessions.Session now) {
        if (JIGUANG.equals(foreground())) return false;
        return MediaSessions.xiaoaiSkip(atRequest, now, requestElapsed, SystemClock.elapsedRealtime())
                == MediaSessions.Skip.MISSED;
    }

    /** The end of the refusal of a second skip: what plays now, so the answer needs no media_now_playing round. */
    private String playingNow(MediaSessions.Session now) {
        if (now == null) return "用 media_now_playing 看现在放的是什么，告诉用户结果。";
        return "现在" + label(now.pkg) + " " + now.describe() + MediaSessions.at(now, SystemClock.elapsedRealtime()) + "：照这个告诉用户结果。";
    }

    /** XiaoAi's skip landing late after all, before 星仪 presses its own: what is playing then, or null when nothing
     * changed before the key may be pressed. Anchored on the press, not on the request: a model round takes longer than
     * SKIP_GRACE_MS on this TV (2026-11-18 audit: anchored on the request, this loop never ran on the model path, and
     * XiaoAi's late skip plus 星仪's key moved two tracks), so the press always gives XiaoAi at least 1.5 s more. */
    private JSONObject skipLandedLate(Tool.Turn t) throws Exception {
        MediaSessions.Session before = nowPlaying();
        long end = Math.max(requestElapsed + SKIP_GRACE_MS, SystemClock.elapsedRealtime() + SKIP_LATE_MS);
        while (before != null && SystemClock.elapsedRealtime() < end && !t.cancelled()) {
            Thread.sleep(300);
            MediaSessions.Session s = nowPlaying();
            if (s != null && !MediaSessions.sameTrack(before, s))
                return ok("observed", label(s.pkg) + " " + s.describe() + "（小爱的那一下切过去了，星仪没有再切）").put("session", session(s));
        }
        return null;
    }

    /** How often this task may search the web; one instance per task, because TvTools is one per task. */
    private final SearchBudget searches = new SearchBudget();
    /** X searches are slow and dear (30–60 s, about 0.3 USD): one per task, whatever the web search found. */
    private final SearchBudget xSearches = new SearchBudget(1);
    /** And a day's worth, whoever asks (a schedule firing every few minutes is not one task). */
    static final int X_PER_DAY = 15, WEB_PER_DAY = 150;
    /** Element numbers the model uses refer to this digest. */
    private volatile ScreenDigest last;
    private volatile long lastAt;

    TvTools(Context context) {
        this.context = context.getApplicationContext();
        pm = this.context.getPackageManager();
        audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        adb = new Adb(this.context);
        build();
        for (int i = 0; i < tools.size(); i++) tools.set(i, quietWhileAsleep(tools.get(i)));
    }

    /**
     * What the PC's silent test may not do while the TV sleeps: light the screen, open an app, make a sound, change the
     * volume or press keys. 2026-11-07 05:00: 「放影视飓风最新的视频」 from a test called wake_screen and opened B站 at
     * night (the guard was on the video link only); with the screen on, as in the daytime runs, a test still acts.
     */
    static final Set<String> AWAKE_ONLY = new HashSet<>(Arrays.asList("wake_screen", "open_app", "open_link", "open_settings",
            "bili_search", "tencent_video_search", "tencent_video_open", "netease_play", "netease_playlist", "media_control", "set_volume", "adjust_volume",
            "screen_click", "press_keys", "screen_input", "screen_scroll", "install_app", "switch_input", "sleep_timer",
            "netease_karaoke", "media_seek"));

    static final String ASLEEP_TEST = "这是电脑上的静音测试，电视在待机：不点亮屏幕、不打开应用、不出声、不按键，如实说这一步测试时跳过。";

    private Tool quietWhileAsleep(Tool tool) {
        if (!AWAKE_ONLY.contains(tool.name())) return tool;
        return new Tool() {
            @Override public String name() { return tool.name(); }
            @Override public Gate gate(JSONObject a, Turn t) {
                if ("test".equals(t.source())) {
                    android.os.PowerManager p = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (p != null && !p.isInteractive()) return Gate.deny(ASLEEP_TEST);
                }
                return tool.gate(a, t);
            }
            @Override public String describe(JSONObject a, Turn t) { return tool.describe(a, t); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception { return tool.run(a, t); }
        };
    }

    @Override public void close() { adb.close(); }

    // ---- shared helpers --------------------------------------------------------------------------

    /** One loopback ADB connection per task, reopened once on failure. A listener that refuses even the retry is
     * first handed to the host to bring back (the bridge switches ADB debugging on again); if it stays gone it is
     * reported as AdbUnavailable rather than as a raw socket error. */
    static final class Adb {
        interface Call<T> { T run(LocalAdb a) throws IOException; }
        /** How far apart keys go through the TV's remote service, and how long its last key's UP is waited for. */
        private static final long REMOTE_GAP_MS = 250, REMOTE_UP_MS = 200;
        private final Context c;
        private LocalAdb link;
        Adb(Context c) { this.c = c; }
        synchronized <T> T call(Call<T> f) throws IOException {
            try { return f.run(get()); }
            catch (IOException first) {
                reset();
                try { return f.run(get()); }
                catch (IOException second) {
                    IOException typed = AdbUnavailable.classify(second);
                    if (!(typed instanceof AdbUnavailable) || !AdbUnavailable.heal()) throw typed;
                    reset();
                    try { return f.run(get()); }
                    catch (IOException third) { throw AdbUnavailable.classify(third); }
                }
            }
        }
        /**
         * A burst of keys. Up to four keys that the TV's own remote service (6095) knows go through
         * {@link RemoteKeys} — 31–58 ms each against ~0.85 s for every {@code input keyevent} (2026-11-10 01:44: the
         * 随心唱 path, down + right ×4 + up, took 2.2 s instead of 9.5 s). The service holds each key ~185 ms, so they
         * go 250 ms apart and the last UP is waited for. A key it refuses, and everything after it, goes the old way in
         * one call; more than four keys, or any key without a name there (media keys, POWER), are exactly as before.
         */
        void keys(int... codes) throws IOException {
            if (codes.length > 0 && codes.length <= 4 && allNamed(codes)) {
                int sent = 0;
                for (; sent < codes.length; sent++) {
                    if (sent > 0) pause(REMOTE_GAP_MS);
                    if (!RemoteKeys.press(KeyNames.of(codes[sent]))) break;
                }
                if (sent == codes.length) { pause(REMOTE_UP_MS); return; }
                int[] rest = Arrays.copyOfRange(codes, sent, codes.length);
                call(a -> { a.keyEvents(rest); return null; });
                return;
            }
            call(a -> { a.keyEvents(codes); return null; });
        }
        private boolean allNamed(int[] codes) {
            for (int c : codes) if (KeyNames.of(c) == null) return false;
            return true;
        }
        private void pause(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        void tap(int x, int y) throws IOException { call(a -> { a.tap(x, y); return null; }); }
        private LocalAdb get() { if (link == null) link = new LocalAdb(c); return link; }
        private void reset() { if (link != null) link.cancel(); link = null; }
        synchronized void close() { reset(); }
    }

    ScreenDigest read() throws Exception {
        ScreenDigest d = readOnce();
        // B站 pops its own login modal over a page it was asked for, sometimes seconds after the page (2026-11-10 10:45:
        // 历史记录 behind .sms.SmsLoginDialogActivityV3): no step of a task should stare at it, so every read closes it
        if (PlayerPages.isBiliLogin(BILI.equals(Eyes.foregroundPackage()) ? BILI : "", Eyes.foregroundClass())) {
            adb.keys(KEYS.get("back"));
            // polled, not slept 600 ms: the dismiss is usually up in one or two frames (2026-11-18 audit: this read is
            // taken several times per video, and every sleep was paid per read). 600 ms stays the ceiling.
            long goneBy = SystemClock.uptimeMillis() + 600;
            while (SystemClock.uptimeMillis() < goneBy
                    && PlayerPages.isBiliLogin(BILI.equals(Eyes.foregroundPackage()) ? BILI : "", Eyes.foregroundClass()))
                Thread.sleep(150);
            d = readOnce();
        }
        // and its splash ad covers the page underneath: a full-screen ad with a 「跳过 5」 countdown, on a cold start and
        // again whenever B站 comes back from the background (2026-11-10 evening). No key presses — the countdown's own
        // button is clicked through Eyes, once, and what is behind it is what this read returns.
        int skip = PlayerPages.biliSplashSkip(d.text());
        ScreenDigest.Element skipButton = skip > 0 && Eyes.get() != null ? d.element(skip) : null;
        if (skipButton != null) {
            Eyes.click(skipButton.node);
            // the ad's window goes with the click: Eyes' change counter (cheap) says when, 500 ms is the ceiling
            long seen = Eyes.changeCount(), goneBy = SystemClock.uptimeMillis() + 500;
            while (SystemClock.uptimeMillis() < goneBy && Eyes.changeCount() == seen) Thread.sleep(150);
            d = readOnce();
        }
        return d;
    }

    private ScreenDigest readOnce() throws Exception {
        Eyes eyes = Eyes.get();
        ScreenDigest.Node root = eyes == null ? null : eyes.capture();
        if (root == null) root = ScreenDigest.parseDump(adb.call(LocalAdb::uiDump));
        String pkg = root == null ? "" : root.pkg;
        String activity = pkg.equals(Eyes.foregroundPackage()) ? shortClass(Eyes.foregroundClass(), pkg) : "";
        last = ScreenDigest.of(root, pkg.isEmpty() ? "" : label(pkg) + " " + pkg, activity);
        lastAt = SystemClock.uptimeMillis();
        return last;
    }

    /** True when the screen the model decided on is more than 3 s old (consent wait, slow model round). */
    private boolean stale() { return last == null || SystemClock.uptimeMillis() - lastAt > 3000; }

    /** The same element on a fresh read: same role, same main text (counts like 播放量 may tick), nearest place. */
    private ScreenDigest.Element relocate(ScreenDigest.Element old) throws Exception {
        ScreenDigest now = read();
        ScreenDigest.Element best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ScreenDigest.Element e : now.elements) {
            if (!e.role.equals(old.role) || !sameText(e.label, old.label)) continue;
            int d = Math.abs(e.node.centerX() - old.node.centerX()) + Math.abs(e.node.centerY() - old.node.centerY());
            if (d < 120 && d < bestDistance) { best = e; bestDistance = d; }
        }
        return best;
    }

    static boolean sameText(String a, String b) {
        if (a.equals(b)) return true;
        String x = mainPart(a), y = mainPart(b);
        return !x.isEmpty() && x.equals(y);
    }

    private static String mainPart(String label) {
        String best = "";
        for (String p : label.split(" · ")) if (p.length() > best.length()) best = p;
        return best;
    }

    /** "，画面换到了 X" / "，页面从 A 换成 B" when the action moved somewhere, else "". */
    static String change(ScreenDigest from, ScreenDigest to) {
        if (from == null || to == null) return "";
        if (!from.app.equals(to.app) && !to.app.isEmpty()) return "，画面换到了" + to.app.split(" ")[0];
        if (!from.activity.equals(to.activity) && !to.activity.isEmpty())
            return "，页面从" + (from.activity.isEmpty() ? "原页面" : from.activity) + "换成" + to.activity;
        return "";
    }

    /** Waits for the UI to react and then go quiet, and reads it. */
    ScreenDigest settle(long changesBefore, long maxMs) throws Exception {
        long start = SystemClock.uptimeMillis(), end = start + maxMs;
        if (Eyes.get() == null) {
            Thread.sleep(Math.min(maxMs, 1200));
        } else {
            while (SystemClock.uptimeMillis() < end) {
                boolean reacted = Eyes.changeCount() != changesBefore;
                long quiet = SystemClock.uptimeMillis() - Eyes.lastChangeAt();
                if (reacted && quiet >= 350) break;
                if (!reacted && SystemClock.uptimeMillis() - start > 900) break;
                Thread.sleep(80);
            }
        }
        return read();
    }

    private ScreenDigest.Element element(int id) throws Exception {
        if (last == null) read();
        return last.element(id);
    }

    String foreground() {
        String pkg = Eyes.foregroundPackage();
        if (pkg.isEmpty()) {
            // mFocusedApp, not mFocusedWindow: the focused window can be one of XiaoAi's overlays, the focused app never is
            try { pkg = FrontApp.pkg(adb.call(LocalAdb::focusedApp)); }
            catch (IOException ignored) { }
        }
        return pkg;
    }

    /**
     * 前台是不是云视听极光的某个页面（类名不带包名）。它的页面都是自己画的，Eyes 收不到窗口事件（2026-11-10
     * 实测：前台类一直停在 OpenJumpProxyActivity），只有窗口管理器的 mFocusedApp 说的是对的。读不到就是不是。
     */
    private boolean jiguangPage(String simpleName) {
        String focused;
        try { focused = adb.call(LocalAdb::focusedApp); }
        catch (IOException e) { return false; }
        if (!JIGUANG.equals(FrontApp.pkg(focused))) return false;
        String cls = FrontApp.cls(focused);
        int dot = cls.lastIndexOf('.');
        return simpleName.equals(dot >= 0 ? cls.substring(dot + 1) : cls);
    }

    /**
     * 「暂停」「继续播放」 while 云视听极光 plays: it keeps no media session, so {@code dispatchMediaKeyEvent} hands the
     * key to the media-session service — which gives it to whichever app last had a media-button receiver (B站 or 网易云,
     * which may even wake up) and never to 云视听极光 (2026-11-11 00:11 on the TV: the key injected into its own window,
     * {@code input keyevent 127} / {@code 126}, paused and resumed the episode). So the key goes to the focused window
     * instead — unless another app's media session is playing at this moment, which then is what the key is for.
     * Null when this is not that case, and media_control sends its key as always.
     *
     * <p>Its keys are toggles, and said to XiaoAi its own voice agent (com.ktcp.tvagent) acts on the sentence about a
     * second and a half later (2026-11-11 20:03: it paused at 18.898, our key 164 ms after that started it again; the
     * 「继续播放」 right after was paused by our key). So the key is only sent for what the app did not do itself, and
     * what it did is read back from the audio service ({@link AudioPlayers}) instead of being claimed.
     */
    private JSONObject jiguangKey(String action, int code, Tool.Turn t) throws Exception {
        // another app's session that is playing right now (music under the page) keeps the key; one that is only
        // paused does not — 网易云 paused an hour ago must not take 「暂停」 away from the show on screen (lead review)
        MediaSessions.Session s = nowPlaying();
        if (s != null && "playing".equals(s.state)) return null;
        String focused;
        try { focused = adb.call(LocalAdb::focusedApp); }
        catch (IOException e) { return null; }
        if (!PlayerPages.isJiguangPlayer(FrontApp.pkg(focused), FrontApp.cls(focused))) return null;
        if ("next".equals(action) || "previous".equals(action)) {
            adb.keys(code);
            // its pages are self-drawn, so nothing of it can be read back: say what was sent, never pretend it was checked
            return ok("sent", "next".equals(action) ? "已切到下一集" : "已切到上一集")
                    .put("note", "（它的画面读不出来，没法核对）");
        }
        String state = jiguangState();
        // XiaoAi's own voice agent acts on the sentence itself: give it its second and a half before pressing anything
        if ("xiaoai".equals(t.source())) state = pollJiguang(action, t, 2500);
        // 停止 on this player is its pause key: there is nothing to stop to
        int key = "stop".equals(action) ? KeyEvent.KEYCODE_MEDIA_PAUSE : code;
        if (PlayerPages.jiguangNeedsKey(action, state)) adb.keys(key);
        state = pollJiguang(action, t, 2000);
        if (!PlayerPages.jiguangSettled(action, state)) {
            // the app's own action landed while ours was on its way and the two cancelled out: one more key, once
            state = jiguangState();
            if (PlayerPages.jiguangNeedsKey(action, state)) {
                adb.keys(key);
                state = pollJiguang(action, t, 1500);
            }
        }
        if (PlayerPages.jiguangSettled(action, state))
            return ok("observed", "play".equals(action) ? "云视听极光继续播放了" : "云视听极光已暂停");
        // said: this sentence is the answer for a quick command too (AgentEngine.ran) — the key was pressed and read back
        return fail("unconfirmed", "play".equals(action) ? "按了播放键，云视听极光还没有开始放"
                : "按了暂停键，云视听极光还在放：片头广告暂停不了，正片开始后再说一次").put("said", true);
    }

    /** What 云视听极光's own players are doing right now, from the audio service. {@code NONE} when it cannot be read. */
    private String jiguangState() {
        int uid;
        try { uid = pm.getApplicationInfo(JIGUANG, 0).uid; }
        catch (PackageManager.NameNotFoundException e) { return AudioPlayers.NONE; }
        try { return AudioPlayers.state(adb.call(LocalAdb::audioPlayers), uid); }
        catch (IOException e) { return AudioPlayers.NONE; }
    }

    /** The app's state every 300 ms until it is where the request wants it, or {@code ms} ran out; the state last read. */
    private String pollJiguang(String action, Tool.Turn t, long ms) throws InterruptedException {
        String state = jiguangState();
        long end = SystemClock.uptimeMillis() + ms;
        while (!PlayerPages.jiguangSettled(action, state) && SystemClock.uptimeMillis() < end) {
            if (t.cancelled()) throw new InterruptedException();
            Thread.sleep(300);
            state = jiguangState();
        }
        return state;
    }

    /** The activity in front as {package, class}, the way the host reads it: Eyes' own foreground first, then
     * mFocusedApp. Two empty strings when neither can be read. Never throws — a caller only asks "is this that page". */
    private String[] frontActivity() {
        if (Eyes.get() != null && !Eyes.foregroundPackage().isEmpty())
            return new String[] {Eyes.foregroundPackage(), Eyes.foregroundClass()};
        try {
            String focused = adb.call(LocalAdb::focusedApp);
            return new String[] {FrontApp.pkg(focused), FrontApp.cls(focused)};
        } catch (IOException e) {
            return new String[] {"", ""};
        }
    }

    /**
     * B站 drops its own 「登录注册解锁更多精彩内容」 modal on top of the page it was just asked for (2026-11-10 10:45:
     * bilibili://history opened 历史记录 behind .sms.SmsLoginDialogActivityV3, and the model spent 90 s reading the screen
     * without ever closing it). It is a dialog activity on top of the page, so one BACK closes it. True when it pressed.
     */
    private boolean dismissBiliLogin() throws IOException, InterruptedException {
        return dismissBiliLogin(frontActivity());
    }

    /** The same, for a caller that has just read the front itself (openBiliVideo's wait loop). */
    private boolean dismissBiliLogin(String[] front) throws IOException, InterruptedException {
        if (!PlayerPages.isBiliLogin(front[0], front[1])) return false;
        adb.keys(KEYS.get("back"));
        // polled, as in read(): the dialog is gone in a frame or two, and openBiliVideo pays this every round of its
        // wait (2026-11-18 review). 600 ms stays the ceiling.
        long goneBy = SystemClock.uptimeMillis() + 600;
        while (SystemClock.uptimeMillis() < goneBy) {
            Thread.sleep(150);
            String[] now = frontActivity();
            if (!PlayerPages.isBiliLogin(now[0], now[1])) break;
        }
        return true;
    }

    /** 「（关掉了B站自己弹的登录框）」 onto a tool's own words, when that call pressed BACK on B站's login dialog. */
    private static JSONObject biliLoginNote(JSONObject result, boolean dismissed) throws JSONException {
        if (!dismissed) return result;
        return result.put("message", result.optString("message") + "（关掉了B站自己弹的登录框）");
    }

    private boolean waitForPackage(String pkg, long ms) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            if (pkg.equals(Eyes.get() != null ? Eyes.foregroundPackage() : foreground())) return true;
            Thread.sleep(150);
        }
        return pkg.equals(foreground());
    }

    String label(String pkg) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    private static String shortClass(String cls, String pkg) {
        if (cls == null || cls.isEmpty()) return "";
        String s = cls.startsWith(pkg) ? cls.substring(pkg.length()) : cls;
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** Launchable apps: label → package. The developer's own test apps are not in it (OwnApps): they are not something
     * to open, and Fluid Lab's screen is black (2026-11-11: 星仪 opened it looking for its own panel). */
    Map<String, String> apps() {
        Map<String, String> out = new TreeMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (r.activityInfo == null) continue;
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(context.getPackageName()) || OwnApps.hidden(pkg) || out.containsValue(pkg)) continue;
                CharSequence l = r.loadLabel(pm);
                out.put(l == null ? pkg : l.toString().trim(), pkg);
            }
        }
        return out;
    }

    String resolveApp(String query) {
        String q = norm(query);
        Map<String, String> apps = apps();
        Collection<String> pkgs = apps.values();
        if (pkgs.contains(query.trim())) return query.trim();
        // A phone package name for an app whose TV build adds a suffix (com.netease.cloudmusic → ….tv).
        if (query.trim().matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")) {
            for (String p : pkgs) if (p.startsWith(query.trim() + ".")) return p;
        }
        for (String[] row : ALIASES) {
            for (int i = 1; i < row.length; i++) {
                if (norm(row[i]).equals(q)) {
                    if (pkgs.contains(row[0]) || row[0].equals(SETTINGS.getPackageName())) return row[0];
                    return null;
                }
            }
        }
        String best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String l = norm(e.getKey());
            if (l.equals(q)) return e.getValue();
            if ((l.contains(q) || q.contains(l)) && l.length() < bestLen && l.length() >= 2) { best = e.getValue(); bestLen = l.length(); }
        }
        return best;
    }

    private static String norm(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[\\s·・]", ""); }

    private static StoreCatalog storeCatalog;

    /** The Xiaomi TV app store's catalog (assets/agent/store_apps.json); an empty one when the asset cannot be read. */
    StoreCatalog catalog() {
        synchronized (TvTools.class) {
            if (storeCatalog != null) return storeCatalog;
            try (java.io.InputStream in = context.getAssets().open("agent/store_apps.json")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                storeCatalog = new StoreCatalog(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                try { storeCatalog = new StoreCatalog("{}"); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            }
            return storeCatalog;
        }
    }

    /** The day's count of a paid search, in the agent's own files. */
    private DailyCap cap(String name, int max, String what) {
        return new DailyCap(new File(context.getFilesDir(), "agent/" + name + "-day.json"), max, what);
    }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()); }

    /** The strings of a JSON array one of our own tools put in its result (SelfRepair's ok / mended / unfixable). */
    private static List<String> strings(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    /** 读屏、面板、本机调试 and 小爱的卡片, checked and mended where the bridge can reach them ({@link SelfRepair}).
     * A method of our own so the tool's {@code this} (an anonymous Base) is not what gets passed. */
    private JSONObject selfRepair() throws JSONException { return SelfRepair.run(this); }

    /** The TV's place with its coordinates (the location setting the user chose, else the located one), or null. */
    Weather.Where here() { return here(context); }

    static Weather.Where here(Context context) {
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            String user = android.provider.Settings.System.getString(cr, "mitv_user_location");
            String located = android.provider.Settings.System.getString(cr, "mitv_location");
            String name = Place.of(user, located);
            if (name == null) return null;
            Weather.Where w = Place.userSet(user) ? Weather.fromLocation(user, name) : null;
            return w != null ? w : Weather.fromLocation(Place.userSet(user) ? null : located, name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Where the TV is (Settings.System mitv_user_location / mitv_location): 「上海徐汇」, or null. */
    String place() {
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            return Place.of(android.provider.Settings.System.getString(cr, "mitv_user_location"),
                    android.provider.Settings.System.getString(cr, "mitv_location"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean installed(String pkg) {
        try { context.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }

    /** The app's own start screen (AgentEngine.startNeteaseEarly brings it up ahead of a song). */
    Intent launchIntent(String pkg) {
        if (pkg.equals(SETTINGS.getPackageName())) return new Intent().setComponent(SETTINGS);
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private void start(Intent i) { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }

    MediaSessions.Session nowPlaying() {
        try { return MediaSessions.current(adb.call(LocalAdb::mediaSessions)); }
        catch (IOException e) { return null; }
    }

    /** What is on the TV right now, in one speakable line, for the reply when a task stops early. Only names the app
     * and what is playing; a session that cannot be read just leaves the title out. Never throws. */
    String observe() {
        String pkg;
        try { pkg = foreground(); } catch (RuntimeException e) { return ""; }
        if (pkg.isEmpty()) return "";
        String app;
        try { app = label(pkg); } catch (RuntimeException e) { return ""; }
        if (app.equals(pkg)) return ""; // no label for it: better say nothing than read a package name aloud
        String title = "";
        try {
            MediaSessions.Session s = nowPlaying();
            if (s != null && "playing".equals(s.state)) title = s.title;
        } catch (RuntimeException ignored) { }
        return Observation.describe(app, title);
    }

    static JSONObject ok(String status, String message) throws JSONException {
        return new JSONObject().put("ok", true).put("status", status).put("message", message);
    }
    static JSONObject fail(String status, String message) throws JSONException {
        return new JSONObject().put("ok", false).put("status", status).put("message", message);
    }
    private JSONObject withScreen(JSONObject r, ScreenDigest d) throws JSONException {
        r.put("screen", d.text());
        // Bilibili search page read by any tool: the query sits in the top row; state the UP's newest uploads.
        if (d.app.endsWith(" " + BILI) && d.activity.contains("Search")) {
            for (ScreenDigest.Element e : d.elements) {
                if (e.node.centerY() > d.height / 10) break;
                JSONObject up = BiliPage.upUploads(d, e.label);
                if (up != null) { r.put("up", up); break; }
            }
        }
        return r;
    }
    private JSONObject session(MediaSessions.Session s) throws JSONException {
        if (s == null) return null;
        JSONObject o = new JSONObject().put("app", label(s.pkg)).put("package", s.pkg).put("state", s.state)
                .put("title", s.title).put("artist", s.artist).put("album", s.album);
        // how far into the track: what tells 「这是重播吗」 (MediaSessions.at); the spoken message stays as it was
        String at = MediaSessions.at(s, SystemClock.elapsedRealtime());
        if (!at.isEmpty()) o.put("position", at.substring(at.indexOf("放到") + 2).trim());
        return o;
    }

    /**
     * media_control exit: leave the app in front for the home in one step — stop what it is playing, then the HOME key
     * the way press_keys sends it (2026-11-08 「回到桌面退出网易云」: press_keys needs a plan, so the model either stopped
     * the song and called that done, or spent 9 rounds on back presses).
     *
     * <p>blank: the user said the screen is black (own-ui, 2026-11-11). The desktop in front is then not "nothing to
     * do" — the model has to look at the picture and tell the user what it is, not open another app — and an app that
     * was left is named as what was in front, since that is what made the screen black.
     */
    private JSONObject exitApp(String named, boolean blank, Tool.Turn t) throws Exception {
        String pkg = foreground(), home = homePackage();
        MediaSessions.Session was = nowPlaying();
        boolean playing = was != null && "playing".equals(was.state);
        boolean atHome = !home.isEmpty() && home.equals(pkg);
        // the app the user named, when it is not the one in front: the app in front is not the one to leave (2026-11-09
        // review: 「退出网易云」 said over B站 stopped B站's video, left B站 and answered 「已退出哔哩哔哩」)
        String want = named == null || named.trim().isEmpty() ? null : resolveApp(named.trim());
        if (want != null && !want.equals(pkg)) {
            if (!playing || !want.equals(was.pkg))
                return ok("observed", label(want) + "没在前台，也没在放，不用退出").put("session", session(was));
            // playing behind the app in front (or the desktop): stop it there and leave the front as it is
            mediaKey(KeyEvent.KEYCODE_MEDIA_STOP);
            Thread.sleep(1000);
            MediaSessions.Session s = nowPlaying();
            if (s != null && "playing".equals(s.state) && want.equals(s.pkg)) { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); Thread.sleep(500); s = nowPlaying(); }
            if (s != null && "playing".equals(s.state) && want.equals(s.pkg))
                return fail("unconfirmed", label(want) + "还在后台放《" + s.title + "》，没能停下").put("session", session(s));
            return ok("observed", "已停掉后台的" + label(want) + (atHome || pkg.isEmpty() ? "" : "，前台的" + label(pkg) + "没动"))
                    .put("session", session(s));
        }
        if (!playing && atHome)
            // 黑屏 with the desktop already in front: saying 「已经在桌面了」 left the user looking at the black screen
            return blank
                    ? fail("at_home", "现在前台就是桌面：先用 screen_look 看看画面到底是什么样，再告诉用户；不要打开别的应用")
                            .put("session", session(was))
                    : ok("observed", "已经在桌面了").put("session", session(was));
        // the app being left: the one in front, or — from the home — the one playing behind it (「退出网易云」 said on the
        // desktop while a song plays: not 「已退出Orrery」)
        String leaving = atHome || pkg.isEmpty() ? (playing ? was.pkg : "") : pkg;
        String app = leaving.isEmpty() ? "" : label(leaving);
        if (playing) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_STOP);
            Thread.sleep(1000);
            MediaSessions.Session s = nowPlaying();
            if (s != null && "playing".equals(s.state)) { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); Thread.sleep(500); }
        }
        adb.keys(KEYS.get("home"));
        boolean arrived = !home.isEmpty() && waitFront(home, 1500, t);
        MediaSessions.Session left = nowPlaying();
        // it kept playing behind the home: leaving it is not done, say so instead of claiming the app is out
        if (left != null && "playing".equals(left.state) && (leaving.isEmpty() || leaving.equals(left.pkg)))
            return fail("unconfirmed", (app.isEmpty() ? "这个应用" : app) + "还在后台放《" + left.title + "》，没能退干净").put("session", session(left));
        if (!arrived) return ok("requested", "已让" + (app.isEmpty() ? "前台的应用" : app) + "退出，还没看到桌面在前台").put("session", session(left));
        String said = app.isEmpty() ? "前台的应用" : app;
        return ok("observed", blank ? "刚才前台是" + said + "，已经退回桌面" : "已退出" + said + "，回到桌面")
                .put("session", session(left));
    }

    /** A media key to whatever is playing now, as media_control sends the others. */
    private void mediaKey(int code) {
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    /**
     * 电脑上的静音测试在待机的电视上不放歌：点歌会点亮屏幕、出声。null 表示可以继续。
     * （2026-11-07 起 {@link #AWAKE_ONLY} 的闸门已经拦过一次，这一句是给模型看的实情。）
     */
    private JSONObject songAsleep(Tool.Turn t) throws JSONException {
        if (!"test".equals(t.source())) return null;
        android.os.PowerManager p = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return p != null && !p.isInteractive()
                ? fail("rejected", "这是电脑上的静音测试，电视在待机：不点亮屏幕放歌，如实说这一步测试时跳过。") : null;
    }

    /**
     * 放歌前先点亮屏幕：网易云的开屏页（评论日历）在屏幕关着时不往下走，2026-11-09 20:11 点的歌在那页停了 17 秒，
     * 直到用户自己说「屏幕打开」。用户 2026-11-10 要的是屏幕一关音乐就停，所以反过来：待机时点的歌先亮屏。
     * 返回要加在工具那句话后面的字（屏幕本来就亮着是空串）。
     */
    private String wakeForSong(Tool.Turn t) throws Exception {
        android.os.PowerManager p = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (p == null || p.isInteractive()) return "";
        return ScreenWake.ensureOn(context, t::cancelled) ? "（先点亮了屏幕）" : "";
    }

    /** 把「（先点亮了屏幕）」加在工具那句话后面；没点亮过就原样返回。 */
    private static JSONObject withLit(JSONObject r, String lit) throws JSONException {
        if (lit.isEmpty() || !r.has("message")) return r;
        return r.put("message", r.optString("message") + lit);
    }

    /**
     * 「继续播放」 when the standby park closed NetEase hours ago (2026-11-09 09:48: paused since the night before, still
     * burning 0.4 of a core): the play key has nothing to act on, no session plays or is paused, and files/agent/parked.json
     * still holds what was closed — put that song back the way netease_play does, from the start, and drop the entry so a
     * second 继续播放 does not replay it. Null when this is not that case, and media_control answers as it always did.
     */
    private JSONObject replayParked(Tool.Turn t) throws Exception {
        if (!nothingPlayingOrPaused()) return null;
        JSONObject entry = MemoryGuard.parkedRecent(context, NETEASE, MemoryGuard.PARKED_FOR_MS);
        if (entry == null) return null;
        String title = entry.optString("title").trim(), artist = entry.optString("artist").trim();
        if (title.isEmpty()) return null;
        JSONObject asleep = songAsleep(t);
        if (asleep != null) return asleep;
        String lit = wakeForSong(t);
        JSONObject played = Legacy.playNetease(context, title, artist.isEmpty() ? null : artist, t::cancelled);
        if (!played.optBoolean("ok")) return played; // the entry stays: the next 继续播放 tries again
        MemoryGuard.parkedForget(context, NETEASE);
        return withLit(ok("observed", "网易云在待机时关掉了，重新放《" + title + "》（从头开始）").put("parked", entry), lit);
    }

    /** No session playing or paused: a media key had nothing to act on. Unreadable counts as something playing, so
     * nothing is ever replayed on a guess. */
    private boolean nothingPlayingOrPaused() {
        try {
            for (MediaSessions.Session s : MediaSessions.parse(adb.call(LocalAdb::mediaSessions)))
                if ("playing".equals(s.state) || "paused".equals(s.state)) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The package the HOME key lands on: what the home intent resolves to (the desktop, Orrery on this TV). */
    private String homePackage() {
        ResolveInfo r = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return r == null || r.activityInfo == null ? "" : r.activityInfo.packageName;
    }

    /** Wait for a package to be in front, the way exit needs it: Eyes first, the focused window when Eyes says nothing. */
    private boolean waitFront(String pkg, long ms, Tool.Turn t) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            if (pkg.equals(foreground())) return true;
            if (SystemClock.uptimeMillis() >= end) return false;
            if (t.cancelled()) throw new InterruptedException();
            Thread.sleep(150);
        }
    }

    /** A click that opened a player page starts playing a few seconds later, so wait for the media session to say so
     * instead of letting the model ask again (seen 2026-11-04: right after the click media_now_playing reported
     * 没有正在播放的媒体). Other pages are left alone. */
    private JSONObject awaitPlayback(JSONObject result, ScreenDigest d, Tool.Turn t) throws Exception {
        if (d == null) return result;
        String pkg = foreground();
        if (!PlayerPages.isPlayer(pkg, d.activity)) return result;
        MediaSessions.Session s = null;
        long end = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < end) {
            if (t.cancelled()) throw new InterruptedException();
            s = nowPlaying();
            if (s != null && pkg.equals(s.pkg) && "playing".equals(s.state)) break;
            s = null;
            Thread.sleep(700);
        }
        if (s == null) return result.put("message", result.optString("message") + "，播放页已打开，还没读到播放状态");
        JSONObject playing = result.put("playing", session(s))
                .put("message", result.optString("message") + "，正在播放" + (s.title.isEmpty() ? "" : "《" + s.title + "》"));
        // a B站 video was seen playing: fill the screen with it (the half-screen player is the phone app's page)
        if (BILI.equals(pkg) && biliFullScreen(t)) playing.put("fullscreen", true);
        return playing;
    }

    /**
     * A B站 video by its link (bili_videos, hot_list): the player page, then playback of a video that was not playing
     * before, so a video already open is not taken for the new one. A cold B站 needs ~15 s on this TV and sometimes
     * swallows the first intent (as with bili_search): sent once more when it is up, idle and not on a player.
     */
    private JSONObject openBiliVideo(Intent i, Tool.Turn t) throws Exception {
        // a video on a sleeping TV: the screen first (2026-11-07 calibration: a scheduled 「播放…」 at night opened the
        // video without wake_screen once); never from the PC's silent test at night
        android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean woke = false;
        if (power != null && !power.isInteractive()) {
            if ("test".equals(t.source()))
                return fail("rejected", "这是电脑上的静音测试，电视在待机：不点亮屏幕打开视频，如实说这一步测试时跳过。");
            woke = ScreenWake.ensureOn(context, t::cancelled);
        }
        String litNote = woke ? "（先点亮了屏幕）" : "";
        MediaSessions.Session was = nowPlaying();
        String old = was != null && BILI.equals(was.pkg) ? was.title : null;
        // a cold B站: not in front and with no media session of its own. Its first intent starts the process and its
        // IntentHandlerActivity, and then nothing is ever displayed (2026-11-10 10:42: 35 s on the handler, the run gave
        // up). A cold start also needs longer than a warm one, and the link is worth sending once more.
        boolean cold = !BILI.equals(foreground()) && !(was != null && BILI.equals(was.pkg));
        long before = Eyes.changeCount(), sentAt = SystemClock.uptimeMillis(), end = sentAt + (cold ? 50000 : 25000);
        start(i);
        // a cold B站 is half a minute of work on this TV (2026-11-10 evening: the page at 15 s, playback at ~33 s), and
        // the panel and XiaoAi were silent through all of it. Say so once, while it is still starting.
        if (cold) t.say("哔哩哔哩启动要半分钟左右，稍等");
        ScreenDigest d = null;
        boolean resent = false, player = false, dismissed = false;
        // B站's screen is read at most every BILI_READ_GAP_MS while it starts, and the player page is known from the
        // window's own class (an event, no read). Every read makes B站's main thread build its whole accessibility tree
        // while it is busiest; nothing here needs it more often. (The 80–90 s this tool took on 2026-11-10 were not the
        // reads: B站's playback was never seen at all, see MediaSessions.)
        long seen = before, readAt = 0;
        while (SystemClock.uptimeMillis() < end) {
            if (t.cancelled()) throw new InterruptedException();
            Thread.sleep(500);
            String[] front = frontActivity();
            boolean inFront = BILI.equals(front[0]);
            if (inFront && PlayerPages.isPlayer(front[0], front[1])) { player = true; break; }
            long changes = Eyes.changeCount();
            if (inFront && changes != seen && SystemClock.uptimeMillis() - readAt > BILI_READ_GAP_MS) {
                seen = changes;
                readAt = SystemClock.uptimeMillis();
                d = read();
                if (player = PlayerPages.isPlayer(BILI, d.activity)) break;
            }
            if (!dismissed && inFront) dismissed = dismissBiliLogin(front);
            long waited = SystemClock.uptimeMillis() - sentAt;
            // cold: the link is sent once more only after 30 s without a player page. A cold B站 reaches its page in
            // 15–19 s since its code was compiled (2026-11-10 evening); the resend at 10 s (from the day it ran from its
            // APK and took a minute) landed in the middle of that start and the video then played 50–85 s after the link
            // instead of 33–35 s. Not only when Eyes sees B站 in front: a stuck handler never draws a window.
            boolean stuck = cold ? waited > 30000 && !PlayerPages.isPlayer(front[0], front[1])
                    : waited > 12000 && SystemClock.uptimeMillis() - Eyes.lastChangeAt() > 1500;
            if ((inFront || cold) && !resent && stuck) {
                resent = true;
                start(i);
            }
        }
        if (d == null) d = settle(before, 500);
        if (!player) {
            return biliLoginNote(withScreen(BILI.equals(foreground()) ? ok("observed", "已用哔哩哔哩打开，但还没到视频的播放页，看一下屏幕上是什么")
                    : ok("requested", "已请求哔哩哔哩打开这个视频，前台还不是它"), d), dismissed);
        }
        MediaSessions.Session s = null;
        // a cold start plays 18–40 s after its player page is up (2026-11-10 evening: the page at 15 s, playback at
        // 33–55 s), so 25 s still left the model looking again; a warm one still needs only its 8 s
        long wait = SystemClock.uptimeMillis() + (cold ? 45000 : 8000);
        while (SystemClock.uptimeMillis() < wait) {
            if (t.cancelled()) throw new InterruptedException();
            s = nowPlaying();
            if (s != null && BILI.equals(s.pkg) && "playing".equals(s.state) && (old == null || !old.equals(s.title))) break;
            s = null;
            // B站's splash ad can land after the player page is up (2026-11-10: seconds after its home on a cold start):
            // a read while waiting is what skips it (read() clicks 跳过) — every few seconds, not more (see above)
            if (SystemClock.uptimeMillis() - readAt > BILI_READ_GAP_MS && BILI.equals(Eyes.foregroundPackage())) {
                readAt = SystemClock.uptimeMillis();
                d = read();
            }
            // 400 ms, not 1500: a media session read costs ~90 ms on this TV (media_now_playing's own median), so the
            // moment playback starts is read within 0.4 s instead of up to 1.5 s later — up to 1.1 s per video, and the
            // screen is still read only every BILI_READ_GAP_MS (a read is the expensive one). The 68-86 s cold starts of
            // 11-10 were not this sleep: most of them was the media-bili session parse (fixed since).
            Thread.sleep(400);
        }
        // The player page is up and playback was still not read: B站 starts the video by itself a little later, and
        // everything the model did after this on 2026-11-10 23:10 only got in its way (media_now_playing, a click on
        // 展开更多 that opened the UP's page, a play/pause that paused the video that had started meanwhile). So this
        // is the answer, and the note says what must not be done to that page.
        if (s == null) {
            JSONObject loading = biliLoginNote(withScreen(ok("observed",
                    "已用哔哩哔哩打开这个视频的播放页" + litNote + "，视频还在加载，稍等就会自己开始播放"), d), dismissed);
            return loading.put("loading", true)
                    .put("note", "B站会自己开始播放：不要点屏幕、不要按键（确认键是暂停），直接把这句话告诉用户");
        }
        JSONObject playing = biliLoginNote(withScreen(ok("observed",
                "已用哔哩哔哩打开" + litNote + "，正在播放" + (s.title.isEmpty() ? "" : "《" + s.title + "》")), d), dismissed)
                .put("playing", session(s));
        // the video is playing: the half-screen player is filled out to the whole screen (nothing is tapped while B站
        // is still loading — that result above is left alone)
        if (biliFullScreen(t)) playing.put("fullscreen", true);
        return playing;
    }

    /**
     * 「播放林俊杰的歌」「林俊杰的合集」「林俊杰热门的歌曲」 (2026-11-07): a run of the singer's songs, not one. With only an
     * artist, netease_play used to open the catalog's single best pick; it ended after one song, and 「播放林俊杰的合集」
     * then cost 13 rounds of pressing keys in NetEase. NetEase's voice search (the route it registered for voice
     * assistants) plays the signed-in account's playable results for the name, in order.
     */
    private JSONObject playArtist(String artist, Tool.Turn t) throws Exception {
        if (launchIntent(NETEASE) == null) return fail("unavailable", "电视上没有网易云音乐");
        MediaSessions.Session was = nowPlaying();
        String old = was != null && NETEASE.equals(was.pkg) && "playing".equals(was.state) ? was.title : null;
        Uri uri = new Uri.Builder().scheme("voice").authority("netease.audioplayer").path("/search")
                .appendQueryParameter("keywords", artist).build();
        start(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "voice/audio").setComponent(NETEASE_VOICE));
        long began = SystemClock.uptimeMillis(), end = began + 12000;
        MediaSessions.Session s = null;
        while (SystemClock.uptimeMillis() < end) {
            if (t.cancelled()) throw new InterruptedException();
            Thread.sleep(700);
            s = nowPlaying();
            boolean playing = s != null && NETEASE.equals(s.pkg) && "playing".equals(s.state);
            // a song of the same singer already playing is the old one until the title changes (or a while has passed)
            if (playing && (old == null || !old.equals(s.title) || SystemClock.uptimeMillis() - began > 6000)) break;
            s = null;
        }
        if (s == null) return fail("unconfirmed", "已让网易云搜索「" + artist + "」并连续播放，还没读到在放").put("session", session(nowPlaying()));
        boolean his = norm(s.artist).contains(norm(artist));
        String now = his ? "网易云正在连续播放" + artist + "的歌，现在是《" + s.title + "》"
                : "网易云按「" + artist + "」搜到的歌在连续播放，现在是" + (s.artist.isEmpty() ? "" : s.artist + "的") + "《" + s.title + "》";
        return ok("observed", now + (s.queue > 1 ? "，一共排了 " + s.queue + " 首" : "") + "。").put("session", session(s)).put("queue", s.queue);
    }

    /**
     * B站 opens a video on its own page with the player in the top half of the screen (2026-11-11 00:02 on the TV): on a
     * TV that is not what 「播放…」 means. Eyes reads nothing of that player while the video plays, so the controls are
     * brought up by one touch on the player itself and 「展开全屏」 is then clicked through Eyes — at most one tap and one
     * click, and no key press at all (OK is pause on that page). Nothing else on the page is ever touched: it carries a
     * shop card with 「立即购买」 right under the player. False whenever that is not what happened; the video plays either
     * way, so a failure here is never worth a retry.
     */
    private boolean biliFullScreen(Tool.Turn t) {
        try {
            if (Eyes.get() == null || !biliPlayerInFront()) return false;
            ScreenDigest d = read();
            if (PlayerPages.biliIsFullScreen(d.text())) return true;
            int control = PlayerPages.biliFullScreenControl(d.text());
            if (control < 0) {
                // the controls are hidden while the video plays, and come up for a few seconds after a touch on the
                // player (which takes the top 55 % of a 1920×1080 screen)
                if (t.cancelled() || !biliPlayerInFront()) return false;
                adb.tap(960, 300);
                Thread.sleep(700);
                d = read();
                control = PlayerPages.biliFullScreenControl(d.text());
            }
            ScreenDigest.Element expand = control > 0 && Eyes.get() != null ? d.element(control) : null;
            if (expand == null || t.cancelled()) return false;
            Eyes.click(expand.node);
            Thread.sleep(1200);
            return PlayerPages.biliIsFullScreen(read().text());
        } catch (Exception e) {
            return false;
        }
    }

    /** B站's own player page in front, read from the window's own class — never from the screen. */
    private boolean biliPlayerInFront() {
        String[] front = frontActivity();
        return PlayerPages.isPlayer(front[0], front[1]);
    }

    /**
     * B站 keeps the results tab of the previous search: a new one can open on 番剧 or 影视, which show
     * "暂无搜索结果，为你推荐热门内容" and unrelated shows (2026-11-05: the user read them as being sent elsewhere, and
     * the model clicked 综合 seven times; an accessibility click does not switch these tabs). Focus + OK does.
     */
    private ScreenDigest ensureTab(ScreenDigest d, String tab, Tool.Turn t) throws Exception {
        for (int i = 0; i < 3; i++) {
            ScreenDigest.Element e = null;
            for (ScreenDigest.Element x : d.elements) if (tab.equals(x.label)) { e = x; break; }
            if (e == null || e.node.selected) return d;
            if (t.cancelled()) throw new InterruptedException();
            long before = Eyes.changeCount();
            if (Eyes.focus(e.node)) adb.keys(23);
            else adb.tap(e.node.centerX(), e.node.centerY());
            d = settle(before, 2500);
        }
        return d;
    }

    /** How far apart the keys of one burst go: the TV needs a moment to move the focus, and the control bar counts as
     * shown while keys keep coming. */
    private static final long KEY_GAP_MS = 280;
    /** NetEase's control bar stays up about 2–3 s after the last key (seen on the TV 2026-11-09): within BAR_UP_MS the
     * focus is surely still where the keys left it; after BAR_HIDES_MS the bar has surely gone. */
    private static final long BAR_UP_MS = 1500, BAR_HIDES_MS = 3500;
    /** How often B站's screen may be read while it is starting (openBiliVideo's wait loop, bili_search's). */
    private static final long BILI_READ_GAP_MS = 4000;

    /** A boolean argument, however the model wrote it (Contract.check turns the word "true" into true, a direct call
     * may not have gone through it). */
    private static boolean flag(JSONObject a, String key) {
        Object v = a == null ? null : a.opt(key);
        return v instanceof Boolean ? (Boolean) v : "true".equals(String.valueOf(v));
    }

    /**
     * The keys that put the focus on NetEase's 随心唱 mic icon, sent as one burst: DOWN shows the bottom control bar
     * with the focus on 暂停/播放 in the middle, RIGHT ×4 walks it to 播放列表 at the right end, UP lands on the mic icon
     * above the progress bar on the right (2026-11-09 17:25 on the TV, Eyes: 图标 焦点 @94,73).
     *
     * <p>The bar hides itself after about 2–3 s without keys, and with it hidden UP opens a full-screen remote
     * tutorial and CENTER pauses the song — so nothing may wait in the middle, and UP or CENTER is never sent without
     * the DOWN that shows the bar right before it. {@code toggle} adds the CENTER that flips 随心唱.
     */
    private void micKeys(boolean toggle) throws Exception {
        adb.keys(KEYS.get("down"));
        for (int i = 0; i < 4; i++) { Thread.sleep(KEY_GAP_MS); adb.keys(KEYS.get("right")); }
        Thread.sleep(KEY_GAP_MS);
        adb.keys(KEYS.get("up"));
        if (toggle) { Thread.sleep(KEY_GAP_MS); adb.keys(KEYS.get("center")); }
    }

    // ---- tools -----------------------------------------------------------------------------------

    private abstract static class Base implements Tool {
        private final String name;
        Base(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public Gate gate(JSONObject a, Turn t) { return Gate.act(); }
    }

    private void build() {
        tools.add(new Base("screen_read") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "看一下屏幕"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                return withScreen(ok("observed", ""), read());
            }
        });

        tools.add(new Base("screen_click") {
            @Override public Gate gate(JSONObject a, Turn t) {
                ScreenDigest.Element e = safeElement(a.optInt("id"));
                ScreenDigest d = last;
                if (e != null && d != null && d.app.endsWith(" " + TV_MANAGER) && SPEED_UP.matcher(e.label).find())
                    return Gate.deny(NOT_TV_MANAGER);
                String why = e == null ? null : Sensitive.why(e.label);
                return why == null ? Gate.act() : Gate.sensitive("点选「" + e.label + "」（" + why + "）");
            }
            @Override public String describe(JSONObject a, Turn t) {
                ScreenDigest.Element e = safeElement(a.optInt("id"));
                return e == null ? "点选 [" + a.optInt("id") + "]" : "点选「" + (e.label.isEmpty() ? e.role : e.label) + "」";
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                ScreenDigest.Element e = element(a.getInt("id"));
                if (e == null) return fail("rejected", "没有编号 " + a.getInt("id") + " 的元素，先调用 screen_read 看最新画面");
                String name = e.label.isEmpty() ? e.role : e.label;
                if (stale()) {
                    // Waiting for consent (or a slow round) leaves time for the screen to move on; XiaoAi itself
                    // presses OK on "确认". Only click what is still there.
                    ScreenDigest.Element same = relocate(e);
                    if (same == null) return withScreen(fail("stale", "画面在等待期间变了，「" + name + "」已经不在原处，没有点。请先看新的画面"), last);
                    e = same;
                }
                ScreenDigest from = last;
                long before = Eyes.changeCount();
                String how = "click";
                boolean sent = Eyes.click(e.node);
                ScreenDigest after = sent ? settle(before, 2500) : null;
                if (after == null || Eyes.changeCount() == before) {
                    // Many TV views only react to the remote: move focus there and press OK, else touch it.
                    before = Eyes.changeCount();
                    if (Eyes.focus(e.node)) { adb.keys(23); how = "focus+center"; }
                    else { adb.tap(e.node.centerX(), e.node.centerY()); how = "tap"; }
                    after = settle(before, 2500);
                }
                boolean changed = Eyes.get() == null || Eyes.changeCount() != before;
                return awaitPlayback(withScreen(ok(changed ? "observed" : "no_change", "已点选「" + name + "」" + (changed ? change(from, after) : "，但画面没有变化"))
                        .put("method", how), after), after, t);
            }
        });

        tools.add(new Base("screen_input") {
            @Override public Gate gate(JSONObject a, Turn t) {
                ScreenDigest.Element e = a.has("id") ? safeElement(a.optInt("id")) : null;
                return e != null && e.node.password ? Gate.deny("不替你输入密码，请自己在电视上输入") : Gate.act();
            }
            @Override public String describe(JSONObject a, Turn t) {
                return "输入“" + a.optString("text") + "”" + (a.optBoolean("submit") ? "并确认" : "");
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String text = a.getString("text");
                ScreenDigest.Element target = a.has("id") ? element(a.getInt("id")) : null;
                if (target == null) target = editable(last == null ? read() : last);
                if (target == null) target = editable(read());
                if (target == null) return fail("rejected", "屏幕上没有可以直接输入的文本框。有些电视应用用屏幕键盘，可以改用应用的搜索连接器或链接");
                if (!target.node.editable) return fail("rejected", "[" + target.id + "] 不是输入框");
                if (target.node.password) return fail("denied", "不替你输入密码");
                long before = Eyes.changeCount();
                if (!Eyes.setText(target.node, text)) return fail("error", "没能把文字填进输入框");
                if (a.optBoolean("submit")) adb.keys(66);
                return withScreen(ok("observed", "已填入“" + text + "”" + (a.optBoolean("submit") ? "并确认" : "")), settle(before, 2500));
            }
            private ScreenDigest.Element editable(ScreenDigest d) {
                ScreenDigest.Element f = d.focused();
                if (f != null && f.node.editable) return f;
                for (ScreenDigest.Element e : d.elements) if (e.node.editable) return e;
                return null;
            }
        });

        tools.add(new Base("press_keys") {
            @Override public Gate gate(JSONObject a, Turn t) {
                JSONArray keys = a.optJSONArray("keys");
                boolean select = false;
                for (int i = 0; keys != null && i < keys.length(); i++) select |= "center".equals(keys.optString(i)) || "enter".equals(keys.optString(i));
                if (!select || last == null) return Gate.act();
                ScreenDigest.Element f = last.focused();
                String why = f == null ? null : Sensitive.why(f.label);
                return why == null ? Gate.act() : Gate.sensitive("按确认键选择「" + f.label + "」（" + why + "）");
            }
            @Override public String describe(JSONObject a, Turn t) {
                StringBuilder s = new StringBuilder("按键");
                JSONArray keys = a.optJSONArray("keys");
                for (int i = 0; keys != null && i < keys.length(); i++) s.append(' ').append(KEY_NAMES.containsKey(keys.optString(i)) ? KEY_NAMES.get(keys.optString(i)) : keys.optString(i));
                int repeat = a.optInt("repeat", 1);
                return repeat > 1 ? s + " ×" + repeat : s.toString();
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                JSONArray keys = a.getJSONArray("keys");
                int repeat = Math.max(1, a.optInt("repeat", 1));
                if (repeat > 10) return fail("rejected", "repeat 最多 10");
                List<Integer> codes = new ArrayList<>();
                for (int r = 0; r < repeat; r++) for (int i = 0; i < keys.length(); i++) {
                    Integer code = KEYS.get(keys.getString(i));
                    if (code == null) return fail("rejected", "没有按键 " + keys.getString(i) + "，可用：" + KEYS.keySet());
                    codes.add(code);
                }
                if (codes.isEmpty() || codes.size() > 30) return fail("rejected", "一次最多按 30 个键");
                int[] c = new int[codes.size()];
                for (int i = 0; i < c.length; i++) c[i] = codes.get(i);
                boolean select = codes.contains(23) || codes.contains(66);
                if (select && stale() && last != null) {
                    // The OK press was approved for what had focus then; refuse if focus is now elsewhere.
                    ScreenDigest.Element was = last.focused();
                    ScreenDigest now = read();
                    ScreenDigest.Element is = now.focused();
                    if (was != null && (is == null || !sameText(is.label, was.label)))
                        return withScreen(fail("stale", "画面在等待期间变了，焦点已经不在「" + was.label + "」上，没有按确认键"), now);
                }
                ScreenDigest from = last;
                long before = Eyes.changeCount();
                adb.keys(c);
                ScreenDigest after = settle(before, 2000);
                JSONObject result = withScreen(ok("observed", describe(a, t) + change(from, after)), after);
                return select ? awaitPlayback(result, after, t) : result;
            }
        });

        tools.add(new Base("screen_scroll") {
            @Override public String describe(JSONObject a, Turn t) { return "向" + dir(a.optString("direction")) + "滚动"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String d = a.getString("direction");
                ScreenDigest.Element target = a.has("id") ? element(a.getInt("id")) : null;
                if (target == null) target = scrollTarget(last == null ? read() : last, "up".equals(d) || "down".equals(d));
                long before = Eyes.changeCount();
                boolean forward = "down".equals(d) || "right".equals(d);
                if (target == null || !Eyes.scroll(target.node, forward)) {
                    int key = KEYS.get(d);
                    adb.keys(key, key, key, key);
                }
                return withScreen(ok("observed", describe(a, t)), settle(before, 2000));
            }
            private String dir(String d) { return "up".equals(d) ? "上" : "down".equals(d) ? "下" : "left".equals(d) ? "左" : "右"; }
        });

        tools.add(new Base("wait_for") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                return a.has("text") ? "等待「" + a.optString("text") + "」出现" : a.has("app") ? "等待" + a.optString("app") + "打开" : "稍等一下";
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                long timeout = Math.max(500, Math.min(10000, a.optInt("timeout_ms", 4000)));
                String text = a.optString("text", ""), app = a.has("app") ? resolveApp(a.getString("app")) : null;
                if (a.has("app") && app == null) return fail("not_found", "没有找到应用“" + a.getString("app") + "”");
                long end = SystemClock.uptimeMillis() + timeout;
                ScreenDigest d;
                do {
                    if (t.cancelled()) throw new InterruptedException();
                    d = read();
                    boolean textOk = text.isEmpty() || d.contains(text);
                    boolean appOk = app == null || d.app.endsWith(" " + app);
                    if ((a.has("text") || a.has("app")) && textOk && appOk) return withScreen(ok("observed", "已出现"), d);
                    Thread.sleep(400);
                } while (SystemClock.uptimeMillis() < end);
                if (!a.has("text") && !a.has("app")) return withScreen(ok("observed", "已等待"), d);
                return withScreen(fail("timeout", "等了 " + timeout / 1000 + " 秒还没有出现"), d);
            }
        });

        tools.add(new Base("screen_look") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "看画面：" + a.optString("question"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                byte[] png = adb.call(LocalAdb::screenshotPng);
                Bitmap full = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (full == null) return fail("error", "截图无法解码");
                Bitmap small = Bitmap.createScaledBitmap(full, 1280, Math.max(1, full.getHeight() * 1280 / Math.max(1, full.getWidth())), true);
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 72, jpeg);
                JSONArray parts = new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", a.getString("question")))
                        .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject()
                                .put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP))));
                JSONArray messages = new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", "这是小米电视当前画面的截图。只根据图片如实回答问题，看不清就说看不清。用中文，不超过 150 字。"))
                        .put(new JSONObject().put("role", "user").put("content", parts));
                JSONObject reply = ModelRouter.open(context).next(messages, new JSONArray());
                String answer = Speech.visible(reply.opt("content"));
                if (answer.isEmpty()) return fail("error", "视觉模型没有回答");
                return ok("observed", answer).put("answer", answer);
            }
        });

        tools.add(new Base("list_apps") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "查看已安装的应用"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                JSONArray list = new JSONArray();
                for (Map.Entry<String, String> e : apps().entrySet()) list.put(e.getKey() + "（" + e.getValue() + "）");
                return ok("observed", "").put("apps", list);
            }
        });

        tools.add(new Base("open_app") {
            @Override public Gate gate(JSONObject a, Turn t) {
                // 「清理电视的内存」 went to 电视管家's 加速 (2026-11-07); 「打开电视管家」 still opens it
                String said = t.utterance() == null ? "" : t.utterance();
                return TV_MANAGER.equals(resolveApp(a.optString("app"))) && MEMORY_WORDS.matcher(said).find() ? Gate.deny(NOT_TV_MANAGER) : Gate.act();
            }
            @Override public String describe(JSONObject a, Turn t) {
                String pkg = resolveApp(a.optString("app"));
                return "打开" + (pkg == null ? a.optString("app") : label(pkg));
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String pkg = resolveApp(a.getString("app"));
                if (pkg == null) {
                    boolean bili = Arrays.asList(ALIASES[0]).contains(norm(a.getString("app")));
                    StoreCatalog.Entry e = bili ? null : catalog().find(a.getString("app"));
                    return fail("not_found", bili ? "电视上没有哔哩哔哩官方客户端（tv.danmaku.bili），可以调用 install_app 安装"
                            : e != null && !installed(e.pkg) ? "电视上还没装「" + e.name + "」（小米应用商店里有），可以调用 install_app 装好再打开"
                            : "没有找到“" + a.getString("app") + "”，可以先用 list_apps 看已安装的应用");
                }
                Intent i = launchIntent(pkg);
                if (i == null) return fail("unavailable", label(pkg) + "没有可以打开的入口");
                long before = Eyes.changeCount();
                start(i);
                boolean arrived = waitForPackage(pkg, 6000);
                ScreenDigest d = settle(before, 1500);
                return withScreen(arrived ? ok("observed", "已打开" + label(pkg))
                        : ok("requested", "已请求打开" + label(pkg) + "，前台还不是它，看一下屏幕上是什么"), d);
            }
        });

        tools.add(new Base("open_link") {
            @Override public Gate gate(JSONObject a, Turn t) {
                Uri u = Uri.parse(a.optString("uri"));
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                if (scheme.isEmpty() || BLOCKED_SCHEMES.contains(scheme)) return Gate.deny("不能打开这种链接：" + scheme);
                // links run without a plan now (explored deep links are one-step connectors): a payment-looking one asks first
                String lower = a.optString("uri").toLowerCase(Locale.ROOT);
                for (String w : new String[]{"pay", "recharge", "purchase", "buy", "order", "charge"})
                    if (lower.contains(w)) return Gate.sensitive("打开一个像是付款或下单的链接：" + a.optString("uri"));
                return Gate.act();
            }
            @Override public String describe(JSONObject a, Turn t) {
                String u = a.optString("uri");
                return "打开链接 " + (u.length() > 60 ? u.substring(0, 59) + "…" : u);
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("uri")));
                String target = null;
                for (ResolveInfo r : pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)) {
                    if (r.activityInfo == null || !r.activityInfo.exported || "android".equals(r.activityInfo.packageName)) continue;
                    // the first that takes it, but B站 for a bilibili.com address a browser would also take
                    if (target == null || BILI.equals(r.activityInfo.packageName)) {
                        target = r.activityInfo.packageName;
                        i.setComponent(new ComponentName(target, r.activityInfo.name));
                    }
                    if (BILI.equals(target)) break;
                }
                if (target == null) return fail("unavailable", "电视上没有应用能打开这个链接");
                String u = a.getString("uri");
                // a video, an episode, a season (Douban's B站 link for a film): the player page, then playback
                if (BILI.equals(target) && (u.startsWith("bilibili://video/") || BILI_EPISODE.matcher(u).find()
                        || u.startsWith("bilibili://pgc/season/") || u.startsWith("bilibili://bangumi/season/")))
                    return openBiliVideo(i, t);
                long before = Eyes.changeCount();
                start(i);
                boolean arrived = waitForPackage(target, BILI.equals(target) ? 20000 : 6000);
                // B站's own login modal lands on top of the page it just opened: close it before the screen is read
                boolean dismissed = BILI.equals(target) && dismissBiliLogin();
                return withScreen(biliLoginNote(arrived ? ok("observed", "已用" + label(target) + "打开")
                        : ok("requested", "已请求" + label(target) + "打开链接，前台还不是它"), dismissed), settle(before, 1500));
            }
        });

        tools.add(new Base("open_settings") {
            @Override public String describe(JSONObject a, Turn t) { return "打开设置：" + SETTINGS_NAMES.getOrDefault(a.optString("page"), a.optString("page")); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String[] target = SETTINGS_PAGES.get(a.getString("page"));
                if (target == null) return fail("rejected", "没有这个设置页：" + a.getString("page"));
                Intent i = new Intent(target[0]);
                ResolveInfo r = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
                if (r == null || r.activityInfo == null) return fail("unavailable", "这台电视没有这个设置页");
                i.setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
                ScreenDigest from = last;
                long before = Eyes.changeCount();
                start(i);
                boolean arrived = waitForPackage(target[1], 5000);
                ScreenDigest d = settle(before, 1500);
                String name = SETTINGS_NAMES.getOrDefault(a.getString("page"), a.getString("page"));
                return withScreen(arrived ? ok("observed", "已打开设置：" + name + change(from, d))
                        : ok("requested", "已请求打开设置：" + name + "，前台还不是设置"), d);
            }
        });

        tools.add(new Base("install_app") {
            /** A vetted app (OfficialApps) comes from its own maker's channel, downloaded, signature-checked and
             * installed by OfficialAppInstaller — B站 (the phone build the user uses) from bilibili.com, the browser
             * from TV Bro's own GitHub releases; any other app only from the Xiaomi TV app store's own catalog
             * (store_apps.json), never by a guessed package. An app the user named in this very request is installed
             * at once — the request is the go-ahead — any other one is confirmed first (2026-11-05: 「缺少的依赖也不
             * 自己安装」).
             *
             * An app in neither list (2026-11-07, 「你点头就装」) may come from the maker's own site, in two calls:
             * {app, url} downloads into the cache and reads the package (gate: act, nothing is installed), then
             * {token} installs exactly that file after the user has heard its source, signer, package and size
             * (gate: sensitive — the sentence ApkConsent builds). ConsentInstaller refuses a package that is not
             * readable, is signed by a debug or test key, carries another signer than the copy already on the TV, or
             * whose native code this TV cannot run. */
            @Override public Gate gate(JSONObject a, Turn t) {
                // 「你点头就装」(2026-11-07): the second call — install what the first one downloaded and read. The
                // sentence is the only confirmation there is (pm install never shows Android's own one), so it carries
                // the source, the signer, the package and the size read off the file itself.
                String token = a.optString("token").trim();
                if (!token.isEmpty()) {
                    JSONObject facts = Legacy.consentFacts(token);
                    if (facts == null) return Gate.deny("这个安装确认不存在或已经过期：重新下载，并把来源、开发者签名、包名和大小告诉用户");
                    return Gate.sensitive(ApkConsent.sentence(facts));
                }
                // The first call only downloads into the cache and reads the package: nothing is installed yet.
                String url = a.optString("url").trim();
                if (!url.isEmpty()) {
                    if (!ApkConsent.urlOk(url)) return Gate.deny("安装包地址要填开发者官网上的 https:// 直链");
                    String host = ApkConsent.host(url);
                    if (ApkConsent.blocked(host)) return Gate.deny(host + " 是下载站或免费托管，不是开发者的官网：换一个开发者自己的站点，"
                            + "找不到就如实告诉用户装不了");
                    return Gate.act();
                }
                String app = target(a);
                OfficialApps.App official = OfficialApps.find(app);
                if (official != null) {
                    if (Legacy.installedOfficial(context, official.key)) return Gate.act();
                    return OfficialApps.named(t.utterance(), official) ? Gate.act()
                            : Gate.sensitive("从" + official.source + "下载并安装「" + official.name + "」（约 " + official.mb + " MB）");
                }
                StoreCatalog.Entry e = catalog().find(app);
                if (e == null) return Gate.deny("小米电视应用商店的目录里没有「" + app + "」：换成它在商店里的名字再试；"
                        + "它有自己的官网时，也可以自己找官网上的 Android 安装包直链（https://…），"
                        + "用 install_app {app, url} 下载，用户听过来源和签名确认之后再装；找不到官网就如实告诉用户装不了");
                if (installed(e.pkg)) return Gate.act();
                return StoreCatalog.named(t.utterance(), e) ? Gate.act() : Gate.sensitive("从小米电视应用商店安装「" + e.name + "」");
            }
            @Override public String describe(JSONObject a, Turn t) {
                String token = a.optString("token").trim();
                if (!token.isEmpty()) {
                    JSONObject facts = Legacy.consentFacts(token);
                    return facts == null ? "安装已下载的应用" : "安装" + facts.optString("label");
                }
                String url = a.optString("url").trim();
                if (!url.isEmpty()) return "从 " + ApkConsent.host(url) + " 下载并核对" + target(a);
                String app = target(a);
                OfficialApps.App official = OfficialApps.find(app);
                if (official != null) return BILI.equals(official.pkg) ? Legacy.INSTALL_PREVIEW
                        : "从" + official.source + "安装" + official.name;
                StoreCatalog.Entry e = catalog().find(app);
                return "从小米应用商店安装" + (e == null ? app : e.name);
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String token = a.optString("token").trim();
                if (!token.isEmpty()) return Legacy.installConsent(context, token, t::cancelled, t::progress);
                String url = a.optString("url").trim();
                if (!url.isEmpty()) {
                    JSONObject prepared = Legacy.prepareConsent(context, url, a.optString("page"), t::cancelled, t::progress);
                    // the model has to say these facts and wait for 「好的」: say so on the panel too
                    if ("needs_consent".equals(prepared.optString("status"))) t.progress("下载完成，等你确认");
                    return prepared;
                }
                String app = target(a);
                OfficialApps.App official = OfficialApps.find(app);
                if (official != null) {
                    if (Legacy.installedOfficial(context, official.key)) {
                        return ok("observed", official.name + "已经装好了，可以直接说“打开" + official.name + "”")
                                .put("package", official.pkg);
                    }
                    JSONObject done = Legacy.installOfficial(context, official.key, t::cancelled, t::progress);
                    // 「那网页端你可以自己做一个映射呀」: say how to use the browser now that it is there.
                    if (done.optBoolean("ok") && OfficialApps.TVBRO == official) {
                        done.put("note", "浏览器装好了：要打开网页就用 open_link 打开网址（https://…），会在 TV Bro 里打开");
                    }
                    return done;
                }
                StoreCatalog.Entry e = catalog().find(app);
                if (e == null) return fail("not_found", "小米电视应用商店里没有「" + app + "」（商店一共只有一百多个电视应用）；"
                        + "它有自己的官网时，可以找官网上的 Android 安装包直链（https://…），用 install_app {app, url} 下载，"
                        + "用户听过来源网站、开发者签名、包名和大小、说「好的」之后再装；找不到官网就告诉用户这台电视装不了，"
                        + "能用别的办法做到的（查信息、读 X 上的帖子）就换个办法");
                if (installed(e.pkg)) return ok("observed", e.name + "已经装好了，在首页的「全部」里，也可以直接说“打开" + e.name + "”")
                        .put("package", e.pkg);
                // The store's own detail page; auto_start presses its 安装 button, and the store downloads, checks and
                // installs the package itself (appstore/API.md 6.1). Then wait for the package to appear.
                start(new Intent(Intent.ACTION_VIEW, Uri.parse("market://appstore.mitv.xiaomi.com/details?package=" + e.pkg))
                        .putExtra("auto_start", true).putExtra("from", "xingyi"));
                long began = SystemClock.uptimeMillis(), end = began + 240_000, said = began;
                t.progress("正在从小米应用商店安装" + e.name);
                while (SystemClock.uptimeMillis() < end) {
                    if (t.cancelled()) throw new InterruptedException();
                    Thread.sleep(1000);
                    if (installed(e.pkg)) {
                        // 2026-11-06: 「安装的应用也不知道在哪找」 — say where it is now
                        return ok("installed", "已从小米应用商店装好" + e.name + "：在首页「全部」的最前面（标着“新”），"
                                + "也可以直接说“打开" + e.name + "”").put("package", e.pkg);
                    }
                    if (SystemClock.uptimeMillis() - said >= 15_000) {
                        said = SystemClock.uptimeMillis();
                        t.progress("正在从小米应用商店安装" + e.name + "（已等 " + (said - began) / 1000 + " 秒）");
                    }
                }
                return withScreen(fail("unconfirmed", "商店的「" + e.name + "」页面打开了，4 分钟还没装好（网络慢，或者商店在等确认），看一下屏幕"),
                        read());
            }
            private String target(JSONObject a) {
                String app = a.optString("app").trim();
                return app.isEmpty() ? a.optString("package").trim() : app;
            }
        });

        tools.add(new Base("tv_status") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "读取电视状态"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String fg = foreground();
                JSONObject r = ok("observed", "").put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                        .put("volume_max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                        .put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC))
                        .put("foreground", fg.isEmpty() ? "未知" : label(fg) + "（" + fg + "）")
                        .put("now_playing", session(nowPlaying()))
                        .put("bluetooth", bluetooth())
                        .put("wifi", wifi())
                        // 「电视怎么这么卡」: the facts, not a tour of the settings pages (2026-11-06 overnight pass)
                        .put("memory", memory())
                        .put("storage", storage())
                        .put("since_restart", DeviceFacts.uptime(SystemClock.elapsedRealtime()) + "（待机不算重启）")
                        // the model that answered this task's last round (the route that answered, not a constant)
                        .put("model", ModelRouter.activeModelName());
                // what the memory guard stopped lately: 「B站怎么重新加载了」
                List<String> guard = MemoryGuard.get(context).recent(3_600_000L);
                if (!guard.isEmpty()) r.put("memory_guard", new JSONArray(guard));
                // 「为什么内存这么小」: the user's apps holding the most (B站's phone client: 1.3 GB in four processes);
                // the two big ones measured (PSS, ~1 s each), the rest named — ps's figure counts shared memory in full
                try {
                    Map<String, Long> rss = MemoryCare.byPackage(adb.call(LocalAdb::processes));
                    JSONArray hogs = new JSONArray();
                    int measured = 0;
                    for (String p : MemoryCare.hogs(rss, MemoryCare.packages(adb.call(LocalAdb::userPackages)), 3)) {
                        long pss = measured < 2 && rss.get(p) >= 150 * 1024 ? MemoryCare.pss(adb.call(l -> l.meminfo(p))) : 0;
                        if (pss > 0) measured++;
                        hogs.put(label(p) + (pss > 0 ? " " + DeviceFacts.size(pss * 1024) : ""));
                    }
                    if (hogs.length() > 0) r.put("memory_hogs", hogs);
                } catch (IOException ignored) {
                    // the rest of the status stands without it
                }
                return r;
            }
            private String memory() {
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    return DeviceFacts.memory(mi.availMem, mi.totalMem, mi.lowMemory);
                } catch (RuntimeException e) { return "读不到"; }
            }
            private String storage() {
                try {
                    android.os.StatFs s = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
                    return DeviceFacts.storage(s.getAvailableBytes(), s.getTotalBytes());
                } catch (RuntimeException e) { return "读不到"; }
            }
            private String bluetooth() {
                try {
                    android.bluetooth.BluetoothAdapter b = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                    return b == null ? "这台电视没有蓝牙" : b.isEnabled() ? "已打开" : "已关闭";
                } catch (RuntimeException e) { return "读不到"; }
            }
            private String wifi() {
                try {
                    android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                    if (w == null || !w.isWifiEnabled()) return "WiFi 已关闭";
                    String ssid = w.getConnectionInfo() == null ? "" : w.getConnectionInfo().getSSID();
                    ssid = ssid == null ? "" : ssid.replace("\"", "");
                    int ip = w.getConnectionInfo() == null ? 0 : w.getConnectionInfo().getIpAddress();
                    String addr = ip == 0 ? "" : "，IP " + (ip & 255) + "." + (ip >> 8 & 255) + "." + (ip >> 16 & 255) + "." + (ip >> 24 & 255);
                    android.net.wifi.WifiInfo info = w.getConnectionInfo();
                    String signal = info == null ? "" : DeviceFacts.wifiSignal(info.getRssi(), info.getLinkSpeed());
                    return (ssid.isEmpty() || "<unknown ssid>".equals(ssid) ? "WiFi 已打开，名称读不到" : "已连接 " + ssid) + addr
                            + (signal.isEmpty() ? "" : "，" + signal);
                } catch (RuntimeException e) { return "读不到"; }
            }
        });

        // 清理内存 (2026-11-07): 「清理电视的内存」 had 星仪 press 电视管家's 加速, which killed 星仪's own process too (the
        // task resumed after the restart) and left less available than before, 478 → 414 MB. This stops only apps the
        // user installed, only from the background, and reports the system's own figures before and after.
        tools.add(new Base("free_memory") {
            @Override public String describe(JSONObject a, Turn t) { return "清理内存"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                long before = MemoryGuard.available(context), wall = System.currentTimeMillis();
                List<Usage.Event> sw = MemoryGuard.switches(context, wall);
                Map<String, Long> rss = MemoryCare.byPackage(adb.call(LocalAdb::processes));
                Set<String> users = MemoryCare.packages(adb.call(LocalAdb::userPackages));
                List<MediaSessions.Session> sessions = MediaSessions.parse(adb.call(LocalAdb::mediaSessions));
                Set<String> listening = MemoryCare.listening(sessions, SystemClock.elapsedRealtime());
                Set<String> keep = MemoryGuard.front(context, sw);
                keep.addAll(listening);
                List<String> stop = MemoryCare.toStop(rss, users, keep);
                if (t.cancelled()) throw new InterruptedException();
                // the app in front, when it is the big one: measured before anything changes (PSS, ~1 s)
                String real = MemoryCare.realFront(sw);
                long frontKb = users.contains(real) && !listening.contains(real) && rss.containsKey(real) && rss.get(real) >= 300 * 1024
                        ? MemoryCare.pss(adb.call(l -> l.meminfo(real))) : 0;
                if (!stop.isEmpty()) {
                    adb.call(l -> { l.stopApps(stop); return null; });
                    // the stopped processes are gone at once; the system's figure settles over a couple of seconds
                    Thread.sleep(2500);
                }
                long after = MemoryGuard.available(context);
                List<String> stopped = new ArrayList<>(), kept = new ArrayList<>();
                for (String p : stop) stopped.add(label(p));
                for (MediaSessions.Session s : sessions)
                    if (listening.contains(s.pkg) && users.contains(s.pkg) && rss.containsKey(s.pkg) && !kept.contains(label(s.pkg)))
                        kept.add(("paused".equals(s.state) ? "暂停着的" : "正在放的") + label(s.pkg));
                return ok("observed", MemoryCare.say(before, after, stopped, kept, users.contains(real) ? label(real) : "", frontKb))
                        .put("available_before_mb", before >> 20).put("available_after_mb", after >> 20).put("stopped", new JSONArray(stopped));
            }
        });

        tools.add(new Base("set_volume") {
            @Override public String describe(JSONObject a, Turn t) { return "音量调到 " + a.optInt("level"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                int level = a.getInt("level");
                if (level < 0 || level > 100) return fail("rejected", "level 应在 0 到 100 之间");
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), want = Math.round(max * level / 100f);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0);
                int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                return (now == want ? ok("observed", "音量现在是 " + now + "/" + max) : fail("unconfirmed", "音量读回 " + now + "/" + max))
                        .put("volume", now).put("volume_max", max);
            }
        });

        tools.add(new Base("adjust_volume") {
            @Override public Gate gate(JSONObject a, Turn t) {
                return xiaoaiDid("Speaker.") ? Gate.deny("小爱已经为这句话调过音量了，再调一次会翻倍；用 tv_status 看现在的音量，告诉用户结果。") : Gate.act();
            }
            @Override public String describe(JSONObject a, Turn t) {
                String d = a.optString("direction");
                return "up".equals(d) ? "调大音量" : "down".equals(d) ? "调小音量" : "mute".equals(d) ? "静音" : "取消静音";
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String d = a.getString("direction");
                int steps = Math.max(1, Math.min(10, a.optInt("steps", 1)));
                int dir = "up".equals(d) ? AudioManager.ADJUST_RAISE : "down".equals(d) ? AudioManager.ADJUST_LOWER
                        : "mute".equals(d) ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
                for (int i = 0; i < (dir == AudioManager.ADJUST_RAISE || dir == AudioManager.ADJUST_LOWER ? steps : 1); i++)
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
                int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC), max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
                return ok("observed", "音量 " + now + "/" + max + (muted ? "，已静音" : "")).put("volume", now).put("muted", muted);
            }
        });

        tools.add(new Base("switch_input") {
            /** 用户没提信号源或接着它的设备就别切：这台电视的 HDMI 口上不一定接着东西，切过去是一片无信号
             * （2026-11-11 校准：「我想看CCTV5」被当成了「切到 HDMI1」）。 */
            @Override public Gate gate(JSONObject a, Turn t) {
                return QuickCommand.namesInput(t.utterance()) ? Gate.act() : Gate.deny(NO_INPUT_NAMED);
            }
            @Override public String describe(JSONObject a, Turn t) { return "切换到 " + a.optString("name"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                int n = Integer.parseInt(a.getString("name").substring(4));
                start(new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                        .setComponent(new ComponentName("com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.tvplayer.ExternalSourceActivity"))
                        .putExtra("input", 22 + n));
                // the switch is the activity in front afterwards (2026-11-11 on the TV): say what was done, not what was asked
                long end = SystemClock.uptimeMillis() + 3000;
                while (SystemClock.uptimeMillis() < end) {
                    if (t.cancelled()) throw new InterruptedException();
                    String focused;
                    try { focused = adb.call(LocalAdb::focusedApp); }
                    catch (IOException e) { focused = ""; }
                    if ("com.xiaomi.mitv.tvplayer".equals(FrontApp.pkg(focused))
                            && FrontApp.cls(focused).endsWith("ExternalSourceActivity"))
                        return ok("observed", "已切到 HDMI " + n + "（这个接口上没接设备的话，画面是无信号）");
                    Thread.sleep(300);
                }
                return ok("requested", "已请求切换到 HDMI " + n + "，没有读回外部信号");
            }
        });

        tools.add(new Base("wake_screen") {
            @Override public String describe(JSONObject a, Turn t) { return "点亮屏幕"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm.isInteractive()) return ok("observed", "屏幕本来就开着");
                // The power key only while asleep: pressed on a lit screen it turns the TV off (2026-11-04).
                adb.call(l -> { l.connect(); l.keyEvents(new int[]{KeyEvent.KEYCODE_POWER}); return null; });
                long end = SystemClock.uptimeMillis() + 4000;
                while (SystemClock.uptimeMillis() < end && !pm.isInteractive()) {
                    if (t.cancelled()) throw new InterruptedException();
                    Thread.sleep(200);
                }
                return pm.isInteractive() ? ok("observed", "屏幕已点亮") : fail("unconfirmed", "按了电源键，屏幕还没亮");
            }
        });

        // 2026-11-06 overnight pass: 「屏幕可以熄灭」 went to XiaoAi through xiaoai_ask (3.8 s); 关电视 had no tool of its own.
        tools.add(new Base("screen_off") {
            @Override public String describe(JSONObject a, Turn t) { return "关掉屏幕，电视待机"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                // XiaoAi acts on 关电视 itself; its instructions for this sentence show whether it did (the bridge waits
                // for them). Off because of that is this request done, not 「本来就是关着的」 (2026-11-09 review).
                boolean acted = false;
                if ("xiaoai".equals(t.source())) for (String n : xiaoaiDid) if (!n.startsWith("Template.")) acted = true;
                if (!pm.isInteractive()) return ok("observed", acted ? QuickCommand.SCREEN_OFF : QuickCommand.SCREEN_OFF_ALREADY);
                // give XiaoAi's own power-off a moment to land before pressing anything — longer when it did something
                if ("xiaoai".equals(t.source())) {
                    long end = SystemClock.uptimeMillis() + (acted ? 3500 : 1500);
                    while (SystemClock.uptimeMillis() < end && pm.isInteractive()) {
                        if (t.cancelled()) throw new InterruptedException();
                        Thread.sleep(150);
                    }
                    if (t.cancelled()) throw new InterruptedException();
                    if (!pm.isInteractive()) return ok("observed", QuickCommand.SCREEN_OFF);
                }
                // LocalAdb.sleep reads the power state twice and sends at most one power key, never to a sleeping TV
                // (this firmware ignores KEYCODE_SLEEP); the sleep timer uses the same.
                adb.call(l -> { l.sleep(); return null; });
                return ok("observed", QuickCommand.SCREEN_OFF);
            }
        });

        tools.add(new Base("sleep_timer") {
            @Override public Gate gate(JSONObject a, Turn t) { return a.has("minutes") ? Gate.act() : Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                if (!a.has("minutes")) return "查看休眠定时";
                return a.optInt("minutes") == 0 ? "取消休眠定时" : a.optInt("minutes") + " 分钟后休眠";
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                if (!a.has("minutes")) return ok("observed", SleepTimer.currentStatus(context));
                int m = a.getInt("minutes");
                if (m == 0) { SleepTimer.cancel(context); return ok("observed", "休眠定时已取消"); }
                if (m < 1 || m > 120) return fail("rejected", "minutes 应在 1 到 120 之间");
                adb.call(l -> { l.connect(); return null; });
                SleepTimer.schedule(context, m);
                return ok("observed", "已设置 " + m + " 分钟后休眠");
            }
        });

        tools.add(new Base("web_search") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "联网搜索：" + a.optString("query"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String query = a.getString("query");
                String refusal = searches.check(t.round());
                if (refusal == null) refusal = cap("web-search", WEB_PER_DAY, "联网").take(today());
                if (refusal != null) return fail("rejected", refusal);
                GatewayConfig route;
                try { route = GatewayConfig.loadRoutes(context).gateway; }
                catch (IOException e) { route = null; }
                if (route == null) return fail("unavailable", "没有配置联网搜索要用的网关线路");
                t.say("我查一下。");
                WebSearch.Result r;
                try { r = new WebSearch(route.baseUrl, route.apiKey, null, WebSearch.https()).at(place()).search(query, t.utterance(), now(), 25000); }
                catch (IllegalArgumentException e) { return fail("rejected", e.getMessage()); }
                catch (IOException e) { return fail("error", e.getMessage()); }
                searches.result(r.answer, r.searched);
                return ok("observed", r.answer).put("answer", r.answer).put("searched", r.searched)
                        .put("seconds", Math.round(r.millis / 100.0) / 10.0);
            }
            /** The TV's own clock, in the wording the search model is told to reason with. */
            private String now() {
                Calendar c = Calendar.getInstance();
                return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(c.getTime())
                        + " 星期" + "日一二三四五六".charAt(c.get(Calendar.DAY_OF_WEEK) - 1);
            }
        });

        // find_installer (2026-11-07, 「你点头就装」): the model has to know a url before install_app {app, url, page}
        // can fetch an app the store and OfficialApps do not carry, and web_search strips every link from its answer
        // (XiaoAi would read an address out). This one keeps them — its result is data for the model, never spoken,
        // so it carries no answer field. Only the maker's own site is accepted; the search model does answer with a
        // download site now and then, and Installers drops those.
        tools.add(new Base("find_installer") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "找「" + a.optString("app") + "」的官方安装包"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String app = a.getString("app").trim();
                JSONObject remembered = installers.get(app, System.currentTimeMillis());
                if (remembered != null) return remembered.put("cached", true);
                JSONObject found = search(app, t);
                // only what the search itself concluded is remembered: a missing route or a network error is worth
                // trying again, 「the maker has no direct link」 is not going to change in the next ten minutes
                if (found.optBoolean("ok") || "not_found".equals(found.optString("status")))
                    installers.put(app, found, System.currentTimeMillis());
                return found;
            }
            /** The search itself: once per app per task, everything below costs a request and ~10 s. */
            private JSONObject search(String app, Turn t) throws Exception {
                GatewayConfig route;
                try { route = GatewayConfig.loadRoutes(context).gateway; }
                catch (IOException e) { route = null; }
                if (route == null) return fail("unavailable", "没有配置联网搜索要用的网关线路");
                t.say("我找一下官网上的安装包。");
                Installers.Found f;
                try {
                    f = new WebSearch(route.baseUrl, route.apiKey, null, WebSearch.https()).findInstaller(app, now(), 40000,
                            new Installers.Cap() {
                                @Override public String take() { return cap("web-search", WEB_PER_DAY, "联网").take(today()); }
                            });
                } catch (IllegalArgumentException e) { return fail("rejected", e.getMessage()); }
                catch (IOException e) { return fail("error", "找安装包时联网失败：" + e.getMessage()); }
                // the cap is taken before anything is sent, so a capped day costs nothing
                if (f.refusal != null) return fail("rejected", f.refusal);
                JSONObject facts = f.facts;
                if (facts == null) return fail("error", "找安装包时没看懂搜到的结果：换个叫法再试一次，还不行就如实告诉用户装不了");
                String developer = facts.optString("developer"), page = facts.optString("page"), note = facts.optString("note");
                if (!f.found()) {
                    JSONObject missed = fail("not_found", "官网没有公开的安装包直链（比如只能扫码下载）：不要去下载站找；有官方网页版的就用浏览器打开");
                    if (!developer.isEmpty()) missed.put("developer", developer);
                    if (!page.isEmpty()) missed.put("page", page);
                    if (!note.isEmpty()) missed.put("note", note);
                    return missed.put("found", false);
                }
                return ok("observed", "找到了「" + app + "」的官方安装包直链，可以接着用 install_app {app, url, page} 下载")
                        .put("app", facts.optString("app").isEmpty() ? app : facts.optString("app"))
                        .put("developer", developer).put("page", page).put("url", facts.optString("url"))
                        .put("package", facts.optString("package")).put("note", note).put("found", true)
                        .put("seconds", Math.round(f.millis / 100.0) / 10.0);
            }
            /** The TV's own clock, in the wording the search model is told to reason with. */
            private String now() {
                Calendar c = Calendar.getInstance();
                return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(c.getTime())
                        + " 星期" + "日一二三四五六".charAt(c.get(Calendar.DAY_OF_WEEK) - 1);
            }
        });

        tools.add(new Base("weather") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String p = a.optString("place").trim(), d = a.optString("day").trim();
                return "查天气：" + (p.isEmpty() ? "这里" : p) + (d.isEmpty() ? "" : d) + (a.optBoolean("air") ? "（含空气质量）" : "");
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String text;
                JSONObject card = null;
                try {
                    String p = a.optString("place").trim();
                    Weather.Where h = here();
                    // a place chosen by the user without coordinates: by its name
                    if (h == null && p.isEmpty()) p = Place.city(
                            android.provider.Settings.System.getString(context.getContentResolver(), "mitv_user_location"),
                            android.provider.Settings.System.getString(context.getContentResolver(), "mitv_location"));
                    if (p == null) p = "";
                    // the answer and the panel's card come from the one forecast this call fetched
                    Weather.Answer w = Weather.answer(Weather.cached(Weather.https()), h, p, a.optString("day"), a.optBoolean("air"),
                            a.optBoolean("rain"), a.optBoolean("sun"), a.optBoolean("dress"));
                    text = w.text;
                    // sunrise has no temperatures to draw; the forecast, the rain and the dress answers all do
                    if (!a.optBoolean("sun")) {
                        try { card = Weather.card(w.place, w.forecast, a.optString("day"), System.currentTimeMillis()); }
                        catch (Exception noCard) { } // a day the card cannot be built for is still answered
                    }
                } catch (IllegalArgumentException e) {
                    return fail("rejected", e.getMessage());
                } catch (IOException e) {
                    return fail("error", "天气服务没连上：可以用 web_search 查");
                }
                JSONObject r = ok("observed", text).put("answer", text).put("source", "Open-Meteo 气象模型预报");
                if (Cards.valid(card)) r.put("card", card);
                return r;
            }
        });

        // stock_quote / exchange_rate / hot_list (2026-11-06, the user: 「继续增加工具调用」): public data feeds, about a
        // second, instead of a 7-9 s web search whose numbers changed from one search to the next
        tools.add(new Base("stock_quote") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String q = a.optString("query").trim();
                return "查行情：" + (q.isEmpty() ? "大盘" : q);
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String query = a.optString("query").trim();
                List<String> heard = new ArrayList<>();
                JSONArray heardAs = new JSONArray();
                // the whole sentence first, then its names; a name no search finds is left out, not failed (Quotes.resolve)
                Quotes.Found found;
                try {
                    found = Quotes.resolve(query, NAME_FEED);
                } catch (IOException e) {
                    return fail("error", "行情服务没连上：可以用 web_search 查");
                }
                if (found.codes.isEmpty() && found.notFound.isEmpty()) {
                    if (found.notListed.isEmpty()) return fail("not_found", "没说查哪只股票或指数：说名字或代码，比如「茅台股价多少」");
                    // every name asked about is a company that is not listed: that is the answer, and there is nothing to
                    // look up (an empty quote request would come back as a 「没找到」)
                    String said = String.join("；", found.notListed);
                    return ok("observed", said).put("answer", said).put("final", true).put("source", "这些公司没有上市，也就没有股价");
                }
                // a name the search did not find is asked again by how it sounds, and only a name that really sounds the
                // same is taken (the user said 智谱, 2026-11-07)
                for (String part : found.notFound) {
                    String spell = Pinyin.hasChinese(part) ? Pinyin.spell(part) : "";
                    Quotes.Hit hit = null;
                    for (int s = 0; hit == null && s < 2; s++) {
                        String spelling = s == 0 ? spell : Pinyin.initials(part);
                        if (spelling.isEmpty() || (s == 1 && spelling.equals(spell))) continue;
                        hit = Quotes.bySound(NAME_FEED.get(Quotes.SEARCH + java.net.URLEncoder.encode(spelling, "UTF-8"), "UTF-8", 6000), part);
                    }
                    if (hit == null) continue;
                    found.codes.add(hit.code);
                    heard.add("「" + part + "」按读音查的是" + hit.name);
                    heardAs.put(new JSONObject().put("said", part).put("found", hit.name).put("code", hit.code));
                    found.foundBySound.add(part);
                }
                List<String> missing = new ArrayList<>(found.notFound);
                missing.removeAll(found.foundBySound);
                if (found.codes.isEmpty()) {
                    return fail("not_found", "没找到「" + String.join("、", missing) + "」这只股票或指数"
                            + "：换成公司全名或股票代码再试；还不行就用 web_search");
                }
                List<String> codes = new ArrayList<>(found.codes);
                try {
                    List<Quotes.Quote> quotes = Quotes.parse(QUOTE_FEED.get(Quotes.QT + String.join(",", codes), "GBK", 6000));
                    if (quotes.isEmpty()) return fail("not_found", "行情服务没有这些代码的数据（" + codes + "）：可以用 web_search");
                    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
                    String answer = Quotes.speak(quotes, now);
                    if (!heard.isEmpty()) answer = String.join("；", heard) + "：" + answer;
                    if (!found.notListed.isEmpty()) answer = String.join("；", found.notListed) + "；" + answer;
                    // a name that was not found is said before the ones that were, so the user hears what is missing
                    if (!missing.isEmpty()) answer = "没找到「" + String.join("、", missing) + "」这只股票或指数；" + answer;
                    // names past the sixth are said, not dropped: no 「」 around them, which Heard would take for a name
                    if (!found.skipped.isEmpty())
                        answer += "；一次最多查六个，" + String.join("、", found.skipped) + " 这次没查";
                    JSONArray rows = new JSONArray();
                    for (Quotes.Quote q : quotes) rows.put(q.toJson());
                    JSONObject r = ok("observed", answer).put("answer", answer).put("quotes", rows).put("source", "腾讯行情（只是数字，不是投资建议）");
                    JSONObject card = Cards.stocks(quotes, now);
                    if (Cards.valid(card)) r.put("card", card);
                    if (heardAs.length() > 0) r.put("heard_as", heardAs);
                    if (!missing.isEmpty()) r.put("not_found", new JSONArray(missing));
                    // a name left out is not the whole answer: with one, the model gets the round to say what to do with it
                    boolean whole = found.notListed.isEmpty() && missing.isEmpty();
                    if (whole) r.put("final", true); // 「华为和小米的股价」: the sentence and the quotes are the answer
                    return r;
                } catch (IOException e) {
                    return fail("error", "行情服务没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("exchange_rate") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String f = a.optString("from").trim(), to = a.optString("to").trim();
                return "查汇率：" + (f.isEmpty() ? "美元" : f) + "→" + (to.isEmpty() ? "人民币" : to);
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String fromName = a.optString("from").trim(), toName = a.optString("to").trim();
                String from = Rates.code(fromName.isEmpty() ? "美元" : fromName), to = Rates.code(toName.isEmpty() ? "人民币" : toName);
                if (from == null) return fail("rejected", "不认识「" + fromName + "」这种货币：用中文名（美元、欧元、日元、港币…）或三个字母的代码（USD）");
                if (to == null) return fail("rejected", "不认识「" + toName + "」这种货币：用中文名或三个字母的代码");
                double amount = a.optDouble("amount", 1);
                if (!(amount > 0) || amount > 1e12) amount = 1;
                try {
                    Rates.Parity parity = null;
                    try { parity = Rates.parity(RATE_FEED.get(Rates.PARITY, "UTF-8", 6000)); } catch (IOException | JSONException ignored) { }
                    Map<String, Double> market = null;
                    if (Rates.needsMarket(from, to, parity)) market = Rates.market(RATE_FEED.get(Rates.MARKET, "UTF-8", 8000));
                    String answer = Rates.answer(from, to, amount, parity, market);
                    if (answer == null) return fail("not_found", "查不到 " + from + " 和 " + to + " 之间的汇率：可以用 web_search");
                    boolean official = !Rates.needsMarket(from, to, parity);
                    return ok("observed", answer).put("answer", answer).put("source", official
                            ? "中国外汇交易中心人民币汇率中间价（银行实际买卖价会略有不同）" : "open.er-api.com 国际市场参考价");
                } catch (IOException | JSONException e) {
                    return fail("error", "汇率服务没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("earthquake") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return a.optBoolean("nearby") ? "查附近有没有地震" : "查地震速报"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                Weather.Where h = here();
                double lat = h == null ? Double.NaN : h.lat, lon = h == null ? Double.NaN : h.lon;
                long now = System.currentTimeMillis();
                try {
                    List<Quake.Event> events = Quake.events(QUAKE_FEED.get(Quake.CENC, "UTF-8", 8000));
                    String where = a.optString("where").trim();
                    String answer = a.optBoolean("nearby") && where.isEmpty() ? Quake.nearby(events, now, lat, lon, Math.max(1, Math.min(24, a.optInt("hours", 2))))
                            : Quake.latest(events, now, lat, lon, where, Math.max(1, Math.min(5, a.optInt("count", 3))));
                    return ok("observed", answer).put("answer", answer).put("source", "中国地震台网速报");
                } catch (IOException | JSONException e) {
                    return fail("error", "地震速报没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("fuel_price") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "查油价：" + a.optString("province", "这里"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String said = a.optString("province").trim();
                String province = Prices.province(said.isEmpty() ? String.valueOf(place()) : said);
                if (province == null) return fail("rejected", said.isEmpty() ? "不知道电视在哪个省：province 写省份或直辖市" : "「" + said + "」不是省份：province 写省份或直辖市（上海、广东、浙江…）");
                try {
                    String answer = Prices.speakFuel(PRICE_FEED.get(Prices.fuelUrl(province), "UTF-8", 8000));
                    return ok("observed", answer).put("answer", answer).put("source", "东方财富数据中心（发改委调价后的零售限价，加油站实际价可能略低）");
                } catch (IOException | JSONException e) {
                    return fail("error", "油价数据没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("fund_nav") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "查基金：" + a.optString("query"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String query = a.optString("query").trim();
                if (query.isEmpty()) return fail("rejected", "query 写基金名或 6 位基金代码");
                try {
                    String[] fund = Prices.pickFund(PRICE_FEED.get(Prices.fundSearchUrl(query), "UTF-8", 6000));
                    if (fund == null) return fail("not_found", "没找到「" + query + "」这只基金：换成全名或 6 位代码再试");
                    String answer = Prices.speakFund(fund[1], PRICE_FEED.get(Prices.fundNavUrl(fund[0]), "UTF-8", 6000));
                    return ok("observed", answer).put("answer", answer).put("code", fund[0])
                            .put("source", "天天基金（净值每个交易日晚上公布，只是数字，不是投资建议）");
                } catch (IOException | JSONException e) {
                    return fail("error", "基金数据没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("history_today") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "查历史上的今天"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                Calendar c = Calendar.getInstance(TvGuide.BEIJING);
                int month = c.get(Calendar.MONTH) + 1, day = c.get(Calendar.DAY_OF_MONTH), n = Math.max(1, Math.min(8, a.optInt("count", 4)));
                try {
                    List<HistoryToday.Entry> picked = HistoryToday.pick(HistoryToday.entries(HISTORY_FEED.get(HistoryToday.url(month), "UTF-8", 10000),
                            month, day), n);
                    if (picked.isEmpty()) return fail("not_found", "百度百科的历史上的今天没有这一天的条目：可以自己讲或者用 web_search");
                    String answer = HistoryToday.speak(picked, month, day);
                    JSONObject list = HistoryToday.readOut(picked);
                    rememberList(t, list);
                    return withList(ok("observed", answer).put("answer", answer).put("source", "百度百科·历史上的今天"), list);
                } catch (IOException | JSONException e) {
                    return fail("error", "历史上的今天没连上：可以自己讲或者用 web_search");
                }
            }
        });

        tools.add(new Base("world_clock") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "查时间：" + a.optString("place"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String said = a.optString("place").trim();
                String[] p = WorldClock.place(said);
                if (p == null) return fail("rejected", "不认识「" + said + "」：place 写城市名（纽约、伦敦、东京、悉尼…）或国家名");
                String answer = WorldClock.now(p[0], p[1], System.currentTimeMillis(), TimeZone.getDefault());
                return ok("observed", answer).put("answer", answer).put("source", "电视自己的时区数据（含夏令时）");
            }
        });

        tools.add(new Base("tv_guide") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String c = a.optString("channel").trim(), p = a.optString("program").trim();
                return "查节目单：" + (c.isEmpty() ? "" : c) + (p.isEmpty() ? "" : "《" + p + "》");
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String said = a.optString("channel").trim(), program = a.optString("program").trim(), part = a.optString("part").trim();
                String dayArg = a.optString("day").trim();
                int off = "明天".equals(dayArg) ? 1 : "昨天".equals(dayArg) ? -1 : "后天".equals(dayArg) ? 2 : 0;
                if (part.contains("明晚") || part.contains("明天晚上")) off = 1;
                String dayWord = off == 1 ? "明天" : off == -1 ? "昨天" : off == 2 ? "后天" : "今天";
                Calendar day = Calendar.getInstance(TvGuide.BEIJING);
                day.add(Calendar.DAY_OF_MONTH, off);
                long now = System.currentTimeMillis();
                String[] ch = said.isEmpty() ? null : TvGuide.channel(said);
                if (!said.isEmpty() && ch == null)
                    return fail("rejected", "不认识「" + said + "」这个频道：写 CCTV-1 到 CCTV-17、CCTV-5+，或者湖南卫视、东方卫视这样的卫视名");
                // watch 只有快命令会带（contract 里没有这个参数，模型不会填）：用户要看这个台的直播。这台电视上
                // 没有直播应用，如实说放不了，再说它现在在播什么——模型自己走这条路要 6–10 轮（2026-11-11 校准）。
                if (a.optBoolean("watch") && ch != null) {
                    for (String label : apps().keySet())
                        if (label.contains("央视频") || label.contains("电视家"))
                            return fail("has_app", "电视上装了" + label + "：用 open_app 打开它看直播");
                    String onNow = null;
                    try {
                        onNow = TvGuide.speakNow(ch[1], GUIDE_FEED.get(TvGuide.url(ch[0], day), "UTF-8", 6000), ch[0], now, 1);
                    } catch (IOException | JSONException e) {
                        onNow = null; // 节目单读不到也照样说放不了：那是真话
                    }
                    String answer = TvGuide.cannotWatch(ch[1], onNow);
                    return ok("observed", answer).put("answer", answer).put("source", "这台电视上装的应用；节目单是央视的（北京时间）");
                }
                try {
                    String answer;
                    if (!program.isEmpty()) {
                        answer = null;
                        String[] codes = ch != null ? new String[]{ch[0]} : GUIDE_DEFAULT;
                        for (String code : codes) {
                            if (t.cancelled()) throw new InterruptedException();
                            String[] named = ch != null ? ch : TvGuide.channel(codeName(code));
                            String json;
                            try { json = GUIDE_FEED.get(TvGuide.url(code, day), "UTF-8", 6000); } catch (IOException e) { continue; }
                            answer = TvGuide.find(named == null ? code : named[1], json, code, program, dayWord, now);
                            if (answer != null) break;
                        }
                        if (answer == null) return fail("not_found", dayWord + (ch != null ? ch[1] : "央视和主要卫视") + "的节目单里没有《" + program
                                + "》：换个叫法、换一天，或者说出频道再查；网上的播出消息用 web_search");
                    } else {
                        if (ch == null) return fail("rejected", "channel 写频道名（CCTV-1、央视五套、湖南卫视…），或者用 program 找节目");
                        String json = GUIDE_FEED.get(TvGuide.url(ch[0], day), "UTF-8", 6000);
                        if (off == 0 && (part.isEmpty() || part.contains("现在"))) answer = TvGuide.speakNow(ch[1], json, ch[0], now);
                        else if (part.contains("晚")) answer = TvGuide.speakPart(ch[1], json, ch[0], 18, 24, off == 0 ? "今晚" : dayWord + "晚上");
                        else if (part.contains("上午") || part.contains("早")) answer = TvGuide.speakPart(ch[1], json, ch[0], 6, 12, dayWord + "上午");
                        else if (part.contains("下午")) answer = TvGuide.speakPart(ch[1], json, ch[0], 12, 18, dayWord + "下午");
                        else answer = TvGuide.speakPart(ch[1], json, ch[0], off == 0 ? 0 : 6, 24, dayWord);
                    }
                    return ok("observed", answer).put("answer", answer).put("source", "央视节目单（北京时间）");
                } catch (IOException | JSONException e) {
                    return fail("error", "节目单服务没连上：可以用 web_search 查");
                }
            }
            private String codeName(String code) {
                switch (code) {
                    case "cctv1": return "CCTV1"; case "cctv13": return "CCTV13"; case "cctv5": return "CCTV5"; case "cctv8": return "CCTV8";
                    case "cctv6": return "CCTV6"; case "cctv3": return "CCTV3"; case "cctv5plus": return "CCTV5+"; case "hunan": return "湖南卫视";
                    case "dongfang": return "东方卫视"; case "zhejiang": return "浙江卫视"; case "jiangsu": return "江苏卫视"; case "btv1": return "北京卫视";
                    default: return code;
                }
            }
        });

        tools.add(new Base("news") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "看新闻：" + News.category(a.optString("category"))[1]; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String said = a.optString("category").trim();
                if (!News.known(said)) return fail("rejected", "「" + said + "」没有现成的新闻源（有最新、国内、国际、社会、财经、体育、科技）：用 web_search 查");
                String[] c = News.category(said);
                int n = Math.max(1, Math.min(10, a.optInt("count", 5)));
                try {
                    long now = System.currentTimeMillis();
                    List<News.Item> items = News.fresh(News.items(c[0], NEWS_FEED.get(News.url(c[0]), "UTF-8", 8000)), now);
                    if (items.isEmpty()) return fail("not_found", c[1] + "最近两天没有更新：用 web_search 查");
                    String answer = News.speak(c[1], c[0], items, n, now);
                    JSONObject list = News.readOut(c[1], items, n);
                    rememberList(t, list);
                    return withList(ok("observed", answer).put("answer", answer).put("source", News.publisher(c[0])), list);
                } catch (IOException | JSONException e) {
                    return fail("error", "新闻源没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("hot_list") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String[] chart = HotList.chart(a.optString("source"));
                return chart != null ? "看" + chart[1] : bili(a) ? "看B站热门" : "看百度热搜";
            }
            private boolean bili(JSONObject a) {
                String s = a.optString("source").toLowerCase(Locale.ROOT);
                return s.contains("b站") || s.contains("bili") || s.contains("哔哩");
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                int n = Math.max(1, Math.min(10, a.optInt("count", 5)));
                try {
                    String[] chart = HotList.chart(a.optString("source"));
                    if (chart != null) {
                        List<HotList.Song> songs = HotList.chartSongs(HOT_FEED.get(HotList.chartUrl(chart[0], 10), "UTF-8", 8000));
                        String answer = HotList.speakChart(chart[1], songs, n);
                        JSONObject list = HotList.readOutSongs(chart[1], songs, n);
                        rememberList(t, list);
                        return withList(ok("observed", answer).put("answer", answer).put("source", chart[1])
                                .put("note", "用户要听其中哪一首，用 netease_play（title、artist）"), list);
                    }
                    if (bili(a)) {
                        List<HotList.Video> videos = HotList.bili(HOT_FEED.get(HotList.BILI, "UTF-8", 6000));
                        String answer = HotList.speakBili(videos, n);
                        JSONObject list = HotList.readOutVideos(videos, n);
                        rememberList(t, list);
                        JSONArray rows = new JSONArray();
                        for (int i = 0; i < Math.min(10, videos.size()); i++) rows.put(videos.get(i).toJson());
                        return withList(ok("observed", answer).put("answer", answer).put("videos", rows)
                                .put("note", "用户要看其中哪一个，用 open_link 打开它的 link"), list);
                    }
                    List<String> words = HotList.baidu(HOT_FEED.get(HotList.BAIDU, "UTF-8", 6000));
                    String answer = HotList.speakBaidu(words, n);
                    JSONObject list = HotList.readOutTopics(words, n);
                    rememberList(t, list);
                    return withList(ok("observed", answer).put("answer", answer)
                            .put("topics", new JSONArray(words.subList(0, Math.min(20, words.size())))).put("source", "百度热搜（实时）"), list);
                } catch (IOException | JSONException e) {
                    return fail("error", "热搜服务没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("bili_videos") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                return BiliVideos.describe(a.optString("up"), a.optString("keyword"), a.optString("order"));
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String up = a.optString("up").trim(), keyword = a.optString("keyword").trim();
                if (up.isEmpty() && keyword.isEmpty()) return fail("rejected", "up 和 keyword 至少填一个");
                int n = Math.max(1, Math.min(10, a.optInt("count", 3)));
                BiliVideos.Found f;
                try {
                    f = BiliVideos.find(BILI_FEED, up, keyword, a.optString("order"), n);
                } catch (IOException | JSONException e) {
                    return fail("error", "B站的搜索没连上：可以用 bili_search 在B站里搜");
                }
                long now = System.currentTimeMillis();
                boolean live = a.optBoolean("live") || BiliVideos.LIVE_ASKED.matcher(t.utterance() == null ? "" : t.utterance()).find();
                String answer = live ? BiliVideos.speakLive(f, now) : BiliVideos.speak(f, n, now);
                if (f.user == null && !f.asked.isEmpty())
                    return fail("not_found", answer + "：名字可能听错了。用 bili_search 在B站里搜一次，或者问用户 UP 主的准确名字");
                JSONObject r = ok("observed", answer).put("answer", answer).put("videos", BiliVideos.rows(f, now)).put("source", "B站搜索");
                // asked whether the UP is live: this sentence is the whole answer
                if (live && f.user != null) r.put("final", true);
                if (f.user != null) {
                    JSONObject u = new JSONObject().put("name", f.user.name).put("fans", f.user.fans).put("videos", f.user.count)
                            .put("space", f.user.space()).put("live", f.user.live);
                    if (f.user.live && f.user.liveLink() != null) u.put("live_link", f.user.liveLink());
                    r.put("up", u);
                }
                if (!f.videos.isEmpty()) {
                    JSONObject list = BiliVideos.readOut(f, n, now);
                    rememberList(t, list);
                    JSONObject card = Cards.list(list.optString("what"), list.optJSONArray("items"));
                    if (Cards.valid(card)) r.put("card", card);
                    r.put("note", "要播放哪个就用 open_link 打开它的 link：B站会直接进到那个视频开始播放");
                }
                // 「看看我的频道」 is the UP's page, not their newest video (the final calibration, once in three)
                if (f.user != null && t.utterance() != null && t.utterance().matches(".*(频道|主页|空间).*"))
                    r.put("note", "用户要看的是 UP 主的主页：用 open_link 打开 up.space，不要直接放视频");
                return r;
            }
        });

        tools.add(new Base("almanac") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String thing = a.optString("thing").trim();
                return "查黄历" + (thing.isEmpty() ? "" : "：" + thing);
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String thing = a.optString("thing").trim(), month = a.optString("month").trim();
                long now = System.currentTimeMillis();
                try {
                    if (!month.isEmpty() && !thing.isEmpty()) {
                        int[] ym = Huangli.monthOf(month, now);
                        if (ym == null) return fail("rejected", "month 写这个月、下个月或者几月");
                        List<Huangli.Day> in = Huangli.month(Huangli.days(ALMANAC_FEED.get(Huangli.url(ym[0], ym[1]), "GBK", 8000)), ym[0], ym[1], now);
                        if (in.isEmpty()) return fail("error", "黄历没有这个月的数据：可以用 web_search 查");
                        String answer = Huangli.goodDays(in, month.contains("月") && !month.matches(".*[0-9].*") ? month : ym[1] + " 月", thing);
                        // the sentence answers what was asked: read as it is (the model left final out and said it again)
                        return ok("observed", answer).put("answer", answer).put("source", "百度万年历（51 万年历）").put("final", true);
                    }
                    int[] d = Huangli.dayOf(a.optString("date"), now);
                    if (d == null) return fail("rejected", "date 写成 YYYY-MM-DD，或者今天、明天、后天");
                    Huangli.Day day = Huangli.on(Huangli.days(ALMANAC_FEED.get(Huangli.url(d[0], d[1]), "GBK", 8000)), d[0], d[1], d[2]);
                    if (day == null) return fail("error", "黄历没有这一天的数据：可以用 web_search 查");
                    String when = d[3] == 0 ? "今天" : d[3] == 1 ? "明天" : d[3] == 2 ? "后天" : d[3] == -1 ? "昨天" : d[1] + " 月 " + d[2] + " 日";
                    String answer = Huangli.speak(day, when, thing);
                    return ok("observed", answer).put("answer", answer).put("source", "百度万年历（51 万年历）").put("final", true);
                } catch (IOException | JSONException e) {
                    return fail("error", "黄历没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("weather_alert") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                return typhoon(a, t) ? "查台风和气象预警" : "查" + (a.optString("place").trim().isEmpty() ? "这里" : a.optString("place").trim()) + "的气象预警";
            }
            private boolean typhoon(JSONObject a, Turn t) { return a.optBoolean("typhoon") || (t.utterance() != null && t.utterance().contains("台风")); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String said = a.optString("place").trim(), at = place() == null ? "" : place();
                String province = Prices.province(said.isEmpty() ? at : said);
                if (province == null) return fail("rejected", "「" + (said.isEmpty() ? at : said) + "」不是省份或城市：place 写省份或城市（上海、浙江、杭州…）");
                // the place's own warnings first: the TV's district (上海徐汇 → 徐汇), or the city asked about
                String local = said.isEmpty() ? at.replaceFirst("^" + java.util.regex.Pattern.quote(province) + "(市|省)?", "") : said.replaceFirst("^" + java.util.regex.Pattern.quote(province) + "(市|省)?", "");
                long now = System.currentTimeMillis();
                StringBuilder answer = new StringBuilder();
                try {
                    String alarmJson;
                    if (typhoon(a, t)) {
                        Weather.Where h = here();
                        // the province's warnings and the storm list at the same time, then the tracks together: three
                        // fetches one after another took 7.5 s (2026-11-10 sweep)
                        List<String> both = ALERT_FEED.many(java.util.Arrays.asList(Alerts.alarmUrl(province), Alerts.TYPHOONS), 8000);
                        alarmJson = both.get(0);
                        List<Alerts.Storm> active = new ArrayList<>();
                        for (Alerts.Storm s : Alerts.storms(both.get(1))) if (s.active && active.size() < 2) active.add(s);
                        List<String> tracks = new ArrayList<>(), urls = new ArrayList<>();
                        for (Alerts.Storm s : active) urls.add(Alerts.trackUrl(s.id));
                        if (!urls.isEmpty()) tracks = ALERT_FEED.many(urls, 8000);
                        List<String> storms = new ArrayList<>();
                        for (int i = 0; i < active.size(); i++) storms.add(Alerts.speakStorm(active.get(i), Alerts.last(tracks.get(i)),
                                h == null ? Double.NaN : h.lat, h == null ? Double.NaN : h.lon, now));
                        answer.append(storms.isEmpty() ? "现在没有活跃的台风" : String.join("；", storms));
                    } else alarmJson = ALERT_FEED.one(Alerts.alarmUrl(province), 8000);
                    String alarms = Alerts.speakAlarms(Alerts.alarms(alarmJson, province), province, local, now);
                    // asked about typhoons: a quiet province in half a sentence (「台风要来上海吗」), warnings in one of their own
                    if (answer.length() == 0) answer.append(alarms);
                    else if (alarms.endsWith("没有生效的气象预警")) answer.append("，").append(province).append("也没有生效的气象预警");
                    else answer.append("。").append(alarms);
                } catch (IOException | JSONException e) {
                    return fail("error", "中央气象台没连上：可以用 web_search 查");
                }
                return ok("observed", answer.toString()).put("answer", answer.toString()).put("source", "中央气象台");
            }
        });

        tools.add(new Base("bili_bangumi") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String title = a.optString("title").trim();
                String kind = a.optString("kind").trim().isEmpty() ? Bangumi.kindAsked(t.utterance()) : a.optString("kind").trim();
                String day = a.optString("day").trim();
                boolean week = Bangumi.weekAsked(day.isEmpty() ? t.utterance() : day);
                return title.isEmpty() ? "看B站" + (week ? "这周" : day.isEmpty() ? "今天" : day) + "更新的" + (kind.isEmpty() ? "番剧" : kind)
                        : "查《" + title + "》在B站的更新";
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String title = a.optString("title").trim().replaceAll("^《|》$", "");
                List<Bangumi.Episode> all = new ArrayList<>();
                try {
                    all.addAll(Bangumi.episodes(BANGUMI_FEED.get(Bangumi.ANIME, "UTF-8", 8000), "番剧"));
                    all.addAll(Bangumi.episodes(BANGUMI_FEED.get(Bangumi.GUOCHUANG, "UTF-8", 8000), "国创"));
                } catch (IOException | JSONException e) {
                    return fail("error", "B站的番剧更新表没连上：可以用 web_search 查");
                }
                Collections.sort(all, (x, y) -> Long.compare(x.at, y.at));
                long now = System.currentTimeMillis();
                if (!title.isEmpty()) {
                    List<Bangumi.Episode> show = Bangumi.find(all, title);
                    String answer = Bangumi.speakShow(show, title, now);
                    if (show.isEmpty()) return fail("not_found", answer + "。要找它就用 bili_search 在B站里搜，问别的事用 web_search");
                    JSONObject r = ok("observed", answer).put("answer", answer).put("episodes", Bangumi.rows(show, now))
                            .put("season_link", show.get(0).seasonLink()).put("source", "B站番剧更新表");
                    Bangumi.Episode last = Bangumi.latest(show);
                    if (last != null) r.put("link", last.link()).put("note", "要放最新一集就用 open_link 打开 link");
                    return r;
                }
                int offset = Bangumi.offset(a.optString("day"), now);
                String kind = a.optString("kind").trim();
                if (kind.isEmpty()) kind = Bangumi.kindAsked(t.utterance());
                List<Bangumi.Episode> mine = Bangumi.withoutDubs(Bangumi.ofKind(all, kind));
                // 「这周有什么新番」: one sentence for the seven days, not a tool call a day (2026-11-10 sweep: three
                // model rounds, 7.1 s)
                String said = a.optString("day").trim();
                if (Bangumi.weekAsked(said.isEmpty() ? t.utterance() : said)) {
                    String answer = Bangumi.speakWeek(mine, now, kind);
                    return ok("observed", answer).put("answer", answer).put("source", "B站番剧更新表")
                            .put("note", "要看哪一部就再用 bili_bangumi 填 title 说它的名字，要放就用 open_link 打开它的 link");
                }
                List<Bangumi.Episode> day = Bangumi.on(mine, now, offset);
                String answer = Bangumi.speakDay(day, offset, now, kind);
                JSONObject list = day.isEmpty() ? null : Bangumi.readOut(day, offset, now);
                if (list != null) rememberList(t, list);
                return withList(ok("observed", answer).put("answer", answer).put("episodes", Bangumi.rows(day, now)).put("source", "B站番剧更新表")
                        .put("note", "要看哪一部就用 open_link 打开它的 link（还没更新的打不开）"), list);
            }
        });

        tools.add(new Base("music_new") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "查" + a.optString("artist") + "最近发的歌"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String artist = a.optString("artist").trim();
                if (artist.isEmpty()) return fail("rejected", "artist 不能为空：填歌手的名字");
                try {
                    Object[] who = MusicNew.artist(HOT_FEED.get(MusicNew.artistUrl(artist), "UTF-8", 8000), artist);
                    if (who == null) return fail("not_found", "网易云上没找到歌手「" + artist + "」：名字可能听错了，问用户或者用 web_search");
                    List<MusicNew.Release> rs = MusicNew.releases(HOT_FEED.get(MusicNew.albumsUrl((Long) who[0]), "UTF-8", 8000));
                    long now = System.currentTimeMillis();
                    String answer = MusicNew.speak((String) who[1], rs, Math.max(1, Math.min(5, a.optInt("count", 3))), now);
                    return ok("observed", answer).put("answer", answer).put("releases", MusicNew.rows(rs, now)).put("artist", who[1]).put("source", "网易云音乐")
                            .put("note", "要放就用 netease_play：title 填单曲的名字（专辑填专辑里的歌名或者只填 artist），artist 填歌手");
                } catch (IOException | JSONException e) {
                    return fail("error", "网易云没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("movie_list") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "看豆瓣的" + Movies.list(a.optString("list"))[1]; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String[] list = Movies.list(a.optString("list"));
                int n = Math.max(1, Math.min(8, a.optInt("count", 5)));
                try {
                    // films on now: the whole list, to pick what is new and most watched from it
                    List<Movies.Item> items = Movies.items(MOVIE_FEED.get(Movies.listUrl(list[0], "movie_showing".equals(list[0]) ? 40 : n), "UTF-8", 8000));
                    String answer = Movies.speakList(list[0], list[1], items, n, System.currentTimeMillis());
                    JSONArray rows = new JSONArray();
                    for (int i = 0; i < Math.min(10, items.size()); i++)
                        rows.put(new JSONObject().put("title", items.get(i).name).put("rating", items.get(i).rating).put("year", items.get(i).year)
                                .put("release", items.get(i).release).put("cast", items.get(i).cast));
                    JSONObject r = ok("observed", answer).put("answer", answer).put("items", rows).put("source", "豆瓣")
                            .put("note", "用户问其中某一部的评分和主创用 movie_rating；要在电视上看用 tencent_video_search 或 bili_search");
                    JSONObject card = Cards.list(list[1], rows);
                    if (Cards.valid(card)) r.put("card", card);
                    return r;
                } catch (IOException | JSONException e) {
                    return fail("error", "豆瓣没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("movie_rating") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                boolean where = a.optBoolean("where") || Movies.WHERE.matcher(t.utterance() == null ? "" : t.utterance()).find();
                return where ? "查《" + a.optString("title") + "》在哪能看" : "查豆瓣上《" + a.optString("title") + "》的评分";
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String title = a.optString("title").trim().replaceAll("^《|》$", "");
                if (title.isEmpty()) return fail("rejected", "title 不能为空");
                try {
                    List<Movies.Title> all = Movies.titles(MOVIE_FEED.get(Movies.url(title), "UTF-8", 8000));
                    if (all.isEmpty()) return fail("not_found", "豆瓣上没找到「" + title + "」：片名可能听错了，换个叫法再查，或者用 web_search");
                    // 「在哪个平台能看」「有几集」: its page (platforms, episodes), a whole answer by itself
                    if (a.optBoolean("where") || Movies.WHERE.matcher(t.utterance() == null ? "" : t.utterance()).find()) {
                        Movies.Title pick = Movies.pick(all, title, a.optInt("year", 0));
                        Movies.Detail d = Movies.detail(MOVIE_FEED.get(Movies.detailUrl(pick), "UTF-8", 8000));
                        String answer = Movies.speakWhere(pick, d);
                        JSONArray vendors = new JSONArray();
                        for (Movies.Vendor v : d.vendors) {
                            JSONObject x = new JSONObject().put("name", v.name).put("pay", v.pay).put("tv", Movies.onTv(v.name));
                            if (!v.link.isEmpty()) x.put("link", v.link);
                            vendors.put(x);
                        }
                        return ok("observed", answer).put("answer", answer).put("vendors", vendors).put("source", "豆瓣").put("final", true)
                                .put("note", "用户要在电视上看：有B站的 link 就先用 open_link 打开它（一步进播放页）；没有再用 tencent_video_search 在云视听极光里搜腾讯视频的；别的平台照 tv 一栏说");
                    }
                    String answer = Movies.speak(all, title, a.optInt("year", 0));
                    return ok("observed", answer).put("answer", answer).put("candidates", Movies.rows(all)).put("source", "豆瓣");
                } catch (IOException | JSONException e) {
                    return fail("error", "豆瓣没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("gold_price") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "查金价"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                try {
                    String answer = Metals.speakGold(GOLD_FEED.get(Metals.GOLD, "GBK", 6000),
                            Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")));
                    if (answer == null) return fail("error", "金价服务没有返回数据：可以用 web_search 查");
                    return ok("observed", answer).put("answer", answer).put("source", "新浪行情：上海黄金交易所 Au99.99、伦敦金现货");
                } catch (IOException e) {
                    return fail("error", "金价服务没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("lottery") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "查开奖：" + a.optString("game", "双色球"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String said = a.optString("game").trim();
                String[] g = Lottery.game(said.isEmpty() ? "双色球" : said);
                if (g == null) return fail("rejected", "不认识「" + said + "」：game 写双色球、福彩3D、七乐彩、快乐8、大乐透、排列三、排列五或七星彩");
                try {
                    String answer = Lottery.speak(g[0], g[1], LOTTERY_FEED.get(Lottery.url(g[1]), "UTF-8", 8000));
                    return ok("observed", answer).put("answer", answer)
                            .put("source", g[1].startsWith("cwl:") ? "中国福利彩票官网" : "中国体育彩票官网");
                } catch (IOException | JSONException e) {
                    return fail("error", "彩票官网这会儿查不了：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("nba_games") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String team = a.optString("team").trim();
                return team.isEmpty() ? "查 NBA 比赛：" + a.optString("day", "今天") : "查 NBA：" + team + (a.optBoolean("last") ? "上一场" : "");
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String said = a.optString("team").trim();
                long now = System.currentTimeMillis();
                try {
                    String answer;
                    if (!said.isEmpty()) {
                        String[] team = Sports.team(said);
                        if (team == null) return fail("rejected", "不认识「" + said + "」：NBA 球队用中文队名（湖人、勇士、尼克斯…）；别的比赛用 web_search");
                        answer = Sports.speakTeam(SPORTS_FEED.get(Sports.scheduleUrl(team[0]), "UTF-8", 8000), team[1], now, a.optBoolean("last"));
                    } else {
                        String day = a.optString("day").trim();
                        Calendar d = Calendar.getInstance(Sports.BEIJING);
                        String word;
                        java.util.regex.Matcher iso = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(day);
                        if (iso.matches()) {
                            d.set(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)) - 1, Integer.parseInt(iso.group(3)));
                            word = " " + Integer.parseInt(iso.group(2)) + " 月 " + Integer.parseInt(iso.group(3)) + " 日";
                        } else {
                            int off = "昨天".equals(day) ? -1 : "明天".equals(day) ? 1 : "后天".equals(day) ? 2 : "前天".equals(day) ? -2 : 0;
                            if (off == 0 && !day.isEmpty() && !"今天".equals(day))
                                return fail("rejected", "day 写今天、昨天、明天、后天，或者 YYYY-MM-DD（北京时间的日期）");
                            d.add(Calendar.DAY_OF_MONTH, off);
                            word = off == -1 ? "昨天" : off == 1 ? "明天" : off == 2 ? "后天" : off == -2 ? "前天" : "今天";
                        }
                        answer = Sports.speakBoard(SPORTS_FEED.get(Sports.boardUrl(d), "UTF-8", 8000), word, now);
                    }
                    return ok("observed", answer).put("answer", answer).put("source", "ESPN 比分（北京时间）");
                } catch (IOException | JSONException e) {
                    return fail("error", "比分服务没连上：可以用 web_search 查");
                }
            }
        });

        tools.add(new Base("football_games") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String team = a.optString("team").trim();
                return team.isEmpty() ? "查足球：" + a.optString("league", "英超") + a.optString("day", "") : "查足球：" + team;
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String said = a.optString("team").trim();
                long now = System.currentTimeMillis();
                try {
                    String answer;
                    if (!said.isEmpty()) {
                        String[] club = Football.club(said);
                        if (club == null) return fail("rejected", "不认识「" + said + "」：用中文队名（曼联、皇马、拜仁、国米、申花…）；"
                                + "五大联赛、欧冠、中超以外的比赛用 web_search");
                        String results = SPORTS_FEED.get(Football.resultsUrl(club[0], club[1]), "UTF-8", 8000);
                        String fixtures;
                        try { fixtures = SPORTS_FEED.get(Football.fixturesUrl(club[0], club[1]), "UTF-8", 8000); } catch (IOException e) { fixtures = null; }
                        answer = Football.speakClub(results, fixtures, club[1], club[2], now);
                    } else {
                        String[] league = Football.league(a.optString("league").trim().isEmpty() ? "英超" : a.optString("league"));
                        if (league == null) return fail("rejected", "league 写英超、西甲、德甲、意甲、法甲、欧冠或中超；别的比赛用 web_search");
                        String day = a.optString("day").trim();
                        Calendar d = Calendar.getInstance(Sports.BEIJING);
                        java.util.regex.Matcher iso = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(day);
                        if (iso.matches()) d.set(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)) - 1, Integer.parseInt(iso.group(3)));
                        else {
                            int off = "昨天".equals(day) ? -1 : "明天".equals(day) ? 1 : "后天".equals(day) ? 2 : "前天".equals(day) ? -2 : 0;
                            if (off == 0 && !day.isEmpty() && !"今天".equals(day)) return fail("rejected", "day 写今天、昨天、明天、后天，或者 YYYY-MM-DD");
                            d.add(Calendar.DAY_OF_MONTH, off);
                        }
                        // a Beijing day spans two of ESPN's days (European evenings are Beijing nights)
                        Calendar before = (Calendar) d.clone();
                        before.add(Calendar.DAY_OF_MONTH, -1);
                        List<String> boards = new ArrayList<>();
                        boards.add(SPORTS_FEED.get(Football.boardUrl(league[0], before), "UTF-8", 8000));
                        boards.add(SPORTS_FEED.get(Football.boardUrl(league[0], d), "UTF-8", 8000));
                        answer = Football.speakBoard(boards, league[1], d, now);
                    }
                    return ok("observed", answer).put("answer", answer).put("source", "ESPN 比分（北京时间）");
                } catch (IOException | JSONException e) {
                    return fail("error", "比分服务没连上：可以用 web_search 查");
                }
            }
        });

        // 「星仪自检」 (2026-11-06 overnight pass): what works and what does not, in one sentence — for the user when
        // something seems broken, and for the model when it has to say why it could not.
        tools.add(new Base("self_check") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "自检"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                List<String> ok = new ArrayList<>(), bad = new ArrayList<>();
                // the model: one tiny round on the usual route
                long began = SystemClock.uptimeMillis();
                try {
                    // its own board: a probe must not replace the route line and model the real turn is showing
                    ModelRouter router = ModelRouter.detached(context);
                    JSONArray ping = new JSONArray().put(new JSONObject().put("role", "user").put("content", "只回复一个字：好"));
                    router.next(ping, null);
                    // lastRoute() is the status line 「gateway · official: insufficient balance …」: the route leads it.
                    // The gateway is now the one answering in normal operation, so calling it 备用网关 told the user
                    // the backup was in use; name the route and the model that actually answered.
                    String route = router.lastRoute().startsWith(ModelRouter.GATEWAY)
                            ? "网关，模型 " + router.activeModel() : "官方线路";
                    ok.add("模型（" + route + "，" + String.format(Locale.ROOT, "%.1f", (SystemClock.uptimeMillis() - began) / 1000.0) + " 秒）");
                } catch (Exception e) {
                    String why = e.getMessage() == null ? "连不上" : e.getMessage();
                    bad.add("模型连不上（" + (why.length() > 40 ? why.substring(0, 40) : why) + "），这时小爱会接过去回答");
                }
                // past the cache: the check is whether the service answers now
                if (Weather.hintFor(Weather.https(), here(), 9, 6000) != null) ok.add("天气");
                else bad.add("天气服务连不上（天气会改用联网搜索）");
                // 读屏、面板、本机调试 and 小爱的卡片: checked and, where the bridge can reach them, mended on the spot
                // and checked again (2026-11-12 01:30: 「他修不了系统的问题」 — the panel did not come up and 星仪 said
                // 「自检正常」 three times, because self_check only ever probed)
                JSONObject repair = selfRepair();
                List<String> mended = strings(repair.getJSONArray("mended")),
                        unfixable = strings(repair.getJSONArray("unfixable"));
                for (String s : strings(repair.getJSONArray("ok"))) ok.add(s);
                int broken = repair.getJSONArray("broken").length();
                // what SelfRepair could not mend, and what this file's own checks found: both still count as problems
                String text;
                if (!mended.isEmpty() || !unfixable.isEmpty() || broken > 0)
                    text = repair.getString("say") + (bad.isEmpty() ? "" : "另外：" + String.join("；", bad) + "。");
                else if (bad.isEmpty()) text = Repairs.normal(ok, mended);
                else text = "自检：" + String.join("；", bad) + (ok.isEmpty() ? "。" : "。其他正常：" + String.join("、", ok) + "。");
                return ok("observed", text).put("problems", bad.size() + broken + unfixable.size());
            }
        });

        tools.add(new Base("tv_usage") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                return "查电视的使用记录：" + Usage.what(a.optString("day"), a.optString("part"));
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String day = a.optString("day"), part = a.optString("part");
                TimeZone tz = TimeZone.getDefault();
                long[] w;
                try { w = Usage.window(day, part, System.currentTimeMillis(), tz); }
                catch (IllegalArgumentException e) { return fail("rejected", e.getMessage()); }
                android.app.usage.UsageStatsManager usm =
                        (android.app.usage.UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm == null) return fail("unavailable", "电视没有提供使用记录");
                // from half a day earlier (whether the TV was already on, and in which app, when the window opens) up to
                // now (a screen event after the window also tells the state before it)
                long now = System.currentTimeMillis();
                android.app.usage.UsageEvents events = usm.queryEvents(w[0] - 12 * 3_600_000L, now);
                List<Usage.Event> list = new ArrayList<>();
                android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
                while (events != null && events.hasNextEvent()) {
                    events.getNextEvent(e);
                    int type = e.getEventType();
                    if (type == Usage.FOREGROUND || type == Usage.SCREEN_ON || type == Usage.SCREEN_OFF)
                        list.add(new Usage.Event(e.getTimeStamp(), type, e.getPackageName()));
                }
                android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                JSONObject s = Usage.summary(list, w[0], w[1], Usage.what(day, part), TvTools.this::label, tz,
                        power != null && power.isInteractive());
                return ok("observed", s.getString("message")).put("on_minutes", s.getLong("on_minutes")).put("on", s.getJSONArray("on"))
                        .put("apps", s.getJSONArray("apps"));
            }
        });

        tools.add(new Base("x_search") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "在 X（推特）上查：" + a.optString("query"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String query = a.getString("query");
                String refusal = xSearches.check(t.round());
                if (refusal == null) refusal = cap("x-search", X_PER_DAY, "在 X 上").take(today());
                if (refusal != null) return fail("rejected", refusal);
                GatewayConfig route;
                try { route = GatewayConfig.loadRoutes(context).gateway; }
                catch (IOException e) { route = null; }
                if (route == null) return fail("unavailable", "没有配置联网搜索要用的网关线路");
                t.say("我去 X 上翻一下原帖，要半分钟到一分钟。");
                WebSearch.Result r;
                try { r = WebSearch.forX(route.baseUrl, route.apiKey, WebSearch.https()).search(query, t.utterance(), now(), 90000); }
                catch (IllegalArgumentException e) { return fail("rejected", e.getMessage()); }
                catch (IOException e) { return fail("error", e.getMessage()); }
                xSearches.result(r.answer, r.searched);
                return ok("observed", r.answer).put("answer", r.answer).put("searched", r.searched)
                        .put("seconds", Math.round(r.millis / 100.0) / 10.0);
            }
            /** The TV's own clock, in the wording the search model is told to reason with. */
            private String now() {
                Calendar c = Calendar.getInstance();
                return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(c.getTime())
                        + " 星期" + "日一二三四五六".charAt(c.get(Calendar.DAY_OF_WEEK) - 1);
            }
        });

        tools.add(new Base("media_now_playing") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "查看正在播放的内容"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                MediaSessions.Session s = nowPlaying();
                if (s != null) return ok("observed", label(s.pkg) + " " + s.describe()).put("session", session(s));
                // 云视听极光 keeps no media session: what its own players are doing is the answer (2026-11-11 20:03)
                String state = jiguangState();
                if (AudioPlayers.NONE.equals(state))
                    return ok("observed", "没有正在播放的媒体").put("session", JSONObject.NULL);
                boolean playing = AudioPlayers.STARTED.equals(state);
                return ok("observed", "云视听极光 " + (playing ? "正在播放" : "暂停着") + openedShow())
                        .put("session", new JSONObject().put("app", "云视听极光").put("package", JIGUANG)
                                .put("state", playing ? "playing" : "paused"));
            }
        });

        tools.add(new Base("media_control") {
            @Override public Gate gate(JSONObject a, Turn t) {
                String act = a.optString("action");
                // XiaoAi's skip is believed unless the player shows it never came (2026-11-16 19:30, skipMissed). The
                // refusal says what plays now, so the answer needs no media_now_playing round (calibration 11-18: the
                // model pressed next first in half the runs, then spent a round asking what was on)
                if (("next".equals(act) || "previous".equals(act)) && xiaoaiDid(SKIPS)) {
                    MediaSessions.Session now = nowPlaying();
                    if (!skipMissed(now)) return Gate.deny("小爱已经为这句话切过了，再切一次会多跳一个；" + playingNow(now));
                }
                return Gate.act();
            }
            @Override public String describe(JSONObject a, Turn t) {
                switch (a.optString("action")) {
                    case "play": return "继续播放"; case "pause": return "暂停"; case "next": return "下一个";
                    case "previous": return "上一个"; case "exit": return "退出应用回到桌面"; default: return "停止播放";
                }
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String action = a.getString("action");
                if ("exit".equals(action)) return exitApp(a.isNull("app") ? "" : a.optString("app"), flag(a, "blank"), t);
                int code;
                switch (action) {
                    case "play": code = KeyEvent.KEYCODE_MEDIA_PLAY; break;
                    case "pause": code = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
                    case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
                    case "previous": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
                    default: code = KeyEvent.KEYCODE_MEDIA_STOP;
                }
                // 云视听极光 has no media session: its key has to go into its own window (see jiguangKey)
                JSONObject jiguang = jiguangKey(action, code, t);
                if (jiguang != null) return jiguang;
                // the gate let a skip through that XiaoAi had sent too, because it had not landed: it still may
                if (("next".equals(action) || "previous".equals(action)) && xiaoaiDid(SKIPS)) {
                    JSONObject late = skipLandedLate(t);
                    if (late != null) return late;
                }
                long now = SystemClock.uptimeMillis();
                audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
                Thread.sleep(1000);
                MediaSessions.Session s = nowPlaying();
                // the play key had nothing to act on and the standby park closed NetEase hours ago: the song comes back
                if ("play".equals(action) && (s == null || !"playing".equals(s.state))) {
                    JSONObject back = replayParked(t);
                    if (back != null) return back;
                }
                // 「关掉」 while a song played: a stop or pause the app ignored is not done (2026-11-07: the answer read
                // 「…正在播放《她说》」 as if it had been)
                if (s != null && "playing".equals(s.state) && ("stop".equals(action) || "pause".equals(action)))
                    return fail("unconfirmed", label(s.pkg) + "还在播放《" + s.title + "》：" + ("stop".equals(action) ? "停止" : "暂停")
                            + "键它没理。换 pause 再试一次，或者 press_keys home 回到桌面").put("session", session(s));
                return ok("observed", s == null ? "已发送" : label(s.pkg) + " " + s.describe()).put("session", session(s));
            }
        });

        tools.add(new Base("media_seek") {
            @Override public Gate gate(JSONObject a, Turn t) {
                return xiaoaiDid("PlaybackController.")
                        ? Gate.deny("小爱已经为这句话快进或快退过了，再做一次会多跳一段：告诉用户已经好了。") : Gate.act();
            }
            @Override public String describe(JSONObject a, Turn t) {
                int n = a.optInt("seconds");
                return (n < 0 ? "快退 " : "快进 ") + Math.abs(n) + " 秒";
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                int seconds = a.getInt("seconds");
                if (seconds == 0) return fail("rejected", "seconds 不能是 0");
                String focused = adb.call(LocalAdb::focusedApp);
                if (!PlayerPages.isJiguangPlayer(FrontApp.pkg(focused), FrontApp.cls(focused)))
                    return fail("unsupported", "现在前台的应用我还不会快进快退：可以说具体的时间点，或者用遥控器");
                // it keeps no media session, so what its player is doing comes from the audio service (jiguangState)
                String state = jiguangState();
                if (!AudioPlayers.STARTED.equals(state) && !AudioPlayers.PAUSED.equals(state))
                    return fail("unconfirmed", "云视听极光现在没有在放东西，没法快进");
                // one press moves about 10 seconds (2026-11-11 on the TV, progress bar on screen); DPAD right would
                // open the SVIP purchase page during the ad before an episode, so only the two media keys are used
                int presses = Math.min(180, Math.max(1, (int) Math.round(Math.abs(seconds) / 10.0)));
                int code = seconds < 0 ? KeyEvent.KEYCODE_MEDIA_REWIND : KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
                int[] codes = new int[presses];
                Arrays.fill(codes, code);
                adb.keys(codes);
                // its pages are self-drawn, so nothing of it can be read back: say what was sent, never claim it was checked
                return ok("sent", PlayerPages.seekSays(seconds));
            }
        });

        tools.add(new Base("netease_play") {
            @Override public String describe(JSONObject a, Turn t) {
                String title = a.optString("title", "").trim(), artist = a.optString("artist", "").trim();
                if (title.isEmpty() && !artist.isEmpty()) return "网易云连续播放" + artist + "的歌";
                return "网易云播放" + (artist.isEmpty() ? "" : artist + (title.isEmpty() ? "的歌" : "的")) + (title.isEmpty() ? "" : "《" + title + "》");
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String title = a.has("title") ? a.getString("title").trim() : "", artist = a.has("artist") ? a.getString("artist").trim() : "";
                if (title.isEmpty() && artist.isEmpty()) return fail("rejected", "至少要有歌名或歌手");
                JSONObject asleep = songAsleep(t);
                if (asleep != null) return asleep;
                String lit = wakeForSong(t);
                if (title.isEmpty()) return withLit(playArtist(artist, t), lit);
                return withLit(Legacy.playNetease(context, title, artist.isEmpty() ? null : artist, t::cancelled), lit);
            }
        });

        tools.add(new Base("netease_playlist") {
            @Override public String describe(JSONObject a, Turn t) { return "fm".equals(a.optString("kind")) ? "播放网易云私人FM" : "播放网易云“我喜欢的音乐”"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                if (launchIntent(NETEASE) == null) return fail("unavailable", "电视上没有网易云音乐");
                JSONObject asleep = songAsleep(t);
                if (asleep != null) return asleep;
                String lit = wakeForSong(t);
                Uri.Builder b = new Uri.Builder().scheme("voice").authority("netease.audioplayer");
                if ("fm".equals(a.getString("kind"))) b.path("/playPrivateFm");
                else b.path("/favoritePlaylist").appendQueryParameter("command", "play");
                start(new Intent(Intent.ACTION_VIEW).setDataAndType(b.build(), "voice/audio").setComponent(NETEASE_VOICE));
                long end = SystemClock.uptimeMillis() + 8000;
                MediaSessions.Session s = null;
                while (SystemClock.uptimeMillis() < end) {
                    Thread.sleep(800);
                    s = nowPlaying();
                    if (s != null && NETEASE.equals(s.pkg) && "playing".equals(s.state)) break;
                }
                boolean playing = s != null && NETEASE.equals(s.pkg) && "playing".equals(s.state);
                return withLit((playing ? ok("observed", "网易云" + s.describe()) : fail("unconfirmed", "已请求网易云播放，还没读到播放状态"))
                        .put("session", session(s)), lit);
            }
        });

        tools.add(new Base("netease_karaoke") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.act(); }
            @Override public String describe(JSONObject a, Turn t) {
                return flag(a, "on") ? "网易云切到伴奏（打开随心唱）" : "网易云切回原唱（关掉随心唱）";
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                boolean on = flag(a, "on");
                ScreenDigest d = read();
                if (!playerInFront(d)) {
                    MediaSessions.Session s = nowPlaying();
                    boolean live = s != null && NETEASE.equals(s.pkg)
                            && ("playing".equals(s.state) || "paused".equals(s.state));
                    if (live) d = bringPlayerBack(t);
                    if (!playerInFront(d))
                        return fail("rejected", Karaoke.notInFront(where(d), live ? s.title : "", live ? s.artist : ""));
                }
                micKeys(false);
                long lastKey = SystemClock.uptimeMillis();
                d = read();
                Boolean now = Karaoke.state(d.text());
                if (now == null) return unread(d, false);
                if (now.booleanValue() == on) return ok("observed", on ? "本来就是伴奏：随心唱开着" : "本来就是原唱：随心唱关着");
                if (SystemClock.uptimeMillis() - lastKey < BAR_UP_MS) {
                    // the bar is still up and the focus still on the mic: OK now. The whole way again would start with
                    // DOWN from the mic, which lands on the progress bar, and RIGHT x4 there seeks the song forward
                    adb.keys(KEYS.get("center"));
                } else {
                    // the bar may have hidden while the screen was read: let it hide, then the whole way again with CENTER
                    // at the end (with the bar hidden CENTER would pause the song, UP open the tutorial). Polled, not
                    // slept: the bar's own label goes with its window, so a read that no longer finds the label means it
                    // has gone and the keys can go now (2026-11-18 audit: this slept 3.5 s on every switch, and
                    // 11-09 20:13 the whole tool took 12.1 s). BAR_HIDES_MS stays the ceiling.
                    long goneBy = SystemClock.uptimeMillis() + BAR_HIDES_MS;
                    // two reads in a row without the label: one null can be a read that failed or caught the bar mid-
                    // animation, and pressing from a bar still up would seek the song (2026-11-18 review of this fix)
                    boolean gone = false;
                    while (SystemClock.uptimeMillis() < goneBy) {
                        Thread.sleep(250);
                        d = read();
                        Boolean saw = Karaoke.state(d.text());
                        if (saw == null && gone) break;
                        gone = saw == null;
                    }
                    micKeys(true);
                }
                Thread.sleep(1200);
                d = read();
                Boolean after = Karaoke.state(d.text());
                if (after == null) return unread(d, true);
                if (after.booleanValue() != on)
                    return withScreen(fail("unconfirmed", "按了确认键，但麦克风旁边写的还是「"
                            + (after.booleanValue() ? Karaoke.ON_LABEL : Karaoke.OFF_LABEL) + "」，没有切过来"), d);
                return ok("observed", on ? "已切到伴奏：随心唱开着，按上键能调人声大小" : "已切回原唱：随心唱关了");
            }
            /** NetEase's player page is in front. Eyes says so, or — when XiaoAi's panel hides the real window from it —
             * the window manager's focused activity settles it with one call (2026-11-09: the player was in front and the
             * agent still went to NetEase's home page). */
            private boolean playerInFront(ScreenDigest d) throws IOException {
                if (NETEASE.equals(foreground()) && PlayerPages.isPlayer(NETEASE, d.activity)) return true;
                String focused = adb.call(LocalAdb::focusedApp);
                return NETEASE.equals(FrontApp.pkg(focused)) && PlayerPages.isPlayer(NETEASE, FrontApp.cls(focused));
            }

            /**
             * 播放页不在前台、而网易云在放或暂停着时，把它叫回来：网易云自己的播放页链接（不带 RESOURCE_ID）经
             * TvRedirectActivity 在 0.15–0.2 秒内回到<b>正在放的那首</b>的播放页，位置不丢，也跳过约 4.5 秒的开屏页
             * （2026-11-10 01:46 实测；带 RESOURCE_ID 或走 voice://…/search 都会先过开屏页）。最多等 3 秒，返回最后读到的画面。
             */
            private ScreenDigest bringPlayerBack(Turn t) throws Exception {
                start(new Intent(Intent.ACTION_VIEW, Uri.parse("nmcloudmusictv://ng/player")).setComponent(NETEASE_VOICE));
                ScreenDigest d = last;
                long end = SystemClock.uptimeMillis() + 3000;
                while (SystemClock.uptimeMillis() < end) {
                    if (t.cancelled()) throw new InterruptedException();
                    Thread.sleep(200);
                    d = read();
                    if (playerInFront(d)) break;
                }
                return d;
            }

            /** What is in front now, in words, for the reject text. */
            private String where(ScreenDigest d) {
                return d.app.isEmpty() ? "别的应用"
                        : d.app.split(" ")[0] + (d.activity.isEmpty() ? "" : " " + d.activity);
            }

            /** The label beside the icon could not be read: say so with what was on screen, and never guess. */
            private JSONObject unread(ScreenDigest d, boolean pressed) throws JSONException {
                String seen = Karaoke.focus(d.text());
                String what = seen.isEmpty() ? "屏幕上没有控制栏" : "焦点在" + seen + "上";
                return withScreen(fail("unconfirmed", pressed ? "按了确认键，但没读出随心唱现在的状态（" + what + "），不确定切过来了没有"
                        : "没读出随心唱现在的状态（" + what + "），没有按确认键，不确定现在是伴奏还是原唱"), d);
            }
        });

        tools.add(new Base("bili_search") {
            @Override public String describe(JSONObject a, Turn t) { return "在哔哩哔哩搜索「" + a.optString("keyword") + "」"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String keyword = a.getString("keyword");
                if (launchIntent(BILI) == null)
                    return fail("unavailable", "电视上没有哔哩哔哩官方客户端，可以先调用 install_app 安装（package=tv.danmaku.bili）");
                Uri uri = new Uri.Builder().scheme("bilibili").authority("search").appendQueryParameter("keyword", keyword).build();
                long before = Eyes.changeCount();
                start(new Intent(Intent.ACTION_VIEW, uri).setPackage(BILI));
                // A cold B站 needs ~15 s on this TV (2026-11-04: 9 s lost 6 of a run's 16 rounds), and it sometimes
                // swallows the first search intent: if it is up but idle without the keyword, send it once more.
                long sentAt = SystemClock.uptimeMillis(), end = sentAt + 25000;
                ScreenDigest d = null;
                boolean resent = false, dismissed = false;
                // every read makes B站's main thread build its whole accessibility tree while it is starting: once B站
                // is in front its screen is read at most every BILI_READ_GAP_MS (as openBiliVideo does), the loop itself
                // still turns twice a second
                long readAt = 0;
                while (SystemClock.uptimeMillis() < end) {
                    if (t.cancelled()) throw new InterruptedException();
                    Thread.sleep(500);
                    if (!BILI.equals(foreground())) continue;
                    // B站's own login modal covers the results it just showed: close it before reading them
                    if (!dismissed) dismissed = dismissBiliLogin();
                    if (SystemClock.uptimeMillis() - readAt > BILI_READ_GAP_MS) {
                        readAt = SystemClock.uptimeMillis();
                        d = read();
                        if (d.contains(keyword) && d.elements.size() > 3) break;
                    }
                    if (!resent && SystemClock.uptimeMillis() - sentAt > 12000
                            && SystemClock.uptimeMillis() - Eyes.lastChangeAt() > 1500) {
                        resent = true;
                        start(new Intent(Intent.ACTION_VIEW, uri).setPackage(BILI));
                    }
                }
                if (d == null) d = settle(before, 500);
                boolean found = BILI.equals(foreground()) && d.contains(keyword);
                if (found) d = ensureTab(d, "综合", t);
                JSONObject result = biliLoginNote(withScreen(found ? ok("observed", "哔哩哔哩已显示「" + keyword + "」的搜索结果")
                        : fail("unconfirmed", "已发出搜索，但没在屏幕上核对到「" + keyword + "」"), d), dismissed);
                JSONObject up = found ? BiliPage.upUploads(d, keyword) : null;
                if (up != null) result.put("up", up);
                return result;
            }
        });

        tools.add(new Base("tencent_video_search") {
            @Override public String describe(JSONObject a, Turn t) { return "在云视听极光搜索「" + a.optString("keyword") + "」"; }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String keyword = a.getString("keyword").trim();
                if (keyword.isEmpty()) return fail("rejected", "keyword 不能为空");
                if (launchIntent(JIGUANG) == null)
                    return fail("unavailable", "电视上没有云视听极光（腾讯视频电视版），可以先调用 install_app 安装");
                // the keyword as it is: 云视听极光's SearchActivity does not decode it, and its box holds 10 characters
                // (2026-11-10 10:41: the percent-encoded keyword arrived as 「%E7%8B%82%」 and the search never found anything)
                String key = PlayerPages.tencentSearchKey(keyword);
                if (key.isEmpty()) return fail("rejected", "keyword 里没有可以搜的字");
                Uri uri = Uri.parse(PlayerPages.tencentSearchLink(keyword));
                long before = Eyes.changeCount();
                start(new Intent(JIGUANG_OPEN, uri).setPackage(JIGUANG));
                long began = SystemClock.uptimeMillis(), end = began + 20000;
                ScreenDigest d = null;
                boolean onSearch = false, early = false;
                while (SystemClock.uptimeMillis() < end) {
                    if (t.cancelled()) throw new InterruptedException();
                    Thread.sleep(500);
                    // 它自己的页面 Eyes 收不到窗口事件，mFocusedApp 说的是对的；读出搜索词是第二条路
                    if (jiguangPage("SearchActivity")) { onSearch = true; break; }
                    if (!JIGUANG.equals(foreground())) continue;
                    d = read();
                    if (d.contains(key) && d.elements.size() > 3) break;
                    // 云视听极光把搜索页画在自己手里，Eyes 和读屏都读不到它（2026-11-10 10:48 / 13:13：两次都跑满 20 秒，
                    // 回答「已发出搜索，但没看到搜索页」）。6 秒后还停在它自己的启动/跳转页上（不是搜索页、也读不到
                    // 关键词），就不再干等，按「搜索已发出、页面自己会加载」回答，让模型看图（2026-11-18 audit；审查
                    // 指出用 Eyes 的全局事件计数不行——任何应用的事件都会让它变，这里改看前台窗口的类名）
                    if (SystemClock.uptimeMillis() - began > 6000) {
                        String cls = FrontApp.cls(adb.call(LocalAdb::focusedApp));
                        String simple = cls.substring(cls.lastIndexOf('.') + 1);
                        if (simple.contains("Start") || simple.contains("Splash") || simple.contains("Open") || simple.isEmpty()) { early = true; break; }
                    }
                }
                if (d == null) d = settle(before, 500);
                // left early: what the screen shows cannot be told, so neither 「打开了」 nor 「没打开」 — a neutral word to
                // the model and screen_look (2026-11-18 review: the search page may still open a few seconds later)
                if (early) return withScreen(ok("requested", "搜索已交给云视听极光，它的页面还在加载：这个应用的界面读不出文字，"
                        + "过几秒用 screen_look 看一眼搜出来的是什么；要直接打开某一部用 tencent_video_open"), d);
                boolean found = onSearch || (JIGUANG.equals(foreground()) && d.contains(key));
                return withScreen(found ? ok("observed", "云视听极光的搜索页已打开，搜的是「" + keyword
                        + "」。这个应用的界面读不出文字：要看结果用 screen_look，要直接打开某一部用 tencent_video_open")
                        : fail("unconfirmed", "已发出搜索，但没看到云视听极光的搜索页：看屏幕上现在是什么，不行就 open_app 打开云视听极光自己搜"), d);
            }
        });

        tools.add(new Base("tencent_video_open") {
            @Override public String describe(JSONObject a, Turn t) {
                int n = a.optInt("episode", 0);
                if (n >= 1) return "在云视听极光播放《" + a.optString("title") + "》第 " + n + " 集";
                return play(a) ? "在云视听极光播放《" + a.optString("title") + "》"
                        : "在云视听极光打开《" + a.optString("title") + "》的详情页";
            }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String title = a.optString("title").trim().replaceAll("^《|》$", "");
                if (title.isEmpty()) return fail("rejected", "title 不能为空");
                if (launchIntent(JIGUANG) == null)
                    return fail("unavailable", "电视上没有云视听极光（腾讯视频电视版），可以先调用 install_app 安装");
                TencentVideo.Result found;
                // The quick command's lookup (strict) stands in front of the model: a search that hangs must not hold
                // every 「我想看…」 for 8 s before the model even starts. The answer comes in 0.2–0.6 s; after 3 s the
                // request goes to the model, as when the search is not sure (lead, 2026-11-11).
                int wait = flag(a, "strict") ? 3000 : 8000;
                try {
                    found = TencentVideo.pick(TENCENT_FEED.post(TencentVideo.SEARCH, TencentVideo.body(title), wait),
                            title, a.optString("kind").trim(), a.optInt("year", 0));
                } catch (IOException | JSONException e) {
                    return fail("error", "腾讯视频的搜索没连上：可以用 tencent_video_search 打开搜索页自己找");
                }
                // strict 只有快命令会带（contract 里没有这个参数）：搜索说得不死就一步都不做，交回模型
                // ——「我想看别无选择」不能变成一次 bili_search 或一首林俊杰的歌（2026-11-11）
                if (flag(a, "strict") && !TencentVideo.sure(found, title, !a.optString("kind").trim().isEmpty()))
                    return fail("not_sure", "不能确定「" + title + "」是腾讯视频里的哪一部");
                if (found.is(TencentVideo.NONE))
                    return fail("not_found", "腾讯视频没搜到《" + title + "》" + TencentVideo.near(found.others, 3));
                if (found.is(TencentVideo.ELSEWHERE)) return notHere(found);
                // 要看的是第几集：选集列表给出这一集自己的编号，链接直接放它；还没上映的片子页面上只有预告
                int episode = a.optInt("episode", 0);
                if (episode >= 1 && !found.show.upcoming) return openEpisode(found, episode, t, flag(a, "strict"));
                return open(found, play(a), t);
            }

            private boolean play(JSONObject a) { return !a.has("play") || flag(a, "play"); }

            /** 电脑上的静音测试在待机的电视上不打开视频：会点亮屏幕、会放广告。null 表示可以继续。 */
            private JSONObject videoAsleep(Turn t) throws JSONException {
                if (!"test".equals(t.source())) return null;
                android.os.PowerManager p = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                return p != null && !p.isInteractive()
                        ? fail("rejected", "这是电脑上的静音测试，电视在待机：不点亮屏幕打开视频，如实说这一步测试时跳过。") : null;
            }

            /** 腾讯视频没有这部片子：它在哪个平台、电视上装没装那个平台的电视版，一句说完，别的一步都不做。 */
            private JSONObject notHere(TencentVideo.Result found) throws JSONException {
                TencentVideo.Show s = found.show;
                StringBuilder said = new StringBuilder("腾讯视频（云视听极光）没有《" + s.title + "》" + TencentVideo.brief(s)
                        + "：它在" + join(found.platforms) + (s.episodes > 0 ? "，共 " + s.episodes + " 集" : "") + "。");
                JSONArray platforms = new JSONArray();
                for (String p : found.platforms) {
                    platforms.put(p);
                    said.append(onTv(p));
                }
                return fail("not_here", said.toString()).put("platforms", platforms)
                        .put("note", "把上面这句话告诉用户就行：不要自己安装应用，也不要打开别的应用去找；用户说要装时再用 install_app");
            }

            private String join(List<String> names) {
                StringBuilder b = new StringBuilder();
                for (String n : names) { if (b.length() > 0) b.append('、'); b.append(n); }
                return b.toString();
            }

            /** 那个平台在电视上是哪个应用、装没装（在 apps() 的名字里找）；电视上没有对应应用的平台不说这句。 */
            private String onTv(String platform) {
                String app = PLATFORM_APPS.get(platform);
                if (app == null) return "";
                for (String label : apps().keySet()) {
                    if (!label.contains(app)) continue;
                    return "电视上有" + app + ("哔哩哔哩".equals(app) ? "，可以用 bili_search 找" : "") + "。";
                }
                return "电视上没装" + app + (app.equals(platform) ? "" : "（" + platform + "的电视版）") + "。";
            }

            /** 发链接，然后等前台真的是那一页（action=7 是播放页，action=1 是详情页）。 */
            private boolean launch(String link, String page, Turn t) throws Exception {
                start(new Intent(JIGUANG_OPEN, Uri.parse(link)).setPackage(JIGUANG));
                long end = SystemClock.uptimeMillis() + 12000;
                while (SystemClock.uptimeMillis() < end) {
                    if (t.cancelled()) throw new InterruptedException();
                    if (jiguangPage(page)) return true;
                    Thread.sleep(300);
                }
                return false;
            }

            /**
             * 「我想看别无选择第3集」（2026-11-11）：选集列表给出这一集自己的编号，链接直接放这一集。
             *
             * <p>strict 是快命令带来的：这一集也要找得到，找不到（或者选集列表连不上）就一步都不做，交回模型。
             * 没有这一集时什么都不打开——把一部片子打开在它记住的那一集上，比说没有更糟。
             */
            private JSONObject openEpisode(TencentVideo.Result found, int n, Turn t, boolean strict) throws Exception {
                JSONObject asleep = videoAsleep(t);
                if (asleep != null) return asleep;
                TencentVideo.Show s = found.show;
                TencentVideo.Episode episode;
                // the largest episode number on the pages that were read: the search's own count takes the extras for
                // episodes (繁花: 36 with its six specials, 30 episodes) (lead, 2026-11-11)
                int[] last = {0};
                try {
                    episode = findEpisode(s.cid, n, last);
                } catch (IOException | JSONException e) {
                    return strict ? notSureEpisode(s, n)
                            : fail("error", "腾讯视频的选集没连上：可以先打开这部片子，再用遥控器选集");
                }
                if (episode == null)
                    return strict ? notSureEpisode(s, n)
                            : fail("no_episode", "《" + s.title + "》在腾讯视频没有第 " + n + " 集"
                                    + (last[0] > 0 ? "（一共 " + last[0] + " 集）" : ""))
                                    // calibration 2026-11-11: told there is no episode 40, the model played episode 30
                                    // instead, twice of three — the user asked for neither (lead)
                                    .put("note", "把上面这句话告诉用户就行：不要改放别的集，也不要去别处搜；用户说了要看哪一集再打开");
                android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                String lit = power != null && !power.isInteractive() && ScreenWake.ensureOn(context, t::cancelled)
                        ? "（先点亮了屏幕）" : "";
                if (!launch(TencentVideo.playLink(s.cid, episode.vid), "TVPlayerActivity", t))
                    return withLit(fail("unconfirmed", "已请求云视听极光打开《" + s.title + "》第 " + n
                            + " 集，前台还不是它的播放页"), lit);
                // what is on is this episode: media_now_playing has no session of this app to read the title from
                openedTitle = s.title + " 第 " + n + " 集";
                openedAt = SystemClock.elapsedRealtime();
                return withLit(ok("observed", "已在云视听极光播放《" + s.title + "》第 " + n + " 集"
                        + (episode.vip ? "；这一集要会员才能看全" : "；不是会员的话正片前有一两分钟广告")), lit);
            }

            /** 第 n 集在哪一页上：先问第一页，再照 {@link TencentVideo.Episodes#contextsFor} 给的凭据一页一页问。 */
            private TencentVideo.Episode findEpisode(String cid, int n, int[] last) throws IOException, JSONException {
                TencentVideo.Episodes page = TencentVideo.episodes(
                        EPISODE_FEED.post(TencentVideo.EPISODES, TencentVideo.episodesBody(cid, ""), 6000));
                last[0] = Math.max(last[0], page.last());
                TencentVideo.Episode hit = page.find(n);
                List<String> contexts = page.contextsFor(n);
                for (int i = 0; hit == null && i < contexts.size(); i++) {
                    TencentVideo.Episodes next = TencentVideo.episodes(EPISODE_FEED.post(TencentVideo.EPISODES,
                            TencentVideo.episodesBody(cid, contexts.get(i)), 6000));
                    last[0] = Math.max(last[0], next.last());
                    hit = next.find(n);
                }
                return hit;
            }

            /** strict：这一集没找着，交给模型（它可以打开这部片子自己选集）。 */
            private JSONObject notSureEpisode(TencentVideo.Show s, int n) throws JSONException {
                return fail("not_sure", "不能确定《" + s.title + "》第 " + n + " 集在腾讯视频的选集里");
            }

            /** 找到了：待机时先点亮屏幕，发链接，然后等前台真的是它的播放页（action=7）或详情页（action=1）。 */
            private JSONObject open(TencentVideo.Result found, boolean play, Turn t) throws Exception {
                JSONObject asleep = videoAsleep(t);
                if (asleep != null) return asleep;
                android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                String lit = power != null && !power.isInteractive() && ScreenWake.ensureOn(context, t::cancelled)
                        ? "（先点亮了屏幕）" : "";
                TencentVideo.Show s = found.show;
                // 还没上映的：页面上只有预告，没有正片可放，进详情页（可以预约），别去等一个不会来的播放页
                boolean soon = s.upcoming, playing = play && !soon;
                String page = playing ? "TVPlayerActivity" : "NormalOnePageActivity";
                boolean arrived = launch(playing ? TencentVideo.playLink(s.cid) : TencentVideo.detailLink(s.cid), page, t);
                String said = "《" + s.title + "》" + TencentVideo.tag(s);
                if (!arrived)
                    return withLit(fail("unconfirmed", "已请求云视听极光打开《" + s.title + "》，前台还不是它的"
                            + (playing ? "播放页" : "详情页")), lit);
                // what is on is this show: media_now_playing has no session of this app to read the title from
                openedTitle = s.title;
                openedAt = SystemClock.elapsedRealtime();
                JSONObject r = soon
                        ? ok("observed", "《" + s.title + "》" + TencentVideo.brief(s)
                                + "还没上线：已在云视听极光打开它的页面，现在只有预告，可以预约")
                        : ok("observed", playing
                        ? "已在云视听极光播放" + said + "；不是会员的话正片前有一两分钟广告"
                        : "已在云视听极光打开" + said + "的详情页，按确认键全屏播放");
                String also = TencentVideo.also(found.others, 2);
                if (also.isEmpty()) return withLit(r, lit);
                JSONArray others = new JSONArray();
                for (int i = 0; i < found.others.size() && i < 2; i++) {
                    TencentVideo.Show o = found.others.get(i);
                    others.put(new JSONObject().put("title", o.title).put("type", o.type).put("year", o.year).put("cid", o.cid));
                }
                return withLit(r.put("message", r.optString("message") + also).put("others", others), lit);
            }
        });

        tools.add(new Base("xiaoai_ask") {
            @Override public Gate gate(JSONObject a, Turn t) {
                String q = a.optString("query");
                // a check from the PC is silent: XiaoAi would act on the sentence and say its answer aloud
                if ("test".equals(t.source())) return Gate.deny("这是电脑上的静音测试：不转交小爱（小爱会出声、会动手），如实说这一步测试时跳过。");
                if ("xiaoai".equals(t.source()) && same(q, t.utterance()))
                    return Gate.deny("小爱刚才已经表示做不到这句话，原样转回给小爱没有用。换一种办法完成，或者如实告诉用户。");
                return Sensitive.securityDevice(q) ? Gate.sensitive("让小爱执行：" + q) : Gate.act();
            }
            @Override public String describe(JSONObject a, Turn t) { return "交给小爱：" + a.optString("query"); }
            @Override public JSONObject run(JSONObject a, Turn t) throws Exception {
                String q = a.getString("query");
                CountDownLatch done = new CountDownLatch(1);
                String[] status = {"未提交"};
                AgentEngine.noteXiaoaiAsk(q);
                Legacy.askXiaoai(context, q, m -> { status[0] = m; done.countDown(); });
                if (!done.await(4, TimeUnit.SECONDS)) return fail("unknown", "小爱提交状态未知，不要重复提交");
                boolean ok = status[0].startsWith("已转交");
                return (ok ? ok("submitted", "已交给小爱，小爱会自己回应；没法确认设备是否执行") : fail("error", status[0]));
            }
            private boolean same(String a, String b) {
                String x = norm(a).replaceAll("^(小爱同学|小爱)", ""), y = norm(b == null ? "" : b).replaceAll("^(小爱同学|小爱)", "");
                if (x.isEmpty() || y.isEmpty()) return false;
                return x.equals(y) || Grounding.distance(x, y, Math.max(2, y.length() / 5)) <= Math.max(2, y.length() / 5);
            }
        });
    }

    private ScreenDigest.Element safeElement(int id) {
        ScreenDigest d = last;
        return d == null ? null : d.element(id);
    }

    /** The list to scroll when none was named: for up/down never a pager (a ViewPager "scrolls" to the next tab,
     * seen on Bilibili's UP page: 投稿 → 收藏), for left/right prefer one; the largest candidate wins. */
    static ScreenDigest.Element scrollTarget(ScreenDigest d, boolean vertical) {
        ScreenDigest.Element best = null;
        long bestArea = -1;
        for (ScreenDigest.Element e : d.elements) {
            if (!e.node.scrollable) continue;
            boolean pager = e.node.cls.contains("ViewPager") || e.node.cls.contains("HorizontalScrollView")
                    || e.node.cls.contains("TabLayout") || e.node.cls.contains("Banner");
            if (vertical == pager) continue;
            long area = (long) (e.node.right - e.node.left) * (e.node.bottom - e.node.top);
            if (area > bestArea) { best = e; bestArea = area; }
        }
        return best;
    }
}
