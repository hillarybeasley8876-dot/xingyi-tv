package xyz.erapi0neer.xingyi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** The model responses are scripted. All intent handling, tool schema checks and previews are real. */
public final class UnderstoodHarnessTest {
    private static final String KEYWORD = "星辰亻旅行者";
    private static final String SEARCH = "打开B站搜索" + KEYWORD;
    private static final String BILI = "tv.danmaku.bili";

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static JSONObject say(String text) throws Exception {
        return new JSONObject().put("role", "assistant").put("content", text);
    }

    private static JSONObject plan(String mode, String goal, String target, String response,
                                   String tool, JSONObject args) throws Exception {
        return say(new JSONObject().put("disposition", mode).put("goal", goal).put("target", target)
                .put("response", response).put("tool", tool).put("arguments", args).toString());
    }

    private static JSONObject search(String term) throws Exception {
        return plan("act", "在哔哩哔哩搜索原词", term, "", "search_video",
                new JSONObject().put("app", BILI).put("query", term));
    }

    private static final class Probe {
        final List<String> executed = new ArrayList<>();
        final List<JSONObject> arguments = new ArrayList<>();
        final List<String> order = new ArrayList<>();
        final List<String> audit = new ArrayList<>();
        final List<JSONObject> replies = new ArrayList<>();
        final String original, context;
        final AgentHarness harness;
        int rounds;

        Probe(String original, String context, JSONObject first) throws Exception {
            this.original = original;
            this.context = context;
            replies.add(first);
            AgentHarness.Model model = new AgentHarness.Model() {
                public JSONObject next(JSONArray messages, JSONArray tools) throws Exception {
                    if (rounds++ == 0) {
                        order.add("understanding-model");
                        check(tools.length() == 0, "execution tools exposed during understanding");
                        check(executed.isEmpty(), "action preceded understanding");
                        check(messages.toString().contains(original), "understanding did not receive original");
                        if (!context.isEmpty()) {
                            check(messages.toString().contains("来源标记"), "prior task not sent for understanding");
                        }
                    }
                    check(!replies.isEmpty(), "model consulted without a scripted response");
                    return replies.remove(0);
                }
                public void cancel() { }
            };
            AgentHarness.Journal journal = new AgentHarness.Journal() {
                public String event(String e,String t,String s,JSONObject a) { audit.add(e + " " + s); return null; }
                public String trace() { return String.join("\n", audit); }
            };
            harness = new AgentHarness(model, (name,args) -> {
                check(order.contains("understood"), "tool ran before understanding was reported");
                order.add("executed"); executed.add(name); arguments.add(new JSONObject(args.toString()));
                return new JSONObject().put("ok", true).put("status", "observed").put("message", "已核对结果");
            }, journal, (event,tool,state) -> order.add(event), () -> 150L);
        }

        AgentHarness.Step start(boolean automatic) throws Exception {
            return harness.startUnderstood(original, null, context, GenericRefusalText.scope(original), automatic);
        }

        void noExecution() {
            check(executed.isEmpty() && !order.contains("tool_start"), "unexpected execution");
            check(!order.contains("confirmation_requested"), "offered an unrequested action");
        }
    }

    private static void verifyNoAction(String original,String mode,String reply) throws Exception {
        Probe p = new Probe(original, "", plan(mode, "理解用户的意图", "", reply, "", new JSONObject()));
        AgentHarness.Step result = p.start(true);
        check(!result.needsConfirmation && result.text.equals(reply) && p.rounds == 1, mode);
        p.noExecution();
    }

    private static void failClosed(String original,String context,JSONObject bad) throws Exception {
        Probe p = new Probe(original, context, bad);
        try { p.start(true); } catch (IOException | IllegalArgumentException rejected) { /* An explicit rejection is valid. */ }
        p.noExecution();
    }

