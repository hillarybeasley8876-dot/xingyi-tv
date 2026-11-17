package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** heard-like (2026-11-07): names XiaoAi's recognition wrote another way, matched against what earlier turns said and
 * answered (Heard); and a why-question that a data tool's own answer must not close. */
final class HeardChecks {
    private HeardChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    /** The 30-day log's answer to 「最近为什么地震了」 on 2026-11-07 14:59, read out as if it were the reason. */
    private static final String QUAKE = "11 月 7 日 14 时 32 分，四川宜宾市兴文县 4.2 级地震，震源深度 10 公里。";

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "heard checks threw " + e);
        }
    }

    static void checks() throws Exception {
        examples();
        collecting();
        matching();
        ordering();
        limits();
        safe();
        wholeRuns();
        echoedPhrases();
        answeredJoins();
        mainReadings();
        quoteShape();
        homophones();
        boundaryWords();
        replay();
        whyRule();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Earlier turns, newest first, each {@code said, replied, when}. */
    private static List<String[]> earlier(String... turn) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i + 2 < turn.length; i += 3) out.add(new String[]{turn[i], turn[i + 1], turn[i + 2]});
        return out;
    }

    private static List<Heard.Hint> hints(String utterance, String... turn) {
        return Heard.hints(utterance, earlier(turn));
    }

    private static String line(String utterance, String... turn) {
        return Heard.line(hints(utterance, turn));
    }

    /** 「「MAX」可能是刚才说过的「MINIMAX」」 */
    private static String hint(String said, String meant, String when) {
        return "「" + said + "」可能是" + when + "说过的「" + meant + "」";
    }

    // ---- the eight requests from the TV ----------------------------------------------------------

    /** 2026-11-06 12:20: 「查询MAX最新的股价」 answered MediaAlpha, because 11:56 had said 「MINI MAX」. */
    private static void examples() {
        String max = line("查询MAX最新的股价", "查一下MINI MAX最新的股价", "MINIMAX 155.80 港元，跌 8.46%（11 月 6 日收盘）。", "刚才");
        check(hint("MAX", "MINIMAX", "刚才").equals(max), "「查询MAX最新的股价」 after 「MINI MAX」: " + max);
        // 11-07 15:00: 「嗯TIBA这个人」 looked up a stock called TIBA; 11-06 had worked out Tibo Sottiaux
        String tiba = line("嗯TIBA这个人", "TI BO这个人",
                "你说的应该是 Tibo Sottiaux，X 账号 @thsottiaux，在 OpenAI 负责 Codex。", "11月6日");
        check(hint("TIBA", "Tibo", "11月6日").equals(tiba), "「嗯TIBA这个人」 after 「TI BO」: " + tiba);
        // 14:57: 质朴 is 智谱, whose quote 星仪 had just read out at 14:59
        String zhipu = line("查询质朴和MINIMAX股价并且进行比较", "查询制储的股价", "智谱 426.20 港元，跌 10.46%（11 月 6 日收盘）。", "刚才");
        check(hint("质朴", "智谱", "刚才").equals(zhipu), "「查询质朴和MINIMAX股价」: only 质朴, as 智谱: " + zhipu);
        // the user said the name right this time
        String right = line("我说的是MINI MAX最新的股价", "查一下MINI MAX最新的股价", "MINIMAX 155.80 港元，跌 8.46%。", "刚才");
        check("".equals(right), "「我说的是MINI MAX最新的股价」 says it right: " + right);
        String why = line("最近为什么地震了", "查询制储的股价", "智谱 426.20 港元，跌 10.46%（11 月 6 日收盘）。", "刚才");
        check("".equals(why), "「最近为什么地震了」 has no name in it: " + why);
        String song = line("播放林俊杰的歌", "放一首林俊杰的歌", "在放《江南》，歌手是「林俊杰」。", "刚才");
        check("".equals(song), "「播放林俊杰的歌」 says 林俊杰 right: " + song);
        String bili = line("打开B站", "打开B站看看", "已经打开哔哩哔哩。", "刚才");
        check("".equals(bili), "「打开B站」: B站 is not three letters, 打开 is no name: " + bili);
        String maotai = line("茅台股价", "查一下茅台的股价", "贵州茅台 1453.08 元，涨 0.52%。", "刚才");
        check("".equals(maotai), "「茅台股价」: 茅台 is inside 贵州茅台, said right: " + maotai);
    }

    // ---- what counts as a name in an earlier turn -------------------------------------------------

    private static void collecting() {
        // 星仪's answers spell names right, in every kind of quote
        String[] quoted = {"《智谱》今天涨了。", "找到了「智谱」。", "找到了“智谱”。", "找到了\"智谱\"。"};
        String[] kinds = {"《》", "「」", "“”", "\"\""};
        for (int i = 0; i < quoted.length; i++) {
            String got = line("智普的股价", "查一下", quoted[i], "刚才");
            check(hint("智普", "智谱", "刚才").equals(got), "a name in " + kinds[i] + ": " + got);
        }
        // a stock quote's name, in every unit a quote is read out in
        String[] quotes = {"智谱 426.20 港元。", "贵州茅台 1453.08 元。", "苹果 229.98 美元。", "上证 3950.12 点。"};
        String[] asks = {"智普的股价", "贵州毛台的股价", "平果的股价", "上正的股价"};
        String[] words = {"智普", "贵州毛台", "平果", "上正"}, names = {"智谱", "贵州茅台", "苹果", "上证"};
        for (int i = 0; i < quotes.length; i++) {
            String got = line(asks[i], "查一下", quotes[i], "刚才");
            check(hint(words[i], names[i], "刚才").equals(got), "the name before a quote in " + quotes[i] + ": " + got);
        }
        check("".equals(line("智普的股价", "查一下", "智谱 港元。", "刚才")), "a unit with no number is no quote");
        check("".equals(line("智普的股价", "查一下", "155.80 港元。", "刚才")), "a price with no name before it");
        // a name said in two words is the joined name as well, but only where 星仪's answer spells it
        String tibo = line("TIBE这个人", "TI BO这个人", "Tibo Sottiaux 在 OpenAI。", "刚才");
        check(hint("TIBE", "Tibo", "刚才").equals(tibo), "「TI BO」 answered 「Tibo」: " + tibo);
        check("".equals(line("TIBE这个人", "TI BO这个人", "好的。", "刚才")), "…and is not a name when only the user said it");
        String joined = line("INIMAX的股价", "查一下MINI MAX最新的股价", "MINI MAX 155.80 港元。", "刚才");
        check(hint("INIMAX", "MINIMAX", "刚才").equals(joined), "「MINI MAX」 answered is the name MINIMAX as well: " + joined);
        String both = line("查询MAX最新的股价", "查一下MINI MAX最新的股价", "MINI MAX 155.80 港元。", "刚才");
        check(hint("MAX", "MINIMAX", "刚才").equals(both), "MINI and MAX are names too, but the word as it stands is not a hint: " + both);
        // everyday words are never names
        check("".equals(line("HTT是什么", "HTT怎么用", "这是 https 开头的地址。", "刚才")), "https is not a name");
        check("".equals(line("HTTQ协议", "HTTQ是什么", "这是 http 协议。", "刚才")), "http is not a name either");
        check("".equals(line("APPL怎么用", "APPL是什么", "打开 the app and the tv。", "刚才")), "the, app, and and tv are not names");
        // two letters are not a name, either way round
        check("".equals(line("MX的股价", "MX是什么", "MINIMAX 155.80 港元。", "刚才")), "a two-letter word of the request is not looked at");
        check("".equals(line("UPS是什么", "UPS是什么", "他是 UP 主。", "刚才")), "a two-letter name is not a name");
        // a Chinese name is only ever taken from 星仪's answer
        check("".equals(line("智普的股价", "智普的股价", "没找到这只股票。", "刚才")), "a Chinese name is never taken from what the user said");
        check(hint("智普", "智谱", "刚才").equals(line("智普的股价", "智普的股价", "智谱 426.20 港元。", "刚才")), "…only from the answer");
        String twelve = line("质朴质朴", "查一下", "《智谱智谱智谱智谱智谱智谱》。", "刚才");
        check(!twelve.isEmpty(), "a twelve-character quoted name is a name: " + twelve);
        check("".equals(line("质朴质朴", "查一下", "《智谱智谱智谱智谱智谱智谱智》。", "刚才")), "thirteen characters is not a name");
    }

    // ---- when a word is a name heard another way ---------------------------------------------------

    private static void matching() {
        check(hint("TIBA", "TIBO", "刚才").equals(line("TIBA", "", "TIBO", "刚才")), "four letters, one wrong: TIBA is TIBO");
        check("".equals(line("TIBA", "", "TABLE", "刚才")), "TIBA is not TABLE: no prefix, no suffix, not the same length");
        check("".equals(line("CAT", "", "CAR", "刚才")), "three letters are too short for the one-letter rule");
        check(hint("CATS", "CARS", "刚才").equals(line("CATS", "", "CARS", "刚才")), "…four letters are enough: CATS is CARS");
        check(hint("MAX", "MINIMAX", "刚才").equals(line("MAX", "", "MINIMAX", "刚才")), "MAX is the end of MINIMAX");
        check(hint("MINI", "MINIMAX", "刚才").equals(line("MINI", "", "MINIMAX", "刚才")), "MINI is the start of MINIMAX");
        check("".equals(line("INI", "", "MINIMAX", "刚才")), "INI is neither the start nor the end of MINIMAX");
        check(hint("MINIMAZ", "MINIMAX", "刚才").equals(line("MINIMAZ", "", "MINIMAX", "刚才")), "one letter wrong in a long name");
        check("".equals(line("MAX", "", "MAX", "刚才")), "a name said exactly as it stands is no hint");
        check("".equals(line("max", "", "MAX", "刚才")), "…whatever case it is said in");
        check(hint("质朴", "智谱", "刚才").equals(line("质朴", "", "《智谱》", "刚才")), "「质朴」 sounds like 智谱");
        check("".equals(line("支付", "", "《智谱》", "刚才")), "「支付」 does not sound like 智谱");
        check(hint("毛台", "茅台", "刚才").equals(line("毛台", "", "茅台 1453.08 元。", "刚才")), "「毛台」 sounds like 茅台");
        check(hint("贵州毛台", "贵州茅台", "刚才").equals(line("贵州毛台", "", "贵州茅台 1453.08 元。", "刚才")), "a whole four-character name heard wrong");
        check("".equals(line("MAX和MINIMAX", "", "MINIMAX", "刚才")), "no hint when the name is in the request as well");
        check("".equals(line("质朴和智谱", "", "《智谱》", "刚才")), "…and the same for a Chinese name");
        // 2026-11-09 review: digits are said on purpose; the right name said again is no hint toward the mishearing
        check("".equals(line("切到HDMI2", "切到HDMI1", "已切到 HDMI1。", "刚才")), "HDMI2 is not HDMI1 heard wrong");
        check("".equals(line("CCTV13", "", "CCTV1 今晚 19 点 新闻联播。", "刚才")), "CCTV13 is not CCTV1 cut short");
        check("".equals(line("IPHONE17", "", "IPHONE16 发布于 2024 年。", "刚才")), "IPHONE17 is not IPHONE16");
        check("".equals(line("TIBO最近发了什么", "TIBA这个人", "你说的应该是 TIBO（Thibault Sottiaux）。", "刚才")),
                "TIBO said right after TIBA was answered as TIBO: no hint back to TIBA");
        check("".equals(line("智谱的股价", "", "你说的「质朴」应该是《智谱》。", "刚才")),
                "智谱 said right after 「质朴」 was answered as 智谱: no hint back to 质朴");
        check(hint("TIBA", "TIBO", "刚才").equals(line("TIBA", "", "TIBO", "刚才")), "TIBA heard again still points at TIBO");
    }

    // ---- which turn's name is taken ---------------------------------------------------------------

    private static void ordering() {
        String newest = line("质朴的股价", "查一下志普的股价", "智谱 426.20 港元。", "刚才",
                "查一下制普的股价", "志普 100.00 港元。", "11月5日");
        check(hint("质朴", "智谱", "刚才").equals(newest), "the newest turn's name wins: " + newest);
        String older = line("质朴的股价", "查一下制普的股价", "志普 100.00 港元。", "11月5日",
                "查一下志普的股价", "智谱 426.20 港元。", "刚才");
        check(hint("质朴", "志普", "11月5日").equals(older), "…and the other way round: " + older);
        String answer = line("TIBA这个人", "TI BO这个人", "Tibo Sottiaux 在 OpenAI。", "刚才");
        check(hint("TIBA", "Tibo", "刚才").equals(answer), "the answer's spelling wins over the user's own: " + answer);
        String two = line("TIBA和质朴", "查一下制储的股价", "智谱 426.20 港元。", "刚才",
                "TI BO这个人", "Tibo Sottiaux 在 OpenAI。", "11月5日");
        check((hint("质朴", "智谱", "刚才") + "；" + hint("TIBA", "Tibo", "11月5日")).equals(two),
                "the newest turn's hint comes first, whatever order the words are in: " + two);
    }

    // ---- how many hints, and what never throws ----------------------------------------------------

    private static void limits() {
        // 「TIBA COGA BEDA…」 would be one run of the request; the words are kept apart by Chinese in between
        List<Heard.Hint> six = hints("TIBA和COGA和BEDA和GABA和ZEFA和DELMA", "", "TIBO CODA BETA GAMA ZETA DELTA", "刚才");
        check(six.size() == 4, "six misheard words give at most four hints: " + Heard.line(six));
        check("TIBA".equals(six.get(0).said) && "TIBO".equals(six.get(0).meant), "the first four, as they were said: " + six);
        check(hints("TIBA和TIBA", "", "TIBO", "刚才").size() == 1, "the same word twice gives one hint");
        List<Heard.Hint> over = hints("质朴质朴", "查一下", "《智谱智谱》。", "刚才");
        check(over.size() == 1 && "质朴质朴".equals(over.get(0).said), "words that overlap give one hint between them: " + Heard.line(over));
        check("".equals(Heard.line(new ArrayList<Heard.Hint>())), "no hints, no line");
        check("".equals(Heard.line(null)), "no hints at all, no line");
        Heard.Hint h = new Heard.Hint("MAX", "MINIMAX", "刚才");
        check("MAX".equals(h.said) && "MINIMAX".equals(h.meant) && "刚才".equals(h.when), "a hint carries the word, the name and when");
        check(hint("TIBA", "TIBO", "刚才").equals(line("TIBA", "", "TIBO", "")), "a turn with no when reads as 刚才");
    }

    private static void safe() {
        check(Heard.hints(null, earlier("a", "b", "c")).isEmpty(), "no utterance");
        check(Heard.hints("", earlier("a", "b", "c")).isEmpty(), "an empty utterance");
        check(Heard.hints("MAX", null).isEmpty(), "no earlier turns");
        check(Heard.hints("MAX", new ArrayList<String[]>()).isEmpty(), "no earlier turns at all");
        check(Heard.hints("MAX", earlier(null, null, null)).isEmpty(), "a turn that says nothing");
        List<String[]> broken = new ArrayList<>();
        broken.add(null);
        broken.add(new String[]{null, null, null});
        broken.add(new String[]{"TI BO", "TIBO"});
        String got = Heard.line(Heard.hints("TIBA这个人", broken));
        check(hint("TIBA", "TIBO", "刚才").equals(got), "a turn with no answer and no when, and a null turn: " + got);
        check("".equals(line("质朴", "质朴", null, null)), "nothing answered, nothing hinted");
        check("".equals(line("", "", "", "")), "nothing anywhere");
    }

    // ---- round 2: a run of Latin words of the request is one word ------------------------------------

    /** 「MINI MAX」 is MINIMAX said in two words: the run is the word, MINI and MAX on their own are not looked at. */
    private static void wholeRuns() {
        // 11-05 22:58: 「OPEN AI」 is OpenAI said right — the old rule read OPEN on its own and matched OPPOOpen
        String oppo = line("我想听的是OPEN AI最新的动向", "我说的是想听OPPO OPEN AI这个公司的",
                "你是想在电视上播放 OPPO Open AI 相关的视频，还是想听我讲讲？", "刚才");
        check("".equals(oppo), "「OPEN AI」 is one word, said right: no hint — " + oppo);
        // 11-07 11:56: 「MINI MAX」 is one name, not MINI (which used to be matched against Gemini)
        String gemini = line("查一下MINI MAX最新的股价", "查一下", "第一名是 Gemini 4 Carbon High。", "刚才");
        check("".equals(gemini), "「MINI MAX」 is not MINI, so Gemini is never matched — " + gemini);
        // the run as a whole is what is compared
        String opena = line("在推特上搜一下OPEN A下一次重置时间", "看一下推特", "没查到关于 OpenAI 的公告。", "11月5日");
        check(hint("OPENA", "OpenAI", "11月5日").equals(opena), "「OPEN A」 is the word OPENA, one letter off OpenAI: " + opena);
        String maz = line("查一下MINI MAZ最新的股价", "查一下", "MINI MAX 155.80 港元。", "刚才");
        check(hint("MINIMAZ", "MINIMAX", "刚才").equals(maz), "「MINI MAZ」 is the word MINIMAZ: " + maz);
        // three words are one run as well, and a single word is still a word of its own
        String h3 = line("打开B站搜索MINI MAX H3相关的视频", "打开B站", "已经打开“MINI MAX H3”里播放量最高的视频。", "刚才");
        check("".equals(h3), "「MINI MAX H3」 is one run and the name as it stands: " + h3);
        check(hint("MAX", "MINIMAX", "刚才").equals(line("查询MAX最新的股价", "", "MINIMAX 155.80 港元。", "刚才")),
                "a single Latin word of the request is still looked at");
        // anything but a single space between two words is not one run: 「MINI、MAX」 stays two words of the request
        List<Heard.Hint> apart = hints("MINI、MAX的股价", "", "MINIMAX 155.80 港元。", "刚才");
        check(apart.size() == 2, "「MINI、MAX」 is two words of the request, not one run: " + Heard.line(apart));
    }

    // ---- round 2: a joined run is a name only in 星仪's answer ----------------------------------------

    /** The recognition writes the name in two words, so only an answer's spelling joins them into one name. */
    private static void answeredJoins() {
        String said = line("MINIMAZ的股价", "查一下MINI MAX最新的股价", "好的。", "刚才");
        check("".equals(said), "「MINI MAX」 said by the user is not the name MINIMAX: " + said);
        String answered = line("MINIMAZ的股价", "查一下MINI MAX最新的股价", "MINI MAX 155.80 港元。", "刚才");
        check(hint("MINIMAZ", "MINIMAX", "刚才").equals(answered), "…but it is when 星仪's answer spelled it: " + answered);
        String single = line("TIBE这个人", "TIBO这个人", "好的。", "刚才");
        check(hint("TIBE", "TIBO", "刚才").equals(single), "a single word the user said is still a name: " + single);
        String both = line("TIBE这个人", "TI BO这个人", "TIBO 100.00 港元。", "刚才");
        check(hint("TIBE", "TIBO", "刚才").equals(both), "the answer's joined run and its single words are both names: " + both);
    }

    // ---- round 2: Chinese sounds by the main reading -------------------------------------------------

    /** 说 is shuo (yue is rare) and 行 is xing (hang is rare): a rare reading must not make two words sound alike. */
    private static void mainReadings() {
        check("".equals(line("我说的是为什么最近跌这么狠", "查一下", "《月的》涨了。", "刚才")),
                "「说的」 is not 「月的」: 说 is shuo, yue is a rare reading");
        check("".equals(line("银行股价", "查一下", "《银杭》涨了。", "刚才")),
                "「银行」 is not 「银杭」: 行 is xing, hang is a rare reading");
        check(hint("质朴", "智谱", "刚才").equals(line("质朴的股价", "查一下", "《智谱》涨了。", "刚才")),
                "「质朴」 is still 智谱: both are zhi pu on the first reading");
        check(Pinyin.sameMainSound("质朴", "智谱"), "sameMainSound: 质朴 / 智谱 (zhi pu)");
        check(!Pinyin.sameMainSound("说的", "月的"), "sameMainSound: 说的 / 月的 is not the same sound");
        check(!Pinyin.sameMainSound("银行", "银杭"), "sameMainSound: 银行 / 银杭 is not the same sound");
        check(Pinyin.sameSound("说的", "月的") && Pinyin.sameSound("银行", "银杭"), "…though sameSound still says they are");
        check(!Pinyin.sameMainSound(null, null) && !Pinyin.sameMainSound("质朴", null), "sameMainSound of nothing is false");
        check(!Pinyin.sameMainSound("质朴", "智谱AI"), "sameMainSound needs the same number of characters");
        check(Pinyin.mainSoundIndex("质朴", "智谱") == 0, "mainSoundIndex: 质朴 in 智谱 is 0");
        check(Pinyin.mainSoundIndex("毛台", "贵州茅台") == 2, "mainSoundIndex: 毛台 in 贵州茅台 is 2");
        check(Pinyin.mainSoundIndex("说的", "月的") < 0, "mainSoundIndex: 说的 is nowhere in 月的");
        check(Pinyin.mainSoundIndex(null, "智谱") < 0 && Pinyin.mainSoundIndex("智谱", null) < 0, "mainSoundIndex of nothing is -1");
        check(Pinyin.mainSoundIndex("质朴质朴", "智谱") < 0, "mainSoundIndex: a is longer than b");
    }

    // ---- round 2: a quote's name is exactly what Quotes.speak writes -----------------------------------

    /** 2–8 characters at the start of the text or after 「：；，。、（」, then 「 <number> <unit>」 — nothing else. */
    private static void quoteShape() {
        String[] before = {"：", "；", "，", "。", "、", "（", "「"};
        for (String p : before) {
            String got = line("质朴的股价", "查一下", "查到的" + p + "智谱 426.20 港元。", "刚才");
            check(hint("质朴", "智谱", "刚才").equals(got), "a quote's name right after " + p + ": " + got);
        }
        check("".equals(line("质朴的股价", "查一下", "今天智谱 426.20 港元。", "刚才")),
                "a name in the middle of a sentence with no mark before it is not a quote's name");
        check("".equals(line("质朴的股价", "查一下", "智谱的历史峰值是1330.00港元。", "刚才")),
                "「的历史峰值是1330.00港元」 is a price inside a sentence, not a name");
        check("".equals(line("质朴的股价", "查一下", "智谱426.20港元。", "刚才")), "a quote with no spaces is not what speak writes");
        check("".equals(line("质朴的股价", "查一下", "智谱  426.20 港元。", "刚才")), "…nor with two spaces");
        check("".equals(line("质朴的股价", "查一下", "智谱 426.20 港币。", "刚才")), "…nor with a unit speak never writes");
        check(hint("质朴", "智谱", "刚才").equals(line("质朴的股价", "查一下", "智谱 426.20 港元，跌 10.46%。", "刚才")),
                "the first quote of an answer is a name");
        String second = line("质朴的股价", "查一下", "上证指数 3636.80 点，跌 1.44%；智谱 426.20 港元，跌 10.46%。", "刚才");
        check(hint("质朴", "智谱", "刚才").equals(second), "…and so is the second, after the ；: " + second);
    }

    // ---- round 2: a phrase the answer itself wrote, repeated by the user, is said right --------------------

    /** 「OPEN AI」 out of the answer's 「OPPO Open AI」 is OpenAI said right; one word out of a run is still a hint. */
    private static void echoedPhrases() {
        String two = line("查一下MINI MAX的股价", "查一下", "已经打开“MINI MAX H3”里播放量最高的视频。", "刚才");
        check("".equals(two), "「MINI MAX」 repeats two words of the answer's 「MINI MAX H3」: " + two);
        String whole = line("OPPOOPENAI的动向", "查一下", "你是说 OPPO Open AI 吗？", "刚才");
        check("".equals(whole), "…and so is the answer's whole run: " + whole);
        String off = line("OPPOOPENAZ的动向", "查一下", "你是说 OPPO Open AI 吗？", "刚才");
        check(hint("OPPOOPENAZ", "OPPOOpenAI", "刚才").equals(off), "one letter off is still a name heard wrong: " + off);
        // what the user said is never an echo: only 星仪's answers spell a name
        String said = line("查一下MINI MAX的股价", "查一下MINI MAX最新的股价", "MINIMAZ 155.80 港元。", "刚才");
        check(hint("MINIMAX", "MINIMAZ", "刚才").equals(said), "a phrase only the user said is not an echo: " + said);
        check(hint("MAX", "MINIMAX", "刚才").equals(line("查询MAX的股价", "查一下", "MINI MAX 155.80 港元。", "刚才")),
                "one word of the answer's run is still looked at on its own");
    }

    // ---- round 3: an everyday word is not an earlier name (2026-11-09 review) -------------------------

    /** A two-character name an answer spells (《稻香》, 「蔚来 5.12 美元」) is not every two characters that sound like
     * it: 「未来几天天气」 is not 蔚来, 「马斯克他说了什么」 is not 《她说》. And a character Pinyin has no reading for
     * (玥 珺 镕) is compared as itself, so 「王玥」 is never 「王珺」. */
    private static void homophones() {
        // 1. an everyday word of the request is not the two-character name an earlier answer spelled
        check("".equals(line("未来几天天气", "查一下", "蔚来 5.12 美元。", "刚才")),
                "「未来几天天气」 after a 蔚来 quote is not 蔚来 heard wrong");
        check("".equals(line("未来三天会下雨吗", "查一下", "蔚来 5.12 美元。", "刚才")),
                "「未来三天会下雨吗」 after a 蔚来 quote is not 蔚来 either");
        check("".equals(line("明天到香港的天气怎么样", "查一下", "网易云正在播放周杰伦《稻香》。", "刚才")),
                "「到香」 is a piece of two words, not 《稻香》");
        check("".equals(line("马斯克他说了什么", "查一下",
                        "电视已经开着了，屏幕亮着，网易云音乐的《她说》还是暂停状态。", "刚才")),
                "「他说」 is a piece of two words, not 《她说》");
        check("".equals(line("你也去吗", "查一下", "网易云正在播放周杰伦《夜曲》。", "刚才")),
                "「也去」 in 「你也去吗」 is not 《夜曲》");
        check("".equals(line("华海药业的股价", "查一下", "网易云正在播放《花海》。", "刚才")),
                "「华海」 in 「华海药业」 is not 《花海》");

        // 2. standsAlone itself: a slice of two words is not a word, a word of the request is
        check(!Heard.standsAlone("未来几天天气", 0, 2), "standsAlone: 未来 is glued to 几天");
        check(!Heard.standsAlone("明天到香港的天气怎么样", 2, 4), "standsAlone: 到香 crosses two words");
        check(!Heard.standsAlone("马斯克他说了什么", 3, 5), "standsAlone: 他说 crosses two words");
        check(!Heard.standsAlone(null, 0, 2), "standsAlone: nothing is never a word");
        check(!Heard.standsAlone("质朴", 1, 3), "standsAlone: a stretch past the end of the request");
        check(Heard.standsAlone("查询质朴和", 2, 4), "standsAlone: 查询 leads 质朴, 和 joins what follows");
        check(Heard.standsAlone("TIBA和质朴", 5, 7), "standsAlone: 和 joins 质朴 to the Latin name before it");
        check(Heard.standsAlone("质朴", 0, 2), "standsAlone: the whole request is a word");
        check(Heard.standsAlone("质朴股价", 0, 2), "standsAlone: 股 follows 质朴");
        check(Heard.standsAlone("我说的是质朴吗", 4, 6), "standsAlone: 是 and 吗 join on both sides");

        // 3. the real hints are untouched
        check(hint("质朴", "智谱", "刚才").equals(line("质朴股价", "查一下", "智谱 426.20 港元。", "刚才")),
                "「质朴股价」 is still 智谱: 股 follows it");
        check(hint("质朴", "智谱", "刚才").equals(line("质朴今天为什么跌", "查一下", "智谱 426.20 港元。", "刚才")),
                "「质朴今天为什么跌」 is still 智谱: 今天 follows it");
        check(hint("毛台", "茅台", "刚才").equals(line("查一下毛台", "查一下", "茅台 1453.08 元。", "刚才")),
                "「查一下毛台」 is still 茅台: 一下 leads it");
        check(hint("质朴", "智谱", "刚才").equals(line("查询质朴和MINIMAX股价并且进行比较", "查一下",
                        "智谱 426.20 港元。", "刚才")),
                "「查询质朴和MINIMAX股价并且进行比较」 is still 智谱");
        check(hint("MAX", "MINIMAX", "刚才").equals(line("查询MAX最新的股价", "查一下", "MINIMAX 155.80 港元。", "刚才")),
                "「查询MAX最新的股价」 is still MINIMAX");

        // 4. a character the pinyin table has no reading for is compared as itself
        check(!Pinyin.sameMainSound("王玥", "王珺"), "王玥 is not 王珺: 玥 and 珺 have no reading and differ");
        check(!Pinyin.sameSound("王玥", "王珺"), "…not on any reading either");
        check(!Pinyin.sameMainSound("王堃", "汪昇"), "王堃 is not 汪昇: two rare characters differ");
        check(Pinyin.sameMainSound("王玥", "汪玥"), "王玥 is 汪玥: a rare character equals itself");
        check(Pinyin.mainSoundIndex("朱镕基", "主机游戏") < 0, "朱镕基 is nowhere in 主机游戏: 镕 is not 机");
        check(Pinyin.soundIndex("朱镕基", "主机游戏") < 0, "…not on any reading either");
        check(Pinyin.keptLength("王玥") == 2, "keptLength: 王玥 keeps both characters");
        check(Pinyin.keptLength("朱镕基") == 3, "keptLength: 朱镕基 keeps all three");
        check(Pinyin.keptLength("MINI MAX") == 7, "keptLength: MINI MAX keeps seven letters");
        check(Pinyin.keptLength("，。") == 0, "keptLength: punctuation keeps nothing");
        check(Pinyin.keptLength(null) == 0, "keptLength: nothing keeps nothing");
        check("".equals(line("王玥是谁", "查一下", "你说的应该是「王珺」。", "刚才")), "「王玥是谁」 is not 「王珺」");
        check("".equals(line("朱镕基是谁", "查一下", "最近的「主机游戏」卖得最好。", "刚才")),
                "「朱镕基是谁」 is not 「主机游戏」");
    }

    // ---- round 4: the boundary words of the way stocks and songs are really asked for (2026-11-09) -----

    /** The real requests of the TV's log again: 「质朴港股」「那毛台呢」「放一首道香」「未来汽车股价」 are the name
     * heard another way, while a word of the request that only crosses two of its words still is not. */
    private static void boundaryWords() {
        String zhipu = "智谱 426.20 港元。", maotai = "茅台 1453.08 元。", daoxiang = "网易云正在播放《稻香》。";
        String weilai = "蔚来 5.12 美元。";

        // 1. what a request really puts after a stock's name
        String[] after = {"质朴港股", "质朴市值多少", "质朴上市了吗", "质朴这只股票怎么样", "质朴还是毛台"};
        for (String s : after) {
            String got = line(s, "查一下", zhipu, "刚才");
            check(hint("质朴", "智谱", "刚才").equals(got), "「" + s + "」 is still 智谱: " + got);
        }
        String car = line("未来汽车股价", "查一下", weilai, "刚才");
        check(hint("未来", "蔚来", "刚才").equals(car), "「未来汽车股价」 is 蔚来 heard wrong: " + car);

        // 2. what a request really puts before a name
        String[] before = {"那毛台呢", "买点毛台"};
        for (String s : before) {
            String got = line(s, "查一下", maotai, "刚才");
            check(hint("毛台", "茅台", "刚才").equals(got), "「" + s + "」 is still 茅台: " + got);
        }
        String[] songs = {"放一首道香", "放个道香吧"};
        for (String s : songs) {
            String got = line(s, "查一下", daoxiang, "刚才");
            check(hint("道香", "稻香", "刚才").equals(got), "「" + s + "」 is still 《稻香》: " + got);
        }

        // 3. and a slice of two words of the request is still no name, however the words around it read
        check("".equals(line("到香港", "查一下", daoxiang, "刚才")), "「到香」 in 「到香港」 is not 《稻香》");
        check("".equals(line("未来几天天气", "查一下", weilai, "刚才")), "「未来几天天气」 is not 蔚来 heard wrong");
        check("".equals(line("马斯克他说了什么", "查一下", "网易云音乐的《她说》还是暂停状态。", "刚才")),
                "「他说」 in 「马斯克他说了什么」 is not 《她说》");
        check("".equals(line("未来三天会下雨吗", "查一下", weilai, "刚才")), "「未来三天会下雨吗」 is not 蔚来 either");

        // 4. standsAlone itself: the new words open a side, and nothing else does
        check(Heard.standsAlone("质朴港股", 0, 2), "standsAlone: 港股 follows 质朴");
        check(Heard.standsAlone("质朴市值多少", 0, 2), "standsAlone: 市值 follows 质朴");
        check(Heard.standsAlone("质朴上市了吗", 0, 2), "standsAlone: 上市 follows 质朴");
        check(Heard.standsAlone("质朴这只股票怎么样", 0, 2), "standsAlone: 这只 follows 质朴");
        check(Heard.standsAlone("质朴还是毛台", 0, 2), "standsAlone: 还是 follows 质朴");
        check(Heard.standsAlone("未来汽车股价", 0, 2), "standsAlone: 汽车 follows 未来");
        check(Heard.standsAlone("那毛台呢", 1, 3), "standsAlone: 那 leads 毛台");
        check(Heard.standsAlone("买点毛台", 2, 4), "standsAlone: 买点 leads 毛台");
        check(Heard.standsAlone("放一首道香", 3, 5), "standsAlone: 一首 leads 道香");
        check(Heard.standsAlone("放个道香吧", 2, 4), "standsAlone: 个 leads 道香");
        check(!Heard.standsAlone("到香港", 0, 2), "standsAlone: a bare 港 does not open 到香");
        check(!Heard.standsAlone("到香港", 1, 3), "standsAlone: 到 does not open 香港");
        check(!Heard.standsAlone("质朴港股", 2, 4), "standsAlone: 质朴 does not open 港股");
        check(!Heard.standsAlone("放一首道香", 1, 3), "standsAlone: 放 does open 一首, 道香 does not");
        check(!Heard.standsAlone("未来汽车股价", 2, 4), "standsAlone: 未来 does not open 汽车");
    }

    // ---- the TV's own 30 days, replayed ---------------------------------------------------------------

    /** Every request of the log against the ones before it: only the three names the recognition really wrote
     * another way may be hinted at. */
    private static void replay() throws Exception {
        JSONArray log = new JSONArray(new String(Files.readAllBytes(
                Paths.get("tests/agent/fixtures/history-2026-11-07.json")), StandardCharsets.UTF_8));
        List<String[]> got = new ArrayList<>();
        for (int i = 0; i < log.length(); i++) {
            JSONObject e = log.getJSONObject(i);
            List<String[]> earlier = new ArrayList<>();
            for (int j = i + 1; j < log.length(); j++) {
                JSONObject o = log.getJSONObject(j);
                earlier.add(new String[]{o.optString("said", ""), o.optString("replied", ""), o.optString("when", "")});
            }
            String said = e.optString("said", "");
            String l = Heard.line(Heard.hints(said, earlier));
            if (!l.isEmpty()) got.add(new String[]{said, l});
        }
        StringBuilder seen = new StringBuilder();
        for (String[] g : got) seen.append("\n  「").append(g[0]).append("」 → ").append(g[1]);
        check(got.size() == 3, "the 30-day replay hints at three requests and no more, got " + got.size() + ":" + seen);
        String[] asked = {"在推特上搜一下OPEN A下一次重置时间", "查询MAX最新的股价", "嗯TIBA这个人"};
        String[] want = {hint("OPENA", "OpenAI", "11月5日 23:42"), hint("MAX", "MiniMax", "11月7日 11:57"),
                hint("TIBA", "Tibo", "11月6日 1:57")};
        for (int i = 0; i < asked.length; i++) {
            String l = "";
            for (String[] g : got) if (asked[i].equals(g[0])) l = g[1];
            check(want[i].equals(l), "the replay's hint for 「" + asked[i] + "」 is " + want[i] + " — got " + l + seen);
        }
    }

    // ---- a why-question is never closed by a data tool's own answer --------------------------------

    private static AgentCoreTest.Fake quake() throws Exception {
        AgentCoreTest.Fake f = new AgentCoreTest.Fake("earthquake", Tool.Gate.read());
        f.reply = AgentCoreTest.o("ok", true, "status", "observed", "answer", QUAKE, "final", true);
        return f;
    }

    /** 2026-11-07 14:59: right after the 智谱 quote 「最近为什么地震了」 was answered with China's latest earthquakes. */
    private static void whyRule() throws Exception {
        AgentCoreTest.Script why = new AgentCoreTest.Script()
                .calls("earthquake", AgentCoreTest.o("count", 3, "final", true))
                .text("你问的是股价吧：智谱今天跌了 10%，大股东减持。");
        Ledger t = AgentCoreTest.loop(why, new AgentCoreTest.Events(), (w, l) -> true, quake())
                .run(new AgentLoop.Request("最近为什么地震了", "test"));
        check(why.seen.size() == 2, "a data tool's own final does not answer a why-question: " + why.seen.size() + " rounds");
        check(t.reply.startsWith("你问的是股价吧"), "the model says why instead: " + t.reply);
        check(t.state == Ledger.State.ANSWERED, "…and the task is answered: " + t.state);

        AgentCoreTest.Script plain = new AgentCoreTest.Script()
                .calls("earthquake", AgentCoreTest.o("count", 3, "final", true)).text("这一轮不该被问到");
        Ledger p = AgentCoreTest.loop(plain, new AgentCoreTest.Events(), (w, l) -> true, quake())
                .run(new AgentLoop.Request("最近哪里地震了", "test"));
        check(plain.seen.size() == 1 && QUAKE.equals(p.reply), "the same request without 为什么: the tool's own answer is read out: " + p.reply);

        AgentCoreTest.Fake web = new AgentCoreTest.Fake("web_search", Tool.Gate.read());
        web.reply = AgentCoreTest.o("ok", true, "status", "observed", "answer", "大股东减持。", "searched", true);
        AgentCoreTest.Script searched = new AgentCoreTest.Script()
                .calls("web_search", AgentCoreTest.o("query", "智谱今天为什么跌", "final", true)).text("这一轮不该被问到");
        Ledger s = AgentCoreTest.loop(searched, new AgentCoreTest.Events(), (w, l) -> true, web)
                .run(new AgentLoop.Request("智谱今天为什么跌", "test"));
        check(searched.seen.size() == 1 && "大股东减持。".equals(s.reply), "a web_search marked final still answers a why-question: " + s.reply);

        JSONArray call = new JSONArray().put(AgentCoreTest.o("id", "a", "function",
                AgentCoreTest.o("name", "earthquake", "arguments", "{\"count\":3,\"final\":true}")));
        JSONObject[] said = {AgentCoreTest.o("ok", true, "answer", QUAKE, "final", true)};
        check(AgentLoop.searchAnswer("最近为什么地震了", call, said) == null, "searchAnswer itself: a data tool never closes a why");
        check(QUAKE.equals(AgentLoop.searchAnswer("最近哪里地震了", call, said)), "…without a why it still does");
        check(QUAKE.equals(AgentLoop.searchAnswer(call, said)), "…and the round without the request is unchanged");
    }
}
