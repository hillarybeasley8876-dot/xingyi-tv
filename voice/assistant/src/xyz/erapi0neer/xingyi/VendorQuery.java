package xyz.erapi0neer.xingyi;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dispatches explicitly supplied user text to Xiaomi's existing assistant. No audio or credentials. */
public final class VendorQuery {
    private static final ComponentName SERVICE = new ComponentName(
            "com.xiaomi.voicecontrol", "com.xiaomi.voicecontrol.VoiceControlService");
    private static final AtomicBoolean DISPATCHING = new AtomicBoolean(false);
    private VendorQuery() { }
    public interface Callback { void onResult(String message); }

    public static boolean isAvailable(Context context) {
        try {
            ServiceInfo info = context.getPackageManager().getServiceInfo(SERVICE, 0);
            return info.enabled && info.applicationInfo.enabled && info.exported
                    && (info.permission == null || context.checkSelfPermission(info.permission)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED);
        } catch (Exception ignored) { return false; }
    }

    /** Result confirms dispatch only. Xiaomi has no external completion callback for this entry point. */
    public static void dispatch(Context context, String userText, Callback callback) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Context app = context.getApplicationContext();
        final String text = userText == null ? "" : userText.trim();
        handler.post(() -> {
            if (text.isEmpty()) { notify(callback, "请先输入要交给小爱的话"); return; }
            if (text.length() > 1000) { notify(callback, "文字过长，请缩短后再提交"); return; }
            if (!isAvailable(app)) { notify(callback, "小爱文字入口不可用"); return; }
            if (!DISPATCHING.compareAndSet(false, true)) { notify(callback, "上一条请求正在提交"); return; }
            try {
                ComponentName started = app.startService(new Intent("com.xiaomi.mitv.voice.EXTERNAL_TEXT_START")
                        .setComponent(SERVICE));
                if (started == null) { DISPATCHING.set(false); notify(callback, "未能启动小爱文字请求"); return; }
            } catch (RuntimeException error) {
                DISPATCHING.set(false); notify(callback, "系统拒绝小爱文字请求"); return;
            }
            handler.postDelayed(() -> {
                String message;
                try {
                    ComponentName started = app.startService(new Intent("com.xiaomi.mitv.voice.EXTERNAL_TEXT_STOP")
                            .setComponent(SERVICE).putExtra("query", text));
                    message = started == null ? "未能提交小爱文字请求" : "已转交小爱，请查看电视上的执行结果";
                } catch (RuntimeException error) {
                    message = "提交小爱文字请求失败";
                } finally { DISPATCHING.set(false); }
                notify(callback, message);
            }, 300);
        });
    }
    private static void notify(Callback callback, String message) {
        if (callback != null) callback.onResult(message);
    }
}
