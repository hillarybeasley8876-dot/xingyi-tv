package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** 每单的真实成本（Cost.java + tools/cost/cost.mjs）：一个 result 事件自己的账、一个会话里累计事件之间的差、
 * 会话换了或者数掉了怎么办、runner 没报成本时是 null 而不是 0、「今天：…」那句话的每一个字，
 * 以及两边的排版字面量是不是一模一样。
 *
 * <p>fixture 在 tests/agent/cost-fixtures/，都是手写的小文件：vd.json 是 verify-durable 头两个累计事件的形状，
 * drop.json 是 reported-2 真的掉下来的那两个事件。 */
final class CostChecks {
    private static final String DIR = "tests/agent/cost-fixtures/";
    private static final String MJS = "tools/cost/cost.mjs";
    private static final String BOARD = "tools/board/board.mjs";
    private static final String JAVA = "src/xyz/erapi0neer/xingyi/agent/Cost.java";

    private CostChecks() { }

    static void run() throws Exception {
        oneEvent();
        cumulative();
        sessionChange();
        numbersDrop();
        noCost();
        nothingThere();
        notAResult();
        twoModels();
        today();
        spendLine();
        spendAgrees();
        dearestOnes();
        dearestSubCentTie();
        sameFormat();
    }

    // ---- 一个事件：它自己报的账 ----

    /** brief 给的那个样例（verify-durable 第四个事件）：令牌数走 modelUsage，不是顶层 usage。 */
    private static void oneEvent() throws Exception {
        Cost c = Cost.of(obj("one.json"));
        check(c != null, "一个 result 事件读得出账");
        check(near(c.usd, 9.073538), "美元是事件自己的 total_cost_usd（9.073538）");
        check(c.ms == 536657L && c.turns == 18L, "时长和来回次数是事件自己的（536657 毫秒、18 次）");
        check(c.inTok == 64009L, "输入令牌取自 modelUsage（64009），不是顶层 usage 的 4389");
        check(c.cacheTok == 11497136L, "缓存读令牌取自 modelUsage（11497136）");
        check(c.outTok == 120197L && c.thinkTok == 77657L, "输出和思考令牌取自 modelUsage（120197 / 77657）");
        check(c.models.size() == 1, "modelUsage 里一个模型就是一个条目");
        Cost.Model m = c.models.get("minimax-M78");
        check(m != null && near(m.usd, 9.073538) && m.outTok == 120197L, "那个模型自己的一份：美元和输出令牌");
        check(Cost.money(c.usd.doubleValue()).equals("$9.07"), "记到分：$9.07");
        check(Cost.delta(new JSONArray().put(obj("one.json"))) != null, "delta() 拿得到最后一个事件的账");
    }

    // ---- 一个会话里的累计事件：一轮的账是差 ----

    /** $4.82 然后 $6.15：第二轮是 $1.32，不是 $6.15——一个会话里的 result 是累计的。 */
    private static void cumulative() throws Exception {
        JSONArray vd = arr("vd.json");
        List<Cost> d = Cost.deltas(vd);
        check(d.size() == 2, "两个事件就是两轮的账");
        Cost first = d.get(0), second = d.get(1);
        check(near(first.usd, 4.821722), "第一轮是第一个事件自己的全部（$4.82）");
        check(near(second.usd, 1.323325), "第二轮是差（$6.15 − $4.82 = $1.32），不是 $6.15");
        check(!near(second.usd, 6.145047), "累计的那个数没有被当成一轮的账");
        check(Cost.money(second.usd.doubleValue()).equals("$1.32"), "第二轮记到分是 $1.32");

        // 令牌也是累计的：差出来的正好是第二个事件顶层 usage 里本轮的那一份
        check(second.inTok == 5011L && second.cacheTok == 1618140L, "令牌的差就是本轮的输入和缓存读");
        check(second.outTok == 19568L && second.thinkTok == 14781L, "输出和思考令牌的差同样是本轮的");
        check(second.inTok == vd.getJSONObject(1).getJSONObject("usage").getLong("input_tokens"),
            "差出来的输入令牌和第二个事件 usage 里本轮的那份一模一样");

        // 时长和来回次数本来就是本轮的，取差会算出负数
        check(second.ms == 424266L && second.turns == 12L, "时长和来回次数照抄本轮的，不取差（取差会是负的）");
        check(second.ms > 0L && second.turns > 0L, "没有一项是负的");

        Cost.Model m = second.models.get("minimax-M78");
        check(m != null && near(m.usd, 1.323325) && m.outTok == 19568L, "按模型的那一份也取差");
        double sum = d.get(0).usd.doubleValue() + d.get(1).usd.doubleValue();
        check(near(Double.valueOf(sum), vd.getJSONObject(1).getDouble("total_cost_usd")),
            "各轮加起来正好是这个会话最后报的总数");
    }

