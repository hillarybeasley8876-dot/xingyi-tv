# Brief: watch-heard — a title as speech recognition wrote it still finds its show; the search answer is kept small

## Why (the real search asked on the PC, 2026-11-11 01:30)
XiaoAi's recognition writes titles by sound: 「狂彪」 for 狂飙, 「琅琊帮」 for 琅琊榜, 「凡人修仙转」 for 凡人修仙传,
「甄环传」 for 甄嬛传; and numbers either way: 「流浪地球二」 for 流浪地球2, 「庆余年2」 for 庆余年第二季. Tencent's search
puts the right show first for all of these — but `TencentVideo.pick` only takes a title that is written the same, so
they all end as NONE (「腾讯视频没搜到《狂彪》（有《狂飙》…）」) and the model needs another round to try the other
spelling. `Pinyin.sameSound(a, b)` (already in the core, used for stock names) tells that two strings sound the same.

Second: the answer of one search is 0.1–1.1 MB of JSON, of which `pick` reads about 15 KB. `TvTools` keeps the last
32 answers whole for ten minutes (`Feeds.cached`) — up to 32 strings of a megabyte or two each in a process that
otherwise holds about 8 MB, on a TV with 2 GB.

Job watch-quick (merged before this one) gave `TencentVideo` `plain(String)`, `Show.upcoming`, `Result.how`
(`"exact"`, `"season"`, `"alias"`, `""`) and `sure(Result, asked, kindNamed)`. Read them first.

## What to build (`TencentVideo.java`, pure; two lines in `tv/TvTools.java`)

### 1. Numbers written the other way — only when there is no exact and no season candidate at all
`plain(asked)` ends with one of 一二三四五六七八九十 or with one digit 1–9, and what is before it (the *base*) has at
least 2 characters. With n the number, a candidate from the `MainNeed` box or `normalList` matches when
- `plain(title)` is the base followed by n as a digit or as a Chinese numeral (流浪地球2 for 「流浪地球二」) → how `"exact"`;
- or `plain(title)` is the base + `第` + n (digit or Chinese numeral) + `季` or `部` (庆余年第二季 for 「庆余年2」) →
  how `"season"`.
Among the matches: the first released one on Tencent → FOUND; else the first upcoming one on Tencent → FOUND
(`show.upcoming`); else the first with platforms → ELSEWHERE. No match → go on to 2.

### 2. The same sound — only when 1 found nothing either
A candidate from the `MainNeed` box or `normalList` whose `plain(title)` has the same length as `plain(asked)`, is not
equal to it, and `Pinyin.sameSound(plain(asked), plain(title))`. Same order of preference as in 1 (released on Tencent,
upcoming on Tencent, elsewhere); how is `"heard"`. The result's show carries the real title (狂飙). `others` (FOUND
only): the other same-sounding shows on Tencent, as for exact ones, at most three.

### 3. A shorter name by sound — in the alias step that exists
Where the alias step tests `n.endsWith(asked)`, also accept a title whose last `asked.length()` characters sound the
same as the asked title (后宫甄嬛传 for 「甄环传」). How stays `"alias"`. The bracketed-edition test is unchanged.

### 4. `sure`
`"heard"` counts like `"exact"` and `"season"` (the other conditions unchanged: FOUND, released, the six types, three
characters or two with a named kind).

### 5. The answer, cut down — `public static String slim(String json)`
Returns a JSON string in the shape of the fixtures (`tests/agent/fixtures/slim_tencent.py` is the reference, read it):
`{"data":{"errcode":…,"errmsg":…,"areaBoxList":[{"boxId":"…","itemList":[…]}],"normalList":{"itemList":[…]}}}`; only
items whose `doc.dataType` is 2; of each item only `doc.dataType`, `doc.id`, and of `videoInfo`: `title`, `typeName`,
`year`, `status`, `playSites` (of each site `enName`, `totalEpisode`). (The fixtures also keep `area`, `subTitle`,
`descrip`, `showName`, `episodeInfoList` — `slim` may drop those: `pick` does not read them.) Boxes left without items
are dropped. A string that is not such an answer (no `data`) is returned unchanged.
`pick(slim(x), …)` must equal `pick(x, …)` for every fixture.

In `tv/TvTools.java`: the Tencent feed slims before it caches —
`TENCENT_FEED = Feeds.cached((url, body, ms) -> TencentVideo.slim(Feeds.httpsPost().post(url, body, ms)), 10 * 60_000L)`
(or the same with a named variable). Nothing else in that file; no new `import` lines.

## Expected (`pick(fixture, title, "", 0)`, `sure(r, title, false)`; "chosen" as `MovieChecks.chosen` prints it)
| fixture | title | status | how | chosen | platforms | sure |
|---|---|---|---|---|---|---|
| heard-kuangbiao | 狂彪 | ELSEWHERE | heard | sdp0010051ttxdf,狂飙,电视剧,2023,39 | 爱奇艺 | false |
| heard-langya | 琅琊帮 | ELSEWHERE | heard | sdp00100515ow3q,琅琊榜,电视剧,2015,54 | 央视频, 爱奇艺 | false |
| heard-fanren | 凡人修仙转 | FOUND | heard | mzc003onbddtsyr,凡人修仙传,动漫,0,127 | | **true** |
| heard-liulang2 | 流浪地球二 | FOUND | exact | mzc002008v8rka3,流浪地球2,电影,2023,1 | | **true** |
| heard-qingyunian2 | 庆余年2 | FOUND | season | mzc002002kqssyu,庆余年第二季,电视剧,2024,37 | | **true** |
| heard-zhenhuan | 甄环传 | ELSEWHERE | alias | sdp001kemwgrsyo,后宫甄嬛传,电视剧,2011,76 | 乐视视频, 优酷 | false |
| heard-none | 别无选者 | NONE | "" | - | | false |
| santidark | 三体二 | NONE | "" | - | | false |
| qingyunian | 庆余年2 | FOUND | season | mzc002002kqssyu,庆余年第二季,电视剧,2024,37 | | **true** |
| liulang2 | 流浪地球2 | FOUND | exact | mzc002008v8rka3,流浪地球2,电影,2023,1 | | **true** (unchanged: exact as before) |
| kuangbiao | 狂飙 | ELSEWHERE | exact | (unchanged) | 爱奇艺 | false |
Every existing check in `MovieChecks` keeps passing unchanged (if one cannot, stop and say which and why — do not
edit it).

## Checks (`tests/agent/MovieChecks.java`, a new group `tencentHeard()` called from `run()`)
The table (status, how, chosen, platforms, sure); the kind filter works as before: 「狂彪」 with kind 电视剧 → the same
ELSEWHERE result, 「凡人修仙转」 with kind 电视剧 → ELSEWHERE heard `sdp001000aabhuk,凡人修仙传,电视剧,2025,30` on 优酷;
a title that only shares the sound of *part* of the asked one is not taken
(「狂彪传」 on heard-kuangbiao → NONE); `slim`: for **every** `tencent-search-*.json` fixture `pick` gives the same
status / how / chosen / others on `slim(fixture)` as on the fixture, with at least three different titles tried per
fixture where that makes sense; `slim` of `{"ret":20607}` is returned unchanged; `slim(slim(x))` equals `slim(x)`;
a fixture padded with a 200 000-character field inside one `videoInfo` slims to under 40 000 characters.
At least 45 checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28. Do not edit the fixtures or `Pinyin.java`.
- In `TvTools.java` change only the `TENCENT_FEED` line (another job edits the same file at the same time).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 45 more than before you
  started (the job's check requires the number in the job file)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_watch_heard.sh` → `WATCH HEARD OK`
