package xyz.erapi0neer.xingyi.agent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一页「停在哪、下一步先做什么」：给凌晨三点接手的人看的那一页。纯函数——给它单子的状态和还没在真机上验过的记录，
 * 它给回一页 markdown；数据从哪来（state.json、这个提交合了没有、APK 是什么时候编的）是调用的人的事，这里只排版。
 *
 * <p>板子（{@link Board}）答的是「现在是什么情况」，这一页答另外三个问题：下一件先做什么、哪几件卡在人这里、
 * 什么不许动。同一套规则的 JS 版在 tools/handoff/handoff.mjs，HandoffChecks 拿同一批 fixture 两边各跑一遍、
 * 比到字节一样——这一页是给人在最累的时候看的，两边排出两页不一样就等于没有这一页。
 *
 * <p>六段，顺序固定，每段都在：一句话 / 下一步 / 卡在人这里的 / 还没在真机上验过的 / 不要动 / 今天花了多少。
 */
public final class Handoff {
    /** 「今天」这一窗口默认多长：24 小时。 */
    public static final long DEFAULT_SINCE_MS = 24L * 3600L * 1000L;
    /** 过了验收 24 小时还没合，算「我忘了」：跟窗口无关——窗口调小也不该把忘掉的单藏起来。 */
    public static final long FORGOT_MS = 24L * 3600L * 1000L;
    /** 下一步最多列三条：再多就不是「下一件先做什么」了。 */
    public static final int MAX_NEXT = 3;
    private static final long HOUR = 3600L * 1000L;

    public static final String TITLE = "# 接手：此刻停在哪（生成于 ";
    public static final String S_ONE = "## 一句话";
    public static final String S_NEXT = "## 下一步（按顺序，最上面的先做）";
    public static final String S_HUMAN = "## 卡在人这里的";
    public static final String S_TV = "## 还没在真机上验过的";
    public static final String S_HANDS_OFF = "## 不要动";
    public static final String S_COST = "## 今天花了多少";

    public static final String P_RUN = " 单在跑";
    public static final String P_HUMAN = " 单卡在人这里";
    public static final String P_TV = " 条还没在真机上验过";
    public static final String CLEAR = "没有在跑的、没有卡住人的、装上的东西都在真机上见过";
    public static final String NO_UNVERIFIED = "一条都没有：装上的东西都在真机上见过";
    public static final String NEXT_NONE = "没有必须现在做的：读 `HARNESS.md` 的最后一节";
    public static final String MORE_HEAD = "（还有 ";
    public static final String MORE_TAIL = " 条没列出来：看 `status/BOARD.md`）";
    public static final String LAST = "；最后一句：";
    public static final String NO_NEEDS = "没有写";
    public static final String NO_SCRIPT_LINE = "没有";
    public static final String NO_SCRIPT_CMD = "没有脚本：照着 needs 手动走一遍";
    public static final String BAD_ENTRY = "读不出来（不是一条记录）";
    public static final String APK_NEWER = "比装的 APK 新";
    public static final String APK_NONE = "手上还没有 APK";
    public static final String INSTALL_CMD = "`cd ../xiaoai-bridge && sh build.sh && sh bridge.sh install`";
    public static final String RED = "重跑验收 ";
    public static final String TV_NEXT = "上电视验 ";
    public static final String INSTALL = "装这一版 APK";
    public static final String BUILT = "编好了还没装（";
    public static final String ON_TV = "，电视上的是 ";
    public static final String ON_TV_UNKNOWN = "，电视上的是哪版不知道";
    public static final String FORGOT = "过了验收 ";
    public static final String HOURS = " 小时还没合（我忘了）";
    public static final String WHY_NEEDS_LEAD = "等我看一眼再决定";
    public static final String WHY_FAILED = "跑挂了，不会自己再动";
    public static final String WHY_FAILED_ROUND = "一轮跑挂了，等我决定要不要再来一轮";
    public static final String WHY_OUTAGE = "渠道断了，等它回来";
    public static final String WHY_WRONG_MODEL = "用错了模型，得重跑";
    public static final String WHY_OTHER = "这个状态没人管就不会再动";
    /** 不要动：固定三行，写在代码里才活得过上下文清空。 */
    public static final List<String> HANDS_OFF = Collections.unmodifiableList(Arrays.asList(
        "- `tests/agent/AgentCoreTest.java` 里 `speechChecks()` 附近那段（打印它会把会话拦停）",
        "- `src/…/LocalAdb.java`、`assets/agent/contract.json`（lead 的）",
        "- 装应用、改系统设置：要用户点头"));