    // ---- 会话换了：数重新数，那一条按整条算 ----

    /** 换了会话（换模型就换会话）之后计数从头来：就算新的一条数更大，也不能跟前一个会话取差。 */
    private static void sessionChange() throws Exception {
        List<Cost> d = Cost.deltas(arr("restart.json"));
        check(d.size() == 3, "三个事件三笔账");
        check(near(d.get(0).usd, 6.23441), "第一个会话的第一轮是它自己的全部");
        check(near(d.get(1).usd, 0.42), "换了会话的那一条按整条算（$0.42），不跟前一个会话取差");
        check(d.get(1).inTok == 900L, "换了会话的令牌数也是整条的");
        check(near(d.get(2).usd, 0.13), "同一个会话里下一条才是差（$0.55 − $0.42 = $0.13）");
        check(d.get(2).inTok == 600L && d.get(2).outTok == 600L, "同一个会话里令牌的差是本轮的");

        // 只靠 session_id 就该认出来：这一条的数比前一条大，取差会得到一个看着很像的 $0.85
        JSONArray mixed = arr("vd.json");
        mixed.put(obj("event-restart.json"));
        List<Cost> m = Cost.deltas(mixed);
        check(m.size() == 3, "两个会话的事件放在一起也是三笔账");
        check(near(m.get(2).usd, 7.0), "session_id 变了就算整条（$7.00），不取差（取差是 $0.85）");
        check(m.get(2).inTok == 60000L && m.get(2).outTok == 90000L, "令牌同样按整条算");
    }

    // ---- 数掉了：never negative ----

    /** reported-2 真的出过这种事（同一个 session_id，令牌数往下掉）：那一条按整条算，账上不许有负数。 */
    private static void numbersDrop() throws Exception {
        List<Cost> d = Cost.deltas(arr("drop.json"));
        check(d.size() == 2, "两个事件两笔账");
        check(near(d.get(1).usd, 3.315021), "数掉了的那一条按整条算（$3.32）");
        check(d.get(1).inTok == 51936L && d.get(1).cacheTok == 3706132L, "掉下去的令牌数也按整条算");
        boolean positive = true;
        for (Cost c : d) {
            if (c.usd != null && c.usd.doubleValue() < 0d) positive = false;
            if (c.inTok < 0L || c.cacheTok < 0L || c.outTok < 0L || c.thinkTok < 0L
                || c.ms < 0L || c.turns < 0L) positive = false;
            for (Cost.Model mm : c.models.values()) {
                if (mm.usd != null && mm.usd.doubleValue() < 0d) positive = false;
                if (mm.outTok < 0L) positive = false;
            }
        }
        check(positive, "美元和令牌没有一项是负的");
        double sum = d.get(0).usd.doubleValue() + d.get(1).usd.doubleValue();
        check(near(Double.valueOf(sum), 6.553822), "这一单是两笔整账加起来的 $6.55，不是最后一条的 $3.32");
    }

    // ---- 没报成本：null，不是 0 ----

