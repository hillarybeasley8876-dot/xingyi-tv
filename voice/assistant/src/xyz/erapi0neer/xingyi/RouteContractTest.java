package xyz.erapi0neer.xingyi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** DUMP-only diagnostics for ModelRouter and MiniMax error parsing. Scripted endpoints, a private
 * status board, an in-memory pause store and a fake clock: no network, no shared state.
 * The 402, 401 and 400 bodies and the HTTP 200 base_resp body were captured from api.minimax.io.
 */
final class RouteContractTest {
    private RouteContractTest() {}

    static final String BALANCE_402 = "{\"type\":\"error\",\"error\":{\"type\":\"insufficient_balance_error\",\"message\":\"insufficient balance (1008)\"}}";
    static final String AUTH_401 = "{\"type\":\"error\",\"error\":{\"type\":\"authorized_error\",\"message\":\"login fail: Please carry the API secret key in the 'Authorization' field of the request header (1004)\"}}";
    static final String UNKNOWN_MODEL_400 = "{\"type\":\"error\",\"error\":{\"type\":\"bad_request_error\",\"message\":\"invalid params, unknown model 'minimax-m3.1' (2013)\"}}";
    static final String BALANCE_200 = "{\"choices\":null,\"base_resp\":{\"status_code\":1008,\"status_msg\":\"insufficient balance\"}}";
    // Not captured live (they cannot be provoked safely); shapes follow the MiniMax error-code reference.
    static final String RATE_429 = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate limit (1002)\"}}";
    static final String SENSITIVE_200 = "{\"choices\":null,\"base_resp\":{\"status_code\":1026,\"status_msg\":\"input new_sensitive\"}}";
    static final long MINUTE = 60_000L;

    private interface Scenario { void run() throws Exception; }

    /** Runs every scenario, appends "name (error)" for each failure and returns the scenario count. */
    static int run(List<String> failures) {
        String[] names = {"官方优先", "余额不足回退并暂停", "暂停期间不打官方", "十分钟后重新探测", "重新探测仍失败",
                "鉴权失败回退", "HTTP200错误体", "5xx回退不暂停", "超时与断网回退", "参数错误不回退", "限流不回退",
                "回答格式错误不回退", "内容审核不回退", "两条线路都失败", "取消后不回退", "同一轮保持网关",
                "未配置官方", "仅官方时直接报错", "暂停持久化与时钟回拨", "错误分类表", "轨迹记录线路",
                "主模型满了换备用模型", "主模型5xx换备用模型", "超时不换备用模型", "备用模型也失败", "网关正常不打扰备用模型",
                "备用模型候选链", "open 接线"};
        Scenario[] scenarios = {RouteContractTest::officialFirst, RouteContractTest::balanceFallback,
                RouteContractTest::pausedSkipsOfficial, RouteContractTest::reprobeRecovers, RouteContractTest::reprobeFailsAgain,
                RouteContractTest::authFallback, RouteContractTest::bodyOn200, RouteContractTest::serverNoPause,
                RouteContractTest::timeoutAndNetwork, RouteContractTest::validationStays, RouteContractTest::rateLimitStays,
                RouteContractTest::invalidReplyStays, RouteContractTest::contentFilterStays, RouteContractTest::bothFail,
                RouteContractTest::cancelStops, RouteContractTest::stickyTurn, RouteContractTest::notConfigured,
                RouteContractTest::officialOnly, RouteContractTest::persistence, RouteContractTest::classification,
                RouteContractTest::harnessTrace,
                RouteContractTest::busyToFallback, RouteContractTest::unavailableToFallback,
                RouteContractTest::timeoutNoFallback, RouteContractTest::fallbackFails, RouteContractTest::fallbackIdle,
                RouteContractTest::fallbackChain, RouteContractTest::openWiring};
        for (int i = 0; i < scenarios.length; i++) {
            try { scenarios[i].run(); }
            catch (Exception | AssertionError e) { failures.add(names[i] + " (" + e + ")"); }
        }
        return scenarios.length;
    }

