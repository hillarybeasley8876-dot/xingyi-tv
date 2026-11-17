package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 一页状态板（Board.java + tools/board/board.mjs）：五段的顺序、在跑 / 等合并 / 已合并 / 卡住各归谁、
 * --since 管哪两张表、空板子说什么，以及两边的排版字面量是不是一模一样。
 *
 * <p>三个 fixture（running / accepted 未合 / needs_lead）从 tests/agent/board-fixtures/ 读进来，
 * 按 board.mjs 的 normalize 同一套规则变成 Board.Job，再看排出来的页面对不对。 */
final class BoardChecks {
    /** 2026-11-12 04:00 +08：fixture 里的时刻都是相对它写的，检查才不会今天过明天不过。 */
    private static final long NOW = 1794427200000L;
    private static final long HOUR = 3600L * 1000L;
    private static final String DIR = "tests/agent/board-fixtures/";
    private static final String MJS = "tools/board/board.mjs";
    private static final String JAVA = "src/xyz/erapi0neer/xingyi/agent/Board.java";

    private BoardChecks() { }

    static void run() throws Exception {
        Board.Job running = job(DIR + "running.json");
        Board.Job accepted = job(DIR + "accepted.json");
        Board.Job needsLead = job(DIR + "needs-lead.json");
        List<Board.Job> all = Arrays.asList(running, accepted, needsLead);
        String md = Board.markdown(all, NOW, 24);

        shape(md);
        running(md, running);
        waiting(md, accepted);
        done(accepted);
        stuck(md);
        since(all, needsLead);
        empty();
        cost(md, running, accepted);
        money(md);
        moneyOrder();
        moneySince();
        dearestCap();
        moneyWiring();
        overflow();
        order(accepted);
        blanks();
        mapping(running, accepted, needsLead);
        paths();
        sameFormat();
    }

