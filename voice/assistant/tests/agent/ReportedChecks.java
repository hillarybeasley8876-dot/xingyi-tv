package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * reported (2026-11-12): the ledger's own rules — one entry per thing the user reported, a label that is really in the
 * file it names, the commit that fixed it and the revision where that check was not there yet. Every ledger here is
 * written into a temporary directory with the files it points at; nothing outside it.
 *
 * <p>The last two groups are not about the ledger: they are the two checks the panel entries point at, because
 * c2ea46d (the panel while the user is still speaking, and self_check looking at the panel) touched no test file at
 * all. They read the sources — what a panel really does on the TV is not something an offline check can see, and the
 * ledger says so.
 */
final class ReportedChecks {
    private ReportedChecks() { }

    private static final String FILE = "tests/agent/FixtureChecks.java", LABEL = "Fixture.label says what it proves",
            TWELVE = "twelve chars", ELEVEN = "eleven char", EIGHT = "12345678", SYMBOL = "static void blank()",
            EVIDENCE = "tests/agent/evidence.txt", ID = "a-thing-the-user-reported";

    private static Path dir;
    private static long now;
    private static int passed, failed;

    /** What the core suite calls: the repository is the working directory it runs in. */
    static void run() throws Exception {
        run(Paths.get("."));
    }

    static void run(Path root) throws Exception {
        passed = 0;
        failed = 0;
        Path tmp = Files.createTempDirectory("reported-checks");
        try {
            fixture(tmp);
            ledger();
            proof();
            table();
            listening(root);
            selfCheck(root);
            stateAge(root);
            handBack(root);
            offlineReasons();
        } finally {
            delete(tmp.toFile());
        }
        System.out.println("reported checks: " + passed + " passed, " + failed + " failed");
        if (failed > 0) throw new IllegalStateException("reported checks: " + failed + " failed");
    }

    /** Counted here for the gate's own line, and through AgentCoreTest so the core suite counts them too. */
    private static void check(boolean ok, String label) {
        if (ok) passed++;
        else failed++;
        AgentCoreTest.check(ok, label);
    }

