package xyz.erapi0neer.xiaoaibridge;

import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The speakable text of an OpenAI Responses API reply (web search answers carry markdown citations). */
final class ResponsesText {
    /** "([sge.com.cn](https://…))" and the full-width variant: dropped with their parentheses. */
    private static final Pattern CITATION = Pattern.compile("[(（]\\s*\\[[^\\]]*\\]\\([^)]*\\)\\s*[)）]");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern MARKUP = Pattern.compile("[*#`]");
    private static final Pattern EMPTY_PARENS = Pattern.compile("[(（]\\s*[)）]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    static String extract(String json) {
        try {
            JSONObject reply = new JSONObject(json);
            String direct = reply.optString("output_text", "");
            StringBuilder text = new StringBuilder(direct.trim());
            JSONArray output = reply.optJSONArray("output");
            if (text.length() == 0 && output != null) {
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    if (item == null || !"message".equals(item.optString("type"))) continue;
                    JSONArray content = item.optJSONArray("content");
                    for (int j = 0; content != null && j < content.length(); j++) {
                        JSONObject part = content.optJSONObject(j);
                        if (part != null && "output_text".equals(part.optString("type"))) text.append(part.optString("text", ""));
                    }
                }
            }
            return clean(text.toString());
        } catch (JSONException | RuntimeException e) {
            return "";
        }
    }

    static boolean usedSearch(String json) {
        try {
            JSONArray output = new JSONObject(json).optJSONArray("output");
            for (int i = 0; output != null && i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item != null && "web_search_call".equals(item.optString("type"))) return true;
            }
        } catch (JSONException | RuntimeException ignored) {
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
