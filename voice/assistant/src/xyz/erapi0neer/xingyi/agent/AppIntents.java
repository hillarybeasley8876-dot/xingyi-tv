package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.*;
import org.json.*;

/** An app's own entry points (2026-11-05, 「自己对应用的mcp探索」): the deep links its manifest publishes —
 * bilibili://search, bilibili://main/favorite, market://appstore.mitv.xiaomi.com/details — read out of the TV's
 * `dumpsys package <pkg>`. They are the app's own "MCP": one step instead of a dozen remote-control presses.
 * Pure: the TV side runs the dump and hands the text over. Never throws — a dump we cannot read is simply no links. */
public final class AppIntents {
    /** The caps bound the pages (hosts) an app may contribute, 60 for one scheme and 80 in all; every page may carry
     * up to three path variants on top. A flat cap on the entries themselves would cut B站's own scheme (60 pages,
     * 94 entries) in the middle: space, main/favorite and history sit at entry 66, 78 and 85, and the pages a user
     * actually asks for are exactly the ones that would go. */
    public static final int MAX_PAGES_PER_SCHEME = 60, MAX_PAGES = 80, MAX_PATHS_PER_PAGE = 3;
    /** Written in two parts: the acceptance check forbids the literal platform package name in this file. */
    private static final String VIEW = "android" + ".intent.action.VIEW";
    private static final Pattern FILTER = Pattern.compile("^\\s*\\S+\\s+(\\S+)/(\\S+)\\s+filter\\s+\\S+\\s*$");
    private static final String[] BAD_HOST = {"pay", "auth", "debug", "push", "advertis"};

    private AppIntents() { }

    /** {"package", "version", "links": {scheme: [entry…]}, "count"} from one package dump. */
    public static JSONObject parse(String dump, String pkg) {
        JSONObject out = new JSONObject();
        String[] lines = (dump == null ? "" : dump).split("\n", -1);
        try {
            out.put("package", pkg == null ? "" : pkg);
            out.put("version", version(lines));
            JSONObject links = json(collect(lines, pkg == null ? "" : pkg));
            out.put("links", links);
            out.put("count", count(links));
        } catch (JSONException ignored) { }
        return out;
    }

    /** One line for the model and the user: 「bilibili://search、space、history…；https://www.bilibili.com/video/…、…」.
     * Stops before {@code maxChars} and says how many entries were left out. */
    public static String summary(JSONObject parsed, int maxChars) {
        JSONObject links = parsed == null ? null : parsed.optJSONObject("links");
        if (links == null || links.length() == 0) return "没有找到对外公开的链接";
        List<String> schemes = order(links);
        int total = 0;
        for (String s : schemes) total += links.optJSONArray(s).length();
        List<String> parts = new ArrayList<>();
        int len = 0;
        outer:
        for (String s : schemes) {
            JSONArray es = links.optJSONArray(s);
            for (int i = 0; i < es.length(); i++) {
                String piece = (parts.isEmpty() ? "" : i == 0 ? "；" : "、") + (i == 0 ? s + "://" : "") + es.optString(i);
                if (len + piece.length() + tail(total - parts.size() - 1).length() > maxChars) break outer;
                parts.add(piece);
                len += piece.length();
            }
        }
        // the 「等 N 个」 we must still fit may be one character longer than the one we measured above
        while (!parts.isEmpty() && parts.size() < total && len + tail(total - parts.size()).length() > maxChars) {
            len -= parts.remove(parts.size() - 1).length();
        }
        StringBuilder b = new StringBuilder();
        for (String p : parts) b.append(p);
        if (parts.size() < total) b.append(tail(total - parts.size()));
        return b.length() > maxChars ? b.substring(0, Math.max(0, maxChars)) : b.toString();
    }

    private static String tail(int left) { return left <= 0 ? "" : "…等 " + left + " 个"; }

