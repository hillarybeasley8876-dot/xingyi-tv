package xyz.erapi0neer.xingyi.agent.tv;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** How the 星仪 screen reaches the agent runtime that lives in the bridge app. */
public final class AgentClient {
    public static final String RUNTIME = "xyz.erapi0neer.xiaoaibridge";
    /** The 星仪 app (its screen); the runtime hides the floating panel while it is in front. */
    public static final String SCREEN = "xyz.erapi0neer.xingyi";
    private static final Uri URI = Uri.parse("content://" + RUNTIME + ".agent");

    private AgentClient() { }

    /** Returns {result, state} or {result:"unavailable"} when the runtime is not installed or not reachable. */
    public static JSONObject call(Context c, String method, String arg, Bundle extras) {
        JSONObject out = new JSONObject();
        try {
            Bundle b = c.getContentResolver().call(URI, method, arg, extras);
            out.put("result", b == null ? "unavailable" : b.getString("result", ""));
            if (b != null && b.getString("state") != null) out.put("state", new JSONObject(b.getString("state")));
            if (b != null && b.getString("data") != null) out.put("data", b.getString("data"));
            if (b != null && b.containsKey("request")) out.put("request", b.getInt("request"));
        } catch (Exception e) {
            try { out.put("result", "unavailable"); } catch (Exception ignored) { }
        }
        return out;
    }

    public static String submit(Context c, String text, String source) {
        Bundle e = new Bundle();
        e.putString("source", source);
        return call(c, "submit", text, e).optString("result", "unavailable");
    }

    /** {result, request}: request is the id state().request shows while (and after) the started task runs; absent
     * unless result is "started" or "queued" (it runs after the task before it). */
    public static JSONObject submitTracked(Context c, String text, String source) {
        Bundle e = new Bundle();
        e.putString("source", source);
        return call(c, "submit", text, e);
    }

    public static JSONObject state(Context c) {
        JSONObject s = call(c, "state", null, null).optJSONObject("state");
        return s == null ? new JSONObject() : s;
    }

    public static void consent(Context c, boolean approve) {
        Bundle e = new Bundle();
        e.putBoolean("approve", approve);
        call(c, "consent", null, e);
    }

    /** Stops the running task, or drops the question 星仪 is waiting on. */
    public static void cancel(Context c) { call(c, "cancel", null, null); }

    /** {facts, aliases:[{heard, written}], habits:[line], notes:[{id, text, when}], schedules:[{id, text}], hints};
     * empty when the runtime is unreachable. */
    public static JSONObject memory(Context c) {
        try { return new JSONObject(call(c, "memory", null, null).optString("data", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** kind: fact | alias | note | schedule | habits; @return the sentence to show. */
    public static String forget(Context c, String kind, String which) {
        Bundle e = new Bundle();
        e.putString("kind", kind);
        return call(c, "forget", which, e).optString("result", "unavailable");
    }

    /** Newest first: [{when, said, replied, state, playing}]. */
    public static JSONArray history(Context c, String query, int days, int limit) {
        Bundle e = new Bundle();
        e.putInt("days", days);
        e.putInt("limit", limit);
        try { return new JSONArray(call(c, "history", query, e).optString("data", "[]")); }
        catch (Exception x) { return new JSONArray(); }
    }

    public static void hints(Context c, boolean on) {
        Bundle e = new Bundle();
        e.putBoolean("on", on);
        call(c, "hints", null, e);
    }
}
