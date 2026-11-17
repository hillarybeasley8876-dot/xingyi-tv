package xyz.erapi0neer.xiaoaibridge;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import xyz.erapi0neer.xiaoaibridge.AdbGuardPolicy.Action;

/** Watches the TV's own adb listener (loopback port 5555), which both the PC and 星仪's key presses and screenshots
 * depend on, and keeps it available. Runs on the heartbeat, on every change of the "USB debugging" setting, and
 * when a task finds the listener refusing connections.
 *
 * Record: files/adb-watch.txt (readable with `adb pull`), a line only when something changes: the listener going away or
 * coming back, the setting flipping, a reboot, the guard acting. Each line carries the facts that tell the causes apart.
 *
 * Guard: Xiaomi's firmware switches ADB debugging off at standby; with WRITE_SECURE_SETTINGS (granted over adb with
 * `pm grant`, taken away again with `pm revoke`) the setting is switched back on, see AdbGuardPolicy. */
final class AdbWatch {
    private static final String PREFS = "adb_watch";
    private static final String SECURE = "android.permission.WRITE_SECURE_SETTINGS";
    private static final long CAP = 32 * 1024;
    private static final long COMEBACK_WAIT_MS = 4000;
    /** When the guard switched the setting on, oldest first (this process only). */
    private static final ArrayDeque<Long> ENABLES = new ArrayDeque<>();

    private AdbWatch() { }

    /** One beat. Blocks for a few seconds at most, so call it off the main thread. */
    static synchronized void beat(Context context) { run(context, false); }

    /** A task found the listener refusing connections: act without the usual waiting; true when it answers afterwards. */
    static synchronized boolean healNow(Context context) {
        run(context, true);
        return listening();
    }

