package xyz.erapi0neer.xingyi;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stops one recognized Xiaomi refusal at the vendor's own TTS player. */
final class QuietRefusals implements Runnable {
    private static final ComponentName PLAYER = new ComponentName("com.xiaomi.voicecontrol",
            "com.xiaomi.voicecontrol.VoiceControlService");
    private final Context context;
    private final Consumer<String> reply;
    interface LocalConfirmation { void accept(String id,String phrase,long elapsed); }
    private final LocalConfirmation localConfirmation;
    private static final Pattern LOCAL_CONTINUE=Pattern.compile(
            "^([0-9]{10})\\.([0-9]{3})[0-9]{0,3}\\s+(?:[0-9]+\\s+)?[0-9]+\\s+[0-9]+\\s+I\\s+=conversation=\\s*:\\s*[^|\\r\\n]{1,80} \\| updateQuery "
            +"((?:小爱同学[，, ]*)?(?:星仪[，, ]*)?(?:继续执行|确认执行)[。！!]?) ([a-fA-F0-9]{32})$");
    private volatile boolean closed;
    private LocalAdb reader;
    volatile String state = "starting";
    volatile int sent;
    volatile int matched;

    QuietRefusals(Context context, Consumer<String> reply, LocalConfirmation localConfirmation) {
        this.context = context.getApplicationContext();
        this.reply = reply;
        this.localConfirmation = localConfirmation;
    }

    @Override public void run() {
        int retryMs = 500;
        while (!closed) {
            LocalAdb connection = new LocalAdb(context);
            synchronized (this) {
                if (closed) return;
                reader = connection;
            }
            QuietRefusalGate gate = new QuietRefusalGate();
            try {
                state = "connecting";
                connection.watchXiaoAiTts(() -> state = "listening", line -> {
                    if(!closed && line.contains("updateQuery ")) {
                        Matcher event=LOCAL_CONTINUE.matcher(line);
                        if(event.matches()) {
                            long age=System.currentTimeMillis()-(Long.parseLong(event.group(1))*1000+Long.parseLong(event.group(2)));
                            if(age>=0 && age<=750)localConfirmation.accept(event.group(4),event.group(3),android.os.SystemClock.elapsedRealtime()-age);
                        }
                        return;
                    }
                    boolean stop = gate.offer(line,System.currentTimeMillis());
                    if (gate.currentReplyEvent() != null && !closed) reply.accept(gate.currentReplyEvent());
                    if (stop) stopCurrentRefusal();
                });
                retryMs = 500;
            } catch (IOException | RuntimeException unavailable) {
                state = "waiting_for_local_source";
                if (!closed) {
                    try { Thread.sleep(retryMs); }
                    catch (InterruptedException stop) { return; }
                    retryMs = Math.min(10000, retryMs * 2);
                }
            } finally {
                connection.cancel();
                synchronized (this) { if (reader == connection) reader = null; }
            }
        }
    }

    private synchronized void stopCurrentRefusal() {
        if (closed || !AutoRelaySettings.quietRefusals(context) || AutoRelaySettings.owner(context).isEmpty()) return;
        if (!((PowerManager)context.getSystemService(Context.POWER_SERVICE)).isInteractive()) return;
        matched++;
        Intent stop = new Intent("com.xiaomi.mitv.voice.STOPSPEAKING").setComponent(PLAYER);
        try { if (context.startService(stop) != null) sent++; }
        catch (RuntimeException unavailable) { state = "stop_unavailable"; }
    }

    synchronized void close() {
        closed = true;
        if (reader != null) reader.cancel();
    }
}
