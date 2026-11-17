package xyz.erapi0neer.xingyi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TimerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SleepTimer.cancel(context);
            return;
        }
        if (!SleepTimer.ACTION.equals(intent.getAction())) return;
        String token = intent.getStringExtra(SleepTimer.EXTRA_TOKEN);
        if (!SleepTimer.isDue(context, token)) return;
        try {
            context.startForegroundService(new Intent(context, TimerService.class)
                    .putExtra(SleepTimer.EXTRA_TOKEN, token));
        } catch (RuntimeException error) {
            SleepTimer.finish(context, token, "系统未能启动定时休眠，请重新设置");
        }
    }
}
