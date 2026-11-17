# Brief: heard-homophones-2 — the boundary words cover how stocks and songs are really asked for

## Why (lead review of heard-homophones, merged 5d3c847, replayed against the TV's real 30-day log)
`Heard.standsAlone` (JOINING / LEAD / FOLLOW at Heard.java ~167–173) stopped the ordinary-word hints, but its lists are too
narrow and real hints that master gave are gone:
- 「质朴港股」「质朴市值多少」「质朴上市了吗」「质朴这只股票怎么样」 (港股 / 市值 / 上市 / 这只 are not follow words)
- 「那毛台呢」「买点毛台」 (那 / 买点 are not lead words)
- 「放一首道香」「放个道香吧」 (一首 / 个 after 放)
- 「未来汽车股价」 (a common mishearing of 蔚来汽车: 汽车 is not a follow word)
- the 质朴 half of 「质朴还是毛台」 (还是)
Also `openLeft` copies `s.substring(0, start)` for almost every slice (quadratic in the request length).

## What to build
1. Extend the lists so every case above gives its hint again — two-character follow words such as 港股 美股 市值 上市 这只
   还是 汽车, lead words such as 那 买点 一首 个 — while every case that must stay silent stays silent. Do NOT add a bare
   港 (「到香港…」 would make 到香 a word again) or a bare 的.
2. `openLeft` (and any other substring copy in the slice loop) checks in place: `start >= w.length() && s.startsWith(w,
   start - w.length())`.
3. Checks in `tests/agent/HeardChecks.java` (a new method called from `checks()`): each case above gives its hint; 「到香港」
   「未来几天天气」「马斯克他说了什么」 still give none; and the 30-day replay still gives exactly its 3 hints (it is already
   checked — do not change that check). Known and left alone (topic, not boundaries): 「关于未来」「未来的天气」
   「华海股价」 (华海→花海) — write them down in your notes, no checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse requests).
- No TV or adb. Android: no Pattern flags, plain String methods are fine.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → its last line `agent core: N passed, 0 failed` with N ≥ 3567
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
