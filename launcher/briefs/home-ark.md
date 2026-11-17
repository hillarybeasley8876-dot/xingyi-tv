# Brief: home-ark — the home screen as a line-drawn terminal at the bottom centre (the user's choice)

## Why
The user (2026-11-08): 「你看看人家明日方舟那种 ui」, then 「但是还是按照线条和简约排版为主」, picked the tilted panel cluster,
and placed it 「下方居中」. The reference is the lead's mock-up `H:/tools/mitv/redesign/concepts/attractor/ui_home_ark.py`,
layout **`bottom`** (HTML + CSS over a still of the flow; the CSS is the spec: open the file and read the numbers);
its picture is `H:/tools/mitv/voice/assistant-out/tmp/lead/home/ark-bottom-0.png`. Everything is a 1 px line, text and
at most a 6 % veil: no filled blocks. The particle flow (`FlowRenderer`) is untouched.

## Read first
`src/xyz/erapi0neer/orrery/HomeUi.java`, `HomeActivity.java` (D-pad handling, `items()`, `kinds()`, `pushToUi()`,
`HomeInfo` use), `HomeLayout.java`, `Shelves.java`, `tools/HomeLayoutCheck.java`, the mock-up script (layout `bottom`:
`BOTTOM`, `BAND`, the `.cell …` CSS rules, `.status`, `.bars`, `.serial`, `.label`, `.frame`, `.veil`, `.hint`).

## What changes on screen (px on the 1920 × 1080 grid; numbers from the mock-up)
- **Keeps**: the clock, the date and the info line under it (as today), the shelf ruler at the bottom left (as today).
- **Removes**: the axis, the big title above it and its about line, the NOW PLAYING block at the top right (it moves
  into the status strip below).
- **The band**: the open shelf's entries in slots on a 6 × 2 grid of cells 186 × 138 px with 12 px gaps (1176 × 288),
  left 372, top 686. Slots, in order: (col 0, row 0, 2 × 2), (2, 0, 2 × 1), (4, 0), (5, 0), (2, 1), (3, 1), (4, 1), (5, 1);
  eight entries a page; more entries go on further pages with the same slots. The band leans back: drawn through an
  `android.graphics.Camera` `rotateX(16)` about its bottom centre, with the camera far enough that its top edge is about
  3.5 % narrower than its bottom edge (the mock-up's `perspective(1600px)`; set `Camera.setLocation(0, 0, −1600 / 72)`
  and check the numbers on the PC: see the checks).
- **A cell**: 1 px outline ink α .20, fill white α .015. Inside, from its corner: the code `01`… (JetBrains Mono 11 px,
  letter spacing .12 em, α .42) at 14, 12 from the top left; a ghost numeral of the same number (Inter weight 100,
  118 px, α .05; 260 px in the 2 × 2 cell) right-aligned 10 px in from the right edge, its baseline 22 px below the
  bottom edge (clipped to the cell); the name (MiSans Medium 26 px, α .62; 2 × 2: MiSans Semibold 62 px, letter spacing
  .10 em; 2 × 1: 30 px) with its baseline 58 px above the bottom (2 × 2: 92); the English line (Oswald weight 500, 11 px,
  letter spacing .26 em, α .38; 2 × 2: 14 px, .34 em) baseline 38 px above the bottom (2 × 2: 64); the state line (MiSans
  Regular 14 px, α .42; 2 × 2: 18 px α .60) baseline 14 px above the bottom (2 × 2: 26), one line, cut with 「…」 to the
  cell's width − 32. Text starts 16 px in from the left.
- **The focused cell**: outline α .95, fill α .06, its name α 1, English α .70, state α .78, code α .90, and four
  corner marks in the accent (12 × 12, 2 px, on the outline). When the focus moves, the outline and corners glide
  from cell to cell (160 ms, ease out).
- **Around the band**: four L-shaped frame corners 18 × 18, 1 px, α .5, 22 px outside the band; above it the label
  `TERMINAL` (Oswald 600, 12 px, .32 em, α .5) at the band's left, 54 px above its top, and 18 px after it the shelf and
  count `常用 · 08` (JetBrains Mono 12 px, α .4); with more than one page, `01 / 02` right-aligned above the band's right
  edge in the same mono.
