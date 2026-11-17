package xyz.erapi0neer.xingyi.agent;

import org.json.*;

/** Checks from the 2026-11-05 evening on the TV (「等我回答之后又问我」「每次会覆盖上一次的结果」, 「绩效优化」): an answer to
 * 星仪's question is never met with a second question, and a search the model marks final is the spoken answer. */
final class ConversationChecks {
    private ConversationChecks() { }

    static void run() throws Exception {
        secondQuestion();
        firstQuestion();
        searchFinal();
        searchNotFinal();
        questionsOpenNoApp();
        longAnswerShortened();
    }

    /** 2026-11-06: a 「详细讲讲」 answer came back at 13 pages. Over LONG_ANSWER it is sent back once to be cut; the
     * second answer stands whatever its length. */
    static void longAnswerShortened() throws Exception {
        StringBuilder essay = new StringBuilder();
        for (int i = 0; i < 60; i++) essay.append("这是一句很长的解释，用来把回答撑到很长。");
        AgentCoreTest.Script m = new AgentCoreTest.Script().text(essay.toString()).text("简短版：验证容易不等于求解容易，至今没人能证明。");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true)
                .run(new AgentLoop.Request("详细讲讲为什么P和NP问题这么难", "test"));
        check(m.seen.size() == 2 && t.reply.startsWith("简短版"), "a too-long answer is cut down once: " + t.reply);
        JSONArray second = m.seen.get(1);
        check(second.getJSONObject(second.length() - 1).optString("content").equals(AgentLoop.SHORTER), "with the reason, as a user message");

        AgentCoreTest.Script twice = new AgentCoreTest.Script().text(essay.toString()).text(essay.toString()).text("不该被问到");
        Ledger u = AgentCoreTest.loop(twice, new AgentCoreTest.Events(), (w, l) -> true)
                .run(new AgentLoop.Request("详细讲讲", "test"));
        check(twice.seen.size() == 2 && u.reply.length() > AgentLoop.LONG_ANSWER, "only once: the second long answer stands");