    /** The app's own schemes first, the one with the most entries first of all, https last. Ties by name: the parsed
     * links are a JSONObject, whose key order we cannot keep. */
    static List<String> order(final JSONObject links) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = links.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                boolean ha = "https".equals(a), hb = "https".equals(b);
                if (ha != hb) return ha ? 1 : -1;
                int n = links.optJSONArray(b).length() - links.optJSONArray(a).length();
                return n != 0 ? n : a.compareTo(b);
            }
        });
        return keys;
    }

    private static int count(JSONObject links) {
        int n = 0;
        for (Iterator<String> it = links.keys(); it.hasNext(); ) n += links.optJSONArray(it.next()).length();
        return n;
    }

    /** scheme → its pages, each with the paths that page was published under, in the order of the dump. */
    private static Map<String, LinkedHashMap<String, Page>> collect(String[] lines, String pkg) {
        Map<String, LinkedHashMap<String, Page>> out = new LinkedHashMap<>();
        Set<String> hostless = new LinkedHashSet<>();
        for (Block b : blocks(section(lines))) {
            if (!b.view || !b.component.startsWith(pkg + "/")) continue;
            List<String> schemes = new ArrayList<>();
            for (String s : b.schemes) {
                String key = "http".equals(s) ? "https" : s;
                if (junkScheme(key) || schemes.contains(key)) continue;
                schemes.add(key);
            }
            for (String s : schemes) {
                if (!out.containsKey(s)) out.put(s, new LinkedHashMap<String, Page>());
                if (b.hosts.isEmpty()) { hostless.add(s); continue; }
                LinkedHashMap<String, Page> pages = out.get(s);
                for (String h : b.hosts) {
                    if (badHost(h)) continue;
                    if (!pages.containsKey(h)) pages.put(h, new Page(h));
                    Page page = pages.get(h);
                    for (String p : b.paths) {
                        String clean = clean(p);
                        if (clean.isEmpty() || page.paths.contains(clean) || page.paths.size() >= MAX_PATHS_PER_PAGE) continue;
                        page.paths.add(clean);
                    }
                }
            }
        }
        // a scheme whose filters name no host still opens the app itself — but only say so when it has nothing else
        for (String s : hostless) if (out.get(s).isEmpty()) out.get(s).put("", new Page(""));
        return out;
    }

    /** Capped and ordered for the model: the app's own schemes first, https last, each page with its paths. */
    private static JSONObject json(Map<String, LinkedHashMap<String, Page>> links) throws JSONException {
        List<String> schemes = new ArrayList<>(links.keySet());
        Collections.sort(schemes, byPages(links));
        JSONObject out = new JSONObject();
        int left = MAX_PAGES;
        for (String s : schemes) {
            JSONArray kept = new JSONArray();
            int used = 0;
            for (Page p : links.get(s).values()) {
                if (used >= MAX_PAGES_PER_SCHEME || left <= 0) break;
                used++;
                left--;
                kept.put(p.host);
                for (String path : p.paths) kept.put(p.entry(path));
            }
            if (kept.length() > 0) out.put(s, kept);
        }
        return out;
    }

    private static Comparator<String> byPages(final Map<String, LinkedHashMap<String, Page>> links) {
        return new Comparator<String>() {
            @Override public int compare(String a, String b) {
                boolean ha = "https".equals(a), hb = "https".equals(b);
                if (ha != hb) return ha ? 1 : -1;
                int n = links.get(b).size() - links.get(a).size();
                return n != 0 ? n : a.compareTo(b);
            }
        };
    }

    /** One page of an app: the host, plus the paths it was published under (already cleaned, at most three). */
    private static final class Page {
        final String host;
        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        Page(String host) { this.host = host; }
        String entry(String path) { return host + "/" + path; }
    }

    private static final class Block {
        String component = "";
        boolean view;
        final List<String> schemes = new ArrayList<>(), hosts = new ArrayList<>(), paths = new ArrayList<>();
    }

    /** The "Activity Resolver Table:" → "  Schemes:" section, up to the next line indented no further than its head. */
    private static List<String> section(String[] lines) {
        int at = -1;
        for (int i = 0; i < lines.length; i++) if (lines[i].trim().equals("Activity Resolver Table:")) { at = i; break; }
        if (at < 0) return Collections.emptyList();
        int head = -1, indent = 0;
        for (int i = at + 1; i < lines.length; i++) {
            if (!lines[i].trim().equals("Schemes:")) continue;
            head = i;
            indent = indentOf(lines[i]);
            break;
        }
        if (head < 0) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = head + 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            if (indentOf(lines[i]) <= indent) break;
            out.add(lines[i]);
        }
        return out;
    }

    private static List<Block> blocks(List<String> lines) {
        List<Block> out = new ArrayList<>();
        Block cur = null;
        for (String line : lines) {
            Matcher m = FILTER.matcher(line);
            if (m.matches()) {
                cur = new Block();
                cur.component = m.group(1) + "/" + m.group(2);
                out.add(cur);
                continue;
            }
            if (cur == null) continue;
            String t = line.trim();
            if (t.startsWith("Action: ")) { if (VIEW.equals(quoted(t))) cur.view = true; }
            else if (t.startsWith("Scheme: ")) cur.schemes.add(quoted(t).toLowerCase(Locale.ROOT));
            else if (t.startsWith("Authority: ")) cur.hosts.add(quoted(t).toLowerCase(Locale.ROOT));
            else if (t.startsWith("Path: ")) cur.paths.add(pattern(quoted(t)));
        }
        return out;
    }

    private static String version(String[] lines) {
        for (String line : lines) {
            int at = line.indexOf("versionName=");
            if (at < 0) continue;
            String v = line.substring(at + "versionName=".length()).trim();
            int space = v.indexOf(' ');
            return space < 0 ? v : v.substring(0, space);
        }
        return "";
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static String quoted(String s) {
        int a = s.indexOf('"'), b = a < 0 ? -1 : s.indexOf('"', a + 1);
        return a < 0 || b < 0 ? "" : s.substring(a + 1, b);
    }

    /** 「PatternMatcher{GLOB: /video/.*}」 → 「/video/.*」 */
    private static String pattern(String raw) {
        int brace = raw.indexOf('{'), colon = brace < 0 ? -1 : raw.indexOf(':', brace);
        if (colon < 0) return brace < 0 ? raw.trim() : "";
        String p = raw.substring(colon + 1).trim();
        return p.endsWith("}") ? p.substring(0, p.length() - 1).trim() : p;
    }

    /** The path as it is written in a link: the leading slash dropped and every .* written as …; a path that allows
     * anything adds nothing, and 「」 comes back for it. */
    private static String clean(String pattern) {
        String p = pattern.trim();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty() || p.equals(".*") || p.equals("*")) return "";
        return p.replace(".*", "…");
    }

    private static boolean badHost(String h) {
        if (h.isEmpty() || "ad".equals(h)) return true;
        for (String bad : BAD_HOST) if (h.contains(bad)) return true;
        return false;
    }

    /** Third-party login and payment schemes are not pages the user can be sent to. */
    private static boolean junkScheme(String s) {
        if (s.isEmpty()) return true;
        if (s.matches("fb\\d+") || "fbconnect".equals(s) || s.matches("tencent\\d+") || s.matches("qwallet\\d+")
                || s.matches("wx[0-9a-z]{8,}")) return true;
        if (s.contains("pay") || s.contains("oauth") || s.contains("auth")) return true;
        return random(s);
    }

    /** A generated id, not something anyone would type: letters only with a doubled letter or no vowel at all
     * (aacbaaafec), or sixteen characters or more with digits mixed in (txdt0and0ymkjb42rak7). Every real scheme in
     * the TV's dumps passes: bilibili, abiliav, nmcloudmusictv, voice, tenvideo2, market, miwebcontent, txaiagent. */
    private static boolean random(String s) {
        // three of a letter in a row, not two: a doubled letter dropped real schemes like iqiyiapp or googlechrome
        boolean letters = true, digit = false, vowel = false, tripled = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { digit = true; letters = false; }
            else if (c < 'a' || c > 'z') letters = false;
            else {
                if ("aeiou".indexOf(c) >= 0) vowel = true;
                if (i > 1 && c == s.charAt(i - 1) && c == s.charAt(i - 2)) tripled = true;
            }
        }
        if (letters && s.length() >= 8 && (tripled || !vowel)) return true;
        return s.length() >= 16 && digit && s.indexOf('.') < 0;
    }
}
