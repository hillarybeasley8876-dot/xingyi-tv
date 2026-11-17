# Brief: cards-data — the data tools hand the panel a card

## Why
A merged job (`cards-panel`) taught 星仪's floating panel to draw three kinds of answer card: `weather`, `stocks`,
`list` (see `src/xyz/erapi0neer/xingyi/agent/tv/HudCards.java` for exactly what it accepts). Nothing produces them yet.
This job makes the tools produce them and carries them to the panel: the user asks 「明天天气怎么样」 and sees the day's
temperatures as a line; 「茅台股价多少」 and sees the price with its arrow; 「B站有什么热门视频」 and sees the five videos.

## Read first
`tv/HudCards.java` and `Hud.State` (the card format: the source of truth), `Quotes.java` (`Quote`, `when`, `speak`),
`Weather.java` (`speak`, `pickDays`, `label`, `dayLine`, `hourly`, `text(code)`, `lookup`), the read-out lists that
`TvTools.rememberList` keeps — `News.readOut`, `HotList.readOutVideos/readOutTopics/readOutSongs`, `BiliVideos.readOut`,
`Bangumi.readOut`, `HistoryToday.readOut` — and `Movies` (`movie_list`'s rows), `Ledger.java`, `AgentLoop.java` (where a
tool's result comes back: search `results[i] =`), `tv/TvTools.java` (the tools `stock_quote`, `weather`, `news`,
`hot_list`, `bili_videos`, `bili_bangumi`, `history_today`, `movie_list`), `tv/AgentEngine.java` (`hud(...)` around line
1251 and the quick commands' `ran(...)` around line 943 and its callers), `tests/agent/fixtures/` (saved forecasts,
quotes and lists used by `WeatherChecks`, `FeedChecks`, `BiliChecks`, `MovieChecks`, `BangumiChecks`).

## What to build

### 1. `src/xyz/erapi0neer/xingyi/agent/Cards.java` (pure: java.util + org.json)
```java
public final class Cards {
    /** {type: stocks, rows: [{name, price, unit, pct, when}]} for at most 4 quotes (pct signed, as Quote.pct; when as
     * Quotes.when gives it for that quote: 「9 点 49 分」「今天收盘」「11 月 6 日收盘」「美东时间 11 月 5 日收盘」); null for none. */
    public static JSONObject stocks(List<Quotes.Quote> quotes, Calendar now)
    /** {type: list, title, items: [{title, meta}]} from a read-out list ({what, items: [{n, title, …}]}) or movie_list's
     * rows: at most 5 items; null when there are none. */
    public static JSONObject list(String title, JSONArray items)
    /** Whether the panel can draw it (HudCards' rules): a known type with its required fields. */
    public static boolean valid(JSONObject card)
}
```
`meta` of a list item, from the fields it has: `up` and `date` → 「影视飓风 · 昨天」; `artist` → 「林俊杰」; `episode` and
`out` → what Bangumi says about the episode (read its readOut); `summary` → its first 28 characters (+「…」 when cut);
movie_list rows → 「豆瓣 8.5 · 主演A、主演B」 (what the row has); nothing → no `meta`. Titles as they are.

### 2. `Weather.card`
```java
/** The panel's card for the day a forecast answer is about: {type: weather, place, day, sky, lo, hi, rain, hours}. */
public static JSONObject card(String place, JSONObject forecast, String day, long now) throws JSONException
```
- The day: the same choice as `speak` for that `day` text (empty = 今天); several days asked (「这几天」「周末」) → the first.
- `day` label: 「今天 11月7日 周六」, 「明天 11月8日 周日」 (`label` + date + weekday).
- `sky` = `text(weather_code)` of that day; `lo`/`hi` its min/max rounded; `rain` its `precipitation_probability_max`.
- `hours`: 8 points 3 hours apart from the hourly forecast — for today from the next whole hour (`now`), for another day
  0, 3, 6 … 21 — each `{h, t (one decimal), p}`; leave out hours the forecast does not have; fewer than 2 → no `hours`.

### 3. The tools attach their card — `tv/TvTools.java`
Add `"card"` to the result only when `Cards.valid(card)`:
- `stock_quote`: `Cards.stocks(quotes, now)`.
- `weather`: `Weather.card(...)` from the same (cached) forecast the answer used, for the plain forecast and the rain /
  dress answers; not for `sun` (sunrise) or when the call failed. Build the card from the forecast JSON already fetched;
  do not fetch twice (`Weather.cached` would hide it, but keep the code honest).
- every tool that calls `rememberList(t, list)`: `Cards.list(list.optString("what"), list.optJSONArray("items"))`;
  `movie_list`: `Cards.list(<its title>, rows)`.
Change nothing else in those tools.

### 4. The card travels — `Ledger`, `AgentLoop`, `AgentEngine`
- `Ledger`: `public volatile JSONObject card;` — not saved by `toJson`, not read back (`fromJson` leaves it null).
- `AgentLoop`: after each tool call, a result with a valid `card` sets `task.card` (the latest wins).
- `AgentEngine.hud(t, mode, reply, page)`: pass `t.card` as the State's card (the State ignores it in modes that do not
  show cards).
- Quick commands (`ran(...)` and the callers that set `task.reply` from it): the tool result's valid card goes on the
  quick command's ledger before its panel is shown.

## Checks — `tests/agent/CardChecks.java` (stub already called by `AgentCoreTest.main`)
At least 40, on saved data (fixtures), each label saying what it proves: stocks from parsed quotes (A share during the
day, a Hong Kong close, a US close, an index, more than 4 → 4, none → null); list from each read-out kind (news with a
long summary, B站 videos with up/date, songs with artist, topics without meta, bangumi, history_today, movie_list) and
the 5-item limit; weather cards for 今天 / 明天 / 周六 / 这几天 from a saved forecast (the hours, the day label, lo/hi
rounding, a day with fewer than 2 hours left → no hours); `valid` (each type with and without each required field,
unknown type, null); the ledger: a scripted loop where a fake data tool returns a card → `task.card` is it; a later card
replaces it; a result without a card leaves it; `toJson` does not contain it.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 2712
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_cards_data.sh` → `CARDS DATA OK`

## Files you own
`src/xyz/erapi0neer/xingyi/agent/Cards.java`, `src/xyz/erapi0neer/xingyi/agent/Weather.java` (the card only),
`src/xyz/erapi0neer/xingyi/agent/Ledger.java` (the field only), `src/xyz/erapi0neer/xingyi/agent/AgentLoop.java` (setting
the card only), `src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java` (attaching cards only),
`src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java` (passing cards only), `tests/agent/CardChecks.java`.
