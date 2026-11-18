package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The oracle for {@link Lexicon} — the lead's file; the job that implements Lexicon may not edit it.
 *
 * <p>The first half replays the TV's own history of 2026-11-05..07 (tests/agent/fixtures/history-2026-11-07.json, 86
 * real turns, oldest first) and then asks what the user asked that week: 「TABLE」 three times before 「TI BO」 was
 * understood, 「TIBA」 the day after, 「MAX」 for MiniMax, 「质朴」 and 「制储」 for 智谱. After the replay each of them
 * must be recognised, and nothing else in those 86 sentences may be hinted at. The second half is made-up exchanges of
 * the same shapes with other names, so that the week above cannot be met by knowing its strings.
 */
final class LexiconOracleChecks {
    private LexiconOracleChecks() { }

    private static int ran;

    private static void check(boolean ok, String label) {
        ran++;
        AgentCoreTest.check(ok, "lexicon oracle: " + label);
    }

    static void run() {
        if (!Lexicon.IMPLEMENTED) {
            AgentCoreTest.check(true, "lexicon oracle: waits for the implementation (Lexicon.IMPLEMENTED is false)");
            return;
        }
        try {
            realWeek();
            otherNames();
            notTied();
            limits();
            safe();
        } catch (Exception e) {
            check(false, "threw " + e);
        }
        System.out.println("LEXICON ORACLE RAN: " + ran + " checks");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static final long T0 = 1_794_000_000_000L; // a moment in November 2026
    private static final long SEC = 1000L, MIN = 60_000L, DAY = 24 * 3600_000L;

    private static String flat(String s) { return s == null ? "" : s.replace(" ", "").toLowerCase(); }

    private static Lexicon.Turn turn(String said, String replied, String state, long at, String... names) {
        return new Lexicon.Turn(said, replied, state, at, new ArrayList<>(Arrays.asList(names)));
    }

    /** Learns the turns one after another, each with the (at most seven) turns before it, as the engine does. */
    private static void learnAll(JSONObject lexicon, List<Lexicon.Turn> turns) {
        for (int i = 0; i < turns.size(); i++) Lexicon.learn(lexicon, new ArrayList<>(turns.subList(Math.max(0, i - 7), i + 1)));
    }

    /** What the word {@code said} (compared without spaces or case) of the request is hinted to be, "" when it is not. */
    private static String meant(JSONObject lexicon, String utterance, String said, long now) {
        for (Heard.Hint h : Lexicon.hints(lexicon, utterance, now)) if (flat(h.said).equals(flat(said))) return h.meant;
        return "";
    }

    /** Whether names() lists {@code name} as a name (not as a spelling of one), whatever its case. */
    private static boolean hasName(JSONArray names, String name) {
        for (int i = 0; i < names.length(); i++) {
            JSONObject o = names.optJSONObject(i);
            if (o != null && flat(o.optString("name")).equals(flat(name))) return true;
        }
        return false;
    }

    private static Set<String> allMeant(JSONObject lexicon, String utterance, long now) {
        Set<String> out = new HashSet<>();
        for (Heard.Hint h : Lexicon.hints(lexicon, utterance, now)) out.add(flat(h.meant));
        return out;
    }

    private static final Pattern WHEN = Pattern.compile("(\\d+)月(\\d+)日 (\\d+):(\\d+)");

    /** The fixture's turns, oldest first; turns of one minute keep the order they were said in. */
    private static List<Lexicon.Turn> week() throws Exception {
        JSONArray a = new JSONArray(new String(Files.readAllBytes(
                Paths.get("tests/agent/fixtures/history-2026-11-07.json")), StandardCharsets.UTF_8));
        List<Lexicon.Turn> out = new ArrayList<>();
        long lastMinute = -1;
        int inMinute = 0;
        for (int i = a.length() - 1; i >= 0; i--) { // the file is newest first
            JSONObject o = a.getJSONObject(i);
            Matcher m = WHEN.matcher(o.optString("when"));
            if (!m.find()) continue;
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
            c.clear();
            c.set(2026, Integer.parseInt(m.group(1)) - 1, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), 0);
            long minute = c.getTimeInMillis();
            inMinute = minute == lastMinute ? inMinute + 1 : 0;
            lastMinute = minute;
            out.add(new Lexicon.Turn(o.optString("said"), o.optString("replied"), "", minute + inMinute * 5 * SEC, new ArrayList<String>()));
        }
        return out;
    }

