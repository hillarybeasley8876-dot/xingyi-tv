package xyz.erapi0neer.xingyi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** DUMP-only harness diagnostics. All five collaborators are in-memory fakes. Also runs RouteContractTest. */
public final class HarnessContractTest {
    private HarnessContractTest() {}

    private interface Scenario { void run() throws Exception; }

    public static String run() {
        List<String> failures = new ArrayList<>();
        String[] names = {"只读回传", "确认一次", "整批预检", "钩子拒绝", "取消", "确认过期",
                "编号重复", "工具额度", "批次上限", "部分执行记录"};
        Scenario[] scenarios = {HarnessContractTest::readRoundTrip, HarnessContractTest::confirmOnce,
                HarnessContractTest::preflight, HarnessContractTest::veto, HarnessContractTest::cancel,
                HarnessContractTest::expire, HarnessContractTest::duplicate, HarnessContractTest::quota,
                HarnessContractTest::batchLimit, HarnessContractTest::partialFailure};
        for (int i = 0; i < scenarios.length; i++) {
            try { scenarios[i].run(); }
            catch (Exception | AssertionError e) { failures.add(names[i] + " (" + e + ")"); }
        }
        int passed = scenarios.length - failures.size();
        List<String> routeFailures = new ArrayList<>();
        int routeTotal = RouteContractTest.run(routeFailures);
        int total = scenarios.length + routeTotal;
        boolean ok = failures.isEmpty() && routeFailures.isEmpty();
        failures.addAll(routeFailures);
        return (ok ? "PASS " : "FAIL ") + (total - failures.size()) + "/" + total + " scenarios: AgentHarness "
                + passed + "/" + scenarios.length + ", ModelRouter " + (routeTotal - routeFailures.size()) + "/" + routeTotal
                + (ok ? "" : " " + failures);
    }

    private static final class Fake implements AgentHarness.Model, AgentHarness.Runtime,
            AgentHarness.Journal, AgentHarness.Listener, AgentHarness.Clock {
        final AgentHarness harness = new AgentHarness(this, this, this, this, this);
        final List<Object> responses = new ArrayList<>();
        final List<JSONArray> requests = new ArrayList<>();
        final List<String> executed = new ArrayList<>(), journal = new ArrayList<>(), listeners = new ArrayList<>();
        String vetoTool = "", brokenTool = "";
        long time = 1_000L;
        int canceled;

        Fake(Object... script) { responses.addAll(Arrays.asList(script)); }
        @Override public JSONObject next(JSONArray messages, JSONArray tools) throws Exception {
            check(tools.length() > 0, "text turn is missing tool definitions");
            requests.add(new JSONArray(messages.toString()));
            check(requests.size() <= responses.size(), "unplanned model round");
            Object response = responses.get(requests.size() - 1);
            if (response instanceof Exception) throw (Exception)response;
            return (JSONObject)response;
        }
        @Override public void cancel() { canceled++; }
        @Override public long millis() { return time; }
        @Override public JSONObject execute(String tool, JSONObject args) throws Exception {
            executed.add(tool);
            if (tool.equals(brokenTool)) throw new IOException("simulated missing acknowledgement");
            return new JSONObject().put("ok", true).put("status", "ok")
                    .put("message", "fixture acknowledgement for " + tool);
        }
        @Override public String event(String event, String tool, String state, JSONObject args) {
            journal.add(event + "/" + tool + "/" + state);
            return event.equals("before_tool") && tool.equals(vetoTool) ? "fixture veto" : null;
        }
        @Override public String trace() { return journal.toString(); }
        @Override public void onEvent(String event, String tool, String state) {
            listeners.add(event + "/" + tool + "/" + state);
        }
        AgentHarness.Step start() throws Exception { return harness.start("测试指令", null); }
    }

