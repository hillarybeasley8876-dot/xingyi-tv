package xyz.erapi0neer.xiaoaibridge;

import java.util.List;

/** Follow-up memory: the last 3 turns of the last 3 minutes, oldest first. */
public final class ConversationTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }

    public static void main(String[] args) {
        Conversation c = new Conversation();
        long t = 1_793_727_000_000L;
        check(c.recent(t).isEmpty(), "empty at start");
        c.add("今天北京天气", "晴，18度", t);
        c.add("那明天呢", "多云，16度", t + 10_000);
        List<String[]> r = c.recent(t + 20_000);
        check(r.size() == 2 && r.get(0)[0].equals("今天北京天气") && r.get(1)[1].equals("多云，16度"), "order oldest first");
        r.clear();
        check(c.recent(t + 20_000).size() == 2, "recent returns a copy");
        c.add("q3", "a3", t + 30_000);
        c.add("q4", "a4", t + 40_000);
        r = c.recent(t + 50_000);
        check(r.size() == 3 && r.get(0)[0].equals("那明天呢") && r.get(2)[0].equals("q4"), "at most 3 turns, oldest dropped");
        r = c.recent(t + 10_000 + Conversation.WINDOW_MS);
        check(r.size() == 2 && r.get(0)[0].equals("q3"), "turns older than the window are left out");
        check(c.recent(t + 40_000 + Conversation.WINDOW_MS).isEmpty(), "all expired");
        c.clear();
        check(c.recent(t + 50_000).isEmpty(), "clear");
        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (conversation)");
        if (fail > 0) System.exit(1);
    }
}
