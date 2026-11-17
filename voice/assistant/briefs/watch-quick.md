# Brief: watch-quick — 「我想看 X」 is decided by what Tencent Video has, not by the model's guess

## Why (measured 2026-11-11, 01:00–02:00)
The calibration never sent what the TV really sends with every request: the list of installed apps and what it knows
about its user (「用户最喜欢的歌手是林俊杰」「常用应用：网易云音乐」…). With both added (`CAL_REAL_ENV=1`),
「我想看别无选择」 went wrong in **7 of 16** runs: `bili_search`, `web_search` for "which song is it", or `netease_play`
(「网易云音乐正在播放林俊杰的《别无选择》」) — it had been 12 of 12 right without them. On the TV itself, silent runs showed
the same (`bili_search` once in five; the detail page instead of playback once). 别无选择 is a 电视剧 that Tencent Video
has under exactly that name; the model cannot know that, the search can — in 0.2–0.6 s.

So: when the sentence is 「我想看 X」 and Tencent Video's own search has a show named exactly X, it is opened at once,
with no model round (about 2.5 s instead of 6 s). Whenever that is not certain, nothing is done and the model takes
the request as today (the engine already works that way: a quick command whose tool does not succeed is handed to
the model — `AgentEngine.runQuick`, which you do not touch).

The real search was asked about 80 phrases for this brief (raw answers slimmed into fixtures with
`tests/agent/fixtures/slim_tencent.py`). What it showed, besides the above:
- its answers are 0.1–1.1 MB (抗日剧: 1 119 209 bytes) and `Feeds.readBody` refuses anything over 1 MB (「回复太大」 —
  the tool then says 「腾讯视频的搜索没连上」);
- titles carry punctuation the user does not say: the film is 「你的名字。」, the series 「三体·黑暗森林」 — today 「你的名字」
  ends as 「腾讯视频没有《你的名字》（综艺，2020）：它在央视频」 (a namesake) and 「三体黑暗森林」 as NONE;
- `videoInfo.status == 1` is a show that is not out yet (60 of 61 such items carry the 「预约」 tag): 「喜剧」 (2026),
  「三体·黑暗森林」 (2026). Today they are "played";
- everyday nouns are titles too: 猫 (电影 1998), 恐龙, 地球, 风景, 喜剧, 汽车 (type 汽车), 周杰伦 (type 音乐),
  黑神话悟空 / 原神 (type 游戏). So a title match alone is not enough to act without the model;
- 「海绵宝宝」 picks the 2015 film although fifteen seasons of the series are there.

## What to build

### 1. `Feeds.java`
`readBody`: the limit is **4 MB**, written `4 * 1024 * 1024` (fix the comment: a search answer is 0.1–1.1 MB).

### 2. `TencentVideo.java` (pure, no android.*)
a. **Punctuation does not count for "the same title".** Add `static String plain(String s)`: `norm(s)` with these
   characters removed: `。．.·・：:，,、！!？?～~—–-_'’"…`. A candidate is an *exact* one when `plain(title)` equals
   `plain(asked)` (and is not empty). Everything else about exact candidates stays (candidate order; the first one on
   Tencent wins). The season test stays on `norm` as it is.
b. **Not out yet.** `Show` gets `public final boolean upcoming` (`videoInfo.status == 1`); keep the existing
   constructor working (add an overload or a parameter — your choice, existing checks must compile unchanged).
   In `pick`: a released show always wins over an upcoming one — the exact loop takes the first *released* Tencent
   exact; `newest(...)` ignores upcoming seasons; the 综艺 rule too. Only when nothing released was chosen: the first
   upcoming Tencent exact, else the newest upcoming Tencent season, is the chosen one (status FOUND, `show.upcoming`
   true). `others` may list upcoming shows as today.
c. **A series before a film of the same name.** When the chosen show is an exact one of type 电影 and at least two
   *released* Tencent season candidates of the asked title have a type other than 电影, the chosen show becomes the
   newest of those seasons (largest year, ties: candidate order); the film is then the first of `others`.
d. **How it was matched.** `Result` gets `public final String how`: `"exact"`, `"season"`, `"alias"`, or `""` (NONE).
   For ELSEWHERE too. (c) sets `"season"`.
