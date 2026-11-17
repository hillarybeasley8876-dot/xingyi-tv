# Brief: notes-history — 随手记 (quick notes) and recall_history (what 星仪 did in the last 30 days)

## Why
The user (2026-11-05) asked to fill the harness's gaps against an agent-architecture checklist (记忆, 场景能力「随手记」).
Today 「帮我记一下明天买牛奶」 has nowhere to go: `remember` is for long-term facts and the contract tells the model not
to store one-off things; and 星仪 remembers nothing older than three hours (`recent.json`: 6 turns, 180 minutes), so
「昨天晚上放的那个视频再放一遍」 cannot be answered. This job builds both stores and their tools as pure Java. The lead
wires them into the engine after merging (registering the tools, appending one history entry per finished task,
listing pending notes under 「关于用户」), using exactly the public API below.

## Read first
- `assets/agent/contract.json`: the tools `note_add`, `note_list`, `note_done`, `recall_history` and the section
  「## 定时、随手记和回忆」 of the system prompt. Argument names are final.
- `src/xyz/erapi0neer/xingyi/agent/Memory.java`, `MemoryTools.java`, `tests/agent/MemoryChecks.java`: the patterns to
  follow (pure stores in a JSONObject, tools over a `Store`, checks). Memory's privacy rule (`SECRET` pattern and 6+
  digits) must also hold for notes: copy those two patterns into Notes.java (Memory.java is not yours).
