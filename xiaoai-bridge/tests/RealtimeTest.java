package xyz.erapi0neer.xiaoaibridge;

/** When does a question need a web search first? */
public final class RealtimeTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }

    public static void main(String[] args) {
        String[] live = {"今天黄金价格是多少", "今天金价多少", "明天北京天气怎么样", "现在外面气温多少度", "明天会下雨吗", "上海今天有雾霾吗",
            "茅台股价多少", "美元兑人民币汇率", "最近油价涨了吗", "今天的热搜是什么", "有什么新闻", "昨天国足的比分是多少",
            "湖人最近的赛程", "这周票房冠军是哪部电影", "今天双色球开奖号码", "北京今天限行尾号", "刚才哪里地震了",
            "今年诺贝尔文学奖名单", "最新的iPhone价格", "比亚迪今天涨了还是跌了", "现在比特币多少钱", "今天苹果发布了什么新品"};
        String[] stable = {"先有鸡还是先有蛋", "量子纠缠是什么原理", "李白是哪个朝代的", "珠穆朗玛峰有多高", "一年有多少天",
            "怎么做红烧肉", "为什么天空是蓝色的", "地球离太阳多远", "介绍一下OpenAI这家公司", "今天是几号", "现在几点了",
            "长城有多长", "水的沸点是多少度", "光速是多少", "背一首李白的诗", "讲个笑话", "一加一等于几", "勾股定理是什么",
            "猫为什么怕水", "哪个国家人口最多", "", "   "};
        for (String q : live) check(Realtime.needsSearch(q), "needs search: " + q);
        for (String q : stable) check(!Realtime.needsSearch(q), "no search: " + q);
        check(!Realtime.needsSearch(null), "null");
        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (realtime)");
        if (fail > 0) System.exit(1);
    }
}