e. **Is it certain enough to act without the model?**
   `public static boolean sure(Result r, String asked, boolean kindNamed)`: true only when all of
   - `r` is FOUND, `!r.show.upcoming`, `r.how` is `"exact"` or `"season"`;
   - `r.show.type` is one of 电视剧, 电影, 综艺, 动漫, 少儿, 纪录片;
   - `plain(asked).length() >= 3`, or `>= 2` when `kindNamed` (the user said 「电视剧繁花」).

Expected — your `pick(fixture, title, "", 0)` and `sure(r, title, false)` must give exactly this (fixtures are
`tests/agent/fixtures/tencent-search-<name>.json`; "chosen" is `cid,title,type,year,episodes` as `MovieChecks.chosen`
prints it):

| fixture | title | status | how | chosen | upcoming | sure |
|---|---|---|---|---|---|---|
| biewu | 别无选择 | FOUND | exact | 3z4n09p1pzelftp,别无选择,电视剧,2000,31 | false | **true** |
| nidemingzi | 你的名字 | FOUND | exact | gz9q7kd8wviwkxv,你的名字。,电影,2016,2 | false | **true** |
| santidark | 三体黑暗森林 | FOUND | exact | mzc00200gq865do,三体·黑暗森林,电视剧,2026,2 | **true** | false |
| santidark | 三体 | FOUND | exact | mzc002007knmh3g,三体,电视剧,2023,0 | false | false (2 characters) |
| xiju | 喜剧 | FOUND | exact | mzc00200vck5hd9,喜剧,电视剧,2026,1 | **true** | false |
| mao | 猫 | FOUND | exact | j5m1vji5qpnqtm6,猫,电影,1998,1 | false | false (1 character) |
| zhoujielun | 周杰伦 | FOUND | exact | mzc003cff73nug9,周杰伦,音乐,0,2 | false | false (type 音乐) |
| heishenhua | 黑神话悟空 | FOUND | exact | mzc003tyhff5elp,黑神话悟空,游戏,0,3 | false | false (type 游戏) |
| haimian | 海绵宝宝 | FOUND | season | mzc00200enuagj3,海绵宝宝 第15季,少儿,2024,13 | false | **true** |
| xiaozhu | 小猪佩奇 | FOUND | season | mzc00200zg0xxlt,小猪佩奇 第12季,少儿,2026,26 | false | **true** |
| dongwu | 动物世界 | FOUND | exact | 6983f15b7g5xch7,动物世界,电影,2018,1 | false | **true** |
| tuokouxiu | 脱口秀大会 | FOUND | season | mzc0020016b6v6q,脱口秀大会 第5季,综艺,2022,35 | false | **true** |
| xinwen | 新闻联播 | ELSEWHERE | exact | sdp001wk0ar4gaq,新闻联播,新闻,0,500 | false | false |
| zhaopian | 照片 | ELSEWHERE | exact | sdp00100514z3dg,照片,纪录片,1968,0 | false | false |
| fanhua | 繁花 | FOUND | exact | mzc00200whsp9r6,繁花,电视剧,2023,36 | false | false (2 characters); `sure(r, "繁花", true)` is **true** |
| qingyunian | 庆余年第二季 | FOUND | exact | mzc002002kqssyu,庆余年第二季,电视剧,2024,37 | false | **true** |
| manchang | 漫长的季节 | FOUND | exact | mzc00200fhhxx8d,漫长的季节,电视剧,2023,12 | false | **true** |
| zhenhuan | 甄嬛传 | ELSEWHERE | alias | sdp001kemwgrsyo,后宫甄嬛传,电视剧,2011,76 | false | false |
| kuangbiao | 狂飙 | ELSEWHERE | exact | (unchanged) | false | false |
| none | 不存在的片 | NONE | "" | - | | false |

haimian's `others` start with the film (`kkpdn0lz6drhxz8`, 海绵宝宝, 电影, 2015). Every existing check in
`MovieChecks` keeps passing unchanged (if one of them cannot, stop and say which and why in your report — do not edit it).

### 3. `QuickCommand.java`
A new matcher `watch(String s)`, tried **last** in `match` (after `app(...)`), on the normalised sentence:
- A: `(我|我们|咱们)?(现在)?(还)?(想要|想|要)看(看|一下)?` + optional kind + title, optionally followed by
  `这部(电视剧|电影|剧|片子|动漫|综艺|纪录片)` / `这个(电视剧|电影|动漫|综艺|纪录片)`;
