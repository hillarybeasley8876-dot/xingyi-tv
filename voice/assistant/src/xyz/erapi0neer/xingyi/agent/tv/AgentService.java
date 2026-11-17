package xyz.erapi0neer.xingyi.agent.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the agent's process in the foreground-service state. MiTV's PeriodicCleaner force-stops idle
 * packages, which also removes their accessibility service from the enabled list (seen 2026-11-04 mid-task). */
public final class AgentService extends Service {
    static void ensure(Context c) {
        try { c.startForegroundService(new Intent(c, AgentService.class)); }
        catch (RuntimeException ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("agent", "星仪", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "agent").setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("星仪").setContentText("随时接手小爱做不到的事").build();
        startForeground(2, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public IBinder onBind(Intent intent) { return null; }
}