- `src/xyz/erapi0neer/xingyi/agent/Tool.java`, `AgentLoop.java`: the note tools must be `Gate.read()` (like the memory
  tools: they change only 星仪's own files, so they run without a plan); `note_add` and `note_done` are in `ANSWERS`:
  their result `message` is spoken as the whole answer, so it must be one natural sentence. `note_list` and
  `recall_history` are answered by the model from the result.
- `src/xyz/erapi0neer/xingyi/agent/Ledger.java`: what a finished task looks like (utterance, reply, state, actions).

## What to build
All time math uses the `TimeZone` passed in. Relative days: 今天 / 昨天 / 前天, older as `11月3日`; times `H:mm`.

### 1. `src/xyz/erapi0neer/xingyi/agent/Notes.java` (pure)
Store shape: `{"next_id": 5, "items": [{"id": 4, "text": "明天买牛奶", "at": <ms>}]}`; missing/broken = empty.
- `public static String add(JSONObject notes, String text, long now) throws JSONException`: trim, collapse inner
  whitespace; empty → throw `IllegalArgumentException("要记什么？")`; over 80 chars → `IllegalArgumentException(
  "太长了，挑重点说一句")`; secret (the Memory patterns) → `IllegalArgumentException("这类信息（密码、验证码、证件号）不记")`;
  a pending note that is the same after normalizing (spaces/punctuation removed) or contains / is contained in the new
  one → nothing added, return 「已经记着了：<old text>」; otherwise add and return 「记下了：<text>」. Items older than 60
  days are dropped on every add; at most 50 items (drop the oldest).
- `public static String list(JSONObject notes, long now, TimeZone tz)`: 「随手记里有 2 件：1 号，明天买牛奶（今天 10:05 记的）；
  4 号，给妈妈打电话（昨天 21:40 记的）。」 oldest first; empty → 「随手记里没有东西。」 (60-day expiry applies).
- `public static List<String> done(JSONObject notes, String which)`: `all`/全部/所有 → everything; a number → that id;
  otherwise every note whose text contains `which` (trimmed) or is contained in it; returns the texts removed.
- `public static List<String> summary(JSONObject notes, long now, TimeZone tz)`: the newest 10 pending notes as lines for
  「关于用户」, each `<text>（<M月d日>记）`, oldest first, each at most 40 chars (cut the text, keep the date).

### 2. `src/xyz/erapi0neer/xingyi/agent/NoteTools.java` (pure)
`public interface Store { JSONObject load() throws Exception; void save(JSONObject notes) throws Exception; }`,
`public static List<Tool> create(Store store, LongSupplier clock, TimeZone tz)`; `run` never throws (storage failure →
`ok:false, status "error", message "随手记没存上：<reason>"`):
- `note_add` → `{"ok":true,"status":"recorded"|"exists","message":…}`; rejection → `ok:false, status "rejected"`.
- `note_list` → `{"ok":true,"status":"observed","message":<list>,"items":[{id, text, when}]}`.
- `note_done` → removed: `{"ok":true,"status":"done","message":"划掉了：" + texts joined with "、" + "。"}`; nothing matched:
  `{"ok":false,"status":"not_found","message":"随手记里没有这件。" + list}`.

### 3. `src/xyz/erapi0neer/xingyi/agent/History.java` (pure)
Store shape: `{"items": [{"at": <ms>, "said": "…", "replied": "…", "state": "done", "playing": "…"(optional),
"app": "…"(optional)}]}`.
- `public static void add(JSONObject log, String said, String replied, String state, String playing, String app,
  long now) throws JSONException`: skip an empty `said`; cut `said` to 80 and `replied` to 120 chars, `playing`/`app` to
  60; null/empty optional fields are left out; keep at most 500 items and only the last 30 days.
- `public static List<JSONObject> query(JSONObject log, String query, int days, long now)`: `days` clamped to 1–30
  (0 or missing → 7); items inside the window, newest first, at most 8. No query (null/blank) → the newest 8. With a
  query: an item matches when said, replied, playing or app contains the whole query; when nothing matches that way,
  split the query on spaces and Chinese/ASCII punctuation and match items containing every part; still nothing →
  cut the query into overlapping 2-character pieces (「林俊杰的歌」→ 林俊、俊杰、杰的、的歌), drop pieces made only of the
  filler characters 的了个那这一吗呢吧再遍放看, and match items containing at least half of the remaining pieces
  (rounded up; none left → no match).
- `public static String describe(JSONObject item, long now, TimeZone tz)`: 「昨天 22:58 你说「…」，星仪回答「…」（当时在放
  《…》）」 — the playing part only when present.

### 4. `src/xyz/erapi0neer/xingyi/agent/HistoryTools.java` (pure)
`public interface Store { JSONObject load() throws Exception; }` (read-only),
`public static List<Tool> create(Store store, LongSupplier clock, TimeZone tz)` → `recall_history`: `Gate.read()`;
found → `{"ok":true,"status":"observed","message":"找到 n 条：" + descriptions joined with "；","items":[…]}`;
nothing → `{"ok":true,"status":"observed","message":"最近 <days> 天没有找到和「<query>」有关的记录。","items":[]}`
(without a query: 「最近 <days> 天还没有记录。」); never throws.

## Checks — `tests/agent/NoteChecks.java` (the stub is already called by `AgentCoreTest.main`)
At least 45 checks with a fixed `now` and `TimeZone.getTimeZone("Asia/Shanghai")`: notes add (normal, empty, 81 chars,
secret word, 6 digits, duplicate after normalizing, contained / containing), the 50 cap and the 60-day expiry, list
wording and the empty text, done by id / keyword / all / no match, summary (10 newest, 40-char cut, date suffix);
history add (cuts, optional fields, 500 cap, 30-day window), query (no query, whole match, parts, 2-char pieces, days
clamped, at most 8, newest first), describe (today / yesterday / older, with and without playing); the four tools
through `create` with in-memory stores (ok / status / message, storage failure); a loop check: a `Script` calling
`note_add` without a plan → one model round, ANSWERED, reply 「记下了：明天买牛奶」.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 850
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_notes.sh` → `NOTES OK` (builds the TV app with your sources, runs the bridge's JVM suite,
  checks the public signatures above and that the four classes import no android.*)

## Files you own
`src/xyz/erapi0neer/xingyi/agent/Notes.java`, `src/xyz/erapi0neer/xingyi/agent/NoteTools.java`,
`src/xyz/erapi0neer/xingyi/agent/History.java`, `src/xyz/erapi0neer/xingyi/agent/HistoryTools.java`,
`tests/agent/NoteChecks.java`.
