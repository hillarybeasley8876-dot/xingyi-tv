package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** 应用经验 loaded on demand (2026-11-05 audit, 「工具与 Skill」): how to work one app (B站's result page, NetEase's
 * connector, the settings pages) is told to the model only when the request is about that app, instead of in the
 * system prompt of every request. A skill is picked for the first message by the words of the request (and, for a
 * short follow-up or 「继续」, of the request before it) and by the app in front; one the task reaches later (it opens
 * the app, or calls the app's connector) rides in that tool's result. Defined in contract.json → "skills". */
public final class Skills {
    private Skills() { }

    public static final class Skill {
        public final String name, text;
        final List<String> match, packages, tools;
        /** Words that, in the request itself, mean it is not about this app after all (「想听…这个公司的」 is news,
         * not a song); a tool that reaches the app still brings the guide. */
        final List<String> unless;
        Skill(String name, String text, List<String> match, List<String> packages, List<String> tools) {
            this(name, text, match, packages, tools, Collections.<String>emptyList());
        }
        Skill(String name, String text, List<String> match, List<String> packages, List<String> tools, List<String> unless) {
            this.name = name; this.text = text; this.match = match; this.packages = packages; this.tools = tools;
            this.unless = unless;
        }
    }

    /** contract.json's "skills" array; a missing array or broken entry is skipped. */
    public static List<Skill> parse(JSONArray skills) {
        List<Skill> out = new ArrayList<>();
        for (int i = 0; skills != null && i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s == null || s.optString("name").isEmpty() || s.optString("text").isEmpty()) continue;
            out.add(new Skill(s.optString("name"), s.optString("text"), strings(s.optJSONArray("match")),
                    strings(s.optJSONArray("packages")), strings(s.optJSONArray("tools")), strings(s.optJSONArray("unless"))));
        }
        return out;
    }

    /** The skills a request is about: a match word in {@code said} or {@code context} (the request before, when this
     * one is a short follow-up). The app in front no longer counts by itself (2026-11-05 21:00: with B站 in front, a
     * question about a person got B站's guide and was searched inside B站 instead of answered from the web);
     * {@code foreground} is kept for the callers and ignored. A guide for an app the task reaches comes with that tool. */
    public static List<Skill> select(List<Skill> all, String said, String foreground, String context) {
        List<Skill> out = new ArrayList<>();
        String text = lower(said) + "\n" + lower(context), own = lower(said);
        for (Skill s : all) {
            boolean not = false;
            for (String w : s.unless) if (!w.isEmpty() && own.contains(lower(w))) not = true;
            if (not) continue;
            for (String w : s.match) if (!w.isEmpty() && text.contains(lower(w))) { out.add(s); break; }
        }
        return out;
    }

    /** The skill a tool call reaches: the app's own connector, or open_app / open_link naming the app. */
    public static Skill forCall(List<Skill> all, String tool, JSONObject args) {
        String target = args == null ? "" : lower(args.optString("app") + "\n" + args.optString("uri"));
        for (Skill s : all) {
            if (s.tools.contains(tool)) return s;
            if (!"open_app".equals(tool) && !"open_link".equals(tool)) continue;
            for (String p : s.packages) if (!p.isEmpty() && target.contains(lower(p))) return s;
            for (String w : s.match) if (!w.isEmpty() && target.contains(lower(w))) return s;
        }
        return null;
    }

    /** Every skill a tool call reaches (an app can have the contract's guide and what 星仪 learned about it). */
    public static List<Skill> reached(List<Skill> all, String tool, JSONObject args) {
        List<Skill> out = new ArrayList<>();
        String target = args == null ? "" : lower(args.optString("app") + "\n" + args.optString("uri"));
        for (Skill s : all) {
            boolean hit = s.tools.contains(tool);
            if (!hit && ("open_app".equals(tool) || "open_link".equals(tool))) {
                for (String p : s.packages) if (!p.isEmpty() && target.contains(lower(p))) hit = true;
                for (String w : s.match) if (!hit && !w.isEmpty() && target.contains(lower(w))) hit = true;
            }
            if (hit) out.add(s);
        }
        return out;
    }

    /** A short follow-up (「再来一个」「换一首」) or 「继续」 is about the same app as the request before it. */
    public static boolean followUp(String said) {
        String s = said == null ? "" : said.trim();
        return s.length() <= 10 || Replies.resume(s);
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) if (!a.optString(i).trim().isEmpty()) out.add(a.optString(i).trim());
        return out;
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
