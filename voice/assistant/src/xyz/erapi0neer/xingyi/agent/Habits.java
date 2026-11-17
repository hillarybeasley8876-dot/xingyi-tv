package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** What 星仪 learns from what it actually does: which apps the user opens, whom they search on B站, which artists and
 * songs they play, the volume they settle on, which HDMI input they use, and at what time of day. Everything stays on the
 * TV (files/agent/habits.json); only the short summary from {@link #summarize} reaches the prompt, so 「放首歌」「老样子」
 * 「音量调回平时的」 resolve the way this user means them. Pure logic — the TV side loads and saves the JSONObject. */
public final class Habits {
    /** The file is read for every prompt and the TV has 2 GB of RAM: keep it small, recent and short-spoken. */
    public static final int MAX_EVENTS = 400, MAX_LINES = 8, MAX_LINE_CHARS = 40, MAX_VALUE_CHARS = 20;
    private static final long KEEP_MS = 90L * 86400_000L, SUMMARY_MS = 30L * 86400_000L;
    private static final String[] BANDS = {"凌晨", "早上", "上午", "中午", "下午", "傍晚", "晚上"};
    private static final int[][] BAND_HOURS = {{0, 5}, {6, 8}, {9, 11}, {12, 13}, {14, 17}, {18, 19}, {20, 23}};

    private Habits() { }

    /** Every successful tool call in an OpenAI-style transcript, as {name, args, ok, result}. Anything that cannot be
     * read (a call whose result is missing, arguments that are not a JSON object, a result that is not one) is skipped,
     * never thrown: habits must never break a task. */
    public static List<JSONObject> callsFromTranscript(JSONArray transcript) {
        List<JSONObject> out = new ArrayList<>();
        if (transcript == null) return out;
        Map<String, JSONObject> results = new HashMap<>();
        for (int i = 0; i < transcript.length(); i++) {
            JSONObject m = transcript.optJSONObject(i);
            if (m == null || !"tool".equals(m.optString("role"))) continue;
            String id = m.optString("tool_call_id", "");
            JSONObject r = parse(m.optString("content", ""));
            if (!id.isEmpty() && r != null) results.put(id, r);
        }
        for (int i = 0; i < transcript.length(); i++) {
            JSONObject m = transcript.optJSONObject(i);
            if (m == null || !"assistant".equals(m.optString("role"))) continue;
            JSONArray calls = m.optJSONArray("tool_calls");
            for (int c = 0; calls != null && c < calls.length(); c++) {
                JSONObject call = calls.optJSONObject(c), fn = call == null ? null : call.optJSONObject("function");
                JSONObject result = call == null ? null : results.get(call.optString("id", ""));
                if (fn == null || result == null || !result.optBoolean("ok")) continue;
                String name = fn.optString("name", "");
                JSONObject args = object(fn.opt("arguments"));
                if (name.isEmpty() || args == null) continue;
                try {
                    out.add(new JSONObject().put("name", name).put("args", args).put("ok", true).put("result", result));
                } catch (JSONException impossible) { }
            }
        }
        return out;
    }

    /** 0..2 events for one successful call: what the user asked for, never what the screen happened to say. */
    public static List<JSONObject> events(String tool, JSONObject args, JSONObject result, long now, int hour) {
        List<JSONObject> out = new ArrayList<>();
        JSONObject a = args == null ? new JSONObject() : args;
        String t = tool == null ? "" : tool;
        if ("open_app".equals(t)) add(out, "app", a.optString("app"), now, hour);
        else if ("bili_search".equals(t)) add(out, "bili", a.optString("keyword"), now, hour);
        else if ("netease_play".equals(t)) {
            add(out, "artist", a.optString("artist"), now, hour);
            add(out, "song", a.optString("title"), now, hour);
        }
        else if ("netease_playlist".equals(t)) add(out, "playlist", a.has("kind") ? a.optString("kind") : a.optString("name"), now, hour);
        else if ("set_volume".equals(t)) addVolume(out, a.opt("level"), now, hour);
        else if ("adjust_volume".equals(t)) addVolume(out, result == null ? null : result.opt("volume"), now, hour);
        else if ("switch_input".equals(t)) add(out, "input", a.optString("name"), now, hour);
        return out;
    }

    /** One event as it is stored: when it happened, the TV's local hour, what kind it is, and its value. */
    public static JSONObject event(String kind, String value, long now, int hour) {
        try {
            return new JSONObject().put("t", now).put("h", hour).put("kind", kind).put("value", value);
        } catch (JSONException impossible) { return new JSONObject(); }
    }

    private static void add(List<JSONObject> out, String kind, String value, long now, int hour) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return;
        out.add(event(kind, v, now, hour));
    }

    /** Only a level the TV could really be at: a whole number 0–100. */
    private static void addVolume(List<JSONObject> out, Object level, long now, int hour) {
        Integer v = number(level);
        if (v == null || v < 0 || v > 100) return;
        add(out, "volume", String.valueOf(v), now, hour);
    }

    private static Integer number(Object o) {
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            return d == Math.rint(d) ? (int) d : null;
        }
        if (!(o instanceof String)) return null;
        try { return Integer.valueOf(((String) o).trim()); } catch (NumberFormatException e) { return null; }
    }

    /** A JSON object from a string, or null when it is not one (a model's malformed arguments are skipped). */
    private static JSONObject parse(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            Object v = new JSONTokener(s).nextValue();
            return v instanceof JSONObject ? (JSONObject) v : null;
        } catch (JSONException e) { return null; }
    }

    private static JSONObject object(Object raw) {
        return raw instanceof JSONObject ? (JSONObject) raw : parse(raw instanceof String ? (String) raw : null);
    }

    // ---- the file --------------------------------------------------------------------------------

    /** Appends and prunes in one go: nothing older than 90 days survives, and at most 400 events (the oldest go). */
    public static void record(JSONObject habits, List<JSONObject> events, long now) throws JSONException {
        if (habits == null || events == null || events.isEmpty()) return;
        JSONArray all = habits.optJSONArray("events");
        if (all == null) { all = new JSONArray(); habits.put("events", all); }
        for (JSONObject e : events) all.put(e);
        long oldest = now - KEEP_MS;
        JSONArray kept = new JSONArray();
        for (int i = Math.max(0, all.length() - MAX_EVENTS); i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e != null && e.optLong("t") >= oldest) kept.put(e);
        }
        habits.put("events", kept);
    }

    /** Removes every event whose value mentions {@code what} (case-insensitive, spaces and punctuation ignored):
     * 「忘掉林俊杰」 also makes 星仪 forget that it ever played him. @return how many went */
    public static int forget(JSONObject habits, String what) throws JSONException {
        if (habits == null) return 0;
        String w = key(what);
        JSONArray all = habits.optJSONArray("events");
        if (w.isEmpty() || all == null) return 0;
        JSONArray kept = new JSONArray();
        int gone = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e == null) continue;
            if (mentions(e, w)) gone++; else kept.put(e);
        }
        habits.put("events", kept);
        return gone;
    }

    private static boolean mentions(JSONObject e, String what) {
        String value = e.optString("value", "");
        if (key(value).contains(what)) return true;
        // A playlist is stored as the tool's own argument (favorites), but the user says 「我喜欢的音乐」.
        return "playlist".equals(e.optString("kind")) && key(label(value)).contains(what);
    }

    private static String key(String s) { return s == null ? "" : Memory.norm(s).toLowerCase(Locale.ROOT); }

    // ---- the summary -----------------------------------------------------------------------------

    /** At most 8 short Chinese lines about the last 30 days, most telling first. Nothing below the thresholds:
     * a line that would rest on one or two events is not a habit and is not said. Seven kinds exist today, so the
     * 8-line cap is a guard for the next one. */
    public static List<String> summarize(JSONObject habits, long now) {
        Map<String, List<JSONObject>> byKind = recent(habits, now);
        List<String> lines = new ArrayList<>();
        countLine(lines, byKind.get("app"), "app", "常用应用", 3, 3);
        countLine(lines, byKind.get("bili"), "bili", "B站常搜", 2, 2);
        countLine(lines, byKind.get("artist"), "artist", "常听", 3, 2);
        countLine(lines, byKind.get("song"), "song", "常点的歌", 2, 2);
        countLine(lines, byKind.get("playlist"), "playlist", "常听歌单", 1, 2);
        volumeLine(lines, byKind.get("volume"));
        countLine(lines, byKind.get("input"), "input", "常用信号源", 1, 2);
        return lines.size() <= MAX_LINES ? lines : new ArrayList<>(lines.subList(0, MAX_LINES));
    }

    private static Map<String, List<JSONObject>> recent(JSONObject habits, long now) {
        Map<String, List<JSONObject>> byKind = new LinkedHashMap<>();
        JSONArray all = habits == null ? null : habits.optJSONArray("events");
        long from = now - SUMMARY_MS;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e == null || e.optLong("t") < from) continue;
            String kind = e.optString("kind", "");
            List<JSONObject> list = byKind.get(kind);
            if (list == null) { list = new ArrayList<>(); byKind.put(kind, list); }
            list.add(e);
        }
        return byKind;
    }

    /** 「常用应用：哔哩哔哩（12次）、网易云音乐（7次），多在晚上」 — the top {@code top} values seen at least
     * {@code min} times; items are dropped from the end until the line fits in 40 characters. */
    private static void countLine(List<String> out, List<JSONObject> events, String kind, String prefix, int top, int min) {
        if (events == null) return;
        List<Count> counts = counts(events, min);
        if (counts.isEmpty()) return;
        List<String> items = new ArrayList<>();
        for (int i = 0; i < Math.min(top, counts.size()); i++) items.add(item(counts.get(i), kind));
        out.add(fit(prefix, items, band(events)));
    }

    /** 「习惯音量：30 左右」 — the middle of at least three volumes, rounded to 5. */
    private static void volumeLine(List<String> out, List<JSONObject> events) {
        if (events == null) return;
        List<Integer> levels = new ArrayList<>();
        for (JSONObject e : events) {
            Integer v = number(e.opt("value"));
            if (v != null) levels.add(v);
        }
        if (levels.size() < 3) return;
        Collections.sort(levels);
        int mid = levels.size() / 2;
        int median = levels.size() % 2 == 1 ? levels.get(mid) : (levels.get(mid - 1) + levels.get(mid)) / 2;
        String line = "习惯音量：" + (int) (Math.round(median / 5.0) * 5) + " 左右";
        String band = band(events);
        out.add(band.isEmpty() ? line : line + "，多在" + band);
    }

    /** How often each value was seen, best first: most often, and among equals the one used most recently. */
    private static List<Count> counts(List<JSONObject> events, int min) {
        Map<String, Count> byValue = new LinkedHashMap<>();
        for (JSONObject e : events) {
            String v = e.optString("value", "").trim();
            if (v.isEmpty()) continue;
            Count c = byValue.get(v);
            if (c == null) { c = new Count(v); byValue.put(v, c); }
            c.n++;
            c.last = Math.max(c.last, e.optLong("t"));
        }
        List<Count> out = new ArrayList<>();
        for (Count c : byValue.values()) if (c.n >= min) out.add(c);
        Collections.sort(out, new Comparator<Count>() {
            @Override public int compare(Count a, Count b) { return a.n != b.n ? b.n - a.n : Long.compare(b.last, a.last); }
        });
        return out;
    }

    private static final class Count {
        final String value;
        int n;
        long last;
        Count(String value) { this.value = value; }
    }

    private static String item(Count c, String kind) {
        String v = "song".equals(kind) ? "《" + c.value + "》" : "playlist".equals(kind) ? label(c.value) : c.value;
        return cut(v) + "（" + c.n + "次）";
    }

    /** netease_playlist speaks in kinds; the user says the name. */
    private static String label(String value) {
        if ("favorites".equals(value)) return "我喜欢的音乐";
        if ("fm".equals(value)) return "私人FM";
        return value;
    }

    private static String cut(String s) {
        return s.length() <= MAX_VALUE_CHARS ? s : s.substring(0, MAX_VALUE_CHARS - 1) + "…";
    }

    private static String fit(String prefix, List<String> items, String band) {
        String suffix = band.isEmpty() ? "" : "，多在" + band;
        List<String> kept = new ArrayList<>(items);
        String line = prefix + "：" + join(kept) + suffix;
        while (line.length() > MAX_LINE_CHARS && kept.size() > 1) {
            kept.remove(kept.size() - 1);
            line = prefix + "：" + join(kept) + suffix;
        }
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS);
    }

    private static String join(List<String> items) {
        StringBuilder s = new StringBuilder();
        for (String i : items) s.append(s.length() == 0 ? "" : "、").append(i);
        return s.toString();
    }

    /** The time of day a kind happens at, when it is plain enough to say: at least 5 events, and clearly more than
     * 60 % of them in one band (5 events with 3 in the evening say nothing). */
    private static String band(List<JSONObject> events) {
        if (events.size() < 5) return "";
        int[] per = new int[BANDS.length];
        for (JSONObject e : events) {
            int h = e.optInt("h", -1);
            for (int b = 0; b < BAND_HOURS.length; b++) if (h >= BAND_HOURS[b][0] && h <= BAND_HOURS[b][1]) per[b]++;
        }
        for (int b = 0; b < per.length; b++) if (per[b] * 5 > events.size() * 3) return BANDS[b];
        return "";
    }
}
