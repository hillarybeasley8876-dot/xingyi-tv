package xyz.erapi0neer.xiaoaibridge;

import java.util.regex.Pattern;

/**
 * Who a sentence to XiaoAi is for when 星仪 does not take everything (2026-11-06, the user: 「小爱走小爱 星仪走星仪」):
 * a request that calls 星仪 by name goes to 星仪, everything else stays with XiaoAi. And the spoken switch between that
 * and 星仪 taking every request. Plain Java, checked by tests/AddressingTest.java.
 */
final class Addressing {
    private Addressing() { }

    /** How XiaoAi's recognizer may write 星仪 (xīng yí) at the start of a sentence: it had never written the name down on
     * this TV before 2026-11-06, so the likely homophones count there; anywhere else only 星仪 itself does. */
    private static final Pattern NAME_FIRST = Pattern.compile(
            "^[\\s，,。.！!？?、]*(?:星仪|星移|星宜|星怡|星一|新仪|新移|心仪|欣怡|兴仪|行仪|星姨)");

    static boolean namesXingyi(String text) {
        if (text == null) return false;
        return text.contains("星仪") || NAME_FIRST.matcher(text).find();
    }

    /** 「小爱走小爱」「点名模式」: XiaoAi keeps its own requests (said first: 「不要全部交给星仪」 also names the other). */
    private static final Pattern BY_NAME = Pattern.compile(
            "小爱走小爱|点名模式|叫星仪(?:的时候)?才(?:找|交给|用)星仪|只有叫星仪|小爱的事(?:让|给|交给)?小爱(?:做|办|处理|自己)"
                    + "|(?:不要|别|不用)(?:再)?(?:把)?(?:全部|所有|什么都)(?:都)?交给星仪");
    /** 「全部交给星仪」: 星仪 takes every request again. */
    private static final Pattern ALL = Pattern.compile(
            "(?:全部|所有|什么都)(?:都)?交给星仪|星仪(?:全部)?接管(?:全部|所有|一切)?|(?:全部|所有的事)让星仪(?:来|做|处理)");

    /** "refusals" (by name), "all" (星仪 takes everything) or null when the sentence is not about this. */
    static String modeRequest(String text) {
        if (text == null) return null;
        String s = text.replaceAll("[\\s，,。.！!？?、]", "");
        if (s.isEmpty() || s.length() > 30) return null;
        if (BY_NAME.matcher(s).find()) return "refusals";
        if (ALL.matcher(s).find()) return "all";
        return null;
    }
}