    /** A status line (see AdbGuardPolicy.describe) plus the last thing the guard did. Opens a socket: not on the main thread. */
    static synchronized String summary(Context context) {
        Context c = context.getApplicationContext();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        while (!ENABLES.isEmpty() && now - ENABLES.peekFirst() > AdbGuardPolicy.ENABLE_WINDOW_MS) ENABLES.removeFirst();
        long restart = p.getLong("last_restart", 0);
        String line = AdbGuardPolicy.describe(c.checkSelfPermission(SECURE) == PackageManager.PERMISSION_GRANTED,
                Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, -1), listening(), ENABLES.size(),
                restart == 0 ? Long.MAX_VALUE : now - restart);
        String last = p.getString("last_action", "");
        return last.isEmpty() ? line : line + "\nadb guard last action: " + last;
    }

    private static void run(Context context, boolean urgent) {
        Context c = context.getApplicationContext();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AdbTransitions t = new AdbTransitions(p.contains("up") ? Boolean.valueOf(p.getBoolean("up", false)) : null,
                p.getLong("boot", -1), p.getInt("enabled", -1));
        ContentResolver cr = c.getContentResolver();
        long now = System.currentTimeMillis();
        boolean up = listening();
        long boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1);
        int enabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, -1);
        List<String> lines = new ArrayList<>(t.beat(up, boot, enabled));

        long downSince = up ? 0 : p.getLong("down_since", 0) > 0 ? p.getLong("down_since", 0) : now;
        long lastRestart = p.getLong("last_restart", 0);
        while (!ENABLES.isEmpty() && now - ENABLES.peekFirst() > AdbGuardPolicy.ENABLE_WINDOW_MS) ENABLES.removeFirst();
        boolean permitted = c.checkSelfPermission(SECURE) == PackageManager.PERMISSION_GRANTED;
        Action action = AdbGuardPolicy.decide(permitted, enabled, up, up ? 0 : now - downSince,
                lastRestart == 0 ? Long.MAX_VALUE : now - lastRestart, ENABLES.size(), urgent);

        if (action != Action.NONE) {
            try {
                String what;
                if (action == Action.ENABLE) {
                    Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
                    ENABLES.addLast(now);
                    what = "guard: adb_enabled 0 -> 1 (the TV had switched it off)";
                } else {
                    Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0);
                    SystemClock.sleep(1000);
                    Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
                    lastRestart = now;
                    what = "guard: adb_enabled 1 -> 0 -> 1 (the listener had been down " + (now - downSince) / 1000 + " s)";
                }
                long started = SystemClock.elapsedRealtime();
                boolean back = waitListening(COMEBACK_WAIT_MS);
                lines.add(what + (back ? ", listener back after " + (SystemClock.elapsedRealtime() - started) + " ms"
                        : ", listener still down after " + COMEBACK_WAIT_MS / 1000 + " s"));
                up = back;
                enabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, -1);
                if (up) downSince = 0;
                t.beat(up, boot, enabled); // bring the memory up to date so the next beat reports only what is new
            } catch (SecurityException | IllegalStateException e) {
                lines.add("guard: could not write the setting (" + e.getClass().getSimpleName() + ")");
            }
        }
        p.edit().putBoolean("up", t.up()).putLong("boot", t.boot()).putInt("enabled", t.enabled())
                .putLong("down_since", up ? 0 : downSince).putLong("last_restart", lastRestart).apply();
        if (lines.isEmpty()) return;
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        for (String l : lines) if (l.startsWith("guard:")) p.edit().putString("last_action", stamp + " " + l).apply();
        append(c, lines, facts(c, enabled, boot, permitted));
    }

    /** A line for something that is not about adb itself but explains what happens to it: the screen going off or on.
     * The TV rebooted once on its own while in standby (2026-11-04 14:16, boot reason "quiescent", nobody touched it);
     * with these lines, the next time shows how long it had been in standby. */
    static synchronized void note(Context context, String what) {
        Context c = context.getApplicationContext();
        ContentResolver cr = c.getContentResolver();
        append(c, Collections.singletonList(what), facts(c, Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, -1),
                Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1), c.checkSelfPermission(SECURE) == PackageManager.PERMISSION_GRANTED));
    }

    /** The bracket that ends every line (see AdbFacts). Gathered only when a line is written: each getprop costs a process. */
    private static String facts(Context c, int enabled, long boot, boolean permitted) {
        String powerState = Settings.Global.getString(c.getContentResolver(), "mitv_power_state");
        return "  " + AdbFacts.format(enabled, boot, SystemClock.elapsedRealtime() / 1000, prop("persist.adb.tcp.port"),
                prop("service.adb.tcp.port"), permitted, prop("sys.boot.reason"), powerState == null ? "-" : powerState);
    }

    /** True when something accepts a connection on the TV's own adb port; a refusal comes back at once. */
    static boolean listening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean waitListening(long millis) {
        long end = SystemClock.elapsedRealtime() + millis;
        while (true) {
            if (listening()) return true;
            if (SystemClock.elapsedRealtime() >= end) return false;
            SystemClock.sleep(400);
        }
    }

    /** A system property by name through getprop; "-" when it is unset, "?" when it could not be read. */
    private static String prop(String name) {
        Process process = null;
        try {
            process = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            process.waitFor(1, TimeUnit.SECONDS);
            return line == null || line.isEmpty() ? "-" : line;
        } catch (IOException e) {
            return "?";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "?";
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static void append(Context c, List<String> lines, String facts) {
        File dir = c.getExternalFilesDir(null);
        File file = new File(dir == null ? c.getFilesDir() : dir, "adb-watch.txt");
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        StringBuilder out = new StringBuilder();
        for (String l : lines) out.append(stamp).append("  ").append(l).append(facts).append('\n');
        try {
            if (file.length() > CAP) keepNewerHalf(file);
            try (FileOutputStream f = new FileOutputStream(file, true)) {
                f.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // a record that cannot be written must not take the heartbeat down
        }
    }

    private static void keepNewerHalf(File file) throws IOException {
        byte[] all = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int n = 0;
            while (n < all.length) {
                int r = in.read(all, n, all.length - n);
                if (r < 0) break;
                n += r;
            }
        }
        int from = all.length / 2;
        while (from < all.length && all[from] != '\n') from++;
        from++;
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (from < all.length) out.write(all, from, all.length - from);
        }
    }
}
