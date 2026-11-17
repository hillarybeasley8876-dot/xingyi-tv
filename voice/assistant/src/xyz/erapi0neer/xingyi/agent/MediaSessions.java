package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads `dumpsys media_session` (Android 9 format): which app is playing what. */
public final class MediaSessions {
    public static final class Session {
        public String pkg = "", state = "none", title = "", artist = "", album = "";
        public boolean active;
        public long position;
        /** How many items the app put in the session's queue; 0 when it keeps none there (many apps do not). */
        public int queue;
        /** When the playback state last changed, on the device's elapsed-time clock (ms); 0 when not printed. */
        public long updated;
        public String describe() {
            StringBuilder s = new StringBuilder(words(state));
            if (!title.isEmpty()) s.append("《").append(title).append("》");
            if (!artist.isEmpty()) s.append(" ").append(artist);
            if (!album.isEmpty()) s.append("（").append(album).append("）");
            return s.toString();
        }
    }

    /** A playback state in words (「stopped」 was read out as it is). */
    public static String words(String state) {
        switch (state == null ? "" : state) {
            case "playing": return "正在播放";
            case "paused": return "已暂停";
            case "stopped": return "已停止";
            case "buffering": return "正在缓冲";
            case "connecting": return "正在连接";
            case "error": return "播放出错";
            case "none": return "没有在播放";
            default: return state.startsWith("skipping") ? "正在切换" : state.startsWith("fast") || state.startsWith("rewind") ? "正在快进快退" : state;
        }
    }

    /** A session header in the sessions stack, whatever the app tagged its session with: B站's is
     * 「HeadsetControlPlayAdapter.Session」, NetEase's happens to be 「MediaSession」. One tag token, a package with at
     * least one dot, a slash, the session's own name (no spaces), then 「(userId=N)」 at the end of the line. The lines
     * that only mention a session — 「Media button session is tv.danmaku.bili/… (userId=0)」 — have words before the
     * package and so do not match. */
    private static final Pattern SESSION = Pattern.compile(
            "^\\s*\\S+\\s+([A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)+)/\\S+\\s+\\(userId=\\d+\\)\\s*$");
    private static final Pattern STATE = Pattern.compile("state=PlaybackState \\{state=(\\d+), position=(-?\\d+)");
    private static final Pattern DESC = Pattern.compile("metadata:size=\\d+, description=(.*)$");
    private static final Pattern QUEUE = Pattern.compile("^queueTitle=.*, size=(\\d+)$");
    private static final Pattern UPDATED = Pattern.compile(", updated=(\\d+)");

    private MediaSessions() { }

    public static List<Session> parse(String dump) {
        List<Session> out = new ArrayList<>();
        if (dump == null) return out;
        Session cur = null;
        for (String line : dump.split("\\r?\\n")) {
            Matcher m = SESSION.matcher(line);
            if (m.find()) { cur = new Session(); cur.pkg = m.group(1); out.add(cur); continue; }
            if (cur == null) continue;
            String t = line.trim();
            if (t.startsWith("package=")) cur.pkg = t.substring(8);
            else if (t.equals("active=true")) cur.active = true;
            else if ((m = STATE.matcher(t)).find()) {
                cur.state = state(Integer.parseInt(m.group(1)));
                cur.position = Long.parseLong(m.group(2));
                Matcher u = UPDATED.matcher(t);
                if (u.find()) cur.updated = Long.parseLong(u.group(1));
            }
            else if ((m = DESC.matcher(t)).find()) describe(cur, m.group(1));
            else if ((m = QUEUE.matcher(t)).find()) cur.queue = Integer.parseInt(m.group(1));
            else if (t.startsWith("Audio playback")) cur = null;
        }
        return out;
    }

    /** The session worth reporting: playing first, then any active one with metadata. */
    public static Session current(String dump) {
        Session best = null;
        for (Session s : parse(dump)) {
            if ("playing".equals(s.state)) return s;
            if (best == null && s.active && !s.title.isEmpty()) best = s;
        }
        return best;
    }

    private static void describe(Session s, String raw) {
        // MediaDescription prints "title, subtitle, description"; the title itself may contain ", ".
        String[] parts = raw.split(", ", -1);
        List<String> p = new ArrayList<>();
        for (String x : parts) p.add("null".equals(x) ? "" : x.trim());
        if (p.size() >= 3) {
            s.album = p.get(p.size() - 1);
            s.artist = p.get(p.size() - 2);
            s.title = String.join(", ", p.subList(0, p.size() - 2));
        } else if (p.size() == 2) {
            s.title = p.get(0); s.artist = p.get(1);
        } else s.title = p.get(0);
    }

    private static String state(int code) {
        switch (code) {
            case 3: return "playing";
            case 2: return "paused";
            case 1: return "stopped";
            case 6: case 8: return "buffering";
            case 7: return "error";
            case 9: case 10: case 11: return "skipping";
            default: return "none";
        }
    }
}
