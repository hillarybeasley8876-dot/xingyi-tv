# Brief: phone-console-2 — the phone page fits the phone; the home screen learns what 星仪 is doing

## What the lead saw (round 1 rendered at 390 × 844 in Chrome against a fake 星仪)
1. **The page is wider than the phone.** The request bubble does not wrap and is cut at the right edge; the send button
   of the input bar is off the screen; on the pairing screen the sentence 「手机和电视连同一个 Wi-Fi。先在电视上显示配对码…」
   runs past the right edge and the whole block sits right of centre. Something is wider than the viewport.
2. The header has no connection dot (the brief asked for one: connected / reconnecting).

## What to do
1. `assets/agent/phone.html`: nothing may be wider than the viewport at 360, 390 and 430 px: `html, body { max-width: 100%;
   overflow-x: hidden }`, every flex child that holds text gets `min-width: 0`, bubbles `max-width: 85%;
   overflow-wrap: anywhere`, the input bar is `display: flex` with the textarea `flex: 1; min-width: 0` and the send button
   `flex: none`, the chips row scrolls sideways on its own (`overflow-x: auto`, no scrollbar shown) without widening the
   page, the pairing block is centred with `max-width: 320px; margin: 0 auto; padding: 0 24px`. Add the header dot
   (6 px; white when the last request succeeded within 10 s, dim and pulsing when not).
2. `tv/AgentEngine.homeInfo()`: add `"agent"`: 「待命」 when idle, 「执行中」 while a task runs, 「等你确认」 / 「等你回答」 while it
   waits on the user (from the same fields `state()` reports). Recomputed on every call (do not keep it in the 30 s cache).
3. Add a lead-usable check page: `assets/agent/phone-check.html` is NOT wanted; instead put three widths into a comment at
   the top of phone.html saying they were checked, and make sure the page has `<meta name="viewport"
   content="width=device-width, initial-scale=1, viewport-fit=cover">`.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3370
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_phone_console.sh` → `PHONE CONSOLE OK`
The lead renders the page again at three widths and looks for anything cut off.

## Files you own
`assets/agent/phone.html`, `src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java` (homeInfo's `agent` only).