    /** 头一行和五段，顺序固定。 */
    private static void shape(String md) {
        check(md.startsWith("# 状态板 "), "板子第一行是「# 状态板 …」");
        check(md.split("\n")[0].matches("# 状态板 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
            "标题带的是本机的日期和钟点（yyyy-MM-dd HH:mm）");
        String[] heads = { Board.S_RUN, Board.S_WAIT, Board.S_DONE, Board.S_STUCK, Board.S_COST };
        int at = -1;
        boolean ordered = true;
        for (String h : heads) {
            int i = md.indexOf(h);
            if (i <= at) ordered = false;
            at = i;
        }
        check(ordered, "五段都在，而且按 在跑 / 等合并 / 今天完成并已合并 / 卡住 / 消耗 的顺序");
        check(md.contains(Board.HEAD_RUN) && md.contains(Board.HEAD_WAIT), "有行的两张表，表头一个字不差");
        check(section(md, Board.S_RUN).contains("| --- | --- | --- | --- | --- |")
            && section(md, Board.S_WAIT).contains("| --- | --- | --- | --- |"),
            "表头下面有分隔行，markdown 才认得出这是表");
        check(section(md, Board.S_DONE).trim().endsWith(Board.NONE), "没有已合并的单时，那一段只写「（没有）」，不摆一张空表");
    }

    /** 在跑的那一单：第几轮、工具次数、开始时刻、最后一句截 80 个字。 */
    private static void running(String md, Board.Job running) {
        String row = rowOf(md, "running-demo");
        check(!row.isEmpty(), "在跑的单进了「在跑」这张表");
        String[] c = cells(row);
        check(c.length == 5 && "running-demo".equals(c[0]), "一行五栏，第一栏是单号");
        check("2/6".equals(c[1]), "第几轮写成 第几轮/上限（2/6）");
        check("37".equals(c[2]), "工具次数是各轮 history[].tools 加起来的（12+25=37）");
        check(Board.clock(running.startedMs).equals(c[3]), "开始一栏是这一轮的开始时刻");
        check(c[4].length() == 80 && c[4].endsWith("…"), "最后一句截到 80 个字，多了给省略号");
        check(c[4].indexOf('\n') < 0 && row.indexOf("\\|") > 0, "最后一句里的换行和竖线都处理过，表格没被撑断");
        check(!"—".equals(c[4]) && c[4].startsWith("第二轮还在跑。"), "最后一句说的是这一单自己的话");
    }

    /** 过了验收还没合的单：完成于、短提交、验收说了什么。 */
    private static void waiting(String md, Board.Job accepted) {
        String row = rowOf(md, "accepted-demo");
        check(section(md, Board.S_WAIT).contains("| accepted-demo |"), "没合的单进「等合并」");
        String[] c = cells(row);
        check(c.length == 4 && Board.clock(accepted.updatedMs).equals(c[1]), "完成于是 state.json 最后一次变的时刻");
        check("abc1234".equals(c[2]), "提交只写前 7 位");
        check("3/3 过：core-checks、android-check、board".equals(c[3]), "验收一栏写的是最后一轮验收的结果");
        check(section(md, Board.S_DONE).indexOf("accepted-demo") < 0, "没合的单不会跑到「已合并」里去");
    }

    /** 合了的单进第三张表，而且不再出现在等合并里。 */
    private static void done(Board.Job accepted) {
        Board.Job merged = new Board.Job(accepted.id, accepted.status, accepted.rounds, accepted.maxRounds, accepted.tools,
            accepted.startedMs, accepted.updatedMs, accepted.commit, true, accepted.last, accepted.note);
        String md = Board.markdown(Arrays.asList(merged), NOW, 24);
        check(section(md, Board.S_DONE).contains("| accepted-demo |"), "合进主仓库的单进「今天完成并已合并」");
        check(section(md, Board.S_DONE).contains(Board.HEAD_DONE) && section(md, Board.S_DONE).contains("| --- | --- | --- | --- |"),
            "已合并这张表的表头和分隔行一个字不差");
        check(section(md, Board.S_WAIT).indexOf("accepted-demo") < 0, "合了的单不再算等合并");
        String[] c = cells(rowOf(md, "accepted-demo"));
        check(c.length == 4 && "abc1234".equals(c[1]) && "1".equals(c[2]) && "41".equals(c[3]),
            "已合并这张表写提交、轮数、工具次数");
        check(section(md, Board.S_RUN).trim().endsWith(Board.NONE), "没有在跑的单时，「在跑」写「（没有）」而不是一张空表");
    }

    /** 卡住：needs_lead 一定在，刚跑过的不在，九十分钟没动静的在。 */
    private static void stuck(String md) {
        String line = stuckOf(md, "needs-lead-demo");
        check(line.startsWith("- needs-lead-demo  needs_lead  "), "needs_lead 的单在「卡住」，一条一行：单号 状态 为什么");
        check(line.contains("1/3 没过：core-checks（output lacks: agent core: 5022 passed, 0 failed；exit code 1, expected 0）"),
            "为什么写的是没过的那条验收和它的原话");
        check(stuckOf(md, "running-demo").isEmpty(), "刚跑过（5 分钟前）的单不算卡住");
        check(section(md, Board.S_RUN).contains("| running-demo |"), "它还是在跑的那一栏里");
        Board.Job stale = new Board.Job("stale-demo", Board.RUNNING, 1, 4, 9, NOW - 100 * 60 * 1000L,
            NOW - 100 * 60 * 1000L, "", false, "跑到一半没声了", "");
        String s = Board.markdown(Arrays.asList(stale), NOW, 24);
        String l = stuckOf(s, "stale-demo");
        check(l.startsWith("- stale-demo  running  超过 90 分钟没更新"), "在跑但九十分钟没更新，算卡住");
        check(l.contains("最后 " + Board.clock(NOW - 100 * 60 * 1000L)), "并且说清最后是什么时候动的");
        check(section(s, Board.S_RUN).contains("| stale-demo |"), "它同时还在「在跑」里，看得见它还占着一格");
        Board.Job quiet = new Board.Job("quiet-demo", Board.RUNNING, 1, 4, 9, NOW - 89 * 60 * 1000L,
            NOW - 89 * 60 * 1000L, "", false, "", "");
        check(stuckOf(Board.markdown(Arrays.asList(quiet), NOW, 24), "quiet-demo").isEmpty(),
            "89 分钟没动还不算卡住：九十分钟是界限，不是大概");
        check(stuckOf(md, "accepted-demo").isEmpty(), "过了验收的单不算卡住");
    }

    /** --since 只管等合并和已合并两张表；卡住和在跑不看它。 */
    private static void since(List<Board.Job> all, Board.Job needsLead) {
        String one = Board.markdown(all, NOW, 1);
        check(section(one, Board.S_WAIT).indexOf("accepted-demo") < 0, "--since 1 把三小时前完成的那单从等合并里去掉");
        check(section(Board.markdown(all, NOW, 24), Board.S_WAIT).contains("| accepted-demo |"),
            "--since 24 留得住它");
        check(stuckOf(one, "needs-lead-demo").startsWith("- needs-lead-demo  needs_lead  "),
            "卡住这一段不看 --since：昨天没合的单今天还是要我出手");
        check(section(one, Board.S_RUN).contains("| running-demo |"), "在跑也不看 --since");
        check(Board.markdown(Arrays.asList(needsLead), NOW, 0).contains(Board.S_STUCK), "--since 0 也还留着卡住那一段");
    }

    /** 一单都没有的时候，整页只说一句实话。 */
    private static void empty() {
        String md = Board.markdown(new ArrayList<Board.Job>(), NOW, 24);
        check(md.contains("没有在跑的、没有等合并的。"), "一单都没有时说的是「没有在跑的、没有等合并的。」");
        check(md.indexOf(Board.S_RUN) < 0 && md.indexOf("| 单 |") < 0, "不摆两张空表出来");
        check(md.startsWith("# 状态板 ") && md.endsWith("\n"), "标题还在，文件以换行收尾");
    }

    /** 消耗：今天的单数、轮数、工具次数，最费的那一单。 */
    private static void cost(String md, Board.Job running, Board.Job accepted) {
        check(md.contains("- 今天：2 个单、3 轮、78 次工具调用（按每轮 `history[].tools` 汇总）；最费的一次：`accepted-demo`（41 次）"),
            "今天的账：25 小时前那单不算，工具次数按每轮加起来，最费的一次点名");
        check(md.contains("- 上限：同时在跑最多 2 单"), "上限那一行说清同时最多两单");
        String none = Board.markdown(Arrays.asList(new Board.Job("old", Board.ACCEPTED, 1, 4, 5,
            NOW - 48 * HOUR, NOW - 48 * HOUR, "aaaaaaa", true, "", "")), NOW, 24);
        check(none.contains("- 今天：没有单跑过。"), "今天一单没跑时，账上不编数字");
        check(running.tools == 37 && accepted.tools == 41, "工具次数是各轮加起来的");
    }

    /** 「花在模型上」：钱是钱，工具次数是工具次数；没测到就直说算不出来，不写 $0.00。 */
    private static void money(String md) throws Exception {
        Board.Job known = job(DIR + "cost-known.json");
        Board.Job none = job(DIR + "cost-none.json");
        check(known.history != null && known.history.length() == 2 && none.history != null,
            "fixture 的 history 原样带进了 Job：板子上的钱是从它加出来的");
        String both = section(Board.markdown(Arrays.asList(known, none), NOW, 24), Board.S_COST);
        check(both.equals(Board.S_COST + "\n\n"
                + "- 今天：2 个单、3 轮、48 次工具调用（按每轮 `history[].tools` 汇总）；最费的一次：`cost-known-demo`（41 次）\n"
                + "- 花在模型上：$6.15（2 单；1 单没有成本，合计是下限）\n"
                + "- 最费的几单：\n"
                + "  - cost-known-demo $6.15 (2 轮)\n"
                + "- 上限：同时在跑最多 2 单\n"),
            "消耗那一段：今天的账、花掉的钱、最费的几单、上限，一个字不差");
        String one = section(Board.markdown(Arrays.asList(known), NOW, 24), Board.S_COST);
        check(one.contains("- 花在模型上：$6.15（1 单）"), "测到的那一单：$6.145047 记到分是 $6.15（1 单）");
        check(one.indexOf("合计是下限") < 0, "都测到了就不说「合计是下限」");
        check(one.contains("- 最费的几单：\n  - cost-known-demo $6.15 (2 轮)\n"), "最费的那一行缩进两格写在标题下面");
        String blind = section(Board.markdown(Arrays.asList(none), NOW, 24), Board.S_COST);
        check(blind.contains("- 花在模型上：算不出来（1 单都没有记成本）"), "一轮都没记成本：说算不出来");
        check(blind.indexOf('$') < 0, "算不出来时一个美元数都不写：null 不是 $0.00");
        check(blind.indexOf("0.00") < 0, "$0.00 说的是「测到了，是零」，不许拿它顶替没测到");
        check(blind.indexOf("最费的几单") < 0, "没有一单排得进最费的，就不摆一个空标题出来");
        check(blind.contains("- 上限：同时在跑最多 2 单"), "算不出来时上限那一行照旧");
        String none24 = section(md, Board.S_COST);
        check(none24.contains("- 花在模型上：算不出来（2 单都没有记成本）"),
            "三个 fixture 那页板子：窗口里的两单都没记成本，照实说算不出来");
        String empty = section(Board.markdown(Arrays.asList(known), NOW, 0), Board.S_COST);
        check(empty.contains("- 今天：没有单跑过。") && empty.contains("- 花在模型上：没有单跑过。"),
            "--since 0：账上没有单子时，钱那一行也说没跑过，不写 $0.00");
        check(empty.indexOf('$') < 0 && empty.indexOf("最费") < 0, "没跑过就没有美元数，也没有最费的几单");
    }

    /** 钱那一行的位置：今天之后、最费的几单之前，最费的几单在上限之前。 */
    private static void moneyOrder() throws Exception {
        String md = Board.markdown(Arrays.asList(job(DIR + "cost-known.json"), job(DIR + "cost-none.json")), NOW, 24);
        int today = md.indexOf("- 今天：");
        int money = md.indexOf("- 花在模型上：");
        int dearest = md.indexOf("- 最费的几单：");
        int cap = md.indexOf("- 上限：同时在跑最多 ");
        check(today > 0 && money > today, "钱那一行写在「今天」后面：工具次数是替身，钱才是成本");
        check(dearest > money, "最费的几单跟在钱后面");
        check(cap > dearest, "最费的几单在上限之前");
        check(md.indexOf("- 花在模型上：", money + 1) < 0, "一页板子上只有一行钱");
        check(md.indexOf("- 今天：", today + 1) < 0 && md.indexOf("- 上限：", cap + 1) < 0,
            "今天和上限各只有一行，位置没被挤动");
        check(md.indexOf("- 今天：2 个单、3 轮、48 次工具调用（按每轮 `history[].tools` 汇总）；最费的一次：`cost-known-demo`（41 次）") > 0,
            "「今天」那一行一个字没改，pin 住的字面量还在");
    }

    /** 钱和「今天」是同一个 --since 窗口：窗口外的贵单不算进来。 */
    private static void moneySince() throws Exception {
        Board.Job known = job(DIR + "cost-known.json");
        Board.Job old = job(DIR + "cost-old.json");
        String day = section(Board.markdown(Arrays.asList(known, old), NOW, 24), Board.S_COST);
        check(day.contains("- 花在模型上：$6.15（1 单）"), "--since 24：两天前那单的 $99.50 不算进来");
        check(day.indexOf("cost-old-demo") < 0 && day.indexOf("99.50") < 0, "它也进不了最费的几单");
        String all = section(Board.markdown(Arrays.asList(known, old), NOW, 72), Board.S_COST);
        check(all.contains("- 花在模型上：$105.65（2 单）"), "--since 72：窗口拉大，那 $99.50 才算进来（6.145047+99.5）");
        check(all.contains("  - cost-old-demo $99.50 (1 轮)"), "它排进最费的几单，而且是第一行");
        check(all.indexOf("  - cost-known-demo $6.15 (2 轮)") > all.indexOf("  - cost-old-demo $99.50 (1 轮)"),
            "最费的几单从贵到便宜");
    }

    /** 最费的几单最多五单，每一行缩进两格。 */
    private static void dearestCap() throws Exception {
        List<Board.Job> jobs = new ArrayList<Board.Job>();
        for (int i = 1; i <= 7; i++) jobs.add(paid("j" + i, i, NOW - HOUR));
        String sec = section(Board.markdown(jobs, NOW, 24), Board.S_COST);
        check(sec.contains("- 花在模型上：$28.00（7 单）"), "七单的钱加起来是 $28.00");
        List<String> lines = new ArrayList<String>();
        for (String l : sec.split("\n")) if (l.startsWith("  - ")) lines.add(l);
        check(lines.size() == 5, "最费的只列五单，剩下的不挤进来");
        check(lines.equals(Arrays.asList("  - j7 $7.00 (1 轮)", "  - j6 $6.00 (1 轮)", "  - j5 $5.00 (1 轮)",
                "  - j4 $4.00 (1 轮)", "  - j3 $3.00 (1 轮)")),
            "五单从贵到便宜，每一行都缩进两格");
        check(sec.contains("- 最费的几单：\n  - j7 $7.00 (1 轮)\n"), "标题顶格，名单缩进两格");
        check(sec.indexOf("  - j1 ") < 0 && sec.indexOf("  - j2 ") < 0, "排在五名开外的单不点名");
    }

    /** 钱的算法只有一份：两边的板子都从 tools/cost 那边拿字面量，不各写各的。 */
    private static void moneyWiring() throws Exception {
        String mjs = source(MJS);
        String java = source(JAVA);
        check(mjs.contains("from '../cost/cost.mjs'"), "board.mjs 从 cost.mjs 拿钱的算法");
        check(mjs.contains("spend(window)") && mjs.contains("dearestLines(window, DEAREST)"),
            "board.mjs 的钱和最费的几单都用 cost.mjs 的那两个函数");
        check(mjs.contains("history: h,"), "normalize 把 history 原样带下来，板子才有账可算");
        check(java.contains("Cost.spend(") && java.contains("Cost.dearest("),
            "Board.java 同样用 Cost 的那两个方法，不自己再写一遍");
        check(java.contains("public final JSONArray history"), "Job 带着 history");
        check(java.contains("note, null);"), "十一个参数的那个构造函数还在，别的调用点一个都不用改");
        check(mjs.contains("repair(") && mjs.contains("resultEvents(") && mjs.contains("log.jsonl"),
            "命令行从 log.jsonl 补账：state.json 还是旧格式的单子也答得出花了多少");
        check(mjs.contains("- 最费的几单：") && java.contains("- 最费的几单："), "两边的「最费的几单：」一个字不差");
        check(!mjs.contains("- 花在模型上") && !java.contains("- 花在模型上"),
            "「- 花在模型上」那一行只写在 cost.mjs / Cost.java 里，板子不抄第二份");
        check(!java.contains("import android."), "Board.java 还是纯的：没有 android.*");
    }

    /** 一单一轮、账记在 history 里的 Job（造最费的几单那一串用）。 */
    private static Board.Job paid(String id, double usd, long updatedMs) throws Exception {
        JSONArray h = new JSONArray().put(new JSONObject().put("round", 1).put("tools", 3)
            .put("cost", new JSONObject().put("usd", usd)));
        return new Board.Job(id, Board.ACCEPTED, 1, 4, 3, updatedMs, updatedMs, "aaaaaaa", true, "", "", h);
    }

    /** 超了两单要写在板上，不能只在心里记着。 */
    private static void overflow() {
        List<Board.Job> three = new ArrayList<Board.Job>();
        for (int i = 1; i <= 3; i++) {
            three.add(new Board.Job("j" + i, Board.RUNNING, 1, 4, i, NOW - 60 * 1000L, NOW - 60 * 1000L, "", false, "", ""));
        }
        String md = Board.markdown(three, NOW, 24);
        check(md.contains("- 上限：同时在跑最多 2 单，现在 3 单在跑，超了：j1、j2、j3"), "同时跑三单时，上限那一行把超了的三单点名");
        check(!md.contains("现在 2 单在跑"), "两单不算超");
    }

    /** 表里新的在上，同一毫秒按单号排：每次刷新顺序都一样。 */
    private static void order(Board.Job accepted) {
        Board.Job older = new Board.Job("older", Board.ACCEPTED, 1, 4, 3, NOW - 5 * HOUR, NOW - 5 * HOUR,
            "bbbbbbb", false, "", "");
        Board.Job same = new Board.Job("aaa-same", Board.ACCEPTED, 1, 4, 3, accepted.updatedMs, accepted.updatedMs,
            "ccccccc", false, "", "");
        String md = Board.markdown(Arrays.asList(older, accepted, same), NOW, 24);
        int a = md.indexOf("| accepted-demo |");
        int b = md.indexOf("| older |");
        check(a > 0 && b > a, "完成得晚的单排在上面");
        check(md.indexOf("| aaa-same |") > 0 && md.indexOf("| aaa-same |") < a,
            "同一毫秒的单按单号排，顺序不会每次刷新都变");
    }

    /** 没说的话写「—」，不写一个空格糊过去。 */
    private static void blanks() {
        Board.Job bare = new Board.Job("bare", Board.RUNNING, 1, 4, 0, NOW, NOW, "", false, "", "");
        String[] c = cells(rowOf(Board.markdown(Arrays.asList(bare), NOW, 24), "bare"));
        check(c.length == 5 && "—".equals(c[4]), "最后一句没有时写「—」");
        check("1/4".equals(c[1]), "轮数上限缺省按 run.mjs 的 4 算");
        Board.Job stuck = new Board.Job("mute", "failed", 1, 4, 0, NOW, NOW, "", false, "", "");
        check(stuckOf(Board.markdown(Arrays.asList(stuck), NOW, 24), "mute").endsWith("state.json 里没写为什么"),
            "卡住又没记下原因时，板上直说没写，不编一个");
    }

    /** 三个 fixture 按 board.mjs 的同一套规则读成 Job。 */
    private static void mapping(Board.Job running, Board.Job accepted, Board.Job needsLead) throws Exception {
        check("running-demo".equals(running.id) && Board.RUNNING.equals(running.status), "running fixture 是 running");
        check(!running.merged && "".equals(running.commit), "在跑的单还没提交");
        check(accepted.status.equals(Board.ACCEPTED) && !accepted.merged && accepted.commit.length() == 7,
            "accepted fixture：验收过了、提交只留 7 位、还没合");
        check(needsLead.rounds == 2 && needsLead.tools == 74, "needs_lead fixture：两轮、30+44=74 次工具");
        check(needsLead.note.startsWith("1/3 没过："), "为什么取的是最后一轮验收，不是第一轮");
        String src = source(MJS);
        check(src.contains("export function board(states, opts)") || src.contains("export function board("),
            "board.mjs 导出的就是那个纯函数 board(states, opts)");
        check(source(JAVA).contains("public static String markdown(List<Job> jobs, long now, int hours)"),
            "Board.java 排版的主入口是 markdown(jobs, now, hours)");
    }

    /** 命令行读盘那半边：这台机的 shell 把 MSYS_NO_PATHCONV 留在环境里，/h/... 是原样传给 node 的。 */
    private static void paths() throws Exception {
        String src = source(MJS);
        check(src.contains("MSYS_NO_PATHCONV") && src.contains("function win("),
            "board.mjs 自己把 /h/... 翻成 H:/...：这台机传给 node 的 POSIX 路径是原样的");
        check(src.contains("merge-base', '--is-ancestor'") || src.contains("'merge-base'"),
            "合没合是问 git merge-base --is-ancestor，不是猜的");
        check(src.contains("'--out'") || src.contains("a.out"), "命令行认 --out");
        check(src.contains("SKYRUSH_OUT") && src.contains("assistant-out"),
            "单子目录认 $SKYRUSH_OUT/jobs，也认主仓库和 worktree 两种相对位置");
    }

    /** 两边排版必须一样：同一个字面量，JS 里有一份、Java 里也得有一份。 */
    private static void sameFormat() throws Exception {
        String mjs = source(MJS);
        String java = source(JAVA);
        String[] shared = {
            Board.S_RUN, Board.S_WAIT, Board.S_DONE, Board.S_STUCK, Board.S_COST,
            Board.HEAD_RUN, Board.HEAD_WAIT, Board.HEAD_DONE,
            Board.NONE, Board.EMPTY_BOARD, "state.json 里没写为什么", "超过 90 分钟没更新",
            "- 今天：", "（按每轮 `history[].tools` 汇总）；最费的一次：`", "- 上限：同时在跑最多 ", "没有单跑过。",
        };
        List<String> missing = new ArrayList<String>();
        for (String s : shared) {
            if (!mjs.contains(s)) missing.add("board.mjs 缺: " + s);
            if (!java.contains(s)) missing.add("Board.java 缺: " + s);
        }
        check(missing.isEmpty(), "两边的排版字面量一模一样" + (missing.isEmpty() ? "" : "（" + missing + "）"));
        check(mjs.contains("export const STUCK = ['needs_lead', 'failed', 'failed_round', 'outage', 'wrong_model']"),
            "board.mjs 的卡住名单是五个状态");
        check(java.contains("\"needs_lead\", \"failed\", \"failed_round\", \"outage\", \"wrong_model\""),
            "Board.java 的卡住名单和它一样，failed_round / wrong_model 不许漏");
        check(!java.contains("import android."), "Board.java 是纯的：没有 android.*");
    }

    // ---- fixture → Job：board.mjs 的 normalize/noteOf 的 Java 版 ----

    private static Board.Job job(String path) throws Exception {
        JSONObject s = new JSONObject(source(path));
        JSONArray h = s.optJSONArray("history");
        int tools = 0;
        if (h != null) {
            for (int i = 0; i < h.length(); i++) tools += h.getJSONObject(i).optInt("tools", 0);
        }
        String commit = s.optString("commit", "");
        return new Board.Job(s.getString("id"), s.getString("status"), s.optInt("rounds", 0), s.optInt("maxRounds", 0),
            tools, s.optLong("startedMs", 0L), s.optLong("updatedMs", 0L),
            commit.isEmpty() ? "" : commit.substring(0, Math.min(7, commit.length())),
            s.optBoolean("merged", false), s.optString("lastText", ""), note(s), h);
    }

    private static String note(JSONObject s) {
        if (s.has("note")) return s.getString("note");
        if (s.has("error") && !s.getString("error").isEmpty()) return s.getString("error");
        JSONArray h = s.optJSONArray("history");
        if (h == null || h.length() == 0) return "";
        JSONArray acc = h.getJSONObject(h.length() - 1).optJSONArray("acc");
        if (acc == null || acc.length() == 0) return "";
        StringBuilder names = new StringBuilder();
        StringBuilder bad = new StringBuilder();
        int nbad = 0;
        for (int i = 0; i < acc.length(); i++) {
            JSONObject a = acc.getJSONObject(i);
            if (i > 0) names.append("、");
            names.append(a.getString("name"));
            if (a.optBoolean("pass", false)) continue;
            nbad++;
            if (bad.length() > 0) bad.append("；");
            bad.append(a.getString("name"));
            JSONArray why = a.optJSONArray("why");
            if (why != null && why.length() > 0) {
                bad.append("（");
                for (int k = 0; k < why.length(); k++) {
                    if (k > 0) bad.append("；");
                    bad.append(why.getString(k));
                }
                bad.append("）");
            }
        }
        if (nbad == 0) return acc.length() + "/" + acc.length() + " 过：" + names;
        return nbad + "/" + acc.length() + " 没过：" + bad;
    }

    // ---- 从一页 markdown 里切出要看的部分 ----

    private static String section(String md, String head) {
        int at = md.indexOf(head);
        if (at < 0) return "";
        int end = md.indexOf("\n## ", at + head.length());
        return end < 0 ? md.substring(at) : md.substring(at, end);
    }

    private static String rowOf(String md, String id) {
        for (String l : md.split("\n")) if (l.startsWith("| " + id + " |")) return l;
        return "";
    }

    private static String stuckOf(String md, String id) {
        for (String l : md.split("\n")) if (l.startsWith("- " + id + "  ")) return l;
        return "";
    }

    /** 一行表格 → 每一栏（栏里的竖线已经被转义成 \|，所以按 " | " 切是安全的）。 */
    private static String[] cells(String row) {
        if (row.isEmpty()) return new String[0];
        return row.substring(2, row.length() - 2).split(" \\| ");
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }
}
