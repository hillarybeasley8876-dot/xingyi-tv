package xyz.erapi0neer.xiaoaibridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import xyz.erapi0neer.xingyi.MusicIntent;
import xyz.erapi0neer.xingyi.agent.AdbUnavailable;
import xyz.erapi0neer.xingyi.agent.Replies;
import xyz.erapi0neer.xingyi.agent.tv.AgentEngine;
import xyz.erapi0neer.xingyi.agent.tv.Eyes;

/**
 * Voice sync with XiaoAi, done on the TV itself: XiaoAi keeps the wake word, the microphone and everything it can
 * answer. This service follows each request in XiaoAi's log (read from logd) and steps in twice:
 * song requests go straight to NetEase, and when XiaoAi's own answer is an "I can't", 星仪's agent (which runs in
 * this process) takes the request: it acts with tools or answers, and speaks through XiaoAi's voice.
 */
public final class BridgeService extends Service {
    static final String PKG = "xyz.erapi0neer.xiaoaibridge";
    static final String SIMULATE = PKG + ".SIMULATE", ASK = PKG + ".ASK", LINE = PKG + ".LINE";
    static final String STATUS = PKG + ".STATUS", PROVISION = PKG + ".PROVISION", MODE = PKG + ".MODE";
    /** --es model MiniMax-M78: the model every route in private config.json should use (RouteOverride). */
    static final String ROUTE = PKG + ".ROUTE";
    private static final Set<String> TAGS = new HashSet<>(Arrays.asList(XiaoaiLog.TAG_BAR, XiaoaiLog.TAG_ASR,
            XiaoaiLog.TAG_INST, XiaoaiLog.TAG_NLU, XiaoaiLog.TAG_PLAYER, XiaoaiLog.TAG_MEDIA, XiaoaiLog.TAG_EXTERNAL));

    /** Song requests (they may wait a moment for XiaoAi's own classification). */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Model answers, so a slow answer never delays a song request. */
    private final ExecutorService answers = Executors.newSingleThreadExecutor();
    /** The adb watch (and its guard) runs here: it waits on sockets and must not hold up anything else. */
    private final ExecutorService watch = Executors.newSingleThreadExecutor();
    /** Closes XiaoAi's own result page after a hand-over (see closeXiaoaiPage). */
    private final ExecutorService pages = Executors.newSingleThreadExecutor();
    /** Watches for audio XiaoAi starts on its own after 星仪 took the request (stopXiaoaiAudio). */
    private final ExecutorService audioWatch = Executors.newSingleThreadExecutor();
    static final String XIAOAI_VIDEO_PAGE = "com.xiaomi.voicecontrol.video.VideoActivity";
    /**
     * 星仪接管模式 (the user, 2026-11-05: 「两个小爱语音老打架」— XiaoAi answered, got cut, then 星仪 spoke in the same
     * voice). "all": every request goes to 星仪, XiaoAi only listens, acts on its basic functions and lends its voice;
     * "refusals": the earlier behaviour (星仪 only for what XiaoAi cannot do). Kept in files/takeover.txt.
     */
    private volatile boolean takeoverAll = true;
    private ContentObserver adbSetting;
    private BroadcastReceiver screen;
    private volatile boolean stopped;
    private volatile LogdReader logd;
    private volatile String readerState = "starting";
    private volatile long linesRead;
    private long startedAt;
    private BridgeLog log;
    private Takeover takeover;
    private XiaoaiLog xiaoai;
    private volatile Llm llm;
    private BroadcastReceiver control;