- **Veil** under it all: from y 560 to 1080 a vertical gradient black α 0 → .35 (at 55 %) → .70.
- **Status strip** (top right, replacing NOW PLAYING): columns right-aligned to W − 96, each a label (Oswald 600 11 px,
  .28 em, α .38, baseline 134) over a value (MiSans Regular 20 px, .04 em, α .82, baseline 164), 28 px padding, 1 px
  hairlines α .18 between columns: `WEATHER` the weather, `NOW PLAYING` title and artist (only while playing), `XINGYI`
  星仪's state word (`HomeInfo` field `agent`: 待命 / 执行中 / 等你确认 / 等你回答). A missing value drops its column.
- **Under the date**: a barcode strip 220 × 14 px at y 286 (bars of 1–2 px, gaps 2–5 px, from a fixed pattern), α .22,
  and 12 px right of it `ORRERY · MITV-ASTP0 · 04` (JetBrains Mono 11 px, .20 em, α .30).
- **Footer**: `ORRERY 4 · XINGYI TERMINAL` (Oswald 500 11 px, .30 em, α .26) right-aligned to W − 96, baseline 1020.

## Words in the cells
`HomeLayout.english(String label, String pkg)`: 星仪电视助手 → `XINGYI · ASSISTANT`, 网易云音乐 → `NETEASE · MUSIC`,
云视听极光 → `TENCENT · VIDEO`, 哔哩哔哩 → `BILIBILI · VIDEO`, HDMI n → `SOURCE · HDMI n`, 无线投屏 → `CAST`, 应用商店 →
`APP STORE`, 设置 → `SETTINGS`, 电视管家 → `MANAGER`, 相册 → `GALLERY`, TV Bro → `TV BRO · BROWSER`, U盘 / 媒体 → `MEDIA`,
夸克网盘 → `QUARK · CLOUD`, 全部应用 → `ALL APPS`; anything else from its kind (视频 → `VIDEO`, 音乐 → `MUSIC`, 游戏 →
`GAME`, …) or `APP`. The name in a cell is the label; 星仪电视助手 shows as `星仪`. The state line is the entry's note
when there is one (上次在听《…》, 新安装), else its kind; 星仪's is `<agent word>　·　叫一声「小爱同学」` (待命 when unknown).

## Keys
On the band: LEFT / RIGHT / UP / DOWN go to the nearest slot whose centre lies that way (ties: the smaller sideways
offset); RIGHT at the right edge turns to the next page, LEFT at the left edge to the previous one or, on the first page,
to the shelf ruler; on the ruler UP / DOWN open the shelf above / below (the band fades over as shelves do today) and
RIGHT goes back to the band's first slot. OK launches the focused entry as today. The pure part of this (the slots,
pages, the neighbour in a direction, the English lines) lives in `HomeLayout` and is checked on the PC.

## Keep
`HomeUi.setShelves(...)`, `setInfo(...)`, `tick()` and the constructor `HomeUi(Context, Typeface inter, Typeface mono,
Typeface cjkLight, Typeface cjk)` keep their signatures (the lead's off-screen preview calls them); add
`void setAgent(String word)`. HomeUi loads the new faces itself from the Context: Oswald from
`assets/fonts/Oswald-Variable.ttf` (weights through `Typeface.Builder.setFontVariationSettings`), MiSans Medium /
Semibold from `/system/fonts/MiSans-Medium.otf` and `MiSans-Semibold.otf` (fallback: the cjk face passed in).

## Checks — `tools/HomeLayoutCheck.java` (add to it; `sh tools/home_check.sh`)
At least 30 new ones: the slot rectangles; page count and the entry on each slot for 4, 8, 9 and 15 entries; the
neighbour in each direction from every slot of a full page (including the 2 × 2 one's edges and the page turns); the
English lines (each listed label, a kind, an unknown app); the state line choice; the camera check — with the same
3-D projection math (a pure method `float[] project(float x, float y, float tiltDeg, float camDistance, …)` used by
nothing on Android but mirroring Camera's), the band's top edge is 3–4 % narrower than its bottom edge.

## Acceptance (run all of them yourself before you finish)
- `sh tools/accept_home_ark.sh` → `HOME ARK OK` (builds the apk, runs both PC checks, checks the pieces are there)

## Files you own
`src/xyz/erapi0neer/orrery/HomeUi.java`, `src/xyz/erapi0neer/orrery/HomeActivity.java`, `src/xyz/erapi0neer/orrery/HomeLayout.java`,
`src/xyz/erapi0neer/orrery/HomeInfo.java`, `tools/HomeLayoutCheck.java`.
