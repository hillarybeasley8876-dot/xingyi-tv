package xyz.erapi0neer.xiaoaibridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One XiaoAi "final text of the request" log line, as printed by `logcat -v threadtime -v uid -v epoch`.
 * Two sources: the voice bar's EventOnFullResult (every request, spoken or typed) and the ASR engine's final result.
 */
final class AsrLine {
    /** XiaoAi (com.xiaomi.voicecontrol) runs as android.uid.system; normal apps cannot log under that uid. */
    static final String TAG_BAR = "=vc=fm=";
    static final String TAG_ASR = "=vc=en=arp=";
    private static final Pattern ROW = Pattern.compile(
            "^\\s*(\\d+)\\.(\\d{1,6})\\s+(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.+?)\\s*: (.*)$");
    private static final Pattern BAR = Pattern.compile("receive bus event: EventOnFullResult text: (.*)$");
    private static final Pattern ASR = Pattern.compile(
            "onAsrResult: (.*), isFinal = (true|false), isNlpRequest = (true|false), dialogId = (\\S+)\\s*$");

    final long epochMillis;
    final String uid, text;
    final boolean isFinal;

    private AsrLine(long epochMillis, String uid, String text, boolean isFinal) {
        this.epochMillis = epochMillis;
        this.uid = uid;
        this.text = text;
        this.isFinal = isFinal;
    }

    /** Null for anything that is not one of the two XiaoAi lines. */
    static AsrLine parse(String line) {
        if (line == null || line.indexOf("EventOnFullResult text: ") < 0 && line.indexOf("onAsrResult: ") < 0) return null;
        Matcher row = ROW.matcher(line);
        if (!row.matches()) return null;
        String fraction = (row.group(2) + "000").substring(0, 3);
        long millis = Long.parseLong(row.group(1)) * 1000L + Long.parseLong(fraction);
        String tag = row.group(7), message = row.group(8);
        if (TAG_BAR.equals(tag)) {
            Matcher m = BAR.matcher(message);
            return m.find() ? new AsrLine(millis, row.group(3), m.group(1).trim(), true) : null;
        }
        if (TAG_ASR.equals(tag)) {
            Matcher m = ASR.matcher(message);
            return m.find() ? new AsrLine(millis, row.group(3), m.group(1).trim(), "true".equals(m.group(2))) : null;
        }
        return null;
    }

    /** An entry read from logd: the uid is the one logd recorded for the writer. */
    static AsrLine fromEntry(long epochMillis, int uid, String tag, String message) {
        if (message == null) return null;
        if (TAG_BAR.equals(tag)) {
            Matcher m = BAR.matcher(message);
            return m.find() ? new AsrLine(epochMillis, Integer.toString(uid), m.group(1).trim(), true) : null;
        }
        if (TAG_ASR.equals(tag)) {
            Matcher m = ASR.matcher(message);
            return m.find() ? new AsrLine(epochMillis, Integer.toString(uid), m.group(1).trim(), "true".equals(m.group(2))) : null;
        }
        return null;
    }

    boolean trusted() { return "1000".equals(uid) || "system".equals(uid); }
}
