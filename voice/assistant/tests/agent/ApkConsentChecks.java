package xyz.erapi0neer.xingyi.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** install-consent (2026-11-07): an app the user names, from the maker's site, installed only after the user hears
 * what it is (ApkConsent: urls, the blocklist, the signer's subject, debug keys, ABIs, the consent sentence). */
final class ApkConsentChecks {
    private ApkConsentChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "apk consent checks threw " + e);
        }
    }

    static void checks() throws Exception {
        urls();
        hosts();
        blocklist();
        subjects();
        debugKeys();
        abis();
        consent();
        expiry();
    }

    /** The real case: 红果短剧's own site, which has no TV store entry. */
    private static final String HONGGUO = "https://www.hongguoduanju.com/download/hongguo-7.4.5.apk";

    /** urlOk: only a plain https link to a real host is ever fetched. */
    private static void urls() {
        check(ApkConsent.urlOk(HONGGUO), "红果短剧's own https download link is fetchable");
        check(ApkConsent.urlOk("https://dl.hdslb.com/mobile/latest/iBiliPlayer-bili.apk"), "so is B站's own link");
        check(ApkConsent.urlOk("HTTPS://WWW.HONGGUODUANJU.COM/a.apk"), "the scheme is read case-insensitively");
        check(!ApkConsent.urlOk("http://www.hongguoduanju.com/a.apk"), "a plain http link is never fetched");
        check(!ApkConsent.urlOk("ftp://www.hongguoduanju.com/a.apk"), "nor an ftp one");
        check(!ApkConsent.urlOk("https://www.hongguoduanju.com@evil.example/a.apk"), "user@host hides the real host: refused");
        check("evil.example".equals(ApkConsent.host("https://evil.example/www.hongguoduanju.com/a.apk")),
                "a maker's name in the path is not the host the bytes come from");
        check(!ApkConsent.urlOk("https://"), "no host at all: refused");
        check(!ApkConsent.urlOk("https:///a.apk"), "an empty host: refused");
        check(!ApkConsent.urlOk("https://localhost/a.apk"), "a bare host name is not a site");
        check(!ApkConsent.urlOk("https://www.hongguoduanju.com/a b.apk"), "a space in the link: refused");
        check(!ApkConsent.urlOk("https://www.hongguoduanju.com/a\nb.apk"), "a newline in the link: refused");
        StringBuilder longUrl = new StringBuilder("https://www.hongguoduanju.com/");
        while (longUrl.length() <= 2000) longUrl.append('a');
        check(!ApkConsent.urlOk(longUrl.toString()), "a link over 2000 characters: refused");
        check(ApkConsent.urlOk(longUrl.substring(0, 2000)), "2000 characters is still a link");
        check(!ApkConsent.urlOk(null) && !ApkConsent.urlOk("") && !ApkConsent.urlOk("   "), "nothing: refused");
    }

    /** host: the site the user will be told the bytes came from. */
    private static void hosts() {
        check("www.hongguoduanju.com".equals(ApkConsent.host(HONGGUO)), "the host of 红果短剧's own download");
        check("www.hongguoduanju.com".equals(ApkConsent.host("https://WWW.HongGuoDuanJu.COM/a.apk")), "the host is lower case");
        check("www.hongguoduanju.com".equals(ApkConsent.host("https://www.hongguoduanju.com:443/a.apk")), "the port is not part of the host");
        check("www.hongguoduanju.com".equals(ApkConsent.host("https://www.hongguoduanju.com")), "a link with no path still has a host");
        check("www.hongguoduanju.com".equals(ApkConsent.host("https://www.hongguoduanju.com?q=1#x")), "nor do the query and fragment matter");
        check("evil.example".equals(ApkConsent.host("https://www.hongguoduanju.com@evil.example/a.apk")),
                "user@ is stripped: the host really fetched is evil.example");
        check("www.hongguoduanju.com".equals(ApkConsent.host("https://www.hongguoduanju.com./a.apk")), "a trailing dot is not part of the host");
        check("".equals(ApkConsent.host("not a url")) && "".equals(ApkConsent.host(null)), "no scheme: no host");
        check("".equals(ApkConsent.host("https://")), "no authority: no host");
    }

    /** blocked: the download sites that re-wrap other people's apps, and free hosting anyone can use. */
    private static void blocklist() {
        String[] sites = {"423down.com", "pc6.com", "uzzf.com", "downkuai.com", "gameggg.com", "beihangsoft.cn",
                "ali213.net", "pp.cn", "wandoujia.com", "3dmgame.com", "duote.com", "onlinedown.net", "cr173.com",
                "crsky.com", "xiazaiba.com", "jb51.net"};
        for (String s : sites) {
            check(ApkConsent.blocked(s), "「" + s + "」 is a download site, never the maker's own");
            check(ApkConsent.blocked("dl." + s), "so is a subdomain of 「" + s + "」");
        }
        check(ApkConsent.blocked("a.b.pc6.com"), "any parent domain counts: a.b.pc6.com");
        check(ApkConsent.blocked("someone.pages.dev") && ApkConsent.blocked("x.y.pages.dev"), "*.pages.dev is free hosting anyone can use");
        check(ApkConsent.blocked("someone.github.io") && ApkConsent.blocked("x.y.github.io"), "so is *.github.io");
        check(ApkConsent.blocked("someone.vercel.app") && ApkConsent.blocked("x.y.vercel.app"), "and *.vercel.app");
        check(ApkConsent.blocked("PC6.COM") && ApkConsent.blocked("dl.PC6.com"), "the blocklist is read case-insensitively");
        check(ApkConsent.blocked("pc6.com."), "a trailing dot does not get past the blocklist");
        check(ApkConsent.blocked("") && ApkConsent.blocked(null), "no host to name to the user: refused");
        check(!ApkConsent.blocked("www.hongguoduanju.com") && !ApkConsent.blocked("hongguoduanju.com"),
                "红果短剧's own site is not blocked");
        check(!ApkConsent.blocked("apk.pp.cn.evil.example"), "a host that merely ends with a blocked name is not that site");
        check(!ApkConsent.blocked("mypc6.com") && !ApkConsent.blocked("notpp.cn"), "nor one that merely contains it");
        check(!ApkConsent.blocked("pages.dev") && !ApkConsent.blocked("github.io"), "the bare free-hosting domains are not subdomains of themselves");
        check(!ApkConsent.blocked("github.com") && !ApkConsent.blocked("dl.hdslb.com"),
                "TV Bro's GitHub and B站's own download host are not blocked");
    }

    /** subject: the O and CN the user is told signed the package. */
    private static void subjects() {
        String[] bytedance = ApkConsent.subject("CN=Beijing ByteDance Technology Co.\\, Ltd, O=Beijing ByteDance Technology Co.\\, Ltd, L=Beijing, C=CN");
        check("Beijing ByteDance Technology Co., Ltd".equals(bytedance[0]), "the O of a real signing certificate, escaped comma and all");
        check("Beijing ByteDance Technology Co., Ltd".equals(bytedance[1]), "and its CN");
        check("Android".equals(ApkConsent.subject("CN=Android Debug, O=Android, C=US")[0])
                && "Android Debug".equals(ApkConsent.subject("CN=Android Debug, O=Android, C=US")[1]),
                "the debug key's O and CN");
        String[] noO = ApkConsent.subject("CN=Pheodor Tsapana, C=UA");
        check("".equals(noO[0]) && "Pheodor Tsapana".equals(noO[1]), "a certificate with only a CN: the O is empty, not missing");
        String[] noCn = ApkConsent.subject("O=Beijing Baimo Liuxiang Technology Co.\\, Ltd.");
        check("Beijing Baimo Liuxiang Technology Co., Ltd.".equals(noCn[0]) && "".equals(noCn[1]), "a certificate with only an O");
        String[] spaced = ApkConsent.subject("CN=Android,OU=Android,O=Android,EMAILADDRESS=android@android.com,C=US");
        check("Android".equals(spaced[0]) && "Android".equals(spaced[1]), "a name printed without spaces parses the same");
        String[] none = ApkConsent.subject("");
        check(none.length == 2 && "".equals(none[0]) && "".equals(none[1]), "an empty name: two empty strings");
        check("".equals(ApkConsent.subject(null)[0]), "no name at all: nothing is invented");
        check("北京笔墨留香科技有限公司".equals(ApkConsent.subject("CN=北京笔墨留香科技有限公司, O=北京笔墨留香科技有限公司")[0]),
                "a Chinese organisation name is read as it is");
    }

    /** debugKey: what never signs a real app. */
    private static void debugKeys() {
        check(ApkConsent.debugKey("CN=Android Debug, O=Android, C=US", ""), "the Android debug key is not a maker's signature");
        check(ApkConsent.debugKey("CN=Android Debug, O=Android, C=US", "1e5124be7e7fcb7a6462b47a42a8567863c6fccc6fe7708cd278be4f43047c75"),
                "even when the digest is somebody else's real key");
        check(ApkConsent.debugKey("CN=android debug,O=android", ""), "however the debug key's name is printed");
        check(ApkConsent.debugKey("CN=Android, OU=Android, O=Android, EMAILADDRESS=android@android.com, C=US", ""),
                "AOSP's test keys are not a maker's signature");
        check(ApkConsent.debugKey("CN=Android,OU=Android,O=Android,C=US", ""), "the same test key without the e-mail address");
        check(ApkConsent.debugKey("CN=Anything At All, O=Anything", ApkConsent.TESTKEY),
                "AOSP's testkey digest is refused whatever the subject says");
        check(ApkConsent.debugKey("CN=Android Debug, O=Android", ApkConsent.TESTKEY.toUpperCase(java.util.Locale.ROOT)),
                "an upper case digest is the same test key");
        check(!ApkConsent.debugKey("CN=Beijing ByteDance Technology Co.\\, Ltd, O=Beijing ByteDance Technology Co.\\, Ltd", ""),
                "a real signing certificate is not a debug key");
        check(!ApkConsent.debugKey("CN=Pheodor Tsapana, O=PhloX Development Team",
                        "1e5124be7e7fcb7a6462b47a42a8567863c6fccc6fe7708cd278be4f43047c75"),
                "TV Bro's own certificate is not a debug key");
        check(!ApkConsent.debugKey("CN=Android Studio, O=Google LLC", ""), "a company whose name starts with Android is not the debug key");
        check(!ApkConsent.debugKey("CN=Android Debugger, O=Android", ""), "nor is a name that merely starts the same");
        check(!ApkConsent.debugKey("CN=Android, OU=Android, O=Android Studio", ""), "an O that is not exactly Android is a real organisation");
        check(!ApkConsent.debugKey(null, null) && !ApkConsent.debugKey("", ""), "nothing at all is not a debug key");
    }

    /** abiOk: the native code in the package has to be something this TV can run. */
    private static void abis() {
        String[] tv = {"armeabi-v7a", "armeabi"};
        List<String> v7a = Arrays.asList("AndroidManifest.xml", "classes.dex", "lib/armeabi-v7a/libread.so");
        List<String> arm64 = Arrays.asList("AndroidManifest.xml", "lib/arm64-v8a/libread.so", "lib/arm64-v8a/libfoo.so");
        List<String> both = Arrays.asList("lib/armeabi-v7a/libread.so", "lib/arm64-v8a/libread.so");
        List<String> none = Arrays.asList("AndroidManifest.xml", "classes.dex", "res/drawable/x.png", "META-INF/CERT.SF");
        check(ApkConsent.abiOk(v7a, tv), "armeabi-v7a native code runs on this TV");
        check(!ApkConsent.abiOk(arm64, tv), "arm64-v8a only: this TV cannot run it, and nothing is installed");
        check(ApkConsent.abiOk(both, tv), "a package with both builds runs: Android picks the armeabi-v7a one");
        check(ApkConsent.abiOk(none, tv), "a package with no native code runs on any TV");
        check(ApkConsent.abiOk(Collections.<String>emptyList(), tv), "an empty archive has no native code");
        check(!ApkConsent.abiOk(v7a, new String[]{"x86_64"}), "an x86_64 TV cannot run this package's native code");
        check(ApkConsent.abiOk(arm64, new String[]{"arm64-v8a", "armeabi-v7a"}), "an arm64 TV runs the arm64 build");
        check(!ApkConsent.abiOk(v7a, new String[0]) && !ApkConsent.abiOk(v7a, null), "an unknown device cannot be said to run it");
        check(ApkConsent.abiOk(none, null), "no native code needs no ABI list");
        check(!ApkConsent.abiOk(Arrays.asList("lib/x86_64/libread.so"), tv), "an x86_64 build is not for this TV");
        check(ApkConsent.abiOk(Arrays.asList("lib/", "lib/armeabi-v7a/"), tv), "directory entries alone are not native code");
        check(ApkConsent.abiOk(Arrays.asList("lib/x86/libread.so", "lib/armeabi-v7a/libread.so"), new String[]{"armeabi-v7a"}),
                "a stray x86 build next to the armeabi-v7a one does not stop it: that is the one Android picks");
        check(!ApkConsent.abiOk(null, tv), "an archive whose entries could not be read is not said to run");
        check(ApkConsent.abiOk(Arrays.asList("res/raw/lib/arm64-v8a/x.so"), tv), "only a real lib/ directory counts as native code");
    }

    /** The facts the prepare step really produced for 红果短剧 (package, version and signer read off the archive). */
    private static JSONObject facts() throws Exception {
        return new JSONObject().put("token", "9f2c1a7b4d6e8035")
                .put("label", "红果免费短剧").put("package", "com.phoenix.read").put("version", "7.4.5")
                .put("size_mb", 259).put("signer_org", "北京笔墨留香科技有限公司")
                .put("signer_cn", "Beijing Baimo Liuxiang Technology Co., Ltd.")
                .put("signer_sha256", "3f94437a010dc70e2340e0c6c27898a95276af5c1382d69a49a8ffc325600ef5")
                .put("host", "www.hongguoduanju.com").put("page_host", "www.hongguoduanju.com");
    }

    /** sentence: the one thing the user hears before anything is installed. */
    private static void consent() throws Exception {
        String s = ApkConsent.sentence(facts());
        check(s.contains("红果免费短剧"), "the sentence names the app the user asked for");
        check(s.contains("com.phoenix.read") && s.contains("7.4.5"), "it names the package and the version really in the file");
        check(s.contains("259 MB"), "and how big the download is");
        check(s.contains("www.hongguoduanju.com"), "and the site the bytes came from");
        check(s.contains("北京笔墨留香科技有限公司"), "and the organisation that signed it");
        check(s.contains("Beijing Baimo Liuxiang Technology Co., Ltd."), "and the name on the certificate");
        check(s.contains("3f94437a010dc70e") && !s.contains("3f94437a010dc70e2340e0c6c27898a95276af5c1382d69a49a8ffc325600ef5"),
                "the fingerprint is short: 64 hex characters would be read out aloud");
        check(s.startsWith("安装「") && !s.contains("好的"),
                "it states the facts; the consent line around it asks for 「好的」, once (2026-11-09: it was read twice)");
        check(s.indexOf("com.phoenix.read") < s.indexOf("www.hongguoduanju.com")
                && s.indexOf("www.hongguoduanju.com") < s.indexOf("北京笔墨留香科技有限公司"),
                "package, then source, then signer: the order the user can follow");
        check(ApkConsent.sentence(facts().put("size_mb", 258.6)).contains("259 MB"), "the size is rounded to whole MB");
        check(ApkConsent.sentence(facts().put("size_mb", 12.2)).contains("12 MB"), "and rounded down when it is under");
        check(ApkConsent.sentence(facts().put("size_mb", 0)).contains("com.phoenix.read 7.4.5）"),
                "a size that could not be read leaves the MB out instead of saying 0");
        JSONObject noOrg = facts().put("signer_org", "");
        check(ApkConsent.sentence(noOrg).contains("开发者签名：Beijing Baimo Liuxiang Technology Co., Ltd."),
                "a certificate without an O falls back to its CN");
        JSONObject noCn = facts().put("signer_cn", "");
        check(ApkConsent.sentence(noCn).contains("开发者签名：北京笔墨留香科技有限公司，签名指纹"),
                "a certificate without a CN says the organisation alone");
        JSONObject noSigner = facts().put("signer_org", "").put("signer_cn", "");
        check(!ApkConsent.sentence(noSigner).contains("开发者签名"), "a certificate that names nobody says nothing rather than guess");
        JSONObject other = facts().put("page_host", "hongguoduanju.com");
        check(ApkConsent.sentence(other).contains("官网页面 hongguoduanju.com"), "the official page it was found on is said too");
        check(!ApkConsent.sentence(facts()).contains("官网页面"), "the page is left out when it is the same site as the download");
        JSONObject noVersion = facts().put("version", "");
        check(ApkConsent.sentence(noVersion).contains("（com.phoenix.read，259 MB）"), "a package with no version name still reads cleanly");
        JSONObject noLabel = facts().put("label", "");
        check(ApkConsent.sentence(noLabel).contains("安装「com.phoenix.read」"), "a package with no readable label falls back to its name");
        check(ApkConsent.sentence(new JSONObject()).contains("这个应用"), "no facts at all: still a sentence, never an exception");
        check(ApkConsent.sentence(null).contains("这个应用"), "nor does a missing result throw");
        JSONObject noHost = facts().put("host", "").put("page_host", "");
        check(!ApkConsent.sentence(noHost).contains("下载"), "a result with no host does not claim a source");
    }

    /** expired: a download waits ten minutes for the user's answer, no longer. */
    private static void expiry() {
        long at = 1_700_000_000_000L;
        check(!ApkConsent.expired(at, at), "a package just downloaded is not expired");
        check(!ApkConsent.expired(at, at + 9 * 60_000L), "nine minutes later it is still there");
        check(!ApkConsent.expired(at, at + ApkConsent.KEEP_MS), "exactly ten minutes: still the user's to accept");
        check(ApkConsent.expired(at, at + 11 * 60_000L), "eleven minutes later it is gone");
        check(ApkConsent.expired(at, at + 24 * 3_600_000L), "and certainly a day later");
        check(ApkConsent.expired(0, at), "a file from before the epoch is expired");
        check(!ApkConsent.expired(at, at - 60_000L), "a clock that moved back does not expire it early");
        check(ApkConsent.KEEP_MS == 600_000L, "the ten minutes the brief asks for");
    }
}

