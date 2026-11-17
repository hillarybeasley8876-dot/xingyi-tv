package xyz.erapi0neer.xingyi.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 一页「停在哪、下一步先做什么」（{@link Handoff} + tools/handoff/handoff.mjs）：六段的顺序、一句话的四种说法、
 * 下一步三类事的先后和「最多三条」、卡在人这里的认哪些单、不要动那三行、今天的账说不说「下限」，
 * 以及最要紧的那一条——两边拿同一批 fixture 排出来的页面**逐字节一样**。
 *
 * <p>fixture 在 tests/agent/handoff-fixtures/：三个状态文件（空 / 忙 / 清）、一个验收红的，和两份 unverified。
 * 按 handoff.mjs 的 jobOf 同一套规则读成 {@link Handoff.Job}，再看排出来的页面对不对。
 */
final class HandoffChecks {
    /** 2026-11-12 11:20 +08：fixture 里的时刻都是相对它写的，检查才不会今天过明天不过。 */
    private static final long NOW = 1794453600000L;
    private static final long HOUR = 3600L * 1000L;
    private static final String DIR = "tests/agent/handoff-fixtures/";
    private static final String MJS = "tools/handoff/handoff.mjs";
    private static final String JAVA = "src/xyz/erapi0neer/xingyi/agent/Handoff.java";
    /** 一版比 fixture 里那次合并早的 APK：装在电视上的那一版比它新。 */
    private static final long APK = 1794440000000L;
    /** 磁盘上最新编出来的那一版：NOW 前一小时（11-12 10:20），比 APK（11-12 07:33）和那次合并都新。 */
    private static final long BUILT = NOW - HOUR;
    /** 一单都没合进来（在跑的、等 lead 的、忘了合的）：lead 自己的修复不是一单，2026-11-16 12:15 那一回就是这样。 */
    private static final String NO_MERGE = DIR + "states-busy.json";

    private HandoffChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static void run() throws Exception {
        shape();
        empty();
        oneLine();
        next();
        human();
        handsOff();
        cost();
        wiring();
        sameBytes();
        built();
        builtBytes();
    }

    /** 头一行和六段，顺序固定，每段都在。 */
    private static void shape() throws Exception {
        String md = page(DIR + "states-busy.json", DIR + "unverified-two.json");
        check(md.startsWith("# 接手：此刻停在哪（生成于 "), "这一页第一行是「# 接手：此刻停在哪（生成于 …）」");
        check(md.split("\n")[0].matches("# 接手：此刻停在哪（生成于 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}）"),
            "标题带的是本机的日期和钟点（yyyy-MM-dd HH:mm）");
        String[] heads = { Handoff.S_ONE, Handoff.S_NEXT, Handoff.S_HUMAN, Handoff.S_TV,
            Handoff.S_HANDS_OFF, Handoff.S_COST };
        int at = -1;
        boolean ordered = true;
        for (String h : heads) {
            int i = md.indexOf(h);
            if (i <= at) ordered = false;
            at = i;
        }
        check(ordered, "六段都在，而且按 一句话 / 下一步 / 卡在人这里的 / 还没在真机上验过的 / 不要动 / 今天花了多少 的顺序");
        boolean bodies = true;
        for (String h : heads) bodies = bodies && !section(md, h).trim().isEmpty();
        check(bodies, "六段每一段都有内容：没有一段只剩一个标题");
    }

    /** 什么都没有的时候：六段照样都在，空的地方说人话。 */
    private static void empty() throws Exception {
        String md = page(DIR + "states-empty.json", DIR + "unverified-none.json");
        check(md.contains(Handoff.S_ONE) && md.contains(Handoff.S_NEXT) && md.contains(Handoff.S_HUMAN)
            && md.contains(Handoff.S_TV) && md.contains(Handoff.S_HANDS_OFF) && md.contains(Handoff.S_COST),
            "一单都没有的时候，六段照样都在");
        check(section(md, Handoff.S_HUMAN).trim().equals("## 卡在人这里的\n\n" + Board.NONE),
            "没有卡在人这里的单，那一段只写「（没有）」");
        check(section(md, Handoff.S_TV).trim().endsWith(Handoff.NO_UNVERIFIED),
            "一条都没验过时，那一段写「一条都没有：装上的东西都在真机上见过」");
        check(section(md, Handoff.S_NEXT).trim().endsWith(Handoff.NEXT_NONE),
            "没有必须现在做的事时，下一步写「没有必须现在做的：读 `HARNESS.md` 的最后一节」");
        check(section(md, Handoff.S_ONE).trim().endsWith(Handoff.CLEAR + "。"),
            "什么都没发生时，一句话是「没有在跑的、没有卡住人的、装上的东西都在真机上见过。」");
    }