        AgentCoreTest.Script brief = new AgentCoreTest.Script().text("杜甫是唐代诗人。");
        AgentCoreTest.loop(brief, new AgentCoreTest.Events(), (w, l) -> true).run(new AgentLoop.Request("杜甫是哪个朝代的", "test"));
        check(brief.seen.size() == 1, "a short answer goes as it is");
        // clock times said as 点 and 分 (2026-11-07), scores and ratios left alone
        check(Speech.clean("昨晚电视开了 2 小时（20:58 到 23:10）").equals("昨晚电视开了 2 小时（20 点 58 分到 23 点 10 分）"), "clock times: " + Speech.clean("昨晚电视开了 2 小时（20:58 到 23:10）"));
        check(Speech.clean("亮屏时段20:58–21:03").equals("亮屏时段 20 点 58 分到 21 点 3 分"), "a range: " + Speech.clean("亮屏时段20:58–21:03"));
        check(Speech.clean("新闻联播 19:00 开始").equals("新闻联播 19 点开始") && Speech.clean("比分 3:2，画面 16:9").equals("比分 3:2，画面 16:9")
                && Speech.clean("版本 1.10:30").equals("版本 1.10:30") && Speech.clean("24:30 不是时间").equals("24:30 不是时间")
                && Speech.clean("地点 北京").equals("地点 北京"), "what is not a clock time stays");
        check(Speech.clean("三层来看。*一、问题的根").equals("三层来看。一、问题的根"), "a stray markdown star is not read");
        // 2026-11-06 overnight pass: a web address is never read out
        check(Speech.clean("诺奖给了卡森（来源：https://www.nobelprize.org/prizes/literature/2026/）。").equals("诺奖给了卡森。"),
                "a bare address and the brackets it leaves go: " + Speech.clean("诺奖给了卡森（来源：https://www.nobelprize.org/prizes/literature/2026/）。"));
        check(Speech.clean("详见[诺贝尔官网](https://www.nobelprize.org)的公告。").equals("详见诺贝尔官网的公告。"), "a markdown link keeps its words");
        check(Speech.clean("访问 www.example.com 查看").equals("访问 查看"), "www addresses go too: " + Speech.clean("访问 www.example.com 查看"));
        check(Speech.clean("比分 109:102，凯尔特人赢了").equals("比分 109:102，凯尔特人赢了"), "a score is not an address");
        check(Speech.clean("好的，已经放上了🎵😊").equals("好的，已经放上了"), "emoji are not read: " + Speech.clean("好的，已经放上了🎵😊"));
        check(Speech.visible("还有 92 天过年，2月5日到13日连休9天。finish").equals("还有 92 天过年，2月5日到13日连休9天。"), "a tool's name at the end is not read");
        check(Speech.visible("比赛结束了，叫 finish line。").endsWith("finish line。"), "a word inside the answer stays");
        check(Speech.clean("晴转多云☀️，记得带伞").equals("晴转多云，记得带伞"), "a weather symbol with its variation selector goes");
    }

    /** 2026-11-05 on the TV: 「我想知道最近有什么新动向」 opened B站 for an UP主 from the habits. A question that names no
     * app gets no app; asking for one still does. */
    static void questionsOpenNoApp() throws Exception {
        check(Dispatch.asksOnly("我想知道最近有什么新动向"), "a question about the world asks only");
        check(Dispatch.asksOnly("周杰伦最新的歌是什么"), "「…是什么」 asks only");
        check(!Dispatch.asksOnly("打开B站搜星辰亻旅行者"), "打开B站… asks for an app");
        check(!Dispatch.asksOnly("B站上有什么新视频"), "naming B站 asks for it");
        check(!Dispatch.asksOnly("播放周杰伦的晴天"), "播放… is not a question");
        check(!Dispatch.asksOnly("") && !Dispatch.asksOnly(null), "nothing said asks nothing");

        AgentCoreTest.Fake bili = new AgentCoreTest.Fake("bili_search", Tool.Gate.act());
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("bili_search", AgentCoreTest.o("keyword", "星辰亻旅行者"))
                .text("你想知道谁的新动向？");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, bili)
                .run(new AgentLoop.Request("我想知道最近有什么新动向", "test"));
        JSONObject refused = m.toolResult("c0");
        check(bili.runs.isEmpty(), "a plain question does not open B站");
        check(refused != null && AgentLoop.JUST_ASKING.equals(refused.optString("message")), "the model is told to search or answer: " + refused);
        check("你想知道谁的新动向？".equals(t.reply), "the model answers after the refusal: " + t.reply);

        AgentCoreTest.Fake bili2 = new AgentCoreTest.Fake("bili_search", Tool.Gate.act());
        AgentCoreTest.Script asked = new AgentCoreTest.Script()
                .calls("bili_search", AgentCoreTest.o("keyword", "星辰亻旅行者")).text("搜好了");
        AgentCoreTest.loop(asked, new AgentCoreTest.Events(), (w, l) -> true, bili2)
                .run(new AgentLoop.Request("打开B站搜星辰亻旅行者", "test"));
        check(bili2.runs.size() == 1, "asking for B站 still opens it");
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    /** 「我说的是想听OPPO OPEN AI这个公司的」 answering 星仪's question: a second ask_user is refused, and the model acts. */
    static void secondQuestion() throws Exception {
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("ask_user", AgentCoreTest.o("question", "你是想放视频，还是想听我讲讲？"))
                .text("OpenAI 11月4日发布了新模型。");
        AgentLoop.Request req = new AgentLoop.Request("我说的是想听OPPO OPEN AI这个公司的", "test");
        req.answering = true;
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true).run(req);
        check(t.state == Ledger.State.ANSWERED, "an answer to 星仪's question is not met with a second question: " + t.state);
        check("OpenAI 11月4日发布了新模型。".equals(t.reply), "the model answers after the refused question: " + t.reply);
        JSONObject refused = m.toolResult("c0");
        check(refused != null && !refused.optBoolean("ok") && AgentLoop.NO_SECOND_ASK.equals(refused.optString("message")),
                "the refusal tells the model to act on the answer: " + refused);
        check(t.question.isEmpty(), "no question is left open: " + t.question);
    }

    /** The first question of an exchange is still asked as before. */
    static void firstQuestion() throws Exception {
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("ask_user", AgentCoreTest.o("question", "你想知道谁的新动向？"));
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true).run(new AgentLoop.Request("我想知道最近有什么新动向", "test"));
        check(t.state == Ledger.State.AWAITING_USER && "你想知道谁的新动向？".equals(t.question),
                "a request that answers nothing may still ask: " + t.state);
    }

    private static AgentCoreTest.Fake search(String answer, boolean searched) throws JSONException {
        AgentCoreTest.Fake f = new AgentCoreTest.Fake("web_search", Tool.Gate.read());
        f.reply = AgentCoreTest.o("ok", true, "status", "observed", "message", answer, "answer", answer, "searched", searched);
        return f;
    }

    /** web_search with final=true that found a conclusion: one model round, and the conclusion is the answer. */
    static void searchFinal() throws Exception {
        String answer = "OpenAI 11月4日发布 Images 2.6，11月3日任命了新的首席企业事务官。";
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("web_search", AgentCoreTest.o("query", "OpenAI 最近有什么新动向", "final", true))
                .text("这一轮不该被问到");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, search(answer, true))
                .run(new AgentLoop.Request("OpenAI最近有什么新动向", "test"));
        check(m.seen.size() == 1, "a final search ends the task without a second model round: " + m.seen.size());
        check(answer.equals(t.reply) && t.state == Ledger.State.ANSWERED, "the search's conclusion is the answer: " + t.reply);
        // A why-question about the world is still answered by its search (asksWhy is about 星仪's own doing).
        AgentCoreTest.Script why = new AgentCoreTest.Script()
                .calls("web_search", AgentCoreTest.o("query", "今天美股为什么跌", "final", true)).text("不该被问到");
        Ledger w = AgentCoreTest.loop(why, new AgentCoreTest.Events(), (x, l) -> true, search("因为非农数据不及预期。", true))
                .run(new AgentLoop.Request("今天美股为什么跌", "test"));
        check(why.seen.size() == 1 && "因为非农数据不及预期。".equals(w.reply), "a why-question about the world: the search answers");
        // a request to play what the search finds: the search is a step, the model gets its round (2026-11-06)
        AgentCoreTest.Fake play = new AgentCoreTest.Fake("netease_play", Tool.Gate.act());
        AgentCoreTest.Script then = new AgentCoreTest.Script()
                .calls("web_search", AgentCoreTest.o("query", "林俊杰 最新单曲", "final", true))
                .calls("netease_play", AgentCoreTest.o("artist", "林俊杰", "title", "时间的歌"))
                .text("在放了");
        AgentCoreTest.loop(then, new AgentCoreTest.Events(), (x, l) -> true, search("林俊杰最新的单曲是《时间的歌》。", true), play)
                .run(new AgentLoop.Request("网易云音乐播放林俊杰最新发表的一个单曲", "test"));
        check(then.seen.size() >= 2 && play.runs.size() == 1, "a final search for 播放… does not end the task: the song is played");
        check(AgentLoop.searchThenAct("查一下林俊杰的新歌然后放给我听", new JSONArray().put(AgentCoreTest.o("id", "a", "function",
                AgentCoreTest.o("name", "web_search", "arguments", "{}")))), "放给我听 is an action");
        check(!AgentLoop.searchThenAct("明年春节放几天假", new JSONArray().put(AgentCoreTest.o("id", "a", "function",
                AgentCoreTest.o("name", "web_search", "arguments", "{}")))), "放几天假 is a question");
        check(!AgentLoop.searchThenAct("B站最近有什么新闻", new JSONArray().put(AgentCoreTest.o("id", "a", "function",
                AgentCoreTest.o("name", "web_search", "arguments", "{}")))), "naming B站 in a question is still a question");
    }

    /** Not final, nothing found, not searched, or something else in the round: the model answers as before. */
    static void searchNotFinal() throws Exception {
        AgentCoreTest.Script plain = new AgentCoreTest.Script()
                .calls("web_search", AgentCoreTest.o("query", "周杰伦最火的歌")).text("是《晴天》。");
        Ledger a = AgentCoreTest.loop(plain, new AgentCoreTest.Events(), (w, l) -> true, search("最火的是《晴天》。", true))
                .run(new AgentLoop.Request("周杰伦最火的歌是哪首", "test"));
        check(plain.seen.size() == 2 && "是《晴天》。".equals(a.reply), "a search without final: the model answers");

        AgentCoreTest.Script empty = new AgentCoreTest.Script()
                .calls("web_search", AgentCoreTest.o("query", "某某最近怎么了", "final", true)).text("没查到可靠消息。");
        Ledger b = AgentCoreTest.loop(empty, new AgentCoreTest.Events(), (w, l) -> true, search("没有找到相关的可靠报道。", true))
                .run(new AgentLoop.Request("某某最近怎么了", "test"));
        check(empty.seen.size() == 2, "a final search that found nothing: the model decides what to say");

        AgentCoreTest.Script unsearched = new AgentCoreTest.Script()
                .calls("web_search", AgentCoreTest.o("query", "明天天气", "final", true)).text("明天晴。");
        AgentCoreTest.loop(unsearched, new AgentCoreTest.Events(), (w, l) -> true, search("明天晴。", false))
                .run(new AgentLoop.Request("明天天气怎么样", "test"));
        check(unsearched.seen.size() == 2, "a final search that did not search: the model answers");

        AgentCoreTest.Fake note = new AgentCoreTest.Fake("note_add", Tool.Gate.read());
        AgentCoreTest.Script two = new AgentCoreTest.Script()
                .calls("web_search", AgentCoreTest.o("query", "明天天气", "final", true), "note_add", AgentCoreTest.o("text", "带伞"))
                .text("明天有雨，记下了带伞。");
        AgentCoreTest.loop(two, new AgentCoreTest.Events(), (w, l) -> true, search("明天有雨。", true), note)
                .run(new AgentLoop.Request("明天天气怎么样，记一下带伞", "test"));
        check(two.seen.size() == 2, "a final search beside another call: the model answers both");

        JSONArray calls = new JSONArray().put(AgentCoreTest.o("id", "a", "function",
                AgentCoreTest.o("name", "web_search", "arguments", "{\"query\":\"x\",\"final\":true}")));
        check(AgentLoop.searchAnswer(calls, new JSONObject[]{AgentCoreTest.o("ok", false, "answer", "x", "searched", true)}) == null,
                "a failed final search is no answer");
        check(AgentLoop.searchAnswer(null, null) == null, "no calls, no answer");
    }
}
