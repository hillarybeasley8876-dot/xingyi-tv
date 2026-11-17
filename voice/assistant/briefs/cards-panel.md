# Brief: cards-panel — answer cards on 星仪's floating panel (weather, stocks, a list)

## Why
星仪's panel (top right of the TV, `agent/tv/Hud.java`) shows answers as plain text. Mainstream TV assistants (Gemini on
Google TV, Alexa+ on Fire TV) show a data answer as a card: the weather with its temperatures through the day, a quote
with its arrow, a list of videos. The user looks at this panel every time they ask 星仪 something; the user cares a lot
about design (black and white, fine lines, quiet, "细腻而深邃"). This job draws three kinds of card. Another job makes
the tools produce them; the lead wires the two together. You only draw.

## Read first
`src/xyz/erapi0neer/xingyi/agent/tv/Hud.java` (all of it: `State`, `stay`, `render`, `unit`, `Panel.set`, `onMeasure`,
`onDraw`, the paints), `src/xyz/erapi0neer/xingyi/agent/tv/AgentDebug.java` (`renderSamples`: off-screen sample images).
The panel is 380 units wide (`width = 380 * d`; `d = unit(c)` = 1.5 px per unit on the 1920-wide TV), 24 units of inner
margin, dark glass, white text at several alphas, software rendering (no hardware layers, no shaders except the
existing shimmer). Everything you add follows the same rules: sizes in units × `d`, colours white with an alpha only.

## The card (JSON, `org.json.JSONObject`; another job produces exactly this)
```json
{"type": "weather", "place": "上海徐汇", "day": "明天 11月8日 周日", "sky": "阴", "lo": 14, "hi": 18, "rain": 20,
 "hours": [{"h": 0, "t": 15.1, "p": 5}, {"h": 3, "t": 14.6, "p": 5}, {"h": 6, "t": 14.2, "p": 10}, {"h": 9, "t": 15.8, "p": 15},
           {"h": 12, "t": 17.6, "p": 20}, {"h": 15, "t": 17.9, "p": 20}, {"h": 18, "t": 16.4, "p": 10}, {"h": 21, "t": 15.5, "p": 5}]}
{"type": "stocks", "rows": [{"name": "智谱", "price": "426.20", "unit": "港元", "pct": "-10.46", "when": "11 月 6 日收盘"},
                            {"name": "MINIMAX", "price": "155.80", "unit": "港元", "pct": "-8.46", "when": "11 月 6 日收盘"}]}
{"type": "list", "title": "B站热门", "items": [{"title": "【何同学】我们做了一个…", "meta": "老师好我叫何同学 · 312万播放"}, …]}
```
- `hours`: 2 to 8 points in time order (`h` 0–23, `t` °C, `p` rain chance 0–100, `p` may be missing = 0); may be missing.
- `rain` may be missing; `lo`/`hi` are numbers (round them when drawn); `pct` is a signed string ("0.00" = flat).
- `rows`: 1 to 4 drawn (more are ignored). `items`: 1 to 5 drawn; `meta` may be missing.
- Anything malformed (unknown type, missing required field, wrong types, empty rows/items) → no card at all, never an
  exception. The panel must look exactly as today when there is no card.

## What to build

### 1. `Hud.State` carries a card
Add `public final JSONObject card;` (null = none) and a constructor with every existing parameter plus `JSONObject card`
last; the existing constructors pass null. Nothing else in `State` changes.

### 2. `src/xyz/erapi0neer/xingyi/agent/tv/HudCards.java` (new; the drawing lives here, not in Hud.java)
```java
final class HudCards {
    /** Lays the card out for the inner width (px); null when the card is absent or malformed. */
    static HudCards of(JSONObject card, float d, int innerWidth)
    /** Height in px. */
    float height()
    /** Draws with its top-left at (x, y). */
    void draw(Canvas c, float x, float y)
}
```
Precompute everything in `of` (StaticLayouts, measured strings, the polyline points); `draw` only draws. Typefaces:
`Typeface.create("sans-serif-light", Typeface.NORMAL)` for big numbers, `"sans-serif"` regular, `"sans-serif-medium"`.
All text white; alphas as below. No colours, no images, no gradients, no animation.

**weather** (about 132 units tall):
- line 1: `place · day` ("上海徐汇 · 明天 11月8日 周日"), 12 units, alpha 140, one line, ellipsized.
- under it, 8 units down: the temperatures `14–18°` (en dash, `lo`–`hi`, rounded) in light 38 units, alpha 245. On the same
  band, right-aligned to the inner width: `sky` 15 units alpha 230, and under it `降水 20%` 12 units alpha 150 (left out
  when `rain` is missing).
