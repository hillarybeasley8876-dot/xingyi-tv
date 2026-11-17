# Brief: cards-panel-2 — design notes on the answer cards (round 2)

## Why
Your round-1 cards (`tv/HudCards.java`, merged) were rendered on the TV and looked at. They are good; three notes:

1. **Weather, rain bars**: at 5–20 % the bars are 1–4 units tall and read as stray grey squares between the line and the
   hour labels. Draw a bar only when `p` ≥ 30; draw none at all when no hour reaches 30. When drawn: 3 units wide
   (not 5), alpha 46, still from the bottom of the band, before the line; and put the hour's `p` as `30%` in 9 units
   alpha 90 just under the hour label of the hours that have a bar (the strip grows by that line only when there is
   at least one bar).
2. **The words under a card repeat the card.** With a card, draw the reply quieter: 13 units (not 15), alpha 175 (not 240),
   at most 2 lines (not 3), and 8 units (not 14) between the card and the separator line above the reply. The reply is
   still what XiaoAi reads; on screen it is a caption now.
3. **Weather, the big temperature**: `14–18°` in light 38 units is right; the en dash is too long next to the digits —
   draw it as `14°` + a thin 10-unit line (1.2 units, alpha 200, vertically centred on the digits' x-height, 6 units of
   space each side) + `18°`. Nothing else in the weather card changes.

## Read first
`src/xyz/erapi0neer/xingyi/agent/tv/HudCards.java`, `tv/Hud.java` (`Panel.set`, `onMeasure`, `onDraw`: how the reply
is laid out and drawn), `tv/AgentDebug.java` (the samples: keep them; change the weather sample's hours so that 15 h and
18 h have `p` 40 and 55 and the others stay under 30, to show the bars; add a sample `card-weather-dry` with every `p`
under 30).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 2598
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_cards_panel.sh` → `CARDS PANEL OK`
The lead renders the samples again and looks at them.

## Files you own
`src/xyz/erapi0neer/xingyi/agent/tv/Hud.java`, `src/xyz/erapi0neer/xingyi/agent/tv/HudCards.java`,
`src/xyz/erapi0neer/xingyi/agent/tv/AgentDebug.java`.
