# Brief: schedules — 星仪 can do things later: 「明天早上7点放新闻联播」「每天晚上11点关电视」「8点提醒我吃药」

## Why
The user (2026-11-05) asked to fill the harness's gaps against an agent-architecture checklist; two of its chapters are
「主动性」 and 「场景能力：定时怎么投到主对话」. Today 星仪 cannot do anything at a later time. `sleep_timer` only turns
the TV off after N minutes; XiaoAi's own alarms and reminders still work, but XiaoAi cannot run a 星仪 task at 7:00.
This job builds the schedule book, its three tools, and the TV-side clock that fires due entries. The lead wires the
tools into the engine and the bridge's 20-second heartbeat into your `tick()` after merging.

## Read first
- `assets/agent/contract.json`: the tools `schedule_task`, `list_schedules`, `cancel_schedule` and the section
  「## 定时、随手记和回忆」 of the system prompt. Their argument names and enums are final: implement exactly those.
- `src/xyz/erapi0neer/xingyi/agent/MemoryTools.java` + `tests/agent/MemoryChecks.java`: the pattern to copy for pure
  tools backed by a `Store` (load/save a JSONObject) and their checks.
- `src/xyz/erapi0neer/xingyi/agent/Tool.java` (Tool, Gate.read/act, Turn), `AgentLoop.java`: `schedule_task` and
  `cancel_schedule` are in `DIRECT` (run without a plan) and `ANSWERS` (their result's `message` is spoken as the
  whole answer, no further model round), so `message` must be one natural Chinese sentence.
- `src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java`: `submit(text, source, note, xiaoaiDid)`, `busy()`, `state()`
  (its `"awaiting"` is non-empty while a question or a confirmation is pending), `spoken(source)`, and the inner
  `Store.save` (temp file + fsync + rename): copy that write pattern.
- `src/xyz/erapi0neer/xingyi/VendorSpeech.java`: `speak(Context, String)` speaks through XiaoAi.

