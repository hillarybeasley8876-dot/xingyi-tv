package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** The data tools sent only when a request may need them (Contract gates, 2026-11-07). */
final class GateChecks {
    private GateChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    private static Set<String> names(JSONArray tools) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < tools.length(); i++) out.add(tools.optJSONObject(i).optJSONObject("function").optString("name"));
        return out;
    }

    static void run() throws Exception {
        Contract c = AgentCoreTest.contract;
        // which requests bring which data tools
        for (String plain : new String[]{"你好", "现在几点", "纽约天气怎么样", "给我加油", "放一部3D电影",
                "用一句话解释一下什么是量子纠缠", "打开网易云播放林俊杰的歌"})
            check(c.gatedIn(plain).isEmpty(), plain + ": no data tool (the common prefix) — got " + c.gatedIn(plain));
        check(c.gatedIn("茅台股价多少").equals(set("stock_quote")), "茅台股价多少 → stock_quote");
        check(c.gatedIn("曼联昨天赢了吗").contains("football_games"), "曼联 → football_games");
        check(c.gatedIn("湖人下一场什么时候").contains("nba_games"), "湖人 → nba_games");
        check(c.gatedIn("今天油价多少").equals(set("fuel_price")), "油价 → fuel_price");
        check(c.gatedIn("刚才是不是地震了").equals(set("earthquake")), "地震 → earthquake");
        check(c.gatedIn("我朋友在纽约，他那边现在几点").equals(set("world_clock")), "a place and the time → world_clock");
        check(c.gatedIn("伦敦和北京时差多少").equals(set("world_clock")), "时差 → world_clock");
        // the full calibration with gates (2026-11-07): these two had gone to a web search
        check(c.gatedIn("伦敦跟我们这儿差几个小时").equals(set("world_clock")), "a place and 差几个小时 → world_clock");
        check(c.gatedIn("易方达蓝筹今天涨了多少").contains("fund_nav"), "a fund company's name → fund_nav");
        check(c.gatedIn("腾讯今天涨了多少").contains("stock_quote"), "涨了多少 → stock_quote");
        check(c.gatedIn("CCTV5现在在放什么").contains("tv_guide"), "CCTV → tv_guide");
        check(c.gatedIn("最近有什么科技新闻").contains("news"), "新闻 → news");
        check(c.gatedIn("双色球开奖了吗").equals(set("lottery")), "双色球 → lottery");
        check(c.gatedIn("一百欧元换多少人民币").equals(set("exchange_rate")), "欧元 → exchange_rate");
        check(c.gatedIn("易方达蓝筹基金净值").equals(set("fund_nav")), "基金 → fund_nav");
        check(c.gatedIn("历史上的今天").equals(set("history_today")), "历史上 → history_today");
        check(c.gatedIn("现在有什么热搜").equals(set("hot_list")), "热搜 → hot_list");
        check(c.gatedIn("今天金价多少").equals(set("gold_price")), "金价 → gold_price");
        // B站's own requests carry bili_videos (2026-11-07): their own common prefix
        check(c.gatedIn("B站搜猫").equals(set("bili_videos")) && c.gatedIn("打开B站的历史记录").equals(set("bili_videos")), "B站 → bili_videos");
        check(c.gatedIn("放星辰亻旅行者最新的视频").equals(set("bili_videos")) && c.gatedIn("影视飓风最近发了什么").contains("bili_videos")
                && c.gatedIn("在B站搜索用户示例小号播放它播放量最高的视频").equals(set("bili_videos")), "an UP's videos → bili_videos");
        // on the TV 「何同学开播了没」 went to a 12 s web search: 开播 was no gate word
        check(c.gatedIn("狂飙在哪个平台能看").contains("movie_rating") && c.gatedIn("繁花一共几集").contains("movie_rating"), "在哪看 / 几集 → movie_rating");
        check(c.gatedIn("林俊杰最近发新歌了吗").contains("music_new") && c.gatedIn("网易云音乐播放林俊杰最新发表的一个单曲").contains("music_new"), "新歌 / 最新发表 → music_new");
        check(c.gatedIn("最近有什么电影上映").equals(set("movie_list")) && c.gatedIn("推荐个好看的韩剧").contains("movie_list"), "上映 / 韩剧 → movie_list");
        check(c.gatedIn("今天宜什么忌什么").equals(set("almanac")) && c.gatedIn("明天适合搬家吗").equals(set("almanac"))
                && c.gatedIn("这个好便宜").isEmpty(), "黄历 / 宜忌 → almanac; 便宜 is not");
        check(c.gatedIn("现在有什么天气预警").equals(set("weather_alert")) && c.gatedIn("今年有台风要来上海吗").equals(set("weather_alert"))
                && c.gatedIn("明天天气怎么样").isEmpty(), "预警 / 台风 → weather_alert; the weather itself is not gated");
        check(c.gatedIn("今天有什么番更新").equals(set("bili_bangumi")) && c.gatedIn("凡人修仙传什么时候更新").equals(set("bili_bangumi"))
                && c.gatedIn("柯南更新了吗").contains("bili_bangumi"), "番 / 更新 / a big show's name → bili_bangumi (更新了 brings bili_videos too)");
        check(c.gatedIn("狂飙豆瓣多少分").equals(set("movie_rating")) && c.gatedIn("流浪地球2值得看吗").equals(set("movie_rating"))
                && c.gatedIn("现在几点几分").isEmpty(), "豆瓣 / 值得看 → movie_rating; 几分 alone is the time");
        check(c.gatedIn("何同学开播了没").equals(set("bili_videos")) && c.gatedIn("影视飓风在直播吗").equals(set("bili_videos")), "直播 / 开播 → bili_videos");

        // the tools and the system text
        JSONArray none = c.toolsFor(Collections.<String>emptySet()), stock = c.toolsFor(set("stock_quote"));
        check(none.length() == c.names().size() - c.gated().size() && c.gated().size() == 23, "without data words: every tool but the 23 gated (find_installer 2026-11-07 night, netease_karaoke 11-09)");
        check(names(stock).contains("stock_quote") && !names(none).contains("stock_quote") && !names(stock).contains("lottery"), "a gated tool when its words are there");
        check(names(none).containsAll(set("web_search", "weather", "calendar", "screen_read", "netease_play", "open_link")), "the core tools always");
        String bare = c.systemFor(Collections.<String>emptySet());
        check(!bare.contains("stock_quote") && c.systemFor(set("stock_quote")).contains("stock_quote"), "a gated tool's rule line only with the tool");
        check(bare.contains("web_search") && bare.contains("netease_play") && bare.length() < c.system.length(), "the other rule lines stay");
        check(c.systemFor(new HashSet<>(c.gated())).equals(c.system), "every gated tool in: the whole system text");
        check(Contract.mentions("用 news 读标题", "news") && !Contract.mentions("看 newsletter", "news") && !Contract.mentions("hot_list_x", "hot_list")
                && Contract.mentions("hot_list：", "hot_list"), "a tool's name as a word of its own");
        check(c.names().contains("stock_quote") && c.check("stock_quote", AgentCoreTest.o("query", "茅台")).optString("query").equals("茅台"),
                "a gated tool is still known (the model may call it from an earlier turn)");

        // what the rounds send
        AgentCoreTest.Script stockAsk = new AgentCoreTest.Script().text("好的");
        AgentCoreTest.loop(stockAsk, new AgentCoreTest.Events(), (w, l) -> true).run(new AgentLoop.Request("茅台股价多少", "test"));
        check(names(stockAsk.toolsSeen.get(0)).contains("stock_quote") && !names(stockAsk.toolsSeen.get(0)).contains("lottery"), "the round sends stock_quote, not lottery");
        AgentCoreTest.Script hello = new AgentCoreTest.Script().text("你好呀");
        AgentCoreTest.loop(hello, new AgentCoreTest.Events(), (w, l) -> true).run(new AgentLoop.Request("你好", "test"));
        check(!names(hello.toolsSeen.get(0)).contains("stock_quote") && hello.toolsSeen.get(0).length() == none.length(), "你好 sends the common list");
        check(hello.seen.get(0).getJSONObject(0).getString("content").equals(bare), "and the common system text");

        // a follow-up carries what it follows; the list just read out carries its tool
        AgentLoop.Request follow = new AgentLoop.Request("那腾讯呢", "test");
        follow.recent = new JSONArray().put(AgentCoreTest.o("said", "茅台股价多少", "replied", "贵州茅台 1453 元"));
        AgentCoreTest.Script f = new AgentCoreTest.Script().text("好的");
        AgentCoreTest.loop(f, new AgentCoreTest.Events(), (w, l) -> true).run(follow);
        check(names(f.toolsSeen.get(0)).contains("stock_quote"), "那腾讯呢 after a stock question: stock_quote");
        AgentLoop.Request list = new AgentLoop.Request("第二条是怎么回事", "test");
        list.environment.put("last_list", AgentCoreTest.o("what", "百度热搜", "items", new JSONArray()));
        AgentCoreTest.Script l = new AgentCoreTest.Script().text("好的");
        AgentCoreTest.loop(l, new AgentCoreTest.Events(), (w, x) -> true).run(list);
        check(names(l.toolsSeen.get(0)).contains("hot_list"), "第二条 after 百度热搜: hot_list");

        // the strong model gets every tool
        AgentCoreTest.Fake read = new AgentCoreTest.Fake("screen_read", Tool.Gate.read());
        AgentCoreTest.Script slow = new AgentCoreTest.Script();
        for (int i = 0; i < 5; i++) slow.calls("screen_read", new JSONObject());
        slow.text("看完了");
        AgentLoop.Limits limits = new AgentLoop.Limits();
        limits.escalateRound = 3;
        long[] t = {1_000_000};
        new AgentLoop(c, slow, Collections.<Tool>singletonList(read), new AgentCoreTest.Events(), (w, x) -> true, () -> t[0] += 10, limits)
                .run(new AgentLoop.Request("屏幕上写的什么", "test"));
        check(slow.toolsSeen.size() >= 3 && slow.toolsSeen.get(0).length() == none.length()
                && slow.toolsSeen.get(2).length() == c.names().size(), "from the escalation on, every tool: " + slow.toolsSeen.size());
    }
}
