# Brief: transcript-ring — keep the last real tasks' transcripts, not just the last one

## Why
The engine saves the full model transcript of a task (system prompt, every round, every tool result, the model's
thinking) only as `files/agent/last_transcript.json`, overwritten by the next task. On 2026-11-09 the user's
「切换到伴奏模式」 took 105 s and 11 rounds; 30 minutes later 「然后关闭显示器」 overwrote its transcript, and the lead could
only piece the failure together from the TV's system log. Real use is the best source of what to fix next, so the TV
keeps the last 12 real tasks.

## What to build
1. **`src/xyz/erapi0neer/xingyi/agent/TranscriptRing.java`** (new, pure, no android.*):
   - `public static final int MAX_COUNT = 12; public static final long MAX_BYTES = 3_000_000L;`
   - `public static String fileName(long startedAt, String id)` → `"<startedAt>-<id>.json"`, `id` reduced to
     `[A-Za-z0-9]` (empty after that → `"task"`).
   - `public static long startedAt(String fileName)` → the number before the first `-`, or -1 when the name does not
     have the form `<digits>-<something>.json`.
   - `public static List<String> newestFirst(Collection<String> names)` → the names that parse, newest first.
   - `public static List<String> toDelete(Map<String, Long> sizes, int maxCount, long maxBytes)` → the names to delete
     (keys of `sizes` = file names, values = their byte sizes): oldest first until at most `maxCount` remain and their
     total is at most `maxBytes`; the newest file is never deleted, even when it alone is bigger than `maxBytes`; names
     that do not parse are always deleted.
   - `public static JSONObject entry(Ledger t, JSONArray messages, long wallMs)` → `{"id","at" (t.startedAt),
     "utterance","source","state" (t.state.name().toLowerCase()),"reply","error" (only when not empty),"wall_ms",
     "messages"}`. Declare `throws JSONException` where org.json needs it (it is checked on Android).
2. **`AgentEngine.Store`** (the inner class in `tv/AgentEngine.java`): a new
   `synchronized void saveRing(Ledger t, JSONArray messages, long wallMs)` writing
   `TranscriptRing.entry(...)` to `<the store's dir>/transcripts/<TranscriptRing.fileName(t.startedAt, t.id)>`, then
   deleting what `TranscriptRing.toDelete(sizes, MAX_COUNT, MAX_BYTES)` names. Never throws (a full disk must not break a
   task). In `AgentEngine.run(...)`, right after the existing `store.saveTranscript(l.transcript());`, call it for traced
   sources only (`if (traced(source)) store.saveRing(result, l.transcript(), <ms since the task started>)` — use the
   time the method already has, or `System.currentTimeMillis() - result.startedAt`). Test and debug runs stay out of
   the ring so they never push real tasks out.
3. **`AgentDebug`**: `TRANSCRIPT` gets an optional `--ei back N`: without it the action stays exactly as today
   (`last_transcript.json`); with it, the N-th newest ring entry (0 = newest) is exported to the external files dir as
   `transcript-<N>.json`, result `"transcript-<N>.json"` or `"none"` when there are fewer entries. A new action
   `TRANSCRIPTS` (`action.endsWith(".TRANSCRIPTS")`; note `".TRANSCRIPT"` does not match it, so the two stay apart)
   exports every ring entry, newest first, one JSON object per line, to `transcripts.jsonl`; result
   `"transcripts.jsonl <count>"`. Both run off the main thread like the existing `TRANSCRIPT` (`goAsync`). Add a small
   package-private method on `AgentEngine` (like `transcript()`) that returns the ring entries' texts newest first, so
   `AgentDebug` does not reach into the store's files itself. Update the javadoc's action list at the top of AgentDebug.

## Checks (in `tests/agent/PerfChecks.java`, a new group `ring()` called from its `run()`)
- `fileName(1794228070607L, "mw99-2fnz")` → `"1794228070607-mw992fnz.json"`; an id of only symbols → `…-task.json`.
- `startedAt` on a good name, on `abc.json`, on `12-x.txt`, on `-x.json`, on null → the right number or -1.
- `newestFirst` drops names that do not parse and orders by the number, not by the text (`9-…` is older than `10-…`).
- `toDelete`: 15 small files → the 3 oldest; 5 files of 1 MB with `maxBytes` 3 MB → the 2 oldest; one 5 MB file alone →
  nothing (the newest stays); a bad name → deleted; an empty map → nothing.
- `entry` has the fields above and no `error` key when the ledger's error is empty.
- At least 15 new checks in all.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network.
- Java 8 / Android API 28 as in the worker rules; org.json's `JSONException` is checked on Android.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 3684
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_transcript_ring.sh` → `TRANSCRIPT RING OK`
