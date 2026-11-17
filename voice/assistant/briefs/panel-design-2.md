# Brief: panel-design-2 — round 2 of the panel (overlaps fixed by one layout pass; the mark; the current number)

## What the lead saw (round 1 rendered on the TV, `AgentDebug` samples)
1. **Text overlaps** (the serious one). In `running-long` the chain rows under step 02 are drawn on top of steps 03 and 04;
   in `failed` the chain under the failed step sits on top of step 03 「核对正在播放的标题」. `onMeasure` and `onDraw` do
   not agree on where the chain under a step ends: the next step is placed as if the chain were not there.
2. **The mark** reads as a power button: a ring with the dot at 12 o'clock, drawn so the ring looks broken there.
3. **The current step's number** sits on a disc inside its spinner arc; the arc touches the digits, the disc looks heavy.

## What to do
1. **One layout pass.** In `Panel.set(...)` compute, once, the list of blocks with their y (header, thread, goal, each step
   row, the chain rows under the current step, the card, the reply, the chips, the footer) into fields; `onMeasure` takes
   the total height from it and `onDraw` draws at those y — neither adds gaps on its own any more. Keep the animation
   rects (the mark, the current step's arc, the live chain marker, the shimmer) from the same positions.
2. **The mark**: ring radius 5 u, stroke 0.9 u, alpha 150; the dot radius 2.2 u, alpha 255, sitting *on* the ring at
   45° up-right (not at the top); running: the dot orbits from there; failed / stopped: the ring alone at alpha 110.
3. **The current step**: no disc. Its number 「02」 plain in light 12 u tabular digits alpha 245; the spinner arc at radius
   9.5 u around the number's centre, 1 u, 110°. The axis hairline stops 11 u above and resumes 11 u below that centre.
4. Add a check you can run: a method `static String overlaps(Panel p)` is not possible (Android), so instead render every
   sample in `renderSamples` into its PNG **and** report in the returned string, per sample, `max_y` of the last block and
   the measured height; the lead reads that line. (No other output format changes.)

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3155
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_panel_design.sh` → `PANEL DESIGN OK`
The lead renders the samples again and looks for any glyph on top of another.

## Files you own
`src/xyz/erapi0neer/xingyi/agent/tv/Hud.java`, `src/xyz/erapi0neer/xingyi/agent/tv/AgentDebug.java`.
