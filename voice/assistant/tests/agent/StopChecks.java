package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** An early stop says truthfully what was done and what is on the TV now (job honest-stop fills this in). */
final class StopChecks {
    static void run() throws Exception {
        stoppedSaysClickAndScreen();
        observerQuiet();
        nothingDone();
        limits();
        sayEvents();
        ledgerReadFlags();
    }

    /** Records every event with its text, so a check can look at what the host would have heard. */
    static final class Rec implements AgentLoop.Listener {
        final List<String> seen = new ArrayList<>();
        @Override public void onEvent(String type, Ledger task, String text) { seen.add(type + "|" + text); }
        boolean said(String text) { return seen.contains("say|" + text); }
        boolean anySay() { for (String s : seen) if (s.startsWith("say|")) return true; return false; }
    }

    /** The same loop the other checks build, but with a listener of our own. */
    static AgentLoop loop(AgentCoreTest.Script model, AgentLoop.Listener listener, Tool... tools) {
        long[] t = {1_000_000};
        return new AgentLoop(AgentCoreTest.contract, model, Arrays.asList(tools), listener, (w, l) -> true,
                () -> t[0] += 10, null);
    }

    /** A read-only tool that speaks one line while it runs. */
    static final class Speaker implements Tool {
        final String line;
        Speaker(String line) { this.line = line; }
        @Override public String name() { return "screen_read"; }
        @Override public Gate gate(JSONObject args, Turn turn) { return Gate.read(); }
        @Override public String describe(JSONObject args, Turn turn) { return "看一下屏幕"; }
        @Override public JSONObject run(JSONObject args, Turn turn) throws Exception {
            turn.say(line);
            return new JSONObject().put("ok", true).put("status", "observed").put("message", "已观察");
        }
    }

