# Brief: explore — 星仪 finds out what an app can do (its deep links) and keeps what worked

## Why
The user (2026-11-05): 「自己对应用的mcp探索」. An Android app publishes its own entry points: the deep links in its
manifest (`bilibili://search`, `bilibili://history`, `bilibili://main/favorite`, …). They are the app's
"MCP": a way to get somewhere in one step instead of a dozen remote-control presses. 星仪 today only knows the few
connectors it was given. This job builds (1) a parser that turns the TV's package dump into the app's own deep links,
(2) a small store of what 星仪 learned about an app by trying (「在哔哩哔哩里，bilibili://history 直接打开观看历史」), and
(3) the two tools `explore_app` and `app_note` over them. The lead wires the dump (a fixed, read-only command on the TV),
the store file and the learned notes (they become 应用经验 the next time the app comes up) after merging.

## Read first
- `tests/agent/fixtures/apps/*.txt`: real `dumpsys package <pkg>` output from the TV (B站, 网易云, 云视听极光, the app
  store). Read the start of `tv.danmaku.bili.txt`: "Activity Resolver Table:" → "Schemes:" → one block per filter:
  a line `<hash> <pkg>/<activity> filter <hash>` then indented `Action: "…"`, `Category: "…"`, `Scheme: "…"`,
  `Authority: "<host>": <port>`, `Path: "PatternMatcher{<TYPE>: <pattern>}"`, `Type: "…"`.
- `src/xyz/erapi0neer/xingyi/agent/Skills.java` (a `Skill` has name, text, match, packages, tools; the constructor is
  package-private, so a class in the same package can build one), `MemoryTools.java` (a pure tool over a `Store`;
  copy its error handling), `Memory.java` (its privacy rule: the SECRET pattern and 6+ digits), `Tool.java`.

## What to build
### 1. `src/xyz/erapi0neer/xingyi/agent/AppIntents.java` (pure)
- `public static JSONObject parse(String dump, String pkg)` → `{"package": pkg, "version": <versionName or "">,
  "links": {"<scheme>": ["<entry>", …], …}, "count": <entries>}`:
  - only the "Activity Resolver Table:" → "Schemes:" section (it ends at the next line with the same or smaller
    indentation than "  Schemes:", e.g. "  Non-Data Actions:", or at "Receiver Resolver Table:");
  - only filter blocks whose component starts with `pkg + "/"`, and only those with Action
    `android.intent.action.VIEW`;
  - drop third-party and payment schemes: a scheme matching `fb\d+`, `fbconnect`, `tencent\d+`, `qwallet\d+`,
    `wx[0-9a-z]{8,}`, anything containing `pay`, `oauth` or `auth`, and an all-letters scheme of 8+ characters that
    contains no vowel run of 2 (random ids like `aacbaaafec`): keep this last rule simple and test it on the fixtures;
  - drop hosts containing `pay`, `auth`, `debug`, `push`, `advertis`, and the host `ad`;
  - `http` and `https` are merged under `"https"`;
  - an entry is the host, plus for each distinct path pattern of that host (at most 3) `host/path` with the leading
    `/` removed and every `.*` written as `…` (a path that is only `.*` or `/.*` adds nothing: the host alone); a
    filter with a scheme and no host gives the entry `""` only when nothing else exists for that scheme;
  - entries deduplicated, at most 60 per scheme and 80 in all; schemes ordered: the app's own schemes first (most
    entries first), `https` last; entries in order of first appearance.
  - `version` from the `versionName=` line. A dump without the section gives `links` `{}` and `count` 0; never throw.
- `public static String summary(JSONObject parsed, int maxChars)` → one line for the model and the user:
  「bilibili://search、space、history、main/favorite…；https://www.bilibili.com/video/…、…」; it stops before
  `maxChars` and says 「…等 N 个」 for the rest. Empty links → 「没有找到对外公开的链接」.

