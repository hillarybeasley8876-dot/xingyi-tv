# Brief: quick-info-3 — weather in another city on any day, day words never in the place, and two sentences that read oddly

## Why
- The lead changes an old rule: **another city's weather no longer goes to the model.** 「广州明天天气怎么样」 took 4.4 s
  through the model (one round + the same `weather` call). Job quick-info-2 made 「北京天气怎么样」 (today) quick but had to
  leave every other day to the model, because four existing checks pin 「北京明天天气怎么样」「北京明天会下雨吗」
  「明天北京天气怎么样」「北京今天几度」 as the model's (`tests/agent/ContextChecks.java` lines 81, 99, 100) and
  `tests/agent/DispatchChecks.java` line 152 has 「明天上海天气怎么样」 waiting for a running task. **You may change exactly
  those check lines** as described below; every other existing check stays as it is.
- 「北京周六天气怎么样」 is matched today with the place 「北京周六」: day words must never end up in the place.
- On the TV after the last install: 「百度热搜第一是什么」 → 「百度热搜前 1 条：第一，全国陨石猎人扎堆赶赴石家庄」, and
  「湖人昨天赢了吗」 → 「湖人上一场 今天 124 比 114 赢了老鹰；…」. Both read oddly aloud.

## What to build
1. **`QuickCommand`, weather somewhere else on any day**: <place> + <day> or <day> + <place>, then 天气 / 天气怎么样 /
   天气如何 / 的天气 / 会下雨吗 / 下雨吗 / 要带伞吗 / 几度 / 多少度 / 冷吗 / 冷不冷 → `weather` with `place`, `day` (when said; none
   = today, as today) and `final: true`; the rain wordings add `rain: true`; 几度 / 多少度 / 冷 add nothing else. Days: 今天
   明天 后天 大后天 今晚 明早 明晚 周末 这周末 这几天 周一…周日 / 周天 / 星期一…星期日 / 星期天 / 礼拜一…礼拜天 / 这周X / 下周X
   (pass the word as said, e.g. 周六, 下周一; the tool knows them). The TV's own place (`here`) keeps its existing
   behaviour (no `place`). A <place> is 2–4 Chinese characters that is not a day word, does not end in a day word, is
   not in the existing never-a-place list (今天 明天 … 那边 这边 这里 那里 家里 你那 我这 我们这 本地 当地 电视) and does
   not end in 的. 「明天天气适合出去玩吗」「明天适合晒被子吗」 stay the model's.
2. **The pinned checks** (only these lines): ContextChecks 81 → 「北京明天天气怎么样」 is `weather {"place":"北京","day":"明天","final":true}`
   (the other two in that line stay null); 99 → 「北京明天会下雨吗」 is `{"place":"北京","day":"明天","rain":true,"final":true}`;
   100 → 「明天北京天气怎么样」 is `{"place":"北京","day":"明天","final":true}` and 「北京今天几度」 is
   `{"place":"北京","day":"今天","final":true}`. DispatchChecks 152: keep 「明天上海天气怎么样」 covered with what
   `Dispatch.queueable` now returns for it (a quick read is answered beside the running task) and add a model question
   that still waits (「明天上海的天气适合出去玩吗」 → waits). Write in your status file what each changed line was and is.
3. **`HotList`, one item**: with count 1 the sentence is 「百度热搜第一是「…」」, 「B站热门第一是《…》，<UP主>，<N> 万播放」,
   「网易云热歌榜第一是《…》，<歌手>」 (same facts as today's first item, no 「前 1 条：第一，」).
4. **`Sports.speakTeam` with last**: 「湖人上一场（今天上午）124 比 114 赢了老鹰；…」 — the day in brackets right after
   上一场: 今天上午 / 今天下午 / 昨天上午 / 昨天晚上 … for today and yesterday (Beijing time, the part of the day from the
   game's start), otherwise 「11 月 8 日」; 「输给了」 for a loss; no stray spaces between the words.

## Checks
- `tests/agent/QuickChecks.java`, a new group `weatherThere()`: 广州明天天气怎么样, 明天广州天气怎么样, 明天广州天气 →
  `{"place":"广州","day":"明天","final":true}`; 北京周六天气怎么样 → `{"place":"北京","day":"周六","final":true}`; 杭州下周一天气
  怎么样 → `{"place":"杭州","day":"下周一","final":true}`; 广州今晚会下雨吗 → `{"place":"广州","day":"今晚","rain":true,"final":true}`;
  成都后天冷不冷 → `{"place":"成都","day":"后天","final":true}`; 上海明天天气怎么样 (here set) → no place, day 明天;
  那边明天天气怎么样 → none; 明天天气适合出去玩吗 → none; 周六天气怎么样 → unchanged (own place, day 周六). At least 14.
- `tests/agent/FeedChecks.java`: one-item hot lists for the three sources; the last-game sentence for today morning,
  yesterday evening, an older date, a loss. At least 8.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Android: no `Pattern.UNICODE_CHARACTER_CLASS` / `(?U)`.
- The bridge's JVM tests pin which quick commands wait for XiaoAi; the acceptance script runs them against your tree.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4002
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_quick_info3.sh` → `QUICK INFO3 OK`