    /** 一句话的四种说法，每种都只有一句。 */
    private static void oneLine() {
        List<Handoff.Job> none = new ArrayList<Handoff.Job>();
        String running = Handoff.page(Arrays.asList(job("run", "running", 0, 0, NOW - HOUR, false, "", null)),
            new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS);
        check(section(running, Handoff.S_ONE).trim().endsWith("1" + Handoff.P_RUN + "。"),
            "只有一单在跑：一句话是「1 单在跑。」");
        String lead = Handoff.page(Arrays.asList(job("lead", "needs_lead", 1, 3, NOW - HOUR, false, "", null)),
            new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS);
        check(section(lead, Handoff.S_ONE).trim().endsWith("1" + Handoff.P_HUMAN + "。"),
            "只有一单 needs_lead：一句话是「1 单卡在人这里。」");
        String clear = Handoff.page(none, new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS);
        check(section(clear, Handoff.S_ONE).trim().endsWith(Handoff.CLEAR + "。"),
            "什么都没发生：一句话是那一句「没有在跑的、没有卡住人的、装上的东西都在真机上见过。」");
        List<Handoff.Job> both = Arrays.asList(
            job("run", "running", 0, 0, NOW - HOUR, false, "", null),
            job("lead", "needs_lead", 1, 3, NOW - HOUR, false, "", null));
        String md = Handoff.page(both, new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS);
        String one = section(md, Handoff.S_ONE).trim();
        check(one.endsWith("1" + Handoff.P_RUN + "；1" + Handoff.P_HUMAN + "。"),
            "两样都有：一句话是「1 单在跑；1 单卡在人这里。」");
        check(one.substring(one.indexOf('\n') + 1).indexOf('。') == one.substring(one.indexOf('\n') + 1).length() - 1,
            "一句话就是一句：通篇只有一个「。」，在末尾");
    }

    /** 下一步：验收红 → 上电视验 → 装这一版，按这个顺序，最多三条。 */
    private static void next() throws Exception {
        String md = page(Arrays.asList(DIR + "states-red.json", DIR + "states-clear.json"),
            DIR + "unverified-two.json", APK);
        String s = section(md, Handoff.S_NEXT);
        List<String> items = items(s);
        check(items.size() == 3, "三件事都在下一步里（实际 " + items.size() + " 条）");
        check(items.size() == 3 && items.get(0).startsWith("1. " + Handoff.RED + "red-acceptance")
            && items.get(1).startsWith("2. " + Handoff.TV_NEXT + "ktcp-pause-real")
            && items.get(2).startsWith("3. " + Handoff.INSTALL),
            "顺序是 验收红 → 还没验 → 装这一版：红的那条在最上面");
        check(items.size() == 3 && items.get(0).contains("bridge 没过")
            && items.get(0).contains("`sh tests/agent/accept_handoff.sh`"),
            "重跑验收那一条带着没过的那条验收和它的命令（命令从这一单的 accept 清单里按名字找来的）");
        check(items.size() == 3 && !items.get(0).contains("stray"),
            "没命令可重跑的那条验收（stray）不算「红在一条命令上」，不排在最上面催人");
        check(items.size() == 3 && items.get(0).contains("4 轮、210 次工具"),
            "重跑验收那一条说着这一单已经烧了多少：4 轮、210 次工具");
        check(items.size() == 3 && items.get(1).contains("`tests/agent/verify.sh`"),
            "上电视验那一条带着脚本的路径");
        check(items.size() == 3 && items.get(2).contains(Handoff.INSTALL_CMD) && items.get(2).contains(Handoff.APK_NEWER),
            "装这一版那一条带着 build + install 的命令，并说清为什么（比装的 APK 新）");

        // 没有脚本的记录：说清要手动走，不编一条命令出来
        List<String> noScript = new ArrayList<String>();
        noScript.add("{\"id\": \"standby-home-real\", \"feature\": \"待机时说回桌面\", \"needs\": [\"亮屏\"],"
            + " \"since\": \"2026-11-12\", \"script\": \"\"}");
        String manual = Handoff.page(jobs(DIR + "states-clear.json"), noScript, NOW, Handoff.DEFAULT_SINCE_MS, APK);
        List<String> manualItems = items(section(manual, Handoff.S_NEXT));
        check(manualItems.size() == 2 && manualItems.get(0).contains(Handoff.NO_SCRIPT_CMD),
            "记录里没写脚本时，那一条说「没有脚本：照着 needs 手动走一遍」，不编一条命令出来");

        // 在跑的单不催人重跑：runner 自己会跑下一轮
        Handoff.Job busyRed = new Handoff.Job("run", "running", 2, 30, NOW - HOUR, false, "", null,
            "bridge", "sh tests/agent/accept_handoff.sh");
        String busyMd = Handoff.page(Arrays.asList(busyRed), new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS, APK);
        check(!section(busyMd, Handoff.S_NEXT).contains(Handoff.RED),
            "在跑的单不进「重跑验收」：它自己还会跑下一轮，这一页不催人做 runner 正在做的事");

        // 最多三条，多的说一句还剩几条
        List<Handoff.Job> four = new ArrayList<Handoff.Job>();
        for (int i = 0; i < 5; i++) {
            four.add(new Handoff.Job("red-" + i, "failed", 1, 5, NOW - HOUR * (i + 1), false, "", null,
                "core-checks", "sh tests/agent/run_core.sh"));
        }
        String many = Handoff.page(four, noScript, NOW, Handoff.DEFAULT_SINCE_MS, APK);
        List<String> manyItems = items(section(many, Handoff.S_NEXT));
        check(manyItems.size() == 3, "六件事也只列三条（实际 " + manyItems.size() + " 条）");
        check(section(many, Handoff.S_NEXT).contains(Handoff.MORE_HEAD + "3" + Handoff.MORE_TAIL),
            "超过三条时说一句还剩几条：「（还有 3 条没列出来：看 `status/BOARD.md`）」");

        // 装的那一版比合并还新：不催着装
        String fresh = page(Arrays.asList(DIR + "states-clear.json"), DIR + "unverified-none.json",
            NOW + HOUR);
        check(!section(fresh, Handoff.S_NEXT).contains(Handoff.INSTALL),
            "装的 APK 比这一窗口里合进来的都新时，不催着装这一版");
        String none = page(Arrays.asList(DIR + "states-clear.json"), DIR + "unverified-none.json", 0L);
        check(section(none, Handoff.S_NEXT).contains(Handoff.APK_NONE),
            "手上还没有 APK 时，那一条说「手上还没有 APK」，不说「比装的 APK 新」");
    }

