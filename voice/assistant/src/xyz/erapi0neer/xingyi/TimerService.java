package xyz.erapi0neer.xingyi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes alarm work; canceled generations never overwrite newer timer state. */
public class TimerService extends Service {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LocalAdb active;
    private final Set<String> receivedTokens = new HashSet<>(); // main-thread only
    private volatile boolean destroyed;
    private int pendingJobs;
    private int latestStartId;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        final String token = intent == null ? null : intent.getStringExtra(SleepTimer.EXTRA_TOKEN);
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("sleep", "定时休眠", NotificationManager.IMPORTANCE_LOW));
        startForeground(901, new Notification.Builder(this, "sleep")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("星仪定时休眠")
                .setContentText("正在检查定时并让电视进入待机").build());
        if (token != null) receivedTokens.add(token);
        pendingJobs++;
        worker.execute(() -> {
            LocalAdb connection = null;
            boolean claimed = false;
            try {
                if (destroyed || !(claimed = SleepTimer.claim(this, token))) return;
                connection = new LocalAdb(this);
                if (!SleepTimer.attach(this, token, connection)) return;
                active = connection;
                if (destroyed) { connection.cancel(); return; }
                connection.sleep();
                SleepTimer.finish(this, token, "已确认电视进入待机");
            } catch (Exception error) {
                SleepTimer.finish(this, token, "定时休眠失败，请检查电视本机调试授权后重新设置");
            } finally {
                if (claimed && destroyed) SleepTimer.finish(this, token, "定时执行已中断，请重新设置");
                if (connection != null) SleepTimer.detach(token, connection);
                active = null;
                main.post(() -> {
                    receivedTokens.remove(token);
                    pendingJobs--;
                    if (!destroyed && pendingJobs == 0) {
                        stopForeground(true);
                        stopSelfResult(latestStartId);
                    }
                });
            }
        });
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        destroyed = true;
        LocalAdb connection = active;
        if (connection != null) connection.cancel();
        for (String token : receivedTokens) SleepTimer.finish(this, token, "定时执行已中断，请重新设置");
        worker.shutdownNow();
        super.onDestroy();
    }
}


