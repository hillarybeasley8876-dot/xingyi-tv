package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;

/** Web search: the Responses API request and the speakable text of its replies. */
final class WebSearchChecks {
    private static final String NOW = "2026-11-04 23:10 星期三";

    static void run() throws Exception {
        responsesText();
        realFixtures();
        requestShape();
        searchHappyPath();
        searchFailures();
        searchRejects();
        answerCut();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String fixture(int n) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/websearch/websearch-" + n + ".json")), StandardCharsets.UTF_8);
    }

    /** A minimal Responses reply carrying one output_text part. */
    private static String message(String text) {
        return "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":" + JSONObject.quote(text) + "}]}]}";
    }

    private static void eq(String got, String want, String what) {
        AgentCoreTest.check(want.equals(got), what + ": got [" + got + "] want [" + want + "]");
    }

    /** A transport that records what it was asked to send and answers with a canned reply. */
    static final class Recorder implements WebSearch.Transport {
        String url, key, body;
        int timeout, calls;
        WebSearch.Reply reply = new WebSearch.Reply(200, "{}");
        IOException failure;
        @Override public WebSearch.Reply post(String url, String bearerKey, String jsonBody, int timeoutMs) throws IOException {
            calls++;
            this.url = url; this.key = bearerKey; this.body = jsonBody; this.timeout = timeoutMs;
            if (failure != null) throw failure;
            return reply;
        }
    }

    // ---- ResponsesText ---------------------------------------------------------------------------

    static void responsesText() throws Exception {
        String real = fixture(0);
        eq(ResponsesText.extract(real), "今天上海金早盘价847.21元/克，午盘价841.08元/克。", "real reply loses its citation");
        AgentCoreTest.check(ResponsesText.usedSearch(real), "real reply did search the web");
        eq(ResponsesText.extract("{\"output_text\":\"直接给的答案\"}"), "直接给的答案", "top-level output_text");
        eq(ResponsesText.extract(message("金价约800元。（[金价网](https://x.cn/a?b=1)）")), "金价约800元。", "full-width citation");
        eq(ResponsesText.extract(message("见[上海黄金交易所](https://www.sge.com.cn)的报价")), "见上海黄金交易所的报价", "plain link keeps its label");
        eq(ResponsesText.extract(message("详情 https://example.com/a 查看")), "详情 查看", "bare url");
        eq(ResponsesText.extract(message("**今天**  晴，# 18度")), "今天 晴， 18度", "markup and spaces");
        eq(ResponsesText.extract("{\"output\":[{\"type\":\"web_search_call\"}]}"), "", "no message");
        eq(ResponsesText.extract("not json"), "", "bad json");
        eq(ResponsesText.extract("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"一。\"},"
                + "{\"type\":\"refusal\",\"text\":\"x\"},{\"type\":\"output_text\",\"text\":\"二。\"}]}]}"), "一。二。", "only output_text parts, in order");
        AgentCoreTest.check(!ResponsesText.usedSearch(message("x")), "a reply without a search call");
        AgentCoreTest.check(!ResponsesText.usedSearch("[]"), "array is not a reply");
        eq(ResponsesText.extract(null), "", "null reply");
        eq(ResponsesText.extract(""), "", "empty reply");
    }

    // ---- the other three real replies -------------------------------------------------------------

    /** Whatever the model searched for, the TV must never hear a url, markdown or a bare domain. */
    static void realFixtures() throws Exception {
        for (int n = 1; n <= 3; n++) {
            String text = ResponsesText.extract(fixture(n));
            AgentCoreTest.check(!text.isEmpty(), "fixture " + n + " has speakable text");
            AgentCoreTest.check(!text.contains("http"), "fixture " + n + " has no url: " + text);
            AgentCoreTest.check(!text.contains("]("), "fixture " + n + " has no markdown link: " + text);
            AgentCoreTest.check(!text.contains("*"), "fixture " + n + " has no asterisk: " + text);
            AgentCoreTest.check(!text.contains("#"), "fixture " + n + " has no hash: " + text);
            AgentCoreTest.check(!text.contains("sge.com.cn"), "fixture " + n + " has no bare domain: " + text);
            AgentCoreTest.check(ResponsesText.usedSearch(fixture(n)), "fixture " + n + " did search the web");
        }
        AgentCoreTest.check(ResponsesText.extract(fixture(1)).contains("元"), "gold price is quoted in RMB");
        AgentCoreTest.check(ResponsesText.extract(fixture(2)).contains("上海"), "weather answer names the city");
        AgentCoreTest.check(ResponsesText.extract(fixture(3)).contains("NBA"), "long answer keeps its subject");
    }

    // ---- what goes out -----------------------------------------------------------------------------

    static void requestShape() throws Exception {
        String instructions = WebSearch.instructions(NOW);
        // the 2026-11-05 wording (tests/agent/search_eval.py: 12/12 with gpt-5.4, against 4/6 before)
        AgentCoreTest.check(instructions.contains(NOW), "the model is told the current time");
        AgentCoreTest.check(instructions.contains("至少看两个来源"), "it is asked for two sources at least");
        AgentCoreTest.check(instructions.contains("已经发生的事不要说成还没发生"), "and to keep the time straight");
        AgentCoreTest.check(instructions.contains("没查到可靠的结果就直说没查到，不要编"), "and to say so instead of inventing");
        AgentCoreTest.check(instructions.contains("人民币"), "prices are asked for in RMB");
        AgentCoreTest.check(instructions.contains("不要念网址"), "addresses are not read out");
        AgentCoreTest.check("gpt-5.4".equals(WebSearch.DEFAULT_MODEL), "the measured model is the default");
        eq(WebSearch.question("上海明天天气", "那明天呢"), "用户原话：那明天呢\n要查的是：上海明天天气", "the user's own words go along");
        eq(WebSearch.question("上海天气", "上海天气"), "上海天气", "unless they are the query itself");
        eq(WebSearch.question("上海天气", null), "上海天气", "or there are none");
        eq(WebSearch.speakable("1 美元约 6.67 元人民币。([xe.com](https://www.xe.com/zh-cn/x?a=1))"), "1 美元约 6.67 元人民币。",
                "a bracketed citation is dropped");
        eq(WebSearch.speakable("来源：[新华社](https://news.cn/a)报道"), "来源：新华社报道", "a link keeps its text");
        eq(WebSearch.speakable("详见 https://example.com/x 。"), "详见。", "a bare address goes");

        JSONObject b = WebSearch.body("gpt-5.4-mini", "查一下", "今天金价");
        AgentCoreTest.check("gpt-5.4-mini".equals(b.getString("model")), "body carries the model");
        AgentCoreTest.check("查一下".equals(b.getString("instructions")), "body carries the instructions");
        AgentCoreTest.check(b.getJSONArray("input").length() == 1, "one user turn");
        JSONObject turn = b.getJSONArray("input").getJSONObject(0);
        AgentCoreTest.check("user".equals(turn.getString("role")) && "今天金价".equals(turn.getString("content")), "the query is the user message");
        AgentCoreTest.check(b.getJSONArray("tools").length() == 1 && "web_search".equals(b.getJSONArray("tools").getJSONObject(0).getString("type")),
                "the web_search tool is requested");
        AgentCoreTest.check(b.getInt("max_output_tokens") == 600, "max_output_tokens is 600");
        AgentCoreTest.check(b.length() == 5, "no other fields are sent: " + b.keySet());
    }

    // ---- what comes back -----------------------------------------------------------------------------

    static void searchHappyPath() throws Exception {
        Recorder rec = new Recorder();
        rec.reply = new WebSearch.Reply(200, fixture(2));
        WebSearch ws = new WebSearch("https://gw.example.com", "sk-key-1", null, rec);
        WebSearch.Result r = ws.search("  上海天气  ", NOW, 20000);
        eq(rec.url, "https://gw.example.com/v1/responses", "posts to /v1/responses");
        eq(rec.key, "sk-key-1", "the api key is the bearer");
        AgentCoreTest.check(rec.timeout == 20000, "the timeout reaches the transport: " + rec.timeout);
        JSONObject sent = new JSONObject(rec.body);
        AgentCoreTest.check(sent.similar(WebSearch.body(WebSearch.DEFAULT_MODEL, WebSearch.instructions(NOW), "上海天气")),
                "the sent body is exactly body(): " + sent);
        AgentCoreTest.check(WebSearch.DEFAULT_MODEL.equals(sent.getString("model")), "an empty model means the default one");
        eq(r.answer, ResponsesText.extract(fixture(2)), "answer is the speakable text of the reply");
        AgentCoreTest.check(r.searched, "searched is true when the reply has a search call");
        AgentCoreTest.check(r.millis >= 0 && r.millis < 60000, "millis measures the call: " + r.millis);

        Recorder slash = new Recorder();
        slash.reply = new WebSearch.Reply(200, fixture(2));
        new WebSearch("https://gw.example.com/", "k", "gpt-5.4-mini", slash).search("上海天气", NOW, 20000);
        eq(slash.url, "https://gw.example.com/v1/responses", "one trailing slash is dropped");
        eq(new JSONObject(slash.body).getString("model"), "gpt-5.4-mini", "a given model is used");

        Recorder plain = new Recorder();
        plain.reply = new WebSearch.Reply(200, message("没有联网的回答。"));
        WebSearch.Result noSearch = new WebSearch("https://gw.example.com", "k", null, plain).search("x", NOW, 3000);
        AgentCoreTest.check(!noSearch.searched, "searched is false without a search call");
        eq(noSearch.answer, "没有联网的回答。", "a direct answer is spoken as it is");
    }

    /** Every HTTP failure becomes a sentence the TV can speak, never a raw status line. */
    static void searchFailures() throws Exception {
        int[] cases = {401, 403, 429, 500, 404};
        String[] want = {"联网搜索的线路鉴权失败", "联网搜索的线路鉴权失败", "联网搜索太忙了，稍后再试",
                "联网搜索失败（HTTP 500）", "联网搜索失败（HTTP 404）"};
        for (int i = 0; i < cases.length; i++) {
            Recorder rec = new Recorder();
            rec.reply = new WebSearch.Reply(cases[i], fixture(2));
            String got = failure(new WebSearch("https://gw.example.com", "k", null, rec), "金价", NOW, 20000);
            eq(got, want[i], "HTTP " + cases[i] + " is spoken as a sentence");
        }
        Recorder empty = new Recorder();
        empty.reply = new WebSearch.Reply(200, "{\"output\":[{\"type\":\"web_search_call\"}]}");
        eq(failure(new WebSearch("https://gw.example.com", "k", null, empty), "金价", NOW, 20000),
                "联网搜索没有返回结果", "a reply with no message is an empty answer");
        Recorder blank = new Recorder();
        blank.reply = new WebSearch.Reply(200, "");
        eq(failure(new WebSearch("https://gw.example.com", "k", null, blank), "金价", NOW, 20000),
                "联网搜索没有返回结果", "an empty body is an empty answer");

        Recorder broken = new Recorder();
        broken.failure = new IOException("连接超时");
        WebSearch ws = new WebSearch("https://gw.example.com", "k", null, broken);
        try {
            ws.search("金价", NOW, 20000);
            AgentCoreTest.check(false, "the transport's own failure must come out");
        } catch (IOException e) {
            AgentCoreTest.check(e == broken.failure, "the transport's own IOException comes out unchanged: " + e);
        }
    }

    /** The message of the IOException search throws, or "" when it threw none. */
    private static String failure(WebSearch ws, String query, String now, int timeoutMs) {
        try {
            ws.search(query, now, timeoutMs);
            return "";
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /** A bad query or timeout is refused before anything leaves the TV. */
    static void searchRejects() throws Exception {
        StringBuilder longQuery = new StringBuilder();
        for (int i = 0; i < 301; i++) longQuery.append('金');
        String[] bad = {"", "   ", longQuery.toString()};
        for (String q : bad) {
            Recorder rec = new Recorder();
            WebSearch ws = new WebSearch("https://gw.example.com", "k", null, rec);
            try {
                ws.search(q, NOW, 20000);
                AgentCoreTest.check(false, "query of " + q.length() + " chars must be refused");
            } catch (IllegalArgumentException e) {
                eq(e.getMessage(), "搜索内容要在 1 到 300 字之间", "query of " + q.length() + " chars refused");
            }
            AgentCoreTest.check(rec.calls == 0, "no request for a bad query of " + q.length() + " chars");
        }
        Recorder rec = new Recorder();
        try {
            new WebSearch("https://gw.example.com", "k", null, rec).search("金价", NOW, 999);
            AgentCoreTest.check(false, "a timeout under a second must be refused");
        } catch (IllegalArgumentException e) {
            AgentCoreTest.check(e.getMessage() != null && !e.getMessage().isEmpty(), "short timeout refused: " + e.getMessage());
        }
        AgentCoreTest.check(rec.calls == 0, "no request for a timeout of 999 ms");

        Recorder ok = new Recorder();
        ok.reply = new WebSearch.Reply(200, message("好。"));
        StringBuilder threeHundred = new StringBuilder();
        for (int i = 0; i < 300; i++) threeHundred.append('金');
        new WebSearch("https://gw.example.com", "k", null, ok).search(threeHundred.toString(), NOW, 1000);
        AgentCoreTest.check(ok.calls == 1, "exactly 300 chars and 1000 ms are allowed");
    }

    /** The TV reads the answer aloud, so a long one is cut at a sentence. */
    static void answerCut() throws Exception {
        StringBuilder sentences = new StringBuilder();
        while (sentences.length() < 500) sentences.append("今天上海金价是八百四十七元每克。");
        Recorder rec = new Recorder();
        rec.reply = new WebSearch.Reply(200, message(sentences.toString()));
        String cut = new WebSearch("https://gw.example.com", "k", null, rec).search("金价", NOW, 20000).answer;
        AgentCoreTest.check(cut.length() <= 400, "a long answer is cut to 400 chars: " + cut.length());
        AgentCoreTest.check(cut.endsWith("。"), "the cut ends on a sentence: " + cut.substring(cut.length() - 5));
        AgentCoreTest.check(sentences.toString().startsWith(cut), "the cut keeps the beginning");

        StringBuilder noEnd = new StringBuilder();
        while (noEnd.length() < 500) noEnd.append("金价涨了一点");
        Recorder plain = new Recorder();
        plain.reply = new WebSearch.Reply(200, message(noEnd.toString()));
        String marked = new WebSearch("https://gw.example.com", "k", null, plain).search("金价", NOW, 20000).answer;
        AgentCoreTest.check(marked.length() == 401, "without a sentence end the cut is 400 chars plus …: " + marked.length());
        AgentCoreTest.check(marked.endsWith("…"), "the hard cut is marked: " + marked.substring(marked.length() - 3));
        AgentCoreTest.check(marked.startsWith(noEnd.substring(0, 20)), "the hard cut keeps the beginning");

        eq(WebSearch.cut("短的回答。"), "短的回答。", "a short answer is left alone");
    }
}
