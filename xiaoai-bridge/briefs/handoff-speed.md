# Brief: faster 星仪 handoff, and give the screen back afterwards

## Why
When a request is handed to 星仪 (Xingyi, another app, package `xyz.erapi0neer.xingyi` — never edit it), `Takeover.handOff`
brings Xingyi to the front (its voice entry only accepts while Xingyi has window focus) and then:
- sleeps 450 ms before the first submit and retries every 450 ms while the reply code is 3 ("not in front yet");
- polls the task status every 500 ms.
Measured on the TV: the handoff was accepted only 1.5 s after XiaoAi's request. And the screen stays on Xingyi after a pure
question ("让星仪看看现在音量是多少"), so whatever the user was watching (a NetEase song, HDMI) is covered.

## What to build
1. **`src/xyz/erapi0neer/xiaoaibridge/Foreground.java`** (new, pure Java, no android.*): the resumed activity from
   `dumpsys activity activities` text.
   `static Foreground parse(String dump)` with public final fields `pkg`, `activity`, `taskId` (int). Accept lines like
   `  ResumedActivity: ActivityRecord{81d39a6 u0 com.netease.cloudmusic.tv/.activity.newplayer.v2.PlayerActivityV2 t111}` and
   `    mResumedActivity: ActivityRecord{363cc3c u0 xyz.erapi0neer.orrery/.HomeActivity t66}`.
   Prefer the LAST top-level `ResumedActivity:` line; if there is none use the last `mResumedActivity:` line; return null when neither
   exists or the line is malformed. Expected for `tests/fixtures/dumpsys-activities.txt`: pkg `com.netease.cloudmusic.tv`,
   activity `.activity.newplayer.v2.PlayerActivityV2`, taskId 111.
2. **Faster submit** (`Takeover.handOff` and/or `Harness`): submit right after `bringForward` without the fixed 450 ms sleep; while the code
   is 3, retry every 120 ms for up to 4500 ms total. Status polling in `follow` every 250 ms instead of 500 ms. Keep every other behaviour
   (auto-confirm of low-risk previews, spoken confirmation, the "正在处理" cue after 5 s, not repeating Xingyi's own answer).
3. **Give the screen back** (`Takeover` + a small Android helper, e.g. in `Harness`):
   - Before `bringForward`, read the foreground: run `dumpsys activity activities` (the app holds DUMP and usage-stats access; read
     stdout with a size cap and a 3 s timeout, never on the logd reader thread) and keep `Foreground prev`.
   - When the task finished with code 3 (done) or failed: wait until the answer has been spoken (if the bridge speaks it) or 2500 ms after
     done (if Xingyi spoke it itself), then read the foreground again. Only if the CURRENT resumed package is still `xyz.erapi0neer.xingyi`
     (so the task did not open another app) and `prev` is not null and `prev.pkg` is not Xingyi:
     - if `prev.pkg` is the launcher `xyz.erapi0neer.orrery` or any HOME activity, send the user home (`Intent.ACTION_MAIN` +
       `Intent.CATEGORY_HOME`, `FLAG_ACTIVITY_NEW_TASK`);
     - else `ActivityManager.moveTaskToFront(prev.taskId, 0)`; if that throws (SecurityException etc.), start
       `getPackageManager().getLaunchIntentForPackage(prev.pkg)` with `FLAG_ACTIVITY_NEW_TASK`; if that is null, do nothing.
   - Do NOT give the screen back while a spoken confirmation is pending (the preview must stay visible), and do not do it for the song path.
   - Log one line `  还原画面 → <pkg>` (or why not) via the existing log.
   - Add `<uses-permission android:name="android.permission.REORDER_TASKS"/>` to `AndroidManifest.xml` (normal permission).
4. **Test**: `tests/ForegroundTest.java` — the fixture plus >= 6 synthetic cases (only mResumedActivity, both kinds, no match, malformed,
   multi-digit task ids, package with digits). Summary label `(foreground)`.

## Do NOT change
`answerWithModel` and the song path in `Takeover` (beyond what the handoff needs), `BridgeService.java`, `Llm.java`, the song
modules, existing tests. The lead will later change one line in `answerWithModel`; keep that method's structure.
