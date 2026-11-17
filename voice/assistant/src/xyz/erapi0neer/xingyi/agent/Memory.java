package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** Edits the user's profile (files/agent/profile.json) in place: the facts the system prompt carries under 「## 关于用户」,
 * and the aliases that rewrite a misheard name before the model sees the sentence. Pure logic — the TV side only loads
 * and saves the JSONObject. Everything is capped: the file is read into every prompt, and the TV has 2 GB of RAM. */
public final class Memory {
    public static final int MAX_FACTS = 60, MAX_FACT_CHARS = 80, MAX_ALIASES = 40, MAX_ALIAS_CHARS = 20;
    /** Never stored: a fact goes into the prompt and can be spoken back, so anything that unlocks something stays out. */
    private static final Pattern SECRET = Pattern.compile("密码|口令|验证码|支付密码|银行卡|卡号|身份证");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{6,}");

    private Memory() { }

    /** @return added, added:dropped_oldest, updated, duplicate, or rejected:empty / :too_long / :secret */
    public static String addFact(JSONObject profile, String fact) throws JSONException {
        String f = clean(fact);
        if (f.isEmpty()) return "rejected:empty";
        if (f.length() > MAX_FACT_CHARS) return "rejected:too_long";
        if (secret(f)) return "rejected:secret";
        String n = norm(f);
        JSONArray facts = facts(profile);
        for (int i = 0; i < facts.length(); i++) {
            if (norm(facts.optString(i)).equals(n)) return "duplicate";
        }
        for (int i = 0; i < facts.length(); i++) {
            if (!near(n, norm(facts.optString(i)))) continue;
            facts.put(i, f); // keeps the position the user gave it
            return "updated";
        }
        boolean full = facts.length() >= MAX_FACTS;
        if (full) facts.remove(0);
        facts.put(f);
        return full ? "added:dropped_oldest" : "added";
    }

    /** Removes every fact that mentions {@code what} (or is a near-duplicate of it) and every alias whose heard or
     * written form mentions it. @return what went: facts as they were, aliases as 「heard→written」 */
    public static List<String> forget(JSONObject profile, String what) throws JSONException {
        List<String> removed = new ArrayList<>();
        String w = norm(clean(what));
        if (w.isEmpty()) return removed;
        JSONArray facts = profile.optJSONArray("facts");
        if (facts != null) {
            JSONArray kept = new JSONArray();
            for (int i = 0; i < facts.length(); i++) {
                String f = facts.optString(i), n = norm(f);
                if (n.contains(w) || near(n, w)) removed.add(f); else kept.put(f);
            }
            profile.put("facts", kept);
        }
        JSONObject aliases = profile.optJSONObject("aliases");
        if (aliases == null) return removed;
        List<String> gone = new ArrayList<>();
        for (Iterator<String> it = aliases.keys(); it.hasNext(); ) {
            String heard = it.next(), written = aliases.optString(heard);
            if (norm(heard).contains(w) || norm(written).contains(w)) gone.add(heard);
        }
        for (String heard : gone) {
            removed.add(heard + "→" + aliases.optString(heard));
            aliases.remove(heard);
        }
        return removed;
    }

    /** @return added, updated, duplicate, or rejected:empty / :too_long / :same / :full */
    public static String addAlias(JSONObject profile, String heard, String written) throws JSONException {
        String h = clean(heard), w = clean(written);
        if (h.isEmpty() || w.isEmpty()) return "rejected:empty";
        if (h.length() > MAX_ALIAS_CHARS || w.length() > MAX_ALIAS_CHARS) return "rejected:too_long";
        if (norm(h).equals(norm(w))) return "rejected:same";
        JSONObject aliases = aliases(profile);
        if (aliases.has(h)) {
            boolean same = w.equals(aliases.optString(h));
            aliases.put(h, w);
            return same ? "duplicate" : "updated";
        }
        if (aliases.length() >= MAX_ALIASES) return "rejected:full";
        aliases.put(h, w);
        return "added";
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Trimmed, inner whitespace collapsed, a trailing 。 dropped: the recognizer adds it, the user does not mean it. */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ").trim();
        while (t.endsWith("。")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    /** Without spaces or punctuation: two facts are the same when their normalised forms are equal. */
    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isIdeographic(c)) b.append(c);
        }
        return b.toString();
    }

    /** One contains the other, or they are within k edits; k grows with the shorter text. */
    private static boolean near(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.contains(b) || b.contains(a)) return true;
        int k = Math.max(2, Math.min(a.length(), b.length()) / 5);
        return Grounding.distance(a, b, k) <= k;
    }

    private static boolean secret(String f) {
        return SECRET.matcher(f).find() || DIGITS.matcher(f).find();
    }

    private static JSONArray facts(JSONObject profile) throws JSONException {
        JSONArray a = profile.optJSONArray("facts");
        if (a == null) { a = new JSONArray(); profile.put("facts", a); }
        return a;
    }

    private static JSONObject aliases(JSONObject profile) throws JSONException {
        JSONObject a = profile.optJSONObject("aliases");
        if (a == null) { a = new JSONObject(); profile.put("aliases", a); }
        return a;
    }
}
