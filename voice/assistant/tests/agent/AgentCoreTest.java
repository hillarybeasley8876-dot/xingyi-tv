package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Offline checks for the agent core: scripted model, fake tools, the real contract file. */
public final class AgentCoreTest {
    static int passed, failed;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean ok, String label) {
        if (ok) passed++; else { failed++; failures.add(label); }
    }

    static Contract contract;

    public static void main(String[] a) throws Exception {
        contract = new Contract(new String(Files.readAllBytes(Paths.get("assets/agent/contract.json")), StandardCharsets.UTF_8));
        contractChecks();
        speechChecks();
        groundingChecks();
        planGate();
        groundedKeyword();
        aliasCanonical();
        consentFlow();
        denied();
        askUser();
        finishStates();
        plainAnswer();
        failureNote();
        adbDown();
        adbClassify();
        roundLimit();
        cancelDuringTool();
        continuation();
        trimScreens();
        planOrderInBatch();
        badCallId();
        modelError();
        ledgerRoundTrip();
        ScreenChecks.run();
        replies();
        progress();
        repeatNote();
        WebSearchChecks.run();
        StopChecks.run();
        WiringChecks.run();
        QuickChecks.run();
        MemoryChecks.run();
        HabitChecks.run();
        PerfChecks.run();
        RoundChecks.run();
        ConversationChecks.run();
        SearchFixChecks.run();
        ContextChecks.run();
        AlmanacChecks.run();
        StreamChecks.run();
        WeatherChecks.run();
        UsageChecks.run();
        FeedChecks.run();
        GateChecks.run();
        FindToolsChecks.run();
        MemoryCareChecks.run();
        MusicQueueChecks.run();
        BiliChecks.run();
        MovieChecks.run();
        BangumiChecks.run();
        AlertChecks.run();
        HuangliChecks.run();
        MusicNewChecks.run();
        ScheduleChecks.run();
        NoteChecks.run();
        DispatchChecks.run();
        HintChecks.run();
        SkillChecks.run();
        ExploreChecks.run();
        StoreChecks.run();
        EscalateChecks.run();
        PinyinChecks.run();
        HeardChecks.run();
        CardChecks.run();
        OfficialAppsChecks.run();
        ApkConsentChecks.run();
        InstallerChecks.run();
        HomeInfoChecks.run();
        LoopFixChecks.run();
        PhoneChecks.run();
        OfferChecks.run();
        System.out.println("agent core: " + passed + " passed, " + failed + " failed");
        for (String f : failures) System.out.println("  FAIL " + f);
        if (failed > 0) System.exit(1);
    }

    // ---- scripted model -------------------------------------------------------------------------

    static final class Script implements AgentLoop.Model {
        final List<JSONObject> replies = new ArrayList<>();
        final List<JSONArray> seen = new ArrayList<>();
        /** The tool list each round was given (gated data tools, 2026-11-07). */
        final List<JSONArray> toolsSeen = new ArrayList<>();
        int next;
        boolean cancelled;
        Script calls(Object... nameArgs) throws JSONException {
            JSONArray calls = new JSONArray();
            for (int i = 0; i < nameArgs.length; i += 2) {
                calls.put(new JSONObject().put("id", "c" + (replies.size() * 10 + i / 2)).put("type", "function")
                        .put("function", new JSONObject().put("name", nameArgs[i]).put("arguments", nameArgs[i + 1].toString())));
            }
            replies.add(new JSONObject().put("role", "assistant").put("content", "").put("tool_calls", calls));
            return this;
        }
        Script text(String t) throws JSONException {
            replies.add(new JSONObject().put("role", "assistant").put("content", t));
            return this;
        }
        @Override public JSONObject next(JSONArray messages, JSONArray tools) throws Exception {
            seen.add(new JSONArray(messages.toString()));
            toolsSeen.add(tools == null ? new JSONArray() : new JSONArray(tools.toString()));
            if (next >= replies.size()) return new JSONObject().put("role", "assistant").put("content", "（脚本结束）");
            return new JSONObject(replies.get(next++).toString());
        }
        @Override public void cancel() { cancelled = true; }
        /** The tool message answering call id, from the last request the model received. */
        JSONObject toolResult(String id) throws JSONException {
            for (int s = seen.size() - 1; s >= 0; s--) {
                JSONArray m = seen.get(s);
                for (int i = 0; i < m.length(); i++) {
                    JSONObject o = m.getJSONObject(i);
                    if ("tool".equals(o.optString("role")) && id.equals(o.optString("tool_call_id"))) return new JSONObject(o.getString("content"));
                }
            }
            return null;
        }
    }

    static final class Fake implements Tool {
        final String name; final Gate gate;
        final List<JSONObject> runs = new ArrayList<>();
        JSONObject reply;
        Runnable during;
        Exception failure;
        Fake(String name, Gate gate) throws JSONException {
            this.name = name; this.gate = gate;
            reply = new JSONObject().put("ok", true).put("status", "observed").put("message", name + " ok");
        }
        @Override public String name() { return name; }
        @Override public Gate gate(JSONObject args, Turn turn) { return gate; }
        @Override public String describe(JSONObject args, Turn turn) { return name + " " + args; }
        @Override public JSONObject run(JSONObject args, Turn turn) throws Exception {
            runs.add(args);
            if (during != null) during.run();
            if (failure != null) throw failure;
            return new JSONObject(reply.toString());
        }
    }

    static final class Events implements AgentLoop.Listener {
        final List<String> types = new ArrayList<>();
        @Override public void onEvent(String type, Ledger task, String text) { types.add(type); }
    }

    static AgentLoop loop(Script model, Events events, AgentLoop.Consent consent, Tool... tools) {
        long[] t = {1_000_000};
        return new AgentLoop(contract, model, Arrays.asList(tools), events, consent, () -> t[0] += 10, null);
    }

    static JSONObject o(Object... kv) throws JSONException {
        JSONObject j = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) j.put((String) kv[i], kv[i + 1]);
        return j;
    }

    static JSONObject plan(String goal, String... steps) throws JSONException {
        return o("goal", goal, "steps", new JSONArray(Arrays.asList(steps)), "done_when", "屏幕上能看到结果");
    }

    // ---- contract --------------------------------------------------------------------------------

    static void contractChecks() throws Exception {
        check(contract.names().containsAll(Arrays.asList("plan", "plan_update", "ask_user", "finish", "screen_read", "bili_search", "netease_play")), "contract has core tools");
        JSONObject ok = contract.check("press_keys", o("keys", new JSONArray().put("down").put("center"), "repeat", "3", "junk", 1));
        check(ok.getInt("repeat") == 3 && !ok.has("junk"), "integer from string, unknown key dropped");
        check(rejects("press_keys", o("repeat", 2)), "missing required rejected");
        check(rejects("media_control", o("action", "explode")), "enum enforced");
        check(rejects("set_volume", o("level", 2.5)), "non-integer rejected");
        check(rejects("bili_search", o("keyword", "  ")), "blank string rejected");
        check(rejects("nope", o()), "unknown tool rejected");
        check(contract.check("screen_input", o("text", "你好", "submit", "true")).getBoolean("submit"), "boolean from string");
        check(contract.tools().length() == contract.names().size(), "tools() copy matches names");
    }

    static boolean rejects(String name, JSONObject args) {
        try { contract.check(name, args); return false; } catch (IllegalArgumentException e) { return true; }
    }

    // ---- speech ----------------------------------------------------------------------------------

    static void speechChecks() {
        check(Speech.visible("<think>想一想</think>好的").equals("好的"), "think stripped");
        String md = "我能做这些：\n\n- **找视频**：B站搜索\n- 听歌：网易云点歌\n1. 调音量";
        String c = Speech.clean(md);
        check(!c.contains("**") && !c.contains("- ") && !c.contains("\n") && c.contains("找视频") && c.contains("调音量"), "markdown cleaned: " + c);
        String longText = "第一句话说完了。第二句话也很长很长很长很长很长很长很长很长很长。第三句话。";
        String s = Speech.spoken(longText, 20);
        check(s.length() <= 20 && s.endsWith("。"), "spoken cut at sentence end: " + s);
        check(Speech.soundsFailed("没能找到这首歌") && !Speech.soundsFailed("已经开始播放"), "failure words");
    }

    // ---- grounding -------------------------------------------------------------------------------

    static void groundingChecks() throws Exception {
        Grounding g = new Grounding(null);
        g.heard("打开B站搜一下星辰亻旅行者，播放他最新那个视频");
        check(g.fix("星辰旅行者").equals("星辰亻旅行者"), "dropped radical restored");
        check(g.fix("星辰亻旅行者").equals("星辰亻旅行者"), "exact kept");
        check(g.fix("《星辰旅行者》").equals("星辰亻旅行者"), "quotes stripped then fixed");
        check(g.fix("猫咪日常").equals("猫咪日常"), "unrelated phrase untouched");
        check(g.fix("最新视频").equals("最新视频"), "model's own words untouched");
        g.saw("[5] 卡片「城市夜景延时摄影 第一期」");
        check(g.fix("城市夜景延时摄影第一期").equals("城市夜景延时摄影第一期") || g.fix("城市夜景延时摄影第一期").equals("城市夜景延时摄影 第一期"), "screen phrase near match ok");
        List<String> notes = new ArrayList<>();
        JSONObject fixed = g.fix(o("keyword", "星辰旅行者", "title", "星辰旅行者"), notes);
        check(fixed.getString("keyword").equals("星辰亻旅行者") && fixed.getString("title").equals("星辰旅行者") && notes.size() == 1, "only search keys grounded");
        check(Grounding.distance("abc", "abd", 2) == 1 && Grounding.distance("abc", "xyz12", 1) == 2, "distance + early exit");
        Map<String, String> aliases = new HashMap<>();
        aliases.put("星辰旅行者", "星辰亻旅行者");
        check(Grounding.canonical("打开B站搜索星辰旅行者", aliases).equals("打开B站搜索星辰亻旅行者"), "alias rewritten");
        check(Grounding.canonical("搜索星辰亻旅行者", aliases).equals("搜索星辰亻旅行者"), "alias not doubled");
        // XiaoAi's recognizer hears 亻 as 人 (2026-11-05); built-ins apply even when the profile file has its own list
        check(Grounding.canonical("哔哩哔哩播放星辰人旅行者的MAD", Grounding.withBuiltins(aliases)).equals("哔哩哔哩播放星辰亻旅行者的MAD"),
                "misheard name rewritten by a built-in alias");
        Map<String, String> own = new HashMap<>();
        own.put("星辰人旅行者", "星辰人旅行者");
        check(Grounding.withBuiltins(own).get("星辰人旅行者").equals("星辰人旅行者") && Grounding.withBuiltins(null).size() == 2,
                "profile aliases win over built-ins");
    }

    // ---- loop ------------------------------------------------------------------------------------

    static void planGate() throws Exception {
        Script m = new Script()
                .calls("screen_click", o("id", 3))
                .calls("screen_read", o())
                .calls("plan", plan("点开第三个", "点开"), "screen_click", o("id", 3))
                .calls("finish", o("status", "done", "reply", "已点开。"));
        Fake click = new Fake("screen_click", Tool.Gate.act()), read = new Fake("screen_read", Tool.Gate.read());
        Ledger t = loop(m, new Events(), (w, l) -> true, click, read).run(new AgentLoop.Request("点开第三个", "test"));
        check(m.toolResult("c0").getString("message").equals(AgentLoop.NEED_PLAN), "action before plan rejected");
        check(read.runs.size() == 1, "read allowed before plan");
        check(click.runs.size() == 1 && t.state == Ledger.State.DONE && t.reply.equals("已点开。"), "acts after plan, finishes done");

        // a one-step connector needs no plan (「B站搜猫」), a sensitive or denied one still goes through its gate
        Script d = new Script().calls("bili_search", o("keyword", "猫")).text("B站搜好了。");
        Fake bili = new Fake("bili_search", Tool.Gate.act());
        Ledger s = loop(d, new Events(), (w, l) -> true, bili).run(new AgentLoop.Request("B站搜猫", "test"));
        check(bili.runs.size() == 1 && s.state == Ledger.State.ANSWERED && "B站搜好了。".equals(s.reply), "a one-step connector runs without a plan");
        Script k = new Script().calls("xiaoai_ask", o("query", "打开门锁")).text("没有打开。");
        Fake lock = new Fake("xiaoai_ask", Tool.Gate.sensitive("让小爱执行：打开门锁"));
        loop(k, new Events(), (w, l) -> true, lock).run(new AgentLoop.Request("打开门锁", "test"));
        check(lock.runs.isEmpty() && AgentLoop.NEED_PLAN.equals(k.toolResult("c0").getString("message")), "a sensitive one still needs a plan");
        check(t.steps.get(0).status.equals("done"), "finish(done) closes steps");
    }

    static void groundedKeyword() throws Exception {
        Script m = new Script()
                .calls("plan", plan("在B站搜索“星辰旅行者”", "搜索“星辰旅行者”"), "bili_search", o("keyword", "星辰旅行者"))
                .calls("finish", o("status", "done", "reply", "好了"));
        Fake bili = new Fake("bili_search", Tool.Gate.act());
        Ledger t = loop(m, new Events(), (w, l) -> true, bili).run(new AgentLoop.Request("打开B站搜一下星辰亻旅行者，播放他最新那个视频", "test"));
        check(bili.runs.get(0).getString("keyword").equals("星辰亻旅行者"), "tool got the exact keyword");
        check(m.toolResult("c1").getJSONArray("notes").length() == 1, "correction reported to model");
        check(t.goal.contains("星辰亻旅行者") && t.steps.get(0).text.contains("星辰亻旅行者"), "quoted goal/steps fixed: " + t.goal);
    }

    static void aliasCanonical() throws Exception {
        Script m = new Script()
                .calls("plan", plan("搜索", "搜索"), "bili_search", o("keyword", "星辰旅行者"))
                .calls("finish", o("status", "done", "reply", "好了"));
        Fake bili = new Fake("bili_search", Tool.Gate.act());
        AgentLoop.Request r = new AgentLoop.Request("打开B站搜索星辰旅行者", "test");
        r.aliases = Collections.singletonMap("星辰旅行者", "星辰亻旅行者");
        Ledger t = loop(m, new Events(), (w, l) -> true, bili).run(r);
        check(t.utterance.equals("打开B站搜索星辰亻旅行者"), "utterance canonicalized");
        check(bili.runs.get(0).getString("keyword").equals("星辰亻旅行者"), "alias reaches tool");
        JSONObject user = new JSONObject(m.seen.get(0).getJSONObject(1).getString("content"));
        check(user.getString("utterance").contains("亻"), "model sees canonical utterance");
    }

    static void consentFlow() throws Exception {
        for (boolean approve : new boolean[]{false, true}) {
            Script m = new Script()
                    .calls("plan", plan("安装哔哩哔哩", "安装"), "install_app", o("app", "tv.danmaku.bili"))
                    .calls("finish", o("status", approve ? "done" : "failed", "reply", "x"));
            Fake install = new Fake("install_app", Tool.Gate.sensitive("安装哔哩哔哩官方客户端"));
            List<String> asked = new ArrayList<>();
            Events ev = new Events();
            Ledger t = loop(m, ev, (what, l) -> { asked.add(what); check(l.state == Ledger.State.AWAITING_CONFIRMATION, "state while asking"); return approve; }, install).run(new AgentLoop.Request("装B站", "test"));
            check(asked.size() == 1 && asked.get(0).equals("安装哔哩哔哩官方客户端"), "consent asked once");
            check(install.runs.size() == (approve ? 1 : 0), "runs only when approved: " + approve);
            check(ev.types.contains("confirm") && ev.types.contains("consent"), "consent events");
            if (!approve) check(m.toolResult("c1").getString("status").equals("declined"), "declined result");
            check(t.state == (approve ? Ledger.State.DONE : Ledger.State.INTERRUPTED), "end state after consent " + approve + " " + t.state);
        }
    }

    static void denied() throws Exception {
        Script m = new Script().calls("plan", plan("付款", "付款"), "screen_click", o("id", 3)).text("没有付款。");
        Fake click = new Fake("screen_click", Tool.Gate.deny("不能替你完成付款"));
        Ledger t = loop(m, new Events(), (w, l) -> true, click).run(new AgentLoop.Request("帮我买会员", "test"));
        check(click.runs.isEmpty() && m.toolResult("c1").getString("status").equals("denied"), "deny never runs");
        check(t.state == Ledger.State.INTERRUPTED, "plain failure text → interrupted: " + t.state);
    }

    static void askUser() throws Exception {
        Script m = new Script().calls("ask_user", o("question", "你想看哪一部？"), "bili_search", o("keyword", "x"));
        Fake bili = new Fake("bili_search", Tool.Gate.act());
        Ledger t = loop(m, new Events(), (w, l) -> true, bili).run(new AgentLoop.Request("放那个", "test"));
        check(t.state == Ledger.State.AWAITING_USER && t.reply.equals("你想看哪一部？") && t.question.equals("你想看哪一部？"), "ask ends turn");
        check(bili.runs.isEmpty(), "calls after ask skipped (ask runs last)");
    }

    static void finishStates() throws Exception {
        Script m = new Script().calls("plan", plan("两步", "一", "二"), "plan_update", o("step", 1, "status", "done"))
                .calls("finish", o("status", "partial", "reply", "只做了一半"));
        Ledger t = loop(m, new Events(), (w, l) -> true).run(new AgentLoop.Request("做两件事", "test"));
        check(t.state == Ledger.State.INTERRUPTED && t.state.resumable(), "partial → interrupted, resumable");
        check(t.steps.get(0).status.equals("done") && t.steps.get(1).status.equals("pending"), "partial keeps step states");
        Script bad = new Script().calls("plan", plan("一步", "一"), "plan_update", o("step", 5, "status", "done")).calls("finish", o("status", "done", "reply", "好"));
        loop(bad, new Events(), (w, l) -> true).run(new AgentLoop.Request("x", "test"));
        check(bad.toolResult("c1").getString("status").equals("rejected"), "out-of-range step rejected");
    }

    static void plainAnswer() throws Exception {
        Script m = new Script().text("我可以帮你搜视频、点歌、调音量。");
        Ledger t = loop(m, new Events(), (w, l) -> true).run(new AgentLoop.Request("你能干嘛", "test"));
        check(t.state == Ledger.State.ANSWERED && t.reply.startsWith("我可以"), "chat answer");
    }

    static void failureNote() throws Exception {
        Fake click = new Fake("screen_click", Tool.Gate.act());
        click.reply = o("ok", false, "status", "failed", "message", "点不动");
        Script m = new Script().calls("plan", plan("点", "点"), "screen_click", o("id", 1))
                .calls("screen_click", o("id", 1)).calls("screen_click", o("id", 1))
                .calls("finish", o("status", "failed", "reply", "点不动"));
        loop(m, new Events(), (w, l) -> true, click).run(new AgentLoop.Request("点一下", "test"));
        check(m.toolResult("c20").has("harness_note") && !m.toolResult("c10").has("harness_note"), "note after third failure only");
    }

    /** The TV's own adb listener refuses connections: the model hears why (not a raw socket error) and is told not to
     * retry; the second hit ends the task in a resumable state with a spoken fix instead of burning the budget. */
    static void adbDown() throws Exception {
        Fake keys = new Fake("press_keys", Tool.Gate.act());
        keys.failure = new AdbUnavailable(new java.net.ConnectException("ECONNREFUSED (Connection refused)"));
        Script m = new Script().calls("plan", plan("按键", "按下", "再按"), "press_keys", o("keys", new JSONArray().put("down")))
                .calls("press_keys", o("keys", new JSONArray().put("down")))
                .calls("press_keys", o("keys", new JSONArray().put("down")))
                .calls("finish", o("status", "failed", "reply", "不该走到这里"));
        Ledger t = loop(m, new Events(), (w, l) -> true, keys).run(new AgentLoop.Request("往下按", "test"));
        JSONObject first = m.toolResult("c1");
        check(first != null && "adb_unavailable".equals(first.optString("status")) && !first.optBoolean("ok", true)
                && first.optString("message").contains("ADB 调试") && !first.optString("message").contains("ECONNREFUSED"),
                "model hears ADB debugging is off, not the socket error");
        check(first != null && first.optString("harness_note").contains("不要重试") && first.optString("harness_note").contains("星仪继续")
                && first.optString("harness_note").contains("账号与安全"),
                "first hit tells the model not to retry and what to tell the user");
        check(keys.runs.size() == 2, "task stops after the second hit: " + keys.runs.size());
        check(t.state == Ledger.State.INTERRUPTED && t.reply.contains("ADB 调试") && t.reply.contains("账号与安全")
                && t.reply.contains("星仪继续") && !t.reply.contains("→"), "planned task ends resumable with a speakable fix: " + t.reply);

        // a question with no plan: nothing to resume, so the reply says to ask again
        Fake read = new Fake("screen_read", Tool.Gate.read());
        read.failure = new AdbUnavailable(new java.net.ConnectException("refused"));
        Script q = new Script().calls("screen_read", o(), "screen_read", o()).text("不该走到这里");
        Ledger asked = loop(q, new Events(), (w, l) -> true, read).run(new AgentLoop.Request("屏幕上有什么", "test"));
        check(asked.state == Ledger.State.FAILED && asked.reply.contains("再说一遍") && !asked.reply.contains("星仪继续"),
                "unplanned question ends failed, asks to repeat: " + asked.state + " " + asked.reply);

        // one hit followed by a model that finishes by itself is left alone
        Fake once = new Fake("press_keys", Tool.Gate.act());
        once.failure = new AdbUnavailable(new java.net.ConnectException("refused"));
        Script s = new Script().calls("plan", plan("按", "按下"), "press_keys", o("keys", new JSONArray().put("up")))
                .calls("finish", o("status", "partial", "reply", "按不了键，请打开 USB 调试，然后说星仪继续"));
        Ledger own = loop(s, new Events(), (w, l) -> true, once).run(new AgentLoop.Request("往上按", "test"));
        check(own.reply.contains("请打开 USB 调试") && once.runs.size() == 1, "the model's own finish after one hit is kept");

        // a model that finishes in the same batch as the second hit keeps its own wording
        Fake pair = new Fake("press_keys", Tool.Gate.act());
        pair.failure = new AdbUnavailable(new java.net.ConnectException("refused"));
        Script b = new Script().calls("plan", plan("按", "按下"), "press_keys", o("keys", new JSONArray().put("up")))
                .calls("press_keys", o("keys", new JSONArray().put("up")), "finish", o("status", "partial", "reply", "我自己说的收尾"));
        Ledger batch = loop(b, new Events(), (w, l) -> true, pair).run(new AgentLoop.Request("往上按", "test"));
        check(batch.reply.equals("我自己说的收尾"), "finish in the same batch wins: " + batch.reply);
    }

    static void adbClassify() throws Exception {
        java.io.IOException refused = new java.net.ConnectException("failed to connect to /127.0.0.1 (port 5555): ECONNREFUSED");
        check(AdbUnavailable.classify(refused) instanceof AdbUnavailable, "refused loopback connect is adb-unavailable");
        check(AdbUnavailable.classify(refused).getCause() == refused, "keeps the original cause for the log");
        java.io.IOException timeout = new java.net.SocketTimeoutException("连接本机调试服务超时");
        check(AdbUnavailable.classify(timeout) == timeout, "a timeout is something else and stays as it was");
        java.io.IOException auth = new java.io.IOException("电视尚未信任星仪，请在电视上允许新的调试授权后重试");
        check(AdbUnavailable.classify(auth) == auth, "an authorization problem stays as it was");

        // the host's heal hook: absent, working, failing, throwing
        AdbUnavailable.healer = null;
        check(!AdbUnavailable.heal(), "no healer: nothing to try");
        AdbUnavailable.healer = () -> true;
        check(AdbUnavailable.heal(), "healer that brings it back");
        AdbUnavailable.healer = () -> false;
        check(!AdbUnavailable.heal(), "healer that cannot");
        AdbUnavailable.healer = () -> { throw new IllegalStateException("boom"); };
        check(!AdbUnavailable.heal(), "a throwing healer never breaks the task");
        AdbUnavailable.healer = null;
    }

    static void roundLimit() throws Exception {
        Script m = new Script().calls("plan", plan("读", "读屏幕", "再读"));
        for (int i = 0; i < 30; i++) m.calls("screen_read", o());
        Fake read = new Fake("screen_read", Tool.Gate.read());
        Ledger t = loop(m, new Events(), (w, l) -> true, read).run(new AgentLoop.Request("一直读", "test"));
        check(t.state == Ledger.State.INTERRUPTED && t.reply.contains("继续") && read.runs.size() == 19, "round limit stops at 20 rounds: " + read.runs.size());
    }

    static void cancelDuringTool() throws Exception {
        Script m = new Script().calls("plan", plan("慢", "慢慢做"), "screen_read", o()).calls("finish", o("status", "done", "reply", "x"));
        Fake read = new Fake("screen_read", Tool.Gate.read());
        AgentLoop[] holder = new AgentLoop[1];
        read.during = () -> holder[0].cancel();
        holder[0] = loop(m, new Events(), (w, l) -> true, read);
        Ledger t = holder[0].run(new AgentLoop.Request("慢慢做", "test"));
        check(t.state == Ledger.State.CANCELLED && m.cancelled && t.reply.equals("已停止。"), "cancel mid-tool");
    }

    static void continuation() throws Exception {
        Ledger prev = new Ledger("p", "B站搜猫并播放第一个", "test", 0);
        prev.plan("搜猫并播放", Arrays.asList("搜索猫", "打开第一个视频", "确认播放"), "在播");
        prev.mark(1, "done", "");
        prev.state = Ledger.State.INTERRUPTED;
        Script m = new Script().calls("plan", plan("搜猫并播放", "搜索猫", "打开第一个视频", "确认播放"), "plan_update", o("step", 2, "status", "in_progress"))
                .calls("finish", o("status", "partial", "reply", "x"));
        AgentLoop.Request r = new AgentLoop.Request("继续", "test");
        r.previous = prev;
        Ledger t = loop(m, new Events(), (w, l) -> true).run(r);
        check(t.steps.get(0).status.equals("done") && t.steps.get(1).status.equals("in_progress"), "finished step inherited by text");
        JSONObject user = new JSONObject(m.seen.get(0).getJSONObject(1).getString("content"));
        check(user.getJSONObject("previous_task").getJSONArray("steps").getJSONObject(0).getString("status").equals("done"), "previous_task given to model");
        Ledger done = new Ledger("d", "x", "t", 0);
        done.plan("g", Arrays.asList("a"), "");
        done.state = Ledger.State.DONE;
        AgentLoop.Request r2 = new AgentLoop.Request("继续", "test");
        r2.previous = done;
        Script m2 = new Script().calls("plan", plan("g", "a"), "finish", o("status", "partial", "reply", "y"));
        Ledger t2 = loop(m2, new Events(), (w, l) -> true).run(r2);
        check(t2.steps.get(0).status.equals("pending"), "finished task not inherited");
    }

    static void trimScreens() throws Exception {
        JSONArray msgs = new JSONArray();
        for (int i = 0; i < 4; i++) msgs.put(o("role", "tool", "tool_call_id", "x" + i, "content", o("ok", true, "screen", "S" + i).toString()));
        AgentLoop.trimScreens(msgs);
        String s0 = new JSONObject(msgs.getJSONObject(0).getString("content")).getString("screen");
        String s3 = new JSONObject(msgs.getJSONObject(3).getString("content")).getString("screen");
        String s2 = new JSONObject(msgs.getJSONObject(2).getString("content")).getString("screen");
        check(s0.contains("省略") && s3.equals("S3") && s2.equals("S2"), "old screens trimmed, last two kept");
    }

    static void planOrderInBatch() throws Exception {
        Script m = new Script().calls("bili_search", o("keyword", "猫"), "plan", plan("搜猫", "搜索"))
                .calls("finish", o("status", "done", "reply", "好"));
        Fake bili = new Fake("bili_search", Tool.Gate.act());
        loop(m, new Events(), (w, l) -> true, bili).run(new AgentLoop.Request("搜猫", "test"));
        check(bili.runs.size() == 1, "plan in same batch runs first");
        JSONArray req = m.seen.get(1);
        check(req.getJSONObject(3).getString("tool_call_id").equals("c0") && req.getJSONObject(4).getString("tool_call_id").equals("c1"), "tool messages in original order");
    }

    static void badCallId() throws Exception {
        JSONObject dup = new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray()
                .put(o("id", "same", "type", "function", "function", o("name", "screen_read", "arguments", "{}")))
                .put(o("id", "same", "type", "function", "function", o("name", "screen_read", "arguments", "{}"))));
        Script m = new Script();
        m.replies.add(dup);
        Ledger t = loop(m, new Events(), (w, l) -> true, new Fake("screen_read", Tool.Gate.read())).run(new AgentLoop.Request("看看", "test"));
        check(t.state == Ledger.State.FAILED && t.error.contains("编号"), "duplicate call id stops: " + t.error);
    }

    static void modelError() throws Exception {
        AgentLoop.Model broken = new AgentLoop.Model() {
            int n;
            @Override public JSONObject next(JSONArray m, JSONArray t) throws Exception {
                if (n++ == 0) return new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray()
                        .put(o("id", "a", "type", "function", "function", o("name", "plan", "arguments", plan("g", "s").toString()))));
                throw new java.io.IOException("备用网关响应超时");
            }
            @Override public void cancel() { }
        };
        Ledger t = new AgentLoop(contract, broken, Collections.<Tool>emptyList(), null, (w, l) -> true, () -> 5L, null)
                .run(new AgentLoop.Request("做点事", "test"));
        check(t.state == Ledger.State.INTERRUPTED && t.reply.contains("超时") && t.reply.contains("继续"), "model failure after plan is resumable: " + t.reply);
    }

    static void progress() throws Exception {
        Ledger l = new Ledger("x", "u", "t", 0);
        l.plan("g", Arrays.asList("一", "二", "三"), "");
        l.touch();
        check(l.steps.get(0).status.equals("in_progress"), "touch starts first step");
        l.touch();
        check(l.steps.get(1).status.equals("pending"), "touch keeps a single running step");
        l.mark(3, "in_progress", "");
        check(l.steps.get(0).status.equals("done") && l.steps.get(1).status.equals("done") && l.steps.get(2).status.equals("in_progress"), "later step closes earlier ones");
        l.action("bili_search", "在哔哩哔哩搜索「猫」", "observed", true, false);
        l.action("screen_click", "点选「x」", "no_change", false, false);
        check(l.lastSuccess().equals("在哔哩哔哩搜索「猫」"), "last success skips failures");
        Script m = new Script().calls("plan", plan("两步", "搜索", "播放"), "bili_search", o("keyword", "猫")).calls("finish", o("status", "partial", "reply", "x"));
        Ledger t = loop(m, new Events(), (w, x) -> true, new Fake("bili_search", Tool.Gate.act())).run(new AgentLoop.Request("搜猫", "test"));
        check(t.steps.get(0).status.equals("in_progress"), "successful action shows progress without plan_update");
    }

    static void repeatNote() throws Exception {
        Fake click = new Fake("screen_click", Tool.Gate.act());
        Script m = new Script().calls("plan", plan("排序", "点最新发布"), "screen_click", o("id", 20))
                .calls("screen_click", o("id", 20)).calls("screen_click", o("id", 21))
                .calls("finish", o("status", "done", "reply", "好"));
        loop(m, new Events(), (w, l) -> true, click).run(new AgentLoop.Request("按最新排序", "test"));
        check(!m.toolResult("c1").has("repeat_note"), "first click no note");
        check(m.toolResult("c10").optString("repeat_note").contains("开关"), "identical repeat noted");
        check(!m.toolResult("c20").has("repeat_note"), "different target no note");
        // two alternating steps that keep coming back (B站 tab, 2026-11-05)
        Fake scroll = new Fake("screen_scroll", Tool.Gate.act());
        Script k = new Script().calls("plan", plan("综合", "切到综合"), "screen_scroll", o("direction", "down"))
                .calls("screen_click", o("id", 6)).calls("screen_scroll", o("direction", "down")).calls("screen_click", o("id", 6))
                .calls("finish", o("status", "failed", "reply", "切不过去"));
        loop(k, new Events(), (w, l) -> true, click, scroll).run(new AgentLoop.Request("切到综合", "test"));
        check(!k.toolResult("c20").has("loop_note") && !k.toolResult("c10").has("loop_note"), "no loop note before the pattern repeats");
        check(k.toolResult("c30").optString("loop_note").contains("来回重复"), "A-B-A-B loop noted");
        check(Replies.resume("继续") && Replies.resume("小爱同学，继续执行") && Replies.resume("接着刚才的任务") && Replies.resume("星仪继续")
                && !Replies.resume("继续播放周杰伦的歌") && !Replies.resume("打开B站"), "resume phrases");
    }

    static void replies() {
        for (String y : new String[]{"确认", "小爱同学，确认", "好的", "可以", "确认执行", "继续", "嗯", "安装吧", "同意。"})
            check(Boolean.TRUE.equals(Replies.consent(y)), "yes: " + y);
        for (String n : new String[]{"取消", "小爱同学，取消", "不要了", "算了", "不同意", "先不要", "不用了"})
            check(Boolean.FALSE.equals(Replies.consent(n)), "no: " + n);
        for (String x : new String[]{"打开B站", "确认一下现在音量多少", "可以帮我放首歌吗", "不要放周杰伦的歌，放林俊杰的"})
            check(Replies.consent(x) == null, "not an answer: " + x);
        check(Replies.stop("停") && Replies.stop("小爱同学，别做了") && Replies.stop("停下来吧") && !Replies.stop("停一下音乐放视频"), "stop words");
        check("可用 310 MB / 共 2.0 GB（内存紧张）".equals(DeviceFacts.memory(310L << 20, 2L << 30, true)), "memory: " + DeviceFacts.memory(310L << 20, 2L << 30, true));
        check("剩余 5.2 GB / 共 16.0 GB".equals(DeviceFacts.storage((long) (5.2 * (1L << 30)), 16L << 30)), "storage: " + DeviceFacts.storage((long) (5.2 * (1L << 30)), 16L << 30));
        check(DeviceFacts.storage(800L << 20, 16L << 30).endsWith("（快满了）"), "storage nearly full");
        check("9 天 4 小时".equals(DeviceFacts.uptime((9 * 24 + 4) * 3_600_000L + 5 * 60_000L)), "uptime in days");
        check("3 小时 5 分".equals(DeviceFacts.uptime(3 * 3_600_000L + 5 * 60_000L)) && "7 分钟".equals(DeviceFacts.uptime(7 * 60_000L)), "uptime in hours and minutes");
        check("信号好（-52 dBm），速率 433 Mbps".equals(DeviceFacts.wifiSignal(-52, 433)) && DeviceFacts.wifiSignal(-127, 0).isEmpty(), "wifi signal");
        check(DeviceFacts.wifiSignal(-80, 0).equals("信号很弱（-80 dBm）"), "a weak signal without a speed");
        for (String enough : new String[]{"别说了", "星仪，别说了", "闭嘴", "别念了", "好了好了", "行了行了", "算了", "不用了", "没事了"})
            check(Replies.stop(enough), "enough: " + enough);
        for (String more : new String[]{"算了，放首歌吧", "别说了放周杰伦", "没事了你帮我关电视", "不用了谢谢你把音量调小"})
            check(!Replies.stop(more), "more to it: " + more);
        check(Sensitive.why("同意并继续") != null && Sensitive.why("不同意") == null && Sensitive.why("立即开通大会员") != null
                && Sensitive.why("卸载") != null && Sensitive.why("搜索") == null && Sensitive.why("暂不开通") == null, "sensitive labels");
        check(Sensitive.securityDevice("把门锁打开") && !Sensitive.securityDevice("把客厅灯关了"), "security devices");
    }

    static void ledgerRoundTrip() throws Exception {
        Ledger l = new Ledger("id1", "说的话", "bridge", 42);
        l.plan("目标", Arrays.asList("一", "二"), "完成");
        l.mark(1, "done", "ok");
        l.action("bili_search", "搜索", "observed", true, false);
        l.state = Ledger.State.INTERRUPTED;
        l.reply = "停下了";
        Ledger r = Ledger.fromJson(new JSONObject(l.toJson().toString()));
        check(r.id.equals("id1") && r.goal.equals("目标") && r.steps.size() == 2 && r.steps.get(0).status.equals("done")
                && r.actions.size() == 1 && r.state == Ledger.State.INTERRUPTED && r.reply.equals("停下了") && r.source.equals("bridge"), "ledger json round trip");
        AtomicInteger n = new AtomicInteger();
        check(n.incrementAndGet() == 1, "sanity");
    }
}
