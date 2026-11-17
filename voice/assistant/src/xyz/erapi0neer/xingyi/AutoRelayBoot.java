package xyz.erapi0neer.xingyi;

import android.content.*;

public final class AutoRelayBoot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        boolean resumed = Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction());
        if (resumed && !AutoRelaySettings.owner(c).isEmpty())
            AutoRelaySettings.launch(c,new Intent(c,AutoRelayService.class));
    }
}
