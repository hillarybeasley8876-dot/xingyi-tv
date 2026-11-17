package xyz.erapi0neer.xingyi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Blocking native MiniMax tool-call client for one route: the official API or the user's gateway.
 * Use only on a worker thread. The complete assistant message is returned, including all reasoning and
 * tool-call fields. Callers must preserve it in model history, but must never display reasoning.
 * Every failure is a {@link RouteFailure}; its kind tells {@link ModelRouter} whether to try another route.
 * MiniMax reports errors in two shapes, both handled here: an HTTP 4xx/5xx JSON error object such as
 * {"type":"error","error":{"type":"insufficient_balance_error","message":"insufficient balance (1008)"}},
 * and HTTP 200 with base_resp.status_code != 0.
 */
public final class MiniMaxClient implements ModelRouter.Endpoint {
    public static final String MODEL = "MiniMax-M3.1-Flash";
    public static final String OFFICIAL_NAME = "官方 MiniMax", GATEWAY_NAME = "备用网关";
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final int MAX_ERROR_BODY = 64 * 1024;
    private static final int MAX_REQUEST = 8 * 1024 * 1024;
    private static final long MAX_BUDGET_MS = 60_000L;
    private static final int CONNECT_MS = 8_000, READ_MS = 40_000;
    private static final Pattern CODE = Pattern.compile("\\((\\d{3,5})\\)");
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "xingyi-minimax-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final GatewayConfig config;
    private final String name;
    private volatile HttpsURLConnection active;
    private volatile boolean cancelled;

