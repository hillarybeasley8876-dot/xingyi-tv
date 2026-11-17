package xyz.erapi0neer.xingyi.agent;

import java.util.Map;
import java.util.TreeMap;
import org.json.*;

/**
 * A streamed chat completion ("stream": true, server-sent events) put back together as the envelope a plain request
 * returns — {choices:[{index, finish_reason, message}], base_resp} — so MiniMaxClient.parseReply checks it exactly as
 * before, while the answer's text goes to a Sink as it arrives: the TV starts reading a long answer at its first
 * sentences instead of after the whole of it (2026-11-06 overnight pass: 「详细讲讲…」 took 6.6–6.9 s before a word was
 * said; the gateway streams the first sentence in about 2 s, tests/agent/stream_probe.py).
 *
 * MiniMax repeats role, name and audio_content in every delta, sends each tool call whole with its index, and ends with
 * a chunk of no choices carrying usage and base_resp, then [DONE] (tests/agent/fixtures/sse-*.txt). Arguments split
 * over several deltas, as other OpenAI-style servers send them, are joined.
 */
public final class Sse {
    /** Receives the answer as it streams, on the request's own thread: keep it quick. */
    public interface Sink {
        /** The content so far, whole, each time it grows. Reasoning is never passed on. */
        void text(String soFar);
        /** The round calls a tool, so it is not a plain answer. Called once, before any later text. */
        void calls();
    }

    private final Sink sink;
    private final StringBuilder content = new StringBuilder(), reasoning = new StringBuilder();
    private final Map<Integer, JSONObject> calls = new TreeMap<>();
    private boolean sawContent, sawCalls, done;
    private String role = "assistant", name, finish;
    private Object audio;
    private JSONObject baseResp, error;

    public Sse(Sink sink) { this.sink = sink; }

    /** One line of the stream. Blank lines, comments and event: lines are skipped. False once [DONE] has come. */
    public boolean line(String line) throws JSONException {
        String l = line == null ? "" : line.trim();
        if (!l.startsWith("data:")) return !done;
        String data = l.substring(5).trim();
        if (data.isEmpty()) return !done;
        if (data.equals("[DONE]")) { done = true; return false; }
        JSONObject chunk = new JSONObject(data);
        JSONObject br = chunk.optJSONObject("base_resp");
        if (br != null) baseResp = br;
        if (chunk.opt("error") instanceof JSONObject) error = chunk;
        JSONArray choices = chunk.optJSONArray("choices");
        if (choices == null) return !done;
        for (int i = 0; i < choices.length(); i++) {
            JSONObject c = choices.optJSONObject(i);
            if (c == null || c.optInt("index", 0) != 0) continue;
            Object f = c.opt("finish_reason");
            if (f instanceof String && !((String) f).isEmpty()) finish = (String) f;
            JSONObject d = c.optJSONObject("delta");
            if (d != null) delta(d);
            else if (c.optJSONObject("message") != null) whole(c.getJSONObject("message"));
        }
        return !done;
    }

    /** A chunk that carries the whole message instead of a delta (MiniMax's own API ends its stream this way): it
     * replaces what the deltas built, so the answer is not doubled. */
    private void whole(JSONObject m) throws JSONException {
        String before = content.toString();
        content.setLength(0);
        reasoning.setLength(0);
        calls.clear();
        boolean had = sawCalls;
        delta(m);
        String now = content.toString();
        if (now.isEmpty() && !before.isEmpty() && !m.has("tool_calls")) content.append(before); // an empty final message
        if (had) sawCalls = true;
    }

    private void delta(JSONObject d) throws JSONException {
        if (d.opt("role") instanceof String) role = d.getString("role");
        if (d.opt("name") instanceof String) name = d.getString("name");
        if (d.has("audio_content")) audio = d.opt("audio_content");
        Object rc = d.opt("reasoning_content");
        if (rc instanceof String) reasoning.append((String) rc);
        JSONArray tc = d.optJSONArray("tool_calls");
        if (tc != null) {
            for (int j = 0; j < tc.length(); j++) {
                JSONObject t = tc.getJSONObject(j);
                int index = t.optInt("index", j);
                JSONObject call = calls.get(index);
                if (call == null) {
                    call = new JSONObject().put("id", "").put("type", "function")
                            .put("function", new JSONObject().put("name", "").put("arguments", ""));
                    calls.put(index, call);
                }
                if (t.opt("id") instanceof String && !t.getString("id").isEmpty()) call.put("id", t.getString("id"));
                if (t.opt("type") instanceof String && !t.getString("type").isEmpty()) call.put("type", t.getString("type"));
                JSONObject fn = t.optJSONObject("function");
                if (fn != null) {
                    JSONObject cf = call.getJSONObject("function");
                    if (fn.opt("name") instanceof String) cf.put("name", cf.getString("name") + fn.getString("name"));
                    if (fn.opt("arguments") instanceof String) cf.put("arguments", cf.getString("arguments") + fn.getString("arguments"));
                }
            }
            if (!sawCalls && tc.length() > 0) {
                sawCalls = true;
                if (sink != null) sink.calls();
            }
        }
        if (d.has("content")) {
            sawContent = true;
            Object ct = d.opt("content");
            if (ct instanceof String && !((String) ct).isEmpty()) {
                content.append((String) ct);
                if (sink != null && !sawCalls) sink.text(content.toString());
            }
        }
    }

    /** Whether the stream said [DONE]. */
    public boolean done() { return done; }

    /** Whether the answer came to an end (a finish_reason arrived): a stream without one and without [DONE] was cut. */
    public boolean finished() { return finish != null; }

    /** The error envelope the stream carried instead of an answer ({"type":"error","error":{…}}), or null. */
    public JSONObject error() { return error; }

    /** Whether any of the answer's text has reached the sink. */
    public boolean spoke() { return content.length() > 0 && !sawCalls; }

    /** The envelope a plain request would have returned. A stream cut short has no finish_reason, which parseReply
     * refuses as unfinished. */
    public JSONObject envelope() throws JSONException {
        JSONObject m = new JSONObject().put("role", role);
        if (name != null) m.put("name", name);
        if (audio != null) m.put("audio_content", audio);
        if (reasoning.length() > 0) m.put("reasoning_content", reasoning.toString());
        if (sawContent || calls.isEmpty()) m.put("content", content.toString());
        if (!calls.isEmpty()) {
            JSONArray a = new JSONArray();
            for (JSONObject c : calls.values()) a.put(c);
            m.put("tool_calls", a);
        }
        JSONObject choice = new JSONObject().put("index", 0).put("message", m).put("finish_reason", finish == null ? "" : finish);
        JSONObject env = new JSONObject().put("choices", new JSONArray().put(choice));
        if (baseResp != null) env.put("base_resp", baseResp);
        return env;
    }
}
