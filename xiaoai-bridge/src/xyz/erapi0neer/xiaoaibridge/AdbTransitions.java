package xyz.erapi0neer.xiaoaibridge;

import java.util.ArrayList;
import java.util.List;

/** What the adb watch writes down. The TV's own adb listener went away once without a reboot and nothing recorded
 * when or why, so every beat compares three facts with the previous beat and reports only the changes: whether the
 * loopback listener answers, the "USB debugging" setting, and the boot counter (a reboot). Negative numbers mean
 * "unknown" (never seen, or the setting could not be read) and never count as a change. Pure Java: no Android. */
final class AdbTransitions {
    private Boolean up;
    private long boot;
    private int enabled;

    AdbTransitions(Boolean up, long boot, int enabled) {
        this.up = up; this.boot = boot; this.enabled = enabled;
    }

    boolean up() { return up != null && up; }
    long boot() { return boot; }
    int enabled() { return enabled; }

    List<String> beat(boolean nowUp, long nowBoot, int nowEnabled) {
        List<String> lines = new ArrayList<>();
        if (up == null) lines.add("watch started, adb 5555 is " + word(nowUp));
        else if (up != nowUp) lines.add("adb 5555 " + word(up) + " -> " + word(nowUp));
        if (boot >= 0 && nowBoot >= 0 && nowBoot != boot) lines.add("reboot: boot_count " + boot + " -> " + nowBoot);
        if (enabled >= 0 && nowEnabled >= 0 && nowEnabled != enabled) lines.add("adb_enabled " + enabled + " -> " + nowEnabled);
        up = nowUp;
        if (nowBoot >= 0) boot = nowBoot;
        if (nowEnabled >= 0) enabled = nowEnabled;
        return lines;
    }

    private static String word(boolean up) { return up ? "up" : "down"; }
}
