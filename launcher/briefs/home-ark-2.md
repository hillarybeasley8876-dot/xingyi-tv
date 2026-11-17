# Brief: home-ark-2 — 常用 fills the band; the weather is never the 农历

## What the lead saw (round 1 rendered off-screen with the bridge's real data)
1. **常用 has 4 entries** (星仪, 网易云, 极光, B站): the band's lower row is empty, the frame looks like something is
   missing. The user's mock-up had eight: 星仪, 网易云音乐, 云视听极光, 哔哩哔哩, 信号源, 无线投屏, 全部应用, 设置.
2. `HomeLayout.weatherOf(line)` returned the 农历 when the line had no weather in it (「农历九月三十」 under WEATHER). The
   TV's own path passes the weather on its own and was right; the 3-argument `setInfo` was wrong.

## What to do
1. `Shelves.build`: 常用 is always eight when there are that many things to put there: the daily ones that are installed
   (星仪, 网易云音乐, 云视听极光, 哔哩哔哩), then what is opened most (as today, at most 2 more), then, filling up to 8 in
   this order, entries that are not apps: **信号源** (opens the 信号源 shelf, focus on its first entry), **无线投屏** (the
   existing cast entry), **全部应用** (opens the 全部 shelf), **设置** (the existing settings entry). A shelf-opening entry
   is a new kind of `Shelves.Item` (an id like `shelf:信号源`); `HomeActivity` handles OK on it by switching shelves the
   way UP/DOWN on the ruler does, the focus landing on the new shelf's first slot. Its state line: 信号源 →
   `HDMI 1　·　2　·　3` (from the 信号源 shelf's entries), 全部应用 → `<n> 个` plus `　·　<k> 个新装` when there are new installs.
   English lines: `SOURCE · HDMI`, `ALL APPS` (already in `english`).
2. `HomeLayout.weatherOf(line)`: only a part that carries a temperature (`°`) is weather; otherwise "".
3. Checks (add to `tools/ShelvesCheck.java` and `tools/HomeLayoutCheck.java`): 常用 with 4 / 6 / 0 daily apps installed
   gets 8 / 8 / its fill; the fill order; the shelf entries' ids; weatherOf with and without weather, with a minus sign.

## Acceptance (run all of them yourself before you finish)
- `sh tools/accept_home_ark.sh` → `HOME ARK OK`

## Files you own
`src/xyz/erapi0neer/orrery/Shelves.java`, `src/xyz/erapi0neer/orrery/HomeActivity.java`, `src/xyz/erapi0neer/orrery/HomeLayout.java`,
`tools/ShelvesCheck.java`, `tools/HomeLayoutCheck.java`.
