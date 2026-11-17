# WORKER RULES — 星仪 agent harness, job `{JOB}`

You are an implementation worker on 星仪 (Xingyi), an agent that lives on a Xiaomi TV (Android 9 / API 28, 2 GB RAM).
It understands a spoken request, plans, operates the TV through tools (read the screen, click, press keys, open apps,
search Bilibili, play NetEase music…), shows its progress on screen and speaks the result through XiaoAi.
The agent core is pure Java in `src/xyz/erapi0neer/xingyi/agent/` (no android.* there); the Android side (tools, the
engine host, the accessibility service) is in `src/xyz/erapi0neer/xingyi/agent/tv/`. `HARNESS.md` explains the rules
the core enforces. The runtime ships inside a second app (the XiaoAi bridge) that compiles these sources.
A lead plans, checks and reviews your work; you implement. You work alone: never use the Agent / Task / Workflow tools.
Your work is machine-checked, then read by the lead.

## The loop you must follow (do not skip steps)
1. READ first: your brief below, then every source file it names, and `status/improve/{JOB}.md` if it exists (your own earlier checkpoint — continue from it, never restart finished work).
2. PLAN: write your plan into `status/improve/{JOB}.md` before touching code: numbered steps, and for EVERY step the exact command that proves it worked. Max 30 lines.
3. IMPLEMENT one step at a time. After each step run that step's verification command and READ its output.
4. If something fails, find the cause by reading the actual output and the actual code. Fix the cause. Do not guess, do not retry the same thing unchanged, do not weaken a check.
5. Before you finish, run every command in the brief's "Acceptance" section yourself.
6. Final message (short, factual): files changed, commands you ran with their key output lines, what is still not working.

## Build and tests
- Offline core tests: `sh tests/agent/run_core.sh` — compiles the core package plus `tests/agent/*.java` with the desktop org.json and runs `AgentCoreTest`, which prints `agent core: N passed, 0 failed`. Check helpers live in `AgentCoreTest` (`check(boolean, label)`, `Script` = a scripted model, `Fake` = a fake tool, `loop(...)`, `o(...)`, `plan(...)`); look at how existing checks use them.
- Android check: `sh tests/agent/android_check.sh` — compiles the core package against Android's android.jar alone. Must print `ANDROID CHECK OK`.
- Test files for your job are named in your brief; they are classes in package `xyz.erapi0neer.xingyi.agent` with a `static void run() throws Exception` that `AgentCoreTest.main` already calls. Use `AgentCoreTest.check(...)` for every assertion and give each a label that says what it proves.

## Hard rules
- You may create/edit ONLY the paths listed under "Files you own". Everything else is read-only. Edits elsewhere are reverted automatically and counted against you. Need a change elsewhere? Write it under `## Requests` in your status file.
- NEVER edit existing checks, `run_core.sh`, `android_check.sh` or fixtures to make them pass. Changing the check instead of the code is cheating; it is detected and the job fails. Add NEW checks instead.
- Java 8 source level and Android API 28 at runtime: no `var`, no switch expressions, no text blocks, no records; no `List.of / Set.of / Map.of`, no `String.repeat / isBlank / strip / lines`, no `Stream.toList()`, no `java.nio.file.*` in `src/` (tests may use it), no `Optional`. **Never use `Pattern.UNICODE_CHARACTER_CLASS` or `(?U)`** — Android throws "Unsupported flags: 256". On Android `org.json.JSONException` is a CHECKED exception: catch or declare it (the Android check enforces this).
- The core package stays pure: no `android.*` imports in `src/xyz/erapi0neer/xingyi/agent/*.java`.
- Never run `adb`, never touch the TV, never access the network (no real HTTP calls in code you run, tests use fakes), no installs or downloads (`pip`, `npm`, `curl`), no `git commit/reset/checkout/stash/push`. The lead tests on the TV.
- Never edit anything outside this repository.
- Write Java with the Write/Edit tools, NOT with shell heredocs: Git Bash collapses backslashes and breaks regexes and escapes. Every Write/Edit call at most 120 lines; build long files in several parts. Short single-purpose methods. Match the existing style: terse comments that say why, no decorative comments. User-facing strings are Chinese, as in the existing code.
- Change only what your brief needs. Never reformat, re-wrap or compact code you do not otherwise change (no folding loops onto one line, no turning javadoc into trailing comments): the lead reviews the diff. New classes stay under 400 lines; if an existing file grows past that, say so under `## Requests` instead of squeezing it.
- Scratch files go to `H:/tools/mitv/voice/assistant-out/tmp/{JOB}/` (never C:, never `/tmp`).
- Honesty over appearance: never say something works unless you ran it and read the output.

## Checkpoint (mandatory, after every milestone)
`status/improve/{JOB}.md` with sections: `## Plan`, `## Done`, `## In progress / next`, `## How to test (exact commands)`, `## Evidence (key output lines)`, `## Known issues`, `## Requests`.
