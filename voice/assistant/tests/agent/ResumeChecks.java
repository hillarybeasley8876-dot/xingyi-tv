package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * resume (2026-11-12)：{@code tools/resume/rehearse.mjs} 那份报告的规矩，和 {@code tests/agent/resume/check.py}
 * 一条一条对上，每一条各在传进去的文本上弄坏一次。
 *
 * <p>为什么这些规矩要有人守：run.mjs 自己说它会接着跑被杀掉的单（{@code if (st.rounds > st.history.length)
 * st.rounds = st.history.length;}、{@code st.sessionStarted ? '--resume' : '--session-id'}），可从来没有任何一条
 * 检查证明过。2026-11-11 晚上 board-money 六轮里四轮被一个验收路径的笔误杀掉，单子停在 needs_lead 都没人发现；
 * 而这几条规矩细到一行改错就会悄悄失效——一轮只有验收过了才记账，所以被杀掉的一轮留下一个脏工作区和一份
 * {@code rounds} 比 {@code history.length} 大的 state.json。
 *
 * <p>这里检查的是「一份说自己演练过的报告」：被杀的那一轮有没有被当成跑过、第二次起来带的是 {@code --resume}
 * 还是 {@code --session-id}、第一句话是续跑句还是 brief 的第一句、换了模型有没有换会话、轮数有没有跑出封顶，
 * 以及最要紧的那一条——演练有没有写进真的 jobs 目录（那是别的会话正在跑的单）。
 *
 * <p>报告的文本是传进来的：{@link #problems(String, Path)} 只看文本和 root，不起 run.mjs、不碰磁盘上别的东西。
 * 真跑一遍 runner 是 {@code tests/agent/accept_resume.sh} 的事，那边的报告落在 {@code tests/agent/resume/reports/}。
 */
final class ResumeChecks {
    private ResumeChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    /** run.mjs 的三句话，报告和检查都按字面认它们（preamble.md 里没有这三句，查过了）。 */
    private static final String CONTINUATION = "The lead resumes this job.";
    private static final String BRIEF_MARK = "# YOUR BRIEF";
    private static final String FRESH_MARK = "# CONTINUATION: a fresh session";
    private static final List<String> DONE = Arrays.asList("accepted_mechanical", "needs_lead");
    private static final String REHEARSE = "tools/resume/rehearse.mjs";
    private static final String REPORTS = "tests/agent/resume/reports/";
    private static final String MODEL = "MiniMax-M78", OLD_MODEL = "MiniMax-M3.1-Flash";
    private static final String S1 = "11111111-1111-4111-8111-111111111111", S2 = "22222222-2222-4222-8222-222222222222";
    private static final String SCRATCH = "H:/tools/mitv/voice/assistant-out/tmp/resume/rehearse-1";
    private static final String REAL_OUT = "H:/tools/mitv/voice/assistant-out";
    private static final String FIRST = "# WORKER RULES\n\n# YOUR BRIEF - rehearsal\n\nFIRST-PROMPT-MARKER\n";
    private static final String CONT = "The lead resumes this job. Re-read your status file and the brief, "
            + "check what is on disk, and continue until the acceptance commands pass.";
    private static final String FRESH = FIRST + "\n\n# CONTINUATION: a fresh session on an existing task "
            + "(another worker model started it); everything done so far is on disk in your working directory.\n";

    // ---- 报告的文本：小块拼起来，要弄坏哪一条就改哪一个参数 ----------------------------------------------

    /** 一个字符串当 JSON 字面量（只转义要转义的那几个）。 */
    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static String state(int rounds, int history, boolean started, String model, String sid, String status) {
        return "{\"rounds\":" + rounds + ",\"history\":" + history + ",\"sessionStarted\":" + started
                + ",\"sessionModel\":" + (model == null ? "null" : q(model)) + ",\"sessionId\":" + q(sid)
                + ",\"status\":" + q(status) + "}";
    }

    private static String stubRun(int round, boolean finished, String flag, String sid, String prompt) {
        return "{\"round\":" + round + ",\"finished\":" + finished + ",\"resume\":" + "--resume".equals(flag)
                + ",\"sessionId\":" + q(sid) + ",\"argv\":[\"-p\",\"--model\"," + q(MODEL) + "," + q(flag) + ","
                + q(sid) + "],\"prompt\":" + q(prompt) + ",\"promptFile\":\"prompt-" + round + ".md\"}";
    }

    private static String accept(int... rounds) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < rounds.length; i++) {
            if (i > 0) b.append(',');
            b.append("{\"round\":").append(rounds[i]).append(",\"end\":\"ok\",\"pass\":true,\"why\":[],\"stray\":[]}");
        }
        return b.append(']').toString();
    }

    private static String launch(int n, boolean killed, int beginRound, String before, String after, String accept,
            String... runs) {
        StringBuilder b = new StringBuilder("{\"n\":").append(n).append(",\"killed\":").append(killed)
                .append(",\"beginRound\":").append(beginRound).append(",\"rounds\":[").append(beginRound)
                .append("],\"stateBefore\":").append(before).append(",\"stateAfter\":").append(after)
                .append(",\"stub\":[");
        for (int i = 0; i < runs.length; i++) {
            if (i > 0) b.append(',');
            b.append(runs[i]);
        }
        // the acceptance entries this launch added: one per round that got as far as the runner's own acceptance
        return b.append("],\"accept\":").append(accept).append('}').toString();
    }

    /** 报告的头：演练写在哪儿。scratch 换成真的 jobs 目录，就是唯一会伤到别人的那个错。 */
    private static String head(String scratch) {
        return "{\"tool\":\"tools/resume/rehearse.mjs\",\"at\":\"2026-11-12T05:32:47.000Z\",\"scratch\":" + q(scratch)
                + ",\"out\":" + q(scratch + "/out") + ",\"wt\":" + q(scratch + "/wt") + ",\"env\":" + q(scratch + "/env")
                + ",\"claude\":" + q(scratch + "/stub/claude-stub.exe") + ",\"realOut\":" + q(REAL_OUT);
    }

    private static String report(String scratch, String... jobs) {
        StringBuilder b = new StringBuilder(head(scratch)).append(",\"jobs\":[");
        for (int i = 0; i < jobs.length; i++) {
            if (i > 0) b.append(',');
            b.append('{').append(jobs[i]).append('}');
        }
        return b.append("]}").toString();
    }

    /**
     * 一场「杀掉再起来」：第一轮被杀在半路，第二次拿同一份 state 再起一次。
     * prompt1 / begin2 / flag2 / prompt2 / finalRounds / finalHistory 就是那几处能被弄坏的地方。
     */
    private static String killJob(String prompt1, int begin2, String flag2, String prompt2, int finalRounds,
            int finalHistory, int maxRounds) {
        String before = state(0, 0, false, null, S1, "new"), afterKill = state(1, 0, true, MODEL, S1, "running");
        String end = state(finalRounds, finalHistory, true, MODEL, S1, "accepted_mechanical");
        return "\"id\":\"rehearse-1-kill\",\"scenario\":\"kill\",\"model\":" + q(MODEL) + ",\"maxRounds\":" + maxRounds
                + ",\"state0\":" + before + ",\"launches\":["
                + launch(1, true, 1, before, afterKill, accept(), stubRun(1, false, "--session-id", S1, prompt1)) + ","
                + launch(2, false, begin2, afterKill, end, accept(begin2), stubRun(begin2, true, flag2, S1, prompt2))
                + "],\"final\":" + end + ",\"stub\":{\"runs\":2,\"finished\":1}";
    }

    /** 一份本身没毛病的：杀掉的那一轮原样再跑一次，第二次带 --resume、说的是续跑句。 */
    private static String goodKill() {
        return report(SCRATCH, killJob(FIRST, 1, "--resume", CONT, 1, 1, 3));
    }

    /** 一场「换了模型」：state 里记的会话是别的模型开的，该换一个 --session-id <新> 从头说。 */
    private static String modelJob(String flag, String sid, String prompt) {
        String before = state(1, 1, true, OLD_MODEL, S1, "failed_round");
        String end = state(2, 2, true, MODEL, sid, "accepted_mechanical");
        return "\"id\":\"rehearse-1-model\",\"scenario\":\"model\",\"model\":" + q(MODEL) + ",\"maxRounds\":3"
                + ",\"state0\":" + before + ",\"launches\":["
                + launch(1, false, 2, before, end, accept(2), stubRun(2, true, flag, sid, prompt))
                + "],\"final\":" + end + ",\"stub\":{\"runs\":1,\"finished\":1}";
    }

    /** 一场「轮数到顶」：state 说 rounds=2，maxRounds=2。 */
    private static String capJob(int begin, int maxRounds, int afterRounds, String status, int finalHistory) {
        String before = state(2, 2, true, MODEL, S1, "failed_round");
        String end = state(afterRounds, finalHistory, true, MODEL, S1, status);
        return "\"id\":\"rehearse-1-cap\",\"scenario\":\"cap\",\"model\":" + q(MODEL) + ",\"maxRounds\":" + maxRounds
                + ",\"state0\":" + before + ",\"launches\":["
                + launch(1, false, begin, before, end, accept(begin), stubRun(begin, true, "--resume", S1, CONT))
                + "],\"final\":" + end + ",\"stub\":{\"runs\":1,\"finished\":1}";
    }

    private static String noLaunchJob() {
        String s = state(0, 0, false, null, S1, "new");
        return "\"id\":\"rehearse-1-kill\",\"scenario\":\"kill\",\"model\":" + q(MODEL) + ",\"maxRounds\":3"
                + ",\"state0\":" + s + ",\"launches\":[],\"final\":" + s + ",\"stub\":{\"runs\":0,\"finished\":0}";
    }

    // ---- 规矩本身：哪里不对，一句一句说清楚；没问题就一句都不说 --------------------------------------------

    /** 一个字段当非负整数看；不是整数就是 -1（形状不对，后面的规矩就不看了）。 */
    private static int num(JSONObject o, String k) {
        Object v = o == null ? null : o.opt(k);
        if (v instanceof Integer) return (Integer) v >= 0 ? (Integer) v : -1;
        if (v instanceof Long) {
            long l = (Long) v;
            return l >= 0 && l <= Integer.MAX_VALUE ? (int) l : -1;
        }
        return -1;
    }

    private static String text(JSONObject o, String k) {
        Object v = o == null ? null : o.opt(k);
        return v instanceof String ? (String) v : null;
    }

    private static boolean isTrue(JSONObject o, String k) {
        Object v = o == null ? null : o.opt(k);
        return v instanceof Boolean && (Boolean) v;
    }

    private static String norm(String p) {
        if (p == null) return "";
        String s = p.replace('\\', '/');
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static List<String> none() { return new ArrayList<>(); }

    private static List<String> one(String sentence) {
        List<String> out = new ArrayList<>();
        out.add(sentence);
        return out;
    }

    /** 演练脚本自己：它在不在，有没有把 SKYRUSH_OUT / WT / ENV 指到 scratch 里。 */
    static List<String> toolProblems(Path root) {
        String src = read(root.resolve(REHEARSE));
        if (src == null) return one(REHEARSE + " 不在");
        List<String> bad = new ArrayList<>();
        for (String k : Arrays.asList("SKYRUSH_OUT", "SKYRUSH_WT", "SKYRUSH_ENV")) {
            if (!src.contains(k)) bad.add("没提 " + k);
        }
        return bad.isEmpty() ? none() : one(REHEARSE + "：" + String.join("；", bad));
    }

    /** 演练只能写在 scratch 里：写进真的 jobs 目录，是这个工具唯一会真正伤到别人的错。 */
    private static List<String> safety(JSONObject r) {
        List<String> bad = new ArrayList<>();
        String scratch = norm(text(r, "scratch")), real = norm(text(r, "realOut"));
        for (String k : Arrays.asList("out", "wt", "env", "claude")) {
            String p = norm(text(r, k));
            if (p.isEmpty()) {
                bad.add(k + " 没写");
                continue;
            }
            if (!scratch.isEmpty() && !p.equals(scratch) && !p.startsWith(scratch + "/")) {
                bad.add(k + "（" + p + "）不在 scratch（" + scratch + "）里面");
            }
            if (!real.isEmpty() && (p.equals(real) || p.startsWith(real + "/jobs"))) {
                bad.add(k + "（" + p + "）落在真的 jobs 目录（" + real + "/jobs）里");
            }
            if (p.contains("assistant-out/jobs")) bad.add(k + "（" + p + "）的路径里有 assistant-out/jobs");
        }
        if (!scratch.isEmpty() && !real.isEmpty() && (scratch.equals(real) || scratch.startsWith(real + "/jobs"))) {
            bad.add("scratch（" + scratch + "）自己就在真的 jobs 目录里");
        }
        if (!scratch.isEmpty() && scratch.contains("assistant-out/jobs")) {
            bad.add("scratch（" + scratch + "）的路径里有 assistant-out/jobs");
        }
        return bad.isEmpty() ? none() : one("演练写到了不该写的地方：" + String.join("；", bad));
    }

    /** 被杀掉的那一轮不算跑过：跟着一次被杀的起来之后的那一次，要原样从那一轮再跑。 */
    private static List<String> killedRound(String who, JSONArray launches, JSONObject fin) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < launches.length(); i++) {
            JSONObject prev = launches.optJSONObject(i - 1), cur = launches.optJSONObject(i);
            if (prev == null || cur == null || !isTrue(prev, "killed")) continue;
            JSONObject pa = prev.optJSONObject("stateAfter"), sb = cur.optJSONObject("stateBefore");
            if (num(pa, "rounds") <= num(pa, "history") || num(sb, "rounds") <= num(sb, "history")) {
                out.add("单 " + who + "：第 " + (i + 1) + " 次起来之前，state 是 rounds=" + num(sb, "rounds")
                        + " history=" + num(sb, "history") + " —— 没有一轮跑完却没记账，"
                        + "这份报告证明不了「没跑完的那一轮会再跑一次」");
                continue;
            }
            List<String> bad = new ArrayList<>();
            if (num(cur, "beginRound") != num(prev, "beginRound")) {
                bad.add("上一次从第 " + num(prev, "beginRound") + " 轮被杀，这一次从第 " + num(cur, "beginRound") + " 轮开始");
            }
            if (num(fin, "rounds") != num(fin, "history")) {
                bad.add("最后 rounds=" + num(fin, "rounds") + " 而 history=" + num(fin, "history"));
            }
            if (!bad.isEmpty()) {
                out.add("单 " + who + "：被杀的那一轮没有原样再跑一次，没跑完的那一轮被当成跑过了 —— "
                        + String.join("；", bad));
            }
        }
        return out;
    }

    /** 起来一次：接着跑、换了模型、还是第一次；外加轮数到顶。 */
    private static List<String> launchRules(String who, int i, JSONObject l, String model, int maxRounds, JSONObject fin) {
        int n = i + 1;
        JSONObject sb = l.optJSONObject("stateBefore");
        JSONArray runs = l.optJSONArray("stub");
        if (runs == null || runs.length() == 0) {
            return one("单 " + who + " 第 " + n + " 次起来：假工人一次都没被叫起来（stub 是空的，这一轮没真跑过）");
        }
        JSONObject first = runs.optJSONObject(0);
        JSONArray argvJson = first == null ? null : first.optJSONArray("argv");
        List<String> argv = new ArrayList<>();
        if (argvJson != null) for (int k = 0; k < argvJson.length(); k++) argv.add(String.valueOf(argvJson.opt(k)));
        String prompt = text(first, "prompt") == null ? "" : text(first, "prompt");
        String sid = text(first, "sessionId");
        boolean resumed = argv.contains("--resume"), fresh = argv.contains("--session-id");
        boolean changed = isTrue(sb, "sessionStarted") && text(sb, "sessionModel") != null
                && !text(sb, "sessionModel").equals(model);
        List<String> bad = new ArrayList<>();
        String headSentence;
        if (changed) {
            // 别的模型开的会话：那边的 thinking block 接着读不了，换一个新 session 从 brief 的第一句说起
            if (resumed) bad.add("用了 --resume");
            if (!fresh) bad.add("没有 --session-id");
            else if (sid != null && sid.equals(text(sb, "sessionId"))) {
                bad.add("--session-id 带的是 state 里原来那个 " + sid + "，不是新的");
            }
            if (prompt.startsWith(CONTINUATION)) bad.add("第一句是续跑句，不是 brief 的第一句加「fresh session」那段");
            else if (!prompt.contains(BRIEF_MARK)) bad.add("第一句不是 brief 的第一句");
            else if (!prompt.contains(FRESH_MARK)) bad.add("第一句里没有「fresh session」那段");
            headSentence = "单 " + who + " 第 " + n + " 次起来（换了模型）：state 里记的会话是 "
                    + text(sb, "sessionModel") + " 开的，单子要的是 " + model + " —— ";
        } else if (resumed) {
            if (!isTrue(sb, "sessionStarted")) bad.add("state 说会话还没起来过，却用了 --resume");
            if (sid == null || !sid.equals(text(sb, "sessionId"))) {
                bad.add("--resume 带的是 " + sid + "，state 里记的是 " + text(sb, "sessionId"));
            }
            if (prompt.contains(BRIEF_MARK)) bad.add("第一句是 brief 的第一句，不是续跑句");
            else if (!prompt.startsWith(CONTINUATION)) bad.add("第一句不是「" + CONTINUATION + "」那句");
            headSentence = "单 " + who + " 第 " + n + " 次起来（接着跑）：";
        } else if (fresh) {
            if (isTrue(sb, "sessionStarted")) bad.add("state 说会话起来过、模型也没换，却开了新会话（该 --resume）");
            if (sid == null || !sid.equals(text(sb, "sessionId"))) {
                bad.add("--session-id 带的是 " + sid + "，state 里记的是 " + text(sb, "sessionId"));
            }
            if (prompt.startsWith(CONTINUATION)) bad.add("第一句是续跑句，不是 brief 的第一句");
            else if (!prompt.contains(BRIEF_MARK)) bad.add("第一句不是 brief 的第一句");
            if (prompt.contains(FRESH_MARK)) bad.add("第一句里有「fresh session」那段，可这个会话是第一次起来");
            headSentence = "单 " + who + " 第 " + n + " 次起来（第一次跑）：";
        } else {
            return one("单 " + who + " 第 " + n + " 次起来：假工人拿到的 argv 里既没有 --resume 也没有 --session-id（"
                    + String.join(" ", argv.subList(0, Math.min(12, argv.size()))) + "）");
        }
        List<String> out = bad.isEmpty() ? none() : one(headSentence + String.join("；", bad));
        // 轮数到顶：run.mjs 实测会把第 maxRounds 轮再跑一次（beginRound == maxRounds），所以规矩是「不越过 maxRounds」
        if (num(sb, "rounds") >= maxRounds) {
            List<String> over = new ArrayList<>();
            if (num(l, "beginRound") < 0 || num(l, "beginRound") > maxRounds) {
                over.add("从第 " + num(l, "beginRound") + " 轮开始");
            }
            JSONArray rounds = l.optJSONArray("rounds");
            int len = rounds == null ? 0 : rounds.length();
            List<String> past = new ArrayList<>();
            for (int k = 0; k < len; k++) {
                Object v = rounds.opt(k);
                if (v instanceof Integer && (Integer) v > maxRounds) past.add("第 " + v + " 轮");
            }
            if (!past.isEmpty()) over.add("跑到了" + String.join("、", past));
            if (num(l.optJSONObject("stateAfter"), "rounds") > maxRounds) {
                over.add("state 记成了 rounds=" + num(l.optJSONObject("stateAfter"), "rounds"));
            }
            if (len > 1) over.add("一次起来跑了 " + len + " 轮（到顶的单最多再跑一轮）");
            if (!DONE.contains(text(fin, "status"))) {
                over.add("最后落在 " + text(fin, "status") + "（该是 accepted_mechanical 或 needs_lead）");
            }
            if (!over.isEmpty()) {
                out.add("单 " + who + " 第 " + n + " 次起来：state 说 rounds=" + num(sb, "rounds") + "，已经到顶（maxRounds="
                        + maxRounds + "），却 " + String.join("；", over) + " —— 轮数跑出了封顶");
            }
        }
        return out;
    }

    /** 一轮要真的跑到 runner 自己的验收：跑完几次就要有几条账，每一条都过了。 */
    private static List<String> acceptRule(String who, int i, JSONObject l) {
        int n = i + 1;
        JSONArray acc = l.optJSONArray("accept");
        if (acc == null) return one("单 " + who + " 第 " + n + " 次起来：没有 accept（这一轮有没有跑到验收，报告里没说）");
        JSONArray runs = l.optJSONArray("stub");
        int finished = 0;
        for (int k = 0; k < (runs == null ? 0 : runs.length()); k++) {
            if (isTrue(runs.optJSONObject(k), "finished")) finished++;
        }
        if (acc.length() != finished) {
            return one("单 " + who + " 第 " + n + " 次起来：假工人跑完 " + finished + " 轮，验收记了 " + acc.length()
                    + " 条（被杀的那一轮不该有账，跑完的也不该没有）");
        }
        int bad = 0;
        for (int k = 0; k < acc.length(); k++) if (!isTrue(acc.optJSONObject(k), "pass")) bad++;
        if (bad > 0) {
            return one("单 " + who + " 第 " + n + " 次起来：" + acc.length() + " 轮里 " + bad
                    + " 轮验收没过 —— 这一轮没有真的跑完，报告却当它跑完了");
        }
        return none();
    }

    private static boolean stateOk(JSONObject s) {
        return s != null && num(s, "rounds") >= 0 && num(s, "history") >= 0
                && s.opt("sessionStarted") instanceof Boolean && text(s, "sessionId") != null;
    }

    private static List<String> job(JSONObject j) {
        String who = text(j, "id") == null ? "(没有 id)" : text(j, "id");
        List<String> out = new ArrayList<>();
        if (!who.startsWith("rehearse-")) {
            out.add("单 " + who + "：id 不是 rehearse- 开头的（演练的单不许和真单混在一个目录里）");
        }
        JSONArray launches = j.optJSONArray("launches");
        if (launches == null || launches.length() == 0) {
            out.add("单 " + who + "：一次 run.mjs 都没起来过（launches 是空的，这份报告什么也没证明）");
            return out;
        }
        String model = text(j, "model");
        int maxRounds = num(j, "maxRounds");
        JSONObject s0 = j.optJSONObject("state0"), fin = j.optJSONObject("final"), stub = j.optJSONObject("stub");
        if (model == null || maxRounds < 0 || !stateOk(s0) || !stateOk(fin) || stub == null
                || num(stub, "runs") < 0 || num(stub, "finished") < 0) {
            out.add("单 " + who + "：报告的形状不对（model / maxRounds / state0 / final / stub.runs / stub.finished）");
            return out;
        }
        for (int i = 0; i < launches.length(); i++) {
            JSONObject l = launches.optJSONObject(i);
            if (l == null || num(l, "beginRound") < 0 || !stateOk(l.optJSONObject("stateBefore"))
                    || !stateOk(l.optJSONObject("stateAfter")) || l.optJSONArray("stub") == null) {
                out.add("单 " + who + " 第 " + (i + 1) + " 次起来：形状不对（beginRound / stateBefore / stateAfter / stub）");
                return out;
            }
        }

        // 每一轮的账：跑完一次记一条，没跑完的一次都不记
        int finished = num(stub, "finished"), want = num(s0, "history") + finished;
        if (num(fin, "history") != want) {
            out.add("单 " + who + "：假工人跑完 " + finished + " 轮，history 该是 " + want + "（state0 的 "
                    + num(s0, "history") + " 加跑完的 " + finished + "），记的是 " + num(fin, "history")
                    + " —— 有一轮没记账，或者有一轮没跑完却记了账");
        }
        int killed = 0;
        for (int i = 0; i < launches.length(); i++) if (isTrue(launches.optJSONObject(i), "killed")) killed++;
        int unfinished = num(stub, "runs") - finished;
        if (unfinished != killed) {
            out.add("单 " + who + "：假工人被叫起来 " + num(stub, "runs") + " 次、跑完 " + finished + " 次，" + unfinished
                    + " 次没跑完，可被杀掉的起来是 " + killed + " 次（对不上）");
        }

        out.addAll(killedRound(who, launches, fin));
        for (int i = 0; i < launches.length(); i++) {
            JSONObject l = launches.optJSONObject(i);
            out.addAll(launchRules(who, i, l, model, maxRounds, fin));
            out.addAll(acceptRule(who, i, l));
        }
        return out;
    }

    /** 一份报告里所有不对的地方，一句一句；没问题就一句都不说。root 是给演练脚本自己那一条用的。 */
    static List<String> problems(String json, Path root) {
        List<String> out = new ArrayList<>(toolProblems(root));
        JSONObject r;
        try {
            r = new JSONObject(json);
        } catch (JSONException e) {
            out.add("报告读不出来（要是一个 JSON 对象）");
            return out;
        }
        out.addAll(safety(r));
        JSONArray jobs = r.optJSONArray("jobs");
        if (jobs == null || jobs.length() == 0) {
            out.add("报告里一个单都没有（jobs 要是一个非空数组）");
            return out;
        }
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject j = jobs.optJSONObject(i);
            if (j == null) out.add("jobs 里有一条不是对象");
            else out.addAll(job(j));
        }
        return out;
    }

    // ---- 一条坏规矩：恰好一句，而且这句话里有它在说的那个词 --------------------------------------------

    private static void problem(List<String> problems, String needle, String label) {
        check(problems.size() == 1, label + "：只有一句（实际 " + problems.size() + " 句 " + problems + "）");
        check(!problems.isEmpty() && problems.get(0).contains(needle),
                label + "：这句话里有「" + needle + "」（实际 " + problems + "）");
    }

    /** 一份传进去的报告，恰好说一句，而且说的是 needle 那件事。 */
    private static void bad(String json, Path root, String needle, String label) {
        problem(problems(json, root), needle, label);
    }

    /** 一份传进去的报告，一句都不说。 */
    private static void ok(String json, Path root, String label) {
        List<String> ps = problems(json, root);
        check(ps.isEmpty(), label + "：一句都不说（实际 " + ps + "）");
    }

    private static void clean(List<String> problems, String label) {
        check(problems.isEmpty(), label + "：一句都不说（实际 " + problems + "）");
    }

    private static String read(Path p) {
        try {
            if (p == null || !Files.isRegularFile(p)) return null;
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void run() throws Exception {
        Path root = Paths.get("");
        sound(root);
        brokenRules(root);
        shippedFixtures(root);
        theToolItself(root);
    }

    // ---- 没毛病的几份，和「这条规矩不是空话」 ------------------------------------------------------------

    private static void sound(Path root) {
        ok(goodKill(), root, "杀掉再起来的一场：被杀的那一轮原样再跑一次");
        ok(report(SCRATCH, modelJob("--session-id", S2, FRESH)), root, "换了模型的一场：新 session-id，说的是 brief 的第一句加「fresh session」那段");
        ok(report(SCRATCH, capJob(2, 2, 2, "accepted_mechanical", 3)), root, "轮数到顶的一场：第 maxRounds 轮再跑一次，过了");
        ok(report(SCRATCH, capJob(2, 2, 2, "needs_lead", 3)), root, "轮数到顶的一场：没过，落在 needs_lead");
        check(!problems(goodKill().replace("\"finished\":1}", "\"finished\":2}"), root).isEmpty(),
                "把跑完的次数改成 2，账就对不上了：这几条规矩不是空话");
    }

    // ---- 每条规矩，各弄坏一次 ------------------------------------------------------------------------------

    private static void brokenRules(Path root) {
        // 1. 被杀的那一轮被当成跑过了
        bad(report(SCRATCH, killJob(FIRST, 2, "--resume", CONT, 2, 1, 3)), root, "没跑完的那一轮被当成跑过了", "第二次从第 2 轮开始（第一轮被杀，没记账）");
        bad(report(SCRATCH, killJob(FIRST, 1, "--resume", CONT, 2, 1, 3)), root, "最后 rounds=2 而 history=1", "最后一次 state 的 rounds 比 history 大");
        // 2. 换了模型还接着跑那个会话
        bad(report(SCRATCH, modelJob("--resume", S1, FRESH)), root, "用了 --resume", "换了模型却接着跑那个会话");
        bad(report(SCRATCH, modelJob("--session-id", S1, FRESH)), root, "不是新的", "换了模型，可 --session-id 带的是 state 里原来那个");
        bad(report(SCRATCH, modelJob("--session-id", S2, FIRST)), root, "第一句里没有「fresh session」那段", "换了模型，第一句只有 brief 的第一句");
        // 3. 两句话放错了地方
        bad(report(SCRATCH, killJob(FIRST, 1, "--resume", FIRST, 1, 1, 3)), root, "第一句是 brief 的第一句", "接着跑的时候给的是 brief 的第一句");
        bad(report(SCRATCH, killJob(CONT, 1, "--resume", CONT, 1, 1, 3)), root, "第一句是续跑句", "第一次跑（会话还没起来过）给的是续跑句");
        // 4. 轮数跑出封顶
        bad(report(SCRATCH, capJob(3, 2, 3, "needs_lead", 3)), root, "轮数跑出了封顶", "到顶的单从第 maxRounds+1 轮开始");
        bad(report(SCRATCH, capJob(2, 2, 2, "failed_round", 3)), root, "该是 accepted_mechanical 或 needs_lead", "到顶的单最后落在 failed_round");
        // 5. 一次都没起来过
        bad(report(SCRATCH, noLaunchJob()), root, "一次 run.mjs 都没起来过", "一个单的 launches 是空的");
        // 6. 写进真的 jobs 目录
        bad(goodKill().replace(SCRATCH, REAL_OUT + "/jobs/rehearse-1"), root, "落在真的 jobs 目录", "scratch 就是真的 jobs 目录");
        // 7. 账对不上、形状不对、读不出来。这两条账都从 stub.finished 算出来，所以各自要单找一份只坏一条的报告
        bad(report(SCRATCH, modelJob("--session-id", S2, FRESH)).replace("\"final\":{\"rounds\":2,\"history\":2", "\"final\":{\"rounds\":2,\"history\":3"),
                root, "有一轮没记账", "跑完的次数和 history 对不上");
        bad(goodKill().replace("\"stub\":{\"runs\":2,\"finished\":1}", "\"stub\":{\"runs\":3,\"finished\":1}"), root, "对不上", "没跑完的次数和被杀的次数对不上");
        bad(goodKill().replace("\"beginRound\":1,\"rounds\":[1],\"stateBefore\":{\"rounds\":1", "\"rounds\":[1],\"stateBefore\":{\"rounds\":1"),
                root, "形状不对", "第二次起来的 beginRound 不见了");
        bad("这不是 JSON", root, "读不出来", "整份报告不是 JSON");
        bad(head(SCRATCH) + ",\"jobs\":[]}", root, "一个单都没有", "jobs 是空的");
        bad(goodKill().replace("rehearse-1-kill", "kill-1"), root, "rehearse-", "单子的 id 不是 rehearse- 开头");
        // 8. 反过来：被杀的那次起来时 state 里没有一轮没记账，这份报告就证明不了什么
        bad(goodKill().replace("\"stateAfter\":{\"rounds\":1,\"history\":0", "\"stateAfter\":{\"rounds\":0,\"history\":0"),
                root, "证明不了", "被杀的那次起来，state 里没有一轮没记账");
        // 9. 一轮要真的跑到 runner 自己的验收
        bad(goodKill().replace("\"pass\":true", "\"pass\":false"), root, "轮验收没过", "跑完的那一轮，验收没过");
        bad(goodKill().replace("\"accept\":[]", "\"accept\":[{\"round\":1,\"pass\":true}]"), root, "验收记了 1 条", "被杀的那一轮也记了一条验收账");
    }

    // ---- 仓库里真的那几份：好的那份一句都不说，坏的那些各说一句 ------------------------------------------

    private static void shippedFixtures(Path root) {
        String[][] files = {
            {"good.json", null},
            {"killed-round-counted.json", "没跑完的那一轮被当成跑过了"},
            {"brief-prompt-where-continuation-belongs.json", "第一句是 brief 的第一句"},
            {"continuation-where-brief-belongs.json", "第一句是续跑句"},
            {"resume-after-model-change.json", "用了 --resume"},
            {"round-past-the-cap.json", "轮数跑出了封顶"},
            {"no-launch-at-all.json", "一次 run.mjs 都没起来过"},
            {"writes-into-the-real-jobs-dir.json", "落在真的 jobs 目录"},
        };
        for (String[] f : files) {
            String json = read(root.resolve(REPORTS + f[0]));
            check(json != null, "仓库里有 " + REPORTS + f[0]);
            if (json == null) continue;
            List<String> ps = problems(json, root);
            if (f[1] == null) clean(ps, "仓库里那份真的演练报告 " + f[0] + "（报告的格式变了，这里就说）");
            else problem(ps, f[1], "仓库里那份坏的 " + f[0]);
        }
    }

    // ---- 演练脚本自己 ------------------------------------------------------------------------------------

    private static void theToolItself(Path root) throws Exception {
        String src = read(root.resolve(REHEARSE));
        check(src != null && !src.isEmpty(), REHEARSE + " 在仓库里");
        check(src != null && src.contains("SKYRUSH_OUT"),
                REHEARSE + " 自己提了 SKYRUSH_OUT（演练写进真的 jobs 目录，是唯一会伤到别人的错）");
        check(src != null && src.contains("SKYRUSH_WT") && src.contains("SKYRUSH_ENV"), "SKYRUSH_WT 和 SKYRUSH_ENV 也都指到 scratch 里");
        check(src != null && src.contains("rehearse-"), "演练的 job id 带 rehearse- 前缀");
        check(src != null && src.contains("--keep"), "收场会把 scratch 删掉，除非 --keep");
        clean(toolProblems(root), "仓库里的 " + REHEARSE);
        problem(toolProblems(Paths.get("build", "no-such-root")), "不在", "root 里没有 " + REHEARSE);
        Path tmp = Files.createTempDirectory("resume-checks");
        try {
            Files.createDirectories(tmp.resolve("tools/resume"));
            Files.write(tmp.resolve(REHEARSE), "// SKYRUSH_OUT only\n".getBytes(StandardCharsets.UTF_8));
            problem(toolProblems(tmp), "没提 SKYRUSH_WT", "一份只提了 SKYRUSH_OUT 的演练脚本");
        } finally {
            delete(tmp.toFile());
        }
    }

    private static void delete(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) delete(k);
        f.delete();
    }
}