    /** 卡在人这里的：认哪些单，每条为什么，最后一句截到 100 个字。 */
    private static void human() throws Exception {
        String md = page(DIR + "states-busy.json", DIR + "unverified-none.json");
        String s = section(md, Handoff.S_HUMAN);
        check(s.contains("- busy-needs-lead：" + Handoff.WHY_NEEDS_LEAD), "needs_lead 的单在卡在人这里，说清为什么");
        check(s.contains("- busy-forgotten：" + Handoff.FORGOT + "25" + Handoff.HOURS),
            "过了验收 25 小时还没合的单在里面，写着「过了验收 25 小时还没合（我忘了）」");
        check(s.contains(Handoff.LAST) && s.contains("验收三条全过，改动已提交，等 lead 合。"),
            "每条带着 worker 的最后一句");
        String lead = line(s, "busy-needs-lead");
        check(lead.endsWith("…"), "最后一句超过 100 个字就截掉（这一条以 … 结尾）");
        check(lead.length() <= 200, "截掉之后那一行还看得完（" + lead.length() + " 个字）");

        Handoff.Job fresh = job("fresh", "accepted_mechanical", 1, 4, NOW - HOUR, false, "刚过验收", null);
        String freshMd = Handoff.page(Arrays.asList(fresh), new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS);
        check(!section(freshMd, Handoff.S_HUMAN).contains("fresh"),
            "一小时前刚过验收的单不在里面：还没到「我忘了」的时候");
        Handoff.Job doneOld = job("done", "accepted_mechanical", 1, 4, NOW - 25 * HOUR, true, "合了", null);
        String doneMd = Handoff.page(Arrays.asList(doneOld), new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS);
        check(!section(doneMd, Handoff.S_HUMAN).contains("done"),
            "25 小时前合了的单不在里面：合了就不算卡在人这里");

        List<Handoff.Job> stuck = Arrays.asList(
            job("a", "needs_lead", 1, 1, NOW - HOUR, false, "", null),
            job("b", "failed", 1, 1, NOW - HOUR, false, "", null),
            job("c", "failed_round", 1, 1, NOW - HOUR, false, "", null),
            job("d", "outage", 1, 1, NOW - HOUR, false, "", null),
            job("e", "wrong_model", 1, 1, NOW - HOUR, false, "", null));
        String all = section(Handoff.page(stuck, new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS),
            Handoff.S_HUMAN);
        check(all.contains(Handoff.WHY_NEEDS_LEAD) && all.contains(Handoff.WHY_FAILED)
            && all.contains(Handoff.WHY_FAILED_ROUND) && all.contains(Handoff.WHY_OUTAGE)
            && all.contains(Handoff.WHY_WRONG_MODEL),
            "五种卡住的状态各有一句自己的为什么（failed_round 也在里面：没人管它就不会再动）");
        check(all.contains("- b：" + Handoff.WHY_FAILED), "failed 的单在卡在人这里");
    }

