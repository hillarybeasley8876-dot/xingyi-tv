package xyz.erapi0neer.xingyi;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;

/** Xiaomi's exported text playback entry point. No microphone, identity changes or mode settings. */
public final class VendorSpeech {
    private static final ComponentName SERVICE = new ComponentName(
            "com.xiaomi.voicecontrol", "com.xiaomi.voicecontrol.fullduplex.FullDuplexDebugService");
    private static final String ACTION = "com.xiaomi.voicecontrol.PLAY_TTS";
    static final String SUBMITTED = "已提交小米语音播报";
    /** uptime of the last speech handed to XiaoAi's voice: its player starting soon after is 星仪 talking. */
    public static volatile long lastSpokeAt;
    private VendorSpeech() { }

    public static boolean isAvailable(Context context) {
        try {
            ServiceInfo info = context.getPackageManager().getServiceInfo(SERVICE, 0);
            return info.enabled && info.applicationInfo.enabled && info.exported
                    && (info.permission == null || context.checkSelfPermission(info.permission)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED);
        } catch (Exception ignored) { return false; }
    }

    /** XiaoAi's player as the XiaoAi bridge reads it from XiaoAi's log: sounds started and completed so far, and whether
     * the last one was stopped rather than played to the end. */
    private static final Object PLAYER = new Object();
    private static long started, completed;
    private static boolean lastManual;

    /** From the bridge's log reader: XiaoAi's player started a sound, or completed one (manual: it was stopped). */
    public static void player(boolean start, boolean manual) {
        synchronized (PLAYER) {
            if (start) started++;
            else { completed++; lastManual = manual; }
            PLAYER.notifyAll();
        }
    }

    public interface Cancelled { boolean now(); }

    /**
     * Speaks text and waits until XiaoAi has read it out: true when it played to the end (or, with no player events at
     * all, when the estimated reading time is up), false when it was stopped (the user woke XiaoAi, a stop, the next
     * speech) or cancelled. The page-by-page reading of long answers waits on this (2026-11-06: 「还是没完全读完啊，读到
     * 后面也不自动切换卡片到下一页」).
     */
    public static boolean speakAndWait(Context context, String text, Cancelled cancelled) throws InterruptedException {
        long before;
        synchronized (PLAYER) { before = started; }
        if (!SUBMITTED.equals(speak(context, text))) return false;
        long began = android.os.SystemClock.uptimeMillis(), now;
        // about 4 characters a second, and the second or two before XiaoAi starts
        long estimate = 3000 + text.length() * 250L, end = began + estimate * 2 + 4000;
        long startedOurs = -1, completedAtStart = 0;
        synchronized (PLAYER) {
            while (true) {
                if (cancelled != null && cancelled.now()) return false;
                now = android.os.SystemClock.uptimeMillis();
                if (startedOurs < 0 && started > before) { startedOurs = started; completedAtStart = completed; }
                if (startedOurs >= 0 && completed > completedAtStart) return !lastManual;
                // XiaoAi never reported starting it (nobody reads its log): the estimate is all there is
                if (startedOurs < 0 && now >= began + estimate) return true;
                // started and never reported done: long past the end of any reading of it
                if (now >= end) return true;
                PLAYER.wait(Math.min(250, end - now));
            }
        }
    }

    /** Returns dispatch status only: the vendor entry point exposes no completion callback. */
    public static String speak(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return "没有可播报的文字";
        if (!isAvailable(context)) return "小米播报入口不可用";
        // what the voice gets is not what the panel shows: erhua words lose the 儿 XiaoAi reads as a syllable of its own
        // (Speech.voiced, 2026-11-19); every spoken word goes through here
        String spoken = xyz.erapi0neer.xingyi.agent.Speech.voiced(text.trim());
        if (spoken.length() > 1800) spoken = spoken.substring(0, 1800);
        try {
            lastSpokeAt = android.os.SystemClock.uptimeMillis();
            Intent intent = new Intent(ACTION).setComponent(SERVICE).putExtra("text", spoken);
            ComponentName started = context.startService(intent);
            return started == null ? "未能提交播报请求" : SUBMITTED;
        } catch (SecurityException denied) {
            return "系统拒绝语音播报请求";
        } catch (RuntimeException unavailable) {
            return "当前无法提交语音播报请求";
        }
    }
}
