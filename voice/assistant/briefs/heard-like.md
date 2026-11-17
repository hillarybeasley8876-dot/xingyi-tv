# Brief: heard-like — names the speech recognition wrote another way, and why-questions data cannot close

## Why
XiaoAi's speech recognition writes the same name differently each time, and 星仪 does not connect them. Real requests on
the TV, 2026-11-06/07 (from the 30-day history):
- 11:56 「查一下MINI MAX最新的股价」 → answered MINIMAX. 12:20 「查询MAX最新的股价」 → answered **MediaAlpha** (ticker MAX).
  The user had to say 「我说的是MINI MAX最新的股价」.
- 11-06 00:04–00:05 「看一下TABLE的推特」「TI BO这个人」 → worked out as Tibo Sottiaux (@thsottiaux). 11-07 15:00
  「嗯TIBA这个人」 → 星仪 looked up a **stock** called TIBA.
- 14:57 「查询质朴和MINIMAX股价」: 质朴 is 智谱 (same sound); 14:59 星仪 had just answered 「智谱 426.20 港元…」.
- 14:59 right after the 智谱 quote: 「最近为什么地震了」 (why did it crash) → 星仪 read out China's latest **earthquakes**: the
  earthquake tool marked its list final and the loop read it out as the answer to a why-question.
Mainstream assistants keep the entities of the conversation and bias recognition towards them; we cannot touch
XiaoAi's recognition, so we do it afterwards: tell the model which words of the request look like a name said or
answered before. And a why-question is never answered by a data tool's list.

