package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** What 星仪 learned about an app by trying it (2026-11-05, 「自己对应用的mcp探索」): 「在哔哩哔哩里，
 * bilibili://history 直接打开观看历史」. Kept in files/agent/app-notes.json as
 * {"apps": {"<pkg>": {"label": …, "at": …, "notes": [{"text": …, "at": …}]}}}; the next time the app comes up they
 * ride in its 应用经验 and in the result of explore_app. Pure: the TV side only loads and saves the JSONObject.
 * Everything is capped — the notes go into a prompt, and the TV has 2 GB of RAM. */
public final class AppNotes {
    public static final int MAX_NOTES = 8, MAX_APPS = 40, MAX_NOTE_CHARS = 120;
    /** Never stored: a note is shown to the model and can be spoken back, so anything that unlocks something stays
     * out — the same rule (and the same patterns) as the user's profile, see {@link Memory}. */
    private static final Pattern SECRET = Pattern.compile("密码|口令|验证码|支付密码|银行卡|卡号|身份证");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{6,}");

    private AppNotes() { }

    /** @return 「记下了：在<label>里，<note>」, or 「已经记着了：<old>」 when this is one the app already has
     * @throws IllegalArgumentException when the note is empty, too long, or a secret */
    public static String add(JSONObject book, String pkg, String label, String note, long now) throws JSONException {
        String text = Memory.clean(note);
        if (text.isEmpty()) throw new IllegalArgumentException("要记的经验是空的");
        if (text.length() > MAX_NOTE_CHARS) throw new IllegalArgumentException("太长了，一句话说清");
        if (SECRET.matcher(text).find() || DIGITS.matcher(text).find()) throw new IllegalArgumentException("这类信息不记");
        String key = key(pkg);
        JSONObject apps = apps(book), app = apps.optJSONObject(key);
        if (app == null) {
            if (apps.length() >= MAX_APPS) dropOldest(apps);
            app = new JSONObject();
            apps.put(key, app);
        }
        if (!clean(label).isEmpty()) app.put("label", clean(label));
        JSONArray notes = array(app);
        String norm = Memory.norm(text);
        for (int i = 0; i < notes.length(); i++) {
            JSONObject old = notes.optJSONObject(i);
            if (old != null && Memory.norm(old.optString("text")).equals(norm)) return "已经记着了：" + old.optString("text");
        }
        if (notes.length() >= MAX_NOTES) notes.remove(0); // the oldest experience of this app goes
        notes.put(new JSONObject().put("text", text).put("at", now));
        app.put("at", now);
        return "记下了：在" + name(app, key) + "里，" + text;
    }

    /** What is known about one app, oldest note first. */
    public static List<String> notes(JSONObject book, String pkg) {
        List<String> out = new ArrayList<>();
        JSONObject app = app(book, pkg);
        JSONArray notes = app == null ? null : app.optJSONArray("notes");
        for (int i = 0; notes != null && i < notes.length(); i++) {
            JSONObject n = notes.optJSONObject(i);
            if (n != null && !n.optString("text").isEmpty()) out.add(n.optString("text"));
        }
        return out;
    }

    /** Drops 「all」, the note at a 1-based number, or every note mentioning a word. @return what went, as it was */
    public static List<String> remove(JSONObject book, String pkg, String which) {
        List<String> gone = new ArrayList<>();
        JSONObject apps = book == null ? null : book.optJSONObject("apps");
        JSONObject app = apps == null ? null : apps.optJSONObject(key(pkg));
        if (app == null) return gone;
        String w = which == null ? "" : which.trim();
        JSONArray kept = new JSONArray(), notes = app.optJSONArray("notes");
        int number = number(w);
        String norm = Memory.norm(w);
        for (int i = 0; notes != null && i < notes.length(); i++) {
            String text = notes.optJSONObject(i) == null ? "" : notes.optJSONObject(i).optString("text");
            boolean drop = w.isEmpty() || "all".equalsIgnoreCase(w) || "全部".equals(w)
                    || (number > 0 && number == i + 1)
                    || (number < 0 && !norm.isEmpty() && Memory.norm(text).contains(norm));
            if (drop) gone.add(text); else kept.put(notes.optJSONObject(i));
        }
        if (kept.length() == 0) apps.remove(key(pkg)); else try { app.put("notes", kept); } catch (JSONException ignored) { }
        return gone;
    }

    /** One 应用经验 per app that has notes, so the model is told what 星仪 found out the next time that app comes up. */
    public static List<Skills.Skill> skills(JSONObject book) {
        List<Skills.Skill> out = new ArrayList<>();
        JSONObject apps = book == null ? null : book.optJSONObject("apps");
        if (apps == null) return out;
        for (String pkg : sorted(apps)) {
            List<String> notes = notes(book, pkg);
            if (notes.isEmpty()) continue;
            String name = name(apps.optJSONObject(pkg), pkg);
            StringBuilder text = new StringBuilder(name + "（星仪自己摸索出来的经验，可能不准）：");
            for (int i = 0; i < notes.size(); i++) text.append(i == 0 ? "" : "；").append(notes.get(i));
            // its own name: B站 also has the contract's guide, and a guide is given once per name in a task
            out.add(new Skills.Skill(name + "·自己摸索的经验", text.toString(), Arrays.asList(name), Arrays.asList(pkg),
                    Collections.<String>emptyList()));
        }
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String key(String pkg) { return pkg == null ? "" : pkg.trim(); }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    private static String name(JSONObject app, String pkg) {
        String label = app == null ? "" : app.optString("label");
        return label.isEmpty() ? pkg : label;
    }

    private static JSONObject apps(JSONObject book) throws JSONException {
        JSONObject apps = book == null ? null : book.optJSONObject("apps");
        if (apps == null) {
            apps = new JSONObject();
            if (book != null) book.put("apps", apps);
        }
        return apps;
    }

    private static JSONObject app(JSONObject book, String pkg) {
        JSONObject apps = book == null ? null : book.optJSONObject("apps");
        return apps == null ? null : apps.optJSONObject(key(pkg));
    }

    private static JSONArray array(JSONObject app) throws JSONException {
        JSONArray notes = app.optJSONArray("notes");
        if (notes == null) {
            notes = new JSONArray();
            app.put("notes", notes);
        }
        return notes;
    }

    /** 40 apps is the cap: the one nobody has added to for the longest time goes. */
    private static void dropOldest(JSONObject apps) {
        String oldest = null;
        long at = Long.MAX_VALUE;
        for (Iterator<String> it = apps.keys(); it.hasNext(); ) {
            String pkg = it.next();
            long seen = apps.optJSONObject(pkg) == null ? 0 : apps.optJSONObject(pkg).optLong("at", 0);
            if (seen <= at) { at = seen; oldest = pkg; }
        }
        if (oldest != null) apps.remove(oldest);
    }

    /** Apps by name, so the prompt they go into is the same text every time. */
    private static List<String> sorted(final JSONObject apps) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = apps.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int n = name(apps.optJSONObject(a), a).compareTo(name(apps.optJSONObject(b), b));
                return n != 0 ? n : a.compareTo(b);
            }
        });
        return keys;
    }

    /** 「3」 → 3, anything else → -1 (a word, not a position). */
    private static int number(String w) {
        if (w.isEmpty() || w.length() > 3) return -1;
        for (int i = 0; i < w.length(); i++) if (w.charAt(i) < '0' || w.charAt(i) > '9') return -1;
        return Integer.parseInt(w);
    }
}
