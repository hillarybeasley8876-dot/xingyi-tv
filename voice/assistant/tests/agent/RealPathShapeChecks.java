package xyz.erapi0neer.xingyi.agent;

import static xyz.erapi0neer.xingyi.agent.RealPathChecks.*;
import static xyz.erapi0neer.xingyi.agent.RealPathEvidenceChecks.*;
import static xyz.erapi0neer.xingyi.agent.RealPathLedgerChecks.*;
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
final class RealPathShapeChecks {
    private RealPathShapeChecks() { }

    static void run() throws Exception {
        payloadShapes();
        shapeTrees();
        realTreeShapes();
    }

    // ---- 场景从广播里读的键：读的每个键都得在真机形状里 ------------------------------------------------------------

    /** A shape fixture the way the lead's recording looks: the keys each broadcast carries, values null, no content. */
    private static final String SHAPES = "{\"STATE\":{\"awaiting\":null,\"busy\":null,\"task\":{\"id\":null},\"warm\":null},"
            + "\"MEMORY\":{\"facts\":[null],\"hints\":null,\"notes\":[]}}";
    private static final String SCEN = "tests/agent/realpath/scenarios/x.py";
    private static final String BUSY = "def check():\n    return p.state().get(\"busy\")\n";
    private static final String HINTS = "def hints():\n    return p.memory().get(\"hints\")\n";
    private static final String NOPE = "def check():\n    return p.state().get(\"nope\")\n";

    private static Map<String, String> script(String name, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(name, text);
        return m;
    }

