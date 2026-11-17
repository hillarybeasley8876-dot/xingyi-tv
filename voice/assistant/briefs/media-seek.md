# Brief: media-seek — 「快进一分钟」 is answered at once: XiaoAi has done it, or 星仪 presses the keys

## Why (on the TV, 2026-11-11 20:02–20:03, 云视听极光 playing an episode)
「快进一分钟」 said to XiaoAi: XiaoAi's own instruction was `PlaybackController.Seek` and the episode jumped a minute
(00:03:47 → 00:05:12 with 28 s of playing in between). 星仪 was handed the sentence all the same, had no tool for it,
and spent 20 seconds on `media_now_playing`, `screen_read` and `screen_look` to end with 「已经帮你快进了一分钟。不过现在
屏幕是黑的，看不到画面和进度条，暂时没法确认视频是否还在正常播放。」 (the video layer is not in screenshots).

The keys, measured in the same session with the progress bar on screen: `KEYCODE_MEDIA_FAST_FORWARD` (90) moves about
10 seconds forward per press, `KEYCODE_MEDIA_REWIND` (89) about 10 back; DPAD right / left do the same, but **during
the ad before an episode DPAD right opens the SVIP purchase page** (「按 → 键开通SVIP关闭该广告」), so only the two
media keys are used. `adb.keys(int... codes)` sends any number of them in one call.

Job ktcp-state (merged before this one) gave `TvTools` the state of 云视听极光's player
(`AudioPlayers.state(adb.call(LocalAdb::audioPlayers), uid)` — read how `jiguangKey` uses it) .

## What to build

### 1. `QuickCommand.java`
A new matcher `seek(String s)`, tried in `match` right after `media(s)` (so before everything about videos and apps):
- the sentence is `再?` + a verb + an optional amount + `吧?`, nothing else:
  - forward verbs: 快进, 前进, 快转, 往后快进, 向后快进;
  - back verbs: 快退, 后退, 倒退, 回退, 往回倒, 倒回去, 倒回, 往前倒;
  - amount: `一下` / `一点` / `一点点` / `一会儿` → 30 seconds; `N秒` / `N秒钟` → N; `N分钟` / `N分` → 60·N;
    `半分钟` → 30; `N分半` / `N分半钟` → 60·N + 30 (一分半 → 90); no amount → 30. N is digits or a Chinese numeral
    (`number(...)`, already in this class; 两 is 2).
- only 5 to 1800 seconds; anything else (「快进到十分钟」, 「两倍速播放」, 「快进三个小时」) is not matched.
- result: `cmd("media_seek", "seconds", <n>)`, n negative for the back verbs.
- `reply(result)`: for `media_seek` the result's `message` (「好的」 when there is none).

### 2. `tv/TvTools.java` — a new tool `media_seek` (it is **not** in the contract: only the quick command calls it)
- `gate`: `xiaoaiDid("PlaybackController.")` →
  `Gate.deny("小爱已经为这句话快进或快退过了，再做一次会多跳一段：告诉用户已经好了。")`; else `Gate.act()`.
  (For a quick command a denied gate is answered 「好的」 by the engine — that is the wanted result.)
- `describe`: `"快进 " + n + " 秒"` / `"快退 " + n + " 秒"`.
- `run`: `seconds` (int, required; 0 → `fail("rejected", "seconds 不能是 0")`).
  - 云视听极光's player in front (`isJiguangPlayer` on `LocalAdb::focusedApp`, as `jiguangKey` does) and its state is
    `STARTED` or `PAUSED` → presses = `max(1, round(|seconds| / 10.0))`, at most 180; an `int[]` of that many
    `KeyEvent.KEYCODE_MEDIA_FAST_FORWARD` (or `KEYCODE_MEDIA_REWIND`) → `adb.keys(codes)` →
    `ok("sent", PlayerPages.seekSays(seconds))`.
  - its player in front but state `NONE` → `fail("unconfirmed", "云视听极光现在没有在放东西，没法快进")`.
  - anything else in front → `fail("unsupported", "现在前台的应用我还不会快进快退：可以说具体的时间点，或者用遥控器")`.
- Add `media_seek` to the list `quietWhileAsleep` protects (the `AWAKE_ONLY` set near the top of the file).
- Nothing else in this file; no new `import` lines.

### 3. `PlayerPages.java` (pure)
`public static String seekSays(int seconds)`: 「已快进 1 分钟」 / 「已快进 30 秒」 / 「已快进 1 分 30 秒」 / 「已快退 10 秒」
(minutes only when there are whole minutes; the sign picks 快进 / 快退).

### 4. `AgentLoop.java`
`media_seek` joins `DIRECT`, `ANSWERS` and `EVIDENT` (nothing else in this file).

## Checks
- `tests/agent/QuickChecks.java`, a new group `seek()` called from `run()` (helpers `is(utterance, tool, argsJson)`,
  `none(utterance)`):
  - `is` → `media_seek`: 快进一分钟 `{"seconds":60}` · 快进30秒 `{"seconds":30}` · 快进 `{"seconds":30}` ·
    快进一下 `{"seconds":30}` · 快进两分钟 `{"seconds":120}` · 快进一分半 `{"seconds":90}` · 再快进十秒
    `{"seconds":10}` · 快进半分钟 `{"seconds":30}` · 快退十秒 `{"seconds":-10}` · 后退30秒 `{"seconds":-30}` ·
    倒退一分钟 `{"seconds":-60}` · 往回倒十秒 `{"seconds":-10}` · 快进5分钟吧 `{"seconds":300}`;
  - `none`: 快进到十分钟 · 两倍速播放 · 快进三个小时 · 快进2秒 · 快进的时候卡不卡 · 怎么快进;
  - every sentence that already has an `is(...)` or `none(...)` in this file keeps its result;
  - `reply`: a `media_seek` quick command answers with the result's `message`.
- `tests/agent/SkillChecks.java`, in the group `audioPlayers()` or a new one: `PlayerPages.seekSays(60)` 「已快进 1 分钟」,
  `seekSays(30)` 「已快进 30 秒」, `seekSays(90)` 「已快进 1 分 30 秒」, `seekSays(-10)` 「已快退 10 秒」,
  `seekSays(-120)` 「已快退 2 分钟」.
- At least 26 checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28. Do not change `assets/agent/contract.json`.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 26 more than before you
  started (the job's check requires the number in the job file)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_media_seek.sh` → `MEDIA SEEK OK`
