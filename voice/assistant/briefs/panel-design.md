# Brief: panel-design — 星仪's panel, typeset with care (the user: 「美化下ui排版」)

## Why
The user (2026-11-08 00:2x), after watching 星仪's panel for five minutes while it installed a browser: 「美化下ui排版」.
Their taste, said over many days: 黑白、细光线、空间感、细腻而深邃、有设计感; not 「HUD 小字堆满」. The TV's desktop (Orrery 3)
speaks that language: a thin large clock, a fine-line axis numbered 01–13, an orbiting particle field. The panel today is
a generic dark card: every text nearly the same white, generic dots and circles for steps, tight lines, a single
character wrapped onto its own line (「…并播放他最新的视／频」), the consent prompt's last sentence repeated on screen.
This job re-typesets the panel. The cards (`HudCards`) stay as they are; only their inner width changes slightly.

## Read first
`src/xyz/erapi0neer/xingyi/agent/tv/Hud.java` (all of it), `tv/HudCards.java` (how a card is laid out and drawn),
`tv/AgentDebug.java` (`renderSamples`). Sizes are units × `d` (1.5 px per unit on the 1920-wide TV). Software rendering
on a weak CPU: build Paints, Typefaces, Paths, StaticLayouts and gradients in `Panel()` / `set()`, never in `onDraw`;
keep the partial invalidation of moving parts (no whole-panel redraw per frame).

## The design (everything white at an alpha; no colour)
**Box** — width 384 u, inner padding 28 u (inner width 328 u), corner radius 22 u. Fill: a vertical gradient from
argb(242, 17, 18, 22) at the top to argb(246, 7, 8, 10) at the bottom. Hairline border 0.75 u alpha 22. On the top edge,
from the left corner's end to the right corner's start, a 1 u light line whose alpha goes 0 → 70 → 0 (a horizontal
gradient built once): light catching the edge.