## What to build
### 1. `src/xyz/erapi0neer/xingyi/agent/Schedules.java` (pure Java: java.util only, no android.*)
The book is a JSONObject: `{"next_id": 4, "items": [{"id": 3, "kind": "task"|"remind", "what": "播放新闻联播",
"repeat": "once"|"daily"|"weekdays"|"weekends", "hour": 7, "minute": 0, "at": <epoch ms of the next firing>,
"created": <epoch ms>}]}` (a missing or broken book = empty). All calendar math uses the `TimeZone` passed in
(production: the TV's default, Asia/Shanghai; tests pass it explicitly).
- `public static String add(JSONObject book, JSONObject args, long now, TimeZone tz) throws JSONException` — validates
  `args` (the tool arguments) and adds one item; returns the confirmation; a bad request throws
  `IllegalArgumentException` whose message is the Chinese reason the model gets back:
  - `kind` must be task or remind; `what` trimmed, inner whitespace collapsed, 1–60 chars;
  - exactly one of `time` and `in_minutes` (neither / both → "要说清几点，或者多少分钟以后");
  - `time` is `H:mm` or `HH:mm`, 0–23 and 0–59; `date` (`YYYY-MM-DD`) only together with `time`, not before today,
    at most 30 days ahead, and only with repeat once;
  - `in_minutes` 1–1440, only with repeat once;
  - `repeat` defaults to once; once + time without date → today if that time is at least 1 minute ahead, else
    tomorrow; daily → the next day (today included) whose time is ahead; weekdays → next Monday–Friday;
    weekends → next Saturday/Sunday; a once item whose date+time is not in the future is rejected ("这个时间已经过了");
  - at most 20 items ("预约已经有 20 个了，先取消一些");
  - an item with the same kind, what, hour, minute and repeat already exists → nothing added, return
    "已经预约过了：" + description.
- Descriptions (also used by list/cancel and the confirmation), `public static String describe(JSONObject item, long now,
  TimeZone tz)`: hours without a leading zero (`7:00`, `23:30`), days relative to `now`: 今天 / 明天 / 后天, later dates
  as `11月8日周六`; repeating items say 每天 / 工作日 / 周末 instead of a day. kind task: `<when> <what>`
  (「明天 7:00 播放新闻联播」「每天 23:00 关电视」); kind remind: `<when>提醒你：<what>`.
  in_minutes items read like `30 分钟后（14:35）暂停` in the confirmation only.
- Confirmation: `"好的，" + description + "。"` (「好的，明天 7:00 播放新闻联播。」「好的，今天 20:00 提醒你：该吃药了。」).
- `public static List<JSONObject> due(JSONObject book, long now)`: items with `at <= now`, oldest first.
- `public static void fired(JSONObject book, int id, long now, TimeZone tz)`: once → removed; repeating → `at` moves to
  the next occurrence strictly after `now`.
- `public static List<JSONObject> skipMissed(JSONObject book, long now, long graceMs, TimeZone tz)`: items with
  `at < now - graceMs` (the TV was off or the process dead): once → removed, repeating → advanced past `now`;
  returns the skipped items (as they were).
- `public static String list(JSONObject book, long now, TimeZone tz)`: 「现在有 2 个预约：1 号，明天 7:00 播放新闻联播；
  3 号，每天 23:00 关电视。」 sorted by `at`; empty → 「现在没有预约。」
- `public static List<String> cancel(JSONObject book, String which, long now, TimeZone tz)`: `all` (also 全部/所有) →
  everything; a number → that id; otherwise every item whose `what` contains `which` (trimmed); returns the
  descriptions removed (empty when nothing matched).

### 2. `src/xyz/erapi0neer/xingyi/agent/ScheduleTools.java` (pure)
`public interface Store { JSONObject load() throws Exception; void save(JSONObject book) throws Exception; }` and
`public static List<Tool> create(Store store, LongSupplier clock, TimeZone tz)` (java.util.function.LongSupplier is
fine on API 28) returning three tools; `run` never throws (storage failure → `ok:false, status "error", message
"预约没存上：<reason>"`):
- `schedule_task`: `Gate.act()`; describe 「预约：<what>」; success → `{"ok":true,"status":"scheduled","message":
  <confirmation>,"id":n,"at":ms}`; IllegalArgumentException → `{"ok":false,"status":"rejected","message":<reason>}`;
  a duplicate is `ok:true, status "exists"`.
- `list_schedules`: `Gate.read()`; `{"ok":true,"status":"observed","message":<list>,"items":[{id, kind, what, when}]}`.
- `cancel_schedule`: `Gate.act()`; removed → `{"ok":true,"status":"cancelled","message":"取消了：" + joined with "；" +
  "。"}`; nothing matched → `{"ok":false,"status":"not_found","message":"没有找到要取消的预约。" + list}`.

### 3. `src/xyz/erapi0neer/xingyi/agent/tv/Scheduler.java` (Android side, the TV's clock)
- The book lives in `new File(context.getFilesDir(), "agent")/schedules.json`; write it with the temp + fsync +
  rename pattern; a missing or unreadable file is an empty book.
- `public static synchronized Scheduler get(Context context)`; `public ScheduleTools.Store store()` (what the engine
  will register); `public synchronized void tick()`: called every ~20 s by the bridge's heartbeat (the lead wires it);
  it must never throw (catch everything: the heartbeat must survive).
- tick: load; `skipMissed(grace 10 minutes)` (log nothing to the user for those); for each due item, oldest first:
  - remind: `VendorSpeech.speak(app, "提醒：" + what)`, then `fired`;
  - task: when the engine is idle (`!AgentEngine.get(app).busy()` and `state().optString("awaiting")` empty) →
    `fired` + save FIRST (a crash must not fire it twice), then `AgentEngine.get(app).submit(what, "schedule", note,
    null)` with note 「这是用户预约的定时任务（<created as M月d日 H:mm> 定的「<description>」），现在到点了：照 utterance 去做，
    做完用一句话说结果。」; when the engine is busy, leave it due (the next tick retries); if it has waited more than
    10 minutes past `at`, skip it (`fired`) and speak 「预约的「<what>」到点了，但当时在忙，没有执行。」.
  - save after every change.
- Keep it under 200 lines. Nothing in AgentEngine changes in this job.

## Checks — `tests/agent/ScheduleChecks.java` (the stub is already called by `AgentCoreTest.main`)
At least 45 checks with a fixed `now` and `TimeZone.getTimeZone("Asia/Shanghai")`, built with `Calendar` so they do not
depend on the machine's zone: validation (each rejection and its reason); next occurrence: time later today, earlier
today (→ tomorrow), exactly now (→ tomorrow), weekdays from Friday evening (→ Monday), weekends from Monday (→ Saturday),
daily, a date 31 days ahead rejected, a past date rejected, in_minutes 0/1/1440/1441; descriptions (今天/明天/后天/a later
date with weekday, 每天/工作日/周末, remind wording, hours without a leading zero); duplicates; the 20-item cap; due /
fired (once removed, repeating advanced) / skipMissed; list ordering and the empty text; cancel by id, keyword, all and
no match; the tools through `ScheduleTools.create` with an in-memory Store (ok/status/message for success, rejection,
duplicate, storage failure); one loop check: `AgentCoreTest.loop` with a `Script` calling `schedule_task` (no plan) →
one model round, state ANSWERED, reply equal to the confirmation.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 850
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_schedules.sh` → `SCHEDULES OK` (builds the TV app with your sources, runs the bridge's JVM
  suite, checks the public signatures above and that the two pure classes import no android.*)

## Files you own
`src/xyz/erapi0neer/xingyi/agent/Schedules.java`, `src/xyz/erapi0neer/xingyi/agent/ScheduleTools.java`,
`src/xyz/erapi0neer/xingyi/agent/tv/Scheduler.java`, `tests/agent/ScheduleChecks.java`.
