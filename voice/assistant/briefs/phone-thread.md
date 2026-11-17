# Brief: phone-thread — the phone thread shows the newest exchange once, and never an older planned task as the newest

## Why (review of 2026-11-09, confirmed)
The phone console (`assets/agent/phone.html`) builds its thread from two answers that it asks for at the same time, and it
merges them in whatever order they arrive:
- `start()` (lines 543 and 545) sends `GET /api/history` and `GET /api/state` together. The TV handles one request at a
  time (`PhoneApi.handle` is synchronized), so the history usually comes back first.
- `seed()` (lines 392–404) fills `log` with the history, newest entry included, without ids. Then `model()` (line 419) sees
  the finished task and calls `add()` (lines 383–389), whose only check is `id === lastId` — and `lastId` (line 238) is
  still `''`. The same exchange is pushed again. What the user sees: the last request 「放首歌 → 好的」 has finished;
  opening the phone page shows it twice at the bottom.
- Answers longer than 120 characters: the history keeps only the first 120 (`History.java:11`, `MAX_REPLIED`), so the
  said+reply comparison in `seed()` (line 399) never matches, and the newest exchange is doubled even when the state
  comes first.
- After the bridge restarts, the state's task is `last_task.json` (`tv/AgentEngine.java:712`,
  `task != null ? task : store.lastTask()`), which is written only for planned tasks and tasks waiting for the user. It is
  often older than the newest history entries (quick commands, plain answers). The page puts it at the bottom: the planned
  「打开B站搜索林俊杰」 appears below the later 「暂停」, as if 星仪 had just run it again.

What the TV sends (the server is not yours; do not change it):
- `GET /api/history` — an array, newest first, at most 50 entries of today and yesterday (`tv/AgentEngine.java:463`):
  `{"when": "11月9日 14:05", "said": …, "replied": …, "state": …, "playing": …}`. `when` is `SimpleDateFormat("M月d日 H:mm")`
  in the TV's local time: minutes only, no year. There are no raw times and no ids. `said` and `replied` are trimmed with
  Java's `trim()` and cut to their first 80 and 120 characters (`History.java:152`).