    private static final class Script implements ModelRouter.Endpoint {
        final List<Object> steps = new ArrayList<>();
        final List<Long> budgets = new ArrayList<>();
        Runnable during;
        boolean cancelled;
        Script(Object... script) { steps.addAll(Arrays.asList(script)); }
        @Override public JSONObject complete(JSONArray messages, JSONArray tools, long budgetMillis) throws IOException {
            budgets.add(budgetMillis);
            if (during != null) during.run();
            check(!steps.isEmpty(), "unplanned model call");
            Object next = steps.remove(0);
            if (next instanceof IOException) throw (IOException) next;
            return (JSONObject) next;
        }
        @Override public void cancel() { cancelled = true; }
        int calls() { return budgets.size(); }
    }
    private static final class Memory implements ModelRouter.Health.Store {
        long until; String kind = "";
        @Override public long until() { return until; }
        @Override public String kind() { return kind; }
        @Override public void save(long until, String kind) { this.until = until; this.kind = kind; }
    }
    /** One device with a clock, a pause store and a status board; routers are created per turn. */
    private static final class Setup implements ModelRouter.Clock {
        long now = 1_793_000_000_000L;
        final Memory store = new Memory();
        final ModelRouter.Health health = new ModelRouter.Health(store);
        final ModelRouter.Board board = new ModelRouter.Board();
        @Override public long now() { return now; }
        ModelRouter router(Script official, Script gateway) { return new ModelRouter(official, gateway, health, this, board); }
        ModelRouter.Status status() { return board.latest(); }
    }

