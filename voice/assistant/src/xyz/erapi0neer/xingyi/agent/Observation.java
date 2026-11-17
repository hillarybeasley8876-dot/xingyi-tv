package xyz.erapi0neer.xingyi.agent;

/** What is on the TV right now, in one line the TV can read aloud when a task stops early. Only names a human says
 * out loud: the app's label and the title of what is playing — never a package name or an activity class. */
public final class Observation {
    /** A title longer than this is cut: the sentence is spoken, not read. */
    private static final int MAX_TITLE = 30;

    private Observation() { }

    public static String describe(String appLabel, String playingTitle) {
        String app = appLabel == null ? "" : appLabel.trim();
        if (app.isEmpty()) return "";
        String title = playingTitle == null ? "" : playingTitle.trim();
        if (title.isEmpty()) return "前台是" + app;
        return "前台是" + app + "，正在播放《" + (title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) + "…" : title) + "》";
    }
}