    @Override public void onCreate() {
        super.onCreate();
        log = new BridgeLog(this);
        takeoverAll = !"refusals".equals(readMode());
        takeover = new Takeover(this, log, new NeteaseSearch());
        llm = Llm.load(this);
        xiaoai = new XiaoaiLog(new XiaoaiLog.Listener() {
            @Override public void onRequest(XiaoaiLog.Dialog d) { route(d); }
            @Override public void onInstruction(XiaoaiLog.Dialog d, String name, String text) { onAnswer(d, text); }
            @Override public void onPlayer(boolean start, boolean manual) { xyz.erapi0neer.xingyi.VendorSpeech.player(start, manual); }
            @Override public void onTtsStart(XiaoaiLog.Dialog d, long millis) {
                if (System.currentTimeMillis() >= d.silenceUntil) return;
                // only XiaoAi's own answer is cut; speech requested through PLAY_TTS (ours, Xingyi's) plays
                XiaoaiLog.Speech sp = xiaoai.lastSpeech();
                if (sp != null && sp.external && millis - sp.at < 2000) return;
                Xiaoai.stopSpeaking(BridgeService.this);
            }
        });
        startForeground(1, notification());
        startedAt = System.currentTimeMillis();
        control = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { onControl(i); }
        };
        IntentFilter filter = new IntentFilter(SIMULATE);
        for (String a : new String[]{ASK, LINE, STATUS, PROVISION, MODE, ROUTE}) filter.addAction(a);
        registerReceiver(control, filter, "android.permission.DUMP", null);
        Thread reader = new Thread(this::readLoop, "xiaoai-log");
        reader.setDaemon(true);
        reader.start();
        AgentEngine.get(this).warmUp();
        // The firmware switches ADB debugging off at standby: react the moment the setting changes (the heartbeat
        // covers a missed change), and let a task that finds the listener refusing ask for a repair on the spot.
        adbSetting = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                watch.execute(() -> {
                    try { AdbWatch.beat(BridgeService.this); } catch (RuntimeException ignored) { }
                });
            }
        };
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbSetting);
        AdbUnavailable.healer = () -> AdbWatch.healNow(getApplicationContext());
        // screen off/on go into the same record: they show how long the TV had been in standby before it rebooted by itself
        screen = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String what = Intent.ACTION_SCREEN_OFF.equals(i.getAction()) ? "screen off" : "screen on";
                // 星仪's quiet card (today's bookings, the 随手记, this hour's habit); it decides itself whether to show
                if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) AgentEngine.get(BridgeService.this).screenOn();
                watch.execute(() -> {
                    try { AdbWatch.note(BridgeService.this, what); } catch (RuntimeException ignored) { }
                });
            }
        };
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, screenFilter);
        log.add("接力服务启动 · 模型 " + (llm == null ? "未配置" : llm.model));
        applyXiaoaiOverlays();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        BootReceiver.arm(this);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        stopped = true;
        LogdReader r = logd;
        if (r != null) r.close();
        unregisterReceiver(control);
        if (adbSetting != null) getContentResolver().unregisterContentObserver(adbSetting);
        if (screen != null) unregisterReceiver(screen);
        AdbUnavailable.healer = null;
        watch.shutdownNow();
        worker.shutdownNow();
        answers.shutdownNow();
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("bridge", "星仪后台", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, "bridge").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("星仪后台").setContentText("点歌交给网易云，小爱做不到的交给星仪").build();
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** XiaoAi's partial recognition (isFinal = false): the user is still speaking, and in takeover mode 星仪 gets the
     * request a second or two from now, so its model connection is opened already (ModelRouter.warm). */
    private void listening(String line) {
        if (!takeoverAll || line == null || !line.contains("isFinal = false")) return;
        AgentEngine engine = AgentEngine.get(this);
        engine.userSpeaking();
        // and the words so far on the panel: with XiaoAi's own card hidden in takeover mode there was nothing on the
        // screen while the user spoke (2026-11-12 01:19: 「你没发现你的那个面板跳不出来了吗就是小爱原本的面板」)
        AsrLine a = AsrLine.parse(line);
        if (a != null && a.text != null && !a.text.isEmpty()) engine.listening(a.text);
    }

    private void readLoop() {
        long backoff = 1000;
        while (!stopped) {
            if (!granted("android.permission.READ_LOGS")) {
                readerState = "等待 READ_LOGS 授权";
                SystemClock.sleep(15000);
                continue;
            }
            LogdReader reader = new LogdReader();
            logd = reader;
            try {
                readerState = "正在听小爱";
                reader.stream((millis, uid, priority, tag, message) -> {
                    linesRead++;
                    if (!TAGS.contains(tag)) return;
                    // `tail=1` and reconnects replay old entries
                    if (millis < startedAt - 1500) return;
                    if (uid != 1000) {
                        if (XiaoaiLog.TAG_BAR.equals(tag)) log.add("忽略非小爱进程写的识别行 uid=" + uid);
                        return;
                    }
                    if (XiaoaiLog.TAG_ASR.equals(tag)) listening(message);
                    xiaoai.entry(millis, tag, message);
                });
                readerState = "logd 关闭了连接，稍后重连";
            } catch (IOException | RuntimeException e) {
                readerState = "读取 logd 失败：" + e;
            } finally {
                reader.close();
                logd = null;
            }
            if (!stopped) {
                SystemClock.sleep(backoff);
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    /** A new XiaoAi request. Order: our own hand-off to XiaoAi coming back, an answer 星仪 is waiting for
     * (确认/取消 or a clarifying question), a stop while it works, a request that names 星仪, then the song path. */
    private void route(XiaoaiLog.Dialog d) {
        if (AgentEngine.isOwnXiaoaiAsk(d.text)) {
            log.add("星仪交给小爱的话，不接手 ← " + d.text);
            return;
        }
        latest = d;
        // 「小爱走小爱」/「全部交给星仪」: the switch between 点名 and 星仪 taking everything, said to XiaoAi (2026-11-06)
        String mode = Addressing.modeRequest(d.text);
        if (mode != null) {
            if (d.taken.compareAndSet(false, true)) {
                d.silenceUntil = System.currentTimeMillis() + 10000;
                Xiaoai.stopSpeaking(this);
                long at = SystemClock.uptimeMillis();
                setMode(mode);
                answers.execute(() -> {
                    closeXiaoaiPage(d, at);
                    SystemClock.sleep(700);
                    Xiaoai.say(this, "all".equals(mode) ? "好的，以后对小爱说的话都交给星仪。"
                            : "好的，以后小爱的事小爱做，叫星仪的才交给星仪。");
                });
            }
            return;
        }
        org.json.JSONObject agent = AgentEngine.get(this).state();
        boolean waiting = !agent.optString("awaiting").isEmpty();
        boolean stopping = agent.optBoolean("busy") && Replies.stop(d.text);
        // In takeover mode an answer to 星仪 or a request naming 星仪 goes over with what XiaoAi did for it, like any
        // other request (handOver): straight over, 「星仪，音量调大一点」 was turned up by XiaoAi and again by 星仪
        // (2026-11-05 audit). Only a stop goes at once.
        if (stopping || (waiting && !takeoverAll)) {
            if (d.taken.compareAndSet(false, true)) answers.execute(() -> giveToAgent(d, waiting ? "回答星仪" : "叫停星仪"));
            return;
        }
        if (!takeoverAll && Addressing.namesXingyi(d.text) && MusicIntent.parse(d.text) == null) {
            // 点名模式 (「小爱走小爱 星仪走星仪」, 2026-11-06): XiaoAi heard it first and may already be acting on it, so it goes
            // over the way every request does in takeover mode: with what XiaoAi did, so nothing is done twice.
            if (d.taken.compareAndSet(false, true)) {
                AgentEngine.get(this).userSpeaking();
                try {
                    android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                    AgentEngine.get(this).volumeAtRequest(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
                } catch (RuntimeException ignored) { }
                d.silenceUntil = System.currentTimeMillis() + 12000;
                Xiaoai.stopSpeaking(this);
                if (!waiting) AgentEngine.get(this).preview(d.text);
                answers.execute(() -> handOver(d, "点名星仪"));
            }
            return;
        }
        if (takeoverAll) {
            AgentEngine.get(this).userSpeaking(); // in case no partial result came first (at most every 20 s anyway)
            // before XiaoAi's own action lands: lets 星仪 say the volume XiaoAi left behind, not the one before it
            try {
                android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                AgentEngine.get(this).volumeAtRequest(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
            } catch (RuntimeException ignored) { }
            if (!waiting && MusicIntent.parse(d.text) != null) {
                // a song while 星仪 works on something else: the user moved on, so that task ends quietly
                if (agent.optBoolean("busy")) AgentEngine.get(this).supersedeQuietly();
                // the panel for a song too: what the user said is on the screen while NetEase starts (2026-11-11)
                AgentEngine.get(this).songAsked(d.text, "网易云点歌");
                // songs keep the direct NetEase path (fast, no model); what it does not take goes to 星仪
                worker.execute(() -> {
                    handle(d, "小爱");
                    if (d.taken.compareAndSet(false, true)) answers.execute(() -> handOver(d, "接管"));
                });
                return;
            }
            if (d.taken.compareAndSet(false, true)) {
                d.silenceUntil = System.currentTimeMillis() + 12000;
                Xiaoai.stopSpeaking(this);
                // 星仪's panel now, not after the ≤0.9 s wait for XiaoAi's instructions: no empty screen in between
                if (!waiting) AgentEngine.get(this).preview(d.text);
                answers.execute(() -> handOver(d, waiting ? "回答星仪" : "接管"));
            }
            return;
        }
        if (Takeover.namesBili(d.text) && MusicIntent.parse(d.text) == null) {
            if (d.taken.compareAndSet(false, true)) answers.execute(() -> giveToAgent(d, "B站的事交给星仪"));
            return;
        }
        // "继续" with an interrupted 星仪 task: XiaoAi would take it as "resume playback" and never refuse it.
        if (Replies.resume(d.text) && AgentEngine.get(this).resumable(10 * 60_000L)) {
            if (d.taken.compareAndSet(false, true)) answers.execute(() -> giveToAgent(d, "接着做星仪的任务"));
            return;
        }
        worker.execute(() -> handle(d, "小爱"));
    }

    /** The newest XiaoAi request (not counting 星仪's own hand-offs to XiaoAi). */
    private volatile XiaoaiLog.Dialog latest;

    /** A request that reaches 星仪 late — the song path hands a song over after its ~20 s check fails — must not cancel
     * whatever the user asked for since (2026-11-05 audit). Hand-overs at once are always the newest request. */
    private boolean superseded(XiaoaiLog.Dialog d, String why) {
        XiaoaiLog.Dialog now = latest;
        if (now == null || now == d) return false;
        log.add("已经有更新的请求，这句不再交给星仪（" + why + "）← " + d.text);
        return true;
    }

    /** XiaoAi's own reply to this request is cut; 星仪 speaks for itself through XiaoAi's voice (external TTS is not cut). */
    private void giveToAgent(XiaoaiLog.Dialog d, String why) {
        if (superseded(d, why)) return;
        d.silenceUntil = System.currentTimeMillis() + 8000;
        Xiaoai.stopSpeaking(this);
        long at = SystemClock.uptimeMillis();
        String routed = AgentEngine.get(this).submit(d.text, "xiaoai");
        log.add("交给星仪(" + why + ") → " + routed + " ← " + d.text);
        closeXiaoaiPage(d, at);
    }

    /**
     * 星仪接管模式: XiaoAi's own instructions come within about half a second of the text; wait for them (at most 0.9 s),
     * then hand the request to 星仪 with what XiaoAi did and meant to say, so nothing is done twice and only 星仪 speaks.
     */
    private void handOver(XiaoaiLog.Dialog d, String why) {
        if (superseded(d, why)) return;
        // XiaoAi's instructions only matter for a relative action it may already have taken (a second 调大 / 下一首 /
        // 返回 would double it). An absolute quick command (音量调到20, 暂停, HDMI2, 回到桌面…) goes over at once.
        long at = SystemClock.uptimeMillis(), end = at + (Takeover.absoluteQuick(d.text) ? 0 : 900);
        while (SystemClock.uptimeMillis() < end && !d.names.contains("Nlp.FinishAnswer") && !d.names.contains("Dialog.Finish"))
            SystemClock.sleep(50);
        d.silenceUntil = System.currentTimeMillis() + 10000;
        Xiaoai.stopSpeaking(this);
        Set<String> did = Takeover.xiaoaiDid(d.names);
        String note = Takeover.xiaoaiNote(did, d.answers);
        boolean ownAudio = Takeover.ownAudio(did);
        if (ownAudio) note += "；小爱自己要放的音频会被接力停掉，用户要听什么由你来放";
        String routed = AgentEngine.get(this).submit(d.text, "xiaoai", note, did);
        log.add("交给星仪(" + why + ") → " + routed + " ← " + d.text + " · " + note);
        closeXiaoaiPage(d, at);
        // 「小爱只是唤醒和语音输入，操作全部给星仪」 (2026-11-06): what XiaoAi starts playing on its own is stopped
        if (ownAudio) stopXiaoaiAudio(d);
    }

    /**
     * XiaoAi plays audio of its own for some requests (news for 「最近有什么新动向」); once 星仪 has the request that is
     * XiaoAi acting on its own. STOPSPEAKING only stops XiaoAi's speech (StopSpeakingHandler → stopTts), so: every media
     * player that starts within 10 s of the hand-over, and is not 星仪's own speech through XiaoAi's voice, loses the
     * audio focus to the bridge for a moment — XiaoAi's player stops as it does when NetEase starts — and XiaoAi's
     * player card is closed when it is the active window. Public Android API only: playback configurations and focus.
     */
    private void stopXiaoaiAudio(XiaoaiLog.Dialog d) {
        audioWatch.execute(() -> {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null || android.os.Build.VERSION.SDK_INT < 26) return;
            java.util.List<android.media.AudioPlaybackConfiguration> seen = new java.util.ArrayList<>(am.getActivePlaybackConfigurations());
            long end = SystemClock.uptimeMillis() + 10_000;
            int cut = 0;
            while (SystemClock.uptimeMillis() < end && !stopped && cut < 3) {
                SystemClock.sleep(150);
                for (android.media.AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations()) {
                    if (seen.contains(c)) continue;
                    seen.add(c);
                    android.media.AudioAttributes a = c.getAudioAttributes();
                    if (a == null || a.getUsage() != android.media.AudioAttributes.USAGE_MEDIA) continue;
                    // 星仪 speaks through XiaoAi's player too: a player that starts right after our own speech is ours
                    if (SystemClock.uptimeMillis() - xyz.erapi0neer.xingyi.VendorSpeech.lastSpokeAt < 4000) continue;
                    android.media.AudioFocusRequest req = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setOnAudioFocusChangeListener(change -> { }).build();
                    int granted = am.requestAudioFocus(req);
                    SystemClock.sleep(400);
                    am.abandonAudioFocusRequest(req);
                    cut++;
                    log.add("  停掉了小爱自己放的音频（音频焦点" + (granted == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "拿到了" : "没拿到") + "）");
                    Eyes eyes = Eyes.get();
                    if (eyes != null && "com.xiaomi.voicecontrol".equals(eyes.activeWindowPackage())
                            && eyes.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                        log.add("  关掉了小爱的播放卡片");
                }
            }
        });
    }

    /** all: 星仪 takes every request XiaoAi hears; refusals (点名): XiaoAi keeps its own, 星仪 gets the ones that call it by
     * name and the ones XiaoAi cannot do. */
    private void setMode(String mode) {
        takeoverAll = "all".equals(mode);
        writeMode(mode);
        log.add("模式：" + (takeoverAll ? "星仪接管全部请求" : "点名模式：叫星仪的和小爱做不到的交给星仪"));
        applyXiaoaiOverlays();
    }

    /**
     * 「小爱的原始卡片还是没ban啊」 (2026-11-08): while 星仪 takes every request, XiaoAi's own windows (its listening bar
     * and answer cards) stay hidden; in 点名模式 XiaoAi answers for itself again and shows them. Set at every start
     * too, in case the firmware put the op back. Off the main thread: the local ADB link takes a moment.
     */
    private void applyXiaoaiOverlays() {
        final boolean shown = !takeoverAll;
        watch.execute(() -> {
            xyz.erapi0neer.xingyi.LocalAdb adb = new xyz.erapi0neer.xingyi.LocalAdb(this);
            try {
                adb.xiaoaiOverlays(shown);
                log.add(shown ? "小爱自己的卡片：显示（点名模式）" : "小爱自己的卡片：不显示（星仪接管全部请求）");
            } catch (Exception e) {
                log.add("小爱自己的卡片没能" + (shown ? "恢复显示" : "藏起来") + "：" + e.getMessage());
            } finally {
                adb.cancel();
            }
        });
    }

    private String readMode() {
        try (java.io.FileInputStream in = new java.io.FileInputStream(new File(getFilesDir(), "takeover.txt"))) {
            byte[] b = new byte[32];
            int n = in.read(b);
            return n <= 0 ? "all" : new String(b, 0, n, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "all";
        }
    }

    private void writeMode(String mode) {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "takeover.txt"))) {
            out.write(mode.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    /**
     * XiaoAi answers what it cannot do with its own video page ("没有找到…，为您推荐以下相关内容"), which stays in
     * front for the seconds 星仪 needs to plan: the user sees unrelated recommendations (2026-11-05). When this very
     * request made XiaoAi open that page (Video.ShowSearchPage) and the page comes up around the hand-over, Back
     * closes it, once. A page that was already up before (the user's own context) is left alone.
     */
    private void closeXiaoaiPage(XiaoaiLog.Dialog d, long handedAt) {
        pages.execute(() -> {
            long end = SystemClock.uptimeMillis() + 5000, cardUntil = SystemClock.uptimeMillis() + 3000;
            boolean covered = false;
            while (SystemClock.uptimeMillis() < end && !stopped) {
                if (d.names.contains("Video.ShowSearchPage") && XIAOAI_VIDEO_PAGE.equals(Eyes.foregroundClass())
                        && Eyes.foregroundClassAt() >= handedAt - 1500) {
                    Eyes eyes = Eyes.get();
                    if (eyes == null) return;
                    // Eyes' foreground is the last window event it saw, and 云视听极光 sends none. Since 2026-11-11
                    // 「我想看 X」 opens its player about a second after the hand-over — on top of this page — and a
                    // BACK pressed then would close the player, not the page. So the window that is active right now
                    // (read fresh) has to be XiaoAi's; while it is not, nothing is pressed.
                    if ("com.xiaomi.voicecontrol".equals(eyes.activeWindowPackage())) {
                        if (eyes.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                            log.add("  关掉了小爱自己的推荐页");
                        return;
                    }
                    covered = true;
                }
                // XiaoAi's own large-model answer card goes on searching and writing its answer on screen after 星仪
                // took over (2026-11-05 21:00: 「本体搜索和星仪搜索都开了」). When that card is the active window, one
                // BACK closes it, as the remote's back key would; if it is not, nothing is pressed.
                Eyes eyes = Eyes.get();
                if (llmCard(d) && eyes != null && SystemClock.uptimeMillis() < cardUntil
                        && "com.xiaomi.voicecontrol".equals(eyes.activeWindowPackage())) {
                    if (eyes.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                        log.add("  关掉了小爱自己的回答卡片");
                    return;
                }
                SystemClock.sleep(120);
            }
            if (covered) log.add("  小爱的推荐页不是当前窗口，没有按返回");
            if (llmCard(d)) log.add("  小爱的回答卡片不是当前窗口，没有按返回");
        });
    }

    /** XiaoAi answered with its own large model (the card that searches and streams its answer on screen). */
    static boolean llmCard(XiaoaiLog.Dialog d) {
        for (String n : d.names) if (n.startsWith("Template.LLM") || n.equals("Template.ToastStream")) return true;
        return false;
    }

    static boolean musicInstruction(String name) {
        return name.startsWith("AudioPlayer.") || name.startsWith("PlaybackController.") || name.equals("Template.PlayInfo")
                || name.equals("Template.Music") || name.equals("Template.MusicInfoListCard");
    }

    /** Waits up to waitMs for XiaoAi's own understanding of the request; true when it treats it as music. */
    private boolean xiaoaiSaysMusic(XiaoaiLog.Dialog d, long waitMs) {
        long end = SystemClock.elapsedRealtime() + waitMs;
        do {
            for (String name : d.names) if (musicInstruction(name)) return true;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < end);
        if (!d.names.isEmpty()) log.add("  小爱的理解: " + d.names);
        return false;
    }

    private void handle(XiaoaiLog.Dialog d, String source) {
        try {
            if (takeover.handle(d.text, source, ms -> xiaoaiSaysMusic(d, ms),
                    () -> d.silenceUntil = System.currentTimeMillis() + 6000, why -> giveToAgent(d, why))) d.taken.set(true);
        } catch (RuntimeException e) {
            log.add("处理出错 " + e);
        }
    }

    /** XiaoAi's answer text for the current request: an "I can't" hands the request to 星仪, which acts or answers. */
    private void onAnswer(XiaoaiLog.Dialog d, String text) {
        if (text == null || !Refusal.is(text)) return;
        // song requests are decided by the music path (which also knows XiaoAi's NetEase refusal)
        if (MusicIntent.parse(d.text) != null) return;
        if (AgentEngine.isOwnXiaoaiAsk(d.text)) return;
        if (!d.taken.compareAndSet(false, true)) return;
        answers.execute(() -> giveToAgent(d, Takeover.isAction(d.text) ? "小爱做不到的操作" : "小爱答不了的问题"));
    }

    /** DUMP-protected hooks (adb shell): SIMULATE/ASK text_b64, LINE line_b64, STATUS, PROVISION. */
    private void onControl(Intent i) {
        String action = i.getAction();
        if (SIMULATE.equals(action)) {
            String text = decode(i.getStringExtra("text_b64"));
            // no XiaoAi reaction to wait for: a simulated sentence counts as music
            if (text != null) worker.execute(() -> {
                try { takeover.handle(text, "模拟", ms -> true, null); } catch (RuntimeException e) { log.add("处理出错 " + e); }
            });
        } else if (ASK.equals(action)) {
            String text = decode(i.getStringExtra("text_b64"));
            Llm model = llm;
            if (text != null && model != null)
                answers.execute(() -> takeover.answerWithModel(new XiaoaiLog.Dialog(text, "", System.currentTimeMillis()), "（模拟）", model));
            else log.add("ASK 被忽略：" + (model == null ? "模型未配置" : "没有文字"));
        } else if (LINE.equals(action)) {
            String line = decode(i.getStringExtra("line_b64"));
            AsrLine a = AsrLine.parse(line);
            if (a != null && !a.trusted()) log.add("忽略非小爱进程写的识别行 uid=" + a.uid);
            else if (a != null && !a.isFinal) listening(line);
            else if (a != null) xiaoai.entry(a.epochMillis, a.isFinal ? XiaoaiLog.TAG_BAR : "", "receive bus event: EventOnFullResult text: " + a.text);
        } else if (PROVISION.equals(action)) {
            String result = Llm.provision(this);
            llm = Llm.load(this);
            log.add(result);
        } else if (ROUTE.equals(action)) {
            String name = i.getStringExtra("model"), which = i.getStringExtra("route");
            String result;
            File cfg = new File(getFilesDir(), "config.json");
            try {
                String before = Llm.readAll(cfg);
                String after = RouteOverride.patch(before, name, which);
                File tmp = new File(cfg.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(after.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
                tmp.setReadable(false, false); tmp.setWritable(false, false);
                tmp.setReadable(true, true); tmp.setWritable(true, true);
                if (!tmp.renameTo(cfg)) throw new java.io.IOException("rename failed");
                // the simple chat route (llm.json) goes the same way when the gateway is the one being changed, so the
                // status line and the agent agree
                String simple = "";
                if (which == null || which.trim().isEmpty() || "gateway".equals(which.trim()) || "all".equals(which.trim())) {
                    Llm s = Llm.load(this);
                    if (s != null && !name.trim().equals(s.model)) simple = "；简单问答仍是 " + s.model + "（这条线路没改）";
                }
                result = "模型换成 " + name.trim() + "（" + (which == null || which.trim().isEmpty() ? "gateway" : which.trim())
                        + " 这条线路，地址和密钥没动）" + simple;
            } catch (java.io.IOException e) {
                result = "改模型失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (IllegalArgumentException e) {
                result = "改模型失败：" + e.getMessage();
            }
            log.add(result);
        } else if (MODE.equals(action)) {
            setMode("refusals".equals(i.getStringExtra("mode")) ? "refusals" : "all");
        } else if (STATUS.equals(action)) {
            // the adb summary opens a loopback socket, which must not happen on the main thread
            watch.execute(this::writeStatus);
        }
    }

    private static String decode(String b64) {
        if (b64 == null || b64.length() > 8192) return null;
        try {
            return new String(Base64.decode(b64, Base64.NO_WRAP), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void writeStatus() {
        Llm model = llm;
        XiaoaiLog.Dialog d = xiaoai.current();
        String status = "reader: " + readerState + "\nlog entries received from logd (all apps): " + linesRead
                + "\nREAD_LOGS: " + granted("android.permission.READ_LOGS") + "\nDUMP: " + granted("android.permission.DUMP")
                + "\nmode: " + (takeoverAll ? "all (星仪接管全部请求)" : "refusals (只接小爱做不到的)")
                + "\nmodel: " + (model == null ? "未配置" : model.model + " @ " + model.base)
                + "\nagent: " + AgentEngine.get(this).state()
                + "\nadb guard: " + AdbWatch.summary(this)
                + "\nlast request: " + (d == null ? "-" : d.text + " · " + d.names + " · " + d.answers)
                + "\nnetease installed: " + NeteasePlayer.installed(this) + "\nnow playing: " + NeteasePlayer.nowPlaying()
                + "\n--- log ---\n" + log.tail(30);
        File dir = getExternalFilesDir(null);
        try (FileOutputStream out = new FileOutputStream(new File(dir == null ? getFilesDir() : dir, "status.txt"))) {
            out.write(status.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // status is best effort
        }
    }
}
