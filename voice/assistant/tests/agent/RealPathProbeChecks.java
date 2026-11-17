package xyz.erapi0neer.xingyi.agent;

import static xyz.erapi0neer.xingyi.agent.RealPathChecks.*;
import static xyz.erapi0neer.xingyi.agent.RealPathEvidenceChecks.*;
import static xyz.erapi0neer.xingyi.agent.RealPathLedgerChecks.*;
import static xyz.erapi0neer.xingyi.agent.RealPathShapeChecks.*;
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
 * real-path: the two rules that need no TV to be wrong to be caught.
 *
 * <p>One, because two kinds of evidence looked exactly the same: {@code probe.evidence()} records {@code by}, the file
 * name of the script that produced the file, so 「the behaviour was verified」 and 「this scenario script itself passes」
 * stop being the same claim. 2026-11-13 01:40: five of the ten evidence files had been written by hand-written probes
 * ({@code session7m.py}, {@code h1.py}, {@code h2.py}, {@code h3.py}) while their scenario scripts had never run at all
 * — hint-card-not-a-switch 11-12 22:47 had already been that same shape. {@link #probeMade} says so, as hints and
 * never as problems: the behaviour really was verified on the TV, the tree is not broken, and what is missing is a
 * rerun. The ledger rule follows: an evidence written by some other script leaves the to-do where it is.
 *
 * <p>Two, the keys a scenario reads: every key read straight off {@code p.state()} / {@code p.memory()} has to be a
 * top-level key that broadcast really carries, as recorded in {@code tests/agent/fixtures/realpath/payload-shapes.json}.
 * 2026-11-12 22:47: hint-card-not-a-switch read {@code hints} from STATE, which has never carried it — it is in MEMORY
 * — so the scenario read {@code None} twice and could not pass however the TV behaved, while the registry and the
 * evidence files were both the right shape.
 *
 * <p>Called by {@link RealPathChecks#run()}.
 */
final class RealPathProbeChecks {
    private RealPathProbeChecks() { }

    static void run() throws Exception {
        provenance();
        provenanceTrees();
        provenanceOnRealTree();
        RealPathShapeChecks.run();
    }

    // ---- 证据是谁跑出来的：探针跑的和场景脚本跑的，文件里要分得清 ------------------------------------------------

    /** A scenario whose script really is {@code <id>.py}, so the expected {@code by} reads the way check.py words it. */
    private static final String OWN = "tests/agent/realpath/scenarios/" + NAME + ".py";
    /** A hand-written probe: it verified the behaviour on the TV, and it is not the scenario script. */
    static final String PROBE = "h1.py";

    private static String ownReg(String unverified) {
        return reg(one(NAME, "说话时逐字上屏", "[\"亮屏\"]", OWN, "2", unverified));
    }

    private static String evBy(String id, String at, String ok, String notes, String by) {
        return "{\"id\":\"" + id + "\",\"at\":\"" + at + "\",\"ok\":" + ok + ",\"by\":\"" + by + "\",\"notes\":"
                + notes + ",\"shots\":[]}";
    }

    private static Map<String, String> oneFile(String id, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(id, text);
        return m;
    }

    private static void provenance() {
        quiet(probeMade(ownReg(""), oneFile(NAME, evBy(NAME, WHEN, "true", "[]", NAME + ".py"))),
                "证据是场景脚本自己跑出来的（by 就是 " + NAME + ".py）：一句提示都不出");

        List<String> probe = probeMade(ownReg(""), oneFile(NAME, evBy(NAME, WHEN, "true", "[]", PROBE)));
        problem(probe, PROBE, "证据是手写探针 " + PROBE + " 跑出来的：有一句，点名那个探针");
        check(probe.size() == 1 && probe.get(0).contains(NAME + ".py"),
                "同一句话点名场景脚本 " + NAME + ".py（实际 " + probe + "）");
        check(probe.size() == 1 && probe.get(0).contains("sh tests/agent/verify.sh --run --only " + NAME),
                "同一句话带着那条重跑命令（实际 " + probe + "）");
        check(probe.size() == 1 && probe.get(0).contains("行为验过了") && probe.get(0).contains("还没跑通过"),
                "同一句话说清「行为验过了，可这个脚本自己还没跑通过」（实际 " + probe + "）");

        List<String> old = probeMade(ownReg(""), oneFile(NAME, ev(NAME, WHEN, "true", "[]")));
        problem(old, "分不清", "老证据里根本没有 by：有一句说分不清是脚本还是探针");
        check(old.size() == 1 && old.get(0).contains("没写是谁跑出来的") && old.get(0).contains("老文件"),
                "同一句话说的是「没写是谁跑出来的（老文件）」（实际 " + old + "）");
        check(probeMade(ownReg(""), oneFile(NAME, evBy(NAME, WHEN, "true", "[]", ""))).equals(old),
                "by 是空串：和根本没有 by 的老文件同一句（实际 "
                        + probeMade(ownReg(""), oneFile(NAME, evBy(NAME, WHEN, "true", "[]", ""))) + "）");

        // 是提示不是问题：算问题的那些规矩，对一份探针跑的证据一句都不说（整棵树上的证明在 provenanceTrees）
        quiet(evidence(evBy(NAME, WHEN, "true", "[]", PROBE), NAME, "", UNVERIFIED, NOW),
                "探针跑出来的证据，形状本身没毛病：evidence() 一句都不说（谁跑的由 probeMade 说）");
        quiet(evidence(ev(NAME, WHEN, "true", "[]"), NAME, "", UNVERIFIED, NOW),
                "老证据（没有 by）形状本身也没毛病：evidence() 一句都不说");
        Map<String, Boolean> selfPassed = new HashMap<>();
        selfPassed.put(NAME, Boolean.TRUE);
        Map<String, String> selfBy = new HashMap<>();
        selfBy.put(NAME, PROBE);
        quiet(registry(ownReg(""), Paths.get("")), "注册表本身没毛病：registry() 一句都不说");
        quiet(todo(ownReg(NAME), UNVERIFIED, selfPassed, selfBy),
                "探针跑的证据 + 账本里还留着这条：账本那条规矩一句都不说（旧规矩在这里会说「该删掉」）");

        quiet(probeMade(ownReg(""), oneFile(NAME, evBy(NAME, WHEN, "false", "[]", PROBE))),
                "证据是 ok:false：一句提示都不出（没过的那条由 link 和账本管）");
        check(probeMade(ownReg(""), new LinkedHashMap<String, String>()).isEmpty(),
                "场景一份证据都没有：一句提示都不出（那是账本那条规矩的事）");
        problem(probeMade(reg(one(NAME, "有", "[\"亮屏\"]", "", "2", "")),
                        oneFile(NAME, evBy(NAME, WHEN, "true", "[]", PROBE))),
                PROBE, "注册表的 script 是空的：按 <id>.py 认，探针跑的照样被点出来");
    }
    // ---- 一棵树：这条规矩读的是给它的那个 root，真树不会被临时树带偏 ------------------------------------------------

    private static void provenanceTrees() throws Exception {
        Path dir = Files.createTempDirectory("realpath-by");
        try {
            Files.createDirectories(dir.resolve("tests/agent/realpath/scenarios"));
            Files.createDirectories(dir.resolve("tests/agent/evidence"));
            Path registry = dir.resolve("tests/agent/realpath/scenarios.json");
            Path a = dir.resolve("tests/agent/evidence/real-a-scenario.json");
            Path b = dir.resolve("tests/agent/evidence/real-b-scenario.json");
            String two = reg(
                    one("a-scenario", "甲", "[\"亮屏\"]", "tests/agent/realpath/scenarios/a-scenario.py", "2", ""),
                    one("b-scenario", "乙", "[\"亮屏\"]", "tests/agent/realpath/scenarios/b-scenario.py", "2", ""));
            Files.write(registry, two.getBytes(StandardCharsets.UTF_8));
            for (String n : new String[]{"a-scenario", "b-scenario"}) {
                Files.write(dir.resolve("tests/agent/realpath/scenarios/" + n + ".py"),
                        SCRIPT_TEXT.getBytes(StandardCharsets.UTF_8));
            }
            check(probeMadeOnTree(dir).isEmpty(),
                    "临时树里一份证据都没有：一句提示都不出（没跑过的场景是账本那条规矩的事）");

            Files.write(a, evBy("a-scenario", WHEN, "true", "[]", PROBE).getBytes(StandardCharsets.UTF_8));
            Files.write(b, evBy("b-scenario", WHEN, "false", "[]", PROBE).getBytes(StandardCharsets.UTF_8));
            List<String> only = probeMadeOnTree(dir);
            check(only.size() == 1 && only.get(0).startsWith("a-scenario "),
                    "探针跑的 ok:true 得一句；ok:false 那份一句都不出（实际 " + only + "）");

            Files.write(dir.resolve("tests/agent/evidence/real-c-scenario.json"),
                    "这不是 JSON".getBytes(StandardCharsets.UTF_8));
            check(probeMadeOnTree(dir).size() == 1,
                    "多一份注册表里没有的证据：还是那一句（这条规矩只看注册表里的场景；实际 "
                            + probeMadeOnTree(dir) + "）");
            Files.write(b, "这不是 JSON".getBytes(StandardCharsets.UTF_8));
            check(probeMadeOnTree(dir).size() == 1,
                    "注册表里有这个场景，可它的证据读不出来：一句都不出，也不抛异常（实际 " + probeMadeOnTree(dir) + "）");

            // 提示不是问题：同一棵树上，算问题的那些规矩一句都不说，账本里那条待办也留着不动
            Files.write(b, evBy("b-scenario", WHEN, "false", "[]", PROBE).getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("tests/agent/unverified.json"),
                    "{\"entries\":[{\"id\":\"a-scenario\"}]}".getBytes(StandardCharsets.UTF_8));
            List<String> counted = new ArrayList<>();
            counted.addAll(registry(read(registry), dir));
            counted.addAll(todoOnTree(dir));
            counted.addAll(misplaced(dir));
            counted.addAll(shapeOnTree(dir));
            check(counted.isEmpty(),
                    "探针跑的证据 + 账本里还留着这条：算问题的规矩一句都不说（实际 " + counted + "）");
            check(failures(counted, new ArrayList<String>(), true) == 0 && probeMadeOnTree(dir).size() == 1,
                    "同一棵树加 --strict 也是 0 处，提示只有 probeMade 那一句（提示没有进 failures()：实际 "
                            + probeMadeOnTree(dir) + "）");

            Files.write(a, evBy("a-scenario", WHEN, "true", "[]", "a-scenario.py").getBytes(StandardCharsets.UTF_8));
            check(probeMadeOnTree(dir).isEmpty(), "同一棵树里那份证据改成场景脚本自己跑的：一句都不出");
            problem(todoOnTree(dir), "该删掉",
                    "改成场景脚本自己跑的：账本里那条待办照旧被点出来（这条规矩没有被放松掉）");
            Files.write(registry, "这不是 JSON".getBytes(StandardCharsets.UTF_8));
            Files.write(a, evBy("a-scenario", WHEN, "true", "[]", PROBE).getBytes(StandardCharsets.UTF_8));
            check(probeMadeOnTree(dir).isEmpty(), "注册表读不出来：这条规矩一句都不抢（registry() 已经说了）");
        } finally {
            delete(dir.toFile());
        }
    }

    // ---- 仓库里真正那一份：每一句提示都点名一个真有证据的场景，没写 by 的证据一句都不漏 --------------------------------

    private static void provenanceOnRealTree() throws Exception {
        Path root = Paths.get("");
        List<String> hints = probeMadeOnTree(root);
        JSONArray shipped = new JSONObject(shippedJson()).getJSONArray("scenarios");
        Map<String, String> evidence = evidenceTextOnTree(root);
        check(evidence.size() >= 10,
                "真树的 tests/agent/evidence/ 真的被读到了（real-*.json " + evidence.size() + " 份）");
        boolean named = true;
        for (String line : hints) {
            if (!namesScenario(line, shipped, evidence)) named = false;
        }
        check(named, "真树上每一句提示都点名一个注册表里有、也有证据文件的场景（现在 " + hints.size() + " 句 " + hints + "）");
        check(hints.size() <= shipped.length(),
                "提示不会比场景还多（" + hints.size() + " 句 / " + shipped.length() + " 个场景）");

        // 「没写 by 的证据都被点了一句」不能是空话：写与不写，这一句都得跟着真树走
        int silent = 0;
        boolean all = true;
        for (Map.Entry<String, String> e : evidence.entrySet()) {
            if (!Boolean.TRUE.equals(ok(e.getValue())) || !by(e.getValue()).isEmpty()) continue;
            silent++;
            boolean there = false;
            for (String line : hints) {
                if (line.startsWith(e.getKey() + " ")) there = true;
            }
            if (!there) all = false;
        }
        check(all, "真树上每一份没写 by、却写着 ok:true 的证据都被点了一句（这样的证据 " + silent + " 份，一共 "
                + hints.size() + " 句提示）");
    }

    /** Whether a hint starts with the id of a scenario the registry has and whose evidence file is really there. */
    private static boolean namesScenario(String line, JSONArray entries, Map<String, String> evidence) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id", "");
            if (!id.isEmpty() && line.startsWith(id + " ") && evidence.containsKey(id)) return true;
        }
        return false;
    }
    // ---- 证据是谁跑出来的：规矩本身 ----------------------------------------------------------------------------

    /**
     * The {@code by} in one evidence file: the file name of the script that produced it, as
     * {@code probe.evidence()} writes it. "" when the file says nothing — no {@code by} at all (every file written
     * before 2026-11-13), an empty one ({@code python -c} wrote it), or anything that is not a string. "" means
     * 「不知道」 and is never read as 「the scenario script ran it」.
     */
    static String by(String json) {
        if (json == null) return "";
        try {
            Object v = new JSONObject(json).opt("by");
            return v instanceof String ? (String) v : "";
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * The scenario script's own file name — what {@code by} has to be for the evidence to be that script's own run.
     * Taken from the registry's {@code script}; {@code <id>.py} when the registry names none ({@link RealPathChecks}
     * already says that is wrong, and this rule still has to know what it is comparing against).
     */
    static String scriptName(JSONObject e) {
        String s = str(e.opt("script")).trim();
        if (!s.isEmpty()) {
            int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
            String name = cut >= 0 ? s.substring(cut + 1) : s;
            if (!name.isEmpty()) return name;
        }
        String id = str(e.opt("id"));
        return id.isEmpty() ? "" : id + ".py";
    }

    /**
     * Whether the evidence was produced by the scenario's own script: {@code by} is that file name. An evidence that
     * says nothing about who wrote it counts as its own run — that is the rule as it stood before {@code by} existed,
     * and turning 「I cannot tell」 into 「the to-do may go」 would lose the to-do.
     */
    static boolean ranItself(String by, String script) {
        return by == null || by.isEmpty() || by.equals(script);
    }

    /**
     * The rule of check.py's {@code probe_made()}: one line per scenario whose passing evidence was not written by its
     * own scenario script, and one per scenario whose evidence says nothing about who wrote it.
     *
     * <p>Hints, NOT problems: {@link RealPathEvidenceChecks#failures} does not count them, {@code --strict} does not
     * turn them into failures, and nothing that counts as a problem returns them. What is missing is a rerun
     * ({@code sh tests/agent/verify.sh --run --only <id>}), which is the ledger's business.
     *
     * <p>Only a passing evidence is looked at: what these lines are about is 「行为验过了，可这个脚本自己还没跑通过」,
     * and an evidence that says ok:false is the to-do link's and the ledger's business. A scenario with no evidence
     * file is {@link RealPathLedgerChecks#todo}'s business and is not repeated here.
     *
     * @param evidence scenario id → the text of its {@code evidence/real-<id>.json}; a key is there when the file is
     */
    static List<String> probeMade(String registryJson, Map<String, String> evidence) {
        List<String> out = new ArrayList<>();
        JSONArray entries;
        try {
            entries = new JSONObject(registryJson == null ? "" : registryJson).optJSONArray("scenarios");
        } catch (JSONException e) {
            return out;                     // registry() already says what is wrong with the registry
        }
        if (entries == null) return out;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            String sid = str(e.opt("id"));
            if (sid.isEmpty() || !evidence.containsKey(sid)) continue;
            String text = evidence.get(sid);
            if (!Boolean.TRUE.equals(ok(text))) continue;
            String by = by(text), script = scriptName(e);
            if (by.isEmpty()) {
                out.add(sid + " 的证据没写是谁跑出来的（老文件）：分不清是脚本还是探针");
            } else if (!by.equals(script)) {
                out.add(sid + " 的证据是 " + by + " 跑出来的，不是场景脚本 " + script
                        + "：行为验过了，可这个脚本自己还没跑通过（sh tests/agent/verify.sh --run --only " + sid
                        + " 能重跑它）");
            }
        }
        return out;
    }

    /** The same rule on a tree: its registry and the {@code evidence/real-*.json} one level under
     * {@code tests/agent/evidence/}. Reads the root it is given and nothing else. */
    static List<String> probeMadeOnTree(Path root) {
        return probeMade(read(root.resolve("tests").resolve("agent").resolve("realpath").resolve("scenarios.json")),
                evidenceTextOnTree(root));
    }
}