## Read first
`src/xyz/erapi0neer/xingyi/agent/Pinyin.java` (sameSound, soundIndex — merged an hour ago), `AgentLoop.java` lines
300-340 (where a round's answer is taken: `asksWhy`, `directAnswer`, `searchThenAct`, `searchAnswer`) and 640-735,
`History.java` (entries `{at, said, replied, state, playing, app}`), `tv/AgentEngine.java` `environment(...)` (around line
1370) and the `Store` methods `recent()` (around 1578: `[{said, replied, minutes_ago}]`, three hours) and `history()`
(`{items: [...]}`, 30 days, oldest first), `tests/agent/PinyinChecks.java` (style of checks).

## What to build

### 1. `src/xyz/erapi0neer/xingyi/agent/Heard.java` (pure: java.util, java.util.regex only)
```java
public final class Heard {
    public static final class Hint {
        public final String said, meant, when;   // 「MAX」, 「MINIMAX」, 「刚才」 or 「11月6日」
    }
    /** earlier: what earlier turns said and answered, newest first, each {said, replied, when}. At most 4 hints, newest
     * names first, one per word of the request. */
    public static List<Hint> hints(String utterance, List<String[]> earlier)
    /** The environment line, "" when there are none:
     * 「「MAX」可能是刚才说过的「MINIMAX」；「TIBA」可能是11月6日说过的「Tibo」」 */
    public static String line(List<Hint> hints)
}
```
**Names in earlier turns** (collect from `said` and `replied` of every earlier entry):
- Latin words: runs of `[A-Za-z0-9]` starting with a letter; runs separated by one space are also taken joined
  (「MINI MAX」 → MINIMAX as well as MINI and MAX; 「TI BO」 → TIBO). A name has 3+ characters (MINIMAX, OpenAI, Tibo,
  Sottiaux, ChatGPT, Codex, TIBO). Skip a stop list of
  everyday words: `the, and, for, you, app, ok, tv, ai, http, https, www, com` (case-insensitive).
- Chinese names, from `replied` only (星仪's answers write names correctly; the user's words are the recognition's):
  text inside 《》, 「」, “” or "" quotes, 2 to 12 characters; and a stock quote's name, the 2–8 Chinese characters right
  before ` <number> 港元|元|美元|点` (「智谱 426.20 港元」 → 智谱; 「贵州茅台 1453.08 元」 → 贵州茅台).
**Words of the request**:
- Latin words the same way (3+ characters; adjacent words joined as well), and every run of 2–4 Chinese characters
  (all of them: 「查询质朴和」 gives 查询, 询质, 质朴, …).
**A hint** when the word is not literally an earlier name (case-insensitive, spaces ignored) and:
- Latin: an earlier name of 5+ letters contains it as a prefix or suffix and it has 3+ letters (MAX in MINIMAX; not
  "INI"), or both have 4+ letters, the same length and differ in exactly one letter (TIBA / TIBO; case-insensitive);
- Chinese: `Pinyin.sameSound(word, name)` for a name of the same length (质朴 / 智谱), or the word is 3+ characters and
  `Pinyin.soundIndex(word, name) >= 0` (毛台 is too short for this; 贵州毛台 would match 贵州茅台 by sameSound anyway).
  A Chinese word literally inside an earlier name is said right: no hint (茅台 in 贵州茅台).
- Never a hint whose `meant` is literally in the request (the user said it right this time).
For each word keep the first match in `earlier` order (newest first; within one entry the names of `replied` before
those of `said`: the answer has the right spelling, so 「TIBA」 gets 「Tibo」 from the answer, not 「TIBO」 from the
user's words). Words that overlap (质朴 and 询质) — keep only
hints whose `said` does not overlap an earlier-kept one in the request. 「刚才」 when the entry's `when` is 「刚才」,
else its `when` as given.

### 2. `AgentLoop`: why-questions are not answered by data
In the round's answer (around line 323): when `asksWhy(said)`, a data tool's own answer (`searchAnswer`'s DATA branch:
the model's final=true or the tool's declared `final`) is not the answer — the model gets the round. `web_search` and
`x_search` answers with final=true still close the round (they answer the why). Nothing else changes.

### 3. `tv/AgentEngine.java`: the environment carries the hints
In `environment(tools, said)`: build `earlier` newest first from `store.recent()` (when = 「刚才」 if `minutes_ago` < 30,
else 「<n>分钟前」) and then `store.history().optJSONArray("items")` from the newest backwards, at most 200 entries
(when = 「M月d日」 of `at`, Asia/Shanghai). `String line = Heard.line(Heard.hints(said, earlier));` and when it is not
empty `o.put("heard_like", line)`. Wrap it so any exception leaves the environment as it was (it must never break a
request). Nothing else in AgentEngine changes.

## Examples (each one a check)
- 「查询MAX最新的股价」, earlier: (刚才, 「查一下MINI MAX最新的股价」, 「MINIMAX 155.80 港元，跌 8.46%…」) →
  「「MAX」可能是刚才说过的「MINIMAX」」.
- 「嗯TIBA这个人」, earlier: (11月6日, 「TI BO这个人」, 「你说的应该是 Tibo Sottiaux，X 账号 @thsottiaux，在 OpenAI 负责…」) →
  「「TIBA」可能是11月6日说过的「Tibo」」 (TIBA/TIBO: one letter; Tibo is in the answer).
- 「查询质朴和MINIMAX股价并且进行比较」, earlier: (刚才, 「查询制储的股价」, 「智谱 426.20 港元，跌 10.46%（11 月 6 日收盘）」) →
  「「质朴」可能是刚才说过的「智谱」」 and no hint for MINIMAX (not in earlier) nor for 制储.
- 「我说的是MINI MAX最新的股价」 with MINIMAX earlier → no hint (said right).
- 「最近为什么地震了」 → no hint. 「播放林俊杰的歌」 with 林俊杰 earlier → no hint (literally the same).
- 「打开B站」 → no hint (B站 is not 3 Latin letters; 打开 is no earlier name).
- 「茅台股价」 with earlier 「贵州茅台 1453.08 元」 → no hint (茅台 is literally inside 贵州茅台: said right).

## Checks — `tests/agent/HeardChecks.java` (stub already called by `AgentCoreTest.main`)
At least 40: every example above; name collection (quotes of each kind, the stock pattern, Latin joined words, the stop
list, 2-letter words ignored); the one-letter rule (TIBA/TIBO yes, TIBA/TABLE no, CAT/CAR no — 3 letters); prefix/suffix
(MAX/MINIMAX yes, INI/MINIMAX no, MINI/MINIMAX yes); newest earlier entry wins; at most 4 hints; overlapping Chinese words
give one hint; `line` of none is ""; null / empty inputs never throw. The why rule: with a scripted model (`Script`) and a
fake data tool returning `{"ok":true,"answer":"…","final":true}`, a why-question (「最近为什么地震了」) does not end the
round with the tool's answer (the model's next reply is used), the same request without 为什么 does; a web_search
final still closes a why-question. Look at how existing checks script `searchAnswer` cases (grep `searchAnswer` and
`declared` in tests/agent).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 2638
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_heard_like.sh` → `HEARD OK` (builds the TV app with your sources, runs the bridge's JVM suite,
  checks the signatures, that Heard imports no android.*, and that the environment puts heard_like)

## Files you own
`src/xyz/erapi0neer/xingyi/agent/Heard.java`, `src/xyz/erapi0neer/xingyi/agent/AgentLoop.java` (the why rule only),
`src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java` (the environment only), `tests/agent/HeardChecks.java`.
