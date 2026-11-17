package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** The record of one task: what was understood, the plan and its progress, what actually ran, and how it ended.
 * It drives the progress panel, is spoken from, and is handed back to the model when the user says 继续. */
public final class Ledger {
    public enum State {
        RUNNING, AWAITING_CONFIRMATION, AWAITING_USER, DONE, INTERRUPTED, FAILED, ANSWERED, CANCELLED;
        public boolean open() { return this == RUNNING || this == AWAITING_CONFIRMATION; }
        /** States a follow-up utterance can resume from. */
        public boolean resumable() { return this == INTERRUPTED || this == AWAITING_USER || this == CANCELLED; }
    }

    public static final class Step {
        public final String text;
        public String status = "pending", note = "";
        Step(String text) { this.text = text; }
    }

    public final String id, utterance, source;
    public final long startedAt;
    public String goal = "", doneWhen = "", question = "", reply = "", error = "";
    public final List<Step> steps = new ArrayList<>();
    /** {tool, what, status, ok} plus "read": true for read-only calls, for every tool that actually ran, in order. */
    public final List<JSONObject> actions = new ArrayList<>();
    public State state = State.RUNNING;
    public long updatedAt;
    /** The panel's data card (weather, quotes, a list) from the last tool that produced one, drawn under the steps;
     * the latest wins. Never saved: a card belongs to the answer on screen, not to the task's record. */
    public volatile JSONObject card;

    public Ledger(String id, String utterance, String source, long now) {
        this.id = id; this.utterance = utterance; this.source = source == null ? "" : source;
        startedAt = now; updatedAt = now;
    }

    public synchronized boolean planned() { return !goal.isEmpty(); }

    /** Finished steps of the task this one continues; a step re-planned with the same text keeps its status. */
    private final List<Step> inherited = new ArrayList<>();

    synchronized void inherit(Ledger previous) {
        synchronized (previous) { inherited.addAll(previous.steps); }
    }

    synchronized void plan(String goal, List<String> texts, String doneWhen) {
        // Step numbers must match the model's own list, so the new list replaces the old one outright.
        Map<String, Step> before = new HashMap<>();
        for (Step s : inherited) before.put(s.text, s);
        for (Step s : steps) before.put(s.text, s);
        steps.clear();
        for (String t : texts) {
            if (steps.size() >= 8) break;
            Step n = new Step(t), old = before.get(t);
            if (old != null && ("done".equals(old.status) || "skipped".equals(old.status))) { n.status = old.status; n.note = old.note; }
            steps.add(n);
        }
        this.goal = goal; this.doneWhen = doneWhen == null ? "" : doneWhen;
    }

    synchronized void mark(int index, String status, String note) {
        if (index < 1 || index > steps.size()) throw new IllegalArgumentException("没有第 " + index + " 步，计划共 " + steps.size() + " 步");
        Step s = steps.get(index - 1);
        s.status = status;
        s.note = note == null ? "" : note;
        if ("in_progress".equals(status) || "done".equals(status)) {
            // Working on step k means the steps before it are behind us; the model often skips those updates.
            for (int i = 0; i < index - 1; i++) {
                Step before = steps.get(i);
                if ("pending".equals(before.status) || "in_progress".equals(before.status)) before.status = "done";
            }
        }
        if ("in_progress".equals(status)) {
            for (Step other : steps) if (other != s && "in_progress".equals(other.status)) other.status = "pending";
        }
    }

    /** After a successful action with nothing marked as running, show the first open step as running. */
    synchronized void touch() {
        for (Step s : steps) if ("in_progress".equals(s.status)) return;
        for (Step s : steps) if ("pending".equals(s.status)) { s.status = "in_progress"; return; }
    }

    /** Description of the last action that succeeded and changed the TV, or "".
     * Reads (screen_read, wait_for, media_now_playing…) succeed all the time and say nothing about progress. */
    public synchronized String lastSuccess() {
        for (int i = actions.size() - 1; i >= 0; i--) {
            JSONObject a = actions.get(i);
            if (a.optBoolean("ok") && !a.optBoolean("read")) return a.optString("what");
        }
        return "";
    }