    /** Opens a pooled connection to a route before a request needs it: the TCP and TLS handshakes took 0.2-1.3 s
     * from this network (2026-11-05 latency_probe), all of it on the user's wait for the first round. GET / sends no
     * key and gets a tiny 404 (Connection: keep-alive) whose body is read to the end, so the socket goes back to the
     * pool for the next POST. Best-effort: false on any failure, never throws.
     * @return whether the route answered */
    static boolean warm(GatewayConfig route) {
        if (route == null || route.baseUrl == null || route.baseUrl.isEmpty()) return false;
        try {
            HttpsURLConnection c = (HttpsURLConnection) new URL(route.baseUrl + "/").openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(5_000);
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) try (InputStream body = in) {
                byte[] buf = new byte[1024];
                for (int n, total = 0; (n = body.read(buf)) > 0 && (total += n) < MAX_ERROR_BODY; ) { }
            }
            return true; // no disconnect(): that would close the socket instead of pooling it
        } catch (Exception e) {
            return false;
        }
    }

    /** The model this route is configured with: the status page says this, so it never claims the usual model
     *  while the gateway answers on another one. */
    String model() { return config.model; }

    /** @param displayName route name used in user-visible errors, e.g. {@link #OFFICIAL_NAME}. */
    public MiniMaxClient(GatewayConfig settings, String displayName) {
        config = settings;
        name = lead(displayName);
    }

    /** Returns choices[0].message without dropping or rewriting any fields.
     * @param budgetMillis total time for this attempt: connect, upload and the complete answer. */
    @Override public JSONObject complete(JSONArray messages, JSONArray tools, long budgetMillis) throws RouteFailure {
        return complete(messages, tools, budgetMillis, null);
    }

    /** The same, streamed when sink is not null ("stream": true): the answer's text reaches sink as it arrives, and the
     * message is put back together from the stream (Sse) and checked like a plain one. A stream that cannot be read as
     * one, before any text reached sink, is asked again whole: streaming must never cost an answer. */
    @Override public JSONObject complete(JSONArray messages, JSONArray tools, long budgetMillis,
            xyz.erapi0neer.xingyi.agent.Sse.Sink sink) throws RouteFailure {
        if (sink == null) return request(messages, tools, budgetMillis, null);
        long began = System.nanoTime();
        xyz.erapi0neer.xingyi.agent.Sse sse = new xyz.erapi0neer.xingyi.agent.Sse(sink);
        try {
            return request(messages, tools, budgetMillis, sse);
        } catch (RouteFailure failure) {
            boolean cut = CUT.equals(failure.getMessage());
            if (!UNSTREAMABLE.equals(failure.getMessage()) && !cut) throw failure;
            if (sse.spoke()) throw cut ? new RouteFailure(RouteFailure.Kind.NETWORK, 0, 0, name + "网络请求失败，请检查连接后重试")
                    : invalid(name + "返回格式无效，本次没有执行新的操作");
            long left = budgetMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            if (left < 3_000) throw timeout(budgetMillis);
            return request(messages, tools, left, null);
        }
    }

    /** The message of a stream whose lines are not the chat-completion chunks it should carry (never shown: the
     * request is made again whole, or reported as an invalid answer). */
    private static final String UNSTREAMABLE = "stream unreadable";
    /** The message of a stream that ended before its answer did (no finish_reason, no [DONE]): asked again whole when
     * nothing was passed on yet, else a network failure. */
    private static final String CUT = "stream cut";

    private static RouteFailure unstreamable() {
        return new RouteFailure(RouteFailure.Kind.INVALID_RESPONSE, 200, 0, UNSTREAMABLE);
    }

    private synchronized JSONObject request(JSONArray messages, JSONArray tools, long budgetMillis,
            xyz.erapi0neer.xingyi.agent.Sse sse) throws RouteFailure {
        if (cancelled) throw cancelledFailure();
        if (messages == null || messages.length() == 0 || messages.length() > 80)
            throw new RouteFailure(RouteFailure.Kind.REJECTED, 0, 0, "对话消息应为 1 至 80 条");
        final long budget = Math.max(1, Math.min(MAX_BUDGET_MS, budgetMillis));
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
        final AtomicBoolean expired = new AtomicBoolean(false);
        HttpsURLConnection conn = null;
        ScheduledFuture<?> watchdog = null;
        boolean complete = false;
        try {
            JSONObject body = new JSONObject()
                    .put("model", config.model)
                    .put("reasoning_effort", "low")
                    .put("max_completion_tokens", 2048)
                    .put("stream", sse != null)
                    .put("messages", messages);
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            if (payload.length > MAX_REQUEST)
                throw new RouteFailure(RouteFailure.Kind.REJECTED, 0, 0, "请求内容过大，请减少图片或对话长度");
            check(deadline, expired, budget);
            conn = (HttpsURLConnection) new URL(config.baseUrl + "/v1/chat/completions").openConnection();
            final HttpsURLConnection connection = conn;
            // Keep Android's certificate and hostname checks. Never forward the key on a redirect.
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout((int) Math.min(CONNECT_MS, budget));
            conn.setReadTimeout((int) Math.min(READ_MS, budget));
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", sse != null ? "text/event-stream" : "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setDoOutput(true);
            active = conn;
            check(deadline, expired, budget);
            watchdog = DEADLINES.schedule(() -> {
                expired.set(true);
                connection.disconnect();
            }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try (OutputStream out = conn.getOutputStream()) {
                for (int offset = 0; offset < payload.length; offset += 8192) {
                    check(deadline, expired, budget);
                    out.write(payload, offset, Math.min(8192, payload.length - offset));
                }
            }
            check(deadline, expired, budget);
            int status = conn.getResponseCode();
            if (status != 200) {
                String error = null;
                InputStream stream = conn.getErrorStream();
                if (stream != null) {
                    try (InputStream input = stream) { error = readBounded(input, MAX_ERROR_BODY, deadline, expired, budget); }
                    catch (RouteFailure stop) { throw stop; }
                    catch (IOException unreadable) { error = null; }
                }
                throw failure(name, status, error);
            }
            if (conn.getContentLengthLong() > MAX_RESPONSE)
                throw new RouteFailure(RouteFailure.Kind.INVALID_RESPONSE, 200, 0, "模型响应过大，已停止读取");
            String response;
            String type = conn.getContentType();
            if (sse != null && type != null && type.toLowerCase(Locale.ROOT).contains("event-stream")) {
                try (InputStream input = conn.getInputStream()) { readStream(input, sse, deadline, expired, budget); }
                if (sse.error() != null) throw failure(name, 200, sse.error().toString());
                if (!sse.done() && !sse.finished()) throw new RouteFailure(RouteFailure.Kind.INVALID_RESPONSE, 200, 0, CUT);
                try { response = sse.envelope().toString(); }
                catch (JSONException e) { throw unstreamable(); }
            } else {
                // a plain answer (no stream asked for, or a server that ignored the request for one)
                try (InputStream input = conn.getInputStream()) {
                    response = readBounded(input, MAX_RESPONSE, deadline, expired, budget);
                }
            }
            check(deadline, expired, budget);
            JSONObject reply = parseReply(name, response);
            complete = true;
            return reply;
        } catch (RouteFailure failure) {
            if (cancelled) throw cancelledFailure();
            throw failure;
        } catch (SocketTimeoutException error) {
            if (cancelled) throw cancelledFailure();
            throw new RouteFailure(RouteFailure.Kind.TIMEOUT, 0, 0, name + "响应超时");
        } catch (SSLException error) {
            if (cancelled) throw cancelledFailure();
            if (expired.get() || System.nanoTime() >= deadline) throw timeout(budget);
            throw new RouteFailure(RouteFailure.Kind.NETWORK, 0, 0, name + "安全连接失败，请检查电视时间和网络");
        } catch (JSONException error) {
            throw new RouteFailure(RouteFailure.Kind.INVALID_RESPONSE, 0, 0, name + "返回格式无效，本次没有执行新的操作");
        } catch (IOException error) {
            if (cancelled) throw cancelledFailure();
            if (expired.get() || System.nanoTime() >= deadline) throw timeout(budget);
            // Transport exceptions may embed URLs or request details. Never surface their text.
            throw new RouteFailure(RouteFailure.Kind.NETWORK, 0, 0, name + "网络请求失败，请检查连接后重试");
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            if (active == conn) active = null;
            // A fully read reply leaves the connection to Android's pool: the next round skips TCP + TLS (~170 ms from
            // the PC, more on the TV's CPU, with spikes to a second). Anything else closes it.
            if (conn != null && !complete) conn.disconnect();
        }
    }

    /** Permanently cancels this client, including a pending network operation. */
    @Override public void cancel() {
        cancelled = true;
        HttpsURLConnection connection = active;
        if (connection != null) { Thread close = new Thread(connection::disconnect, "xingyi-minimax-cancel"); close.setDaemon(true); close.start(); }
    }

    /** Validates an HTTP 200 body and returns choices[0].message unchanged. */
    static JSONObject parseReply(String route, String response) throws RouteFailure {
        try {
            JSONObject envelope = new JSONObject(response);
            int code = baseCode(envelope);
            if (code != 0) throw failure(route, 200, response);
            JSONArray choices = envelope.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw invalid("模型没有返回可用回答");
            JSONObject choice = choices.getJSONObject(0);
            String finish = choice.optString("finish_reason", "");
            if ("length".equals(finish)) throw invalid("模型回答被截断，本次没有执行新的操作，请缩短请求后重试");
            // content_filter is the model declining, not a sentence that ran out: 「说短一点」 would not help
            if ("content_filter".equals(finish))
                throw invalid("判定内容不合规，本次没有执行新的操作");
            if (!("stop".equals(finish) || "tool_calls".equals(finish)))
                throw invalid("模型回答未完整结束，本次没有执行新的操作");
            JSONObject message = choice.getJSONObject("message");
            if (!"assistant".equals(message.optString("role"))) throw invalid("模型返回了无效的消息角色");
            Object rawCalls = message.opt("tool_calls");
            JSONArray calls = null;
            if (rawCalls != null && rawCalls != JSONObject.NULL) {
                if (!(rawCalls instanceof JSONArray)) throw invalid("模型返回的工具调用格式无效");
                calls = (JSONArray) rawCalls;
                if (calls.length() > 4) throw invalid("一次最多处理 4 个工具调用，请拆分请求");
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    if (!(call.opt("id") instanceof String) || call.getString("id").isEmpty()
                            || call.getString("id").length() > 256 || !"function".equals(call.optString("type")))
                        throw invalid("模型返回的工具调用标识无效");
                    JSONObject function = call.getJSONObject("function");
                    if (!(function.opt("name") instanceof String) || !function.getString("name").matches("[A-Za-z0-9_-]{1,64}")
                            || !(function.opt("arguments") instanceof String))
                        throw invalid("模型返回的工具参数格式无效");
                }
            }
            if ("tool_calls".equals(finish) && (calls == null || calls.length() == 0))
                throw invalid("模型没有提供完整的工具调用");
            // Deliberately return the same JSON message, retaining reasoning_content, content,
            // tool_calls and any provider metadata. Do not reconstruct a reduced message.
            return message;
        } catch (JSONException error) {
            throw invalid(lead(route) + "返回格式无效，本次没有执行新的操作");
        }
    }

    /** Classifies a non-success answer: HTTP 4xx/5xx with an optional JSON error body, or HTTP 200 with
     * base_resp.status_code != 0. Server text is parsed for type and code only and never shown. */
    static RouteFailure failure(String route, int http, String body) {
        JSONObject json = null;
        if (body != null) {
            try { json = new JSONObject(body); } catch (JSONException notJson) { json = null; }
        }
        int code = errorCode(json);
        RouteFailure.Kind kind = classify(http, code, errorType(json));
        return new RouteFailure(kind, http, code, message(route, kind, http, code));
    }

    /** Codes follow https://platform.minimax.io/docs/api-reference/errorcode (checked 2026-11-03): 2067 (usage limit,
     * credit auto-consumption off) is a balance failure, 2068 (plan tier lacks the model) an authorization failure,
     * 2066 (timed out while processing) a timeout and 2064 (cluster under high load) a server failure. */
    static RouteFailure.Kind classify(int http, int code, String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (http == 402 || code == 1008 || code == 2053 || code == 2056 || code == 2067 || t.contains("insufficient_balance")
                || t.contains("insufficient_quota"))
            return RouteFailure.Kind.BALANCE;
        if (http == 401 || http == 403 || code == 1004 || code == 2049 || code == 2057 || code == 2061 || code == 2068
                || t.contains("authorized_error") || t.contains("authentication") || t.contains("permission"))
            return RouteFailure.Kind.AUTH;
        if (http == 429 || code == 1002 || code == 1039 || code == 1041 || code == 1045 || code == 2045
                || code == 2046 || code == 2047 || code == 2062 || t.contains("rate_limit"))
            return RouteFailure.Kind.RATE_LIMIT;
        if (code == 1001 || code == 2066 || http == 408) return RouteFailure.Kind.TIMEOUT;
        if (http >= 500 || code == 1000 || code == 1013 || code == 1024 || code == 1033 || code == 2064
                || t.contains("server_error") || t.contains("overloaded"))
            return RouteFailure.Kind.SERVER;
        return RouteFailure.Kind.REJECTED;
    }

    private static int baseCode(JSONObject json) {
        JSONObject base = json == null ? null : json.optJSONObject("base_resp");
        return base == null ? 0 : base.optInt("status_code", 0);
    }

    static int errorCode(JSONObject json) {
        if (json == null) return 0;
        int base = baseCode(json);
        if (base != 0) return base;
        JSONObject error = json.optJSONObject("error");
        String message = json.optString("message", "");
        if (error != null) {
            Object code = error.opt("code");
            if (code instanceof Number) return ((Number) code).intValue();
            if (code instanceof String && ((String) code).matches("\\d{3,5}")) return Integer.parseInt((String) code);
            message = error.optString("message", "");
        }
        int found = 0;
        Matcher matcher = CODE.matcher(message);
        while (matcher.find()) found = Integer.parseInt(matcher.group(1));
        return found;
    }

    private static String errorType(JSONObject json) {
        if (json == null) return "";
        JSONObject error = json.optJSONObject("error");
        return error != null ? error.optString("type", "") : "";
    }

    /** "官方 MiniMax" + "余额不足" reads better with a space between Latin and CJK text. */
    static String lead(String route) {
        return route.matches(".*[A-Za-z0-9]$") ? route + " " : route;
    }

    private static String message(String name, RouteFailure.Kind kind, int http, int code) {
        String route = lead(name);
        String id = http > 0 && http != 200 ? "HTTP " + http + (code != 0 ? " · " + code : "") : (code != 0 ? String.valueOf(code) : "");
        String suffix = id.isEmpty() ? "" : "（" + id + "）";
        switch (kind) {
            case BALANCE: return route + "余额不足" + suffix;
            case AUTH: return route + "鉴权失败，请检查密钥或模型权限" + suffix;
            case RATE_LIMIT: return route + "请求过于频繁，请稍后重试" + suffix;
            case TIMEOUT: return route + "响应超时" + suffix;
            case SERVER: return route + "服务暂时异常，请稍后重试" + suffix;
            default:
                if (code == 1026 || code == 1027) return route + "判定内容不合规，本次没有执行新的操作" + suffix;
                if (http == 404) return route + "没有找到该模型或接口" + suffix;
                if (http >= 300 && http < 400) return route + "返回跳转，已停止请求" + suffix;
                if (http == 400 || code == 2013) return route + "拒绝了这次请求（请求格式或参数不对）" + suffix;
                return route + "拒绝了这次请求" + suffix;
        }
    }

    private static RouteFailure invalid(String message) {
        return new RouteFailure(RouteFailure.Kind.INVALID_RESPONSE, 200, 0, message);
    }

    private RouteFailure timeout(long budget) {
        return new RouteFailure(RouteFailure.Kind.TIMEOUT, 0, 0,
                name + "请求超过 " + Math.max(1, budget / 1000) + " 秒，已停止等待");
    }

    private static RouteFailure cancelledFailure() {
        return new RouteFailure(RouteFailure.Kind.CANCELLED, 0, 0, "请求已取消");
    }

    private void check(long deadline, AtomicBoolean expired, long budget) throws RouteFailure {
        if (cancelled) throw cancelledFailure();
        if (expired.get() || System.nanoTime() >= deadline) throw timeout(budget);
    }

    /** Reads a server-sent event stream line by line into sse, to its end (so the connection can be pooled), within
     * the attempt's budget and the response size limit. */
    private void readStream(InputStream input, xyz.erapi0neer.xingyi.agent.Sse sse, long deadline, AtomicBoolean expired,
            long budget) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        boolean open = true;
        int n;
        while ((n = input.read(chunk)) != -1) {
            check(deadline, expired, budget);
            total += n;
            if (total > MAX_RESPONSE) throw new RouteFailure(RouteFailure.Kind.INVALID_RESPONSE, 200, 0, "模型响应过大，已停止读取");
            for (int i = 0; i < n; i++) {
                byte b = chunk[i];
                if (b != '\n') { line.write(b); continue; }
                String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
                line.reset();
                if (!open) continue; // after [DONE]: drained, not read
                try { open = sse.line(text); }
                catch (JSONException e) { throw unstreamable(); }
            }
        }
        if (open && line.size() > 0) {
            try { sse.line(new String(line.toByteArray(), StandardCharsets.UTF_8)); }
            catch (JSONException e) { throw unstreamable(); }
        }
    }

    private String readBounded(InputStream input, int limit, long deadline, AtomicBoolean expired, long budget)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = input.read(chunk)) != -1) {
            check(deadline, expired, budget);
            if (buffer.size() + n > limit) {
                if (limit == MAX_RESPONSE)
                    throw new RouteFailure(RouteFailure.Kind.INVALID_RESPONSE, 200, 0, "模型响应过大，已停止读取");
                break; // An oversized error body is classified from its HTTP status alone.
            }
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
