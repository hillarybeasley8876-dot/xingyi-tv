# Brief: tencent-open — 「我想看繁花」 in one step: `tencent_video_open` finds the show in Tencent Video and opens it

## Why (the TV, 2026-11-10)
- 10:45 「我想看狂飙」 wandered for 2 minutes in 云视听极光 (Tencent Video's TV app) and found nothing: the app's pages are
  self-drawn, Eyes reads no elements there (`（没有读到界面元素…）`), so the model can only look at screenshots and press
  keys — and 狂飙 is not in Tencent Video at all (it is iQiyi's).
- Tencent Video's own website search says, for a title, which shows exist, their cover id, type, year, episode count
  and **where each one plays** (腾讯视频, or another site: 爱奇艺 `qiyi`, 哔哩哔哩 `bilibili`, 优酷 `youku`…).
- Measured on the TV 21:43–21:47 (probe3/probe4): with intent action `com.tencent.qqlivetv.open`, package
  `com.ktcp.video`:
  - data `tenvideo2://?action=1&cover_id=<cid>` → the show's detail page,
    `com.ktcp.video/.activity.onepage.NormalOnePageActivity` (2.5 s after a force-stop, 0.6 s warm); the focus is on the
    small player window, OK makes it full screen.
  - data `tenvideo2://?action=7&cover_id=<cid>` → full-screen playback, `com.ktcp.video/.activity.TVPlayerActivity`
    (2.5 s cold, 0.8 s warm). Without a membership a pre-roll ad of about two minutes plays first.
  - The app has no media session, and Eyes gets no window events from these pages (its foreground class stayed at
    `OpenJumpProxyActivity`). The window manager's focused activity is right: `adb.call(LocalAdb::focusedApp)` returns
    `"com.ktcp.video/com.ktcp.video.activity.TVPlayerActivity"` (package, slash, full class name; "" when unknown) — use
    `FrontApp.pkg(...)` / `FrontApp.cls(...)` on it. Do not open `LocalAdb.java`.

## The search (pure, in a new `src/xyz/erapi0neer/xingyi/agent/TencentVideo.java`)
- POST `https://pbaccess.video.qq.com/trpc.videosearch.mobile_search.MultiTerminalSearch/MbSearch?vplatform=2`, headers
  `Content-Type: application/json`, `Referer: https://v.qq.com/`, `Origin: https://v.qq.com`, body (UTF-8 JSON):
  `{"version":"25042201","clientType":1,"filterValue":"","uuid":"DE7F930F-307B-4405-B91F-7A0D95402A62","retry":0,
  "query":"<title>","pagenum":0,"isPrefetch":true,"pagesize":30,"queryFrom":0,"searchDatakey":"","transInfo":"",
  "isneedQc":true,"preQid":"","adClientInfo":"","extraInfo":{"isNewMarkLabel":"1","multi_terminal_pc":"1",
  "themeType":"1","sugRelatedIds":"{}","appVersion":""}}`. Build it with org.json (never by string concatenation).
- Real answers, trimmed to the fields that matter, are in `tests/agent/fixtures/tencent-search-*.json`
  (fanhua 繁花, kuangbiao 狂飙, qingyunian 庆余年, fanren 凡人修仙传, paonan 奔跑吧, santi 三体, liulang2 流浪地球2, none).
  Shape: `data.areaBoxList[].{boxId, itemList[]}` and `data.normalList.itemList[]`; an item is
  `{doc:{dataType,id}, videoInfo:{title (may carry <em>…</em>), typeName, year, area, subTitle, descrip, playSites:[
  {showName, enName, totalEpisode, episodeInfoList}]}}`. A show has `doc.dataType == 2` and its `doc.id` is the cover id;
  other dataTypes (1 = a clip) are not shows. `playSites[0].enName == "qq"` (showName 腾讯视频) plays in Tencent Video;
  anything else (`qiyi`, `bilibili`, `youku`, `mgtv`…, showName 播放源) is another platform.
- `TencentVideo.pick(String json, String title, String kind, int year)` → a result with `status` FOUND / ELSEWHERE /
  NONE, the chosen show (cid, title, type, year, episodes), `others` (other shows worth naming) and for ELSEWHERE the
  platforms. Rules:
  1. Candidates: every dataType-2 item, in this order: the box with boxId `MainNeed`, then `normalList`, then the other
     boxes; the same cover id once. Titles without `<em>` tags.
  2. Normalise for comparing: drop 《》「」“” and spaces, full-width to half-width digits/letters, lower-case ASCII.
  3. *Exact* = normalised title equals the asked one. *Season* = it starts with the asked one and the rest is a season
     or part (`第十季`, `第2季`, `2`, `·大湾区篇`, ` 第9季`). Types 短剧 and 原创 never count unless `kind` asks for them.
  4. `kind` (when given) must equal typeName and `year` (when > 0) the year; if that leaves no candidate at all, the
     filters are dropped (and the result says so in `others`).
  5. FOUND = the first exact candidate that plays in Tencent Video; if no exact one does but a season one does, the
     season one with the highest year (the first of them in candidate order on a tie). One exception: when the exact
     one is a 综艺 and a season candidate has a later year, the newest season is chosen (a running show: 「奔跑吧」 means
     this year's). `others`: up to 3 more exact-or-season Tencent candidates in candidate order (title, type, year),
     leaving out any with the same normalised title, type and year as the chosen one, so the reply can say 「还有…」.
  6. ELSEWHERE = no Tencent candidate, but an exact one on another platform: its platforms in Chinese (qiyi 爱奇艺,
     youku 优酷, bilibili 哔哩哔哩, mgtv 芒果TV, sohu 搜狐视频, cctv/cntv 央视, migu 咪咕视频, else the enName) with the
     episode count.
  7. NONE otherwise, with up to 3 nearest show titles from the candidates (for 「没搜到《X》，有《…》」).
  The lead ran these rules on the fixtures (a throw-away script); your `pick` must give exactly this:
  | fixture | title | kind | year | status | chosen (cid, title, type, year, episodes) | others / platforms |
  |---|---|---|---|---|---|---|
  | fanhua | 繁花 | | | FOUND | mzc00200whsp9r6, 繁花, 电视剧, 2023, 36 | (none: the other 繁花 2023 is the same show) |
  | kuangbiao | 狂飙 | | | ELSEWHERE | 狂飙, 电视剧, 2023 | 爱奇艺 39 集 |
  | kuangbiao | 狂飙 | 电视剧 | | ELSEWHERE | 狂飙, 电视剧, 2023 | 爱奇艺 39 集 |
  | qingyunian | 庆余年 | | | FOUND | rjae621myqca41h, 庆余年, 电视剧, 2019, 46 | 庆余年第三季 (2026), 庆余年第二季 (2024) |
  | qingyunian | 庆余年 | | 2024 | FOUND | mzc002002kqssyu, 庆余年第二季, 电视剧, 2024, 37 | |
  | qingyunian | 庆余年第二季 | | | FOUND | mzc002002kqssyu, 庆余年第二季, 电视剧, 2024, 37 | |
  | fanren | 凡人修仙传 | | | FOUND | mzc003onbddtsyr, 凡人修仙传, 动漫, 0, 127 | |
  | fanren | 凡人修仙传 | 电视剧 | | ELSEWHERE | 凡人修仙传, 电视剧, 2025 | 优酷 30 集 |
  | paonan | 奔跑吧 | | | FOUND | mzc002001mel3nv, 奔跑吧 第10季, 综艺, 2026 | includes 奔跑吧 (2017) |
  | santi | 三体 | | | FOUND | mzc002007knmh3g, 三体, 电视剧, 2023, 40 | 三体·黑暗森林 (2026), 三体·周年纪念版 (2023) |
  | santi | 三体 | 动漫 | | ELSEWHERE | 三体, 动漫, 2022 | 哔哩哔哩 15 集 |
  | liulang2 | 流浪地球2 | | | FOUND | mzc002008v8rka3, 流浪地球2, 电影, 2023, 1 | |
  | none | 不存在的片 | | | NONE | | |
  (`year` filters candidates before exact/season is decided, which is why 庆余年 + 2024 is the second season.)
- Links: `TencentVideo.detailLink(cid)` = `tenvideo2://?action=1&cover_id=<cid>`, `playLink(cid)` =
  `tenvideo2://?action=7&cover_id=<cid>`; a cid is `[A-Za-z0-9]{6,32}` or the method throws IllegalArgumentException.

## The fetch
`Feeds.java`: add `public interface Post { String post(String url, String json, int timeoutMs) throws IOException; }`,
`public static Post httpsPost()` (HttpURLConnection POST, the headers above for `pbaccess.video.qq.com`, UTF-8, same
timeouts and User-Agent style as `fetch`), and `public static Post cached(Post p, long ttlMs)` keyed by url + body.

## The tool (`TvTools`)
`tencent_video_open {title, kind?, year?, play?}` (the contract already has it; `play` defaults to true):
1. No 云视听极光 installed → the same `unavailable` text as tencent_video_search.
2. Search (8 s, cached 10 minutes), `TencentVideo.pick`. A failed fetch → `fail("error", "腾讯视频的搜索没连上：可以用
   tencent_video_search 打开搜索页自己找")`.
3. NONE → `fail("not_found", "腾讯视频没搜到《X》" + （有《A》《B》）)`.
4. ELSEWHERE → `fail("not_here", …)` with message 「腾讯视频（云视听极光）没有《狂飙》（电视剧，2023）：它在爱奇艺，共 39 集。」 plus,
   for each platform, whether its TV app is on this TV (`apps()` labels): 爱奇艺 → 银河奇异果, 优酷 → 酷喵, 芒果TV → 芒果TV,
   哔哩哔哩 → 哔哩哔哩: 「电视上没装银河奇异果（爱奇艺的电视版）」 or 「电视上有哔哩哔哩，可以用 bili_search 找」; a `platforms`
   array; and a `note` field: 「把上面这句话告诉用户就行：不要自己安装应用，也不要打开别的应用去找；用户说要装时再用 install_app」.
5. FOUND → as the song tools do: a silent test on a sleeping TV is rejected (`songAsleep`-style text, with 打开视频), else
   the screen is woken (`ScreenWake.ensureOn`). Start the link (action `JIGUANG_OPEN`, package `JIGUANG`): the play link
   when `play` is not false, else the detail link. Wait up to 12 s (300 ms steps) until `LocalAdb::focusedApp` is
   云视听极光's and its class's simple name is `TVPlayerActivity` (play) / `NormalOnePageActivity` (detail).
   - arrived, play: `ok("observed", "已在云视听极光播放《繁花》（电视剧，2023，共 36 集）；不是会员的话正片前有一两分钟广告")`
   - arrived, detail: `ok("observed", "已在云视听极光打开《繁花》（电视剧，2023，共 36 集）的详情页，按确认键全屏播放")`
   - `others` not empty: append 「。还有《繁花剧场版》（2024）」 (at most two) and put them in an `others` array.
   - not arrived: `fail("unconfirmed", "已请求云视听极光打开《繁花》，前台还不是它的播放页")` (详情页 for detail).
   Episodes 0 → leave 「共 N 集」 out; year 0 → leave it out.
6. `tencent_video_search`: arrival is `LocalAdb::focusedApp` = 云视听极光's `SearchActivity` (the text check can stay as a
   second way): `ok("observed", "云视听极光的搜索页已打开，搜的是「狂飙」。这个应用的界面读不出文字：要看结果用 screen_look，要直接
   打开某一部用 tencent_video_open")`.
7. `AWAKE_ONLY` gets `tencent_video_open`. In `AgentLoop`: add it to `OPENS`, `DIRECT`, `ANSWERS` and `EVIDENT`.
   `Movies.java` line ~172: the hint for 腾讯 becomes 「电视上用云视听极光（tencent_video_open）」.
8. Do not add `import` lines to TvTools.java (another job edits the same file): use fully qualified names where needed.

## Checks (`tests/agent/MovieChecks.java`, a new group `tencent()` called from its run())
Every expectation listed under the pick rules (read the fixtures with the helper the file already uses for fixtures, or
`java.nio.file` — tests may use it), the links, a bad cid (throws), the request body (it parses as JSON and carries the
query), ELSEWHERE's Chinese platform names, NONE's nearest titles. At least 24 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network (the checks use the fixtures; the tool's fetch is never run by a check).
- Java 8 / Android API 28; org.json's JSONException is checked on Android; read JSON strings with `News.str` (Android's
  org.json reads JSON null as the string "null").
- If `python tests/agent/prompts_doc.py --check` fails after your change, run `python tests/agent/prompts_doc.py`.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4067
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `python tests/agent/prompts_doc.py --check` → `PROMPTS.md is up to date`
- `sh tests/agent/accept_tencent_open.sh` → `TENCENT OPEN OK`
