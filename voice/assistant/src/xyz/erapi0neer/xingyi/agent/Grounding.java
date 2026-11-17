package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** Keeps search words exactly as the user said them. The model tends to "normalize" rare characters
 * (星辰亻旅行者 became 星辰旅行者 in calibration), so a near-miss of a phrase the user actually said, or that is
 * on screen, is replaced by that phrase. Song titles and artists are left alone: the music catalog already
 * tolerates recognition errors and simplified/traditional variants. */
public final class Grounding {
    static final String[] KEYS = {"keyword", "query", "text"};
    private static final String QUOTES = "《》「」『』“”\"'‘’<>【】";

    private final Map<String, String> aliases;
    private final List<String> heard = new ArrayList<>();
    private final LinkedList<String> seen = new LinkedList<>();

    /** @param aliases spoken form → written form, e.g. 星辰旅行者 → 星辰亻旅行者 */
    public Grounding(Map<String, String> aliases) {
        this.aliases = aliases == null ? Collections.<String, String>emptyMap() : aliases;
    }

    /** Names the recognizer gets wrong in ways speaking more clearly cannot fix: heard form → written form. XiaoAi
     * hears 星辰亻旅行者 as 星辰人旅行者 (2026-11-05: "哔哩哔哩播放星辰人旅行者的MAD" searched the wrong name first).
     * Always on, under the user's own profile.json aliases, which an existing profile file would otherwise hide. */
    static final String[][] BUILTIN = {{"星辰旅行者", "星辰亻旅行者"}, {"星辰人旅行者", "星辰亻旅行者"}};

    /** The built-in aliases with the profile's on top (the profile wins on the same spoken form). */
    public static Map<String, String> withBuiltins(Map<String, String> profile) {
        Map<String, String> out = new HashMap<>();
        for (String[] a : BUILTIN) out.put(a[0], a[1]);
        if (profile != null) out.putAll(profile);
        return out;
    }

    /** Words the user said (this utterance, the task it continues). */
    public synchronized void heard(String text) { if (text != null && !text.isEmpty()) heard.add(text); }

    /** Text from the screen; only the latest few are kept. */
    public synchronized void saw(String text) {
        if (text == null || text.isEmpty()) return;
        seen.addLast(text);
        while (seen.size() > 4) seen.removeFirst();
    }

    /** Rewrites spoken aliases into their written form, longest alias first. */
    public static String canonical(String utterance, Map<String, String> aliases) {
        if (utterance == null || aliases == null || aliases.isEmpty()) return utterance;
        List<String> spoken = new ArrayList<>(aliases.keySet());
        Collections.sort(spoken, (a, b) -> b.length() - a.length());
        String out = utterance;
        for (String s : spoken) {
            String written = aliases.get(s);
            if (s.isEmpty() || written == null || out.contains(written)) continue;
            out = out.replace(s, written);
        }
        return out;
    }

    /** Returns args with grounded values; notes receives one line per change for the tool result. */
    public synchronized JSONObject fix(JSONObject args, List<String> notes) throws JSONException {
        JSONObject out = new JSONObject(args.toString());
        for (String key : KEYS) {
            if (!(out.opt(key) instanceof String)) continue;
            String before = out.getString(key), after = fix(before);
            if (!after.equals(before)) {
                out.put(key, after);
                if (notes != null) notes.add(key + " 已按原话更正：“" + before + "”→“" + after + "”");
            }
        }
        return out;
    }

    synchronized String fix(String value) {
        String v = strip(value);
        if (v.isEmpty()) return value;
        for (String w : aliases.values()) if (w.equals(v)) return v;
        List<String> sources = new ArrayList<>(heard);
        sources.addAll(seen);
        for (String s : sources) if (s.contains(v)) return v;
        int n = v.length(), limit = Math.max(1, n / 5);
        String best = null;
        int bestDistance = Integer.MAX_VALUE, bestGap = Integer.MAX_VALUE;
        for (String s : sources) {
            for (int len = Math.max(1, n - limit); len <= n + limit; len++) {
                for (int i = 0; i + len <= s.length(); i++) {
                    String w = s.substring(i, i + len);
                    if (!clean(w)) continue;
                    int d = distance(v, w, limit);
                    int gap = Math.abs(len - n);
                    if (d < bestDistance || (d == bestDistance && gap < bestGap)) { best = w; bestDistance = d; bestGap = gap; }
                }
            }
        }
        // Accept a close phrase only; anything farther is a deliberate choice of the model.
        return best != null && bestDistance <= limit ? best : v;
    }

    /** A name in its written form (星辰旅行者 → 星辰亻旅行者), by the aliases as for the utterance. Not a near miss of what
     * was said: an UP's name the model wrote right (影视飓风 for a heard 影视飓凤) is not put back to the mishearing. */
    public String written(String value) { return canonical(value, aliases); }

    /**
     * Whether a name comes from the user: this request, the task it continues, the screen, or {@code extra} (the turns
     * before, the list just read out). A fuller form counts (老师好我叫何同学 for 何同学): a third of it, two characters at
     * least, is there. 2026-11-07 on the TV: 「何同学最新的视频是什么」 once went to 星辰亻旅行者, the UP in 关于用户.
     */
    public synchronized boolean said(String value, String extra) {
        String v = letters(strip(value));
        if (v.isEmpty()) return true;
        int k = Math.min(v.length(), Math.max(2, (v.length() + 2) / 3));
        List<String> sources = new ArrayList<>(heard);
        sources.addAll(seen);
        if (extra != null) sources.add(extra);
        for (String s : sources) {
            String n = letters(s);
            for (int i = 0; i + k <= v.length(); i++) if (n.contains(v.substring(i, i + k))) return true;
        }
        return false;
    }

    private static String letters(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) out.append(Character.toLowerCase(s.charAt(i)));
        return out.toString();
    }

    private static String strip(String s) {
        int a = 0, b = s.length();
        while (a < b && (QUOTES.indexOf(s.charAt(a)) >= 0 || Character.isWhitespace(s.charAt(a)))) a++;
        while (b > a && (QUOTES.indexOf(s.charAt(b - 1)) >= 0 || Character.isWhitespace(s.charAt(b - 1)))) b--;
        return s.substring(a, b);
    }

    /** A window must not start or end inside punctuation or spaces, or it would pull in a neighbouring word. */
    private static boolean clean(String w) {
        return !w.isEmpty() && boundary(w.charAt(0)) && boundary(w.charAt(w.length() - 1));
    }
    private static boolean boundary(char c) {
        return Character.isLetterOrDigit(c) || Character.isIdeographic(c) || (c >= 0x2E80 && c <= 0x2FDF);
    }

    /** Levenshtein distance, giving up (returning limit+1) once every path exceeds limit. */
    public static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) return limit + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
