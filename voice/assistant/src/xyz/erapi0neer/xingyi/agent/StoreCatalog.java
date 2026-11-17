package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** The Xiaomi TV app store's catalog (assets/agent/store_apps.json, built by tests/agent/store_catalog.py): what
 * install_app may install from the store, by the name the store gives it or the name people say (爱奇艺 is the store's
 * 「银河奇异果」, 腾讯视频 its 「云视听极光」). A package name is never guessed: an app that is not in here is not
 * installed from the store. */
public final class StoreCatalog {
    public static final class Entry {
        public final String pkg, name;
        final List<String> aliases;
        Entry(String pkg, String name, List<String> aliases) { this.pkg = pkg; this.name = name; this.aliases = aliases; }
        /** The store's name and every alias. */
        public List<String> names() {
            List<String> all = new ArrayList<>();
            all.add(name);
            all.addAll(aliases);
            return all;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public StoreCatalog(String json) throws JSONException {
        JSONArray apps = new JSONObject(json).optJSONArray("apps");
        for (int i = 0; apps != null && i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a == null || a.optString("package").isEmpty() || a.optString("name").isEmpty()) continue;
            List<String> aliases = new ArrayList<>();
            JSONArray al = a.optJSONArray("aliases");
            for (int k = 0; al != null && k < al.length(); k++) if (!al.optString(k).trim().isEmpty()) aliases.add(al.optString(k).trim());
            entries.add(new Entry(a.getString("package"), a.getString("name"), aliases));
        }
    }

    public int size() { return entries.size(); }

    /** The app a name or package means: the package itself, then a name or alias said exactly (spaces and case
     * ignored), then the shortest name or alias that contains it or is contained in it (two characters at least).
     * null when the store has nothing like it. */
    public Entry find(String query) {
        String q = norm(query);
        if (q.isEmpty()) return null;
        for (Entry e : entries) if (e.pkg.equalsIgnoreCase(query.trim())) return e;
        for (Entry e : entries) for (String n : e.names()) if (norm(n).equals(q)) return e;
        if (q.length() < 2) return null; // one letter is part of too many names
        Entry best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Entry e : entries) {
            for (String n : e.names()) {
                String m = norm(n);
                if (m.length() < 2 || !(m.contains(q) || (q.length() >= 2 && q.contains(m)))) continue;
                if (m.length() < bestLen) { best = e; bestLen = m.length(); }
            }
        }
        return best;
    }

    /** Whether the user's own words name this app: then the request itself is the go-ahead to install it. */
    public static boolean named(String said, Entry e) {
        String s = norm(said);
        if (s.isEmpty() || e == null) return false;
        for (String n : e.names()) {
            String m = norm(n);
            if (m.length() >= 2 && s.contains(m)) return true;
        }
        return false;
    }

    /** Lower case, no spaces or middle dots. Nothing else is stripped: dropping 「电视版」 made 「B站电视版」 answer
     * for plain 「B站」, and dropping a final 「tv」 turned CCTV into 「cc」. */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s·・]", "");
    }
}