    /**
     * The temporary repository: one check file that really holds the labels the rules tell apart — a sentence in double
     * quotes (twelve characters, and one of eleven that is quoted and still too short), a bare number, and a method
     * header that is in the file without quotes around it — plus one evidence file and a fixed clock.
     */
    private static void fixture(Path tmp) throws Exception {
        dir = tmp;
        Files.createDirectories(tmp.resolve("tests/agent"));
        Files.write(tmp.resolve(FILE), ("final class FixtureChecks {\n"
                + "    static void run() {\n"
                + "        check(true, \"" + LABEL + "\");\n"
                + "        check(true, \"" + TWELVE + "\");\n"
                + "        check(true, \"" + ELEVEN + "\");\n"
                + "        check(true, \"" + EIGHT + "\");\n"
                + "    }\n"
                + "    " + SYMBOL + " { }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve(EVIDENCE), "2026-11-11 21:12 on the TV\n".getBytes(StandardCharsets.UTF_8));
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 12, 12, 0, 0);
        now = c.getTimeInMillis();
    }

    /** A sound entry: every field the rules ask for, and a label that is in the file it names. */
    private static JSONObject entry() throws JSONException {
        return new JSONObject()
                .put("id", ID)
                .put("said", "暂停")
                .put("when", "2026-11-11")
                .put("symptom", "说了暂停，电视还在放")
                .put("check", new JSONObject().put("file", FILE).put("label", LABEL))
                .put("fix", "833d99c")
                .put("before", "833d99c^")
                .put("verified", "test")
                .put("evidence", "")
                .put("unverified_because", "");
    }

    private static String ledger(JSONObject... entries) throws JSONException {
        JSONArray a = new JSONArray();
        for (JSONObject e : entries) a.put(e);
        return new JSONObject().put("entries", a).toString();
    }

    private static List<String> only(JSONObject e) throws JSONException {
        return Reported.problems(ledger(e), dir, now);
    }

    /** Exactly one sentence, naming that entry and that field. */
    private static boolean one(List<String> got, String id, String field) {
        return got.size() == 1 && got.get(0).contains(id) && got.get(0).contains(field);
    }

    private static void delete(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) delete(k);
        f.delete();
    }
    /** Every rule broken once, and only that rule: the sentences have to name the entry and the field. */
    private static void ledger() throws Exception {
        check(Reported.problems(ledger(entry()), dir, now).isEmpty(), "a sound one-entry ledger has no problems");
        check(Reported.problems("{\"entries\":[]}", dir, now).isEmpty(), "a ledger with nothing reported yet is sound");

        List<String> got = Reported.problems("这不是 JSON", dir, now);
        check(got.size() == 1 && got.get(0).contains("读不出来"), "a ledger that is not JSON: one sentence and nothing else");
        got = Reported.problems("{\"other\":1}", dir, now);
        check(got.size() == 1 && got.get(0).contains("entries"), "no entries array: one sentence and nothing else");

        check(one(only(entry().put("id", "Not Kebab Case")), "第 1 条", "id"), "an id that is not kebab-case: one problem, naming id");
        check(one(only(entry().put("id", "")), "第 1 条", "id"), "an empty id: one problem, naming id");
        check(one(only(entry().put("said", "")), ID, "said"), "no said: one problem, naming said");
        check(one(only(entry().put("when", "2026-11-1")), ID, "when"), "a when that is not YYYY-MM-DD: one problem, naming when");
        check(one(only(entry().put("when", "2026-02-31")), ID, "when"), "a when that is no day at all: one problem, naming when");
        check(one(only(entry().put("when", "2026-11-13")), ID, "when"), "a when that has not come yet: one problem, naming when");
        check(one(only(entry().put("symptom", "")), ID, "symptom"), "no symptom: one problem, naming symptom");
        check(one(only(entry().put("check", new JSONObject().put("file", "src/X.java").put("label", LABEL))), ID, "check.file"),
                "a check file outside tests/agent: one problem, naming check.file");
        check(one(only(entry().put("check", new JSONObject().put("file", "tests/agent/Nope.java").put("label", LABEL))), ID, "check.file"),
                "a check file that is not in the repository: one problem, naming check.file");
        check(one(only(entry().put("check", new JSONObject().put("file", FILE).put("label", "NoSuchLabel"))), ID, "check.label"),
                "a label that is not in its file: one problem, naming check.label");
        check(one(only(entry().put("check", new JSONObject().put("file", FILE).put("label", ""))), ID, "check.label"),
                "an empty label: one problem, naming check.label");
        check(one(only(entry().put("fix", "833d99")), ID, "fix"), "a fix that is not seven hex digits: one problem, naming fix");
        check(one(only(entry().put("before", "833d99c")), ID, "before"), "a before without ^: one problem, naming before");
        check(one(only(entry().put("before", "")), ID, "before"), "an empty before: one problem, naming before");
        check(one(only(entry().put("verified", "yes")), ID, "verified"),
                "a verified that is not test / real / none: one problem, naming verified");
        check(one(only(entry().put("verified", "real")), ID, "evidence"), "real with no evidence written: one problem, naming evidence");
        check(one(only(entry().put("verified", "real").put("evidence", "tests/agent/nope.png")), ID, "evidence"),
                "real with an evidence file that is not there: one problem, naming evidence");
        check(one(only(entry().put("verified", "none").put("unverified_because", "")), ID, "unverified_because"),
                "none with no reason: one problem, naming unverified_because");

        got = Reported.problems(ledger(entry(), entry()), dir, now);
        check(got.size() == 1 && got.get(0).contains("id"), "two entries with the same id: one problem");
        got = Reported.problems(new JSONObject().put("entries", new JSONArray().put(entry()).put(7)).toString(), dir, now);
        check(got.size() == 1 && got.get(0).contains("第 2 条"), "an entry that is not an object: one problem, naming where it stands");
        check(one(only(entry().put("fix", "833D99C")), ID, "fix"), "a fix in capitals: one problem, naming fix");
        check(only(entry().put("when", "2026-11-12")).isEmpty(), "a when that is today: sound (the clock is 2026-11-12 12:00)");
        check(Reported.problems(ledger(entry().put("verified", "real").put("evidence", EVIDENCE)), dir, now).isEmpty(),
                "real with the evidence really in the repository: sound");
        check(Reported.problems(ledger(entry().put("verified", "none").put("unverified_because", "真机上还没验")), dir, now).isEmpty(),
                "none with a reason: sound");
        JSONObject other = entry().put("id", "another-thing").put("said", "面板跳不出来");
        check(Reported.problems(ledger(entry(), other), dir, now).isEmpty(), "two different entries: sound");
    }

