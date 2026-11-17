package xyz.erapi0neer.xiaoaibridge;

import java.util.ArrayList;
import java.util.List;

/** JVM test for XiaoaiLog (with lines copied from the TV on 2026-11-03/04) and the refusal classifier. */
public final class DialogTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }

    public static void main(String[] args) {
        final List<String> events = new ArrayList<>();
        XiaoaiLog log = new XiaoaiLog(new XiaoaiLog.Listener() {
            public void onRequest(XiaoaiLog.Dialog d) { events.add("request " + d.rid + " " + d.text); }
            public void onInstruction(XiaoaiLog.Dialog d, String name, String text) { events.add("inst " + name + " " + text); }
            public void onTtsStart(XiaoaiLog.Dialog d, long millis) { events.add("tts " + millis); }
        });
        // verbatim message parts from 2026-11-04 01:10 (the user's sentence through XiaoAi's text entry)
        log.entry(1793725820344L, "=vc=fm=", "main | runWhenSameRequestId EventOnFullResult requestId 3345f9181f3647c4ba92fd5ab36c0c8b");
        log.entry(1793725820344L, "=vc=fm=", "main | receive bus event: EventOnFullResult text: 网易云音乐播放薛之谦的你还要我怎样");
        log.entry(1793725820400L, "=vc=en=arp=", "main | [performance]: onAsrResult: 网易云音乐播放薛之谦的你还要我怎样, isFinal = true, isNlpRequest = false, dialogId = 3345f9181f3647c4ba92fd5ab36c0c8b");
        log.entry(1793725821244L, "=vc=en=aifw==xaf=aew=", "DownloadThread | eid=FarFieldEnginedb8ecd;recv inst tra: nopt-3345-null-bbfd-Template.PlayInfo");
        log.entry(1793725821249L, "=vc=en=aifw==xaf=aew=", "DownloadThread | eid=FarFieldEnginedb8ecd;recv inst tra: nopt-3345-null-d153-Template.Toast[抱歉，当前暂不支持使用网易云音乐源播放，将继续使用当前音乐源为您播放]");
        log.entry(1793725821379L, "=vc=cp=nlp=", "NEAR_FIELD_callback | receive nlp instruction Template.PlayInfo, multiIntention false");
        log.entry(1793725821500L, "=vc=en=aifw==xaf=aew=", "DownloadThread | eid=X;recv inst tra: nopt-9999-null-d153-Template.Toast[别的请求]");
        log.entry(1793725822000L, "=vc=mm=mod=", "Thread-1 | onTtsStartPlay, isTtsUrl: true, dialogId: 3345f9181f3647c4ba92fd5ab36c0c8b");
        log.entry(1793725822100L, "=vc=mm=mod=", "Thread-1 | onTtsStartPlay, isTtsUrl: true, dialogId: 1e0425ff25b64991a47051a891bb0fa1");
        XiaoaiLog.Dialog d = log.current();
        check(d != null && "3345f9181f3647c4ba92fd5ab36c0c8b".equals(d.rid) && "网易云音乐播放薛之谦的你还要我怎样".equals(d.text), "request with id");
        check(events.size() == 5, "events (request once, 2 instructions of this request, every speech start in the window): " + events);
        check(d != null && d.names.contains("Template.PlayInfo") && d.names.contains("Template.Toast") && d.names.size() == 2, "names " + (d == null ? null : d.names));
        check(d != null && d.answers.size() == 1 && d.answers.get(0).startsWith("抱歉，当前暂不支持"), "toast text kept " + (d == null ? null : d.answers));
        // XiaoAi speaks its own answer under a fresh dialog id (seen 2026-11-04 02:20): speech is matched by time
        check(d != null && d.ttsStartedAt == 1793725822100L, "latest speech start in the window");
        // speech source: PLAY_TTS (this bridge / Xingyi) is announced by =vc=fd=ds= handlePlayTts; XiaoAi's own is not
        log.entry(1793725823000L, "=vc=mmp=", "OpIoPool-thread-1 | playTts: <现在音量是5>");
        XiaoaiLog.Speech own = log.lastSpeech();
        check(own != null && "现在音量是5".equals(own.text) && !own.external, "XiaoAi's own speech");
        log.entry(1793725824000L, "=vc=fd=ds=", "main | handlePlayTts");
        log.entry(1793725824001L, "=vc=mmp=", "main | playTts: <当前媒体音量为 5%（最大 100），未静音。>");
        XiaoaiLog.Speech ext = log.lastSpeech();
        check(ext != null && ext.external && ext.text.equals("当前媒体音量为 5%（最大 100），未静音。"), "external speech");
        log.entry(1793725826000L, "=vc=mmp=", "OpIoPool-thread-2 | playTts: <好的>");
        check(log.lastSpeech() != null && !log.lastSpeech().external, "an old handlePlayTts does not mark later speech");
        check(XiaoaiLog.answerText("SpeechSynthesizer.Speak", "需要你确认。请看电视上的操作预览，按确认执行后我再继续。, false")
                .equals("需要你确认。请看电视上的操作预览，按确认执行后我再继续。"), "speak payload text");
        check(XiaoaiLog.answerText("Nlp.StartPreStream", "1e0425ff25b64991a47051a891bb0fa1") == null, "non-text payload ignored");
        // a new request replaces the old one; an instruction without payload still counts as a name
        log.entry(1793727005818L, "=vc=fm=", "main | runWhenSameRequestId EventOnFullResult requestId 1e0425ff25b64991a47051a891bb0fa1");
        log.entry(1793727005818L, "=vc=fm=", "main | receive bus event: EventOnFullResult text: 量子纠缠是什么原理");
        log.entry(1793727006465L, "=vc=en=aifw==xaf=aew=", "DownloadThread | recv inst tra: nopt-1e04-null-b54d-Template.LLMLoadingCard");
        d = log.current();
        check(d != null && d.names.contains("Template.LLMLoadingCard") && d.answers.isEmpty(), "llm request names");

        String[] refusals = {"抱歉，当前暂不支持使用网易云音乐源播放，将继续使用当前音乐源为您播放", "我还不会这个哦，我会继续学习的",
            "抱歉，我没听懂你说的是什么", "这个问题我也不知道呢", "对不起，没有找到相关的内容", "不好意思，我暂时无法回答这个问题",
            "你可以换个说法试试", "这个我暂时还不支持哦", "小爱没有找到星辰人旅行者的mad的资源，为您推荐以下相关内容"};
        String[] answers = {"现在北京晴，气温12到20度", "好的，已为你打开电视设置", "从进化论角度看，先有蛋。", "已为你暂停",
            "1加1等于2", "不客气，随时叫我", "没问题，已经调大音量", "抱歉让你久等了，正在为你播放周杰伦的晴天",
            "已为您找到时代少年团的相关视频"};
        for (String r : refusals) check(Refusal.is(r), "refusal: " + r);
        for (String a : answers) check(!Refusal.is(a), "not a refusal: " + a);
        check(!Refusal.is(null) && !Refusal.is(""), "empty");
        // B站 requests go to 星仪 at once; described songs are looked up by 星仪 (2026-11-05)
        for (String t : new String[]{"打开b站搜索播放量最高的视频", "B站搜索星辰人旅行者这个用户", "哔哩哔哩播放星辰人旅行者的MAD", "打开bilibili"})
            check(Takeover.namesBili(t), "B站 request: " + t);
        for (String t : new String[]{"播放周杰伦的晴天", "打开网易云音乐", "今天天气怎么样", null})
            check(!Takeover.namesBili(t), "not B站: " + t);
        for (String t : new String[]{"打开网易云音乐播放林俊杰目前收听人数最高的单曲", "网易云音乐播放林俊杰最新发表的一个单曲",
                "播放周杰伦最火的歌", "放一首甄嬛传的主题曲", "网易云播放热歌榜第一"})
            check(Takeover.described(t), "described song: " + t);
        for (String t : new String[]{"播放薛之谦的你还要我怎样", "网易云播放榜样", "播放晴天", "播放林俊杰的江南"})
            check(!Takeover.described(t), "named song: " + t);
        // 星仪接管模式: what XiaoAi did, without its protocol traffic, and the note 星仪 gets
        java.util.List<String> names = java.util.Arrays.asList("Nlp.StartAnswer", "Template.Query", "Speaker.SetVolume",
                "Template.Toast", "SpeechSynthesizer.Speak", "Nlp.FinishAnswer", "Dialog.Finish");
        java.util.Set<String> did = Takeover.xiaoaiDid(names);
        check(did.size() == 2 && did.contains("Speaker.SetVolume") && did.contains("Template.Toast"), "did " + did);
        String note = Takeover.xiaoaiNote(did, java.util.Arrays.asList("音量已调到20"));
        check(note.equals("小爱自己的处理：动作 Speaker.SetVolume、Template.Toast；它本来要说「音量已调到20」"), "note " + note);
        check(Takeover.xiaoaiNote(new java.util.LinkedHashSet<String>(), java.util.Collections.<String>emptyList()).equals("小爱自己的处理：没有动作"), "empty note");
        // absolute quick commands go to 星仪 without waiting for XiaoAi's instructions; relative ones wait
        for (String t : new String[]{"音量调到20", "静音", "暂停", "继续播放", "切换到HDMI2", "回到桌面", "30分钟后关机"})
            check(Takeover.absoluteQuick(t), "absolute: " + t);
        for (String t : new String[]{"音量调大一点", "小声点", "下一首", "上一集", "返回", "打开B站", "打开B站搜索星辰亻旅行者", "关电视", "亮屏"})
            check(!Takeover.absoluteQuick(t), "waits for XiaoAi: " + t);
        // since 2026-11-06 the read-only quick answers go over at once: what XiaoAi did changes nothing in them
        for (String t : new String[]{"今天天气怎么样", "现在几点", "今天农历几号", "现在放的是什么歌"})
            check(Takeover.absoluteQuick(t), "answered at once: " + t);
        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (dialog + refusal)");
        if (fail > 0) System.exit(1);
    }
}