    private static void payloadShapes() {
        quiet(shape(SHAPES, script(SCEN, BUSY)), "从 STATE 里读一个真机带的键（busy）：一句都不说");
        quiet(shape(SHAPES, script(SCEN, HINTS)),
                "从 MEMORY 里读 hints —— 2026-11-12 22:47 那条场景现在读的就是这一路：一句都不说");

        List<String> wrong = shape(SHAPES, script(SCEN, "def hints():\n    return p.state().get(\"hints\")\n"));
        problem(wrong, "hints", "从 STATE 里读 hints（那一路从来不带它）：有一句，点名这个键");
        problem(wrong, "x.py", "同一句话点名是哪个脚本在读");
        problem(wrong, "STATE", "同一句话点名是哪一路广播");
        check(wrong.size() == 1 && wrong.get(0).contains("awaiting") && wrong.get(0).contains("warm")
                        && wrong.get(0).contains("、"),
                "同一句话里列出真机形状真的有的那几个键（实际 " + wrong + "）");
        problem(shape(SHAPES, script(SCEN, "def check():\n    return p.memory().get(\"nope\")\n")), "MEMORY",
                "从 MEMORY 里读一个不存在的键：有一句，点名 MEMORY");

        // 三种写法都认：读得对的键一句不说，读不到的键每种写法都被点出来
        quiet(shape(SHAPES, script(SCEN, "x = p.state().get(\"busy\")\n")), "写法 .get(\"busy\")：一句都不说");
        quiet(shape(SHAPES, script(SCEN, "x = p.state()[\"busy\"]\n")), "写法 [\"busy\"]：一句都不说");
        quiet(shape(SHAPES, script(SCEN, "x = p.state().get( \"busy\" )\n")), "写法 .get( \"busy\" )：一句都不说");
        problem(shape(SHAPES, script(SCEN, "x = p.state().get(\"nope\")\n")), "nope", "写法 .get(\"nope\")：被点出来");
        problem(shape(SHAPES, script(SCEN, "x = p.state()[\"nope\"]\n")), "nope", "写法 [\"nope\"]：被点出来");
        problem(shape(SHAPES, script(SCEN, "x = p.state().get( \"nope\" )\n")), "nope",
                "写法 .get( \"nope\" )（带空格）：被点出来");
        problem(shape(SHAPES, script(SCEN, "x = p.state()\n        .get(\"nope\")\n")), "nope",
                "中间换了行：照样被点出来");
        problem(shape(SHAPES, script(SCEN, "x = p.memory().get('nope')\n")), "nope", "单引号：照样被点出来");
        quiet(shape(SHAPES, script(SCEN, "x = p.state().get(\"task\")[\"id\"]\n")),
                "顶层键 task 在形状里，后面的 [\"id\"] 是它里面的：只核对顶层那一个键");

        // 形状读不出来：一句说读不出来，不抛异常，也不去猜哪个键不对
        problem(shape(null, script(SCEN, NOPE)), "读不出来", "形状文件不在：有一句说读不出来，没有抛异常");
        problem(shape("这不是 JSON", script(SCEN, NOPE)), "读不出来", "形状文件不是 JSON：有一句说读不出来");
        problem(shape("[]", script(SCEN, NOPE)), "读不出来", "形状文件是一个数组不是对象：也是读不出来");
        check(shape(null, script(SCEN, NOPE)).size() == 1,
                "读不出来的时候只有那一句：不去猜哪个键不对，也不把脚本一句一句数下去");
        problem(shape("{\"STATE\":{\"busy\":null}}", script(SCEN, HINTS)), "这一路一个键都没有",
                "形状里根本没有 MEMORY 这一路：有一句，说这一路一个键都没有");

        // 不碰这两路广播的脚本，和写明了不做的变量再读：都一句都不说
        quiet(shape(SHAPES, script(SCEN, "def main():\n    return 0\n")), "脚本里根本不碰这两路广播：一句都不说");
        quiet(shape(SHAPES, script(SCEN, "def check():\n    s = p.state()\n    return s.get(\"nope\")\n")),
                "变量再读（s = p.state()，然后 s.get(\"nope\")）：一句都不说 —— 写明了不做，就不假装做了");

        // 一条错误只报一次；两个场景各读同一个不存在的键，各点名自己
        List<String> twice = shape(SHAPES, script(SCEN, "a = p.state().get(\"nope\")\nb = p.state().get(\"nope\")\n"));
        check(twice.size() == 1, "同一个脚本里同一个键读两次：只报一句（实际 " + twice.size() + " 句 " + twice + "）");
        Map<String, String> two = new LinkedHashMap<>();
        two.put("tests/agent/realpath/scenarios/a.py", "x = p.state().get(\"nope\")\n");
        two.put("tests/agent/realpath/scenarios/b.py", "x = p.state().get(\"nope\")\n");
        List<String> both = shape(SHAPES, two);
        check(both.size() == 2, "两个场景读同一个不存在的键：各得一句（实际 " + both.size() + " 句 " + both + "）");
        check(both.size() == 2 && both.get(0).contains("a.py") && both.get(1).contains("b.py"),
                "两句各点名自己的脚本（实际 " + both + "）");

        // 是问题，不是提醒：不加 --strict 也算一处不对
        check(failures(shape(SHAPES, script(SCEN, NOPE)), new ArrayList<String>(), false) == 1,
                "这是问题不是提醒：不加 --strict 也算一处不对");
    }
    // ---- 一棵树：没有形状可比的时候规则不乱说，有形状的时候它真的跑 ------------------------------------------------

    private static void shapeTrees() throws Exception {
        Path bare = Files.createTempDirectory("realpath-shapes");
        try {
            Files.createDirectories(bare.resolve("tests/agent/realpath/scenarios"));
            Files.write(bare.resolve("tests/agent/realpath/scenarios/x.py"),
                    NOPE.getBytes(StandardCharsets.UTF_8));
            quiet(shapeOnTree(bare), "一棵树里没有那份形状 fixture：规则一句都不说（没有形状可比，不是场景错了）");
            check(SHAPE_UNREADABLE.equals(shapeMissing(bare)),
                    "同一棵树：shapeMissing 说那一句原文（实际 " + shapeMissing(bare) + "）");
            check(failures(shapeOnTree(bare), new ArrayList<String>(), true) == 0,
                    "这句读不出来不算问题：--strict 也是 0 处（它说的是这棵树拿到了什么，不是哪条场景错了）");

            Path dir = bare.resolve("tests/agent/fixtures/realpath");
            Files.createDirectories(dir);
            Files.write(dir.resolve("payload-shapes.json"), SHAPES.getBytes(StandardCharsets.UTF_8));
            problem(shapeOnTree(bare), "nope", "同一棵树放上形状 fixture：规则当场跑起来，把读不到的键点出来");
            check(shapeOnTree(bare).size() == 1 && shapeOnTree(bare).get(0).contains("x.py"),
                    "这句话点名树里那个脚本（实际 " + shapeOnTree(bare) + "）");
            check(shapeMissing(bare) == null, "形状 fixture 在：shapeMissing 一句都不说");
            Files.write(bare.resolve("tests/agent/realpath/scenarios/x.py"),
                    BUSY.getBytes(StandardCharsets.UTF_8));
            quiet(shapeOnTree(bare), "同一棵树里那个脚本改读真机带的键：一句都不说");
            Files.write(dir.resolve("payload-shapes.json"), "这不是 JSON".getBytes(StandardCharsets.UTF_8));
            quiet(shapeOnTree(bare), "形状 fixture 坏了（不是 JSON）：也一句都不说");
            check(SHAPE_UNREADABLE.equals(shapeMissing(bare)), "形状 fixture 坏了：shapeMissing 还是那一句");
        } finally {
            delete(bare.toFile());
        }
    }

