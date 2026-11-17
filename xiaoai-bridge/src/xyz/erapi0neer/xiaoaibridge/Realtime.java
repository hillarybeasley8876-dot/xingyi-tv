package xyz.erapi0neer.xiaoaibridge;

import java.util.regex.Pattern;

/** Whether a question needs live data (prices, weather, scores, news), i.e. a web search before answering. */
final class Realtime {
    /** Topics that are live by nature, whatever else the sentence says. */
    private static final Pattern LIVE = Pattern.compile(
            "天气|气温|下雨|下雪|雾霾|空气质量|股价|股票|汇率|金价|油价|热搜|新闻|比分|赛果|赛程|票房|开奖|限行|航班|地震");
    private static final Pattern WHEN = Pattern.compile(
            "今天|今日|现在|此刻|目前|当前|最新|实时|刚刚|刚才|昨天|明天|后天|本周|这周|今年|最近");
    private static final Pattern DATA = Pattern.compile(
            "价格|多少钱|价钱|报价|行情|排名|结果|成绩|名单|发布|上映|播出|涨|跌|几度|多少度|冠军");

    static boolean needsSearch(String question) {
        if (question == null || question.trim().isEmpty()) return false;
        return LIVE.matcher(question).find() || WHEN.matcher(question).find() && DATA.matcher(question).find();
    }

    private Realtime() { }
}
