# Brief: own-ui — 星仪 knows its own panel, does not open apps to look for it, and gets the user out of a black screen

## Why (the user, on the TV, 2026-11-11 21:12–21:18 — every line below is from that evening's transcripts)
Two requests in a row had shown no panel (one fell into an install, the other was a song, whose path showed none), so
the user asked: 「为什么现在HARNESS面板没有打开」. "HARNESS 面板" is the user's name for 星仪's own panel — the box at
the top right that shows what was said, the steps and the answer. 星仪 did not know that, and:

1. took it for an app: `list_apps`, `explore_app`, `open_app 星仪电视助手`, `open_app Fluid Lab`. Fluid Lab is a
   developer's test app whose screen is black; 星仪 left it in front. The user was looking at a black TV.
2. 「你帮自己修复呀」 → `self_check` → 「自检正常…HARNESS 面板不是坏了，是这台电视上本来就没这个独立入口」.
3. 「那你想办法给他装上」 → `find_installer {"app":"HARNESS"}` → an answer about "Harness On-Call" on Google Play.
4. 「你看现在屏幕就是错的呀什么都没有」 — `screen_look` had told it twice 「屏幕大部分为黑色」; it did nothing about it.
5. 「你看我现在每次给你提示提示提示提示提示」 → `set_hints {"on": false}` (「好的，以后亮屏不弹提示了」), and
   「现在就是因为没有提示我看不到我的输入你懂吗」 → `set_hints {"on": true}`. The user meant the panel; the tool is the
   switch of a different thing (the quiet card when the screen comes on).

The lead has already changed the song path (it shows the panel now) and writes the contract's text about the panel.
This job is the part that must hold whatever the model thinks.

## What to build

### 1. `OwnApps.java` (new, pure, package `xyz.erapi0neer.xingyi.agent`)
```java
/** The developer's own test apps on this TV: never listed for the model, never opened by it. */
public static boolean hidden(String pkg)   // xyz.erapi0neer.fluidlab, xyz.erapi0neer.xingyi.voicecheck; null → false
```
`tv/TvTools.java`, `apps()`: a package for which `OwnApps.hidden(pkg)` is true is skipped (so it is not in
`environment.apps`, `list_apps`, and `open_app` / `explore_app` cannot resolve it). Nothing else in `apps()`.

