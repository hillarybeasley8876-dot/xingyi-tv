package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** loop-fixes (2026-11-08): a long tool call does not use up the task's budget; the screen-off reply says what
 * happened; one installer search per app (InstallerCache). */
final class LoopFixChecks {
    private LoopFixChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    /** The apps QuickCommand knows by name, as QuickChecks passes them. */
    private static final List<String> APPS = Arrays.asList("哔哩哔哩", "网易云音乐", "云视听极光", "电视设置", "相册");
    private static final String TVTOOLS = "src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java";

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "loop fix checks threw " + e);
        }
    }

    static void checks() throws Exception {
        budget();
        screenOff();
        installers();
        deniedOneStep();
        wakeBeside();
    }

    // ---- wake_screen beside the connector it was for -----------------------------------------------

    private static JSONArray round(Object... nameArgs) throws Exception {
        JSONArray calls = new JSONArray();
        for (int i = 0; i < nameArgs.length; i += 2)
            calls.put(new JSONObject().put("id", "c" + i / 2).put("type", "function").put("function",
                    new JSONObject().put("name", nameArgs[i]).put("arguments", nameArgs[i + 1].toString())));
        return calls;
    }

    /** 2026-11-11: the screen lit for the connector in the same round is not what the answer is about. */
    static void wakeBeside() throws Exception {
        JSONObject lit = new JSONObject("{\"ok\":true,\"status\":\"observed\",\"message\":\"屏幕已点亮\"}");
        JSONObject played = new JSONObject("{\"ok\":true,\"status\":\"observed\",\"message\":\"已在云视听极光播放《别无选择》（电视剧，2015，共 30 集）；不是会员的话正片前有一两分钟广告\"}");
        JSONArray both = round("wake_screen", AgentCoreTest.o(), "tencent_video_open", AgentCoreTest.o("title", "别无选择"));
        String said = AgentLoop.directAnswer(both, new JSONObject[]{lit, played});
        check(played.optString("message").equals(said), "wake_screen beside tencent_video_open: the connector's sentence is the answer: " + said);
        // alone it still leaves the answer to the model (「亮屏」 is a quick command; here the model had more to do)
        check(AgentLoop.directAnswer(round("wake_screen", AgentCoreTest.o()), new JSONObject[]{lit}) == null,
                "wake_screen alone closes nothing");
        check(AgentLoop.directAnswer(round("wake_screen", AgentCoreTest.o(), "wake_screen", AgentCoreTest.o()), new JSONObject[]{lit, lit}) == null,
                "two wake_screens say nothing either");
        // a wake that failed keeps the round open: the connector may have run on a dark screen
        JSONObject dark = new JSONObject("{\"ok\":false,\"status\":\"unconfirmed\",\"message\":\"按了电源键，屏幕还没亮\"}");
        check(AgentLoop.directAnswer(both, new JSONObject[]{dark, played}) == null, "a wake that failed leaves the answer to the model");
        // and beside something that is not an answer (a search page) nothing changes
        JSONObject page = new JSONObject("{\"ok\":true,\"status\":\"observed\",\"message\":\"搜索页已打开\"}");
        check(AgentLoop.directAnswer(round("wake_screen", AgentCoreTest.o(), "bili_search", AgentCoreTest.o("keyword", "猫")),
                new JSONObject[]{lit, page}) == null, "wake_screen beside a search page: the model still answers");
    }

    // ---- a one-step connector the gate refuses ----------------------------------------------------

    /** 2026-11-11: a one-step connector that its gate refuses tells the model why straight away. It used to hear
     * "write a plan first", and needed two more rounds to reach the same refusal (「大点声」 after XiaoAi had already
     * turned it up; every video and music connector on a silent test while the TV sleeps). */
    static void deniedOneStep() throws Exception {
        String why = "小爱已经为这句话调过音量了，再调一次会翻倍";
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("adjust_volume", AgentCoreTest.o("direction", "up"))
                .text("小爱已经调大了，现在音量 12。");
        AgentCoreTest.Fake volume = new AgentCoreTest.Fake("adjust_volume", Tool.Gate.deny(why));
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, volume)
                .run(new AgentLoop.Request("大点声", "test"));
        JSONObject told = m.toolResult("c0");
        check(told != null && "denied".equals(told.optString("status")) && why.equals(told.optString("message")),
                "a refused one-step connector says why, with no plan asked for: " + told);
        check(volume.runs.isEmpty(), "…and it did not run");
        check(m.seen.size() == 2 && t.state == Ledger.State.ANSWERED && t.reply.contains("音量 12"),
                "…so the answer comes in the next round: " + m.seen.size() + " " + t.state + " " + t.reply);

        // what is not a one-step connector still has to be planned before anything is said about it
        AgentCoreTest.Script m2 = new AgentCoreTest.Script()
                .calls("press_keys", AgentCoreTest.o("keys", new JSONArray(Arrays.asList("ok"))))
                .text("没有做。");
        AgentCoreTest.Fake keys = new AgentCoreTest.Fake("press_keys", Tool.Gate.deny("不行"));
        AgentCoreTest.loop(m2, new AgentCoreTest.Events(), (w, l) -> true, keys).run(new AgentLoop.Request("按一下确认", "test"));
        JSONObject planFirst = m2.toolResult("c0");
        check(planFirst != null && AgentLoop.NEED_PLAN.equals(planFirst.optString("message")) && keys.runs.isEmpty(),
                "a refused key press without a plan is still asked for its plan first: " + planFirst);

        // and a one-step connector that would need the user's yes is not waved through either
        AgentCoreTest.Script m3 = new AgentCoreTest.Script()
                .calls("open_link", AgentCoreTest.o("uri", "https://example.com/pay"))
                .text("没有做。");
        AgentCoreTest.Fake link = new AgentCoreTest.Fake("open_link", Tool.Gate.sensitive("打开付款页"));
        AgentCoreTest.loop(m3, new AgentCoreTest.Events(), (w, l) -> true, link).run(new AgentLoop.Request("打开那个链接", "test"));
        JSONObject sensitive = m3.toolResult("c0");
        check(sensitive != null && AgentLoop.NEED_PLAN.equals(sensitive.optString("message")) && link.runs.isEmpty(),
                "a one-step connector that needs the user's yes still needs its plan: " + sensitive);
    }

    // ---- the budget -------------------------------------------------------------------------------

    /** A clock the checks move by hand: nothing in the core advances it but the tool made to take time. */
    private static final class Time implements AgentLoop.Clock {
        long t = 1_000_000;
        @Override public long now() { return t; }
    }

    /** The scripted model, spending think[i] ms before reply i (the last figure repeats): thinking is what the budget
     * is for, a tool's own wait is not. */
    private static AgentLoop.Model thinking(final AgentCoreTest.Script m, final Time time, final long... think) {
        return new AgentLoop.Model() {
            @Override public JSONObject next(JSONArray messages, JSONArray tools) throws Exception {
                time.t += think[Math.min(m.seen.size(), think.length - 1)];
                return m.next(messages, tools);
            }
            @Override public void cancel() { m.cancel(); }
        };
    }

    private static AgentLoop loop(AgentLoop.Model model, Time time, Tool... tools) {
        return new AgentLoop(AgentCoreTest.contract, model, Arrays.asList(tools), new AgentCoreTest.Events(),
                (w, l) -> true, time, null);
    }

    static void budget() throws Exception {
        // 2026-11-08 on the TV: install_app (TV Bro, 150 MB from GitHub) took 312 s, the 150 s budget ran out right
        // after it and 「打开浏览器看看」 ended as 「太复杂」 — the browser was installed and one open_app away.
        Time time = new Time();
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("装个浏览器并打开", "装浏览器", "打开浏览器"), "install_app", AgentCoreTest.o("app", "TV Bro"))
                .calls("open_app", AgentCoreTest.o("app", "TV Bro"))
                .calls("finish", AgentCoreTest.o("status", "done", "reply", "浏览器装好了，也打开了。"));
        AgentCoreTest.Fake install = new AgentCoreTest.Fake("install_app", Tool.Gate.act());
        AgentCoreTest.Fake open = new AgentCoreTest.Fake("open_app", Tool.Gate.act());
        install.during = () -> time.t += 300_000;
        Ledger t = loop(thinking(m, time, 0), time, install, open).run(new AgentLoop.Request("装个浏览器并打开", "test"));
        check(t.state == Ledger.State.DONE && open.runs.size() == 1,
                "a 300 s install leaves budget for the open_app after it: " + t.state + " " + t.reply);

        // ordinary rounds still pay for themselves: three minutes of thinking is past the budget, tool or no tool
        Time slow = new Time();
        AgentCoreTest.Script reading = new AgentCoreTest.Script().calls("plan", AgentCoreTest.plan("一直读", "读屏幕", "再读"));
        for (int i = 0; i < 5; i++) reading.calls("screen_read", AgentCoreTest.o());
        AgentCoreTest.Fake read = new AgentCoreTest.Fake("screen_read", Tool.Gate.read());
        Ledger stopped = loop(thinking(reading, slow, 60_000), slow, read).run(new AgentLoop.Request("一直读", "test"));
        check(stopped.state == Ledger.State.INTERRUPTED && reading.seen.size() == 3 && stopped.reply.contains("继续"),
                "three rounds of a minute's thinking still stop at the budget: " + reading.seen.size() + " " + stopped.state);

        // a 25 s tool gives back exactly the 5 s it ran past 20: 130 s of thinking + 25 s of tool is 155 s, and the
        // deadline has moved from 150 s to 155 s, so the next round runs — one second more of thinking and it stops
        check(roundAfterRuns(130_000, 25_000), "a 25 s tool pushes the deadline back by 5 s");
        check(!roundAfterRuns(131_000, 25_000), "…by 5 s and no more");
        check(!roundAfterRuns(131_000, 20_000), "a 20 s tool gives nothing back: 131 s of thinking and it still stops");
    }

    /** A planned task that thinks for think ms and then reads for tool ms in its first round, and works quickly after
     * that: does the round after the long one still run, and does the task get to finish? */
    private static boolean roundAfterRuns(long think, long tool) throws Exception {
        Time time = new Time();
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("读屏幕", "读屏幕", "再读一次"), "screen_read", AgentCoreTest.o())
                .calls("screen_read", AgentCoreTest.o())
                .calls("finish", AgentCoreTest.o("status", "done", "reply", "读完了。"));
        AgentCoreTest.Fake read = new AgentCoreTest.Fake("screen_read", Tool.Gate.read());
        int[] reads = {0};
        read.during = () -> { if (reads[0]++ == 0) time.t += tool; };
        Ledger t = loop(thinking(m, time, think, 0), time, read).run(new AgentLoop.Request("读一下屏幕", "test"));
        return m.seen.size() == 3 && read.runs.size() == 2 && t.state == Ledger.State.DONE;
    }

    // ---- 「关闭屏幕」 ------------------------------------------------------------------------------

    static void screenOff() throws Exception {
        String off = "{\"ok\":true,\"status\":\"observed\",\"message\":\"" + QuickCommand.SCREEN_OFF + "\"}";
        String already = "{\"ok\":true,\"status\":\"observed\",\"message\":\"" + QuickCommand.SCREEN_OFF_ALREADY + "\"}";
        check("屏幕关了，电视还在待机，叫一声小爱同学就能用".equals(said("关闭屏幕", off)),
                "「关闭屏幕」 says the screen is off and the TV is in standby: " + said("关闭屏幕", off));
        check("屏幕本来就是关着的，电视在待机".equals(said("关闭屏幕", already)),
                "…and says so when the screen was already off: " + said("关闭屏幕", already));
        check("屏幕本来就是关着的，电视在待机".equals(said("关电视", already)) && "屏幕本来就是关着的，电视在待机".equals(said("熄屏", already)),
                "every wording of it answers the same way");
        // a result the tool does not produce any more (an old build, a failure) must not become a claim about the TV
        for (String result : new String[]{"{}", "{\"ok\":false,\"message\":\"调试服务连不上\"}", "{\"ok\":true,\"message\":\"屏幕已经关了\"}"}) {
            String reply = said("关闭屏幕", result);
            check(!reply.contains("电视已经关") && !reply.contains("电视待机了"),
                    "never claims the TV is off (" + result + " → " + reply + ")");
        }
        // the tool's own messages, which the model relays when it is not a quick command
        String tv = source(TVTOOLS);
        // already off: 「本来就是关着的」, unless XiaoAi turned it off for this very sentence (2026-11-09 review)
        check(tv.contains("ok(\"observed\", acted ? QuickCommand.SCREEN_OFF : QuickCommand.SCREEN_OFF_ALREADY)")
                        && tv.contains("ok(\"observed\", QuickCommand.SCREEN_OFF)"),
                "screen_off returns the standby sentence as its message");
        check(!tv.contains("屏幕已经关了") && !tv.contains("电视已经关了") && !tv.contains("屏幕已关，电视待机了"),
                "no old screen-off message is left in TvTools");
    }

    /** What 星仪 says right after the quick command ran with that result. */
    private static String said(String utterance, String result) throws Exception {
        QuickCommand q = QuickCommand.match(utterance, APPS);
        return q == null ? "（没有匹配）" : q.reply(new JSONObject(result));
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    // ---- one installer search per app -------------------------------------------------------------

    static void installers() throws Exception {
        long at = 1_700_000_000_000L;
        InstallerCache cache = new InstallerCache();
        JSONObject found = AgentCoreTest.o("ok", true, "status", "observed", "app", "TV Bro",
                "url", "https://github.com/x/releases/y.apk", "found", true);
        cache.put("TV Bro", found, at);
        JSONObject again = cache.get("  tv BRO ", at + 60_000);
        check(again != null && "https://github.com/x/releases/y.apk".equals(again.optString("url")),
                "the same app in another case and with spaces around it is served from the cache");
        check(cache.get("Kodi", at + 60_000) == null && cache.get("TV Bro 2", at + 60_000) == null,
                "another app is never served from it");
        check(cache.get("TV Bro", at + InstallerCache.TEN_MINUTES - 1) != null, "a remembered installer is good for ten minutes");
        check(cache.get("TV Bro", at + InstallerCache.TEN_MINUTES) == null, "…and goes stale at ten minutes");
        check(cache.size() == 0, "the stale entry is dropped, not kept: " + cache.size());
        // what a caller adds to what it got (「cached」) must not change what the next caller gets
        cache.put("TV Bro", found, at);
        cache.get("TV Bro", at).put("cached", true);
        check(!cache.get("TV Bro", at).optBoolean("cached"), "the caller gets a copy: its 「cached」 does not stick");
        found.put("url", "https://elsewhere/z.apk");
        check("https://github.com/x/releases/y.apk".equals(cache.get("TV Bro", at).optString("url")),
                "what was put in is copied too: changing the result afterwards changes nothing");

        // find_installer asks it first and marks what it served
        String tv = source(TVTOOLS);
        check(tv.contains("installers.get(app, System.currentTimeMillis())") && tv.contains("remembered.put(\"cached\", true)"),
                "find_installer serves a second call for the same app from the cache, marked cached");
        check(tv.contains("installers.put(app, found, System.currentTimeMillis())"),
                "…and remembers what the search concluded");
    }
}
