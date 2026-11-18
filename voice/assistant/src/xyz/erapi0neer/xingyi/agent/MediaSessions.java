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

    /** What became of a 「下一首」「上一首」 XiaoAi sent itself for the sentence 星仪 is handling. */
    public enum Skip { LANDED, MISSED, UNKNOWN }

    /** How much longer than the time since the request a track must have played to have begun before it. The bridge
     * holds the request up to 0.9 s while it collects XiaoAi's instructions, so a track XiaoAi's skip started cannot
     * be more than that older than the request; the rest is room for a position the app reported a little late. */
    public static final long SKIP_MARGIN_MS = 3000;

    /**
     * Whether XiaoAi's own skip reached the player. 2026-11-16 19:30: 「下一首歌」, XiaoAi sent PlaybackController.Next,
     * NetEase stayed on 《江南》 (still on it 16 s later), and the guard that keeps 星仪 from skipping a second time
     * refused the only skip that would have worked: 「小爱已经切过了，现在还是…《江南》」, and the user said it again.
     * The same instruction did move 云视听极光 to the next episode (2026-11-11), so the instruction alone says nothing.
     *
     * @param atRequest what was playing when the request reached 星仪 (null: not read)
     * @param now what is playing now (null: nothing, or nothing with a media session — 云视听极光 keeps none)
     * @param requestElapsed when the request reached 星仪, on the device's elapsed-time clock (ms)
     * @param nowElapsed now, on the same clock
     * @return LANDED: another track than at the request. MISSED: the same track, playing since well before the request,
     *     where XiaoAi's skip would have started another one. UNKNOWN: anything else (not playing, no position, a track
     *     that began around the request); the callers treat it as landed, as before.
     */
    public static Skip xiaoaiSkip(Session atRequest, Session now, long requestElapsed, long nowElapsed) {
        if (now == null) return Skip.UNKNOWN;
        if (atRequest != null && !sameTrack(atRequest, now)) return Skip.LANDED;
        if (!"playing".equals(now.state) || now.updated <= 0 || now.position < 0 || requestElapsed <= 0) return Skip.UNKNOWN;
        long played = now.position + Math.max(0, nowElapsed - now.updated);
        long since = Math.max(0, nowElapsed - requestElapsed);
        return played > since + SKIP_MARGIN_MS ? Skip.MISSED : Skip.UNKNOWN;
    }

    /** The same app on the same title by the same artist. */
    public static boolean sameTrack(Session a, Session b) {
        return a != null && b != null && a.pkg.equals(b.pkg) && a.title.equals(b.title) && a.artist.equals(b.artist);
    }

    /** How far into the track (ms) at nowElapsed, or -1 when the session does not say: the position the app last
     * reported, plus the time since then while it plays. */
    public static long played(Session s, long nowElapsed) {
        if (s == null || s.position < 0 || s.updated <= 0) return -1;
        if ("playing".equals(s.state)) return s.position + Math.max(0, nowElapsed - s.updated);
        return "paused".equals(s.state) ? s.position : -1;
    }

    /**
     * Where the track is, in words for the model: 「，放到 2:13」, or "" when the session does not say. 2026-11-16 19:45:
     * 「重播这首歌」, XiaoAi's PlaybackController.StartOver did not land on NetEase; to 「这是重播」 星仪 answered
     * 「对，现在放的是重新从头播的」 with nothing to go by, and the user: 「但是没有重播」. The position is what tells.
     */
    public static String at(Session s, long nowElapsed) {
        long ms = played(s, nowElapsed);
        if (ms < 0) return "";
        long sec = ms / 1000;
        return "，放到 " + sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
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