    private static JSONObject call(String id, String name, JSONObject args) throws Exception {
        return new JSONObject().put("id", id).put("type", "function").put("function",
                new JSONObject().put("name", name).put("arguments", args.toString()));
    }
    private static JSONObject read(String id) throws Exception { return call(id, "get_status", new JSONObject()); }
    private static JSONObject write(String id) throws Exception {
        return call(id, "set_volume", new JSONObject().put("level", 12));
    }
    private static JSONObject tools(JSONObject... calls) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject c : calls) a.put(c);
        return new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", a);
    }
    private static JSONObject answer(String text) throws Exception {
        return new JSONObject().put("role", "assistant").put("content", text);
    }
    private static JSONObject toolResult(JSONArray history, int index, String id) throws Exception {
        JSONObject message = history.getJSONObject(index);
        check("tool".equals(message.getString("role")) && id.equals(message.getString("tool_call_id")),
                "tool result did not match " + id);
        return new JSONObject(message.getString("content"));
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private static String reject(Scenario operation, String reason) throws Exception {
        try { operation.run(); }
        catch (Exception expected) {
            String message = expected.getMessage();
            check(message != null && message.contains(reason), "unexpected rejection: " + message);
            return message;
        }
        throw new AssertionError("expected rejection: " + reason);
    }
    private static void readRoundTrip() throws Exception {
        JSONObject first = tools(read("read-1")).put("reasoning_content", "fixture inner reasoning")
                .put("provider_metadata", new JSONObject().put("cursor", "fixture cursor"));
        Fake f = new Fake(first, answer("<thinking>private deliberation</thinking>\n音量是 12。"));
        AgentHarness.Step step = f.start();
        check(!step.needsConfirmation && step.text.equals("音量是 12。"), "visible answer or confirmation incorrect");
        check(f.executed.equals(Arrays.asList("get_status")) && f.requests.size() == 2, "read did not loop exactly once");
        JSONArray history = f.requests.get(1);
        check(history.length() == 4 && history.getJSONObject(2).toString().equals(first.toString()),
                "original assistant message or provider fields lost");
        check(toolResult(history, 3, "read-1").getBoolean("ok"), "result missing");
        check(f.journal.equals(f.listeners) && f.journal.contains("after_tool/get_status/ok")
                && f.journal.contains("turn_end//complete"), "incomplete lifecycle events");
    }
    private static void confirmOnce() throws Exception {
        Fake f = new Fake(tools(read("r"), write("w")), answer("完成"));
        AgentHarness.Step pending = f.start();
        check(pending.needsConfirmation && pending.preview.contains("12") && f.executed.isEmpty(),
                "mixed batch must wait for confirmation");
        f.time += 89_999;
        check(!f.harness.confirm().needsConfirmation, "confirmed turn did not finish");
        reject(() -> f.harness.confirm(), "没有待确认");
        check(f.executed.equals(Arrays.asList("get_status", "set_volume")), "duplicate or reordered execution");
        JSONArray history = f.requests.get(1);
        toolResult(history, 3, "r"); toolResult(history, 4, "w");
    }
    private static void preflight() throws Exception {
        Fake f = new Fake(tools(read("r"), write("w"), call("bad", "set_volume", new JSONObject().put("level", 101))));
        try { f.start(); throw new AssertionError("invalid batch produced a preview/result"); }
        catch (IOException expected) { check(f.executed.isEmpty(), "earlier actions ran before invalid parameters rejected"); }
        reject(() -> f.harness.confirm(), "没有待确认");
    }
    private static void veto() throws Exception {
        Fake f = new Fake(tools(read("r"), write("w"))); f.vetoTool = "set_volume";
        reject(() -> f.start(), "fixture veto");
        check(f.executed.isEmpty() && f.requests.size() == 1, "veto allowed a partial batch");
    }
    private static void cancel() throws Exception {
        Fake f = new Fake(tools(write("w")));
        check(f.start().needsConfirmation, "missing preview"); f.harness.cancel(); f.harness.cancel();
        reject(() -> f.harness.confirm(), "已取消");
        check(f.executed.isEmpty() && f.canceled == 1, "cancellation did not prevent execution");
    }
    private static void expire() throws Exception {
        Fake f = new Fake(tools(write("w"))); f.start(); f.time += 90_000;
        reject(() -> f.harness.confirm(), "过期");
        check(f.executed.isEmpty() && f.requests.size() == 1, "expired preview executed");
    }
    private static void duplicate() throws Exception {
        Fake f = new Fake(tools(read("same")), tools(read("same")));
        reject(() -> f.start(), "重复");
        check(f.executed.size() == 1 && f.requests.size() == 2, "a reused id executed twice");
    }
    private static void quota() throws Exception {
        Fake f = new Fake(tools(read("1"), read("2"), read("3"), read("4")),
                tools(read("5"), read("6"), read("7"), read("8")), tools(read("9")));
        reject(() -> f.start(), "上限");
        check(f.executed.size() == 8 && f.requests.size() == 3, "turn tool limit not enforced");
    }
    private static void batchLimit() throws Exception {
        Fake f = new Fake(tools(read("1"), read("2"), read("3"), read("4"), read("5")));
        reject(() -> f.start(), "上限"); check(f.executed.isEmpty(), "overlong batch partially ran");
    }
    private static void partialFailure() throws Exception {
        Fake f = new Fake(tools(write("ok"), call("unknown", "say", new JSONObject().put("text", "测试")),
                call("skipped", "home", new JSONObject())), new IOException("simulated second-round failure"));
        f.brokenTool = "say"; f.start();
        String error = reject(() -> f.harness.confirm(), "simulated second-round failure");
        check(error.contains("fixture acknowledgement for set_volume"), "a completed side effect vanished from the error");
        check(f.executed.equals(Arrays.asList("set_volume", "say")), "batch continued after an uncertain side effect");
        JSONArray history = f.requests.get(1);
        check(history.length() == 6 && toolResult(history, 3, "ok").getBoolean("ok"), "first result missing");
        JSONObject unknown = toolResult(history, 4, "unknown"), skipped = toolResult(history, 5, "skipped");
        check(!unknown.getBoolean("ok") && "unknown".equals(unknown.getString("status")), "failure not marked unknown");
        check(!skipped.getBoolean("ok") && "skipped".equals(skipped.getString("status")), "skipped result not recorded");
    }
}
