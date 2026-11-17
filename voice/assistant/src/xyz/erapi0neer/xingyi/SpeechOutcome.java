package xyz.erapi0neer.xingyi;

import java.util.regex.Pattern;
import org.json.JSONObject;

/** Per-Activity, in-memory dispatch receipts. The text and arguments are never retained. */
final class SpeechOutcome {
    private static final String DISPLAY = "处理结果已显示在星仪上。";
    private static final Pattern PRIVATE = Pattern.compile(
            "(?i)密码|验证码|密钥|令牌|口令|认证码|授权码|password|passcode|secret|token|cookie|authorization|bearer|api[ _-]?key|"
            + "(?:sk|pk)-[a-z0-9_-]{8,}|[a-z0-9_+/=-]{24,}|\\d{6,}|[\\w.+-]+@[\\w.-]+");
    private static final Pattern MARKUP = Pattern.compile("[<>{}\\[\\]|`*_~#]|https?://|www\\.",Pattern.CASE_INSENSITIVE);
    private static final Pattern NATIVE_DECLINE = Pattern.compile("抱歉|很遗憾.{0,12}不支持|暂不支持使用网易云|将继续使用当前音乐源");
    private int generation = -1, lastPreview = -1;
    private boolean terminal, sent;
    private long sequence;
    private String stage = "idle";
    private String requestHash="", spokenHash="";

    void begin(int token,String prompt) {
        generation=token; lastPreview=-1; terminal=false; sent=false; stage="waiting";
        requestHash=HandoffBinding.digest(prompt); spokenHash="";
    }
    boolean claim(int token,String next,int preview) {
        if (token != generation || terminal) return false;
        if ("confirmation".equals(next)) {
            if (preview <= lastPreview) return false;
            lastPreview=preview;
        } else terminal=true;
        stage=next; sent=false; spokenHash=""; sequence++;
        return true;
    }
    void submitted(boolean success,String text) { sent=success; spokenHash=success ? HandoffBinding.digest(text) : ""; }
    JSONObject receipt(String target) throws Exception {
        boolean matched=HandoffBinding.hex64(target) && HandoffBinding.equal(requestHash,target);
        JSONObject result=new JSONObject().put("matched",matched);
        return matched ? result.put("spoken_seq",sequence).put("spoken_stage",stage).put("spoken_submitted",sent)
                .put("spoken_hash",spokenHash) : result;
    }
    static String answer(String raw) {
        if (raw==null || raw.length()>12000) return DISPLAY;
        String text=raw.replaceAll("(?is)<(?:think|thinking|reasoning)(?:\\s[^>]*)?>.*?(?:</(?:think|thinking|reasoning)>|\\z)","")
                .replaceAll("(?s)```.*?(?:```|\\z)","")
                .replaceAll("(?m)^\\s{0,3}(?:#{1,6}|[-*])\\s+","")
                .replace("**","").replace("__","").replaceAll("\\s+"," ").trim();
        if (text.isEmpty() || PRIVATE.matcher(text).find() || MARKUP.matcher(text).find()
                || NATIVE_DECLINE.matcher(text).find()) return DISPLAY;
        if (text.codePointCount(0,text.length())<=180) return text;
        int limit=text.offsetByCodePoints(0,162);
        String head=text.substring(0,limit);
        int end=Math.max(Math.max(head.lastIndexOf('。'),head.lastIndexOf('！')),head.lastIndexOf('？'));
        return end>=0 ? head.substring(0,end+1)+"其余内容请看电视。" : DISPLAY;
    }
    static String music(JSONObject result) {
        String status=result.optString("status");
        if (result.optBoolean("ok") && ("observed".equals(status) || "already_playing".equals(status)
                || "artist_already_playing".equals(status) || "title_already_playing".equals(status)))
            return answer("网易云正在播放"+result.optString("artist")+"的《"+result.optString("title")+"》。");
        if ("not_found".equals(status)) return "网易云没有找到符合要求的歌曲。";
        if ("unavailable".equals(status)) return "电视上的网易云点播入口不可用。";
        if ("lookup_failed".equals(status)) return "这次没能查到网易云曲库。";
        if ("edition_uncertain".equals(status)) return "同名歌曲正在播放，专辑版本没有确认，已保留当前播放。";
        return "这次没能确认网易云已经播出目标歌曲。";
    }
}
