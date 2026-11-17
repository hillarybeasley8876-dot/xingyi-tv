package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * The pure half of 「你点头就装」 (2026-11-07): an app the user names may be fetched from the maker's own site, but only
 * after the user has heard where it came from and who signed it. Everything here is a decision the PC checks can test
 * — the URL, the download sites that are never a maker, the signer's name, the keys that are not a maker's, whether the
 * native code in the package can run on this TV, and the one sentence the user is asked to agree to. The Android side
 * (ConsentInstaller) only downloads and installs.
 */
public final class ApkConsent {
    /** How long a downloaded package waits for the user's 「好的」. */
    public static final long KEEP_MS = 10 * 60_000L;
    /** The largest package ever fetched this way. */
    public static final long LIMIT = 400L * 1024 * 1024;
    /** AOSP's shared testkey, from the AOSP tree itself (build/target/product/security/testkey). */
    static final String TESTKEY = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc";

    /** Download sites and 软件站 that re-sign or re-wrap other people's apps; a link on one of these is never the
     * maker's own download, whatever the page calls itself. A floor, not the check: the user hearing the host is. */
    private static final List<String> BLOCKED = Collections.unmodifiableList(Arrays.asList(
            "423down.com", "pc6.com", "uzzf.com", "downkuai.com", "gameggg.com", "beihangsoft.cn", "ali213.net",
            "pp.cn", "wandoujia.com", "3dmgame.com", "duote.com", "onlinedown.net", "cr173.com", "crsky.com",
            "xiazaiba.com", "jb51.net"));
    /** Free hosting anyone can put an APK on: a page there is nobody's official site. */
    private static final List<String> FREE_HOSTING = Collections.unmodifiableList(Arrays.asList(
            ".pages.dev", ".github.io", ".vercel.app"));

    private ApkConsent() { }

