package xyz.erapi0neer.xingyi.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The apps install_app may fetch from their own maker's channel, with the source and the signing key the lead checked
 * on 2026-11-07 (briefs/official-installs.md). Nothing else is ever downloaded: a package name is never guessed, a
 * mirror is never used, and a download whose SHA-256 certificate is not one of these is refused. Everything here is a
 * pure decision so the PC checks can test it; the Android side (OfficialAppInstaller, LocalAdb) only does the I/O. */
public final class OfficialApps {

    /** One vetted app: where its own maker publishes it, what it may be called, and which signing key it must carry. */
    public static final class App {
        public final String key, name, pkg;
        public final List<String> names, certs;
        public final long limit;
        /** Where the bytes come from, said to the user: 哔哩哔哩官方网站 / TV Bro 在 GitHub 的官方发布页. */
        public final String source;
        /** The fixed download URL, or the GitHub releases API when {@link #repo} is set. */
        public final String url, referer, repo;
        /** The release asset to take: {@code <prefix><version><infix><abi>.apk}. */
        public final String assetPrefix, assetInfix;
        /** The only hosts a redirect may lead to; empty means this source must not redirect at all. */
        public final List<String> hosts;
        /** Roughly how big the download is, for the confirmation question. */
        public final int mb;
        /** The Xiaomi TV store ships a TV build of this app (云视听小电视 = B站电视版 / 哔哩哔哩TV): a request that says
         * 电视版 or TV means that app, not the phone client this source publishes. */
        public final boolean tvBuildInStore;

        App(String key, String name, String pkg, List<String> names, List<String> certs, long limit, String source,
                String url, String referer, String repo, String assetPrefix, String assetInfix, List<String> hosts,
                int mb, boolean tvBuildInStore) {
            this.key = key; this.name = name; this.pkg = pkg;
            this.names = Collections.unmodifiableList(names); this.certs = Collections.unmodifiableList(certs);
            this.limit = limit; this.source = source; this.url = url; this.referer = referer; this.repo = repo;
            this.assetPrefix = assetPrefix; this.assetInfix = assetInfix;
            this.hosts = Collections.unmodifiableList(hosts); this.mb = mb; this.tvBuildInStore = tvBuildInStore;
        }
    }

    // Bilibili publishes these app signing identities at https://b23.tv/.well-known/assetlinks.json.
    // TV Bro's is CN=Pheodor Tsapana, PhloX Development Team, read from the official v2.1.6 release.
    public static final App BILIBILI = new App("bilibili", "哔哩哔哩", "tv.danmaku.bili",
            Arrays.asList("哔哩哔哩", "B站", "b站", "bilibili", "哔哩", "bili"),
            Arrays.asList("93ba270f5521139ecafe4bb638ac5b1198bc548f62d9fd8f8580a079faf5910e",
                    "3f94437a010dc70e2340e0c6c27898a95276af5c1382d69a49a8ffc325600ef5"),
            384L * 1024 * 1024, "哔哩哔哩官方网站", "https://dl.hdslb.com/mobile/latest/iBiliPlayer-bili.apk",
            "https://app.bilibili.com/", null, null, null, Collections.<String>emptyList(), 100, true);

    /** TV Bro, the open-source browser made for TV remotes; the geckoIncluded build carries a current Firefox engine
     * (the TV's own WebView is Chrome 66 from 2018 and cannot open today's sites). */
    public static final App TVBRO = new App("tvbro", "TV Bro", "com.phlox.tvwebbrowser",
            Arrays.asList("浏览器", "电视浏览器", "网页浏览器", "上网", "TV Bro", "TVBro", "tvbro"),
            Arrays.asList("1e5124be7e7fcb7a6462b47a42a8567863c6fccc6fe7708cd278be4f43047c75"),
            256L * 1024 * 1024, "TV Bro 在 GitHub 的官方发布页",
            "https://api.github.com/repos/truefedex/tv-bro/releases/latest", null, "truefedex/tv-bro",
            "tvbro-", "-generic-geckoIncluded-",
            Arrays.asList("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"), 150, false);

    public static final List<App> ALL = Collections.unmodifiableList(Arrays.asList(BILIBILI, TVBRO));
    /** The ABIs the vendor publishes a build for, in the order they are preferred. */
    static final List<String> ABIS = Arrays.asList("armeabi-v7a", "arm64-v8a", "x86_64");
    /** The two package directories the agent runtime runs under (星仪 itself, and the XiaoAi bridge that ships it). */
    static final List<String> PKG_DIRS = Arrays.asList("xyz.erapi0neer.xingyi", "xyz.erapi0neer.xiaoaibridge");

    /** 「上网」 is a plain verb: it means the browser when the request is exactly that, never inside a longer one
     * (「上网课」 is a lesson, not a browser, and must not pull 150 MB down). */
    private static final List<String> EXACT_ONLY = Arrays.asList("上网");

