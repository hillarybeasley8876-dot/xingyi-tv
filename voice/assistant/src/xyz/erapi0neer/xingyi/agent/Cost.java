package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 一轮跑了多少钱：runner 在 log.jsonl 末尾留的那个 {@code result} 事件里的数（total_cost_usd、modelUsage、
 * duration_ms、num_turns），读成一条账；一单的花销汇成一行；今天的账汇成一句话。纯函数，不碰磁盘。
 * 同一套规则的 JS 版在 tools/cost/cost.mjs，CostChecks 拿两边的字面量对着查，免得两边各算各的。
 *
 * <p>一件要紧的事：<b>同一个会话里的 result 事件是累计的</b>。verify-durable 的五个事件读 $4.82 / $6.15 /
 * $6.95 / $9.07 / $9.94，这一单花的是 $9.94，不是 $36.93。所以一轮的账是相邻两个事件之差
 * （{@link #deltas}），第一个事件是它自己的全部。会话换了（换模型就换会话）或者累计数往下掉了，
 * 那一条按整条事件算——永远不会是负的。
 *
 * <p>没测到的就是没测到的：{@code total_cost_usd} 不在事件里，{@link #usd} 是 {@code null}，不是 0；
 * 一单没有任何一轮测到成本，{@link #line} 说「N 单没有成本，合计是下限」。
 */
public final class Cost {
    /** 「最费的几单」最多列 5 单：再多就不是一眼能看完的账了。 */
    public static final int DEAREST = 5;

    /** 这一轮花掉的美元。{@code null} = runner 没报成本（total_cost_usd 不在事件里），不是 0。 */
    public final Double usd;
    /** 这一轮用了多久（毫秒）。事件里的 duration_ms 本来就是这一轮自己的，不是累计的。 */
    public final long ms;
    /** 这一轮模型来回了几次（num_turns），同样是这一轮自己的。 */
    public final long turns;
    /** 累计口径的令牌数：一次会话里每个 result 事件报的都是「到这个事件为止」的数。 */
    public final long inTok;
    public final long cacheTok;
    public final long outTok;
    public final long thinkTok;
    /** 每个模型自己那一份（modelUsage）：美元和输出令牌。 */
    public final Map<String, Model> models;

    /** 一个模型在一个事件里的账。 */
    public static final class Model {
        public final Double usd;
        public final long outTok;

        public Model(Double usd, long outTok) {
            this.usd = usd;
            this.outTok = outTok;
        }
    }

    public Cost(Double usd, long ms, long turns, long inTok, long cacheTok, long outTok, long thinkTok,
                Map<String, Model> models) {
        this.usd = usd;
        this.ms = ms;
        this.turns = turns;
        this.inTok = inTok;
        this.cacheTok = cacheTok;
        this.outTok = outTok;
        this.thinkTok = thinkTok;
        this.models = models == null
            ? Collections.<String, Model>unmodifiableMap(new LinkedHashMap<String, Model>())
            : Collections.unmodifiableMap(new LinkedHashMap<String, Model>(models));
    }

    /** 一个 {@code result} 事件 → 它自己报的账。不是 result 事件（或者根本没有事件）给 {@code null}。
     * 缺的、非数字的字段算 0；{@code usd} 例外——runner 没报成本就是没测到，给 {@code null}。 */
    public static Cost of(JSONObject ev) {
        if (ev == null || !"result".equals(ev.optString("type", ""))) return null;
        JSONObject usage = ev.optJSONObject("usage");
        if (usage == null) usage = new JSONObject();
        JSONObject det = usage.optJSONObject("output_tokens_details");
        if (det == null) det = new JSONObject();
        JSONObject mu = ev.optJSONObject("modelUsage");
        Map<String, Model> models = new LinkedHashMap<String, Model>();
        long inTok = 0L, cacheTok = 0L, outTok = 0L, thinkTok = 0L;
        if (mu != null && mu.length() > 0) {
            for (Iterator<String> it = mu.keys(); it.hasNext();) {
                String k = it.next();
                JSONObject m = mu.optJSONObject(k);
                if (m == null) m = new JSONObject();
                long o = m.optLong("outputTokens", 0L);
                models.put(k, new Model(dollars(m, "costUSD"), o));
                inTok += m.optLong("inputTokens", 0L);
                cacheTok += m.optLong("cacheReadInputTokens", 0L);
                outTok += o;
                thinkTok += m.optLong("thinkingTokens", 0L);
            }
        } else {
            // 老日志没有 modelUsage：退到 usage。那里的令牌数是这一轮自己的，不是累计的，
            // 所以这种事件的 delta 由「低了就算整条」兜住，不会算出负数。
            inTok = usage.optLong("input_tokens", 0L);
            cacheTok = usage.optLong("cache_read_input_tokens", 0L);
            outTok = usage.optLong("output_tokens", 0L);
            thinkTok = det.optLong("thinking_tokens", 0L);
        }
        return new Cost(dollars(ev, "total_cost_usd"), ev.optLong("duration_ms", 0L), ev.optLong("num_turns", 0L),
            inTok, cacheTok, outTok, thinkTok, models);
    }

    /** 一串 {@code result} 事件 → 一轮一条的账。第一条是它自己的全部，之后每条是它和前一条之差。
     * 不是 result 的事件跳过（它不算一轮）。 */
    public static List<Cost> deltas(JSONArray events) {
        List<Cost> out = new ArrayList<Cost>();
        if (events == null) return out;
        Cost prev = null;
        String prevSid = "";
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            Cost cur = of(ev);
            if (cur == null) continue;
            String sid = ev.optString("session_id", "");
            boolean restart = prev != null
                && ((sid.length() > 0 && prevSid.length() > 0 && !sid.equals(prevSid)) || cur.dropsFrom(prev));
            out.add(prev == null || restart ? cur : cur.minus(prev));
            prev = cur;
            prevSid = sid;
        }
        return out;
    }

    /** 一串 result 事件里<b>最后一个</b>事件的账，也就是刚跑完这一轮花掉的。 */
    public static Cost delta(JSONArray events) {
        List<Cost> d = deltas(events);
        return d.isEmpty() ? null : d.get(d.size() - 1);
    }

    /** 会话换了或者累计数往下掉了：这一条不能取差。 */
    private boolean dropsFrom(Cost p) {
        if (usd != null && p.usd != null && usd.doubleValue() < p.usd.doubleValue()) return true;
        if (inTok < p.inTok || cacheTok < p.cacheTok || outTok < p.outTok || thinkTok < p.thinkTok) return true;
        for (Map.Entry<String, Model> e : models.entrySet()) {
            Model a = e.getValue();
            Model b = p.models.get(e.getKey());
            if (b == null) continue;
            if (a.usd != null && b.usd != null && a.usd.doubleValue() < b.usd.doubleValue()) return true;
            if (a.outTok < b.outTok) return true;
        }
        return false;
    }

    /** 这一条减去上一条：美元和令牌是累计的所以取差，ms 和 turns 本来就是这一轮自己的，照抄。 */
    private Cost minus(Cost p) {
        Map<String, Model> m = new LinkedHashMap<String, Model>();
        for (Map.Entry<String, Model> e : models.entrySet()) {
            Model c = e.getValue();
            Model b = p.models.get(e.getKey());
            m.put(e.getKey(), b == null ? c : new Model(
                c.usd == null || b.usd == null ? null : Double.valueOf(c.usd.doubleValue() - b.usd.doubleValue()),
                c.outTok - b.outTok));
        }
        return new Cost(usd == null || p.usd == null ? null : Double.valueOf(usd.doubleValue() - p.usd.doubleValue()),
            ms, turns, inTok - p.inTok, cacheTok - p.cacheTok, outTok - p.outTok, thinkTok - p.thinkTok, m);
    }

    /** 美元字段：在、且是个真数字，才算测到了。 */
    private static Double dollars(JSONObject o, String key) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        return (Double.isNaN(d) || Double.isInfinite(d)) ? null : Double.valueOf(d);
    }

    /** 一单一行：id、这一单花的美元、跑了几轮。 */
    private static final class Row {
        final String id;
        final double usd;
        final int rounds;

        Row(String id, double usd, int rounds) {
            this.id = id;
            this.usd = usd;
            this.rounds = rounds;
        }
    }

    /** 一单（{@code {id, history}}，history 的每一项是 run.mjs 写的那一条外加 {@code cost}）→ 它的 history。
     * 给成一串「轮」对象（每一项自己有 cost / tools）也认，按一单一轮算。 */
    private static JSONArray historyOf(JSONObject job) {
        if (job == null) return null;
        JSONArray h = job.optJSONArray("history");
        if (h != null) return h;
        if (job.has("cost") || job.has("tools") || job.has("round")) return new JSONArray().put(job);
        return null;
    }

    /** 这一单花的美元；一单都没测到成本给 {@code null}——不是 0。 */
    private static Double usdOf(JSONArray history) {
        if (history == null) return null;
        Double sum = null;
        for (int i = 0; i < history.length(); i++) {
            JSONObject r = history.optJSONObject(i);
            JSONObject c = r == null ? null : r.optJSONObject("cost");
            if (c == null) continue;
            Double v = dollars(c, "usd");
            if (v == null) continue;
            sum = Double.valueOf((sum == null ? 0d : sum.doubleValue()) + v.doubleValue());
        }
        return sum;
    }

    /** 「今天：67 单、74 轮、$260.78」；有单子没测到成本就加一句「合计是下限」——没测到的不当成 0。 */
    public static String line(List<JSONObject> histories) {
        List<JSONObject> jobs = histories == null ? Collections.<JSONObject>emptyList() : histories;
        int rounds = 0, unknown = 0;
        double usd = 0d;
        for (JSONObject j : jobs) {
            JSONArray h = historyOf(j);
            rounds += h == null ? 0 : h.length();
            Double own = usdOf(h);
            if (own == null) unknown++;
            else usd += own.doubleValue();
        }
        String s = "今天：" + jobs.size() + " 单、" + rounds + " 轮、" + money(usd);
        if (unknown > 0) s += "（" + unknown + " 单没有成本，合计是下限）";
        return s;
    }

    /**
     * 状态板「消耗」里的那一行钱：{@code - 花在模型上：$249.10（72 单；3 单没有成本，合计是下限）}。
     * 和 {@link #line} 不同，这一行整个由「测到没测到」说了算：一单都没跑过说「没有单跑过。」，
     * 一单都没测到说「算不出来（N 单都没有记成本）」——{@code null} 的合计永远不写成 $0.00，
     * 那句话说的是「测到了，是零」，是另一回事。tools/cost/cost.mjs 的 spend() 是同一句话，
     * CostChecks 拿两边的字面量对着查。
     */
    public static String spend(List<JSONObject> jobs) {
        List<JSONObject> list = jobs == null ? Collections.<JSONObject>emptyList() : jobs;
        int unknown = 0;
        double usd = 0d;
        for (JSONObject j : list) {
            Double own = usdOf(historyOf(j));
            if (own == null) unknown++;
            else usd += own.doubleValue();
        }
        if (list.isEmpty()) return "- 花在模型上：没有单跑过。";
        if (list.size() - unknown == 0) {
            return "- 花在模型上：算不出来（" + list.size() + " 单都没有记成本）";
        }
        // 和 cost.mjs 的 usd2 一个算法：先归到分，再排版，两边才不会差一分钱
        String s = "- 花在模型上：" + money(round2(usd)) + "（" + list.size() + " 单";
        if (unknown > 0) s += "；" + unknown + " 单没有成本，合计是下限";
        return s + "）";
    }

    /** 最费的 n 单，一单一行 {@code - <id> $9.94 (5 轮)}。n 超过 {@link #DEAREST} 按 {@link #DEAREST} 算；
     * 没测到成本的单排不进来（它在 {@link #line} 的「N 单没有成本」里点名，不藏）。同样贵的两单按 id 排。
     *
     * <p>「同样贵」看的是<b>板子上写的那个数</b>（归到分），不是没归到分的原数：cost.mjs 的 dearestLines
     * 排的是 {@code usd2(own)}，差不到一分的两单在它那边是平手、按 id 排。这边要是按原数排，同一页板子
     * 两边就会排出两个顺序——BoardChecks 要防的正是这个。 */
    public static String dearest(List<JSONObject> jobs, int n) {
        List<Row> rows = new ArrayList<Row>();
        if (jobs != null) {
            for (JSONObject j : jobs) {
                if (j == null) continue;
                JSONArray h = historyOf(j);
                Double own = usdOf(h);
                if (own == null) continue;
                String id = j.optString("id", "");
                rows.add(new Row(id.isEmpty() ? "?" : id, round2(own.doubleValue()), h == null ? 0 : h.length()));
            }
        }
        Collections.sort(rows, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                if (a.usd != b.usd) return a.usd > b.usd ? -1 : 1;
                return a.id.compareTo(b.id);
            }
        });
        int take = Math.min(n, DEAREST);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < take && i < rows.size(); i++) {
            Row r = rows.get(i);
            b.append("- ").append(r.id).append(" ").append(money(r.usd)).append(" (")
                .append(r.rounds).append(" 轮)\n");
        }
        return b.toString();
    }

    /** 归到分，cost.mjs 的 usd2 那个算法。板子上写的就是这个数，排序也比这个数，两边才排得一样。 */
    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }

    /** 记到分：板子上写的是 $9.94，不是 $9.935437。 */
    public static String money(double v) {
        return "$" + String.format(Locale.US, "%.2f", Double.valueOf(v));
    }
}
