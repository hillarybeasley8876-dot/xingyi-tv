package xyz.erapi0neer.xingyi;

public class QuietRefusalGateTest {
    private static final long NOW = 1793727000000L;
    private static final String RID = "a123abcdeffedcba3210bcdef0123456";
    private static final String OTHER = "a1230000000000000000000000000000";
    private static final String DECLINE = "抱歉，当前暂不支持使用网易云音乐源播放，将继续使用当前音乐源为您播放。";
    private static int checks;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("check " + (checks+1)); checks++;
    }
    private static boolean feed(QuietRefusalGate gate, String message, int ms) {
        return gate.offer(line(message, ms), NOW+ms+10);
    }
    private static String line(String message,int ms) {
        return String.format(java.util.Locale.US, "1793727000.%03d943  6137  6316 %s", ms, message);
    }
    private static String speak(String reply) {
        return "I =vc=en=aifw==xaf=aew=: DownloadThread | eid=FarFieldEngine43d029;recv inst tra: nopt-a123-null-f032-SpeechSynthesizer.Speak[" + reply + ", false]";
    }
    private static String bind(String rid) {
        return "I =vc=en=en=proxy=: NEAR_FIELD_callback | onEngineEvent NlpEvent(rid='" + rid + "', push=false, stream=false, SpeechSynthesizer.Speak)";
    }
    private static String start(String rid) {
        return "D =vc=mm=mod=: Thread-1 | onTtsStartPlay, isTtsUrl: false, dialogId: " + rid;
    }
    private static QuietRefusalGate prepared(String reply) {
        QuietRefusalGate gate = new QuietRefusalGate();
        check(!feed(gate,speak(reply),100));
        check(!feed(gate,bind(RID),110));
        return gate;
    }
    public static void main(String[] args) {
        for (String refusal : new String[]{DECLINE,"对不起我还在学习","对不起，我还在学习。","抱歉，我还在学习中！","抱歉，目前暂不支持该功能","抱歉，您的电视暂不支持此功能。","我暂时不支持这个操作。"}) {
            QuietRefusalGate gate = prepared(refusal);
            check(feed(gate,start(RID),350));
            check(!feed(gate,speak(refusal),370) && !feed(gate,bind(RID),380) && !feed(gate,start(RID),490));
        }
        for (String reply : new String[]{"好的，一起来听晴天。","抱歉，今天会下雨。",DECLINE+"请先授权", "抱歉，没有播放权限",
                "抱歉，目前暂不支持该功能，但我可以帮您关灯","他说“抱歉，目前暂不支持该功能”"}) {
            check(!feed(prepared(reply),start(RID),350));
        }
        QuietRefusalGate gate = prepared(DECLINE);
        check(!feed(gate,start(OTHER),350) && !feed(gate,start(RID),351));
        gate = prepared(DECLINE);
        check(!feed(gate,bind(OTHER),160) && !feed(gate,start(OTHER),350));
        gate = prepared(DECLINE);
        check(!feed(gate,speak("已打开"),160) && !feed(gate,start(RID),350));
        gate = prepared(DECLINE);
        check(!gate.offer(line(start(RID),500), NOW+3000));
        gate = prepared(DECLINE);
        check(!gate.offer(line(start(RID),350).replace("6137","9999"),NOW+360));
        gate = prepared(DECLINE);
        check(!feed(gate,start(RID).replace("false","true"),350));
        gate = new QuietRefusalGate();
        check(!feed(gate,bind(RID),90) && !feed(gate,speak(DECLINE),110) && feed(gate,start(RID),350));
        gate = new QuietRefusalGate();
        check(!feed(gate,speak(DECLINE),100) && !feed(gate,start(RID),350));
        gate = new QuietRefusalGate();
        check(!feed(gate,bind(RID),100) && !feed(gate,start(RID),350));
        gate = new QuietRefusalGate();
        check(!feed(gate,speak("天气晴朗"),90) && !feed(gate,speak(DECLINE),100)
                && !feed(gate,bind(RID),110) && !feed(gate,start(RID),350));
        gate = prepared(DECLINE);
        check(gate.offer(line(start(RID),350).replace("6137", "1000  6137"), NOW+360));
        String pcm = "I =vc=cp=tts=: FAR_FIELD_callback | onDataStreamStart "+RID;
        String track = "D =byte=tts=: Thread-1 | =vc=xp= msg: play by audio track a123";
        gate = prepared(DECLINE);
        check(!feed(gate,pcm,125) && !feed(gate,track,280) && feed(gate,start(RID),310));
        gate = prepared(DECLINE);
        check(!feed(gate,pcm.replace(RID,OTHER),125) && !feed(gate,track,280));
        gate = prepared(DECLINE);
        check(!feed(gate,track,280) && feed(gate,start(RID),310));
        gate = prepared(DECLINE);
        check(!feed(gate,bind(RID),140) && !feed(gate,start(RID),310));
        gate = new QuietRefusalGate();
        check(!feed(gate,bind(RID),100) && gate.currentReplyEvent()==null);
        check(!feed(gate,speak(DECLINE),105) && RID.equals(gate.currentReplyEvent()));
        check(!feed(gate,bind(RID),120) && gate.currentReplyEvent()==null);
        gate = new QuietRefusalGate();
        check(!feed(gate,speak(DECLINE),80) && gate.currentReplyEvent()==null);
        check(!feed(gate,bind(RID),85) && RID.equals(gate.currentReplyEvent()));
        check(feed(gate,start(RID),200) && gate.currentReplyEvent()==null);
        check(!gate.offer(line(speak(DECLINE),100),NOW+4000) && gate.currentReplyEvent()==null);
        check(!gate.offer(line(speak(DECLINE),100).replace("1793727000","1793727004"),NOW+4101));
        check(!gate.offer(line(bind(RID),110).replace("1793727000","1793727004"),NOW+4111)
                && gate.currentReplyEvent()==null);
        gate = new QuietRefusalGate();
        check(!feed(gate,bind(RID),10) && !feed(gate,bind(OTHER),14) && !feed(gate,speak(DECLINE),19)
                && gate.currentReplyEvent()==null);
        for (String reply : new String[]{"好的", "抱歉，网络无法连接", "我没有权限", "抱歉，目前暂不支持该功能，请你登录"}) {
            gate = prepared(reply);
            check(gate.currentReplyEvent()==null);
        }
        for (String synth : new String[]{"TtsEngine","TtsEngine_aivse_inner_tts_engine"}) {
            gate = new QuietRefusalGate();
            check(!feed(gate,speak(DECLINE).replace("FarFieldEngine",synth),100));
            check(!feed(gate,bind(RID),110) && gate.currentReplyEvent()==null && !feed(gate,start(RID),160));
        }
        System.out.println("PASS "+checks+" selective refusal speech checks");
    }
}