### 2. `src/xyz/erapi0neer/xingyi/agent/AppNotes.java` (pure)
Store shape: `{"apps": {"<pkg>": {"label": "哔哩哔哩", "at": <ms>, "notes": [{"text": "…", "at": <ms>}]}}}`.
- `public static String add(JSONObject book, String pkg, String label, String note, long now) throws JSONException`:
  note trimmed, inner whitespace collapsed, 1–120 chars (`IllegalArgumentException("要记的经验是空的")` /
  `("太长了，一句话说清")`); a secret (Memory's patterns) → `IllegalArgumentException("这类信息不记")`; a note equal to an
  existing one after normalizing (spaces and punctuation removed) → 「已经记着了：<old>」; else added → 「记下了：在<label>里，
  <note>」. At most 8 notes per app (the oldest goes), 40 apps (the app noted least recently goes).
- `public static List<String> notes(JSONObject book, String pkg)` oldest first;
  `public static List<String> remove(JSONObject book, String pkg, String which)` — `all` / a 1-based number / a word.
- `public static List<Skills.Skill> skills(JSONObject book)`: one Skill per app with notes: name = label (or the
  package), packages = [pkg], match = [label], tools = [], text = 「<label>（星仪自己摸索出来的经验，可能不准）：」 + the
  notes joined with 「；」.

### 3. `src/xyz/erapi0neer/xingyi/agent/ExploreTools.java` (pure)
```java
public interface Dumper { String dump(String pkg) throws Exception; }     // the TV's `dumpsys package <pkg>`
public interface Apps {
    String resolve(String app);      // an installed app's package for a name or package, or null
    String label(String pkg);        // its name on the TV
}
public static List<Tool> create(Dumper dumper, Apps apps, MemoryTools.Store notes)
```
- `explore_app` `{app}`: `Gate.read()`; describe 「看看<app>能直接打开哪些页面」. Not resolved → `{"ok":false,"status":
  "not_found","message":"电视上没装「<app>」：要用的话先用 install_app 装上"}`. Else dump → parse → `{"ok":true,
  "status":"observed","message":"<label> <version> 能直接打开：<summary(…, 900)>" + (notes ? "。之前摸索出来的经验：<notes
  joined 「；」>" : ""), "package", "links", "notes"}`. A dump that fails → `ok:false, status "error"`. Never throws.
- `app_note` `{app, note}`: `Gate.read()`; the app resolved (or, not installed, the app's words as label and package
  `"?<app>"`); `AppNotes.add` → `{"ok":true,"status":"recorded"|"exists","message":…}`; rejections `ok:false,
  status "rejected"`; storage failure `ok:false, status "error", message "经验没存上：<reason>"`.

## Checks — `tests/agent/ExploreChecks.java` (the stub is already called by `AgentCoreTest.main`)
At least 45 checks, using the fixtures (read them with `java.nio.file.Files` from `tests/agent/fixtures/apps/`):
B站 has `bilibili` first with `search`, `space`, `history`, `main/favorite`, `following` among its entries and no
`bilipay`, `auth…`, `fb…`, `tencent…`, `aacbaaafec`; https entries include `www.bilibili.com`; the version is
8.91.1; counts and caps hold; NetEase and 云视听极光 give their own schemes (look at the fixtures and pin what you find);
a dump of another package's filters gives nothing; empty / null dump → empty, no exception; summary length, its
「等 N 个」 and the empty sentence; AppNotes (add, duplicate, 120-char limit, secret, 8 per app, 40 apps, notes order,
remove by number / word / all, skills text and match); both tools through `create` with fakes (found, not installed,
dump failing, note stored, note rejected, storage failing); one loop check: a `Script` calling `explore_app` without a
plan runs (it is a read) and the model gets the links.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 1340
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_explore.sh` → `EXPLORE OK` (builds the TV app with your sources, runs the bridge's JVM
  suite, checks the signatures above and that the three classes import no android.*)

## Files you own
`src/xyz/erapi0neer/xingyi/agent/AppIntents.java`, `src/xyz/erapi0neer/xingyi/agent/AppNotes.java`,
`src/xyz/erapi0neer/xingyi/agent/ExploreTools.java`, `tests/agent/ExploreChecks.java`.