    /**
     * reported-2: what makes a proof a proof. A label is a check's own sentence — whole in double quotes, and long
     * enough to be one — because a symbol was in the file long before the fix and 「absent at before」 would be true of
     * nothing anyone ever checked. And an entry with no revision to compare against says why in {@code void_because}:
     * a file the fix itself created is not proof of absence, so the two never go together and neither stands alone.
     */
    private static void proof() throws Exception {
        check(only(entry().put("check", new JSONObject().put("file", FILE).put("label", LABEL))).isEmpty(),
                "a sound one-entry ledger whose label is a whole quoted sentence in the file: no problems");
        check(only(entry().put("check", new JSONObject().put("file", FILE).put("label", TWELVE))).isEmpty(),
                "a label of exactly twelve characters that is a whole quoted string: sound");

        List<String> got = only(entry().put("check", new JSONObject().put("file", FILE).put("label", EIGHT)));
        check(one(got, ID, "check.label"), "a label of eight characters: one problem, naming check.label");
        check(got.get(0).contains("太短"), "and that sentence says the label is too short to prove a check");

        got = only(entry().put("check", new JSONObject().put("file", FILE).put("label", ELEVEN)));
        check(one(got, ID, "check.label"),
                "a label of eleven characters that is really quoted in the file: still too short, and nothing else");

        got = only(entry().put("check", new JSONObject().put("file", FILE).put("label", SYMBOL)));
        check(one(got, ID, "check.label"),
                "a method header that is in the file but not a whole quoted string: one problem, naming check.label");
        check(got.get(0).contains("不是一条检查的句子") && got.get(0).contains("\"" + SYMBOL + "\""),
                "and that sentence shows the quotes it looked for and did not find");

        check(one(only(entry().put("check", new JSONObject().put("file", FILE).put("label", "NoSuchLabelInTheFile"))),
                        ID, "check.label"),
                "a long label that is not in the file at all: the older sentence, and only that one");
        proofOfNothing();
    }

    /** before and void_because: one without the other, never both, and the sentence names the entry and the field. */
    private static void proofOfNothing() throws Exception {
        String why = "这条检查是修复那个提交才写进文件的，没有修复前的对照";
        List<String> got = only(entry().put("before", ""));
        check(one(got, ID, "void_because"), "an empty before with no void_because: one problem, naming void_because");
        check(got.get(0).contains("before 是空的"), "and that sentence says before 是空的");

        got = only(entry().put("void_because", why));
        check(one(got, ID, "void_because"), "a before with a void_because as well: one problem, naming void_because");
        check(got.get(0).contains(ID) && got.get(0).contains("before"), "and that sentence names the entry and before");

        check(only(entry().put("before", "").put("void_because", why)).isEmpty(),
                "an empty before with a void_because that says why: sound");
        check(only(entry().put("before", "").put("void_because", why).put("verified", "none")
                .put("unverified_because", "真机上还没验")).isEmpty(),
                "an entry with no proof and nothing verified: sound, both reasons written");
        JSONObject none = entry().put("id", "a-thing-with-no-proof").put("before", "").put("void_because", why);
        check(Reported.problems(ledger(entry(), none), dir, now).isEmpty(),
                "a ledger where one entry has a proof and the other has none: sound");
    }