    private Handoff() { }

    /**
     * 一单。{@code costUsd} 是这一单花掉的美元（原样相加，不归到分：归到分是 {@link Cost} 的事，早归一次就会和
     * 它差一分钱），{@code null} 是没测到，不是 0。{@code redName} / {@code redCmd} 是最后一轮里没过的那条验收和
     * 它的命令——命令找不到就两个都空，这一页不假装能重跑一件没命令可跑的事。
     */
    public static final class Job {
        public final String id;
        public final String status;
        public final int rounds;
        public final int tools;
        public final long updatedMs;
        public final boolean merged;
        public final String last;
        public final Double costUsd;
        public final String redName;
        public final String redCmd;

        public Job(String id, String status, int rounds, int tools, long updatedMs, boolean merged, String last,
                   Double costUsd) {
            this(id, status, rounds, tools, updatedMs, merged, last, costUsd, "", "");
        }

        public Job(String id, String status, int rounds, int tools, long updatedMs, boolean merged, String last,
                   Double costUsd, String redName, String redCmd) {
            this.id = id == null ? "" : id;
            this.status = status == null ? "" : status;
            this.rounds = rounds;
            this.tools = tools;
            this.updatedMs = updatedMs;
            this.merged = merged;
            this.last = last == null ? "" : last;
            this.costUsd = costUsd;
            this.redName = redName == null ? "" : redName;
            this.redCmd = redCmd == null ? "" : redCmd;
        }
    }

    /** 一页「停在哪、下一步先做什么」。{@code apkMs} 当 0：不知道电视上装的那一版是什么时候编的。 */
    public static String page(List<Job> jobs, List<String> unverified, long nowMs, long sinceMs) {
        return page(jobs, unverified, nowMs, sinceMs, 0L);
    }

    /**
     * @param jobs       每单一条
     * @param unverified unverified.json 里每条记录的**原文**，一条一个字符串
     * @param nowMs      现在的时刻
     * @param sinceMs    「今天」这一窗口多长（毫秒，默认 24 小时）；过了验收多久算忘了跟它无关
     * @param apkMs      装在电视上的那一版 APK 的时刻；0 = 手上还没有 APK，不是「装过了」
     */
    public static String page(List<Job> jobs, List<String> unverified, long nowMs, long sinceMs, long apkMs) {
        return page(jobs, unverified, nowMs, sinceMs, apkMs, 0L);
    }

    /**
     * 同上，外加 {@code builtMs}：磁盘上最新编出来的那一版 APK 的时刻（0 = 不知道 / 没有）。没有单子合进来、
     * 它却比电视上那一版新，也是一次在等人点头的装机——lead 自己提交的修复不是一单，不看它这一页就说没事可做。
     */
    public static String page(List<Job> jobs, List<String> unverified, long nowMs, long sinceMs, long apkMs,
                              long builtMs) {
        List<Job> all = new ArrayList<Job>(jobs == null ? Collections.<Job>emptyList() : jobs);
        sort(all);
        List<Entry> tv = entries(unverified);
        long cutoff = nowMs - sinceMs;
        List<Job> running = new ArrayList<Job>();
        for (Job j : all) if (Board.RUNNING.equals(j.status)) running.add(j);
        List<Job> human = human(all, nowMs);
        List<Job> red = red(all);
        int mergedNewer = 0;
        for (Job j : all) if (j.merged && j.updatedMs >= cutoff && j.updatedMs > apkMs) mergedNewer++;

        StringBuilder b = new StringBuilder();
        b.append(TITLE).append(Board.stamp(nowMs)).append("）\n");
        b.append("\n").append(S_ONE).append("\n\n")
            .append(oneLine(running.size(), human.size(), tv.size())).append("\n");
        b.append("\n").append(S_NEXT).append("\n\n").append(nextLines(red, tv, mergedNewer, apkMs, builtMs));
        b.append("\n").append(S_HUMAN).append("\n\n").append(humanLines(human, nowMs));
        b.append("\n").append(S_TV).append("\n\n").append(tvLines(tv));
        b.append("\n").append(S_HANDS_OFF).append("\n\n");
        for (int i = 0; i < HANDS_OFF.size(); i++) {
            if (i > 0) b.append("\n");
            b.append(HANDS_OFF.get(i));
        }
        b.append("\n");
        b.append("\n").append(S_COST).append("\n\n").append(Cost.spend(costJobs(all, cutoff))).append("\n");
        return b.toString();
    }

