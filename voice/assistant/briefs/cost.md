# Brief: cost — 每单的真实成本自己记账，不再靠我口算

## Why (the user's yardstick: 每个成功任务的总成本; and tonight)
Tonight 67 worker jobs have run and nobody can say what they cost. The number is on disk and unused: every `log.jsonl`
ends with a `result` event holding `total_cost_usd`, `usage.input_tokens / cache_read_input_tokens / output_tokens /
output_tokens_details.thinking_tokens`, `duration_ms`, `num_turns`, and a per-model `modelUsage` block. `run.mjs`
already counts tool calls per round (`history[].tools`) and model turns (`history[].models`), but throws all of this
away — so `status/BOARD.md`'s 「消耗」 section was written from my arithmetic in chat, and a $36.93 job
(`verify-durable`, 5 rounds) looked like any other.

The totals, computed by hand once: **67 jobs, $260.78**. The most expensive: `verify-durable` $36.93, `quick-info-2`
$13.48, `phone-console` $10.26. That is the账 a worker must never have to guess again.

## What to build

### 1. `tools/cost/cost.mjs` — the accounting, pure
`export function roundCost(resultEvent)` → the cost of one round, or `null` when the event is not a `result`:
```js
{ usd: 9.074, ms: 536657, turns: 18, inTok: 64009, cacheTok: 11497136, outTok: 120197, thinkTok: 77657,
  models: { "MiniMax-M78": { usd: 9.074, outTok: 120197 } } }
```
- `result` events are **cumulative within one session** (each one reports the session so far): `verify-durable`'s five
  events read $4.82, $6.15, $6.95, $9.07, $9.94 — the job cost $9.94, not $36.93. `roundCost` gives one event's own
  figures; the **delta** between consecutive events of one job is what one round cost. Write that as
  `export function roundDeltas(events)` → an array of the same shape, one per event, the first being the whole first
  event and each later one the difference from the one before. Guard: if an event's numbers are *lower* than the
  previous (a fresh session id after a model switch restarts the count), the delta is the whole event — never negative.
- Missing or non-numeric fields count as 0; `usd` may be `null` when the runner reported no cost (`total_cost_usd`
  absent), and then `roundDeltas` carries `null` through rather than inventing 0.

`export function summary(jobs)` → the 「消耗」 section's numbers as data:
```js
{ jobs: 67, rounds: 74, tools: 2510, usd: 260.78, outTok: 3241905, msu: { "MiniMax-M78": 260.78 },
  dearest: [{ id: "verify-durable", usd: 9.94 }, …] }   // dearest: top 5, descending
```
`jobs` is an array of `{ id, history }` where each history item is what `run.mjs` writes plus `cost` (the step-3 shape);
`rounds` counts history items; `tools` sums `history[].tools`; `usd` sums non-null `cost.usd`; `dearest` sorts by that.

`node tools/cost/cost.mjs [--jobs <dir>] [--out <path>]` — reads every `state.json` (for `history[].cost`) and, where a
`history[].cost` is absent, digs it out of that job's `log.jsonl` (`roundDeltas` over its `result` events) — the same
retroactive repair a reader of tonight's jobs needs. Prints one table: job, rounds, tools, $, and a total line; writes
nothing unless `--out`.

### 2. Honesty about what a missing number means
A job whose `log.jsonl` has no `result` event (it was killed) prints `$?` — not `$0.00`. The summary counts such a job
in `jobs` and `rounds`, and adds `unknown: <id>, …`; `usd` is then a floor, and the CLI prints `（N 单没有成本，合计是下限）`.
Same rule as `Repairs`/`Unverified`: what was not measured is said to be not measured.

### 3. `Cost.java` (new, pure, package `xyz.erapi0neer.xingyi.agent`) + checks
The board reads `state.json` from Java, so the same rules get a Java port that cannot drift:
```java
public static Cost of(org.json.JSONObject resultEvent)                       // the step-1 shape, or null
public static java.util.List<Cost> deltas(org.json.JSONArray events)         // the same, per round
public static String line(java.util.List<org.json.JSONObject> histories)     // 「今天：67 单、74 轮、$260.78」 or
                                                                             // 「今天：3 单、4 轮、$12.30（1 单没有成本，合计是下限）」
public static String dearest(java.util.List<org.json.JSONObject> jobs, int n) // the top-n lines `- <id> $9.94 (5 轮)`
```
`tests/agent/CostChecks.java`, at least 16 checks, run from the last line of `tests/agent/SkillChecks.run()`, reading
fixtures under `tests/agent/cost-fixtures/` (new directory — `tests/agent/fixtures/` is the lead's):
- a single event → its own numbers; two cumulative events → the second's delta is the difference (the `verify-durable`
  case: $4.82 then $6.15 → $1.32, not $6.15);
- a session restart (numbers drop) → the whole event, never negative;
- a missing `total_cost_usd` → `null` usd, carried through `deltas` and counted in `line`'s 「没有成本」;
- missing usage fields → 0, not a crash; a non-`result` object → `null`;
- `line` with 0, 1, many jobs; with an unknown one; its wording exactly as specified;
- `dearest` top-n ordering and the 5-limit;
- `board.mjs` and `Cost.java`: the literal `今天：` appears in both files' text (the format string cannot drift).

### 4. What I wire in `run.mjs` (do not edit it)
In your report, say the exact two edits: after `st.history.push({...})` add
`st.history.at(-1).cost = delta(roundResultEvent)` using `roundDeltas`'s last element, and import `summary` in the
`board` branch. That is my one-line job once `board` lands; you write the functions it calls.

## Rules
- Only the files you own: `tools/cost/cost.mjs`, `src/xyz/erapi0neer/xingyi/agent/Cost.java`,
  `tests/agent/CostChecks.java`, `tests/agent/cost-fixtures/`, and the single line at the end of
  `tests/agent/SkillChecks.run()`. Never open or grep `tests/agent/AgentCoreTest.java` or
  `src/xyz/erapi0neer/xingyi/LocalAdb.java`. Do not touch `tools/m31w/run.mjs`, `NEXT.md`, `HARNESS.md`, the bridge.
- No TV, no adb, no network. Java 8, no android import.
- Read the real `log.jsonl` files of two or three jobs under `voice/assistant-out/jobs/` to get the shapes right, but
  **do not** copy any of them into fixtures (they are 200 KB+ and hold prompts); write small fixtures by hand.

## Acceptance (run all of them yourself before you finish)
- `node tools/cost/cost.mjs` → a table whose total line names the real figure and ends with `单、` … `$` and no `NaN`
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`, N ≥ 5097
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_cost.sh` → `COST OK`
