# Brief: phone-thread-2 — an older task never shows as newest mid-session; an emoji cut in half is no duplicate

## Why (lead review of phone-thread, merged 849bc4a)
1. `PhoneThread.merge` rule (c) (assets/agent/phone.html ~319) only compares a task with the newest minute of `hist`, and
   `hist` is fetched once per page load (~629–636). Sequence: the page never sees planned task P finish (phone locked), then
   sees a newer quick task Q finish, then the bridge restarts and `state.task` falls back to last_task.json = P. `done` is
   [Q, P] and P is drawn below Q as the newest. Probe: history newest 14:05, Q 「暂停」 at 14:08, then P 「打开B站搜索周杰伦」
   at 14:06:30 gives bubbles `… 放首歌 | 暂停 | 打开B站搜索周杰伦`.
2. `cut()` (~255–258): when the 80- or 120-unit cut splits an emoji it keeps the lone high surrogate. The TV writes history
   with getBytes(UTF_8), which turns that half into '?', so rule (d) never matches and the exchange shows twice. Probe: reply
   = '好'×119 + 😀 + more text.
3. Names that hide outer names: plan()'s local `done` (~446) hides the main script's `var done` (~342); the parameter of
   `at(when, now)` (~246) hides `function when(t)` (~261). No bug today; rename so a later edit cannot pick the wrong one.

## What to build
1. In merge's loop keep a running maximum of the times of the tasks already joined; a task older than it is skipped (rule
   (c) still applies against the history).
2. In cut(): if the last unit after cutting is a high surrogate (0xD800–0xDBFF), replace it with '?', as the TV does.
3. Rename plan()'s local `done` (e.g. `past`) and at()'s parameter (e.g. `text`).
4. In `tests/agent/phone_thread_test.mjs` add the two probes above as cases (P never last after Q; the emoji reply once).
   The page stays ES5 (no arrow functions, let/const, template strings, spread) and nothing else changes.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java`. No TV, adb, browser or npm.
- The strings PhoneChecks looks for stay; the page must not contain http:// or https://, comments included.

## Acceptance (run all of them yourself before you finish)
- `H:/tools/node/node-v24.22.2-win-x64/node.exe tests/agent/phone_thread_test.mjs` → `PHONE THREAD OK`
- `sh tests/agent/run_core.sh` → its last line `agent core: N passed, 0 failed` with N ≥ 3557