    /** read=true for calls that only looked at the TV; the flag is stored so old ledgers and the model see no change. */
    synchronized void action(String tool, String what, String status, boolean ok, boolean read) {
        try {
            JSONObject a = new JSONObject().put("tool", tool).put("what", what).put("status", status).put("ok", ok);
            if (read) a.put("read", true);
            actions.add(a);
        }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        if (actions.size() > 60) actions.remove(0);
    }

    /** Step currently shown as running: the explicit in_progress one, else the first unfinished. */
    public synchronized int current() {
        for (int i = 0; i < steps.size(); i++) if ("in_progress".equals(steps.get(i).status)) return i + 1;
        for (int i = 0; i < steps.size(); i++) if ("pending".equals(steps.get(i).status)) return i + 1;
        return 0;
    }

    public synchronized boolean unfinished() {
        for (Step s : steps) if (!"done".equals(s.status) && !"skipped".equals(s.status)) return true;
        return false;
    }

    /** What the model sees as previous_task. Only facts that were recorded, never the model's private reasoning. */
    public synchronized JSONObject forModel() {
        try {
            JSONObject o = new JSONObject().put("utterance", utterance).put("state", state.name().toLowerCase(Locale.ROOT))
                    .put("minutes_ago", 0);
            if (!goal.isEmpty()) o.put("goal", goal);
            if (!doneWhen.isEmpty()) o.put("done_when", doneWhen);
            if (!steps.isEmpty()) {
                JSONArray a = new JSONArray();
                for (Step s : steps) {
                    JSONObject j = new JSONObject().put("text", s.text).put("status", s.status);
                    if (!s.note.isEmpty()) j.put("note", s.note);
                    a.put(j);
                }
                o.put("steps", a);
            }
            if (!actions.isEmpty()) {
                JSONArray a = new JSONArray();
                for (int i = Math.max(0, actions.size() - 12); i < actions.size(); i++) a.put(actions.get(i));
                o.put("actions", a);
            }
            if (!question.isEmpty()) o.put("question_asked", question);
            if (!reply.isEmpty()) o.put("last_reply", reply);
            return o;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized JSONObject toJson() {
        try {
            JSONObject o = forModel().put("id", id).put("source", source).put("started_at", startedAt)
                    .put("updated_at", updatedAt).put("error", error);
            o.remove("minutes_ago");
            if (!steps.isEmpty()) o.put("steps", stepsJson());
            o.put("actions", new JSONArray(actions));
            return o;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private JSONArray stepsJson() throws JSONException {
        JSONArray a = new JSONArray();
        for (Step s : steps) a.put(new JSONObject().put("text", s.text).put("status", s.status).put("note", s.note));
        return a;
    }

    public static Ledger fromJson(JSONObject o) throws JSONException {
        Ledger l = new Ledger(o.getString("id"), o.getString("utterance"), o.optString("source"), o.optLong("started_at"));
        l.updatedAt = o.optLong("updated_at", l.startedAt);
        l.goal = o.optString("goal"); l.doneWhen = o.optString("done_when");
        l.question = o.optString("question_asked"); l.reply = o.optString("last_reply"); l.error = o.optString("error");
        try { l.state = State.valueOf(o.optString("state", "interrupted").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException unknown) { l.state = State.INTERRUPTED; }
        JSONArray steps = o.optJSONArray("steps");
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            JSONObject j = steps.getJSONObject(i);
            Step s = new Step(j.getString("text"));
            s.status = j.optString("status", "pending"); s.note = j.optString("note");
            l.steps.add(s);
        }
        JSONArray actions = o.optJSONArray("actions");
        // The "read" flag rides along in the stored object; ledgers written before it simply have none.
        if (actions != null) for (int i = 0; i < actions.length(); i++) l.actions.add(actions.getJSONObject(i));
        return l;
    }
}
