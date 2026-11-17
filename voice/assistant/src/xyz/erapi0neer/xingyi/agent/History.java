package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** What 星仪 did, one entry per finished task (files/agent/history.json), so 「昨天晚上放的那个视频再放一遍」 can be
 * answered after recent_turns (three hours) has forgotten it. Pure logic; the TV side only loads and saves the
 * JSONObject. Capped at 500 entries and 30 days: the file is read on every recall, and the TV has 2 GB of RAM. */
public final class History {
    public static final int MAX_ITEMS = 500, MAX_DAYS = 30, DEFAULT_DAYS = 7, MAX_RESULTS = 8;
    public static final int MAX_SAID = 80, MAX_REPLIED = 120, MAX_FIELD = 60;
    private static final long DAY = 24L * 60 * 60 * 1000;
    /** 「林俊杰的歌」 must not become four unrelated hits, but a misheard word still has to find its task. */
    private static final String FILLER = "的了个那这一吗呢吧再遍放看";
    private static final String SPLIT = "\\s+|[，。！？、；：,.!?;:~—…「」《》()（）\\[\\]{}\"'“”‘’-]+";

    private History() { }

    /** Appends one finished task. An empty {@code said} is not a task and is skipped; everything is cut to what is
     * worth keeping, and the log is trimmed to the last 30 days and 500 entries. */
    public static void add(JSONObject log, String said, String replied, String state, String playing, String app,
            long now) throws JSONException {
        String s = cut(said, MAX_SAID);
        if (s.isEmpty()) return;
        JSONArray items = items(log);
        JSONObject e = new JSONObject().put("at", now).put("said", s).put("replied", cut(replied, MAX_REPLIED));
        if (!clean(state).isEmpty()) e.put("state", clean(state));
        if (!clean(playing).isEmpty()) e.put("playing", cut(clean(playing), MAX_FIELD));
        if (!clean(app).isEmpty()) e.put("app", cut(clean(app), MAX_FIELD));
        items.put(e);
        trim(items, now);
    }

    /** The entries of the last {@code days} days (1–30, 0 → 7) that match {@code query}, newest first, at most 8.
     * Matching widens only when the narrower way found nothing: the whole query, then its parts, then its
     * overlapping two-character pieces. */
    public static List<JSONObject> query(JSONObject log, String query, int days, long now) {
        long from = now - window(days) * DAY;
        List<JSONObject> recent = new ArrayList<>();
        JSONArray items = log == null ? null : log.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.optLong("at") >= from && it.optLong("at") <= now) recent.add(it);
        }
        Collections.reverse(recent); // stored oldest first, answered newest first
        String q = clean(query);
        if (q.isEmpty()) return head(recent);
        List<JSONObject> hit = matching(recent, q);
        if (hit.isEmpty()) hit = matchingParts(recent, parts(q));
        if (hit.isEmpty()) hit = matchingPieces(recent, pieces(q));
        return head(hit);
    }

    /** 「昨天 22:58 你说「…」，星仪回答「…」（当时在放《…》）」 — the last part only when something was playing. */
    public static String describe(JSONObject item, long now, TimeZone tz) {
        StringBuilder b = new StringBuilder(Notes.when(item.optLong("at"), now, tz));
        b.append(" 你说「").append(item.optString("said")).append("」，星仪回答「").append(item.optString("replied")).append("」");
        String playing = item.optString("playing");
        if (!playing.isEmpty()) b.append("（当时在放《").append(playing).append("》）");
        return b.toString();
    }

    // ---- matching --------------------------------------------------------------------------------

    private static List<JSONObject> matching(List<JSONObject> items, String q) {
        List<JSONObject> hit = new ArrayList<>();
        for (JSONObject it : items) if (haystack(it).contains(q)) hit.add(it);
        return hit;
    }

    /** Every part of the query must be somewhere in the entry: 「林俊杰 视频」 needs both words. */
    private static List<JSONObject> matchingParts(List<JSONObject> items, List<String> parts) {
        List<JSONObject> hit = new ArrayList<>();
        if (parts.isEmpty()) return hit;
        for (JSONObject it : items) {
            String hay = haystack(it);
            boolean all = true;
            for (String p : parts) all &= hay.contains(p);
            if (all) hit.add(it);
        }
        return hit;
    }

    /** The last resort for a query the recognizer got wrong: half of its two-character pieces is enough. */
    private static List<JSONObject> matchingPieces(List<JSONObject> items, List<String> pieces) {
        List<JSONObject> hit = new ArrayList<>();
        if (pieces.isEmpty()) return hit;
        int need = (pieces.size() + 1) / 2;
        for (JSONObject it : items) {
            String hay = haystack(it);
            int found = 0;
            for (String p : pieces) if (hay.contains(p)) found++;
            if (found >= need) hit.add(it);
        }
        return hit;
    }

    private static List<String> parts(String q) {
        List<String> out = new ArrayList<>();
        for (String p : q.split(SPLIT)) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** 「林俊杰的歌」 → 林俊、俊杰、杰的、的歌, without the pieces that are only filler. */
    private static List<String> pieces(String q) {
        List<String> out = new ArrayList<>();
        String flat = q.replaceAll(SPLIT, "");
        for (int i = 0; i + 2 <= flat.length(); i++) {
            String p = flat.substring(i, i + 2);
            if (!filler(p)) out.add(p);
        }
        return out;
    }

    private static boolean filler(String piece) {
        for (int i = 0; i < piece.length(); i++) if (FILLER.indexOf(piece.charAt(i)) < 0) return false;
        return true;
    }

    private static String haystack(JSONObject item) {
        return item.optString("said") + "\n" + item.optString("replied") + "\n"
                + item.optString("playing") + "\n" + item.optString("app");
    }

    private static List<JSONObject> head(List<JSONObject> items) {
        return items.size() <= MAX_RESULTS ? items : new ArrayList<>(items.subList(0, MAX_RESULTS));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** How many days a recall really looks at: the contract says 1 to 30, and nothing at all means a week. */
    static int window(int days) { return days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS); }

    private static JSONArray items(JSONObject log) throws JSONException {
        JSONArray a = log.optJSONArray("items");
        if (a == null) { a = new JSONArray(); log.put("items", a); }
        return a;
    }

    /** Stored oldest first, so the entries that go are always at the front. */
    private static void trim(JSONArray items, long now) {
        long from = now - MAX_DAYS * DAY;
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || it.optLong("at") < from) items.remove(i);
        }
        while (items.length() > MAX_ITEMS) items.remove(0);
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    private static String cut(String s, int max) {
        String t = clean(s);
        return t.length() <= max ? t : t.substring(0, max);
    }
}