- `GET /api/state` — `{"busy", "awaiting", "prompt", "task", …}`; `task` is null or the ledger (`Ledger.toJson`): `id`,
  `utterance`, `state` (`running`, `awaiting_confirmation`, `awaiting_user`, `done`, `interrupted`, `failed`, `answered`,
  `cancelled`), `last_reply` (only when there is one), `started_at` and `updated_at` (ms since 1970, the TV's clock),
  `goal`, `steps`, `actions`, `question_asked`.
- A finished task's history entry is written right after its state, reply and `updated_at` are set, from the same text.

## What to build
1. **`PhoneThread`, a pure module of the page.** Add a new `<script>` block right before the page's main `<script>`,
   holding only `var PhoneThread = (function () { 'use strict'; … return { finished: finished, at: at, merge: merge }; })();`.
   It must not use `document`, `window`, `localStorage`, `fetch`, timers or anything of the main script: the test runs
   this block alone under node. Use the same ES5 style as the page (`var`, `function`; no arrow functions, no `let`/`const`,
   no template strings, classes or modules).
   - `finished(t)`: true for the five states the page already treats as finished: done, cancelled, failed, interrupted,
     answered. It moves here from the main script.
   - `at(when, now)`: reads `"M月d日 H:mm"` (`/^(\d{1,2})月(\d{1,2})日 (\d{1,2}):(\d{2})$/`) and returns the ms of the start
     of that minute in local time (`new Date(y, M - 1, d, H, mm).getTime()`). `y` is the year of `now`, or the year before
     when that date would be more than a day after `now` (「12月31日 23:58」 read on 1 January). Anything else (`""`, `null`,
     `undefined`, 「昨天 22:58」) gives `NaN`.
   - `merge(history, tasks, now)` returns the finished part of the thread, oldest first: `[{said, reply}]`, at most 20
     (the newest 20).
     - `history`: what `GET /api/history` sent (newest first), or `null` while it has not come back or when it failed.
       Entries without `said` are skipped. Every other entry becomes `{said: e.said, reply: e.replied || ''}`, oldest first.
     - `tasks`: the tasks the page took from `/api/state`, oldest first. After the history entries, each task joins as
       `{said: t.utterance, reply: t.last_reply || ''}` unless:
       a. it has no `utterance`, or it is not `finished`. A running task is never in the log: the page shows it once, as
          the live block.
       b. an earlier finished task in `tasks` has the same `id` (each id is decided once).
       c. it is older than the history: its time (`updated_at`, or `started_at` when `updated_at` is missing or 0) is
          before the newest `at()` among the history's entries. This is the restarted bridge's old planned task.
       d. the history already holds it. That means an entry whose minute is the task's minute or later
          (`at(e.when, now) + 60000 > time`, or that `at()` is NaN, or the task has no time) has
          `e.said === cut(t.utterance, 80)` and `(e.replied || '') === cut(t.last_reply || '', 120)`. `cut` does what
          Java's `trim()` does (it strips only characters up to U+0020: `/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g`) and then
          keeps the first n characters. This catches the newest exchange, also when the answer is longer than 120
          characters.
     - With a `null` or empty history only (a) and (b) apply. That is what the page shows before the history arrives.
     - It is pure: it reads its arguments and never changes them, and the same arguments always give the same result.
   Known and accepted: the history has minutes only, so if the same words are repeated within the same minute as their
   history entry, the exchange shows once. Do not work around it outside your files.
2. **The main script uses it.**
   - The requests stay exactly as they are. The history and the first state still go out together at start, and nothing
     waits for anything new.
   - Replace `seed()`, `add()` and `lastId` with `hist` (null until `GET /api/history` came back) and `done`. `done` holds
     the finished tasks taken from `/api/state`, oldest first, the first finished report of each id, at most 20.
   - In `model()`, a finished task sets `live = null` and joins `done` when its id is not there yet. Then, whether it
     joined or not, `log = PhoneThread.merge(hist, done, Date.now())`. The history's callback does
     `hist = Array.isArray(h) ? h : []`, then the same `log = PhoneThread.merge(hist, done, Date.now())` and `render()`.
     Its failure callback stays empty.
   - Write both calls literally as `PhoneThread.merge(` (the test counts them). Use `PhoneThread.finished` wherever the
     page needs `finished`.
   - Nothing else changes: the live block, `render()`, the polling times, the requests, pairing, the HTML and the CSS.
3. **`tests/agent/phone_thread_test.mjs`**, using only node built-ins. It reads `assets/agent/phone.html` relative to
   itself (`new URL('../../assets/agent/phone.html', import.meta.url)`), takes the `<script>` block that contains
   `var PhoneThread`, runs it with `new Function(block + '\nreturn PhoneThread;')()`, and checks the list below.
   - Build every time with the local-time constructor, e.g. `new Date(2026, 10, 9, 14, 5, 20).getTime()`, so the result
     does not depend on the time zone.
   - `now` is 9 Nov 2026 14:10 unless said otherwise.
   - "saids" means `log.map(e => e.said)`, compared exactly and in order.
   - `H` = `[{when: "11月9日 14:05", said: "放首歌", replied: "好的"}, {when: "11月9日 14:01", said: "暂停",
     replied: "好的，已暂停"}, {when: "11月8日 22:58", said: "打开B站搜索林俊杰", replied: "已在B站搜索林俊杰"}]`.

   1. **History alone, and the newest exchange once.**
      - `merge(H, [], now)` gives the three entries oldest first, with their replies.
      - `H` plus the task `{id: "a1", utterance: "放首歌", state: "done", last_reply: "好的", updated_at: 14:05:20}` gives
        saids `打开B站搜索林俊杰, 暂停, 放首歌`.
      - The same with `updated_at` 14:04:59 (its entry fell into the next minute).
      - `merge(null, [that task], now)` gives saids `放首歌` (before the history came).
   2. **A long answer once.** Put the entry `{when: "11月9日 14:06", said: "今天天气怎么样", replied: <the first 120
      characters of a 130-character answer>}` on top of `H`. The task with the whole answer, `updated_at` 14:06:10, gives
      saids `打开B站搜索林俊杰, 暂停, 放首歌, 今天天气怎么样`.
   3. **An older planned task is never the newest.**
      - `H2` = `[{when: "11月9日 14:07", said: "暂停", replied: "好的"}, {when: "11月9日 14:03", said: "打开B站搜索林俊杰",
        replied: "已在B站搜索林俊杰"}]`.
      - `H2` plus the planned task `{id: "p1", utterance: "打开B站搜索林俊杰", state: "done", last_reply: "已在B站搜索林俊杰",
        goal: "在B站搜林俊杰", steps: [{text: "打开B站", status: "done"}, {text: "搜索林俊杰", status: "done"}],
        updated_at: 14:03:30}` gives saids `打开B站搜索林俊杰, 暂停`.
      - A planned task that is not in the history at all, `updated_at` 7 Nov 20:00, also gives saids `打开B站搜索林俊杰, 暂停`.
   4. **New exchanges join, oldest to newest.** `H` plus `下一首 → 好的` at 14:06:30 and `暂停 → 好的，已暂停` at 14:08:00
      (ids `n1`, `n2`) gives saids `打开B站搜索林俊杰, 暂停, 放首歌, 下一首, 暂停`. A repeat of an older exchange in a later
      minute joins again.
   5. **A running task is never in the log.**
      - `H` plus `{id: "r1", utterance: "打开B站搜索林俊杰", state: "running", steps: [{text: "打开B站", status:
        "in_progress"}], updated_at: 14:09:00}` gives saids `打开B站搜索林俊杰, 暂停, 放首歌`.
      - `merge(null, [that task], now)` gives an empty log.
      - `finished` is false for running, awaiting_user and awaiting_confirmation, and true for the five finished states.
   6. **One id once.** The task `n1` twice in `tasks` gives saids `打开B站搜索林俊杰, 暂停, 放首歌, 下一首`.
   7. **The year turns.**
      - `now` = 1 Jan 2027 00:10, history `[{when: "1月1日 0:02", said: "暂停", replied: "好的"}, {when: "12月31日 23:58",
        said: "放首歌", replied: "好的"}]`.
      - The tasks `放首歌 → 好的` at 31 Dec 2026 23:58:30 and `下一首 → 好的` at 1 Jan 2027 00:05 give saids
        `放首歌, 暂停, 下一首`.
      - `at("12月31日 23:58", now)` equals `new Date(2026, 11, 31, 23, 58).getTime()`.
      - `at("昨天 22:58", now)`, `at("", now)` and `at(null, now)` are NaN.
   8. **At most 20.**
      - Build a history of 25 entries, entry i (0–24) being `{when: "11月9日 13:" + (50 - i, two digits), said: "第" +
        (25 - i) + "句", replied: "好"}`. Put `{when: "11月9日 13:51", said: "", replied: "空"}` in front of them.
      - The result has exactly 20 entries: `第6句` … `第25句`, in that order.
   9. **`merge` does not change its arguments.** `JSON.stringify` of the history and the tasks is the same before and
      after.
   10. **Wiring.** `phone.html` no longer contains `function seed(` or `lastId`, and contains `PhoneThread.merge(` at
       least twice.

   Print one line per failed check and exit 1. Print `PHONE THREAD OK` only when every check passed.

## Rules
- Only the two files you own. Do not open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse
  requests). No TV, no adb, no browser, no npm packages, no Java changes.
- PhoneChecks, which the core checks run, reads phone.html's text. Keep `BUSY_MS = 700`, `IDLE_MS = 4000`, the paths
  `/api/say`, `/api/state`, `/api/consent`, `/api/stop`, `/api/key` and `/api/pair`, and the texts `localStorage`,
  `回答星仪的问题`, `好的`, `算了`, `电视朗读`, `在电视上显示配对码` and `env(safe-area-inset-bottom)`. Never write `http://`
  or `https://` anywhere in the page, comments included.
- JSON fields may be missing or null (`task`, `last_reply`, `goal`, `replied`, `updated_at`). Read them with `|| ''` or
  `|| 0` before you use them.
- Run node as `H:/tools/node/node-v24.22.2-win-x64/node.exe`. The runner uses this exact path, and the node on PATH may be
  a different version.

## Acceptance (run all of them yourself before you finish)
- `H:/tools/node/node-v24.22.2-win-x64/node.exe tests/agent/phone_thread_test.mjs` → `PHONE THREAD OK`
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3523
