package xyz.erapi0neer.xingyi;

import android.content.*;

/** ADB-authorized commissioning. Responses contain state and counts only. */
public final class AutoRelayDiagnostic extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        try {
            String action=i.getAction();
            if("xyz.erapi0neer.xingyi.RELAY_TRIAL".equals(action)) {
                AutoRelaySettings.armTrial(c,i.getStringExtra("owner"),i.getStringExtra("expected"));
            } else if("xyz.erapi0neer.xingyi.RELAY_ENABLE".equals(action) && i.getBooleanExtra("owner_opt_in",false)) {
                AutoRelaySettings.enable(c,i.getStringExtra("owner"));
            } else if("xyz.erapi0neer.xingyi.QUIET_REFUSALS".equals(action) && i.hasExtra("enabled")) {
                AutoRelaySettings.setQuietRefusals(c,i.getBooleanExtra("enabled",false));
            } else if("xyz.erapi0neer.xingyi.RELAY_DISABLE".equals(action)) {
                AutoRelaySettings.disable(c);
            } else if("xyz.erapi0neer.xingyi.RELAY_VERIFY".equals(action)) {
                AutoRelayService.beginVerification();
            } else if(!"xyz.erapi0neer.xingyi.RELAY_STATUS".equals(action)) { setResultCode(0);return; }
            setResult(1,AutoRelayService.receipt().put("enabled",!AutoRelaySettings.owner(c).isEmpty())
                    .put("quiet_refusals",AutoRelaySettings.quietRefusals(c)).toString(),null);
        } catch(Exception e) { setResult(0,"{}",null); }
    }
}