    /** 一句话：一句就是一句——几段用 ； 接起来，末尾一个 。，中间一个 。 都没有。 */
    private static String oneLine(int running, int human, int tv) {
        List<String> parts = new ArrayList<String>();
        if (running > 0) parts.add(running + P_RUN);
        if (human > 0) parts.add(human + P_HUMAN);
        if (tv > 0) parts.add(tv + P_TV);
        if (parts.isEmpty()) return CLEAR + "。";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append("；");
            b.append(parts.get(i));
        }
        return b.append("。").toString();
    }

    /** 下一步：验收红 → 上电视验 → 装这一版，按这个顺序，最多三条，多的说一句还剩几条。
     * 装机提醒最多一条：有单子合进来时用那一条，没有才看编出来的是不是比装着的新。 */
    private static String nextLines(List<Job> red, List<Entry> tv, int mergedNewer, long apkMs, long builtMs) {
        List<String> items = new ArrayList<String>();
        for (Job j : red) items.add(redItem(j));
        Entry first = null;
        for (Entry e : tv) if (!e.bad && !e.id.isEmpty()) { first = e; break; }
        if (first != null) items.add(tvItem(first));
        if (mergedNewer > 0) items.add(installItem(mergedNewer, apkMs));
        else if (builtMs > 0 && builtMs > apkMs) items.add(builtItem(builtMs, apkMs));
        if (items.isEmpty()) return NEXT_NONE + "\n";
        StringBuilder b = new StringBuilder();
        int n = Math.min(items.size(), MAX_NEXT);
        for (int i = 0; i < n; i++) b.append(i + 1).append(". ").append(items.get(i)).append("\n");
        if (items.size() > MAX_NEXT) b.append(MORE_HEAD).append(items.size() - MAX_NEXT).append(MORE_TAIL).append("\n");
        return b.toString();
    }

    private static String redItem(Job j) {
        return RED + j.id + " · " + j.redName + " 没过，这一单已经跑了 " + j.rounds + " 轮、"
            + j.tools + " 次工具 · `" + j.redCmd + "`";
    }

    private static String tvItem(Entry e) {
        return TV_NEXT + e.id + " · " + e.feature + " 还没在真机上见过 · "
            + (e.script.isEmpty() ? NO_SCRIPT_CMD : "`" + e.script + "`");
    }

    private static String installItem(int n, long apkMs) {
        return INSTALL + " · " + n + " 单在这个窗口里合进来了，" + (apkMs > 0 ? APK_NEWER : APK_NONE)
            + " · " + INSTALL_CMD;
    }

    private static String builtItem(long builtMs, long apkMs) {
        return INSTALL + " · " + BUILT + clock(builtMs) + (apkMs > 0 ? ON_TV + clock(apkMs) : ON_TV_UNKNOWN)
            + "）· " + INSTALL_CMD;
    }

    /** MM-dd HH:mm，按北京时间：不跟着跑这一页的那台机的时区走，JS 那边同一个算法，两边才排得出同一页。 */
    static String clock(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));
        return f.format(new Date(ms));
    }

    /** 新的在上；同一毫秒的单按 id 排，这一页才不会每次刷新顺序都变（和 {@link Board} 一个排法）。 */
    private static void sort(List<Job> all) {
        Collections.sort(all, new Comparator<Job>() {
            public int compare(Job a, Job b) {
                if (a.updatedMs != b.updatedMs) return a.updatedMs > b.updatedMs ? -1 : 1;
                return a.id.compareTo(b.id);
            }
        });
    }

    /** 卡在人这里的：{@link Board#STUCK} 那五个状态，加上过了验收 24 小时还没合的单（我忘了的）。 */
    private static List<Job> human(List<Job> all, long now) {
        List<Job> out = new ArrayList<Job>();
        for (Job j : all) {
            if (Board.STUCK.contains(j.status)) { out.add(j); continue; }
            if (Board.ACCEPTED.equals(j.status) && !j.merged && now - j.updatedMs > FORGOT_MS) out.add(j);
        }
        return out;
    }

    /** 验收红在一条命令上的单。在跑的不算：它自己还会跑下一轮，这一页不催人去做 runner 正在做的事。 */
    private static List<Job> red(List<Job> all) {
        List<Job> out = new ArrayList<Job>();
        for (Job j : all) if (!j.redCmd.isEmpty() && !Board.RUNNING.equals(j.status)) out.add(j);
        return out;
    }

    /** 这一单为什么卡在人这里。accepted_mechanical 过了验收却没合：那就是我忘了。 */
    private static String whyOf(Job j, long now) {
        if (Board.ACCEPTED.equals(j.status)) return FORGOT + ((now - j.updatedMs) / HOUR) + HOURS;
        if ("needs_lead".equals(j.status)) return WHY_NEEDS_LEAD;
        if ("failed".equals(j.status)) return WHY_FAILED;
        if ("failed_round".equals(j.status)) return WHY_FAILED_ROUND;
        if ("outage".equals(j.status)) return WHY_OUTAGE;
        if ("wrong_model".equals(j.status)) return WHY_WRONG_MODEL;
        return WHY_OTHER;
    }

    private static String humanLines(List<Job> human, long now) {
        if (human.isEmpty()) return Board.NONE + "\n";
        StringBuilder b = new StringBuilder();
        for (Job j : human) {
            b.append("- ").append(j.id).append("：").append(whyOf(j, now)).append(LAST)
                .append(Board.cell(j.last, 100)).append("\n");
        }
        return b.toString();
    }

    private static String tvLines(List<Entry> tv) {
        if (tv.isEmpty()) return NO_UNVERIFIED + "\n";
        StringBuilder b = new StringBuilder();
        for (Entry e : tv) {
            if (e.bad) {
                b.append("- 第 ").append(e.at).append(" 条：").append(BAD_ENTRY).append("\n");
                continue;
            }
            b.append("- ").append(e.id).append("：").append(e.feature).append("（要 ")
                .append(e.needs.isEmpty() ? NO_NEEDS : e.needs).append("；脚本 ")
                .append(e.script.isEmpty() ? NO_SCRIPT_LINE : e.script).append("）\n");
        }
        return b.toString();
    }

    /** 一条 unverified.json 的记录（原文）。读不动的也占一行：读不出来比不说好。 */
    private static final class Entry {
        final int at;
        final boolean bad;
        final String id;
        final String feature;
        final String needs;
        final String script;

        Entry(int at, boolean bad, String id, String feature, String needs, String script) {
            this.at = at;
            this.bad = bad;
            this.id = id;
            this.feature = feature;
            this.needs = needs;
            this.script = script;
        }
    }

    private static List<Entry> entries(List<String> list) {
        List<Entry> out = new ArrayList<Entry>();
        if (list == null) return out;
        for (int i = 0; i < list.size(); i++) out.add(entry(list.get(i), i + 1));
        return out;
    }

    private static Entry entry(String json, int at) {
        try {
            JSONObject e = new JSONObject(json == null ? "" : json);
            return new Entry(at, false, e.optString("id", ""), e.optString("feature", ""),
                needsOf(e.optJSONArray("needs")), e.optString("script", ""));
        } catch (JSONException ex) {
            return new Entry(at, true, "", "", "", "");
        }
    }

    /** 前提一条条写出来，、 分开（和 {@code Unverified.needsOf} 一个排法）。不是数组就是没写。 */
    private static String needsOf(JSONArray needs) {
        if (needs == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < needs.length(); i++) {
            Object it = needs.opt(i);
            if (it == null) continue;
            if (b.length() > 0) b.append('、');
            b.append(it.toString());
        }
        return b.toString();
    }

    /** 窗口里的单子 → {@link Cost#spend} 认得的 {@code {id, history}}：这一页只有一单花掉的总数，
     * 所以那一单就是一轮。没测到成本的给空 history：它是没测到的那一单，不是不存在的一单。 */
    private static List<JSONObject> costJobs(List<Job> all, long cutoff) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (Job j : all) {
            if (j.updatedMs < cutoff) continue;
            try {
                JSONArray h = new JSONArray();
                if (j.costUsd != null) {
                    h.put(new JSONObject().put("cost", new JSONObject().put("usd", j.costUsd)));
                }
                out.add(new JSONObject().put("id", j.id).put("history", h));
            } catch (JSONException e) {
                // put 只在键是 null 时抛；id 不会是 null。真的抛了，这一单的钱就不算，别把整页排不出来
            }
        }
        return out;
    }
}