    /** 不要动：固定三行，两个文件路径都在。 */
    private static void handsOff() throws Exception {
        String s = section(page(DIR + "states-empty.json", DIR + "unverified-none.json"), Handoff.S_HANDS_OFF);
        check(s.contains("tests/agent/AgentCoreTest.java"), "不要动那一段写着 tests/agent/AgentCoreTest.java");
        check(s.contains("LocalAdb.java") && s.contains("assets/agent/contract.json"),
            "也写着 LocalAdb.java 和 assets/agent/contract.json（lead 的）");
        check(s.contains("装应用、改系统设置：要用户点头"), "还写着装应用、改系统设置要用户点头");
        int n = 0;
        for (String l : s.split("\n")) if (l.startsWith("- ")) n++;
        check(n == 3, "那一段就是三行（实际 " + n + " 行）");
    }

    /** 今天的账：cost 的合计，和「合计是下限」。 */
    private static void cost() throws Exception {
        String md = page(DIR + "states-busy.json", DIR + "unverified-two.json");
        String s = section(md, Handoff.S_COST);
        check(s.contains("- 花在模型上：$2.03（2 单；1 单没有成本，合计是下限）"),
            "今天的账是 cost 的合计，并且有一单没测到成本时说「合计是下限」（实际：" + s.trim() + "）");
        check(s.contains("2 单"), "25 小时前那一单不算进今天：窗口里是 2 单");
        String all = section(page(DIR + "states-clear.json", DIR + "unverified-none.json"), Handoff.S_COST);
        check(all.contains("$2.50") && !all.contains("下限"),
            "每一单都记了成本时不说「下限」（实际：" + all.trim() + "）");
        Handoff.Job unknown = job("x", "failed", 1, 1, NOW - HOUR, false, "", null);
        String none = section(Handoff.page(Arrays.asList(unknown), new ArrayList<String>(), NOW,
            Handoff.DEFAULT_SINCE_MS), Handoff.S_COST);
        check(none.contains("算不出来"), "一单成本都没测到时说「算不出来」，不写 $0.00（实际：" + none.trim() + "）");
    }

    /** 两边共用的字面量，以及「读的那一侧用 board.mjs 的、钱用 cost.mjs 的」这条线。 */
    private static void wiring() throws Exception {
        String mjs = source(MJS);
        String java = source(JAVA);
        String[] shared = {
            Handoff.TITLE, Handoff.S_ONE, Handoff.S_NEXT, Handoff.S_HUMAN, Handoff.S_TV, Handoff.S_HANDS_OFF,
            Handoff.S_COST, Handoff.P_RUN, Handoff.P_HUMAN, Handoff.P_TV, Handoff.CLEAR, Handoff.NO_UNVERIFIED,
            Handoff.NEXT_NONE, Handoff.MORE_HEAD, Handoff.MORE_TAIL, Handoff.LAST, Handoff.NO_NEEDS,
            Handoff.NO_SCRIPT_LINE, Handoff.NO_SCRIPT_CMD, Handoff.BAD_ENTRY, Handoff.APK_NEWER, Handoff.APK_NONE,
            Handoff.INSTALL_CMD, Handoff.RED, Handoff.TV_NEXT, Handoff.INSTALL, Handoff.FORGOT, Handoff.HOURS,
            Handoff.WHY_NEEDS_LEAD, Handoff.WHY_FAILED, Handoff.WHY_FAILED_ROUND, Handoff.WHY_OUTAGE,
            Handoff.WHY_WRONG_MODEL, " 没过，这一单已经跑了 ", " 轮、", " 次工具 · `", " 还没在真机上见过 · ",
            " 单在这个窗口里合进来了，",
        };
        List<String> missing = new ArrayList<String>();
        for (String s : shared) {
            if (!mjs.contains(s)) missing.add("handoff.mjs 缺: " + s);
            if (!java.contains(s)) missing.add("Handoff.java 缺: " + s);
        }
        check(missing.isEmpty(), "两边的字面量一模一样" + (missing.isEmpty() ? "" : "（" + missing + "）"));
        for (String l : Handoff.HANDS_OFF) {
            check(mjs.contains(l) && java.contains(l), "不要动的三行两边一个字不差：" + l.substring(0, 12) + "…");
        }
        check(!java.contains("import android."), "Handoff.java 是纯的：没有 android.*");
        check(mjs.contains("from '../board/board.mjs'") && mjs.contains("normalize") && mjs.contains("cell(")
            && mjs.contains("stamp("), "handoff.mjs 读的那一侧用 board.mjs 自己的 normalize / cell / stamp，不重写一遍");
        check(!mjs.contains(Board.HEAD_RUN) && !mjs.contains(Board.HEAD_WAIT) && !mjs.contains(Board.HEAD_DONE),
            "handoff.mjs 不抄板子的表头：这一页一张表都没有");
        check(mjs.contains("from '../cost/cost.mjs'") && mjs.contains("spend(")
            && !mjs.contains("- 花在模型上") && !java.contains("- 花在模型上"),
            "钱走 cost.mjs / Cost.java 的 spend：「- 花在模型上」那一行只写在它们里");
        check(java.contains("Cost.spend(") && java.contains("public static String page("),
            "Handoff.java 同样用 Cost.spend，page 的签名是 brief 要的那个");
        check(mjs.contains("SKYRUSH_OUT") && mjs.contains("--out") && mjs.contains("UNVERIFIED"),
            "命令行认 --out / --jobs / $SKYRUSH_OUT，默认的 unverified.json 写在代码里");
        check(mjs.contains("MSYS_NO_PATHCONV") && mjs.contains("function win("),
            "handoff.mjs 自己把 /h/... 翻成 H:/...：这台机传给 node 的 POSIX 路径是原样的");
    }

