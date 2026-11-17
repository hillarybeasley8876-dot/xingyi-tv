# Brief: ktcp-state — 「暂停」 said to XiaoAi must stay paused: 云视听极光's keys are toggles, and its own voice agent acts first

## Why (on the TV, 2026-11-11 20:03 — the audio service's own event log)
Job ktcp-controls (the night before) sends `KEYCODE_MEDIA_PAUSE` / `KEYCODE_MEDIA_PLAY` into 云视听极光's window for
「暂停」 / 「继续播放」. It was tried only without XiaoAi. Said to XiaoAi, as the user says it:

```
20:03:17.358  the bridge hands 「暂停」 to 星仪 (XiaoAi's instructions: none)
20:03:18.898  player piid:143 state:paused     <- 云视听极光's own voice agent (com.ktcp.tvagent) paused it
20:03:19.062  player piid:143 state:started    <- 星仪's key, 164 ms later: in this app the key is a toggle
```
and 「继续播放」 right after: nothing to do for the app (it was playing), then 星仪's `MEDIA_PLAY` **paused** it
(20:03:27.138). So with 星仪 installed 「暂停」 does not pause and 「继续播放」 pauses. Both keys toggle.

The same probe showed how to know what that app is doing although it has no media session: `dumpsys audio` lists
every player with its owner and state —
`  ID:135 -- type:android.media.AudioTrack -- u/pid:10021/18067 -- state:started -- attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_UNKNOWN flags=0x200 …`
— `started` while the episode (or the ad before it) plays, `paused` when paused, no line of that uid when nothing is
open. `LocalAdb.audioPlayers()` (already there, do not open that file) returns the whole dump (about 12–18 KB);
`adb.call(LocalAdb::audioPlayers)` is how `TvTools` gets it, exactly like `adb.call(LocalAdb::mediaSessions)`.
Fixtures: `tests/agent/fixtures/audio-ktcp-ad.txt`, `audio-ktcp-playing.txt`, `audio-ktcp-paused.txt`,
`audio-ktcp-paused-2.txt` (paused, and the app's SoundPool idle beside it), `audio-idle.txt` (the app not running).
云视听极光's uid in all of them is 10021. Note `ID:87` in every one of them: an AudioTrack of uid 1000 that is always
`started` (usage NOTIFICATION) — only the asked uid's lines count, and only media.

Also seen in that probe: after `switch_input` the activity in front is
`com.xiaomi.mitv.tvplayer/.ExternalSourceActivity` — the tool can confirm what it did instead of answering
「已请求切换…没有读回外部信号」, after which the model pressed HOME (which undoes the switch) and switched again
(calibration: ten rounds, forty seconds).

## What to build

### 1. `AudioPlayers.java` (new, pure, package `xyz.erapi0neer.xingyi.agent`)
```java
public final class AudioPlayers {
    public static final String STARTED = "started", PAUSED = "paused", NONE = "";
    /** What the media players of one app are doing, from `dumpsys audio`. */
    public static String state(String dump, int uid)
}
```
Only the lines of the `players:` block (from the line `  players:` to the next empty line), of the form
`ID:<n> -- type:<class> -- u/pid:<uid>/<pid> -- state:<word> -- attr:AudioAttributes: usage=<USAGE_…> …`.
Counted: the asked uid, a type that is not `android.media.SoundPool`, usage `USAGE_MEDIA`, `USAGE_GAME` or
`USAGE_UNKNOWN`. `STARTED` when any counted line is `started`; else `PAUSED` when any is `paused`; else `NONE`
(also for a null / empty dump, or one with no players block).
The dump comes from a shell: a line may end with a carriage return (U+000D) — trim each line before looking at it
(a line that holds only a carriage return is an empty line).

### 2. `PlayerPages.java` (pure) — the decision, so it can be checked
```java
/** 暂停/停止 need the key only while it plays; 继续 needs it unless it plays. state is AudioPlayers.state. */
public static boolean jiguangNeedsKey(String action, String state)   // pause, stop: STARTED;  play: not STARTED
/** Whether the app is where the request wants it. */
public static boolean jiguangSettled(String action, String state)    // pause, stop: not STARTED;  play: STARTED
```
Any other action → `jiguangNeedsKey` true, `jiguangSettled` true (next / previous are sent as before).

### 3. `tv/TvTools.java`
a. `jiguangKey(String action, int code)` becomes `jiguangKey(String action, int code, Turn t)` (update its one caller).
   The first part stays (another app's session playing → null; 云视听极光's player not in front → null). `next` and
   `previous` stay exactly as they are. For `pause`, `stop`, `play`:
   1. `state` = `AudioPlayers.state(adb.call(LocalAdb::audioPlayers), uid)`, uid from
      `pm.getApplicationInfo(JIGUANG, 0).uid`; a dump that cannot be read counts as `NONE`.
   2. When `"xiaoai".equals(t.source())`: 云视听极光 acts on the sentence itself, about a second and a half after it
      was said. Poll the state every 300 ms for at most 2500 ms; as soon as `jiguangSettled` → done, **no key**.
   3. Otherwise (or when the wait ran out): if `jiguangNeedsKey(action, state)` → `adb.keys(code)` (pause and stop:
      `KEYCODE_MEDIA_PAUSE`, as today), once.
   4. Poll again every 300 ms for at most 2000 ms for `jiguangSettled`. If it still is not: read once more and, if
      `jiguangNeedsKey` again (the app's own action landed while ours was on its way and the two cancelled out), press
      once more and poll 1500 ms.
   5. Settled → `ok("observed", message)` with the messages of today (「云视听极光已暂停」 / 「云视听极光继续播放了」) and
      **no** `note` any more (it is checked now). Not settled →
      `fail("unconfirmed", pause/stop ? "按了暂停键，云视听极光还在放：片头广告暂停不了，正片开始后再说一次" : "按了播放键，云视听极光还没有开始放")`.
   Honour `t.cancelled()` in the polls (`throw new InterruptedException()`), as the other loops in this file do.
b. tool `media_now_playing`: when there is no media session at all, look at 云视听极光's state: `STARTED` or `PAUSED` →
   `ok("observed", "云视听极光 " + (STARTED ? "正在播放" : "暂停着") + opened)` with
   `.put("session", new JSONObject().put("app", "云视听极光").put("package", JIGUANG).put("state", STARTED ? "playing" : "paused"))`,
   where `opened` is `"（刚才打开的是《" + title + "》）"` when `tencent_video_open` opened something in this process
   within the last six hours, else `""`. For that, `tencent_video_open`'s `open(...)` remembers the show's title and
   `SystemClock.elapsedRealtime()` in two `static volatile` fields when it reports success. `NONE` → as today.
c. tool `switch_input`: after `start(...)`, poll every 300 ms for at most 3000 ms until `adb.call(LocalAdb::focusedApp)`
   is package `com.xiaomi.mitv.tvplayer` with a class ending in `ExternalSourceActivity` (`FrontApp.pkg` / `FrontApp.cls`).
   Seen → `ok("observed", "已切到 HDMI " + n + "（这个接口上没接设备的话，画面是无信号）")`; not seen → the result of today.
d. Nothing else in this file; no new `import` lines.

### 4. `AgentLoop.java`
`switch_input` joins the `ANSWERS` set (its confirmed sentence is the answer; nothing else in this file).

## Checks (`tests/agent/SkillChecks.java`, a new group `audioPlayers()` called from `run()`)
- `AudioPlayers.state(fixture, 10021)`: ad → `started`; playing → `started`; paused → `paused`; paused-2 → `paused`;
  idle → `""`. `state(fixture, 1000)` → `""` for every fixture (ID:87 is not media). `state(playing, 10022)` → `""`.
  `state(null, 10021)`, `state("", 10021)`, `state("no players here", 10021)` → `""`. A dump where the uid has one
  `paused` and one `started` media AudioTrack → `started`. A dump where the uid's only line is a `started` SoundPool →
  `""`. (Read fixtures the way the file's other checks do; build the synthetic dumps as strings.)
- `PlayerPages.jiguangNeedsKey` / `jiguangSettled`: pause & stop × {started, paused, ""}; play × the same; next.
- At least 28 checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`
  (`LocalAdb::audioPlayers` and `LocalAdb::focusedApp` are called exactly as `LocalAdb::mediaSessions` is).
  No TV, no adb, no network. Java 8 / Android API 28. Do not edit the fixtures.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 28 more than before you
  started (the job's check requires the number in the job file)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_ktcp_state.sh` → `KTCP STATE OK`
