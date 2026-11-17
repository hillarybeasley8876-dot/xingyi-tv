# Brief: netease-karaoke — 伴奏/原唱 in one step: a `netease_karaoke` tool, a quick command, and the right reading of the label

## Why (the user's worst real experience, and today's test on the TV)
- 2026-11-08 14:24–14:31 the user asked five times to switch NetEase between 伴奏 and 原唱; 星仪 took 47–113 s each time,
  7–16 rounds, pressing keys and looking at screenshots, and the user ended up writing 「我给你高亮了你看自己看这个图标你这都
  不会吗」 (perf.jsonl). The app skill 「网易云随心唱」 written afterwards still takes 7–9 rounds in calibration.
- 2026-11-09 17:25 on the TV, 「把这首歌切成伴奏模式」 took 51.5 s and answered 「已经是伴奏模式了」 — wrong: the vision model
  read the label 「开启随心唱」 (a button that *turns it on*) as the state *on*.
- What the TV really does (17:25–17:31, screenshots in assistant-out/tmp/lead/karaoke2, Eyes dumps saved as
  `tests/agent/fixtures/netease-karaoke-off.txt` and `-on.txt`; the off dump is the on one with the label put back, the
  label itself was seen on screen):
  1. On NetEase's player page (`PlayerActivityV2`) DOWN (KEYCODE_DPAD_DOWN, 20) shows the bottom control bar with the
     focus on 暂停/播放 in the middle. RIGHT ×4 (22) moves the focus to 「播放列表」 at the right end; UP (19) then puts it
     on the 随心唱 mic icon above the progress bar, right side (Eyes: `图标 焦点 @94,73`).
  2. With the icon focused Eyes also lists the text beside it (`文本「…」 @84,73`): **「开启随心唱」 = it is OFF**;
     **「按 上 键调节人声」 = it is ON** (the icon also gets an ON badge and a vocal-level slider appears above it).
  3. CENTER (23) on the focused icon toggles it at once (the label flips within about a second).
  4. The control bar hides itself after about 2–3 s without keys. With the bar hidden, CENTER **pauses the song** and UP
     **opens a full-screen remote tutorial** — so a sequence must never pause between keys, and a CENTER must follow
     within about a second of the last key that kept the bar up. (A 30 s pause before CENTER paused the song today.)

## What to build
1. **`src/xyz/erapi0neer/xingyi/agent/Karaoke.java`** (pure, PC-checked): `Boolean state(String screenDigestText)` →
   TRUE when the text beside the focused icon says 调节人声, FALSE when it says 开启随心唱, null otherwise (no focused
   icon in the right-hand area, other text, not the player page); plus whatever small pure helpers the tool needs.
2. **Tool `netease_karaoke {on: boolean}`** in `TvTools`: only when NetEase's player page is in front (else
   `fail("rejected", …)` telling the model to open the playing song's page first). Send DOWN, RIGHT×4, UP as one burst
   (~0.3 s apart), read the screen once through Eyes (`read()`), get the state with `Karaoke.state`. Already as asked →
   answer that. Otherwise send DOWN, RIGHT×4, UP, CENTER again as one burst (the bar may have hidden while reading), wait
   ~1.2 s, read again and report the new state — 「已切到伴奏：随心唱开着，按上键能调人声大小」 / 「已切回原唱：随心唱关了」.
   If a read gives null, return `fail("unconfirmed", …)` with what was on screen; never send UP or CENTER without the
   DOWN that opens the bar right before it. A gate in `gate()` like the other actions (`Gate.act()`).
3. **Already done by the lead (do not change)**: `assets/agent/contract.json` has the tool `netease_karaoke {on: boolean}`, its
   gate and the rewritten app skill 「网易云随心唱」; `tests/agent/calibrate.mjs` simulates the tool and has it in DIRECT,
   ANSWERS and EVIDENT (banzou and yuanchang now finish in one round, answered from the tool's message);
   `tests/agent/GateChecks.java` counts 23 gated tools. Mirror the engine side in `AgentLoop`: add the tool to
   `DIRECT` (no plan needed), `ANSWERS` and `EVIDENT` (its message is the answer when it succeeded), exactly as the
   calibration script now has it. Regenerate PROMPTS.md with `python tests/agent/prompts_doc.py`.
4. **Quick commands** (`QuickCommand`): 「切成伴奏」「切到伴奏模式」「伴奏模式」「打开随心唱」「开启随心唱」 → `netease_karaoke {on:true}`;
   「切回原唱」「原唱模式」「关掉随心唱」「关闭随心唱」 → `{on:false}`; the reply is the tool's own message (as `media_control
   exit` does). Nothing else changes.
5. **Calibration**: nothing to change; scenarios banzou and yuanchang must keep passing (acceptance runs them).
6. **Checks**: `Karaoke.state` on both fixtures and on unrelated screens (null), in `tests/agent/SkillChecks.java`; the
   quick phrases in `tests/agent/QuickChecks.java` (also that 「伴奏」 inside a song request like 「放演员的伴奏」 is not taken);
   at least 12 new checks in all.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse
  requests); do not open `src/xyz/erapi0neer/xingyi/LocalAdb.java` — send keys the way TvTools already does.
- No TV, no adb. Android: no `Pattern.UNICODE_CHARACTER_CLASS`; JSON strings read with `isNull` first.
- Run the calibration once at the end only (it calls the model gateway); one process.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → its last line `agent core: N passed, 0 failed` with N ≥ 3624
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `python tests/agent/prompts_doc.py --check` → `PROMPTS.md is up to date`
- `H:/tools/node/node-v24.22.2-win-x64/node.exe tests/agent/calibrate.mjs banzou yuanchang` → `EXPECT OK` for both, no
  `EXPECT FAIL`, no `ERROR`
