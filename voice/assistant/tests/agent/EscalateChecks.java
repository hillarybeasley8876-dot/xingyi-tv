package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** Escalation (AgentLoop.Limits.escalateRound / escalateFailures): a task that is not done by round 5, or has failed
 * twice, goes on with the strong model; easy tasks never leave the fast one. */
final class EscalateChecks {
    private EscalateChecks() { }

    static void run() throws Exception {
        easyStaysFast();
        slowTaskEscalates();
        failuresEscalate();
        portableMessages();
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    /** A scripted model that records, round by round, whether the strong model was asked. */
    static final class Recorder implements AgentLoop.Model {
        final AgentCoreTest.Script script;
        final List<Boolean> strong = new ArrayList<>();
        Recorder(AgentCoreTest.Script script) { this.script = script; }
        @Override public JSONObject next(JSONArray m, JSONArray t) throws Exception { strong.add(false); return script.next(m, t); }
        @Override public JSONObject next(JSONArray m, JSONArray t, boolean s) throws Exception { strong.add(s); return script.next(m, t); }
        @Override public void cancel() { script.cancel(); }
    }

    private static AgentLoop loop(Recorder r, AgentCoreTest.Events events, Tool... tools) {
        long[] t = {1_000_000};
        return new AgentLoop(AgentCoreTest.contract, r, Arrays.asList(tools), events, (w, l) -> true, () -> t[0] += 10, null);
    }

    static void easyStaysFast() throws Exception {
        AgentCoreTest.Fake bili = new AgentCoreTest.Fake("bili_search", Tool.Gate.act());
        AgentCoreTest.Fake click = new AgentCoreTest.Fake("screen_click", Tool.Gate.act());
        click.reply.put("playing", AgentCoreTest.o("title", "猫"));
        Recorder r = new Recorder(new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("B站播放猫", "搜", "点开"), "bili_search", AgentCoreTest.o("keyword", "猫"))
                .calls("screen_click", AgentCoreTest.o("id", 5))
                .calls("finish", AgentCoreTest.o("status", "done", "reply", "在放了")));
        AgentCoreTest.Events ev = new AgentCoreTest.Events();
        Ledger t = loop(r, ev, bili, click).run(new AgentLoop.Request("B站搜猫播放", "test"));
        check(t.state == Ledger.State.DONE && r.strong.equals(Arrays.asList(false, false, false)), "a three-round task stays on the fast model: " + r.strong);
        check(!ev.types.contains("escalate"), "and says nothing about escalating");
    }

    static void slowTaskEscalates() throws Exception {
        AgentCoreTest.Fake read = new AgentCoreTest.Fake("screen_read", Tool.Gate.read());
        AgentCoreTest.Script s = new AgentCoreTest.Script().calls("plan", AgentCoreTest.plan("找", "找"), "screen_read", AgentCoreTest.o());
        for (int i = 0; i < 5; i++) s.calls("screen_read", AgentCoreTest.o());
        s.calls("finish", AgentCoreTest.o("status", "done", "reply", "找到了"));
        Recorder r = new Recorder(s);
        AgentCoreTest.Events ev = new AgentCoreTest.Events();
        loop(r, ev, read).run(new AgentLoop.Request("找一找", "test"));
        check(r.strong.size() == 7, "seven rounds ran: " + r.strong.size());
        check(r.strong.subList(0, 4).equals(Arrays.asList(false, false, false, false)), "rounds 1-4 on the fast model: " + r.strong);
        check(r.strong.subList(4, 7).equals(Arrays.asList(true, true, true)), "round 5 on, the strong model, and it stays: " + r.strong);
        check(ev.types.indexOf("escalate") >= 0 && Collections.frequency(ev.types, "escalate") == 1, "escalate is said once");
    }

    static void failuresEscalate() throws Exception {
        AgentCoreTest.Fake click = new AgentCoreTest.Fake("screen_click", Tool.Gate.act());
        click.reply = new JSONObject().put("ok", false).put("status", "not_found").put("message", "没有这个元素");
        Recorder r = new Recorder(new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("点", "点"), "screen_click", AgentCoreTest.o("id", 9))
                .calls("screen_click", AgentCoreTest.o("id", 8))
                .calls("finish", AgentCoreTest.o("status", "failed", "reply", "点不到")));
        loop(r, new AgentCoreTest.Events(), click).run(new AgentLoop.Request("点那个", "test"));
        check(r.strong.equals(Arrays.asList(false, false, true)), "after two failures the next round is the strong model's: " + r.strong);
    }

    static void portableMessages() throws Exception {
        JSONArray in = new JSONArray()
                .put(AgentCoreTest.o("role", "system", "content", "s"))
                .put(AgentCoreTest.o("role", "assistant", "content", JSONObject.NULL, "reasoning_content", "想一想",
                        "tool_calls", new JSONArray().put(AgentCoreTest.o("id", "a"))))
                .put(AgentCoreTest.o("role", "tool", "tool_call_id", "a", "content", "{}"));
        JSONArray out = AgentLoop.portable(in);
        check(out.length() == 3, "every message stays");
        check(!out.getJSONObject(1).has("reasoning_content"), "MiniMax's reasoning field is dropped for another model");
        check("".equals(out.getJSONObject(1).getString("content")) && out.getJSONObject(1).has("tool_calls"), "tool calls stay, a null content is empty");
        check("a".equals(out.getJSONObject(2).getString("tool_call_id")), "tool results keep their call id");
    }
}
