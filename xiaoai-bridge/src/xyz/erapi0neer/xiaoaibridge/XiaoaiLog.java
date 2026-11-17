package xyz.erapi0neer.xiaoaibridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows XiaoAi's current request through its own log (uid 1000 lines only): the final text, the cloud's
 * instructions with their payloads (Toast / Speak texts), and the moment XiaoAi starts speaking.
 * Line formats verified on MiTV-ASTP0 / voicecontrol 5.12.0.7, 2026-11-03.
 */
final class XiaoaiLog {
    static final String TAG_BAR = "=vc=fm=", TAG_ASR = "=vc=en=arp=", TAG_INST = "=vc=en=aifw==xaf=aew=",
            TAG_NLU = "=vc=cp=nlp=", TAG_PLAYER = "=vc=mm=mod=", TAG_MEDIA = "=vc=mmp=", TAG_EXTERNAL = "=vc=fd=ds=";
    private static final Pattern RID = Pattern.compile("runWhenSameRequestId EventOnFullResult requestId (\\w+)");
    private static final Pattern FULL = Pattern.compile("receive bus event: EventOnFullResult text: (.*)$");
    private static final Pattern ASR = Pattern.compile("onAsrResult: (.*), isFinal = true, isNlpRequest = (?:true|false), dialogId = (\\w+)");
    /** recv inst tra: nopt-3345-null-d153-Template.Toast[抱歉，…]  (request-id prefix, instruction, optional payload) */
    private static final Pattern INST = Pattern.compile("recv inst tra: \\w+-(\\w+)-[^-\\s]*-\\w+-([A-Za-z]+[.][A-Za-z]+)(?:\\[(.*)\\])?\\s*$");
    private static final Pattern NLU = Pattern.compile("receive nlp instruction ([A-Za-z]+[.][A-Za-z]+)");
    private static final Pattern TTS = Pattern.compile("onTtsStartPlay, isTtsUrl: \\w+, dialogId: (\\w*)");
    /** 「Thread-1 | onPlayComplete byManual false」: XiaoAi's player finished a sound (2026-11-06 logcat). */
    private static final Pattern COMPLETE = Pattern.compile("\\| onPlayComplete byManual (true|false)\\s*$");
    private static final Pattern PLAY_TTS = Pattern.compile("\\| playTts: <(.*)>\\s*$");
    /** SpeechSynthesizer.Speak payload is "<text>, <bool>". */
    private static final Pattern SPEAK = Pattern.compile("^(.*), (?:true|false)$");

    interface Listener {
        void onRequest(Dialog d);
        void onInstruction(Dialog d, String name, String text);
        void onTtsStart(Dialog d, long millis);
        /** XiaoAi's player started (start) or completed a sound; manual: it was stopped, not played to the end. */
        default void onPlayer(boolean start, boolean manual) { }
    }

    /** One XiaoAi request. Lists are safe to read from other threads. */
    static final class Dialog {
        final String text, rid;
        final long startedAt;
        final List<String> names = new CopyOnWriteArrayList<>();
        final List<String> answers = new CopyOnWriteArrayList<>();
        volatile long ttsStartedAt;
        /** While now < this, any XiaoAi speech for this request is stopped as it starts. */
        volatile long silenceUntil;
        /** Set once by whichever path (music or model) takes the request away from XiaoAi. */
        final java.util.concurrent.atomic.AtomicBoolean taken = new java.util.concurrent.atomic.AtomicBoolean();

        Dialog(String text, String rid, long startedAt) { this.text = text; this.rid = rid; this.startedAt = startedAt; }

        boolean owns(String id) {
            return id != null && !rid.isEmpty() && (id.startsWith(rid) || rid.startsWith(id));
        }
    }

    /** The text XiaoAi's player was last given, and whether it came through the external PLAY_TTS entry. */
    static final class Speech {
        final String text;
        final long at;
        final boolean external;
        Speech(String text, long at, boolean external) { this.text = text; this.at = at; this.external = external; }
    }
    private volatile Speech lastSpeech;
    private long externalAt;

    Speech lastSpeech() { return lastSpeech; }

    private final Listener listener;
    private volatile Dialog current;
    private String pendingRid = "";
    private String lastText = "";
    private long lastAt;

    XiaoaiLog(Listener listener) { this.listener = listener; }

    Dialog current() { return current; }

    /** Feed every uid-1000 log entry here (other uids must be filtered out by the caller). */
    void entry(long millis, String tag, String msg) {
        if (msg == null) return;
        Matcher m;
        if (TAG_BAR.equals(tag)) {
            if ((m = RID.matcher(msg)).find()) { pendingRid = m.group(1); return; }
            if ((m = FULL.matcher(msg)).find()) start(millis, m.group(1).trim(), pendingRid);
        } else if (TAG_ASR.equals(tag)) {
            if ((m = ASR.matcher(msg)).find()) start(millis, m.group(1).trim(), m.group(2));
        } else if (TAG_INST.equals(tag)) {
            Dialog d = current;
            if (d == null || !(m = INST.matcher(msg)).find() || !d.owns(m.group(1))) return;
            String name = m.group(2), payload = m.group(3), text = payload == null ? null : answerText(name, payload);
            if (!d.names.contains(name)) d.names.add(name);
            if (text != null && !text.isEmpty()) d.answers.add(text);
            listener.onInstruction(d, name, text);
        } else if (TAG_NLU.equals(tag)) {
            Dialog d = current;
            if (d != null && (m = NLU.matcher(msg)).find() && millis - d.startedAt < 15000 && !d.names.contains(m.group(1)))
                d.names.add(m.group(1));
        } else if (TAG_EXTERNAL.equals(tag)) {
            // PLAY_TTS from another app (this bridge or Xingyi) is announced here just before its playTts
            if (msg.contains("handlePlayTts")) externalAt = millis;
        } else if (TAG_MEDIA.equals(tag)) {
            if ((m = PLAY_TTS.matcher(msg)).find())
                lastSpeech = new Speech(m.group(1), millis, millis - externalAt < 800);
        } else if (TAG_PLAYER.equals(tag)) {
            if (msg.endsWith("| onPlayStart")) listener.onPlayer(true, false);
            else if ((m = COMPLETE.matcher(msg)).find()) listener.onPlayer(false, "true".equals(m.group(1)));
            // XiaoAi speaks its own answers under a fresh dialog id: match by time, not by id
            Dialog d = current;
            if (d != null && (m = TTS.matcher(msg)).find() && millis - d.startedAt < 20000) {
                d.ttsStartedAt = millis;
                listener.onTtsStart(d, millis);
            }
        }
    }

    private void start(long millis, String text, String rid) {
        if (text.isEmpty()) return;
        // the voice bar and the ASR engine both report the same final text
        if (text.equals(lastText) && Math.abs(millis - lastAt) < 5000) return;
        lastText = text;
        lastAt = millis;
        pendingRid = "";
        Dialog d = new Dialog(text, rid == null ? "" : rid, millis);
        current = d;
        listener.onRequest(d);
    }

    /** The spoken or shown text of an instruction payload, or null when the payload is not text. */
    static String answerText(String name, String payload) {
        String p = payload.trim();
        if (name.equals("SpeechSynthesizer.Speak")) {
            Matcher m = SPEAK.matcher(p);
            return m.matches() ? m.group(1).trim() : p;
        }
        if (name.equals("Template.Toast") || name.equals("Template.ToastStream")) return p;
        return null;
    }
}
