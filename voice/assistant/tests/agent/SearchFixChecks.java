package xyz.erapi0neer.xingyi.agent;

import org.json.*;

/** Checks from 2026-11-06 00:05 on the TV: 「查一下目前大模型的榜单」 was cut mid-name when read out, and 「Tibo 推特上关于
 * 重置的消息」 was answered from a months-old post — Grok's X search, the last message of its reply, the not-found
 * phrases, the year the agent slipped into a query, and whole answers read aloud. */
final class SearchFixChecks {
    private SearchFixChecks() { }

    static void run() throws Exception {
        grokReply();
        xRequest();
        notFound();
        years();
        readWhole();
        finalX();
        pagesOfAnswer();
    }

    /** 2026-11-06: an 865-character answer was read to 300 characters and the panel never turned. Pages lose nothing,
     * keep whole sentences, and none is longer than PAGE_MAX. */
    static void pagesOfAnswer() {
        String np = "NP=P 难解决，核心在于它和主流复杂性理论假设、证明工具和计算模型三方都拧着：它是形式化的“能快速验证”是否等于“能快速找到”的问题。"
                + "NP 里每个答案有简短证书且可快速验真，但没规定怎么找。NP=P 等于说验证难的题（SAT、图着色、哈密顿回路等）都存在通用多项式算法；"
                + "NP≠P 则说这些题本质上没有快解。主流猜想都站在 NP≠P 一边，且无一被证明。现有证明工具不够用。"
                + "若 NP=P，密码学立刻崩塌，一大堆已建成的理论与工程要重建。一句话：NP=P 要么是复杂性理论最深的定理，要么是它最硬的墙。";
        java.util.List<String> pages = Speech.pages(np, Speech.PAGE_MAX);
        check(pages.size() >= 2, "a long answer has pages: " + pages.size());
        check(String.join("", pages).equals(Speech.clean(np)), "the pages together are the whole answer");
        boolean fits = true, whole = true;
        for (int i = 0; i < pages.size(); i++) {
            String pg = pages.get(i);
            if (pg.length() > Speech.PAGE_MAX) fits = false;
            if (i < pages.size() - 1 && "。！？!?；;".indexOf(pg.charAt(pg.length() - 1)) < 0) whole = false;
        }
        check(fits, "no page is longer than " + Speech.PAGE_MAX);
        check(whole, "every page but the last ends a sentence: " + pages);
        check(Speech.pages("现在时间是凌晨 1 点 32 分。", Speech.PAGE_MAX).size() == 1, "a short answer is one page");
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < 40; i++) run.append("这是一个没有标点的很长的句子");
        java.util.List<String> cut = Speech.pages(run.toString(), Speech.PAGE_MAX);
        check(cut.size() >= 4 && String.join("", cut).equals(run.toString()), "a sentence with no stop is cut at the page size");
        check(Speech.pages("", Speech.PAGE_MAX).isEmpty(), "nothing to read, no page");
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static JSONObject message(String text) throws JSONException {
        return new JSONObject().put("type", "message").put("role", "assistant")
                .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", text)));
    }