- the hours strip, 12 units under the temperatures, 58 units tall in total, only when there are ≥ 2 hours: points evenly
  spaced across the inner width (first and last 8 units in from the edges); the temperatures mapped into a 22-unit band
  (min at the bottom, max at the top; all equal → the middle); a polyline 1.3 units wide, alpha 200, round joins and caps;
  a dot of radius 1.8 units alpha 235 at each point; above each dot its temperature rounded with `°` (10 units alpha 150,
  centred); under the band the hour as `6时` (10 units alpha 110, centred). Rain: for each point with `p` > 0 a bar 5 units
  wide, centred on the point, from the bottom of the band up `p`/100 × 22 units, alpha 38, drawn before the line.
**stocks** (52 units per row, 1–4 rows, a separator line alpha 26 between rows):
- left: `name` 15 units medium alpha 240, baseline 20 units from the row top; under it, baseline 41: a small filled
  triangle (6 units wide, 5 tall; pointing up when pct > 0, down when < 0; a 6-unit horizontal dash when 0), then 6 units
  later the absolute pct with `%` (12.5 units alpha 200: "10.46%").
- right-aligned: `price` light 24 units alpha 250, baseline 24, followed by a space and `unit` 11 units alpha 140 (the
  unit's right edge on the inner width); under it, right-aligned, `when` 11 units alpha 110, baseline 41.
- a name longer than the room left by the price is ellipsized.
**list** (title, then 1–5 items):
- `title` 11.5 units, letter spacing 0.08, alpha 130; 8 units under it the items.
- each item: the number `01`…`05` in light 13 units alpha 120 in a 28-unit column; next to it `title` 14 units alpha 235,
  one line, ellipsized; `meta` under the title (11.5 units alpha 120, one line) when present; 8 units between items.

### 3. `Hud.Panel` shows it
- `set(State)`: `cards = HudCards.of(s.card, d, inner)` — only for the finished modes (`done`, `stopped`, `failed`) and
  `reading`; other modes ignore the card.
- With a card the reply is at most 3 lines (instead of 10): the card already says the numbers.
- Order in the panel: header, the thread (`before`), the goal, the steps, **the card**, the reply, the footer. Between the
  goal (or the thread / header when there is no goal) and the card 6 units; between the card and the reply's separator
  line 10 units. `onMeasure` and `onDraw` agree on every height (read the existing code: each block adds the same amount
  in both).
- `stay(State)`: a finished state with a card stays 4 s longer than without.

### 4. Samples in `AgentDebug.renderSamples`
Add three samples (files `hud-card-weather.png`, `hud-card-stocks.png`, `hud-card-list.png`), mode `done`, with the cards
above and these goals / replies:
- weather: goal `“明天天气怎么样”`, reply `上海徐汇明天（11 月 8 日 星期日）阴，14 到 18 度，降水概率 20%，东风 3 级`.
- stocks: goal `“查询质朴和MINIMAX股价并且进行比较”`, reply `「质朴」按读音查的是智谱：智谱 426.20 港元，跌 10.46%；MINIMAX 155.80 港元，跌 8.46%（11 月 6 日收盘）`.
- list: goal `“B站有什么热门视频”`, title `B站热门`, five items (make them realistic B站 titles, some long enough to be cut,
  metas like `UP主名 · 312万播放`), reply `B站热门：第一，…；第二，…` (shortened).
Keep the existing samples as they are.

## Common mistakes to avoid
- Measuring in `onMeasure` with one set of gaps and drawing with another: the card overlaps the reply or leaves a hole.
- Creating Paints, Typefaces, StaticLayouts or Paths inside `draw` / `onDraw` (software rendering on a weak CPU).
- `JSONObject.getString` on an optional field (throws) — use `opt*` and check; Android's org.json returns the string
  "null" for JSON null from `optString`: treat "" and "null" as missing.
- Drawing outside the inner width (a long name, a 5-digit price): ellipsize or shrink, never overflow the panel.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 2534 (you add no core checks; nothing in the
  core may break)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_cards_panel.sh` → `CARDS PANEL OK` (builds the TV app with your sources, runs the bridge's JVM
  suite, checks the State constructor, HudCards' methods, the three types, the three sample files)
The lead renders the samples on the TV and judges them; expect a second round with design notes.

## Files you own
`src/xyz/erapi0neer/xingyi/agent/tv/Hud.java`, `src/xyz/erapi0neer/xingyi/agent/tv/HudCards.java`,
`src/xyz/erapi0neer/xingyi/agent/tv/AgentDebug.java`.