    /** 「浏览器」 after a brand (「Via浏览器」「夸克浏览器」「谷歌浏览器」「360浏览器」): another browser, not TV Bro. After a word
     * that only asks for one (「装个浏览器」「下载浏览器」「电视浏览器」「好用的浏览器」) it still is (2026-11-09 review: every
     * 「X浏览器」 resolved to TV Bro, was installed without a question or answered 「已经装好了」). */
    private static final Pattern BRANDED_BROWSER = Pattern.compile("(?<=[a-z0-9\\u4e00-\\u9fa5])(?<![个款装载的要想用开找视页])浏览器");

    /** A browser's generic name, out of play when the words name another browser. */
    private static boolean otherBrowser(String name, String words) {
        return name.contains("浏览器") && BRANDED_BROWSER.matcher(words).find();
    }

    private OfficialApps() { }

    public static App byKey(String key) {
        for (App a : ALL) if (a.key.equals(key)) return a;
        return null;
    }

    /** The vetted app a request means, by StoreCatalog's rules: the package itself, then a name said exactly (spaces
     * and case ignored), then the shortest name that contains the query or is contained in it. null when the request
     * means no vetted app — then install_app falls back to the Xiaomi TV store's own catalog. */
    public static App find(String asked) {
        String q = StoreCatalog.norm(asked);
        if (q.isEmpty()) return null;
        for (App a : ALL) if (a.pkg.equalsIgnoreCase(asked.trim())) return a;
        for (App a : ALL) for (String n : a.names) if (StoreCatalog.norm(n).equals(q)) return a;
        if (q.length() < 2) return null; // one letter is part of too many names
        App best = null;
        int bestLen = Integer.MAX_VALUE;
        for (App a : ALL) {
            // 「B站电视版」「哔哩哔哩TV」 is the store's TV build of the same app, not what this source publishes.
            if (a.tvBuildInStore && (q.contains("电视版") || q.contains("tv"))) continue;
            for (String n : a.names) {
                String m = StoreCatalog.norm(n);
                if (m.length() < 2 || EXACT_ONLY.contains(m) || otherBrowser(m, q) || !(m.contains(q) || q.contains(m))) continue;
                if (m.length() < bestLen) { best = a; bestLen = m.length(); }
            }
        }
        return best;
    }

    /** Whether the user's own words name this app: then the request itself is the go-ahead to install it. */
    public static boolean named(String utterance, App app) {
        String s = StoreCatalog.norm(utterance);
        if (s.isEmpty() || app == null) return false;
        for (String n : app.names) {
            String m = StoreCatalog.norm(n);
            if (m.length() >= 2 && !EXACT_ONLY.contains(m) && !otherBrowser(m, s) && s.contains(m)) return true;
        }
        return false;
    }

    /** From GitHub's release JSON: the download URL of the asset built for the device's first supported ABI, or null
     * (a draft or pre-release, no asset for any of the device's ABIs, or one over the size limit). */
    public static String githubAsset(JSONObject release, String[] abis, App app) throws JSONException {
        if (release == null || app == null || abis == null) return null;
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
        String tag = release.optString("tag_name").trim();
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        if (version.isEmpty()) return null;
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (String abi : abis) {
            if (abi == null || !ABIS.contains(abi)) continue;
            String want = app.assetPrefix + version + app.assetInfix + abi + ".apk";
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null || !want.equals(a.optString("name"))) continue;
                String state = a.optString("state");
                long size = a.optLong("size", -1);
                String url = a.optString("browser_download_url");
                // The device's own build is the one to take: if it is unusable, say so instead of offering another ABI.
                if ((!state.isEmpty() && !"uploaded".equals(state)) || size < 1 || size > app.limit) return null;
                return redirectAllowed(app, url) ? url : null;
            }
        }
        return null;
    }

    /** Whether a redirect may be followed for this app: https, and a host the vendor's own download uses. */
    public static boolean redirectAllowed(App app, String url) {
        if (app == null || url == null || app.hosts.isEmpty()) return false;
        String u = url.trim();
        if (!u.regionMatches(true, 0, "https://", 0, 8)) return false;
        String rest = u.substring(8);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String host = rest.substring(0, end);
        int at = host.lastIndexOf('@'); // user@host is never the vendor's host
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.lastIndexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return app.hosts.contains(host.toLowerCase(Locale.ROOT));
    }

    /** Whether a certificate digest is one of the app's own signing keys. */
    public static boolean trusted(App app, String sha256hex) {
        if (app == null || sha256hex == null) return false;
        String d = sha256hex.trim().toLowerCase(Locale.ROOT);
        for (String c : app.certs) if (c.equals(d)) return true;
        return false;
    }

    /** The three values LocalAdb puts into its fixed install command; anything else is refused before the shell sees
     * it, so no caller-supplied text ever reaches the TV's shell. */
    public static boolean installArgsOk(String pkgDir, String filename, String digest) {
        return pkgDir != null && PKG_DIRS.contains(pkgDir)
                && filename != null && filename.matches("xingyi-[a-z]+-[A-Za-z0-9_-]+[.]apk")
                && digest != null && digest.matches("[0-9a-f]{64}");
    }
}
