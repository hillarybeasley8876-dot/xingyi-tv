# Brief: phone-console — 星仪 from the phone: type (or dictate with the phone's keyboard), watch, confirm, stop

## Why
The user (2026-11-08): 「我需要一个手机端的app 可以控制harness 主要是方便具体输入什么的 目前小爱语音识别有点问题」.
XiaoAi mishears names (质朴/智谱, TIBA/Tibo, 合集/和谐…); typing on the phone, or the phone keyboard's own dictation,
does not. What exists today (`src/xyz/erapi0neer/xingyi/PhoneVoiceInput.java`) is a two-minute window opened from the
星仪 app on the TV that passes one sentence into its input box — not a remote. This job makes a web app the phone opens
from the browser (and can add to its home screen), served by 星仪's runtime in the XiaoAi bridge, which is always up.

## Read first
`src/xyz/erapi0neer/xingyi/PhoneVoiceInput.java` (how the old window binds the LAN address and pairs), `tv/AgentEngine.java`
(`submit(text, source)`, `consent`, `stop`, `state()`, `history`, `spoken(source)`, how the engine is created and lives
in the bridge, `eyes.hud().showHint(...)`), `tv/AgentProvider.java` (the same operations for the 星仪 app), `tv/Hud.java`
(the hint card), `tests/agent/AgentCoreTest.java` (check helpers).

## What to build
### 1. `src/xyz/erapi0neer/xingyi/agent/PhoneApi.java` (pure: java.util, java.io, org.json — tested on the PC)
- A minimal HTTP/1.1 request parser over an InputStream (request line, headers, a body of at most 8 KB; anything
  larger or malformed → 400) and a response writer (status, `Content-Type`, `Content-Length`, `Cache-Control: no-store`,
  `Connection: close`).
- Routing to an interface the TV side implements (`interface Engine { String submit(String text, boolean speak);
  JSONObject state(); void consent(boolean yes); void stop(); JSONArray history(int days); boolean showPairCode(String
  code); }`) so the checks can drive it with a fake.
- Pairing and tokens: `POST /api/pair/start` makes a 6-digit code (SecureRandom), valid 120 s, shown on the TV through
  `showPairCode` (false when the screen is off → 409 with 「先打开电视屏幕，再配对」); `POST /api/pair {code, name}` → a
  32-byte hex token; 5 wrong codes and the code is void. Tokens persist (the TV side saves `files/agent/phones.json`:
  `{token, name, created, seen}`, at most 5, the oldest dropped). Every other `/api/*` needs `Authorization: Bearer
  <token>` (401 otherwise).
- `POST /api/say {text, speak}` (text 1–500 characters) → `{request}`; `GET /api/state` → the engine's state as it is;
  `POST /api/consent {approve}`; `POST /api/stop`; `GET /api/history` (today and yesterday);
  `POST /api/key {key}` with key one of `vol_up vol_down mute play_pause next prev home back screen_off screen_on` →
  submitted as the Chinese command the quick commands already know (「音量调大一点」「音量调小一点」「静音」「暂停」…「回到桌面」
  「返回」「关闭屏幕」「亮屏」), speak false.
- `GET /` → the web app (`phone.html`, read from assets by the TV side and handed to PhoneApi); `GET /manifest.json`
  (name 星仪, display standalone, background #0b0c0f) and `GET /icon.svg` (a ring with a dot: the panel's orrery mark,
  white on #0b0c0f).
### 2. `src/xyz/erapi0neer/xingyi/agent/tv/PhoneServer.java` (Android side)
A ServerSocket on port **18780** on all interfaces, one daemon accept thread, each connection handled on a small fixed
pool (2 threads), 10 s socket timeout; started once from `AgentEngine` when it is created (log and carry on if the port
is taken). Implements `PhoneApi.Engine` on the engine: `submit(text, speak ? "phone" : "phone-quiet")`; the pair code
on the TV is a hint card `手机配对码　482 913` / `两分钟内在手机上输入` (shown even when another hint was shown lately).
`AgentEngine.spoken(...)` treats `phone-quiet` like `ui` (answered on screen, not aloud).
### 3. `assets/agent/phone.html` — the web app (one file, inline CSS and JS, no external resources)
Mobile first (360–430 px wide), dark: background #0b0c0f, text white at alphas, hairlines `rgba(255,255,255,.12)`,
the accent #A8C8EE used once (the send button / focus), system font stack.
- Not paired: the mark, 「星仪」, a button 「在电视上显示配对码」, then a 6-digit code field and 「配对」; the token goes to
  localStorage; any 401 later returns to this screen.
- Paired: a header (mark, 星仪, a dot: connected / reconnecting); the conversation: each exchange the request (right,
  dim) and the answer (left, bright); while a task runs, under it the plan steps and the thinking chain like the TV's
  panel (numbered steps, ✓ done, a turning arc on the current one, the last actions) from `/api/state`, polled every
  700 ms while busy and every 4 s while idle; a consent card when the state is awaiting a confirmation (its prompt, two
  big buttons 「好的」「算了」); when 星仪 asks a question, the input placeholder says 「回答星仪的问题…」.
- Bottom: a row of quick chips (音量− 音量＋ 暂停/继续 回桌面 关屏 停) and the input bar (auto-growing textarea, the
  send button; Enter sends, Shift+Enter is a new line), a small switch 「电视朗读」 (on by default, kept in localStorage).
- Safe areas (`env(safe-area-inset-bottom)`), no zoom on focus (font-size ≥ 16 px in inputs), works in iOS Safari and
  Android Chrome; the page must also be usable when the TV is busy (requests time out after 8 s with a quiet notice).

## Checks — `tests/agent/PhoneChecks.java` (stub already called by `AgentCoreTest.main`)
At least 40 with a fake Engine: parsing (a normal GET, a POST with a JSON body, a body over the limit, a header without
colon, Content-Length lies), routing (each endpoint, 404, 405), auth (no token, a wrong token, a good one), pairing (a
code works once, expires at 120 s with a fake clock, 5 wrong tries void it, the screen-off 409), say (empty text, 501
characters, speak false → quiet), keys (each key's Chinese command, an unknown key → 400), the manifest and icon.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3256
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_phone_console.sh` → `PHONE CONSOLE OK`

## Files you own
`src/xyz/erapi0neer/xingyi/agent/PhoneApi.java`, `src/xyz/erapi0neer/xingyi/agent/tv/PhoneServer.java`,
`assets/agent/phone.html`, `src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java` (starting the server, `spoken`, the pair
card only), `tests/agent/PhoneChecks.java`.
