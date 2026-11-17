package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.*;

/** official-installs (2026-11-07): the vetted apps installed from their own official source (OfficialApps): which one
 * a request means, the GitHub release asset, the redirects allowed, the pinned signing keys. */
final class OfficialAppsChecks {
    private OfficialAppsChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "official apps checks threw " + e);
        }
    }

    private static final OfficialApps.App BILI = OfficialApps.BILIBILI, BRO = OfficialApps.TVBRO;

    static void checks() throws Exception {
        which();
        names();
        release();
        redirects();
        keys();
        arguments();
    }

    /** find: what a request means, the store's own apps never. */
    private static void which() {
        for (String n : BILI.names) check(is(n, BILI), "「" + n + "」 means the official 哔哩哔哩 client");
        for (String n : BRO.names) check(is(n, BRO), "「" + n + "」 means TV Bro, the browser from its own releases");
        check(is("装个浏览器", BRO), "「装个浏览器」 means TV Bro");
        check(is("安装B站", BILI), "「安装B站」 means the official 哔哩哔哩 client");
        check(is("上网", BRO), "「上网」 on its own means TV Bro");
        check(OfficialApps.find("上网课") == null && OfficialApps.find("我要上网买东西") == null,
                "「上网」 inside a longer request is not the browser: no 150 MB download for a verb");
        check(is("tv.danmaku.bili", BILI) && is("com.phlox.tvwebbrowser", BRO), "the package name itself means the app");
        check(is("TV Bro", BRO) && is("tvbro", BRO) && is("TVBro", BRO), "however TV Bro is typed");
        check(OfficialApps.find("爱奇艺") == null && OfficialApps.find("云视听极光") == null
                && OfficialApps.find("QQ音乐") == null && OfficialApps.find("com.gitvdemo.video") == null,
                "a Xiaomi TV store app is not one of the official sources");
        check(OfficialApps.find("优酷") == null && OfficialApps.find("红果短剧") == null,
                "an app in neither list is not guessed");
        check(OfficialApps.find("B站电视版") == null && OfficialApps.find("哔哩哔哩TV") == null
                && OfficialApps.find("b站tv版") == null,
                "the store's TV build of B站 (云视听小电视) is not the phone client this source ships");
        check(OfficialApps.find(null) == null && OfficialApps.find("") == null && OfficialApps.find("x") == null,
                "nothing or one letter finds nothing");
        check(OfficialApps.ALL.size() == 2 && OfficialApps.byKey("bilibili") == BILI
                && OfficialApps.byKey("tvbro") == BRO && OfficialApps.byKey("other") == null,
                "the table is the two vetted apps, looked up by key");
        check("com.phlox.tvwebbrowser".equals(BRO.pkg) && "tv.danmaku.bili".equals(BILI.pkg), "the packages are the checked ones");
        check(BRO.limit == 256L * 1024 * 1024 && BILI.limit == 384L * 1024 * 1024, "the download limits are the checked ones");
    }

    private static boolean is(String asked, OfficialApps.App app) { return OfficialApps.find(asked) == app; }

    /** named: the user's own words are the go-ahead to install. */
    private static void names() {
        check(OfficialApps.named("装个浏览器", BRO), "「装个浏览器」 names TV Bro: installing it needs no extra question");
        check(OfficialApps.named("给我装个电视浏览器吧", BRO), "so does 「给我装个电视浏览器吧」");
        check(OfficialApps.named("TV Bro 装一下", BRO), "and 「TV Bro 装一下」");
        check(!OfficialApps.named("我想看红果短剧", BRO), "「我想看红果短剧」 names no browser");
        check(!OfficialApps.named("打开设置", BRO), "「打开设置」 names no browser");
        check(!OfficialApps.named("我要上网", BRO) && !OfficialApps.named("孩子要上网课", BRO),
                "「上网」 in a sentence is not the go-ahead to download a browser: it is confirmed first");
        check(OfficialApps.named("用B站看视频", BILI) && OfficialApps.named("装一下bilibili", BILI),
                "the user's words name B站 too");
        check(!OfficialApps.named("打开爱奇艺", BILI) && !OfficialApps.named("我想听歌", BILI), "an unrelated request names no app");
        check(!OfficialApps.named(null, BILI) && !OfficialApps.named("B站", null), "missing words or app: not named");
        // 2026-11-09 review: 「X浏览器」 is another browser, not TV Bro; 「浏览器」 asked for on its own still is
        check(OfficialApps.find("Via浏览器") == null && OfficialApps.find("夸克浏览器") == null && OfficialApps.find("谷歌浏览器") == null
                && OfficialApps.find("360浏览器") == null && OfficialApps.find("UC 浏览器") == null,
                "a branded browser is not TV Bro (it goes to the store catalog, then find_installer)");
        check(is("浏览器", BRO) && is("电视浏览器", BRO) && is("一个浏览器", BRO) && is("好用的浏览器", BRO) && is("TV Bro浏览器", BRO),
                "「浏览器」 on its own, or with words that only ask for one, is TV Bro");
        check(!OfficialApps.named("帮我装个Via浏览器", BRO) && !OfficialApps.named("装一个夸克浏览器吧", BRO),
                "「装个Via浏览器」 is no go-ahead to install TV Bro");
        check(OfficialApps.named("下载一个好用的浏览器", BRO) && OfficialApps.named("帮我安装浏览器", BRO),
                "「下载一个好用的浏览器」「帮我安装浏览器」 still are");
    }

    private static final String V7A = "https://github.com/truefedex/tv-bro/releases/download/v2.1.6/tvbro-2.1.6-generic-geckoIncluded-armeabi-v7a.apk";
    private static final String ARM64 = "https://github.com/truefedex/tv-bro/releases/download/v2.1.6/tvbro-2.1.6-generic-geckoIncluded-arm64-v8a.apk";
    private static final String X64 = "https://github.com/truefedex/tv-bro/releases/download/v2.1.6/tvbro-2.1.6-generic-geckoIncluded-x86_64.apk";

    /** githubAsset: the vendor's own release JSON (saved by the lead) gives the build for this TV's ABI, nothing else. */
    private static void release() throws Exception {
        JSONObject real = new JSONObject(fixture("github-tvbro-latest.json"));
        check(V7A.equals(asset(real, new String[]{"armeabi-v7a", "arm64-v8a"})), "this TV (armeabi-v7a first) gets the 2.1.6 armeabi-v7a build");
        check(ARM64.equals(asset(real, new String[]{"arm64-v8a", "armeabi-v7a"})), "an arm64 TV gets the arm64 build");
        check(X64.equals(asset(real, new String[]{"x86_64"})), "an x86_64 TV gets the x86_64 build");
        check(asset(real, new String[]{"mips64", "armeabi-v7a"}) != null && V7A.equals(asset(real, new String[]{"mips64", "armeabi-v7a"})),
                "an ABI the vendor does not publish is skipped, the next one is taken");
        check(asset(real, new String[]{"x86"}) == null, "x86 alone: no build, nothing is guessed");
        check(asset(real, new String[0]) == null && OfficialApps.githubAsset(real, null, BRO) == null, "no ABI list: nothing");
        check(OfficialApps.githubAsset(null, new String[]{"armeabi-v7a"}, BRO) == null && OfficialApps.githubAsset(real, new String[]{"armeabi-v7a"}, null) == null,
                "a missing release or app: nothing");
        check(V7A.contains("geckoIncluded") && V7A.equals(asset(real, new String[]{"armeabi-v7a"})), "the geckoIncluded build (a current Firefox engine), never geckoExcluded");
        check(asset(only("tvbro-2.1.6-generic-geckoExcluded.apk", 6790605), new String[]{"armeabi-v7a"}) == null,
                "a release with only the geckoExcluded build is not installed");
        check(asset(edit(real, "prerelease", true), new String[]{"armeabi-v7a"}) == null, "a pre-release is never installed");
        check(asset(edit(real, "draft", true), new String[]{"armeabi-v7a"}) == null, "nor a draft");
        check(asset(edit(real, "assets", new JSONArray()), new String[]{"armeabi-v7a"}) == null, "a release without assets: nothing");
        check(asset(edit(real, "tag_name", "v9.9.9"), new String[]{"armeabi-v7a"}) == null, "an asset for another version is not taken");
        check(asset(edit(real, "tag_name", "2.1.6"), new String[]{"armeabi-v7a"}) != null, "a tag without the v still matches its asset");
        JSONObject fat = edit(real, "size", BRO.limit + 1);
        check(asset(fat, new String[]{"armeabi-v7a"}) == null, "a build over the 256 MiB limit is refused, not downloaded");
        check(asset(edit(real, "size", 0), new String[]{"armeabi-v7a"}) == null, "a build with no size is refused");
        check(asset(edit(real, "state", "new"), new String[]{"armeabi-v7a"}) == null, "an asset still uploading is refused");
        check(asset(edit(real, "browser_download_url", V7A.replace("https://", "http://")), new String[]{"armeabi-v7a"}) == null,
                "a download URL that is not https is refused");
        check(asset(edit(real, "browser_download_url", "https://apk.mirror.example/tvbro.apk"), new String[]{"armeabi-v7a"}) == null,
                "a download URL on another host is refused: only GitHub's own hosts");
    }

    private static String asset(JSONObject release, String[] abis) throws JSONException {
        return OfficialApps.githubAsset(release, abis, BRO);
    }

    /** A copy of the release with every asset's field set to the same value (the ones checked are name-like fields). */
    private static JSONObject edit(JSONObject release, String field, Object value) throws JSONException {
        JSONObject copy = new JSONObject(release.toString());
        if ("prerelease".equals(field) || "draft".equals(field) || "tag_name".equals(field) || "assets".equals(field)) {
            copy.put(field, value); return copy;
        }
        JSONArray assets = copy.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if ("size".equals(field) && !a.optString("name").contains("armeabi-v7a")) continue;
            a.put(field, value);
        }
        return copy;
    }

    /** A release carrying exactly one asset of this name and size. */
    private static JSONObject only(String name, long size) throws JSONException {
        return new JSONObject().put("tag_name", "v2.1.6").put("draft", false).put("prerelease", false)
                .put("assets", new JSONArray().put(new JSONObject().put("name", name).put("size", size)
                        .put("state", "uploaded").put("browser_download_url", "https://github.com/truefedex/tv-bro/releases/download/v2.1.6/" + name)));
    }

    /** redirectAllowed: GitHub's own download hosts only, and B站's source must not redirect at all. */
    private static void redirects() {
        String[] ok = {"https://github.com/truefedex/tv-bro/releases/download/v2.1.6/tvbro-2.1.6-generic-geckoIncluded-armeabi-v7a.apk",
                "https://objects.githubusercontent.com/github-production-release-asset/x",
                "https://release-assets.githubusercontent.com/github-production-release-asset/x"};
        for (String u : ok) check(OfficialApps.redirectAllowed(BRO, u), "TV Bro may follow " + u.substring(8, u.indexOf('/', 8)));
        check(OfficialApps.redirectAllowed(BRO, "HTTPS://GITHUB.COM/truefedex/tv-bro"), "the scheme and host are read case-insensitively");
        check(OfficialApps.redirectAllowed(BRO, "https://github.com:443/x"), "the default https port is the same host");
        String[] no = {"http://github.com/x", "https://evil.example/x", "https://github.com.evil.example/x",
                "https://raw.githubusercontent.com/truefedex/tv-bro/x", "https://user@evil.example/x",
                "https://", "ftp://github.com/x", "https://github.com.evil.example/?x=https://github.com/"};
        for (String u : no) check(!OfficialApps.redirectAllowed(BRO, u), "TV Bro refuses " + u);
        check(!OfficialApps.redirectAllowed(BRO, null) && !OfficialApps.redirectAllowed(null, "https://github.com/x"), "a missing app or URL: no");
        check(!OfficialApps.redirectAllowed(BILI, "https://dl.hdslb.com/x") && !OfficialApps.redirectAllowed(BILI, "https://github.com/x"),
                "B站's own source must not redirect anywhere: only its fixed URL is used");
    }

    /** trusted: the pinned signing keys, and nothing else. */
    private static void keys() {
        for (String c : BILI.certs) check(OfficialApps.trusted(BILI, c), "B站's own signing key " + c.substring(0, 8) + "… is trusted");
        for (String c : BRO.certs) check(OfficialApps.trusted(BRO, c), "TV Bro's own signing key is trusted");
        check(BILI.certs.size() == 2 && BRO.certs.size() == 1, "the keys are exactly the ones the lead checked");
        check(OfficialApps.trusted(BILI, BILI.certs.get(0).toUpperCase(java.util.Locale.ROOT)), "an upper case digest is the same key");
        check(OfficialApps.trusted(BRO, " " + BRO.certs.get(0) + " "), "a digest with spaces around it is the same key");
        check(!OfficialApps.trusted(BILI, BRO.certs.get(0)) && !OfficialApps.trusted(BRO, BILI.certs.get(0)),
                "another app's key does not sign this one");
        check(!OfficialApps.trusted(BRO, "d8634edfe8d94b4fb9a52005d68a090a7f1e85b7f39c2cf01a25dc9dd60942b2"),
                "the digest GitHub prints for an asset is not a signing key");
        check(!OfficialApps.trusted(BILI, "") && !OfficialApps.trusted(BILI, null) && !OfficialApps.trusted(null, BILI.certs.get(0)),
                "an empty or missing digest is never trusted");
    }

    /** installArgsOk: the only text that ever reaches the TV's shell through LocalAdb's fixed install command. */
    private static void arguments() {
        String digest = BRO.certs.get(0);
        check(OfficialApps.installArgsOk("xyz.erapi0neer.xingyi", "xingyi-bilibili-1234567.apk", digest), "星仪's own cache file is accepted");
        check(OfficialApps.installArgsOk("xyz.erapi0neer.xiaoaibridge", "xingyi-tvbro-98765.apk", digest), "so is the bridge's (the agent runs there)");
        check(!OfficialApps.installArgsOk("com.evil.app", "xingyi-tvbro-1.apk", digest), "another package's directory is refused");
        check(!OfficialApps.installArgsOk(null, "xingyi-tvbro-1.apk", digest) && !OfficialApps.installArgsOk("xyz.erapi0neer.xingyi", null, digest)
                && !OfficialApps.installArgsOk("xyz.erapi0neer.xingyi", "xingyi-tvbro-1.apk", null), "a missing argument is refused");
        String[] bad = {"../xingyi-tvbro-1.apk", "xingyi-tvbro-1.apk;rm -rf /", "xingyi-TVbro-1.apk", "other-tvbro-1.apk",
                "xingyi-tvbro-1.apk.sh", "/data/local/tmp/x.apk", "xingyi--1.apk", "xingyi-tvbro-.apk"};
        for (String f : bad) check(!OfficialApps.installArgsOk("xyz.erapi0neer.xingyi", f, digest), "the file name 「" + f + "」 is refused");
        check(!OfficialApps.installArgsOk("xyz.erapi0neer.xingyi", "xingyi-tvbro-1.apk", digest.substring(1)), "a short digest is refused");
        check(!OfficialApps.installArgsOk("xyz.erapi0neer.xingyi", "xingyi-tvbro-1.apk", digest.toUpperCase(java.util.Locale.ROOT)),
                "an upper case digest is refused: the shell compares sha256sum's own lower case");
        check(!OfficialApps.installArgsOk("xyz.erapi0neer.xingyi", "xingyi-tvbro-1.apk", digest.substring(0, 63) + "z"), "a digest that is not hex is refused");
    }
}
