package xyz.erapi0neer.xingyi.agent;

import static xyz.erapi0neer.xingyi.agent.RealPathChecks.*;
import static xyz.erapi0neer.xingyi.agent.RealPathEvidenceChecks.*;
import static xyz.erapi0neer.xingyi.agent.RealPathProbeChecks.PROBE;
import static xyz.erapi0neer.xingyi.agent.RealPathProbeChecks.by;
import static xyz.erapi0neer.xingyi.agent.RealPathProbeChecks.ranItself;
import static xyz.erapi0neer.xingyi.agent.RealPathProbeChecks.scriptName;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/**
 * real-path (2026-11-12): the registry half of the rules of {@code tests/agent/realpath/check.py}, each broken once on
 * texts passed in — an id that is unique kebab-case, a one-line {@code what}, a {@code needs} array, an {@code hours}
 * of 1–10, an {@code unverified} that is "" or kebab-case, and a {@code script} that exists and has a {@code main()}
 * in it.
 *
 * <p>This is the one entry point: {@link #run} hands the other three topics to {@link RealPathEvidenceChecks} (the
 * shape of an evidence file, the misplaced WHERE lines and {@code --strict}), {@link RealPathLedgerChecks} (the
 * registry and the ledger saying the same thing) and {@link RealPathProbeChecks} (who wrote an evidence, and the
 * broadcast keys a scenario reads). Those three are four separate {@code final} classes with their own {@code run()},
 * but they still share this file: the three paths they belong in are not in this job's file list, so splitting the
 * text out is a request, not something this job may do (see {@code status/improve/realpath-split.md}).
 *
 * <p>What these checks cannot see is the point: whether the panel really came up while the user was speaking, whether
 * 「暂停」 really paused. That is the scenarios' own business, on the TV. What is checked here is that a claim of
 * having run one is a file of the right shape — and that the one claim nobody may make quietly, 「it passed」, is a
 * boolean somebody wrote down next to what they saw.
 *
 * <p>The small tools all four of them use — {@code check} / {@code problem} / {@code quiet} / {@code one} /
 * {@code reg} / {@code ev} / {@code read} / {@code delete} and the constants they are called with — live here, once,
 * package-visible.
 */
final class RealPathLedgerChecks {
    private RealPathLedgerChecks() { }

    static void run() throws Exception {
        link();
        ledgerAndRegistry();
        ranItselfRelaxation();
    }

    // ---- 没过的一次验证，要变成一条待办 --------------------------------------------------------------------------

    private static void link() {
        String failed = ev(NAME, WHEN, "false", "[\"3 秒后音频服务还是 started\"]");
        problem(evidence(failed, NAME, "", UNVERIFIED, NOW), "unverified 是空的",
                "没过的证据，unverified 是空的：有一句说没过的验证要留下待办");
        problem(evidence(failed, NAME, "gone", UNVERIFIED, NOW), "不在 tests/agent/unverified.json 里",
                "unverified 指的那条待办在 unverified.json 里没有");
        problem(evidence(failed, NAME, "panel-while-speaking", null, NOW), "unverified.json 读不出来",
                "unverified.json 不在：有一句说读不出来，没有悄悄放过");
        problem(evidence(failed, NAME, "panel-while-speaking", "{}", NOW), "unverified.json 读不出来",
                "unverified.json 里没有 entries 数组");
        check(evidence(failed, NAME, "panel-while-speaking", UNVERIFIED, NOW).isEmpty(),
                "没过的证据，unverified 指着一条真的待办：一句都不说");
        check(evidence(ev(NAME, WHEN, "true", "[]"), NAME, "gone", UNVERIFIED, NOW).isEmpty(),
                "过了的证据不去查待办（这条规矩只管没过的）");
        check(unverifiedIds(UNVERIFIED) != null && unverifiedIds(UNVERIFIED).size() == 2
                        && unverifiedIds(UNVERIFIED).contains("ktcp-pause-real"),
                "unverified.json 只读 entries[].id");
        check(unverifiedIds(null) == null && unverifiedIds("{}") == null && unverifiedIds("这不是 JSON") == null,
                "unverified.json 不在 / 没有 entries / 不是 JSON：都是读不出来");
    }

    // ---- 注册表和账本必须说的是同一件事：没跑过的场景要指着一条待办，验过的待办要删掉 ----------------------------------