    // ---- the real week ---------------------------------------------------------------------------------------------

    /** Which names a sentence of that week may be hinted to mean; every other hint is noise. */
    private static Set<String> allowed(String said) {
        String s = said.toUpperCase();
        Set<String> ok = new HashSet<>();
        if (s.contains("TABLE") || s.contains("TIBA") || s.contains("TI BO")) ok.add("tibo");
        if (s.contains("MAX")) ok.add("minimax");
        if (s.contains("质朴") || s.contains("制储") || s.contains("支付")) ok.add("智谱");
        if (s.contains("OPEN")) ok.add("openai");
        return ok;
    }

    private static void realWeek() throws Exception {
        List<Lexicon.Turn> week = week();
        check(week.size() == 86, "the week's 86 turns were read: " + week.size());
        JSONObject lex = Lexicon.empty();
        learnAll(lex, week);
        long now = week.get(week.size() - 1).at + MIN;

        // what that week's exchanges should have taught
        check("Tibo".equals(meant(lex, "看一下TABLE的推特", "TABLE", now)),
                "11-06 00:04: TABLE three times, then 「TI BO 这个人」 was understood: TABLE is Tibo from then on: "
                        + Lexicon.hints(lex, "看一下TABLE的推特", now));
        check("Tibo".equals(meant(lex, "嗯TIBA这个人", "TIBA", now)), "11-07 15:00: TIBA is one letter from a name that came up: "
                + Lexicon.hints(lex, "嗯TIBA这个人", now));
        check("minimax".equals(flat(meant(lex, "查询MAX最新的股价", "MAX", now))),
                "11-07 12:20: 「查询MAX」 got MediaAlpha and was corrected with 「我说的是MINI MAX」: MAX is MiniMax: "
                        + Lexicon.hints(lex, "查询MAX最新的股价", now));
        check("智谱".equals(meant(lex, "查询质朴的股价", "质朴", now)), "11-07 14:57: 质朴 was asked back twice, then 智谱 was quoted: "
                + Lexicon.hints(lex, "查询质朴的股价", now));
        check("智谱".equals(meant(lex, "查询制储的股价", "制储", now)), "11-07 14:59: 「查询制储的股价」 was answered with 智谱's quote: "
                + Lexicon.hints(lex, "查询制储的股价", now));
        check("openai".equals(flat(meant(lex, "在推特上搜一下OPEN A下一次重置时间", "OPEN A", now))),
                "11-06 00:04: 「OPEN A」 was answered about OpenAI: " + Lexicon.hints(lex, "在推特上搜一下OPEN A下一次重置时间", now));
        // spellings nobody has made yet, of a name that comes up
        check("Tibo".equals(meant(lex, "看一下提博的推特", "提博", now)), "a Chinese transliteration that reads ti-bo: "
                + Lexicon.hints(lex, "看一下提博的推特", now));
        check(allMeant(lex, "T I B O 最近发了什么", now).contains("tibo"), "the name said letter by letter: "
                + Lexicon.hints(lex, "T I B O 最近发了什么", now));
        check(Lexicon.hints(lex, "Tibo最近发了什么", now).isEmpty() && Lexicon.hints(lex, "智谱今天多少钱", now).isEmpty(),
                "a name written as the lexicon has it needs no hint");
        String line = Lexicon.line(Lexicon.hints(lex, "看一下TABLE的推特", now));
        check(line.contains("TABLE") && line.contains("Tibo") && line.length() <= 80, "the line for the model names both: " + line);
        check(Lexicon.line(new ArrayList<Heard.Hint>()).isEmpty() && Lexicon.line(null).isEmpty(), "no hints, no line");

        // and nothing else in that week is hinted at
        int hinted = 0;
        for (Lexicon.Turn t : week) {
            Set<String> got = allMeant(lex, t.said, now), ok = allowed(t.said);
            if (!got.isEmpty()) hinted++;
            check(ok.containsAll(got), "no noise on 「" + t.said + "」: " + Lexicon.hints(lex, t.said, now));
            check(Lexicon.hints(lex, t.said, now).size() <= Lexicon.MAX_HINTS, "at most MAX_HINTS on 「" + t.said + "」");
        }
        check(hinted >= 8 && hinted <= 24, "the week's own misheard sentences are hinted at, the rest are not: " + hinted + " of 86");
        for (String plain : new String[] {"打开电视", "音量调到30", "播放林俊杰的歌", "明天上海天气怎么样", "回到桌面", "我想看别无选择",
                "现在几点", "打开B站", "查一下苹果的股价", "打开table tennis的视频", "暂停", "下一首", "今天有什么新闻", "帮我记一下明天买牛奶",
                "HDMI1", "把音量调到最大", "播放CCTV5", "这个视频讲的什么", "你记得我什么", "关闭屏幕不要关机"})
            check(Lexicon.hints(lex, plain, now).isEmpty(), "an everyday sentence gets no hint: " + plain + " " + Lexicon.hints(lex, plain, now));

        // the names that come up most, for 「关于用户」
        List<String> lines = Lexicon.lines(lex, now);
        String all = flat(String.join("\n", lines));
        check(!lines.isEmpty() && lines.size() <= 2 && String.join("", lines).length() <= 160, "one or two short lines: " + lines);
        check(all.contains("tibo") && all.contains("minimax") && all.contains("openai") && all.contains("智谱"),
                "the four names the user came back to that week are in them: " + lines);
        check(!all.contains("hazelbaker") && !all.contains("carson") && !all.contains("paypal") && !all.contains("mediaalpha")
                && !all.contains("table") && !all.contains("质朴"),
                "a name only an answer mentioned is not one the user comes back to, and a misheard spelling is not a name: " + lines);

        // what the app's memory page lists
        String page = Lexicon.names(lex).toString().toLowerCase();
        check(hasName(Lexicon.names(lex), "Tibo") && hasName(Lexicon.names(lex), "智谱") && !hasName(Lexicon.names(lex), "TABLE")
                && !hasName(Lexicon.names(lex), "TIBA") && !hasName(Lexicon.names(lex), "质朴") && !hasName(Lexicon.names(lex), "MAX"),
                "the names are the names; what the recognition wrote instead is not one of them: " + page.length() + " chars");
        check(Lexicon.names(lex).length() <= Lexicon.MAX_NAMES && page.contains("tibo") && page.contains("table"),
                "names() carries the name and the spellings learned for it: " + page.length() + " chars");

        // learned once; saved and loaded; forgotten when told to
        String before = lex.toString();
        learnAll(lex, week);
        check(before.equals(lex.toString()), "a turn is learned once: replaying the week changes nothing");
        JSONObject loaded = new JSONObject(lex.toString());
        check("Tibo".equals(meant(loaded, "看一下TABLE的推特", "TABLE", now)), "the lexicon works the same after a save and a load");
        check(Lexicon.hints(lex, "看一下TABLE的推特", now + 120 * DAY).isEmpty() && Lexicon.lines(lex, now + 120 * DAY).isEmpty(),
                "a name nobody came back to for 120 days is neither hinted nor listed");
        check(Lexicon.forget(lex, "Tibo") >= 1, "forget says how much went");
        check(!allMeant(lex, "看一下TABLE的推特", now).contains("tibo") && !flat(String.join("", Lexicon.lines(lex, now))).contains("tibo")
                && !Lexicon.names(lex).toString().toLowerCase().contains("tibo"), "「忘掉 Tibo」: the name and its spellings are gone");
        check(allMeant(lex, "查询MAX最新的股价", now).contains("minimax"), "and the other names stay");
    }

