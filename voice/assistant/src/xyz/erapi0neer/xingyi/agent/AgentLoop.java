package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.*;

/** One agent task: understand (plan) → act → observe → verify → report. The loop owns the rules the model cannot be
 * trusted to follow on its own: nothing but reads before a plan exists, sensitive steps wait for the user, search words
 * stay as said, failures and budgets are bounded. run() blocks; cancel() may be called from any thread. */
public final class AgentLoop {
    public interface Model {
        JSONObject next(JSONArray messages, JSONArray tools) throws Exception;
        /** The same from the strong model, for a task that is struggling; a host without one answers as usual. */
        default JSONObject next(JSONArray messages, JSONArray tools, boolean strong) throws Exception { return next(messages, tools); }
        /** The same, streamed: the answer's text goes to sink as it arrives (Sse). A host that cannot stream ignores it. */
        default JSONObject next(JSONArray messages, JSONArray tools, boolean strong, Sse.Sink sink) throws Exception {
            return next(messages, tools, strong);
        }
        void cancel();
    }
    /** Where a plain answer goes while the model is still writing it, so the host can start reading its first page
     * (2026-11-06 overnight pass), and what of it the host has already read out. */
    public interface Drafts {
        /** The answer's visible text so far, whole; called on the loop thread as it grows. */
        void text(Ledger task, String soFar);
        /** The beginning of task's answer the host has already read out (whole pages, cleaned), or "". */
        String read(Ledger task);
    }
    /** Event types: start plan step tool tool_done progress say confirm consent timing escalate finish. Called on the loop
     * thread. */
    public interface Listener { void onEvent(String type, Ledger task, String text); }
    /** Asks the user to approve one sensitive step. Blocks; true only on explicit approval. */
    public interface Consent { boolean ask(String what, Ledger task) throws InterruptedException; }
    /** What is on the TV right now, in one speakable line; the host answers, the core cannot see the screen. */
    public interface Observer { String now() throws Exception; }
    public interface Clock { long now(); }

    public static final class Request {
        public final String utterance, source;
        public JSONObject environment = new JSONObject();
        public Ledger previous; // the last task with a plan; continued when it is resumable
        public JSONArray recent = new JSONArray(); // recent exchanges: [{said, replied, minutes_ago}]
        public String profile = ""; // facts about the user, carried in the first user message under 「关于用户」
        public Map<String, String> aliases = Collections.emptyMap();
        /** 应用经验 the host adds to the contract's: what 星仪 learned about an app by trying (AppNotes). */
        public List<Skills.Skill> skills = Collections.emptyList();
        /** The utterance answers the question 星仪 asked last: it may not be met with a second question. */
        public boolean answering;
        public Request(String utterance, String source) { this.utterance = utterance; this.source = source; }
    }

    public static final class Limits {
        public int rounds = 20, calls = 36, failures = 3;
        /** When the rest of a task goes to the strong model: not done by this round, or failed this often
         * (tests/agent/decide_compare.py, 2026-11-05: Flash alone hit the 10-round cap on 「下一首」 after XiaoAi had
         * switched and on 「播放他最新那个视频」; gpt-5.4 alone was right but took 2.3x the model time on easy tasks;
         * Flash first and gpt-5.4 from round 5 or after two failures kept the easy ones fast and finished the hard ones). */
        public int escalateRound = 5, escalateFailures = 2;
        public long millis = 150_000;
    }

    static final String NEED_PLAN = "还没有调用 plan，操作被拒绝。一步就能做完的请求直接用连接器（netease_play、bili_search、"
            + "set_volume、open_app 等）；要点选、按键、输入或打开链接的任务，先用 plan 写出你对用户意图的理解和步骤再操作；"
            + "如果用户只是在提问、抱怨或聊天，直接用文字回答，不要操作。";
    /** Tools that bring an app to the front; a question that names no app does not get one (Dispatch.asksOnly). */
    private static final Set<String> OPENS = new HashSet<>(Arrays.asList("bili_search", "tencent_video_search", "tencent_video_open", "open_app", "open_link",
            "netease_play", "netease_playlist", "open_settings", "install_app"));
    static final String JUST_ASKING = "用户只是在提问，没说要在哪个应用里看：不要打开应用，用 web_search 查（结论就是回答时填 "
            + "final=true），或者直接回答。用户想在应用里看，会自己说“打开…”“放…”。";
    private static final Pattern QUOTED = Pattern.compile("[“「《\"]([^”」》\"]{1,60})[”」》\"]");
    private static final Set<String> META = new HashSet<>(Arrays.asList("plan", "plan_update", "ask_user", "finish", "find_tools"));
    /** The model looking for a tool the gates left out (Claude's tool search, 2026-11-07); the loop answers it. */
    static final String FIND = "find_tools";
    /** The first line of the profile's own user message; the contract calls the block 「关于用户」. */
    static final String PROFILE_HEADER = "「关于用户」\n";

    private final Contract contract;
    private final Model model;
    private final Map<String, Tool> tools = new HashMap<>();
    private final Listener listener;
    private final Consent consent;
    private final Clock clock;
    private final Limits limits;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile JSONArray transcript = new JSONArray(); // the last run's conversation, for debugging
    private String lastSignature = ""; // name+arguments of the previous tool call, to flag an immediate repeat
    private final ArrayDeque<String> recent = new ArrayDeque<>(); // the three calls before this one, for A-B-A-B loops
    private volatile Observer observer; // set by the host; asked at most once per run, only when the loop stops early
    private volatile Drafts drafts;     // set by the host: plain answers are streamed to it
    private boolean observed;
    private volatile int round; // the round being executed, for tools that budget themselves per round
    private boolean asking;     // the round asks the user: no one-step connector runs on a guess without a plan
    private boolean answering;  // the request answers 星仪's question: ask_user is refused (Request.answering)
    private String profile = "";  // 关于用户, for names the user means by 「我的…」
    private String context = "";  // the turns before, the list just read out and this task's findings, where a name may come from
    /** The user speaking of their own (「我的频道」「我关注的」), when 关于用户's names stand for what they said. */
    private static final Pattern OWN = Pattern.compile("我的|我关注|我自己|我常看|我喜欢|我最喜欢|我订阅|我收藏");
    private boolean shortened;  // a too-long spoken answer was already sent back once to be cut down
    private String kept = "";   // the part of a long answer read out before it was sent back, kept at its head

    /** An answer read out by XiaoAi longer than this is sent back once to be cut to SHORTER (2026-11-06: 「详细讲讲为什么P和
     * NP问题这么难」 came back at 13 pages, six minutes of voice, though the contract says 600 characters at most). */
    static final int LONG_ANSWER = 700;
    /** A tool call longer than this is waiting on the TV, not thinking: its extra seconds are given back to the task
     * (2026-11-08: install_app of TV Bro, 150 MB from GitHub, took 312 s; the 150 s budget ran out right after it and
     * 「打开浏览器看看」 ended as 「太复杂」 one open_app away from done). */
    static final long LONG_TOOL = 20_000;
    static final String SHORTER = "这段回答会被小爱整段读出来，太长了。压缩到 600 字以内再回答一次：先说结论，保留最重要的几点，"
            + "去掉铺垫、重复和没把握的细节，不要用 markdown 和列表。";