    /** 同一批 fixture，JS 和 Java 排出来的页面逐字节一样。 */
    private static void sameBytes() throws Exception {
        String exe = nodeExe();
        if (exe == null) {
            System.out.println("handoff: node 不在 PATH 上，JS 和 Java 的逐字节比对跳过（Java 这一边照查）");
            return;
        }
        String[][] combos = {
            { DIR + "states-empty.json", DIR + "unverified-none.json", "0" },
            { DIR + "states-busy.json", DIR + "unverified-two.json", String.valueOf(APK) },
            { DIR + "states-clear.json", DIR + "unverified-none.json", String.valueOf(APK) },
        };
        for (String[] c : combos) {
            String java = page(Arrays.asList(c[0]), c[1], Long.parseLong(c[2]));
            String js = node(exe, Arrays.asList(c[0]), c[1], Long.parseLong(c[2]));
            check(java.equals(js), "JS 和 Java 逐字节一样：" + c[0].substring(c[0].lastIndexOf('/') + 1)
                + (java.equals(js) ? "" : "（第一处不同：" + firstDiff(java, js) + "）"));
        }
        // 顺序那一组要两个状态文件：--states 可以连着给
        List<String> two = Arrays.asList(DIR + "states-red.json", DIR + "states-clear.json");
        String java = page(two, DIR + "unverified-two.json", APK);
        String js = node(exe, two, DIR + "unverified-two.json", APK);
        check(java.equals(js), "JS 和 Java 逐字节一样：states-red + states-clear（下一步三类事的顺序）"
            + (java.equals(js) ? "" : "（第一处不同：" + firstDiff(java, js) + "）"));
    }