    private static JSONObject reply(String text) throws Exception {
        return new JSONObject().put("role", "assistant").put("content", text);
    }
    private static JSONArray question() throws Exception {
        return new JSONArray().put(new JSONObject().put("role", "user").put("content", "现在音量是多少"));
    }
    private static RouteFailure official(int http, String body) {
        return MiniMaxClient.failure(MiniMaxClient.OFFICIAL_NAME, http, body);
    }
    private static RouteFailure failure(RouteFailure.Kind kind) {
        return new RouteFailure(kind, 0, 0, MiniMaxClient.OFFICIAL_NAME + " fixture " + kind);
    }
    private static String ask(ModelRouter router) throws Exception {
        return router.next(question(), new JSONArray()).getString("content");
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private static RouteFailure rejects(ModelRouter router, RouteFailure.Kind kind) throws Exception {
        try { router.next(question(), new JSONArray()); }
        catch (RouteFailure expected) {
            check(expected.kind == kind, "wrong failure kind " + expected.kind + ": " + expected.getMessage());
            return expected;
        }
        throw new AssertionError("expected " + kind);
    }
    private static void fallsBackFrom(Setup s, RouteFailure first, String reason) throws Exception {
        Script official = new Script(first), gateway = new Script(reply("网关回答"));
        check("网关回答".equals(ask(s.router(official, gateway))), "gateway answer missing");
        check(official.calls() == 1 && gateway.calls() == 1, "each route must be called once");
        check(ModelRouter.GATEWAY.equals(s.status().route) && reason.equals(s.status().reason),
                "status should name the gateway and the official failure: " + s.status());
    }

    private static void officialFirst() throws Exception {
        Setup s = new Setup();
        Script official = new Script(reply("官方回答")), gateway = new Script();
        check("官方回答".equals(ask(s.router(official, gateway))), "official answer missing");
        check(gateway.calls() == 0 && official.budgets.get(0) == ModelRouter.OFFICIAL_BUDGET_MS, "gateway used or wrong budget");
        ModelRouter.Status status = s.status();
        check(ModelRouter.OFFICIAL.equals(status.route) && "official: ok".equals(status.reason)
                && status.officialAttempts == 1 && status.label.contains("官方"), "status " + status);
    }
    private static void balanceFallback() throws Exception {
        Setup s = new Setup();
        RouteFailure balance = official(402, BALANCE_402);
        check(balance.kind == RouteFailure.Kind.BALANCE && balance.code == 1008, "402 body not classified as balance");
        fallsBackFrom(s, balance, "official: insufficient balance");
        check("HTTP 402, code 1008".equals(s.status().detail), "detail " + s.status().detail);
        check(s.store.until == s.now + ModelRouter.PAUSE_MS && "BALANCE".equals(s.store.kind), "pause not stored");
        check(s.status().label.contains("余额不足"), "label " + s.status().label);
    }
    private static void pausedSkipsOfficial() throws Exception {
        Setup s = new Setup();
        fallsBackFrom(s, official(402, BALANCE_402), "official: insufficient balance");
        s.now += ModelRouter.PAUSE_MS - 1000;
        Script official = new Script(), gateway = new Script(reply("网关回答"));
        check("网关回答".equals(ask(s.router(official, gateway))), "gateway answer missing");
        check(official.calls() == 0, "official retried during the pause");
        check(s.status().detail.startsWith("paused until ") && s.status().officialAttempts == 1
                && "official: insufficient balance".equals(s.status().reason), "status " + s.status());
        check(gateway.budgets.get(0) == ModelRouter.FULL_BUDGET_MS, "gateway budget");
    }
    /** The request after the pause goes to the gateway at once; the official route is asked in the background
     * (2026-11-05: an inline retry of a still-empty account cost every first request ~0.7 s). */
    private static void reprobeRecovers() throws Exception {
        Setup s = new Setup();
        fallsBackFrom(s, official(402, BALANCE_402), "official: insufficient balance");
        s.now += ModelRouter.PAUSE_MS;
        Script official = new Script(reply("充值后")), gateway = new Script(reply("网关回答"));
        check("网关回答".equals(ask(s.router(official, gateway))), "the request after the pause waited for the official route");
        waitProbe();
        check(official.calls() == 1 && s.store.until == 0, "the background probe did not clear the pause");
        Script again = new Script(reply("充值后")), unused = new Script();
        check("充值后".equals(ask(s.router(again, unused))), "after a good probe the official route did not answer");
        check(unused.calls() == 0 && ModelRouter.OFFICIAL.equals(s.status().route), "recovery not recorded: " + s.status());
    }
    private static void reprobeFailsAgain() throws Exception {
        Setup s = new Setup();
        fallsBackFrom(s, official(402, BALANCE_402), "official: insufficient balance");
        s.now += ModelRouter.PAUSE_MS + 5;
        Script official = new Script(official(402, BALANCE_402)), gateway = new Script(reply("网关回答"));
        check("网关回答".equals(ask(s.router(official, gateway))), "gateway answer missing");
        waitProbe();
        check(official.calls() == 1 && s.store.until == s.now + ModelRouter.PAUSE_MS, "the background probe did not renew the pause");
    }
    private static void waitProbe() throws InterruptedException {
        for (int i = 0; i < 300 && ModelRouter.probing(); i++) Thread.sleep(10);
    }
    private static void authFallback() throws Exception {
        Setup s = new Setup();
        RouteFailure auth = official(401, AUTH_401);
        check(auth.kind == RouteFailure.Kind.AUTH && auth.code == 1004, "401 body not classified as auth");
        check(!auth.getMessage().contains("Authorization"), "server text leaked into the message");
        fallsBackFrom(s, auth, "official: authentication failed");
        check("AUTH".equals(s.store.kind), "auth failure must pause the official route");
    }
    private static void bodyOn200() throws Exception {
        try { MiniMaxClient.parseReply(MiniMaxClient.OFFICIAL_NAME, BALANCE_200); throw new AssertionError("accepted"); }
        catch (RouteFailure expected) {
            check(expected.kind == RouteFailure.Kind.BALANCE && expected.http == 200 && expected.code == 1008,
                    "HTTP 200 base_resp 1008 misread: " + expected.kind + " " + expected.detail());
            Setup s = new Setup();
            fallsBackFrom(s, expected, "official: insufficient balance");
            check("HTTP 200, code 1008".equals(s.status().detail), "detail " + s.status().detail);
        }
    }
    private static void serverNoPause() throws Exception {
        Setup s = new Setup();
        RouteFailure server = official(503, "<html>busy</html>");
        check(server.kind == RouteFailure.Kind.SERVER, "503 not a server error");
        fallsBackFrom(s, server, "official: server error");
        check(s.store.until == 0, "a 5xx must not pause the official route");
        Script official = new Script(reply("官方")), gateway = new Script();
        check("官方".equals(ask(s.router(official, gateway))), "next turn should try the official route again");
    }
    private static void timeoutAndNetwork() throws Exception {
        Setup s = new Setup();
        fallsBackFrom(s, failure(RouteFailure.Kind.TIMEOUT), "official: timeout");
        fallsBackFrom(s, failure(RouteFailure.Kind.NETWORK), "official: network error");
        check(s.store.until == 0, "transient failures must not pause the official route");
    }
    private static void staysOnOfficial(RouteFailure first, RouteFailure.Kind kind, String text) throws Exception {
        Setup s = new Setup();
        Script official = new Script(first), gateway = new Script(reply("不应出现"));
        RouteFailure seen = rejects(s.router(official, gateway), kind);
        check(gateway.calls() == 0, kind + " silently fell back to the gateway");
        check(seen.getMessage().contains(text), "message: " + seen.getMessage());
        check(s.store.until == 0 && s.status().route.isEmpty(), "failure changed route state");
    }
    private static void validationStays() throws Exception {
        RouteFailure bad = official(400, UNKNOWN_MODEL_400);
        check(bad.code == 2013, "2013 not parsed");
        staysOnOfficial(bad, RouteFailure.Kind.REJECTED, "2013");
    }
    private static void rateLimitStays() throws Exception {
        staysOnOfficial(official(429, RATE_429), RouteFailure.Kind.RATE_LIMIT, "频繁");
    }
    private static void invalidReplyStays() throws Exception {
        String body = "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":\"get_status\"}}],"
                + "\"base_resp\":{\"status_code\":0,\"status_msg\":\"\"}}";
        RouteFailure invalid = null;
        try { MiniMaxClient.parseReply(MiniMaxClient.OFFICIAL_NAME, body); }
        catch (RouteFailure expected) { invalid = expected; }
        check(invalid != null && invalid.kind == RouteFailure.Kind.INVALID_RESPONSE, "malformed tool calls accepted");
        staysOnOfficial(invalid, RouteFailure.Kind.INVALID_RESPONSE, "工具调用");
    }
    private static void contentFilterStays() throws Exception {
        RouteFailure sensitive = null;
        try { MiniMaxClient.parseReply(MiniMaxClient.OFFICIAL_NAME, SENSITIVE_200); }
        catch (RouteFailure expected) { sensitive = expected; }
        check(sensitive != null && sensitive.code == 1026, "1026 not parsed");
        staysOnOfficial(sensitive, RouteFailure.Kind.REJECTED, "不合规");
    }
    private static void bothFail() throws Exception {
        Setup s = new Setup();
        Script official = new Script(official(402, BALANCE_402)),
                gateway = new Script(MiniMaxClient.failure(MiniMaxClient.GATEWAY_NAME, 503, null));
        RouteFailure both = rejects(s.router(official, gateway), RouteFailure.Kind.SERVER);
        check(both.getMessage().contains("官方 MiniMax 余额不足") && both.getMessage().contains("备用网关也未能应答")
                && both.getMessage().contains("503"), "message: " + both.getMessage());
        Script second = new Script(), gateway2 = new Script(MiniMaxClient.failure(MiniMaxClient.GATEWAY_NAME, 502, null));
        RouteFailure paused = rejects(s.router(second, gateway2), RouteFailure.Kind.SERVER);
        check(second.calls() == 0 && paused.getMessage().contains("后重试"), "paused message: " + paused.getMessage());
    }
    private static void cancelStops() throws Exception {
        Setup s = new Setup();
        Script official = new Script(failure(RouteFailure.Kind.NETWORK)), gateway = new Script(reply("不应出现"));
        ModelRouter router = s.router(official, gateway);
        official.during = router::cancel;
        rejects(router, RouteFailure.Kind.CANCELLED);
        check(gateway.calls() == 0 && official.cancelled && gateway.cancelled, "cancel did not stop both routes");
    }
    private static void stickyTurn() throws Exception {
        Setup s = new Setup();
        s.now += 1;
        Script official = new Script(official(500, null), reply("不应出现")), gateway = new Script(reply("一"), reply("二"));
        ModelRouter router = s.router(official, gateway);
        check("一".equals(ask(router)) && "二".equals(ask(router)), "gateway answers missing");
        check(official.calls() == 1 && gateway.calls() == 2, "a turn switched back to the official route mid-loop");
        check("official: server error".equals(s.status().reason), "status " + s.status());
    }
    private static void notConfigured() throws Exception {
        Setup s = new Setup();
        Script gateway = new Script(reply("网关回答"));
        check("网关回答".equals(ask(s.router(null, gateway))), "gateway answer missing");
        check("official: not configured".equals(s.status().reason) && s.status().officialAttempts == 0, "status " + s.status());
    }
    private static void officialOnly() throws Exception {
        Setup s = new Setup();
        Script official = new Script(official(402, BALANCE_402), official(402, BALANCE_402));
        rejects(s.router(official, null), RouteFailure.Kind.BALANCE);
        rejects(s.router(official, null), RouteFailure.Kind.BALANCE);
        check(official.calls() == 2 && official.budgets.get(0) == ModelRouter.FULL_BUDGET_MS,
                "without a gateway every request must reach the official route");
    }
    private static void persistence() throws Exception {
        Memory store = new Memory();
        long now = 1_793_000_000_000L;
        new ModelRouter.Health(store).pause(RouteFailure.Kind.BALANCE, now);
        ModelRouter.Health restarted = new ModelRouter.Health(store);
        check(restarted.paused(now + 5 * MINUTE) == RouteFailure.Kind.BALANCE, "pause lost on restart");
        check(restarted.paused(now + ModelRouter.PAUSE_MS) == null, "pause outlived ten minutes");
        check(restarted.paused(now - 20 * MINUTE) == null, "clock moved back: should probe instead of waiting");
        restarted.clear();
        check(store.until == 0 && store.kind.isEmpty(), "clear not stored");
    }
    private static void classification() throws Exception {
        Object[][] table = {
                {402, BALANCE_402, RouteFailure.Kind.BALANCE}, {401, AUTH_401, RouteFailure.Kind.AUTH},
                {403, null, RouteFailure.Kind.AUTH}, {400, UNKNOWN_MODEL_400, RouteFailure.Kind.REJECTED},
                {404, null, RouteFailure.Kind.REJECTED}, {413, "{}", RouteFailure.Kind.REJECTED},
                {429, null, RouteFailure.Kind.RATE_LIMIT}, {500, null, RouteFailure.Kind.SERVER},
                {502, "<html>", RouteFailure.Kind.SERVER}, {302, null, RouteFailure.Kind.REJECTED},
                {529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}", RouteFailure.Kind.SERVER},
                {200, "{\"base_resp\":{\"status_code\":1001,\"status_msg\":\"timeout\"}}", RouteFailure.Kind.TIMEOUT},
                {200, "{\"base_resp\":{\"status_code\":1013,\"status_msg\":\"internal\"}}", RouteFailure.Kind.SERVER},
                {200, "{\"base_resp\":{\"status_code\":2013,\"status_msg\":\"invalid params\"}}", RouteFailure.Kind.REJECTED},
                {200, "{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"not authorized\"}}", RouteFailure.Kind.AUTH},
                {200, "{\"base_resp\":{\"status_code\":1002,\"status_msg\":\"rate limit\"}}", RouteFailure.Kind.RATE_LIMIT},
                // Codes from the current error-code reference; their HTTP status on /v1/chat/completions is not known.
                {200, "{\"base_resp\":{\"status_code\":2064,\"status_msg\":\"high load\"}}", RouteFailure.Kind.SERVER},
                {200, "{\"base_resp\":{\"status_code\":2066,\"status_msg\":\"timed out\"}}", RouteFailure.Kind.TIMEOUT},
                {200, "{\"base_resp\":{\"status_code\":2067,\"status_msg\":\"usage limit\"}}", RouteFailure.Kind.BALANCE},
                {200, "{\"base_resp\":{\"status_code\":2068,\"status_msg\":\"plan tier\"}}", RouteFailure.Kind.AUTH},
                {429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"usage limit (2067)\"}}", RouteFailure.Kind.BALANCE},
                {400, "{\"type\":\"error\",\"error\":{\"type\":\"bad_request_error\",\"message\":\"plan tier (2068)\"}}", RouteFailure.Kind.AUTH}};
        for (Object[] row : table) {
            RouteFailure f = official((Integer) row[0], (String) row[1]);
            check(f.kind == row[2], row[0] + " " + row[1] + " -> " + f.kind);
        }
        for (RouteFailure.Kind kind : RouteFailure.Kind.values()) {
            boolean route = kind == RouteFailure.Kind.BALANCE || kind == RouteFailure.Kind.AUTH || kind == RouteFailure.Kind.SERVER
                    || kind == RouteFailure.Kind.TIMEOUT || kind == RouteFailure.Kind.NETWORK;
            check(kind.routeLevel == route, kind + " route-level flag");
            check(kind.pausesOfficial == (kind == RouteFailure.Kind.BALANCE || kind == RouteFailure.Kind.AUTH), kind + " pause flag");
        }
    }
    private static void harnessTrace() throws Exception {
        Setup s = new Setup();
        JSONObject call = new JSONObject().put("id", "r1").put("type", "function").put("function",
                new JSONObject().put("name", "get_status").put("arguments", "{}"));
        Script official = new Script(official(402, BALANCE_402));
        Script gateway = new Script(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("tool_calls", new JSONArray().put(call)), reply("音量 19"));
        ModelRouter router = s.router(official, gateway);
        List<String> journal = new ArrayList<>();
        AgentHarness harness = new AgentHarness(router,
                (tool, args) -> new JSONObject().put("ok", true).put("status", "ok").put("volume", 19),
                new AgentHarness.Journal() {
                    @Override public String event(String e, String t, String state, JSONObject args) { journal.add(e + "/" + t + "/" + state); return null; }
                    @Override public String trace() { return journal.toString(); }
                }, null, () -> 1_000L);
        AgentHarness.Step step = harness.start("现在音量是多少", null);
        check(!step.needsConfirmation && "音量 19".equals(step.text), "turn did not finish on the gateway");
        String route = "model_route//gateway · official: insufficient balance (HTTP 402, code 1008) · official attempts 1";
        check(journal.indexOf(route) == journal.indexOf("before_model//round=1") + 1, "route event missing: " + journal);
        check(journal.lastIndexOf(route) > journal.indexOf("after_tool/get_status/ok"), "second round route missing: " + journal);
        check(official.calls() == 1 && gateway.calls() == 2, "official retried inside the turn");
    }

    // ---- 2026-11-14: the gateway had no capacity for MiniMax-M3.1-Flash for hours; another model on it answered ----
    /** Captured live on 2026-11-14 at 15:52 (the request id replaced): 429 from the gateway for the usual model. */
    static final String NO_CAPACITY_429 = "{\"error\":{\"message\":\"scheduler capacity exhausted for model=MiniMax-M3.1-Flash "
            + "(request id: 0000)\",\"type\":\"system_error\",\"param\":\"\",\"code\":\"scheduler_no_capacity\"}}";
    /** Captured live the same afternoon on the gateway: 503 model_unavailable. */
    static final String UNAVAILABLE_503 = "{\"error\":{\"code\":\"model_unavailable\",\"message\":\"The requested model is "
            + "temporarily unavailable. Please try again later. (request id: 0000)\",\"type\":\"system_error\"}}";

    private static RouteFailure gatewayFailure(int http, String body) {
        return MiniMaxClient.failure(MiniMaxClient.GATEWAY_NAME, http, body);
    }

    /** Official without balance, the gateway out of capacity for the usual model: the fallback model answers. */
    private static void busyToFallback() throws Exception {
        Setup s = new Setup();
        RouteFailure busy = gatewayFailure(429, NO_CAPACITY_429);
        check(busy.kind == RouteFailure.Kind.RATE_LIMIT, "the live no-capacity 429 is a rate limit: " + busy.kind);
        Script official = new Script(official(402, BALANCE_402)), gateway = new Script(busy),
                fallback = new Script(reply("备用模型回答"));
        ModelRouter router = s.router(official, gateway);
        router.fallback = fallback;
        router.fallbackModel = "MiniMax-M78";
        check("备用模型回答".equals(ask(router)), "the fallback's answer is the answer");
        check(official.calls() == 1 && gateway.calls() == 1 && fallback.calls() == 1, "each route once, the fallback once");
        check(fallback.budgets.get(0) == ModelRouter.FALLBACK_BUDGET_MS, "the fallback gets its own budget, not the whole minute");
        check(ModelRouter.GATEWAY.equals(s.status().route) && s.status().label.contains(router.fallbackModel),
                "the status names the fallback model: " + s.status().label);
        check(s.status().reason.contains("gateway: rate limited"), "the trace says why the usual model was passed over");
    }

    /** 503 model_unavailable on the gateway is the same situation as no capacity. */
    private static void unavailableToFallback() throws Exception {
        Setup s = new Setup();
        RouteFailure gone = gatewayFailure(503, UNAVAILABLE_503);
        check(gone.kind == RouteFailure.Kind.SERVER, "503 model_unavailable is a server failure: " + gone.kind);
        Script official = new Script(official(402, BALANCE_402)), gateway = new Script(gone),
                fallback = new Script(reply("备用模型回答"));
        ModelRouter router = s.router(official, gateway);
        router.fallback = fallback;
        check("备用模型回答".equals(ask(router)) && fallback.calls() == 1, "a 5xx goes to the fallback");
    }

    /** A gateway timeout already cost a minute: no second minute on another model. */
    private static void timeoutNoFallback() throws Exception {
        Setup s = new Setup();
        Script official = new Script(official(402, BALANCE_402)),
                gateway = new Script(new RouteFailure(RouteFailure.Kind.TIMEOUT, 0, 0, MiniMaxClient.GATEWAY_NAME + "响应超时")),
                fallback = new Script(reply("不应出现"));
        ModelRouter router = s.router(official, gateway);
        router.fallback = fallback;
        rejects(router, RouteFailure.Kind.TIMEOUT);
        check(fallback.calls() == 0, "a timeout must not be followed by the fallback");
        Script official2 = new Script(), gateway2 = new Script(new RouteFailure(RouteFailure.Kind.NETWORK, 0, 0,
                MiniMaxClient.GATEWAY_NAME + "网络请求失败，请检查连接后重试")), fallback2 = new Script(reply("不应出现"));
        ModelRouter router2 = s.router(official2, gateway2);
        router2.fallback = fallback2;
        rejects(router2, RouteFailure.Kind.NETWORK);
        check(fallback2.calls() == 0, "a network failure reaches the same host: no fallback");
    }

    /** Both the usual model and the fallback busy: one sentence naming all three, and the cause is the last one. */
    private static void fallbackFails() throws Exception {
        Setup s = new Setup();
        Script official = new Script(official(402, BALANCE_402)), gateway = new Script(gatewayFailure(429, NO_CAPACITY_429)),
                fallback = new Script(MiniMaxClient.failure(ModelRouter.FALLBACK_NAME, 429, RATE_429));
        ModelRouter router = s.router(official, gateway);
        router.fallback = fallback;
        RouteFailure all = rejects(router, RouteFailure.Kind.RATE_LIMIT);
        String m = all.getMessage();
        check(m.contains("官方 MiniMax 余额不足") && m.contains("备用网关也未能应答") && m.contains("备用模型也未能应答"),
                "message names all three routes: " + m);
        check(xyz.erapi0neer.xingyi.agent.AgentLoop.offline(m), "the task still goes back to XiaoAi");
        check("星仪用的模型这会儿太忙".equals(xyz.erapi0neer.xingyi.agent.AgentLoop.offlineReason(m)),
                "what the user hears is that the model is busy, not the network: " + xyz.erapi0neer.xingyi.agent.AgentLoop.offlineReason(m));
    }

    /** The gateway's usual model answers: the fallback is never called. */
    private static void fallbackIdle() throws Exception {
        Setup s = new Setup();
        Script official = new Script(official(402, BALANCE_402)), gateway = new Script(reply("网关回答")),
                fallback = new Script(reply("不应出现"));
        ModelRouter router = s.router(official, gateway);
        router.fallback = fallback;
        router.fallbackModel = "MiniMax-M78";
        check("网关回答".equals(ask(router)) && fallback.calls() == 0, "a working gateway never reaches the fallback");
        check(!s.status().label.contains(router.fallbackModel), "status: " + s.status().label);
    }
    /** The fallback model follows the gateway: it is never the model the gateway is already serving, and M3.1-Flash
     * (429 no capacity since 11-14) is not in the chain at all. With the gateway on M78 the chain used to be empty. */
    private static void fallbackChain() throws Exception {
        check("MiniMax-M2.7".equals(ModelRouter.fallbackModelFor("MiniMax-M78")),
                "with the gateway on M78 the fallback must be M2.7, not nothing: " + ModelRouter.fallbackModelFor("MiniMax-M78"));
        check("MiniMax-M78".equals(ModelRouter.fallbackModelFor("MiniMax-M3.1-Flash")),
                "from M3.1-Flash the chain starts at M78");
        check("MiniMax-M78".equals(ModelRouter.fallbackModelFor("claude-opus-5-5")), "any other gateway model falls back to M78");
        String picked = ModelRouter.fallbackModelFor("minimax-m78");
        check("MiniMax-M2.7".equals(picked), "the comparison ignores case: " + picked);
        check(ModelRouter.fallbackModelFor(null) == null, "no gateway, no fallback");
        for (String c : ModelRouter.FALLBACK_CANDIDATES)
            check(!"MiniMax-M3.1-Flash".equals(c), "M3.1-Flash answers 429 no capacity since 11-14; it must not be a candidate");

        // the model in the status label is the instance field, not a constant: a router built for M2.7 says M2.7
        Setup s = new Setup();
        Script official = new Script(official(402, BALANCE_402)),
                gateway = new Script(gatewayFailure(429, NO_CAPACITY_429));
        ModelRouter router = s.router(official, gateway);
        router.fallback = new Script(reply("M2.7 回答"));
        router.fallbackModel = "MiniMax-M2.7";
        check("M2.7 回答".equals(ask(router)), "the fallback answers");
        check(s.status().label.contains("MiniMax-M2.7") && !s.status().label.contains("MiniMax-M78"),
                "the status names the model that answered: " + s.status().label);
        // the model in use comes from the endpoint, so a hand-built fallback (no fallbackModel field) still names a
        // real model instead of publishing null to get_status
        ModelRouter hand = s.router(new Script(official(402, BALANCE_402)), new Script(gatewayFailure(429, NO_CAPACITY_429)));
        hand.fallback = new Script(reply("手装的回答"));
        check("手装的回答".equals(ask(hand)), "a hand-built fallback answers");
        check(hand.activeModel() != null && !hand.activeModel().isEmpty(),
                "a hand-built fallback still names a model, never null: " + hand.activeModel());
        check(!s.status().label.contains("null"), "the label never says null: " + s.status().label);
        check(!router.fallbackAvailable() == false, "a router with a fallback says so");
    }

    /** open() itself, with routes built by hand: the wiring the TV runs on every turn. Until 2026-11-16 nothing in
     * this file called open() — the review deleted the whole fallback block from it and ROUTE OK stayed 27 green. No
     * request goes out here: the endpoints are real, and only the wiring (which fallback model exists at all) is
     * asserted. */
    private static void openWiring() throws Exception {
        Setup s = new Setup();
        GatewayConfig m78 = new GatewayConfig("https://gw.example.com", "k-1234567890", "MiniMax-M78");
        GatewayConfig m31 = new GatewayConfig("https://gw.example.com", "k-1234567890", "MiniMax-M3.1-Flash");
        GatewayConfig official = new GatewayConfig("https://api.minimax.io", "o-1234567890", "MiniMax-M3.1-Flash");

        ModelRouter onM78 = ModelRouter.open(new GatewayConfig.Routes(official, m78), s.health, s, s.board);
        check(onM78.fallbackAvailable(), "gateway on M78 still has a second leg (this was the 11-14..11-16 bug)");
        check("MiniMax-M2.7".equals(onM78.fallbackModel), "and it is M2.7: " + onM78.fallbackModel);

        ModelRouter onFlash = ModelRouter.open(new GatewayConfig.Routes(official, m31), s.health, s, s.board);
        check("MiniMax-M78".equals(onFlash.fallbackModel), "gateway on M3.1-Flash falls back to M78");

        ModelRouter gatewayOnly = ModelRouter.open(new GatewayConfig.Routes(null, m78), s.health, s, s.board);
        check(gatewayOnly.fallbackAvailable(), "a gateway-only config still has its fallback");

        ModelRouter officialOnly = ModelRouter.open(new GatewayConfig.Routes(official, null), s.health, s, s.board);
        check(!officialOnly.fallbackAvailable(), "no gateway means no fallback to build");
        check("MiniMax-M3.1-Flash".equals(officialOnly.activeModel()),
                "before any call the official route already names its own configured model: " + officialOnly.activeModel());
        GatewayConfig officialOn27 = new GatewayConfig("https://api.minimax.io", "o-1234567890", "MiniMax-M2.7");
        ModelRouter unusual = ModelRouter.open(new GatewayConfig.Routes(officialOn27, m78), s.health, s, s.board);
        check("MiniMax-M2.7".equals(unusual.activeModel()),
                "the configured official model, not the constant: " + unusual.activeModel());

        // what the agent is told it runs on (expectedModel, in its environment): 2026-11-16 the TV said 「M3 Pro」
        check("MiniMax-M3.1-Flash（官方线路）".equals(onM78.expectedModel()),
                "official route healthy: its model answers first: " + onM78.expectedModel());
        s.health.pause(RouteFailure.Kind.BALANCE, s.now);
        check("MiniMax-M78（网关；网关忙时换 MiniMax-M2.7）".equals(onM78.expectedModel()),
                "official paused: the gateway's model, and what stands behind it: " + onM78.expectedModel());
        check("MiniMax-M3.1-Flash（官方线路）".equals(officialOnly.expectedModel()),
                "with no gateway the official route is tried even when paused: " + officialOnly.expectedModel());
        check(gatewayOnly.expectedModel().startsWith("MiniMax-M78（网关"), "gateway only: " + gatewayOnly.expectedModel());
        s.health.clear();
    }

}
