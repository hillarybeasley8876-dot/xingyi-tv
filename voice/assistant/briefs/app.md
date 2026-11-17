# Brief: app — the 星仪 app follows the engine faithfully, and gets 历史 and 记忆

## Why
The user (2026-11-05) asked to fix everything the harness audit (`AUDIT.md`) left open. The 星仪 app
(`src/xyz/erapi0neer/xingyi/MainActivity.java`, the TV screen app; the agent itself runs in the XiaoAi bridge and is
reached through `agent/tv/AgentClient`) has these faults:
1. Typing 好的 / 算了 / 停 / a volume command while 星仪 works: `submitFrom` treats every result except "idle" and
   "unavailable" as a new task and waits for one that started after the submit (`applyAgentState`, the
   `task.startedAt >= agentSince - 5000` test). For "approved", "declined", "cancelled" and "side" no new task starts,
   so the screen stays on 「正在理解你的意思…」 with 发送 disabled.
2. When a consent is answered elsewhere (by voice through XiaoAi, or from the PC), the app keeps showing 等你确认.
3. After the activity is recreated (or the user comes back to it), it does not pick up a task that is still running.
4. While the app is in front the floating panel is hidden, so a task the user started by voice is shown nowhere.
5. There is no way to see what 星仪 did (history) or what it remembers (memory) other than asking by voice.
6. Two runtimes: 「看当前屏幕」 and the 「小爱自动接手」 hand-offs still run the old in-app `AgentHarness`
   (`startHarness`, `playHandedOffSong`) beside the engine. 「小爱自动接手」 is obsolete since the bridge's takeover
   mode hands every XiaoAi request to the engine.

## Read first
`MainActivity.java` (all of it), `AssistantLayout.java` (buttons, focus order, `makeButton`, `addSingle`, `addPair`),
`TaskProgressView.java` (`agent(...)`, `finish(...)`), `agent/tv/AgentClient.java` (every method: `submitTracked`,
`state`, `consent`, `cancel`, `memory`, `forget`, `history`, `hints`), `agent/Ledger.java` (`fromJson`, states, `open()`),
`agent/tv/AgentEngine.java` only to understand `state()` (keys: busy, awaiting "" | "consent" | "answer", prompt, task,
request) and the submit results ("started", "approved", "declined", "cancelled", "side", "idle", "empty").

## What to build (only in the files you own)
### 1. Follow the engine by request id
- `submitFrom` uses `AgentClient.submitTracked(this, text, source)`: result "started" → remember `request` and watch
  until `state.request == request` and the task is finished (not busy, not awaiting) or awaiting; other results are
  final at once: approved → 「已确认，继续执行」 and keep watching the task that is running; declined → 「这一步不做了」;
  cancelled → 「已停止」 (or the question was dropped); side → 「已调好音量，正在做的事没有打断」; idle → 「现在没有正在执行的
  任务。」; unavailable → as today. In every final case 发送 is enabled again and the input focused.
- While watching: if `awaiting` is no longer "consent" but the screen shows 等你确认, update to the task's real state.
- A light poll keeps running while the activity is visible (onStart → onStop), every 1.5 s when idle and 400 ms while
  watching: when not watching and `state.busy` or `awaiting` is set for a request the app has not shown yet (a task
  started by voice, or one still running when the activity came back), start watching it and show it.
- Remove the `agentSince` / `startedAt` heuristic.

### 2. 历史 and 记忆
- In `AssistantLayout` replace the 「小爱自动接手」 button (id `auto_relay`) with a pair: id `history` 「历史」 and id
  `memory` 「记忆」 (`addPair`, same height as the other pairs); keep the focus order working (the column's
  right-neighbour list in AssistantLayout names `auto_relay`: use `history` and `memory` there).
- 历史: `AgentClient.history(this, null, 7, 50)` on the worker thread, then an `AlertDialog` list, newest first, each
  item 「<when> · <said>」 (said cut to 30 chars with …). Picking one shows it in the answer area: 「<when>\n你说：<said>\n
  星仪：<replied>」 + 「\n当时在放：《<playing>》」 when present, and puts `said` into the input box (so 发送 does it again).
  Empty → 「最近 7 天还没有记录。」.
- 记忆: `AgentClient.memory(this)` on the worker thread, then an `AlertDialog` list with one entry per item, grouped in
  this order with a prefix: 「记住的事：」 facts, 「写法：<heard> → <written>」 aliases, 「随手记：<text>（<when>）」 notes,
  「预约：<text>」 schedules, then 「使用习惯（自动整理）」 as ONE entry that shows the habit lines, and a last entry
  「空闲提示：开着 / 关着」. Picking an item asks 「删掉这条？」 (删掉 / 返回) and calls `AgentClient.forget(this, kind,
  which)` with kind fact (which = the text), alias (which = heard), note / schedule (which = the id as a string); the
  habit entry asks 「清空使用习惯？」 → kind habits; the hint entry toggles with `AgentClient.hints(this, !on)`. After
  each change show the returned sentence and reopen the list. Nothing kept → 「星仪还没有记住什么。」.

### 3. One runtime
- 「看当前屏幕」: no screenshot and no `AgentHarness` in the app any more. Ask as today (the dialog), then
  `moveTaskToBack(true)`, wait 1.2 s on the worker thread, and submit 「看看现在屏幕上是什么，用一两句话告诉我」 with source
  "ui" through `submitTracked` (星仪 looks at the TV itself with screen_look; its floating panel shows the answer over
  the app that is in front; the app shows it too when the user comes back).
- `receiveEntry` (the AutoMcpBroker hand-off) submits the request's original text to the engine with source "voice"
  instead of `playHandedOffSong` / `startHarness`; the 「小爱自动接手」 settings are no longer opened from anywhere.
- Delete the members that become unused in MainActivity (`startHarness`, `onHarnessEvent`, `applyStep`,
  `playHandedOffSong`, `songReceipt`, `captureScreen`, `screenJpeg`, …) — only in MainActivity; other classes stay.
  The direct remote-control buttons (`local(...)`: 音量、静音、应用、信号源、设置、回桌面、定时休眠) stay as they are.

## Rules for this job
- Every `AgentClient` call is a cross-process call: never on the main thread (use the existing `worker` executor and
  `main.post`). Guard every UI update with `!destroyed`.
- User-facing strings in Chinese, short, like the existing ones. Keep the existing visual style (do not restyle).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 1146
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_app.sh` → `APP OK` (builds the 星仪 APK with `sh build.sh`, then checks the calls above are
  used, the old harness paths and the `auto_relay` button are gone, and the new button ids exist)

## Files you own
`src/xyz/erapi0neer/xingyi/MainActivity.java`, `src/xyz/erapi0neer/xingyi/AssistantLayout.java`.
