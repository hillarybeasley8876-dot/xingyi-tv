# Brief: data-polish — five answers from the 2026-11-10 morning sweep that were wrong, odd or slow

## Why
The lead ran 54 everyday non-music requests on the TV (silent test source, `tests/agent/scenario_sweep.py`). Most data
answers were right and fast. These five were not:
1. 「湖人昨天赢了吗」 → 「湖人正在比赛：湖人 31 比 22 领先老鹰，第 1 节结束；下一场是 11 月 12 日上午 9 点，客场打雄鹿」 — the
   question was about the last game, and the answer never says how it ended.
2. 「最近AI圈有什么新闻」 at 08:3x → 「量子位 AI 新闻（最新一条 16 点 57 分）：…」 — 16:57 was yesterday; said like this it sounds
   like today.
3. 「何同学在直播吗」 → 「老师好我叫何同学现在没在直播；…」 — the channel's full name read straight into the sentence
   sounds like 「老师好，我叫何同学，现在没在直播」.
4. 「最近有台风吗」 took 7.5 s although it is a quick command with no model round (the typhoon list, the track and the
   province's alarms are fetched one after another).
5. 「这周有什么新番」 took 7.1 s and three model rounds calling `bili_bangumi` once per day: the tool knows one day at a time.

## What to build
1. **`nba_games` with `last: true`** (the contract now has this parameter: 「问某支球队上一场、昨天那场、赢没赢时填 true：先说
   上一场打完的结果（正在打的放在后面）」). In team mode with `last`, the sentence starts with the team's last *finished*
   game — date in Beijing time, score, won or lost, against whom (「湖人上一场 11 月 8 日 117 比 109 赢了勇士」) — then, when
   a game is live, 「现在正在打：…」 as today, then the next game as today. Without `last` nothing changes. No finished
   game in the schedule → say so in a few words and go on as today. `Sports.speakTeam` (and the tool in `TvTools`) is
   where this lives; build the checks on `tests/agent/fixtures/nba-schedule-nyk.json` (and a copy of it changed in the
   test, in memory, so one game is in progress).
2. **News times with the day** (`News.java`, the 「（最新一条 …）」 part): the newest item's time gets 昨天 / 前天 / 「11 月 8 日」
   before it when it is not from today (Beijing date, the TV's clock); today's stays as it is.
3. **Live status with the name the user said** (`BiliVideos.java`): when the asked name (`up`) is part of the channel's
   name and shorter than it (何同学 in 老师好我叫何同学), the live-status sentence uses the asked name (「何同学现在没在直播；
   最新的视频是…」). Otherwise the channel's name, as today.
4. **Typhoon and alarms in parallel** (`Alerts.java` and the `weather_alert` tool): the fetches that do not depend on each
   other start together (two or three threads, each with the timeout it has today), and each URL's answer is kept for
   10 minutes, so a second ask in that time costs nothing. Same sentences as today.
5. **`bili_bangumi` with `day: "这周"`** (`Bangumi.java` + the tool): 「这周」「这几天」「最近」 mean today and the next six days:
   one sentence, day by day (今天、明天、then 周三…), each with its count and at most three names (「今天 3 部：掌门低调点、
   王者？别闹！、海绵宝宝；明天 3 部：…」), days with nothing left out, at most about 300 characters (cut whole days at the end
   and say 「还有…天没念」). A `title` given → unchanged. The contract already describes 这周.

## Checks
In the files that already test these classes: `tests/agent/FeedChecks.java` (Sports, News), `tests/agent/BiliChecks.java`
(BiliVideos), `tests/agent/AlertChecks.java` (Alerts), `tests/agent/BangumiChecks.java` (Bangumi), each a new group called
from its run(). At least 20 new checks, among them: last finished game first with `last` (won and lost wording); live game
after it; no `last` → today's sentence unchanged; the news day word for yesterday, the day before, an older date, today
(none); 何同学 / 老师好我叫何同学 and a name that is not part of the channel name; a cache hit returns the same answer
without a second fetch (count the fetches through the fake you give the class); 这周 on
`tests/agent/fixtures/bangumi-anime.json` lists days in order and stays under the length limit.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network (the classes take their fetches through what `Feeds` / the tools pass in; fake them).
- Java 8 / Android API 28; org.json's JSONException is checked on Android; read strings with `News.str` (Android's
  org.json reads JSON null as "null").

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 3837
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_data_polish.sh` → `DATA POLISH OK`
