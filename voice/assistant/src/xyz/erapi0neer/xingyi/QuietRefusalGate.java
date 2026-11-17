package xyz.erapi0neer.xingyi;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes complete stock refusal speech as the same native TTS player starts.
 * Input is the live, PID-filtered Android main log. No spoken text is kept between lines. */
final class QuietRefusalGate {
    private static final String ENGINE = "=vc=en=aifw==xaf=aew=";
    private static final String PROXY = "=vc=en=en=proxy=";
    private static final String PLAYER = "=vc=mm=mod=";
    private static final Pattern HEADER = Pattern.compile(
            "^\\s*([0-9]{10})\\.([0-9]{3,6})\\s+(?:\\d+\\s+)?(\\d+)\\s+\\d+\\s+[IDVW]\\s+(\\S+)\\s*:\\s*(.*)$");
    private static final String THREAD = "[^|\\r\\n]{1,72} \\| ";
    private static final String SHORT = "([A-Fa-f0-9]{4})";
    private static final String FULL = "([A-Fa-f0-9]{32})";
    private static final Pattern SPEAK = Pattern.compile(THREAD
            + "eid=(?:(?:main_)?FarFieldEngine|QueryEngine)[A-Fa-f0-9]{6};recv inst tra: [A-Za-z0-9]{1,4}-" + SHORT
            + "-[A-Za-z0-9]{1,4}-[A-Fa-f0-9]{4}-SpeechSynthesizer\\.Speak\\[([^\\[\\]\\r\\n]{1,180}), (true|false)\\]");
    private static final Pattern BIND = Pattern.compile("(?:NEAR|FAR)_FIELD_callback \\| onEngineEvent NlpEvent\\(rid='" + FULL
            + "', push=false, stream=false, SpeechSynthesizer\\.Speak\\)");
    private static final Pattern START = Pattern.compile(THREAD + "onTtsStartPlay, isTtsUrl: (true|false), dialogId: " + FULL);
    private static final Pattern CAPABILITY = Pattern.compile(
            "(?:(?:很抱歉|抱歉|对不起|不好意思),? *)?(?:"
            + "(?:目前|当前)暂不支持(?:该|此)功能"
            + "|(?:我|小爱同学)(?:现在|目前|暂时)?(?:还)?不支持(?:这项|这个|该|此)(?:功能|操作|请求)"
            + "|(?:当前应用|您的设备|该设备|您的电视|这台电视|本设备)暂不支持(?:该|此)(?:操作|功能)"
            + "|(?:当前|目前)暂不支持使用网易云音乐源播放[,。]将继续使用当前音乐源为您播放)[。.!]?");
    private static final long MAX_DELIVERY_MS = 750, MAX_PAIR_MS = 2200;

    private final ArrayDeque<String> used = new ArrayDeque<>();
    private final ArrayDeque<String> announced = new ArrayDeque<>();
    private String pid, candidate, full, earlyFull, lastBind, lastSpeech;
    private long candidateAt, earlyAt, lastBindAt, lastSpeechAt;
    private boolean url;
    private String replyId;

    /** Valid only for the most recent input line. The reader independently validates the native decision. */
    String currentReplyEvent() { return replyId; }

    private void clear() {
        candidate = full = earlyFull = null;
    }

    boolean offer(String line, long nowWallMs) {
        replyId = null;
        if (line == null || line.length() > 4096) return false;
        Matcher header = HEADER.matcher(line);
        if (!header.matches()) return false;
        long at = Long.parseLong(header.group(1)) * 1000L + Integer.parseInt(header.group(2).substring(0, 3));
        if (at > nowWallMs || nowWallMs - at > MAX_DELIVERY_MS) {
            clear();
            return false;
        }
        if (!header.group(3).equals(pid)) {
            clear();
            lastSpeech = lastBind = null;
            pid = header.group(3);
        }
        if (candidate != null && (at < candidateAt || at - candidateAt > MAX_PAIR_MS)) clear();
        String tag = header.group(4), body = header.group(5);
        if (ENGINE.equals(tag) && body.contains("-SpeechSynthesizer.Speak[")) {
            Matcher speak = SPEAK.matcher(body);
            if (!speak.matches()) { clear(); return false; }
            // Multiple Speak messages for a dialog may start asynchronously. Its first
            // start cannot establish which of those messages is playing.
            boolean ambiguous = speak.group(1).equals(lastSpeech) && at - lastSpeechAt <= MAX_PAIR_MS;
            lastSpeech = speak.group(1);
            lastSpeechAt = at;
            if (ambiguous || !refusal(speak.group(2))) { clear(); return false; }
            candidate = speak.group(1);
            candidateAt = at;
            url = "true".equals(speak.group(3));
            full = earlyFull != null && earlyFull.startsWith(candidate) && at >= earlyAt && at - earlyAt <= 160
                    ? earlyFull : null;
            earlyFull = null;
            if (full != null) announceRefusal();
        } else if (PROXY.equals(tag) && body.contains("SpeechSynthesizer.Speak")) {
            Matcher event = BIND.matcher(body);
            if (!event.matches()) { clear(); return false; }
            String id = event.group(1);
            boolean repeated = id.equals(lastBind) && at - lastBindAt <= MAX_PAIR_MS;
            boolean collision = lastBind != null && !id.equals(lastBind) && id.regionMatches(0,lastBind,0,4)
                    && at - lastBindAt <= MAX_PAIR_MS;
            lastBind = id;
            lastBindAt = at;
            if (repeated || collision) {
                clear();
            } else if (candidate == null || !id.startsWith(candidate)) {
                clear(); earlyFull = id; earlyAt = at;
            } else if (full != null && !full.equals(id)) {
                clear();
            } else {
                full = id;
                announceRefusal();
            }
        } else if (PLAYER.equals(tag) && body.contains("onTtsStartPlay,")) {
            // At the earlier PCM marker the vendor still reports player state 0 and
            // stopSync can return without silencing it. Wait for this state-1 callback.
            Matcher start = START.matcher(body);
            if (start.matches() && start.group(2).equals(full)
                    && url == "true".equals(start.group(1))) return claim();
            clear();
        } else if (PLAYER.equals(tag) && body.contains("onTtsPlayFinish,")) {
            clear();
        }
        return false;
    }

    private void announceRefusal() {
        if (announced.contains(full)) return;
        if (announced.size() == 64) announced.removeFirst();
        announced.addLast(full);
        replyId = full;
    }

    private boolean claim() {
        boolean fresh = !used.contains(full);
        if (fresh) {
            if (used.size() == 64) used.removeFirst();
            used.addLast(full);
        }
        clear();
        return fresh;
    }

    private static boolean refusal(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).trim();
        return CAPABILITY.matcher(normalized).matches() || normalized.matches("(?:对不起|抱歉)[, ]*我还在学习(?:中)?[。.!！]?");
    }
}
