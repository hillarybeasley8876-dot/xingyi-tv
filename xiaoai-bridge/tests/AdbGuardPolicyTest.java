package xyz.erapi0neer.xiaoaibridge;

import xyz.erapi0neer.xiaoaibridge.AdbGuardPolicy.Action;

/** When the bridge switches the TV's ADB debugging back on, and when it leaves it alone. */
public final class AdbGuardPolicyTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }
    private static final long NEVER = Long.MAX_VALUE;

    public static void main(String[] args) {
        // the permission is the opt-in: without it nothing is touched, whatever the state
        check(AdbGuardPolicy.decide(false, 0, false, 999_999, NEVER, 0, true) == Action.NONE, "no permission: setting off");
        check(AdbGuardPolicy.decide(false, 1, false, 999_999, NEVER, 0, true) == Action.NONE, "no permission: listener down");

        // the firmware switched it off: switch it back on at once
        check(AdbGuardPolicy.decide(true, 0, false, 0, NEVER, 0, false) == Action.ENABLE, "setting off -> enable");
        check(AdbGuardPolicy.decide(true, 0, true, 0, NEVER, 0, false) == Action.ENABLE, "setting off even if something still answers");
        check(AdbGuardPolicy.decide(true, 0, false, 0, NEVER, 3, false) == Action.ENABLE, "third and fourth enable still allowed");

        // something keeps switching it off: stop fighting
        check(AdbGuardPolicy.decide(true, 0, false, 0, NEVER, AdbGuardPolicy.MAX_ENABLES_PER_WINDOW, false) == Action.NONE,
                "too many enables in the window: leave it");

        // everything fine
        check(AdbGuardPolicy.decide(true, 1, true, 0, NEVER, 0, false) == Action.NONE, "on and listening");
        check(AdbGuardPolicy.decide(true, 1, true, 500_000, 0, 0, true) == Action.NONE, "listening wins over every timer");

        // setting says on but nobody listens: wait a minute, then restart adbd once in a while
        check(AdbGuardPolicy.decide(true, 1, false, 0, NEVER, 0, false) == Action.NONE, "just noticed: wait");
        check(AdbGuardPolicy.decide(true, 1, false, AdbGuardPolicy.DOWN_BEFORE_RESTART_MS - 1, NEVER, 0, false) == Action.NONE, "not yet a minute");
        check(AdbGuardPolicy.decide(true, 1, false, AdbGuardPolicy.DOWN_BEFORE_RESTART_MS, NEVER, 0, false) == Action.RESTART, "a minute down: restart");
        check(AdbGuardPolicy.decide(true, 1, false, 600_000, 60_000, 0, false) == Action.NONE, "restarted a minute ago: cool down");
        check(AdbGuardPolicy.decide(true, 1, false, 600_000, AdbGuardPolicy.RESTART_COOLDOWN_MS, 0, false) == Action.RESTART, "cool-down over");

        // a task is waiting for adb: do not wait out the minute, but keep the cool-down
        check(AdbGuardPolicy.decide(true, 1, false, 0, NEVER, 0, true) == Action.RESTART, "urgent: restart now");
        check(AdbGuardPolicy.decide(true, 1, false, 0, 1000, 0, true) == Action.NONE, "urgent still respects the cool-down");

        // unreadable setting: never act on a guess
        check(AdbGuardPolicy.decide(true, -1, false, 999_999, NEVER, 0, true) == Action.NONE, "unknown setting");

        // the status line the PC side parses (tools/adb_recover.py parse_guard reads exactly these keys)
        check(AdbGuardPolicy.describe(true, 1, true, 0, NEVER)
                .equals("permission=granted adb_enabled=1 listening=yes enables_2min=0 last_restart=never"), "describe: all well");
        check(AdbGuardPolicy.describe(false, 0, false, 3, 90_500)
                .equals("permission=missing adb_enabled=0 listening=no enables_2min=3 last_restart=90s_ago"), "describe: guard off, adb off");
        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (adb guard policy)");
        if (fail > 0) System.exit(1);
    }
}
