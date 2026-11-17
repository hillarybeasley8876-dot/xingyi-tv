package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Compiling an app the system never managed to compile (2026-11-10). B站 took 61 s from its link to its first page: its
 * code had never been compiled since it was installed, so every start unpacked and verified 38 dex files (263 MB).
 * 「dumpsys package dexopt」 said {@code [tv.danmaku.bili] … arm: [status=run-from-apk] [reason=unknown]}, and the
 * nightly compile died after 95 s every night (dex2oat is a 32-bit process here and its compact-dex step keeps all the
 * dex data in one buffer, which runs out of address space). Compiled by hand with that step off it took 4 min 14 s and
 * the same link reached the page in 15 s.
 *
 * <p>Pure: the host reads the dexopt state, compiles, and keeps the record. Only apps the user installed, never 星仪's
 * own, and the same code is not tried again for {@link #RETRY_MS} — an app the system could not compile is not worth
 * four minutes every night, but an update (a new path) is worth a try at once.
 */
public final class AppCompile {
    private AppCompile() { }

    /** How long one package's same code is left alone after a try, whatever came of it. */
    public static final long RETRY_MS = 7 * 24 * 3_600_000L;
    /** How many packages the record keeps; the oldest try is dropped. */
    static final int MAX_TRIED = 20;

    /** A package block's header: two spaces and the package in brackets ({@code [tv.danmaku.bili]}). */
    private static final Pattern PKG = Pattern.compile("^\\s{0,4}\\[([A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*)\\]\\s*$");
    /** One APK of that package: {@code path: /data/app/tv.danmaku.bili-2h0…==/base.apk} (a split has its own line). */
    private static final Pattern PATH = Pattern.compile("^\\s*path:\\s*(\\S+)\\s*$");
    /** Its compiler state: {@code arm: [status=run-from-apk] [reason=unknown]} (one line per ABI). */
    private static final Pattern STATUS = Pattern.compile("^\\s*\\S+:\\s*\\[status=([^\\]]*)\\]");
    /** The state of code that was never compiled: it runs from the APK, unpacked and verified on every start. */
    private static final String UNCOMPILED = "run-from-apk";

    /** What a package name may be: a dot between two non-empty parts, each letters, digits and underscores. */
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    /** The longest name the on-demand compile takes (no installed app's name comes near it). */
    private static final int MAX_PKG = 200;

    /**
     * Whether {@code pkg} may be handed to the compiler as an installed package name: letters, digits, dots and
     * underscores only, at least one dot with a part on each side, at most 200 characters. Anything else
     * — a shell character, a path, an empty part, nothing at all — is refused before it reaches the TV's shell.
     */
    public static boolean validPackage(String pkg) {
        return pkg != null && pkg.length() <= MAX_PKG && PACKAGE.matcher(pkg).matches();
    }

    /**
     * The packages whose code was never compiled, each mapped to the path of the APK that says so, in the order the
     * text has them. A package can have several {@code path:} lines (splits), each with its own status line: only the
     * one that is {@code run-from-apk} counts, and of several the base APK. Anything unreadable → an empty map.
     */
    public static Map<String, String> uncompiled(String dexoptText) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dexoptText == null || dexoptText.isEmpty()) return out;
        String pkg = null, path = null, chosen = null;
        boolean chosenBase = false;
        for (String line : dexoptText.split("\\r?\\n")) {
            Matcher head = PKG.matcher(line);
            if (head.matches()) {
                pkg = head.group(1); path = null; chosen = null; chosenBase = false;
                continue;
            }
            Matcher p = PATH.matcher(line);
            if (p.matches()) { path = p.group(1); continue; }
            Matcher s = STATUS.matcher(line);
            if (pkg == null || path == null || !s.find() || !UNCOMPILED.equals(s.group(1))) continue;
            // several APKs of one package can be in this state: the base one is the package's code
            boolean isBase = path.endsWith("/base.apk");
            if (chosen == null || (isBase && !chosenBase)) { out.put(pkg, path); chosen = path; chosenBase = isBase; }
        }
        return out;
    }

    /**
     * The package to compile now, or null: only one the user installed (a system app is never touched), never 星仪's
     * own, and not one whose <em>same path</em> was tried within the last {@link #RETRY_MS} — an app that was updated
     * has a new path and is tried at once. The first that qualifies, in the order {@link #uncompiled} found them.
     */
    public static String next(Map<String, String> uncompiled, Set<String> userPackages, JSONObject tried, long now) {
        if (uncompiled == null || userPackages == null) return null;
        for (Map.Entry<String, String> e : uncompiled.entrySet()) {
            String pkg = e.getKey();
            if (pkg == null || !userPackages.contains(pkg) || pkg.startsWith(MemoryCare.OURS)) continue;
            JSONObject was = tried == null ? null : tried.optJSONObject(pkg);
            if (was == null) return pkg;
            String path = e.getValue() == null ? "" : e.getValue();
            // a new path is the app's code after an update: what was tried before says nothing about it
            if (!path.equals(was.optString("path", "")) || now - was.optLong("at") >= RETRY_MS) return pkg;
        }
        return null;
    }

    /**
     * The record with {@code pkg}'s try added, or the one it had replaced: {@code {"<pkg>": {"path", "at", "ok"}}}. A
     * new object — {@code before} is left as it was — with at most {@link #MAX_TRIED} entries, the oldest try dropped.
     */
    public static JSONObject tried(JSONObject before, String pkg, String path, long at, boolean ok) throws JSONException {
        Map<String, JSONObject> all = new LinkedHashMap<>();
        if (before != null) {
            for (Iterator<String> it = before.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject v = before.optJSONObject(k);
                if (v != null && !k.equals(pkg)) all.put(k, v);
            }
        }
        all.put(pkg, new JSONObject().put("path", path == null ? "" : path).put("at", at).put("ok", ok));
        while (all.size() > MAX_TRIED) all.remove(oldest(all));
        JSONObject out = new JSONObject();
        for (Map.Entry<String, JSONObject> e : all.entrySet()) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static String oldest(Map<String, JSONObject> all) {
        String key = null;
        long at = 0;
        for (Map.Entry<String, JSONObject> e : all.entrySet()) {
            long t = e.getValue().optLong("at");
            if (key == null || t < at) { key = e.getKey(); at = t; }
        }
        return key;
    }

    /**
     * What the guard records, and what 星仪 says when the TV was busy for minutes in standby:
     * 「给哔哩哔哩做了一次系统代码优化（用了 4 分钟），以后打开它会快很多」 — or, when the package manager would not,
     * 「想给哔哩哔哩做系统代码优化，没成功（7 天后再试）」.
     */
    public static String line(String label, boolean ok, long tookMs) {
        if (!ok) return "想给" + label + "做系统代码优化，没成功（" + (RETRY_MS / 86_400_000L) + " 天后再试）";
        long sec = Math.max(0, tookMs) / 1000L;
        // 「用了不到 1 分钟」 / 「用了 4 分钟」: no space before 不到 (2026-11-11 00:36 on the TV it read 「用了 不到 1 分钟」)
        String took = sec < 60 ? "不到 1 分钟" : " " + Math.round(sec / 60.0) + " 分钟";
        return "给" + label + "做了一次系统代码优化（用了" + took + "），以后打开它会快很多";
    }
}
