package xyz.erapi0neer.xingyi.agent;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 「还没验」是文件里的一条记录（verify-durable, 2026-11-12）: what is installed but not yet checked on the real path
 * has a validator, a generated NEXT.md section, a line for bridge.sh status and a runner that takes an entry out again.
 * Every broken rule has to come back as exactly one sentence that names it — a validator that only says 「不对」 is the
 * thing this job exists to stop.
 */
final class UnverifiedChecks {
    private UnverifiedChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    /** The repository root: the offline checks run from it (run_core.sh cds there). */
    private static final Path ROOT = Paths.get("");
    /** 2026-11-12 noon, local: 手写的样例日期都按这一天造，所以「以后的日子」这类规矩能被测到，也不会随日历漂。
     * 仓库里那一份真账本**不能**用它：2026-11-14 补了五条 since=2026-11-14 的待办，被这个冻结的时钟判成「以后的
     * 日子」，账本从此再进不去新条目。真账本用真时钟（见 sound()）。 */
    private static final long NOW = at(2026, 11, 12);

    private static long at(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 12, 0, 0);
        return c.getTimeInMillis();
    }

    static void run() throws Exception {
        sound();
        registryAndLedger();
        brokenRules();
        scripts();
        markdownSection();
        statusLine();
        listLines();
        withoutOne();
    }

    // ---- 一条没毛病的记录，和仓库里真正在用的那一份 ----------------------------------------------------------------

    private static void sound() throws Exception {
        check(Unverified.problems(file(ok("ktcp-pause-real", "2026-11-11")), ROOT, NOW).isEmpty(),
                "一条本身没毛病的记录，problems 一句都不说");
        // 真账本用真时钟：这条记录的是「今天还有哪几件事没验」，用冻结的日期验它，等于从那天起就不许再添待办
        List<String> shipped = Unverified.problems(shipped(), ROOT, System.currentTimeMillis());
        check(shipped.isEmpty(), "仓库里的 tests/agent/unverified.json 是干净的（实际：" + shipped + "）");
        // 这一条记录的是「还有哪几件事没在真机上验过」，不是条数：验掉一条就少一条，全验完就该是空的。
        // 11-12 03:16 / 10:38 / 10:40 / 11:05 / 11:06 五次真机把暂停、快进、选集、待机回桌面、说话中上屏、待机点播都验过了。
        // 账本里能出现什么，不再由这里手写一张单子说了算：单子就是注册表 tests/agent/realpath/scenarios.json 里的场景
        // id。2026-11-12 21:40 就是手写的那张漏了三条——注册表加场景时没人想起账本，空账本被 NEXT / bridge status /
        // HANDOFF 三处读成「一条都没有：装上的东西都在真机上见过」。
        List<String> stray = strays(ids(shipped()), scenarioIds(registry()));
        check(stray.isEmpty(), "仓库里那份的每一条都是注册表里的场景（实际：" + stray + "）");
    }

    // ---- 白名单从注册表来：账本里出现一条没有对应场景的待办，当场红 ---------------------------------------------------

    private static void registryAndLedger() throws Exception {
        check(scenarioIds("这不是 JSON") == null, "注册表不是 JSON：读不出来，不抛异常");
        check(scenarioIds("{}") == null, "注册表里没有 scenarios 数组：读不出来，不抛异常");
        check(scenarioIds(null) == null, "注册表不在：读不出来，不抛异常");
        check(scenarioIds("{\"scenarios\":[1]}").isEmpty(), "scenarios 里有一条不是对象的：跳过去，不抛异常");
        List<String> two = scenarioIds("{\"scenarios\":[{\"id\":\"a-b\"},{\"id\":\"c-d\"}]}");
        check(two != null && two.equals(Arrays.asList("a-b", "c-d")),
                "只读 scenarios[].id，先后照旧（实际 " + two + "）");

        check(strays(Arrays.asList("a-b"), two).isEmpty(), "账本里那条有对应的场景：一句都不说");
        List<String> one = strays(Arrays.asList("x-y", "a-b"), two);
        check(one.size() == 1, "账本里只有一条没有对应的场景：只有一句（实际 " + one + "）");
        check(!one.isEmpty() && one.get(0).contains("x-y"), "这句话点名是哪一条待办（实际 " + one + "）");
        check(!one.isEmpty() && one.get(0).contains("没有对应的场景")
                        && one.get(0).contains("要么注册一个，要么把这条从账本里删掉"),
                "这句话说清两条路：注册一个场景，或者把这条从账本里删掉（实际 " + one + "）");
        List<String> blind = strays(Arrays.asList("x-y"), null);
        check(blind.size() == 1 && blind.get(0).contains("tests/agent/realpath/scenarios.json")
                        && blind.get(0).contains("读不出来"),
                "注册表读不出来：一句点名是哪个文件读不出来，不悄悄放过（实际 " + blind + "）");

        List<String> scenario = scenarioIds(registry());
        // at least the ten of 2026-11-14: a new scenario is no failure (next-song-through-xiaoai, 2026-11-18, was the 11th)
        check(scenario != null && scenario.size() >= 10, "仓库里的注册表至少记着那十个场景（实际 "
                + (scenario == null ? "读不出来" : scenario.size() + " 个 " + scenario) + "）");
        check(scenario != null && strays(ids(shipped()), scenario).isEmpty(),
                "仓库里的账本：每一条都是注册表里的场景（实际 " + strays(ids(shipped()), scenario) + "）");
        // 反着证一次：账本里塞一条注册表没有的，这条检查当场红（不然上面那条可能只是碰巧）
        List<String> withStray = new ArrayList<>(ids(shipped()));
        withStray.add("a-todo-nobody-registered");
        List<String> caught = strays(withStray, scenario);
        check(caught.size() == 1 && caught.get(0).contains("a-todo-nobody-registered"),
                "账本里多一条注册表没有的待办：当场被点出来（实际 " + caught + "）");
    }

    // ---- 每条坏规矩，各一句说清它坏在哪 --------------------------------------------------------------------------

    private static void brokenRules() {
        problem("这不是 JSON", "读不出来", "整份文件不是 JSON，只有一句话说读不出来");
        problem("{}", "没有 entries", "没有 entries 数组，有一句说没有");
        problem("{\"entries\": [1]}", "不是一条记录", "entries 里有一条不是对象的，被指着位置说出来");
        problem(file(entry("", "有", "有", needs("放一集"), "2026-11-11", "")), "id 是空的", "id 是空的");
        problem(file(entry("Pause_Real", "有", "有", needs("放一集"), "2026-11-11", "")), "不是 kebab-case",
                "id 不是 kebab-case（大写和下划线都不行）");
        problem(file(ok("same-id", "2026-11-11"), ok("same-id", "2026-11-10")), "和前面的一条一样",
                "两条用同一个 id，后一条被指出来");
        problem(file(entry("a-b", "", "有", needs("放一集"), "2026-11-11", "")), "feature 是空的",
                "feature 是空的（不知道是哪件事还没验）");
        problem(file(entry("a-b", "有", "", needs("放一集"), "2026-11-11", "")), "why 是空的",
                "why 是空的（不知道为什么静音检查代替不了它）");
        problem(file(entry("a-b", "有", "有", "\"放一集\"", "2026-11-11", "")), "needs 不是数组", "needs 不是数组");
        problem(file(entry("a-b", "有", "有", needs("放一集", ""), "2026-11-11", "")), "第 2 项是空的",
                "needs 里有一项是空的，被指着第几项说出来");
        problem(file(entry("a-b", "有", "有", needs("放一集"), "11月11日", "")), "不是 YYYY-MM-DD",
                "since 不是 YYYY-MM-DD");
        problem(file(entry("a-b", "有", "有", needs("放一集"), "2026-02-30", "")), "不是哪一天",
                "since 是一个格式对但没有的日子（2026-02-30）");
        problem(file(entry("a-b", "有", "有", needs("放一集"), "2026-12-01", "")), "是以后的日子",
                "since 是以后的日子，拿调用者的钟比出来的");
        problem(file(entry("a-b", "有", "有", needs("放一集"), "2026-11-11", "tests/agent/no-such-probe.py")),
                "在磁盘上没有", "script 写了一个磁盘上没有的路径");
        check(Unverified.problems(file(entry("a-b", "有", "有", needs("放一集"), "2026-11-11", "")), ROOT, NOW).isEmpty(),
                "script 是空的反而是对的：还没有东西能验它，就写没有");
    }

    /** That file has exactly one thing wrong with it, and the sentence says what. */
    private static void problem(String json, String must, String label) {
        List<String> ps = Unverified.problems(json, ROOT, NOW);
        check(ps.size() == 1 && ps.get(0).contains(must), label + "（实际：" + ps + "）");
    }

    // ---- 脚本路径：仓库里有、仓库上面的草稿树里有、哪儿都没有 ------------------------------------------------------

    private static void scripts() {
        check(Unverified.find(ROOT, "tests/agent/unverified.json") != null, "root 下面的脚本找得到");
        check(Unverified.find(Paths.get("tests/agent"), "tests/agent/unverified.json") != null,
                "root 下面没有、root 往上才有的脚本也找得到（探针在仓库外的草稿树里）");
        check(Unverified.find(ROOT, "tests/agent/no-such-probe.py") == null, "哪儿都没有的脚本，找不到");
        check(Unverified.find(ROOT, "") == null, "空的脚本路径不当成文件");
        check(Unverified.find(Paths.get("tests/agent/fixtures/deeper"), "tests/agent/unverified.json") == null,
                "往上只找三级：再远的地方碰巧有个同名文件不算");
    }

    // ---- NEXT.md 那一节是生成的 ---------------------------------------------------------------------------------

    private static void markdownSection() throws Exception {
        String one = Unverified.markdown(file(ok("ktcp-pause-real", "2026-11-11")));
        check(one.startsWith("## 还没验的\n\n"), "markdown 以「## 还没验的」开头，后面空一行");
        check(bullets(one).size() == 1, "一条记录一个条目");
        check(one.contains("脚本 tests/agent/unverified.json）"), "条目里写着脚本在哪");

        String three = file(ok("newest", "2026-11-12"), ok("oldest", "2026-11-09"), ok("middle", "2026-11-10"));
        List<String> b = bullets(Unverified.markdown(three));
        check(b.size() == 3, "三条记录三个条目");
        check(b.get(0).contains("oldest") && b.get(1).contains("middle") && b.get(2).contains("newest"),
                "条目按 since 从旧到新排");
        check(bullets(Unverified.markdown(file(ok("first", "2026-11-11"), ok("second", "2026-11-11")))).get(0)
                .contains("first"), "since 一样的两条，保持文件里的先后");
        // 回读：条目里得能找到每一条的 id，还有它为什么还没验
        String text = Unverified.markdown(three);
        for (String id : ids(three)) check(text.contains("- **" + id + "**"), "条目里回读得到 " + id);
        check(text.contains("静音来源对有副作用的工具是拒执的"), "条目里带着 why（为什么静音检查代替不了它）");
        check(Unverified.markdown("{\"entries\": []}").equals("## 还没验的\n\n"),
                "一条都没有时只有那个标题，别的什么都不写");
        check(Unverified.markdown(file(entry("a-b", "有", "有", needs("放一集"), "2026-11-11", "")))
                .contains("脚本 没有）"), "脚本是空的，条目里写「没有」而不是空着");
    }

    // ---- bridge.sh status 那一行 ---------------------------------------------------------------------------------

    private static void statusLine() {
        check(Unverified.line("{\"entries\": []}", 3).isEmpty(), "一条都没有，status 那一行是空的");
        check(Unverified.line(file(ok("only-one", "2026-11-11")), 3).equals("还没在真实路径验：1 条（only-one）"),
                "一条：条数和一个 id，没有省略号");
        String three = file(ok("newest", "2026-11-12"), ok("oldest", "2026-11-09"), ok("middle", "2026-11-10"));
        check(Unverified.line(three, 3).equals("还没在真实路径验：3 条（oldest, middle, newest）"),
                "三条、max 3：三个 id 都写出来，还是从旧到新，没有省略号");
        String five = file(ok("id-1", "2026-11-07"), ok("id-2", "2026-11-08"), ok("id-3", "2026-11-09"),
                ok("id-4", "2026-11-10"), ok("id-5", "2026-11-11"));
        check(Unverified.line(five, 3).equals("还没在真实路径验：5 条（id-1, id-2, id-3, …）"),
                "五条、max 3：条数是五条，只写三个 id，末尾一个省略号");
    }

    // ---- verify.sh 的输入：一条一行，从旧到新，字段用 US 分开 ---------------------------------------------------

    private static void listLines() {
        String json = file(ok("newest", "2026-11-12"), ok("oldest", "2026-11-09"),
                entry("no-script", "还没有脚本的一条", "还没有东西能验它", needs("待机"), "2026-11-10", ""));
        String[] lines = Unverified.list(json).split("\n");
        check(lines.length == 3, "list 一条一行");
        String[] first = lines[0].split("\u001f", -1);
        check(first.length == 4 && "oldest".equals(first[0]), "list 从旧到新，第一行是最旧的那条");
        check("放一集、亮屏".equals(first[3]), "最后一列是前提，用、分隔");
        String[] second = lines[1].split("\u001f", -1);
        check("no-script".equals(second[0]) && second[2].isEmpty(),
                "脚本是空的，那一列还是空的（用 US 分开，shell 的 read 才不会把它吞掉）");
    }

    // ---- 验过了就把那一条拿掉 -----------------------------------------------------------------------------------
    private static void withoutOne() throws Exception {
        String json = file(ok("a-one", "2026-11-09"), ok("b-two", "2026-11-10"), ok("c-three", "2026-11-11"));
        String gone = Unverified.without(json, "b-two");
        check(ids(gone).equals(java.util.Arrays.asList("a-one", "c-three")),
                "without 只拿掉那一条，别的都还在，先后也没变");
        check(!gone.contains("b-two"), "被拿掉的那一条，一个字都不剩");
        check(Unverified.problems(gone, ROOT, NOW).isEmpty(), "拿掉一条以后，剩下的还是干净的");
        check(Unverified.without(json, "no-such-id").equals(json), "id 不在文件里，文件一个字都不动");
        check(ids(Unverified.without(json, "a-one")).equals(java.util.Arrays.asList("b-two", "c-three")),
                "拿掉的是第一条");
        check(ids(Unverified.without(json, "c-three")).equals(java.util.Arrays.asList("a-one", "b-two")),
                "拿掉的是最后一条");
        String only = Unverified.without(file(ok("only", "2026-11-11")), "only");
        check(ids(only).isEmpty() && Unverified.problems(only, ROOT, NOW).isEmpty(),
                "只有一条时拿掉它，文件还读得动，entries 空了");
        check(Unverified.without(shipped(), "no-such-id").equals(shipped()),
                "仓库里那份，跑一轮什么都没验成时一个字都不动");
        // 仓库那份可能是空的（2026-11-12 全部验完之后就是）：空的时候没有「第一条」可拿。但这一条检查不能因此
        // 整个跳过——检查的总数会随账本内容变，给工人定的门槛（agent core: N passed）就会时对时错：
        // 2026-11-12 23:20 账本一空，核心检查就从 5696 掉到 5695，verify-wait 的门槛当场失效。
        // 所以两种情形各验一条，总数恒定：有条目就验「拿掉第一条」，没有就验「空账本再拿一次也一个字不动」。
        List<String> live = ids(shipped());
        String after;
        String label;
        if (live.isEmpty()) {
            after = Unverified.without(shipped(), "no-such-id");
            label = "仓库那份是空的：从空账本里拿一条，一个字都不动，也还读得动";
        } else {
            String kept = live.get(0);
            after = Unverified.without(shipped(), kept);
            label = "从仓库那份里拿掉「" + kept + "」，剩下的都还在，也都还干净";
        }
        // 真账本 → 真时钟（和 sound() 同一个理由：冻结的日期会把今天新添的待办判成「以后的日子」）
        check(ids(after).size() == live.size() - (live.isEmpty() ? 0 : 1)
                && (live.isEmpty() || !ids(after).contains(live.get(0)))
                && Unverified.problems(after, ROOT, System.currentTimeMillis()).isEmpty(), label);
    }

    // ---- 造记录的零件 -------------------------------------------------------------------------------------------

    private static String file(String... entries) {
        StringBuilder b = new StringBuilder("{\"entries\": [");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) b.append(", ");
            b.append(entries[i]);
        }
        return b.append("]}").toString();
    }

    private static String entry(String id, String feature, String why, String needs, String since, String script) {
        return "{\"id\": \"" + id + "\", \"feature\": \"" + feature + "\", \"why\": \"" + why + "\", \"needs\": "
                + needs + ", \"since\": \"" + since + "\", \"script\": \"" + script + "\"}";
    }

    private static String needs(String... items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(items[i]).append('"');
        }
        return b.append(']').toString();
    }

    /** 一条本身没毛病的记录：脚本指向仓库里真有的文件。 */
    private static String ok(String id, String since) {
        return entry(id, "云视听极光的暂停/继续", "静音来源对有副作用的工具是拒执的", needs("放一集", "亮屏"), since,
                "tests/agent/unverified.json");
    }

    private static List<String> ids(String json) throws Exception {
        List<String> out = new ArrayList<>();
        JSONArray es = new JSONObject(json).optJSONArray("entries");
        if (es == null) return out;
        for (int i = 0; i < es.length(); i++) out.add(es.optJSONObject(i).optString("id", ""));
        return out;
    }

    private static List<String> bullets(String markdown) {
        List<String> out = new ArrayList<>();
        for (String l : markdown.split("\n")) if (l.startsWith("- ")) out.add(l);
        return out;
    }

    // ---- 白名单从注册表来 -------------------------------------------------------------------------------------

    /** tests/agent/realpath/scenarios.json 里的场景 id；读不出来（不在 / 不是 JSON / 没有 scenarios 数组）是 null。 */
    private static List<String> scenarioIds(String json) {
        if (json == null) return null;
        JSONArray es;
        try {
            es = new JSONObject(json).optJSONArray("scenarios");
        } catch (JSONException e) {
            return null;
        }
        if (es == null) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < es.length(); i++) {
            JSONObject e = es.optJSONObject(i);
            if (e != null) out.add(e.optString("id", ""));
        }
        return out;
    }

    /**
     * 账本里的每一条都要在注册表里找得到一个场景；找不到的，一句一句点出来。注册表读不出来也是一句（说清是哪个文件），
     * 不是抛异常：这条检查存在的意思就是「注册表加场景时没人想起账本」这件事要当场红。
     */
    private static List<String> strays(List<String> ledger, List<String> scenario) {
        List<String> out = new ArrayList<>();
        if (scenario == null) {
            out.add("tests/agent/realpath/scenarios.json 读不出来（不在，或者里面没有 scenarios 数组），"
                    + "没法确认账本里的每一条都有对应的场景");
            return out;
        }
        for (String id : ledger) {
            if (!scenario.contains(id))
                out.add("「" + id + "」这条待办没有对应的场景（要么注册一个，要么把这条从账本里删掉）");
        }
        return out;
    }

    /** 仓库里真正在用的注册表；读不出来是 null，交给 scenarioIds 说。 */
    private static String registry() {
        try {
            return new String(java.nio.file.Files.readAllBytes(Paths.get("tests/agent/realpath/scenarios.json")),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** 仓库里真正在用的那一份。 */
    private static String shipped() throws Exception {
        return new String(java.nio.file.Files.readAllBytes(Paths.get("tests/agent/unverified.json")),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