    /** 2026-11-05 on the TV: 「我说的是想听OPPO OPEN AI这个公司的」 got a second question (play a video, or tell you?),
     * and every answer costs the user another 「小爱同学」. One question per exchange; after the answer, act. */
    static final String NO_SECOND_ASK = "用户刚回答了你上一个问题，不要再问：按他的回答和最可能的理解直接去做或回答，"
            + "另一种理解用半句话带上（例如“要是你说的是…，再告诉我”）。";

    /** After a long answer's first page was read out, it is not said again: the rest is rewritten to follow it. */
    static String shorterAfter(String read) {
        return "这段回答会被小爱整段读出来，太长了。开头这段已经读给用户听了：「" + read + "」。不要重复它，直接从它后面接着讲，"
                + "剩下的部分压缩到 450 字以内：保留最重要的几点，去掉铺垫、重复和没把握的细节，不要用 markdown 和列表。";
    }

    public JSONArray transcript() { return transcript; }
    /** The host's reader for plain answers as they stream; null: answers arrive whole. */
    public void drafts(Drafts d) { drafts = d; }
    /** The host tells the core what is on the TV, for the reply when a task stops early. null clears it. */
    public void observe(Observer o) { observer = o; }
    public AgentLoop(Contract contract, Model model, Collection<Tool> tools, Listener listener, Consent consent,
                     Clock clock, Limits limits) {
        this.contract = contract; this.model = model; this.listener = listener; this.consent = consent;
        this.clock = clock; this.limits = limits == null ? new Limits() : limits;
        for (Tool t : tools) this.tools.put(t.name(), t);
    }

