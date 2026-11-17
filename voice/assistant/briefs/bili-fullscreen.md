# Brief: bili-fullscreen — a B站 video that 星仪 started fills the TV screen

## Why (the TV, 2026-11-10 23:54 and 2026-11-11 00:02)
B站 on this TV is the phone app: a video opens on its page with the player in the **top half** of the screen and the
description, a shop card and the comments below. On a TV that is not what 「播放…」 means. Seen on the TV (probe8; the Eyes
dumps are `tests/agent/fixtures/bili-player-playing.txt`, `bili-player-tapped.txt`, `bili-player-full.txt`):
- While the video plays in the half-screen player, Eyes reads the page under it but **nothing of the player** (no
  control, no node for the video surface): `bili-player-playing.txt`.
- One touch on the player (the screen point 960,300 of 1920×1080 — the player takes the top 55 %) brings its controls up
  for a few seconds: `[4] 选项「暂停」 @2,51`, `[7] 选项「展开全屏」 @97,51`, `[8] 选项「视频进度，已播放到16秒，共1分40秒」 @44,51`
  (`bili-player-tapped.txt`).
- A click on 「展开全屏」 makes the player full screen, in the same activity (`UnitedBizDetailsActivity`); then Eyes reads
  `选项「锁定屏幕」 @96,41`, `选项「倍速」`, `选项「清晰度调节」` and no 「展开全屏」 (`bili-player-full.txt`). The video kept playing.

## What to build
1. `PlayerPages` (pure):
   - `public static int biliFullScreenControl(String screenDigestText)` → the id of the element labelled exactly
     「展开全屏」 on a B站 screen (first line has `tv.danmaku.bili`), else -1.
   - `public static boolean biliIsFullScreen(String screenDigestText)` → true when it is a B站 screen that has a
     「锁定屏幕」 element or a 「清晰度调节」 element, and no 「展开全屏」 element.
   (Element lines look like `[7] 选项「展开全屏」 @97,51`; the class already has helpers for the id and the label.)
2. `TvTools`: `private boolean biliFullScreen(Tool.Turn t)` — only when B站's player page is in front and Eyes is
   connected:
   - read the screen; already full (`biliIsFullScreen`) → true;
   - no 「展开全屏」 on it → `adb.tap(960, 300)` (the tool already sends taps this way), wait 700 ms, read again;
   - 「展开全屏」 there → click that element through Eyes (as `read()` clicks 「跳过」), wait 1200 ms, read again, and
     return `biliIsFullScreen` of that; not there → false. At most one tap and one click per call; never any key press
     (OK is pause here). Any exception → false (the video is playing either way).
   - never touch anything else on the page (it has a shop card with 「立即购买」).
3. Call it where a B站 video was **seen playing**: at the end of `openBiliVideo` (the branch that returns 「正在播放《…》」)
   and in `awaitPlayback` when the page is B站's player and the session plays. When it returns true the result gets
   `"fullscreen": true`; the message stays as it is (「正在播放《…》」). The 「还在加载」 result is left alone (nothing is
   tapped while B站 is still starting).
4. `bili_search`'s wait loop reads B站's screen every half second while B站 starts. Every read makes B站's main thread
   build its whole accessibility tree, and that slowed its start badly in `openBiliVideo` (2026-11-10 23:40: the same
   cold link played after 31 s with nobody reading and after ~90 s with the loop reading on every change; that loop
   now reads at most every `BILI_READ_GAP_MS` = 4 s). Do the same in `bili_search`: once B站 is in front, read at most
   every `BILI_READ_GAP_MS` (keep the loop's 500 ms sleep, the keyword check, the login-dialog closer and the resend
   rule as they are; only how often `read()` runs changes).
5. Do not add `import` lines to TvTools.java; use fully qualified names where needed.

## Checks (`tests/agent/SkillChecks.java`, a new group `biliFull()` called from its run())
- `biliFullScreenControl`: tapped fixture → 7; playing fixture → -1; full fixture → -1; the tapped text under another
  app's head line → -1; null / "" → -1; a comment line that merely contains the words (`文本「怎么展开全屏啊」`) → -1.
- `biliIsFullScreen`: full fixture → true; playing and tapped fixtures → false; another app's screen with 「锁定屏幕」 →
  false; null / "" → false.
- At least 12 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4202
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_bili_fullscreen.sh` → `BILI FULLSCREEN OK`