### 2. A black screen: `QuickCommand.java` and `tv/TvTools.java`
- `QuickCommand`: a complaint that the screen is black. The sentences (each the whole sentence): 黑屏了, 屏幕黑了,
  屏幕黑屏了, 电视黑屏了, 电视屏幕黑了, 电视屏幕都黑了, 屏幕怎么黑了, 怎么黑屏了, 屏幕全黑了, 屏幕是黑的, 画面黑了,
  没画面了, 屏幕没画面了, 画面没了, 屏幕什么都没有, 屏幕上什么都没有, 屏幕不显示了 — with an optional 你看 / 现在 in
  front and an optional 呀 / 啊 / 呢 / 吧 / 。 / ！ at the end →
  `cmd("media_control", "action", "exit", "blank", true)`. (`reply` for an exit already is the tool's `message`.)
  **`normalise` takes a trailing 了 off, and 「黑屏」 without 了 must not match** (it may be a wish to make the screen
  dark): so match these on the sentence as it was said (`utterance`, trimmed), not on `s`. Try it in `match` before
  `screen_off` is looked up.
- `TvTools`, `exitApp(...)` gets to know `blank` (pass it from the `media_control` tool's `run`: `flag(a, "blank")`):
  - the home screen is in front and nothing plays → instead of 「已经在桌面了」:
    `fail("at_home", "现在前台就是桌面：先用 screen_look 看看画面到底是什么样，再告诉用户；不要打开别的应用")` (the model then
    takes the request);
  - an app is left → the message of today with one clause added: `"已退出" + app + "，回到桌面"` becomes
    `"刚才前台是" + app + "，已经退回桌面"` when `blank` (only the wording; everything else as today).
- `blank` is not in the contract (only the quick command sends it).

### 3. `set_hints` only when it was asked for: `Hints.java` (pure) and `HintTools.java`
```java
/** +1: the sentence asks for the screen-on card to be shown; -1: asks for it to stop; 0: neither. */
public static int switchAsked(String utterance)
```
- −1: the sentence has 提示 (or 提示卡) together with one of 别弹 / 不要弹 / 不用弹 / 别再弹 / 关掉 / 关闭 / 关了 / 取消 /
  不要 / 别给我 / 停掉 — 「别弹提示了」「关掉提示」「把亮屏提示关了」「不要提示了」.
- +1: 提示 together with 打开 / 开启 / 恢复 / 重新弹 / 继续弹 / 要弹 — 「打开提示」「恢复亮屏提示」.
- 0 for everything else, and always 0 when the sentence has 为什么 / 怎么 / 看不到 / 没有提示 / 每次 / 我的输入 in it
  (「现在就是因为没有提示我看不到我的输入你懂吗」, 「你看我现在每次给你提示提示提示提示提示」, 「为什么没有提示」).
- `HintTools`: the tool's `gate(a, turn)`: `switchAsked(turn.utterance())` must be +1 for `on: true` and −1 for
  `on: false`; otherwise
  `Gate.deny("用户这句话不是在要求开关亮屏提示卡，不要改这个设置。他说的“提示”“面板”多半是星仪自己右上角的执行面板：照实回答面板的事。")`.
  (The gate was `Gate.read()`; when it lets the call through it stays that.)

### 4. A question about 星仪's own panel opens nothing: `Dispatch.java`
`asksOnly(text)` is also true when the sentence is a why / how question (为什么 / 为啥 / 怎么 / 怎么回事 / 咋) **about
星仪's own interface** — it names 面板, 提示卡, 弹窗, HARNESS (any case), 星仪 or 你自己 — even though an action word
(打开, 关…) is in it: 「为什么现在HARNESS面板没有打开」, 「星仪的面板怎么不弹了」, 「为什么面板关了」.
Unchanged: 「为什么B站打不开」 (not about 星仪's interface → not asks-only, as today), 「打开星仪」 (no question),
「星仪帮我打开B站」.
(`AgentLoop` already refuses `open_app`, `open_link` and the like for an asks-only request and tells the model to
answer in words.)

## Checks
- `tests/agent/SkillChecks.java` (a new group `ownApps()`): `OwnApps.hidden` for the two packages, for
  `xyz.erapi0neer.orrery`, `xyz.erapi0neer.xingyi`, `com.ktcp.video`, null, "".
- `tests/agent/QuickChecks.java` (a new group `blank()` called from `run()`): every sentence of 2 →
  `media_control` `{"action":"exit","blank":true}`, also 「你看现在屏幕黑了」 and 「屏幕黑了呀」; 「回到桌面」 is still what it was;
  `none`: 黑屏 · 屏幕黑了怎么办 · 为什么黑屏 · 黑屏是什么原因 · 把屏幕调黑一点 · 黑屏一下.
- `tests/agent/HintChecks.java` (a new group, called from its `run()`): `Hints.switchAsked` — −1: 别弹提示了 / 关掉提示 /
  把亮屏提示关了 / 不要提示了; +1: 打开提示 / 恢复亮屏提示 / 开启提示卡; 0: the three sentences of the user above,
  为什么没有提示, 提示, 你好, null, "". And the gate: a `set_hints {"on":false}` for
  「你看我现在每次给你提示提示提示提示提示」 is denied and nothing is saved; for 「别弹提示了」 it runs; `{"on":true}`
  for 「别弹提示了」 is denied.
- `tests/agent/DispatchChecks.java` (a new group): the three asks-only sentences of 4 are true; the three "unchanged"
  ones keep their value (state it: false, false, false).
- At least 45 checks in all.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28. Do not change `assets/agent/contract.json`.
- In `TvTools.java` touch only `apps()`, `exitApp(...)` and the one line of `media_control`'s `run` that calls it; no new
  `import` lines.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 45 more than before you
  started (the job's check requires the number in the job file)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_own_ui.sh` → `OWN UI OK`
