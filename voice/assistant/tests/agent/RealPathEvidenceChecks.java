package xyz.erapi0neer.xingyi.agent;

import static xyz.erapi0neer.xingyi.agent.RealPathChecks.*;
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
 * real-path (2026-11-12): the shape of every evidence file a run on the TV leaves behind — an {@code id} equal to its
 * own file name, an {@code at} that is an ISO time and not in the future, a boolean {@code ok}, a {@code notes} array.
 *
 * <p>And the rule this job exists for: an evidence with {@code "ok": false} has to name, through the registry's
 * {@code unverified}, an entry that is really in {@code tests/agent/unverified.json} — a verification that did not pass
 * comes back as a to-do, or it is lost. 2026-11-11 20:03: 「暂停已验证」 rested on silent-source checks that cannot
 * exercise an action at all, and nothing in the repository said otherwise.
 *
 * <p>Plus the evidence the checks cannot see at all: every {@code *.json} under {@code tests/agent/evidence/realpath/},
 * however deep. Those lines are notices, not problems — {@code --strict} is what turns them into failures. 2026-11-12
 * 20:30: five real runs of the sleeping-TV and standby paths were written there by hand, and a fresh session could not
 * tell them from never-run.
 *
 * <p>Called by {@link RealPathChecks#run()}. Until 2026-11-14 this comment sat at the end of {@code RealPathChecks.java},
 * where nothing followed it: the realpath-split job copied {@code RealPathChecks}'s own class comment onto this class
 * and left this one behind. The two files now say what they are.
 */
final class RealPathEvidenceChecks {
    private RealPathEvidenceChecks() { }

    private static final Pattern AT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

    static void run() throws Exception {
        goodEvidence();
        brokenEvidence();
        Path root = Files.createTempDirectory("realpath-misplaced");
        try {
            misplacedEvidence(root);
        } finally {
            delete(root.toFile());
        }
    }

    // ---- 一份没毛病的证据 ------------------------------------------------------------------------------------

    private static void goodEvidence() {
        List<String> none = evidence(ev(NAME, WHEN, "true", "[\"面板窗口 1 个\"]"), NAME, "", UNVERIFIED, NOW);
        check(none.isEmpty(), "一份本身没毛病的证据，problems 一句都不说（实际：" + none + "）");
        check(evidence(ev(NAME, WHEN, "true", "[]"), NAME, "", UNVERIFIED, NOW).isEmpty(), "notes 和 shots 是空数组也没关系");
        check(evidence(ev(NAME, WHEN, "true", "[]").replace("\"shots\":[]", "\"shots\":[\"probe-out/a.png\"]"),
                NAME, "", UNVERIFIED, NOW).isEmpty(), "shots 里有照片也没关系");
        check(evidence(ev(NAME, "2026-11-11T20:03:00", "true", "[]"), NAME, "", UNVERIFIED, NOW).isEmpty(),
                "at 没写时区（当成本机时间）也没关系");
    }

    private static void brokenEvidence() {
        problem(evidence("{", NAME, "", UNVERIFIED, NOW), "读不出来", "整份证据文件不是 JSON");
        problem(evidence("[]", NAME, "", UNVERIFIED, NOW), "不是一个对象", "证据文件不是一个对象");
        problem(evidence(ev("", WHEN, "true", "[]"), NAME, "", UNVERIFIED, NOW), "id 是空的", "证据里没有 id");
        problem(evidence(ev("other", WHEN, "true", "[]"), NAME, "", UNVERIFIED, NOW), "和自己的文件名不一样",
                "id 和自己的文件名不一样（文件叫 real-panel-while-speaking.json）");
        problem(evidence(ev(NAME, "2026-11-11", "true", "[]"), NAME, "", UNVERIFIED, NOW), "不是 ISO 时间",
                "at 只是一个日子，没有时间");
        problem(evidence(ev(NAME, "昨天", "true", "[]"), NAME, "", UNVERIFIED, NOW), "不是 ISO 时间", "at 根本不是时间");
        problem(evidence(ev(NAME, "2026-11-13T02:40:11+08:00", "true", "[]"), NAME, "", UNVERIFIED, NOW),
                "是将来的时间", "at 是明天的（今天是 2026-11-12）");
        problem(evidence(ev(NAME, "2099-01-01T00:00:00+08:00", "true", "[]"), NAME, "", UNVERIFIED, NOW),
                "是将来的时间", "at 是 2099 年");
        problem(evidence(ev(NAME, WHEN, "\"yes\"", "[]"), NAME, "", UNVERIFIED, NOW), "不是 true / false",
                "ok 是字符串 yes，不是布尔");
        problem(evidence(ev(NAME, WHEN, "1", "[]"), NAME, "", UNVERIFIED, NOW), "不是 true / false", "ok 是 1，不是布尔");
        problem(evidence(ev(NAME, WHEN, "true", "\"看到面板了\""), NAME, "", UNVERIFIED, NOW), "notes 不是数组",
                "notes 不是数组（看到了什么，要一条一条写下来）");
        problem(evidence(ev(NAME, WHEN, "true", "[]").replace("\"shots\":[]", "\"shots\":\"a.png\""),
                NAME, "", UNVERIFIED, NOW), "shots 不是数组", "shots 不是数组");
        problem(evidence(ev(NAME, WHEN, "true", "[]"), NAME, null, UNVERIFIED, NOW), "没有 " + NAME + " 这个场景",
                "证据文件对应的场景在 scenarios.json 里没有");
    }
    // ---- 放错地方的证据：检查看不到的那些，一句一句点出来；不算问题，除非加了 --strict --------------------------

    private static void misplacedEvidence(Path root) throws Exception {
        List<String> empty = misplaced(root);
        check(empty.isEmpty(), "evidence/realpath/ 都不在：一句 WHERE 都不说（实际：" + empty + "）");
        Path dir = root.resolve("tests/agent/evidence/realpath");
        Files.createDirectories(dir);
        Files.write(dir.resolve("real-ktcp-pause-real.json"), "{}".getBytes(StandardCharsets.UTF_8));
        List<String> notice = misplaced(root);
        check(notice.size() == 1, "放错地方的一个文件：只有一句（实际 " + notice.size() + " 句 " + notice + "）");
        check(!notice.isEmpty() && notice.get(0).startsWith("WHERE"),
                "这句话是 WHERE 开头的提醒（实际 " + notice + "）");
        check(!notice.isEmpty() && notice.get(0).contains("evidence/realpath/"),
                "这句话里说清它在哪：evidence/realpath/（实际 " + notice + "）");
        check(!notice.isEmpty() && notice.get(0).contains("real-ktcp-pause-real.json"),
                "这句话点名是哪个文件（实际 " + notice + "）");
        check(!notice.isEmpty() && notice.get(0).contains("real-<场景 id>.json"),
                "这句话说清该放在哪、该叫什么（实际 " + notice + "）");
        Files.write(dir.resolve("real-standby-home-real.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("shot.png"), "x".getBytes(StandardCharsets.UTF_8));
        List<String> two = misplaced(root);
        check(two.size() == 2, "两个 json 加一张照片：只有 json 各得一句（实际 " + two.size() + " 句 " + two + "）");
        check(two.size() == 2 && two.get(0).contains("real-ktcp-pause-real.json")
                        && two.get(1).contains("real-standby-home-real.json"),
                "两句各点名自己的文件，而且是排好序的（实际 " + two + "）");
        Path deep = dir.resolve("old");
        Files.createDirectories(deep);
        Files.write(deep.resolve("real-ktcp-seek-real.json"), "{}".getBytes(StandardCharsets.UTF_8));
        check(misplaced(root).size() == 3, "子目录里的 json 也点出来（实际 " + misplaced(root) + "）");
        Files.write(root.resolve("tests/agent/evidence/real-good.json"), "{}".getBytes(StandardCharsets.UTF_8));
        check(misplaced(root).size() == 3, "放对了地方的证据一句 WHERE 都不出（实际 " + misplaced(root) + "）");
        List<String> problem = one("real-good.json：notes 不是数组（看到了什么，要一条一条写下来）");
        check(failures(problem, two, false) == 1, "不加 --strict：放错地方的证据不算问题（还是 1 处）");
        check(failures(problem, two, true) == 3, "加 --strict：同样两句变成问题（1 + 2 处）");
        check(failures(problem, empty, true) == 1, "加 --strict，没有放错地方的证据：和问题一样多");
        check(failures(new ArrayList<String>(), two, false) == 0,
                "只有放错地方的证据、不加 --strict：0 处，今天这棵树照样 REALPATH OK");
        check(failures(new ArrayList<String>(), two, true) == 2, "只有放错地方的证据、加 --strict：两处");
        List<String> real = misplaced(Paths.get(""));
        boolean allThere = true;
        for (String line : real) {
            String name = line.substring(line.indexOf(' ') + 1, line.indexOf('：'));
            if (!Files.isRegularFile(Paths.get("tests/agent/evidence/realpath").resolve(name))) allThere = false;
        }
        check(allThere, "真实树上每一句 WHERE 点的文件都真的在 evidence/realpath/ 里（现在 " + real.size() + " 句）");
    }

    // ---- 规矩本身：一份证据文件哪里不对 ------------------------------------------------------------------------

    /**
     * Everything wrong with one evidence file, as sentences; empty when it is sound.
     *
     * @param name       the id in the file's own name ({@code real-<id>.json})
     * @param unverified the registry's {@code unverified} for that id, or null when the registry has no such scenario
     * @param nowMs      the clock, so an {@code at} that has not happened yet is caught
     */
    static List<String> evidence(String json, String name, String unverified, String unverifiedJson, long nowMs) {
        String who = "real-" + name + ".json";
        if (json.trim().startsWith("[")) return one(who + "：不是一个对象");
        JSONObject d;
        try {
            d = new JSONObject(json);
        } catch (JSONException e) {
            return one(who + "：读不出来（不是 JSON）");
        }
        List<String> out = new ArrayList<>();
        String id = str(d.opt("id"));
        if (id.isEmpty()) out.add(who + "：id 是空的");
        else if (!id.equals(name))
            out.add(who + "：id「" + id + "」和自己的文件名不一样（这个文件叫 real-" + name + ".json）");
        Object rawAt = d.opt("at");
        long when = rawAt instanceof String ? when((String) rawAt) : -1;
        if (when < 0) out.add(who + "：at「" + rawAt + "」不是 ISO 时间（要像 2026-11-12T02:40:11+08:00）");
        else if (when > nowMs) out.add(who + "：at「" + rawAt + "」是将来的时间（现在是 " + iso(nowMs) + "）");
        Object ok = d.opt("ok");
        if (!(ok instanceof Boolean)) out.add(who + "：ok「" + ok + "」不是 true / false");
        if (!(d.opt("notes") instanceof JSONArray)) out.add(who + "：notes 不是数组（看到了什么，要一条一条写下来）");
        if (d.has("shots") && !(d.opt("shots") instanceof JSONArray)) out.add(who + "：shots 不是数组");
        if (unverified == null) out.add(who + "：scenarios.json 里没有 " + name + " 这个场景（证据文件要有对应的场景）");
        else if (Boolean.FALSE.equals(ok)) out.addAll(link(who, unverified, unverifiedJson));
        return out;
    }

    /** A verification that did not pass has to come back as a to-do, or it is lost. */
    private static List<String> link(String who, String unverified, String json) {
        if (unverified.isEmpty())
            return one(who + "：这次没过，可 scenarios.json 里它的 unverified 是空的（没过的验证要留下一条待办）");
        List<String> ids = unverifiedIds(json);
        if (ids == null)
            return one(who + "：tests/agent/unverified.json 读不出来（不在，或者里面没有 entries 数组），没法确认「"
                    + unverified + "」这条待办");
        if (!ids.contains(unverified))
            return one(who + "：unverified「" + unverified + "」不在 tests/agent/unverified.json 里（待办没了，或者名字改了）");
        return new ArrayList<>();
    }

    /** The ids in tests/agent/unverified.json; null when the file is not there or has no entries array. */
    static List<String> unverifiedIds(String json) {
        if (json == null) return null;
        JSONArray entries;
        try {
            entries = new JSONObject(json).optJSONArray("entries");
        } catch (JSONException e) {
            return null;
        }
        if (entries == null) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e != null) out.add(e.optString("id", ""));
        }
        return out;
    }

    /** The {@code at} as epoch milliseconds, or -1 when it is not an ISO timestamp. One without an offset is local. */
    private static long when(String at) {
        String s = at.trim();
        if (!AT.matcher(s).lookingAt()) return -1;
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1) + "+00:00";
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS"};
        for (String p : patterns) {
            SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
            f.setLenient(false);
            ParsePosition pos = new ParsePosition(0);
            Date d = f.parse(s, pos);
            if (d != null && pos.getIndex() == s.length()) return d.getTime();
        }
        return -1;
    }

    private static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(ms));
    }
    // ---- 一棵树上的证据文件，和放错地方的那些 ----------------------------------------------------------------------

    /** The {@code evidence/real-*.json} one level under {@code tests/agent/evidence/}: id → its {@code ok}. */
    static Map<String, Boolean> evidenceOnTree(Path root) {
        Map<String, Boolean> evidence = new HashMap<>();
        File[] kids = root.resolve("tests").resolve("agent").resolve("evidence").toFile().listFiles();
        if (kids == null) return evidence;
        for (File f : kids) {
            String n = f.getName();
            if (!f.isFile() || !n.startsWith("real-") || !n.endsWith(".json")) continue;
            evidence.put(n.substring("real-".length(), n.length() - ".json".length()), ok(read(f.toPath())));
        }
        return evidence;
    }

    /** The {@code ok} in one evidence file; null when it is not there, not JSON, or says anything but a boolean. */
    static Boolean ok(String json) {
        if (json == null) return null;
        try {
            Object v = new JSONObject(json).opt("ok");
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** The {@code evidence/real-*.json} one level under {@code tests/agent/evidence/}: id → its text. */
    static Map<String, String> evidenceTextOnTree(Path root) {
        Map<String, String> out = new LinkedHashMap<>();
        File[] kids = root.resolve("tests").resolve("agent").resolve("evidence").toFile().listFiles();
        if (kids == null) return out;
        for (File f : kids) {
            String n = f.getName();
            if (!f.isFile() || !n.startsWith("real-") || !n.endsWith(".json")) continue;
            out.put(n.substring("real-".length(), n.length() - ".json".length()), read(f.toPath()));
        }
        return out;
    }

    /**
     * One line per {@code *.json} under {@code tests/agent/evidence/realpath/}, however deep: evidence the loop over
     * {@code evidence/real-*.json} never sees, because that loop only looks one level down. A notice, not a problem —
     * {@code problems()} does not return these, so the tree as it stands still prints REALPATH OK; {@code --strict} is
     * what turns the same lines into failures. 2026-11-12 20:30: five real runs of the sleeping-TV and standby paths
     * were written there by hand, and a fresh session could not tell them from never-run.
     */
    static List<String> misplaced(Path root) {
        List<String> out = new ArrayList<>();
        collect(root.resolve("tests").resolve("agent").resolve("evidence").resolve("realpath").toFile(), out);
        Collections.sort(out);
        return out;
    }

    private static void collect(File dir, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else if (k.getName().endsWith(".json"))
                out.add("WHERE " + k.getName() + "：放在 evidence/realpath/ 里，检查看不到"
                        + "（要放在 evidence/ 根下、文件名 real-<场景 id>.json）");
        }
    }

    /** How many things count as wrong: the problems, plus the misplaced evidence when {@code --strict} was given. */
    static int failures(List<String> problems, List<String> notices, boolean strict) {
        return problems.size() + (strict ? notices.size() : 0);
    }
}

/**
 * real-path (2026-11-12): the registry and the ledger ({@code tests/agent/unverified.json}) have to say the same thing
 * about a scenario nobody has run at all — no evidence file means the registry's {@code unverified} names a to-do that
 * is really in the ledger, and an evidence that says {@code "ok": true} means no to-do of that id is left there.
 * 2026-11-12 21:40: three scenarios had never run on the TV, the ledger was empty because nobody had written it down,
 * and NEXT.md / bridge.sh status / HANDOFF all read that empty ledger as 「一条都没有：装上的东西都在真机上见过」.
 *
 * <p>The other direction is here too: a verification that did not pass has to come back as a to-do, or it is lost.
 * 2026-11-11 20:03: 「暂停已验证」 rested on silent-source checks that cannot exercise an action at all, and nothing
 * in the repository said otherwise.
 *
 * <p>The second direction is narrowed by who wrote the evidence — see {@link RealPathProbeChecks#by}, which owns the
 * rule for {@code by} itself; an evidence some other script produced leaves the to-do exactly where it is.
 *
 * <p>Called by {@link RealPathChecks#run()}.
 */
