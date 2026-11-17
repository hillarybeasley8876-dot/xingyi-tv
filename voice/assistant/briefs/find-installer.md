# Brief: find-installer — the tool that finds an app's official Android installer link

## Why
The user chose 「你点头就装」 (2026-11-07): an app the user names may be installed from its maker's own site after the
user hears the source, the signer, the package and the size. The install half is merged (`install_app {app, url, page}`
→ facts → `install_app {app, token}` behind a sensitive gate; `ConsentInstaller`, `ApkConsent`). What is missing is how
the model gets `url`: `web_search` removes every link from its answer before the model sees it (`WebSearch.speakable`,
so XiaoAi never reads an address aloud). The lead tried the search model with installer-specific instructions
(`tests/agent/fixtures/installer-*.txt` are its real answers, 2026-11-07 23:5x, 9–12 s each):
- Via浏览器 → `https://res.viayoo.com/v1/via-release-cn.apk` (the maker's own CDN), page `https://viayoo.com/zh-cn/docs/get-via.html`;
- 当贝市场 → a download host its own page lists, page `https://www.dangbei.com/app/m/`;
- 红果短剧 → no direct link (its page only offers a QR code), page `https://applink.novelquickapp.com/`.
The contract already has the tool (`assets/agent/contract.json`: `find_installer {app}`, gated by install words) and the
rule that goes with it; this job implements it.

## Read first
`src/xyz/erapi0neer/xingyi/agent/WebSearch.java` (all: `body`, `instructions`, `Transport`, `Reply`, `check`,
`ResponsesText.extract`, how `x_search` is a second flavour of the same call), `ApkConsent.java` (`urlOk`, `host`,
`blocked`), `DailyCap.java` (the daily cap of paid searches), `tv/TvTools.java` (the `web_search` and `x_search` tools:
how they get the key, the base URL, the model and the cap, and what they return), the three fixtures above,
`tests/agent/WebSearchChecks.java` (how checks fake the transport).

## What to build
### 1. `WebSearch.findInstaller(...)` and the pure parsing — `src/xyz/erapi0neer/xingyi/agent/Installers.java`
- The request: the same Responses call as `web_search` (same model, `tools:[{type:web_search}]`), with these
  instructions (word for word; `now` as the other searches give it):
  `你在帮一台小米电视（Android 9，32 位 armeabi-v7a）找某个应用的官方 Android 安装包。现在是 <now>。先联网搜索。只认开发者自己的官网、它官网上写明的下载地址或开发者自己的 CDN，不要第三方下载站、应用市场镜像、网盘、破解版。只回答一个 JSON 对象，不要别的文字：{"app":应用名,"developer":开发者公司,"page":官网上提供下载的页面地址,"url":安装包直链（https，能直接下载到 .apk；没有直链就填空字符串）,"package":包名（知道的话）,"note":一句说明}`
  and the input `找「<app>」的官方 Android 安装包`. The answer text is **not** passed through `speakable` (the links are
  the point); `ResponsesText.extract` as usual.
- `Installers.parse(String text)` → `JSONObject {app, developer, page, url, package, note, found}`: take the first `{`
  … last `}` (the model sometimes wraps it in ```json fences or a sentence); fields missing → ""; `url` and `page` kept
  only when `ApkConsent.urlOk` and not `ApkConsent.blocked(host)` (a dropped one is said in `note`: 「…是下载站，不算」);
  `found` = url not empty. Unparseable text → null.
### 2. The tool `find_installer` in `TvTools`
Gate `read`. Counts against the web search daily cap like `web_search`. Run: `WebSearch.findInstaller` with a 40 s
timeout; result `ok observed` with the parsed fields when `found`; when not found, `ok:false status not_found` with the
developer and page if known and the message `官网没有公开的安装包直链（比如只能扫码下载）：不要去下载站找；有官方网页版的就用浏览器打开`;
network errors → `fail("error", "找安装包时联网失败：…")`. The result is data for the model, never read out (no `answer`
field). `describe`: `找「<app>」的官方安装包`.

## Checks — `tests/agent/InstallerChecks.java` (stub already called by `AgentCoreTest.main`)
At least 25: parse each fixture (Via found with url and page; 当贝 found; 红果 not found, page kept); the same text in
```json fences; with a sentence before and after; a blocked url (e.g. `https://www.pc6.com/x.apk`) dropped and said in
note; an http url dropped; garbage → null; empty → null; the request body (instructions word for word, the input, the
web_search tool, the model) built through a fake transport; the answer not passed through `speakable` (the url survives);
an HTTP 429 → the error message; the daily cap respected (a capped day refuses before any request).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3070
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_find_installer.sh` → `FIND INSTALLER OK`

## Files you own
`src/xyz/erapi0neer/xingyi/agent/Installers.java`, `src/xyz/erapi0neer/xingyi/agent/WebSearch.java` (findInstaller only),
`src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java` (the find_installer tool only), `tests/agent/InstallerChecks.java`.
