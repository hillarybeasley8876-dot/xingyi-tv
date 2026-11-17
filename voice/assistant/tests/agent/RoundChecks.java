package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** Lead checks from the 2026-11-05 perf pass: a round of memory tools answers by itself, with no second model round, and
 * a finish(done) next to actions closes the task only when their results are their own evidence. */
final class RoundChecks {
    private RoundChecks() { }

    static void run() throws Exception {
        memoryRound();
        notAlone();
        memoryAnswerText();
        finishBesideEvidence();
        finishSetAside();
        finishAfterClick();
        finishEvidenceRules();
        directConnectors();
        directLeftToModel();
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static AgentCoreTest.Fake memory(String name, boolean ok, String message) throws JSONException {
        AgentCoreTest.Fake f = new AgentCoreTest.Fake(name, Tool.Gate.read());
        f.reply = new JSONObject().put("ok", ok).put("status", ok ? "recorded" : "rejected").put("message", message);
        return f;
    }

    /** 「记住我喜欢猫」: one model round, and the tool's own words (in the second person) are the answer. */
    static void memoryRound() throws Exception {
        AgentCoreTest.Fake remember = memory("remember", true, "记住了：用户喜欢猫");
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls("remember", AgentCoreTest.o("fact", "用户喜欢猫"))
                .text("这一轮不该被问到");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, remember).run(new AgentLoop.Request("记住我喜欢猫", "test"));
        check(m.seen.size() == 1, "a memory-only round ends the task without a second model round: " + m.seen.size());
        check("记住了：你喜欢猫".equals(t.reply), "the answer is the tool's message, spoken to the user: " + t.reply);
        check(t.state == Ledger.State.ANSWERED, "the task is answered");
    }

