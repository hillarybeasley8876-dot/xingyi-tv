# Brief: speech-guards — an answer never says itself twice, and never ends on 「要不我…？」

## Why (the TV, 2026-11-10 morning sweep and real use)
- 「感冒了吃什么好」 came back as two answers in one: 「感冒了多喝热水，注意休息、别着凉，饮食清淡好消化：小米粥、面条、
  蒸蛋羹、苹果和梨。忌辛辣油腻。病情持续加重或高烧不退，及时去医院。感冒了吃什么好，多喝水多休息，吃清淡易消化的，比如小米粥、
  面条、蒸蛋羹、水果，忌辛辣油腻。病情持续或高烧不退及时就医。」 The model wrote it so (two reruns were fine): XiaoAi reads
  the same advice twice. The second part even starts by repeating the question.
- 「我今天好累」 ended 「…要不我放点林俊杰给你听着，边听边缓一缓？」. The contract says no offers at the end (every answer to
  one costs the user another 「小爱同学」), and `Speech.settled` already removes 「要不要我…」「需要我…吗」, but not 「要不我…？」.

## What to build (in `src/xyz/erapi0neer/xingyi/agent/Speech.java`, wired in `AgentLoop.end`)
1. `public static String deduped(String reply, String said)`: when a later part of the reply restates an earlier part,
   keep only the earlier part. Split candidates are sentence ends (。！？!? and ；; — not commas). A later part
   `b = reply[cut:]` restates `a = reply[:cut]` when both are at least 20 characters long (after `Speech.clean`) and
   either (i) `b` starts with the user's request — the first 4 or more characters of `said` with punctuation and spaces
   removed, compared the same way — or (ii) at least 55 % of `b`'s distinct character bigrams (punctuation, spaces and
   quotes removed) also occur in `a`. Take the earliest such cut. Otherwise return the reply unchanged. Never throws;
   null → "".
2. In `AgentLoop.end`, for ANSWERED and DONE only, apply `deduped(reply, task.utterance)` before `Speech.settled`, with
   the same rule `end` already has for the first page XiaoAi has read out: never cut into that page (if the result does
   not start with the read page while the reply did, keep the read page).
3. `Speech.settled` also drops a last sentence that is the assistant offering to do something, said as 「要不我…？」
   「要不…我给你…？」「我给你…吧？」「我帮你…吧？」「我再给你…吧？」 (it must end with ？ or ?; the subject is 我 — 「要不你…」
   「要不要去医院看看？」 are advice to the user and stay). Same exclusions as the existing offers (a question about which
   one, another reading of the request, a hint, inside quotes); the rest is left alone when it would be under 4
   characters, as today.

## Checks (add a group to `tests/agent/OfferChecks.java`, called from its run())
- `tests/agent/fixtures/answers-1110.jsonl` holds 174 real replies (`said`, `reply`): the TV's 30-day history and the
  2026-11-10 sweep. `deduped` changes exactly one of them — the 感冒 one, to its first part ending 「及时去医院。」 — and
  `settled` after `deduped` changes, among them, only replies whose removed text is an offer: list every changed reply
  in the check labels so the lead can read them, and assert that 「我今天好累」 loses exactly its last sentence.
- Hand-made: a list answer with 第一…第二… that repeats words (must stay); a two-part answer whose second part starts with
  the question (cut); an answer under 40 characters (never cut); 「要不要去医院看看？」 at the end (stays); 「要不我给你放首歌？」
  at the end (removed); null and "".
- At least 12 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8, no `Pattern.UNICODE_CHARACTER_CLASS` / `(?U)`.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 3829
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `python tests/agent/prompts_doc.py --check` → `PROMPTS.md is up to date`
