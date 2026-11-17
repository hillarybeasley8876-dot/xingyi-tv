# Brief: wire web search and the honest stop into the TV, and stop B站 runs from stalling

## Why
Two pure-Java pieces just landed in the core: `WebSearch` (+ `ResponsesText`) and the honest early stop (`AgentLoop.observe`,
`Tool.Turn.say`, read-aware `Ledger`). Nothing on the TV uses them yet. And the B站 run of 2026-11-04 22:58 (transcript:
`tests/agent/evidence/bili-latest-2026-11-04/transcript.json`) lost 6 of its 16 rounds because `bili_search` gave a cold
B站 9 s (it needs ~15 s on this TV), and after the video card was clicked `media_now_playing` said "没有正在播放的媒体" —
the video started a few seconds later. Calibration against the real model (2026-11-04 23:20) also showed it will run
`web_search` up to 8 times for one question when results look inconsistent (7 s each): the harness must cap it.

The contract already has the `web_search` tool and the prompt lines (`assets/agent/contract.json`, lead-owned; read it).

## What to build
1. `Tool.Turn` gets `int round();` — the model round (1-based) the current call belongs to. The Turn in `AgentLoop.run`
   returns the loop's round counter (make it reachable from the anonymous class: a field or a one-element array).
2. `src/xyz/erapi0neer/xingyi/agent/SearchBudget.java` (pure): at most one search per round and two per task.
   `String check(int round)` returns null and records the search when allowed; otherwise the refusal, exactly
   `"这一轮已经联网查过了，先看上一次的结果再决定。"` (same round) or
   `"这个任务已经联网查了 2 次，用已经查到的结果回答；查不到就如实告诉用户，不要再查。"` (third search in the task).
3. `src/xyz/erapi0neer/xingyi/agent/Observation.java` (pure): `static String describe(String appLabel, String playingTitle)`
   → `""` when appLabel is null/empty; else `"前台是" + appLabel` + (`"，正在播放《" + title + "》"` when title is not empty;
   titles over 30 chars are cut to 30 + "…"). It is read aloud: never class names or package names.
4. `src/xyz/erapi0neer/xingyi/agent/PlayerPages.java` (pure): `static boolean isPlayer(String pkg, String activity)` —
   true for `tv.danmaku.bili` when the activity's simple name contains `DetailsActivity` or `PlayerActivity`
   (seen: `com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity`, `VideoDetailsActivity`); for
   `com.netease.cloudmusic.tv` when it contains `Player`; false otherwise (null-safe).
5. `TvTools` (Android):
   - a new tool `web_search` (`Gate.read()`, describe `"联网搜索：" + query`). `run`: refusal = the task's `SearchBudget`
     (a field: `TvTools` is created per task) `.check(t.round())` → `fail("rejected", refusal)`; the gateway route
     `GatewayConfig.loadRoutes(context).gateway` (null → `fail("unavailable", "没有配置联网搜索要用的网关线路")`);
     `t.say("我查一下。")`; now = `yyyy-MM-dd HH:mm` + `" 星期"` + one of 日一二三四五六 (Locale.CHINA, the TV's clock);
     `new WebSearch(gw.baseUrl, gw.apiKey, null, WebSearch.https()).search(query, now, 25000)` → `ok("observed", answer)`
     plus `"answer"`, `"searched"`, `"seconds"` (one decimal); IOException → `fail("error", message)`;
     IllegalArgumentException → `fail("rejected", message)`.
   - `String observe()` for the honest stop: `Observation.describe(label(foreground package), title of the media session
     that is playing)`; any failure reading the session just leaves the title out; must not throw.
   - after a successful `screen_click`, and after `press_keys` that pressed center/enter: when the foreground is a player page
     (`PlayerPages.isPlayer`), poll the media session (the existing `current()`) every 700 ms for up to 6 s until that
     package reports `playing`; then add `"playing"` (the existing `session(s)` JSON) and append
     `"，正在播放《" + title + "》"` to the message; if it never plays, append `"，播放页已打开，还没读到播放状态"`.
   - `bili_search`: wait up to 25 s instead of 9 s (still breaking out as soon as B站 is in front and shows the keyword);
     if after 12 s B站 is in front, the screen has had no change for 1.5 s and the keyword is not there, send the same
     search intent once more. Result format unchanged.
6. `AgentEngine`: `case "say"` in `onEvent` → `speak(t, text)` (it already stays silent for ui/mcp); after creating the loop,
   `l.observe(tools::observe)` (or a lambda that calls it).

## Checks
`tests/agent/WiringChecks.java` (an empty stub; `AgentCoreTest.main` calls `WiringChecks.run()`). Pure parts only:
SearchBudget (first allowed; a second in the same round refused with the round message; a new round allowed; the third in
the task refused with the task message), Observation (empty label, label only, with title, 31-char title cut),
PlayerPages (the activities above → true; `tv.danmaku.bili/.MainActivityV2`, `…SearchResultActivity`, Orrery, null → false;
NetEase `PlayerActivityV2` → true), and `Turn.round()` (a Fake tool that records `t.round()` sees 1 in round 1 and 2 in round 2).

## Acceptance
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`, N at least 15 more than before your job.
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`.
- `sh tests/agent/accept_tv_wiring.sh` → builds the TV app with your sources (`build.sh` of the XiaoAi bridge), runs the
  bridge's JVM suite against them and checks the wiring is there; prints `WIRING OK`.
