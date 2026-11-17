package xyz.erapi0neer.hudpreview;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.Method;

/** Renders the panel samples (AgentDebug.renderSamples) into this app's external files directory. Reflection, because
 * renderSamples is private and AgentDebug.onReceive would start 星仪's whole engine in this process. */
public final class Render extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String out;
        try {
            Method m = Class.forName("xyz.erapi0neer.xingyi.agent.tv.AgentDebug").getDeclaredMethod("renderSamples", Context.class);
            m.setAccessible(true);
            out = String.valueOf(m.invoke(null, context));
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            out = "error: " + c;
        }
        if (isOrderedBroadcast()) setResultData(out);
    }
}
