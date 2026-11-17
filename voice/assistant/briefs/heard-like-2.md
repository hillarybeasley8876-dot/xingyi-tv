# Brief: heard-like-2 — fewer, better hints (round 2, checked against the TV's real 30 days)

## Why
Your round-1 `Heard` (merged) was replayed by the lead over the TV's real request log of the last 30 days
(`tests/agent/fixtures/history-2026-11-07.json`: 86 requests, newest first, each `{when, said, replied}`), each
request against the ones before it. It gave 6 hints; 3 are right and 3 are noise that would mislead the model:

| request | hint given | verdict |
|---|---|---|
| 11月7日 12:20 查询MAX最新的股价 | 「MAX」→「MiniMax」 | right |
| 11月7日 15:00 嗯TIBA这个人 | 「TIBA」→「Tibo」 | right |
| 11月6日 0:04 在推特上搜一下OPEN A下一次重置时间 | 「OPEN」→「OpenAI」 | right in spirit; should be 「OPENA」 |
| 11月5日 22:58 我想听的是OPEN AI最新的动向 | 「OPEN」→「OPPOOpen」 | noise: OPEN AI *is* OpenAI, said right; OPPOOpen is the user's own words joined |
| 11月7日 11:56 查一下MINI MAX最新的股价 | 「MINI」→「Gemini」 | noise: the user said MINI MAX, one name |
| 11月7日 14:59 我说的是为什么最近跌这么狠 | 「说的」→「月的」 | noise: 说 has a rare reading yue; 「月的」 is no name |

## What to change (in `Heard.java`; `Pinyin.java` only to add the two methods below)
1. **A Latin word of the request is the whole run.** Runs of letters/digits joined across single spaces are one word
   (「MINI MAX」 is MINIMAX only, 「OPEN A」 is OPENA, 「OPEN AI」 is OPENAI); the single words inside a run are not looked
   at on their own. (Earlier names keep both the single words and the joined run.)
2. **Joined names only from 星仪's answers.** In earlier turns, a joined run counts as a name only in `replied`; in `said`
   only the single words count (「OPPO OPEN AI」 said by the user gives OPPO, OPEN; not OPPOOPEN).
3. **Chinese sounds by the main reading.** A Chinese word sounds like a name only when each character pair shares its
   *first* (most common) reading. Add to `Pinyin` (do not change what is there):
   ```java
   /** sameSound on each character's first (most common) reading only: 「质朴」/「智谱」 yes (zhi pu); 「说的」/「月的」 no
    * (说 is shuo; yue is a rare reading); 「银行」/「银杭」 no (xing / hang). */
   public static boolean sameMainSound(String a, String b)
   /** soundIndex on the first readings only. */
   public static int mainSoundIndex(String a, String b)
   ```
   and use them in `Heard` instead of `sameSound` / `soundIndex`.
4. **A quote's name is exactly what `Quotes.speak` writes**: 2–8 Chinese characters at the start of the text or right
   after one of `：；，。、（「`, then one space, a number, one space, the unit (`智谱 426.20 港元`, `上证指数 3636.80 点`).
   「的历史峰值是1330.00港元」 gives no name.

## Checks you may change, and how
Your round-1 checks stay, except these, which change with the rules above (edit them to say the new thing, and say so in
your status file): 「TI BO」 said by the user is no longer the name TIBO (the hint for TIBE then comes only when an answer
spelled it); `MINI and MAX are names too…` keeps its expectation. Add at least 15 new checks: each rule above with a
case that changes and one that does not, `sameMainSound` / `mainSoundIndex` (质朴/智谱, 说的/月的, 银行/银杭, null), and
**the replay**: read the fixture (newest first), for each request call `Heard.hints(said, earlier)` with the requests
before it as `earlier` (`{said, replied, when}`), and check that exactly these three requests get a hint and the hints are:
- 「在推特上搜一下OPEN A下一次重置时间」 → `「OPENA」可能是<when>说过的「OpenAI」` (the newest earlier entry with OpenAI)
- 「查询MAX最新的股价」 → `「MAX」可能是<when>说过的「MiniMax」`
- 「嗯TIBA这个人」 → `「TIBA」可能是<when>说过的「Tibo」`
Print every hint the replay gives in the check's label when it fails, so a failure says what came out.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 2687
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_heard_like.sh` → `HEARD OK`

## Files you own
`src/xyz/erapi0neer/xingyi/agent/Heard.java`, `src/xyz/erapi0neer/xingyi/agent/Pinyin.java` (the two new methods only),
`tests/agent/HeardChecks.java`.
