package xyz.erapi0neer.xingyi.agent;

/**
 * What the home screen says under an app whose playback is paused (Orrery 「轴」, 2026-11-08): the launcher has no right
 * to read media sessions, 星仪's runtime has — so the one line about the focused app comes from here.
 * 「上次在听《江南》林俊杰」 for the music apps, 「上次在看《…》」 for everything else.
 */
public final class HomeNotes {
    private HomeNotes() { }

    /** The apps whose sessions are music: 在听, not 在看. A TV build of a phone package is the same app
     * (com.netease.cloudmusic → com.netease.cloudmusic.tv), and QQ音乐's TV build has a name of its own. */
    private static final String[] MUSIC = {"com.netease.cloudmusic", "com.tencent.qqmusic", "com.tencent.qqmusictv", "com.kugou"};

    /** Whether pkg is one of the music apps. */
    public static boolean music(String pkg) {
        String p = pkg == null ? "" : pkg.trim();
        for (String m : MUSIC) if (p.equals(m) || p.startsWith(m + ".")) return true;
        return false;
    }

    /** The line for a paused session, or "" when there is no title to name (nothing worth saying about it). */
    public static String paused(String pkg, String title, String artist) {
        String t = title == null ? "" : title.trim(), a = artist == null ? "" : artist.trim();
        if (t.isEmpty()) return "";
        if (!music(pkg)) return "上次在看《" + t + "》";
        return "上次在听《" + t + "》" + a;
    }
}
