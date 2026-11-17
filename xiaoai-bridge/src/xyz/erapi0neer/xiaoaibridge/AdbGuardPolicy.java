package xyz.erapi0neer.xiaoaibridge;

/** Both the PC and 星仪's own key presses and screenshots go through the TV's adb listener, and Xiaomi's firmware
 * switches "ADB debugging" off when the TV goes to standby or is shut down (seen on Android 9 Xiaomi TVs; the 2026-11-04
 * outage fits). This decides when the bridge switches it back on. Pure Java: no Android.
 *
 * The bridge only acts when it holds WRITE_SECURE_SETTINGS, which can be granted only over adb with `pm grant`: that
 * grant is the owner's opt-in, `pm revoke` is the off switch. */
final class AdbGuardPolicy {
    enum Action { NONE, ENABLE, RESTART }

    /** Switching it back on this many times within the window means something keeps switching it off: stop and leave it. */
    static final int MAX_ENABLES_PER_WINDOW = 4;
    static final long ENABLE_WINDOW_MS = 120_000;
    /** The setting says on but the listener is gone: give the system this long to bring it back by itself. */
    static final long DOWN_BEFORE_RESTART_MS = 60_000;
    static final long RESTART_COOLDOWN_MS = 5 * 60_000;

    private AdbGuardPolicy() { }

    /**
     * @param permitted      WRITE_SECURE_SETTINGS has been granted
     * @param adbEnabled     the adb_enabled setting: 1 on, 0 off, negative when it could not be read
     * @param listening      the loopback listener accepts connections
     * @param downForMs      how long the listener has been seen down (only meaningful when not listening)
     * @param sinceRestartMs how long ago the guard last restarted adbd (Long.MAX_VALUE when it never did)
     * @param recentEnables  how many times the guard switched it on within ENABLE_WINDOW_MS
     * @param urgent         someone is waiting for adb right now (a task): do not wait out DOWN_BEFORE_RESTART_MS
     */
    static Action decide(boolean permitted, int adbEnabled, boolean listening, long downForMs, long sinceRestartMs,
                         int recentEnables, boolean urgent) {
        if (!permitted) return Action.NONE;
        if (adbEnabled == 0) return recentEnables >= MAX_ENABLES_PER_WINDOW ? Action.NONE : Action.ENABLE;
        if (adbEnabled == 1 && !listening && (urgent || downForMs >= DOWN_BEFORE_RESTART_MS)
                && sinceRestartMs >= RESTART_COOLDOWN_MS) return Action.RESTART;
        return Action.NONE;
    }

    /**
     * One line for status.txt, parsed by tools/adb_recover.py (keep the key names in step with its parse_guard).
     * {@code sinceRestartMs} is Long.MAX_VALUE when the guard never restarted adbd.
     */
    static String describe(boolean permitted, int adbEnabled, boolean listening, int recentEnables, long sinceRestartMs) {
        return "permission=" + (permitted ? "granted" : "missing") + " adb_enabled=" + adbEnabled
                + " listening=" + (listening ? "yes" : "no") + " enables_2min=" + recentEnables
                + " last_restart=" + (sinceRestartMs == Long.MAX_VALUE ? "never" : sinceRestartMs / 1000 + "s_ago");
    }
}
