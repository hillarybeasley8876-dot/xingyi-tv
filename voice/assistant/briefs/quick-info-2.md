# Brief: quick-info-2 — everyday data questions that still waited for a model round

## Why (2026-11-10 morning sweep on the TV, silent test source)
These went to the model (2–7 s, 1–3 rounds) though one data tool answers them whole, as the quick commands already do
for 「今天有什么热搜」「美股怎么样」「双色球开奖」「今天黄历」「今天有什么新番」:
「百度热搜第一是什么」 4.0 s · 「B站现在什么最火」 2.0 s · 「网易云热歌榜前三是什么」 2.2 s · 「美股昨晚怎么样」 2.6 s ·
「双色球最新开奖号码是多少」 2.3 s · 「今天宜搬家吗」 2.3 s · 「繁花在哪个平台能看」 4.5 s · 「庆余年第二季一共几集」 2.0 s ·
「今天有NBA比赛吗」 2.9 s · 「这周有什么新番」 7.1 s (3 rounds) · 「广州明天天气怎么样」 4.4 s.
And 「湖人昨天赢了吗」 is a quick command that drops the 昨天: it should ask for the last game.

## What to build (only `QuickCommand.java`; checks in `QuickChecks.java`)
Every new mapping below is exact (tool and args). Read how the existing quick commands for these tools are written
(`hot_list`, `stock_quote` MARKET, `lottery`, `almanac`, `movie_rating` MOVIE, `nba_games` NBA_TEAM / NBA_DAY,
`bili_bangumi`, `weather` DAY_FIRST / `here`) and extend them in the same style; `reply()` must return the tool's own
message for each of these tools (check it already does; add what is missing).
1. **hot_list**: 百度热搜第一是什么 / 热搜第一是什么 / 热搜第一 → `{"source":"百度","count":1,"final":true}`; 微博热搜 /
   微博热搜是什么 → `{"source":"百度","count":5,"final":true}` (微博 needs a login; the tool says it is Baidu's list);
   B站现在什么最火 / B站最火的视频是什么 / B站什么最火 → `{"source":"B站","count":5,"final":true}`; 网易云热歌榜前三是什么 /
   网易云热歌榜前三 → `{"source":"网易云热歌榜","count":3,"final":true}`, 网易云热歌榜 → count 5. 前N (一 to 十, 1 to 10) is the count.
2. **美股 / A股 with 昨晚 / 昨天**: 美股昨晚怎么样 / 昨晚美股怎么样 / 美股昨天怎么样 → `stock_quote {"query":"美股","final":true}`;
   A股昨天怎么样 → `{"query":"A股","final":true}`.
3. **lottery**: 双色球最新开奖号码是多少 / 双色球开奖号码是多少 → `{"game":"双色球","final":true}`; 最新一期大乐透开奖号码 →
   `{"game":"大乐透","final":true}`; 福彩3D开奖号码是多少 → `{"game":"福彩3D","final":true}`.
4. **almanac 宜不宜**: 今天宜搬家吗 → `{"date":"今天","thing":"搬家","final":true}`; 明天适合结婚吗 → `{"date":"明天","thing":"结婚","final":true}`;
   后天宜不宜出行 → `{"date":"后天","thing":"出行","final":true}`. Days: 今天 明天 后天 (none said → 今天). Things only from
   this list: 搬家 结婚 出行 开业 装修 理发 签约 提车 领证 订婚 动土 入宅 安床 出游 搬新家(→搬家).
5. **movie_rating where / episodes**: <title>在哪个平台能看 / 在哪看 / 在哪能看 / 哪里能看 / 在哪个平台 → `{"title":<title>,"where":true,"final":true}`;
   <title>一共几集 / 有几集 / 多少集 / 一共多少集 → the same. <title> 2–16 characters as in MOVIE (《》 taken off); not a
   pronoun or a reference (这个 那个 这部 那部 它 这 那 这剧 那剧 刚才那个), not starting with 我/你.
6. **NBA**: 今天有NBA比赛吗 / 今天有没有NBA比赛 / NBA今天有比赛吗 → `{"day":"今天","final":true}`, 明天有NBA比赛吗 →
   `{"day":"明天","final":true}`. A team with 昨天 / 上一场 / 赢了吗 / 输了吗 / 赢没赢 → `{"team":<team>,"last":true,"final":true}`
   (湖人昨天赢了吗, 尼克斯赢了吗, 湖人上一场比分); a team without them stays as today (湖人比分 → `{"team":"湖人","final":true}`).
7. **bili_bangumi 这周**: 这周有什么新番 / 最近有什么新番 / 这几天有什么番 / 这周更新什么番 → `{"day":"这周","final":true}`.
   今天有什么新番 stays `{"final":true}`.
8. **Weather somewhere else**: <place> 2–4 Chinese characters (not the TV's own, which stays as today without `place`)
   before or after the day: 广州明天天气怎么样 / 明天广州天气怎么样 / 明天广州天气 → `{"place":"广州","day":"明天","final":true}`;
   北京天气怎么样 → `{"place":"北京","final":true}`; 杭州今天下雨吗 / 杭州今天会下雨吗 → `{"place":"杭州","day":"今天","rain":true,"final":true}`.
   Never a place: 今天 明天 后天 大后天 今晚 明早 明晚 周末 这周末 这几天 现在 外面 那边 这边 这里 那里 家里 你那 我这
   我们这 本地 当地 电视, and nothing ending in 的. If the weather tool cannot find the place, the engine already hands the
   sentence to the model (a quick command whose tool fails falls back): no special case needed.

## Checks (`tests/agent/QuickChecks.java`, a new group `info2()` called from `run()`)
Every example above as a check with the exact args, plus: 热搜上那个第二条 → none; 打开B站最火的视频 → none (an action);
这个在哪看 → none; 它有几集 → none; 我在哪看 → none; 湖人比分 unchanged; 今天有什么新番 unchanged; 明天天气怎么样
unchanged (no place); 上海明天天气怎么样 unchanged (the TV's own place — `QuickCommand.here(...)` is set the way the
existing checks set it); 那边天气怎么样 → none; 外面天气怎么样 unchanged. At least 45 new checks. Every existing check keeps
passing unchanged.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Android: no `Pattern.UNICODE_CHARACTER_CLASS` / `(?U)`.
- The XiaoAi bridge compiles `QuickCommand` and its JVM tests pin which quick commands wait for XiaoAi
  (`Takeover.absoluteQuick`): reads like these do not wait. The acceptance script runs that suite against your tree.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 3862
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_quick_info2.sh` → `QUICK INFO2 OK`
