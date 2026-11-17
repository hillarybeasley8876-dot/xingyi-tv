# Brief: home-b — the home screen typeset as variant B 「轴」 (the user chose it, 2026-11-08)

## Why
The user: 「美化下ui排版」「我指的是主页面ui」. The lead made two mock-ups over the live flow and the user chose **B 「轴」**:
`H:/tools/mitv/redesign/concepts/attractor/ui_home_v4.py` (variant `B`) is the reference — every number below comes
from it; look at `H:/tools/mitv/voice/assistant-out/tmp/lead/home/v4-B-3.png` if you can open images. What changes
against today (`HomeUi`): no key hints; a third line under the date (农历 and weather); what is playing at the top
right; the shelf index becomes a ruler; a smaller title with one line about the entry; the axis only as long as the
shelf; a soft dark floor under the bottom block.

## Read first
`src/xyz/erapi0neer/orrery/HomeUi.java` (all), `HomeActivity.java` (`Entry`, `items()`, `kinds()`, `fresh()`,
`pushToUi()`, `onResume()` / `onPause()`, the `watch` runnable, `main` Handler), `Shelves.java` (`kind`), the mock-up
script above, `tools/shelves_check.sh` and `tools/ShelvesCheck.java` (the PC-check pattern to copy), `AndroidManifest.xml`.

## 1. HomeUi (px on the 1920×1080 grid, as the file already scales)
- **Remove** the key hint (`HINT` and its drawing).
- **Third line** under the date: `infoLine` (set by `setInfo`), cjk regular 19 px, letter spacing 0.10 em, alpha 0.38,
  x = MX (ink-aligned like the date), baseline DATE_BASE + 38. Not drawn when empty.
- **Now playing** (top right) when `nowTitle` is not empty: the text `nowTitle + "　" + nowArtist` (artist may be empty)
  in cjk regular 19 px, letter spacing 0.06 em, alpha 0.66, right-aligned to W − MX on baseline DATE_BASE; 30 px to its
  left three static bars 2 px wide, heights 8, 14 and 10 px, 6 px apart, standing on the baseline, alpha 0.6; above,
  right-aligned, `NOW PLAYING` in the mono face 12 px, letter spacing 0.18 em, alpha 0.30, baseline DATE_BASE − 34.
- **The index as a ruler** (rows as today: the last on INDEX_BOTTOM, 46 px apart): a 1 px hairline at x = MX from the
  first row − 30 to INDEX_BOTTOM + 12, alpha 0.18; on each row a tick at y − 8: 8 × 1 px alpha 0.40; the open shelf's
  tick is 18 × 2 px in ACCENT and slides with `shelfPos` (it replaces today's mark); the shelf's number `01`…`05` in the
  mono face 13 px at x = MX + 30, alpha 0.28 → 0.80 on the open one; the name at x = MX + 64 (cjk regular 23 px, letter
  spacing 0.14 em), alpha 0.36 → 1.0; after the name, 10 px on, its count without a leading zero (`4`, `15`) in mono
  13 px, alpha 0.20 → 0.55. **Remove** the vertical rule at RULE_X.
- **Title** 60 px (cjk light, letter spacing 0.04 em), alpha 0.97, baseline 852; anchored on its column as today.
- **About line** (today's kind line): baseline 892, cjk regular 20 px, letter spacing 0.06 em, alpha 0.56, its ink
  starting 2 px right of the title's ink. Its text comes from `kinds` as now (HomeActivity puts the note in, below).
- **Axis**: IDX 14 px, LABEL 21 px. When the shelf has at most PER_PAGE entries: the hairline runs from SHELF_X to
  `colX(n − 1) + COL`, then a fade to nothing over `min(260, SHELF_R − end)` px (a horizontal LinearGradient, ink at
  alpha 0.16 → 0, built once per length), and there is no closing tick at SHELF_R. Longer shelves keep today's full
  width, closing tick and `05 / 14`.
- **Floor**: before anything else, a rect from y 690 to 1080 across the width with a vertical LinearGradient black
  alpha 0 → 0.5 (built once).
- `void setInfo(String line, String nowTitle, String nowArtist)`: stores them and redraws once if anything changed.

## 2. The data — `src/xyz/erapi0neer/orrery/HomeInfo.java` (new) and HomeActivity
- 星仪's bridge answers `getContentResolver().call(Uri.parse("content://xyz.erapi0neer.xiaoaibridge.agent"), "home", null,
  null)` with a Bundle whose `"json"` is `{"lunar":"农历九月三十","weather":"多云 13° – 19°","now":{"title":"江南","artist":
  "林俊杰","state":"playing"},"notes":{"com.netease.cloudmusic.tv":"上次在听《江南》林俊杰"}}` — every field optional; a
  missing provider, a SecurityException or bad JSON means "nothing to show", never a crash.
- `HomeInfo.fetch(Context)` runs on a background thread (one single-thread executor), posts the parsed result to the
  main thread. HomeActivity fetches on resume and every 60 s while resumed (its own Runnable; removed on pause).
- On a result: `ui.setInfo(line, nowTitle, nowArtist)` (line = lunar and weather joined by `　·　`, either alone, or "";
  now only when state is `playing`), and keep `notes`; `kinds()` appends `　·　<note>` to an entry's kind when there is a
  note for its package (`Entry.pkg`); when the notes changed, push the shelves again (`pushToUi()`), without moving focus.
- `AndroidManifest.xml`: add `<uses-permission android:name="xyz.erapi0neer.xingyi.permission.USE_LOCAL_MCP" />` (the
  bridge's signature permission; the same debug key signs both apps).

## 3. Pure helper and its PC check
`src/xyz/erapi0neer/orrery/HomeLayout.java` (no android imports): the joins and the geometry, used by HomeUi /
HomeActivity: `static String infoLine(String lunar, String weather)`, `static String nowLine(String title, String artist)`
(cut to 18 characters with 「…」), `static String about(String kind, String note)`, `static float axisEnd(int n, float
windowStart)`, `static float fadeLength(float end)`, `static float rulerTop(int shelves)`. `tools/HomeLayoutCheck.java` +
`tools/home_check.sh` (copy the shelves_check pattern): at least 20 checks, each printing `ok   …` or `FAIL …`, and a
last line `home checks: N ok, M failed`.

## Acceptance (run all of them yourself before you finish)
- `sh tools/accept_home_b.sh` → `HOME B OK` (builds the apk, runs both PC checks, checks the pieces above are there)

## Files you own
`src/xyz/erapi0neer/orrery/HomeUi.java`, `src/xyz/erapi0neer/orrery/HomeActivity.java`,
`src/xyz/erapi0neer/orrery/HomeInfo.java`, `src/xyz/erapi0neer/orrery/HomeLayout.java`, `tools/HomeLayoutCheck.java`,
`tools/home_check.sh`, `AndroidManifest.xml` (the one uses-permission line).