    // ---- the same shapes with other names ----------------------------------------------------------------------------

    private static void otherNames() {
        // a near miss in the same turn, twice; then a spelling nobody made yet
        JSONObject lex = Lexicon.empty();
        List<Lexicon.Turn> a = new ArrayList<>();
        a.add(turn("ANTROPIC最近怎么了", "Anthropic 这周发布了新模型，主要更新是长任务更稳。", "answered", T0, "Anthropic"));
        a.add(turn("查一下ANTROPIC的新闻", "Anthropic 今天宣布了新的企业方案。", "answered", T0 + 2 * MIN, "Anthropic"));
        learnAll(lex, a);
        long now = T0 + 5 * MIN;
        check("Anthropic".equals(meant(lex, "ANTROPIC的新模型怎么样", "ANTROPIC", now)), "a spelling the same turn resolved is learned: "
                + Lexicon.hints(lex, "ANTROPIC的新模型怎么样", now));
        check("Anthropic".equals(meant(lex, "ANTHROPIK有什么消息", "ANTHROPIK", now)), "one letter off a name that came up twice: "
                + Lexicon.hints(lex, "ANTHROPIK有什么消息", now));
        check(Lexicon.hints(lex, "Anthropic最近怎么样", now).isEmpty(), "said right: no hint");
        check(Lexicon.hints(lex, "ANTENNA怎么接", now).isEmpty(), "another word that only begins alike is no spelling of it: "
                + Lexicon.hints(lex, "ANTENNA怎么接", now));

        // not understood, asked back, then said again another way within the window: the first word is tied to the name
        lex = Lexicon.empty();
        List<Lexicon.Turn> b = new ArrayList<>();
        b.add(turn("CLOUD最近出了什么新模型", "你说的 CLOUD 是指云服务，还是别的？", "awaiting_user", T0));
        b.add(turn("我说的是CLAUDE", "Claude 是 Anthropic 的模型，最新的一代上个月发布。", "answered", T0 + 15 * SEC, "Claude"));
        learnAll(lex, b);
        check("Claude".equals(meant(lex, "CLOUD的新模型怎么样", "CLOUD", now)), "CLOUD, asked back, then 「我说的是CLAUDE」: CLOUD is Claude: "
                + Lexicon.hints(lex, "CLOUD的新模型怎么样", now));
        check(hasName(Lexicon.names(lex), "Claude") && !hasName(Lexicon.names(lex), "CLOUD"),
                "the word that was asked back is a spelling of the name, not a name: " + Lexicon.names(lex));

        // a wrong answer corrected with 「我说的是…」: the word of the first turn belongs to the corrected name
        lex = Lexicon.empty();
        List<Lexicon.Turn> c = new ArrayList<>();
        c.add(turn("查询VIDIA的股价", "没有叫 VIDIA 的股票，最接近的是 Vidia Health，今天没有成交。", "answered", T0));
        c.add(turn("我说的是NVIDIA的股价", "英伟达 181.60 美元，跌 9.26%（美东时间 11 月 5 日收盘）", "done", T0 + 20 * SEC, "NVIDIA"));
        c.add(turn("NVIDIA为什么跌", "NVIDIA 这次下跌主要是因为出口限制的消息。", "answered", T0 + 2 * MIN, "NVIDIA"));
        learnAll(lex, c);
        check("nvidia".equals(flat(meant(lex, "查询VIDIA的股价", "VIDIA", now))), "VIDIA, answered wrongly, then corrected: VIDIA is NVIDIA: "
                + Lexicon.hints(lex, "查询VIDIA的股价", now));

        // Chinese, the same sound, the name taken from the quote line (no names from the tools)
        lex = Lexicon.empty();
        List<Lexicon.Turn> d = new ArrayList<>();
        d.add(turn("查询必亚迪的股价", "比亚迪 105.20 元，涨 1.20%（今天收盘）", "done", T0));
        learnAll(lex, d);
        check("比亚迪".equals(meant(lex, "必亚迪今天怎么样", "必亚迪", now)), "必亚迪 was answered with 比亚迪's quote: "
                + Lexicon.hints(lex, "必亚迪今天怎么样", now));
        check(Lexicon.hints(lex, "比亚迪今天怎么样", now).isEmpty(), "比亚迪 said right: no hint");
    }

