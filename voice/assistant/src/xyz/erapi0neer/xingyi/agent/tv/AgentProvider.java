package xyz.erapi0neer.xingyi.agent.tv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Background entry for same-signature apps (the XiaoAi bridge): no need to bring 星仪 to the front.
 * call(method): submit(arg=text, extras.source) | state | consent(extras.approve) | cancel (stops the task or drops a
 * pending question) | memory → data | forget(arg=which, extras.kind) | history(arg=query, extras.days, extras.limit)
 * → data | hints(extras.on) → Bundle{result, state, data?} | home → Bundle{json} (what the home screen draws). */
public final class AgentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        AgentEngine engine = AgentEngine.get(getContext());
        Bundle out = new Bundle();
        switch (method == null ? "" : method) {
            case "submit": {
                String routed = engine.submit(arg, extras == null ? "xiaoai" : extras.getString("source", "xiaoai"));
                out.putString("result", routed);
                // the request id the started task carries in state().request, so the caller follows its own task
                if ("started".equals(routed) || "queued".equals(routed)) out.putInt("request", engine.lastRequest());
                break;
            }
            case "consent":
                engine.consent(extras != null && extras.getBoolean("approve"));
                out.putString("result", "ok");
                break;
            case "cancel":
                engine.stop();
                out.putString("result", "ok");
                break;
            case "memory":
                out.putString("data", engine.memory().toString());
                out.putString("result", "ok");
                break;
            case "forget":
                out.putString("result", engine.forgetItem(extras == null ? "" : extras.getString("kind", ""), arg));
                break;
            case "history":
                out.putString("data", engine.history(arg, extras == null ? 7 : extras.getInt("days", 7),
                        extras == null ? 50 : extras.getInt("limit", 50)).toString());
                out.putString("result", "ok");
                break;
            case "hints":
                engine.setHints(extras != null && extras.getBoolean("on", true));
                out.putString("result", "ok");
                break;
            case "home":
                // the home screen (Orrery): 农历, the day's weather and what is playing, as one JSON string
                out.putString("json", engine.homeInfo().toString());
                out.putString("result", "ok");
                break;
            case "state":
                out.putString("result", "ok");
                break;
            default:
                out.putString("result", "unknown_method");
        }
        out.putString("state", engine.state().toString());
        return out;
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