    /** 账本里真有的两条：一条是场景自己的 id，一条和场景无关。 */
    static final String LEDGER =
            "{\"entries\":[{\"id\":\"panel-self-check\"},{\"id\":\"ktcp-pause-real\"}]}";
    /** 一条从没在真机上跑过的场景。 */
    static final String NEVER = "panel-self-check";

    private static String neverRun(String unverified) {
        return reg(one(NEVER, "面板自检：self_check 的回答里说面板", "[\"亮屏\"]", SCRIPT, "2", unverified));
    }
    private static void ledgerAndRegistry() throws Exception {
        Map<String, Boolean> noEvidence = new HashMap<>();
        List<String> empty = todo(neverRun(""), LEDGER, noEvidence);
        problem(empty, NEVER, "一个没跑过、unverified 空着的场景：有一句，而且点名它");
        check(empty.size() == 1 && empty.get(0).contains("还没有证据文件") && empty.get(0).contains("待办"),
                "这句话说的是「还没有证据文件」「要指着一条待办」（实际 " + empty + "）");

        List<String> gone = todo(neverRun("gone-todo"), LEDGER, noEvidence);
        problem(gone, "gone-todo", "没跑过、unverified 指着账本里没有的 id：有一句，点名那个 id");
        check(gone.size() == 1 && gone.get(0).contains(NEVER),
                "这句话也点名是哪个场景在指它（实际 " + gone + "）");

        check(todo(neverRun(NEVER), LEDGER, noEvidence).isEmpty(),
                "没跑过、unverified 指着账本里真有的 id：一句都不说");

        problem(todo(neverRun(NEVER), null, noEvidence), "读不出来", "账本不在：有一句说读不出来，没有悄悄放过");
        problem(todo(neverRun(NEVER), "{}", noEvidence), "读不出来", "账本里没有 entries 数组：有一句说读不出来");
        problem(todo(neverRun(NEVER), "这不是 JSON", noEvidence), "读不出来", "账本不是 JSON：有一句说读不出来");
        List<String> blind = todo(neverRun(NEVER), null, noEvidence);
        check(blind.size() == 1 && blind.get(0).contains(NEVER) && blind.get(0).contains("tests/agent/unverified.json"),
                "读不出来那句话点名是哪个文件、哪个场景（实际 " + blind + "）");

        Map<String, Boolean> passed = new HashMap<>();
        passed.put(NEVER, Boolean.TRUE);
        List<String> stale = todo(neverRun(NEVER), LEDGER, passed);
        problem(stale, "该删掉", "验过了（real-<id>.json 是 ok:true），账本里还留着这条：有一句说该删掉");
        check(stale.size() == 1 && stale.get(0).contains(NEVER) && stale.get(0).contains("verify.sh"),
                "这句话点名场景，还说清跑什么会自己把它删掉（实际 " + stale + "）");

        Map<String, Boolean> failed = new HashMap<>();
        failed.put(NEVER, Boolean.FALSE);
        check(todo(neverRun(NEVER), LEDGER, failed).isEmpty(),
                "证据是 ok:false：这条规矩一句都不说（没过的那条由 link 管）");
        check(todo(neverRun(NEVER), "{\"entries\":[{\"id\":\"ktcp-pause-real\"}]}", passed).isEmpty(),
                "验过了，账本里没有它：一句都不说");
        check(todo(neverRun(NEVER), null, passed).isEmpty(),
                "验过了，可账本读不出来：这条规矩不猜（读不出来的话由没跑过的那半边说）");
        Map<String, Boolean> odd = new HashMap<>();
        odd.put(NEVER, null);
        check(todo(neverRun(NEVER), LEDGER, odd).isEmpty(),
                "证据文件在，可 ok 不是布尔：这条规矩不把它当成验过了");
        check(todo("这不是 JSON", LEDGER, noEvidence).isEmpty() && todo("{}", LEDGER, noEvidence).isEmpty(),
                "注册表读不出来：这条规矩一句都不抢（registry() 已经说了）");

        Path fix = Files.createTempDirectory("realpath-todo");
        try {
            Files.createDirectories(fix.resolve("tests/agent/realpath"));
            Files.createDirectories(fix.resolve("tests/agent/evidence"));
            Files.write(fix.resolve("tests/agent/realpath/scenarios.json"),
                    neverRun("").getBytes(StandardCharsets.UTF_8));
            Files.write(fix.resolve("tests/agent/unverified.json"), LEDGER.getBytes(StandardCharsets.UTF_8));
            problem(todoOnTree(fix), NEVER, "一棵临时的树：读的是给它的那个 root");
            check(todoOnTree(Paths.get("")).isEmpty(),
                    "同一时刻真实树还是干净的：fixture 的临时树没有被当成真树（实际 " + todoOnTree(Paths.get("")) + "）");
            Files.write(fix.resolve("tests/agent/evidence/real-" + NEVER + ".json"),
                    ev(NEVER, WHEN, "true", "[]").getBytes(StandardCharsets.UTF_8));
            problem(todoOnTree(fix), "该删掉", "临时树里放上一份 ok:true 的证据：账本里那条待办就被点出来");
            Files.write(fix.resolve("tests/agent/evidence/real-" + NEVER + ".json"),
                    ev(NEVER, WHEN, "false", "[]").getBytes(StandardCharsets.UTF_8));
            check(todoOnTree(fix).isEmpty(), "同一份证据改成 ok:false：又一句都不说");
        } finally {
            delete(fix.toFile());
        }
        check(todoOnTree(Paths.get("")).isEmpty(),
                "仓库里真正那一份：注册表、证据和账本说的是同一件事（实际 " + todoOnTree(Paths.get("")) + "）");
        // 「真树一句都没有」不能是空话：把真树的账本换成空的，每一条没有证据文件的场景都得当场被点出来
        Map<String, Boolean> realEvidence = evidenceOnTree(Paths.get(""));
        check(!realEvidence.isEmpty(),
                "真树的 tests/agent/evidence/ 真的被读到了（real-*.json " + realEvidence.size() + " 份）");
        JSONArray shipped = new JSONObject(shippedJson()).getJSONArray("scenarios");
        List<String> never = new ArrayList<>();
        for (int i = 0; i < shipped.length(); i++) {
            String id = shipped.getJSONObject(i).optString("id", "");
            if (!id.isEmpty() && !realEvidence.containsKey(id)) never.add(id);
        }
        List<String> ifForgotten = todo(shippedJson(), "{\"entries\":[]}", realEvidence);
        boolean allNamed = ifForgotten.size() == never.size() && (never.isEmpty() || !ifForgotten.isEmpty());
        for (String id : never) if (!ifForgotten.toString().contains(id)) allNamed = false;
        check(allNamed, "真树的账本要是忘了写，每一条没有证据文件的场景都当场被点出来（真树上这样的场景有 "
                + never.size() + " 条 " + never + "；实际 " + ifForgotten + "）");
    }