    private static void table() throws Exception {
        JSONObject other = entry().put("id", "another-thing").put("said", "你为什么面板跳不出来");
        String t = Reported.table(ledger(entry(), other));
        check(t.split("\n").length == 2, "the table is one line per entry");
        check(t.contains(ID) && t.contains("another-thing"), "the table holds every id");
        check(t.contains("2026-11-11") && t.contains("用户说「暂停」") && t.contains("→ 检查 " + FILE)
                && t.contains("修于 833d99c") && t.contains("验证 test"), "the table holds the date, the words, the check, the fix and what was verified");
        check(t.contains("你为什么面板跳不出来"), "the second line holds its own words");
        check(Reported.table("这不是 JSON").contains("读不出来"), "a table of a ledger that is not JSON says so and does not throw");
        check(Reported.table("{}").contains("读不出来"), "a table of a ledger with no entries says so and does not throw");
        String thin = Reported.table(new JSONObject().put("entries",
                new JSONArray().put(new JSONObject().put("id", "only-an-id"))).toString());
        check(thin.split("\n").length == 1 && thin.contains("only-an-id"),
                "a table of an entry with nothing but an id: one line, and no throw");
        check("833d99c^".equals(Reported.beforeOf("833d99c")), "beforeOf(833d99c) is 833d99c^");

        // an entry with no revision before the fix: 没有修复前对照 where 修于 fix would stand, and nothing else changes
        String noProof = Reported.table(ledger(entry().put("before", "")
                .put("void_because", "这条检查是修复那个提交才写进文件的，没有修复前的对照")));
        check(noProof.contains("没有修复前对照"), "the table says 没有修复前对照 for an entry with no revision before the fix");
        check(!noProof.contains("修于"), "and that line has no 修于: there is no revision to name");
        check(noProof.trim().endsWith("验证 test"), "the line still ends with what was verified");
        String withFix = Reported.table(ledger(entry()));
        check(withFix.contains("修于 833d99c") && !withFix.contains("没有修复前对照"),
                "an entry that has a revision still says 修于 fix, and never 没有修复前对照");
    }
    private static final String AGENT_ENGINE = "src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java",
            HUD = "src/xyz/erapi0neer/xingyi/agent/tv/Hud.java",
            TVTOOLS = "src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java",
            SELF_REPAIR = "src/xyz/erapi0neer/xingyi/agent/tv/SelfRepair.java";

    /**
     * 2026-11-11 21:12 – 01:19 on the TV: 「你为什么面板跳不出来」. In takeover mode XiaoAi's own card is hidden and 星仪's
     * panel only went up when the sentence was final, 2–3 s after the first words — so while the user watched what they
     * were saying there was nothing on the screen at all. c2ea46d: {@code AgentEngine.listening} puts the panel up with
     * the words so far and follows them. This reads the source: whether the words really appear on a TV is not
     * something an offline check can see, and the ledger says so ({@code verified: none}).
     */
    private static void listening(Path root) {
        String engine = source(root, AGENT_ENGINE);
        String body = method(engine, "public void listening(String words)");
        check(engine != null && body.contains("heardWords = w") && body.contains("hud(new Ledger(")
                        && body.contains("w, \"xiaoai\""),
                "the panel goes up with the words so far, while the user is still speaking");
        check(engine != null && !body.isEmpty(), "AgentEngine.listening(String) is the entry the partial words come to");
        check(body.contains("if (w.isEmpty()) return;"), "an empty partial draws nothing: a blank panel is worse than none");
        check(body.contains("if (busy()) return;"), "a task that is running keeps the panel: the words do not draw over it");
    }

