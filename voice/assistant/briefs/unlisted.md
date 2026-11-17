# Brief: unlisted — 「华为股价」 is answered 「华为没有上市」, not with 华微电子's price

## Why (review of 2026-11-09, confirmed twice)
「华为股价多少」 is a quick command: `stock_quote` runs without the model and its result is read out as the answer.
`Quotes.pick` finds no 华为 (it is not listed), so `TvTools` (stock_quote, ~line 1475–1490) asks the search again by sound
(`Pinyin.spell` → "huawei") and `Quotes.bySound` takes 华微电子, whose sound starts with huawei. The TV says
「「华为」按读音查的是华微电子 …元」. HARNESS.md (11-06) meant: a well-known company that is not listed gets a plain answer.
The by-sound retry itself is right and must stay: 「质朴」→ 智谱 (hk02513), 「毛台」→ 贵州茅台 are real mishearings.

## What to build
1. `Quotes.unlisted(String name)`: a small table of well-known companies whose main entity is not listed, with the names
   people use: 华为 (华为技术, 华为公司), 海思, 荣耀, 字节跳动 (字节, 抖音, 今日头条), 大疆, 蚂蚁集团 (蚂蚁金服, 支付宝),
   OPPO, vivo, 小红书, 米哈游, 娃哈哈, 老干妈, 国家电网, 中国烟草. Returns one sentence to say (「华为没有上市，没有股价」)
   or null. A name heard by sound counts too: 「华维」「花为」 sound like 华为 (use `Pinyin.sameSound`/`Pinyin.spell`,
   tones ignored, the whole name, not a part of it). Listed companies are never in it (小米、腾讯、美团、比亚迪、宁德时代…).
2. In `TvTools` stock_quote: before the search for a part, `Quotes.unlisted(part)` answers it: the result is
   `ok("observed", sentence)` with `"final": true` (the way the other data tools mark a final answer) and no price lookup
   for that part. Several parts in one request (「华为和小米的股价」): the unlisted sentence plus the others' quotes.
   Check how `QuickCommand` turns a stock_quote result into the spoken reply and make sure the sentence is what is said.
3. Checks in `tests/agent/PinyinChecks.java` (it is already run by the core checks): unlisted for 华为, 华为技术, 华维, 抖音,
   字节跳动, 大疆; null for 小米, 腾讯, 贵州茅台, 智谱, 毛台 and an empty string; and the existing by-sound checks still pass.

## Rules
- Only the files you own. Do not open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse requests).
- Android: no `Pattern.UNICODE_CHARACTER_CLASS`; read optional JSON strings with `isNull` first.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3484
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `grep -c "Quotes.unlisted" src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java` → at least 1