    private static void noCost() throws Exception {
        Cost c = Cost.of(arr("no-cost.json").getJSONObject(0));
        check(c != null, "没有 total_cost_usd 的事件照样读得出账");
        check(c.usd == null, "没报成本就是 null，不是 0");
        check(c.models.get("minimax-M78").usd == null, "按模型的那一份也没报成本");
        check(c.outTok == 30L && c.thinkTok == 5L, "没报成本不妨碍令牌数");

        List<Cost> d = Cost.deltas(arr("no-cost.json"));
        check(d.size() == 2, "两个事件两笔账");
        check(d.get(0).usd == null && d.get(1).usd == null, "null 一路带着走，不偷偷变成 0");
        check(d.get(1).inTok == 30L && d.get(1).outTok == 60L, "没成本的轮次，令牌照样取差");
        check(d.get(1).models.get("minimax-M78").usd == null, "按模型的美元也还是 null");
    }

    // ---- 什么都没有 / 不是 result ----

    private static void nothingThere() throws Exception {
        Cost c = Cost.of(obj("bare.json"));
        check(c != null, "一个只剩 type 的 result 事件不炸");
        check(c.usd == null && c.ms == 0L && c.turns == 0L, "缺的字段算 0（美元例外，是 null）");
        check(c.inTok == 0L && c.cacheTok == 0L && c.outTok == 0L && c.thinkTok == 0L, "令牌数一个都没有就是四个 0");
        check(c.models.isEmpty(), "没有 modelUsage 就没有模型条目");

        // 老日志只有 usage：退到顶层，令牌数照样读得出来
        Cost u = Cost.of(obj("usage-only.json"));
        check(near(u.usd, 1.5) && u.ms == 700L && u.turns == 2L, "只有 usage 的事件：美元、时长、来回次数都在");
        check(u.inTok == 11L && u.cacheTok == 22L && u.outTok == 33L && u.thinkTok == 4L,
            "没有 modelUsage 就退到顶层 usage 里的四个数");
        check(u.models.isEmpty(), "退到 usage 的事件没有按模型的账");
    }

    private static void notAResult() throws Exception {
        check(Cost.of(obj("not-result.json")) == null, "一个 assistant 事件不是一轮的账");
        check(Cost.of(obj("not-result-costly.json")) == null,
            "带着 total_cost_usd 的 assistant 事件也不算——认的是 type");
        check(Cost.of(null) == null, "根本没有事件就是 null");
        check(Cost.of(new JSONObject()) == null, "一个空对象不是 result 事件");

        JSONArray mixed = new JSONArray().put(obj("not-result-costly.json"))
            .put(arr("vd.json").getJSONObject(0));
        List<Cost> d = Cost.deltas(mixed);
        check(d.size() == 1, "deltas 跳过不是 result 的事件");
        check(near(d.get(0).usd, 4.821722), "跳过之后剩下那条的账还是它自己的");
        check(Cost.deltas(new JSONArray()).isEmpty(), "没有事件就没有账");
        check(Cost.deltas(null).isEmpty(), "事件列表是 null 也不炸");
        check(Cost.delta(new JSONArray()) == null, "没有 result 事件时 delta() 是 null");
    }

    // ---- 两个模型 ----

    private static void twoModels() throws Exception {
        Cost c = Cost.of(obj("event-two-models.json"));
        check(c.models.size() == 2, "modelUsage 里两个模型就是两个条目");
        check(near(c.models.get("minimax-M78").usd, 2.0) && c.models.get("minimax-M78").outTok == 20L,
            "第一个模型自己那一份");
        check(near(c.models.get("other-model").usd, 1.0) && c.models.get("other-model").outTok == 2L,
            "第二个模型自己那一份");
        check(c.inTok == 11L && c.cacheTok == 33L && c.outTok == 22L && c.thinkTok == 9L,
            "令牌数是两个模型加起来的（11 / 33 / 22 / 9）");

        List<Cost> d = Cost.deltas(new JSONArray().put(obj("event-two-models.json"))
            .put(obj("event-two-models-2.json")));
        check(d.size() == 2 && near(d.get(1).usd, 2.0), "一轮的美元是差（$5 − $3 = $2）");
        check(near(d.get(1).models.get("minimax-M78").usd, 1.0)
            && d.get(1).models.get("minimax-M78").outTok == 10L, "每个模型各自取差（第一个：$1、10 个令牌）");
        check(near(d.get(1).models.get("other-model").usd, 1.0)
            && d.get(1).models.get("other-model").outTok == 3L, "第二个模型也各自取差（$1、3 个令牌）");
        check(d.get(1).inTok == 6L && d.get(1).cacheTok == 13L && d.get(1).outTok == 13L,
            "两个模型的令牌加在一起取差");
    }

