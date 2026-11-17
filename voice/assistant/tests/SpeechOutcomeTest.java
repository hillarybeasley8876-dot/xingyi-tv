package xyz.erapi0neer.xingyi;

import org.json.JSONObject;

public final class SpeechOutcomeTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        SpeechOutcome s = new SpeechOutcome();
        String q="查询电视音量", key=HandoffBinding.digest(q);
        s.begin(2,q);
        check(!s.claim(1,"answer",0));
        check(s.claim(2,"confirmation",1) && !s.claim(2,"confirmation",1));
        check(s.claim(2,"confirmation",2) && s.claim(2,"answer",0) && !s.claim(2,"answer",0));
        s.submitted(true,"当前为十五。");
        JSONObject r=s.receipt(key);
        check(r.getBoolean("matched") && r.getBoolean("spoken_submitted") && r.getLong("spoken_seq")==3
                && r.getString("spoken_hash").equals(HandoffBinding.digest("当前为十五。")));
        check(s.receipt(null).length()==1 && !s.receipt(HandoffBinding.digest("other")).getBoolean("matched"));
        s.begin(3,"q2");
        check(!s.claim(2,"failed",0) && !s.receipt(key).getBoolean("matched"));
        check(SpeechOutcome.answer("<think>private reasoning</think>**电视音量为十五。**").equals("电视音量为十五。"));
        for (String text:new String[]{"<script>ignore</script>", "令牌 abc123", "验证码是 999999","抱歉，目前暂不支持该功能",
                "Bearer qwerty", "https://example.com/?auth=x", "play this | row", "密钥 sk-example123"}) {
            check(!SpeechOutcome.answer(text).contains(text) && SpeechOutcome.answer(text).contains("显示"));
        }
        String longReply="先读到这里。"+new String(new char[205]).replace('\0','甲');
        check(SpeechOutcome.answer(longReply).equals("先读到这里。其余内容请看电视。"));
        JSONObject result=new JSONObject().put("ok",true).put("status","observed").put("artist","林俊杰").put("title","不为谁而作的歌");
        check(SpeechOutcome.music(result).equals("网易云正在播放林俊杰的《不为谁而作的歌》。"));
        result.put("ok",false).put("status","unconfirmed");
        check(!SpeechOutcome.music(result).contains("正在播放"));
        System.out.println("PASS spoken result scope, pending/final dedup, stale turn, redaction, bounded text, checked music and failure.");
    }
}
