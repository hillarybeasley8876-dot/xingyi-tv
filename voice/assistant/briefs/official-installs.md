# Brief: official-installs — install a browser (and other vetted apps) from their official source, signature-checked

## Why
2026-11-07 22:46–22:48 on the TV the user wanted to watch 红果短剧: 「没有你不知道安卓的安装包吗」「那网页端你可以自己做一个映射呀」
and then, to the lead: 「为什么连安装浏览器这么简单的任务都做不了」. `install_app` knows two sources only: the Xiaomi TV
store's catalog (116 apps; Chinese TV app stores carry no browser at all) and B站's official APK. The TV has no browser
(only the system WebView, Chrome 66 from 2018). So 星仪 could only say no. Mainstream assistants install what the user
asks for from the vendor's own channel; we do the same, for a short list of apps whose official source and signing key
the lead has checked: no guessed package, no mirror site, no unverified APK, ever.

Also a latent bug: `OfficialAppInstaller` downloads into `context.getExternalCacheDir()` — in the bridge process (where
the agent runs) that is `…/Android/data/xyz.erapi0neer.xiaoaibridge/cache/` — but `LocalAdb.installVerifiedBilibili`
copies from `…/Android/data/xyz.erapi0neer.xingyi/cache/`. The B站 install has never run on the TV; it would fail with
COPY_FAILED.

