package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 一页状态板：在跑什么、卡在哪、消耗多少、怎么停。纯函数——给它单子的状态，它给回一页 markdown；
 * 数据从哪来（state.json、这个提交合了没有）是调用的人的事，这里只排版。同一套规则的 JS 版在
 * tools/board/board.mjs，BoardChecks 拿两边的字面量对着查，免得两边各排各的。
 *
 * <p>一单一条 {@link Job}。五段固定这个顺序：在跑 / 等合并 / 今天完成并已合并 / 卡住 / 消耗。 */
public final class Board {
    /** 在跑但 90 分钟没写过 state.json 的，算卡住：一轮预算是 100 分钟，中间也会写状态。 */
    public static final long STALE_MS = 90L * 60L * 1000L;
    /** 同时在跑最多 2 单（run.mjs 的 --parallel）。 */
    public static final int MAX_RUNNING = 2;
    /** --since 的默认小时数：只看今天。 */
    public static final int DEFAULT_HOURS = 24;
    /** state.json 里没写 maxRounds 时的兜底（run.mjs 的默认值）。 */
    public static final int DEFAULT_MAX_ROUNDS = 4;

    public static final String RUNNING = "running";
    public static final String ACCEPTED = "accepted_mechanical";
    /** 除了 needs_lead / failed / outage，failed_round 和 wrong_model 也是没人管就不会再动的单，
     * 板上不留它们就等于把它们弄丢了。 */
    public static final List<String> STUCK = Collections.unmodifiableList(
        java.util.Arrays.asList("needs_lead", "failed", "failed_round", "outage", "wrong_model"));

    public static final String HEAD_RUN = "| 单 | 第几轮 | 这个会话已用工具次数 | 开始 | 最后一句（截 80 字） |";
    public static final String HEAD_WAIT = "| 单 | 完成于 | 提交 | 验收 |";
    public static final String HEAD_DONE = "| 单 | 提交 | 轮数 | 工具次数 |";
    public static final String S_RUN = "## 在跑";
    public static final String S_WAIT = "## 等合并（过了验收，我还没合）";
    public static final String S_DONE = "## 今天完成并已合并";
    public static final String S_STUCK = "## 卡住 / 需要我出手";
    public static final String S_COST = "## 消耗";
    public static final String NONE = "（没有）";
    public static final String EMPTY_BOARD = "没有在跑的、没有等合并的。";

    private Board() { }

    /** 一单。{@code note} 是「验收」和「为什么」两栏要说的话（验收没过的那条、渠道断的原因），
     * {@code startedMs} 是这一轮的开始时刻，{@code updatedMs} 是 state.json 最后一次变的时刻。
     * {@code history} 是 state.json 里那一串轮（每一项外加 run.mjs 写的 {@code cost}），「花在模型上」
     * 那一行钱就是从它加出来的；没有它（调用的人只有汇总好的几个数）时给 {@code null}。 */
    public static final class Job {
        public final String id;
        public final String status;
        public final int rounds;
        public final int maxRounds;
        public final int tools;
        public final long startedMs;
        public final long updatedMs;
        public final String commit;
        public final boolean merged;
        public final String last;
        public final String note;
        public final JSONArray history;

        public Job(String id, String status, int rounds, int maxRounds, int tools, long startedMs, long updatedMs,
                   String commit, boolean merged, String last, String note) {
            this(id, status, rounds, maxRounds, tools, startedMs, updatedMs, commit, merged, last, note, null);
        }

        public Job(String id, String status, int rounds, int maxRounds, int tools, long startedMs, long updatedMs,
                   String commit, boolean merged, String last, String note, JSONArray history) {
            this.id = id == null ? "" : id;
            this.status = status == null ? "" : status;
            this.rounds = rounds;
            this.maxRounds = maxRounds;
            this.tools = tools;
            this.startedMs = startedMs;
            this.updatedMs = updatedMs;
            this.commit = commit == null ? "" : commit;
            this.merged = merged;
            this.last = last == null ? "" : last;
            this.note = note == null ? "" : note;
            this.history = history;
        }
    }

