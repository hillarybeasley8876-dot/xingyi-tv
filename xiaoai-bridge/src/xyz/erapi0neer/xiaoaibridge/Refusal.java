package xyz.erapi0neer.xiaoaibridge;

import java.util.regex.Pattern;

/**
 * Recognizes XiaoAi's "I can't" answers (the fallback lines the reference video keys on): only these hand the
 * request to our model; anything XiaoAi can answer itself, including its own large-model answers, is left alone.
 */
final class Refusal {
    /**
     * Phrases that on their own mean XiaoAi did not do or did not understand the request. "没有找到" stands alone:
     * XiaoAi puts the query between it and the rest ("小爱没有找到星辰人旅行者的mad的资源，为您推荐以下相关内容",
     * 2026-11-05, a video request it answered with a page of unrelated recommendations).
     */
    private static final Pattern STRONG = Pattern.compile(
            "暂不支持|暂时不支持|还不支持|不支持这个|我还不会|还没学会|我还在学习|正在学习中|没有听懂|没听懂|没听清|没有听清|"
            + "听不懂|不太明白|没太明白|没明白你|不太理解|没能理解|无法理解|我不知道|我也不知道|不太清楚|我不清楚|"
            + "答不上来|回答不了|无法回答|不能回答|无法为你|不能为你|没办法帮你|帮不了你|做不到|换个说法|换种说法|"
            + "没有找到|没找到|找不到|未找到|未能找到|推荐以下相关|没有搜到|暂无相关|超出了我的|这个问题有点难|这个问题太难");
    /** An apology only counts together with a negative. */
    private static final Pattern APOLOGY = Pattern.compile("抱歉|对不起|不好意思|sorry", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATIVE = Pattern.compile("不|没|无法|未能|暂时");

    static boolean is(String answer) {
        if (answer == null) return false;
        String a = answer.trim();
        if (a.isEmpty() || a.length() > 120) return false;
        if (STRONG.matcher(a).find()) return true;
        return APOLOGY.matcher(a).find() && NEGATIVE.matcher(a).find();
    }

    private Refusal() { }
}