    /** 编好了还没装：没有单子合进来，磁盘上编出来的那一版却比电视上的新——这也是一次在等人点头的装机。 */
    private static void built() throws Exception {
        String line = "1. " + Handoff.INSTALL + " · " + Handoff.BUILT + "11-12 10:20" + Handoff.ON_TV + "11-12 07:33"
            + "）· " + Handoff.INSTALL_CMD;
        List<String> a = items(section(page(Arrays.asList(NO_MERGE), DIR + "unverified-none.json", APK, BUILT),
            Handoff.S_NEXT));
        check(a.size() == 1 && a.get(0).equals(line),
            "(a) 没有新合进来的单、编出来的比装着的新：下一步就是那一条「编好了还没装（11-12 10:20，电视上的是 11-12 07:33）」"
            + "（实际 " + a + "）");
        String b = section(page(Arrays.asList(NO_MERGE), DIR + "unverified-none.json", APK, APK), Handoff.S_NEXT);
        check(b.trim().endsWith(Handoff.NEXT_NONE) && !b.contains(Handoff.INSTALL),
            "(b) 编出来的就是装着的那一版：不催着装，下一步是「没有必须现在做的」");
        List<String> c = items(section(page(Arrays.asList(DIR + "states-clear.json"), DIR + "unverified-none.json",
            APK, BUILT), Handoff.S_NEXT));
        int installs = 0;
        for (String l : c) if (l.contains(Handoff.INSTALL)) installs++;
        check(installs == 1, "(c) 有一单合进来、编出来的也更新：装机提醒只有一条（实际 " + installs + " 条）");
        check(c.size() == 1 && c.get(0).contains(" 单在这个窗口里合进来了，") && !c.get(0).contains(Handoff.BUILT),
            "(c) 那一条是老的那条（「1 单在这个窗口里合进来了」），不是「编好了还没装」");
        List<String> d = items(section(page(Arrays.asList(DIR + "states-empty.json"), DIR + "unverified-none.json",
            0L, BUILT), Handoff.S_NEXT));
        check(d.size() == 1 && d.get(0).contains(Handoff.BUILT + "11-12 10:20" + Handoff.ON_TV_UNKNOWN + "）· "),
            "(d) 不知道电视上是哪版、手上编出来了一版：那一条写「电视上的是哪版不知道」（实际 " + d + "）");
        check(page(Arrays.asList(NO_MERGE), DIR + "unverified-none.json", APK, 0L)
            .equals(page(Arrays.asList(NO_MERGE), DIR + "unverified-none.json", APK)),
            "builtMs = 0（不知道编出来的是哪版）时，这一页和没有这个参数时一模一样");
        check(Handoff.page(jobs(NO_MERGE), new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS, APK)
            .equals(Handoff.page(jobs(NO_MERGE), new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS, APK, 0L)),
            "旧的五个参数的 page 还在，等于 builtMs 传 0：别的调用方不用改");
        String mjs = source(MJS);
        String java = source(JAVA);
        boolean same = true;
        for (String s : new String[] { Handoff.BUILT, Handoff.ON_TV, Handoff.ON_TV_UNKNOWN, "）· " }) {
            same = same && mjs.contains(s) && java.contains(s);
        }
        check(same, "「编好了还没装」那一条的字面量两边一模一样");
        check(mjs.contains("function builtMsOf(") && mjs.contains("a['built-ms']") && mjs.contains("a.built"),
            "命令行有 builtMsOf，认 --built <path>，--print 认 --built-ms");
        builtFull();
        builtClock();
    }

    /** 下一步满了三条时，「编好了还没装」照样排在最后，挤不进前三就算进「还有 N 条」。 */
    private static void builtFull() throws Exception {
        List<String> two = Arrays.asList(NO_MERGE, DIR + "states-red.json");
        List<String> tail = items(section(page(two, DIR + "unverified-two.json", APK, BUILT), Handoff.S_NEXT));
        check(tail.size() == 3 && tail.get(0).startsWith("1. " + Handoff.RED)
            && tail.get(1).startsWith("2. " + Handoff.TV_NEXT) && tail.get(2).contains(Handoff.BUILT),
            "顺序照旧：验收红 → 上电视验 → 编好了还没装（实际 " + tail + "）");
        List<Handoff.Job> reds = new ArrayList<Handoff.Job>();
        for (int i = 0; i < 3; i++) {
            reds.add(new Handoff.Job("red-" + i, "failed", 1, 5, NOW - HOUR * (i + 1), false, "", null,
                "core-checks", "sh tests/agent/run_core.sh"));
        }
        String full = section(Handoff.page(reds, new ArrayList<String>(), NOW, Handoff.DEFAULT_SINCE_MS, APK, BUILT),
            Handoff.S_NEXT);
        check(items(full).size() == 3 && !full.contains(Handoff.BUILT)
            && full.contains(Handoff.MORE_HEAD + "1" + Handoff.MORE_TAIL),
            "三条验收红已经占满：「编好了还没装」不挤进去，算进「（还有 1 条没列出来…）」");
    }

