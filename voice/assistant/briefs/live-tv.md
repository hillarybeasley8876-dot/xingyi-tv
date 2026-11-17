# Brief: live-tv — 「我想看 CCTV5」 gets a true answer at once, and the TV's input is not switched on a guess

## Why (calibration with the TV's real environment, 2026-11-11 01:30; three runs of 「我想看CCTV5」)
This TV has no app that plays live channels. What the model did with the request, every time, in 6 to 10 rounds and
17 to 60 seconds: `switch_input HDMI1` (guessing a set-top box — on the TV that leaves the user in front of
「无信号」), `install_app` with 央视频 or with a list of thirty app names, `open_link https://tv.cctv.com/live/cctv5/`,
`open_app TV Bro` and key presses, and at the end 「这台电视现在没装能看 CCTV5 的应用」 plus what the guide says is on.
That last sentence is the whole answer, and `tv_guide` can give it in a second.

## What to build

### 1. `QuickCommand.java`
a. `public static boolean namesInput(String utterance)`: true when the sentence names an input or something plugged
   into one: `HDMI` / `hdmi`, 信号源, 输入源, 机顶盒, 盒子, 游戏机, 主机, PS4 / PS5 (any case), Switch (any case), Xbox (any
   case), 电脑, 笔记本, 投影, 高清线, 外接. null and "" → false.
b. A new matcher `live(String s)`, tried in `match` **before** `watch(...)` (and after everything that is there now, so
   「CCTV5 现在在放什么」 keeps going to the guide as it does): the sentence is one of
   - `(我|我们)?(想要|想|要)?看(一下|看)?` + channel + optional `的?(直播|节目|台)`;
   - `(打开|切到|切换到|换到|调到|转到|播放|播|放)` + channel + optional `的?(直播|节目)`;
   where channel is 2 to 10 characters, `TvGuide.channel(channel) != null`, and nothing follows. `TvGuide.channel`
   accepts any text that *contains* a channel name (「CCTV5的NBA」, 「湖南卫视的歌手」, 「CCTV5能」 all resolve), so the
   channel text must also contain none of these characters: `的 吗 呢 吧 能 可 什 哪 怎 有 没 和 跟 与 、 ， , ? ？`.
   Result: `cmd("tv_guide", "channel", <channel as said>, "part", "现在", "watch", true, "final", true)`.
   Not matched (stays with what handles it today): a programme on a channel (「我想看CCTV5的NBA」「看湖南卫视的歌手」),
   a question (「CCTV5能看吗」), 「CCTV5 现在在放什么」 (→ the existing guide command, unchanged).

### 2. `TvGuide.java` (pure)
`public static String cannotWatch(String name, String now)`: 
`"这台电视上没有能看电视直播的应用，放不了" + name + "。"` followed by `now` when it is not null or empty (`now` is what
`speakNow` returns, e.g. 「CCTV-5 体育现在在播《…》…」).

### 3. `tv/TvTools.java`
a. tool `tv_guide`: an argument the contract does not list, `watch` (boolean; only the quick command sends it). When
   true: if one of the installed apps (`apps().keySet()`) has a label containing 央视频 or 电视家 →
   `return fail("has_app", "电视上装了" + <that label> + "：用 open_app 打开它看直播");` (the model then takes the
   request). Otherwise the answer is `TvGuide.cannotWatch(ch[1], <the now sentence>)`; when the guide cannot be read
   (IOException / JSONException) the answer is `TvGuide.cannotWatch(ch[1], null)` and the result is still ok. An
   unknown channel fails as today.
b. tool `switch_input`: a gate — `QuickCommand.namesInput(t.utterance())` → `Gate.act()`, else
   `Gate.deny("用户这句话没有提到信号源或外接的设备（HDMI、机顶盒、游戏机、电脑）：不要切换信号源，这台电视的 HDMI 口上不一定接着东西，切过去是一片无信号。要看电视台直播而电视上没有直播应用时，用 tv_guide 查它现在在播什么，如实告诉用户放不了。")`.
c. Nothing else in this file; no new `import` lines. (Another job changes the `TENCENT_FEED` line at the same time.)

### 4. The contract — already done by the lead, do not touch it
`assets/agent/contract.json` and `PROMPTS.md` already say this (the `switch_input` description, and a system line that
starts 「用户要看电视台的直播」). You do not own them.

## Checks
- `tests/agent/QuickChecks.java`, a new group `live()` called from `run()` (helpers `is(utterance, tool, argsJson)` and
  `none(utterance)`):
  - `is` → `tv_guide` `{"channel":"…","part":"现在","watch":true,"final":true}` with the channel as said: 我想看CCTV5 ·
    我要看CCTV5 · 看CCTV5 · 我想看中央五台 · 我想看湖南卫视 · 打开CCTV1 · 切到湖南卫视 · 换到东方卫视 · 我想看CCTV5直播 ·
    我想看体育频道 · 播放CCTV13 · 我想看一下浙江卫视;
  - unchanged: `CCTV5现在在放什么` and `湖南卫视在播什么` are still `tv_guide` **without** `watch`
    (`{"channel":"…","part":"现在","final":true}`);
  - `none`: 我想看CCTV5的NBA · 看湖南卫视的歌手 · CCTV5能看吗 · 我想看电视 · 我想看直播;
  - `namesInput`: true for 切到HDMI1 / 换到 hdmi 2 / 打开机顶盒 / 我要玩游戏机 / 切到电脑 / 打开PS5 / 切换信号源;
    false for 我想看CCTV5 / 看湖南卫视 / 打开B站 / null / "";
  - `reply` of the quick command is the result's `answer`.
  At least 28 checks.
- `tests/agent/FeedChecks.java`, in or next to `tvGuide()`: `TvGuide.cannotWatch("CCTV-5 体育", null)` is
  `这台电视上没有能看电视直播的应用，放不了CCTV-5 体育。`; with a now sentence it ends with that sentence; with "" as with
  null. At least 3 checks.
- Whatever check pins the contract's text or tool list keeps passing (run the suite).

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28.
- `watch` is deliberately not in the contract.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 31 more than before you
  started (the job's check requires the number in the job file)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_live_tv.sh` → `LIVE TV OK`
