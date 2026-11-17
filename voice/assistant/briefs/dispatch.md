# Brief: dispatch — what happens when the user speaks while 星仪 is busy, asking, or idle

## Why
The user (2026-11-05) asked to fill the harness's gaps against an agent-architecture checklist (对话流, 任务空间和任务
调度). An audit of `tv/AgentEngine.java` found these, all on the TV, all user-visible:
1. Every new sentence cancels the running task (`submit`: `if (busy) cancel();`). 「音量调大一点」 in the middle of a B站
   task kills it, and the user first hears 「已停止。」 for the old task, then the volume.
2. A task cancelled because a NEW request arrived still speaks 「已停止。」 and shows 已停下 — the user moved on.
3. 「停」 while 星仪 waits for an answer (`AWAITING_USER`, within the 180 s answer window) does nothing: the task is not
   busy, so `submit` returns "idle"; the question stays pending and its panel keeps saying 等你回答.
4. 「停」 with nothing running and nothing pending gets no reply at all (and XiaoAi's own voice is already cut).
5. The ask / confirm panel never hides by itself (`Hud`: running/confirm/ask have no timeout): after the 180 s answer
   window it still says 等你回答 and keeps animating.
6. The 「好的，…」 opener (`acknowledge`, 2.5 s after a multi-step plan) checks `t.state.open()`, which is also true while
   AWAITING_CONFIRMATION, so it can talk over the consent prompt.
7. A question asked without a plan (「放那个」 → 「放哪一首？」) is never saved as `last_task.json` (`Store.remember` saves
   planned tasks only), so the answer's `previous_task` is some older task.
8. `resume` returns only when `pending > 1`: a request queued right at service start (`pending == 1`) is then
   superseded by the resume's generation and silently dropped. It must be `pending > 0`. (The javadoc says 「at most
   twice」, the code allows 3 resumes: make the comment say 3.)

## Read first
`src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java` (all of it: `submit`, `cancel`, `run`, `runQuick`, `ran`,
`alreadyDone`, `onEvent`, `hud`, `acknowledge`, `speak`, `spoken`, `resume`, `Store.remember`), `tv/Hud.java`,
`src/xyz/erapi0neer/xingyi/agent/QuickCommand.java` (`match`, `tool`, `args`), `Replies.java` (`stop`, `consent`),
`Ledger.java` (states, `open()`), `HARNESS.md` 「星仪接管模式、快速命令…」.

## What to build
### 1. `src/xyz/erapi0neer/xingyi/agent/Dispatch.java` (pure, no android.*) — the routing decision, testable offline
```java
public enum Route { CONSENT, STOP_TASK, DROP_QUESTION, IDLE_STOP, SIDE, SUPERSEDE, START }
/** consentReply: Replies.consent(text) while a consent is pending (null = not a yes/no or nothing pending). */
public static Route route(boolean consentPending, Boolean consentReply, boolean stopWord, boolean busy,
                          boolean questionPending, boolean sideCommand)
/** Commands that may run beside a running task without stopping it: the volume, nothing else. */
public static boolean side(QuickCommand q)   // q != null && q.tool is set_volume or adjust_volume
```
Order: a pending consent with a yes/no → CONSENT; a stop word → STOP_TASK when busy, else DROP_QUESTION when a question
is pending, else IDLE_STOP; busy and a side command → SIDE; busy → SUPERSEDE; otherwise START. (A pending consent with
another sentence keeps today's behavior: the waiting step is declined and the sentence routes on as above.)
Also `public static final long ANSWER_WINDOW_MS = 180_000;` used by `state()` and the panel instead of the literal.

### 2. `AgentEngine.submit` uses `Dispatch.route` (same return strings as today, plus "side")
- CONSENT → as today ("approved"/"declined").
- STOP_TASK → as today (`cancel()`, "cancelled"); the stopped task still says 「已停止。」.
- DROP_QUESTION → the pending task becomes CANCELLED with reply 「好的，不问了。」, is stored (`store.remember`), its panel
  hides at once, and 「好的。」 is spoken (only for spoken sources); returns "cancelled".
- IDLE_STOP → speak 「好的。」 for spoken sources; returns "idle".
- SIDE (busy + volume command, matched with `QuickCommand.match(text, Collections.emptySet())`): do NOT cancel. Run it
  on a second single-thread executor: its own `TvTools` (try-with-resources), `xiaoaiDid` set from the call's
  argument, the tool's gate (DENY → `alreadyDone`, as `runQuick` does), `ran` otherwise; speak the reply with
  `VendorSpeech` directly. It must not touch the running task's fields (`task`, `loop`, `timings`, `taskStarted`,
  `firstSpeechMs`, `lastAction`) nor its panel; record its habit call the way `runQuick` does and one perf line with
  quick = true and its own wall time. Returns "side". If anything fails, fall back to SUPERSEDE.
- SUPERSEDE → cancel the running task quietly: it ends CANCELLED as today but speaks nothing and shows no 已停下 panel
  (the new task's panel replaces it); then start the new task as today. A flag set before `cancel()` and read in
  `onEvent("finish")` is enough because the worker thread runs the old task to its end before the new one starts.
- START → as today.

### 3. Panels (`Hud.java`, `AgentEngine.hud`)
- An `ask` panel hides when the answer window ends (`Dispatch.ANSWER_WINDOW_MS` after it appeared) and a `confirm` panel
  65 s after it appeared (the consent wait is 60 s); both stop animating when hidden. A dropped question hides its
  panel at once. Everything else keeps today's timing.

### 4. Smaller fixes
- `acknowledge`: speak the opener only if the task is still `RUNNING` 2.5 s later.
- `Store.remember`: also save `last_task.json` for a task in `AWAITING_USER` without a plan.
- `resume`: return when `pending > 0`; javadoc 「at most three times」.

## Checks — `tests/agent/DispatchChecks.java` (the stub is already called by `AgentCoreTest.main`)
At least 35 checks of `Dispatch.route` and `Dispatch.side`: every row of the order above, including the combinations
that must NOT happen (a stop word while busy never drops a question; a side command while idle is START; a side
command during a pending consent with a non-yes/no sentence; `side` for each QuickCommand kind: only set_volume and
adjust_volume — use `QuickCommand.match` on real sentences such as 「音量调大一点」「音量调到20」「静音」「暂停」「下一首」
「回到桌面」「打开相册」), and `ANSWER_WINDOW_MS == 180_000`. AgentEngine and Hud are Android code: they are checked by the
TV build in the acceptance script, so keep the logic that needs a test in Dispatch.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 840
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_dispatch.sh` → `DISPATCH OK` (builds the TV app with your sources, runs the bridge's JVM suite,
  checks that AgentEngine uses Dispatch.route, has the side executor, the quiet supersede, `pending > 0`, and that
  Dispatch.java imports no android.*)

## Files you own
`src/xyz/erapi0neer/xingyi/agent/Dispatch.java`, `src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java`,
`src/xyz/erapi0neer/xingyi/agent/tv/Hud.java`, `tests/agent/DispatchChecks.java`.