    /** 一页板子。{@code hours} 是 --since：只管「等合并」和「今天完成并已合并」两张表。 */
    public static String markdown(List<Job> jobs, long now, int hours) {
        List<Job> all = new ArrayList<Job>(jobs == null ? Collections.<Job>emptyList() : jobs);
        if (all.isEmpty()) return "# 状态板 " + stamp(now) + "\n\n" + EMPTY_BOARD + "\n";
        long cutoff = now - (long) hours * 3600L * 1000L;
        sort(all);
        StringBuilder b = new StringBuilder();
        b.append("# 状态板 ").append(stamp(now)).append("\n");

        b.append("\n").append(S_RUN).append("\n\n");
        List<Job> running = keep(all, new Filter() { public boolean ok(Job j) { return RUNNING.equals(j.status); } });
        table(b, HEAD_RUN, runRows(running));

        b.append("\n").append(S_WAIT).append("\n\n");
        List<Job> waiting = keep(all, accepted(false, cutoff));
        table(b, HEAD_WAIT, waitRows(waiting));

        b.append("\n").append(S_DONE).append("\n\n");
        List<Job> done = keep(all, accepted(true, cutoff));
        table(b, HEAD_DONE, doneRows(done));

        b.append("\n").append(S_STUCK).append("\n\n");
        stuck(b, all, now);

        b.append("\n").append(S_COST).append("\n\n");
        cost(b, all, cutoff, running);

        return b.toString();
    }

    private interface Filter { boolean ok(Job j); }

    private static List<Job> keep(List<Job> all, Filter f) {
        List<Job> out = new ArrayList<Job>();
        for (Job j : all) if (f.ok(j)) out.add(j);
        return out;
    }

    private static Filter accepted(final boolean merged, final long cutoff) {
        return new Filter() {
            public boolean ok(Job j) { return ACCEPTED.equals(j.status) && j.merged == merged && j.updatedMs >= cutoff; }
        };
    }

    /** 新的在上；同一毫秒的单按 id 排，板上才不会每次刷新顺序都变。 */
    private static void sort(List<Job> all) {
        Collections.sort(all, new Comparator<Job>() {
            public int compare(Job a, Job b) {
                if (a.updatedMs != b.updatedMs) return a.updatedMs > b.updatedMs ? -1 : 1;
                return a.id.compareTo(b.id);
            }
        });
    }

    private static List<String[]> runRows(List<Job> running) {
        List<String[]> rows = new ArrayList<String[]>();
        for (Job j : running) {
            rows.add(new String[] { j.id, j.rounds + "/" + (j.maxRounds > 0 ? j.maxRounds : DEFAULT_MAX_ROUNDS),
                String.valueOf(j.tools), clock(j.startedMs), cell(j.last, 80) });
        }
        return rows;
    }

    private static List<String[]> waitRows(List<Job> waiting) {
        List<String[]> rows = new ArrayList<String[]>();
        for (Job j : waiting) rows.add(new String[] { j.id, clock(j.updatedMs), commit(j), cell(j.note, 120) });
        return rows;
    }

    private static List<String[]> doneRows(List<Job> done) {
        List<String[]> rows = new ArrayList<String[]>();
        for (Job j : done) rows.add(new String[] { j.id, commit(j), String.valueOf(j.rounds), String.valueOf(j.tools) });
        return rows;
    }

    private static String commit(Job j) { return j.commit.isEmpty() ? "—" : j.commit; }

    /** 卡住：没人管就不会再动的单，加上在跑但九十分钟没动静的。一条一行。 */
    private static void stuck(StringBuilder b, List<Job> all, long now) {
        int n = 0;
        for (Job j : all) {
            String why = why(j, now);
            if (why == null) continue;
            b.append("- ").append(j.id).append("  ").append(j.status).append("  ").append(why).append("\n");
            n++;
        }
        if (n == 0) b.append(NONE).append("\n");
    }