    /** Grok narrates between its searches; only its last message is the answer, and an X search counts as searching. */
    static void grokReply() throws Exception {
        JSONObject reply = new JSONObject().put("output", new JSONArray()
                .put(new JSONObject().put("type", "reasoning"))
                .put(message("我来查一下 @thsottiaux 的最新推文。"))
                .put(new JSONObject().put("type", "custom_tool_call").put("name", "x_keyword_search").put("status", "completed"))
                .put(message("换个方式再查一次最新推文。"))
                .put(new JSONObject().put("type", "web_search_call").put("status", "completed"))
                .put(message("最新一条是北京时间11月2日早上7点27分的「Day 28/ Banked 2/2」。")));
        String text = ResponsesText.extract(reply.toString());
        check(text.equals("最新一条是北京时间11月2日早上7点27分的「Day 28/ Banked 2/2」。"), "only Grok's last message is the answer: " + text);
        check(ResponsesText.usedSearch(reply.toString()), "a Grok reply with searches searched");
        JSONObject xOnly = new JSONObject().put("output", new JSONArray()
                .put(new JSONObject().put("type", "custom_tool_call").put("name", "x_semantic_search"))
                .put(message("找到了。")));
        check(ResponsesText.usedSearch(xOnly.toString()), "an X search alone counts as searching");
        JSONObject other = new JSONObject().put("output", new JSONArray()
                .put(new JSONObject().put("type", "custom_tool_call").put("name", "code_interpreter"))
                .put(message("算好了。")));
        check(!ResponsesText.usedSearch(other.toString()), "another custom tool is not a search");
        // one message with two parts, as the default model sends it: both parts stay
        JSONObject parts = new JSONObject().put("output", new JSONArray().put(new JSONObject().put("type", "message")
                .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", "上海今天阴，"))
                        .put(new JSONObject().put("type", "output_text").put("text", "18度。")))));
        check("上海今天阴，18度。".equals(ResponsesText.extract(parts.toString())), "the parts of one message stay together");
    }

    /** forX asks Grok with xAI's X search and web search, through the same Responses endpoint. */
    static void xRequest() throws Exception {
        final String[] sent = new String[2];
        WebSearch.Transport t = (url, key, body, timeout) -> {
            sent[0] = url; sent[1] = body;
            return new WebSearch.Reply(200, new JSONObject().put("output", new JSONArray()
                    .put(new JSONObject().put("type", "custom_tool_call").put("name", "x_keyword_search"))
                    .put(message("最新一条是11月2日的「Day 28/ Banked 2/2」（[x.com](https://x.com/thsottiaux/status/2117035794678546764)）。"))).toString());
        };
        WebSearch.Result r = WebSearch.forX("https://gw.example/", "k", t)
                .search("@thsottiaux 最近关于重置的帖子", "他推特上最近说重置了吗", "2026-11-06 00:20 星期五", 90000);
        JSONObject b = new JSONObject(sent[1]);
        check("https://gw.example/v1/responses".equals(sent[0]), "the X search goes to the Responses endpoint: " + sent[0]);
        check(WebSearch.X_MODEL.equals(b.getString("model")) && "grok-4.7".equals(WebSearch.X_MODEL), "the X search asks grok-4.7");
        JSONArray tools = b.getJSONArray("tools");
        check(tools.length() == 2 && "x_search".equals(tools.getJSONObject(0).getString("type"))
                && "web_search".equals(tools.getJSONObject(1).getString("type")), "with the X search and the web search: " + tools);
        check(b.getString("instructions").contains("按发帖时间从新到旧") && b.getString("instructions").contains("2026-11-06"),
                "Grok is told to look at the newest posts and the time");
        check(r.searched && r.answer.startsWith("最新一条是11月2日") && !r.answer.contains("http"), "the answer is speakable: " + r.answer);
        check(new JSONObject(WebSearch.body(WebSearch.DEFAULT_MODEL, "x", "q").toString()).getJSONArray("tools").length() == 1,
                "the web search still sends the web search alone");
        check(WebSearch.instructions("2026-11-06 00:20").contains("前 5 项"), "a ranking is said with its first five");
    }

    /** 「我这边没拿到更晚且更可靠的原帖」 is not a conclusion: the budget leaves a second search. */
    static void notFound() {
        check(!SearchBudget.answered("查到他8月发过一条；我这边没拿到更晚且更可靠的原帖。", true), "没拿到… is not a conclusion");
        check(!SearchBudget.answered("没能找到他最近的帖子。", true), "没能找到 is not a conclusion");
        check(SearchBudget.answered("湖人没能进入季后赛，赛季在4月结束。", true), "没能 inside a fact is still a conclusion");
        check(SearchBudget.answered("最新一条是11月2日的 Day 28。", true), "a dated post is a conclusion");
        SearchBudget one = new SearchBudget(1);
        check(one.check(1) == null, "a one-search budget allows one");
        check(one.check(2) != null, "and refuses the second");
    }

    /** The agent wrote last year for 「最近」; a year the user said, 去年, and seasons stay. */
    static void years() {
        String now = "2026-11-06 00:05 星期五";
        check("thsottiaux X 帖子 2026年11月 用量重置".equals(WebSearch.fixYear("thsottiaux X 帖子 2025年11月 用量重置", "他最近发的重置", now)),
                "last year becomes this year when the user did not ask for it");
        check("2025年诺贝尔文学奖".equals(WebSearch.fixYear("2025年诺贝尔文学奖", "2025年诺贝尔文学奖给了谁", now)), "a year the user said stays");
        check("2025年诺贝尔文学奖".equals(WebSearch.fixYear("2025年诺贝尔文学奖", "去年的诺贝尔文学奖给了谁", now)), "去年 keeps last year");
        check("NBA 2025-26赛季 总冠军".equals(WebSearch.fixYear("NBA 2025-26赛季 总冠军", "上赛季总冠军是谁", now)), "a season stays");
        check("2025到2026年 金价走势".equals(WebSearch.fixYear("2025到2026年 金价走势", "金价这一年怎么走的", now)), "a range stays");
        check("2024年奥运会".equals(WebSearch.fixYear("2024年奥运会", "上届奥运会在哪", now)), "an older year stays");
        check("OpenAI news".equals(WebSearch.fixYear("OpenAI news", "OpenAI 最近怎么样", now)), "no year, nothing changes");
        check("x 2025".equals(WebSearch.fixYear("x 2025", "y", "bad clock")), "an unreadable clock changes nothing");
    }

    /** A ranking is read whole, or up to ANSWER_MAX at a clause, never inside a word. */
    static void readWhole() {
        String ranking = "最新常见“大模型榜单”里，Artificial Analysis显示第一是 Gemini 4 Carbon High，第二 Claude Opus 5.5 Max，"
                + "第三 Gemini 4 Carbon Medium；Hugging Face 开源榜第一是 DeepSeek V4.1。";
        check(ranking.equals(Speech.answer(ranking)), "a 120-character ranking is read whole: " + Speech.answer(ranking));
        String noStop = "第一是 Gemini 4 Carbon High，第二 Claude Opus 5.5 Max，第三 Gemini 4 Carbon Medium，第四 Claude Opus 5.5 Xhigh";
        String cut = Speech.spoken(noStop, 90);
        check(cut.endsWith("。") && !cut.contains("Carb。") && cut.length() <= 90, "without a full stop it ends on a clause: " + cut);
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 40; i++) long_.append("第").append(i + 1).append("名是某个模型，");
        String said = Speech.answer(long_.toString());
        check(said.endsWith("后面的在屏幕上。") && said.length() <= Speech.ANSWER_MAX + 10, "past ANSWER_MAX it points at the screen: " + said.length());
    }

    /** x_search with final=true that found a conclusion is the answer, as web_search's is. */
    static void finalX() throws Exception {
        AgentCoreTest.Fake x = new AgentCoreTest.Fake("x_search", Tool.Gate.read());
        String answer = "Tibo 最新一条是北京时间11月2日早上的「Day 28/ Banked 2/2」，第二次可自己兑换的用量重置。";
        x.reply = AgentCoreTest.o("ok", true, "status", "observed", "message", answer, "answer", answer, "searched", true);
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("x_search", AgentCoreTest.o("query", "@thsottiaux 最近关于重置的帖子", "final", true))
                .text("这一轮不该被问到");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, x)
                .run(new AgentLoop.Request("就是这个人然后你去查他的推特账号他经常会发一些重置相关的东西", "test"));
        check(m.seen.size() == 1 && answer.equals(t.reply), "a final X search is the answer: " + t.reply);
    }
}