    // ---- what must not be tied together ------------------------------------------------------------------------------

    private static void notTied() {
        long now = T0 + 30 * MIN;
        // the next turn is about something else
        JSONObject lex = Lexicon.empty();
        List<Lexicon.Turn> a = new ArrayList<>();
        a.add(turn("看一下TABLE的推特", "已停止。", "cancelled", T0));
        a.add(turn("明天上海天气怎么样", "上海明天阴，16 到 18 度，降水概率 20%。", "answered", T0 + 10 * SEC));
        a.add(turn("ORANGE这个人", "你说的应该是歌手 Orange，她上个月发了新专辑。", "answered", T0 + 40 * SEC, "Orange"));
        learnAll(lex, a);
        check(Lexicon.hints(lex, "看一下TABLE的推特", now).isEmpty(), "TABLE is not tied to a name that does not read like it: "
                + Lexicon.hints(lex, "看一下TABLE的推特", now));

        // the name comes too late
        lex = Lexicon.empty();
        List<Lexicon.Turn> b = new ArrayList<>();
        b.add(turn("看一下TABLE的推特", "已停止。", "cancelled", T0));
        b.add(turn("TI BO这个人", "你说的应该是 Tibo Sottiaux，AI 圈常被叫“Tibo”。", "answered", T0 + 10 * MIN, "Tibo"));
        learnAll(lex, b);
        check(!allMeant(lex, "看一下TABLE的推特", now).contains("tibo"), "a name said ten minutes later is not that turn's repair: "
                + Lexicon.hints(lex, "看一下TABLE的推特", now));

        // an answer that asks back teaches no name
        lex = Lexicon.empty();
        List<Lexicon.Turn> c = new ArrayList<>();
        c.add(turn("嗯查询质朴和MINIMAX股价并且进行比较", "“质朴”是指哪只股票？请说公司全名或代码。", "awaiting_user", T0));
        c.add(turn("质朴的股票你应该知道", "已停止。", "cancelled", T0 + 30 * SEC));
        learnAll(lex, c);
        check(!Lexicon.names(lex).toString().contains("质朴"), "a word quoted in a question asked back is not a name: " + Lexicon.names(lex));

        // a secret is never kept, whatever quotes it
        lex = Lexicon.empty();
        List<Lexicon.Turn> e = new ArrayList<>();
        e.add(turn("记一下门锁密码是123456", "记下了：“门锁密码是123456”", "answered", T0, "门锁密码是123456"));
        learnAll(lex, e);
        check(!lex.toString().contains("123456") && !lex.toString().contains("密码"), "nothing that looks like a secret is stored: " + lex);
    }

