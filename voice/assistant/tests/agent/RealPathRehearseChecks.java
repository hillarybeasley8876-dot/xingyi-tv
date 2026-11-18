package xyz.erapi0neer.xingyi.agent;

import static xyz.erapi0neer.xingyi.agent.RealPathChecks.*;
import static xyz.erapi0neer.xingyi.agent.RealPathEvidenceChecks.*;
import static xyz.erapi0neer.xingyi.agent.RealPathLedgerChecks.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/**
 * real-path: the rule of check.py's {@code rehearse_problems()} — every scenario in the registry has cases in
 * {@code tests/agent/realpath/rehearse.py}, and both sides of it: one that has to pass and one that has to not.
 *
 * <p>2026-11-14: the judges had never been shown to be able to go red. hint-card-not-a-switch (11-12 22:47) read a key
 * the probe never carried, got {@code None} on both sides, and could not have passed however the TV behaved — a hundred
 * runs of it would have stayed green. The other direction is just as quiet: an {@code ok = True} judge passes once and
 * is never touched again. So 「至少一条」 is not enough on its own — one {@code expect: True} case proves the judge says
 * yes and says nothing about whether it can ever say no.
 *
 * <p>What is checked here is the case table, as text: names unique, {@code expect} one of the three words, a case
 * pointing at a scenario the registry really has, and both sides present for every scenario. Nothing is exec'd and
 * nothing is imported — running a scenario's judge is {@code rehearse.py}'s own business, offline and against a fake
 * probe. A rehearse.py that cannot be read is one sentence and nothing else: a rule that cannot read the file does not
 * get to guess which scenario is short of cases.
 *
 * <p>Called by {@link RealPathChecks#run()}. Until 2026-11-14 this lived in {@link RealPathLedgerChecks}, which is a
 * different subject: whether the registry and the ledger say the same thing.
 */
final class RealPathRehearseChecks {
    private RealPathRehearseChecks() { }

    static void run() throws Exception {
        rehearseCoverage();
    }

    // ---- 有条目就得有条局面：每条场景的判据，正反两面都得在离线被证明过一次 --------------------------------------

    private static final String NO_CASE = "没有离线局面";
    private static final String ONLY_PASS = "只有「该过」的局面";
    private static final String ONLY_FAIL = "只有「该不过」的局面";

    /** One case of rehearse.py, written the way that file says to: the three keys next to each other. */
    private static String c(String sid, String name, String expect) {
        return "    {\"scenario\": \"" + sid + "\", \"name\": \"" + name + "\", \"expect\": " + expect + ",\n"
                + "     \"facts\": {\"resumed_seq\": [\"x\"]}}";
    }

