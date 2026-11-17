# Brief: loop-fixes — a long download is not 「太复杂」; 「关闭屏幕」 is answered as what happened; one installer search per app

## Why (the TV's own log, 2026-11-08 01:45–02:44)
1. 「打开浏览器看看」: `install_app` (TV Bro, 150 MB from GitHub) took **312 s**. The task's budget is 150 s
   (`AgentLoop.Limits.millis`, `deadline` set once at the start, checked before each round), so right after the install
   came back the loop stopped with 「这件事比预想的复杂，我先停下了…说“星仪继续”可以接着做」 — the browser was installed and
   one `open_app` away. The user had to say 「星仪继续」 and wait 6 more rounds (22 s).
2. 「关闭屏幕」 → the quick command answered 「电视已经关了」 (`QuickCommand` line ~576, for `screen_off` when the result's
   message is 「屏幕已经关了」); the user corrected it: 「我说的是关闭屏幕不是关闭电视」 (and on 11-06: 「关闭屏幕但是不要关机」).
   On this TV turning the screen off *is* standby: the TV stays on, XiaoAi still listens, music can play.
3. `find_installer` is often called twice in one task (the model plans, then searches again): ~10 s each.

## What to build
1. **Budget**: the 150 s is for thinking and ordinary steps. Time spent inside a single tool call that ran longer than
   20 s does not count against `deadline` (push the deadline back by that call's duration beyond 20 s). Rounds and call
   counts are unchanged. Checks with the fake clock the existing round/limit checks use (grep `limits.millis` and
   `roundLimit` in tests/agent): a 300 s install followed by an `open_app` round completes; three 60 s ordinary rounds
   still stop at the budget; a 25 s tool pushes the deadline by 5 s.
2. **Screen off said truly**: the reply for `screen_off` (quick command and the tool's own message) becomes
   「屏幕关了，电视还在待机，叫一声小爱同学就能用」 when it turned the screen off, and 「屏幕本来就是关着的，电视在待机」 when it was
   already off — never 「电视已经关了」. Check both quick replies and the tool messages (`TvTools` ~1217/1229).
3. **One installer search per app per task**: `find_installer` remembers its result per app name (trimmed, lower case)
   for 10 minutes in `TvTools`; a second call for the same app returns the remembered result with `"cached": true` and
   no network call. Put the cache in a small pure class (`InstallerCache`: put/get with a clock) and check it on the PC
   (expiry at 10 minutes, case and spaces ignored, other apps not served).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3167
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_loop_fixes.sh` → `LOOP FIXES OK`
- `sh ../../xiaoai-bridge/tests/run_all.sh` → no FAIL (QuickCommand changes are checked there too: DialogTest)

## Files you own
`src/xyz/erapi0neer/xingyi/agent/AgentLoop.java` (the deadline only), `src/xyz/erapi0neer/xingyi/agent/QuickCommand.java`
(the screen_off reply only), `src/xyz/erapi0neer/xingyi/agent/InstallerCache.java`,
`src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java` (screen_off messages and the find_installer cache only),
`tests/agent/LoopFixChecks.java`.