    private static void searchHandoff() throws Exception {
        check(GenericRefusalText.scope(SEARCH).kind == GenericRefusalText.Kind.SEARCH, "fixture has no search scope");
        Probe p = new Probe(SEARCH, "", search(KEYWORD));
        AgentHarness.Step result = p.start(true);
        check(!result.needsConfirmation && p.rounds >= 1 && p.executed.size() == 1, "search lifecycle");
        check("search_video".equals(p.executed.get(0)), "expected search");
        check(BILI.equals(p.arguments.get(0).getString("app")) && KEYWORD.equals(p.arguments.get(0).getString("query")),
                "did not retain exact app and keyword");
        check(!String.join(" ",p.audit).contains(KEYWORD), "intent text leaked to persistent journal fields");
        try { p.harness.confirm(); throw new AssertionError("completed search confirmable"); }
        catch (IllegalStateException expected) { }
        // Same action with changed target, or a correct target paired with changed arguments, must not be sent.
        failClosed(SEARCH,"",search("星辰旅行者"));
        failClosed(SEARCH,"",plan("act","搜索用户原词",KEYWORD,"","search_video",
                new JSONObject().put("app", BILI).put("query","星辰旅行者")));
    }

    private static void novelRequest() throws Exception {
        String request = "再过一刻钟，让家里的这台电视休眠";
        check(GenericRefusalText.scope(request) == null, "novel wording accidentally recognized by deterministic grammar");
        Probe p = new Probe(request,"",plan("act","十五分钟后休眠","","","set_sleep_timer",
                new JSONObject().put("minutes",15)));
        p.replies.add(say("已设置休眠定时。"));
        AgentHarness.Step pending = p.start(false);
        check(pending.needsConfirmation && pending.preview.contains("15") && p.executed.isEmpty(), "timer lost review");
        p.harness.confirm();
        check(p.executed.size()==1 && p.executed.get(0).equals("set_sleep_timer")
                && p.arguments.get(0).getInt("minutes")==15, "unparsed utterance not routed through normal tool");
    }

    private static void install() throws Exception {
        String request = "安装哔哩哔哩官方客户端";
        Probe p = new Probe(request,"",plan("act","安装官方客户端",BILI,"","install_app",
                new JSONObject().put("package",BILI)));
        AgentHarness.Step step = p.start(true);
        check(step.needsConfirmation && step.preview.contains(BILI) && p.executed.isEmpty(), "install skipped review");
        p.harness.confirm();
        check(p.executed.size()==1 && p.executed.get(0).equals("install_app"), "confirmed install count");
    }

    private static void continuation() throws Exception {
        JSONObject past = new JSONObject().put("request",SEARCH).put("goal","来源标记：搜索指定词")
                .put("result","搜索结果页已显示").put("recent",new JSONArray()).put("completedCalls",new JSONArray()
                .put(new JSONObject().put("tool","search_video").put("status","observed")
                .put("arguments",new JSONObject().put("app",BILI).put("query",KEYWORD))));
        failClosed("继续执行","",search(KEYWORD));
        failClosed("继续执行",past.put("state","completed").toString(),search(KEYWORD));
        failClosed("继续执行",past.put("state","interrupted").toString(),search(KEYWORD));
        failClosed("继续执行",past.put("state","pending_confirmation").toString(),search(KEYWORD));
    }

    public static void main(String[] args) throws Exception {
        verifyNoAction("举个例子，打开B站搜索星辰亻旅行者这样的句子你能理解吗", "answer","能理解你举的例子。");
        verifyNoAction("打开B站搜索星辰亻旅行者", "clarify","你想查找哪类内容？");
        failClosed(SEARCH,"",say("不是有效的意图 JSON"));
        failClosed(SEARCH,"",search(KEYWORD).put("tool_calls",new JSONArray().put(new JSONObject()
                .put("id","attempted").put("type","function").put("function",new JSONObject()
                .put("name","search_video").put("arguments","{}")))));
        searchHandoff();
        novelRequest();
        install();
        continuation();
        System.out.println("PASS understanding-first ordering, quotation, clarification, invalid plans, original target, bounded continuation and install/timer review");
    }
}