    // ---- 仓库里真正那一份：每个场景读的键都在真机形状里 ----------------------------------------------------------

    private static void realTreeShapes() throws Exception {
        String who = "tests/agent/realpath/scenarios/hint-card-not-a-switch.py";
        String scenario = read(Paths.get(who));
        check(scenario != null && scenario.contains("p.memory().get(\"hints\")"),
                "真树的 hint-card-not-a-switch 真的从 p.memory() 里读 hints（这条检查不是空跑）");
        // 不管这棵树里有没有那份形状 fixture，扫的是真树上这份脚本，改回 STATE 就得当场被点出来
        problem(shape(SHAPES, script(who, scenario == null ? "" : scenario.replace("p.memory()", "p.state()"))),
                "hints", "同一份脚本改回 p.state()：当场被点出来 —— 扫的确实是真树上那份脚本");

        Path root = Paths.get("");
        String json = shapeJson(root);
        if (json == null) {
            quiet(shapeOnTree(root), "这棵树里没有那份形状 fixture：规则一句都不说");
            check(SHAPE_UNREADABLE.equals(shapeMissing(root)),
                    "同一棵树：shapeMissing 说一句读不出来，规则跑没跑是明说的（实际 " + shapeMissing(root) + "）");
            return;
        }
        JSONObject shapes = new JSONObject(json);
        check(broadcastKeys(shapes, "MEMORY").contains("hints") && !broadcastKeys(shapes, "STATE").contains("hints"),
                "真机形状里 hints 在 MEMORY、不在 STATE —— 2026-11-12 22:47 白跑的正是这一条（MEMORY "
                        + broadcastKeys(shapes, "MEMORY") + "）");
        check(shapeMissing(root) == null, "形状 fixture 在：shapeMissing 一句都不说");
        quiet(shapeOnTree(root), "仓库里真正那一份：每个场景读的键都在真机形状里");
        problem(shape(json, script(who, scenario == null ? "" : scenario.replace("p.memory()", "p.state()"))),
                "hints", "用真树上那份形状：同一句话照样点出来");
    }
    // ---- 场景从广播里读的键：真机带的那些键里有没有 ---------------------------------------------------------------

    /** {@code p.state()} / {@code p.memory()}, whitespace and newlines anywhere in between, then {@code .get("k")} or
     * {@code ["k"]} straight off it. Scanned as text: nothing is exec'd and nothing is imported. */
    private static final Pattern READ_KEY = Pattern.compile("p\\s*\\.\\s*(state|memory)\\s*\\(\\s*\\)\\s*"
            + "(?:\\.\\s*get\\s*\\(\\s*([\"'])([^\"']+)\\2\\s*\\)|\\[\\s*([\"'])([^\"']+)\\4\\s*\\])");

    private static final String SHAPE_UNREADABLE =
            "payload-shapes.json 读不出来（形状 fixture 不在或者不是 JSON），没法核对场景读的字段";

