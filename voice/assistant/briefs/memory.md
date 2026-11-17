# Brief: memory — the user can make 星仪 remember and forget, and teach it how a name is written

## Why
The user (2026-11-05): 「各种记忆管理很重要」. Today 星仪 has a profile file `files/agent/profile.json`
`{"aliases": {heard: written}, "facts": [..]}`: the facts are appended to the system prompt under 「## 关于用户」 and the
aliases rewrite misheard names before the model sees the sentence (`Grounding.canonical`; e.g. XiaoAi hears 星辰亻旅行者
as 星辰人旅行者). Nobody can change it by voice: 「记住我喜欢林俊杰」「以后说我的频道就是星辰亻旅行者」「忘掉我喜欢林俊杰」
「不是星辰人旅行者，是星辰亻旅行者」 all go nowhere. This job adds the pure logic and three tools; the lead wires them into
the engine and the contract.

## What to build (pure Java, package `xyz.erapi0neer.xingyi.agent`, no android.*)
1. `src/xyz/erapi0neer/xingyi/agent/Memory.java` — edits a profile `JSONObject` in place:
   - limits: `MAX_FACTS = 60`, `MAX_FACT_CHARS = 80`, `MAX_ALIASES = 40`, `MAX_ALIAS_CHARS = 20`.
   - `public static String addFact(JSONObject profile, String fact) throws JSONException` → one of
     `"added"`, `"added:dropped_oldest"` (the list was full: the oldest fact goes), `"updated"` (it replaced a near-duplicate),
     `"duplicate"`, `"rejected:empty"`, `"rejected:too_long"`, `"rejected:secret"`.
     Trim, collapse whitespace, drop a trailing 。. Secret = contains 密码/口令/验证码/支付密码/银行卡/卡号/身份证, or 6+ digits in
     a row: never stored. Two facts are the same when equal after removing spaces and punctuation; a near-duplicate is one
     that contains the other, or whose `Grounding.distance(a, b, k) <= k` with `k = max(2, shorter length / 5)` → replaced in
     place (keeps its position). A missing `facts` array is created.
   - `public static List<String> forget(JSONObject profile, String what) throws JSONException` → removes every fact whose
     normalised text contains the normalised `what` (or is a near-duplicate of it), and every alias whose heard or written
     form contains it; returns what was removed (facts as they were, aliases as 「heard→written」). Empty `what` removes nothing.
   - `public static String addAlias(JSONObject profile, String heard, String written) throws JSONException` →
     `"added"`, `"updated"` (same heard form, new written form), `"duplicate"`, `"rejected:empty"`, `"rejected:too_long"`,
     `"rejected:same"` (heard equals written), `"rejected:full"` (40 aliases and a new key). A missing `aliases` object is created.
2. `src/xyz/erapi0neer/xingyi/agent/MemoryTools.java`:
   - `public interface Store { JSONObject load() throws Exception; void save(JSONObject profile) throws Exception; }`
   - `public static List<Tool> create(Store store)` → three `Tool`s (see `Tool.java`; `gate` returns `Tool.Gate.read()`
     for all three: they change no TV state, so they work without a plan):
     - `remember` {fact}: `describe` → 「记住：<fact>」; `run` loads, `Memory.addFact`, saves only when something changed;
       result `{ok, status, message, facts}`: ok=true for added/updated/duplicate (status `recorded` or `duplicate`, message
       e.g. 「记住了：<fact>」「本来就记得：<fact>」), ok=false status `rejected` with a Chinese message for the rejections
       (secret: 「这类信息（密码、验证码、证件号）不记」); `facts` = the number of facts now.
     - `forget` {what}: `describe` → 「忘掉：<what>」; result ok=true `status` `forgotten` with `removed` (JSONArray) and
       「已忘掉 N 条」, or ok=true `status` `nothing` and 「没有和「<what>」有关的记忆」.
     - `remember_name` {heard, written}: `describe` → 「记住写法：<heard> → <written>」; `Memory.addAlias`; ok for
       added/updated/duplicate, ok=false `rejected` otherwise.
     - a `Store` failure → ok=false `status` `error`, message 「记忆没存上：…」; never throw from `run`.
   - helpers short; `MemoryTools.java` under 150 lines.

## Checks
`tests/agent/MemoryChecks.java` (an empty stub; `AgentCoreTest.main` already calls `MemoryChecks.run()`), with an in-memory
`Store` for the tools. At least 30 labelled checks: every status of `addFact` (including the 61st fact dropping the
oldest, 「我喜欢林俊杰。」 then 「我喜欢林俊杰」 → duplicate, 「我喜欢林俊杰」 then 「我最喜欢林俊杰」 → updated in place,
「我的银行卡号是6222020200112233」 → rejected:secret), `forget` (by a word, removes the matching fact and alias, keeps the
rest, empty `what` removes nothing), every status of `addAlias`, each tool's result JSON (ok/status/message fields), that
the tools save only on a change (count `save` calls), and that a throwing `Store` gives status `error`.

## Acceptance
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 318 (288 before this job).
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`.
- `sh tests/agent/accept_memory.sh` → checks the API above exists and the files stay pure; prints `MEMORY OK`.