**Header** — top padding 22 u, height 18 u.
- The mark: a ring of radius 4.5 u (stroke 1 u, alpha 190) with a dot (radius 1.6 u, alpha 255) on the ring — the orrery.
  running: the dot orbits once per 2.4 s (redraw only the mark's rect, every 80 ms as today); confirm / ask: the dot sits
  at the top and pulses (alpha 120↔255, 1.4 s); done / reading / hint: the dot sits at the top, still; failed / stopped:
  the ring only, no dot, ring alpha 140.
- 10 u right of the mark: 「星仪」 in medium 12.5 u, letter spacing 0.32, alpha 215 (no space typed between the two characters).
- Right aligned: the status word (执行中 / 等你确认 / 等你回答 / 朗读 / 完成 / 已停下 / 没做成 / 提示) in 11.5 u alpha 140;
  the page (「1/…」) after it, 8 u apart, alpha 105. The old status dot goes (the mark carries the state).
- 18 u below the header: the content.

**Thread** (`before`, the exchanges above this one) — no 「你：」「星仪：」 prefixes. A 1 u vertical hairline (alpha 40) at the
inner left edge spans the thread block; text indented 12 u from it. What the user said: 12.5 u alpha 120, one line, in
“ ” quotes; what 星仪 answered: 12.5 u alpha 168, two lines at most, 4 u under it; 12 u between exchanges. After the
thread a hairline separator (alpha 22) with 14 u above and 16 u below.

**Goal** — 18 u medium alpha 245, line spacing multiplier 1.25, two lines at most, wrapped *balanced* (below).

**Steps** — 14 u under the goal. Each step is a row: a 26 u marker column, then the text (14 u, line spacing 1.25, two
lines at most, balanced). A 1 u vertical hairline (alpha 30) runs down the centre of the marker column from the first
marker to the last (the axis), drawn behind the markers. Markers, centred on the first text line:
- done: a fine check (a 2-segment polyline, 1.3 u stroke, round caps, 7 u wide, alpha 165), text alpha 125;
- current (running, the in-progress or first pending step): its number 「02」 in light 12 u tabular digits alpha 245 on a
  small disc of the box colour (so the axis does not run through it), and a 1 u arc (radius 8 u, 110°) orbiting the
  number every 1.1 s (the spinner, kept from today), text alpha 245;
- pending: the number in light 12 u alpha 80, text alpha 100;
- failed: a fine × (two 1.2 u strokes, 6 u) alpha 200, text alpha 200; skipped: a 6 u dash alpha 110, text alpha 90.
12 u between rows.

**Action line** (running, no reply yet) — replaced by the thinking chain below.

**Thinking chain** (the user, 2026-11-08: 「思维链什么的也要优化」 — the panel showed the plan and one line of the current
action, nothing of what 星仪 had done, seen or was trying). `Hud.State` gets `public final List<String[]> trace`
(`{what, status}`, oldest first, at most 4; status `live` for the one running now, else `ok` / `failed` / `denied`; empty
list when none) and a constructor with it after `card`; `AgentEngine.hud(...)` fills it from the task's ledger: the last
three entries of `t.actions` (`what`; `ok` → ok, a `denied` / `rejected` status → denied, else failed) plus, while
running, `lastAction` as the live head when it is not already the last action's `what`. Drawing (running, confirm, ask,
and the finished modes when there is no card): a block of chain rows, each a 14 u marker column then the text 12.5 u,
one line, ellipsized:
- `live`: a 1 u arc (radius 3.5 u, 120°) turning once per 0.9 s (partial invalidation of its rect), text alpha 225;
- `ok`: a 3 u dot alpha 120, text alpha 140 for the newest, 110 for older ones;
- `failed` / `denied`: a 5 u × (1 u strokes) alpha 160, text alpha 150.
Rows 7 u apart; a 1 u hairline (alpha 30) joins the markers. Where it goes: with steps, indented under the **current
step** (its marker column starts at the step text's left edge: the chain belongs to that step); without steps, 14 u under
the goal. Finished modes keep at most the last two rows (the answer is what matters then); a card hides the chain.

**Card** — as today (`HudCards.of(card, d, inner)` with the new inner width), 16 u under the goal or the steps.

**Reply** — a hairline separator (alpha 22) with 16 u above it (10 u when a card is above) and 14 u below; the reply in
15.5 u regular alpha 232, line spacing multiplier 1.38, at most 10 lines (with a card: 13 u alpha 175, 2 lines, as round 2
of the cards set), balanced.
- confirm mode: when the reply ends with the spoken instruction (`可以就说“好的”，不做就说“算了”。` or the same with
  「」 quotes), the panel leaves that sentence out of the text and draws two quiet chips under it instead, 14 u below: a
  rounded rectangle (radius 12 u, height 24 u, 1 u stroke alpha 110, horizontal padding 12 u) with 「说“好的”」 (12.5 u
  alpha 220), 10 u gap, a second with 「说“算了”」 (alpha 150). XiaoAi still reads the whole text: only the panel changes.

**Footer** — 16 u under the last block. 「回答时先叫一声「小爱同学」」 (ask mode): a small microphone drawn with fine lines
(a 5 u × 8 u rounded capsule, a 7 u arc under it and a 2 u stem; 1 u stroke alpha 140) and, 8 u later, the text 12 u
alpha 140. 「接下来：…」: the word 「接下来」 12 u alpha 95, 8 u gap, the question 12 u alpha 155, one line, ellipsized.

**Hint** — the lines in 14.5 u alpha 222, line spacing 1.3, 12 u apart; the first line alpha 240.

**Bottom shimmer** (running) — as today but 1 u thick and its peak alpha 150 (not 200). 26 u bottom padding (not 18).

## Balanced wrapping (the 「视／频」 problem)
`static StaticLayout balanced(CharSequence text, TextPaint paint, int width, int maxLines, float spacingMult)`: lay out at
`width` with `setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)`; when it takes 2+ lines (and fits within maxLines)
and its last line has fewer than 4 characters (counting a CJK character, a digit run or a Latin word as one... keep it
simple: `lineEnd - lineStart` < 4 after trimming), try narrower widths in steps of 8 u, down to 70 % of `width`, and
take the first width at which the line count stays the same and the last line has ≥ 4 characters; otherwise keep the
first layout. Ellipsize only at maxLines. Use it for the goal, step texts, the reply and the thread answers.

## Samples (`AgentDebug.renderSamples`)
Keep every sample (give the running ones a realistic trace: the B站 sample `{"搜索B站：星辰亻旅行者","ok"},
{"读屏","ok"}, {"点选「视频」标签","live"}`; the queued web-search sample `{"联网搜索：OpenAI 最近的新动向","live"}`). Add:
`running-long` (a goal that wraps: 「在B站搜索“星辰亻旅行者”并播放他最新的视频」, four steps with one
done, one current, two pending, one of them long enough to wrap, and a trace of three under the current step), `failed`
(a goal, three steps: done, failed, skipped; trace `{"打开播放页","ok"}, {"等待开播","failed"}`; reply
「没能播出来：B站一直停在开屏广告，你可以说“星仪继续”。」), `chain-noplan` (running, goal 「“打开浏览器看看”」, no steps,
trace `{"从 GitHub 官方发布页下载 TV Bro","ok"}, {"已下载 60%（90 / 150 MiB）","live"}`), `confirm-chips` (the existing
confirm sample — its text ends with the spoken instruction, so the chips show), `install-consent` (mode confirm, goal
「“帮我装个Via浏览器”」, no steps, trace `{"找「Via浏览器」的官方安装包","ok"}, {"从 res.viayoo.com 下载并核对Via浏览器","ok"}`,
reply `安装「Via」（mark.via 6.5.0，3 MB）：从 res.viayoo.com 下载，开发者签名：Shanghai Zhiwei Network Technology Co., Ltd.（Via）。可以就说“好的”，不做就说“算了”。`).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3155
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_panel_design.sh` → `PANEL DESIGN OK`
The lead renders every sample on the TV and judges it; expect a second round with notes.

## Files you own
`src/xyz/erapi0neer/xingyi/agent/tv/Hud.java`, `src/xyz/erapi0neer/xingyi/agent/tv/AgentDebug.java`,
`src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java` (only `hud(...)`: filling `trace`).
