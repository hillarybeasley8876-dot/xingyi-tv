# Brief: panel-stopped — a stopped panel does not show a step as still running; an answer is never cut to a caption it is not

## Why (review of 2026-11-09, both confirmed)
1. `Hud.java` ~line 711 (and `current()` ~593): when a task has stopped (已停下 / failed / cancelled), `current()` still
   returns the step that was in progress, and it is drawn with the running marker, as if the task were still working.
2. `Hud.java` ~line 404: when the task produced a card (weather, quotes, list), the reply is drawn as a 2-line caption
   under it (`cards == null ? body : caption`, max 2 lines), on the idea that the words repeat the card. A card made by an
   earlier step stays on the ledger for the whole task, so when the answer is about something else, or longer, the real
   answer is cut to two lines on the panel.

## What to build
1. Put the two decisions in a small pure class `src/xyz/erapi0neer/xingyi/agent/PanelRules.java` (no Android imports):
   - `stepDrawnRunning(boolean live, String stepState)`: a step is drawn running only while the panel is live; on a
     stopped, failed or finished panel the in-progress step is drawn as stopped (use the existing non-running marker
     style you find in Hud for unfinished steps).
   - `replyIsCaption(String reply, ...card facts you need)`: the reply is a caption only when it is short (fits 2 caption
     lines; use a character budget you justify from the caption width) or it names what the card shows (its title or
     main value). Otherwise the reply is drawn as the body (up to 10 lines, as without a card) and the card is drawn
     above or left out — your choice, say which in a comment.
   Use them in `Hud.java`.
2. Checks in `tests/agent/CardChecks.java` (already run by the core checks): at least 6 cases covering both rules,
   including a long answer with an unrelated weather card (not a caption) and 「北京今天晴，最高 15 度」 with a weather card
   for 北京 (a caption).

## Rules
- Only the files you own. Do not open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse requests).
- No TV or adb. Android: software-rendered overlay, keep drawing on the main thread as it is.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3484
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `grep -c "PanelRules\." src/xyz/erapi0neer/xingyi/agent/tv/Hud.java` → at least 2
