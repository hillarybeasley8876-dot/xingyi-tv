package xyz.erapi0neer.xingyi.agent;

import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** The speakable text of an OpenAI Responses API reply (web search answers carry markdown citations). */
public final class ResponsesText {
    /** "([sge.com.cn](https://…))" and the full-width variant: dropped with their parentheses. */
    private static final Pattern CITATION = Pattern.compile("[(（]\\s*\\[[^\\]]*\\]\\([^)]*\\)\\s*[)）]");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern MARKUP = Pattern.compile("[*#`]");
    private static final Pattern EMPTY_PARENS = Pattern.compile("[(（]\\s*[)）]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public static String extract(String json) {
        try {
            JSONObject reply = new JSONObject(json);
            String direct = reply.optString("output_text", "");
            StringBuilder text = new StringBuilder(direct.trim());
            JSONArray output = reply.optJSONArray("output");
            if (text.length() == 0 && output != null) {
                // The last message only: Grok narrates between its searches (「我来查一下…」「换个方式再查一次」) in
                // messages of their own, and only the last one is the answer (2026-11-06).
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    if (item == null || !"message".equals(item.optString("type"))) continue;
                    StringBuilder one = new StringBuilder();
                    JSONArray content = item.optJSONArray("content");
                    for (int j = 0; content != null && j < content.length(); j++) {
                        JSONObject part = content.optJSONObject(j);
                        if (part != null && "output_text".equals(part.optString("type"))) one.append(part.optString("text", ""));
                    }
                    if (one.toString().trim().length() > 0) { text.setLength(0); text.append(one); }
                }
            }
            return clean(text.toString());
        } catch (Exception e) {
            // Android's JSONException is checked, the desktop one is not: catch both with one clause
            return "";
        }
    }

    public static boolean usedSearch(String json) {
        try {
            JSONArray output = new JSONObject(json).optJSONArray("output");
            for (int i = 0; output != null && i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) continue;
                if ("web_search_call".equals(item.optString("type"))) return true;
                // Grok's X search: x_keyword_search, x_semantic_search, x_user_search…
                if ("custom_tool_call".equals(item.optString("type")) && item.optString("name").startsWith("x_")) return true;
            }
        } catch (Exception ignored) {
            // not a Responses reply
        }
        return false;
    }

    static String clean(String s) {
        String t = CITATION.matcher(s).replaceAll("");
        t = LINK.matcher(t).replaceAll("$1");
        t = URL.matcher(t).replaceAll("");
        t = MARKUP.matcher(t).replaceAll("");
        t = EMPTY_PARENS.matcher(t).replaceAll("");
        return SPACES.matcher(t).replaceAll(" ").trim();
    }

    private ResponsesText() { }
}