    // ---- limits ---------------------------------------------------------------------------------------------------

    private static void limits() {
        String[] one = {"ka", "lo", "mi", "ne", "pu", "ra", "si", "to", "vu", "ze"};
        JSONObject lex = Lexicon.empty();
        List<Lexicon.Turn> many = new ArrayList<>();
        int n = 0;
        for (String x : one) for (String y : one) {
            if (n >= 80) break;
            String name = Character.toUpperCase(x.charAt(0)) + x.substring(1) + y + "rion";
            many.add(turn(name + "最近怎么样", "“" + name + "”最近没有新消息。", "answered", T0 + n * MIN, name));
            n++;
        }
        learnAll(lex, many);
        JSONArray names = Lexicon.names(lex);
        String newest = many.get(many.size() - 1).names.get(0), oldest = many.get(0).names.get(0);
        check(names.length() <= Lexicon.MAX_NAMES && names.length() >= 20, "80 names learned, at most MAX_NAMES kept: " + names.length());
        check(names.toString().contains(newest) && !names.toString().contains(oldest), "the newest stays, the oldest goes");
        check(lex.toString().length() < 40_000, "the file stays small: " + lex.toString().length() + " chars");

        // one name, many spellings
        lex = Lexicon.empty();
        List<Lexicon.Turn> sp = new ArrayList<>();
        String[] wrong = {"ANTROPIC", "ANTHROPIK", "ANTHROPICK", "ENTHROPIC", "ANTHROBIC", "ANTHROPIG", "ANTHRAPIC", "ANTHROPEC", "ANTHROPIX"};
        for (int i = 0; i < wrong.length; i++)
            sp.add(turn(wrong[i] + "最近怎么了", "Anthropic 这周没有新消息。", "answered", T0 + i * MIN, "Anthropic"));
        learnAll(lex, sp);
        JSONArray kept = Lexicon.names(lex);
        check(kept.length() == 1, "nine spellings of one name are one name: " + kept);
        JSONArray heard = kept.length() == 1 ? kept.getJSONObject(0).optJSONArray("heard") : null;
        check(heard != null && heard.length() >= 1 && heard.length() <= Lexicon.MAX_HEARD, "at most MAX_HEARD spellings are kept for it: " + heard);
    }

