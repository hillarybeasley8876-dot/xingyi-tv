package xyz.erapi0neer.xingyi;

import java.util.Arrays;
import org.json.JSONObject;

public final class XiaomiRefusalGateTest {
    static int count;
    static String row(String dialog, String namespace, String name, JSONObject payload) throws Exception {
        return new JSONObject().put("header",new JSONObject().put("dialog_id", dialog)
                .put("namespace",namespace).put("name",name)).put("payload",payload).toString();
    }
    static String query(String text) throws Exception {
        return row("abc","Template","Query",new JSONObject().put("text",text));
    }
    static String speak(Object category) throws Exception {
        return row("abc","SpeechSynthesizer","Speak",new JSONObject().put("text","这个操作暂不支持").put("tts_category", category));
    }
    static XiaomiRefusalGate.Assessment assess(String... rows) {
        return XiaomiRefusalGate.assess("abc", Arrays.asList(rows));
    }
    static void check(boolean v) { if (!v) throw new AssertionError("check " + (count+1)); count++; }
    public static void main(String[] args) throws Exception {
        String q = query("查询星仪定时"), refusal = speak("UNSUPPORT");
        XiaomiRefusalGate.Assessment ok = assess(q,refusal);
        check(ok.explicit && "查询星仪定时".equals(ok.query));
        check(!assess(q).explicit);
        check(!assess(refusal).explicit);
        check(!assess(q,q,refusal).explicit);
        check(!assess(q,refusal,refusal).explicit);
        String search=query("打开B站然后搜索星辰亻旅行者"), resume=query("继续执行");
        for(String learning : new String[]{"对不起我还在学习","对不起，我还在学习。","抱歉，我还在学习中！"}) {
            String reply=row("abc","SpeechSynthesizer","Speak",new JSONObject().put("text",learning));
            check(assess(search,reply).explicit && assess(search,reply).reason.startsWith("explicit_scoped_action:"));
            check(assess(resume,reply).explicit && assess(resume,reply).reason.equals("explicit_harness_refusal"));
            check(assess(search,row("abc","SpeechSynthesizer","Speak",new JSONObject().put("text",learning).put("multi_intention",true))).explicit);
        }
        for (String success : new String[]{"我还在学习","对不起，我还在学习这段历史","对不起，我还在学习。答案是二。","有人说：对不起我还在学习"})
            check(!assess(search,row("abc","SpeechSynthesizer","Speak",new JSONObject().put("text",success))).explicit);
        check(!assess(query("打开B站搜索视频，关闭电视"),refusal).explicit);
        check(!assess(query("继续执行然后删除文件"),refusal).explicit);
        for (Object cat : new Object[]{"UNKNOWN","SUCC","SAME_STATE","NEED_CONFIRM","LIMIT","EXCEPTION","CANCEL",4,5,JSONObject.NULL})
            check(!assess(q,speak(cat)).explicit);
        check(!assess(q,speak("SUCC"),refusal).explicit);
        check(!assess(q, row("other","SpeechSynthesizer","Speak",new JSONObject().put("text","抱歉").put("tts_category","UNSUPPORT"))).explicit);
        check(!assess(q,row("abc","Dialog","IllegalContent",new JSONObject()),refusal).explicit);
        check(!assess(q,row("abc","Template","Toast",new JSONObject().put("text","未处理")),refusal).explicit);
        check(!assess(query("打开书房灯然后把电视音量降到1"),refusal).explicit);
        check(!assess(query("Open the lights and read the TV volume"),refusal).explicit);
        check(!assess(query("a\nb"),refusal).explicit);
        for (Object v : new Object[]{true, "true", 1, JSONObject.NULL})
            check(!assess(q,row("abc","SpeechSynthesizer","Speak",new JSONObject().put("text","抱歉").put("tts_category","UNSUPPORT")
                    .put("multi_intention_non_speak",v))).explicit);
        check(!assess(q+"junk",refusal).explicit);
        check(!assess(q,refusal,"{}").explicit);
        String no = "抱歉，我无法获取星仪休眠定时。";
        JSONObject text = new JSONObject().put("text",no);
        String untyped = row("abc","SpeechSynthesizer","Speak",text);
        String passive = row("abc","Template","Toast",text);
        String markdown = java.util.Base64.getEncoder().encodeToString(no.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String stream = row("abc","Template","ToastStream",new JSONObject().put("markdown_text",markdown));
        String footer = row("abc","Nlp","LargeLanguageModelContent",new JSONObject().put("bottom_button_display",false));
        check(assess(q,untyped).explicit);
        check(assess(q,passive,untyped,stream,footer).reason.equals("explicit_readonly_text:cannot_read"));
        String audioOnly = row("abc", "SpeechSynthesizer", "Speak", new JSONObject().put("text",""));
        check(assess(q,stream,audioOnly,footer).explicit);
        check(!assess(q,audioOnly,footer).explicit);
        check(!assess(query("把音量加一"),stream,audioOnly,footer).explicit);
        check(!assess(q,passive).explicit);
        check(!assess(query("把电视音量设为十五"),passive,untyped).explicit);
        check(!assess(q,untyped,row("abc","Template","Toast",new JSONObject().put("text","当前音量为30。"))).explicit);
        check(!assess(q,untyped,passive,row("abc","Template","ToastStream",new JSONObject().put("markdown_text","我帮您设置好了。"))).explicit);
        check(!assess(q,untyped,row("abc","Template","Toast",new JSONObject().put("text",no).put("launcher",new JSONObject()))).explicit);
        check(!assess(q,untyped,row("abc","Nlp","LargeLanguageModelContent",new JSONObject().put("instruction","act"))).explicit);
        check(!assess(q,passive,row("abc","SpeechSynthesizer","Speak",new JSONObject().put("text",no).put("tts_category","SUCC"))).explicit);
        check(!assess(q,passive,row("abc","SpeechSynthesizer","Speak",new JSONObject().put("text",no).put("multi_intention",true))).explicit);
        check(!assess(q,passive,untyped,untyped).explicit);
        String ordinary = query("现在电视音量是多少"), song = query("打开网易云音乐，播放林俊杰的《不为谁而作的歌》");
        JSONObject clear = new JSONObject().put("text","我暂时不支持这个操作。");
        String declined = row("abc","SpeechSynthesizer","Speak",clear);
        String card = row("abc","Template","Toast",clear);
        check(assess(ordinary,card,declined).reason.startsWith("explicit_readonly_text:"));
        check(assess(song,card,declined).reason.equals("explicit_scoped_action:native_capability_song"));
        check(!assess(song,declined,row("abc","Template","Toast",new JSONObject().put("text","已开始播放。"))).explicit);
        check(!assess(query("打开网易云音乐然后关闭书房灯"),card,declined).explicit);
        check(!assess(song,card,speak("EXCEPTION")).explicit);
        check(!assess(query("不要打开网易云音乐"),declined).explicit);
        String byArtist = query("网易云音乐打开周杰伦的歌");
        JSONObject unavailable = new JSONObject().put("text","抱歉，目前暂不支持该功能");
        String voice = row("abc","SpeechSynthesizer","Speak",unavailable);
        String toast = row("abc","Template","Toast",unavailable);
        check(assess(byArtist,voice,toast).explicit);
        String selection = query("黄霄云的空心");
        String serviceRefusal = row("abc","SpeechSynthesizer","Speak",new JSONObject()
                .put("text","抱歉，当前暂不支持使用网易云音乐源播放，将继续使用当前音乐源为您播放"));
        check(assess(selection,serviceRefusal).explicit && assess(selection,serviceRefusal).musicSelection);
        for(String unparsed : new String[]{"能不能帮我在网易云来首适合夜跑的歌","用网易云播放适合夜跑的音乐","请在网易云给我放一下昨晚听过的那首歌"}) {
            check(GenericRefusalText.scope(unparsed)==null && GenericRefusalText.musicSelection(unparsed)==null);
            XiaomiRefusalGate.Assessment decision=assess(query(unparsed),serviceRefusal);
            check(decision.explicit && !decision.musicSelection && "explicit_harness_refusal".equals(decision.reason));
        }
        for (String unrelated : new String[]{"介绍下网易云的播放功能","网易云音乐为什么不能播放歌曲","我不知道网易云能不能播放歌曲",
                "别用网易云给我放首歌","请复述“在网易云播放空心”","帮我在QQ音乐来首喜欢的歌","请在网易云来首歌然后关灯",
                "给我看看网易云的账号密码","打开网易云应用","讲讲《网易云播放歌曲》","听说黄霄云的空心","有人说刘端端的空心"}) {
            check(!assess(query(unrelated),serviceRefusal).explicit);
        }
        check(!assess(query("能不能帮我在网易云来首适合夜跑的歌"),row("abc","SpeechSynthesizer","Speak",
                new JSONObject().put("text","已在网易云开始播放。").put("tts_category","SUCC"))).explicit);
        check(assess(selection,voice).explicit && !assess(selection,voice).musicSelection);
        check(XiaomiRefusalGate.assess("abc",Arrays.asList(selection,voice),"空心").musicSelection);
        check(!XiaomiRefusalGate.assess("abc",Arrays.asList(selection,voice),"其他歌").musicSelection);
        String acceptedMusic = row("abc","SpeechSynthesizer","Speak",new JSONObject().put("text","好的，一起来听空心。").put("tts_category","SUCC"));
        check(assess(selection,acceptedMusic).musicSelection && !assess(selection,acceptedMusic).explicit);
        check(!assess(query("黄霄云的其他歌"),acceptedMusic).musicSelection);
        check(!assess(selection,voice,row("abc","Template","Toast",new JSONObject().put("text","已开始播放"))).explicit);
        check(!assess(byArtist,voice,row("abc","Template","Toast",new JSONObject().put("text","已开始播放。"))).explicit);
        check(!assess(query("网易云音乐打开周杰伦的歌然后关灯"),voice,toast).explicit);
        String loose = query("这台电视还能做哪些事情");
        check(GenericRefusalText.scope("这台电视还能做哪些事情")==null);
        check(assess(loose,voice,toast).reason.equals("explicit_harness_refusal"));
        check(assess(loose,voice).explicit && assess(loose,speak("UNSUPPORT")).explicit);
        check(!assess(loose,audioOnly,toast).explicit && !assess(loose,toast).explicit);
        for (String unsafeReply : new String[]{"抱歉，目前暂不支持该功能，请登录","抱歉，网络连接失败",
                "可能不支持该功能","抱歉，我不能读取账号密码","如果不支持该功能怎么办？","电视已进入休眠"})
            check(!assess(loose,row("abc","SpeechSynthesizer","Speak",new JSONObject().put("text",unsafeReply))).explicit);
        for (String outOfScope : new String[]{"读取我的账户密码","不要打开书房灯","先打开相册然后打开灯","解锁儿童锁"})
            check(!assess(query(outOfScope),voice).explicit);
        check(!assess(loose,voice,row("abc","Template","Toast",
                new JSONObject().put("text","抱歉，目前暂不支持该功能").put("actions",new org.json.JSONArray()))).explicit);
        check(!assess(loose,voice,row("abc","Template","Toast",new JSONObject().put("text","已经打开"))).explicit);
        check(!assess(loose,voice,row("abc","Application","LaunchApp",new JSONObject())).explicit);
        System.out.println("PASS " + count + " refusal gate checks");
    }
}
