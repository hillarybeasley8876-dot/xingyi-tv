package xyz.erapi0neer.xingyi.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Live information for the agent: one Responses API call with the web_search tool, answered in speakable Chinese.
 * Model and instructions chosen by tests/agent/search_eval.py (2026-11-05, six questions checked by hand against
 * nobelprize.org, the ECB rate, open-meteo and ESPN): gpt-5.4-mini answered 4/6 (「诺贝尔文学奖还没公布」 a month after it
 * was, 「尼克斯今天没比赛」 and once an invented score), gpt-5.4 with these instructions 12/12 in two runs at 8.8 s median;
 * gpt-5.6 was right too but took 38 s. Since its answer is spoken as it comes (web_search final=true), point 3 asks for
 * 80 characters at most: 6/6 again, answers 116 characters median with the links (v2: 217) at the same speed — and a
 * ranking shrank to three names (2026-11-06), so a list of several now gets its first five, 200 characters at most.
 *
 * X (Twitter): the web search cannot read X, and 「Tibo 推特上关于重置的消息」 got an August post when the newest was
 * 「Day 28/ Banked 2/2」 of 11-02 (2026-11-06). {@link #forX} asks Grok with xAI's own X search instead
 * (tests/agent/x_search_probe.py): grok-4.7 with x_search + web_search was right 4/4 in 37–63 s, about 0.3 USD a
 * question; grok-4.5 2/4, the fast Grok models 0/4.
 *
 * 2026-11-16: tried on claude-opus-5-5 for a few hours and reverted — the user wants the TV's harness on
 * MiniMax-M3.1-Flash, and armeabi opus needed {@code reasoning_effort: "low"} to stay inside the 25 s a search gets
 * (58–124 s without it). gpt-5.4 stays the calibrated choice: 12/12 on the six hand-checked questions at 8.8 s.
 */
public final class WebSearch {
    public static final String DEFAULT_MODEL = "gpt-5.4";
    public static final String X_MODEL = "grok-4.7";
    /** Longest answer we speak: the TV reads it aloud, so it is cut at a sentence end. */
    private static final int MAX_ANSWER = 400;
    private static final int MAX_QUERY = 300;
    private static final int MIN_TIMEOUT = 1000;
    private static final int MAX_BODY = 1024 * 1024;

    /** How the request actually leaves the device; the checks replace it, the TV uses {@link #https()}. */
    public interface Transport {
        Reply post(String url, String bearerKey, String jsonBody, int timeoutMs) throws IOException;
    }

    public static final class Reply {
        public final int status;
        public final String body;
        public Reply(int status, String body) { this.status = status; this.body = body; }
    }

    public static final class Result {
        public final String answer;
        public final boolean searched;
        public final long millis;
        public Result(String answer, boolean searched, long millis) {
            this.answer = answer; this.searched = searched; this.millis = millis;
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Transport transport;
    /** Searching X with Grok rather than the web with the default model. */
    private final boolean x;
    /** Where the household is, told to the search model (at); null: unknown. */
    private String place;

    public WebSearch(String baseUrl, String apiKey, String model, Transport transport) {
        this(baseUrl, apiKey, model, transport, false);
    }

    private WebSearch(String baseUrl, String apiKey, String model, Transport transport, boolean x) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model == null || model.isEmpty() ? (x ? X_MODEL : DEFAULT_MODEL) : model;
        this.transport = transport;
        this.x = x;
    }

    /** Tells the search where the household is (「上海徐汇」); returns this. */
    public WebSearch at(String place) { this.place = place; return this; }

    /** Posts on X (Twitter), read by Grok's own X search and checked on the web: 30–60 s. */
    public static WebSearch forX(String baseUrl, String apiKey, Transport transport) {
        return new WebSearch(baseUrl, apiKey, null, transport, true);
    }

    static String instructions(String now) { return instructions(now, null); }

    /** place: where the household is (「上海徐汇」), for questions that leave it out; null when unknown. */
    static String instructions(String now, String place) {
        return "你在帮中国大陆一户人家的电视语音助手查实时信息。现在是 " + now + "（北京时间）。"
                + (place == null || place.isEmpty() ? "" : "这户人家在" + place + "，问题没说地点时就按这里。") + "\n"
                + "1. 先联网搜索，至少看两个来源；比分、价格、汇率、天气这类数字以权威来源（官方、交易所、气象台、联赛官网、主流媒体）为准，"
                + "来源之间不一致时说出差异；没查到可靠的结果就直说没查到，不要编。\n"
                + "2. 注意时效：只用和问题的时间对得上的信息（“今天”就是上面的日期；北京时间“今天早上”的美国比赛是美国前一天晚上打的），"
                + "已经发生的事不要说成还没发生，旧消息不要当成新的。\n"
                + "3. 回答会在电视上直接读出来，不要用 markdown 和列表，不要念网址。只问一件事（一个数字、一个结果）就一两句、80 个字以内；"
                + "问榜单、排名、几场比赛、几个选项这类好几项的，按顺序只说前 5 项、每项带名字和关键数字（分数、比分、价格），其余的一句带过；"
                + "榜单要用最新的，不要用停更的旧榜；整段回答最多 200 个字，句子要说完整。数字、日期、地点要具体，价格、汇率、油价、金价按人民币和国内的报价说。来源说法一致时最多提一个来源名，"
                + "不一致时用半句话说差异。\n"
                + "4. 某人在 X（推特）、微博上的发言网页上常常跟不上：只说你看到的最新一条和它的日期，并说明可能还有更新的。";
    }

    /** What Grok is told for an X search. */
    static String xInstructions(String now) {
        return "你在帮中国大陆一户人家的电视语音助手查 X（推特）上的帖子。现在是 " + now + "（北京时间）。\n"
                + "1. 先用 X 搜索找原帖（知道账号就按账号找最新的），必要时用网页搜索核对；按发帖时间从新到旧看，说清最新一条是哪天发的，"
                + "换算成北京时间。\n"
                + "2. 只说你确实看到的帖子，没找到就直说，不要编；转述原话大意，英文帖子翻成中文。\n"
                + "3. 回答会在电视上直接读出来：一两句、100 个字以内（要说好几条帖子时每条一句，最多 3 条、200 个字以内）；"
                + "不要用 markdown 和列表，不要念网址。";
    }

    /** The request for an X search: Grok with xAI's X search and web search tools. */
    static JSONObject xBody(String model, String instructions, String query) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("model", model);
        j.put("instructions", instructions);
        j.put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", query)));
        j.put("tools", new JSONArray().put(new JSONObject().put("type", "x_search")).put(new JSONObject().put("type", "web_search")));
        j.put("max_output_tokens", 600);
        return j;
    }

    /** What the search model is told when it looks for an app's own installer (find_installer, 2026-11-07): only the
     * maker's own site, its own CDN or the address its own page writes down — never a download site or a mirror. */
    static String installerInstructions(String now) {
        return "你在帮一台小米电视（Android 9，32 位 armeabi-v7a）找某个应用的官方 Android 安装包。现在是 " + now + "。先联网搜索。"
                + "只认开发者自己的官网、它官网上写明的下载地址或开发者自己的 CDN，不要第三方下载站、应用市场镜像、网盘、破解版。"
                + "只回答一个 JSON 对象，不要别的文字：{\"app\":应用名,\"developer\":开发者公司,\"page\":官网上提供下载的页面地址,"
                + "\"url\":安装包直链（https，能直接下载到 .apk；没有直链就填空字符串）,\"package\":包名（知道的话）,\"note\":一句说明}";
    }

    /** What it is asked for, in the words of the tool call the model sees. */
    static String installerInput(String app) { return "找「" + app + "」的官方 Android 安装包"; }

    /**
     * Looks for an app's official installer: the same Responses call as {@link #search} (same model, the web_search
     * tool), answered with one JSON object the links survive in — the answer is data for the model, never spoken, so
     * it does not go through {@link #speakable} nor {@link ResponsesText#extract} (whose clean() deletes every
     * address). The daily cap is asked first: a capped day sends nothing.
     *
     * @param cap the day's paid-search allowance (null when the caller has none)
     * @return the refusal when the cap refuses, otherwise the parsed answer (facts null when it was not one)
     */
    public Installers.Found findInstaller(String app, String now, int timeoutMs, Installers.Cap cap) throws IOException {
        String name = app == null ? "" : app.trim();
        if (name.isEmpty() || name.length() > MAX_QUERY) throw new IllegalArgumentException("要找的应用名要在 1 到 300 字之间");
        if (timeoutMs < MIN_TIMEOUT) throw new IllegalArgumentException("找安装包的超时至少要 1 秒");
        String refusal = cap == null ? null : cap.take();
        if (refusal != null) return new Installers.Found(null, refusal, 0);
        String request;
        try {
            request = body(model, installerInstructions(now), installerInput(name)).toString();
        } catch (JSONException e) {
            throw new IOException("找安装包的请求构造失败：" + e.getMessage());
        }
        long start = System.currentTimeMillis();
        Reply reply = transport.post(baseUrl + "/v1/responses", apiKey, request, timeoutMs);
        check(reply);
        String answer = Installers.text(reply.body == null ? "" : reply.body);
        if (answer.isEmpty()) throw new IOException("联网搜索没有返回结果");
        return new Installers.Found(Installers.parse(answer), null, System.currentTimeMillis() - start);
    }

    /** A year on its own: not part of a range or a season (「2025-26赛季」「2025到2026」), which the agent may mean. */
    private static final java.util.regex.Pattern YEAR = java.util.regex.Pattern.compile(
            "(?<![0-9\\-–/~至到])(20[0-9]{2})(?![0-9]|\\s*[\\-–/~至到])");

    /** Last year in a query the user did not ask about is the agent's own slip: 2026-11-06 it searched 「2025年11月」 for
     * 「他最近发的」. The year of {@code now} (「2026-11-06 00:05 星期五」) replaces it unless the user said that year or 去年. */
    static String fixYear(String query, String said, String now) {
        if (query == null || now == null || now.length() < 4 || !now.substring(0, 4).matches("20[0-9]{2}")) return query;
        int year = Integer.parseInt(now.substring(0, 4));
        String last = Integer.toString(year - 1), s = said == null ? "" : said;
        if (s.contains(last) || s.contains("去年") || s.contains("前年")) return query;
        java.util.regex.Matcher m = YEAR.matcher(query);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, m.group(1).equals(last) ? Integer.toString(year) : m.group(1));
        m.appendTail(out);
        return out.toString();
    }

    /** What the search model is asked: the agent's query, with the user's own words when they add something (the
     * query is the agent's rewording, and 「今天早上」「明天」 sometimes got lost in it). */
    static String question(String query, String said) {
        String s = said == null ? "" : said.trim();
        return s.isEmpty() || s.equals(query) ? query : "用户原话：" + s + "\n要查的是：" + query;
    }

    static JSONObject body(String model, String instructions, String query) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("model", model);
        j.put("instructions", instructions);
        j.put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", query)));
        j.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")));
        j.put("max_output_tokens", 600);
        return j;
    }

    public Result search(String query, String now, int timeoutMs) throws IOException { return search(query, null, now, timeoutMs); }

    /** @param said the user's own words for this request (may be null) */
    public Result search(String query, String said, String now, int timeoutMs) throws IOException {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || q.length() > MAX_QUERY) throw new IllegalArgumentException("搜索内容要在 1 到 300 字之间");
        if (timeoutMs < MIN_TIMEOUT) throw new IllegalArgumentException("联网搜索的超时至少要 1 秒");
        q = fixYear(q, said, now);
        String request;
        try {
            request = (x ? xBody(model, xInstructions(now), question(q, said)) : body(model, instructions(now, place), question(q, said))).toString();
        } catch (JSONException e) {
            throw new IOException("联网搜索的请求构造失败：" + e.getMessage());
        }
        long start = System.currentTimeMillis();
        Reply reply = transport.post(baseUrl + "/v1/responses", apiKey, request, timeoutMs);
        check(reply);
        String json = reply.body == null ? "" : reply.body;
        String answer = ResponsesText.extract(json);
        if (answer.isEmpty()) throw new IOException("联网搜索没有返回结果");
        return new Result(cut(speakable(answer)), ResponsesText.usedSearch(json), System.currentTimeMillis() - start);
    }

    private static void check(Reply reply) throws IOException {
        if (reply == null) throw new IOException("联网搜索没有返回结果");
        if (reply.status == 401 || reply.status == 403) throw new IOException("联网搜索的线路鉴权失败");
        if (reply.status == 429) throw new IOException("联网搜索太忙了，稍后再试");
        if (reply.status != 200) throw new IOException("联网搜索失败（HTTP " + reply.status + "）");
    }

    private static final java.util.regex.Pattern CITED = java.util.regex.Pattern.compile("[（(]\\s*\\[[^\\]]*\\]\\([^)]*\\)\\s*[)）]");
    private static final java.util.regex.Pattern LINK = java.util.regex.Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final java.util.regex.Pattern URL_TEXT = java.util.regex.Pattern.compile("https?://\\S+");

    /** What can be read aloud: the search model still appends 「([xe.com](https://…))」 now and then (2026-11-05), and
     * XiaoAi would read the address. Citations in brackets go, a link keeps its text, a bare address goes. */
    static String speakable(String s) {
        String t = CITED.matcher(s).replaceAll("");
        t = LINK.matcher(t).replaceAll("$1");
        t = URL_TEXT.matcher(t).replaceAll("");
        return t.replaceAll("[ \\t]{2,}", " ").replaceAll("\\s+([，。！？；])", "$1").trim();
    }

    /** At most MAX_ANSWER characters, ending on a sentence; without one, the cut is marked with an ellipsis. */
    static String cut(String s) {
        if (s.length() <= MAX_ANSWER) return s;
        String head = s.substring(0, MAX_ANSWER);
        for (int i = head.length() - 1; i >= 0; i--) {
            if ("。！？".indexOf(head.charAt(i)) >= 0) return head.substring(0, i + 1);
        }
        return head + "…";
    }

    /** The real transport: one HTTP POST, nothing kept open. */
    public static Transport https() {
        return new Transport() {
            @Override public Reply post(String url, String bearerKey, String jsonBody, int timeoutMs) throws IOException {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                try {
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(Math.min(8000, timeoutMs));
                    c.setReadTimeout(timeoutMs);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("Authorization", "Bearer " + bearerKey);
                    c.setDoOutput(true);
                    byte[] out = jsonBody.getBytes("UTF-8");
                    c.setFixedLengthStreamingMode(out.length);
                    OutputStream os = c.getOutputStream();
                    try { os.write(out); } finally { os.close(); }
                    int status = c.getResponseCode();
                    return new Reply(status, read(status >= 400 ? c.getErrorStream() : c.getInputStream()));
                } finally {
                    c.disconnect();
                }
            }
        };
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) throw new IOException("联网搜索的回复太大");
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
