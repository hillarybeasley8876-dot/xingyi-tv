# Brief: home-info — what the home screen shows from 星仪: 农历, the day's weather, what is playing

## Why
The user chose a new home screen (Orrery, variant B 「轴」, 2026-11-08): under the date a quiet line
「农历九月三十　·　多云 13° – 19°」, at the top right what is playing 「江南　林俊杰」, and under the focused app one line about
it (「上次在听《江南》林俊杰」). The home screen is another app with no network code and no right to read media sessions;
星仪's runtime (in the XiaoAi bridge) has the calendar (`Almanac`), the forecast cache (`Weather`) and the sessions
(`TvTools.nowPlaying`). The bridge already exports `AgentProvider` (authority `xyz.erapi0neer.xiaoaibridge.agent`,
signature permission, same key as the home screen): this job adds one `call` method to it.

## Read first
`src/xyz/erapi0neer/xingyi/agent/Almanac.java` (`environment`, the lunar month/day names, terms and festivals),
`Weather.java` (`cached`, `https`, `warm`, `forecastUrl`, `speak`, `text(code)`, `hint` / `hintFor`, the day choice),
`MediaSessions.java` (`Session`, `current`), `tv/AgentProvider.java` (all: how `call` dispatches and what it returns),
`tv/AgentEngine.java` (how `place()`, `TvTools.here(app)` and the tools are reached; `hints` builds a weather line from
the cache — reuse that path), `tests/agent/AlmanacChecks.java` and `WeatherChecks.java` (fixtures, a fixed now).

## What to build
1. `Almanac.homeLine(int y, int m, int d)` (pure): 「农历九月三十」; when the day is a solar term or a festival the
   calendar knows, add it after a full-width middle dot: 「农历十月初一　·　寒衣节」, 「农历九月十八　·　立冬」 (one item: the
   term first). Null-safe for years outside the table (returns "").
2. `Weather.homeLine(JSONObject forecast, long now, java.util.TimeZone tz)` (pure): today's 「多云 13° – 19°」 (the sky
   word from `text(code)`, the min and max rounded, an en dash with spaces); from 20:00 on, tomorrow's with 「明天 」 in
   front: 「明天 小雨 12° – 17°」. "" when the forecast lacks the day.
3. `AgentEngine.homeInfo()` → `JSONObject`: `{lunar, weather, now:{title, artist, app, state}, notes:{package: text}}`:
   lunar from `homeLine` (Asia/Shanghai today); weather from the **cached** forecast of the TV's own place only (never
   wait on the network: when the cache is cold, start `Weather.warm(...)` in the background and leave `weather` out);
   now from the media session (state `playing` → `now`; `paused` → no `now` but `notes[pkg]` = 「上次在听《title》artist」 for
   music apps (NetEase, QQ音乐, 酷狗) or 「上次在看《title》」 for the others; title missing → no note). Built at most once
   every 30 s (keep the last one), never throws (any part that fails is left out), and returns within ~300 ms.
4. `AgentProvider.call("home")` → a Bundle with `"json"` = `homeInfo().toString()`.

## Checks — `tests/agent/HomeInfoChecks.java` (stub already called by `AgentCoreTest.main`)
At least 25 on the pure parts with fixed dates and saved forecasts (`tests/agent/fixtures/` has Open-Meteo answers the
weather checks use): `homeLine` on an ordinary day, a term (立冬 2026-11-07), a festival (寒衣节 = 农历十月初一; 除夕
2027-02-05; 春节 2027-02-06), a leap month, a year outside the table; `Weather.homeLine` before and after 20:00, missing
days, rounding (13.5 → 14, −0.4 → 0, −2.6 → −3), a code the table does not know; the `notes` wording for music and
video packages (put the wording in a small pure helper, e.g. `HomeNotes.paused(pkg, title, artist)`).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3180
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_home_info.sh` → `HOME INFO OK`

## Files you own
`src/xyz/erapi0neer/xingyi/agent/Almanac.java` (homeLine only), `src/xyz/erapi0neer/xingyi/agent/Weather.java`
(homeLine only), `src/xyz/erapi0neer/xingyi/agent/HomeNotes.java`, `src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java`
(homeInfo only), `src/xyz/erapi0neer/xingyi/agent/tv/AgentProvider.java`, `tests/agent/HomeInfoChecks.java`.
