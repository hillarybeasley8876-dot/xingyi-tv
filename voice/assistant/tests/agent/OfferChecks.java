package xyz.erapi0neer.xingyi.agent;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Speech.settled (2026-11-09): an answer loses the offer or the question it ends on, and nothing else.
 * Speech.deduped (2026-11-10): an answer that tells itself twice keeps the first telling. */
final class OfferChecks {
    private OfferChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static void settles(String in, String out) {
        String got = Speech.settled(in);
        check(out.equals(got), "settled: " + in + " -> " + got + " (want " + out + ")");
    }

    private static void stays(String in) { settles(in, in); }

    private static void dedups(String said, String reply, String want) {
        String got = Speech.deduped(reply, said);
        check(want.equals(got), "deduped: 「" + said + "」 " + reply + " -> " + got + " (want " + want + ")");
    }

    private static void keeps(String said, String reply) { dedups(said, reply, reply); }

    static void run() {
        try {
            checks();
            voiced();
        } catch (Exception e) {
            check(false, "offer checks threw " + e);
        }
    }

    private static void says(String in, String out) {
        String got = Speech.voiced(in);
        check(out.equals(got), "voiced: " + in + " -> " + got + " (want " + out + ")");
    }

    private static void same(String in) { says(in, in); }

    /**
     * Speech.voiced (2026-11-19): what XiaoAi's voice is given, not what the panel shows. 2026-11-17 00:19 the answer
     * said 「留着劲儿给你找片听歌更实在」; XiaoAi read the 儿 as a syllable of its own and the user asked back what had
     * been said. The erhua words people write lose their 儿 for the voice; 儿 that is a word of its own, a name, and
     * anything inside 《》 stay as written.
     */
    static void voiced() throws Exception {
        says("真要挑刺我也能挑，不过没必要——大半夜的，留着劲儿给你找片听歌更实在。",
                "真要挑刺我也能挑，不过没必要——大半夜的，留着劲给你找片听歌更实在。");
        says("等一会儿再说", "等一会再说");
        says("没事儿，慢慢儿来", "没事，慢慢来");
        says("小点儿声", "小点声");
        says("这事儿挺好玩儿的", "这事挺好玩的");
        says("差点儿忘了，待会儿提醒你", "差点忘了，待会提醒你");
        says("“留着劲儿”那句话不是冲你", "“留着劲”那句话不是冲你");
        says("坐着歇会儿，别一直站着", "坐着歇会，别一直站着");
        // 一块儿 loses its 儿 and nothing else: 「一起」 was wrong wherever it is a piece or a part (2026-11-19 review)
        says("咱们一块儿看", "咱们一块看");
        says("这一块儿我不太熟", "这一块我不太熟");
        says("屏幕右下角那一块儿是执行面板", "屏幕右下角那一块是执行面板");
        says("第一块儿拼图在左上角", "第一块拼图在左上角");
        // 儿 that is a word of its own, or the end of a name, stays
        for (String keep : new String[] {"他儿子和女儿都在", "婴儿和幼儿都能看", "儿童节放儿歌", "这儿子真懂事", "这事儿子不知道",
                "两个儿童", "上官婉儿是唐朝人", "赵灵儿", "新生儿", "这儿歌真好听", "那儿媳妇人不错", "混血儿", "托儿所", "少儿频道",
                "明天儿子回来", "今天儿童节", "开会儿童不宜", "薪儿姐", "没有儿化音的句子。", ""})
            same(keep);
        // what the first version changed and should not have (2026-11-19 review, each run against it): a singer or a
        // station whose name ends in 儿, a title outside 《》, a story's 鸟儿们, a word 今儿 / 明儿 only looks like, the
        // short forms that begin with 儿 — and the everyday 这儿 / 那儿 / 哪儿, left as written until heard on the TV
        for (String keep : new String[] {"好的，现在是花儿乐队的《嘻唰唰》", "现在放的是小曲儿的《上邪》", "豆瓣上没找到「爸爸去哪儿」",
                "已忘掉和「小鱼儿」有关的 1 条记忆", "爸爸去哪儿重播", "鸟儿们叽叽喳喳地叫着，花儿们都开了", "鱼儿鱼儿水中游", "鱼儿沟站",
                "别耍小聪明儿", "如今儿不比从前", "明儿个个都得来", "今天儿内科还有号", "著名儿外科专家", "明天儿研所几点开门",
                "每天儿茶素的摄入量", "这儿太吵，去那儿吧", "你现在在哪儿", "今儿个天气不错，明儿见", "有空儿给个信儿，这天儿真冷",
                "他说「等一会儿」就走", "下午三点儿内科开门", "这件事儿研所会处理", "一会儿外科见"})
            same(keep);
        says("这儿有点儿吵，去那儿吧", "这儿有点吵，去那儿吧");
        // a title is read as it is written, and the words around it are still tidied
        same("正在播放《花儿与少年》");
        same("网易云正在放《鱼儿》");
        says("《女儿情》放完了，等一会儿放《花儿为什么这样红》", "《女儿情》放完了，等一会放《花儿为什么这样红》");
        check(Speech.voiced(null).isEmpty(), "voiced: null is nothing to say");
        // the TV's own answers: nothing but erhua changes, titles are kept, and a second pass changes nothing more
        List<String> lines = Files.readAllLines(Paths.get("tests/agent/fixtures/answers-1110.jsonl"), StandardCharsets.UTF_8);
        int answers = 0, changed = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String r = new JSONObject(line).optString("reply");
            if (r.isEmpty()) continue;
            answers++;
            String v = Speech.voiced(r);
            if (!v.equals(r)) changed++;
            boolean ok = v.equals(Speech.voiced(v)) && (r.indexOf('儿') >= 0 || v.equals(r)) && v.length() <= r.length();
            java.util.regex.Matcher t = java.util.regex.Pattern.compile("《[^》]*》").matcher(r);
            while (t.find()) ok &= v.contains(t.group());
            check(ok, "voiced, a real answer: only erhua goes, titles stay: " + r + " -> " + v);
        }
        // some of them do change, and only a few: a voiced that changes nothing, or one that rewrites every tenth answer,
        // is not this one. Not an exact count: the public copy of the fixture is reworded by the exporter (1 here, 4 there).
        check(answers > 100 && changed >= 1 && changed <= answers / 10, "the kept answers were read: " + answers + ", and "
                + changed + " of them lose an erhua for the voice");
        // the one place every spoken word goes through (xingyi/VendorSpeech is Android code: read in the source)
        String vendor = new String(Files.readAllBytes(Paths.get("src/xyz/erapi0neer/xingyi/VendorSpeech.java")), StandardCharsets.UTF_8);
        check(vendor.contains("Speech.voiced(text.trim())"), "VendorSpeech.speak gives XiaoAi the voiced text");
    }

    static void checks() throws Exception {
        // the shapes the TV's answers had (11-05/06), and the usual ones
        settles("这件事网上说法很多，目前没有官方消息。需要我按具体人或单位再帮你核实吗？", "这件事网上说法很多，目前没有官方消息。");
        settles("他昨天发了一条新视频，讲的是自制光刻机。要我去看他的主页动态吗？", "他昨天发了一条新视频，讲的是自制光刻机。");
        settles("公开报道里只有这一条。要是你愿意，我可以改帮你查他公司的正式公告。", "公开报道里只有这一条。");
        settles("已经开始播放《跳楼机》了，要不要再来一首？", "已经开始播放《跳楼机》了。");
        settles("音量已经调到 30，还要再大点吗？", "音量已经调到 30。");
        settles("今天北京晴，最高 15 度，如果需要我可以再查明天的。", "今天北京晴，最高 15 度。");
        settles("明天降温到 5 度。如果明天还下雨，要不要我提醒你带伞？", "明天降温到 5 度。");
        settles("已经在放了。要不要我把音量调大？或者换一首？", "已经在放了。");
        settles("北京今天晴。有什么想知道的随时告诉我。", "北京今天晴。");
        settles("已经暂停了。要我继续放的话说一声。", "已经暂停了。");
        settles("一共 5 期，最新一期是 11 月 7 日。还想看别的吗？", "一共 5 期，最新一期是 11 月 7 日。");
        settles("已经调好了。要不要我再调大一点？随时叫我。", "已经调好了。");
        settles("已经打开网易云音乐了；要我放首歌吗", "已经打开网易云音乐了。");
        settles("他的新歌叫“雪落下的声音”，要我放吗？", "他的新歌叫“雪落下的声音”。");

        // what stays: a question that is the whole answer, one that asks what was meant, the other reading the
        // contract asks for, a hint, a quoted question, a statement with 随时 in it, and too little before the offer
        stays("你说的“新动向”是指星辰亻旅行者的最新动态，还是最近的热点新闻？");
        stays("我没找到“跳楼鸡”。你说的是不是《跳楼机》？");
        stays("网易云上两个人都唱过。要我放周杰伦的，还是林俊杰的？");
        stays("这是米哈游的游戏。若你说的是别的公司，告诉我是谁。");
        stays("这是米哈游的游戏。如果你想找的是另一家，告诉我名字。");
        stays("已经切成伴奏了。要是你问的是原唱，再说一声。");
        stays("已经切到伴奏了。如果你想听原唱，说「切回原唱」就行。");
        stays("屏幕已经是关着的状态，电视没有断电，音乐还暂停着随时能接着放。");
        stays("这一句歌词是：“你还要我怎样？”");
        stays("我是星仪。我可以帮你放歌、查天气、搜 B 站。");
        stays("这件事比预想的复杂，我先停下了。说“星仪继续”可以接着做。");
        stays("好的。要不要我放给你听？");
        stays("要不要我放给你听？");
        stays("");

        // 2026-11-09 review: a condition before the offer goes with it; advice to the user stays; the other reading stays
        // however it is phrased
        settles("这首歌是 LBI利比 唱的。你要是想听原唱的话，要不要我切回去？", "这首歌是 LBI利比 唱的。");
        settles("已经切成伴奏了。想听原唱的话，我可以再切回去。", "已经切成伴奏了。");
        settles("今天晴，最高 15 度，你要是想知道明天的，我可以再查。", "今天晴，最高 15 度。");
        stays("脚扭了先别走动，冰敷十五分钟，把脚垫高。肿得厉害的话，要不要去医院看看？");
        stays("明天降温到 5 度，要不要带件外套？");
        stays("这是原唱。要是你想问的是伴奏版，再说一声。");
        stays("这是米哈游的游戏。如果你想找的是另一家，告诉我一声。");
        settles("音量已经调到 30，还要大一点吗？", "音量已经调到 30。");

        // the loop: a plain answer and a finished task settle; a question asked and a task stopped keep their words
        AgentCoreTest.Script plain = new AgentCoreTest.Script().text("因为瑞利散射，蓝光被空气散射得最多。要不要我再讲讲晚霞为什么是红的？");
        Ledger answered = AgentCoreTest.loop(plain, new AgentCoreTest.Events(), (w, l) -> true)
                .run(new AgentLoop.Request("天空为什么是蓝的", "test"));
        check(answered.state == Ledger.State.ANSWERED && "因为瑞利散射，蓝光被空气散射得最多。".equals(answered.reply),
                "a plain answer ends without its offer: " + answered.state + " " + answered.reply);

        AgentCoreTest.Script asking = new AgentCoreTest.Script()
                .calls("ask_user", AgentCoreTest.o("question", "你想听哪一首？要不要我先放最热门的？"));
        Ledger asked = AgentCoreTest.loop(asking, new AgentCoreTest.Events(), (w, l) -> true)
                .run(new AgentLoop.Request("放首他的歌", "test"));
        check(asked.state == Ledger.State.AWAITING_USER && asked.reply.contains("要不要我先放最热门的"),
                "ask_user keeps its question whole: " + asked.state + " " + asked.reply);

        AgentCoreTest.Script done = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("打开网易云音乐", "打开网易云音乐"), "open_app", AgentCoreTest.o("app", "网易云音乐"))
                .calls("finish", AgentCoreTest.o("status", "done", "reply", "已经打开网易云音乐了，要不要我放首歌？"));
        AgentCoreTest.Fake open = new AgentCoreTest.Fake("open_app", Tool.Gate.act());
        Ledger finished = AgentCoreTest.loop(done, new AgentCoreTest.Events(), (w, l) -> true, open)
                .run(new AgentLoop.Request("打开网易云音乐", "test"));
        check(finished.state == Ledger.State.DONE && "已经打开网易云音乐了。".equals(finished.reply),
                "a finished task ends without its offer: " + finished.state + " " + finished.reply);

        AgentCoreTest.Script failing = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("打开网易云音乐", "打开网易云音乐"), "open_app", AgentCoreTest.o("app", "网易云音乐"))
                .calls("finish", AgentCoreTest.o("status", "failed", "reply", "网易云音乐没打开，要我再试一次吗？"));
        Ledger failed = AgentCoreTest.loop(failing, new AgentCoreTest.Events(), (w, l) -> true,
                new AgentCoreTest.Fake("open_app", Tool.Gate.act())).run(new AgentLoop.Request("打开网易云音乐", "test"));
        check(failed.state != Ledger.State.DONE && failed.reply.contains("要我再试一次吗"),
                "a task that did not get done keeps its words: " + failed.state + " " + failed.reply);

        // the first page was already read out (streamed) and the offer is in it: the reply stops after that page, it is
        // not cut into it (the host would read the answer again from its start)
        final String page = "这件事的来龙去脉是这样的。要不要我再查查别的？";
        AgentCoreTest.Script streamed = new AgentCoreTest.Script().text(page + "随时叫我。");
        AgentLoop withPage = AgentCoreTest.loop(streamed, new AgentCoreTest.Events(), (w, l) -> true);
        withPage.drafts(new AgentLoop.Drafts() {
            @Override public void text(Ledger task, String soFar) { }
            @Override public String read(Ledger task) { return page; }
        });
        Ledger read = withPage.run(new AgentLoop.Request("讲讲这件事", "test"));
        check(read.state == Ledger.State.ANSWERED && page.equals(read.reply),
                "an offer inside the page already read is not cut into: " + read.reply);

        twice();
        askedOffers();
        answers();
    }

    // ---- the TV's own answers: the 30-day history and the 2026-11-10 sweep ---------------------------------

    /** Every real reply through deduped and settled: one answer says itself twice, and what comes off the others is
     * an offer and nothing else. */
    private static void answers() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("tests/agent/fixtures/answers-1110.jsonl"), StandardCharsets.UTF_8);
        List<String[]> twice = new ArrayList<>(), offered = new ArrayList<>();
        int n = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            n++;
            JSONObject e = new JSONObject(line);
            String said = e.optString("said"), reply = e.optString("reply");
            String dedup = Speech.deduped(reply, said);
            if (!dedup.equals(reply)) twice.add(new String[]{said, dedup});
            String settled = Speech.settled(dedup);
            if (!settled.equals(dedup)) offered.add(new String[]{said, dedup.substring(settled.length()), settled});
        }
        check(n == 174, "the fixture holds the TV's 174 real replies, got " + n);
        check(twice.size() == 1 && GANMAO_SAID.equals(twice.get(0)[0]) && GANMAO_ONCE.equals(twice.get(0)[1]),
                "deduped changes the 感冒 answer and no other of the 174:" + shown(twice));
        check(offered.size() == 4, "settled after deduped takes something off four of the 174 and no more:" + shown(offered));
        String[] wants = {"要我去看他的主页动态吗？", "要是你愿意，我可以改帮你查他或叠纸的公开、可核实的正式新闻。",
                "需要我按具体人或单位再帮你核实吗？", "要不我放点林俊杰给你听着，边听边缓一缓？"};
        for (int i = 0; i < Math.min(wants.length, offered.size()); i++) {
            check(offered.get(i)[1].endsWith(wants[i]),
                    "what comes off 「" + offered.get(i)[0] + "」 is the offer 「" + wants[i] + "」, got 「" + offered.get(i)[1] + "」");
        }
    }

    /** Every changed reply, as the check labels carry it. */
    private static String shown(List<String[]> changed) {
        StringBuilder out = new StringBuilder();
        for (String[] c : changed) out.append("\n  「").append(c[0]).append("」 ").append(c[1]);
        return out.toString();
    }

    // ---- 2026-11-10: the answer that was written twice in one reply ---------------------------------------

    /** The two tellings the model wrote for 「感冒了吃什么好」: the first one, and nothing of the second. */
    private static final String GANMAO_SAID = "感冒了吃什么好";
    private static final String GANMAO_TWICE = "感冒了多喝热水，注意休息、别着凉，饮食清淡好消化：小米粥、面条、蒸蛋羹、苹果和梨。"
            + "忌辛辣油腻。病情持续加重或高烧不退，及时去医院。感冒了吃什么好，多喝水多休息，吃清淡易消化的，比如小米粥、"
            + "面条、蒸蛋羹、水果，忌辛辣油腻。病情持续或高烧不退及时就医。";
    private static final String GANMAO_ONCE = "感冒了多喝热水，注意休息、别着凉，饮食清淡好消化：小米粥、面条、蒸蛋羹、苹果和梨。"
            + "忌辛辣油腻。病情持续加重或高烧不退，及时去医院。";

    private static void twice() {
        dedups(GANMAO_SAID, GANMAO_TWICE, GANMAO_ONCE);
        check(Speech.deduped(GANMAO_TWICE, GANMAO_SAID).endsWith("及时去医院。"),
                "the 感冒 answer stops at 「及时去医院。」: " + Speech.deduped(GANMAO_TWICE, GANMAO_SAID));
        // the second telling opens with the question itself, which is what makes it the same answer again
        dedups("北京明天天气怎么样",
                "北京明天多云，最高 12 度，风不大，出门穿件外套就行。北京明天天气怎么样，我再说一遍：多云，12 度，风不大，记得穿外套。",
                "北京明天多云，最高 12 度，风不大，出门穿件外套就行。");
        // what stays: a list whose items repeat words, a line said again (a refrain, not a second answer), a short answer
        keeps("怎么保护嗓子", "第一，多喝温水，少说话，让嗓子好好休息。第二，房间里放个加湿器，别让空气太干，嗓子会舒服些。"
                + "第三，少吃辛辣油腻的东西，多吃水果。");
        keeps("活着是谁写的", "他的代表作有这些。第一部是《活着》，讲的是时代的苦难。第二部是《许三观卖血记》，讲的是时代的苦难。");
        keeps("讲讲这件事", "这一段是重复的测试文字，用来把回答撑到足够长。这一段是重复的测试文字，用来把回答撑到足够长。");
        keeps("今天星期几", "今天是星期一，明天就是星期二了。");
        // the subject said again at the head of the next sentence is not the answer told twice (lead review, 2026-11-10)
        keeps("林俊杰最近有什么新歌", "林俊杰最近发了新歌《无拘》，是永劫无间的周年主题曲，网易云能听。"
                + "林俊杰最近还在准备明年的巡演，第一站定在上海，具体日期还没公布。");
        keeps("湖人昨天赢了吗", "湖人昨天 117 比 109 赢了勇士，詹姆斯拿了 28 分。湖人昨天这场之后排到西部第五，下一场周四客场打雄鹿。");
        // nothing to cut, and nothing to throw
        check("".equals(Speech.deduped(null, "今天星期几")), "deduped(null) is empty: 「" + Speech.deduped(null, "x") + "」");
        check("".equals(Speech.deduped("", "今天星期几")), "deduped(\"\") is empty: 「" + Speech.deduped("", "x") + "」");
        keeps(null, "今天是星期一，明天就是星期二了。");

        // in the loop, an answer told twice is read once (2026-11-10: the TV read the same advice twice)
        AgentCoreTest.Script twice = new AgentCoreTest.Script().text(GANMAO_TWICE);
        Ledger t = AgentCoreTest.loop(twice, new AgentCoreTest.Events(), (w, l) -> true)
                .run(new AgentLoop.Request(GANMAO_SAID, "test"));
        check(t.state == Ledger.State.ANSWERED && GANMAO_ONCE.equals(t.reply),
                "an answer told twice is read once: " + t.state + " " + t.reply);

        // and never into the page XiaoAi has already read out: the second telling goes, the page stays at the head
        final String page = "今天的事先说这么多，路上注意安全，明天会凉一些。";
        AgentCoreTest.Script streamed = new AgentCoreTest.Script().text(page + GANMAO_TWICE);
        AgentLoop withPage = AgentCoreTest.loop(streamed, new AgentCoreTest.Events(), (w, l) -> true);
        withPage.drafts(new AgentLoop.Drafts() {
            @Override public void text(Ledger task, String soFar) { }
            @Override public String read(Ledger task) { return page; }
        });
        Ledger read = withPage.run(new AgentLoop.Request(GANMAO_SAID, "test"));
        check(read.state == Ledger.State.ANSWERED && (page + GANMAO_ONCE).equals(read.reply),
                "the page already read keeps its place at the head: " + read.reply);

        // the second telling is inside the page that was read out: nothing is cut, XiaoAi would only read it again
        AgentCoreTest.Script allRead = new AgentCoreTest.Script().text(GANMAO_TWICE);
        AgentLoop readTwice = AgentCoreTest.loop(allRead, new AgentCoreTest.Events(), (w, l) -> true);
        readTwice.drafts(new AgentLoop.Drafts() {
            @Override public void text(Ledger task, String soFar) { }
            @Override public String read(Ledger task) { return GANMAO_TWICE; }
        });
        Ledger whole = readTwice.run(new AgentLoop.Request(GANMAO_SAID, "test"));
        check(whole.state == Ledger.State.ANSWERED && GANMAO_TWICE.equals(whole.reply),
                "a second telling inside the page already read is not cut into: " + whole.reply);
    }

    // ---- 2026-11-10: the offer said as 「要不我…？」 --------------------------------------------------------

    private static void askedOffers() {
        final String tired = "今天才早上呢，就说累了。昨晚是不是没睡好？先喝点热的，坐着歇会儿，别一睁眼就开始忙。"
                + "要不我放点林俊杰给你听着，边听边缓一缓？";
        settles(tired, "今天才早上呢，就说累了。昨晚是不是没睡好？先喝点热的，坐着歇会儿，别一睁眼就开始忙。");
        check(tired.length() - "要不我放点林俊杰给你听着，边听边缓一缓？".length() == Speech.settled(tired).length(),
                "「我今天好累」 loses exactly its last sentence: " + Speech.settled(tired));
        settles("今天先早点休息吧。要不我给你放首歌？", "今天先早点休息吧。");
        settles("今天有点冷。我给你热点牛奶吧？", "今天有点冷。");
        settles("你先去忙。我帮你把音量调小吧？", "你先去忙。");
        settles("你先歇着。我再给你念一段吧？", "你先歇着。");
        settles("你先歇着。要不这样，我给你放首歌？", "你先歇着。");
        // what stays: advice to the user, the question as the whole answer, too little before it, words inside
        // quotes, and a question about which one
        stays("嗓子疼了三天，先多喝温水，少说话。要不要去医院看看？");
        stays("明天降温到 5 度。要不你加件外套？");
        stays("我给你放首歌？");
        stays("好。要不我给你放首歌？");
        stays("他刚才说“要不我给你放首歌？”");
        stays("网易云上有两个版本。要不我给你放原唱，还是伴奏？");
    }
}
