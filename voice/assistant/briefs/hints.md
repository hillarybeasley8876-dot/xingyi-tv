# Brief: hints — a quiet card when the screen comes on (主动性 that does not interrupt)

## Why
The user (2026-11-05) asked to fill the harness's gaps against an agent-architecture checklist; 「主动性」 is one of its
chapters. On a TV a voice that speaks up unasked is intrusive, so 星仪's proactivity is a silent card: when the screen
comes on, the floating panel shows for 8 seconds what is coming up today, what is still on the 随手记, and what the
user usually does at this hour — then fades. No sound. At most once every 3 hours, never between 1:00 and 6:00, and
the user can turn it off (the lead adds the voice switch and the engine wiring after merging).

## Read first
`src/xyz/erapi0neer/xingyi/agent/Habits.java` (event shape `{t, h, kind, value}`; kinds app / bili / artist / song /
playlist / volume / input), `Notes.java` (items `{id, text, at}`, `MAX_AGE_DAYS`), `Schedules.java` (book `{items:
[{id, kind, what, repeat, hour, minute, at, created}]}`, `describe(item, now, tz)`), `tests/agent/NoteChecks.java` and
`ScheduleChecks.java` (how checks pin a time zone and a fixed now), `agent/tv/Hud.java` (State, modes, `stay`,
`Panel.set` / `onDraw`, `render`), `agent/tv/AgentDebug.java` (`renderSamples`: off-screen panel images).

## What to build
### 1. `src/xyz/erapi0neer/xingyi/agent/Hints.java` (pure: java.util + org.json only)
```java
public static final long MIN_GAP_MS = 3 * 3_600_000L;
/** Up to 3 lines for the card, most useful first; empty = nothing worth showing (then no card at all). */
public static List<String> card(JSONObject habits, JSONObject notes, JSONObject schedules, long now, TimeZone tz)
/** Whether a card may show now: not between 1:00 and 5:59 (TV local time), and MIN_GAP_MS after the last one. */
public static boolean allowed(long lastShown, long now, TimeZone tz)
```
Lines, in this order, each at most 40 characters (cut a long name, keep the sentence):
1. **Coming up**: the soonest schedule item whose `at` is after now and at most 12 hours ahead →
   `Schedules.describe(item, now, tz)` (「今天 20:00 提醒你：该吃药了」「明天 7:00 播放新闻联播」).
2. **随手记**: notes not older than `Notes.MAX_AGE_DAYS` days: one → 「随手记：<text>」; more → 「随手记里有 <n> 件没划掉」.
3. **This hour's habit**: habit events of the last 30 days whose hour `h` is within ±1 of the current local hour
   (wrapping around midnight: 23 and 0 are neighbours), kinds artist, bili and app only; group by kind + value; a
   group counts when it has at least 3 events on at least 2 different days (TV local dates). Pick the group with the
   most events (tie: the one used most recently). The line:
   artist → 「这个时候你常听<value>，说「放首<value>的歌」就行」; bili → 「这个时候你常在B站看<value>，说「B站搜<value>」就行」;
   app → 「这个时候你常开<value>」.
Missing / broken objects count as empty; never throw.

### 2. The card in `src/xyz/erapi0neer/xingyi/agent/tv/Hud.java`
- A mode `"hint"`: the State carries the lines in `reply` (joined with "\n") and nothing else. Draw it like the other
  panels (same glass, same header wordmark) with the status word 「提示」, no spinner / shimmer / pulsing dot, the
  lines in the body paint, at most 3 lines, each ellipsized to the panel width.
- `stay`: a hint stays 8000 ms, then fades like a finished panel.
- `public boolean showHint(State hint)`: shows it only when no panel is up, or the panel that is up is finished or a
  hint; returns whether it did. A task's panel (running / confirm / ask) is never replaced by a hint, and a task's
  `show(...)` always replaces a hint.

### 3. A design sample in `src/xyz/erapi0neer/xingyi/agent/tv/AgentDebug.java`
`renderSamples` also renders a hint card with three sample lines to `hud-hint.png`, like the existing samples.

## Checks — `tests/agent/HintChecks.java` (the stub is already called by `AgentCoreTest.main`)
At least 35 checks with a fixed now and `TimeZone.getTimeZone("Asia/Shanghai")`: each line on its own and all three
in order; schedule window (11 h ahead yes, 13 h no, already past no, the soonest of two); notes (none, one, several,
one older than 60 days ignored); habits (±1 hour, the midnight wrap, 2 events no, 3 events on one day no, 3 events on 2
days yes, kinds volume / song / input ignored, the most frequent group wins, tie by recency, older than 30 days
ignored); 40-character cut; broken inputs (nulls, wrong types) give an empty or partial card, never an exception;
`allowed` (1:00 / 5:59 no, 6:00 yes, 2 h 59 min after the last no, 3 h yes, first card ever yes).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 1180
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_hints.sh` → `HINTS OK` (builds the TV app with your sources, runs the bridge's JVM suite,
  checks the signatures above, the hint mode and sample, and that Hints.java imports no android.*)

## Files you own
`src/xyz/erapi0neer/xingyi/agent/Hints.java`, `src/xyz/erapi0neer/xingyi/agent/tv/Hud.java`,
`src/xyz/erapi0neer/xingyi/agent/tv/AgentDebug.java`, `tests/agent/HintChecks.java`.
