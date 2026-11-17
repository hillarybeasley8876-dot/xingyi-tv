package xyz.erapi0neer.xingyi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.UUID;

/** Device-local timer. Tokens make canceled or superseded alarm deliveries harmless. */
public final class SleepTimer {
    static final String ACTION = "xyz.erapi0neer.xingyi.SLEEP_TIMER";
    static final String EXTRA_TOKEN = "timer_token";
    private static final Object LOCK = new Object();
    private static String activeToken;
    private static LocalAdb activeConnection;

    private SleepTimer() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("timer", 0); }
    private static int boot(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }
    private static AlarmManager alarms(Context c) {
        AlarmManager manager = (AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) throw new IllegalStateException("电视未提供定时服务");
        return manager;
    }
    private static PendingIntent operation(Context c, String token, boolean create) {
        Intent intent = new Intent(c, TimerReceiver.class).setAction(ACTION)
                .setData(Uri.parse("xingyi://sleep/" + token)).putExtra(EXTRA_TOKEN, token);
        return PendingIntent.getBroadcast(c, 901, intent,
                (create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE)
                        | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void removeAlarm(Context c, String token) {
        if (token != null && !token.isEmpty()) {
            PendingIntent p = operation(c, token, false);
            if (p != null) { alarms(c).cancel(p); p.cancel(); }
        }
        // Remove the old 0.2 tokenless alarm when updating an existing installation.
        PendingIntent legacy = PendingIntent.getBroadcast(c, 901, new Intent(c, TimerReceiver.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (legacy != null) { alarms(c).cancel(legacy); legacy.cancel(); }
    }
    private static void interruptActive() {
        if (activeConnection != null) activeConnection.cancel();
        activeConnection = null;
        activeToken = null;
    }
    private static void save(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("无法保存定时状态，请稍后重试");
    }
    private static boolean current(SharedPreferences p, String token, int bootCount) {
        return token != null && !token.isEmpty() && token.equals(p.getString("token", ""))
                && p.getInt("boot", -1) == bootCount;
    }

    public static void schedule(Context c, int minutes) {
        if (minutes < 1 || minutes > 120) throw new IllegalArgumentException("定时范围为 1–120 分钟");
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            String previous = p.getString("token", ""), token = UUID.randomUUID().toString();
            long duration = minutes * 60000L;
            long elapsed = SystemClock.elapsedRealtime() + duration;
            // Persist the new generation before touching the OS alarm queue.
            save(p.edit().putString("token", token).putString("state", "scheduled")
                    .putInt("boot", boot(c)).putLong("elapsedDeadline", elapsed)
                    .putLong("deadline", System.currentTimeMillis() + duration).remove("startedElapsed").remove("lastResult"));
            interruptActive();
            try {
                removeAlarm(c, previous);
                alarms(c).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        elapsed, operation(c, token, true));
            } catch (RuntimeException failure) {
                finish(c, token, "定时设置失败，请稍后重试");
                throw new IllegalStateException("定时设置失败，请稍后重试");
            }
        }
    }

    public static void cancel(Context c) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            String token = p.getString("token", "");
            // Invalidate first: an already queued broadcast must see canceled state.
            save(p.edit().remove("token").remove("deadline").remove("elapsedDeadline")
                    .remove("startedElapsed").putString("state", "canceled").putString("lastResult", "已取消定时休眠"));
            interruptActive();
            removeAlarm(c, token);
        }
    }

    static boolean isDue(Context c, String token) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            return current(p, token, boot(c)) && "scheduled".equals(p.getString("state", ""))
                    && p.getLong("elapsedDeadline", Long.MAX_VALUE) <= SystemClock.elapsedRealtime();
        }
    }
    static boolean claim(Context c, String token) {
        synchronized (LOCK) {
            if (!isDue(c, token)) return false;
            save(prefs(c).edit().putString("state", "running").putLong("startedElapsed", SystemClock.elapsedRealtime()));
            return true;
        }
    }
    static boolean attach(Context c, String token, LocalAdb connection) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            if (!current(p, token, boot(c)) || !"running".equals(p.getString("state", ""))) {
                connection.cancel();
                return false;
            }
            interruptActive();
            activeToken = token;
            activeConnection = connection;
            return true;
        }
    }
    static void detach(String token, LocalAdb connection) {
        synchronized (LOCK) {
            connection.cancel();
            if (token.equals(activeToken) && connection == activeConnection) {
                activeToken = null;
                activeConnection = null;
            }
        }
    }
    static void finish(Context c, String token, String message) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            if (!current(p, token, boot(c))) return;
            String state = p.getString("state", "");
            if (!"scheduled".equals(state) && !"running".equals(state)) return;
            save(p.edit().remove("deadline").remove("elapsedDeadline")
                    .remove("startedElapsed").putString("state", "finished").putString("lastResult", message));
        }
    }

    /** Uses the monotonic clock, so a wall-clock correction cannot alter the countdown. */
    public static int remainingMinutes(Context c) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            if (p.getInt("boot", -1) != boot(c) || !"scheduled".equals(p.getString("state", ""))) return 0;
            long left = p.getLong("elapsedDeadline", 0) - SystemClock.elapsedRealtime();
            return (int)Math.max(0, (left + 59999) / 60000);
        }
    }
    /** A query about the current schedule must not report an earlier alarm's execution as a new failure. */
    public static String currentStatus(Context c) {
        synchronized (LOCK) {
            String state = prefs(c).getString("state", "");
            if ("scheduled".equals(state) || "running".equals(state)) return getStatus(c);
            return "当前未设置定时休眠。";
        }
    }

    public static String getStatus(Context c) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            if (p.getInt("boot", -1) != boot(c) && p.contains("deadline")) return "电视已重启，原定时已失效";
            String state = p.getString("state", "");
            if ("scheduled".equals(state)) {
                int minutes = remainingMinutes(c);
                return minutes > 0 ? "约 " + minutes + " 分钟后休眠" : "已到定时时间，等待系统执行";
            }
            if ("running".equals(state)) {
                long started = p.getLong("startedElapsed", 0);
                // Process death can prevent onDestroy / final result persistence. Never retry a power key here.
                if (started <= 0 || SystemClock.elapsedRealtime() - started > 120000)
                    return "上次定时执行结果未确认，请重新设置";
                return "正在执行定时休眠";
            }
            return p.getString("lastResult", "未设置定时休眠");
        }
    }
}