    // ---- 「今天：……」那句话 ----

    private static void today() throws Exception {
        check(Cost.line(Collections.<JSONObject>emptyList()).equals("今天：0 单、0 轮、$0.00"),
            "一单都没有：今天：0 单、0 轮、$0.00");
        check(Cost.line(null).equals("今天：0 单、0 轮、$0.00"), "单子列表是 null 也一样");

        List<JSONObject> one = Collections.singletonList(list(arr("jobs-tie.json")).get(0));
        check(Cost.line(one).equals("今天：1 单、1 轮、$2.50"), "一单：今天：1 单、1 轮、$2.50");

        // 3 单、4 轮，其中一单被杀了没 result 事件：$12.30 是下限
        List<JSONObject> jobs = list(arr("jobs-3.json"));
        check(Cost.line(jobs).equals("今天：3 单、4 轮、$12.30（1 单没有成本，合计是下限）"),
            "三单四轮，其中一单没成本：那句话一个字不差");
        check(Cost.line(jobs).indexOf("单、") > 0 && Cost.line(jobs).indexOf("$") > 0, "有「单、」也有美元数");

        // 三单都有成本时不说下限
        List<JSONObject> all = new ArrayList<JSONObject>(jobs);
        all.remove(2);
        check(Cost.line(all).equals("今天：2 单、3 轮、$12.30"), "都测到了就不加那句下限");

        // 一轮摊平了给（没有 history，只有自己的 cost）也认，按一单一轮算
        List<JSONObject> flat = Collections.singletonList(arr("jobs-tie.json").getJSONObject(0));
        check(Cost.line(flat).equals("今天：1 单、1 轮、$2.50"), "摊平的一轮也算一单一轮");

        // 每一轮都没成本的单算「没有成本」，不按 0 算
        JSONObject dead = new JSONObject().put("id", "dead")
            .put("history", new JSONArray().put(new JSONObject().put("round", 1).put("tools", 3)));
        check(Cost.line(Collections.singletonList(dead)).equals("今天：1 单、1 轮、$0.00（1 单没有成本，合计是下限）"),
            "一单都没测到：合计是 $0.00，但明说是下限");
        check(Cost.money(0d).equals("$0.00") && Cost.money(1234.5d).equals("$1234.50"),
            "美元记到分，两位小数");
    }

    // ---- 「花在模型上」那一行：状态板的「消耗」写的那句钱 ----