## Read first
`src/xyz/erapi0neer/xingyi/OfficialAppInstaller.java` (all), `src/xyz/erapi0neer/xingyi/LocalAdb.java`
(`installVerifiedBilibili`, `runFixed`, `HandoffBinding.hex64`), `src/xyz/erapi0neer/xingyi/Legacy.java`,
`src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java` (the `install_app` tool around line 929: `target`, `bili`, `named`,
`catalog()`, the gate's rules), `src/xyz/erapi0neer/xingyi/agent/StoreCatalog.java` (how names and aliases match),
`tests/agent/StoreChecks.java` (style).

## The vetted apps (the lead checked these; use exactly these values)
| key | names (what users say) | package | source | signing certificate SHA-256 | limit |
|---|---|---|---|---|---|
| `bilibili` | 哔哩哔哩, B站, b站, bilibili | `tv.danmaku.bili` | `https://dl.hdslb.com/mobile/latest/iBiliPlayer-bili.apk` (Referer `https://app.bilibili.com/`, no redirects) | the two in OfficialAppInstaller today | 384 MiB |
| `tvbro` | 浏览器, 电视浏览器, 网页浏览器, 上网, TV Bro, TVBro, tvbro | `com.phlox.tvwebbrowser` | GitHub's latest stable release of `truefedex/tv-bro`: `GET https://api.github.com/repos/truefedex/tv-bro/releases/latest` (header `Accept: application/vnd.github+json`, a `User-Agent`), take the asset named `tvbro-<tag without v>-generic-geckoIncluded-<abi>.apk` where `<abi>` is the device's first ABI among `armeabi-v7a, arm64-v8a, x86_64` (`Build.SUPPORTED_ABIS` order); download its `browser_download_url` following redirects **only** to `https://github.com/…`, `https://objects.githubusercontent.com/…`, `https://release-assets.githubusercontent.com/…` (anything else: refuse) | `1e5124be7e7fcb7a6462b47a42a8567863c6fccc6fe7708cd278be4f43047c75` (CN=Pheodor Tsapana, PhloX Development Team; read from the official v2.1.6 release) | 256 MiB |
TV Bro is an open-source browser made for TV remotes; the geckoIncluded build carries a current Firefox engine (the
system WebView is too old for today's sites). On this TV (`armeabi-v7a`) the v2.1.6 asset is 150 MB.

## What to build
### 1. `src/xyz/erapi0neer/xingyi/agent/OfficialApps.java` (pure: java.util, org.json)
The table above as data, and the pure decisions, testable on the PC:
```java
public final class OfficialApps {
    public static final class App { public final String key, name, pkg; public final List<String> names, certs; public final long limit; }
    /** The vetted app a request means (StoreCatalog's matching rules: exact name or alias first, then contained), or null. */
    public static App find(String asked)
    /** Whether the user named it in this sentence (the request is the go-ahead, as for store apps). */
    public static boolean named(String utterance, App app)
    /** From GitHub's release JSON: the download URL of the asset for this ABI list, or null (no such asset, a pre-release,
     * a draft, a size over the limit). */
    public static String githubAsset(JSONObject release, String[] abis, App app)
    /** Whether a redirect target may be followed for this app (https and an allowed host; B站: none). */
    public static boolean redirectAllowed(App app, String url)
    /** Whether a certificate digest is one of the app's. */
    public static boolean trusted(App app, String sha256hex)
}
```
### 2. `OfficialAppInstaller` installs any `OfficialApps.App`
- Constructor takes the `App`; `install()` works for both (B站 behaviour unchanged: same source, headers, checks,
  messages with the app's name instead of 哔哩哔哩 where they name it).
- For `tvbro`: resolve the release first (`githubAsset`), then download following only allowed redirects (handle 301/302/
  307/308 yourself, at most 5 hops), same size/space/progress/cancel/SHA-256 rules.
- The temp file is `xingyi-<key>-<random>.apk` in the running app's own external cache dir.
### 3. `LocalAdb.installVerified(String pkgDir, String filename, String digest)`
Replaces `installVerifiedBilibili`: `pkgDir` must be exactly `xyz.erapi0neer.xingyi` or `xyz.erapi0neer.xiaoaibridge`
(the caller passes `context.getPackageName()`), `filename` must match `xingyi-[a-z]+-[A-Za-z0-9_-]+[.]apk`, `digest` 64
hex; the command is the same fixed shell text with the directory filled in from those checked values only. Keep
`installVerifiedBilibili` as a one-line wrapper only if something else calls it (grep).
### 4. `install_app` in `TvTools`
Before the store catalog: `OfficialApps.find(app)` → gate: already installed and trusted → act (open it is the model's
next step); named in this sentence → act; otherwise `Gate.sensitive("从<来源>下载并安装「<name>」（约 <size> MB）")`
(来源: 哔哩哔哩官方网站 / TV Bro 在 GitHub 的官方发布页). `run` → `new OfficialAppInstaller(...)` via `Legacy` (add a public
`Legacy.installOfficial(Context, String key, BooleanSupplier, Consumer<String>)`). The result says what was installed and,
for the browser, `"note": "浏览器装好了：要打开网页就用 open_link 打开网址（https://…），会在 TV Bro 里打开"`.
The deny message for an app in neither list stays as it is.

## Checks — `tests/agent/OfficialAppsChecks.java` (stub already called by `AgentCoreTest.main`)
At least 35: `find` (every name and alias of both apps, 「装个浏览器」, 「安装B站」, a store app is not an official one,
null); `named`; `githubAsset` on `tests/agent/fixtures/github-tvbro-latest.json` (the real answer, saved by the lead):
armeabi-v7a → the 2.1.6 armeabi-v7a URL, arm64-v8a first → arm64, x86 only → null, a release marked prerelease or
draft → null, an asset over the limit → null, the geckoExcluded asset never; `redirectAllowed` (the three hosts yes,
http no, another host no, B站 none); `trusted` (the pinned digests yes, another no, upper case hex yes); the
`LocalAdb` argument checks are Android-side — test their regexes through a small pure helper in OfficialApps
(`static boolean installArgsOk(String pkgDir, String filename, String digest)`), and have LocalAdb call it.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 2853
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_official_installs.sh` → `OFFICIAL INSTALLS OK`

## Files you own
`src/xyz/erapi0neer/xingyi/agent/OfficialApps.java`, `src/xyz/erapi0neer/xingyi/OfficialAppInstaller.java`,
`src/xyz/erapi0neer/xingyi/LocalAdb.java` (the install method only), `src/xyz/erapi0neer/xingyi/Legacy.java`,
`src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java` (install_app only), `tests/agent/OfficialAppsChecks.java`.
