package xyz.erapi0neer.xingyi.agent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * What find_installer already found for an app, remembered for ten minutes. The model plans and then looks the app up
 * again in the same task (2026-11-08 on the TV: two searches, ~10 s each), and the answer does not change in between:
 * the second call is served from here, without a network call and without spending the day's search budget.
 *
 * The app name is the key as the model writes it — trimmed and lower case, so 「TV Bro」 and 「tv bro 」 are one app.
 * Nothing found for one app is ever served for another.
 */
public final class InstallerCache {
    /** How long a found installer stays good: long enough for one task, short enough that a maker's new release is
     * looked up again the next time the user asks. */
    public static final long TEN_MINUTES = 600_000;

    private static final class Entry {
        final JSONObject result;
        final long at;
        Entry(JSONObject result, long at) { this.result = result; this.at = at; }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    /** The remembered result for app, or null when there is none or it has gone stale. A copy: what the caller adds
     * to it (「cached」) must not change what the next caller gets. */
    public synchronized JSONObject get(String app, long now) {
        String key = key(app);
        Entry e = entries.get(key);
        if (e == null) return null;
        if (now - e.at >= TEN_MINUTES) { entries.remove(key); return null; }
        return copy(e.result);
    }

    /** Remembers result for app as of now. */
    public synchronized void put(String app, JSONObject result, long now) {
        String key = key(app);
        JSONObject kept = copy(result);
        if (key.isEmpty() || kept == null) return;
        entries.put(key, new Entry(kept, now));
    }

    /** How many apps are remembered (stale ones included, they are dropped when asked for); for the checks. */
    public synchronized int size() { return entries.size(); }

    /** A result that cannot be copied is not worth keeping or serving. */
    private static JSONObject copy(JSONObject result) {
        if (result == null) return null;
        try { return new JSONObject(result.toString()); }
        catch (Exception e) { return null; }
    }

    private static String key(String app) { return app == null ? "" : app.trim().toLowerCase(Locale.ROOT); }
}
