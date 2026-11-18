package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** What a task's timing trace adds up to, and what a run of tasks adds up to. Both are read by the host from
 * files/agent/perf.jsonl, so neither may ever throw: a broken trace reports zeros instead of failing a task. */
public final class Perf {
    private Perf() { }

    /** One task: how long the model rounds took, how long the tools took, which tool was slowest, and how long the
     * user waited for the first word. firstSpeechMs is -1 when nothing was ever said. */
    public static JSONObject summary(List<JSONObject> timings, long wallMs, long firstSpeechMs) {
        JSONObject out = new JSONObject();
        long modelMs = 0, toolsMs = 0, maxRound = 0, firstRound = 0, slowest = 0, setupMs = -1, tailMs = -1;
        int rounds = 0;
        String slowestTool = "";
        for (JSONObject t : timings == null ? Collections.<JSONObject>emptyList() : timings) {
            if (t == null) continue;
            String kind = t.optString("kind");
            long ms = t.optLong("ms");
            if ("model".equals(kind)) {
                if (rounds++ == 0) firstRound = ms;
                modelMs += ms;
                if (ms > maxRound) maxRound = ms;
            } else if ("tool".equals(kind)) {
                toolsMs += ms;
                if (ms > slowest || slowestTool.isEmpty()) { slowest = ms; slowestTool = t.optString("name"); }
            } else if ("phase".equals(kind)) {
                // AgentEngine's two lines around the loop: what was spent before the first model round and after it
                if ("setup".equals(t.optString("name"))) setupMs = ms;
                else if ("tail".equals(t.optString("name"))) tailMs = ms;
            }
        }
        put(out, "rounds", rounds);
        put(out, "model_ms", modelMs);
        put(out, "first_round_ms", firstRound);
        put(out, "max_round_ms", maxRound);
        put(out, "tools_ms", toolsMs);
        put(out, "slowest_tool", slowestTool);
        put(out, "slowest_tool_ms", slowest);
        put(out, "wall_ms", wallMs);
        put(out, "first_speech_ms", firstSpeechMs);
        // -1: a task from before 2026-11-18, or the quick path, which has no loop to measure around
        put(out, "setup_ms", setupMs);
        put(out, "tail_ms", tailMs);
        return out;
    }

    /** The middle of a run of tasks: the medians a user actually feels. Tasks that never spoke are left out of the
     * speech median, and tasks with no model round (the quick path) out of both round medians. No tasks, just the
     * count. */
    public static JSONObject rollup(List<JSONObject> summaries) {
        JSONObject out = new JSONObject();
        List<Long> wall = new ArrayList<>(), firstRound = new ArrayList<>(), round = new ArrayList<>(), speech = new ArrayList<>(),
                shown = new ArrayList<>();
        int tasks = 0;
        for (JSONObject s : summaries == null ? Collections.<JSONObject>emptyList() : summaries) {
            if (s == null) continue;
            tasks++;
            wall.add(s.optLong("wall_ms"));
            int rounds = s.optInt("rounds");
            if (rounds > 0) { firstRound.add(s.optLong("first_round_ms")); round.add(s.optLong("model_ms") / rounds); }
            long spoke = s.optLong("first_speech_ms", -1);
            if (spoke >= 0) speech.add(spoke);
            // when the answer first showed (a streamed answer's first page, or the whole one): since 2026-11-06
            long seen = s.optLong("first_shown_ms", -1);
            if (seen >= 0) shown.add(seen);
        }
        put(out, "tasks", tasks);
        if (tasks == 0) return out;
        put(out, "median_wall_ms", median(wall));
        put(out, "median_first_round_ms", median(firstRound));
        put(out, "median_round_ms", median(round));
        put(out, "median_first_speech_ms", median(speech));
        if (!shown.isEmpty()) put(out, "median_first_shown_ms", median(shown));
        return out;
    }

    /** The middle value; the mean of the middle two when the count is even. Nothing to measure is 0. */
    static long median(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static void put(JSONObject o, String key, Object value) {
        try { o.put(key, value); } catch (JSONException ignored) { }
    }
}
