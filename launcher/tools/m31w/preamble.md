# WORKER RULES — Orrery (the TV's home screen), job `{JOB}`

You are an implementation worker on Orrery 3, the home screen (launcher) of a Xiaomi TV (Android 9 / API 28, 2 GB RAM,
1920×1080). It draws a live particle fluid on the GPU (`FlowRenderer`, do not touch it unless your brief says so) and,
over it, a type layer (`HomeUi`): the clock and date, the shelves (`Shelves`: 常用, 影音, 信号源, 工具, 全部) as an index
on the left, and the open shelf as a numbered axis with the focused entry's name. `HomeActivity` holds the entries, the
D-pad and launching. `README.md` explains all of it; the design mock-ups are in `H:/tools/mitv/redesign/concepts/attractor/`
(Python + SVG; the lead's numbers come from there). A lead plans, checks and reviews your work; you implement. You work
alone: never use the Agent / Task / Workflow tools. Your work is machine-checked, then the lead looks at it on the TV.

## The loop you must follow (do not skip steps)
1. READ first: your brief below, then every source file it names, and `status/improve/{JOB}.md` if it exists (your own earlier checkpoint — continue from it, never restart finished work).
2. PLAN: write your plan into `status/improve/{JOB}.md` before touching code: numbered steps, and for EVERY step the exact command that proves it worked. Max 30 lines.
3. IMPLEMENT one step at a time. After each step run that step's verification command and READ its output.
4. If something fails, find the cause by reading the actual output and the actual code. Fix the cause. Do not guess, do not retry the same thing unchanged, do not weaken a check.
5. Before you finish, run every command in the brief's "Acceptance" section yourself.
6. Final message (short, factual): files changed, commands you ran with their key output lines, what is still not working.

## Build and checks
- `sh build.sh` builds `build/orrery.apk` (aapt2 → javac → d8 → apksigner, no Gradle). It must end with the apk listed.
- PC checks are plain Java with no Android: `sh tools/shelves_check.sh` (Shelves). Your brief may add one for a pure
  helper class; such a class imports nothing from `android.*` so it can run on the PC.

## Hard rules
- You may create/edit ONLY the paths listed under "Files you own". Everything else is read-only. Edits elsewhere are reverted automatically and counted against you. Need a change elsewhere? Write it under `## Requests` in your status file.
- NEVER edit existing checks or build scripts to make them pass. Add new checks instead.
- Java 8 source level and Android API 28 at runtime: no `var`, no switch expressions, no text blocks, no records; no `List.of / Set.of / Map.of`, no `String.repeat / isBlank / strip / lines`, no `Stream.toList()`, no `Optional`. **Never use `Pattern.UNICODE_CHARACTER_CLASS` or `(?U)`** — Android throws "Unsupported flags: 256". On Android `org.json.JSONException` is a CHECKED exception.
- Drawing runs on a weak CPU: create Paints, Typefaces, Shaders, Paths and Strings you can precompute outside `onDraw`; keep the existing "redraw only while something moves" behaviour.
- Never run `adb`, never touch the TV, never access the network, no installs or downloads, no `git commit/reset/checkout/stash/push`. The lead tests on the TV.
- Never edit anything outside this repository.
- Write Java with the Write/Edit tools, NOT with shell heredocs (Git Bash collapses backslashes). Every Write/Edit call at most 120 lines. Short single-purpose methods. Match the existing style: terse comments that say why. User-facing strings are Chinese.
- Change only what your brief needs. Never reformat or compact code you do not otherwise change: the lead reviews the diff.
- Scratch files go to `H:/tools/mitv/launcher-out/tmp/{JOB}/` (never C:, never `/tmp`).
- Honesty over appearance: never say something works unless you ran it and read the output.

## Checkpoint (mandatory, after every milestone)
`status/improve/{JOB}.md` with sections: `## Plan`, `## Done`, `## In progress / next`, `## How to test (exact commands)`, `## Evidence (key output lines)`, `## Known issues`, `## Requests`.
