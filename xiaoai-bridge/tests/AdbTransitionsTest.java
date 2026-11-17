package xyz.erapi0neer.xiaoaibridge;

import java.util.List;

/** The adb watch writes a line only when something changed: the heartbeat comes every 20 seconds. */
public final class AdbTransitionsTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }

    public static void main(String[] args) {
        AdbTransitions t = new AdbTransitions(null, -1, -1);
        List<String> first = t.beat(true, 7, 1);
        check(first.size() == 1 && first.get(0).contains("watch started") && first.get(0).contains("up"), "first beat says it started: " + first);
        check(t.beat(true, 7, 1).isEmpty(), "nothing changed, nothing written");
        check(t.beat(true, 7, 1).isEmpty(), "still nothing");

        List<String> down = t.beat(false, 7, 1);
        check(down.size() == 1 && down.get(0).contains("up -> down"), "listener gone: " + down);
        check(t.beat(false, 7, 1).isEmpty(), "staying down is not repeated");

        List<String> off = t.beat(false, 7, 0);
        check(off.size() == 1 && off.get(0).contains("adb_enabled 1 -> 0"), "the setting flipped: " + off);

        List<String> both = t.beat(true, 8, 1);
        check(both.size() == 3 && both.get(0).contains("down -> up") && both.get(1).contains("reboot")
                && both.get(1).contains("7 -> 8") && both.get(2).contains("adb_enabled 0 -> 1"), "reboot + back up + setting: " + both);

        // a restarted process resumes from what the last one saw instead of announcing a start
        AdbTransitions again = new AdbTransitions(Boolean.TRUE, 8, 1);
        check(again.beat(true, 8, 1).isEmpty(), "restored memory: quiet when nothing changed");
        List<String> gone = again.beat(false, 8, 1);
        check(gone.size() == 1 && gone.get(0).contains("up -> down"), "restored memory: a change is still noticed");
        check(!again.up() && again.boot() == 8 && again.enabled() == 1, "state to persist");

        // unreadable settings (-1) never count as a reboot or a flip
        AdbTransitions blind = new AdbTransitions(Boolean.TRUE, -1, -1);
        check(blind.beat(true, -1, -1).isEmpty(), "unreadable settings stay quiet");
        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (adb watch)");
        if (fail > 0) System.exit(1);
    }
}
