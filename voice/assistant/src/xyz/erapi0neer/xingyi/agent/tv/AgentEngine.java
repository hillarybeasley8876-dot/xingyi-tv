package xyz.erapi0neer.xingyi.agent.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.text.TextUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.json.*;
import xyz.erapi0neer.xingyi.ModelRouter;
import xyz.erapi0neer.xingyi.VendorSpeech;
import xyz.erapi0neer.xingyi.agent.*;

/** The one place every request enters (XiaoAi bridge, 星仪 screen, phone/PC voice, debug). Runs one task at a
 * time, routes 确认/取消/停 to the running task, shows progress over any app and speaks results through XiaoAi. */
public final class AgentEngine {
    public interface Listener { void onTask(Ledger task, String event, String text); }

    private static AgentEngine instance;
    private static volatile String xiaoaiAsk = "";
    private static volatile long xiaoaiAskAt;
    private static volatile boolean eyesTried;

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "xingyi-agent"));
    /** Volume commands spoken while a task is running (Dispatch.Route.SIDE): their own thread, so they never wait
     * behind the task they are meant to happen during. */
    private final ExecutorService sideWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "xingyi-side"));
    /** Side answers that go to the network (the weather, the self-check, the brief, what is playing): on their own
     * thread, so a slow one does not hold up a volume change behind it (review 2026-11-06). */
    private final ExecutorService sideNet = Executors.newSingleThreadExecutor(r -> new Thread(r, "xingyi-side-net"));
    private final android.os.Handler ack = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Reads long answers page by page (narrate); one at a time, and a newer one ends the one before. */
    private final ExecutorService narrator = Executors.newSingleThreadExecutor(r -> new Thread(r, "xingyi-narrator"));
    private final java.util.concurrent.atomic.AtomicInteger narration = new java.util.concurrent.atomic.AtomicInteger();
    private final Store store;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object consentLock = new Object();
    private String consentAsk;
    private Boolean consentAnswer;
    private volatile AgentLoop loop;
    private volatile Ledger task;
    /** The task a newer request superseded: it ends CANCELLED as usual but says nothing and shows no 已停下 panel,
     * because the user has already moved on and the new task's panel takes its place. */
    private volatile Ledger superseded;
    /** The request (generation) the shown task belongs to: the PC side waits for its own task by it, not for "a new
     * task id", which a XiaoAi request started in between would also be (2026-11-05 audit). */
    private volatile int taskGen = -1;
    private volatile String lastAction = "";
    /** How many times the running task has already been resumed after the process was killed. */
    private volatile int resumes;
    private int generation, pending;
    /** Requests numbered below this are cancelled: a superseding request or 停 moves it up to themselves, while a
     * question that waits its turn (Dispatch.Route.QUEUE) leaves it, so the task before it runs on to its answer. */
    private volatile int floor;
    /** Questions waiting for the running task (Dispatch.Route.QUEUE), in order: {request, text}, for the panel's 接下来. */
    private final List<Object[]> waiting = Collections.synchronizedList(new ArrayList<Object[]>());
    /** The last few exchanges, for the panel's thread: a new sentence no longer wipes the last answer off the screen. */
    private final ArrayDeque<Exchange> exchanges = new ArrayDeque<>();
    /** How long an exchange stays in the thread above the task on screen. */
    private static final long THREAD_MS = 180_000;
    private Contract contract;
    /** The running task's timing trace (AgentLoop's "timing" events) and when it started, for perf.jsonl. */
    private final List<JSONObject> timings = new ArrayList<>();
    private volatile long taskStarted;
    private volatile long firstSpeechMs = -1;
    /** B站 cold-starts in ~15 s; this is what the user says when they want it. */
    private static final Pattern BILI_WORDS = Pattern.compile("b站|bilibili|哔哩", Pattern.CASE_INSENSITIVE);
    /** A song asked for (「播放汪苏泷的晴」「我要唱林俊杰的歌」): NetEase takes 10-14 s from silence to playing, most of it its
     * own cold start (2026-11-18 audit of the TV's perf log: 12 of 16 netease_play calls at 10-16 s; the catalog's two
     * HTTP calls answer in 0.3 s). The verb must be followed by 歌/音乐/曲, so 「我想知道…」 「这首歌叫什么」 are left alone. */
    private static final Pattern SONG_WORDS = Pattern.compile("唱|放|听|来一首|来首|播放|点歌|点一首");
    private static final Pattern SONG_NOUN = Pattern.compile("歌|音乐|曲|fm", Pattern.CASE_INSENSITIVE);
    /** This TV's home screen (launcher/). */
    static final String HOME_PKG = "xyz.erapi0neer.orrery";
    private static final Pattern ABOUT_TIME = Pattern.compile("多久|多长时间|几个?小时|几分钟|看了多少|用了多少|什么时候看");
    /** The home screen's answer, kept for half a minute (it asks whenever it comes to the front), and what the media
     * session last said, read on a thread of its own. */
    private static final long HOME_MS = 30_000, MEDIA_WAIT_MS = 250, WEATHER_WAIT_MS = 800;
    /** The last weather line the home had, and its day (yyyyMMdd): what a miss of the 10-minute forecast falls back on. */
    private volatile String homeWeatherLine = "";
    private volatile int homeWeatherDay;
    private final Object homeLock = new Object();
    private volatile JSONObject home;
    private volatile long homeAt;
    private volatile JSONObject homeMedia;
    private volatile long homeMediaAt;
    private volatile boolean homeMediaBusy;

    public static synchronized AgentEngine get(Context c) {
        if (instance == null) instance = new AgentEngine(c.getApplicationContext());
        return instance;
    }

    private AgentEngine(Context c) {
        app = c;
        store = new Store(new File(c.getFilesDir(), "agent"));
        // The phone console lives as long as the engine does: the bridge is the process that is always up, so the
        // phone can reach 星仪 while the TV is on any app. A taken port only means the console is off (logged there).
        PhoneServer.start(app, this);
    }

    static void eyesConnected(Eyes eyes) { AgentService.ensure(eyes); }

    /** Remembered so the XiaoAi bridge does not hand our own request back to us. */
    static void noteXiaoaiAsk(String q) { xiaoaiAsk = q; xiaoaiAskAt = System.currentTimeMillis(); }

    /** True when XiaoAi is now handling a sentence this engine handed to it moments ago. */
    public static boolean isOwnXiaoaiAsk(String heard) {
        if (heard == null || System.currentTimeMillis() - xiaoaiAskAt > 20_000) return false;
        String a = heard.replaceAll("[\\s，。！？,.!?]", ""), b = xiaoaiAsk.replaceAll("[\\s，。！？,.!?]", "");
        return !b.isEmpty() && (a.equals(b) || a.endsWith(b) || Grounding.distance(a, b, 2) <= 2);
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** Called when the host service starts: borrow 星仪's identity, load the contract, and pick up a task that
     * the low-memory killer interrupted (at most three times per task, only if it was cut off in the last 3 minutes). */
    public void warmUp() {
        worker.execute(() -> {
            Identity.ensure(app);
            try { contract(); } catch (Exception ignored) { }
            // the TV's own place names for the quick weather (「明天上海天气怎么样」), refreshed with every request's environment
            QuickCommand.here(placeNames());
            JSONObject running = store.running();
            if (running == null) return;
            store.clearRunning();
            try {
                Ledger cut = Ledger.fromJson(running.getJSONObject("task"));
                int count = running.optInt("resumes");
                long age = System.currentTimeMillis() - running.optLong("at");
                cut.state = Ledger.State.INTERRUPTED;
                cut.reply = "（被系统内存清理打断）";
                cut.updatedAt = System.currentTimeMillis();
                if (traced(cut.source)) store.remember(cut);
                // Only a planned task can be continued: 「继续」 with an unplanned one would pick up some older planned
                // task as previous_task (2026-11-05 audit). A one-step request that was cut is simply said again.
                if (age < 180_000 && count < 3 && cut.planned()) resume(cut, count + 1);
                else if (age < 180_000 && spoken(cut.source)) VendorSpeech.speak(app, cut.planned()
                        ? "刚才的任务被系统清理打断了好几次，没有再接着做，你可以再说一遍。"
                        : "刚才那句被系统清理打断了，没做完，你可以再说一遍。");
            } catch (JSONException ignored) { }
        });
    }

    private synchronized void resume(Ledger cut, int count) {
        // A request that arrived at service start is already queued (pending == 1): it must not be superseded and
        // silently dropped by the resume's own generation (2026-11-05 audit).
        if (loop != null || pending > 0) return;
        int gen = ++generation;
        pending++;
        String note = "星仪后台刚被电视的内存清理打断，现在自动接着做上一项任务（previous_task）。先看当前屏幕，再从没完成的步骤继续。";
        worker.execute(() -> {
            try {
                // Restarting into the same low-memory storm just gets killed again: let memory recover first.
                // ...but a request the user makes meanwhile goes first (it moves the generation on)
                try { waitForMemory(250L * 1024, 30_000, () -> gen != currentGeneration() || cancelled(gen)); } catch (InterruptedException stop) { return; }
                if (gen != currentGeneration() || cancelled(gen)) return;
                run("继续", cut.source, gen, count, note, false);
            }
            finally { synchronized (AgentEngine.this) { pending--; } }
        });
    }

    /** Returns how the text was routed: started, approved, declined, cancelled, side, idle, empty. */
    /** What XiaoAi itself did for each request handed over (its instruction names), so the tools refuse to repeat
     * a relative action it already took; by request, since a question may wait behind the running task. */
    private final Map<Integer, Set<String>> handed = new java.util.concurrent.ConcurrentHashMap<>();
    /** When each of those requests came (elapsed-time clock): a request that waited behind another task still measures
     * from when XiaoAi acted, not from when it ran (TvTools.requestStarted). */
    private final Map<Integer, Long> arrived = new java.util.concurrent.ConcurrentHashMap<>();

    public synchronized String submit(String text, String source) { return submit(text, source, null, null); }

    /**
     * 星仪接管模式 (2026-11-05): XiaoAi still acts on its own basic functions but its voice is cut; the bridge hands the
     * request over with note = what XiaoAi did and meant to say, and xiaoaiDid = its instruction names.
     */
    public synchronized String submit(String text, String source, String note, Set<String> xiaoaiDid) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "empty";
        String src = source == null ? "" : source;
        stopReading();
        boolean consentPending;
        Boolean consentReply;
        synchronized (consentLock) {
            consentPending = consentAsk != null;
            consentReply = consentPending ? Replies.consent(t) : null;
            if (consentPending && consentReply == null) {
                // Anything else declines the waiting step and becomes the next request.
                consentAnswer = false;
                consentLock.notifyAll();
            }
        }
        boolean busy = loop != null || pending > 0, answering = questionPending();
        // While a task is running the volume runs beside it and a question waits its turn; everything else
        // supersedes the task.
        QuickCommand quick = busy ? QuickCommand.match(t, Collections.<String>emptySet()) : null;
        Dispatch.Route route = Dispatch.route(consentPending, consentReply, Replies.stop(t), busy,
                answering, Dispatch.side(quick), busy && Dispatch.queueable(t, quick));
        if (route == Dispatch.Route.CONSENT) {
            synchronized (consentLock) { consentAnswer = consentReply; consentLock.notifyAll(); }
            return consentReply ? "approved" : "declined";
        }
        if (route == Dispatch.Route.STOP_TASK) { cancel(); return "cancelled"; }
        if (route == Dispatch.Route.DROP_QUESTION) { dropQuestion(src); return "cancelled"; }
        if (route == Dispatch.Route.IDLE_STOP) {
            if (spoken(src)) VendorSpeech.speak(app, "好的。");
            return "idle";
        }
        if (route == Dispatch.Route.SIDE && beside(quick, t, src, xiaoaiDid)) return "side";
        boolean queued = route == Dispatch.Route.QUEUE;
        // SIDE that could not be handed over, and SUPERSEDE: the running task ends quietly, the new one replaces it.
        // QUEUE: the running task goes on to its answer and this question runs after it (one task at a time).
        if (busy && !queued) { superseded = task; cancel(); }
        AgentService.ensure(app);
        int gen = ++generation;
        pending++;
        if (xiaoaiDid != null) {
            handed.put(gen, new HashSet<>(xiaoaiDid));
            arrived.put(gen, android.os.SystemClock.elapsedRealtime());
        }
        if (queued) {
            waiting.add(new Object[]{gen, t});
            refreshPanel();
        }
        worker.execute(() -> {
            try { run(t, src, gen, 0, note, answering); }
            finally { synchronized (AgentEngine.this) { pending--; } }
        });
        return queued ? "queued" : "started";
    }

    /** Whether a request was cancelled: by a newer one that superseded it, or by 停. */
    private boolean cancelled(int gen) { return gen < floor; }

    /** A question is still open while its answer window is: ANSWER_WINDOW_MS after it was asked. */
    private boolean questionPending() {
        Ledger t = task;
        return t != null && t.state == Ledger.State.AWAITING_USER
                && System.currentTimeMillis() - t.updatedAt < Dispatch.ANSWER_WINDOW_MS;
    }

    /** 「停」 while 星仪 is waiting for an answer: the question is dropped, its panel goes at once, and the exchange
     * is recorded (the answer would have been this task's next step). */
    private void dropQuestion(String source) {
        Ledger t = task;
        if (t == null || t.state != Ledger.State.AWAITING_USER) return;
        synchronized (t) {
            t.state = Ledger.State.CANCELLED;
            t.reply = "好的，不问了。";
            t.updatedAt = System.currentTimeMillis();
        }
        if (traced(t.source)) store.remember(t);
        Eyes eyes = Eyes.get();
        if (eyes != null && eyes.hud() != null) eyes.hud().hide();
        if (spoken(source)) VendorSpeech.speak(app, "好的。");
    }

    /**
     * A volume command spoken while a task is running: it runs beside the task, on its own thread and its own tools,
     * touching nothing the task owns (its ledger, panel, timings, perf trace or crash-recovery file). True when it was
     * handed over; false means it could not even be started and the request must supersede the task as usual.
     */
    private boolean beside(QuickCommand q, String text, String source, Set<String> did) {
        boolean net = "weather".equals(q.tool) || "self_check".equals(q.tool) || "media_now_playing".equals(q.tool)
                || "stock_quote".equals(q.tool) || "exchange_rate".equals(q.tool) || "hot_list".equals(q.tool)
                || "gold_price".equals(q.tool) || "lottery".equals(q.tool) || "nba_games".equals(q.tool) || "football_games".equals(q.tool)
                || "tv_guide".equals(q.tool) || "news".equals(q.tool) || "earthquake".equals(q.tool) || "fuel_price".equals(q.tool)
                || "fund_nav".equals(q.tool) || "history_today".equals(q.tool) || "movie_rating".equals(q.tool)
                || "bili_bangumi".equals(q.tool) || "weather_alert".equals(q.tool) || "almanac".equals(q.tool)
                || "movie_list".equals(q.tool) || "music_new".equals(q.tool)
                || ("clock".equals(q.tool) && "brief".equals(q.args.optString("kind")));
        try {
            (net ? sideNet : sideWorker).execute(() -> {
                long startedAt = System.currentTimeMillis();
                if ("clock".equals(q.tool) || "replay".equals(q.tool)) {
                    // an uncaught exception on an executor thread ends the whole process on Android, task and all
                    try {
                        String reply = localReply(q);
                        if (reply == null) { sayBeside(source, NOT_FOUND); return; }
                        sayBeside(source, reply);
                        recordSidePerf(text, source, System.currentTimeMillis() - startedAt);
                    } catch (Exception ignored) { }
                    return;
                }
                try (TvTools tools = new TvTools(app)) {
                    tools.xiaoaiDid = did == null ? Collections.<String>emptySet() : new HashSet<>(did);
                    Tool tool = null;
                    for (Tool x : tools.tools) if (x.name().equals(q.tool)) tool = x;
                    if (tool == null) return;
                    Ledger side = new Ledger(Long.toString(System.currentTimeMillis(), 36), text, source, startedAt);
                    Tool.Turn turn = new Tool.Turn() {
                        @Override public String utterance() { return text; }
                        @Override public String source() { return source; }
                        // No generation of its own: cancelling the running task must not abort the volume, and it is
                        // over in milliseconds anyway.
                        @Override public boolean cancelled() { return false; }
                        @Override public void progress(String p) { }
                        @Override public void say(String s) { sayBeside(source, s); }
                        @Override public int round() { return 1; }
                    };
                    Tool.Gate gate = tool.gate(q.args, turn);
                    if (gate.risk == Tool.Gate.Risk.SENSITIVE) return;
                    // DENY: XiaoAi already turned the volume for this very sentence — say what is true now.
                    // …or the PC's silent test while the TV sleeps: nothing done, and said so
                    String reply = gate.risk == Tool.Gate.Risk.DENY ? (TvTools.ASLEEP_TEST.equals(gate.reason) ? gate.reason : alreadyDone(q)) : ran(tool, q, turn, side);
                    // a question asked beside a task gets an answer even when its tool came back empty (before, it was
                    // silent; such a question used to wait for the task and go to the model)
                    if (reply == null) { if (net) sayBeside(source, NOT_FOUND); return; }
                    side.state = Ledger.State.DONE;
                    side.reply = reply;
                    side.updatedAt = System.currentTimeMillis();
                    if (traced(source)) {
                        store.remember(side);
                        history(side, tools);
                    }
                    sayBeside(source, reply);
                    recordSidePerf(text, source, System.currentTimeMillis() - startedAt);
                } catch (Exception ignored) {
                    // The volume simply did not change. The running task is untouched either way.
                }
            });
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    /** Said when a quick question asked beside a running task found nothing. */
    private static final String NOT_FOUND = "这个刚才没查到，等会儿再问我一次。";

    /** Spoken beside a running task: straight through XiaoAi, never through the task's own speak() (which would
     * record its first-speech time and show the running task's panel). */
    private void sayBeside(String source, String text) {
        if (text == null || text.isEmpty() || !spoken(source)) return;
        VendorSpeech.speak(app, text);
    }

    /** A side command's own perf line: quick, with its own wall time and none of the running task's timings. */
    private void recordSidePerf(String utterance, String source, long wallMs) {
        try {
            String said = utterance == null ? "" : utterance;
            store.perf(new JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("utterance", said.length() > 40 ? said.substring(0, 40) : said)
                    .put("source", source == null ? "" : source)
                    .put("quick", true)
                    .put("summary", Perf.summary(new ArrayList<JSONObject>(), wallMs, -1)));
        } catch (Exception ignored) { }
    }

    public void cancel() {
        synchronized (this) { floor = generation + 1; }
        waiting.clear();
        AgentLoop l = loop;
        if (l != null) l.cancel();
        synchronized (consentLock) {
            if (consentAsk != null) { consentAnswer = false; consentLock.notifyAll(); }
        }
    }

    /** The 星仪 app's 取消 and the PC's tv_cancel: stop the running task, or drop the question 星仪 is waiting on
     * (cancel() alone left a pending question and its panel in place). */
    public void stop() {
        if (busy()) { cancel(); return; }
        if (questionPending()) dropQuestion("ui");
    }

    // ---- the 星仪 app's 记忆 and 历史 pages -----------------------------------------------------------

    /** Everything 星仪 keeps about the user, for the 记忆 page; each item carries what forgetItem needs to delete it. */
    public JSONObject memory() {
        JSONObject o = new JSONObject();
        long now = System.currentTimeMillis();
        SimpleDateFormat day = new SimpleDateFormat("M月d日 H:mm", Locale.CHINA);
        try {
            JSONObject profile = store.profile();
            JSONArray facts = profile.optJSONArray("facts");
            o.put("facts", facts == null ? new JSONArray() : facts);
            JSONArray aliases = new JSONArray();
            JSONObject a = profile.optJSONObject("aliases");
            if (a != null) for (Iterator<String> k = a.keys(); k.hasNext(); ) {
                String heard = k.next();
                aliases.put(new JSONObject().put("heard", heard).put("written", a.optString(heard)));
            }
            o.put("aliases", aliases);
            List<String> learned;
            try { learned = Habits.summarize(store.habits(), now); } catch (RuntimeException e) { learned = Collections.emptyList(); }
            o.put("habits", new JSONArray(learned));
            JSONArray notes = new JSONArray(), items = store.notes().optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject n = items.optJSONObject(i);
                if (n == null || now - n.optLong("at") > Notes.MAX_AGE_DAYS * 86_400_000L) continue;
                notes.put(new JSONObject().put("id", n.optInt("id")).put("text", n.optString("text"))
                        .put("when", day.format(new Date(n.optLong("at")))));
            }
            o.put("notes", notes);
            JSONArray schedules = new JSONArray();
            for (JSONObject s : Schedules.all(Scheduler.get(app).store().load()))
                schedules.put(new JSONObject().put("id", s.optInt("id"))
                        .put("text", Schedules.describe(s, now, TimeZone.getDefault())));
            o.put("schedules", schedules);
            o.put("hints", hintsOn());
        } catch (Exception ignored) { }
        return o;
    }

    /** Deletes one item of the 记忆 page. kind: fact (which = its text), alias (which = the heard spelling), note and
     * schedule (which = the id), habits (everything learned from use). @return the sentence the page shows. */
    public String forgetItem(String kind, String which) {
        String w = which == null ? "" : which.trim();
        try {
            switch (kind == null ? "" : kind) {
                case "fact": {
                    JSONObject p = store.profile();
                    JSONArray f = p.optJSONArray("facts");
                    for (int i = 0; f != null && i < f.length(); i++) {
                        if (!f.optString(i).equals(w)) continue;
                        f.remove(i);
                        store.saveProfile(p);
                        return "已忘掉：" + w;
                    }
                    return "这条已经不在了";
                }
                case "alias": {
                    JSONObject p = store.profile(), a = p.optJSONObject("aliases");
                    if (a == null || !a.has(w)) return "这个写法已经不在了";
                    a.remove(w);
                    store.saveProfile(p);
                    return "删掉了「" + w + "」的写法";
                }
                case "note": {
                    JSONObject n = store.notes();
                    List<String> gone = Notes.done(n, w);
                    if (gone.isEmpty()) return "随手记里已经没有这件了";
                    store.saveNotes(n);
                    return "划掉了：" + TextUtils.join("、", gone);
                }
                case "schedule": {
                    ScheduleTools.Store s = Scheduler.get(app).store();
                    JSONObject book = s.load();
                    List<String> gone = Schedules.cancel(book, w, System.currentTimeMillis(), TimeZone.getDefault());
                    if (gone.isEmpty()) return "这个预约已经不在了";
                    s.save(book);
                    return "取消了：" + TextUtils.join("；", gone);
                }
                case "habits":
                    store.saveHabits(new JSONObject());
                    return "使用习惯已经清空";
                default:
                    return "不认识的类型：" + kind;
            }
        } catch (Exception e) {
            return "没删成：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** The 历史 page: what 星仪 did, newest first, at most {@code limit} entries of the last {@code days} days, with
     * an optional word to look for (in what was said, answered or playing). */
    public JSONArray history(String query, int days, int limit) {
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis(), from = now - Math.max(1, Math.min(30, days)) * 86_400_000L;
        String q = query == null ? "" : query.trim();
        SimpleDateFormat day = new SimpleDateFormat("M月d日 H:mm", Locale.CHINA);
        try {
            JSONArray items = store.history().optJSONArray("items");
            for (int i = items == null ? -1 : items.length() - 1; i >= 0 && out.length() < limit; i--) {
                JSONObject e = items.optJSONObject(i);
                if (e == null || e.optLong("at") < from) continue;
                String said = e.optString("said"), replied = e.optString("replied"), playing = e.optString("playing");
                if (!q.isEmpty() && !(said + "\n" + replied + "\n" + playing).contains(q)) continue;
                out.put(new JSONObject().put("when", day.format(new Date(e.optLong("at")))).put("said", said)
                        .put("replied", replied).put("state", e.optString("state")).put("playing", playing));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * What the home screen shows (Orrery 「轴」, 2026-11-08): {lunar, weather, now:{title, artist, app, state},
     * notes:{package: 上次在听《…》}} — the launcher has no network code and no right to read media sessions, so it asks
     * 星仪. Built at most once every 30 s and kept (the launcher asks whenever it comes to the front), waits on the
     * network at most WEATHER_WAIT_MS (a cold forecast) and MEDIA_WAIT_MS, and never throws: whatever fails is simply
     * left out of the answer.
     */
    public JSONObject homeInfo() {
        JSONObject kept = home;
        if (kept != null && System.currentTimeMillis() - homeAt < HOME_MS) return withAgent(kept);
        synchronized (homeLock) {
            if (home != null && System.currentTimeMillis() - homeAt < HOME_MS) return withAgent(home);
            JSONObject built = buildHome(System.currentTimeMillis());
            home = built;
            homeAt = System.currentTimeMillis();
            return withAgent(built);
        }
    }

    /**
     * What 星仪 is doing right now, put on every answer and never kept in the 30 s cache (the home screen must not
     * show 待命 while a task runs): 待命 idle, 执行中 while one runs, and while it waits on the user 等你确认 /
     * 等你回答 — from the same fields state() reads, so the two never disagree.
     */
    private JSONObject withAgent(JSONObject o) {
        String awaiting = "";
        synchronized (consentLock) { if (consentAsk != null) awaiting = "consent"; }
        if (awaiting.isEmpty() && questionPending()) awaiting = "answer";
        String word = "consent".equals(awaiting) ? "等你确认" : "answer".equals(awaiting) ? "等你回答"
                : busy() ? "执行中" : "待命";
        try {
            o.put("agent", word);
        } catch (JSONException ignored) { }
        return o;
    }

    /** The answer's parts, each on its own: a part that cannot be read is left out (the home screen draws what is there). */
    private JSONObject buildHome(long now) {
        JSONObject o = new JSONObject();
        try {
            // 农历 is the same everywhere the TV can be, and the almanac's own years are the only limit
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
            c.setTimeInMillis(now);
            String lunar = Almanac.homeLine(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            if (!lunar.isEmpty()) o.put("lunar", lunar);
        } catch (Exception ignored) { }
        try {
            String weather = homeWeather(now);
            if (weather != null && !weather.isEmpty()) o.put("weather", weather);
        } catch (Exception ignored) { }
        try {
            JSONObject media = media(MEDIA_WAIT_MS);
            JSONObject playing = media == null ? null : media.optJSONObject("now");
            if (playing != null) o.put("now", playing);
            JSONObject notes = media == null ? null : media.optJSONObject("notes");
            if (notes != null && notes.length() > 0) o.put("notes", notes);
        } catch (Exception ignored) { }
        return o;
    }

    /**
     * The day's weather for the TV's own place, from the forecast already in the cache. The forecast is kept 10 minutes
     * and the launcher asks every minute, so a miss is common while the home sits in front: it warms the cache, waits
     * a little for it (a cold forecast came in 0.3–1.6 s), and otherwise answers with the line it last had for the same
     * day — before 2026-11-08 the home's WEATHER column went away for a minute in every eleven.
     */
    private String homeWeather(long now) {
        Weather.Where here = TvTools.here(app);
        if (here == null) return null;
        TimeZone tz = TimeZone.getDefault();
        String line = keptWeather(here, now, tz);
        if (line == null) {
            Weather.warm(here);
            for (long end = System.currentTimeMillis() + WEATHER_WAIT_MS; line == null && System.currentTimeMillis() < end; ) {
                try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                line = keptWeather(here, now, tz);
            }
        }
        int day = dayOf(now, tz);
        if (line != null && !line.isEmpty()) {
            homeWeatherLine = line;
            homeWeatherDay = day;
            return line;
        }
        return day == homeWeatherDay ? homeWeatherLine : null;
    }

    /** The home's weather line from the cache alone (the Get under it only fails, so a miss costs nothing), or null. */
    private static String keptWeather(Weather.Where here, long now, TimeZone tz) {
        try {
            Weather.Get cold = (url, timeout) -> { throw new IOException("还没缓存"); };
            return Weather.homeLine(Weather.answer(Weather.cached(cold), here, "", "今天", false, false, false, false).forecast,
                    now, tz);
        } catch (Exception e) {
            return null;
        }
    }

    private static int dayOf(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** What the media session says, read on a thread of its own: homeInfo waits {@code ms} for it and otherwise takes
     * what the last read left — dumpsys over the local ADB link takes a few hundred ms, and the home screen must not
     * be held up for it (the next call, within the half minute it is kept, has it). */
    private JSONObject media(long ms) {
        long now = System.currentTimeMillis(), was = homeMediaAt;
        if (homeMedia != null && now - was < HOME_MS) return homeMedia;
        if (!homeMediaBusy) {
            homeMediaBusy = true;
            Thread t = new Thread(() -> {
                try { homeMedia = readMedia(); } catch (Exception ignored) { }
                finally { homeMediaAt = System.currentTimeMillis(); homeMediaBusy = false; mediaCame(); }
            }, "xingyi-home-media");
            t.setDaemon(true);
            t.start();
        }
        for (long end = now + ms; System.currentTimeMillis() < end && homeMediaAt == was; ) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return homeMedia;
    }

    /** The session came back after the answer was built (the home screen must not wait for it): the kept answer is
     * built again with it, so the next call — the launcher asks whenever it comes to the front — has what is playing
     * without a second read. */
    private void mediaCame() {
        JSONObject read = homeMedia;
        if (read == null || read.length() == 0) return; // nothing came: nothing to add, and no second read to start
        synchronized (homeLock) {
            JSONObject kept = home;
            if (kept != null && (kept.has("now") || kept.has("notes"))) return;
            home = buildHome(System.currentTimeMillis());
            homeAt = System.currentTimeMillis();
        }
    }

    /** {now:{…}} while something plays, {notes:{pkg: 上次在听《…》}} for a paused session, {} for anything else. */
    private JSONObject readMedia() {
        try (TvTools tools = new TvTools(app)) {
            MediaSessions.Session s = tools.nowPlaying();
            if (s == null || s.pkg.isEmpty()) return new JSONObject();
            if ("playing".equals(s.state))
                return new JSONObject().put("now", new JSONObject().put("title", s.title).put("artist", s.artist)
                        .put("app", tools.label(s.pkg)).put("state", s.state));
            if ("paused".equals(s.state)) {
                String note = HomeNotes.paused(s.pkg, s.title, s.artist);
                if (!note.isEmpty()) return new JSONObject().put("notes", new JSONObject().put(s.pkg, note));
            }
            return new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** When the last quiet card went up (process memory: after a restart one card more is no harm). */
    private volatile long lastHint;

    /** The screen came on (the bridge's screen receiver): a quiet card, no sound, with what is coming up today, what is
     * still on the 随手记 and what the user usually does at this hour — unless hints are off, a task is running or
     * waiting, 星仪's own screen is in front, or the last card was less than 3 hours ago (Hints.allowed). */
    public void screenOn() {
        Weather.warm(TvTools.here(app));    // the home comes to the front now and asks for the day's weather
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        if (busy() || !Hints.allowed(lastHint, now, tz)) return;
        sideWorker.execute(() -> {
            try {
                if (!hintsOn() || !state().optString("awaiting").isEmpty()) return;
                Eyes eyes = Eyes.get();
                if (eyes == null || eyes.hud() == null || AgentClient.SCREEN.equals(Eyes.foregroundPackage())) return;
                // the day's weather first (2.5 s at most: the card waits for it, the screen is already on)
                int hour = Calendar.getInstance(tz).get(Calendar.HOUR_OF_DAY);
                String weather = Weather.hintFor(Weather.cached(Weather.https()), TvTools.here(app), hour, 2500);
                List<String> lines = Hints.card(store.habits(), store.notes(), Scheduler.get(app).store().load(), now, tz, weather);
                if (lines.isEmpty()) return;
                if (eyes.hud().showHint(new Hud.State("hint", "", null, "", TextUtils.join("\n", lines)))) lastHint = now;
            } catch (Exception ignored) { }
        });
    }

    /** Quiet hint cards when the screen comes on (on unless the user turned them off). */
    public boolean hintsOn() { return store.profile().optBoolean("hints", true); }

    public void setHints(boolean on) {
        try {
            JSONObject p = store.profile();
            p.put("hints", on);
            store.saveProfile(p);
        } catch (Exception ignored) { }
    }

    public void consent(boolean approve) {
        synchronized (consentLock) {
            if (consentAsk != null) { consentAnswer = approve; consentLock.notifyAll(); }
        }
    }

    public boolean busy() { return loop != null || pending > 0; }

    /** Whether a recent task can be continued (interrupted, cancelled, or waiting for the user). */
    public boolean resumable(long withinMs) {
        Ledger t = task != null ? task : store.lastTask();
        return t != null && t.state.resumable() && System.currentTimeMillis() - t.updatedAt < withinMs;
    }

    /** How many paid searches today has seen (DailyCap), for bridge.sh status: {"web": n, "x": n}. */
    private JSONObject searchesToday() throws JSONException {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        File dir = new File(app.getFilesDir(), "agent");
        return new JSONObject()
                .put("web", new DailyCap(new File(dir, "web-search-day.json"), TvTools.WEB_PER_DAY, "").used(day))
                .put("x", new DailyCap(new File(dir, "x-search-day.json"), TvTools.X_PER_DAY, "").used(day));
    }

    /** DUMP-only (AgentDebug FORGET_CHECKS): the history and recent-turn entries the PC's checks left since a time. */
    String forgetChecks(long since, Set<String> sources) { return store.forgetChecks(since, sources); }

    /** DUMP-only: a store file as it is, for a backup before forgetChecks. */
    String rawFile(String name) { return store.raw(name); }

    /** The last task's full conversation (debug export only). */
    String transcript() { return store.transcript(); }

    /** The last real tasks' full conversations, newest first (debug export only). */
    List<String> transcripts() { return store.ring(); }

    /** For the bridge and the screen: what is running, whether an answer is awaited, the latest task. */
    public JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            Ledger t = task != null ? task : store.lastTask();
            String awaiting = "", prompt = "";
            synchronized (consentLock) { if (consentAsk != null) { awaiting = "consent"; prompt = consentAsk; } }
            long now = System.currentTimeMillis();
            if (awaiting.isEmpty() && questionPending()) { awaiting = "answer"; prompt = t.question; }
            o.put("busy", busy()).put("awaiting", awaiting).put("prompt", prompt)
                    .put("task", t == null ? JSONObject.NULL : t.toJson())
                    .put("xiaoai_ask", xiaoaiAsk).put("xiaoai_ask_ms_ago", xiaoaiAskAt == 0 ? -1 : now - xiaoaiAskAt)
                    .put("eyes", Eyes.get() != null)
                    .put("perf", Perf.rollup(store.perfSummaries(20)))
                    .put("request", taskGen)
                    .put("perf_recent", store.perfRecent(5))
                    .put("warm", ModelRouter.lastWarm())
                    .put("searches_today", searchesToday())
                    .put("streaming", streaming());
        } catch (JSONException ignored) { }
        return o;
    }

    // ---- running a task --------------------------------------------------------------------------

    /** answering: the sentence answers the question 星仪 asked (it was open when the sentence came). */
    private void run(String text, String source, int gen, int resumeCount, String note, boolean answering) {
        Set<String> did = handed.remove(gen);
        Long came = arrived.remove(gen);
        synchronized (waiting) {
            for (Iterator<Object[]> i = waiting.iterator(); i.hasNext(); ) if ((int) i.next()[0] <= gen) i.remove();
        }
        if (cancelled(gen)) return;
        taskGen = gen;
        // The worker runs one task at a time, so whatever task this one superseded has already ended by now.
        superseded = null;
        resumes = resumeCount;
        taskStarted = System.currentTimeMillis();
        timings.clear();
        firstSpeechMs = -1;
        firstShownMs = -1;
        // The panel goes up now, before the TV is read (foreground, volume, what is playing: 1-2 s): XiaoAi's own card
        // is closed the moment 星仪 takes over, and without this the screen stayed empty in between (2026-11-05 21:53).
        if (resumeCount == 0) {
            Ledger early = new Ledger("early", text, source, taskStarted);
            lastAction = "正在理解";
            hud(early, "running", "");
        }
        Identity.ensure(app);
        AgentLoop l;
        try (TvTools tools = new TvTools(app)) {
            if (did != null) tools.xiaoaiDid = did;
            // when the request came, on the clock the media sessions use: did XiaoAi's own skip land (media_control)
            tools.requestStarted(came != null ? came
                    : android.os.SystemClock.elapsedRealtime() - Math.max(0, System.currentTimeMillis() - taskStarted));
            runningDid = did == null ? Collections.<String>emptySet() : did;
            // A simple, unambiguous TV command (音量调到20, 暂停, 回到桌面…) runs its tool at once, with no model round;
            // everything else is planned by the model exactly as before.
            if (resumeCount == 0) {
                QuickCommand quick = QuickCommand.match(text, tools.apps().keySet());
                if (quick != null && runQuick(quick, tools, text, source, gen)) return;
            }
            // B站 takes ~15 s to cold-start and the model's first round is ~2 s: start it now, while the model plans.
            // bili_search already waits for it, so nothing else changes.
            startBilibiliEarly(text, tools);
            // The same for a song: NetEase's cold start then overlaps the model's first round instead of following it.
            // netease_play still brings it to the front and plays; nothing else changes.
            startNeteaseEarly(text, tools);
            ensureEyes(tools);
            ModelRouter router = ModelRouter.open(app);
            AgentLoop.Model model = new AgentLoop.Model() {
                @Override public JSONObject next(JSONArray m, JSONArray t) throws Exception { return router.next(m, t); }
                @Override public JSONObject next(JSONArray m, JSONArray t, boolean strong) throws Exception {
                    return strong ? router.nextStrong(m, t) : router.next(m, t);
                }
                @Override public JSONObject next(JSONArray m, JSONArray t, boolean strong, Sse.Sink sink) throws Exception {
                    return strong ? router.nextStrong(m, t) : router.next(m, t, sink);
                }
                @Override public void cancel() { router.cancel(); }
            };
            // The TV's tools plus the memory tools, which edit profile.json (remember / forget / remember_name) and,
            // with the habits store, let forget also drop what 星仪 learned from use (habits.json).
            List<Tool> all = new ArrayList<>(tools.tools);
            all.addAll(MemoryTools.create(new MemoryTools.Store() {
                @Override public JSONObject load() { return store.profile(); }
                @Override public void save(JSONObject profile) throws Exception { store.saveProfile(profile); }
            }, new MemoryTools.Store() {
                @Override public JSONObject load() { return store.habits(); }
                @Override public void save(JSONObject habits) throws Exception { store.saveHabits(habits); }
            }));
            // 定时、随手记和回忆 (2026-11-05 audit): the schedule book belongs to the TV's clock (Scheduler, fired by the
            // bridge's heartbeat); the notes and the 30-day history are 星仪's own files.
            TimeZone tz = TimeZone.getDefault();
            all.addAll(ScheduleTools.create(Scheduler.get(app).store(), System::currentTimeMillis, tz));
            all.addAll(NoteTools.create(new NoteTools.Store() {
                @Override public JSONObject load() { return store.notes(); }
                @Override public void save(JSONObject notes) throws Exception { store.saveNotes(notes); }
            }, System::currentTimeMillis, tz));
            all.addAll(HistoryTools.create(store::history, System::currentTimeMillis, tz));
            all.addAll(CalendarTools.create(System::currentTimeMillis, tz));
            // 自己探索应用: an app's own deep links (a read-only package dump) and what 星仪 learned about it by trying
            all.addAll(ExploreTools.create(pkg -> tools.adb.call(a -> a.packageDump(pkg)), new ExploreTools.Apps() {
                @Override public String resolve(String app) { return tools.resolveApp(app); }
                @Override public String label(String pkg) { return tools.label(pkg); }
            }, new MemoryTools.Store() {
                @Override public JSONObject load() { return store.appNotes(); }
                @Override public void save(JSONObject notes) throws Exception { store.saveAppNotes(notes); }
            }));
            all.addAll(HintTools.create(new MemoryTools.Store() {
                @Override public JSONObject load() { return store.profile(); }
                @Override public void save(JSONObject profile) throws Exception { store.saveProfile(profile); }
            }));
            l = new AgentLoop(contract(), model, all, this::onEvent, this::ask, System::currentTimeMillis, null);
            if (streaming()) l.drafts(drafts);
            // What is on the TV right now, for the reply when a task stops early (read at most once per run).
            l.observe(tools::observe);
            AgentLoop.Request req = new AgentLoop.Request(text, source);
            req.answering = answering;
            req.environment = environment(tools, text);
            // which model answers this turn, so 「你用的什么模型」 is answered from a fact, not from its own guess
            req.environment.put("model", router.expectedModel());
            if (note != null) req.environment.put("note", note);
            Ledger previous = store.lastTask();
            if (previous != null && System.currentTimeMillis() - previous.updatedAt < 2 * 3600_000L) req.previous = previous;
            req.recent = store.recent();
            req.profile = profileText();
            req.aliases = store.aliases();
            // what 星仪 learned about apps by trying, offered like the contract's 应用经验 when the app comes up
            try { req.skills = AppNotes.skills(store.appNotes()); } catch (RuntimeException ignored) { }
            if (resumeCount > 0 && spoken(source)) VendorSpeech.speak(app, "刚才被系统打断了，我接着做。");
            loop = l;
            if (cancelled(gen)) l.cancel();
            // The setup between the request and the first model round (reading the TV, starting B站, the tools, the
            // stores) and the tail after the loop were in no timing at all: 2026-11-11 21:18 took 46 s with 5.8 s of
            // model and 19 ms of tools, and none of the other 40 s could be placed (2026-11-18 audit). Two lines.
            long loopBegan = System.currentTimeMillis();
            timing(new JSONObject().put("kind", "phase").put("name", "setup").put("ms", loopBegan - taskStarted).toString());
            Ledger result = l.run(req);
            timing(new JSONObject().put("kind", "phase").put("name", "tail").put("ms", System.currentTimeMillis() - loopBegan).toString());
            if (traced(source)) {
                store.remember(result);
                history(result, tools);
            }
            store.clearRunning();
            store.saveTranscript(l.transcript());
            // the ring keeps the last 12 real tasks (test and debug runs stay out, so they never push real ones out)
            if (traced(source)) store.saveRing(result, l.transcript(), System.currentTimeMillis() - result.startedAt);
            if (traced(source)) recordHabits(l.transcript());
            recordPerf(text, source, false);
        } catch (Exception e) {
            store.clearRunning();
            Ledger failed = new Ledger(Long.toString(System.currentTimeMillis(), 36), text, source, System.currentTimeMillis());
            failed.state = Ledger.State.FAILED;
            failed.error = String.valueOf(e.getMessage());
            failed.reply = "星仪没能开始处理：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            onEvent("finish", failed, failed.reply);
        } finally {
            loop = null;
        }
    }

    private synchronized int currentGeneration() { return generation; }

    /** The request id a "started" submit was just given (the debug receiver hands it to the PC side). */
    synchronized int lastRequest() { return generation; }

    // ---- perf ------------------------------------------------------------------------------------

    /** Where the running task's time went, one line per finished task in files/agent/perf.jsonl. Best-effort. */
    private void recordPerf(String utterance, String source, boolean quick) {
        try {
            String said = utterance == null ? "" : utterance;
            store.perf(new JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("utterance", said.length() > 40 ? said.substring(0, 40) : said)
                    .put("source", source == null ? "" : source)
                    .put("quick", quick)
                    .put("summary", Perf.summary(new ArrayList<>(timings), System.currentTimeMillis() - taskStarted, firstSpeechMs)
                            .put("first_shown_ms", firstShownMs)));
        } catch (Exception ignored) { }
    }

    /** One timing line from the loop; a line that is not JSON is dropped, and only the last 200 are kept. */
    private void timing(String json) {
        try {
            if (timings.size() >= 200) return;
            timings.add(new JSONObject(json == null ? "" : json));
        } catch (Exception ignored) { }
    }

    /** B站 is the slowest thing on this TV to start (~15 s cold). Fire its launch intent without waiting, so it is
     * coming up while the model writes its plan; bili_search waits for it either way. Never throws. */
    private void startBilibiliEarly(String text, TvTools tools) {
        try {
            if (text == null || !BILI_WORDS.matcher(text).find()) return;
            // not in standby (a video needs the screen first, and B站 cold in the dark only takes memory on a 2 GB TV),
            // nor for a question about the time spent there (2026-11-06: 「昨天看了多久B站」 at 05:57 started it)
            android.os.PowerManager pm = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;
            if (ABOUT_TIME.matcher(text).find()) return;
            if (TvTools.BILI.equals(tools.foreground())) return; // already up: nothing to warm
            Intent i = app.getPackageManager().getLeanbackLaunchIntentForPackage(TvTools.BILI);
            if (i == null) i = app.getPackageManager().getLaunchIntentForPackage(TvTools.BILI);
            if (i == null) return; // not installed: install_app / bili_search will say so
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    /** A song asked for: start NetEase's process now so its cold start overlaps the model's first round. It is only
     * brought up (its own screen, the way it starts), never told to play; netease_play does that. Never throws. */
    private void startNeteaseEarly(String text, TvTools tools) {
        try {
            if (text == null || !SONG_WORDS.matcher(text).find() || !SONG_NOUN.matcher(text).find()) return;
            // not in standby (a song needs the screen first, and a player cold in the dark only takes memory on a 2 GB TV)
            android.os.PowerManager pm = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;
            // nothing playing and nothing else to disturb: a video in the background would be covered by NetEase's screen
            MediaSessions.Session playing = tools.nowPlaying();
            if (playing != null && "playing".equals(playing.state) && !TvTools.NETEASE.equals(playing.pkg)) return;
            if (TvTools.NETEASE.equals(tools.foreground())) return; // already up: nothing to warm
            Intent i = tools.launchIntent(TvTools.NETEASE);
            if (i == null) return; // not installed: netease_play will say so
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    // ---- habits ----------------------------------------------------------------------------------

    /** What 星仪 learned from a finished task: every successful tool call in the transcript, recorded at the TV's
     * own hour. Best-effort — nothing here may ever break a task. */
    private void recordHabits(JSONArray transcript) {
        try {
            long now = System.currentTimeMillis();
            int hour = hour();
            List<JSONObject> events = new ArrayList<>();
            for (JSONObject call : Habits.callsFromTranscript(transcript))
                events.addAll(Habits.events(call.optString("name"), call.optJSONObject("args"),
                        call.optJSONObject("result"), now, hour));
            learn(events, now);
        } catch (Exception ignored) { }
    }

    /** One call that ran outside the loop (a quick command), recorded the same way. */
    private void recordCall(String tool, JSONObject args, JSONObject result) {
        try {
            long now = System.currentTimeMillis();
            learn(Habits.events(tool, args, result, now, hour()), now);
        } catch (Exception ignored) { }
    }

    /** For the XiaoAi bridge's own paths, which never reach the loop (it plays a song itself): one habit, now. */
    public void habit(String kind, String value) {
        try {
            String k = kind == null ? "" : kind.trim(), v = value == null ? "" : value.trim();
            if (k.isEmpty() || v.isEmpty()) return;
            long now = System.currentTimeMillis();
            learn(Collections.singletonList(Habits.event(k, v, now, hour())), now);
        } catch (Exception ignored) { }
    }

    /** For the bridge's own song path, which never reaches the loop: a history entry, so 「昨天那首歌」 finds it. */
    public void played(String artist, String title) { played("", artist, title); }

    /** The same, with what the user said: before 2026-11-08 the line read 「播放<artist>的<title>」 even when the user had
     * said 「我要听你要的全拿走」 (and the bridge had wrongly seen the song before it), so recent_turns showed the model
     * words nobody said. Without the words (old callers) the line is still made up from the song. */
    public void played(String said, String artist, String title) {
        String a = artist == null ? "" : artist.trim(), t = title == null ? "" : title.trim(), s = said == null ? "" : said.trim();
        if (t.isEmpty()) return;
        store.addHistory(s.isEmpty() ? "播放" + (a.isEmpty() ? "" : a + "的") + t : s, "网易云正在播放" + a + "《" + t + "》",
                "done", t, "网易云音乐");
    }

    /** The request the bridge's song path is working on, for the panel (null when there is none). */
    private volatile Ledger song;

    /**
     * The bridge's song path took a request (it plays songs itself and never reaches the loop): the panel goes up with
     * what the user said, as it does for every other request. Before 2026-11-11 a song showed nothing for the 18 s
     * NetEase needs and then a toast at the bottom; that evening 「开机」 had fallen into an install and
     * 「播放汪苏泷的晴」 into this gap, and after two requests without the panel the user asked
     * 「为什么现在HARNESS面板没有打开」.
     */
    public void songAsked(String said, String doing) {
        if (said == null || said.trim().isEmpty() || busy()) return;
        final Ledger t = new Ledger("song" + Long.toString(System.currentTimeMillis(), 36), said.trim(), "xiaoai",
                System.currentTimeMillis());
        song = t;
        lastAction = doing == null || doing.trim().isEmpty() ? "网易云点歌" : doing.trim();
        hud(t, "running", "");
        // a song path that ends without a word (a newer request took over) must not leave 「执行中」 on the screen
        ack.postDelayed(() -> {
            if (song != t) return;
            song = null;
            Eyes eyes = Eyes.get();
            if (!busy() && eyes != null && eyes.hud() != null) eyes.hud().hideSoon();
        }, 45_000);
    }

    /** …and what came of it, on the same panel: not spoken (the song is the answer), and kept as the exchange above
     * the next request. False when there is no panel to show it on — the caller then says it its own way. */
    public boolean songAnswered(String said, String reply) {
        String s = said == null ? "" : said.trim(), r = reply == null ? "" : reply.trim();
        Ledger t = song;
        song = null;
        if (r.isEmpty()) return false;
        Eyes eyes = Eyes.get();
        if (eyes == null || eyes.hud() == null) return false;
        if (busy()) return true; // a task took the screen meanwhile: its panel stays, the song is playing anyway
        if (t == null || !t.utterance.equals(s))
            t = new Ledger("song" + Long.toString(System.currentTimeMillis(), 36), s, "xiaoai", System.currentTimeMillis());
        t.state = Ledger.State.DONE;
        t.reply = r;
        t.updatedAt = System.currentTimeMillis();
        lastAction = "";
        hud(t, "done", r);
        exchanged(t);
        return true;
    }

    /** The bridge's song path took a request while a task was running: the user moved on, so the task ends quietly,
     * as it does when a newer request supersedes it. */
    public synchronized void supersedeQuietly() {
        if (loop == null && pending == 0) return;
        superseded = task;
        cancel();
    }

    /** One entry of what 星仪 did (files/agent/history.json, for recall_history): what was said, the answer, what was
     * playing when it ended and the app in front. Best-effort: a history that cannot be written costs nothing else. */
    private void history(Ledger t, TvTools tools) {
        try {
            MediaSessions.Session s = tools.nowPlaying();
            String playing = s != null && "playing".equals(s.state) && !s.title.isEmpty() ? s.title : titleIn(t.reply);
            String fg = tools.foreground();
            store.addHistory(t.utterance, t.reply, t.state.name().toLowerCase(Locale.ROOT), playing,
                    fg.isEmpty() ? null : tools.label(fg));
        } catch (Exception ignored) { }
    }

    private static final Pattern TITLE = Pattern.compile("《([^》]{1,60})》");

    /** The first 《…》 of an answer (「已在B站播放《…》」), when the media session says nothing. */
    static String titleIn(String reply) {
        java.util.regex.Matcher m = TITLE.matcher(reply == null ? "" : reply);
        return m.find() ? m.group(1) : null;
    }

    /** Appends what was learned and writes the file; nothing learned, nothing written. */
    private void learn(List<JSONObject> events, long now) throws Exception {
        if (events.isEmpty()) return;
        JSONObject habits = store.habits();
        Habits.record(habits, events, now);
        store.saveHabits(habits);
    }

    /** The TV's local hour, which is what 「多在晚上」 means. */
    private static int hour() { return Calendar.getInstance().get(Calendar.HOUR_OF_DAY); }

    /** The facts the user asked 星仪 to remember, plus what it worked out by itself — marked as a guess, because it is one. */
    private String profileText() {
        long now = System.currentTimeMillis();
        StringBuilder s = new StringBuilder(store.profileText());
        List<String> learned, notes;
        try { learned = Habits.summarize(store.habits(), now); }
        catch (RuntimeException e) { learned = Collections.emptyList(); }
        try { notes = Notes.summary(store.notes(), now, TimeZone.getDefault()); }
        catch (RuntimeException e) { notes = Collections.emptyList(); }
        if (!learned.isEmpty()) {
            if (s.length() > 0) s.append('\n');
            s.append("\n（以下是根据使用记录自动整理的习惯，可能不准）\n");
            for (String line : learned) s.append("- ").append(line).append('\n');
        }
        // the contract tells the model to answer 「我让你记了什么」 from here, without a note_list round
        if (!notes.isEmpty()) {
            if (s.length() > 0) s.append('\n');
            s.append("\n（随手记，还没划掉）\n");
            for (String line : notes) s.append("- ").append(line).append('\n');
        }
        return s.toString().trim();
    }

    /** One simple command, run without the model: true when it was carried out (or XiaoAi had already done it) and the
     * exchange was spoken and recorded, false when the model must take over — nothing ran, nothing changed. */
    private boolean runQuick(QuickCommand q, TvTools tools, String text, String source, int gen) {
        if ("clock".equals(q.tool) || "replay".equals(q.tool)) return answerHere(q, text, source);
        try {
            Tool tool = null;
            for (Tool t : tools.tools) if (t.name().equals(q.tool)) tool = t;
            if (tool == null) return false;
            Ledger task = new Ledger(Long.toString(System.currentTimeMillis(), 36), text, source, System.currentTimeMillis());
            Tool.Turn turn = new Tool.Turn() {
                @Override public String utterance() { return text; }
                @Override public String source() { return source; }
                @Override public boolean cancelled() { return AgentEngine.this.cancelled(gen); }
                @Override public void progress(String p) { }
                @Override public void say(String s) { onEvent("say", task, s); }
                @Override public int round() { return 1; }
            };
            Tool.Gate gate = tool.gate(q.args, turn);
            if (gate.risk == Tool.Gate.Risk.SENSITIVE) return false; // the model asks for consent as usual
            // A DENY means XiaoAi already did this very thing for the same sentence (a second 调大/下一首 would double it),
            // and a UIController instruction means it pressed the key itself: say what is true now instead of acting.
            // media_control's refusal carries the song and the position (TvTools.playingNow): 「好的」 told the user
            // nothing about what was playing (2026-11-18 audit), so that reason is said as it is.
            String reply = gate.risk == Tool.Gate.Risk.DENY && (TvTools.ASLEEP_TEST.equals(gate.reason) || "media_control".equals(q.tool)) ? gate.reason
                    : gate.risk == Tool.Gate.Risk.DENY || ("press_keys".equals(q.tool) && uiPressed(tools))
                    ? alreadyDone(q) : ran(tool, q, turn, task);
            if (reply == null) return false;
            // The command is done: from here on nothing may hand it to the model, which would do it a second time.
            try {
                task.state = Ledger.State.DONE;
                task.reply = reply;
                task.updatedAt = System.currentTimeMillis();
                if (traced(source)) {
                    store.remember(task);
                    history(task, tools);
                }
                store.clearRunning();
                onEvent("finish", task, reply);
                recordPerf(text, source, true);
            } catch (Exception ignored) { }
            return true;
        } catch (Exception e) {
            return false; // anything unexpected: the model handles the request as before
        }
    }

    /** 「现在几点」「今天几号」 from the TV's clock, 「再说一遍」 from the last answer: no tool, no model (2026-11-06
     * overnight pass). False when there is nothing to say (no answer yet to repeat): the model takes it. */
    private boolean answerHere(QuickCommand q, String text, String source) {
        String reply = localReply(q);
        if (reply == null) return false; // outside the almanac's years: the model takes it
        Ledger task = new Ledger(Long.toString(System.currentTimeMillis(), 36), text, source, System.currentTimeMillis());
        task.state = Ledger.State.ANSWERED;
        task.reply = reply;
        task.updatedAt = System.currentTimeMillis();
        // a repeat is not a new exchange: the answer it repeats stays the last one
        if ("clock".equals(q.tool) && traced(source)) store.remember(task);
        onEvent("finish", task, reply);
        recordPerf(text, source, true);
        return true;
    }

    /** The answer to a quick question that needs no tool: the clock, the calendar, the volume level, 再说一遍. */
    private String localReply(QuickCommand q) {
        String reply;
        if ("clock".equals(q.tool) && "brief".equals(q.args.optString("kind"))) {
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            int hour = Calendar.getInstance(tz).get(Calendar.HOUR_OF_DAY);
            // today's weather even in the evening: 「今天」 is what a briefing tells
            String weather = Weather.hintFor(Weather.cached(Weather.https()), TvTools.here(app), Math.min(hour, 17), 2500);
            JSONObject book;
            try { book = Scheduler.get(app).store().load(); } catch (Exception e) { book = new JSONObject(); }
            return Brief.today(now, tz, weather, book, store.notes());
        }
        if ("clock".equals(q.tool) && "notes".equals(q.args.optString("kind"))) {
            try { return Notes.list(store.notes(), System.currentTimeMillis(), TimeZone.getDefault()); }
            catch (Exception e) { return null; } // the model takes it
        }
        if ("clock".equals(q.tool) && "schedules".equals(q.args.optString("kind"))) {
            try { return Schedules.list(Scheduler.get(app).store().load(), System.currentTimeMillis(), TimeZone.getDefault()); }
            catch (Exception e) { return null; } // the model takes it
        }
        if ("clock".equals(q.tool) && "volume".equals(q.args.optString("kind"))) {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int level = max <= 0 ? v : Math.round(v * 100f / max);
            reply = am.isStreamMute(AudioManager.STREAM_MUSIC) ? "现在是静音，音量 " + level : "现在音量 " + level;
        } else if ("clock".equals(q.tool)) {
            Calendar cal = Calendar.getInstance();
            reply = "holiday".equals(q.args.optString("kind"))
                    ? Almanac.holidayAnswer(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                            q.args.optString("label"), q.args.optString("when"))
                    : QuickCommand.clockText(q.args.optString("kind"), QuickCommand.daysAhead(q.args, cal), q.args.optString("label"), cal);
            if (reply == null) return null; // outside the almanac's years
        } else {
            reply = "";
            synchronized (exchanges) {
                // only within the three hours recent turns keep (exchanges live as long as the process)
                Exchange last = exchanges.isEmpty() ? null : exchanges.peekLast();
                if (last != null && System.currentTimeMillis() - last.at < 3 * 3_600_000L) reply = last.reply;
            }
            if (reply.isEmpty()) {
                JSONArray recent = store.recent();
                JSONObject last = recent.length() == 0 ? null : recent.optJSONObject(recent.length() - 1);
                reply = last == null ? "" : last.optString("replied");
            }
            // nothing said in the last three hours: no model round guessing at what 「刚才」 was (2026-11-06 sweep: three
            // rounds through the history, 8.6 s, to read out an answer from hours before)
            if (reply.isEmpty()) reply = "我刚才没说什么。想问什么直接说就行。";
        }
        return reply;
    }

    /** What the tool reports, or null when it did not succeed (the model then takes over, with nothing done). The
     * panel's card a data tool put on its result goes on the quick command's own ledger, so the answer is drawn with
     * the numbers it was read from. */
    private String ran(Tool tool, QuickCommand q, Tool.Turn turn, Ledger task) throws Exception {
        JSONObject result = tool.run(q.args, turn);
        if (result == null) return null;
        if (!result.optBoolean("ok")) {
            // A tool that did what it could and knows why it did not work has the answer already (云视听极光's ad cannot
            // be paused: the key was pressed twice and read back): its sentence is said. The model would only press
            // the same key again and say the same thing ten seconds later (lead, 2026-11-11). Anything else that
            // fails goes to the model as before — nothing was done, it may know another way.
            String said = result.optBoolean("said") ? result.optString("message").trim() : "";
            return said.isEmpty() ? null : said;
        }
        JSONObject card = result.optJSONObject("card");
        if (task != null && Cards.valid(card)) task.card = card;
        if (traced(turn.source())) recordCall(q.tool, q.args, result);
        return q.reply(result);
    }

    /** The user is speaking to XiaoAi: open the model connection now, not when the request is ready; a long answer
     * being read stops after the page XiaoAi is on (2026-11-06: a reading went on to its last page after a stop). */
    public void userSpeaking() {
        stopReading();
        ModelRouter.warm(app);
        Weather.warm(TvTools.here(app));
    }

    /** The words XiaoAi's recognition has so far, and how long ago — only for the panel's listening line. */
    private volatile String heardWords = "";
    private volatile long heardAt;
    private String listeningPanel;

    /**
     * The user is still speaking: XiaoAi's partial recognition reached this text (2026-11-11/12 on the TV: 「为什么没有
     * 看到我输入的文字」「你没发现你的那个面板跳不出来了吗就是小爱原本的面板」). In takeover mode XiaoAi's own card is
     * hidden and 星仪's panel only went up when the sentence was final — 2–3 s after the first words — so while the user
     * watched what they were saying there was nothing on the screen at all.
     *
     * <p>The panel goes up with the words so far and follows them. It does not replace a task that is running or a
     * question waiting for an answer: those keep the panel.
     */
    public void listening(String words) {
        String w = words == null ? "" : words.trim();
        if (w.isEmpty()) return;
        heardWords = w;
        heardAt = System.currentTimeMillis();
        if (busy()) return;
        // a ledger per partial text (its utterance is final): one id for the whole sentence, so it reads as one panel
        if (listeningPanel == null)
            listeningPanel = "listen" + Long.toString(System.currentTimeMillis(), 36);
        lastAction = "正在听";
        hud(new Ledger(listeningPanel, w, "xiaoai", System.currentTimeMillis()), "running", "");
    }

    /** XiaoAi has heard a request that 星仪 will take (takeover mode): the panel goes up at once, while the bridge is
     * still collecting what XiaoAi did (≤0.9 s), so XiaoAi's bar and 星仪's panel meet with no empty screen between. */
    public void preview(String text) {
        if (text == null || text.trim().isEmpty() || busy()) return;
        listeningPanel = null;   // the words so far were their own panel; the task is a new one
        lastAction = "正在理解";
        hud(new Ledger("preview", text.trim(), "xiaoai", System.currentTimeMillis()), "running", "");
    }

    /** What was heard before the final sentence came (or "" when it was a while ago): the panel asks for it, so the
     * words do not blink out between the partial text and the final one. */
    String justHeard() {
        String w = heardWords;
        return System.currentTimeMillis() - heardAt <= 20_000 ? w : "";
    }

    /** The music volume when the bridge saw the request, before XiaoAi could act on it (-1: unknown). */
    private volatile int volumeBefore = -1;
    private volatile long volumeBeforeAt;

    /** Called by the XiaoAi bridge the moment XiaoAi's text arrives, before XiaoAi's own action can land. */
    public void volumeAtRequest(int volume) { volumeBefore = volume; volumeBeforeAt = System.currentTimeMillis(); }

    /** XiaoAi already acted on this sentence: say what is true now. Its volume change lands a moment after its
     * instructions (2026-11-05: an immediate read said 音量 25 while the TV went to 35), so wait for the volume to move
     * away from what it was when the request came, at most 2 s. */
    private String alreadyDone(QuickCommand q) throws InterruptedException {
        if (!"adjust_volume".equals(q.tool)) return "好的";
        String direction = q.args.optString("direction");
        if ("mute".equals(direction)) return "已静音";
        if ("unmute".equals(direction)) return "已取消静音";
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC), before = volumeBefore;
        if (before >= 0 && System.currentTimeMillis() - volumeBeforeAt < 10_000) {
            long end = System.currentTimeMillis() + 2000;
            while (v == before && System.currentTimeMillis() < end) {
                Thread.sleep(100);
                v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
        }
        return "音量 " + v;
    }

    private static boolean uiPressed(TvTools tools) {
        Set<String> did = tools.xiaoaiDid;
        for (String name : did == null ? Collections.<String>emptySet() : did)
            if (name != null && name.startsWith("UIController.")) return true;
        return false;
    }

    private boolean ask(String what, Ledger t) throws InterruptedException {
        synchronized (consentLock) {
            consentAsk = what;
            consentAnswer = null;
            long end = System.currentTimeMillis() + 60_000;
            try {
                while (consentAnswer == null) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    consentLock.wait(left);
                }
                return Boolean.TRUE.equals(consentAnswer);
            } finally {
                consentAsk = null;
                consentAnswer = null;
            }
        }
    }

    private void onEvent(String type, Ledger t, String text) {
        task = t;
        // A task cut off by the low-memory killer is picked up again from this file when the process returns.
        if (t.state.open() && !"progress".equals(type) && !"tool".equals(type) && !"timing".equals(type))
            store.saveRunning(t, resumes);
        switch (type) {
            case "start": lastAction = "正在理解"; hud(t, "running", ""); break;
            case "plan": lastAction = ""; hud(t, "running", ""); acknowledge(t); break;
            case "tool": case "progress": lastAction = text; hud(t, "running", ""); break;
            case "tool_done": case "step": case "consent": hud(t, "running", ""); break;
            case "timing": timing(text); break;
            case "say": speak(t, text); break;
            case "confirm": {
                // Not 确认/取消: XiaoAi itself presses OK on 确认/可以/同意 and Back on 取消 (seen 2026-11-04),
                // which would act on the very screen waiting for consent. 好的/算了 are plain chat to XiaoAi.
                String say = "需要你确认：" + text + "。可以就说“好的”，不做就说“算了”。";
                hud(t, "confirm", say);
                speak(t, say);
                break;
            }
            case "finish": {
                // Superseded by a newer request: it ends CANCELLED as usual, but the user has already moved on, so
                // nothing is said and no 已停下 panel appears — the new task's panel takes its place.
                if (t == superseded) { superseded = null; break; }
                // the hand-back is still this turn's answer: it belongs in the panel's conversation line too
                if (handBack(t)) { if (traced(t.source)) exchanged(t); break; }
                narrate(t, mode(t.state));
                if (t.state != Ledger.State.CANCELLED && traced(t.source)) exchanged(t);
                break;
            }
            default:
        }
        for (Listener x : listeners) {
            try { x.onTask(t, type, text); } catch (RuntimeException ignored) { }
        }
    }

    private static String mode(Ledger.State s) {
        switch (s) {
            case DONE: case ANSWERED: return "done";
            case AWAITING_USER: return "ask";
            case CANCELLED: return "stopped";
            case INTERRUPTED: return "stopped";
            default: return "failed";
        }
    }

    /**
     * The answer on the panel and in XiaoAi's voice: a short one at once, a long one page by page — each page shown
     * while it is read, the next one when XiaoAi has finished it (VendorSpeech.speakAndWait), to the last page
     * (2026-11-06: 「问了下NP问题 还是没完全读完啊 而且读到后面也不自动切换卡片到下一页」). The user waking XiaoAi, 停, or a
     * new task ends the reading; the page on screen then fades like any finished answer.
     */
    private void narrate(Ledger t, String mode) {
        if (firstShownMs < 0) firstShownMs = System.currentTimeMillis() - taskStarted;
        String read;
        synchronized (earlyLock) { read = earlyTask == t ? early : ""; earlyTask = null; early = ""; }
        if (!read.isEmpty()) { readOn(t, mode, read); return; }
        List<String> pages = Speech.pages(t.reply, Speech.PAGE_MAX);
        int id = narration.incrementAndGet();
        if (pages.size() <= 1) {
            hud(t, mode, t.reply);
            speak(t, Speech.answer(t.reply));
            return;
        }
        final int gen = taskGen;
        narrator.execute(() -> {
            VendorSpeech.Cancelled moved = () -> narration.get() != id || taskGen != gen;
            for (int i = 0; i < pages.size(); i++) {
                if (moved.now()) { settle(t, mode); return; }
                boolean last = i == pages.size() - 1;
                String page = (i + 1) + "/" + pages.size();
                hud(t, last ? mode : "reading", pages.get(i), page);
                if (!spoken(t.source)) {
                    // typed on 星仪's screen or from the PC: nothing is read, the pages turn at reading pace
                    long until = android.os.SystemClock.uptimeMillis() + 2000 + pages.get(i).length() * 200L;
                    while (!last && android.os.SystemClock.uptimeMillis() < until && !moved.now()) android.os.SystemClock.sleep(250);
                    continue;
                }
                if (firstSpeechMs < 0) firstSpeechMs = System.currentTimeMillis() - taskStarted;
                boolean whole;
                try { whole = VendorSpeech.speakAndWait(app, pages.get(i), moved); }
                catch (InterruptedException e) { return; }
                if (!whole || moved.now()) { settle(t, mode); return; }
            }
        });
    }

    // ---- reading an answer while the model is still writing it -----------------------------------------

    /** The first page of the running task's answer, read (or shown) while the model was still writing the rest:
     * earlyTask, its text, and the narration it belongs to (set together under earlyLock). */
    private final Object earlyLock = new Object();
    private volatile Ledger earlyTask;
    private volatile String early = "";
    private volatile int earlyId;
    private volatile long firstShownMs = -1;

    /**
     * The model's plain answer as it streams (AgentLoop.Drafts, 2026-11-06 overnight pass): what Speech.early gives —
     * the first page once the second has begun, and since 2026-11-09 the first whole sentence as soon as the model has
     * begun the next one, which is about a second in (a 60–150 character answer used to be read only once it was
     * whole: first speech median 5.4 s, 「嗯塔罗牌你了解吗」 3.3 s). It goes up and XiaoAi starts reading it while the
     * model writes the rest, which follows at finish (readOn). A one-sentence answer comes whole, as before.
     */
    private final AgentLoop.Drafts drafts = new AgentLoop.Drafts() {
        @Override public void text(Ledger t, String soFar) {
            if (earlyTask == t || cancelled(taskGen)) return;
            String page = Speech.early(soFar, Speech.PAGE_MAX);
            if (page == null) return;
            final int id;
            synchronized (earlyLock) {
                if (earlyTask == t) return; // its first page is already up; the rest comes at finish
                earlyTask = t;
                early = page;
                id = earlyId = narration.incrementAndGet();
            }
            if (firstShownMs < 0) firstShownMs = System.currentTimeMillis() - taskStarted;
            final int gen = taskGen;
            hud(t, "reading", page, "1/…");
            narrator.execute(() -> {
                VendorSpeech.Cancelled moved = () -> narration.get() != id || taskGen != gen || cancelled(gen);
                if (moved.now()) return;
                if (!spoken(t.source)) {
                    long until = android.os.SystemClock.uptimeMillis() + 2000 + page.length() * 200L;
                    while (android.os.SystemClock.uptimeMillis() < until && !moved.now()) android.os.SystemClock.sleep(250);
                    return;
                }
                if (firstSpeechMs < 0) firstSpeechMs = System.currentTimeMillis() - taskStarted;
                try { VendorSpeech.speakAndWait(app, page, moved); }
                catch (InterruptedException ignored) { }
            });
        }

        @Override public String read(Ledger t) {
            synchronized (earlyLock) { return earlyTask == t ? early : ""; }
        }
    };

    /** Streaming can be switched off without a new build: a file named stream-off in the bridge's external files
     * (adb shell touch /sdcard/Android/data/xyz.erapi0neer.xiaoaibridge/files/stream-off). */
    private boolean streaming() {
        try {
            java.io.File dir = app.getExternalFilesDir(null);
            return dir == null || !new java.io.File(dir, "stream-off").exists();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The finished answer after its first page was read early: the pages after it, in the same narration, so the
     * user's 停 or a new request ends them like any reading. The answer's text starts with the page read (cleaned);
     * when it does not (the route failed part way and the other one answered anew), it is read from its start. */
    private void readOn(Ledger t, String mode, String read) {
        String all = Speech.clean(t.reply);
        boolean follows = all.startsWith(read);
        List<String> rest = Speech.pages(follows ? all.substring(read.length()) : all, Speech.PAGE_MAX);
        int offset = follows ? 1 : 0, total = rest.size() + offset;
        final int id = earlyId, gen = taskGen;
        if (narration.get() != id) {
            // the reading was stopped (停, the user woke XiaoAi): the page on screen settles, nothing more is read
            hud(t, mode, read, total > 1 ? "1/" + total : "");
            return;
        }
        narrator.execute(() -> {
            VendorSpeech.Cancelled moved = () -> narration.get() != id || taskGen != gen;
            if (rest.isEmpty()) { if (!moved.now()) hud(t, mode, read, ""); return; }
            for (int i = 0; i < rest.size(); i++) {
                if (moved.now()) { settle(t, mode); return; }
                boolean last = i == rest.size() - 1;
                String label = (offset + i + 1) + "/" + total;
                hud(t, last ? mode : "reading", rest.get(i), label);
                if (!spoken(t.source)) {
                    long until = android.os.SystemClock.uptimeMillis() + 2000 + rest.get(i).length() * 200L;
                    while (!last && android.os.SystemClock.uptimeMillis() < until && !moved.now()) android.os.SystemClock.sleep(250);
                    continue;
                }
                boolean whole;
                try { whole = VendorSpeech.speakAndWait(app, rest.get(i), moved); }
                catch (InterruptedException e) { return; }
                if (!whole || moved.now()) { settle(t, mode); return; }
            }
        });
    }

    /** A reading that ended part way (the user spoke to XiaoAi, 停, a new request): the page still on screen becomes a
     * finished answer and fades, instead of saying 朗读 for good. Another task's panel is left alone. */
    private void settle(Ledger t, String mode) {
        Object[] s = shown;
        if (s != null && s[0] == t && "reading".equals(s[1])) hud(t, mode, (String) s[2], (String) s[3]);
    }

    /** Ends a reading of a long answer, if one is going on: the next page is not read. */
    public void stopReading() { narration.incrementAndGet(); }

    /** What XiaoAi itself did for the request running now (its instruction names), for the hand-back when 星仪 is
     * offline. */
    private volatile Set<String> runningDid = Collections.emptySet();

    /**
     * 星仪 could not reach its model on any route (AgentLoop.offline) and did nothing: the sentence goes back to XiaoAi
     * rather than the TV saying 「没做成」 — XiaoAi answers it itself (its own reply is not cut: the bridge knows our
     * hand-offs), or, when XiaoAi already acted on it (volume, playback…), that is said. True when it took the finish.
     */
    private boolean handBack(Ledger t) {
        if (!AgentLoop.handsBack(t)) return false;
        boolean acted = false;
        for (String n : runningDid) if (!n.startsWith("Template.")) acted = true;
        // a first page read while the stream still came (it broke off after) stops here: XiaoAi speaks next
        stopReading();
        synchronized (earlyLock) { earlyTask = null; early = ""; }
        // 原因按失败的真实类别说（AgentLoop.offlineReason）：以前一律是「星仪暂时连不上网络」，2026-11-14 13:12 网关只是
        // M3.1-Flash 满了（429），用户听成断网、去修电视的 Wi-Fi。只有真的是网络时才说「网络」。
        String why = AgentLoop.offlineReason(t.error);
        // 用户那一侧的这句话写进 t.reply：handBack 在 l.run 里面跑，run 返回以后 store.remember / history 才落盘，所以
        // 这里定下来的就是历史、transcript、recall_history 里留的那句（2026-11-14 13:12：原来留的是原始路由错误）。
        if (acted) {
            String say = why + "，小爱已经处理了这句话。";
            handedBackAs(t, say);
            VendorSpeech.speak(app, say);
            return true;
        }
        noteXiaoaiAsk(t.utterance);
        final String handedSay = why + "，这句交给小爱回答。", later = why + "，请稍后再说一遍。";
        final int gen = currentGeneration();
        // XiaoAi's text entry reports a refusal in its callback (「上一条请求正在提交」「小爱文字入口不可用」…), 300 ms after
        // dispatch on the main thread. Wait for it here — the record is written as soon as this returns — but only an
        // explicit refusal counts as one (review 2026-11-16): a main thread busy past the wait is not XiaoAi saying no,
        // and treating it so told the user 「请稍后再说一遍」 while XiaoAi was answering, and opened the echo gate so the
        // bridge could send XiaoAi's handling of the sentence back to 星仪. The slot decides, once, who was first.
        final java.util.concurrent.atomic.AtomicReference<String> slot = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            xyz.erapi0neer.xingyi.Legacy.askXiaoai(app, t.utterance, m -> {
                String v = m == null ? "" : m;
                // the wait already gave up: a refusal now is still the user's to hear (the record keeps 交给小爱)
                if (!slot.compareAndSet(null, v) && !handedOff(v)) lateRefusal(t, later, gen);
                done.countDown();
            });
        } catch (RuntimeException e) {
            slot.compareAndSet(null, ""); // dispatch itself failed: XiaoAi never got the sentence
            done.countDown();
        }
        try {
            done.await(VERDICT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String verdict = slot.compareAndSet(null, GAVE_UP) ? null : slot.get();
        if (verdict == null || handedOff(verdict)) {
            // handed off, or no word yet: XiaoAi answers the sentence itself, and 星仪 says nothing over it — one TTS
            // player, two voices (the 11-16 version spoke here too). The echo gate stays: XiaoAi may be on it.
            handedBackAs(t, handedSay);
            return true;
        }
        // refused: XiaoAi never got the sentence, so the gate goes (「再说一遍」 must not be swallowed as our own
        // hand-off) and the user hears, the panel shows and the record keeps the one sentence that is true
        clearEchoGate();
        handedBackAs(t, later);
        if (!cancelled(gen) && currentGeneration() == gen) VendorSpeech.speak(app, later);
        return true;
    }

    /** How long handBack waits for XiaoAi's verdict: the callback comes 300 ms after dispatch on an idle main thread. */
    static final long VERDICT_MS = 1500;
    /** The slot's mark that handBack stopped waiting before XiaoAi's verdict came (compared by reference). */
    private static final String GAVE_UP = new String("gave-up");

    private static boolean handedOff(String verdict) { return verdict != null && verdict.startsWith("已转交"); }

    /** The sentence the user gets for a task handed back: recorded on the task and shown on its panel. */
    private void handedBackAs(Ledger t, String said) {
        t.reply = said;
        t.updatedAt = System.currentTimeMillis();
        hud(t, "done", said);
    }

    /** XiaoAi refused after handBack had stopped waiting (its main thread was busy): it never got the sentence. The
     * gate goes and the user is told — unless a newer request or a stop came meanwhile; the panel changes only while
     * it still shows this task. The record, already written, keeps 「这句交给小爱回答」. */
    private void lateRefusal(Ledger t, String later, int gen) {
        clearEchoGate();
        if (cancelled(gen) || currentGeneration() != gen) return;
        if (task == t) hud(t, "done", later);
        VendorSpeech.speak(app, later);
    }

    /** The hand-off failed: forget the echo gate left by noteXiaoaiAsk, so the user's next try is heard. Only on an
     * explicit refusal — while XiaoAi may be answering, the gate is what keeps its handling out of 星仪. */
    private static void clearEchoGate() { xiaoaiAsk = ""; xiaoaiAskAt = 0; }

    /** The panel's last task, mode and reply, so a question queued behind it can add its 接下来 line. */
    private volatile Object[] shown;

    private void hud(Ledger t, String mode, String reply) { hud(t, mode, reply, ""); }

    /** page: 「2/4」 while a long answer is read page by page, else "". */
    private void hud(Ledger t, String mode, String reply, String page) {
        shown = new Object[]{t, mode, reply, page};
        Eyes eyes = Eyes.get();
        if (eyes == null || eyes.hud() == null) return;
        // 星仪's own screen already shows the task; the floating panel would only cover it.
        if (AgentClient.SCREEN.equals(Eyes.foregroundPackage())) { eyes.hud().hideSoon(); return; }
        List<String[]> steps = new ArrayList<>();
        List<String[]> trace = new ArrayList<>();
        String goal;
        JSONObject card;
        synchronized (t) {
            for (Ledger.Step s : t.steps) steps.add(new String[]{s.text, s.status});
            goal = t.goal.isEmpty() ? "“" + t.utterance + "”" : t.goal;
            card = t.card;
            trace(t, trace);
        }
        // while the work runs the last action is not in the ledger yet: it is what 星仪 is doing this second
        if ("running".equals(mode) && !lastAction.isEmpty()
                && (trace.isEmpty() || !lastAction.equals(trace.get(trace.size() - 1)[0]))) {
            trace.add(new String[]{lastAction, "live"});
        }
        // a plan already fills the panel: one exchange above it, two above a plain question
        eyes.hud().show(new Hud.State(mode, goal, steps, lastAction, reply, before(t, steps.isEmpty() ? 2 : 1), next(), page, card, trace));
    }

    /** The last three things the task did, oldest first: {what, ok | denied | failed}. The panel shows them as the
     * thinking chain under the step they belong to (2026-11-08: 「思维链什么的也要优化」 — the panel said what it planned
     * and nothing of what it had done, seen or was trying). */
    private static void trace(Ledger t, List<String[]> out) {
        int from = Math.max(0, t.actions.size() - 3);
        for (int i = from; i < t.actions.size(); i++) {
            JSONObject a = t.actions.get(i);
            String what = a.optString("what", "");
            if (what.isEmpty()) continue;
            String status = a.optString("status", "");
            out.add(new String[]{what, a.optBoolean("ok") ? "ok"
                    : "denied".equals(status) || "rejected".equals(status) ? "denied" : "failed"});
        }
    }

    /** The panel again with what is queued now: the running task's own state, unchanged. */
    private void refreshPanel() {
        Object[] s = shown;
        if (s != null) hud((Ledger) s[0], (String) s[1], (String) s[2], (String) s[3]);
    }

    private static final class Exchange {
        final String id, said, reply;
        final long at;
        Exchange(String id, String said, String reply, long at) { this.id = id; this.said = said; this.reply = reply; this.at = at; }
    }

    /** A finished exchange, for the thread above the next task (2026-11-05 on the TV: 「每次会覆盖上一次的结果」 —
     * answering 星仪's question replaced the question on screen, and the next request the answer). */
    private void exchanged(Ledger t) {
        String said = t.utterance == null ? "" : t.utterance.trim(), reply = t.reply == null ? "" : t.reply.trim();
        if (said.isEmpty() || reply.isEmpty()) return;
        synchronized (exchanges) {
            exchanges.addLast(new Exchange(t.id, said, reply, System.currentTimeMillis()));
            while (exchanges.size() > 3) exchanges.removeFirst();
        }
    }

    /** What was said and answered just before the task on screen: the last {@code max} exchanges of the last 3 minutes,
     * oldest first, never the task itself. */
    private List<String[]> before(Ledger current, int max) {
        long now = System.currentTimeMillis();
        List<String[]> out = new ArrayList<>();
        synchronized (exchanges) {
            for (Exchange e : exchanges)
                if (now - e.at <= THREAD_MS && !e.id.equals(current.id)) out.add(new String[]{e.said, e.reply});
        }
        while (out.size() > max) out.remove(0);
        return out;
    }

    /** The first question waiting behind the running task, or "". */
    private String next() {
        synchronized (waiting) {
            for (Object[] w : waiting) if (!cancelled((int) w[0])) return (String) w[1];
        }
        return "";
    }

    /** A short spoken acknowledgement, only when the task is still running 2.5 s after the plan:
     * quick tasks (音量调到12: plan to result in 4 s) just say the result, and a task that stopped to ask for
     * consent (Ledger.State.open() is true there too) must not be talked over. */
    private void acknowledge(Ledger t) {
        if (t.steps.size() < 2 || !spoken(t.source)) return;
        String goal = t.goal.replaceAll("[。！!？?]+$", "");
        String say = goal.length() <= 18 ? "好的，" + goal + "。" : "好的，马上办。";
        ack.postDelayed(() -> { if (task == t && t.state == Ledger.State.RUNNING) speak(t, say); }, 2500);
    }

    /** Waits until /proc/meminfo reports at least minKb available, the deadline passes, or {@code giveUp} says so. */
    private static void waitForMemory(long minKb, long maxMs, java.util.function.BooleanSupplier giveUp) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end && !giveUp.getAsBoolean()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("MemAvailable:")) {
                        long kb = Long.parseLong(line.replaceAll("[^0-9]", ""));
                        if (kb >= minKb) return;
                        break;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                return;
            }
            Thread.sleep(1000);
        }
    }

    private void speak(Ledger t, String text) {
        if (text == null || text.isEmpty() || !spoken(t.source)) return;
        if (firstSpeechMs < 0) firstSpeechMs = System.currentTimeMillis() - taskStarted;
        VendorSpeech.speak(app, text);
    }

    /** Typed requests on the 星仪 screen are answered on screen; everything else came by voice. The phone's own
     * 「电视朗读」 switch off (phone-quiet) is the same: typed there, read on the TV, not read out loud. */
    private static boolean spoken(String source) {
        return !"ui".equals(source) && !"mcp".equals(source) && !"test".equals(source) && !"phone-quiet".equals(source);
    }

    /**
     * Whether a task leaves a trace in what 星仪 knows about the user: recent turns, last task, the 30-day history, habits
     * and the panel's thread. Not for checks run from the PC — source test (silent) and debug (audible) — which until
     * 2026-11-06 filled them: a night of silent test questions came back as 「你常查科技新闻、汇率和空气质量」.
     */
    static boolean traced(String source) { return !"test".equals(source) && !"debug".equals(source); }

    private void ensureEyes(TvTools tools) {
        if (Eyes.get() != null || eyesTried) return;
        eyesTried = true;
        String component = new ComponentName(app, Eyes.class).flattenToShortString();
        try {
            tools.adb.call(a -> { a.enableAccessibility(component); return null; });
            for (int i = 0; i < 30 && Eyes.get() == null; i++) Thread.sleep(100);
        } catch (Exception ignored) {
            // Without the service, screens are read through uiautomator dumps and taps.
        }
    }

    private JSONObject environment(TvTools tools, String said) throws JSONException {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        JSONObject o = new JSONObject()
                .put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(new Date()));
        String fg = tools.foreground();
        // the home screen is said 桌面 (the model called it 「桌面和 Orrery」 as if they were two places)
        o.put("foreground", fg.isEmpty() ? "未知" : HOME_PKG.equals(fg) ? "桌面（Orrery，" + fg + "）" : tools.label(fg) + "（" + fg + "）");
        o.put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + (am.isStreamMute(AudioManager.STREAM_MUSIC) ? "，已静音" : ""));
        MediaSessions.Session s = tools.nowPlaying();
        // with where the track is (「，放到 2:13」): whether a 重播 or a skip happened is read from it (MediaSessions.at)
        if (s != null) o.put("now_playing", tools.label(s.pkg) + " " + s.describe() + MediaSessions.at(s, android.os.SystemClock.elapsedRealtime()));
        o.put("apps", new JSONArray(tools.apps().keySet()));
        android.os.PowerManager pm = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
        o.put("screen", pm == null || pm.isInteractive() ? "开着" : "关着（电视在待机）");
        // a question about the screen gets the screen with it (through Eyes only: a uiautomator dump takes seconds)
        if ((pm == null || pm.isInteractive()) && Dispatch.aboutScreen(said) && Eyes.get() != null) {
            try {
                String seen = tools.read().text();
                o.put("on_screen", seen.length() > 1500 ? seen.substring(0, 1500) + "…" : seen);
            } catch (Exception ignored) {
                // the model reads it itself
            }
        }
        // where the TV is (Xiaomi's own setting, the one its weather uses): 「明天天气怎么样」 needs no city any more
        String place = place();
        if (place != null) o.put("place", place);
        // the list just read out (B站热门, 百度热搜): 「放第二个」 is its second item (2026-11-06)
        JSONObject readOut = TvTools.lastList(System.currentTimeMillis());
        if (readOut != null) o.put("last_list", readOut);
        QuickCommand.here(placeNames());
        // 农历, a term or festival today or tomorrow, a holiday or a moved working day (Almanac)
        Calendar now = Calendar.getInstance();
        String calendar = Almanac.environment(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        if (calendar != null) o.put("calendar", calendar);
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (hour >= 23 || hour < 5) o.put("quiet", "深夜，家里人可能在睡觉：回答说短一点，不要主动点亮屏幕或大声放东西");
        else if (hour < 7) o.put("quiet", "清早，家里人可能还在睡：回答说短一点，不要主动点亮屏幕或大声放东西");
        // words the recognition may have written another way: what earlier turns said and answered (2026-11-07)
        try {
            String heard = Heard.line(Heard.hints(said, heardEarlier()));
            if (!heard.isEmpty()) o.put("heard_like", heard);
        } catch (Exception ignored) {
            // a hint must never break a request
        }
        return o;
    }

    /**
     * What earlier turns said and answered, newest first: the last three hours (recent.json, 「刚才」 under half an hour
     * ago) and then the 30-day log (history.json, by its date). At most 200 entries — the environment is built on every
     * request, and the TV has 2 GB of RAM.
     */
    private List<String[]> heardEarlier() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray recent = store.recent();
            for (int i = recent.length() - 1; i >= 0 && out.size() < 200; i--) {
                JSONObject r = recent.optJSONObject(i);
                if (r == null) continue;
                long ago = r.optLong("minutes_ago");
                out.add(new String[]{r.optString("said"), r.optString("replied"), ago < 30 ? "刚才" : ago + "分钟前"});
            }
            JSONArray items = store.history().optJSONArray("items");
            if (items == null) return out;
            SimpleDateFormat day = new SimpleDateFormat("M月d日", Locale.CHINA);
            day.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            for (int i = items.length() - 1; i >= 0 && out.size() < 200; i--) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                out.add(new String[]{it.optString("said"), it.optString("replied"), day.format(new Date(it.optLong("at")))});
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The TV's place by each of its names (「上海徐汇」「上海」「徐汇」), for the quick weather. */
    List<String> placeNames() {
        List<String> out = new ArrayList<>();
        try {
            android.content.ContentResolver cr = app.getContentResolver();
            String user = android.provider.Settings.System.getString(cr, "mitv_user_location");
            String json = Place.userSet(user) ? user : android.provider.Settings.System.getString(cr, "mitv_location");
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            String full = Place.of(user, android.provider.Settings.System.getString(cr, "mitv_location"));
            if (full != null) out.add(full);
            for (String k : new String[]{"city", "district"}) {
                String v = o.optString(k).trim();
                if (v.length() >= 2 && !"null".equals(v)) out.add(v);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 「上海徐汇」 from Settings.System mitv_user_location / mitv_location, or null. */
    String place() {
        try {
            android.content.ContentResolver cr = app.getContentResolver();
            return Place.of(android.provider.Settings.System.getString(cr, "mitv_user_location"),
                    android.provider.Settings.System.getString(cr, "mitv_location"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private synchronized Contract contract() throws IOException, JSONException {
        if (contract == null) {
            try (InputStream in = app.getAssets().open("agent/contract.json")) { contract = new Contract(read(in)); }
        }
        return contract;
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Private files: the last planned task, recent exchanges, and the user's profile (aliases, facts). */
    static final class Store {
        private final File dir;
        Store(File dir) { this.dir = dir; dir.mkdirs(); }

        synchronized Ledger lastTask() {
            try { return Ledger.fromJson(new JSONObject(load("last_task.json"))); } catch (Exception e) { return null; }
        }

        /** A file of the store as it is, or null (for the debug receiver's backups). */
        synchronized String raw(String name) {
            try { return load(name); } catch (IOException e) { return null; }
        }

        /** Takes the checks' entries out of the history and the recent turns (TestTraces); each file is copied to
         * <name>.before-forget first. */
        synchronized String forgetChecks(long since, Set<String> sources) {
            List<JSONObject> perf = perfLines();
            int[] h = {0}, r = {0};
            try {
                String hs = raw("history.json");
                if (hs != null) {
                    save("history.json.before-forget", hs);
                    JSONObject log = new JSONObject(hs);
                    JSONArray items = log.optJSONArray("items");
                    if (items != null) {
                        log.put("items", TestTraces.without(items, perf, since, sources, h));
                        save("history.json", log.toString());
                    }
                }
                String rs = raw("recent.json");
                if (rs != null) {
                    save("recent.json.before-forget", rs);
                    save("recent.json", TestTraces.without(new JSONArray(rs), perf, since, sources, r).toString());
                }
            } catch (Exception e) {
                return "error " + e.getClass().getSimpleName();
            }
            return "history " + h[0] + " removed, recent " + r[0] + " removed";
        }

        /** The task in progress, rewritten at each step; survives the process being killed. */
        synchronized void saveRunning(Ledger t, int resumes) {
            try { save("running.json", new JSONObject().put("task", t.toJson()).put("resumes", resumes)
                    .put("at", System.currentTimeMillis()).toString()); }
            catch (Exception ignored) { }
        }

        synchronized JSONObject running() {
            try { return new JSONObject(load("running.json")); } catch (Exception e) { return null; }
        }

        synchronized void clearRunning() { new File(dir, "running.json").delete(); }

        /** perf.jsonl as parsed lines, read from the file once: state() asks for them on every XiaoAi request and every
         * 400 ms while the 星仪 app polls, and reading the file twice per call was the cost (2026-11-05 audit). */
        private List<JSONObject> perfLines;

        private List<JSONObject> perfLines() {
            if (perfLines != null) return perfLines;
            perfLines = new ArrayList<>();
            try {
                for (String s : load("perf.jsonl").split("\n")) {
                    if (s.trim().isEmpty()) continue;
                    try { perfLines.add(new JSONObject(s)); } catch (JSONException broken) { /* skip a torn line */ }
                }
            } catch (IOException e) { /* no file yet: this is the first task */ }
            return perfLines;
        }

        /** One line per finished task, last 300 kept: this is where 「性能上」 is answered from. */
        synchronized void perf(JSONObject line) {
            try {
                List<JSONObject> lines = perfLines();
                lines.add(line);
                while (lines.size() > 300) lines.remove(0);
                StringBuilder out = new StringBuilder();
                for (JSONObject o : lines) out.append(o.toString()).append('\n');
                save("perf.jsonl", out.toString());
            } catch (Exception ignored) { }
        }

        /** The summaries of the last n tasks, oldest first. */
        synchronized List<JSONObject> perfSummaries(int n) {
            List<JSONObject> out = new ArrayList<>();
            List<JSONObject> lines = perfLines();
            for (int i = lines.size() - 1; i >= 0 && out.size() < n; i--) {
                JSONObject summary = lines.get(i).optJSONObject("summary");
                if (summary != null && traced(lines.get(i).optString("source"))) out.add(0, summary);
            }
            return out;
        }

        /** The last n tasks, oldest first, one short line each: what was said and where its time went, so a slow
         * request can be found on the status page without reading the private file. */
        synchronized JSONArray perfRecent(int n) {
            JSONArray out = new JSONArray();
            try {
                List<JSONObject> lines = perfLines(), kept = new ArrayList<>();
                for (int i = lines.size() - 1; i >= 0 && kept.size() < n; i--)
                    if (lines.get(i).optJSONObject("summary") != null) kept.add(0, lines.get(i));
                for (JSONObject o : kept) {
                    JSONObject s = o.getJSONObject("summary");
                    out.put(new JSONObject().put("said", o.optString("utterance")).put("quick", o.optBoolean("quick"))
                            .put("wall_ms", s.optLong("wall_ms")).put("rounds", s.optInt("rounds"))
                            .put("first_round_ms", s.optLong("first_round_ms")).put("first_speech_ms", s.optLong("first_speech_ms", -1))
                            .put("first_shown_ms", s.optLong("first_shown_ms", -1))
                            .put("slowest_tool", s.optString("slowest_tool")).put("slowest_tool_ms", s.optLong("slowest_tool_ms")));
                }
            } catch (Exception ignored) { }
            return out;
        }

        /** Private; exported only through the DUMP-protected debug receiver. */
        synchronized void saveTranscript(JSONArray messages) {
            try { save("last_transcript.json", messages.toString(1)); } catch (Exception ignored) { }
        }

        synchronized String transcript() {
            try { return load("last_transcript.json"); } catch (Exception e) { return "[]"; }
        }

        /** The ring of the last real tasks' transcripts (files/agent/transcripts/<startedAt>-<id>.json): the whole model
         * conversation of each of the last 12 tasks, so a failure can still be read hours later (2026-11-09: a task that
         * took 105 s and 11 rounds was overwritten by the next request before anyone looked at it). Never throws: a full
         * disk must not break a task. */
        synchronized void saveRing(Ledger t, JSONArray messages, long wallMs) {
            try {
                File ring = new File(dir, "transcripts");
                ring.mkdirs();
                save(ring, TranscriptRing.fileName(t.startedAt, t.id), TranscriptRing.entry(t, messages, wallMs).toString(1));
                String[] listed = ring.list();
                Map<String, Long> sizes = new HashMap<>();
                for (String n : listed == null ? new String[0] : listed) sizes.put(n, new File(ring, n).length());
                for (String old : TranscriptRing.toDelete(sizes, TranscriptRing.MAX_COUNT, TranscriptRing.MAX_BYTES))
                    new File(ring, old).delete();
            } catch (Exception ignored) { }
        }

        /** The ring's entries' texts, newest first, for the debug receiver's export (it never touches the files itself). */
        synchronized List<String> ring() {
            List<String> out = new ArrayList<>();
            try {
                File ring = new File(dir, "transcripts");
                String[] listed = ring.list();
                List<String> names = TranscriptRing.newestFirst(listed == null ? new ArrayList<String>() : Arrays.asList(listed));
                for (String n : names) {
                    try { out.add(load(ring, n)); } catch (IOException torn) { /* a half-written file: skip it */ }
                }
            } catch (Exception ignored) { }
            return out;
        }

        /** How much of an earlier answer (not the last) recent_turns carries. */
        static final int RECENT_BRIEF = 150;

        synchronized JSONArray recent() {
            JSONArray out = new JSONArray();
            try {
                JSONArray all = new JSONArray(load("recent.json"));
                long now = System.currentTimeMillis();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject r = all.getJSONObject(i);
                    long ago = (now - r.optLong("at")) / 60000;
                    if (ago > 180) continue;
                    // the last answer whole (「第三点再说说」 needs it), the ones before it in brief
                    String replied = i == all.length() - 1 ? r.optString("replied") : Speech.brief(r.optString("replied"), RECENT_BRIEF);
                    out.put(new JSONObject().put("said", r.optString("said")).put("replied", replied).put("minutes_ago", ago));
                }
            } catch (Exception ignored) { }
            return out;
        }

        synchronized void remember(Ledger t) {
            try {
                // A question asked without a plan (「放那个」→「放哪一首？」) is the task the answer continues, so it is
                // saved as last_task.json too — otherwise the answer's previous_task is some older task.
                if (t.planned() || t.state == Ledger.State.AWAITING_USER) save("last_task.json", t.toJson().toString());
                JSONArray all;
                try { all = new JSONArray(load("recent.json")); } catch (Exception e) { all = new JSONArray(); }
                all.put(new JSONObject().put("said", t.utterance).put("replied", t.reply).put("state", t.state.name()).put("at", t.updatedAt));
                JSONArray kept = new JSONArray();
                for (int i = Math.max(0, all.length() - 6); i < all.length(); i++) kept.put(all.get(i));
                save("recent.json", kept.toString());
            } catch (Exception ignored) { }
        }

        synchronized JSONObject profile() {
            try { return new JSONObject(load("profile.json")); }
            catch (Exception e) {
                try {
                    JSONObject p = new JSONObject()
                            .put("aliases", new JSONObject().put("星辰旅行者", "星辰亻旅行者").put("星辰人旅行者", "星辰亻旅行者"))
                            .put("facts", new JSONArray().put("“星辰旅行者”“星辰人旅行者”指 B 站 UP 主“星辰亻旅行者”（中间有单人旁“亻”），搜索时用带“亻”的写法。"));
                    save("profile.json", p.toString(1));
                    return p;
                } catch (Exception ignored) { return new JSONObject(); }
            }
        }

        synchronized void saveProfile(JSONObject p) throws IOException, JSONException { save("profile.json", p.toString(1)); }

        /** What 星仪 learned from use; an empty object when the file is missing or unreadable, so nothing ever fails. */
        synchronized JSONObject habits() {
            try { return new JSONObject(load("habits.json")); } catch (Exception e) { return new JSONObject(); }
        }

        synchronized void saveHabits(JSONObject h) throws IOException, JSONException { save("habits.json", h.toString(1)); }

        synchronized JSONObject notes() {
            try { return new JSONObject(load("notes.json")); } catch (Exception e) { return new JSONObject(); }
        }

        synchronized void saveNotes(JSONObject notes) throws IOException, JSONException { save("notes.json", notes.toString(1)); }

        synchronized JSONObject appNotes() {
            try { return new JSONObject(load("app_notes.json")); } catch (Exception e) { return new JSONObject(); }
        }

        synchronized void saveAppNotes(JSONObject notes) throws IOException, JSONException { save("app_notes.json", notes.toString(1)); }

        synchronized JSONObject history() {
            try { return new JSONObject(load("history.json")); } catch (Exception e) { return new JSONObject(); }
        }

        /** Best-effort, like perf: a history entry that cannot be written must never break a task. */
        synchronized void addHistory(String said, String replied, String state, String playing, String app) {
            try {
                JSONObject log = history();
                History.add(log, said, replied, state, playing, app, System.currentTimeMillis());
                save("history.json", log.toString());
            } catch (Exception ignored) { }
        }

        Map<String, String> aliases() {
            Map<String, String> out = new HashMap<>();
            JSONObject a = profile().optJSONObject("aliases");
            if (a != null) for (Iterator<String> it = a.keys(); it.hasNext(); ) { String k = it.next(); out.put(k, a.optString(k)); }
            return Grounding.withBuiltins(out);
        }

        String profileText() {
            JSONArray f = profile().optJSONArray("facts");
            StringBuilder s = new StringBuilder();
            for (int i = 0; f != null && i < f.length(); i++) s.append("- ").append(f.optString(i)).append('\n');
            return s.toString().trim();
        }

        private String load(String name) throws IOException { return load(dir, name); }

        private String load(File dir, String name) throws IOException {
            try (InputStream in = new FileInputStream(new File(dir, name))) { return read(in); }
        }

        private void save(String name, String text) throws IOException { save(dir, name, text); }

        private void save(File dir, String name, String text) throws IOException {
            File tmp = new File(dir, name + ".tmp"), dst = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            if (!tmp.renameTo(dst)) throw new IOException("rename failed");
        }
    }
}
