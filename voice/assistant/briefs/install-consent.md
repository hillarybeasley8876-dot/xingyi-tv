# Brief: install-consent — any app the user names, from the maker's site, installed only after the user hears what it is

## Why
The user (2026-11-07 23:3x), asked how open 星仪's installing should be, chose: **「你点头就装」** — for an app the user
names, 星仪 may find the installer on the web itself; before installing it shows and says the source site, the
developer in the signing certificate, the package and the size, and installs only after the user says 「好的」; a package
whose signature does not fit the maker (cracked, modified, personally re-signed) is never installed.
Today `install_app` knows only the Xiaomi store catalog and the vetted `OfficialApps` (B站, TV Bro). Real case: 红果短剧
(package `com.phoenix.read`, 北京笔墨留香科技有限公司, Douyin Group) has an official Android build but no TV store entry;
its search results are full of fake 「官网」 sites handing out their own APKs.

Background that matters: the agent installs through the TV's local ADB (`pm install`), which skips Android's own
「install unknown apps」 prompt — this confirmation is the only one the user gets. Make it say the truth.

## Read first
`src/xyz/erapi0neer/xingyi/OfficialAppInstaller.java` (download with size, space, cancel, progress, redirects, SHA-256;
`installedTrusted`; how it inspects an archive with `getPackageArchiveInfo` and the Android 9 signature flags),
`src/xyz/erapi0neer/xingyi/agent/OfficialApps.java` (`installArgsOk`, `redirectAllowed`), `LocalAdb.installVerified`,
`Legacy.java`, `tv/TvTools.java` `install_app` (gate and run, around line 929), `Tool.java` (`Gate.act/sensitive/deny`,
`Turn`), `tests/agent/OfficialAppsChecks.java` (style).

## The flow (two calls of install_app, so the confirmation can show what was really downloaded)
1. **Prepare** — `install_app {app, url}` for an app in neither list: `url` is an `https://` link to the installer that the
   model found on the maker's own site (`page` optional: the official page it came from). Gate: `act` (it only downloads
   into the cache and reads the package; nothing is installed). Run:
   - download (https only; redirects followed only to https, at most 5 hops; refuse a host on the blocklist below at
     every hop; at most 400 MiB; free space ≥ 3 × size; progress and cancel like OfficialAppInstaller);
   - read the archive: package name, versionName, versionCode, the app's label (set `applicationInfo.sourceDir` and
     `publicSourceDir` to the file before `loadLabel`), `minSdkVersion` (Android 9 cannot read it from PackageInfo:
     take `applicationInfo.minSdkVersion` when the platform has it, else skip that check), the signer (exactly one
     signer; its SHA-256 and its subject's O and CN through `CertificateFactory.getInstance("X.509")`), and the native
     ABIs it ships (`lib/<abi>/` entries of the zip);
   - refuse, with the reason in the result (status `refused`), when: not a readable package; two or more signers; a
     debug or test key (`ApkConsent.debugKey`: the subject is the Android debug key's `CN=Android Debug, O=Android`, or
     AOSP's test keys' `CN=Android, OU=Android, O=Android` (with or without `EMAILADDRESS=android@android.com`), or the
     digest is AOSP testkey's `a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc` — do not add digests
     from memory; the subjects catch the rest); the same package already installed with a
     different signer; native code only for ABIs this TV cannot run (it runs `Build.SUPPORTED_ABIS`); minSdk > 28;
   - otherwise keep it in the cache under a token (10 minutes) and return status `needs_consent` with the facts
     `{token, label, package, version, size_mb, signer_org, signer_cn, signer_sha256, host, page_host}` and a note telling
     the model to say these to the user and call `install_app {token}`; and that when the signing company plainly is not
     the maker (a person's name, a download site, a cracker) it must not ask for the install but say so.
2. **Install** — `install_app {token}`: Gate `sensitive` with the full sentence the user hears and sees, e.g.
   `安装「红果免费短剧」（com.phoenix.read 7.4.5，259 MB）：从 www.hongguoduanju.com 下载，开发者签名：Beijing ...（O），可以就说“好的”`
   (an unknown or expired token: `deny`). Run: `LocalAdb.installVerified` with the digest taken at download, then check
   that the installed package has that versionCode and that signer, remember the signer for the package
   (`files/agent/installed_signers.json`: package → sha256, first install wins, later installs must match), delete the
   file. Result `installed` with the facts.
A token is used once. Prepared files older than 10 minutes are deleted the next time install_app runs.

## Blocklist (pure helper; a host or any parent domain)
`423down.com, pc6.com, uzzf.com, downkuai.com, gameggg.com, beihangsoft.cn, ali213.net, pp.cn, wandoujia.com, 3dmgame.com,
duote.com, onlinedown.net, cr173.com, crsky.com, xiazaiba.com, jb51.net` and any `*.pages.dev`, `*.github.io`, `*.vercel.app`
(free hosting anyone can use). The blocklist is a floor, not the check: the user hearing the host and the signer is.

## Pure helper — `src/xyz/erapi0neer/xingyi/agent/ApkConsent.java` (java.util, org.json; tested on the PC)
```java
public final class ApkConsent {
    public static boolean urlOk(String url)                     // https, a host, no user@, ≤ 2000 chars
    public static String host(String url)                        // lower case, no port; "" when none
    public static boolean blocked(String host)                   // the list above, parents and the three suffixes
    public static String[] subject(String dn)                    // {O, CN} from an RFC 2253 / X.500 name, "" when absent
    public static boolean debugKey(String dn, String sha256)     // CN=Android Debug, or a test key's digest
    public static boolean abiOk(List<String> zipEntries, String[] deviceAbis)   // no lib/ at all → true
    public static String sentence(JSONObject facts)              // the consent sentence above
    public static boolean expired(long preparedAt, long now)     // older than 10 minutes
}
```

## Checks — `tests/agent/ApkConsentChecks.java` (stub already called by `AgentCoreTest.main`)
At least 40: urlOk (http, user@host, no host, long, a normal https link), host (ports, upper case), blocked (each listed
host, a subdomain of one, the three suffixes, the maker's real site www.hongguoduanju.com not blocked), subject (DNs with
and without O, escaped commas, CN only), debugKey (the Android Debug DN, the testkey digest, a real-looking DN), abiOk
(armeabi-v7a only on this TV yes, arm64-v8a only on this TV no, both yes, no lib/ yes), sentence (every fact appears;
missing O falls back to CN; size rounded), expired (9 min no, 11 min yes).

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 2951
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_install_consent.sh` → `INSTALL CONSENT OK`

## Files you own
`src/xyz/erapi0neer/xingyi/agent/ApkConsent.java`, `src/xyz/erapi0neer/xingyi/ConsentInstaller.java` (new: prepare and
install, Android side), `src/xyz/erapi0neer/xingyi/Legacy.java` (entry points), `src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java`
(install_app only), `tests/agent/ApkConsentChecks.java`.
