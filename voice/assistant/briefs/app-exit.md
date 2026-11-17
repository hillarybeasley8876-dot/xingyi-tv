# Brief: app-exit — 「退出网易云」 leaves the app for the home in one step

## Why
2026-11-08 12:14 on the TV the user said 「回到桌面退出网易云」. The bridge's song path took it for a song (fixed by the
lead in the bridge's MusicIntent); it now reaches 星仪, which has no one-step way to leave an app: `press_keys` needs a
plan, so in calibration the model either stopped the song and called it done, or went 9 rounds. The lead has added a
`media_control` action `exit` to the contract (`assets/agent/contract.json`: the enum and its description, and a rule
after 「不要只按返回」); with a simulated exit the model calls it in one round, 3 times out of 3. Your job: make `exit`
real on the TV, and catch the common phrasings as a quick command so they do not wait for the model at all.

## Read first
- `src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java`: the `media_control` tool (`tools.add(new Base("media_control")`),
  `nowPlaying()`, `label(pkg)`, `session(s)`, and how `press_keys` sends `home` (the key table near the top and the
  `press_keys` tool).
- `src/xyz/erapi0neer/xingyi/agent/QuickCommand.java`: `match(utterance, appLabels)`, `keys(s)`, `HOME`, `APP_ALIASES`,
  how 「打开…」 resolves an app name to an installed label, `cmd(...)`, and how a quick command's answer is said.
- `tests/agent/QuickChecks.java` (`is`, `none`, `apps()`, `notSimple()`) and `tests/agent/AgentCoreTest.java`.

## What to do
1. **TvTools, `media_control` action `exit`** (the other actions stay exactly as they are):
   - note the app in front (its label) and the media session;
   - if something is playing: MEDIA_STOP; if it still plays a second later, MEDIA_PAUSE;
   - then HOME, sent the same way `press_keys` sends `home`;
   - wait up to 1.5 s for the home screen to be in front (the package the HOME intent resolves to; today
     `xyz.erapi0neer.orrery`);
   - answer `ok("observed", "已退出<label>，回到桌面")`; if a session of that app is still `playing`,
     `fail("unconfirmed", "<label>还在后台放《<title>》…")` with the session; if the home was already in front and
     nothing played, `ok("observed", "已经在桌面了")`.
   - `describe`: 退出应用回到桌面. The gate stays as it is (the next/previous denial included).
2. **QuickCommand**: a sentence that is only leaving an app → `media_control {"action":"exit"}`:
   退出 | 关掉 | 关闭 | 关了 + an app name that resolves the way 「打开…」 does (aliases, installed labels), with
   回到桌面 / 返回桌面 / 回桌面 / 回到主页 / 返回主页 allowed before or after, joined by nothing, 并, 然后, 再 or a comma;
   and 「把<app>关了/关掉」. Examples that must match: 「退出网易云」「关掉B站」「关闭网易云音乐」「回到桌面退出网易云」
   「退出网易云回到桌面」「返回桌面关掉网易云」「把网易云关了」「把B站关掉」「退出哔哩哔哩」.
   Must not change: a name that is not an installed app (「关掉屏幕」「关闭声音」「关掉弹幕」「退出登录」「退出全屏」:
   first see what each gives today and keep exactly that), a bare 「关掉」 or 「退出」 (the model decides by what is
   playing), anything with more to it (「退出网易云然后打开B站」), and 「回到桌面」 alone (still `press_keys` home).
3. **Checks**: a new group `exits()` in `tests/agent/QuickChecks.java`, called from `run()`, at least 16 checks: every
   example above → exit; every 「must not change」 sentence → what it gives today; 「回到桌面」 → press_keys home.

## Keep
QuickCommand stays pure (no `android.*`). Nothing else in TvTools changes.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3386
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_app_exit.sh` → `APP EXIT OK`

## Files you own
`src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java`, `src/xyz/erapi0neer/xingyi/agent/QuickCommand.java`,
`tests/agent/QuickChecks.java`.
