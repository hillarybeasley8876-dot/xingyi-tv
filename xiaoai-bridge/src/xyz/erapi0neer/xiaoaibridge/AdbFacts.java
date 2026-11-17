package xyz.erapi0neer.xiaoaibridge;

/** The bracket at the end of every adb-watch.txt line. Tools read it (tools/reboot_test.py selects the lines of one boot by
 * "boot_count=N"), so existing keys keep their place and new ones are only appended. Pure Java: no Android. */
final class AdbFacts {
    private AdbFacts() { }

    static String format(int adbEnabled, long bootCount, long uptimeSeconds, String persistPort, String servicePort,
                         boolean guard, String bootReason, String powerState) {
        return "[adb_enabled=" + adbEnabled + " boot_count=" + bootCount + " uptime=" + uptimeSeconds + "s"
                + " persist.adb.tcp.port=" + persistPort + " service.adb.tcp.port=" + servicePort
                + " guard=" + (guard ? "on" : "off") + " boot_reason=" + bootReason + " power_state=" + powerState + "]";
    }
}