    /** A task that clicks once and then only reads until the round limit, exactly like the 2026-11-04 run. */
    static void stoppedSaysClickAndScreen() throws Exception {
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("在B站找视频并播放", "搜索", "点开最新一个", "确认在播"),
                        "screen_click", AgentCoreTest.o("id", 3));
        for (int i = 0; i < 25; i++) m.calls("screen_read", AgentCoreTest.o());
        AgentCoreTest.Fake click = new AgentCoreTest.Fake("screen_click", Tool.Gate.act());
        AgentCoreTest.Fake read = new AgentCoreTest.Fake("screen_read", Tool.Gate.read());
        AtomicInteger asked = new AtomicInteger();
        AgentLoop loop = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, click, read);
        loop.observe(() -> { asked.incrementAndGet(); return "前台是哔哩哔哩，正在播放《示例小镇》。"; });
        Ledger t = loop.run(new AgentLoop.Request("让星仪打开B站搜索星辰旅行者，播放最新的一个视频", "test"));
        check(t.state == Ledger.State.INTERRUPTED && click.runs.size() == 1 && read.runs.size() == 19,
                "20 rounds, then the task stops: " + read.runs.size());
        check(t.reply.contains("screen_click") && !t.reply.contains("screen_read"),
                "the last thing that changed the TV is named, not the read after it: " + t.reply);
        check(t.reply.contains("现在前台是哔哩哔哩，正在播放《示例小镇》。") && !t.reply.contains("。。"),
                "what is on the TV now is said once, with one 。: " + t.reply);
        check(asked.get() == 1, "the observer is asked exactly once: " + asked.get());
        check(t.reply.endsWith("说“星仪继续”可以接着做。"), "the resume hint is still there: " + t.reply);
    }

    /** A run that only reads until the round limit; returns the stop reply. observer null = not set. */
    static String stopReply(AgentLoop.Observer observer) throws Exception {
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls("plan", AgentCoreTest.plan("读屏幕", "读屏幕"));
        for (int i = 0; i < 25; i++) m.calls("screen_read", AgentCoreTest.o());
        AgentLoop loop = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true,
                new AgentCoreTest.Fake("screen_read", Tool.Gate.read()));
        if (observer != null) loop.observe(observer);
        return loop.run(new AgentLoop.Request("一直读屏幕", "test")).reply;
    }

    static void observerQuiet() throws Exception {
        check(!stopReply(null).contains("现在"), "no observer: nothing is claimed about the screen");
        check(!stopReply(() -> null).contains("现在"), "observer that knows nothing");
        check(!stopReply(() -> "").contains("现在"), "observer returning an empty line");
        check(!stopReply(() -> "   ").contains("现在"), "observer returning only spaces");
        check(!stopReply(() -> { throw new IllegalStateException("adb 断了"); }).contains("现在"),
                "a throwing observer never breaks the stop reply");
        check(stopReply(() -> "电视黑屏").contains("现在电视黑屏。"), "an observer without 。 still reads as a sentence");
    }

    static void nothingDone() throws Exception {
        String reply = stopReply(null);
        check(!reply.contains("已经做到"), "reads alone are not something done: " + reply);
        check(reply.startsWith("这件事比预想的复杂，我先停下了。"), "the stop itself is still said: " + reply);
    }

    static void limits() {
        AgentLoop.Limits l = new AgentLoop.Limits();
        check(l.rounds == 20 && l.calls == 36 && l.failures == 3 && l.millis == 150_000L,
                "defaults: 20 rounds, 36 calls, 3 failures, 150 s");
    }

    static void sayEvents() throws Exception {
        check(saySeen("我查一下"), "a tool's say() reaches the listener");
        check(!saySeen("  "), "blank say() emits nothing");
        check(metaNotAnAction(), "plan / plan_update / finish are not actions");
    }

    /** True when the run emitted a say event carrying this line (for a blank line: any say event at all). */
    static boolean saySeen(String line) throws Exception {
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("看一眼", "看一眼"), "screen_read", AgentCoreTest.o())
                .calls("finish", AgentCoreTest.o("status", "done", "reply", "看完了"));
        Rec rec = new Rec();
        loop(m, rec, new Speaker(line)).run(new AgentLoop.Request("看一眼屏幕", "test"));
        return line.trim().isEmpty() ? rec.anySay() : rec.said(line);
    }

    static boolean metaNotAnAction() throws Exception {
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("看一眼", "看一眼"), "screen_read", AgentCoreTest.o())
                .calls("plan_update", AgentCoreTest.o("step", 1, "status", "done"))
                .calls("finish", AgentCoreTest.o("status", "done", "reply", "看完了"));
        Ledger t = loop(m, new Rec(), new Speaker("我查一下"))
                .run(new AgentLoop.Request("看一眼屏幕", "test"));
        return t.actions.size() == 1 && "screen_read".equals(t.actions.get(0).optString("tool"))
                && t.actions.get(0).optBoolean("read");
    }

    static void ledgerReadFlags() throws Exception {
        Ledger l = new Ledger("s1", "播放最新视频", "test", 7);
        l.action("screen_read", "看一下屏幕", "observed", true, true);
        l.action("screen_click", "点选「示例小镇」", "ok", true, false);
        l.action("media_now_playing", "看正在播放什么", "observed", true, true);
        check(l.lastSuccess().equals("点选「示例小镇」"), "reads after the click are not what was done: " + l.lastSuccess());
        JSONArray acts = l.toJson().getJSONArray("actions");
        check(acts.getJSONObject(0).optBoolean("read") && acts.getJSONObject(2).optBoolean("read")
                && !acts.getJSONObject(1).has("read"), "read:true on reads only, no key on the others");
        Ledger r = Ledger.fromJson(new JSONObject(l.toJson().toString()));
        check(r.lastSuccess().equals("点选「示例小镇」") && r.toJson().getJSONArray("actions").getJSONObject(0).optBoolean("read"),
                "json round trip keeps the read flags: " + r.lastSuccess());
        JSONArray shown = l.forModel().getJSONArray("actions");
        check(shown.getJSONObject(0).optBoolean("read") && !shown.getJSONObject(1).has("read"), "forModel shows read on reads only");
        Ledger old = new Ledger("s2", "u", "t", 0);
        old.actions.add(new JSONObject().put("tool", "screen_read").put("what", "看一下屏幕").put("status", "observed").put("ok", true));
        check(old.lastSuccess().equals("看一下屏幕"), "a ledger written before the flag still reads as it did");
        check(new Ledger("s3", "u", "t", 0).lastSuccess().isEmpty(), "no action at all: nothing was done");
    }

    static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }
}
