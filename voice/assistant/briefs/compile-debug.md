# Brief: compile-debug — the standby compile can be run on demand from the PC

## Why
Job app-compile lets the guard compile an app the system never compiled (B站 ran from its APK: 61 s to its first page,
15 s once compiled). It runs only after 30 minutes of standby, at most every 6 hours, and only for an app whose status
is `run-from-apk` — so the path it takes on the TV (a call that lasts minutes over the loopback ADB link, a record
written afterwards) has never run there, and cannot be tried without waiting for the next app that needs it. The PC
needs a way to run exactly that path once, for a package it names.

## What to build
1. `MemoryGuard`:
   `public String compileOnDemand(final String pkg)` — starts the same work `compileNow()` does, for the package given
   (whatever its status; it must be an installed package name: letters, digits, dots and underscores, at least one
   dot, at most 200 characters — anything else → returns `"rejected"` and does nothing), on the same kind of thread of
   its own. Returns at once: `"started"`, or `"busy"` when a compile is already running (the `compiling` flag).
   When it ends it writes the record (`AppCompile.tried`, with the path `AppCompile.uncompiled` gives for the package or
   `""` when it has none) and the guard's note (`AppCompile.line`), exactly as the standby compile does. Share the code
   with `compileNow()` instead of copying it (one private method that compiles one package and records it).
2. `AgentDebug`: a new action `COMPILE` (`action.endsWith(".COMPILE")`), `--es pkg <package>`: result
   `MemoryGuard.get(context).compileOnDemand(pkg)`; a missing `pkg` → `"pkg required"`. Add it to the action list in the
   class comment: `COMPILE (--es pkg <package>: compiles that app's code now, as the standby compile would; the outcome
   is in MEMGUARD's recent)`.
3. `plan()` (the `MEMGUARD --es mode plan` dry run) gets `"compiling": true|false`.

## Checks (`tests/agent/MemoryCareChecks.java`, in the existing `compiling()` group or a new one called from its run())
The package-name rule as a pure static method you add to `AppCompile` — `public static boolean validPackage(String)` —
with checks: `tv.danmaku.bili` true; `com.example_app2.tv` true; `bili` (no dot) false; a name with a space, a `;`, a
`$`, a `/` → false; 201 characters → false; null / "" → false. At least 8 new checks.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`
  (`LocalAdb.compileApp(String)` is called exactly as `compileNow()` already calls it).
  No TV, no adb, no network. Java 8 / Android API 28.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 4198
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_compile_debug.sh` → `COMPILE DEBUG OK`