    // ---- 账本那条规矩跟着放宽：探针跑的证据，账本里留着这条是对的 ------------------------------------------------

    private static void ranItselfRelaxation() {
        Map<String, Boolean> passed = new HashMap<>();
        passed.put(NEVER, Boolean.TRUE);
        String regNever = reg(one(NEVER, "面板自检", "[\"亮屏\"]",
                "tests/agent/realpath/scenarios/" + NEVER + ".py", "2", NEVER));
        Map<String, String> byProbe = new HashMap<>();
        byProbe.put(NEVER, PROBE);
        quiet(todo(regNever, LEDGER, passed, byProbe),
                "证据是别的脚本跑出来的，账本里还留着这条：一句都不说（那个脚本自己还没跑过）");
        Map<String, String> byItself = new HashMap<>();
        byItself.put(NEVER, NEVER + ".py");
        problem(todo(regNever, LEDGER, passed, byItself), "该删掉",
                "证据是场景脚本自己跑出来的，账本里还留着：照旧说该删掉");
        problem(todo(regNever, LEDGER, passed, new HashMap<String, String>()), "该删掉",
                "老证据没写 by，账本里还留着：照旧说该删掉（旧规矩一个字没改）");
        check(todo(regNever, LEDGER, passed).equals(todo(regNever, LEDGER, passed, new HashMap<String, String>())),
                "不给 by 的三参数版和「一份 by 都没有」的四参数版一样：老检查照旧");
    }
    // ---- 注册表和账本说的是不是同一件事 --------------------------------------------------------------------------

