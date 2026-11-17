# Brief: tencent-open-2 — a show known by a shorter name, and platforms the first job did not know

## Why (the engine's own search run against the real service on the PC, 2026-11-10 23:00)
`TencentVideo.pick` on real answers was right for 繁花, 狂飙, 庆余年第二季, 流浪地球2, 奔跑吧 and 三体, and wrong for two:
- 「甄嬛传」 → NONE. The show is titled **「后宫甄嬛传」** (电视剧 2011, 播放源 `letv`, 76 集); 「甄嬛传(美版)」 is another
  edition. Fixture: `tests/agent/fixtures/tencent-search-zhenhuan.json`. The related box (boxId 3505) also holds
  「看懂《甄嬛传》就读懂了人性」 (a 纪录片 on Tencent) — that must never be taken for the show.
- 「琅琊榜」 → the exact one plays on `yangshipin` (央视频), which the platform table does not know, so the sentence would
  say 「它在yangshipin」. Fixture: `tencent-search-langya.json`.
Two more fixtures for what must keep working: `tencent-search-manchang.json` (漫长的季节: the exact one, and
「漫长的季节[卫视版]」) and `tencent-search-lizhi.json` (长安的荔枝: a 电影 2025 and a 电视剧 2025 of the same name, both in
Tencent Video).

## What to build (`TencentVideo.java`; the table of TV apps in `TvTools.java`)
1. A third way to match, used **only when there is no exact and no season candidate at all** (on any platform): an
   *alias* — the normalised title **ends with** the asked title (「后宫甄嬛传」 for 甄嬛传), or **starts with** it and the
   rest is one bracketed edition (「甄嬛传(美版)」, 「漫长的季节[卫视版]」 — round, square, full-width brackets). The asked
   title must be at least 3 characters. Only candidates from the `MainNeed` box and from `normalList` (never the other
   boxes: they are "related"), and only types 电视剧, 电影, 综艺, 动漫, 少儿 (or `kind` when given). Among aliases, one
   that ends with the asked title comes before a bracketed edition; then Tencent before other platforms as usual
   (FOUND / ELSEWHERE as for exact ones). The result's show carries the real title (「后宫甄嬛传」).
2. Platform names: `letv` 乐视视频, `yangshipin` 央视频, `pptv` PP视频, `wasu` 华数TV, `m1905` / `1905` 1905电影网,
   `hunantv` 芒果TV (as `mgtv`), `sdp` 「别的网站」; anything else stays the enName.
3. Same title, two kinds (长安的荔枝: 电影 and 电视剧, no `kind` given): the first in candidate order is opened, as today,
   and the other is in `others` — check that `others` then names it with its kind (「《长安的荔枝》（电视剧，2025）」), so the
   reply can say there is also the series.
4. `TvTools`' table of TV apps (`PLATFORM_APPS`): 央视频 → 央视频. (乐视视频 has no TV app here: no entry.)

Expected (your `pick` must give exactly this):
| fixture | title | kind | status | chosen | others / platforms |
|---|---|---|---|---|---|
| zhenhuan | 甄嬛传 | | ELSEWHERE | 后宫甄嬛传, 电视剧, 2011 | 乐视视频 76 集 |
| langya | 琅琊榜 | | ELSEWHERE | 琅琊榜, 电视剧, 2015 | 央视频 54 集 |
| manchang | 漫长的季节 | | FOUND | mzc00200fhhxx8d, 漫长的季节, 电视剧, 2023, 12 | (the 卫视版 may be in others) |
| lizhi | 长安的荔枝 | | FOUND | mzc00200tj1w6ah, 长安的荔枝, 电影, 2025 | includes 长安的荔枝 电视剧 2025 |
| lizhi | 长安的荔枝 | 电视剧 | FOUND | mzc00200iyue5he, 长安的荔枝, 电视剧, 2025, 53 | |
| kuangbiao | 狂飙 | | ELSEWHERE | 狂飙 (unchanged: an exact one exists, so no alias is looked for) | 爱奇艺 39 集 |
| fanhua | 繁花 | | FOUND | mzc00200whsp9r6 (unchanged) | |
| none | 不存在的片 | | NONE | | |
Every existing check in `MovieChecks.tencent()` keeps passing unchanged.

## Checks (`tests/agent/MovieChecks.java`, a new group `tencentAlias()` called from its run())
The table above; 「甄嬛」 (2 characters) on zhenhuan → NONE (too short for an alias); the documentary in box 3505 is never
chosen for 甄嬛传 even with `kind` 纪录片 absent; the platform names for letv, yangshipin, an unknown enName. At least 12
new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28.
- In `TvTools.java` change only the `PLATFORM_APPS` block (another job edits the same file); no new `import` lines.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4145
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_tencent_open.sh` → `TENCENT OPEN OK`