    /**
     * 2026-11-12 01:19 on the TV: 「自检全是问题啊」 — self_check said 「读屏、按键、模型线路都没问题」 while the panel was not
     * coming up at all, because it never looked at it. c2ea46d: {@code Hud.probe()} puts a card up and answers whether
     * its window was really added, and self_check's 面板 line is that answer. The panel line may come straight from
     * TvTools or, once the self-repair job lands, from {@code SelfRepair} — both count.
     */
    private static void selfCheck(Path root) {
        String hud = source(root, HUD), tv = source(root, TVTOOLS), repair = source(root, SELF_REPAIR);
        String probe = method(hud, "public boolean probe()");
        boolean fromTools = tv != null && tv.contains("hud().probe()");
        boolean fromRepair = tv != null && tv.contains("SelfRepair.run(this)") && repair != null && repair.contains("probe()");
        check(hud != null && probe.contains("show(new State(") && probe.contains("return up();") && (fromTools || fromRepair),
                "self_check puts a panel up and looks whether its window is there");
        check(hud != null && hud.contains("public boolean up()"), "up() is the answer: the window was added and not taken down");
        check(tv != null && tv.contains("\"self_check\""), "the tool the user is talking about is still self_check");
        check(tv != null && (tv.contains("\"面板\"") || fromRepair), "a panel that does not come up is said as a problem, not left out");
        check(hud != null && hud.contains("自检：这块面板"), "the card the probe puts up says what it is");
    }

    /**
     * 2026-11-14 中午: {@code STATE} printed {@code "xiaoai_ask_ms_ago": 1793861729734} when XiaoAi had never been asked
     * anything since boot — {@code now - 0} is the whole Unix epoch in milliseconds, about 56 years. Nothing reads the
     * field (it is a diagnostic), but a number that size in a STATE dump reads as a fault: the two people who looked at
     * it both asked whether the clock was broken. -1 is the one impossible age, and the source scan holds it there: the
     * offline core cannot build an AgentEngine (it needs a Context), so the rule is read off the source the same way
     * {@link #listening} reads the panel.
     */
    private static void stateAge(Path root) {
        String engine = source(root, AGENT_ENGINE);
        check(engine != null, "AgentEngine.java is where STATE is built");
        check(engine != null && engine.contains("xiaoaiAskAt == 0 ? -1 : now - xiaoaiAskAt"),
                "an age of 0 means never asked, and it is reported as -1, not as 56 years");
        check(engine != null && engine.contains("xiaoaiAskAt = System.currentTimeMillis()"),
                "the moment is still taken when XiaoAi is asked, so a real age is a real age");
        check(engine != null && engine.contains("System.currentTimeMillis() - xiaoaiAskAt > 20_000"),
                "the echo guard still uses 0 as \"long ago\", which is why the sentinel is -1 and not 0");
    }

