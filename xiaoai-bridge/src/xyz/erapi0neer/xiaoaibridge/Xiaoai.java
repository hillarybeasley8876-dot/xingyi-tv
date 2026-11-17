package xyz.erapi0neer.xiaoaibridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** XiaoAi's exported text-mode entry points: stop its current speech, or speak a short line. */
final class Xiaoai {
    private static final String PKG = "com.xiaomi.voicecontrol";
    private static final ComponentName VOICE = new ComponentName(PKG, PKG + ".VoiceControlService");
    private static final ComponentName TTS = new ComponentName(PKG, PKG + ".fullduplex.FullDuplexDebugService");

    static void stopSpeaking(Context context) {
        start(context, new Intent("com.xiaomi.mitv.voice.STOPSPEAKING").setComponent(VOICE));
    }

    static void say(Context context, String text) {
        xyz.erapi0neer.xingyi.VendorSpeech.lastSpokeAt = android.os.SystemClock.uptimeMillis();
        start(context, new Intent("com.xiaomi.voicecontrol.PLAY_TTS").setComponent(TTS).putExtra("text", text));
    }

    private static void start(Context context, Intent intent) {
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            Log.w(BridgeLog.TAG, "xiaoai " + intent.getAction() + " failed: " + e);
        }
    }

    private Xiaoai() { }
}
