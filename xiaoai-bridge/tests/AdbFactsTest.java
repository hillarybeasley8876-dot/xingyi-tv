package xyz.erapi0neer.xiaoaibridge;

/** The facts bracket of adb-watch.txt lines: the old keys keep their order, new keys come last. */
public final class AdbFactsTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }

    public static void main(String[] args) {
        String line = AdbFacts.format(1, 38, 22, "5555", "5555", true, "reboot,adb", "usermode");
        check(line.equals("[adb_enabled=1 boot_count=38 uptime=22s persist.adb.tcp.port=5555 service.adb.tcp.port=5555 guard=on"
                + " boot_reason=reboot,adb power_state=usermode]"), "exact format: " + line);
        // what the TV wrote on 2026-11-04 22:03 (before the two new keys existed) is a prefix of the new bracket
        check(line.startsWith("[adb_enabled=1 boot_count=38 uptime=22s persist.adb.tcp.port=5555 service.adb.tcp.port=5555 guard=on"),
                "old keys unchanged and in place");
        // tools/reboot_test.py finds the lines of a boot by "boot_count=N " or "boot_count=N]": both must stay possible
        check(line.contains("boot_count=38 "), "boot_count is followed by a space");
        check(AdbFacts.format(-1, -1, 0, "-", "-", false, "?", "?").equals(
                "[adb_enabled=-1 boot_count=-1 uptime=0s persist.adb.tcp.port=- service.adb.tcp.port=- guard=off boot_reason=? power_state=?]"),
                "unknown values print as they are");
        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (adb facts)");
        if (fail > 0) System.exit(1);
    }
}