    /**
     * 2026-11-14 13:12 on the TV: 「我今天好累啊」 came to 星仪 through XiaoAi while the official route was
     * probing and the gateway answered 429. Both routes were down, so {@code handBack} spoke 「星仪暂时连不上网络，
     * 这句交给小爱回答。」 — but it never set {@code t.reply}, so history.json, the transcript and recall_history all
     * recorded the raw 「…备用网关也未能应答：…（HTTP 429）」. What the user heard and what the record said were two
     * different things, and the record is the one recall_history will read back.
     */
    private static void handBack(Path root) {
        String engine = source(root, AGENT_ENGINE);
        String body = method(engine, "private boolean handBack(Ledger t)");
        String settle = method(engine, "private void handedBackAs(Ledger t, String said)");
        String late = method(engine, "private void lateRefusal(Ledger t, String later, int gen)");
        check(!body.isEmpty(), "AgentEngine.handBack(Ledger) is where a failed-for-want-of-a-model task goes back");
        // one place writes what the user gets: the record first, then the panel (2026-11-14 13:12)
        int set = settle.indexOf("t.reply = said;"), shown = settle.indexOf("hud(t, \"done\", said)");
        check(set >= 0 && shown > set, "the record is written before the panel shows it");
        check(body.contains("handedBackAs(t, say)") && body.contains("handedBackAs(t, handedSay)") && body.contains("handedBackAs(t, later)"),
                "every exit (XiaoAi acted, handed off, refused) records and shows its own sentence");
        // 2026-11-16 review: the wait gave up => treated as a refusal => 「请稍后再说一遍」 while XiaoAi was answering
        int handedBranch = body.indexOf("if (verdict == null || handedOff(verdict))");
        check(handedBranch > 0, "no word from XiaoAi yet counts as handed off, not as a refusal");
        int handedEnd = handedBranch < 0 ? -1 : body.indexOf("return true;", handedBranch);
        check(handedEnd > handedBranch && !body.substring(handedBranch, handedEnd).contains("VendorSpeech.speak"),
                "a handed-off sentence is not spoken over: XiaoAi answers it on the one TTS player");
        check(body.indexOf("clearEchoGate()") > handedEnd && handedEnd > 0,
                "only an explicit refusal opens the echo gate; while XiaoAi may be answering it stays shut");
        check(body.contains("slot.compareAndSet(null, GAVE_UP)") && body.contains("slot.compareAndSet(null, v)"),
                "the wait and the callback decide once, race-free, who came first");
        check(body.contains("Thread.currentThread().interrupt()"), "an interrupted wait keeps the thread's interrupt");
        check(late.contains("clearEchoGate()") && late.indexOf("cancelled(gen)") >= 0
                        && late.indexOf("cancelled(gen)") < late.indexOf("VendorSpeech.speak"),
                "a late refusal is still said, unless a stop or a newer request came meanwhile");
        check(engine != null && engine.contains("if (handBack(t)) { if (traced(t.source)) exchanged(t); break; }"),
                "a hand-back is this turn's answer on the panel's conversation line too");
        check(engine != null && engine.contains("failed.error = String.valueOf(e.getMessage())"),
                "the raw route error is still kept on the task itself, so the trace can show why");
        check(body.contains("AgentLoop.offlineReason(t.error)"), "the sentence names the cause the routes reported");
        check(!body.contains("\"星仪暂时连不上网络，"), "no line in handBack says 「连不上网络」 whatever the cause");
    }

