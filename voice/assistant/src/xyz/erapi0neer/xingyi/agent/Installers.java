package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * find-installer (2026-11-07, 「你点头就装」): the answer of a search for an app's own Android installer. The model is
 * asked for one JSON object (developer, the maker's download page, the https link that downloads the .apk, the package
 * name); this reads it. The links are the whole point, so unlike a web_search answer nothing here is made speakable —
 * it is data for the model, never read out.
 *
 * A link is kept only when {@link ApkConsent#urlOk} says it is an https link to a real host and the host is not one of
 * the download sites {@link ApkConsent#blocked} knows: the search model does sometimes answer with pc6.com or an http
 * link, and those are never the maker's own download. A dropped link is said in the note, so the model can tell the
 * user why it is not installing from there instead of trying again.
 */
public final class Installers {
    /** What the search model is asked for, and what comes back. */
    static final String[] FIELDS = {"app", "developer", "page", "url", "package", "note"};

    private Installers() { }

    /** The day's paid-search allowance: null and the search runs, a sentence refuses it before anything is sent. */
    public interface Cap {
        String take();
    }

    /** What one find_installer search came back with. */
    public static final class Found {
        /** The parsed answer, or null when the model did not answer with a JSON object. */
        public final JSONObject facts;
        /** The daily cap's refusal; nothing was sent when it is set. */
        public final String refusal;
        public final long millis;
        Found(JSONObject facts, String refusal, long millis) {
            this.facts = facts; this.refusal = refusal; this.millis = millis;
        }
        /** Whether a direct link was found and may be fetched. */
        public boolean found() { return facts != null && facts.optBoolean("found"); }
    }

    /**
     * The text of a Responses reply with nothing taken out of it. {@link ResponsesText#extract} is what a spoken
     * answer goes through, and its clean() deletes every address and every asterisk in the text — here the addresses
     * are the answer, so this reads the same fields (the last message only, as there: the search model narrates
     * between its searches) and leaves the text alone.
     */
    public static String text(String json) {
        try {
            JSONObject reply = new JSONObject(json == null ? "" : json);
            String direct = reply.optString("output_text", "").trim();
            if (!direct.isEmpty()) return direct;
            String out = "";
            JSONArray output = reply.optJSONArray("output");
            for (int i = 0; output != null && i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null || !"message".equals(item.optString("type"))) continue;
                StringBuilder one = new StringBuilder();
                JSONArray content = item.optJSONArray("content");
                for (int j = 0; content != null && j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part != null && "output_text".equals(part.optString("type"))) one.append(part.optString("text", ""));
                }
                if (one.toString().trim().length() > 0) out = one.toString();
            }
            return out;
        } catch (Exception e) {
            // Android's JSONException is checked and the desktop one is not: catch both with one clause
            return ""; // not a Responses reply
        }
    }

    /**
     * The search model's answer: everything from the first opening brace to the last closing one (it wraps the object
     * in ```json fences or a sentence now and then), its fields, and {@code found} = a link that may be fetched.
     * Text that is not a JSON object at all gives null.
     */
    public static JSONObject parse(String text) {
        if (text == null) return null;
        int from = text.indexOf('{'), to = text.lastIndexOf('}');
        if (from < 0 || to <= from) return null;
        JSONObject raw;
        try {
            raw = new JSONObject(text.substring(from, to + 1));
        } catch (Exception e) {
            // Android's JSONException is checked and the desktop one is not: catch both with one clause
            return null;
        }
        JSONObject out = new JSONObject();
        try {
            // a JSON null is "null" to Android's optString (the desktop jar gives ""): the search model writes null for
            // what it did not find (2026-11-09 review)
            for (String f : FIELDS) out.put(f, raw.isNull(f) ? "" : raw.optString(f, "").trim());
            String note = out.optString("note");
            String url = out.optString("url"), page = out.optString("page");
            String why = drop(url);
            if (why != null) out.put("url", "");
            String whyPage = drop(page);
            if (whyPage != null) out.put("page", "");
            note = say(note, why);
            note = say(note, whyPage);
            out.put("note", note);
            out.put("found", !out.optString("url").isEmpty());
        } catch (Exception e) {
            return null; // a field org.json will not take: not an answer we can hand on
        }
        return out;
    }

    /** Why this link is not kept — a download site, or not an https link at all — or null when it is kept. */
    private static String drop(String url) {
        if (url == null || url.isEmpty()) return null;
        if (!ApkConsent.urlOk(url)) return "「" + url + "」不是能直接下载的 https 直链，不算";
        String host = ApkConsent.host(url);
        if (ApkConsent.blocked(host)) return "「" + host + "」是下载站，不算";
        return null;
    }

    /** The note with one dropped link added to it. */
    private static String say(String note, String why) {
        if (why == null) return note;
        return note.isEmpty() ? why : note + "；" + why;
    }
}
