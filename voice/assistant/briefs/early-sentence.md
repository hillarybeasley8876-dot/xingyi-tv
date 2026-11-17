# Brief: early-sentence — an answer starts being read at its first sentence, not at its second page

## Why (perf.jsonl, the TV's 61 real model answers 2026-11-06…08)
A plain answer (常识、为什么、聊天) is read aloud only once it is whole, unless it is longer than one page: the streaming
reader in `AgentEngine` (`drafts.text`, ~line 1305) starts reading only when `Speech.pages(soFar, PAGE_MAX)` has two pages
(130 characters). Most answers are 60–150 characters, so the user waits for the whole answer: first speech median 5.4 s,
one-round chat answers 3.1–5.8 s after the request (e.g. 「嗯塔罗牌你了解吗」 model 3.8 s, first speech 3.3 s;
「为什么每次我问你问题的时候你会先搜索吗」 6.1 s / 5.4 s). The model writes its first sentence in about a second.

## What to build
1. `Speech.early(String soFar, int pageMax)` (pure): what may be read before the answer is finished, or null:
   - two or more pages of `pages(soFar, pageMax)` → the first page (as today);
   - otherwise the first sentence of `clean(soFar)` when it is complete (ends with 。！？!?), has at least 8 letters or
     digits, and more text has begun after it (the model is still writing — a finished one-sentence answer is read
     at finish as today); a first sentence longer than `pageMax` is not taken this way (pages handle it);
   - otherwise null. The text returned must be a prefix of `clean()` of the finished answer whenever the finished
     answer begins with the same words (the reader and `AgentLoop.end` compare with startsWith).
2. `AgentEngine`'s `drafts.text` uses `Speech.early(soFar, Speech.PAGE_MAX)` instead of its own two-page test; everything
   else stays: one early read per task, `readOn` reads the rest at finish (`all.substring(read.length())`), the panel
   shows it, quiet sources only wait instead of speaking, the `stream-off` switch still turns it off.
3. Checks in `tests/agent/StreamChecks.java` (already run by the core checks), at least 8: a two-page answer still gives its
   first page; 「天空是蓝的，因为阳光里的蓝光被空气散射得最多。傍晚」 gives the first sentence; the same without text after the
   stop gives null; 「好的。然后」 gives null (too short); a first sentence still being written gives null; markdown and a
   list in the stream are cleaned as `clean()` does; the early text is a prefix of `clean(finished)`; null and "" give null.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse requests).
- No TV, no adb. Android: no `Pattern.UNICODE_CHARACTER_CLASS`.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → its last line `agent core: N passed, 0 failed` with N ≥ 3620
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `grep -c "Speech.early(" src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java` → at least 1
