package xyz.erapi0neer.xiaoaibridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Starts the bridge after boot, after an upgrade, and on a 20-second heartbeat. On this 2 GB TV the kernel's
 * low-memory killer takes the bridge down whenever a heavy app (Bilibili's phone client: ~1.35 GB) starts; alarms
 * survive that, and the moment the process is back its pending services (this one, the accessibility service)
 * restart at once instead of after the system's growing crash back-off. */
public final class BootReceiver extends BroadcastReceiver {
    static final String HEARTBEAT = "xyz.erapi0neer.xiaoaibridge.HEARTBEAT";
    private static final long PERIOD_MS = 20_000;

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || HEARTBEAT.equals(action)) {
            arm(context);
            context.startForegroundService(new Intent(context, BridgeService.class));
            PendingResult pending = goAsync();
            new Thread(() -> {
                try { AdbWatch.beat(context); }
                catch (RuntimeException ignored) { }  // the record must never take the heartbeat down
                // 星仪's schedule book (明天早上7点放新闻联播…): whatever is due runs now; tick() never throws
                try { xyz.erapi0neer.xingyi.agent.tv.Scheduler.get(context).tick(); }
                catch (RuntimeException ignored) { }
                // short of memory: stop the user's apps that sat in the background for minutes (B站: 1.3 GB); never throws
                try { xyz.erapi0neer.xingyi.agent.tv.MemoryGuard.get(context).tick(); }
                catch (RuntimeException ignored) { }
                finally { pending.finish(); }
            }, "adb-watch").start();
        }
    }

    static void arm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(c, BootReceiver.class).setAction(HEARTBEAT),
                PendingIntent.FLAG_UPDATE_CURRENT);
        am.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + PERIOD_MS, pi);
    }
}
