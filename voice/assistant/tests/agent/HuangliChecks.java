package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** almanac (2026-11-07): the almanac's 宜忌 by day, from Baidu's calendar card as saved that night (October to December). */
final class HuangliChecks {
    private HuangliChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    /** 2026-11-07 02:00 in Beijing (立冬). */
    private static final long NOW = 1793988000000L;

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "almanac checks threw " + e);
        }
    }

    static void checks() throws Exception {
        List<Huangli.Day> all = Huangli.days(fixture("huangli-2026-11.json"));
        check(all.size() == 92, "three months: " + all.size());
        Huangli.Day today = Huangli.on(all, 2026, 11, 7), tomorrow = Huangli.on(all, 2026, 11, 8);
        String s = Huangli.speak(today, "今天", "");
        check(s.equals("今天（11 月 7 日，农历九月廿九，乙酉日，立冬）宜打扫；忌馀事勿取（其余的事都不宜）"), "today: " + s);
        s = Huangli.speak(tomorrow, "明天", "搬家");
        check(s.startsWith("明天（11 月 8 日，农历九月三十，丙戌日）忌搬家；这天宜安床、祭祀"), "a thing it says no to: " + s);
        s = Huangli.speak(tomorrow, "明天", "祭拜");
        check(s.equals("明天（11 月 8 日，农历九月三十，丙戌日）宜祭拜（黄历写的是宜祭祀）"), "the almanac's own word for it: " + s);
        s = Huangli.speak(today, "今天", "理发");
        check(s.equals("今天（11 月 7 日，农历九月廿九，乙酉日，立冬）宜打扫，忌馀事勿取：理发不宜"), "馀事勿取 covers what is not listed: " + s);
        s = Huangli.speak(tomorrow, "明天", "钓鱼");
        check(s.startsWith("明天（11 月 8 日，农历九月三十，丙戌日）黄历上没说宜不宜钓鱼：宜安床、"), "neither said: " + s);

        List<Huangli.Day> dec = new ArrayList<>();
        for (Huangli.Day d : all) if (d.month == 12) dec.add(d);
        s = Huangli.goodDays(dec, "下个月", "结婚");
        check(s.equals("下个月宜结婚的日子有 3 天：12 月 4 日（周五）、12 月 17 日（周四）、12 月 24 日（周四）"), "a month's days for a wedding: " + s);
        check(Huangli.goodDays(dec, "下个月", "跳伞").equals("下个月黄历上没有宜跳伞的日子"), "none");

        // the review, 2026-11-07: this month's days from today on; 明年 and a year said
        List<Huangli.Day> nov = Huangli.month(all, 2026, 11, NOW);
        check(!nov.isEmpty() && nov.get(0).day == 7 && Huangli.month(all, 2026, 12, NOW).size() == 31, "this month from today; next month whole");
        check(Huangli.monthOf("2027年3月", NOW)[0] == 2027 && Huangli.monthOf("明年3月", NOW)[0] == 2027 && Huangli.monthOf("今年12月", NOW)[0] == 2026
                && Huangli.monthOf("今年12月", NOW)[1] == 12, "a year said");

        int[] d = Huangli.dayOf("明天", NOW);
        check(d[0] == 2026 && d[1] == 11 && d[2] == 8 && d[3] == 1 && Huangli.dayOf("2026-12-24", NOW)[2] == 24 && Huangli.dayOf("某天", NOW) == null, "days");
        int[] m = Huangli.monthOf("下个月", NOW);
        check(m[0] == 2026 && m[1] == 12 && Huangli.monthOf("1 月", NOW)[0] == 2027 && Huangli.monthOf("这个月", NOW)[1] == 11, "months: 1 月 in November is next year's");
        check(Huangli.url(2026, 11).endsWith("query=2026%E5%B9%B411%E6%9C%88"), "the card's query: 2026年11月");

        // a tool's own final ends the task, unless the request asks for something done after it
        org.json.JSONArray call = new org.json.JSONArray().put(AgentCoreTest.o("id", "a", "function", AgentCoreTest.o("name", "almanac", "arguments", "{}")));
        org.json.JSONObject[] said = {AgentCoreTest.o("ok", true, "answer", "明天忌搬家", "final", true)};
        check(!AgentLoop.searchThenAct("明天适合搬家吗", call, said) && AgentLoop.searchAnswer(call, said).equals("明天忌搬家"), "the almanac's own final is the answer");
        check(AgentLoop.searchThenAct("明天适合搬家吗，适合的话提醒我", call, said), "…and 提醒我 still gets its round");
    }
}