    /**
     * What handBack says names the right cause (AgentLoop.offlineReason). The error texts are the TV's own, copied from
     * the 2026-11-11 and 11-14 transcripts. The one that matters most: a gateway that answered 429 is never said as
     * 「网络」 — a TV that got a 429 back in 0.6 s was on the network. On 11-14 it was said that way, the user asked
     * 「为什么没网络」 and went to fix the Wi-Fi while the gateway simply had no capacity left for MiniMax-M3.1-Flash.
     */
    private static void offlineReasons() {
        String r1312 = "官方 MiniMax 后台重新检查中；备用网关也未能应答：备用网关请求过于频繁，请稍后重试（HTTP 429）";
        String r1420 = "官方 MiniMax 余额不足，14:23 后重试；备用网关也未能应答：备用网关请求过于频繁，请稍后重试（HTTP 429）";
        String r2118 = "官方 MiniMax 余额不足，21:22 后重试；备用网关也未能应答：备用网关响应超时";
        String net = "官方 MiniMax 网络请求失败，请检查连接后重试；备用网关也未能应答：备用网关网络请求失败，请检查连接后重试";
        String tls = "备用网关安全连接失败，请检查电视时间和网络";
        String mixed = "官方 MiniMax 网络请求失败，请检查连接后重试；备用网关也未能应答：备用网关请求过于频繁，请稍后重试（HTTP 429）";
        check("星仪用的模型这会儿太忙".equals(AgentLoop.offlineReason(r1312)), "11-14 13:12: a gateway 429 is the model being busy");
        check(!AgentLoop.offlineReason(r1420).contains("网络"), "11-14 14:20: no balance + gateway 429 is never said as 「网络」");
        check("星仪用的模型这会儿响应太慢".equals(AgentLoop.offlineReason(r2118)), "11-11 21:18: a gateway timeout is the model being slow");
        check("星仪暂时连不上网络".equals(AgentLoop.offlineReason(net)), "both routes failing to connect is the network");
        check("星仪暂时连不上网络".equals(AgentLoop.offlineReason(tls)), "a TLS failure on the only route is the network");
        check("星仪用的模型这会儿太忙".equals(AgentLoop.offlineReason(mixed)),
                "the route tried last decides: the gateway answered 429, so the TV was online");
        check("星仪用的模型服务这会儿出了故障".equals(AgentLoop.offlineReason("备用网关服务暂时异常，请稍后重试（HTTP 502）")),
                "a 5xx is a fault on the model's side");
        check("星仪用的模型线路这会儿用不了".equals(AgentLoop.offlineReason("官方 MiniMax 余额不足（HTTP 402 · 1008）")),
                "no balance and nothing behind it: the line cannot be used");
        check(!AgentLoop.offlineReason(null).contains("网络") && !AgentLoop.offlineReason("no model route").contains("网络"),
                "what is not recognised is never said as the network");
        // 2026-11-16 review: the server answered and refused. Saying 「连不上它用的模型」 for these sends the user to
        // check the Wi-Fi, and each of them has one thing the user can do about it.
        String missing = "官方 MiniMax 余额不足（HTTP 402 · 1008）；备用网关也未能应答：备用网关没有找到该模型或接口（HTTP 404）";
        String rejected = "官方 MiniMax 余额不足（HTTP 402 · 1008）；备用网关也未能应答：备用网关拒绝了这次请求（HTTP 400 · 2013）";
        String cut = "官方 MiniMax 余额不足（HTTP 402 · 1008）；备用网关也未能应答：模型回答被截断，本次没有执行新的操作，请缩短请求后重试";
        String unfin = "官方 MiniMax 余额不足（HTTP 402 · 1008）；备用网关也未能应答：模型回答未完整结束，本次没有执行新的操作";
        String unsafe = "官方 MiniMax 余额不足（HTTP 402 · 1008）；备用网关也未能应答：判定内容不合规，本次没有执行新的操作（HTTP 200 · 1026）";
        check("星仪用的模型名字这会儿对不上，要去改一下配置".equals(AgentLoop.offlineReason(missing)),
                "a 404 model name is a configuration problem, not the network: " + AgentLoop.offlineReason(missing));
        check("星仪用的模型名字这会儿对不上，要去改一下配置".equals(AgentLoop.offlineReason(rejected)),
                "a 400 unknown model is the same: " + AgentLoop.offlineReason(rejected));
        for (String e : new String[]{cut, unfin})
            check(AgentLoop.offlineReason(e).contains("太长"),
                    "a cut answer asks for a shorter one: " + AgentLoop.offlineReason(e));
        check(AgentLoop.offlineReason(unsafe).contains("换个说法"),
                "content the model declined asks for different words: " + AgentLoop.offlineReason(unsafe));
        for (String e : new String[]{missing, rejected, cut, unfin, unsafe})
            check(!AgentLoop.offlineReason(e).contains("网络") && !AgentLoop.offlineReason(e).contains("连不上它用的模型"),
                    "none of the server's refusals is said as the network: " + AgentLoop.offlineReason(e));
        for (String e : new String[]{r1312, r1420, r2118, net, tls, mixed, missing, rejected, cut, unfin, unsafe})
            check(AgentLoop.offline(e), "still goes back to XiaoAi (offline): " + e.substring(0, Math.min(20, e.length())));
    }

    /** The file's text under the repository root, or null when it is not there. */
    private static String source(Path root, String path) {
        try {
            Path p = root.resolve(path);
            return Files.isRegularFile(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** One method's text, from its header to the closing brace at the class's own indent. */
    private static String method(String text, String header) {
        if (text == null) return "";
        int at = text.indexOf(header);
        if (at < 0) return "";
        int end = text.indexOf("\n    }", at);
        return end < 0 ? text.substring(at) : text.substring(at, end);
    }
}
