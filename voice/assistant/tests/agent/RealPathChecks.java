package xyz.erapi0neer.xingyi.agent;

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
 * <p>This is the one entry point: {@link #run} hands the other topics to their own files —
 * {@link RealPathEvidenceChecks} (the shape of an evidence file, the misplaced WHERE lines and {@code --strict}),
 * {@link RealPathLedgerChecks} (the registry and the ledger saying the same thing), {@link RealPathProbeChecks} (who
 * wrote an evidence) and {@link RealPathShapeChecks} (the broadcast keys a scenario reads). They were four classes in
 * one 1338-line file until 2026-11-14; the split is here, and the shared tools below are the one copy they all use.
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
final class RealPathChecks {
    private RealPathChecks() { }

    // ---- 四个类共用的小工具：只有这一份 ----------------------------------------------------------------------

    static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    /** One broken rule: exactly one sentence, and it says the thing the rule is about. */
    static void problem(List<String> problems, String needle, String label) {
        check(problems.size() == 1, label + "：只有一句（实际 " + problems.size() + " 句 " + problems + "）");
        check(!problems.isEmpty() && problems.get(0).contains(needle),
                label + "：这句话里有「" + needle + "」（实际 " + problems + "）");
    }

    /** Nothing wrong: no sentence at all — and the label carries what was actually returned if that is not true. */
    static void quiet(List<String> problems, String label) {
        check(problems.isEmpty(), label + "（实际 " + problems + "）");
    }

    static String one(String id, String what, String needs, String script, String hours, String unverified) {
        return "{\"id\":\"" + id + "\",\"what\":\"" + what + "\",\"needs\":" + needs + ",\"script\":\"" + script
                + "\",\"hours\":" + hours + ",\"unverified\":\"" + unverified + "\"}";
    }

    static String reg(String... entries) {
        StringBuilder b = new StringBuilder("{\"scenarios\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) b.append(',');
            b.append(entries[i]);
        }
        return b.append("]}").toString();
    }

    static String ev(String id, String at, String ok, String notes) {
        return "{\"id\":\"" + id + "\",\"at\":\"" + at + "\",\"ok\":" + ok + ",\"notes\":" + notes + ",\"shots\":[]}";
    }

    static String shippedJson() throws Exception {
        return source("tests/agent/realpath/scenarios.json");
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    /** A JSON value as a string; anything that is not one is "" — it cannot be an id, a what or a script. */
    static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    static List<String> one(String sentence) {
        List<String> out = new ArrayList<>();
        out.add(sentence);
        return out;
    }

    /** The file's text, or null when it is not there (a directory, a missing path, an unreadable file). */
    static String read(Path p) {
        try {
            if (!Files.isRegularFile(p)) return null;
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
    // ---- 共用的常量 ------------------------------------------------------------------------------------

    /** ids are file names ({@code real-<id>.json}) and are read aloud in reports: kebab-case, nothing else. */
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    /** A scenario's own entry point. The text is read, not parsed — check.py parses it and says the same thing. */
    private static final Pattern MAIN = Pattern.compile("(?m)^def main\\s*\\(");
    static final String SCRIPT = "tests/agent/realpath/scenarios/x.py", NAME = "panel-while-speaking",
            WHEN = "2026-11-11T20:03:00+08:00", SCRIPT_TEXT = "def check():\n    return 0\n\n\ndef main():\n    return 0\n",
            UNVERIFIED = "{\"entries\":[{\"id\":\"panel-while-speaking\"},{\"id\":\"ktcp-pause-real\"}]}";
    /** 2026-11-12 noon, local: the day this job was written, so an {@code at} that has not come yet is caught. */
    static final long NOW = at(2026, 11, 12);

    private static long at(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 12, 0, 0);
        return c.getTimeInMillis();
    }

    static void run() throws Exception {
        Path tmp = Files.createTempDirectory("realpath-checks");
        try {
            Files.createDirectories(tmp.resolve("tests/agent/realpath/scenarios"));
            Files.write(tmp.resolve(SCRIPT), SCRIPT_TEXT.getBytes(StandardCharsets.UTF_8));
            sound(tmp);
            brokenRegistry(tmp);
            RealPathEvidenceChecks.run();
            RealPathLedgerChecks.run();
            RealPathRehearseChecks.run();
            RealPathProbeChecks.run();
            newScenarios();
        } finally {
            delete(tmp.toFile());
        }
    }

    // ---- 一份没毛病的注册表，和仓库里真正在用的那一份 ----------------------------------------------------------

    private static void sound(Path root) throws Exception {
        List<String> none = registry(reg(one(NAME, "说话时逐字上屏", "[\"亮屏\"]", SCRIPT, "2", "")), root);
        check(none.isEmpty(), "一条本身没毛病的场景，problems 一句都不说（实际：" + none + "）");
        List<String> shipped = registry(shippedJson(), Paths.get(""));
        check(shipped.isEmpty(), "仓库里的 tests/agent/realpath/scenarios.json 是干净的（实际：" + shipped + "）");
        JSONArray entries = new JSONObject(shippedJson()).getJSONArray("scenarios");
        // at least the ten of 2026-11-14; the ids below say none of them went missing
        check(entries.length() >= 10, "仓库里那份至少记着那十个场景 " + entries.length());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) ids.add(entries.getJSONObject(i).optString("id", ""));
        check(ids.contains("panel-while-speaking") && ids.contains("panel-self-check") && ids.contains("black-screen-home")
                        && ids.contains("hint-card-not-a-switch") && ids.contains("episode-open")
                        && ids.contains("pause-through-xiaoai") && ids.contains("karaoke-switch"),
                "七个老场景的 id 都还在：说话时的面板、面板自检、黑屏回桌面、提示卡不是开关、开第5集、对小爱说暂停、伴奏/原唱切换");
        check(ids.contains("seek-one-minute") && ids.contains("standby-home") && ids.contains("sleeping-episode"),
                "三个新场景的 id 都在：快进一分钟、熄屏后守护把桌面放回去、待机时开第5集（实际 " + ids + "）");
    }

    // ---- 每条坏规矩，各一句说清它坏在哪 --------------------------------------------------------------------------

    private static void brokenRegistry(Path root) throws Exception {
        problem(registry("这不是 JSON", root), "读不出来", "整份文件不是 JSON，只有一句话说读不出来");
        problem(registry("{}", root), "读不出来", "没有 scenarios 数组");
        problem(registry("{\"scenarios\":[1]}", root), "不是一条记录", "scenarios 里有一条不是对象的，被指着位置说出来");
        problem(registry(reg(one("", "有", "[\"亮屏\"]", SCRIPT, "2", "")), root), "id 是空的", "id 是空的");
        problem(registry(reg(one("Panel_While", "有", "[\"亮屏\"]", SCRIPT, "2", "")), root), "不是 kebab-case",
                "id 不是 kebab-case（只小写字母、数字和中划线）");
        problem(registry(reg(one(NAME, "有", "[\"亮屏\"]", SCRIPT, "2", ""),
                one(NAME, "有", "[\"亮屏\"]", SCRIPT, "2", "")), root), "和前面的一条一样", "两个场景用了同一个 id");
        problem(registry(reg(one(NAME, "  ", "[\"亮屏\"]", SCRIPT, "2", "")), root), "what 是空的", "what 是空的");
        problem(registry(reg(one(NAME, "一行\\n两行", "[\"亮屏\"]", SCRIPT, "2", "")), root), "不止一行",
                "what 不止一行（要一句话）");
        problem(registry(reg(one(NAME, "有", "\"亮屏\"", SCRIPT, "2", "")), root), "needs 不是数组",
                "needs 不是数组（前置条件要写出来）");
        problem(registry(reg(one(NAME, "有", "[]", SCRIPT, "2", "")), root), "needs 不是数组", "needs 是空的数组");
        problem(registry(reg(one(NAME, "有", "[\"亮屏\",3]", SCRIPT, "2", "")), root), "needs 里有一条",
                "needs 里有一条不是一句非空的话");
        problem(registry(reg(one(NAME, "有", "[\"亮屏\"]", SCRIPT, "0", "")), root), "hours", "hours 是 0");
        problem(registry(reg(one(NAME, "有", "[\"亮屏\"]", SCRIPT, "11", "")), root), "hours", "hours 是 11");
        problem(registry(reg(one(NAME, "有", "[\"亮屏\"]", SCRIPT, "\"3\"", "")), root), "hours", "hours 是字符串");
        problem(registry(reg(one(NAME, "有", "[\"亮屏\"]", SCRIPT, "true", "")), root), "hours", "hours 是 true");
        problem(registry("{\"scenarios\":[{\"id\":\"" + NAME + "\",\"what\":\"有\",\"needs\":[\"亮屏\"],\"script\":\""
                + SCRIPT + "\",\"hours\":2,\"unverified\":3}]}", root), "unverified 不是字符串",
                "unverified 是数字，不是字符串（可以是空串）");
        problem(registry(reg(one(NAME, "有", "[\"亮屏\"]", SCRIPT, "2", "X Y")), root), "不是 kebab-case",
                "unverified 不是 kebab-case");
        problem(registry(reg(one(NAME, "有", "[\"亮屏\"]", "", "2", "")), root), "script 是空的", "script 是空的");
        problem(registry(reg(one(NAME, "有", "[\"亮屏\"]", "tests/agent/realpath/scenarios/nope.py", "2", "")), root),
                "在仓库里没有", "script 指的文件在仓库里没有");
        Path noMain = Files.createTempDirectory("realpath-nomain");
        try {
            Files.createDirectories(noMain.resolve("tests/agent/realpath/scenarios"));
            Files.write(noMain.resolve(SCRIPT), "def check():\n    return 0\n".getBytes(StandardCharsets.UTF_8));
            problem(registry(reg(one(NAME, "有", "[\"亮屏\"]", SCRIPT, "2", "")), noMain), "没有 main()",
                    "script 里有别的函数，可没有 main()（跑不起来的场景不算场景）");
        } finally {
            delete(noMain.toFile());
        }
    }
    // ---- 三个新场景：脚本在、有 main()、写着自己的 ID，注册表也指着它 -------------------------------------------

    private static final String[][] NEW = {
            {"seek-one-minute", "3", "亮屏、桌面在前、20 分钟没人动"},
            {"standby-home", "2", "亮屏、桌面在前、20 分钟没人动"},
            {"sleeping-episode", "2", "待机"}};

    private static void newScenarios() throws Exception {
        JSONArray entries = new JSONObject(shippedJson()).getJSONArray("scenarios");
        for (String[] w : NEW) {
            String id = w[0], script = "tests/agent/realpath/scenarios/" + id + ".py";
            String text = read(Paths.get(script));
            check(text != null, "新场景 " + id + "：脚本 " + script + " 在仓库里");
            if (text == null) continue;
            check(MAIN.matcher(text).find(), "新场景 " + id + "：脚本里有 main()（跑不起来的场景不算场景）");
            check(text.contains("ID = \"" + id + "\""),
                    "新场景 " + id + "：脚本里写着 ID = \"" + id + "\"，和注册表里的 id 一样");
            JSONObject e = entry(entries, id);
            check(e != null && script.equals(e.optString("script", "")),
                    "新场景 " + id + "：注册表的 script 指着这个文件（实际 "
                            + (e == null ? "注册表里没有这条" : e.optString("script", "")) + "）");
            check(e != null && e.optInt("hours", -1) == Integer.parseInt(w[1])
                            && w[2].equals(needs(e)),
                    "新场景 " + id + "：注册表里 hours " + w[1] + "、needs 是「" + w[2] + "」（实际 "
                            + (e == null ? "没有这条" : e.opt("hours") + " / " + needs(e)) + "）");
            check(e != null && "".equals(e.optString("unverified", "x")),
                    "新场景 " + id + "：unverified 是空的（还没在真机上验过，没有待办可指）");
        }
    }

    private static JSONObject entry(JSONArray entries, String id) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e != null && id.equals(e.optString("id", ""))) return e;
        }
        return null;
    }

    private static String needs(JSONObject e) {
        JSONArray a = e.optJSONArray("needs");
        if (a == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) b.append('、');
            b.append(str(a.opt(i)));
        }
        return b.toString();
    }

    // ---- 规矩本身：哪里不对，一句一句说清楚；没问题就一句都不说 --------------------------------------------------

    private static final String UNREADABLE = "scenarios.json 读不出来（要是一个 JSON，里面有 scenarios 数组）";

    /** Everything wrong with scenarios.json, as sentences; empty when it is sound. */
    static List<String> registry(String json, Path root) {
        List<String> out = new ArrayList<>();
        JSONArray entries;
        try {
            entries = new JSONObject(json).optJSONArray("scenarios");
        } catch (JSONException e) {
            out.add(UNREADABLE);
            return out;
        }
        if (entries == null) {
            out.add(UNREADABLE);
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) {
                out.add("第 " + (i + 1) + " 个场景：不是一条记录（scenarios 里要都是对象）");
                continue;
            }
            scenario(out, e, i, root, seen);
        }
        return out;
    }

    private static void scenario(List<String> out, JSONObject e, int i, Path root, Set<String> seen) {
        String id = str(e.opt("id"));
        if (id.isEmpty()) {
            out.add("第 " + (i + 1) + " 个场景：id 是空的");
            return;
        }
        String who = id;
        if (!ID.matcher(id).matches()) out.add(who + "：id 不是 kebab-case（只小写字母、数字和中划线）");
        else if (!seen.add(id)) out.add(who + "：id 和前面的一条一样（每个场景要自己的 id）");
        String what = str(e.opt("what"));
        if (what.trim().isEmpty()) out.add(who + "：what 是空的（这个场景验什么，要写一句）");
        else if (what.trim().indexOf('\n') >= 0) out.add(who + "：what 不止一行（要一句话）");
        JSONArray needs = e.optJSONArray("needs");
        if (needs == null || needs.length() == 0) {
            out.add(who + "：needs 不是数组，或者里面是空的（前置条件要写出来）");
        } else {
            for (int j = 0; j < needs.length(); j++) {
                if (str(needs.opt(j)).trim().isEmpty()) {
                    out.add(who + "：needs 里有一条不是一句非空的话");
                    break;
                }
            }
        }
        Object hours = e.opt("hours");
        if (!(hours instanceof Integer) || (Integer) hours < 1 || (Integer) hours > 10)
            out.add(who + "：hours「" + hours + "」不是 1 到 10 的整数（这个场景自己说要多长）");
        Object un = e.opt("unverified");
        if (!(un instanceof String)) out.add(who + "：unverified 不是字符串（可以是空串）");
        else if (!((String) un).isEmpty() && !ID.matcher((String) un).matches())
            out.add(who + "：unverified「" + un + "」不是 kebab-case");
        script(out, who, e.opt("script"), root);
    }

    /** The script has to be there, and it has to have a main() of its own — a scenario nobody can run is no scenario. */
    private static void script(List<String> out, String who, Object raw, Path root) {
        String s = str(raw).trim();
        if (s.isEmpty()) {
            out.add(who + "：script 是空的（要说清脚本在哪）");
            return;
        }
        String text = read(root.resolve(s.replace('/', File.separatorChar)));
        if (text == null) {
            out.add(who + "：script「" + s + "」在仓库里没有");
            return;
        }
        if (!MAIN.matcher(text).find()) out.add(who + "：script「" + s + "」里没有 main()（跑不起来的场景不算场景）");
    }
}
