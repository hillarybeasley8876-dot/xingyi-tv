package xyz.erapi0neer.xingyi.agent;

/**
 * What one app's own players are doing, read out of {@code dumpsys audio}. 云视听极光 keeps no media session, so its
 * state can only be read here (2026-11-11 20:03 on the TV, the audio service's own event log):
 *
 * <pre>
 *   players:
 *   ID:135 -- type:android.media.AudioTrack -- u/pid:10021/18067 -- state:started -- attr:AudioAttributes: usage=USAGE_MEDIA …
 * </pre>
 *
 * {@code started} while the episode (or the ad before it) plays, {@code paused} when it is paused, and no line of that
 * uid at all when nothing of the app is open. Only the asked uid's own media lines count: {@code ID:87}, an AudioTrack
 * of uid 1000 that is always {@code started} for a notification, is not the show.
 */
public final class AudioPlayers {
    public static final String STARTED = "started", PAUSED = "paused", NONE = "";

    /** The player class whose lines never say what a video or a song is doing (the app's own click sounds). */
    private static final String SOUND_POOL = "android.media.SoundPool";
    /** The usages that carry what is playing. USAGE_UNKNOWN is what some players report instead of USAGE_MEDIA. */
    private static final String[] MEDIA_USAGES = {"USAGE_MEDIA", "USAGE_GAME", "USAGE_UNKNOWN"};

    private AudioPlayers() { }

    /** What the media players of one app are doing, from `dumpsys audio`. */
    public static String state(String dump, int uid) {
        if (dump == null || dump.isEmpty()) return NONE;
        boolean inBlock = false, started = false, paused = false;
        for (String raw : dump.split("\n")) {
            // a dump comes from a shell: a line may end with a carriage return, and a line of nothing but one is empty
            String line = trim(raw);
            if (!inBlock) {
                if ("players:".equals(line)) inBlock = true;
                continue;
            }
            if (line.isEmpty()) break;
            String[] f = line.split(" -- ");
            if (f.length < 5 || !f[0].startsWith("ID:")) continue;
            String type = after(f[1], "type:"), who = after(f[2], "u/pid:"), state = after(f[3], "state:");
            if (SOUND_POOL.equals(type) || uidOf(who) != uid || !media(f[4])) continue;
            if (STARTED.equals(state)) started = true;
            else if (PAUSED.equals(state)) paused = true;
        }
        return started ? STARTED : paused ? PAUSED : NONE;
    }

    /** The field's value, without the {@code name:} in front of it. */
    private static String after(String field, String name) {
        return field.startsWith(name) ? field.substring(name.length()).trim() : "";
    }

    /** The uid out of {@code 10021/18067}, or -1 when the field is not that. */
    private static int uidOf(String who) {
        int slash = who.indexOf('/');
        String digits = slash < 0 ? who : who.substring(0, slash);
        if (digits.isEmpty()) return -1;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        return Integer.parseInt(digits);
    }

    /** Whether the attr field says this player carries media at all. */
    private static boolean media(String attr) {
        int at = attr.indexOf("usage=");
        if (at < 0) return false;
        String usage = attr.substring(at + "usage=".length());
        int end = usage.indexOf(' ');
        if (end >= 0) usage = usage.substring(0, end);
        for (String m : MEDIA_USAGES) if (m.equals(usage)) return true;
        return false;
    }

    /** The line without the whitespace (and the carriage return) a shell leaves at its ends. */
    private static String trim(String line) {
        int start = 0, end = line.length();
        while (start < end && line.charAt(start) <= ' ') start++;
        while (end > start && line.charAt(end - 1) <= ' ') end--;
        return line.substring(start, end);
    }
}
