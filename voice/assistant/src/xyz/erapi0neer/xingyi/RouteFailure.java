package xyz.erapi0neer.xingyi;

import java.io.IOException;

/** A failed model call on one route. The message is safe to show: it never contains server text,
 * URLs, headers or credentials. {@link Kind#routeLevel} decides whether another route may be tried.
 */
public final class RouteFailure extends IOException {
    public enum Kind {
        /** 402 / 1008: the account behind the key has no balance. Pauses the official route. */
        BALANCE("insufficient balance", "余额不足", true, true),
        /** 401 / 1004 / 2049: the key is missing, wrong or not allowed. Pauses the official route. */
        AUTH("authentication failed", "鉴权失败", true, true),
        /** HTTP 5xx or a MiniMax internal-error code. */
        SERVER("server error", "服务异常", true, false),
        /** No complete answer within the route's time budget, or MiniMax code 1001 / 2066. */
        TIMEOUT("timeout", "响应超时", true, false),
        /** DNS, connect, TLS or reset before a complete HTTP answer. */
        NETWORK("network error", "网络不通", true, false),
        /** 429 and MiniMax rate/concurrency codes. Reported, not rerouted. */
        RATE_LIMIT("rate limited", "请求过于频繁", false, false),
        /** Parameter, model-name, content-policy or other request errors. Reported, never rerouted. */
        REJECTED("request rejected", "拒绝了请求", false, false),
        /** HTTP 200 whose body fails validation (bad tool calls, truncation, wrong role). Never rerouted. */
        INVALID_RESPONSE("invalid response", "回答格式无效", false, false),
        CANCELLED("cancelled", "已取消", false, false);

        public final String reason, chinese;
        public final boolean routeLevel, pausesOfficial;

        Kind(String reason, String chinese, boolean routeLevel, boolean pausesOfficial) {
            this.reason = reason;
            this.chinese = chinese;
            this.routeLevel = routeLevel;
            this.pausesOfficial = pausesOfficial;
        }
    }

    public final Kind kind;
    /** HTTP status, or 0 when no HTTP answer was received. */
    public final int http;
    /** MiniMax status code (base_resp.status_code or the "(1008)" in error.message), 0 if none. */
    public final int code;

    public RouteFailure(Kind kind, int http, int code, String message) {
        super(message);
        this.kind = kind;
        this.http = http;
        this.code = code;
    }

    /** "HTTP 402, code 1008", "HTTP 503", "code 1008" or "". */
    public String detail() {
        String status = http > 0 ? "HTTP " + http : "";
        if (code == 0) return status;
        return status.isEmpty() ? "code " + code : status + ", code " + code;
    }
}
