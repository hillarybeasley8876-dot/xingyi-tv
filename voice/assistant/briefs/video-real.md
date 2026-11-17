# Brief: video-real — what the TV itself showed on 2026-11-10 10:41–10:48 (the user's go-ahead, screen on)

## Why (each of these was run on the real TV; screenshots and the system log are the evidence)
1. **云视听极光 search shows garbage.** `tencent_video_search` sends `tenvideo2://?action=9&search_key=` + `Uri.encode(keyword)`.
   The app opened its SearchActivity (6.5 s cold) with **「%E7%8B%82%」** in the search box: it takes the query text as
   it is, without decoding it (and cuts it to 10 characters). 「我想看狂飙」 then wandered for 2 minutes (screen_look,
   explore_app, open_app, keys, the search once more) and never found the show.
2. **A B站 video link on a cold B站 goes nowhere.** 「播放影视飓风最新的视频」: `bili_videos` found it, `open_link
   bilibili://video/117568757109187` started B站's process and its `tv.danmaku.bili/.ui.intent.IntentHandlerActivity`
   (10:42:54) — and nothing happened for 35 s, no page was ever displayed, until the model opened B站 by its launcher
   (MainActivityV2) and gave up: 「没能把哔哩哔哩成功拉起来播放」. (bili_search already copes with the same thing for its
   search link: B站 sometimes swallows the first intent, so the search is sent once more when B站 is up and idle.)
3. **B站's own login dialog blocks the page.** 「看看B站的观看历史」: `bilibili://history` opened 历史记录 (the list was
   there behind it), but B站 also started `bilibili://main/login/sms/dialog-step?from=home` →
   `tv.danmaku.bili/.sms.SmsLoginDialogActivityV3`, a modal 「登录注册解锁更多精彩内容」 with a × at its top right. The model
   spent 90 s reading and looking at the screen and never closed it. BACK closes such a dialog on B站 (it is a dialog
   activity on top of the page).
4. **netease_play missed a song whose title has a subtitle.** 「播放李佳薇的甲乙丙丁」: `netease_play {title:"甲乙丙丁",
   artist:"李佳薇"}` → `not_found`, then the model tried again with the catalog's full title 「甲乙丙丁 (你我怎么两清)」 and it
   played (5 s and a model round lost). `NeteaseCatalog` wants the catalog name to equal the asked title after
   normalising; NetEase writes alternate titles in brackets: 「甲乙丙丁 (你我怎么两清)」「如果可以 (电影《月老》主题曲)」
   「布拉格广场 (单元剧《上班女郎》主题曲)」「无拘 (永劫无间周年主题曲)」.

## What to build
1. `tencent_video_search` (TvTools): the keyword goes into the link **as it is** (no `Uri.encode`; build the string and
   `Uri.parse` it), after taking off characters that would break the link (`&`, `#`, `?`, `=`, `%`, spaces at the ends).
   Keywords longer than 10 characters are cut to 10 (the box holds 10). Nothing else in the tool changes.
2. B站 video links (`openBiliVideo`): when B站 was not running before the link (no process / not in the recent tasks —
   use what TvTools already knows, e.g. `foreground()` and the media session; or simply "B站 was not in front"), and
   after 10 s the front is still not a player page (`PlayerPages.isPlayer`) but is a B站 page
   (`IntentHandlerActivity`, `MainActivityV2`, a splash), send the same link **once more**; then keep waiting as today
   (the whole wait may grow to 35 s). Never more than one resend. Keep everything else (the media-session check, the
   reply wording, the screen wake) as it is.
3. B站's login dialog: a small helper `dismissBiliLogin()` in TvTools — when the activity in front (Eyes' foreground
   class, or `LocalAdb::focusedApp`) is B站's and its class name contains `LoginDialog` (`.sms.SmsLoginDialogActivityV3`
   today), press BACK once and wait 600 ms. Call it in `open_link` for B站 targets (after the page arrived, before the
   screen is read for the result), in `openBiliVideo` while waiting, and in `bili_search` before it reads the results.
   At most once per tool call; when it pressed, the tool's message says 「（关掉了B站自己弹的登录框）」.
4. `NeteaseCatalog`: a catalog song whose name is the asked title followed by a bracketed subtitle — 「 (…)」「（…）」
   「 [ … ]」「 - …」 — counts as that title, **unless** the bracket holds a version word the code already treats as a
   version (Live, 伴奏, 纯音乐, Instrumental, DJ, Remix, 翻唱, cover, 女声, 男声, 片段, 慢速, 加速, 0.8x …: reuse the existing
   VERSIONS / LIVE patterns). An exact name still wins over a subtitled one. Where this goes: the title comparisons in
   `find` / `findByTitle` (`normalized(name)` against the wanted title, around lines 104–118 and 209–216).

## Checks
- `tests/NeteaseCatalogTest.java` (run by `sh tests/run_catalog.sh`): 「甲乙丙丁」 by 李佳薇 finds 「甲乙丙丁 (你我怎么两清)」;
  「如果可以」 finds 「如果可以 (电影《月老》主题曲)」; 「演员」 does **not** take 「演员 (伴奏)」 or 「演员 (Live)」 when the original is
  there, and an exact 「江南」 wins over 「江南 (某某版)」; all existing checks still pass. At least 8 new checks.
- `tests/agent/SkillChecks.java`: the link the Tencent search builds (make the builder a small static method you can call
  with a keyword, in TvTools or a pure class): 狂飙 → `tenvideo2://?action=9&search_key=狂飙`; 「a&b=c#d」 loses `&`, `=`, `#`;
  a 14-character name is cut to 10; and the login-dialog class test (a pure static `isBiliLogin(String pkg, String cls)`):
  B站 + `tv.danmaku.bili.sms.SmsLoginDialogActivityV3` → true; B站 + `MainActivityV2` → false; another app's LoginDialog
  → false. At least 8 new checks there.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 3988
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/run_catalog.sh` → `PASS exact-title and artist-only catalog checks`
- `sh tests/agent/accept_video_real.sh` → `VIDEO REAL OK`