    /** 一单都没跑过、一单都没测到、测到了带不带下限，三种样子；null 的合计永远不写成 $0.00。 */
    private static void spendLine() throws Exception {
        check(Cost.spend(Collections.<JSONObject>emptyList()).equals("- 花在模型上：没有单跑过。"),
            "一单都没跑过：- 花在模型上：没有单跑过。");
        check(Cost.spend(null).equals("- 花在模型上：没有单跑过。"), "单子列表是 null 也一样");

        List<JSONObject> all = list(arr("jobs-tie.json"));
        check(Cost.spend(all).equals("- 花在模型上：$7.50（3 单）"),
            "三单都测到了：$2.50 × 3 = $7.50（3 单），一个字不差");
        check(Cost.spend(all).indexOf("合计是下限") < 0, "都测到了就不说「合计是下限」");

        List<JSONObject> mixed = list(arr("jobs-3.json"));
        check(Cost.spend(mixed).equals("- 花在模型上：$12.30（3 单；1 单没有成本，合计是下限）"),
            "三单里一单没成本：$12.30（3 单；1 单没有成本，合计是下限）");

        List<JSONObject> none = list(arr("jobs-none.json"));
        check(Cost.spend(none).equals("- 花在模型上：算不出来（3 单都没有记成本）"),
            "三单都没记成本：算不出来（3 单都没有记成本）");
        check(Cost.spend(none).indexOf('$') < 0, "算不出来时一个美元数都不写");
        check(Cost.spend(none).indexOf("0.00") < 0, "null 不是 $0.00：$0.00 说的是「测到了，是零」");

        // 一轮摊平了给的（没有 history，只有自己的 cost）也认，按一单一轮算
        check(Cost.spend(Collections.singletonList(arr("jobs-tie.json").getJSONObject(0)))
                .equals("- 花在模型上：$2.50（1 单）"),
            "摊平的一轮也算一单：$2.50（1 单）");

        // 一单里只有一轮记了成本：这一单算测到了
        JSONObject half = new JSONObject().put("id", "half").put("history", new JSONArray()
            .put(new JSONObject().put("round", 1).put("cost", new JSONObject().put("usd", 1.25)))
            .put(new JSONObject().put("round", 2)));
        check(Cost.spend(Collections.singletonList(half)).equals("- 花在模型上：$1.25（1 单）"),
            "一单里一轮记了成本，这一单算测到了：$1.25（1 单）");

        // 真的测到零，才写 $0.00
        JSONObject zero = new JSONObject().put("id", "zero").put("history", new JSONArray()
            .put(new JSONObject().put("round", 1).put("cost", new JSONObject().put("usd", 0))));
        check(Cost.spend(Collections.singletonList(zero)).equals("- 花在模型上：$0.00（1 单）"),
            "真的测到零才写 $0.00");
        check(Cost.spend(Collections.singletonList(zero)).indexOf("算不出来") < 0
                && Cost.spend(Collections.singletonList(zero)).indexOf("下限") < 0,
            "测到零不是没测到：不说算不出来，也不说合计是下限");
    }

    /** 两边的 spend() 说的是同一句话：把 cost.mjs 里 spend() 用的字面量按源码顺序抠出来，按它自己的拼法
     * 拼一遍，拼出来的句子要和 {@link Cost#spend} 一个字不差——两边各排各的，这一查就露。 */
    private static void spendAgrees() throws Exception {
        String mjs = source(MJS);
        String java = source(JAVA);
        check(mjs.contains("export function spend(jobs)") && java.contains("public static String spend(List<JSONObject> jobs)"),
            "两边的 spend 都在：cost.mjs 的 spend(jobs) 是正本，Cost.java 的 spend(List<JSONObject>) 是镜子");
        List<String> lit = literals(mjs, "export function spend(");
        check(lit.equals(Arrays.asList("- 花在模型上：没有单跑过。", "- 花在模型上：算不出来（", " 单都没有记成本）",
                "- 花在模型上：$", "（", " 单", "；", " 单没有成本，合计是下限", "）")),
            "cost.mjs 的 spend() 用的就是这九个字面量，顺序也一样：" + lit);
        String miss = "";
        for (String s : lit) {
            // 那个 $ 是 Cost.money 排出来的，Java 里写的是它前面那一段
            String want = s.endsWith("$") ? s.substring(0, s.length() - 1) : s;
            if (!java.contains("\"" + want + "\"")) miss = want;
        }
        check(miss.isEmpty(), "这九个字面量在 Cost.java 里也都写着（$ 由 Cost.money 排出来）：缺 " + miss);

        check(jsSpend(lit, 0, 0, "").equals(Cost.spend(Collections.<JSONObject>emptyList())),
            "一单都没跑过：cost.mjs 的字面量拼出来的话和 Cost.spend 一模一样");
        check(jsSpend(lit, 3, 0, "7.50").equals(Cost.spend(list(arr("jobs-tie.json")))),
            "三单都测到了：两边的句子一个字节不差");
        check(jsSpend(lit, 3, 1, "12.30").equals(Cost.spend(list(arr("jobs-3.json")))),
            "三单里一单没成本：两边的句子一个字节不差");
        check(jsSpend(lit, 3, 3, "").equals(Cost.spend(list(arr("jobs-none.json")))),
            "三单都没记成本：两边的句子一个字节不差");
        check(jsSpend(lit, 1, 0, "0.00").equals(Cost.spend(Collections.singletonList(new JSONObject().put("id", "zero")
                .put("history", new JSONArray().put(new JSONObject().put("round", 1)
                    .put("cost", new JSONObject().put("usd", 0))))))),
            "测到零的那一句也对得上");
        check(source(BOARD).contains("spend(") && source(BOARD).contains("dearestLines("),
            "board.mjs 用的是 cost.mjs 的 spend / dearestLines，不自己再算一遍钱");
    }

