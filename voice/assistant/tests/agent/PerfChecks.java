package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** Checks for job perf: a system prompt that never changes (so the gateway can cache it), the timing trace every
 * task leaves behind, and the summaries the host writes to perf.jsonl. */
final class PerfChecks {
    private PerfChecks() { }

    static void run() throws Exception {
        staticSystem();
        profileInUserMessage();
        timingTrace();
        failingTools();
        summary();
        rollup();
        ring();
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static JSONObject o(Object... kv) throws JSONException { return AgentCoreTest.o(kv); }

    private static JSONObject plan(String goal, String... steps) throws JSONException { return AgentCoreTest.plan(goal, steps); }

    /** A clock that only moves when a test says so, so a measured ms is exactly what the model or the tool "took". */
    static final class Clock implements AgentLoop.Clock {
        long t = 5_000;
        @Override public long now() { return t; }
        void advance(long ms) { t += ms; }
    }

    /** A scripted model that also moves the clock: round i costs the i-th number of ms. */
    static final class Rounds implements AgentLoop.Model {
        final AgentCoreTest.Script script = new AgentCoreTest.Script();
        final Clock clock;
        final long[] ms;
        int i;
        Rounds(Clock clock, long... ms) { this.clock = clock; this.ms = ms; }
        @Override public JSONObject next(JSONArray messages, JSONArray tools) throws Exception {
            clock.advance(ms[Math.min(i++, ms.length - 1)]);
            return script.next(messages, tools);
        }
        @Override public void cancel() { script.cancel(); }
    }

    /** Every event of a run, with the timing lines parsed so a check can look inside them. */
    static final class Trace implements AgentLoop.Listener {
        final List<String> types = new ArrayList<>();
        final List<JSONObject> timings = new ArrayList<>();
        @Override public void onEvent(String type, Ledger task, String text) {
            types.add(type);
            if (!"timing".equals(type)) return;
            try { timings.add(new JSONObject(text)); } catch (JSONException e) { timings.add(null); }
        }
        int timingEvents() { return Collections.frequency(types, "timing"); }
        /** "kind name" of each timing line, so one check can pin the whole order down. */
        String shape() {
            StringBuilder s = new StringBuilder();
            for (JSONObject t : timings) s.append(t == null ? "?" : t.optString("kind")).append(' ');
            return s.toString().trim();
        }
        List<JSONObject> of(String kind) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject t : timings) if (t != null && kind.equals(t.optString("kind"))) out.add(t);
            return out;
        }
    }

    private static AgentLoop loop(Rounds model, Trace trace, Tool... tools) {
        return new AgentLoop(AgentCoreTest.contract, model, Arrays.asList(tools), trace, (w, t) -> true, model.clock, null);
    }

    private static long ms(List<JSONObject> timings, int i) { return timings.get(i).optLong("ms"); }

    // ---- the cacheable prompt --------------------------------------------------------------------

    /** The whole point of the job: the system message must be byte-for-byte the contract's, whatever the profile says. */
    static void staticSystem() throws Exception {
        Clock clock = new Clock();
        Rounds with = new Rounds(clock, 100);
        with.script.calls("plan", plan("搜猫", "搜索")).calls("finish", o("status", "done", "reply", "好了"));
        // a request without data words (B站's carry bili_videos since 2026-11-07, a prefix of their own)
        AgentLoop.Request r = new AgentLoop.Request("打开网易云搜一下猫", "test");
        r.profile = "- 用户常看 B 站\n- 习惯音量 20";
        loop(with, new Trace()).run(r);
        JSONArray first = with.script.seen.get(0);
        check("system".equals(first.getJSONObject(0).getString("role")), "the first message is still the system message");
        // the contract's system text for this request: the rule lines of the gated tools it does not need left out
        String gatedSystem = AgentCoreTest.contract.systemFor(AgentCoreTest.contract.gatedIn("打开网易云搜一下猫"));
        check(first.getJSONObject(0).getString("content").equals(gatedSystem),
                "with a profile the system message is exactly the contract's system text for the request");
        check(!first.getJSONObject(0).getString("content").contains("习惯音量"), "no profile text leaks into the system message");
        check(with.script.seen.get(1).getJSONObject(0).getString("content").equals(gatedSystem),
                "the system message is still unchanged in the second round");

        Rounds plain = new Rounds(clock, 100);
        plain.script.text("好的");
        loop(plain, new Trace()).run(new AgentLoop.Request("你好", "test"));
        check(plain.script.seen.get(0).getJSONObject(0).getString("content").equals(gatedSystem),
                "without a profile the system message is the same string (no gated words in either: the same prefix)");
        check(plain.script.seen.get(0).getJSONObject(0).getString("content")
                        .equals(first.getJSONObject(0).getString("content")),
                "the cached prefix is identical with and without a profile");
    }

    static void profileInUserMessage() throws Exception {
        Clock clock = new Clock();
        Rounds m = new Rounds(clock, 100);
        m.script.text("好的");
        AgentLoop.Request r = new AgentLoop.Request("放首歌", "test");
        r.profile = "- 用户喜欢林俊杰";
        r.recent = new JSONArray().put(o("said", "放首歌", "replied", "好的", "minutes_ago", 1));
        Ledger previous = new Ledger("p", "上一件事", "test", clock.t);
        previous.plan("搜猫", Arrays.asList("搜索"), "在播");
        previous.state = Ledger.State.INTERRUPTED;
        r.previous = previous;
        loop(m, new Trace()).run(r);
        JSONArray sent = m.script.seen.get(0);
        check(sent.length() == 3, "system, the profile, then the utterance: " + sent.length());
        check((AgentLoop.PROFILE_HEADER + "- 用户喜欢林俊杰").equals(sent.getJSONObject(1).getString("content"))
                && "user".equals(sent.getJSONObject(1).getString("role")),
                "the profile is a user message of its own, ahead of the utterance, under 「关于用户」");
        JSONObject user = new JSONObject(sent.getJSONObject(2).getString("content"));
        check("放首歌".equals(user.optString("utterance")), "the utterance comes after it");
        check(user.has("environment") && user.has("recent_turns") && user.has("previous_task"),
                "environment, recent_turns and previous_task are still with the utterance");
        // utterance, environment, recent_turns, previous_task (+ 应用经验: 放首歌 brings NetEase's guide)
        check(!user.has("关于用户") && user.length() == 4 + (user.has("应用经验") ? 1 : 0),
                "and the profile is not repeated there: " + user.length());

        Rounds again = new Rounds(clock, 100);
        again.script.text("好的");
        AgentLoop.Request r2 = new AgentLoop.Request("暂停", "test");
        r2.profile = "- 用户喜欢林俊杰";
        loop(again, new Trace()).run(r2);
        JSONArray sent2 = again.script.seen.get(0);
        check(sent2.getJSONObject(0).toString().equals(sent.getJSONObject(0).toString())
                        && sent2.getJSONObject(1).toString().equals(sent.getJSONObject(1).toString()),
                "the same profile makes the same prefix for another sentence (cacheable)");

        Rounds empty = new Rounds(clock, 100);
        empty.script.text("好的");
        loop(empty, new Trace()).run(new AgentLoop.Request("你好", "test"));
        JSONArray bare = empty.script.seen.get(0);
        check(bare.length() == 2 && new JSONObject(bare.getJSONObject(1).getString("content")).has("utterance"),
                "an empty profile adds no message at all");

        Rounds none = new Rounds(clock, 100);
        none.script.text("好的");
        AgentLoop.Request nulled = new AgentLoop.Request("你好", "test");
        nulled.profile = null;
        loop(none, new Trace()).run(nulled);
        check(none.script.seen.get(0).length() == 2, "a null profile adds none either");
    }

    // ---- the timing trace ------------------------------------------------------------------------

    /** A two-round task with two tools: one line per model round and one per tool call, ms straight from the clock. */
    static void timingTrace() throws Exception {
        Clock clock = new Clock();
        Rounds m = new Rounds(clock, 1800, 900);
        m.script.calls("plan", plan("搜猫并播放", "搜索", "播放"), "bili_search", o("keyword", "猫"))
                .calls("screen_click", o("id", 5), "finish", o("status", "done", "reply", "好了"));
        AgentCoreTest.Fake bili = new AgentCoreTest.Fake("bili_search", Tool.Gate.act());
        bili.during = () -> clock.advance(700);
        // a click that started playback: its result is evidence, so the finish beside it closes the task
        AgentCoreTest.Fake read = new AgentCoreTest.Fake("screen_click", Tool.Gate.act());
        read.reply.put("playing", o("title", "猫"));
        read.during = () -> clock.advance(40);
        Trace trace = new Trace();
        Ledger task = loop(m, trace, bili, read).run(new AgentLoop.Request("B站搜猫并播放", "test"));
        check(task.state == Ledger.State.DONE, "the scripted task finished: " + task.state);

        check(trace.timingEvents() == 6, "six timing events: two rounds and four calls: " + trace.timingEvents());
        check(trace.timings.size() == 6, "every timing event carried a JSON object");
        check(trace.shape().equals("model tool tool model tool tool"), "the order is round, its calls, next round: " + trace.shape());

        List<JSONObject> rounds = trace.of("model");
        check(rounds.size() == 2, "one line per model round");
        check(rounds.get(0).getInt("round") == 1 && rounds.get(1).getInt("round") == 2, "rounds are numbered from 1");
        check(ms(rounds, 0) == 1800 && ms(rounds, 1) == 900, "each round's ms is what the model took: " + ms(rounds, 0) + "/" + ms(rounds, 1));
        check(rounds.get(0).getInt("calls") == 2 && rounds.get(1).getInt("calls") == 2, "each round reports how many calls came back");
        check(rounds.get(0).length() == 4, "a model line has kind, round, ms and calls only");

        List<JSONObject> tools = trace.of("tool");
        check(tools.size() == 4, "one line per tool call, plan and finish included");
        check("plan".equals(tools.get(0).optString("name")), "the plan call is timed first");
        check("bili_search".equals(tools.get(1).optString("name")), "then the tool that ran with it");
        check("screen_click".equals(tools.get(2).optString("name")), "then the second round's tool");
        check("finish".equals(tools.get(3).optString("name")), "and the finish call");
        check(ms(tools, 1) == 700, "the tool's ms is what the tool took: " + ms(tools, 1));
        check(ms(tools, 2) == 40, "the second tool too: " + ms(tools, 2));
        check(ms(tools, 0) == 0 && ms(tools, 3) == 0, "plan and finish cost nothing on the fake clock");
        check(tools.get(1).getBoolean("ok") && tools.get(2).getBoolean("ok"), "both tools report ok=true");
        check(tools.get(0).length() == 4, "a tool line has kind, name, ms and ok only");

        check(trace.types.indexOf("timing") > trace.types.indexOf("start"), "timing starts after the task started");
        check(trace.types.lastIndexOf("timing") < trace.types.indexOf("finish"), "and stops before the task finishes");

        JSONObject s = Perf.summary(trace.timings, clock.t - 5_000, 2_600);
        check(s.getInt("rounds") == 2 && s.getLong("model_ms") == 2700, "the trace sums to the model's 2700 ms");
        check(s.getLong("first_round_ms") == 1800 && s.getLong("max_round_ms") == 1800, "first and slowest round are the first one");
        check(s.getLong("tools_ms") == 740, "the tools add up to 740 ms: " + s.getLong("tools_ms"));
        check("bili_search".equals(s.getString("slowest_tool")) && s.getLong("slowest_tool_ms") == 700, "the slowest tool is named");
        check(s.getLong("wall_ms") == 3440, "the wall clock is the whole run: " + s.getLong("wall_ms"));
    }

    /** A tool that throws, and a call whose arguments are not JSON: both still leave a line, with ok=false. */
    static void failingTools() throws Exception {
        Clock clock = new Clock();
        Rounds m = new Rounds(clock, 500);
        m.script.calls("plan", plan("点一下", "点"), "screen_click", o("id", 3))
                .calls("screen_click", "{这不是 JSON")
                .calls("finish", o("status", "failed", "reply", "点不动"));
        AgentCoreTest.Fake click = new AgentCoreTest.Fake("screen_click", Tool.Gate.act());
        click.during = () -> clock.advance(120);
        click.failure = new IllegalStateException("炸了");
        Trace trace = new Trace();
        loop(m, trace, click).run(new AgentLoop.Request("点一下", "test"));
        List<JSONObject> tools = trace.of("tool");
        check(tools.size() == 4, "the throwing call and the rejected call are both timed: " + tools.size());
        JSONObject thrown = tools.get(1);
        check("screen_click".equals(thrown.optString("name")), "the throwing tool is named");
        check(!thrown.optBoolean("ok", true), "a tool that throws is timed with ok=false");
        check(thrown.optLong("ms") == 120, "and with the time it actually took: " + thrown.optLong("ms"));
        JSONObject rejected = tools.get(2);
        check("screen_click".equals(rejected.optString("name")), "the call with broken arguments is named too");
        check(!rejected.optBoolean("ok", true), "a call rejected before it ran is ok=false");
        check(rejected.optLong("ms") == 0, "and costs nothing");
        JSONObject s = Perf.summary(trace.timings, 1_000, -1);
        check(s.getLong("tools_ms") == 120 && "screen_click".equals(s.getString("slowest_tool")), "a failed tool still counts towards tools_ms");
        check(s.getLong("first_speech_ms") == -1, "nothing was said, so first_speech_ms stays -1");
    }

    // ---- Perf ------------------------------------------------------------------------------------

    static void summary() throws Exception {
        List<JSONObject> t = new ArrayList<>(Arrays.asList(
                o("kind", "model", "round", 1, "ms", 1800, "calls", 2),
                o("kind", "tool", "name", "bili_search", "ms", 700, "ok", true),
                o("kind", "tool", "name", "plan", "ms", 5, "ok", true),
                o("kind", "model", "round", 2, "ms", 900, "calls", 1),
                o("kind", "tool", "name", "screen_read", "ms", 40, "ok", false)));
        JSONObject s = Perf.summary(t, 4_200, 2_600);
        check(s.getInt("rounds") == 2, "rounds counts the model lines");
        check(s.getLong("model_ms") == 2700, "model_ms is their sum");
        check(s.getLong("first_round_ms") == 1800, "first_round_ms is the first model line");
        check(s.getLong("max_round_ms") == 1800, "max_round_ms is the slowest model line");
        check(s.getLong("tools_ms") == 745, "tools_ms is the sum of the tool lines");
        check("bili_search".equals(s.getString("slowest_tool")), "slowest_tool is the slowest tool's name");
        check(s.getLong("slowest_tool_ms") == 700, "slowest_tool_ms is its time");
        check(s.getLong("wall_ms") == 4_200, "wall_ms is passed through");
        check(s.getLong("first_speech_ms") == 2_600, "first_speech_ms is passed through");
        check(s.length() == 11, "summary has exactly the eleven fields: " + s.length());
        // since 2026-11-18: the two phase lines around the loop (AgentEngine), -1 when a task has none to show
        check(s.getLong("setup_ms") == -1 && s.getLong("tail_ms") == -1, "no phase lines: setup_ms and tail_ms are -1");
        List<JSONObject> withPhases = new ArrayList<>(t);
        withPhases.add(0, o("kind", "phase", "name", "setup", "ms", 3_100));
        withPhases.add(o("kind", "phase", "name", "tail", "ms", 120));
        JSONObject p = Perf.summary(withPhases, 7_500, 2_600);
        check(p.getLong("setup_ms") == 3_100 && p.getLong("tail_ms") == 120,
                "the phase lines are read by name: " + p.optLong("setup_ms") + " / " + p.optLong("tail_ms"));
        check(p.getInt("rounds") == 2 && p.getLong("tools_ms") == 745, "and they are no model or tool line");

        check(Perf.summary(t, 4_200, -1).getLong("first_speech_ms") == -1, "first_speech_ms is -1 when nothing was said");

        List<JSONObject> late = new ArrayList<>(Arrays.asList(
                o("kind", "model", "round", 1, "ms", 100, "calls", 1),
                o("kind", "model", "round", 2, "ms", 900, "calls", 1)));
        JSONObject l = Perf.summary(late, 0, -1);
        check(l.getLong("first_round_ms") == 100 && l.getLong("max_round_ms") == 900, "the slowest round need not be the first");

        JSONObject empty = Perf.summary(new ArrayList<JSONObject>(), 900, -1);
        check(empty.getInt("rounds") == 0 && empty.getLong("model_ms") == 0 && empty.getLong("first_round_ms") == 0
                        && empty.getLong("max_round_ms") == 0 && empty.getLong("tools_ms") == 0
                        && empty.getString("slowest_tool").isEmpty() && empty.getLong("slowest_tool_ms") == 0,
                "a trace with nothing in it sums to zero and names no tool");
        check(empty.getLong("wall_ms") == 900, "the wall clock is still reported");
        check(Perf.summary(null, 10, -1).getInt("rounds") == 0, "a missing trace is not a crash");
        check(Perf.summary(Arrays.asList((JSONObject) null, o("kind", "model", "round", 1, "ms", 50, "calls", 0)), 0, -1)
                .getInt("rounds") == 1, "a null entry is skipped");
        JSONObject junk = Perf.summary(Arrays.asList(o("kind", "something_else", "ms", 999)), 0, -1);
        check(junk.getInt("rounds") == 0 && junk.getLong("tools_ms") == 0, "an unknown kind counts for nothing");
        JSONObject slowZero = Perf.summary(Arrays.asList(
                o("kind", "tool", "name", "a", "ms", 0, "ok", true),
                o("kind", "tool", "name", "b", "ms", 0, "ok", true)), 0, -1);
        check("a".equals(slowZero.getString("slowest_tool")), "with every tool at 0 ms the first one is still named");
    }

    static void rollup() throws Exception {
        JSONObject none = Perf.rollup(new ArrayList<JSONObject>());
        check(none.getInt("tasks") == 0 && none.length() == 1, "no tasks, just the count: " + none);
        check(Perf.rollup(null).getInt("tasks") == 0, "a missing list is not a crash");
        check(Perf.rollup(Arrays.asList((JSONObject) null)).getInt("tasks") == 0, "a null summary is not a task");

        List<JSONObject> three = Arrays.asList(
                o("wall_ms", 1000, "first_round_ms", 200, "rounds", 2, "model_ms", 600, "first_speech_ms", 900),
                o("wall_ms", 3000, "first_round_ms", 400, "rounds", 2, "model_ms", 1000, "first_speech_ms", 2500),
                o("wall_ms", 2000, "first_round_ms", 300, "rounds", 1, "model_ms", 800, "first_speech_ms", 1800));
        JSONObject odd = Perf.rollup(three);
        check(odd.getInt("tasks") == 3, "three tasks counted");
        check(odd.getLong("median_wall_ms") == 2000, "the middle wall clock of three");
        check(odd.getLong("median_first_round_ms") == 300, "the middle first round of three");
        check(odd.getLong("median_round_ms") == 500, "the middle of the per-task average rounds (300/500/800)");
        check(odd.getLong("median_first_speech_ms") == 1800, "the middle first speech of three");
        check(odd.length() == 5, "rollup has tasks and four medians: " + odd.length());

        List<JSONObject> four = Arrays.asList(
                o("wall_ms", 1000, "first_round_ms", 100, "rounds", 1, "model_ms", 200, "first_speech_ms", 800),
                o("wall_ms", 2000, "first_round_ms", 200, "rounds", 1, "model_ms", 400, "first_speech_ms", -1),
                o("wall_ms", 3000, "first_round_ms", 300, "rounds", 1, "model_ms", 600, "first_speech_ms", 1000),
                o("wall_ms", 5000, "first_round_ms", 500, "rounds", 2, "model_ms", 1400, "first_speech_ms", 4000));
        JSONObject even = Perf.rollup(four);
        check(even.getInt("tasks") == 4, "four tasks counted, the silent one included");
        check(even.getLong("median_wall_ms") == 2500, "an even count averages the middle two wall clocks");
        check(even.getLong("median_first_round_ms") == 250, "an even count averages the middle two first rounds");

        // the quick path never asks the model: its zero first round must not pull the median down
        List<JSONObject> quick = Arrays.asList(
                o("wall_ms", 300, "first_round_ms", 0, "rounds", 0, "model_ms", 0, "first_speech_ms", 250),
                o("wall_ms", 2000, "first_round_ms", 900, "rounds", 1, "model_ms", 900, "first_speech_ms", 1900));
        check(Perf.rollup(quick).getLong("median_first_round_ms") == 900, "a quick task leaves the round medians alone: " + Perf.rollup(quick));
        check(even.getLong("median_round_ms") == 500, "the middle two average rounds (400 and 600)");
        check(even.getLong("median_first_speech_ms") == 1000, "the task that never spoke is left out of the speech median");

        JSONObject quiet = Perf.rollup(Arrays.asList(
                o("wall_ms", 10, "first_round_ms", 1, "rounds", 1, "model_ms", 1, "first_speech_ms", -1)));
        check(quiet.getLong("median_first_speech_ms") == 0, "nothing spoke at all: the speech median is 0, not -1");

        List<JSONObject> mixed = Arrays.asList(
                o("wall_ms", 100, "first_round_ms", 0, "rounds", 0, "model_ms", 0, "first_speech_ms", 50),
                o("wall_ms", 200, "first_round_ms", 60, "rounds", 2, "model_ms", 400, "first_speech_ms", 60));
        JSONObject m = Perf.rollup(mixed);
        check(m.getInt("tasks") == 2, "a task with no model round is still a task");
        check(m.getLong("median_round_ms") == 200, "but it is left out of the round median");
        check(m.getLong("median_wall_ms") == 150, "and kept in the wall clock median");

        check(Perf.median(Arrays.asList(3L, 1L, 2L)) == 2, "median of three is the middle one");
        check(Perf.median(Arrays.asList(4L, 1L, 3L, 2L)) == 2, "median of four is the mean of the middle two");
        check(Perf.median(Arrays.asList(7L)) == 7, "median of one is that one");
        check(Perf.median(new ArrayList<Long>()) == 0, "median of nothing is 0");
    }

    /** The ring of the last real tasks' transcripts (TranscriptRing): the names, what is dropped when it is full, and
     * the entry that is saved — a slow task's 11 rounds must still be readable hours later (2026-11-09). */
    static void ring() throws Exception {
        check("1794228070607-mw992fnz.json".equals(TranscriptRing.fileName(1794228070607L, "mw99-2fnz")),
                "the file name is <startedAt>-<id>.json with the id's dash dropped: " + TranscriptRing.fileName(1794228070607L, "mw99-2fnz"));
        check(TranscriptRing.fileName(1794228070607L, "！？。_-/").equals("1794228070607-task.json"),
                "an id of only symbols becomes task: " + TranscriptRing.fileName(1794228070607L, "！？。_-/"));
        check(TranscriptRing.fileName(5L, null).equals("5-task.json"), "a missing id becomes task too");
        check(TranscriptRing.fileName(5L, "a b.c").equals("5-abc.json"), "spaces and dots are dropped from the id");

        check(TranscriptRing.startedAt("1794228070607-mw992fnz.json") == 1794228070607L, "startedAt reads the number before the dash");
        check(TranscriptRing.startedAt("12-a-b.json") == 12, "only the first dash matters");
        check(TranscriptRing.startedAt("abc.json") == -1, "a name without a dash is not ours");
        check(TranscriptRing.startedAt("12-x.txt") == -1, "a name that is not .json is not ours");
        check(TranscriptRing.startedAt("-x.json") == -1, "a name starting with a dash is not ours");
        check(TranscriptRing.startedAt("12-.json") == -1, "nothing between the dash and .json is not ours");
        check(TranscriptRing.startedAt("1x2-a.json") == -1, "a non-digit before the dash is not ours");
        check(TranscriptRing.startedAt(null) == -1, "a null name is not ours");

        List<String> mixed = Arrays.asList("9-a.json", "10-b.json", "notes.txt", "11-c.json", "-d.json");
        List<String> newest = TranscriptRing.newestFirst(mixed);
        check(newest.size() == 3, "only the names that parse are kept: " + newest);
        check(newest.get(0).equals("11-c.json") && newest.get(2).equals("9-a.json"),
                "ordered by the number, not by the text (9 is older than 10): " + newest);
        check(TranscriptRing.newestFirst(new ArrayList<String>()).isEmpty(), "nothing in, nothing out");
        check(TranscriptRing.newestFirst(null).isEmpty(), "a null list is not a crash");

        Map<String, Long> many = new LinkedHashMap<>();
        for (int i = 1; i <= 15; i++) many.put(i + "-t.json", 1000L);
        List<String> dropped = TranscriptRing.toDelete(many, TranscriptRing.MAX_COUNT, TranscriptRing.MAX_BYTES);
        check(dropped.size() == 3, "15 files, 12 kept: the 3 oldest go: " + dropped);
        check(dropped.contains("1-t.json") && dropped.contains("3-t.json") && !dropped.contains("4-t.json"),
                "and they really are the oldest three: " + dropped);
        check(dropped.get(0).equals("1-t.json"), "oldest first: " + dropped);

        Map<String, Long> big = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) big.put(i + "-t.json", 1_000_000L);
        List<String> overBytes = TranscriptRing.toDelete(big, TranscriptRing.MAX_COUNT, 3_000_000L);
        check(overBytes.size() == 2 && overBytes.contains("1-t.json") && overBytes.contains("2-t.json"),
                "5 MB over a 3 MB budget: the two oldest go: " + overBytes);

        Map<String, Long> huge = new LinkedHashMap<>();
        huge.put("7-t.json", 5_000_000L);
        check(TranscriptRing.toDelete(huge, TranscriptRing.MAX_COUNT, TranscriptRing.MAX_BYTES).isEmpty(),
                "one file bigger than the whole budget stays: it is the newest");

        Map<String, Long> junk = new LinkedHashMap<>();
        junk.put("1-t.json", 10L);
        junk.put("last_transcript.json", 10L);
        junk.put("2-t.json.tmp", 10L);
        List<String> cleaned = TranscriptRing.toDelete(junk, TranscriptRing.MAX_COUNT, TranscriptRing.MAX_BYTES);
        check(cleaned.size() == 2 && cleaned.contains("last_transcript.json") && cleaned.contains("2-t.json.tmp"),
                "a stray file in the ring directory is always deleted: " + cleaned);
        check(TranscriptRing.toDelete(new LinkedHashMap<String, Long>(), TranscriptRing.MAX_COUNT, TranscriptRing.MAX_BYTES).isEmpty(),
                "an empty directory drops nothing");
        check(TranscriptRing.toDelete(null, TranscriptRing.MAX_COUNT, TranscriptRing.MAX_BYTES).isEmpty(),
                "a null directory drops nothing");
        check(TranscriptRing.MAX_COUNT == 12 && TranscriptRing.MAX_BYTES == 3_000_000L,
                "the TV keeps the last 12 tasks within 3 MB");

        Ledger t = new Ledger("mw99", "切换到伴奏模式", "xiaoai", 1794228070607L);
        t.state = Ledger.State.DONE;
        t.reply = "已经切到伴奏了。";
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", "切换到伴奏模式"));
        JSONObject e = TranscriptRing.entry(t, messages, 105_000);
        check(e.getString("id").equals("mw99") && e.getLong("at") == 1794228070607L, "the entry carries the task's id and start");
        check(e.getString("utterance").equals("切换到伴奏模式") && e.getString("source").equals("xiaoai"),
                "and what was said and where it came from");
        check(e.getString("state").equals("done"), "the state is lower case: " + e.optString("state"));
        check(e.getString("reply").equals("已经切到伴奏了。") && e.getLong("wall_ms") == 105_000L, "the reply and the wall clock");
        check(e.getJSONArray("messages").length() == 1, "the whole model conversation is in the entry");
        check(!e.has("error"), "a task that did not fail has no error key: " + e);
        check(e.length() == 8, "the entry is exactly the eight fields: " + e.length());

        t.state = Ledger.State.FAILED;
        t.error = "adb 超时";
        JSONObject failed = TranscriptRing.entry(t, messages, 105_000);
        check(failed.getString("error").equals("adb 超时"), "a failed task's error is kept");
        check(failed.getString("state").equals("failed"), "and its state says failed");
    }
}