    /** 时刻按北京时间写：跑这一页的那台机设成别的时区，写出来的还是同一个钟点。 */
    private static void builtClock() {
        TimeZone was = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            check(Handoff.clock(NOW).equals("11-12 11:20") && Handoff.clock(APK).equals("11-12 07:33"),
                "时刻是北京时间的 MM-dd HH:mm，本机时区设成纽约也一样（实际 " + Handoff.clock(NOW) + "）");
        } finally {
            TimeZone.setDefault(was);
        }
    }

    /** 同一批 fixture 带上 builtMs，JS 和 Java 照样逐字节一样；有一组让两边都在纽约时区里跑。 */
    private static void builtBytes() throws Exception {
        String exe = nodeExe();
        if (exe == null) {
            System.out.println("handoff: node 不在 PATH 上，builtMs 那几组的逐字节比对跳过（Java 这一边照查）");
            return;
        }
        Object[][] combos = {
            { "(a) states-busy，编的比装的新", Arrays.asList(NO_MERGE), APK, BUILT },
            { "(b) states-busy，编的就是装的", Arrays.asList(NO_MERGE), APK, APK },
            { "(c) states-clear，合进来一单、编的也更新", Arrays.asList(DIR + "states-clear.json"), APK, BUILT },
            { "(d) states-empty，不知道装的是哪版", Arrays.asList(DIR + "states-empty.json"), 0L, BUILT },
            { "states-busy + states-red，三条排满", Arrays.asList(NO_MERGE, DIR + "states-red.json"), APK, BUILT },
        };
        for (Object[] c : combos) {
            @SuppressWarnings("unchecked")
            List<String> files = (List<String>) c[1];
            String unv = files.size() > 1 ? DIR + "unverified-two.json" : DIR + "unverified-none.json";
            long apk = ((Long) c[2]).longValue();
            long built = ((Long) c[3]).longValue();
            String java = page(files, unv, apk, built);
            String js = node(exe, files, unv, apk, built, null);
            check(java.equals(js), "JS 和 Java 逐字节一样：" + c[0]
                + (java.equals(js) ? "" : "（第一处不同：" + firstDiff(java, js) + "）"));
        }
        builtBytesElsewhere(exe);
    }

    /** 两边都在纽约时区里跑：标题跟着本机走（两边一起变），「编好了还没装」那一条的钟点两边都不跟着变。 */
    private static void builtBytesElsewhere(String exe) throws Exception {
        String unv = DIR + "unverified-none.json";
        TimeZone was = TimeZone.getDefault();
        String java;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            java = page(Arrays.asList(NO_MERGE), unv, APK, BUILT);
        } finally {
            TimeZone.setDefault(was);
        }
        String js = node(exe, Arrays.asList(NO_MERGE), unv, APK, BUILT, "America/New_York");
        check(java.equals(js) && js.contains("11-12 10:20" + Handoff.ON_TV + "11-12 07:33"),
            "本机在纽约时区时 JS 和 Java 照样逐字节一样，装机那一条写的还是北京时间"
            + (java.equals(js) ? "" : "（第一处不同：" + firstDiff(java, js) + "）"));
    }
    private static String firstDiff(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) if (a.charAt(i) != b.charAt(i)) {
            return "第 " + i + " 个字，Java 是「" + a.substring(i, Math.min(a.length(), i + 30))
                + "」，JS 是「" + b.substring(i, Math.min(b.length(), i + 30)) + "」";
        }
        return "长度不同：Java " + a.length() + "，JS " + b.length();
    }

    /** 哪个 node：环境里的 NODE，其次这台机上装着的那一版，再其次 PATH 上的 node。都跑不动就是 null。 */
    private static String nodeExe() {
        List<String> cands = new ArrayList<String>();
        String env = System.getenv("NODE");
        if (env != null && !env.isEmpty()) cands.add(env);
        cands.add("H:/tools/node/node-v24.22.2-win-x64/node.exe");
        cands.add("node");
        for (String c : cands) {
            try {
                Process p = new ProcessBuilder(c, "--version").redirectErrorStream(true).start();
                read(p.getInputStream());
                if (p.waitFor() == 0) return c;
            } catch (Exception e) {
                // 这一个跑不动：试下一个
            }
        }
        return null;
    }

    private static String node(String exe, List<String> stateFiles, String unvFile, long apk) throws Exception {
        return node(exe, stateFiles, unvFile, apk, 0L, null);
    }

    /** {@code tz} 不是 null 时，node 在那个时区里跑（TZ 环境变量）。 */
    private static String node(String exe, List<String> stateFiles, String unvFile, long apk, long built, String tz)
        throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(exe);
        cmd.add(MJS);
        for (String f : stateFiles) { cmd.add("--states"); cmd.add(f); }
        cmd.add("--unverified");
        cmd.add(unvFile);
        cmd.add("--now");
        cmd.add(String.valueOf(NOW));
        cmd.add("--since-ms");
        cmd.add(String.valueOf(Handoff.DEFAULT_SINCE_MS));
        cmd.add("--apk-ms");
        cmd.add(String.valueOf(apk));
        cmd.add("--built-ms");
        cmd.add(String.valueOf(built));
        cmd.add("--print");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (tz != null) pb.environment().put("TZ", tz);
        Process p = pb.start();
        byte[] out = read(p.getInputStream());
        byte[] err = read(p.getErrorStream());
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("node 跑 handoff.mjs 退 " + code + "："
            + new String(err, StandardCharsets.UTF_8));
        return new String(out, StandardCharsets.UTF_8);
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    // ---- fixture → Job：handoff.mjs 的 jobOf / usdOf / redOf 的 Java 版 ----

    private static String page(String statesFile, String unvFile) throws Exception {
        return page(Arrays.asList(statesFile), unvFile, APK);
    }

    private static String page(List<String> statesFiles, String unvFile, long apk) throws Exception {
        return page(statesFiles, unvFile, apk, 0L);
    }

    private static String page(List<String> statesFiles, String unvFile, long apk, long built) throws Exception {
        List<Handoff.Job> all = new ArrayList<Handoff.Job>();
        for (String f : statesFiles) all.addAll(jobs(f));
        return Handoff.page(all, unverified(unvFile), NOW, Handoff.DEFAULT_SINCE_MS, apk, built);
    }

    private static List<Handoff.Job> jobs(String path) throws Exception {
        JSONArray a = new JSONArray(source(path));
        List<Handoff.Job> out = new ArrayList<Handoff.Job>();
        for (int i = 0; i < a.length(); i++) out.add(job(a.getJSONObject(i)));
        return out;
    }

    /** unverified.json → 每条记录的原文：Handoff 收的就是原文，两边一样。 */
    private static List<String> unverified(String path) throws Exception {
        JSONObject j = new JSONObject(source(path));
        JSONArray es = j.optJSONArray("entries");
        List<String> out = new ArrayList<String>();
        if (es == null) return out;
        for (int i = 0; i < es.length(); i++) out.add(es.get(i).toString());
        return out;
    }

    private static Handoff.Job job(String id, String status, int rounds, int tools, long updatedMs, boolean merged,
                                   String last, Double usd) {
        return new Handoff.Job(id, status, rounds, tools, updatedMs, merged, last, usd);
    }

    private static Handoff.Job job(JSONObject s) {
        JSONArray h = s.optJSONArray("history");
        int tools = 0;
        Double usd = null;
        if (h != null) {
            for (int i = 0; i < h.length(); i++) {
                JSONObject r = h.optJSONObject(i);
                if (r == null) continue;
                tools += r.optInt("tools", 0);
                JSONObject c = r.optJSONObject("cost");
                if (c == null) continue;
                Object v = c.opt("usd");
                if (!(v instanceof Number)) continue;
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                usd = Double.valueOf((usd == null ? 0d : usd.doubleValue()) + d);
            }
        }
        String[] red = red(s);
        String last = s.has("lastText") ? s.optString("lastText", "") : s.optString("last", "");
        return new Handoff.Job(s.optString("id", "?"), s.optString("status", "new"), s.optInt("rounds", 0), tools,
            s.optLong("updatedMs", 0L), s.optBoolean("merged", false), last, usd, red[0], red[1]);
    }

    /** 最后一轮里没过的那条验收和它的命令；命令先看这一轮自己带的 cmd，再看这一单的 accept 清单。 */
    private static String[] red(JSONObject s) {
        JSONArray h = s.optJSONArray("history");
        JSONObject last = (h == null || h.length() == 0) ? null : h.optJSONObject(h.length() - 1);
        JSONArray acc = last != null ? last.optJSONArray("acc") : s.optJSONArray("acc");
        JSONArray spec = s.optJSONArray("accept");
        if (acc == null) return new String[] { "", "" };
        for (int i = 0; i < acc.length(); i++) {
            JSONObject a = acc.optJSONObject(i);
            if (a == null || a.optBoolean("pass", true)) continue;
            String name = a.optString("name", "");
            String cmd = a.optString("cmd", "");
            if (cmd.isEmpty() && spec != null) {
                for (int k = 0; k < spec.length(); k++) {
                    JSONObject p = spec.optJSONObject(k);
                    if (p == null || !name.equals(p.optString("name", ""))) continue;
                    if (!p.optString("cmd", "").isEmpty()) { cmd = p.optString("cmd", ""); break; }
                }
            }
            if (!cmd.isEmpty()) return new String[] { name, cmd };
        }
        return new String[] { "", "" };
    }

    // ---- 从一页 markdown 里切出要看的部分 ----

    private static String section(String md, String head) {
        int at = md.indexOf(head);
        if (at < 0) return "";
        int end = md.indexOf("\n## ", at + head.length());
        return end < 0 ? md.substring(at) : md.substring(at, end);
    }

    /** 下一步那一段里的编号条目（「（还有 N 条…）」那行不算一条）。 */
    private static List<String> items(String s) {
        List<String> out = new ArrayList<String>();
        for (String l : s.split("\n")) if (l.matches("^\\d+\\. .*")) out.add(l);
        return out;
    }

    private static String line(String s, String id) {
        for (String l : s.split("\n")) if (l.startsWith("- " + id + "：")) return l;
        return "";
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
