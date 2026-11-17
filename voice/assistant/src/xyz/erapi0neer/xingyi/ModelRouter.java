package xyz.erapi0neer.xingyi;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Chooses the model route for one agent turn.
 *
 * The official MiniMax API is tried first. The user's gateway answers only after a route-level failure of
 * the official API: insufficient balance (402/1008), authentication (401/1004), HTTP 5xx, timeouts and
 * transport failures. Parameter errors, content filtering, rate limits, malformed replies and anything a
 * tool does are reported as they are and never retried on the other route.
 *
 * A balance or authentication failure pauses the official route for ten minutes, for every turn and across
 * process restarts. The first request after the pause probes it again, so the assistant returns to the
 * official API by itself after the account is topped up. Within one turn, once the gateway has answered,
 * later rounds of the same tool loop stay on the gateway.
 *
 * {@link #status()} and {@link #setListener} tell the UI which route answered and why.
 */
public final class ModelRouter implements AgentHarness.Model, AgentHarness.RouteAware {
    public static final String OFFICIAL = "official", GATEWAY = "gateway";
    static final long PAUSE_MS = 10 * 60_000L;
    /** The official attempt leaves time for the fallback; without a fallback it gets the full minute. */
    static final long OFFICIAL_BUDGET_MS = 30_000L, FULL_BUDGET_MS = 60_000L;
    /** The third leg cannot get a full minute: official 30 s + gateway 60 s + fallback 60 s is the whole 150 s task
     *  budget, so a slow fallback would leave the turn with no time to say what it did. */
    static final long FALLBACK_BUDGET_MS = 35_000L;

    /** One HTTP attempt on one route. */
    interface Endpoint {
        JSONObject complete(JSONArray messages, JSONArray tools, long budgetMillis) throws IOException;
        /** Streamed to sink when it is not null; an endpoint that cannot stream answers whole. */
        default JSONObject complete(JSONArray messages, JSONArray tools, long budgetMillis,
                xyz.erapi0neer.xingyi.agent.Sse.Sink sink) throws IOException {
            return complete(messages, tools, budgetMillis);
        }
        void cancel();
    }
    interface Clock { long now(); }

    /** Which route produced the latest reply and why. Immutable; safe on any thread. */
    public static final class Status {
        /** {@link #OFFICIAL}, {@link #GATEWAY}, or "" before the first reply. */
        public final String route;
        /** For example "official: ok" or "official: insufficient balance". */
        public final String reason;
        /** Each route's own state in the trace: the failing route's HTTP status/code as well as the pause deadline.
         * May be empty. */
        public final String detail;
        /** One short Chinese line for a status bar, for example "备用网关应答 · 官方余额不足". */
        public final String label;
        /** Wall-clock time of the reply in milliseconds. */
        public final long time;
        /** Official HTTP attempts made by this process so far. */
        public final int officialAttempts;

        Status(String route, String reason, String detail, String label, long time, int officialAttempts) {
            this.route = route; this.reason = reason; this.detail = detail; this.label = label;
            this.time = time; this.officialAttempts = officialAttempts;
        }
        /** Compact form used in the harness trace. Contains no prompt, key or server text. */
        @Override public String toString() {
            if (route.isEmpty()) return "none";
            return route + " · " + reason + (detail.isEmpty() ? "" : " (" + detail + ")")
                    + " · official attempts " + officialAttempts;
        }
    }
    /** Called on the model worker thread after every answered model call. */
    public interface Listener { void onRoute(Status status); }

    /** Latest status and official attempt count. Real turns share one board; diagnostics use their own. */
    static final class Board {
        private volatile Status latest = new Status("", "", "", "尚未调用模型", 0, 0);
        private volatile Listener listener;
        private int attempts;
        synchronized int countAttempt() { return ++attempts; }
        synchronized int attempts() { return attempts; }
        Status latest() { return latest; }
        void publish(Status status) {
            latest = status;
            Listener observer = listener;
            if (observer != null) {
                try { observer.onRoute(status); } catch (RuntimeException ignored) { }
            }
        }
    }
    private static final Board BOARD = new Board();

    /** Which route answered the latest model call in this process, and why. */
    public static Status status() { return BOARD.latest(); }
    /** Registers one UI observer (or null). It is called on the model worker thread. */
    public static void setListener(Listener observer) { BOARD.listener = observer; }

    /** Pause state for the official route, shared by all turns and persisted in private preferences. */
    static final class Health {
        interface Store { long until(); String kind(); void save(long until, String kind); }
        private final Store store;
        private long until;
        private String kind;
        Health(Store store) { this.store = store; until = store.until(); kind = store.kind(); }

        /** The failure kind that paused the official route, or null when it may be tried. */
        synchronized RouteFailure.Kind paused(long now) {
            // A deadline further away than one pause means the clock moved backwards: probe again.
            if (until == 0 || now >= until || until - now > PAUSE_MS) return null;
            try { return RouteFailure.Kind.valueOf(kind); } catch (RuntimeException unknown) { return null; }
        }
        synchronized long until() { return until; }
        /** The pause ran out: the official route failed before and has not answered since. */
        synchronized boolean expired(long now) { return until != 0 && now >= until && !kind.isEmpty(); }
        synchronized void pause(RouteFailure.Kind cause, long now) {
            until = now + PAUSE_MS; kind = cause.name(); store.save(until, kind);
        }
        synchronized void clear() {
            if (until == 0 && kind.isEmpty()) return;
            until = 0; kind = ""; store.save(0, "");
        }
    }
    private static Health shared;

    static synchronized Health health(Context context) {
        if (shared == null) {
            final SharedPreferences prefs = context.getApplicationContext().getSharedPreferences("model_route", Context.MODE_PRIVATE);
            shared = new Health(new Health.Store() {
                @Override public long until() { return prefs.getLong("official_paused_until", 0); }
                @Override public String kind() { return prefs.getString("official_pause_kind", ""); }
                @Override public void save(long until, String kind) {
                    prefs.edit().putLong("official_paused_until", until).putString("official_pause_kind", kind).apply();
                }
            });
        }
        return shared;
    }

    private final Endpoint official, gateway;
    /** The gateway's strong model, for the rounds of a task that is struggling (AgentLoop's escalation); null
     * without a gateway. gpt-5.4 through calibrate.mjs (2026-11-05): Flash first, gpt-5.4 from round 5 or after two
     * failures. Tried on claude-opus-5-5 for a few hours on 2026-11-16 and reverted with the rest of that switch. */
    private Endpoint strong;
    public static final String STRONG_MODEL = "gpt-5.4";
    /**
     * Another MiniMax model on the same gateway, tried once when the gateway answers that it cannot serve the usual
     * model right now (429 or 5xx). 2026-11-14 13:12 to past 16:00: the gateway said 429 scheduler_no_capacity for
     * MiniMax-M3.1-Flash on every call while the official account had no balance, so 星仪 answered nothing that needed
     * a model for hours although M78 on the same gateway answered in 2.5 s. The M78 calibration (build/cal-m78.txt)
     * ran 18 scored scenarios, 17 as expected; rememberfact claimed the fact without calling remember, and song and
     * complain carried no expectation. A full run on 11-16 again failed rememberfact (198 scenarios, 189 ok).
     * Package-private so the route contract can put a fake here; null without a gateway.
     */
    Endpoint fallback;
    /**
     * Candidates tried in order, first one that is not the gateway's own model. M3.1-Flash is deliberately absent:
     * the gateway has answered 429 scheduler_no_capacity for it since 11-14 13:12, so putting it first would spend a
     * doomed round trip on every fallback. M78 rescued 11-14; M2.7 answered on the same gateway in 11.9 s.
     */
    static final String[] FALLBACK_CANDIDATES = { "MiniMax-M78", "MiniMax-M2.7" };
    /** The model this router falls back to, or null when the gateway already is every candidate. */
    String fallbackModel;
    /** The candidate that is not {@code gatewayModel}; null when there is none (the status page says so honestly). */
    static String fallbackModelFor(String gatewayModel) {
        if (gatewayModel == null) return null;
        for (String c : FALLBACK_CANDIDATES) if (!c.equalsIgnoreCase(gatewayModel)) return c;
        return null;
    }
    /** The name the fallback goes by in a failure sentence: 「…；备用模型也未能应答：…」. */
    static final String FALLBACK_NAME = "备用模型";
    private final Health health;
    private final Clock clock;
    private final Board board;
    private volatile boolean cancelled;
    /** The model that produced the latest reply: MiniMaxClient.MODEL until a route names a different one (the
     *  gateway may be on another model). The status page reads this instead of the constant. */
    private String activeModel = MiniMaxClient.MODEL;
    private String stickyReason, stickyDetail, stickyNote;
    private String lastRoute = "";

    /** Reads both routes from files/config.json. */
    private static final AtomicLong WARMED_AT = new AtomicLong();
    private static volatile String warmed = "";

    /** XiaoAi is still listening to the user: open the connection the next request will try first (the official
     * route while it may be tried, else the gateway), so its handshake is done before the request is. At most every
     * 20 s (the pool keeps an idle socket for minutes), off the caller's thread. */
    public static void warm(Context context) {
        long now = System.currentTimeMillis(), last = WARMED_AT.get();
        if (now - last < 20_000 || !WARMED_AT.compareAndSet(last, now)) return;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                GatewayConfig.Routes routes = GatewayConfig.loadRoutes(app);
                Health h = health(app);
                long at = System.currentTimeMillis();
                boolean officialFirst = routes.official != null
                        && (routes.gateway == null || (h.paused(at) == null && !h.expired(at)));
                boolean ok = MiniMaxClient.warm(officialFirst ? routes.official : routes.gateway);
                warmed = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(now)) + " "
                        + (officialFirst ? OFFICIAL : GATEWAY) + (ok ? " " + (System.currentTimeMillis() - at) + " ms" : " failed");
            } catch (Exception e) {
                warmed = "failed: " + e.getClass().getSimpleName();
            }
        }, "xingyi-warm");
        t.setDaemon(true);
        t.start();
    }

    /** The last warm-up, for the status page: time, route and how long the handshake took; empty before the first. */
    public static String lastWarm() { return warmed; }

    public static ModelRouter open(Context context) throws IOException {
        return open(GatewayConfig.loadRoutes(context), health(context), System::currentTimeMillis, BOARD);
    }

    /** The wiring open(Context) does, without a Context: the routes decide which endpoints exist, what the strong
     * model is and which model the fallback goes to. Package-private so the route contract can drive it with routes
     * built by hand — before 2026-11-16 nothing called open() at all, and deleting the whole fallback wiring left
     * ROUTE OK 27 green. */
    static ModelRouter open(GatewayConfig.Routes routes, Health health, Clock clock, Board board) throws IOException {
        ModelRouter r = new ModelRouter(
                routes.official == null ? null : new MiniMaxClient(routes.official, MiniMaxClient.OFFICIAL_NAME),
                routes.gateway == null ? null : new MiniMaxClient(routes.gateway, MiniMaxClient.GATEWAY_NAME),
                health, clock, board);
        // the official route's own model, so the first answer and get_status before any call name it, not the constant
        if (routes.official != null) r.activeModel = named(r.official);
        if (routes.gateway != null) r.strong = new MiniMaxClient(
                new GatewayConfig(routes.gateway.baseUrl, routes.gateway.apiKey, STRONG_MODEL), MiniMaxClient.GATEWAY_NAME);
        // same gateway, same key, another MiniMax model — the first candidate that is not the gateway's own model.
        // With the gateway on M78 this used to leave fallback null (the route the TV actually ran), so a 429 ended
        // the turn instead of trying M2.7.
        String fb = fallbackModelFor(routes.gateway == null ? null : routes.gateway.model);
        if (routes.gateway != null && fb != null) {
            r.fallback = new MiniMaxClient(
                    new GatewayConfig(routes.gateway.baseUrl, routes.gateway.apiKey, fb), FALLBACK_NAME);
            r.fallbackModel = fb;
        }
        return r;
    }

    /** A router whose results stay off the process's board and out of LAST_MODEL: self_check's probe. */
    public static ModelRouter detached(Context context) throws IOException {
        Health h = health(context);
        return open(GatewayConfig.loadRoutes(context), h, System::currentTimeMillis, new Board());
    }

    ModelRouter(Endpoint official, Endpoint gateway, Health health, Clock clock, Board board) {
        if (official == null && gateway == null) throw new IllegalArgumentException("no model route");
        this.official = official; this.gateway = gateway; this.health = health; this.clock = clock; this.board = board;
    }

    @Override public String lastRoute() { return lastRoute; }

    @Override public JSONObject next(JSONArray messages, JSONArray tools) throws Exception { return next(messages, tools, null); }

    /** One round on the usual route, its answer streamed to sink when sink is not null. A route that fails part way
     * through and is followed by the other one streams again from the start: sink sees the text begin anew. */
    public JSONObject next(JSONArray messages, JSONArray tools, xyz.erapi0neer.xingyi.agent.Sse.Sink sink) throws Exception {
        checkCancelled();
        // reason/detail are short English for traces; note is the Chinese state of the official route.
        String reason, detail, note;
        RouteFailure officialFailure = null;
        RouteFailure.Kind pause;
        if (official == null) {
            reason = "official: not configured"; detail = ""; note = "未配置";
        } else if (stickyReason != null) {
            reason = stickyReason; detail = stickyDetail; note = stickyNote;
        } else if (gateway != null && (pause = health.paused(clock.now())) != null) {
            // Without a gateway there is nothing to save by skipping the official route, so it is always tried.
            String until = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(health.until()));
            reason = "official: " + pause.reason; detail = "paused until " + until;
            note = pause.chinese + "，" + until + " 后重试";
        } else if (gateway != null && health.expired(clock.now())) {
            // The pause ran out, but the account most likely has the same problem still (2026-11-05: insufficient balance
            // since 11-03; every inline retry cost the user ~0.7 s before the gateway answered). Ask off the user's path.
            probeOfficial();
            reason = "official: probing in the background"; detail = ""; note = "后台重新检查中";
        } else {
            board.countAttempt();
            try {
                JSONObject reply = official.complete(messages, tools, gateway == null ? FULL_BUDGET_MS : OFFICIAL_BUDGET_MS, sink);
                activeModel = named(official);
                health.clear();
                publish(OFFICIAL, "official: ok", "", "官方 MiniMax 应答");
                return reply;
            } catch (RouteFailure failure) {
                checkCancelled();
                if (!failure.kind.routeLevel || gateway == null) throw failure;
                if (failure.kind.pausesOfficial) health.pause(failure.kind, clock.now());
                officialFailure = failure;
                reason = "official: " + failure.kind.reason; detail = failure.detail(); note = failure.kind.chinese;
            }
        }
        checkCancelled();
        stickyReason = reason; stickyDetail = detail; stickyNote = note;
        try {
            JSONObject reply = gateway.complete(messages, tools, FULL_BUDGET_MS, sink);
            activeModel = named(gateway);
            publish(GATEWAY, reason, detail, "备用网关应答 · 官方" + note);
            return reply;
        } catch (RouteFailure failure) {
            checkCancelled();
            String before = officialFailure != null ? officialFailure.getMessage()
                    : official == null ? "未配置官方 API" : MiniMaxClient.OFFICIAL_NAME + " " + note;
            String both = before + "；备用网关也未能应答：" + failure.getMessage();
            // The gateway answered, so the network is fine; it only cannot serve the usual model now (429 no capacity,
            // 5xx model unavailable). Another model on it may: one try. Not after a timeout (that would double a
            // minute's wait), not after a network or key failure (the same host and key would fail the same way).
            boolean busy = failure.kind == RouteFailure.Kind.RATE_LIMIT || failure.kind == RouteFailure.Kind.SERVER;
            if (fallback == null || !busy) throw new RouteFailure(failure.kind, failure.http, failure.code, both);
            try {
                // the turn may already hold MiniMax-M3.1 reasoning fields: show the other model only what every chat
                // API knows, as the strong model is shown
                JSONObject reply = fallback.complete(xyz.erapi0neer.xingyi.agent.AgentLoop.portable(messages), tools,
                        FALLBACK_BUDGET_MS, sink);
                // the endpoint's own model, not the field: a router whose fallback was put in by hand (the
                // contract's scenarios do that) has no fallbackModel, and a null here would be published as the
                // model in use by LAST_MODEL and get_status
                activeModel = named(fallback);
                // each route's own state in the trace: the gateway's failure code as well as the official route's
                String gwDetail = failure.kind.reason + (failure.detail().isEmpty() ? "" : " " + failure.detail());
                publish(GATEWAY, reason + "; gateway: " + failure.kind.reason,
                        detail.isEmpty() ? gwDetail : gwDetail + "; official " + detail,
                        FALLBACK_NAME + (fallbackModel == null ? "" : "（" + fallbackModel + "）") + "应答 · 主模型" + failure.kind.chinese);
                return reply;
            } catch (RouteFailure second) {
                checkCancelled();
                throw new RouteFailure(second.kind, second.http, second.code,
                        both + "；" + FALLBACK_NAME + "也未能应答：" + second.getMessage());
            }
        }
    }

    @Override public void cancel() {
        cancelled = true;
        if (official != null) official.cancel();
        if (gateway != null) gateway.cancel();
        Endpoint s = strong;
        if (s != null) s.cancel();
        Endpoint f = fallback;
        if (f != null) f.cancel();
    }

    /** A round from the strong model. The conversation is made portable first (MiniMax adds reasoning fields that
     * other APIs refuse); when the strong model fails, the usual route answers, so escalating never costs the task. */
    public JSONObject nextStrong(JSONArray messages, JSONArray tools) throws Exception {
        checkCancelled();
        Endpoint s = strong;
        if (s != null) {
            try {
                JSONObject reply = s.complete(xyz.erapi0neer.xingyi.agent.AgentLoop.portable(messages), tools, FULL_BUDGET_MS);
                activeModel = named(s);
                publish(GATEWAY, "strong: ok", "", "备用网关强模型（" + STRONG_MODEL + "）应答");
                return reply;
            } catch (IOException failure) {
                checkCancelled();
            }
        }
        return next(messages, tools);
    }


    private static final java.util.concurrent.atomic.AtomicBoolean PROBING = new java.util.concurrent.atomic.AtomicBoolean();
    /** For the contract test: a background probe is still running. */
    static boolean probing() { return PROBING.get(); }

    /** One tiny request on the official route on its own thread: an answer clears the pause, a route-level failure
     * pauses it again. At most one probe at a time; the request that started it never waits for it. */
    private void probeOfficial() {
        if (!PROBING.compareAndSet(false, true)) return;
        final Endpoint route = official;
        Thread probe = new Thread(() -> {
            try {
                JSONArray ping = new JSONArray().put(new JSONObject().put("role", "user").put("content", "ping"));
                route.complete(ping, null, 15_000);
                health.clear();
            } catch (RouteFailure failure) {
                if (failure.kind.pausesOfficial) health.pause(failure.kind, clock.now());
            } catch (Exception ignored) {
                // a network hiccup: the next request after the pause probes again
            } finally {
                PROBING.set(false);
            }
        }, "xingyi-official-probe");
        probe.setDaemon(true);
        probe.start();
    }

    private void checkCancelled() throws RouteFailure {
        if (cancelled) throw new RouteFailure(RouteFailure.Kind.CANCELLED, 0, 0, "请求已取消");
    }

    /** The model an endpoint is configured with, when it says; the usual model otherwise. */
    private static String named(Endpoint e) { return e instanceof MiniMaxClient ? ((MiniMaxClient) e).model() : MiniMaxClient.MODEL; }

    /** The model that produced the latest reply (MiniMaxClient.MODEL before the first). The status page and the
     *  self-check say this, so they never claim the usual model while the gateway answers on another one. */
    public String activeModel() { return activeModel; }
    /** The model the next round will be answered by, with its route: the official route while it may be tried, else
     * the gateway's — the choice warm() makes. Put in the agent's environment (2026-11-16 on the TV: asked 「你现在用的是
     * 什么模型」 it called nothing and said 「MiniMax 的 M3 Pro」, while the gateway ran MiniMax-M78; nothing it could
     * see said which model it was). */
    public String expectedModel() {
        long now = clock.now();
        boolean officialFirst = official != null && (gateway == null || (health.paused(now) == null && !health.expired(now)));
        String route = officialFirst ? "官方线路" : "网关";
        String fb = fallback == null || officialFirst ? "" : "；网关忙时换 " + named(fallback);
        return named(officialFirst ? official : gateway) + "（" + route + fb + "）";
    }

    /** The same for callers that have no router in hand (get_status builds its own). */
    private static volatile String LAST_MODEL = MiniMaxClient.MODEL;
    public static String activeModelName() { return LAST_MODEL; }
    /** True when a second model on the gateway can be tried after a busy answer. */
    public boolean fallbackAvailable() { return fallback != null; }

    private void publish(String route, String reason, String detail, String label) {
        // only the process's own board speaks for the process: self_check's probe has its own, so 用的是什么模型 never
        // answers with the probe's round
        if (board == BOARD) LAST_MODEL = activeModel;
        Status status = new Status(route, reason, detail, label, clock.now(), board.attempts());
        lastRoute = status.toString();
        board.publish(status);
    }
}
