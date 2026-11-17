package xyz.erapi0neer.orrery;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What 星仪's bridge knows about today: 农历, the weather, what is playing and a note per app. Read off the main thread
 * (one executor) and posted back to it. A missing provider, a SecurityException or bad JSON means nothing to show.
 */
final class HomeInfo {
    static final Uri AGENT = Uri.parse("content://xyz.erapi0neer.xiaoaibridge.agent");
    static final String METHOD = "home", KEY = "json", PLAYING = "playing";

    interface Callback { void onInfo(HomeInfo info); }

    /** Nothing playing is what an empty title says; state must be "playing" for the line to show at all. */
    final String lunar, weather, title, artist;
    final boolean playing;
    /** What 星仪 is doing: 待命, 执行中, 等你确认, 等你回答 — empty when the bridge has not said. */
    final String agent;
    /** Package -> what is new in it, for the line under an entry's name. */
    final Map<String, String> notes;

    static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    HomeInfo(String lunar, String weather, String title, String artist, boolean playing, String agent,
             Map<String, String> notes) {
        this.lunar = lunar;
        this.weather = weather;
        this.title = title;
        this.artist = artist;
        this.playing = playing;
        this.agent = agent;
        this.notes = notes;
    }

    /** Ask the bridge on the pool, answer on the main thread. Nothing is called back when there is nothing. */
    static void fetch(final Context c, final Callback cb) {
        POOL.execute(new Runnable() {
            public void run() {
                final HomeInfo info = read(c);
                if (info == null) return;
                MAIN.post(new Runnable() {
                    public void run() { cb.onInfo(info); }
                });
            }
        });
    }

    static HomeInfo read(Context c) {
        try {
            Bundle b = c.getContentResolver().call(AGENT, METHOD, null, null);
            String json = b == null ? null : b.getString(KEY);
            return json == null ? null : parse(json);
        } catch (Exception e) {
            return null;                    // no bridge, not allowed, nothing to show
        }
    }

    /** Every field is optional: what is missing is simply empty. */
    static HomeInfo parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject now = o.optJSONObject("now");
            return new HomeInfo(o.optString("lunar", ""), o.optString("weather", ""),
                    now == null ? "" : now.optString("title", ""), now == null ? "" : now.optString("artist", ""),
                    now != null && PLAYING.equals(now.optString("state", "")), agent(o),
                    notes(o.optJSONObject("notes")));
        } catch (JSONException e) {
            return null;
        }
    }

    /** 星仪's own state: a word, or an object with one. Anything else is nothing to show. */
    static String agent(JSONObject o) {
        Object a = o.opt("agent");
        if (a instanceof JSONObject) {
            JSONObject j = (JSONObject) a;
            String s = j.optString("state", "");
            return s.isEmpty() ? j.optString("word", "") : s;
        }
        return a instanceof String ? (String) a : "";
    }

    static Map<String, String> notes(JSONObject o) {
        Map<String, String> m = new HashMap<>();
        if (o == null) return m;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            String v = o.optString(k, "");
            if (!v.isEmpty()) m.put(k, v);
        }
        return m;
    }
}
