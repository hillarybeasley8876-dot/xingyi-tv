package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/**
 * The entries the PC's checks left in what 星仪 knows about the user (the 30-day history, the recent turns): those of
 * tasks whose perf line says they came from a check source. Until 2026-11-06 the silent checks ran as source mcp and
 * were remembered like the user's own requests — a night of test questions came back as 「你常查科技新闻、汇率和空气
 * 质量」; checks now run as source test or debug and leave nothing (AgentEngine.traced), and this takes the old ones out.
 */
public final class TestTraces {
    private TestTraces() { }

    /** How far apart an entry and its task's perf line may be written (both at the task's end, normally within ms). */
    static final long WINDOW_MS = 60_000L;

    /**
     * The entries (each {at, said, …}) that are not the trace of a check: a perf line of one of {@code sources}, at or
     * after {@code since}, for the same request (the same start) within WINDOW_MS. {@code removed[0]} counts the rest.
     */
    public static JSONArray without(JSONArray entries, List<JSONObject> perf, long since, Set<String> sources, int[] removed) {
        List<JSONObject> checks = new ArrayList<>();
        for (JSONObject p : perf == null ? Collections.<JSONObject>emptyList() : perf)
            if (p != null && p.optLong("at") >= since && sources.contains(p.optString("source"))) checks.add(p);
        JSONArray kept = new JSONArray();
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e != null && e.optLong("at") >= since - WINDOW_MS && isCheck(e, checks)) { removed[0]++; continue; }
            kept.put(entries.opt(i));
        }
        return kept;
    }

    private static boolean isCheck(JSONObject e, List<JSONObject> checks) {
        String said = norm(e.optString("said"));
        if (said.isEmpty()) return false;
        for (JSONObject p : checks) {
            if (Math.abs(e.optLong("at") - p.optLong("at")) > WINDOW_MS) continue;
            String u = norm(p.optString("utterance"));
            if (u.isEmpty()) continue;
            int n = Math.min(6, Math.min(said.length(), u.length()));
            if (said.startsWith(u) || u.startsWith(said) || said.substring(0, n).equals(u.substring(0, n))) return true;
        }
        return false;
    }

    private static String norm(String s) { return s == null ? "" : s.replaceAll("[\\s，。！？,.!?、：:；;“”\"]", ""); }
}
