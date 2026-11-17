package xyz.erapi0neer.xingyi;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import xyz.erapi0neer.xingyi.agent.OfficialApps;

/** Android 9 loopback ADB client with fixed operations.
 * Uses its own private RSA identity and the TV's normal ADB authorization dialog.
 * All methods except cancel() block and must run on a worker thread.
 * Wire protocol reference: AOSP system/core/adb/protocol.txt (Android 9).
 * RSA wire layout reference: AOSP system/core/libcrypto_utils/android_pubkey.cpp.
 */
public final class LocalAdb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    private static final int MAX_PACKET = 256 * 1024, MAX_PNG = 8 * 1024 * 1024;
    private static final byte[] EMPTY = new byte[0];
    private static final Object KEY_LOCK = new Object();
    private final Context context;
    private volatile Socket socket;
    private InputStream input;
    private OutputStream output;
    private volatile boolean cancelled;
    private boolean connected;
    private int nextId = 1;

    public LocalAdb(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void connect() throws IOException {
        connect(true);
    }

    private void connect(boolean mayPrompt) throws IOException {
        ensureNotCancelled();
        if (connected && socket != null && !socket.isClosed()) return;
        closeSocket();
        boolean requestedAuthorization = false;
        String stage = "身份读取";
        try {
            RSAPrivateKey key = loadKey();
            stage = "连接";
            ensureNotCancelled();
            Socket connection = new Socket();
            socket = connection;
            connection.connect(new InetSocketAddress("127.0.0.1", 5555), 5000);
            connection.setTcpNoDelay(true);
            input = connection.getInputStream();
            output = connection.getOutputStream();
            send(CNXN, 0x01000000, MAX_PACKET, zeroTerminated("host::xingyi"));
            long deadline = System.nanoTime() + (mayPrompt ? 30_000_000_000L : 3_000_000_000L);
            int challenges = 0;
            while (true) {
                Packet packet = read(deadline);
                if (packet.command == CNXN) {
                    if (packet.arg0 < 0x01000000 || packet.arg1 < 4096)
                        throw new IOException("电视 ADB 协议版本不兼容");
                    connected = true;
                    return;
                }
                if (packet.command != AUTH || packet.arg0 != 1 || packet.payload.length != 20)
                    throw new IOException("电视 ADB 返回了无效的授权响应");
                challenges++;
                if (challenges == 1) { stage = "身份签名"; send(AUTH, 2, 0, signToken(key, packet.payload)); }
                else if (challenges == 2) {
                    if (!mayPrompt) throw new IOException("本机调试尚未授权读取播放状态");
                    stage = "公钥授权";
                    send(AUTH, 3, 0, publicKeyMessage(key));
                    requestedAuthorization = true;
                } else throw new IOException("电视尚未信任星仪，请在电视上允许新的调试授权后重试");
            }
        } catch (SocketTimeoutException e) {
            closeSocket();
            throw new IOException(requestedAuthorization
                    ? "请在电视上允许星仪的新调试授权，然后重试"
                    : "连接本机调试服务超时，请确认电视网络调试已开启");
        } catch (IOException e) {
            closeSocket();
            if (cancelled) throw new IOException("请求已取消");
            throw e;
        } catch (Exception e) {
            closeSocket();
            throw new IOException("本机调试" + stage + "失败（" + e.getClass().getSimpleName() + "）");
        }
    }

    public synchronized byte[] screenshotPng() throws IOException {
        connect();
        byte[] png = runFixed("exec:screencap -p", MAX_PNG, 15000);
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (png.length <= signature.length || !Arrays.equals(Arrays.copyOf(png, signature.length), signature))
            throw new IOException("电视没有返回有效截图；受保护画面可能不可读取");
        return png;
    }

    /** Reads a fresh session snapshot. The song and artist never enter the shell command. */
    public synchronized boolean isNetEasePlaying(List<String> displayTitles, String creditedArtist,
            String album) throws IOException {
        return MusicPlaybackEvidence.matches(netEaseSessionDump(true), displayTitles, creditedArtist, album);
    }

    synchronized SongOutcome.Observation readNetEasePlayback() throws IOException {
        String dump = netEaseSessionDump(false);
        return SongOutcome.parse(dump, android.os.SystemClock.elapsedRealtime());
    }

    synchronized boolean isPreviewOrXiaomiForeground() throws IOException {
        connect(false);
        byte[] match=runFixed("shell:dumpsys activity activities | grep -m1 'mResumedActivity:'"
                +" | grep -Eq ' u0 (xyz[.]erapi0neer[.]xingyi|com[.]xiaomi[.]voicecontrol)/' && echo OWNED",32,1400);
        return "OWNED".equals(new String(match,StandardCharsets.US_ASCII).trim());
    }

    /** Read only while the requested client's own window has focus. */
    synchronized String biliSearchSnapshot() throws IOException {
        connect(false);
        String cmd="shell:own() { dumpsys window | grep -m1 'mCurrentFocus=' | grep -q ' tv.danmaku.bili/'; }; "
                + "if own; then f=$(mktemp /data/local/tmp/xingyi-search.XXXXXX) || exit 1; "
                + "trap 'rm -f \"$f\"' EXIT; timeout 9 uiautomator dump --compressed \"$f\" >/dev/null 2>&1 "
                + "&& own && cat \"$f\"; fi";
        return new String(runFixed(cmd,1536*1024,11500),StandardCharsets.UTF_8);
    }

    // Fixed source. ASR transcripts, ordinary answer text and other apps' logs are excluded.
    private static final String TTS_WATCH = "shell:p=$(pidof com.xiaomi.voicecontrol); "
            + "case \"$p\" in ''|*[!0-9]*) exit 1;; esac; echo XINGYI_TTS_READY; "
            + "exec timeout 60 logcat -b main --pid=\"$p\" -T 1 -v threadtime -v epoch -v usec -e "
            + "'recv inst tra: [^[:space:]]*-SpeechSynthesizer[.]Speak\\[(很抱歉|抱歉|对不起|不好意思|目前暂不支持|当前暂不支持|我暂时不支持)"
            + "|onEngineEvent NlpEvent[(]rid=.[0-9a-fA-F]{32}., push=false, stream=false, SpeechSynthesizer[.]Speak[)]$"
            + "|onTtsStartPlay, isTtsUrl: |onTtsPlayFinish, byManual: "
            + "|updateQuery (小爱同学[，, ]*)?(星仪[，, ]*)?(继续执行|确认执行)[。！!]? [0-9a-fA-F]{32}$' "
            + "'=vc=en=aifw==xaf=aew=:I' '=vc=en=en=proxy=:I' '=vc=mm=mod=:D' '=conversation=:I' '*:S' 2>/dev/null";

    /** Blocks for a bounded subscription. Calls ready once and emits complete, post-subscribe
     * epoch/usec log lines. Caller can open a new subscription on EOF or cancel() from another thread. */
    synchronized void watchXiaoAiTts(Runnable ready, Consumer<String> onLine) throws IOException {
        connect(false);
        final long startWallMs = System.currentTimeMillis();
        class Lines implements ChunkSink {
            final ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean overflow, started;
            @Override public void accept(byte[] chunk) {
                for (byte b : chunk) {
                    if (b == '\n') {
                        if (!overflow) {
                            String value = new String(line.toByteArray(),StandardCharsets.UTF_8).trim();
                            if (!started && "XINGYI_TTS_READY".equals(value)) {
                                started = true;
                                ready.run();
                            } else if (started && freshLogTime(value,startWallMs)) {
                                onLine.accept(value);
                            }
                        }
                        line.reset();
                        overflow = false;
                    } else if (line.size() < 4096) {
                        line.write(b);
                    } else {
                        overflow = true;
                    }
                }
            }
        }
        Lines lines = new Lines();
        long startedNs = System.nanoTime();
        try {
            readFixed(TTS_WATCH,70000,lines);
            if (!lines.started || System.nanoTime()-startedNs < 1_000_000_000L)
                throw new IOException("小爱播报监听入口未就绪");
        } finally {
            closeSocket();
        }
    }

    private static boolean freshLogTime(String line, long startWallMs) {
        // logcat -v epoch -v usec starts with SSSSSSSSSS.uuuuuu. -T 1 can return a stale tail.
        if (line.length() < 18 || line.charAt(10) != '.' || line.charAt(17) != ' ') return false;
        for (int i=0; i<17; i++)
            if (i!=10 && (line.charAt(i) < '0' || line.charAt(i) > '9')) return false;
        long loggedMs = Long.parseLong(line.substring(0,10))*1000L + Long.parseLong(line.substring(11,14));
        return loggedMs > startWallMs && loggedMs <= System.currentTimeMillis()+50;
    }

    private static final String NETEASE_SNAPSHOT = "dumpsys media_session | grep -E '^ *(Sessions Stack - have [0-9]+ sessions:|package=|active=|state=|metadata:)'"
                + " | while IFS= read -r row; do row=${row#\"${row%%[![:space:]]*}\"}; "
                + "case \"$row\" in 'Sessions Stack - have '*) printf '%s\\n' \"$row\"; continue;; "
                + "package=com.netease.cloudmusic.tv) show=1;; package=*) show=0; echo package=other;; esac; "
                + "[ \"$show\" != 1 ] || printf '%s\\n' \"$row\"; done";
    private String netEaseSessionDump(boolean mayPrompt) throws IOException {
        connect(mayPrompt);
        byte[] snapshot = runFixed("shell:"+NETEASE_SNAPSHOT,64 * 1024,2500);
        return new String(snapshot, StandardCharsets.UTF_8);
    }

    /** Measure both snapshots inside one device-side request. Foreground changes and ADB
     * reconnects between reads cannot consume the time window for this pair. */
    synchronized SongOutcome.Observation[] readNetEasePair() throws IOException {
        connect(false);
        String cmd = "shell:sample(){ IFS=' ' read -r ts rest < /proc/uptime || exit 1; "
                + "printf '@sample %s\\n' \"$ts\"; " + NETEASE_SNAPSHOT + "; }; "
                + "sample; sleep 2; sample; printf '@complete\\n'";
        try {
            return parseNetEasePair(new String(runFixed(cmd,128 * 1024,8000),StandardCharsets.UTF_8));
        } finally {
            closeSocket();
        }
    }

    static SongOutcome.Observation[] parseNetEasePair(String raw) throws IOException {
        String clean=raw.replace("\r","").trim();
        if (!clean.endsWith("\n@complete")) throw new IOException("网易云播放会话读数不完整");
        String[] pages=clean.substring(0,clean.length()-"\n@complete".length()).split("(?m)^@sample ",-1);
        if (pages.length!=3 || !pages[0].isEmpty()) throw new IOException("网易云播放会话样本数不符");
        SongOutcome.Observation[] pair=new SongOutcome.Observation[2];
        for (int i=0;i<pair.length;i++) {
            String page=pages[i+1];
            int end=page.indexOf('\n');
            if (end<1 || !page.substring(0,end).matches("[0-9]{1,10}\\.[0-9]{1,3}"))
                throw new IOException("网易云播放会话时间无效");
            long ms=Math.round(Double.parseDouble(page.substring(0,end))*1000.0);
            pair[i]=SongOutcome.parse(page.substring(end+1),ms);
        }
        return pair;
    }

    /** Stage the already verified public download in a shell-private directory to seal the bytes used by pm.
     * pkgDir is the running app's own package (the caller passes context.getPackageName()), so the fixed command text
     * carries nothing but the three values OfficialApps.installArgsOk has checked. */
    synchronized String installVerified(String pkgDir, String filename, String digest)throws IOException {
        if (!OfficialApps.installArgsOk(pkgDir,filename,digest))
            throw new IOException("安装缓存标识无效");
        connect(false);
        String command = "shell:d=$(mktemp -d /data/local/tmp/xingyi-install-XXXXXX) || exit 1; "
                + "f=$d/base.apk; trap 'rm -f \"$f\"; rmdir \"$d\"' EXIT; "
                + "cp \"$EXTERNAL_STORAGE/Android/data/"+pkgDir+"/cache/"+filename+"\" \"$f\" || { echo COPY_FAILED; exit 1; }; "
                + "hash=$(sha256sum \"$f\") && [ \"${hash%% *}\" = '"+digest+"' ] || { echo DIGEST_MISMATCH; exit 1; }; "
                + "size=$(stat -c %s \"$f\") && pm install -S \"$size\" < \"$f\"";
        // 5 minutes: TV Bro's package is 150 MiB and pm streams it in; cancel() closes the socket, so this only bounds
        // a stuck install, it does not delay a cancelled task.
        String output = new String(runFixed(command,8192,300000),StandardCharsets.UTF_8).trim();
        if(output.endsWith("Success"))return "Success";
        java.util.regex.Matcher reason=java.util.regex.Pattern.compile("INSTALL_[A-Z_]+|COPY_FAILED|DIGEST_MISMATCH").matcher(output);
        return reason.find()?reason.group():"系统安装器没有成功回执";
    }

    /** Firmware-specific sleep with before/after verification; sends at most one power key. */
    public synchronized void sleep() throws IOException {
        connect();
        PowerState first = readPowerState(5000);
        if (first.sleeping) return;
        if (!first.awake || first.changing) throw new IOException("电视电源状态尚不明确，本次未发送休眠按键");
        // Recheck immediately before the single toggle: an already sleeping TV must not be woken.
        PowerState latest = readPowerState(5000);
        if (latest.sleeping) return;
        if (!latest.awake || latest.changing) throw new IOException("电视电源状态正在变化，本次未发送休眠按键");
        // This MiTV firmware ignores KEYCODE_SLEEP. Only this fixed power-key fallback is allowed.
        byte[] result = runFixed("shell:input keyevent 26 && echo XINGYI_SLEEP_SUBMITTED", 4096, 10000);
        if (!"XINGYI_SLEEP_SUBMITTED".equals(new String(result, StandardCharsets.UTF_8).trim()))
            throw new IOException("电视休眠指令未正常完成");
        long deadline = System.nanoTime() + 6_000_000_000L;
        while (System.nanoTime() < deadline) {
            ensureNotCancelled();
            int remaining = (int)Math.max(1, (deadline - System.nanoTime()) / 1_000_000L);
            try {
                if (readPowerState(Math.min(remaining, 2000)).sleeping) return;
            } catch (IOException e) {
                if (cancelled) throw new IOException("请求已取消");
                // Never retry a power toggle, including when its verification connection closes.
                throw new IOException("已发送一次休眠按键，但未能确认电视进入待机");
            }
            try { Thread.sleep(250); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("待机验证已中断"); }
        }
        throw new IOException("未能确认电视进入待机，本次不再重复发送电源按键");
    }

    private PowerState readPowerState(int timeoutMs) throws IOException {
        return parsePowerState(new String(runFixed("shell:dumpsys power", 128 * 1024, timeoutMs), StandardCharsets.UTF_8));
    }

    private static PowerState parsePowerState(String dump) {
        java.util.regex.Matcher state = java.util.regex.Pattern.compile("(?m)^\\s*mWakefulness=(Awake|Asleep|Dozing|Dreaming)\\s*$").matcher(dump);
        String wake = state.find() ? state.group(1) : "";
        boolean off = java.util.regex.Pattern.compile("(?m)^\\s*Display Power: state=OFF\\s*$").matcher(dump).find();
        boolean changing = java.util.regex.Pattern.compile("(?m)^\\s*mWakefulnessChanging=true\\s*$").matcher(dump).find();
        return new PowerState("Asleep".equals(wake) || "Dozing".equals(wake) || off, "Awake".equals(wake), changing);
    }

    private static final class PowerState {
        final boolean sleeping, awake, changing;
        PowerState(boolean sleeping, boolean awake, boolean changing) {
            this.sleeping = sleeping; this.awake = awake; this.changing = changing;
        }
    }

    // ---- Fixed operations for the agent. Every argument is a validated number or a checked component name. ----

    /** Sends key codes in order through `input keyevent`. */
    public synchronized void keyEvents(int[] codes) throws IOException {
        if (codes == null || codes.length == 0 || codes.length > 40) throw new IOException("按键数量无效");
        StringBuilder cmd = new StringBuilder("shell:input keyevent");
        for (int c : codes) {
            if (c < 1 || c > 300) throw new IOException("按键编码无效");
            cmd.append(' ').append(c);
        }
        connect(false);
        runFixed(cmd.toString(), 4096, 4000 + codes.length * 400);
    }

    /** Touches one screen point. */
    public synchronized void tap(int x, int y) throws IOException {
        if (x < 0 || y < 0 || x > 4096 || y > 4096) throw new IOException("坐标无效");
        connect(false);
        runFixed("shell:input tap " + x + " " + y, 4096, 5000);
    }

    public synchronized String mediaSessions() throws IOException {
        connect(false);
        return new String(runFixed("shell:dumpsys media_session", 256 * 1024, 5000), StandardCharsets.UTF_8);
    }

    /**
     * The audio service's own list of who has a player open and in which state (the `players:` block of
     * `dumpsys audio`, one line each: `ID:135 -- type:android.media.AudioTrack -- u/pid:10021/18067 -- state:started --
     * attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_UNKNOWN flags=0x200 …`). An app with no media session
     * (云视听极光) is still there: started while it plays, paused when paused (2026-11-11 20:01 on the TV). The whole
     * dump is about 12 KB, so it is read whole and AudioPlayers picks its lines.
     */
    public synchronized String audioPlayers() throws IOException {
        connect(false);
        return new String(runFixed("shell:dumpsys audio", 256 * 1024, 5000), StandardCharsets.UTF_8);
    }

    private static final java.util.regex.Pattern PACKAGE = java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+");

    /** What one installed app publishes (its intent filters, version): read-only `dumpsys package`, for explore_app.
     * Only a well-formed package name ever reaches the command. */
    public synchronized String packageDump(String pkg) throws IOException {
        if (pkg == null || pkg.length() > 200 || !PACKAGE.matcher(pkg).matches()) throw new IOException("包名无效");
        connect(false);
        return new String(runFixed("shell:dumpsys package " + pkg, 1024 * 1024, 8000), StandardCharsets.UTF_8);
    }

    /** Every process's resident memory and name (read-only `ps`), for 清理内存 and the memory guard. */
    public synchronized String processes() throws IOException {
        connect(false);
        return new String(runFixed("shell:ps -A -o RSS,NAME", 256 * 1024, 6000), StandardCharsets.UTF_8);
    }

    /** The apps the user installed (read-only `pm list packages -3`). */
    public synchronized String userPackages() throws IOException {
        connect(false);
        return new String(runFixed("shell:pm list packages -3", 64 * 1024, 8000), StandardCharsets.UTF_8);
    }

    /** One package's memory per process (read-only `dumpsys meminfo -s`, ~1 s): what `ps` cannot say, the PSS. */
    public synchronized String meminfo(String pkg) throws IOException {
        if (pkg == null || pkg.length() > 200 || !PACKAGE.matcher(pkg).matches()) throw new IOException("包名无效");
        connect(false);
        return new String(runFixed("shell:dumpsys meminfo -s " + pkg, 256 * 1024, 8000), StandardCharsets.UTF_8);
    }

    /** How each installed app's code is compiled (read-only `dumpsys package dexopt`): per package its APK path and
     * `[status=speed-profile]`, `verify`, `run-from-apk`…, for the standby maintenance that compiles what the system
     * itself could not (AppCompile). */
    public synchronized String dexoptStates() throws IOException {
        connect(false);
        return new String(runFixed("shell:dumpsys package dexopt", 512 * 1024, 10000), StandardCharsets.UTF_8);
    }

    /** Compiles one app's code with the system's own optimizer (`cmd package compile -m speed-profile -f`), with its
     * compact-dex step switched off while it runs. On this TV dex2oat is a 32-bit process and that step keeps every dex
     * file's data in one growing buffer: B站 (38 dex files, 263 MB) died there with std::bad_alloc after 95 s, every
     * night since it was installed, so it ran from its APK — 61 s to its first page, 15 s once compiled (2026-11-10).
     * The switch is a volatile system property, put back when the compile ends. Takes minutes (B站: 4 min 14 s): never
     * on a thread that anything waits for. True when the package manager said Success. */
    public synchronized boolean compileApp(String pkg) throws IOException {
        if (pkg == null || pkg.length() > 200 || !PACKAGE.matcher(pkg).matches()) throw new IOException("包名无效");
        connect(false);
        String out = new String(runFixed("shell:setprop dalvik.vm.dex2oat-flags --compact-dex-level=none; "
                + "cmd package compile -m speed-profile -f " + pkg + " 2>&1; setprop dalvik.vm.dex2oat-flags ''",
                8192, 600_000), StandardCharsets.UTF_8);
        return out.contains("Success");
    }

    /**
     * Stops these apps (`am force-stop`: each a well-formed package name and never one of 星仪's own) and has the system
     * drop the processes it counts as cached (`am kill-all` kills nothing in front, nothing persistent, no service).
     */
    public synchronized void stopApps(java.util.Collection<String> pkgs) throws IOException {
        if (pkgs.size() > 20) throw new IOException("一次要关的应用太多");
        StringBuilder cmd = new StringBuilder("shell:");
        for (String p : pkgs) {
            if (p == null || p.length() > 200 || !PACKAGE.matcher(p).matches() || p.startsWith("xyz.erapi0neer."))
                throw new IOException("包名无效");
            cmd.append("am force-stop ").append(p).append("; ");
        }
        cmd.append("am kill-all; echo XINGYI_STOPPED");
        connect(false);
        String out = new String(runFixed(cmd.toString(), 16 * 1024, 8000 + pkgs.size() * 3000), StandardCharsets.UTF_8);
        if (!out.contains("XINGYI_STOPPED")) throw new IOException("清理命令没有正常完成");
    }

    /**
     * XiaoAi's own windows — its listening bar and answer cards, two APPLICATION_OVERLAY windows — hidden or shown
     * (「小爱的原始卡片还是没ban啊」, 2026-11-08). XiaoAi runs as the system uid, yet the window manager still hides an
     * overlay whose package has SYSTEM_ALERT_WINDOW set to ignore (seen on the TV: mAppOpVisibility=false at once).
     * The op is per package and kept across reboots; "default" is what the firmware had.
     */
    public synchronized void xiaoaiOverlays(boolean shown) throws IOException {
        connect(false);
        String mode = shown ? "default" : "ignore";
        String out = new String(runFixed("shell:appops set com.xiaomi.voicecontrol SYSTEM_ALERT_WINDOW " + mode
                + " && appops get com.xiaomi.voicecontrol SYSTEM_ALERT_WINDOW", 4096, 5000), StandardCharsets.UTF_8);
        if (!out.contains("SYSTEM_ALERT_WINDOW: " + mode)) throw new IOException("小爱悬浮窗的开关没有生效：" + out.trim());
    }

    /** Whether XiaoAi's own windows are hidden right now: its SYSTEM_ALERT_WINDOW appop is "ignore" (see
     * {@link #xiaoaiOverlays}). The self-repair checks the card this way before deciding it has to be closed again. */
    public synchronized boolean overlayHidden() throws IOException {
        connect(false);
        String out = new String(runFixed("shell:appops get com.xiaomi.voicecontrol SYSTEM_ALERT_WINDOW", 4096, 5000),
                StandardCharsets.UTF_8);
        return out.contains("SYSTEM_ALERT_WINDOW: ignore");
    }

    /** "package/activity" of the focused window, or "" when unknown. */
    public synchronized String focusedWindow() throws IOException {
        connect(false);
        String line = new String(runFixed("shell:dumpsys window | grep -m1 'mCurrentFocus='", 4096, 4000), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("u0 ([\\w.]+/[\\w.$]+)\\}").matcher(line);
        return m.find() ? m.group(1) : "";
    }

    /** "package/full.activity.Class" of the activity the window manager gives focus to (mFocusedApp), or "" when unknown.
     * Unlike the focused window this is never an overlay: 2026-11-09 XiaoAi's hidden panel was the 「foreground」 the
     * model was told about while NetEase's player page was in front. */
    public synchronized String focusedApp() throws IOException {
        connect(false);
        String line = new String(runFixed("shell:dumpsys window | grep -m1 'mFocusedApp='", 4096, 4000), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("u0 ([\\w.]+)/([\\w.$]+) t\\d+\\}").matcher(line);
        if (!m.find()) return "";
        String cls = m.group(2).startsWith(".") ? m.group(1) + m.group(2) : m.group(2);
        return m.group(1) + "/" + cls;
    }

    /** uiautomator dump of whatever has focus; slow (1–3 s), used when the accessibility service is unavailable. */
    public synchronized String uiDump() throws IOException {
        connect(false);
        String cmd = "shell:f=$(mktemp /data/local/tmp/xingyi-ui.XXXXXX) || exit 1; trap 'rm -f \"$f\"' EXIT; "
                + "timeout 9 uiautomator dump --compressed \"$f\" >/dev/null 2>&1 && cat \"$f\"";
        return new String(runFixed(cmd, 2 * 1024 * 1024, 11500), StandardCharsets.UTF_8);
    }

    /**
     * Takes one accessibility service out of the enabled list and puts it straight back — what makes Android 9 bind it
     * again when it is listed but not connected (the bridge's install script does the same thing by hand, and it is
     * the one repair that worked on the TV 2026-11-12 01:33: the service came back within 1.5 s). Every other entry is
     * kept in its order. When it is not in the list at all it is put in — that is the same repair (2026-11-12
     * 01:55: the list had been wiped and the first version returned false, so the self-check said 修不了 while the fix
     * was one write away). True when it was rebound or added; it only returns false when the list cannot be read.
     */
    public synchronized boolean rebindAccessibility(String component) throws IOException {
        java.util.regex.Pattern name = java.util.regex.Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");
        if (!name.matcher(component).matches()) throw new IOException("服务名称无效");
        connect(true);
        String current = new String(runFixed("shell:settings get secure enabled_accessibility_services", 16 * 1024, 4000),
                StandardCharsets.UTF_8).trim();
        // The service can be gone from the list altogether — XiaoAi's own service rewrites that setting and has dropped
        // it (2026-11-05 12:48), and the self-repair's own test did it on purpose (2026-11-12 01:55): then there is
        // nothing to take out, and putting it in is the repair.
        if (current.isEmpty() || "null".equals(current)) {
            runFixed("shell:settings put secure enabled_accessibility_services '" + component
                    + "' && settings put secure accessibility_enabled 1", 4096, 5000);
            return true;
        }
        boolean found = false;
        StringBuilder list = new StringBuilder();
        for (String part : current.split(":")) {
            if (part.isEmpty()) continue;
            if (!name.matcher(part).matches()) throw new IOException("现有无障碍服务列表无法识别，未修改");
            if (part.equals(component)) { found = true; continue; }
            if (list.length() > 0) list.append(':');
            list.append(part);
        }
        if (!found) {
            runFixed("shell:settings put secure enabled_accessibility_services '" + current + ":" + component
                    + "' && settings put secure accessibility_enabled 1", 4096, 5000);
            return true;
        }
        if (list.length() > 0) runFixed("shell:settings put secure enabled_accessibility_services '" + list + "'", 4096, 5000);
        else runFixed("shell:settings delete secure enabled_accessibility_services", 4096, 5000);
        try { Thread.sleep(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        runFixed("shell:settings put secure enabled_accessibility_services '" + (list.length() > 0 ? list + ":" : "") + component
                + "' && settings put secure accessibility_enabled 1", 4096, 5000);
        return true;
    }

    /** Adds one accessibility service component to the enabled list, keeping every existing entry. */
    public synchronized boolean enableAccessibility(String component) throws IOException {
        java.util.regex.Pattern name = java.util.regex.Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");
        if (!name.matcher(component).matches()) throw new IOException("服务名称无效");
        connect(true);
        String current = new String(runFixed("shell:settings get secure enabled_accessibility_services", 16 * 1024, 4000),
                StandardCharsets.UTF_8).trim();
        StringBuilder list = new StringBuilder();
        if (!current.isEmpty() && !"null".equals(current)) {
            for (String part : current.split(":")) {
                if (part.isEmpty()) continue;
                if (!name.matcher(part).matches()) throw new IOException("现有无障碍服务列表无法识别，未修改");
                if (part.equals(component)) return true;
                if (list.length() > 0) list.append(':');
                list.append(part);
            }
        }
        if (list.length() > 0) list.append(':');
        list.append(component);
        runFixed("shell:settings put secure enabled_accessibility_services '" + list + "' && settings put secure accessibility_enabled 1",
                4096, 5000);
        return false;
    }

    // This method is private on purpose: model output cannot become arbitrary shell input.
    private byte[] runFixed(String destination, int maxBytes, int timeoutMs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        readFixed(destination,timeoutMs,chunk -> {
            if (bytes.size() + chunk.length > maxBytes) throw new IOException("电视返回的数据过大，已停止读取");
            bytes.write(chunk);
        });
        return bytes.toByteArray();
    }

    private interface ChunkSink { void accept(byte[] chunk) throws IOException; }

    private void readFixed(String destination, int timeoutMs, ChunkSink sink) throws IOException {
        int local = nextId++;
        if (nextId <= 0) nextId = 1;
        int remote = 0;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        try {
            send(OPEN, local, 0, zeroTerminated(destination));
            while (true) {
                Packet packet = read(deadline);
                if (packet.arg1 != local) {
                    if (!late(packet.command, packet.arg1, local)) throw new IOException("电视 ADB 返回了错误的通道");
                    if (packet.command == WRTE) send(CLSE, packet.arg1, packet.arg0, EMPTY);
                    continue;
                }
                if (packet.command == OKAY) {
                    if (packet.arg0 == 0 || (remote != 0 && remote != packet.arg0) || packet.payload.length != 0)
                        throw new IOException("电视 ADB 通道响应无效");
                    remote = packet.arg0;
                } else if (packet.command == WRTE) {
                    if (remote == 0 || packet.arg0 != remote) throw new IOException("电视 ADB 数据通道无效");
                    sink.accept(packet.payload);
                    send(OKAY, local, remote, EMPTY);
                } else if (packet.command == CLSE) {
                    if (remote == 0 || packet.arg0 != remote) throw new IOException("电视拒绝了本次本机控制请求");
                    return;
                } else throw new IOException("电视 ADB 返回了意外的数据包");
            }
        } catch (IOException e) {
            closeSocket();
            if (cancelled) throw new IOException("请求已取消");
            if (e instanceof SocketTimeoutException) throw new IOException("电视本机控制响应超时");
            throw e;
        }
    }

    /**
     * A packet for a stream this connection has already finished: adbd answers the OKAY we send for a stream's last
     * WRTE, when it has closed that stream in the meantime, with a CLSE of its own (arg0 0, arg1 the old local id),
     * and it reaches the next command's stream. Before 2026-11-08 that ended the command with 「错误的通道」, three
     * times running while a song buffered, and netease_play said it could not confirm a song that was playing.
     */
    static boolean late(int command, int target, int local) {
        return target > 0 && target < local && (command == CLSE || command == OKAY || command == WRTE);
    }

    /** Disconnect and permanently cancel this instance. Safe from any thread. */
    public void cancel() { cancelled = true; closeSocket(); }

    private void closeSocket() {
        connected = false;
        Socket connection = socket;
        socket = null;
        if (connection != null) try { connection.close(); } catch (IOException ignored) { }
    }

    private void ensureNotCancelled() throws IOException { if (cancelled) throw new IOException("请求已取消"); }

    private void send(int command, int arg0, int arg1, byte[] payload) throws IOException {
        ensureNotCancelled();
        if (payload.length > 4096) throw new IOException("本机调试请求过大");
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length).putInt(checksum(payload)).putInt(command ^ -1);
        output.write(header.array());
        output.write(payload);
        output.flush();
    }

    private Packet read(long deadline) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(readBytes(24, deadline)).order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt(), arg0 = header.getInt(), arg1 = header.getInt();
        int length = header.getInt(), sum = header.getInt(), magic = header.getInt();
        if (magic != (command ^ -1) || length < 0 || length > MAX_PACKET
                || !(command == CNXN || command == AUTH || command == OKAY || command == WRTE || command == CLSE))
            throw new IOException("电视 ADB 数据包头无效");
        byte[] payload = readBytes(length, deadline);
        if (checksum(payload) != sum) throw new IOException("电视 ADB 数据校验失败");
        return new Packet(command, arg0, arg1, payload);
    }

    private byte[] readBytes(int count, long deadline) throws IOException {
        byte[] bytes = new byte[count];
        int got = 0;
        while (got < count) {
            ensureNotCancelled();
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) throw new SocketTimeoutException();
            Socket connection = socket;
            if (connection == null) throw new IOException("本机调试连接已关闭");
            connection.setSoTimeout((int)Math.max(1, Math.min(30000, remaining)));
            int n;
            try { n = input.read(bytes, got, count - got); }
            catch (SocketTimeoutException idle) {
                if (System.nanoTime() >= deadline) throw idle;
                continue;
            }
            if (n < 0) throw new IOException("电视关闭了本机调试连接，请确认调试授权");
            got += n;
        }
        return bytes;
    }

    private RSAPrivateKey loadKey() throws Exception {
        synchronized (KEY_LOCK) {
            AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "xingyi-adb-key.pk8"));
            if (file.getBaseFile().exists()) {
                try (FileInputStream stream = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[1024]; int n;
                    while ((n = stream.read(chunk)) != -1) {
                        if (bytes.size() + n > 8192) throw new IOException("本机调试身份文件无效");
                        bytes.write(chunk, 0, n);
                    }
                    RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                            .generatePrivate(new PKCS8EncodedKeySpec(bytes.toByteArray()));
                    if (key.getModulus().bitLength() != 2048) throw new IOException("本机调试身份格式不兼容");
                    return key;
                }
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
            RSAPrivateKey key = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
            FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                stream.write(key.getEncoded());
                file.finishWrite(stream);
                // Private files directory plus owner-only key permissions.
                file.getBaseFile().setReadable(false, false);
                file.getBaseFile().setWritable(false, false);
                file.getBaseFile().setReadable(true, true);
                file.getBaseFile().setWritable(true, true);
            } catch (IOException e) {
                if (stream != null) file.failWrite(stream);
                throw e;
            }
            return key;
        }
    }

    private static byte[] signToken(RSAPrivateKey key, byte[] token) throws Exception {
        // ADB sends the SHA-1 digest itself; do not hash it a second time.
        byte[] digestInfo = {0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
        byte[] message = new byte[digestInfo.length + token.length];
        System.arraycopy(digestInfo, 0, message, 0, digestInfo.length);
        System.arraycopy(token, 0, message, digestInfo.length, token.length);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(message);
    }

    private static byte[] publicKeyMessage(RSAPrivateKey key) {
        BigInteger n = key.getModulus(), two32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer encoded = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        encoded.putInt(64).putInt(two32.subtract(n.modInverse(two32)).intValue());
        encoded.put(littleEndian(n, 256));
        encoded.put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(n), 256));
        // Some Android providers expose decoded PKCS8 keys without the CRT interface.
        // This app only generates 2048-bit keys with F4 (65537).
        BigInteger exponent = key instanceof RSAPrivateCrtKey
                ? ((RSAPrivateCrtKey) key).getPublicExponent() : RSAKeyGenParameterSpec.F4;
        encoded.putInt(exponent.intValue());
        return zeroTerminated(Base64.getEncoder().encodeToString(encoded.array()) + " xingyi@tv");
    }

    private static byte[] littleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray(), little = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) little[i] = big[big.length - 1 - i];
        return little;
    }
    private static byte[] zeroTerminated(String text) { return (text + '\0').getBytes(StandardCharsets.UTF_8); }
    private static int checksum(byte[] bytes) { int sum = 0; for (byte b : bytes) sum += b & 255; return sum; }
    private static final class Packet {
        final int command, arg0, arg1; final byte[] payload;
        Packet(int command, int arg0, int arg1, byte[] payload) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.payload = payload;
        }
    }
}