    /** cost.mjs 的 spend() 用它自己的字面量拼出来的句子（数字和钱由调用的人给）。 */
    private static String jsSpend(List<String> l, int jobs, int unknown, String money) {
        if (jobs == 0) return l.get(0);
        if (jobs - unknown == 0) return l.get(1) + jobs + l.get(2);
        String s = l.get(3) + money + l.get(4) + jobs + l.get(5);
        if (unknown > 0) s += l.get(6) + unknown + l.get(7);
        return s + l.get(8);
    }

    /** 一个函数体里用到的单引号字面量，按源码里的先后。 */
    private static List<String> literals(String src, String head) {
        List<String> out = new ArrayList<String>();
        int at = src.indexOf(head);
        if (at < 0) return out;
        int open = src.indexOf('{', at);
        int depth = 0, end = src.length();
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        Matcher m = Pattern.compile("'([^'\\\\]*)'").matcher(src.substring(open, end));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ---- 最费的几单 ----

    private static void dearestOnes() throws Exception {
        List<JSONObject> jobs = list(arr("jobs-dearest.json"));
        String top = Cost.dearest(jobs, 5);
        check(top.equals("- h8 $9.94 (5 轮)\n- h7 $7.24 (2 轮)\n- h6 $6.42 (2 轮)\n"
                + "- h4 $3.30 (1 轮)\n- h5 $3.30 (1 轮)\n"),
            "最费的五单一单一行，从贵到便宜，连轮数一起");
        check(top.startsWith("- h8 $9.94 (5 轮)\n"), "一行的样子是 - <id> $9.94 (5 轮)");
        check(Cost.dearest(jobs, 99).equals(top), "要 99 个也只给 5 个：上限是 5");
        check(Cost.dearest(jobs, Cost.DEAREST).equals(top), "DEAREST 就是那个 5");
        check(Cost.dearest(jobs, 3).equals("- h8 $9.94 (5 轮)\n- h7 $7.24 (2 轮)\n- h6 $6.42 (2 轮)\n"),
            "要 3 个就给 3 个");
        check(Cost.dearest(jobs, 0).isEmpty() && Cost.dearest(jobs, -1).isEmpty(), "要 0 个（或者负数）什么都不给");
        check(top.indexOf("none") < 0, "没测到成本的单排不进最费的（它在「没有成本」里点名）");

        List<String> ids = new ArrayList<String>();
        for (String l : top.split("\n")) ids.add(l.substring(2, l.indexOf(' ', 2)));
        check(ids.equals(Arrays.asList("h8", "h7", "h6", "h4", "h5")), "顺序是按美元从多到少");

        // 一样贵的两单按 id 排，板子才不会每次刷新顺序都变
        check(Cost.dearest(list(arr("jobs-tie.json")), 5)
                .equals("- apple $2.50 (1 轮)\n- mango $2.50 (1 轮)\n- zebra $2.50 (1 轮)\n"),
            "一样贵的按 id 排");
        check(Cost.dearest(null, 5).isEmpty(), "单子列表是 null 也不炸");
        check(Cost.dearest(Collections.<JSONObject>emptyList(), 5).isEmpty(), "一单都没有就没有最费的");
    }

    /** 差不到一分的两单：「同样贵」看的是板子上写的那个数。cost.mjs 的 dearestLines 排的是 usd2(own)，
     * 两单都归到 $3.30 就是平手、按 id 排；Cost.dearest 要是按没归到分的原数排，同一页板子两边会排出
     * 两个顺序。node 跑 cost.mjs 的结果是 {@code - aaa $3.30 (1 轮)\n- bbb $3.30 (1 轮)\n}。 */
    private static void dearestSubCentTie() throws Exception {
        JSONObject big = new JSONObject().put("id", "bbb").put("history", new JSONArray()
            .put(new JSONObject().put("round", 1).put("cost", new JSONObject().put("usd", 3.3049))));
        JSONObject small = new JSONObject().put("id", "aaa").put("history", new JSONArray()
            .put(new JSONObject().put("round", 1).put("cost", new JSONObject().put("usd", 3.3041))));
        String out = Cost.dearest(Arrays.asList(big, small), 5);
        check(out.equals("- aaa $3.30 (1 轮)\n- bbb $3.30 (1 轮)\n"),
            "两单都归到 $3.30：按 id 排，和 cost.mjs 的 dearestLines 一模一样");
        check(!out.startsWith("- bbb"), "不是原数大的那单排前面：$3.3049 和 $3.3041 写在板上都是 $3.30");
        check(out.replace("$3.30", "").indexOf('$') < 0, "两行写的都是 $3.30，账上没有别的美元数");
        // 归到分之后差一分的，照旧按钱排
        JSONObject dearer = new JSONObject().put("id", "zzz").put("history", new JSONArray()
            .put(new JSONObject().put("round", 1).put("cost", new JSONObject().put("usd", 3.31))));
        check(Cost.dearest(Arrays.asList(big, dearer), 5).startsWith("- zzz $3.31"),
            "真的贵一分的那单还是排前面");
        check(Cost.dearest(Arrays.asList(big, small), 1).equals("- aaa $3.30 (1 轮)\n"),
            "只要一单时给的也是同一个顺序的第一名");
    }

    // ---- 两边的字面量不能各排各的 ----
    private static void sameFormat() throws Exception {
        String mjs = source(MJS), board = source(BOARD), java = source(JAVA);
        check(board.contains("今天："), "board.mjs 里写的是「今天：」");
        check(java.contains("今天："), "Cost.java 里写的是「今天：」");
        check(mjs.contains("今天："), "cost.mjs 里写的是「今天：」");
        check(java.contains("单没有成本，合计是下限"), "Cost.java 里说得出「单没有成本，合计是下限」");
        check(mjs.contains("单没有成本，合计是下限"), "cost.mjs 里同一句话一个字不差");
        check(java.contains(" 轮)") && mjs.contains(" 轮)"), "两边的最费一行都写「轮)」");
        check(java.contains("DEAREST = 5") && mjs.contains("DEAREST = 5"), "两边的上限都是 5");
        check(java.contains("单、") && mjs.contains("单、"), "两边都用「单、」分隔");
        check(!java.contains("import android."), "Cost 保持纯的：没有 android.*");
    }

    // ---- 用具 ----

    private static List<JSONObject> list(JSONArray a) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        return out;
    }

    private static JSONObject obj(String name) throws Exception {
        return new JSONObject(read(DIR + name));
    }

    private static JSONArray arr(String name) throws Exception {
        return new JSONArray(read(DIR + name));
    }

    private static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    private static String source(String p) throws Exception {
        return read(p);
    }

    /** 浮点的账只比到六位小数：$6.145047 减 $4.821722 在二进制里是 1.3233250000000005。 */
    private static boolean near(Double a, double b) {
        return a != null && Math.abs(a.doubleValue() - b) < 1e-6;
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }
}