    /**
     * The rule of check.py's {@code todo_problems()}, both ways: a scenario with no {@code evidence/real-<id>.json}
     * names, through its {@code unverified}, an entry that is really in {@code tests/agent/unverified.json} — a
     * scenario nobody has run is a to-do, or it disappears; and a scenario whose evidence says {@code "ok": true} has
     * no entry of its own id left in that ledger — the run that verified it takes the to-do out again (that second
     * direction is narrowed by {@code by}: see the four-argument version below). 2026-11-12
     * 21:40: three scenarios had never run on the TV, the ledger was empty because nobody had written it down, and
     * NEXT.md / bridge.sh status / HANDOFF all read that as 「一条都没有：装上的东西都在真机上见过」.
     *
     * <p>These are problems, not notices: {@code --strict} or not, they count. Evidence with {@code "ok": false} is
     * {@link RealPathEvidenceChecks}'s business and is not repeated here, a registry that cannot be read is
     * {@link RealPathChecks#registry}'s, and a ledger that cannot be read is complained about in the first direction
     * only — the second one needs the ledger to say anything at all, so it stays quiet instead of guessing.
     *
     * @param evidence scenario id → the {@code ok} in its evidence file: a key is there when the file is, and the value
     *                 is null when the file is not JSON or says anything but a boolean
     */
    static List<String> todo(String registryJson, String unverifiedJson, Map<String, Boolean> evidence) {
        return todo(registryJson, unverifiedJson, evidence, new HashMap<String, String>());
    }

    /**
     * The same rule, with who wrote each evidence: an evidence that says "ok": true takes its to-do out of the ledger
     * only when the scenario's own script wrote it ({@code by} is that script's file name, or the file is old and says
     * nothing). When {@code by} is some other script the entry stays and nothing is said: a hand-written probe verified
     * the behaviour, the scenario script itself still has not run, and the to-do is exactly right where it is —
     * {@link RealPathProbeChecks#probeMade} is what says that, as a hint.
     *
     * @param by scenario id → the {@code by} in its evidence file; a missing key means the file says nothing
     */
    static List<String> todo(String registryJson, String unverifiedJson, Map<String, Boolean> evidence,
                             Map<String, String> by) {
        List<String> out = new ArrayList<>();
        JSONArray entries;
        try {
            entries = new JSONObject(registryJson == null ? "" : registryJson).optJSONArray("scenarios");
        } catch (JSONException e) {
            return out;
        }
        if (entries == null) return out;
        List<String> ids = unverifiedIds(unverifiedJson);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            String sid = str(e.opt("id"));
            if (sid.isEmpty()) continue;          // registry() already says what is wrong with this one
            if (evidence.containsKey(sid)) {
                if (Boolean.TRUE.equals(evidence.get(sid)) && ids != null && ids.contains(sid)
                        && ranItself(by.get(sid), scriptName(e)))
                    out.add(sid + " 已经验过了（real-" + sid + ".json 是 ok:true），待办该删掉"
                            + "（跑 tests/agent/verify.sh --run 会自己删）");
                continue;
            }
            String un = str(e.opt("unverified"));
            if (un.isEmpty()) {
                out.add(sid + " 还没有证据文件，scenarios.json 里它的 unverified 是空的（没跑过的场景要指着一条待办）");
            } else if (ids == null) {
                out.add(sid + " 还没有证据文件，可 tests/agent/unverified.json 读不出来（不在，或者里面没有 entries 数组），"
                        + "没法确认「" + un + "」这条待办");
            } else if (!ids.contains(un)) {
                out.add(sid + " 还没有证据文件，可 unverified「" + un + "」不在 unverified.json 里（待办没了，或者名字改了）");
            }
        }
        return out;
    }

    /**
     * The same rule on a tree: its registry, the {@code evidence/real-*.json} one level under
     * {@code tests/agent/evidence/}, and its ledger. Reads the root it is given and nothing else — a fixture tree is
     * never mistaken for the repository.
     */
    static List<String> todoOnTree(Path root) {
        Map<String, String> ran = new HashMap<>();
        for (Map.Entry<String, String> e : evidenceTextOnTree(root).entrySet()) ran.put(e.getKey(), by(e.getValue()));
        return todo(read(root.resolve("tests").resolve("agent").resolve("realpath").resolve("scenarios.json")),
                read(root.resolve("tests").resolve("agent").resolve("unverified.json")), evidenceOnTree(root), ran);
    }
}
