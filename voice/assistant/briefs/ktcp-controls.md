# Brief: ktcp-controls — 「暂停」「继续播放」 work while 云视听极光 plays

## Why (the TV, 2026-11-11 00:11, probe10)
云视听极光 (Tencent Video's TV app, `com.ktcp.video`) has **no media session**. `media_control` sends its key with
`AudioManager.dispatchMediaKeyEvent`, which goes to the media-session service — so while a show plays in 云视听极光,
「暂停」 does nothing to it (and the key goes to whichever app last had a media-button receiver: B站 or NetEase, which
may wake up). Measured on a free show after its ad: the key **injected into the focused window** works —
`input keyevent 127` (KEYCODE_MEDIA_PAUSE) paused the episode (a pause overlay with 「▶ 00:00:18」), `input keyevent 126`
(KEYCODE_MEDIA_PLAY) resumed it. Its screen cannot be read (self-drawn) and Eyes gets no window events from it; only
the window manager's focused activity tells that its player is in front: `adb.call(LocalAdb::focusedApp)` =
`com.ktcp.video/com.ktcp.video.activity.TVPlayerActivity` (the full-screen player) or
`…activity.onepage.NormalOnePageActivity` (a show's detail page, which has a small player). TvTools already has
`jiguangPage(String simpleName)` for that check (do not open `LocalAdb.java`).

## What to build (`TvTools`, the `media_control` tool; nothing else)
For `play`, `pause`, `stop`, `next`, `previous` — before the `AudioManager` path — when **no media session is playing
or paused** (`nowPlaying()` is null or its state is neither `playing` nor `paused`) **and** 云视听极光's
`TVPlayerActivity` or `NormalOnePageActivity` is in front:
- send the key to the focused window with `adb.keys(code)` (the tool's usual key sender; `stop` sends
  KEYCODE_MEDIA_PAUSE here — there is nothing to stop to);
- return `ok("sent", …)` — it cannot be checked, and the words say so without sounding unsure:
  pause / stop → 「云视听极光已暂停」, play → 「云视听极光继续播放了」, next → 「已切到下一集」, previous → 「已切到上一集」, each
  followed by 「（它的画面读不出来，没法核对）」 only in a `note` field, not in the message;
- do **not** call `dispatchMediaKeyEvent` in this case, and do not fall into the `replayParked` branch.
In every other case the tool behaves exactly as today.
Put the decision in a small pure helper so it can be checked: in `PlayerPages`,
`public static boolean isJiguangPlayer(String pkg, String cls)` → true for package `com.ktcp.video` with a class whose
simple name is `TVPlayerActivity` or `NormalOnePageActivity` (full or short class name), false otherwise, null-safe.
`AgentLoop`'s `spoken(...)` for media_control: the message is used as it is today (「已发送」 becomes 「好的」) — check that
the new messages are spoken as written.
Do not add `import` lines to TvTools.java (another job edits the same file).

## Checks (`tests/agent/SkillChecks.java`, a new group `jiguangPlayer()` called from its run())
`isJiguangPlayer`: `com.ktcp.video` + `com.ktcp.video.activity.TVPlayerActivity` → true; + `.activity.TVPlayerActivity`
→ true; + `com.ktcp.video.activity.onepage.NormalOnePageActivity` → true; + `…SearchActivity` → false; +
`…OpenJumpAdActivity` → false; `tv.danmaku.bili` + `…TVPlayerActivity` → false; null / "" → false. At least 8 new
checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4198
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_ktcp_controls.sh` → `KTCP CONTROLS OK`
