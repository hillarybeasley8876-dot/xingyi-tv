# Orrery 3 — Xiaomi TV home screen

Package `xyz.erapi0neer.orrery` (the TV's default HOME). Build: `sh build.sh` (aapt2 → javac → d8 → apksigner, no Gradle),
install: `adb install -r H:/tools/mitv/launcher/build/orrery.apk` (Windows path: `MSYS_NO_PATHCONV=1` breaks `/h/...`).
Orrery 2.0 (Codex's Saturn build) is the first commit and `orrery-2.0.apk`.

## What it is
- `FlowRenderer` — a live particle fluid on the Chen attractor, on the TV's GPU every frame: compute shader advances
  393 216 particles and splats them into R32UI images (sharp full-res + soft quarter-res, stochastic depth of field),
  `soft.frag` filters the soft layer and carries the grade gain, `final.frag` tone-maps through a table. Trails are an
  exponential decay of the accumulation (stochastic rounding). Locked to 30 fps on vsync (`look.json` `fps`).
- Depth and detail (`look.json`; without a key the look is exactly what it was before the key existed):
  `echo` splats the first `share` of the (shuffled) particles a second time into the soft layer through a copy of the
  attractor `scale`d and `turn`ed (pitch, yaw, roll in the camera's rest frame) and centred `at` [frame x, frame y
  from the bottom-left, distance]: a dim, soft, cold far plane behind the gesture. `twinkle` [sharpness, rate, focal]
  turns the `glint` sine into short flashes kept to the focal band. `tone` [toe, shoulder order, mid contrast, pivot]
  shapes the table: a finite shoulder order keeps gradation in the core, the contrast is an S around the pivot.
- `HomeUi` — the type layer (clock, date, focused title, numbered axis) on the 1920×1080 grid of
  the 1920×1080 grid the design mock-ups use; redraws only while something moves. The design brief and the mock-ups are
  in the private development tree (`redesign/`), not in this repository.
- `HomeActivity` — entries (星仪, 网易云, 云视听极光, other apps, HDMI 1-3, the TV's tools, 应用商店, U盘, 设置),
  D-pad, launch, and render only while resumed with the screen on.
- `Shelves` (2026-11-06, the user: 「ui还是太乱了 没有分类 收纳 太简约了有点」「安装的应用也不知道在哪找」) — the entries
  sorted onto shelves: 常用 (星仪, 网易云, 极光, B站, then what is opened from the home 3+ times, at most 6), 影音
  (known TV builds, the store category, words like 视频/音乐), 游戏 (only when one is installed), 信号源 (HDMI 1-3 and
  投屏), 工具 (everything else, settings last), 全部 (everything, the last three days' installs first, marked 新).
  UP/DOWN pick a shelf (the index on the left, with counts), LEFT/RIGHT walk it; each shelf remembers its focus.
  Under the focused name, what it is (语音助手, 视频 · 新安装…). A shelf longer than 7 slides, with 「05 / 14」.
  Mock-up in the private tree (`redesign/concepts/attractor/ui_categories.py`); PC check: `sh tools/shelves_check.sh`.
  adb: `FOCUS --ei shelf K --ei index N`.

- **veil** (2026-11-06, the user: 「层次感空间感有待加强」): like the echo, a share of the particles splats a second time,
  through a small turned copy of the attractor close to the lens (`at` [0.06, 0.2, 0.7]), wide and out of focus: a
  faint smoke on the left, between the clock and the shelf index, that the gesture is seen through and that moves
  most as the camera sways. `look.json` `veil` (share 0.025, energy 0.2, blur 30, scale 0.55, turn [-20, 30, 70]);
  off without the key. On the TV: 30.0 fps, no slow frames. Tried and dropped: a far dust slab (one-pixel points
  are drawn as short streaks by the trails as the camera turns, and its code alone cost ~3 slow frames per 5 s).

- **spray** (2026-11-06, 「目前只有主体 缺点缀」): a share of the particles peels off the sheet: over its own cycle
  each drifts along a screen-space wind (up and a little left, into the dark) with its own spread, swaying side to
  side twice, and fades; the trails draw curling threads, the gesture's upper edge dissolving into smoke. `look.json`
  `spray` (share 0.028, reach 0.26, spread 0.5, period 11, energy 0.8, wind [-0.25, 1.0]). The first try pushed them
  out from the attractor's centre: they slid along the sheet and nothing showed; straight drifts drew hair-like lines.
  With the veil and the spray the TV ran 29.0–29.2 fps (4–6 slow frames per 5 s) at 393 216 particles: `count` is
  now 368 640 (the energy is normalised by the count), 30.0 fps, no slow frames.

- **sky** (2026-11-06, 「要有银河粒子那种深邃感觉 拉丝感觉」, 「目前没很好的融合在一起」): `stars.comp`, its own
  dispatch after the particles (so the particle pass keeps its registers): a disc of stars around the luminous core
  in the flow's 3-D space, through the flow's camera, leaning back from the view (`incline` 70°, turned `tilt` 10.7°
  along the gesture): the far side recedes small and dim, the near side blurs, all of it sways with the camera. It
  turns (`turn` 2°/s); every frame each star lays one random point of its arc (5–16°, brightest at the head) into the
  soft layer — a long-exposure trail, whole at once — and the brighter ones in focus their head into the sharp layer.
  `look.json` `sky` (6000 stars, inner 0.03, outer 1.2, energy 1.5). First tried as screen-space trails about a pole
  off the frame: near-straight parallel streaks, rain; then about the core in screen space: a vortex, but pasted on.
  With the sky, `count` is 352 256 (30.0 fps with the screen-space version; 8000 stars at 368 640 ran 28.5).

- **removed** (2026-11-06 morning, the user: 「删了星轨和蒸汽吧」): the `sky` (星轨, the star-trail disc) and `spray`
  (蒸汽: the curling threads rising off the gesture's upper edge) keys are out of `look.json`; their code stays and
  does nothing without the keys (the stars pass is not dispatched, the spray share is 0). `count` is back to 393 216,
  the budget the two had taken (with the veil alone the TV ran 30.0 fps at that count). The `veil` stays.

- **B 「轴」** (2026-11-08, the user: 「美化下ui排版」「我指的是主页面ui」; chosen from two mock-ups in the private tree
  (`redesign/concepts/attractor/ui_home_v4.py`)): no key hints; under the date a quiet line 农历 · weather; at the top right what is playing
  (NOW PLAYING, only while playing); the shelf index is a ruler (hairline, ticks, 01–05, counts), the open shelf's tick
  in the accent; the focused name 60 px over its own column with one line about it (kind, 新安装, 上次在听《…》); a
  shelf's axis ends after its last entry and fades out (long shelves keep the full width and 05 / 15); a soft floor
  gradient under the bottom block. The data comes from 星仪's bridge: `HomeInfo` calls `content://xyz.erapi0neer.
  xiaoaibridge.agent` method `home` (signature permission USE_LOCAL_MCP; on resume and every 60 s) →
  `{lunar, weather, now, notes}`; nothing shows when the bridge is missing. PC check: `sh tools/home_check.sh`.
  Off-screen check without touching the installed home: `sh tools/homepreview/preview.sh [tree] [out] [art]` (a tiny
  app renders the type layer with that tree's code and the bridge's real data; the PC lays it over a still of the flow).

- **Orrery 4 「终端」** (2026-11-08, the user: 「你看看人家明日方舟那种 ui」「但是还是按照线条和简约排版为主」, then the
  tilted cluster placed 「下方居中」; mock-up `redesign/concepts/attractor/ui_home_ark.py` layout `bottom` (private tree; the card it replaced is
  rendered by `briefs/ui_home_ark.py`); jobs
  home-ark and home-ark-2; installed 12:02): the open shelf is a line-drawn band at the bottom centre — a 6 × 2 grid
  of 186 × 138 cells (the first entry 2 × 2), leaning back 16° through `android.graphics.Camera`, eight entries a page
  with a code, a ghost numeral, the name, an English line (`HomeLayout.english`) and a state line in each cell; the
  focus outline and accent corners glide between cells. Above the band `TERMINAL 常用 · 08`; at the top right a status
  strip WEATHER / NOW PLAYING / XINGYI (星仪's word from the bridge's `agent` field: 待命 / 执行中 / 等你确认 / 等你回答);
  under the date a barcode and `ORRERY · MITV-ASTP0 · 04`; the footer `ORRERY 4 · XINGYI TERMINAL`. Oswald (OFL) is in
  `assets/fonts`. 常用 always fills to eight: the daily apps, what is opened most, then 信号源 (opens that shelf,
  `HDMI 1　·　2　·　3`), 无线投屏, 全部应用 (opens 全部, `15 个　·　1 个新装`), 设置 — `Shelves.fill`, the openers are
  `shelf:<name>` items. Keys: the four directions go to the nearest cell that way; LEFT at the band's left edge goes to
  the shelf ruler, where UP / DOWN switch shelves and RIGHT comes back. The bridge answers the weather from its last
  line of the day when the 10-minute forecast has run out (before that the WEATHER column went away for a minute in
  every eleven).

## Look development on the PC
`lookdev/flowpc.py` runs the same shader files with moderngl and mirrors the Java host logic line for line, so a PC
still is what the TV draws: `python lookdev/flowpc.py still out.png --fps 30 --frames 110 [--set key=json]`.
`python lookdev/flowpc.py gen` re-bakes `assets/flow/chen.bin` (particles laid on the attractor) and its stats.
Change Java and Python together.

## On the TV without rebuilding
Files pushed to `/sdcard/Android/data/xyz.erapi0neer.orrery/files/flow/` override the assets; broadcast
`xyz.erapi0neer.orrery.RELOAD` to rebuild. `lookdev/tvbench.py '[{"count": 262144}]' [--shader final.frag=path]`
pushes variants and prints the renderer's stats line (`files/flow-stats.txt`, every 5 s). `look.json`
`"profile": true` adds per-pass times (glFinish after each pass: inflated), `"skip": "compute,soft,final"` drops passes
for measurement. Other adb-only broadcasts (need DUMP): `SNAPSHOT --es name x.png` (GL frame + type layer into
`files/`), `FOCUS --ei index N`, `UI --ez show false`.

## Budget (Mali-G31 MP2 at 800 MHz, measured 2026-11-05)
About 6 G ALU ops/s: at 1080p every ALU op in a full-screen pass costs ~0.3 ms, so full-screen shaders only fetch
(noise and tone curve are tables drawn once by `tables.frag`) and empty pixels stop early. Compute costs ~5 ms per
131 072 particles. Pipelined frame time: 524 288 → 34 ms, 393 216 → 29 ms, 262 144 → 24 ms. 60 fps is out of reach
with this technique; the UI layer still animates at 60. Both layers are composed by the GPU (HWC "Client").
The echo at `share` 0.125 adds 49 152 short splats (≈ 95 ALU ops + one atomic each, against ≈ 260 for a particle):
estimated 0.7–0.9 ms, not yet measured on the TV. `twinkle` and `tone` cost nothing measurable (the glint branch is
taken by 1–2 % of warps; the tone curve is a table).
Before pressing keys on the TV, check the foreground is this app (`dumpsys activity activities | grep mResumed`).
