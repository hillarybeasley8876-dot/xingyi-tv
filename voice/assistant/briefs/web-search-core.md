# Brief: web search for the agent — the client and the speakable text

## Why
星仪 cannot look up live information (weather, prices, scores, news). The gateway it already uses also offers OpenAI's
Responses API with a `web_search` tool (model `gpt-5.4-mini`), measured on 2026-11-04 at 6–10 s with real results. The
lead will add a `web_search` tool to the agent and wire it on the TV. Your job is the pure-Java core that tool calls:
build the request, send it through a pluggable transport, turn the reply into one or two speakable sentences.

Real replies from the gateway are in `tests/agent/fixtures/websearch/`:
`websearch-0.json` (gold price, 2026-11-04 morning), `websearch-1.json` (gold price in RMB, ends with a markdown citation
`([sge.com.cn](https://...))`), `websearch-2.json` (Shanghai weather), `websearch-3.json` (tonight's NBA games, long).

## What to build
1. `src/xyz/erapi0neer/xingyi/agent/ResponsesText.java`: copy the bridge's version
   (`H:/tools/mitv/xiaoai-bridge/src/xyz/erapi0neer/xiaoaibridge/ResponsesText.java`, read-only to you), change the package to
   `xyz.erapi0neer.xingyi.agent`, make the class, `extract` and `usedSearch` `public`. Behaviour unchanged.
2. `src/xyz/erapi0neer/xingyi/agent/WebSearch.java` (pure Java + org.json + java.net), with exactly this API:
   - `public static final String DEFAULT_MODEL = "gpt-5.4-mini";`
   - `public interface Transport { Reply post(String url, String bearerKey, String jsonBody, int timeoutMs) throws IOException; }`
   - `public static final class Reply { public final int status; public final String body; public Reply(int status, String body) }`
   - `public static final class Result { public final String answer; public final boolean searched; public final long millis; }`
   - `public WebSearch(String baseUrl, String apiKey, String model, Transport transport)` — one trailing `/` of baseUrl is
     dropped; a null or empty model means DEFAULT_MODEL.
   - `static String instructions(String now)` returns exactly:
     `"你在帮中国大陆一户人家的电视语音助手查实时信息。现在是 " + now + "（北京时间）。先联网搜索，再用一两句中文说清结论：数字、日期、地点要具体；价格、汇率、油价、金价按人民币和国内的报价说。可以说来源名称，不要念网址，不要用 markdown，不要列表。"`
   - `static JSONObject body(String model, String instructions, String query) throws JSONException` →
     `{"model":model,"instructions":instructions,"input":[{"role":"user","content":query}],"tools":[{"type":"web_search"}],"max_output_tokens":600}`
   - `public Result search(String query, String now, int timeoutMs) throws IOException`:
     - query is trimmed; empty or longer than 300 chars → `IllegalArgumentException("搜索内容要在 1 到 300 字之间")`; timeoutMs < 1000 → `IllegalArgumentException`. In both cases the transport is never called.
     - POST to `baseUrl + "/v1/responses"` through the transport, bearer = apiKey, body = `body(model, instructions(now), query).toString()`.
     - status 401 or 403 → `IOException("联网搜索的线路鉴权失败")`; 429 → `IOException("联网搜索太忙了，稍后再试")`; any other non-200 → `IOException("联网搜索失败（HTTP " + status + "）")`. An IOException from the transport propagates unchanged.
     - answer = `ResponsesText.extract(body)`; "" → `IOException("联网搜索没有返回结果")`.
     - an answer longer than 400 chars is cut after the last 。！？ within the first 400 chars; with none there, the first 400 chars + "…".
     - `searched` = `ResponsesText.usedSearch(body)`; `millis` = elapsed wall time of the call.
   - `public static Transport https()` — the real transport: `HttpURLConnection` POST with headers
     `Content-Type: application/json; charset=utf-8`, `Accept: application/json`, `Authorization: Bearer <key>`; connect timeout
     `min(8000, timeoutMs)`, read timeout `timeoutMs`; request body UTF-8; reads the body (the error stream when status ≥ 400)
     as UTF-8, more than 1 MiB → `IOException("联网搜索的回复太大")`; always disconnects. The checks do not run it (no network);
     keep it short and obviously right.
3. `tests/agent/WebSearchChecks.java` — fill in `run()` (the class is an empty stub; `AgentCoreTest.main` already calls it).
   Every assertion through `AgentCoreTest.check(ok, label)`. At least:
   - the 13 cases of `H:/tools/mitv/xiaoai-bridge/tests/ResponsesTextTest.java`, ported (its fixture is now `tests/agent/fixtures/websearch/websearch-0.json`, same content; expected text `今天上海金早盘价847.21元/克，午盘价841.08元/克。`);
   - fixtures 1–3: `extract` is non-empty and contains none of `http`, `](`, `*`, `#`, `sge.com.cn`; fixture 1 contains `元`; all three `usedSearch` true;
   - `body(...)`: the six fields above with the right values;
   - `search(...)` with a fake Transport that records its arguments: the URL (also when baseUrl ends with `/`), the bearer key, the parsed body equals `body(...)`; 200 + fixture 2 → answer equals `extract(fixture 2)` and searched is true; 401, 403, 429, 500 → the IOException messages above; 200 with a reply that has no message → `联网搜索没有返回结果`; query `""`, `"   "`, 301 chars, timeout 999 → IllegalArgumentException and the transport was not called; the transport's own IOException comes out unchanged (same object); a reply of 500 chars made of sentences is cut to at most 400 chars ending with 。, one with no sentence end to 401 chars ending with …;
   - `instructions("2026-11-04 23:10 星期三")` contains that string and `人民币`.

## Acceptance
- `sh tests/agent/run_core.sh` prints `agent core: N passed, 0 failed` with N ≥ 180 (it is 144 before your job).
- `sh tests/agent/android_check.sh` prints `ANDROID CHECK OK`.
- The API lines above exist verbatim (the runner greps for them); no `android.` imports in your two source files.
