# Brief: bili-start — B站's splash ad is skipped, and a cold B站 video waits for playback and says it is starting

## Why (the TV, 2026-11-10 evening, probes 3–5)
- B站's code was never compiled on this TV (the lead compiled it by hand tonight): a cold video link now reaches the
  video page in **15 s** (was 61 s) and plays after **33–35 s**. A warm one reaches the page in 2.4 s and still plays
  only after ~25 s.
- Part of that is B站's **splash ad**: a full-screen ad with a 「跳过 5」 countdown, shown on a cold start and again when
  B站 comes back from the background. Eyes reads it (the dump is `tests/agent/fixtures/bili-splash.txt`):
  `前台：哔哩哔哩 tv.danmaku.bili（MainActivityV2）`, `[1] 图标「splash_layout」 @50,50`, `[2] 选项「广告」 @50,50`, …,
  `[26] 选项「点击进入淘宝app」 @50,76`, `[35] 选项「跳过 5」 @95,93`. `tests/agent/fixtures/bili-home.txt` is B站's home
  a few seconds earlier, without the ad.
- `openBiliVideo` waits 8 s for playback after the player page appears and then says 「还没读到播放状态」 — on a cold
  start playback comes ~18 s after the page, so the model was left to wait and look again.
- Nobody tells the user that B站 needs half a minute: the panel and XiaoAi are silent meanwhile.

## What to build
1. `PlayerPages` (pure): `public static int biliSplashSkip(String screenDigestText)` → the id of the element to click
   (35 for the fixture) when the text is B站's screen (its first line has `tv.danmaku.bili`) and has a line whose label
   is 「跳过」 or 「跳过 N」 (N digits; the label is the text between 「 and 」 on an element line `[id] role「label」 …`) **and**
   also a `splash_layout` or 「广告」 line; -1 otherwise (B站's home, another app, a video page whose comments say 跳过,
   null, empty).
2. `TvTools.read()`: after the login-dialog closer (same style), when `PlayerPages.biliSplashSkip(d.text())` gives an
   id, click that element the way `screen_click` clicks one (through Eyes; no key presses), wait 500 ms and read once
   more. At most once per `read()` call. Nothing happens when Eyes is not connected.
3. `openBiliVideo`:
   - cold start (the `cold` flag that is already there): wait up to **25 s** for the media session after the player
     page is up (warm stays 8 s);
   - cold start: tell the user once, right after the link is sent: `t.say("哔哩哔哩启动要半分钟左右，稍等")` (look at how
     other tools use `t.say`; a silent test source must stay silent — `Turn.say` implementations already handle the
     source, do not add your own check);
   - the result texts stay as they are.
4. Do not add `import` lines to TvTools.java (another job edits the same file): use fully qualified names where needed.

## Checks (`tests/agent/SkillChecks.java`, a new group `biliSplash()` called from its run())
The splash fixture → 35; the home fixture → -1; the splash text with the 跳过 line removed → -1; the 跳过 line alone on
B站 without `splash_layout`/广告 → -1; the same lines under another app's head line → -1; 「跳过」 without a number → its
id; null and "" → -1. At least 8 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4051
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_bili_start.sh` → `BILI START OK`