    /** 这一单为什么卡住；不卡的单给 null。 */
    private static String why(Job j, long now) {
        if (RUNNING.equals(j.status)) {
            if (now - j.updatedMs <= STALE_MS) return null;
            return "超过 90 分钟没更新（最后 " + clock(j.updatedMs) + "）";
        }
        if (!STUCK.contains(j.status)) return null;
        return cell(j.note, 120).equals("—") ? "state.json 里没写为什么" : cell(j.note, 120);
    }

    /** 今天的账：单数、轮数、工具次数，最费的那一单；再往下是这一窗口花掉的钱和最费的几单。 */
    private static void cost(StringBuilder b, List<Job> all, long cutoff, List<Job> running) {
        int n = 0, rounds = 0, tools = 0;
        Job most = null;
        for (Job j : all) {
            if (j.updatedMs < cutoff) continue;
            n++;
            rounds += j.rounds;
            tools += j.tools;
            if (most == null || j.tools > most.tools) most = j;
        }
        if (n == 0) {
            b.append("- 今天：没有单跑过。\n");
        } else {
            b.append("- 今天：").append(n).append(" 个单、").append(rounds).append(" 轮、").append(tools)
                .append(" 次工具调用（按每轮 `history[].tools` 汇总）；最费的一次：`").append(most.id)
                .append("`（").append(most.tools).append(" 次）\n");
        }
        // 钱是同一批单子的钱（同一个 --since 窗口），不是全天的账
        List<JSONObject> window = costJobs(all, cutoff);
        b.append(Cost.spend(window)).append("\n");
        String dearest = Cost.dearest(window, Cost.DEAREST);
        if (!dearest.isEmpty()) {
            b.append("- 最费的几单：\n");
            for (String l : dearest.split("\n")) b.append("  ").append(l).append("\n");
        }
        b.append("- 上限：同时在跑最多 ").append(MAX_RUNNING).append(" 单");
        if (running.size() > MAX_RUNNING) {
            b.append("，现在 ").append(running.size()).append(" 单在跑，超了：");
            for (int i = 0; i < running.size(); i++) {
                if (i > 0) b.append("、");
                b.append(running.get(i).id);
            }
        }
        b.append("\n");
    }

    /** 窗口里的单子 → {@link Cost} 认得的 {@code {id, history}}。没有 history 的单子照样算一单：
     * 它是没测到成本的那一单，不是不存在的一单。 */
    private static List<JSONObject> costJobs(List<Job> all, long cutoff) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (Job j : all) {
            if (j.updatedMs < cutoff) continue;
            try {
                out.add(new JSONObject().put("id", j.id)
                    .put("history", j.history == null ? new JSONArray() : j.history));
            } catch (JSONException e) {
                // put 只在键是 null 时抛；id 不会是 null。真的抛了，这一单的钱就不算，别把整页排不出来
            }
        }
        return out;
    }

    private static void table(StringBuilder b, String head, List<String[]> rows) {
        if (rows.isEmpty()) { b.append(NONE).append("\n"); return; }
        b.append(head).append("\n").append(separator(head)).append("\n");
        for (String[] r : rows) {
            b.append("|");
            for (String c : r) b.append(" ").append(c).append(" |");
            b.append("\n");
        }
    }

    private static String separator(String head) {
        int bars = 0;
        for (int i = 0; i < head.length(); i++) if (head.charAt(i) == '|') bars++;
        StringBuilder s = new StringBuilder("|");
        for (int i = 0; i < bars - 1; i++) s.append(" --- |");
        return s.toString();
    }

    /** 进表格的一句话：换行压成一个空格，竖线转义（不然表格就断了），超过 max 个字就截掉。 */
    public static String cell(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim().replace("|", "\\|");
        if (t.isEmpty()) return "—";
        if (t.length() > max) t = t.substring(0, max - 1) + "…";
        return t;
    }

    /** HH:mm（本机时间）。 */
    public static String clock(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
    }

    /** yyyy-MM-dd HH:mm（本机时间）。 */
    public static String stamp(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) + "-" + two(c.get(Calendar.MONTH) + 1) + "-" + two(c.get(Calendar.DAY_OF_MONTH))
            + " " + two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
    }

    private static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }
}