    /** Whether a download link may be fetched at all: https, a real host, nobody's user@ in front of it. */
    public static boolean urlOk(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty() || u.length() > 2000 || !u.regionMatches(true, 0, "https://", 0, 8)) return false;
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c <= ' ' || c == 0x7f) return false; // a space or control character is never in a real link
        }
        String rest = u.substring(8);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String authority = rest.substring(0, end);
        if (authority.isEmpty() || authority.indexOf('@') >= 0) return false; // user@host hides the real host
        String h = stripPort(authority);
        if (h.isEmpty() || h.indexOf('.') < 0 || h.startsWith(".") || h.endsWith(".")
                || h.startsWith("-") || h.endsWith("-")) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            boolean name = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.';
            if (!name) return false;
        }
        return true;
    }

    /** The host a link really goes to: lower case, no port, no user@; "" when there is none. */
    public static String host(String url) {
        if (url == null) return "";
        String u = url.trim();
        int scheme = u.indexOf("://");
        if (scheme < 0) return "";
        String rest = u.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String authority = rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        String h = stripPort(authority).toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static String stripPort(String authority) {
        int colon = authority.lastIndexOf(':');
        if (colon < 0) return authority;
        String port = authority.substring(colon + 1);
        if (port.isEmpty()) return authority;
        for (int i = 0; i < port.length(); i++) if (port.charAt(i) < '0' || port.charAt(i) > '9') return authority;
        return authority.substring(0, colon);
    }

    /** Whether a host is one of the download sites above, any of their subdomains, or free hosting. */
    public static boolean blocked(String host) {
        if (host == null) return true;
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return true; // nothing to say to the user: never fetched
        for (String s : FREE_HOSTING) if (h.endsWith(s)) return true;
        for (String b : BLOCKED) if (h.equals(b) || h.endsWith("." + b)) return true;
        return false;
    }

    /** The signer's organisation and common name, {O, CN}, either "" when the certificate does not carry it. */
    public static String[] subject(String dn) {
        Map<String, String> a = attributes(dn);
        String o = a.containsKey("O") ? a.get("O") : "", cn = a.containsKey("CN") ? a.get("CN") : "";
        return new String[]{o, cn};
    }

    /**
     * Whether a certificate is not a maker's own: the Android debug key every SDK install signs with, or one of AOSP's
     * shared test keys (the subjects catch those however they are printed; the digest catches a re-packaged testkey).
     */
    public static boolean debugKey(String dn, String sha256) {
        String digest = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        if (TESTKEY.equals(digest)) return true;
        Map<String, String> a = attributes(dn);
        String cn = a.containsKey("CN") ? a.get("CN") : "", o = a.containsKey("O") ? a.get("O") : "";
        String ou = a.containsKey("OU") ? a.get("OU") : "";
        // CN=Android Debug, O=Android — the key `debug.keystore` carries; O is sometimes left out when printed.
        if ("Android Debug".equalsIgnoreCase(cn) && (o.isEmpty() || "Android".equalsIgnoreCase(o))) return true;
        // CN=Android, OU=Android, O=Android (+/- EMAILADDRESS=android@android.com): AOSP's platform/test keys.
        return "Android".equalsIgnoreCase(cn) && "Android".equalsIgnoreCase(o) && "Android".equalsIgnoreCase(ou);
    }

    /** Every attribute of an RFC 2253 / X.500 name, keys upper case, in the order they appear. */
    private static Map<String, String> attributes(String dn) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dn == null) return out;
        for (String part : parts(dn)) {
            int eq = -1;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) == '\\') { i++; continue; }
                if (part.charAt(i) == '=') { eq = i; break; }
            }
            if (eq <= 0) continue;
            String key = unescape(part.substring(0, eq)).trim().toUpperCase(Locale.ROOT);
            String value = unescape(part.substring(eq + 1)).trim();
            if (!key.isEmpty() && !out.containsKey(key)) out.put(key, value);
        }
        return out;
    }

    /** The name's components, split on commas that are not escaped as \, */
    private static List<String> parts(String dn) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (c == '\\' && i + 1 < dn.length()) { cur.append(c).append(dn.charAt(++i)); continue; }
            if (c == ',') { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { b.append(s.charAt(++i)); continue; }
            b.append(c);
        }
        return b.toString();
    }

    /** Whether this TV can run the native code in the package: it ships none, or one of its lib/ ABIs is one of ours.
     * A missing entry list is not "no native code" — it is an archive that could not be read, so it is not installed. */
    public static boolean abiOk(List<String> zipEntries, String[] deviceAbis) {
        if (zipEntries == null) return false;
        Set<String> libs = new LinkedHashSet<>();
        for (String e : zipEntries) {
            if (e == null || !e.startsWith("lib/")) continue;
            String rest = e.substring(4);
            int slash = rest.indexOf('/');
            if (slash <= 0 || slash == rest.length() - 1) continue;
            libs.add(rest.substring(0, slash));
        }
        if (libs.isEmpty()) return true;
        if (deviceAbis == null || deviceAbis.length == 0) return false;
        Set<String> have = new HashSet<>(Arrays.asList(deviceAbis));
        for (String abi : libs) if (have.contains(abi)) return true;
        return false;
    }

    /** Older than the ten minutes a download waits for the user's answer. */
    public static boolean expired(long preparedAt, long now) {
        return now - preparedAt > KEEP_MS;
    }

    /**
     * The one sentence the user hears and sees before anything is installed: what it is, which package and version,
     * how big, where the bytes came from and who signed them. The full certificate digest is left out on purpose —
     * it would be read out aloud; the short fingerprint is enough to recognise the same signer twice.
     */
    public static String sentence(JSONObject facts) {
        JSONObject f = facts == null ? new JSONObject() : facts;
        String label = f.optString("label").trim(), pkg = f.optString("package").trim();
        String version = f.optString("version").trim(), host = f.optString("host").trim();
        String page = f.optString("page_host").trim(), org = f.optString("signer_org").trim();
        String cn = f.optString("signer_cn").trim();
        String digest = f.optString("signer_sha256").trim().toLowerCase(Locale.ROOT);
        if (label.isEmpty()) label = pkg.isEmpty() ? "这个应用" : pkg;
        StringBuilder s = new StringBuilder("安装「").append(label).append("」（").append(pkg.isEmpty() ? label : pkg);
        if (!version.isEmpty()) s.append(' ').append(version);
        long mb = Math.round(f.optDouble("size_mb", 0));
        if (mb > 0) s.append('，').append(mb).append(" MB");
        s.append('）');
        if (!host.isEmpty()) {
            s.append("：从 ").append(host).append(" 下载");
            if (!page.isEmpty() && !page.equals(host)) s.append("（官网页面 ").append(page).append("）");
        }
        // a certificate without an O still names its signer in the CN: say that rather than nothing
        String signer = org.isEmpty() ? cn : org;
        if (!signer.isEmpty()) {
            s.append("，开发者签名：").append(signer);
            if (!org.isEmpty() && !cn.isEmpty() && !cn.equals(org)) s.append("（").append(cn).append("）");
        }
        if (digest.length() >= 16) s.append("，签名指纹 ").append(digest, 0, 16).append('…');
        // the consent line around it asks for 「好的」 (AgentEngine: 「需要你确认：…。可以就说“好的”，不做就说“算了”。」): asking here
        // too read it out twice (2026-11-09 review)
        return s.toString();
    }
}