    private static String cases(String... cs) {
        StringBuilder b = new StringBuilder("CASES = [\n");
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) b.append(",\n");
            b.append(cs[i]);
        }
        return b.append("\n]\n").toString();
    }

    /** Both sides of one scenario's judge. */
    private static String both(String sid) {
        return cases(c(sid, "该过", "True"), c(sid, "该不过", "False"));
    }

    private static String reg2() {
        return reg(one(NAME, "说话时逐字上屏", "[\"亮屏\"]", SCRIPT, "2", ""),
                one(NEVER, "面板自检", "[\"亮屏\"]", SCRIPT, "2", ""));
    }

    private static void rehearseCoverage() throws Exception {
        String reg1 = reg(one(NAME, "说话时逐字上屏", "[\"亮屏\"]", SCRIPT, "2", ""));

        quiet(rehearse(reg1, both(NAME)), "一条场景，正反两面各有局面：一句都不说");
        quiet(rehearse(reg1, cases(c(NAME, "该过", "True"), c(NAME, "验不了", "None"))),
                "一面 True、一面 None（这次验不了）：也一句都不说");

        List<String> none = rehearse(reg1, cases());
        problem(none, NO_CASE, "一条场景，局面表里一条都没有：有一句");
        problem(none, NAME, "同一句话点名是哪条场景没有局面");
        problem(none, "rehearse.py", "同一句话说清去哪个文件里加（实际 " + none + "）");
        problem(none, "正反两面", "同一句话说的是正反两面都没被证明过（实际 " + none + "）");

        // 缺一个点名一个：有局面的那条一句都不说
        List<String> two = rehearse(reg2(), both(NAME));
        check(two.size() == 1, "两条场景只给一条写了局面：只有一句（实际 " + two.size() + " 句 " + two + "）");
        problem(two, NEVER, "那一句点名缺局面的那条场景");
        check(!two.isEmpty() && !two.get(0).contains(NAME),
                "有局面的那条一句都不说（实际 " + two + "）");

        List<String> pass = rehearse(reg1, cases(c(NAME, "该过", "True")));
        problem(pass, ONLY_PASS, "只有「该过」的局面：有一句，说判据红的那一面没被证明过");
        check(pass.size() == 1 && pass.get(0).contains(NAME) && pass.get(0).contains("False"),
                "这句话点名场景，还说清要加一条什么样的（实际 " + pass + "）");
        List<String> fail = rehearse(reg1, cases(c(NAME, "该不过", "False")));
        problem(fail, ONLY_FAIL, "只有「该不过」的局面：有一句，说判据绿的那一面没被证明过");
        check(fail.size() == 1 && fail.get(0).contains(NAME) && fail.get(0).contains("True"),
                "这句话点名场景，还说清要加一条 expect: True 的（实际 " + fail + "）");

        // rehearse.py 不在：只说一句读不出来，不去猜哪条场景缺局面
        List<String> gone = rehearse(reg2(), null);
        problem(gone, "读不出来", "rehearse.py 不在：有一句说读不出来，没有悄悄放过");
        check(gone.size() == 1 && !gone.get(0).contains(NAME) && !gone.get(0).contains(NEVER),
                "读不出来的时候只有那一句：不去猜哪条场景缺局面（实际 " + gone + "）");

        // 局面名唯一；expect 只能是 True / False / None；局面得指着注册表真有的场景
        problem(rehearse(reg1, cases(c(NAME, "重名", "True"), c(NAME, "重名", "False"))), "重名",
                "两条局面用了同一个名字：有一句，点名那个名字");
        List<String> bad = rehearse(reg1, cases(c(NAME, "该过", "True"), c(NAME, "写坏了", "1")));
        problem(bad, "1", "有一条局面的 expect 是 1：有一句，点名看到的那个词");
        check(bad.size() == 1 && bad.get(0).contains("写坏了") && bad.get(0).contains("True / False / None"),
                "这句话点名是哪条局面，还说清只能写哪三个词（实际 " + bad + "）");
        problem(rehearse(reg1, cases(c(NAME, "该过", "True"), c(NAME, "该不过", "False"),
                c("nope", "给不存在的场景", "True"))), "nope",
                "有一条局面指着注册表里没有的场景：有一句，点名那个 id");

        // 扫不到别的写法：三个键不挨在一起，就当成没有局面
        String odd = "CASES = [\n    {\"scenario\": \"" + NAME + "\", \"facts\": {},\n"
                + "     \"name\": \"该过\", \"expect\": True},\n]\n";
        problem(rehearse(reg1, odd), NO_CASE, "三个键没挨在一起：扫不到就当成没有局面（不猜哪条字典是局面）");
        check(rehearse("这不是 JSON", both(NAME)).isEmpty(),
                "注册表读不出来：这条规矩一句都不抢（registry() 已经说了）");

        // 是问题，不是提醒：不加 --strict 也算一处不对
        check(failures(rehearse(reg1, cases()), new ArrayList<String>(), false) == 1,
                "这是问题不是提醒：不加 --strict 也算一处不对");

        rehearseTree();
        realTreeRehearse();
    }

    // ---- 一棵树：读的是给它的那个 root；仓库里那一份，十条场景正反两面都在 ------------------------------------------

    private static void rehearseTree() throws Exception {
        Path fix = Files.createTempDirectory("realpath-rehearse");
        try {
            Path dir = fix.resolve("tests/agent/realpath");
            Files.createDirectories(dir);
            Files.write(dir.resolve("scenarios.json"), reg2().getBytes(StandardCharsets.UTF_8));
            problem(rehearseOnTree(fix), "读不出来", "一棵临时的树，rehearse.py 不在：有一句说读不出来");
            Files.write(dir.resolve("rehearse.py"), cases().getBytes(StandardCharsets.UTF_8));
            check(rehearseOnTree(fix).size() == 2, "两条场景一条局面都没有：各得一句（实际 "
                    + rehearseOnTree(fix) + "）");
            Files.write(dir.resolve("rehearse.py"),
                    cases(c(NAME, "甲该过", "True"), c(NAME, "甲该不过", "False"),
                            c(NEVER, "乙该过", "True"), c(NEVER, "乙验不了", "None"))
                            .getBytes(StandardCharsets.UTF_8));
            quiet(rehearseOnTree(fix), "同一棵树补上正反两面：一句都不说");
            check(todoOnTree(Paths.get("")).isEmpty() && rehearseOnTree(Paths.get("")).isEmpty(),
                    "同一时刻真实树还是干净的：fixture 的临时树没有被当成真树");
        } finally {
            delete(fix.toFile());
        }
    }

    private static void realTreeRehearse() throws Exception {
        String text = read(Paths.get("tests/agent/realpath/rehearse.py"));
        check(text != null, "仓库里有 tests/agent/realpath/rehearse.py（这条检查不是空跑）");
        Matcher m = CASE.matcher(text == null ? "" : text);
        Map<String, Set<String>> sides = new LinkedHashMap<>();
        int n = 0;
        while (m.find()) {
            n++;
            Set<String> w = sides.get(m.group(1));
            if (w == null) sides.put(m.group(1), w = new HashSet<>());
            w.add(m.group(3));
        }
        check(n >= 20, "真树的局面表扫到 " + n + " 条局面（十条场景每条至少正反两条，至少 20 条）");
        JSONArray shipped = new JSONObject(shippedJson()).getJSONArray("scenarios");
        // at least the ten of 2026-11-14 (a scenario added is no failure; the ids below say none went missing)
        check(shipped.length() >= 10, "注册表里至少还是那十个场景 " + shipped.length());
        List<String> short_ = new ArrayList<>();
        for (int i = 0; i < shipped.length(); i++) {
            String id = shipped.getJSONObject(i).optString("id", "");
            Set<String> w = sides.get(id);
            if (w == null || !w.contains("True") || !(w.contains("False") || w.contains("None"))) short_.add(id);
        }
        check(short_.isEmpty(), "十条场景每一条都既有 expect: True 也有 expect: False / None 的局面（缺的是 "
                + short_ + "；扫到的 " + sides + "）");
        quiet(rehearseOnTree(Paths.get("")),
                "仓库里真正那一份：局面表和注册表说的是同一件事");
    }

    // ---- 有条目就得有条局面：规矩本身 ---------------------------------------------------------------------------

    /** A case of rehearse.py, as that file documents it: {@code "scenario"}, {@code "name"} and {@code "expect"} next
     * to each other. Scanned as text — nothing is exec'd and nothing is imported; a scenario's judge is run by
     * rehearse.py, never by this. */
    private static final Pattern CASE = Pattern.compile("\"scenario\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
            + "\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"expect\"\\s*:\\s*([^,\\s\\]}]+)");
    /** Every {@code "expect":} in the file, well-formed case or not — a token nobody may write has to be seen. */
    private static final Pattern EXPECT = Pattern.compile("\"expect\"\\s*:\\s*([^,\\s\\]}]+)");
    private static final List<String> TOKENS = Arrays.asList("True", "False", "None");
    private static final String REHEARSE_UNREADABLE = "tests/agent/realpath/rehearse.py 读不出来（不在或者读不了），"
            + "没法核对哪条场景有没有离线局面";

    /**
     * The rule of check.py's {@code rehearse_problems()}: every scenario in the registry has at least one case in
     * {@code tests/agent/realpath/rehearse.py}, and both sides of it — one that has to pass and one that has to not.
     * Case names have to be unique and {@code expect} has to be True / False / None, so a report can point at one
     * case. Problems, not notices — {@code --strict} or not, they count.
     *
     * <p>2026-11-14: the judges had never been shown to be able to go red. hint-card-not-a-switch (11-12 22:47) read a
     * key the probe never carried, got {@code None} on both sides, and could not have passed however the TV behaved —
     * a hundred runs of it would have stayed green, and the other direction is just as quiet: an {@code ok = True}
     * judge passes once and is never touched again. 「至少一条」 is not enough on its own: one {@code expect: True}
     * case proves the judge says yes and says nothing about whether it can ever say no, so both sides are asked for.
     *
     * <p>{@code rehearseText} null (the file is not there, or cannot be read) is one sentence and nothing else: a rule
     * that cannot read the file does not get to guess which scenario is short of cases. A registry that cannot be read
     * is {@link RealPathChecks#registry}'s business — then only the case-table rules here run. Only the one shape
     * rehearse.py documents is seen; a case written any other way counts as no case at all, because guessing which
     * dict is a case is how a rule stops meaning anything.
     */
    static List<String> rehearse(String registryJson, String rehearseText) {
        if (rehearseText == null) return one(REHEARSE_UNREADABLE);
        List<String[]> cases = new ArrayList<>();               // scenario, case name, the expect word
        Map<Integer, String> named = new HashMap<>();
        Matcher m = CASE.matcher(rehearseText);
        while (m.find()) {
            cases.add(new String[]{m.group(1), m.group(2), m.group(3)});
            named.put(m.start(3), m.group(2));
        }
        List<String> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        JSONArray entries = null;
        try {
            entries = new JSONObject(registryJson == null ? "" : registryJson).optJSONArray("scenarios");
        } catch (JSONException e) {
            entries = null;
        }
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.optJSONObject(i);
                if (e == null) continue;
                String sid = str(e.opt("id"));
                if (!sid.isEmpty()) ids.add(sid);              // registry() already says what is wrong with the rest
            }
            for (String sid : ids) out.addAll(sides(sid, cases));
        }
        Set<String> seen = new HashSet<>();
        for (String[] c : cases) {
            if (!seen.add(c[1]))
                out.add("局面名「" + c[1] + "」和前面的一条一样（每条局面要自己的名字，报告里要能点名）");
        }
        Matcher bad = EXPECT.matcher(rehearseText);
        while (bad.find()) {
            String token = bad.group(1);
            if (TOKENS.contains(token)) continue;
            String who = named.get(bad.start(1));
            out.add("有一条局面的 expect 是「" + token + "」" + (who == null ? "" : "（局面「" + who + "」）")
                    + "：只能是 True / False / None");
        }
        if (entries != null) {
            for (String[] c : cases) {
                if (!ids.contains(c[0]))
                    out.add("局面「" + c[1] + "」指着场景「" + c[0] + "」，可 scenarios.json 里没有这个场景"
                            + "（局面表和注册表得说同一件事）");
            }
        }
        return out;
    }

    /** What one scenario's cases do and do not prove; one sentence at most, and it names the scenario. */
    private static List<String> sides(String sid, List<String[]> cases) {
        List<String> out = new ArrayList<>();
        Set<String> words = new HashSet<>();
        for (String[] c : cases) if (sid.equals(c[0])) words.add(c[2]);
        if (words.isEmpty()) {
            out.add(sid + " 没有离线局面：判据正反两面都没被证明过（tests/agent/realpath/rehearse.py 里加几条："
                    + "一条 expect: True、一条 expect: False 或 None）");
            return out;
        }
        for (String w : words) if (!TOKENS.contains(w)) return out;   // expect 写坏了：那句话已经说了
        if (!words.contains("True")) {
            out.add(sid + " 只有「该不过」的局面，没有「该过」的：判据绿的那一面没被证明过"
                    + "（tests/agent/realpath/rehearse.py 里加一条 expect: True 的）");
        } else if (!words.contains("False") && !words.contains("None")) {
            out.add(sid + " 只有「该过」的局面，没有「该不过」的：判据红的那一面没被证明过"
                    + "（tests/agent/realpath/rehearse.py 里加一条 expect: False 或 None 的）");
        }
        return out;
    }

    /** The same rule on a tree: its registry and its rehearse.py. Reads the root it is given and nothing else — a
     * fixture tree is never mistaken for the repository. */
    static List<String> rehearseOnTree(Path root) {
        Path dir = root.resolve("tests").resolve("agent").resolve("realpath");
        return rehearse(read(dir.resolve("scenarios.json")), read(dir.resolve("rehearse.py")));
    }
}