    public void cancel() { if (cancelled.compareAndSet(false, true)) model.cancel(); }
    private static final class Stopped extends RuntimeException { Stopped() { super("已停止"); } }
    public Ledger run(Request req) {
        String said = Grounding.canonical(req.utterance == null ? "" : req.utterance.trim(), req.aliases);
        Ledger task = new Ledger(Long.toString(clock.now(), 36), said, req.source, clock.now());
        Grounding grounding = new Grounding(req.aliases);
        grounding.heard(said);
        Ledger previous = req.previous;
        if (previous != null) {
            grounding.heard(previous.utterance);
            grounding.heard(previous.goal);
            if (previous.state.resumable()) task.inherit(previous);
        }
        emit("start", task, said);
        observed = false;
        round = 0;
        answering = req.answering;
        profile = req.profile == null ? "" : req.profile;
        StringBuilder before = new StringBuilder();
        for (int i = 0; req.recent != null && i < req.recent.length(); i++) {
            JSONObject t = req.recent.optJSONObject(i);
            if (t != null) before.append(t.optString("said")).append('\n').append(t.optString("replied")).append('\n');
        }
        JSONObject list = req.environment == null ? null : req.environment.optJSONObject("last_list");
        if (list != null) before.append(list.toString());
        context = before.toString();
        shortened = false;
        kept = "";
        Tool.Turn turn = new Tool.Turn() {
            @Override public String utterance() { return task.utterance; }
            @Override public String source() { return task.source; }
            @Override public boolean cancelled() { return cancelled.get(); }
            @Override public void progress(String text) { emit("progress", task, text); }
            @Override public void say(String text) { if (text != null && !text.trim().isEmpty()) emit("say", task, text); }
            @Override public int round() { return round; }
        };
        try {
            if (said.isEmpty() || said.length() > 2000) return end(task, Ledger.State.ANSWERED, "我没听清，请再说一遍。");
            JSONArray messages = new JSONArray();
            transcript = messages;
            // the data tools this request may need (Contract gates): the rest, and the rule lines about them, stay out of
            // every round but the strong model's. A request with none of their words — most of what reaches the model —
            // always sends the same prefix, so the gateway's prompt cache keeps working.
            final Set<String> gatedIn = contract.gatedIn(gateContext(said, req, previous));
            messages.put(message("system", contract.systemFor(gatedIn)));
            // what each round sends: the gated tools of the request, and those find_tools adds on the way
            final Set<String> sent = new HashSet<>(gatedIn);
            JSONObject input = new JSONObject().put("utterance", said).put("environment", req.environment);
            if (previous != null) {
                JSONObject p = previous.forModel();
                p.put("minutes_ago", Math.max(0, (clock.now() - previous.updatedAt) / 60000));
                input.put("previous_task", p);
            }
            if (req.recent != null && req.recent.length() > 0) input.put("recent_turns", req.recent);
            // 应用经验 for the apps this request is about; the ones the task reaches later ride in a tool result
            Set<String> guided = new HashSet<>();
            JSONArray guides = new JSONArray();
            List<Skills.Skill> skills = new ArrayList<>(contract.skills);
            if (req.skills != null) skills.addAll(req.skills);
            for (Skills.Skill s : Skills.select(skills, said,
                    req.environment == null ? "" : req.environment.optString("foreground"), followUpContext(said, req, previous))) {
                guides.put(s.text);
                guided.add(s.name);
            }
            if (guides.length() > 0) input.put("应用经验", guides);
            // The profile rides in a user message of its own ahead of the utterance, never in the system prompt: its
            // habit counts change after nearly every task, and a system prefix that changes breaks the gateway's
            // prompt cache every time (measured 2026-11-05: 1.6–2.6 s per round cached, ~3.4 s uncached). Ahead of the
            // utterance it joins the cached prefix whenever it has not changed since the last request; after it, inside
            // the utterance's message, it never could.
            if (req.profile != null && !req.profile.isEmpty()) messages.put(message("user", PROFILE_HEADER + req.profile));
            messages.put(message("user", input.toString()));
            long deadline = clock.now() + limits.millis;
            int rounds = 0;
            int[] counters = new int[3]; // [tool calls, failures, adb-unavailable hits]
            boolean strong = false;
            boolean warned = false;
            Set<String> ids = new HashSet<>();
            while (true) {
                check();
                round = rounds + 1;
                // > and not >=: a tool that ran past the budget gives back what it overran (givenBack), so a round
                // beginning exactly at the moved deadline is the one that overrun was paid for (LoopFixChecks:159-163).
                if (++rounds > limits.rounds || clock.now() > deadline || messages.length() > 72) {
                    return end(task, Ledger.State.INTERRUPTED, stoppedReply(task));
                }
                if (!strong && (round >= limits.escalateRound || counters[1] >= limits.escalateFailures)) {
                    strong = true;
                    emit("escalate", task, round >= limits.escalateRound ? "第 " + round + " 轮还没做完" : "已经失败 " + counters[1] + " 次");
                }
                long roundBegan = clock.now();
                // A task without a plan may be answering in words: its text streams to the host, which reads the first
                // page while the rest is still being written. A planned task ends with a short report; it comes whole.
                final Drafts reader = drafts;
                Sse.Sink sink = reader == null || task.planned() || strong ? null : new Sse.Sink() {
                    @Override public void text(String soFar) { reader.text(task, Speech.visible(soFar)); }
                    @Override public void calls() { }
                };
                JSONObject reply = strong ? model.next(messages, contract.tools(), true)
                        : sink == null ? model.next(messages, contract.toolsFor(sent)) : model.next(messages, contract.toolsFor(sent), false, sink);
                check();
                if (reply == null || !"assistant".equals(reply.optString("role"))) throw new IOException("模型返回的格式不对");
                messages.put(reply);
                JSONArray calls = reply.optJSONArray("tool_calls");
                modelTiming(task, round, clock.now() - roundBegan, calls == null ? 0 : calls.length(), strong);
                if (calls == null || calls.length() == 0) {
                    String text = Speech.visible(reply.opt("content"));
                    // the rewrite of a long answer follows the page the host already read out
                    if (!kept.isEmpty() && !text.isEmpty()) text = kept + "\n" + text;
                    if (text.isEmpty()) return end(task, task.planned() ? Ledger.State.INTERRUPTED : Ledger.State.ANSWERED, "我没想好怎么回答，请再说一遍。");
                    if (!shortened && Speech.clean(text).length() > LONG_ANSWER) {
                        shortened = true;
                        String read = reader == null ? "" : reader.read(task);
                        read = read == null ? "" : read;
                        // only a page that really is the start of this answer counts as said
                        if (!read.isEmpty() && Speech.clean(text).startsWith(read)) kept = read;
                        messages.put(message("user", kept.isEmpty() ? SHORTER : shorterAfter(kept)));
                        continue;
                    }
                    Ledger.State state = !task.planned() ? Ledger.State.ANSWERED
                            : Speech.soundsFailed(text) || hasFailedStep(task) || lastActionFailed(task) ? Ledger.State.INTERRUPTED : Ledger.State.DONE;
                    if (state == Ledger.State.DONE) closeSteps(task);
                    return end(task, state, text);
                }

                // Execution order: plan first, then the rest as given, ask_user/finish last.
                // Tool messages still go back in the model's original order.
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < calls.length(); i++) if ("plan".equals(name(calls, i))) order.add(i);
                for (int i = 0; i < calls.length(); i++) { String n = name(calls, i); if (!"plan".equals(n) && !"ask_user".equals(n) && !"finish".equals(n)) order.add(i); }
                for (int i = 0; i < calls.length(); i++) { String n = name(calls, i); if ("ask_user".equals(n) || "finish".equals(n)) order.add(i); }

                JSONObject[] results = new JSONObject[calls.length()];
                String[] callIds = new String[calls.length()];
                Ending ending = null;
                asking = false;
                for (int i = 0; i < calls.length(); i++) if ("ask_user".equals(name(calls, i)) && !answering) asking = true;
                for (int i : order) {
                    JSONObject call = calls.optJSONObject(i);
                    String id = call == null ? "" : call.optString("id", "");
                    if (id.isEmpty() || id.length() > 256 || !ids.add(id)) throw new IOException("模型的工具调用编号无效");
                    callIds[i] = id;
                    if (ending != null) { results[i] = result(false, "skipped", "本轮已结束，未执行"); continue; }
                    JSONObject fn = call.optJSONObject("function");
                    String name = fn == null ? "" : fn.optString("name", "");
                    long began = clock.now();
                    JSONObject raw;
                    try { raw = arguments(fn == null ? null : fn.opt("arguments")); }
                    catch (IllegalArgumentException e) {
                        results[i] = result(false, "rejected", e.getMessage());
                        counters[1]++;
                        long took = clock.now() - began;
                        deadline += givenBack(took);
                        toolTiming(task, name, took, false);
                        continue;
                    }
                    if (FIND.equals(name)) {
                        results[i] = findTools(raw, sent);
                        long took = clock.now() - began;
                        deadline += givenBack(took);
                        toolTiming(task, name, took, true);
                        continue;
                    }
                    if (META.contains(name)) {
                        Object outcome = meta(name, raw, task, grounding);
                        if (outcome instanceof Ending) { ending = (Ending) outcome; results[i] = result(true, "recorded", "已记录"); }
                        else results[i] = (JSONObject) outcome;
                    } else {
                        results[i] = invoke(name, raw, task, grounding, turn, counters);
                        StringBuilder brought = new StringBuilder();
                        for (Skills.Skill k : Skills.reached(skills, name, raw))
                            if (guided.add(k.name)) brought.append(brought.length() == 0 ? "" : "\n").append(k.text);
                        if (brought.length() > 0) results[i].put("应用经验", brought.toString());
                    }
                    long took = clock.now() - began;
                    deadline += givenBack(took);
                    toolTiming(task, name, took, results[i].optBoolean("ok", false));
                    takeCard(task, results[i]);
                }
                // A finish(done) next to actions stands only when every action went through and its result proves the
                // request done by itself (a connector, the volume, a click that started playback): that saves the
                // closing round, 1-2 s on the TV. Whatever the model still has to look at (a read, a search, an
                // unconfirmed click, a failure) goes back to it first, and the finish is set aside. A partial or failed
                // finish claims nothing and stands as before.
                if (ending != null && ending.state != Ledger.State.AWAITING_USER && claimsDone(calls) && !closesOnEvidence(calls, results)) {
                    ending = null;
                    for (int i = 0; i < calls.length(); i++) if ("finish".equals(name(calls, i))) results[i] = result(false, "deferred",
                            "这次 finish 没有生效：同一轮的操作没有成功，或者它的结果还要你看。看完上面的结果再决定怎么收尾");
                }
                for (int i = 0; i < calls.length(); i++) {
                    messages.put(new JSONObject().put("role", "tool").put("tool_call_id", callIds[i]).put("content", results[i].toString()));
                }
                // 「记住…」「放首周杰伦的歌」「半小时后关机」: without a plan, a round of memory tools and one-step connectors
                // that proved themselves is the answer; a second model round would only rephrase it, and costs the user
                // 1-3 s (2026-11-05 on the TV: 记住我最喜欢的歌手是林俊杰 took 6.2 s in two rounds).
                if (ending == null && !task.planned()) {
                    // After round 1 a round of memory tools alone is not the answer: what the task found earlier (a web
                    // search) has not been said yet (2026-11-05 calibration: 「我说的是姚润浩…」 searched, then noted the
                    // name in round 2, and the user would have heard only 「记住了：…」).
                    String answer = asksWhy(said) || (round > 1 && onlyMemory(calls)) ? null : directAnswer(calls, results);
                    if (answer == null && !searchThenAct(said, calls, results)) answer = searchAnswer(said, calls, results);
                    if (answer != null) return end(task, Ledger.State.ANSWERED, answer);
                }
                // A B站 page that is still loading is the answer for every round directAnswer does not close — a planned
                // task, or one with another call beside the link (2026-11-10 23:10: the model went on from that page to
                // media_now_playing, a click on 展开更多 and a play/pause that paused the video B站 had started itself).
                if (ending == null) {
                    String loading = loadingAnswer(calls, results);
                    if (loading != null) {
                        if (task.planned()) { closeSteps(task); return end(task, Ledger.State.DONE, loading); }
                        return end(task, Ledger.State.ANSWERED, loading);
                    }
                }
                // The first hit tells the model (harness_note); a second one means it cannot work around it.
                if (ending == null && counters[2] >= 2) {
                    return end(task, task.planned() ? Ledger.State.INTERRUPTED : Ledger.State.FAILED, AdbUnavailable.reply(task.planned()));
                }
                if (ending != null) {
                    if (ending.state == Ledger.State.DONE) closeSteps(task);
                    return end(task, ending.state, ending.reply);
                }
                if (counters[1] >= limits.failures && !warned) {
                    warned = true;
                    JSONObject last = messages.getJSONObject(messages.length() - 1);
                    JSONObject content = new JSONObject(last.getString("content"))
                            .put("harness_note", "本任务已累计失败 " + counters[1] + " 次。不要再重复同样的操作：换一种完全不同的方法，或者调用 finish 如实说明卡在哪里。");
                    last.put("content", content.toString());
                }
                trimScreens(messages);
            }
        } catch (Stopped stopped) {
            return end(task, Ledger.State.CANCELLED, "已停止。");
        } catch (Exception e) {
            if (cancelled.get()) return end(task, Ledger.State.CANCELLED, "已停止。");
            String why = e.getMessage() == null ? "出错了" : e.getMessage();
            task.error = why;
            return end(task, task.planned() ? Ledger.State.INTERRUPTED : Ledger.State.FAILED,
                    (task.planned() ? "做到一半停下了：" : "这次没做成：") + why + (task.planned() ? "。说“星仪继续”可以接着做。" : ""));
        }
    }

    private static final class Ending {
        final Ledger.State state; final String reply;
        Ending(Ledger.State state, String reply) { this.state = state; this.reply = reply; }
    }

    /** plan / plan_update return a tool result; ask_user / finish return an Ending. */
    private Object meta(String name, JSONObject raw, Ledger task, Grounding grounding) throws JSONException {
        JSONObject args;
        try { args = contract.check(name, raw); }
        catch (IllegalArgumentException e) { return result(false, "rejected", e.getMessage()); }
        switch (name) {
            case "plan": {
                JSONArray list = args.getJSONArray("steps");
                List<String> steps = new ArrayList<>();
                for (int i = 0; i < list.length(); i++) { String s = list.getString(i).trim(); if (!s.isEmpty()) steps.add(fixQuoted(s, grounding)); }
                if (steps.isEmpty()) return result(false, "rejected", "steps 至少要有一步");
                task.plan(fixQuoted(args.getString("goal"), grounding), steps, fixQuoted(args.getString("done_when"), grounding));
                emit("plan", task, task.goal);
                return result(true, "recorded", "计划已显示在电视上，共 " + task.steps.size() + " 步");
            }
            case "plan_update": {
                if (!task.planned()) return result(false, "rejected", "还没有计划");
                try { task.mark(args.getInt("step"), args.getString("status"), args.optString("note", "")); }
                catch (IllegalArgumentException e) { return result(false, "rejected", e.getMessage()); }
                emit("step", task, args.getString("status"));
                return result(true, "recorded", "已更新");
            }
            case "ask_user": {
                if (answering) return result(false, "rejected", NO_SECOND_ASK);
                task.question = args.getString("question");
                return new Ending(Ledger.State.AWAITING_USER, task.question);
            }
            default: { // finish
                String status = args.getString("status"), reply = args.getString("reply");
                if (!task.planned()) return new Ending(Ledger.State.ANSWERED, reply);
                if ("done".equals(status)) return new Ending(Ledger.State.DONE, reply);
                return new Ending(task.unfinished() || "partial".equals(status) ? Ledger.State.INTERRUPTED : Ledger.State.FAILED, reply);
            }
        }
    }

    private JSONObject invoke(String name, JSONObject raw, Ledger task, Grounding grounding, Tool.Turn turn, int[] counters)
            throws Exception {
        Tool tool = tools.get(name);
        if (tool == null || !contract.names().contains(name)) { counters[1]++; return result(false, "rejected", "没有这个工具：" + name); }
        JSONObject args;
        List<String> notes = new ArrayList<>();
        try { args = grounding.fix(contract.check(name, raw), notes); }
        catch (IllegalArgumentException e) { counters[1]++; return result(false, "rejected", e.getMessage()); }
        // bili_videos' UP is one the user named, not a guess; 关于用户's only when the user speaks of their own (「我的频道」)
        String up = "bili_videos".equals(name) ? grounding.written(args.optString("up").trim()) : "";
        if (!up.isEmpty()) args.put("up", up);
        // 关于用户's UP only for the user's own (「我的频道」); 「帮我放何同学…」 has a 我 and is not about them
        boolean own = task.utterance != null && OWN.matcher(task.utterance).find();
        if (!up.isEmpty() && !(profile.contains(up) && own) && !grounding.said(up, context)) {
            counters[1]++;
            return result(false, "rejected", "up 要填用户说的 UP 主名字：用户这句话和前面几轮里都没有「" + up + "」，按原话里的名字查。"
                    + "用户说“我的频道”“我关注的”这类话时，才用「关于用户」里的名字。");
        }
        Tool.Gate gate = tool.gate(args, turn);
        boolean read = gate.risk == Tool.Gate.Risk.READ;
        // A one-step connector the gate refuses says why at once (2026-11-11): 「小爱已经为这句话调过音量了」 is what the
        // model has to hear. It used to be told to write a plan first, wrote one, called again and only then heard the
        // refusal — two rounds for nothing, and on a silent test the plan it wrote went off to another app.
        boolean oneStep = DIRECT.contains(name) && !asking
                && (gate.risk == Tool.Gate.Risk.ACT || gate.risk == Tool.Gate.Risk.DENY);
        if (gate.risk != Tool.Gate.Risk.READ && !task.planned() && !oneStep) {
            counters[1]++;
            return result(false, "rejected", NEED_PLAN);
        }
        if (OPENS.contains(name) && Dispatch.asksOnly(task.utterance)) {
            counters[1]++;
            return result(false, "rejected", JUST_ASKING);
        }
        String what = tool.describe(args, turn);
        if (gate.risk == Tool.Gate.Risk.DENY) {
            counters[1]++;
            task.action(name, what, "denied", false, read);
            emit("tool_done", task, "✗ " + what + "：" + gate.reason);
            return result(false, "denied", gate.reason);
        }
        if (++counters[0] > limits.calls) return result(false, "rejected", "本任务的工具调用次数已到上限，请调用 finish 说明进展");
        if (gate.risk == Tool.Gate.Risk.SENSITIVE) {
            String ask = gate.reason.isEmpty() ? what : gate.reason;
            task.state = Ledger.State.AWAITING_CONFIRMATION;
            emit("confirm", task, ask);
            boolean approved;
            try { approved = consent.ask(ask, task); }
            catch (InterruptedException e) { throw new Stopped(); }
            check();
            task.state = Ledger.State.RUNNING;
            emit("consent", task, approved ? "approved" : "declined");
            if (!approved) {
                task.action(name, what, "declined", false, read);
                return result(false, "declined", "用户没有同意：" + ask + "。不要再尝试这一步，调用 finish 说明情况。");
            }
        }
        emit("tool", task, what);
        JSONObject result;
        try {
            result = tool.run(args, turn);
            if (result == null || !(result.opt("ok") instanceof Boolean)) throw new IllegalStateException("工具没有返回结果");
        } catch (Stopped | InterruptedException e) {
            throw new Stopped();
        } catch (AdbUnavailable e) {
            check();
            counters[2]++;
            result = result(false, "adb_unavailable", AdbUnavailable.MESSAGE).put("harness_note", AdbUnavailable.NOTE);
        } catch (Exception e) {
            check();
            result = result(false, "error", e.getMessage() == null || e.getMessage().isEmpty() ? "执行出错" : e.getMessage());
        }
        check();
        boolean ok = result.getBoolean("ok");
        // a name this task found (an UP on the hot list, in a search) counts as the user's for what follows
        if (ok && (DATA.contains(name) || "web_search".equals(name) || "x_search".equals(name))) {
            String found = result.toString();
            context = context + "\n" + (found.length() > 4000 ? found.substring(0, 4000) : found);
        }
        if (!ok) counters[1]++;
        if (!notes.isEmpty()) result.put("notes", new JSONArray(notes));
        String signature = name + args;
        if (signature.equals(lastSignature)) {
            // Seen live: the model clicked Bilibili's 最新发布 toggle twice and sorted by 最多播放 instead.
            result.put("repeat_note", "这一步和上一步的操作完全一样。开关类按钮（比如“最新发布/最多播放”）再点一次会切回去；先看清新的画面再决定下一步。");
        }
        else if (recent.size() == 3) {
            // Seen live 2026-11-05: click 综合, scroll, click 综合, scroll... seven times, each "observed", on a B站 tab
            // the click did not switch. Two alternating steps that keep coming back make no progress: say so, and count
            // it as a failure so the usual three-failure rule ends the loop.
            String[] r = recent.toArray(new String[0]);
            if (signature.equals(r[1]) && r[0].equals(r[2]) && !r[0].equals(r[1])) {
                counters[1]++;
                result.put("loop_note", "你在来回重复同样的两步，画面没有按预期变化，这样不会有进展。换一种办法（比如用方向键把焦点移过去再按 center，或者换个入口），还不行就如实收尾。");
            }
        }
        recent.addLast(signature);
        while (recent.size() > 3) recent.removeFirst();
        lastSignature = signature;
        if (result.opt("screen") instanceof String) grounding.saw(result.getString("screen"));
        String status = result.optString("status", ok ? "ok" : "error");
        task.action(name, what, status, ok, read);
        if (ok && !read) task.touch();
        emit("tool_done", task, (ok ? "✓ " : "✗ ") + what + (ok ? "" : "：" + result.optString("message", status)));
        return result;
    }

    /**
     * find_tools: the gated tools for what the model needs, sent from the next round on. Their rule lines ride in the
     * result, so the system prompt (the cached prefix) stays what it was. Nothing matched: every gated tool by name, to
     * ask again by one of them, or a web search.
     */
    private JSONObject findTools(JSONObject raw, Set<String> sent) throws JSONException {
        String query = raw == null ? "" : raw.optString("query").trim();
        List<String> found = contract.search(query, 3);
        if (found.isEmpty()) {
            JSONArray all = new JSONArray();
            for (String g : contract.gated()) all.put(new JSONObject().put("name", g).put("description", contract.summary(g)));
            return result(true, "none", "没有和「" + query + "」对上的工具。下面是只在用得上时才发的工具，有合适的就用它的名字再 find_tools 一次；"
                    + "都不合适就用 web_search").put("tools", all);
        }
        JSONArray tools = new JSONArray();
        for (String n : found) {
            tools.put(contract.function(n));
            sent.add(n);
        }
        JSONObject r = result(true, "found", "这些工具从下一轮起可以直接调用：" + String.join("、", found)).put("tools", tools);
        List<String> lines = contract.linesFor(found);
        if (!lines.isEmpty()) r.put("rules", new JSONArray(lines));
        return r;
    }

    private String fixQuoted(String text, Grounding grounding) {
        Matcher m = QUOTED.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1), fixed = grounding.fix(inner);
            m.appendReplacement(out, Matcher.quoteReplacement(m.group().replace(inner, fixed)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Old screens are the bulk of the context; the model only needs the latest two. */
    static void trimScreens(JSONArray messages) throws JSONException {
        int kept = 0;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.getJSONObject(i);
            if (!"tool".equals(m.optString("role"))) continue;
            String content = m.optString("content");
            if (!content.contains("\"screen\"")) continue;
            if (++kept <= 2) continue;
            JSONObject c = new JSONObject(content);
            if (c.opt("screen") instanceof String && !"（较早的画面已省略）".equals(c.getString("screen"))) {
                c.put("screen", "（较早的画面已省略）");
                m.put("content", c.toString());
            }
        }
    }

    private Ledger end(Ledger task, Ledger.State state, String reply) {
        task.state = state;
        // an answer or a finished task does not end on an offer; a question asked, a stop explained, keep theirs
        String reply2 = Speech.clean(reply);
        if (state == Ledger.State.ANSWERED || state == Ledger.State.DONE) {
            // the model sometimes writes the answer twice in one reply (2026-11-10 「感冒了吃什么好」): the second
            // telling goes before the offer is looked for, so it cannot be what the answer ends on
            String settled = Speech.settled(Speech.deduped(reply2, task.utterance));
            // never into the first page XiaoAi already read out: the host would read the answer again from its start
            // (2026-11-09 review); stopping after that page drops the same offer without the repeat
            Drafts reader = drafts;
            String read = reader == null ? null : reader.read(task);
            if (read != null && !read.isEmpty() && reply2.startsWith(read) && !settled.startsWith(read)) settled = read;
            reply2 = settled;
        }
        task.reply = reply2;
        task.updatedAt = clock.now();
        emit("finish", task, task.reply);
        return task;
    }

    /** Said when the budget ran out: the last thing that actually changed the TV, then what is on it now. */
    private String stoppedReply(Ledger task) {
        String done = task.lastSuccess();
        String seen = seen();
        return "这件事比预想的复杂，我先停下了" + (done.isEmpty() ? "。" : "，已经做到：" + done + "。")
                + (seen.isEmpty() ? "" : "现在" + seen + "。") + "说“星仪继续”可以接着做。";
    }

    /** The observer is asked once per run at most; a host that cannot tell costs the reply nothing. */
    private String seen() {
        Observer o = observer;
        if (o == null || observed) return "";
        observed = true;
        String text;
        try { text = o.now(); } catch (Exception ignored) { return ""; }
        text = text == null ? "" : text.trim();
        return text.endsWith("。") ? text.substring(0, text.length() - 1).trim() : text;
    }

    private static boolean hasFailedStep(Ledger task) {
        synchronized (task) { for (Ledger.Step s : task.steps) if ("failed".equals(s.status)) return true; }
        return false;
    }

    private static boolean lastActionFailed(Ledger task) {
        synchronized (task) { return !task.actions.isEmpty() && !task.actions.get(task.actions.size() - 1).optBoolean("ok"); }
    }

    /** What a short follow-up (「再来一个」「继续」) is about: the request before it, for picking 应用经验. */
    /** What the gated tools are matched against: the request, what a follow-up follows (followUpContext), the task a
     * reply answers, and the list just read out (「第二个」 after B站热门). */
    static String gateContext(String said, Request req, Ledger previous) {
        StringBuilder s = new StringBuilder(said == null ? "" : said);
        String follow = followUpContext(said, req, previous);
        if (!follow.isEmpty()) s.append('\n').append(follow);
        else if (req.answering && previous != null) s.append('\n').append(previous.utterance).append('\n').append(previous.goal);
        JSONObject list = req.environment == null ? null : req.environment.optJSONObject("last_list");
        if (list != null) s.append('\n').append(list.optString("what"));
        return s.toString();
    }

    private static String followUpContext(String said, Request req, Ledger previous) {
        if (!Skills.followUp(said)) return "";
        StringBuilder s = new StringBuilder();
        if (req.recent != null && req.recent.length() > 0) {
            JSONObject last = req.recent.optJSONObject(req.recent.length() - 1);
            if (last != null) s.append(last.optString("said"));
        }
        if (previous != null) s.append('\n').append(previous.utterance).append('\n').append(previous.goal);
        return s.toString();
    }

    /** Only the fields every chat API knows: role, content, tool calls and their ids. */
    public static JSONArray portable(JSONArray messages) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i), o = new JSONObject().put("role", m.optString("role"));
            Object content = m.opt("content");
            o.put("content", content == null || content == JSONObject.NULL ? "" : content);
            if (m.has("tool_calls")) o.put("tool_calls", m.get("tool_calls"));
            if (m.has("tool_call_id")) o.put("tool_call_id", m.get("tool_call_id"));
            if (m.has("name")) o.put("name", m.get("name"));
            out.put(o);
        }
        return out;
    }

    private static final Set<String> MEMORY = new HashSet<>(Arrays.asList("remember", "forget", "remember_name"));

    private static boolean onlyMemory(JSONArray calls) {
        for (int i = 0; i < calls.length(); i++) if (!MEMORY.contains(name(calls, i))) return false;
        return calls.length() > 0;
    }

    /** The model could not be reached on any route (RouteFailure: network, timeout, server, balance, key, rate): the
     * words MiniMaxClient / ModelRouter put in its message, 「…；备用网关也未能应答：…」 once both routes failed. The engine then hands the sentence back to
     * XiaoAi instead of saying it failed (2026-11-06 overnight pass). */
    private static final Pattern OFFLINE = Pattern.compile("网络请求失败|安全连接失败|响应超时|服务暂时异常|余额不足|鉴权失败|请求过于频繁|未能应答|no model route");

    public static boolean offline(String error) { return error != null && OFFLINE.matcher(error).find(); }

    /**
     * Why the model could not be reached, in words that name the right thing — the start of what is said when the
     * sentence goes back to XiaoAi. 2026-11-14 on the TV: every both-routes failure used to be spoken as
     * 「星仪暂时连不上网络」. At 13:12 the TV reached the gateway in 0.6 s and was told the gateway had no capacity left for
     * MiniMax-M3.1-Flash (429 scheduler_no_capacity), the official account had no balance — and the user heard
     * 「连不上网络」, asked 「为什么没网络」 at 14:13 and went to fix the TV's Wi-Fi, which had been fine all along.
     * The route that would have answered is the one tried last, so the part after 「未能应答：」 decides when it is there.
     * 「网络」 is said only for a failure that really is the network (no connection, TLS); anything not recognised is
     * said as the model being out of reach, never as the network.
     */
    public static String offlineReason(String error) {
        String e = error == null ? "" : error;
        int at = e.lastIndexOf(LAST_ROUTE);
        String last = at >= 0 ? e.substring(at + LAST_ROUTE.length()) : e;
        if (NETWORK_WORDS.matcher(last).find()) return "星仪暂时连不上网络";
        if (last.contains("请求过于频繁")) return "星仪用的模型这会儿太忙";
        if (last.contains("响应超时")) return "星仪用的模型这会儿响应太慢";
        if (last.contains("服务暂时异常")) return "星仪用的模型服务这会儿出了故障";
        if (last.contains("余额不足") || last.contains("鉴权失败")) return "星仪用的模型线路这会儿用不了";
        // The server answered and refused: these are not "cannot reach the model" at all, and saying so sends the user
        // to check the Wi-Fi. Each names the one thing the user can do.
        if (last.contains("回答被截断") || last.contains("回答未完整结束"))
            return "星仪这次要说的话太长，被截断了，说短一点就行";
        if (last.contains("判定内容不合规"))
            return "星仪这边没接这句话（内容不合规），换个说法再试试";
        if (last.contains("没有找到该模型或接口") || last.contains("拒绝了这次请求"))
            return "星仪用的模型名字这会儿对不上，要去改一下配置";
        return "星仪这会儿连不上它用的模型";
    }

    private static final String LAST_ROUTE = "未能应答：";
    private static final Pattern NETWORK_WORDS = Pattern.compile("网络请求失败|安全连接失败|网络不通|无法连接");

    /** Whether a finished task goes back to XiaoAi: it failed for want of a model (offline), did nothing on the TV, and
     * came by voice through XiaoAi (a typed or scheduled one has no XiaoAi to answer it). */
    public static boolean handsBack(Ledger t) {
        if (t == null || (t.state != Ledger.State.FAILED && t.state != Ledger.State.INTERRUPTED)) return false;
        return offline(t.error) && t.actions.isEmpty() && "xiaoai".equals(t.source);
    }

    /** One-step connectors the model may call without a plan (「放首周杰伦的歌」「半小时后关机」「切到 HDMI2」): writing a
     * plan costs the first round about a second (2026-11-05 calibration: 1.5-3.2 s with one, 0.7-1.3 s without) and
     * shows nothing worth reading for one step. Clicks, keys, typing, links and installs still need a plan. */
    private static final Set<String> DIRECT = new HashSet<>(Arrays.asList("netease_play", "netease_playlist", "netease_karaoke",
            "bili_search", "tencent_video_search", "tencent_video_open",
            "set_volume", "adjust_volume", "media_control", "media_seek", "sleep_timer", "switch_input", "open_app", "open_settings", "xiaoai_ask",
            "schedule_task", "cancel_schedule", "wake_screen", "screen_off", "open_link", "free_memory", "install_app"));

    /** The one-step connectors whose result, once it proves itself, is the whole answer. A search page or an opened app
     * is often only the first step, so the model answers (or plans the rest) after seeing it. */
    private static final Set<String> ANSWERS = new HashSet<>(Arrays.asList("netease_play", "netease_playlist", "netease_karaoke",
            "set_volume", "tencent_video_open",
            "adjust_volume", "media_control", "media_seek", "sleep_timer", "schedule_task", "cancel_schedule", "note_add", "note_done",
            "set_hints", "screen_off", "free_memory", "switch_input"));

    /** 「你刚才为什么搜错了」: the user wants the explanation, even when the model also fixed something (remember_name);
     * a tool's own words would skip it. Calibration 2026-11-05: the answer came back as just 「记住了：…」. */
    private static final Pattern WHY = Pattern.compile("为什么|为啥|怎么回事|怎么又|怎么老|怎么总|咋回事|咋又");

    static boolean asksWhy(String said) { return said != null && WHY.matcher(said).find(); }

    /** The spoken answer when a round did only memory tools and answering connectors and each of them proved itself,
     * else null (the model answers). Facts are stored in the third person (用户喜欢…); the answer speaks to the user. */
    static String directAnswer(JSONArray calls, JSONObject[] results) {
        if (calls == null || calls.length() == 0 || !closesOnEvidence(calls, results)) return null;
        StringBuilder said = new StringBuilder();
        for (int i = 0; i < calls.length(); i++) {
            String n = name(calls, i), m;
            // Lighting the screen for what the same round goes on to do has nothing of its own to say (2026-11-11:
            // 「我想看别无选择」 to a sleeping TV came as wake_screen + tencent_video_open, and the closing round the
            // model then got only repeated the connector's sentence).
            if ("wake_screen".equals(n) && calls.length() > 1) continue;
            if (MEMORY.contains(n)) m = results[i].optString("message").trim().replace("用户", "你");
            else if (ANSWERS.contains(n) || playedLink(n, results[i]) || loadingLink(n, results[i])) m = spoken(n, argumentsOf(calls, i), results[i]);
            else return null;
            if (m.isEmpty()) return null;
            said.append(said.length() == 0 ? "" : "；").append(m);
        }
        return said.length() == 0 ? null : said.toString();
    }

    /** A search the model marked final (web_search final=true: its conclusion is the whole answer), alone in its round,
     * that found a conclusion: that conclusion is the answer, said as the search model wrote it. The round after it only
     * rephrased it, and cost 1.5-3 s (2026-11-05 on the TV: web-search questions took ~16 s, ~2 s of it that round). */
    /** What the user asked to have done once something is found: 「播放林俊杰最新的单曲」「查一下…然后放给我听」. */
    private static final Pattern THEN_ACT = Pattern.compile("播放(?!量|数|次数|最多|最高|最火)|播一|放一?[首下个集部遍]|放给|放(?:他|她|它|那)|打开|点开|提醒我|预约|定时|记一下|收藏|下载|安装"
            // 「放影视飓风最新的视频」「放B站热门第一个」「看他最新的视频」 (bili_videos, 2026-11-07); 播放量 is a count, not a request
            // 「放凡人修仙传最新一集」 too (2026-11-07 calibration: the timetable was read and nothing played)
            + "|(?<!播)放[^，。？！]{0,12}?(?:视频|最新一[集话期]|第[一二三四五六七八九十两0-9]+[个首条集部话期])|看[^，。？！]{0,12}?(?:视频|最新一[集话期])"
            // 「适合的话帮我记到随手记」「适合就设个提醒」「在哪能看，我要看」 (2026-11-07 review)
            + "|设个?提醒|定个?提醒|记到|记下|加到|日程|我要看|我想看|想看|帮我放|给我放|给我播");

    /**
     * A search for a request that asks to play, open or remind is a step, not the answer, even when the model marked it
     * final (2026-11-06 calibration: 「网易云音乐播放林俊杰最新发表的一个单曲」 read out the song's name and played nothing
     * in 1 run of 3). The model gets its round to act. So are the lists whose items open (B站's videos, the charts'
     * songs); the other data tools (weather, quotes…) are answers either way.
     */
    static boolean searchThenAct(String said, JSONArray calls) { return searchThenAct(said, calls, null); }

    /** Also a data tool that marked its own answer final (almanac, bili_videos asked whether live): 「明天适合搬家吗，适合的话
     * 提醒我」 still gets the round that sets the reminder. */
    static boolean searchThenAct(String said, JSONArray calls, JSONObject[] results) {
        if (calls == null || calls.length() != 1 || said == null) return false;
        String n = name(calls, 0);
        boolean declared = DATA.contains(n) && results != null && results.length > 0 && results[0] != null && results[0].optBoolean("final");
        // every data tool: a list or a fact the request goes on to act on is a step (music_new before netease_play, the
        // almanac before a reminder); a pure question has no act word and is answered as before
        return ("web_search".equals(n) || "x_search".equals(n) || DATA.contains(n) || declared) && THEN_ACT.matcher(said).find();
    }

    /** Tools whose answer is data: with final=true, what they return is read out as it is. */
    private static final Set<String> DATA = new HashSet<>(Arrays.asList("weather", "stock_quote", "exchange_rate", "hot_list",
            "gold_price", "lottery", "nba_games", "football_games", "world_clock", "tv_guide", "news", "earthquake", "fuel_price",
            "fund_nav", "history_today", "bili_videos", "movie_rating", "bili_bangumi", "weather_alert", "almanac", "movie_list", "music_new"));

    static String searchAnswer(JSONArray calls, JSONObject[] results) {
        return searchAnswer(null, calls, results);
    }

    /**
     * What the round's one search answers. A why-question is never closed by a data tool's own words: 「最近为什么地震了」
     * right after a stock quote got China's latest earthquakes read out (2026-11-07 14:59), because the earthquake tool
     * marks its list final. The model gets the round and says why. A web_search or x_search marked final still closes it
     * — that search is what answers the why.
     */
    static String searchAnswer(String said, JSONArray calls, JSONObject[] results) {
        if (calls == null || calls.length() != 1) return null;
        String n = name(calls, 0);
        if (!"web_search".equals(n) && !"x_search".equals(n) && !DATA.contains(n)) return null;
        if (DATA.contains(n) && asksWhy(said)) return null;
        // a data tool may know its words are the answer (bili_videos asked whether an UP is live, 2026-11-07: the model
        // left final out three times of three and only said it again)
        boolean declared = DATA.contains(n) && results != null && results[0] != null && results[0].optBoolean("final");
        if (!argumentsOf(calls, 0).optBoolean("final") && !declared) return null;
        JSONObject r = results[0];
        if (r == null || !r.optBoolean("ok", false)) return null;
        // the forecast (a quote, a rate, a hot list) is data, not a search that may have come back empty: when it came,
        // it is the answer
        if (DATA.contains(n)) return r.optString("answer").trim().isEmpty() ? null : r.optString("answer").trim();
        String answer = r.optString("answer").trim();
        return SearchBudget.answered(answer, r.optBoolean("searched")) ? answer : null;
    }

    /** A link that opened a video and saw it play (open_link on a bili_videos link, 2026-11-07): that is the answer, as a
     * song netease_play started is; the model's closing round would only say so (1-2 s). An opened page is not. */
    static boolean playedLink(String name, JSONObject result) {
        return "open_link".equals(name) && result != null && result.optJSONObject("playing") != null;
    }

    /** A B站 player page that is up and still loading (openBiliVideo waited 45 s for playback, 2026-11-10 23:10): B站
     * starts the video by itself, so this is the answer as much as a link seen playing is. What the model did after it
     * that night — media_now_playing, a click on 展开更多 that opened the UP's page, a play/pause that paused the video
     * that had started meanwhile — only got in the video's way. */
    static boolean loadingLink(String name, JSONObject result) {
        return "open_link".equals(name) && result != null && result.optBoolean("ok", false) && result.optBoolean("loading");
    }

    /** The tool's own sentence when a round opened a page that is still loading, else null (no such call, or it said
     * nothing). For the rounds `directAnswer` does not close: a task with a plan, or one with another call beside it. */
    private static String loadingAnswer(JSONArray calls, JSONObject[] results) {
        if (calls == null || results == null) return null;
        for (int i = 0; i < calls.length() && i < results.length; i++) {
            if (!loadingLink(name(calls, i), results[i])) continue;
            String m = spoken(name(calls, i), argumentsOf(calls, i), results[i]);
            return m.isEmpty() ? null : m;
        }
        return null;
    }

    /** A connector's result as a sentence to speak: 「音量现在是 30/100」 would be read out as a fraction. */
    private static String spoken(String name, JSONObject args, JSONObject result) {
        String message = result.optString("message").trim();
        switch (name) {
            case "set_volume": return "音量调到 " + args.optInt("level") + " 了";
            case "adjust_volume":
                if (result.optBoolean("muted")) return "已静音";
                return result.has("volume") ? "音量 " + result.optInt("volume") : message;
            case "media_control": return "已发送".equals(message) ? "好的" : message;
            case "open_link": {
                // a B站 page that is still loading: its own words, which say the video will start by itself
                if (result.optBoolean("loading")) return message;
                JSONObject p = result.optJSONObject("playing");
                String title = p == null ? "" : p.optString("title").trim();
                return title.isEmpty() ? message : "正在播放《" + title + "》";
            }
            default: return message;
        }
    }

    /** Tools whose successful result is its own evidence (the contract's evidence rule): the connectors check what
     * they did (netease_play only succeeds once the asked-for recording plays), the others report the new state. */
    private static final Set<String> EVIDENT = new HashSet<>(Arrays.asList("netease_play", "netease_playlist", "netease_karaoke",
            "bili_search", "tencent_video_open", "set_volume", "adjust_volume", "media_control", "media_seek", "open_app", "open_link", "open_settings",
            "sleep_timer", "switch_input", "install_app", "xiaoai_ask", "remember", "forget", "remember_name",
            "schedule_task", "cancel_schedule", "note_add", "note_done", "wake_screen", "screen_off", "set_hints", "free_memory"));

    /** Whether a finish may close the task in the round it came with: yes when the round has no action, or every action
     * succeeded with a result that proves it (a click only once playback started, sleep_timer only when it set
     * something, open_app only once the app is in front). Reads and searches never do: the finish was written before
     * the model saw what they returned. */
    static boolean closesOnEvidence(JSONArray calls, JSONObject[] results) {
        for (int i = 0; i < calls.length(); i++) {
            String n = name(calls, i);
            if (META.contains(n)) continue;
            JSONObject r = results[i];
            if (r == null || !r.optBoolean("ok", false)) return false;
            String status = r.optString("status");
            if ("requested".equals(status) || "no_change".equals(status)) return false;
            if ("screen_click".equals(n)) { if (!r.has("playing")) return false; continue; }
            if (!EVIDENT.contains(n)) return false;
            if ("sleep_timer".equals(n) && !argumentsOf(calls, i).has("minutes")) return false;
        }
        return true;
    }

    private static boolean claimsDone(JSONArray calls) {
        for (int i = 0; i < calls.length(); i++) if ("finish".equals(name(calls, i))) return "done".equals(argumentsOf(calls, i).optString("status"));
        return false;
    }

    private static JSONObject argumentsOf(JSONArray calls, int i) {
        JSONObject call = calls.optJSONObject(i), fn = call == null ? null : call.optJSONObject("function");
        try { return arguments(fn == null ? null : fn.opt("arguments")); }
        catch (IllegalArgumentException e) { return new JSONObject(); }
    }

    private static void closeSteps(Ledger task) {
        synchronized (task) { for (Ledger.Step s : task.steps) if ("pending".equals(s.status) || "in_progress".equals(s.status)) s.status = "done"; }
    }

    private void emit(String type, Ledger task, String text) {
        task.updatedAt = clock.now();
        if (listener == null) return;
        try { listener.onEvent(type, task, text); } catch (RuntimeException ignored) { }
    }

    /** The panel's card a data tool put on its result (weather, quotes, a list): the last one wins, so the card on
     * screen belongs to the answer that was just read out. A result without one leaves the card as it was. */
    private static void takeCard(Ledger task, JSONObject result) {
        JSONObject card = result == null ? null : result.optJSONObject("card");
        if (Cards.valid(card)) task.card = card;
    }

    /** Where a slow task spent its time, one line per model round and per tool call; the host turns the trace into
     * perf.jsonl. Nothing here may ever break a task, so a JSON that cannot be built is simply dropped. */
    private void modelTiming(Ledger task, int round, long ms, int calls, boolean strong) {
        try {
            JSONObject t = new JSONObject().put("kind", "model").put("round", round).put("ms", ms).put("calls", calls);
            if (strong) t.put("strong", true);
            emit("timing", task, t.toString());
        } catch (JSONException ignored) { }
    }

    private void toolTiming(Ledger task, String name, long ms, boolean ok) {
        try { emit("timing", task, new JSONObject().put("kind", "tool").put("name", name).put("ms", ms).put("ok", ok).toString()); }
        catch (JSONException ignored) { }
    }

    /** The part of a tool call the task does not pay for: a download or an install waiting on the TV is not the agent
     * thinking, and the budget is there to stop the loop from spinning. */
    private static long givenBack(long took) { return took > LONG_TOOL ? took - LONG_TOOL : 0; }

    private void check() { if (cancelled.get()) throw new Stopped(); }

    private static String name(JSONArray calls, int i) {
        JSONObject c = calls.optJSONObject(i), f = c == null ? null : c.optJSONObject("function");
        return f == null ? "" : f.optString("name", "");
    }

    private static JSONObject arguments(Object raw) {
        if (raw == null || (raw instanceof String && ((String) raw).trim().isEmpty())) return new JSONObject();
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (!(raw instanceof String) || ((String) raw).length() > 8000) throw new IllegalArgumentException("参数必须是 JSON 对象");
        try {
            JSONTokener parser = new JSONTokener((String) raw);
            Object v = parser.nextValue();
            if (!(v instanceof JSONObject)) throw new IllegalArgumentException("参数必须是 JSON 对象");
            return (JSONObject) v;
        } catch (JSONException e) {
            throw new IllegalArgumentException("参数不是有效的 JSON");
        }
    }

    static JSONObject result(boolean ok, String status, String message) {
        try { return new JSONObject().put("ok", ok).put("status", status).put("message", message == null ? "" : message); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JSONObject message(String role, String content) throws JSONException { return new JSONObject().put("role", role).put("content", content); }
}