    /** Anything else in the round, a failed memory call, or a planned task: the model still finishes as before. */
    static void notAlone() throws Exception {
        AgentCoreTest.Fake remember = memory("remember", true, "记住了：用户喜欢猫");
        AgentCoreTest.Fake read = new AgentCoreTest.Fake("screen_read", Tool.Gate.read());
        AgentCoreTest.Script mixed = new AgentCoreTest.Script()
                .calls("remember", AgentCoreTest.o("fact", "用户喜欢猫"), "screen_read", AgentCoreTest.o()).text("记住了，屏幕上是桌面");
        Ledger a = AgentCoreTest.loop(mixed, new AgentCoreTest.Events(), (w, l) -> true, remember, read).run(new AgentLoop.Request("记住我喜欢猫，再看看屏幕", "test"));
        check(mixed.seen.size() == 2 && "记住了，屏幕上是桌面".equals(a.reply), "memory plus another tool: the model answers");

        AgentCoreTest.Fake refused = memory("remember", false, "这类信息（密码、验证码、证件号）不记");
        AgentCoreTest.Script failed = new AgentCoreTest.Script().calls("remember", AgentCoreTest.o("fact", "密码是1234")).text("这个我不记");
        Ledger b = AgentCoreTest.loop(failed, new AgentCoreTest.Events(), (w, l) -> true, refused).run(new AgentLoop.Request("记住我的密码", "test"));
        check(failed.seen.size() == 2 && "这个我不记".equals(b.reply), "a refused memory call: the model explains");

        AgentCoreTest.Fake remember2 = memory("remember", true, "记住了：用户喜欢猫");
        AgentCoreTest.Fake search = new AgentCoreTest.Fake("bili_search", Tool.Gate.act());
        AgentCoreTest.Script planned = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("记住并搜索", "记住", "搜索"), "remember", AgentCoreTest.o("fact", "用户喜欢猫"))
                .calls("bili_search", AgentCoreTest.o("keyword", "猫"))
                .calls("finish", AgentCoreTest.o("status", "done", "reply", "记住了，也搜好了"));
        Ledger c = AgentCoreTest.loop(planned, new AgentCoreTest.Events(), (w, l) -> true, remember2, search).run(new AgentLoop.Request("记住我喜欢猫然后在B站搜猫", "test"));
        check(planned.seen.size() == 3 && "记住了，也搜好了".equals(c.reply), "a planned task goes on past its memory step");
    }

    static void memoryAnswerText() throws Exception {
        JSONArray calls = new JSONArray()
                .put(AgentCoreTest.o("id", "a", "function", AgentCoreTest.o("name", "forget", "arguments", "{}")))
                .put(AgentCoreTest.o("id", "b", "function", AgentCoreTest.o("name", "remember_name", "arguments", "{}")));
        JSONObject[] results = {AgentCoreTest.o("ok", true, "message", "已忘掉 2 条"),
                AgentCoreTest.o("ok", true, "message", "记住了：「星辰人旅行者」写作「星辰亻旅行者」")};
        check("已忘掉 2 条；记住了：「星辰人旅行者」写作「星辰亻旅行者」".equals(AgentLoop.directAnswer(calls, results)),
                "two memory calls are answered together");
        results[1] = AgentCoreTest.o("ok", true, "message", "");
        check(AgentLoop.directAnswer(calls, results) == null, "an empty message leaves the answer to the model");
        check(AgentLoop.directAnswer(new JSONArray(), new JSONObject[0]) == null, "no calls, no answer");
    }

    /** The deferred finish's own tool message, as the model sees it in the next round (null: none). */
    private static JSONObject deferred(AgentCoreTest.Script m, int request) throws JSONException {
        JSONArray messages = m.seen.get(request);
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            if (!"tool".equals(msg.optString("role"))) continue;
            JSONObject content = new JSONObject(msg.getString("content"));
            if ("deferred".equals(content.optString("status"))) return content;
        }
        return null;
    }

    /** 「放林俊杰的不为谁而作的歌」: plan, the connector and finish in one round, and the task is done in that round. */
    static void finishBesideEvidence() throws Exception {
        AgentCoreTest.Fake song = new AgentCoreTest.Fake("netease_play", Tool.Gate.act());
        song.reply = new JSONObject().put("ok", true).put("status", "played").put("message", "网易云音乐正在播放林俊杰的《不为谁而作的歌》。");
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("播放不为谁而作的歌", "用 netease_play 播放"),
                        "netease_play", AgentCoreTest.o("artist", "林俊杰", "title", "不为谁而作的歌"),
                        "finish", AgentCoreTest.o("status", "done", "reply", "已在网易云播放林俊杰的《不为谁而作的歌》。"))
                .text("这一轮不该被问到");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, song).run(new AgentLoop.Request("放林俊杰的不为谁而作的歌", "test"));
        check(m.seen.size() == 1 && song.runs.size() == 1, "connector + finish: one model round: " + m.seen.size());
        check(t.state == Ledger.State.DONE && "已在网易云播放林俊杰的《不为谁而作的歌》。".equals(t.reply), "the finish closes the task: " + t.state + " " + t.reply);
        boolean closed = !t.steps.isEmpty();
        for (Ledger.Step step : t.steps) closed &= "done".equals(step.status);
        check(closed, "and marks its steps done");

        AgentCoreTest.Fake vol = new AgentCoreTest.Fake("set_volume", Tool.Gate.act());
        AgentCoreTest.Script v = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("音量设为 30", "设音量"), "set_volume", AgentCoreTest.o("level", 30),
                        "finish", AgentCoreTest.o("status", "done", "reply", "音量调到 30 了"))
                .text("这一轮不该被问到");
        Ledger tv = AgentCoreTest.loop(v, new AgentCoreTest.Events(), (w, l) -> true, vol).run(new AgentLoop.Request("音量调到30", "test"));
        check(v.seen.size() == 1 && tv.state == Ledger.State.DONE, "the volume reports itself: one round");
    }

    /** The connector did not get there, or the finish sits next to something the model has not seen yet: the finish
     * is set aside, the model gets the results (and why), and finishes on what really happened. */
    static void finishSetAside() throws Exception {
        AgentCoreTest.Fake song = new AgentCoreTest.Fake("netease_play", Tool.Gate.act());
        song.reply = new JSONObject().put("ok", false).put("status", "unconfirmed").put("message", "已请求网易云点播，但播放器尚未报告目标歌曲正在播放。");
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("播放不为谁而作的歌", "用 netease_play 播放"),
                        "netease_play", AgentCoreTest.o("artist", "林俊杰", "title", "不为谁而作的歌"),
                        "finish", AgentCoreTest.o("status", "done", "reply", "已经在放了"))
                .calls("finish", AgentCoreTest.o("status", "failed", "reply", "网易云没有放起来，请看一下电视上的提示"));
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, song).run(new AgentLoop.Request("放林俊杰的不为谁而作的歌", "test"));
        check(m.seen.size() == 2, "a failed connector: the finish beside it does not close the task: " + m.seen.size());
        check(t.reply.startsWith("网易云没有放起来") && t.state != Ledger.State.DONE, "the model finishes on what happened: " + t.state + " " + t.reply);
        JSONObject note = deferred(m, 1);
        check(note != null && !note.optBoolean("ok", true) && note.optString("message").contains("没有生效"), "and is told its finish was set aside: " + note);
        check(song.runs.size() == 1, "the connector is not run again by the loop");

        AgentCoreTest.Fake search = new AgentCoreTest.Fake("web_search", Tool.Gate.read());
        search.reply = new JSONObject().put("ok", true).put("status", "observed").put("message", "上海今天多云，18 度");
        AgentCoreTest.Script w = new AgentCoreTest.Script()
                .calls("web_search", AgentCoreTest.o("query", "上海 今天 天气"), "finish", AgentCoreTest.o("status", "done", "reply", "上海今天晴"))
                .text("上海今天多云，18 度。");
        Ledger tw = AgentCoreTest.loop(w, new AgentCoreTest.Events(), (x, l) -> true, search).run(new AgentLoop.Request("上海天气怎么样", "test"));
        check(w.seen.size() == 2 && "上海今天多云，18 度。".equals(tw.reply), "a search beside a finish: the answer comes from what it found: " + tw.reply);

        AgentCoreTest.Fake app = new AgentCoreTest.Fake("open_app", Tool.Gate.act());
        app.reply = new JSONObject().put("ok", true).put("status", "requested").put("message", "已请求打开设置，前台还不是它，看一下屏幕上是什么");
        AgentCoreTest.Script o = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("打开设置", "打开"), "open_app", AgentCoreTest.o("app", "设置"),
                        "finish", AgentCoreTest.o("status", "done", "reply", "设置打开了"))
                .calls("finish", AgentCoreTest.o("status", "partial", "reply", "设置还没打开，再说一次试试"));
        AgentCoreTest.loop(o, new AgentCoreTest.Events(), (x, l) -> true, app).run(new AgentLoop.Request("打开设置", "test"));
        check(o.seen.size() == 2, "an app that is only requested, not in front yet: the finish waits for a look");
    }

    /** A click proves itself only once playback started (screen_click reports "playing"). */
    static void finishAfterClick() throws Exception {
        for (boolean playing : new boolean[] {false, true}) {
            AgentCoreTest.Fake bili = new AgentCoreTest.Fake("bili_search", Tool.Gate.act());
            AgentCoreTest.Fake click = new AgentCoreTest.Fake("screen_click", Tool.Gate.act());
            if (playing) click.reply.put("playing", AgentCoreTest.o("title", "时代的回声")).put("message", "已点选，正在播放《时代的回声》");
            else click.reply.put("message", "已点选，播放页已打开，还没读到播放状态");
            AgentCoreTest.Script m = new AgentCoreTest.Script()
                    .calls("plan", AgentCoreTest.plan("播放时代的回声", "搜索", "点开"), "bili_search", AgentCoreTest.o("keyword", "星辰亻旅行者"))
                    .calls("screen_click", AgentCoreTest.o("id", 8), "finish", AgentCoreTest.o("status", "done", "reply", "在放《时代的回声》了"))
                    .calls("finish", AgentCoreTest.o("status", "partial", "reply", "播放页开了，还没开始放"));
            Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, bili, click).run(new AgentLoop.Request("播放星辰亻旅行者的MAD", "test"));
            if (playing) check(m.seen.size() == 2 && t.state == Ledger.State.DONE, "a click that started playback closes with its finish: " + m.seen.size());
            else check(m.seen.size() == 3 && t.reply.startsWith("播放页开了"), "a click not yet playing: the model looks first: " + m.seen.size());
        }
    }

    /** closesOnEvidence on its own: which results prove themselves. */
    static void finishEvidenceRules() throws Exception {
        JSONObject ok = AgentCoreTest.o("ok", true, "status", "observed", "message", "好");
        check(AgentLoop.closesOnEvidence(calls("plan_update", "{}", "finish", "{}"), new JSONObject[] {ok, ok}), "no action at all: the finish stands");
        check(!AgentLoop.closesOnEvidence(calls("sleep_timer", "{}"), new JSONObject[] {ok}), "sleep_timer without minutes only reads the timer");
        check(AgentLoop.closesOnEvidence(calls("sleep_timer", "{\"minutes\":30}"), new JSONObject[] {ok}), "sleep_timer that sets one proves itself");
        check(!AgentLoop.closesOnEvidence(calls("press_keys", "{}"), new JSONObject[] {ok}), "keys prove nothing by themselves");
        check(!AgentLoop.closesOnEvidence(calls("tv_status", "{}"), new JSONObject[] {ok}), "a read is not evidence for a finish written before it");
        check(!AgentLoop.closesOnEvidence(calls("bili_search", "{}"), new JSONObject[] {AgentCoreTest.o("ok", true, "status", "no_change")}), "no_change is not done");
        check(!AgentLoop.closesOnEvidence(calls("netease_play", "{}", "set_volume", "{}"),
                new JSONObject[] {ok, AgentCoreTest.o("ok", false, "status", "denied")}), "one failed action is enough to wait");
        check(AgentLoop.closesOnEvidence(calls("netease_play", "{}", "set_volume", "{}"), new JSONObject[] {ok, ok}), "two connectors that both report success");
    }

    private static JSONArray calls(String... nameArgs) throws JSONException {
        JSONArray a = new JSONArray();
        for (int i = 0; i < nameArgs.length; i += 2)
            a.put(AgentCoreTest.o("id", "c" + i, "function", AgentCoreTest.o("name", nameArgs[i], "arguments", nameArgs[i + 1])));
        return a;
    }

    private static Ledger direct(AgentCoreTest.Script m, String said, Tool... tools) throws Exception {
        return AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, tools).run(new AgentLoop.Request(said, "test"));
    }

    /** 「放首周杰伦的歌」「音量调到30」「静音」: no plan, one model round, the connector's own result is the answer. */
    static void directConnectors() throws Exception {
        AgentCoreTest.Fake song = new AgentCoreTest.Fake("netease_play", Tool.Gate.act());
        song.reply = new JSONObject().put("ok", true).put("status", "played").put("message", "网易云音乐正在播放周杰伦的《晴天》。");
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls("netease_play", AgentCoreTest.o("artist", "周杰伦")).text("这一轮不该被问到");
        Ledger t = direct(m, "放首周杰伦的歌", song);
        check(m.seen.size() == 1 && song.runs.size() == 1, "a song without a plan: one model round: " + m.seen.size());
        check(t.state == Ledger.State.ANSWERED && "网易云音乐正在播放周杰伦的《晴天》。".equals(t.reply), "the connector's words are the answer: " + t.reply);

        AgentCoreTest.Fake vol = new AgentCoreTest.Fake("set_volume", Tool.Gate.act());
        vol.reply = new JSONObject().put("ok", true).put("status", "observed").put("message", "音量现在是 30/100");
        AgentCoreTest.Script v = new AgentCoreTest.Script().calls("set_volume", AgentCoreTest.o("level", 30)).text("这一轮不该被问到");
        check("音量调到 30 了".equals(direct(v, "音量调到30", vol).reply) && v.seen.size() == 1, "the volume is said as a number, not a fraction");

        AgentCoreTest.Fake mute = new AgentCoreTest.Fake("adjust_volume", Tool.Gate.act());
        mute.reply = new JSONObject().put("ok", true).put("status", "observed").put("message", "音量 30/100，已静音").put("volume", 30).put("muted", true);
        AgentCoreTest.Script u = new AgentCoreTest.Script().calls("adjust_volume", AgentCoreTest.o("direction", "mute")).text("这一轮不该被问到");
        check("已静音".equals(direct(u, "静音", mute).reply), "mute says so");

        AgentCoreTest.Fake timer = new AgentCoreTest.Fake("sleep_timer", Tool.Gate.act());
        timer.reply = new JSONObject().put("ok", true).put("status", "observed").put("message", "已设置 30 分钟后休眠");
        AgentCoreTest.Script z = new AgentCoreTest.Script().calls("sleep_timer", AgentCoreTest.o("minutes", 30)).text("这一轮不该被问到");
        check("已设置 30 分钟后休眠".equals(direct(z, "半小时后关机", timer).reply) && z.seen.size() == 1, "a timer that was set answers itself");

        AgentCoreTest.Fake remember = memory("remember", true, "记住了：用户喜欢周杰伦");
        AgentCoreTest.Fake song2 = new AgentCoreTest.Fake("netease_play", Tool.Gate.act());
        song2.reply = new JSONObject().put("ok", true).put("status", "played").put("message", "网易云音乐正在播放周杰伦的《晴天》。");
        AgentCoreTest.Script both = new AgentCoreTest.Script()
                .calls("remember", AgentCoreTest.o("fact", "用户喜欢周杰伦"), "netease_play", AgentCoreTest.o("artist", "周杰伦")).text("这一轮不该被问到");
        check("记住了：你喜欢周杰伦；网易云音乐正在播放周杰伦的《晴天》。".equals(direct(both, "我喜欢周杰伦，放首他的歌", remember, song2).reply),
                "a memory and a song in one round are answered together");
    }

    /** What still goes back to the model: a connector that failed, a step that is often only the first one, a read. */
    static void directLeftToModel() throws Exception {
        AgentCoreTest.Fake song = new AgentCoreTest.Fake("netease_play", Tool.Gate.act());
        song.reply = new JSONObject().put("ok", false).put("status", "not_found").put("message", "网易云曲库里没有找到这首歌");
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls("netease_play", AgentCoreTest.o("title", "不存在的歌")).text("网易云里没有这首歌。");
        Ledger t = direct(m, "放不存在的歌", song);
        check(m.seen.size() == 2 && "网易云里没有这首歌。".equals(t.reply), "a failed connector: the model says what happened");

        AgentCoreTest.Fake bili = new AgentCoreTest.Fake("bili_search", Tool.Gate.act());
        AgentCoreTest.Script b = new AgentCoreTest.Script().calls("bili_search", AgentCoreTest.o("keyword", "猫")).text("B站的猫搜好了。");
        check(direct(b, "B站搜猫", bili).reply.equals("B站的猫搜好了。") && b.seen.size() == 2, "a search page: the model answers (or plans the rest)");

        AgentCoreTest.Fake timer = new AgentCoreTest.Fake("sleep_timer", Tool.Gate.read());
        timer.reply = new JSONObject().put("ok", true).put("status", "observed").put("message", "没有休眠定时");
        AgentCoreTest.Script r = new AgentCoreTest.Script().calls("sleep_timer", AgentCoreTest.o()).text("现在没有定时。");
        check(direct(r, "有定时吗", timer).reply.equals("现在没有定时。") && r.seen.size() == 2, "reading the timer: the model answers");

        AgentCoreTest.Fake fixed = memory("remember_name", true, "记住了：「星辰人旅行者」写作「星辰亻旅行者」");
        AgentCoreTest.Script why = new AgentCoreTest.Script()
                .calls("remember_name", AgentCoreTest.o("heard", "星辰人旅行者", "written", "星辰亻旅行者"))
                .text("语音把“亻”听成了“人”，所以搜错了；正确写法已经记住。");
        Ledger y = direct(why, "你刚才为什么搜错了", fixed);
        check(why.seen.size() == 2 && y.reply.startsWith("语音把"), "a why-question gets the model's explanation, not 「记住了」: " + y.reply);
        check(AgentLoop.asksWhy("怎么又放错了") && !AgentLoop.asksWhy("记住我喜欢猫") && !AgentLoop.asksWhy(null), "which questions ask why");

        AgentCoreTest.Fake guess = new AgentCoreTest.Fake("netease_play", Tool.Gate.act());
        AgentCoreTest.Script a = new AgentCoreTest.Script()
                .calls("ask_user", AgentCoreTest.o("question", "放哪一首？"), "netease_play", AgentCoreTest.o("artist", "周杰伦"));
        Ledger asked = direct(a, "放那个", guess);
        check(guess.runs.isEmpty() && asked.state == Ledger.State.AWAITING_USER, "a round that asks the user plays nothing on a guess");
    }
}