    // ---- nothing here may ever break a task --------------------------------------------------------------------------

    private static void safe() throws Exception {
        boolean threw = false;
        try {
            Lexicon.learn(null, null);
            Lexicon.learn(Lexicon.empty(), null);
            Lexicon.learn(Lexicon.empty(), new ArrayList<Lexicon.Turn>());
            Lexicon.learn(null, new ArrayList<>(Arrays.asList(turn("a", "b", "", T0))));
            List<Lexicon.Turn> withNull = new ArrayList<>();
            withNull.add(null);
            withNull.add(new Lexicon.Turn(null, null, null, T0, null));
            Lexicon.learn(Lexicon.empty(), withNull);
            JSONObject broken = new JSONObject("{\"names\":5,\"learned\":\"x\",\"other\":[1,2]}");
            Lexicon.learn(broken, new ArrayList<>(Arrays.asList(turn("Tibo最近怎么样", "“Tibo”最近发了新帖。", "answered", T0, "Tibo"))));
            check(Lexicon.hints(broken, "Tibo", T0).isEmpty(), "a broken file is started over, and still answers");
            check(Lexicon.hints(null, "x", T0).isEmpty() && Lexicon.hints(Lexicon.empty(), null, T0).isEmpty()
                    && Lexicon.hints(Lexicon.empty(), "", T0).isEmpty(), "no lexicon, no utterance: no hints");
            check(Lexicon.lines(null, T0).isEmpty() && Lexicon.lines(Lexicon.empty(), T0).isEmpty(), "nothing learned: no lines");
            check(Lexicon.forget(null, "x") == 0 && Lexicon.forget(Lexicon.empty(), null) == 0 && Lexicon.forget(Lexicon.empty(), "") == 0,
                    "nothing to forget: 0");
            check(Lexicon.names(null).length() == 0 && Lexicon.names(Lexicon.empty()).length() == 0, "nothing learned: no names");
        } catch (Throwable t) {
            threw = true;
            check(false, "something threw: " + t);
        }
        check(!threw, "null, empty and broken input never throw");
    }
}