- B: `(帮我|给我)?(播放|播|放|打开|看)` + **kind (required)** + title;
- C: `看` + title (nothing before 看).
- kind: 电视剧, 电影, 动漫, 动画片 (→ 动漫), 动画 (→ 动漫), 综艺, 纪录片. The title may be wrapped in 《》, which are dropped.
- the title: 3 to 20 characters (2 are enough when a kind was said), and none of this in it:
  `的 吗 呢 吧 呀 啊 么 嘛 ， 。 ？ ！ , . ? !`; 什么 哪 怎么 几 多少 谁 有没有 能不能 可不可以 是不是;
  视频 直播 新闻 比赛 教程 攻略 解说 混剪 演唱会 MV mv 预告 花絮 集锦 合集 热搜 排行 榜 频道 卫视 CCTV cctv 央视 短剧;
  B站 b站 哔哩哔哩 网易云 腾讯视频 云视听极光 极光 爱奇艺 优酷 芒果 抖音 快手 小红书 浏览器 推特;
  第…集 / 第…期 / 第…话 (any 「第」 followed within 4 characters by 集, 期 or 话), 最新 最后 上一集 下一集;
  and the title is not one of: 电影 电视剧 电视 动画片 动画 动漫 综艺 纪录片 节目 片子 大片 东西 别的 其他的 天气预报
  相册 照片 图片 设置 桌面 屏幕 日历 时间 历史记录 我的收藏 收藏夹 稍后再看 一下 一会儿.
- result: `cmd("tencent_video_open", "title", <title>, "strict", true)`, plus `"kind", <kind>` when one was said
  (key order does not matter).
- `reply(result)`: for `tencent_video_open` the tool's own `message` (「好的」 when there is none).

### 4. `tv/TvTools.java`, tool `tencent_video_open` only
- an argument the contract does not list, `strict` (boolean, only the quick command sends it): right after `pick`
  (before `notHere` / `open`, before anything is lit or opened), when `strict` is true and
  `!TencentVideo.sure(found, title, !a.optString("kind").trim().isEmpty())` → `return fail("not_sure", "不能确定「" + title + "」是腾讯视频里的哪一部");`
- a chosen show that is `upcoming`: open its detail page whatever `play` says, and the message is
  `"《" + title + "》" + TencentVideo.brief(s) + "还没上线：已在云视听极光打开它的页面，现在只有预告，可以预约"`
  (status `observed`; the "also" part as for any other result).
- nothing else in this file; no new `import` lines (use fully qualified names if you need a class).

## Checks
- `tests/agent/MovieChecks.java`: a new group `tencentWatch()` called from `run()`: the whole table (status, how,
  chosen, upcoming, sure), haimian's others, `plain("你的名字。")` equals `plain("你的名字")`, `plain("三体·黑暗森林")`
  equals `"三体黑暗森林"`, `sure` with a null / NONE result is false. At least 45 checks.
- `tests/agent/QuickChecks.java`: a new group `watch()` called from `run()`, with the file's own helpers
  `is(utterance, tool, argsJson)` and `none(utterance)`:
  - `is`: 我想看别无选择 / 我要看别无选择 / 想看别无选择 / 我想看看别无选择 / 我想看一下漫长的季节 / 我们想看庆余年第二季 /
    看庆余年第二季 / 我想看《长安的荔枝》 / 嗯我想看别无选择 → `{"title":"…","strict":true}`;
    我想看电视剧繁花 / 播放电视剧繁花 / 帮我放电影流浪地球2 / 我想看动画片海绵宝宝 (kind 动漫) / 我想看别无选择这部电视剧
    (kind is NOT set by the tail; title 别无选择) → with `"kind"` where one was said before the title;
  - `none`: 我想看繁花 (2 characters, no kind) · 我想看何同学的视频 · 我想看B站 · 我想看电影 · 我想看新闻联播吗 ·
    我想看繁花第5集 · 我想看最新的电影 · 你想看什么 · 我不想看别无选择 · 我想看一下设置 · 看一下推特 · 我想看少儿频道 ·
    我想看周杰伦演唱会 · 我想看红果短剧 · 播放别无选择 (no kind: it may be a song) · 放别无选择 · 我想听别无选择 ·
    打开别无选择;
  - every sentence that already has an `is(...)` or `none(...)` in this file keeps its result (run the suite);
  - `reply`: a `tencent_video_open` quick command answers with the result's `message`.
  At least 35 checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28.
- Do not edit the fixtures. Do not change `assets/agent/contract.json` (`strict` is deliberately not in it).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4327
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_watch_quick.sh` → `WATCH QUICK OK`