    /**
     * The rule of check.py's {@code shape_problems()}: every key a scenario reads straight off {@code p.state()} /
     * {@code p.memory()} has to be a top-level key that broadcast really carries. Problems, not notices —
     * {@code --strict} or not, they count.
     *
     * <p>{@code shapesJson} is tests/agent/fixtures/realpath/payload-shapes.json, the shapes (keys and container
     * shapes, no content) of the two debug broadcasts as captured on the TV; null or anything but a JSON object is
     * 「读不出来」 and one sentence says so instead of guessing. {@code scripts} maps a scenario's repo-relative path to
     * its text. On a whole tree that has no recording at all, {@link #shapeOnTree} steps aside instead of turning
     * 「I was given nothing」 into 「this tree is broken」 — {@link #shapeMissing} is what says it.
     *
     * <p>Out of scope, on purpose: a read through a variable ({@code s = p.state()} and then {@code s.get("x")}).
     * This is a text scan, not a parse — telling which variable holds which broadcast would be guessing, and a check
     * that guesses is worse than one that says nothing. So that read is not reported at all.
     */
    static List<String> shape(String shapesJson, Map<String, String> scripts) {
        JSONObject shapes = null;
        if (shapesJson != null) {
            try {
                shapes = new JSONObject(shapesJson);
            } catch (JSONException e) {
                shapes = null;
            }
        }
        if (shapes == null) return one(SHAPE_UNREADABLE);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : scripts.entrySet()) {
            String who = e.getKey();
            Set<String> seen = new HashSet<>();       // one sentence per script per key, however often it is read
            Matcher m = READ_KEY.matcher(e.getValue() == null ? "" : e.getValue());
            while (m.find()) {
                String which = m.group(1).toUpperCase(Locale.US);
                String key = m.group(3) != null ? m.group(3) : m.group(5);
                if (key == null || key.isEmpty() || !seen.add(which + ' ' + key)) continue;
                List<String> keys = broadcastKeys(shapes, which);
                if (keys.contains(key)) continue;
                out.add(who + "：从 " + which + " 里读「" + key + "」，可那个广播不带这个键（真机形状里"
                        + (keys.isEmpty() ? "这一路一个键都没有" : "只有 " + join(keys))
                        + "）；2026-11-12 22:47 就是这么白跑了一次");
            }
        }
        return out;
    }

    /** The top-level keys one broadcast really carries, sorted; empty when the fixture has no such broadcast. */
    private static List<String> broadcastKeys(JSONObject shapes, String which) {
        List<String> keys = new ArrayList<>();
        JSONObject d = shapes.optJSONObject(which);
        if (d == null) return keys;
        for (Iterator<String> it = d.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys);
        return keys;
    }

    private static String join(List<String> keys) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) b.append('、');
            b.append(keys.get(i));
        }
        return b.toString();
    }

    /** The same rule on a tree: its payload-shapes.json and every {@code *.py} under its scenarios/. Reads the root it
     * is given and nothing else — a fixture tree is never mistaken for the repository. Nothing comes back when the
     * shape recording is not in that tree: there is no shape to compare a key against, and a rule that could not run
     * is not evidence that a scenario is wrong. {@link #shapeMissing} is what says so. */
    static List<String> shapeOnTree(Path root) {
        String json = shapeJson(root);
        if (json == null) return new ArrayList<>();
        Path dir = root.resolve("tests").resolve("agent").resolve("realpath").resolve("scenarios");
        List<String> names = new ArrayList<>();
        File[] kids = dir.toFile().listFiles();
        if (kids != null) {
            for (File f : kids) if (f.isFile() && f.getName().endsWith(".py")) names.add(f.getName());
        }
        Collections.sort(names);
        Map<String, String> scripts = new LinkedHashMap<>();
        for (String n : names) scripts.put("tests/agent/realpath/scenarios/" + n, read(dir.resolve(n)));
        return shape(json, scripts);
    }

    /**
     * One line when the shape recording is not in this tree, so the rule above could not run at all; null when it is
     * there. Printed on every run of check.py and counted by nothing, {@code --strict} included: it is about what the
     * tree was given, not about a scenario, and the one thing that fixes it — recording the two broadcasts on the TV —
     * is not something a run of the checks can do. 2026-11-13: the recording was left untracked in one checkout, so
     * every other tree had no shape rule at all; this line is what keeps that visible.
     */
    static String shapeMissing(Path root) {
        return shapeJson(root) == null ? SHAPE_UNREADABLE : null;
    }

    /** The tree's payload-shapes.json, or null when it is not there or is not a JSON object. */
    private static String shapeJson(Path root) {
        String json = read(root.resolve("tests").resolve("agent").resolve("fixtures").resolve("realpath")
                .resolve("payload-shapes.json"));
        if (json == null) return null;
        try {
            new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
        return json;
    }
}
