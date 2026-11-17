package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** movie_rating (2026-11-07): Douban's ratings, from its mobile search as saved that night. */
final class MovieChecks {
    private MovieChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    private static String say(String file, String asked, int year) throws Exception { return Movies.speak(Movies.titles(fixture(file)), asked, year); }

    static void run() {
        try {
            ratings();
            lists();
            where();
            quick();
            tencent();
            tencentLinks();
            tencentAlias();
            tencentWatch();
            tencentHeard();
            tencentEpisodes();
        } catch (Exception e) {
            check(false, "movie checks threw " + e);
        }
    }

    static void ratings() throws Exception {
        List<Movies.Title> kb = Movies.titles(fixture("douban-kuangbiao.json"));
        check(kb.size() == 5 && kb.get(0).name.equals("狂飙") && kb.get(0).votes == 1074807, "the search's titles: " + kb.size());
        String s = say("douban-kuangbiao.json", "狂飙", 0);
        check(s.equals("《狂飙》（2023 年的电视剧）豆瓣 8.5 分，107 万人评价；中国大陆，剧情、犯罪，导演徐纪周，主演张译、张颂文"), "狂飙: " + s);
        s = say("douban-liulangdiqiu2.json", "流浪地球2", 0);
        check(s.equals("《流浪地球2》（2023 年的电影）豆瓣 8.3 分，143 万人评价；中国大陆，科幻、冒险、灾难，导演郭帆，主演吴京、刘德华"), "流浪地球2: " + s);
        s = say("douban-liulangdiqiu2.json", "流浪地球3(上)", 0);
        check(s.equals("《流浪地球3(上)》（2027 年的电影）豆瓣还没有评分：尚未上映；中国大陆，科幻、冒险、灾难，导演郭帆，主演吴京、沈腾"), "not out yet: " + s);
        s = say("douban-fanhua.json", "繁花", 0);
        check(s.startsWith("《繁花》（2023 年的电视剧）豆瓣 8.7 分，56 万人评价；"), "繁花: the series, not the film not out yet: " + s);
        s = say("douban-spyfamily.json", "间谍过家家", 0);
        check(s.equals("《间谍过家家 代号：白》（2023 年的动画电影）豆瓣 7.3 分，11 万人评价；日本，喜剧、动画，导演片桐崇，配音江口拓也、早见沙织；还有第三季 8.8 分、第一季 8.9 分"),
                "no title of that very name: the search's first, then the seasons by number: " + s);
        s = say("douban-benpaoba.json", "奔跑吧", 0);
        check(s.startsWith("《奔跑吧 第十季》（2026 年的综艺）豆瓣 6.7 分，1 万人评价；中国大陆，真人秀，导演姚译添，嘉宾李晨、郑恺；还有第九季 7.3 分、"),
                "a reality show is 综艺, with 嘉宾: " + s);
        s = say("douban-avatar3.json", "阿凡达3", 0);
        check(s.startsWith("《阿凡达：火与烬》（2025 年的电影）豆瓣 7.5 分，29 万人评价；美国，") && !s.contains("还有"), "the search knows 阿凡达3: " + s);
        s = say("douban-nezha2.json", "哪吒之魔童闹海", 0);
        check(s.startsWith("《哪吒之魔童闹海》（2025 年的动画电影）豆瓣 8.4 分，158 万人评价"), "哪吒: " + s);
        check(Movies.pick(Movies.titles(fixture("douban-liulangdiqiu2.json")), "流浪地球", 2019).year.equals("2019"), "the year said picks among the same name");
        boolean threw = false;
        try { Movies.titles("{\"banned\":\"请求过于频繁\",\"items\":[]}"); } catch (Exception e) { threw = true; }
        check(threw, "a ban is an error (the model searches), not an empty answer");
        check(Movies.titles("{\"banned\":\"\",\"count\":0,\"items\":[]}").isEmpty() && Movies.speak(Movies.titles("{\"items\":[]}"), "某某某", 0).equals("豆瓣上没找到「某某某」"), "nothing found");
    }

    /** 2026-11-07 02:00 in Beijing. */
    private static final long NOW = 1793988000000L;

    static void lists() throws Exception {
        List<Movies.Item> on = Movies.items(fixture("douban-list-showing.json"));
        String s = Movies.speakList("movie_showing", "正在上映的电影", on, 5, NOW);
        check(on.size() == 30 && s.startsWith("正在上映的热门电影：《欢迎来龙餐馆》8.7 分；《奥德赛》8.6 分；") && !s.contains("肖申克") && !s.contains("笑傲江湖")
                && s.contains("；这几天新上映：《我们仅有一次的人生》（今天）、《心迷宫之三重罪》（今天）"), "films on now: this year's most watched, re-releases left out, then the new: " + s);
        List<Movies.Item> tv = Movies.items(fixture("douban-list-tv.json"));
        s = Movies.speakList("tv_hot", "近期热门剧集", tv, 3, NOW);
        check(s.equals("近期热门剧集：第一，《小城良方》8.0 分，肖战、倪大红；第二，《嫁金钗》7.0 分，田曦薇、闫桉；第三，《末路兼职》还没有评分，李宰旭、高旻示"),
                "series in the list's order, leads from the subtitle: " + s);
        s = Movies.speakList("show_hot", "近期热门综艺", Movies.items(fixture("douban-list-show.json")), 2, NOW);
        check(s.startsWith("近期热门综艺：第一，《后会友期》8.7 分，"), "shows: " + s);
        check(Movies.list("有什么好看的韩剧")[0].equals("tv_korean") && Movies.list("")[0].equals("movie_showing") && Movies.list("综艺")[0].equals("show_hot")
                && Movies.list("最近有什么好看的电视剧")[0].equals("tv_hot"), "which list");
        check(Movies.clean("神探之痕迹" + (char) 0x200E).equals("神探之痕迹"), "a direction mark after a title is dropped");
        check(Movies.daysSince("11.08", NOW) == -1 && Movies.daysSince("11.07", NOW) == 0 && Movies.daysSince("10.05", NOW) == 33, "days since a release");
        long dec30 = 1798567200000L; // 2026-12-30 02:00 in Beijing
        check(Movies.daysSince("01.01", dec30) == -2 && Movies.daysSince("12.28", dec30) == 2, "across the new year: 01.01 on 12.30 is two days ahead");
        check(News.str(new org.json.JSONObject("{\"a\":null,\"b\":\"x\"}"), "a").isEmpty() && News.str(new org.json.JSONObject("{\"b\":\"x\"}"), "b").equals("x"),
                "a JSON null is no \"null\" (Android's org.json)");
    }

    static void where() throws Exception {
        Movies.Title kb = Movies.pick(Movies.titles(fixture("douban-kuangbiao.json")), "狂飙", 0);
        check(Movies.detailUrl(kb).equals("https://m.douban.com/rexxar/api/v2/tv/35465232?ck=&for_mobile=1"), "a series' page");
        Movies.Detail d = Movies.detail(fixture("douban-detail-kuangbiao.json"));
        String s = Movies.speakWhere(kb, d);
        check(d.episodes == 39 && s.equals("《狂飙》（2023 年的电视剧，豆瓣 8.5 分）可以在爱奇艺看（VIP免费观看），共 39 集"), "where a series streams: " + s);
        Movies.Title ld = Movies.pick(Movies.titles(fixture("douban-liulangdiqiu2.json")), "流浪地球2", 0);
        Movies.Detail m = Movies.detail(fixture("douban-detail-liulangdiqiu2.json"));
        s = Movies.speakWhere(ld, m);
        check(s.equals("《流浪地球2》（2023 年的电影，豆瓣 8.3 分）可以在腾讯视频、咪咕视频、爱奇艺、哔哩哔哩、优酷视频看（都是VIP免费观看），片长 173 分钟"), "a film on five: " + s);
        String bili = "";
        for (Movies.Vendor v : m.vendors) if (v.name.equals("哔哩哔哩")) bili = v.link;
        check(bili.equals("bilibili://pgc/season/44847") && Movies.onTv("腾讯视频").contains("tencent_video_search"), "B站's own link, without its tracking: " + bili);
        check(Movies.WHERE.matcher("狂飙在哪个平台能看").find() && Movies.WHERE.matcher("繁花一共几集").find() && !Movies.WHERE.matcher("狂飙豆瓣多少分").find(), "which questions want the page");
    }

    static void quick() throws Exception {
        String[][] cases = {{"狂飙豆瓣多少分", "狂飙"}, {"流浪地球2的评分是多少", "流浪地球2"}, {"繁花豆瓣评分", "繁花"}, {"《繁花》评分多少", "繁花"},
                {"电影哪吒之魔童闹海豆瓣几分", "哪吒之魔童闹海"}, {"间谍过家家这部动画评分怎么样", "间谍过家家"}};
        for (String[] c : cases) {
            QuickCommand q = QuickCommand.match(c[0], java.util.Collections.<String>emptySet());
            check(q != null && "movie_rating".equals(q.tool) && c[1].equals(q.args.optString("title")) && q.args.optBoolean("final"), c[0] + " → movie_rating " + c[1]
                    + (q == null ? " (none)" : " (" + q.tool + " " + q.args + ")"));
        }
        // bili_bangumi and weather_alert by their fixed questions (2026-11-07)
        String[][] fixed = {{"今天有什么番更新", "bili_bangumi", ""}, {"明天有什么新番", "bili_bangumi", "明天"}, {"今天有什么国创", "bili_bangumi", ""},
                {"现在有什么天气预警", "weather_alert", ""}, {"有台风吗", "weather_alert", "typhoon"}, {"台风到哪了", "weather_alert", "typhoon"},
                {"今天宜什么忌什么", "almanac", ""}, {"今天黄历", "almanac", ""}, {"明天黄历", "almanac", "明天"},
                {"最近有什么电影上映", "movie_list", "电影"}, {"有什么好看的韩剧", "movie_list", "韩剧"}, {"最近有什么好看的剧", "movie_list", "剧集"},
                {"推荐个综艺", "movie_list", "综艺"}, {"林俊杰最近发新歌了吗", "music_new", "林俊杰"}, {"周杰伦最新的歌是哪首", "music_new", "周杰伦"},
                {"最近有什么AI新闻", "news", "AI"}, {"AI圈有什么新闻", "news", "AI"},
                {"今天看了多久电视", "tv_usage", "今天"}, {"昨晚看了多久电视", "tv_usage", "昨晚"}, {"昨天电视开了多久", "tv_usage", "昨天"}};
        for (String[] c : fixed) {
            QuickCommand q = QuickCommand.match(c[0], java.util.Collections.<String>emptySet());
            boolean args = c[2].isEmpty() || ("typhoon".equals(c[2]) ? q != null && q.args.optBoolean("typhoon")
                    : q != null && (c[2].equals(q.args.optString("day")) || c[2].equals(q.args.optString("date")) || c[2].equals(q.args.optString("list"))
                    || c[2].equals(q.args.optString("artist")) || c[2].equals(q.args.optString("category"))));
            // tv_usage has no final: its sentence is the reply of the quick command itself
            check(q != null && c[1].equals(q.tool) && (q.args.optBoolean("final") || "tv_usage".equals(c[1])) && args,
                    c[0] + " → " + c[1] + (q == null ? " (none)" : " (" + q.tool + " " + q.args + ")"));
        }
        for (String no : new String[]{"繁花好看吗", "湖人得了多少分", "现在几点几分", "考试多少分算及格"}) {
            QuickCommand q = QuickCommand.match(no, java.util.Collections.<String>emptySet());
            check(q == null || !"movie_rating".equals(q.tool), no + ": not a quick rating");
        }
        // not a singer: these go to the model (「播放周杰伦的新歌」 is a request to play; the review, 2026-11-07)
        for (String no : new String[]{"最近有什么新歌", "他最近发新歌了吗", "今年有什么新歌", "最近有新歌吗", "播放周杰伦的新歌", "放一首周杰伦的新歌",
                "听周杰伦的新歌", "今天有什么新歌", "现在有什么新歌"}) {
            QuickCommand q = QuickCommand.match(no, java.util.Collections.<String>emptySet());
            check(q == null || !"music_new".equals(q.tool), no + ": not a singer's new songs " + (q == null ? "" : q.args));
        }
        // no title, no request: 「这部电影的评分」 read the search's first hit for 「这部电影」; 「电影」「消息」 alone answer a question
        for (String[] no : new String[][]{{"这部电影的评分", "movie_rating"}, {"这个剧豆瓣多少分", "movie_rating"}, {"它的评分是多少", "movie_rating"},
                {"电影", "movie_list"}, {"韩剧", "movie_list"}, {"综艺", "movie_list"}, {"消息", "news"}, {"有什么新消息", "news"},
                {"昨晚我看了什么", "tv_usage"}}) {
            QuickCommand q = QuickCommand.match(no[0], java.util.Collections.<String>emptySet());
            check(q == null || !no[1].equals(q.tool), no[0] + ": not " + no[1] + (q == null ? "" : " " + q.args));
        }
        QuickCommand news = QuickCommand.match("最近有什么新消息", java.util.Collections.<String>emptySet());
        check(news != null && "news".equals(news.tool), "最近有什么新消息 is still the news");
        for (String no : new String[]{}) {
            QuickCommand q = QuickCommand.match(no, java.util.Collections.<String>emptySet());
            check(q == null || !"movie_rating".equals(q.tool), no + ": not a quick rating");
        }
    }

    /** tencent_video_open (2026-11-10): what 腾讯视频's own search says about a title, from the answers saved that night. */
    static void tencent() throws Exception {
        TencentVideo.Result r = TencentVideo.pick(fixture("tencent-search-fanhua.json"), "繁花", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc00200whsp9r6,繁花,电视剧,2023,36"),
                "繁花: 腾讯视频里的那部，36 集 " + chosen(r));
        check(r.others.isEmpty(), "繁花: 搜索里另一个 繁花 2023 是同一部，不说「还有」 " + others(r));

        r = TencentVideo.pick(fixture("tencent-search-kuangbiao.json"), "狂飙", "", 0);
        check(r.is(TencentVideo.ELSEWHERE) && chosen(r).equals("sdp0010051ttxdf,狂飙,电视剧,2023,39")
                && r.platforms.toString().equals("[爱奇艺]"), "狂飙: 腾讯视频没有，在爱奇艺，39 集 " + chosen(r) + " " + r.platforms);
        TencentVideo.Result kb = TencentVideo.pick(fixture("tencent-search-kuangbiao.json"), "狂飙", "电视剧", 0);
        check(kb.is(TencentVideo.ELSEWHERE) && chosen(kb).equals(chosen(r)),
                "狂飙（说了是电视剧）: 过滤以后还是同一部 " + chosen(kb));

        r = TencentVideo.pick(fixture("tencent-search-qingyunian.json"), "庆余年", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("rjae621myqca41h,庆余年,电视剧,2019,46"),
                "庆余年: 同名的那一部（2019，46 集），不是最新一季 " + chosen(r));
        check(others(r).equals("庆余年第三季(2026) 庆余年第二季(2024)"),
                "庆余年: 「还有」两季；第一季特别版不是一季 " + others(r));
        r = TencentVideo.pick(fixture("tencent-search-qingyunian.json"), "庆余年", "", 2024);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc002002kqssyu,庆余年第二季,电视剧,2024,37") && r.others.isEmpty(),
                "庆余年（说了 2024 年）: 年份先过滤，所以是第二季 " + chosen(r) + " " + others(r));
        r = TencentVideo.pick(fixture("tencent-search-qingyunian.json"), "庆余年第二季", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc002002kqssyu,庆余年第二季,电视剧,2024,37") && r.others.isEmpty(),
                "庆余年第二季: 名字说全了就是同名那一部 " + chosen(r) + " " + others(r));

        r = TencentVideo.pick(fixture("tencent-search-fanren.json"), "凡人修仙传", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc003onbddtsyr,凡人修仙传,动漫,0,127"),
                "凡人修仙传: 动漫 127 集，没有年份就是 0 " + chosen(r));
        r = TencentVideo.pick(fixture("tencent-search-fanren.json"), "凡人修仙传", "电视剧", 0);
        check(r.is(TencentVideo.ELSEWHERE) && chosen(r).equals("sdp001000aabhuk,凡人修仙传,电视剧,2025,30")
                && r.platforms.toString().equals("[优酷]"), "凡人修仙传（说了是电视剧）: 腾讯视频没有，在优酷，30 集 " + chosen(r) + " " + r.platforms);

        r = TencentVideo.pick(fixture("tencent-search-paonan.json"), "奔跑吧", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc002001mel3nv,奔跑吧 第10季,综艺,2026,36"),
                "奔跑吧: 还在播的综艺，「奔跑吧」指今年那一季 " + chosen(r));
        check(r.others.size() == 3 && others(r).contains("奔跑吧(2017)"),
                "奔跑吧: 「还有」最多三部，同名的 2017 那季在其中 " + others(r));

        r = TencentVideo.pick(fixture("tencent-search-santi.json"), "三体", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc002007knmh3g,三体,电视剧,2023,40"),
                "三体: 电视剧 40 集 " + chosen(r));
        check(others(r).equals("三体·黑暗森林(2026) 三体·周年纪念版(2023)"),
                "三体: 「还有」两部，·开头的是同系列 " + others(r));
        r = TencentVideo.pick(fixture("tencent-search-santi.json"), "三体", "动漫", 0);
        check(r.is(TencentVideo.ELSEWHERE) && chosen(r).equals("sdp0010051y8m7i,三体,动漫,2022,15")
                && r.platforms.toString().equals("[哔哩哔哩]"), "三体（说了是动漫）: 在哔哩哔哩，15 集 " + chosen(r) + " " + r.platforms);

        r = TencentVideo.pick(fixture("tencent-search-liulang2.json"), "流浪地球2", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc002008v8rka3,流浪地球2,电影,2023,1") && r.others.isEmpty(),
                "流浪地球2: 电影，再次冒险不是它的一季 " + chosen(r) + " " + others(r));

        r = TencentVideo.pick(fixture("tencent-search-none.json"), "不存在的片", "", 0);
        check(r.is(TencentVideo.NONE) && r.show == null && r.others.isEmpty(), "什么都没有就是没有 " + r.status);
    }

    /**
     * tencent-open-2 (2026-11-10 23:00)：片子用的是简称（甄嬛传 = 后宫甄嬛传），和第一版不认识的平台。
     * 简称只在同名的一部和它的某一季都一部都没有时才找。
     */
    static void tencentAlias() throws Exception {
        String zh = fixture("tencent-search-zhenhuan.json");
        TencentVideo.Result r = TencentVideo.pick(zh, "甄嬛传", "", 0);
        check(r.is(TencentVideo.ELSEWHERE) && chosen(r).equals("sdp001kemwgrsyo,后宫甄嬛传,电视剧,2011,76"),
                "甄嬛传: 搜索里叫《后宫甄嬛传》，腾讯视频没有，在乐视视频 " + chosen(r));
        check(r.platforms.toString().equals("[乐视视频, 优酷]"), "后宫甄嬛传在乐视视频（还有优酷）" + r.platforms);
        check(r.show.episodes == 76 && r.others.isEmpty(), "76 集，" + r.show.episodes + " " + others(r));

        // 括号里的版本排在「名字以它结尾的」后面：说的是 2015 年那部才是《甄嬛传(美版)》
        r = TencentVideo.pick(zh, "甄嬛传", "", 2015);
        check(r.is(TencentVideo.ELSEWHERE) && chosen(r).equals("sdp0010051ud12a,甄嬛传(美版),电视剧,2015,76")
                && r.platforms.toString().equals("[乐视视频]"), "说了 2015 年：括号里的美版 " + chosen(r) + " " + r.platforms);

        // 两个字太短，不当简称找；相关盒子里那部纪录片任何时候都不选
        check(TencentVideo.pick(zh, "甄嬛", "", 0).is(TencentVideo.NONE), "「甄嬛」两个字：太短，不当简称");
        for (String kind : new String[]{"", "纪录片", "电视剧"}) {
            r = TencentVideo.pick(zh, "甄嬛传", kind, 0);
            boolean doc = r.show != null && "mzc00200tvpsyz0".equals(r.show.cid);
            check(!doc, "相关盒子里的《看懂《甄嬛传》就读懂了人性》不是这部片子（kind=" + kind + "）" + chosen(r));
        }

        // 简称只从 MainNeed 和 normalList 里找：同一个名字放在相关盒子里不算
        String related = answer(box("3505", item("aaa111", "后宫甄嬛传", "电视剧", 2011, 76, "letv")), "");
        check(TencentVideo.pick(related, "甄嬛传", "", 0).is(TencentVideo.NONE), "相关盒子里的简称不找");

        // 简称的类型：只认能看的片子，解读视频（纪录片）不算
        String docAlias = answer(box("MainNeed", item("aaa111", "后宫甄嬛传", "纪录片", 2011, 76, "qq")), "");
        check(TencentVideo.pick(docAlias, "甄嬛传", "", 0).is(TencentVideo.NONE), "纪录片不算一部能看的片子");
        check(TencentVideo.pick(docAlias, "甄嬛传", "纪录片", 0).is(TencentVideo.FOUND), "用户点名要纪录片才算");

        String ly = fixture("tencent-search-langya.json");
        r = TencentVideo.pick(ly, "琅琊榜", "", 0);
        check(r.is(TencentVideo.ELSEWHERE) && chosen(r).equals("sdp00100515ow3q,琅琊榜,电视剧,2015,54"),
                "琅琊榜: 同名那一部在央视频 " + chosen(r));
        check(r.platforms.toString().equals("[央视频, 爱奇艺]") && !r.platforms.contains("yangshipin"),
                "平台说「央视频」，不是 yangshipin " + r.platforms);
        check(r.show != null && r.show.title.equals("琅琊榜"), "《琅琊榜之风起长林》不是它的一季，也不是简称 " + chosen(r));

        String mc = fixture("tencent-search-manchang.json");
        r = TencentVideo.pick(mc, "漫长的季节", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc00200fhhxx8d,漫长的季节,电视剧,2023,12"),
                "漫长的季节: 同名的就在腾讯视频，简称轮不到《漫长的季节[卫视版]》 " + chosen(r));
        boolean edition = false;
        for (TencentVideo.Show s : r.others) if (s.title.contains("卫视版")) edition = true;
        check(!edition, "有同名的那一部，[卫视版] 不当选 " + others(r));

        String lz = fixture("tencent-search-lizhi.json");
        r = TencentVideo.pick(lz, "长安的荔枝", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc00200tj1w6ah,长安的荔枝,电影,2025,1"),
                "长安的荔枝: 没说类型就开候选顺序里的第一部（电影）" + chosen(r));
        check(others(r).equals("长安的荔枝(2025)") && "电视剧".equals(r.others.get(0).type),
                "还有一部电视剧 " + others(r));
        check(TencentVideo.also(r.others, 2).equals("。还有《长安的荔枝》（电视剧，2025）"),
                "同名不同类型：「还有」里那部要带上类型 " + TencentVideo.also(r.others, 2));
        r = TencentVideo.pick(lz, "长安的荔枝", "电视剧", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc00200iyue5he,长安的荔枝,电视剧,2025,53") && r.others.isEmpty(),
                "说了是电视剧：53 集那部 " + chosen(r) + " " + others(r));

        // 有同名的那一部就不找简称：一个字都没变
        r = TencentVideo.pick(fixture("tencent-search-kuangbiao.json"), "狂飙", "", 0);
        check(r.is(TencentVideo.ELSEWHERE) && chosen(r).equals("sdp0010051ttxdf,狂飙,电视剧,2023,39"),
                "狂飙: 有同名的那一部，简称一个字都没变 " + chosen(r));
        r = TencentVideo.pick(fixture("tencent-search-fanhua.json"), "繁花", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc00200whsp9r6,繁花,电视剧,2023,36") && r.others.isEmpty(),
                "繁花: 也没变 " + chosen(r) + " " + others(r));
        r = TencentVideo.pick(fixture("tencent-search-none.json"), "不存在的片", "", 0);
        check(r.is(TencentVideo.NONE) && r.show == null, "什么都没有还是什么都没有 " + r.status);
    }

    /**
     * watch-quick（2026-11-11）：「我想看 X」由腾讯视频自己的搜索说了算，不由模型猜。
     * 名字里的标点不算（用户说「你的名字」，腾讯视频写《你的名字。》）；没上映的（status 1）不当成能看的；
     * 同名的电影和好几季剧摆在一起时选剧；只有搜索说得死死的（{@link TencentVideo#sure}）才不用问模型。
     */
    static void tencentWatch() throws Exception {
        String F = TencentVideo.FOUND, E = TencentVideo.ELSEWHERE, N = TencentVideo.NONE;
        row("biewu", "别无选择", F, "exact", "3z4n09p1pzelftp,别无选择,电视剧,2000,31", false, true);
        row("nidemingzi", "你的名字", F, "exact", "gz9q7kd8wviwkxv,你的名字。,电影,2016,2", false, true);
        row("santidark", "三体黑暗森林", F, "exact", "mzc00200gq865do,三体·黑暗森林,电视剧,2026,2", true, false);
        row("santidark", "三体", F, "exact", "mzc002007knmh3g,三体,电视剧,2023,0", false, false);
        row("xiju", "喜剧", F, "exact", "mzc00200vck5hd9,喜剧,电视剧,2026,1", true, false);
        row("mao", "猫", F, "exact", "j5m1vji5qpnqtm6,猫,电影,1998,1", false, false);
        row("zhoujielun", "周杰伦", F, "exact", "mzc003cff73nug9,周杰伦,音乐,0,2", false, false);
        row("heishenhua", "黑神话悟空", F, "exact", "mzc003tyhff5elp,黑神话悟空,游戏,0,3", false, false);
        row("haimian", "海绵宝宝", F, "season", "mzc00200enuagj3,海绵宝宝 第15季,少儿,2024,13", false, true);
        row("xiaozhu", "小猪佩奇", F, "season", "mzc00200zg0xxlt,小猪佩奇 第12季,少儿,2026,26", false, true);
        row("dongwu", "动物世界", F, "exact", "6983f15b7g5xch7,动物世界,电影,2018,1", false, true);
        row("tuokouxiu", "脱口秀大会", F, "season", "mzc0020016b6v6q,脱口秀大会 第5季,综艺,2022,35", false, true);
        row("xinwen", "新闻联播", E, "exact", "sdp001wk0ar4gaq,新闻联播,新闻,0,500", false, false);
        row("zhaopian", "照片", E, "exact", "sdp00100514z3dg,照片,纪录片,1968,0", false, false);
        row("fanhua", "繁花", F, "exact", "mzc00200whsp9r6,繁花,电视剧,2023,36", false, false);
        row("qingyunian", "庆余年第二季", F, "exact", "mzc002002kqssyu,庆余年第二季,电视剧,2024,37", false, true);
        row("manchang", "漫长的季节", F, "exact", "mzc00200fhhxx8d,漫长的季节,电视剧,2023,12", false, true);
        row("zhenhuan", "甄嬛传", E, "alias", "sdp001kemwgrsyo,后宫甄嬛传,电视剧,2011,76", false, false);
        row("kuangbiao", "狂飙", E, "exact", "sdp0010051ttxdf,狂飙,电视剧,2023,39", false, false);
        row("none", "不存在的片", N, "", "-", false, false);

        // 海绵宝宝：选的是第 15 季，那部电影排到「还有」的第一部
        TencentVideo.Result hm = TencentVideo.pick(fixture("tencent-search-haimian.json"), "海绵宝宝", "", 0);
        TencentVideo.Show film = hm.others.isEmpty() ? null : hm.others.get(0);
        check(film != null && film.cid.equals("kkpdn0lz6drhxz8") && film.title.equals("海绵宝宝")
                && film.type.equals("电影") && film.year == 2015,
                "海绵宝宝: 「还有」的第一部是那部电影 " + (film == null ? "-" : film.cid + "," + film.title));
        // 标点不算在名字里
        check(TencentVideo.plain("你的名字。").equals(TencentVideo.plain("你的名字")),
                "《你的名字。》就是「你的名字」 " + TencentVideo.plain("你的名字。"));
        check(TencentVideo.plain("三体·黑暗森林").equals("三体黑暗森林"),
                "《三体·黑暗森林》就是「三体黑暗森林」 " + TencentVideo.plain("三体·黑暗森林"));
        // 拿不准就不动手：null、什么都没有、两个字（除非说了是电视剧）
        TencentVideo.Result none = TencentVideo.pick(fixture("tencent-search-none.json"), "不存在的片", "", 0);
        check(!TencentVideo.sure(null, "别无选择", false), "sure(null) 是 false");
        check(!TencentVideo.sure(none, "不存在的片", true), "sure（什么都没有）是 false");
        TencentVideo.Result fh = TencentVideo.pick(fixture("tencent-search-fanhua.json"), "繁花", "", 0);
        check(!TencentVideo.sure(fh, "繁花", false), "「繁花」两个字、没说类型：交给模型");
        check(TencentVideo.sure(fh, "繁花", true), "说了是电视剧：两个字也够");
    }

    /**
     * watch-heard（2026-11-11）：小爱按声音写名字——「狂彪」是狂飙，「琅琊帮」是琅琊榜，「凡人修仙转」是凡人修仙传，
     * 「甄环传」是甄嬛传；数字两边都写——「流浪地球二」是《流浪地球2》，「庆余年2」是《庆余年第二季》。
     * 搜索的回复 0.1–1.1 MB，缓存以前先切成 pick 读得动的那 15 KB。
     */
    static void tencentHeard() throws Exception {
        String F = TencentVideo.FOUND, E = TencentVideo.ELSEWHERE, N = TencentVideo.NONE;
        heardRow("heard-kuangbiao", "狂彪", E, "heard", "sdp0010051ttxdf,狂飙,电视剧,2023,39", "爱奇艺", false);
        heardRow("heard-langya", "琅琊帮", E, "heard", "sdp00100515ow3q,琅琊榜,电视剧,2015,54", "央视频, 爱奇艺", false);
        heardRow("heard-fanren", "凡人修仙转", F, "heard", "mzc003onbddtsyr,凡人修仙传,动漫,0,127", "", true);
        heardRow("heard-liulang2", "流浪地球二", F, "exact", "mzc002008v8rka3,流浪地球2,电影,2023,1", "", true);
        heardRow("heard-qingyunian2", "庆余年2", F, "season", "mzc002002kqssyu,庆余年第二季,电视剧,2024,37", "", true);
        heardRow("heard-zhenhuan", "甄环传", E, "alias", "sdp001kemwgrsyo,后宫甄嬛传,电视剧,2011,76", "乐视视频, 优酷", false);
        heardRow("heard-none", "别无选者", N, "", "-", "", false);
        heardRow("santidark", "三体二", N, "", "-", "", false);
        // 原来就有的那几部一个字没变（写的名字对得上，走的还是 exact / season）
        heardRow("qingyunian", "庆余年2", F, "season", "mzc002002kqssyu,庆余年第二季,电视剧,2024,37", "", true);
        heardRow("liulang2", "流浪地球2", F, "exact", "mzc002008v8rka3,流浪地球2,电影,2023,1", "", true);
        heardRow("kuangbiao", "狂飙", E, "exact", "sdp0010051ttxdf,狂飙,电视剧,2023,39", "爱奇艺", false);

        // 说了是什么类型：还是找同音的那一部
        TencentVideo.Result r = TencentVideo.pick(fixture("tencent-search-heard-kuangbiao.json"), "狂彪", "电视剧", 0);
        check(r.is(E) && "heard".equals(r.how) && chosen(r).equals("sdp0010051ttxdf,狂飙,电视剧,2023,39")
                && r.platforms.toString().equals("[爱奇艺]"), "「狂彪」说了是电视剧：还是那一部 " + chosen(r) + " " + r.platforms);
        r = TencentVideo.pick(fixture("tencent-search-heard-fanren.json"), "凡人修仙转", "电视剧", 0);
        check(r.is(E) && "heard".equals(r.how) && chosen(r).equals("sdp001000aabhuk,凡人修仙传,电视剧,2025,30")
                && r.platforms.toString().equals("[优酷]"), "「凡人修仙转」说了是电视剧：在优酷的那部 " + chosen(r) + " " + r.platforms);

        // 只是名字里某几个字同音的不算：「狂彪传」不是《狂飙》
        r = TencentVideo.pick(fixture("tencent-search-heard-kuangbiao.json"), "狂彪传", "", 0);
        check(r.is(N) && r.show == null, "「狂彪传」只有一半同音，不当《狂飙》 " + r.status + " " + chosen(r));

        slim();
    }

    /** 切小的回复：pick 的结论一个字都不差，切两次还是同一份，不是搜索回复的东西原样返回，多出来的字段丢得掉。 */
    private static void slim() throws Exception {
        java.util.List<String> files = new java.util.ArrayList<>();
        java.nio.file.DirectoryStream<java.nio.file.Path> dir =
                Files.newDirectoryStream(Paths.get("tests/agent/fixtures"), "tencent-search-*.json");
        for (java.nio.file.Path p : dir) files.add(p.getFileName().toString());
        dir.close();
        java.util.Collections.sort(files);
        check(files.size() >= 25, "把那些搜索的回复都拿来试：" + files.size() + " 个");
        int tried = 0;
        for (String f : files) {
            String raw = fixture(f), cut = TencentVideo.slim(raw);
            check(cut.equals(TencentVideo.slim(cut)), f + ": 切两次还是同一份");
            for (String asked : titlesIn(raw)) {
                tried++;
                TencentVideo.Result a = TencentVideo.pick(raw, asked, "", 0);
                TencentVideo.Result b = TencentVideo.pick(cut, asked, "", 0);
                check(a.status.equals(b.status) && a.how.equals(b.how) && chosen(a).equals(chosen(b))
                        && others(a).equals(others(b)) && a.platforms.toString().equals(b.platforms.toString())
                        && a.note.equals(b.note),
                        f + "「" + asked + "」切小以后结论一样：" + a.status + "/" + a.how + "/" + chosen(a) + "/" + others(a)
                        + "（切小后 " + b.status + "/" + b.how + "/" + chosen(b) + "/" + others(b) + "）");
            }
        }
        check(tried >= 60, "每个回复试了它自己前三个片名，一共 " + tried + " 次");

        String bad = "{\"ret\":20607}";
        check(TencentVideo.slim(bad).equals(bad), "不是搜索的回复（没有 data）原样返回 " + TencentVideo.slim(bad));
        String junk = "这不是 JSON";
        check(TencentVideo.slim(junk).equals(junk), "根本不是 JSON 的也原样返回 " + TencentVideo.slim(junk));

        // 一条 videoInfo 里塞 200000 个字（真回复里是简介、封面地址、预告）：切小以后只剩 pick 读得动的那点
        String raw = fixture("tencent-search-kuangbiao.json");
        org.json.JSONObject padded = new org.json.JSONObject(raw);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) big.append("0123456789");
        padded.getJSONObject("data").getJSONObject("normalList").getJSONArray("itemList")
                .getJSONObject(0).getJSONObject("videoInfo").put("descrip", big.toString());
        String fat = padded.toString(), cut = TencentVideo.slim(fat);
        check(big.length() == 200000 && fat.length() > 200000, "塞进去 200000 个字 " + fat.length());
        check(cut.length() < 40000, "切小以后不到 40000 个字 " + cut.length());
        TencentVideo.Result a = TencentVideo.pick(raw, "狂飙", "", 0), b = TencentVideo.pick(cut, "狂飙", "", 0);
        check(a.status.equals(b.status) && a.how.equals(b.how) && chosen(a).equals(chosen(b)),
                "塞了那么大一个字段，切小以后还是那一部 " + chosen(b));
    }

    /** 一个回复里前三个片名（按候选顺序：MainNeed → normalList → 其它盒子，只取 dataType 2 的）。 */
    private static List<String> titlesIn(String json) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        org.json.JSONObject data = new org.json.JSONObject(json).optJSONObject("data");
        org.json.JSONArray boxes = data == null ? null : data.optJSONArray("areaBoxList");
        List<org.json.JSONArray> lists = new java.util.ArrayList<>();
        for (int i = 0; boxes != null && i < boxes.length(); i++) {
            org.json.JSONObject b = boxes.optJSONObject(i);
            if (b != null && "MainNeed".equals(b.optString("boxId"))) lists.add(b.optJSONArray("itemList"));
        }
        org.json.JSONObject normal = data == null ? null : data.optJSONObject("normalList");
        lists.add(normal == null ? null : normal.optJSONArray("itemList"));
        for (int i = 0; boxes != null && i < boxes.length(); i++) {
            org.json.JSONObject b = boxes.optJSONObject(i);
            if (b != null && !"MainNeed".equals(b.optString("boxId"))) lists.add(b.optJSONArray("itemList"));
        }
        for (org.json.JSONArray items : lists) {
            for (int i = 0; items != null && i < items.length() && out.size() < 3; i++) {
                org.json.JSONObject item = items.optJSONObject(i);
                org.json.JSONObject doc = item == null ? null : item.optJSONObject("doc");
                if (doc == null || doc.optInt("dataType") != 2) continue;
                org.json.JSONObject v = item.optJSONObject("videoInfo");
                String t = v == null ? "" : v.optString("title", "").replaceAll("<[^>]*>", "").trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    /** 一行期望：状态、怎么对上的、选中的是哪一部、在哪些平台（腾讯视频里放的是空串）、能不能不问模型。 */
    private static void heardRow(String file, String asked, String status, String how, String want,
            String platforms, boolean sure) throws Exception {
        TencentVideo.Result r = TencentVideo.pick(fixture("tencent-search-" + file + ".json"), asked, "", 0);
        String at = "「" + asked + "」（" + file + "）";
        check(status.equals(r.status), at + ": " + status + "（得到 " + r.status + "）");
        check(how.equals(r.how), at + ": 怎么对上的 " + how + "（得到 " + r.how + "）");
        check(want.equals(chosen(r)), at + ": " + want + "（得到 " + chosen(r) + "）");
        check(("[" + platforms + "]").equals(r.platforms.toString()),
                at + ": 平台 " + platforms + "（得到 " + r.platforms + "）");
        boolean isSure = TencentVideo.sure(r, asked, false);
        check(sure == isSure, at + ": 能不能不问模型 " + sure + "（得到 " + isSure + "）");
    }

    /** 一行期望：状态、怎么对上的、选中的是哪一部、上没上映、能不能不问模型。 */
    private static void row(String file, String asked, String status, String how, String want,
            boolean upcoming, boolean sure) throws Exception {
        TencentVideo.Result r = TencentVideo.pick(fixture("tencent-search-" + file + ".json"), asked, "", 0);
        String at = "「" + asked + "」（" + file + "）";
        check(status.equals(r.status), at + ": " + status + "（得到 " + r.status + "）");
        check(how.equals(r.how), at + ": 怎么对上的 " + how + "（得到 " + r.how + "）");
        check(want.equals(chosen(r)), at + ": " + want + "（得到 " + chosen(r) + "）");
        check(r.show == null ? !upcoming : r.show.upcoming == upcoming, at + ": 上没上映 " + upcoming
                + "（得到 " + (r.show == null ? "没有片子" : String.valueOf(r.show.upcoming)) + "）");
        boolean isSure = TencentVideo.sure(r, asked, false);
        check(sure == isSure, at + ": 能不能不问模型 " + sure + "（得到 " + isSure + "）");
    }

    private static String chosen(TencentVideo.Result r) {
        TencentVideo.Show s = r.show;
        return s == null ? "-" : s.cid + "," + s.title + "," + s.type + "," + s.year + "," + s.episodes;
    }
    private static String others(TencentVideo.Result r) {
        StringBuilder b = new StringBuilder();
        for (TencentVideo.Show s : r.others) {
            if (b.length() > 0) b.append(' ');
            b.append(s.title).append('(').append(s.year).append(')');
        }
        return b.toString();
    }

    /** 一条搜索结果（一条 item）。 */
    private static String item(String cid, String title, String type, int year, int episodes, String site) {
        return "{\"doc\":{\"dataType\":2,\"id\":\"" + cid + "\"},\"videoInfo\":{\"title\":\"" + title
                + "\",\"typeName\":\"" + type + "\",\"year\":" + year + ",\"playSites\":[{\"enName\":\"" + site
                + "\",\"showName\":\"播放源\",\"totalEpisode\":" + episodes + "}]}}";
    }

    private static String answer(String boxes, String normal) {
        return "{\"data\":{\"errcode\":0,\"areaBoxList\":[" + boxes + "],\"normalList\":{\"itemList\":[" + normal + "]}}}";
    }

    private static String box(String boxId, String... items) {
        StringBuilder b = new StringBuilder("{\"boxId\":\"" + boxId + "\",\"itemList\":[");
        for (int i = 0; i < items.length; i++) { if (i > 0) b.append(','); b.append(items[i]); }
        return b.append("]}").toString();
    }

    static void tencentLinks() throws Exception {
        check(TencentVideo.detailLink("mzc00200whsp9r6").equals("tenvideo2://?action=1&cover_id=mzc00200whsp9r6"),
                "详情页的链接（焦点在小播放窗上）" + TencentVideo.detailLink("mzc00200whsp9r6"));
        check(TencentVideo.playLink("mzc00200whsp9r6").equals("tenvideo2://?action=7&cover_id=mzc00200whsp9r6"),
                "全屏播放的链接" + TencentVideo.playLink("mzc00200whsp9r6"));
        check(TencentVideo.playLink("abcdef").endsWith("cover_id=abcdef"), "六个字的最短编号也认");
        for (String bad : new String[]{"", "abcde", "mzc00200whsp9r6mzc00200whsp9r6abc", "mzc-00200whsp9r6", "中文编号"}) {
            boolean threw = false;
            try { TencentVideo.detailLink(bad); } catch (IllegalArgumentException e) { threw = true; }
            boolean threwPlay = false;
            try { TencentVideo.playLink(bad); } catch (IllegalArgumentException e) { threwPlay = true; }
            check(threw && threwPlay, "编号不对就抛出来，不拼出一个打不开的链接：" + bad);
        }

        org.json.JSONObject body = new org.json.JSONObject(TencentVideo.body("繁花"));
        check("繁花".equals(body.getString("query")) && "25042201".equals(body.getString("version"))
                && body.getInt("pagesize") == 30 && body.getInt("clientType") == 1 && body.getBoolean("isneedQc")
                && body.getInt("pagenum") == 0 && body.getBoolean("isPrefetch")
                && "DE7F930F-307B-4405-B91F-7A0D95402A62".equals(body.getString("uuid"))
                && "{}".equals(body.getJSONObject("extraInfo").getString("sugRelatedIds")),
                "请求体是 JSON，带着片名和它网站自己发的那些字段 " + body);
        String quoted = "他说\"繁花\"&<script>";
        check(quoted.equals(new org.json.JSONObject(TencentVideo.body(quoted)).getString("query")),
                "片名里的引号和尖括号原样带过去（org.json 拼的，不是字符串接的）");

        // 平台的中文名
        String sites = "{\"data\":{\"normalList\":{\"itemList\":[{\"doc\":{\"dataType\":2,\"id\":\"aaa111\"},\"videoInfo\":"
                + "{\"title\":\"测试片\",\"typeName\":\"电影\",\"year\":2020,\"playSites\":[{\"enName\":\"qiyi\"},{\"enName\":\"youku\"},"
                + "{\"enName\":\"bilibili\"},{\"enName\":\"mgtv\"},{\"enName\":\"sohu\"},{\"enName\":\"cctv\"},{\"enName\":\"cntv\"},"
                + "{\"enName\":\"migu\"},{\"enName\":\"pptv\"},{\"enName\":\"xyzzy\"}]}}]}}}";
        TencentVideo.Result r = TencentVideo.pick(sites, "测试片", "", 0);
        check(r.is(TencentVideo.ELSEWHERE)
                && r.platforms.toString().equals("[爱奇艺, 优酷, 哔哩哔哩, 芒果TV, 搜狐视频, 央视, 咪咕视频, PP视频, xyzzy]"),
                "平台说中文名，不认识的照原样说，同一个平台只说一次 " + r.platforms);
        check("央视".equals(TencentVideo.platform("cntv")) && "央视".equals(TencentVideo.platform("cctv")),
                "cctv 和 cntv 都是央视");
        check("乐视视频".equals(TencentVideo.platform("letv")) && "央视频".equals(TencentVideo.platform("yangshipin"))
                && "PP视频".equals(TencentVideo.platform("pptv")) && "华数TV".equals(TencentVideo.platform("wasu"))
                && "1905电影网".equals(TencentVideo.platform("m1905")) && "1905电影网".equals(TencentVideo.platform("1905"))
                && "芒果TV".equals(TencentVideo.platform("hunantv")) && "别的网站".equals(TencentVideo.platform("sdp")),
                "甄嬛传在的乐视视频、琅琊榜在的央视频都说得出中文名，sdp 是「别的网站」");
        check("xyzzy".equals(TencentVideo.platform("xyzzy")) && "".equals(TencentVideo.platform(null)),
                "真的不认识的 enName 原样说，没有 enName 就是空串");

        // 候选的顺序：MainNeed → normalList → 其它盒子
        String order = answer(box("3505", item("ccc333", "测试片", "电视剧", 2022, 10, "qq")) + ","
                + box("MainNeed", item("aaa111", "测试片", "电视剧", 2020, 10, "qq")),
                item("bbb222", "测试片", "电视剧", 2021, 10, "qq"));
        r = TencentVideo.pick(order, "测试片", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("aaa111,测试片,电视剧,2020,10")
                && others(r).equals("测试片(2021) 测试片(2022)"),
                "MainNeed 的排在 normalList 和其它盒子前面 " + chosen(r) + " " + others(r));

        // 同一个封面编号只算一次
        String twice = answer(box("3505", item("aaa111", "测试片", "电视剧", 2021, 10, "qq")),
                item("aaa111", "测试片", "电视剧", 2020, 10, "qq"));
        r = TencentVideo.pick(twice, "测试片", "", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("aaa111,测试片,电视剧,2020,10") && r.others.isEmpty(),
                "同一个编号在两个盒子里只算一部 " + chosen(r) + " " + others(r));

        // 短剧和原创不算片子，除非用户点名要
        String drama = answer("", item("ddd444", "测试片", "短剧", 2020, 10, "qq") + ","
                + item("eee555", "测试片", "原创", 2021, 10, "qq") + "," + item("fff666", "别的片", "电视剧", 2020, 10, "qq"));
        check(TencentVideo.pick(drama, "测试片", "", 0).is(TencentVideo.NONE), "短剧和原创不当成能看的片子");
        r = TencentVideo.pick(drama, "测试片", "短剧", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("ddd444,测试片,短剧,2020,10"), "用户点名要短剧才算 " + chosen(r));

        // 过滤完一部都不剩就把过滤放下，并说明
        r = TencentVideo.pick(fixture("tencent-search-fanhua.json"), "繁花", "纪录片", 0);
        check(r.is(TencentVideo.FOUND) && chosen(r).equals("mzc00200whsp9r6,繁花,电视剧,2023,36")
                && r.note.contains("纪录片") && r.note.contains("繁花"),
                "没有纪录片《繁花》：过滤放下，note 里说明 " + r.note);

        // 搜不到时最接近的名字
        r = TencentVideo.pick(fixture("tencent-search-santi.json"), "三体2", "", 0);
        check(r.is(TencentVideo.NONE) && TencentVideo.near(r.others, 3).equals("（有《三体·黑暗森林》《三体》《三体·周年纪念版》）"),
                "没搜到《三体2》：说搜索里最接近的几个名字 " + TencentVideo.near(r.others, 3));
        check(TencentVideo.near(new java.util.ArrayList<TencentVideo.Show>(), 3).isEmpty()
                && TencentVideo.also(new java.util.ArrayList<TencentVideo.Show>(), 2).isEmpty(), "没有就不加那半句");

        // 说话用的片段
        TencentVideo.Show fh = TencentVideo.pick(fixture("tencent-search-fanhua.json"), "繁花", "", 0).show;
        check(TencentVideo.tag(fh).equals("（电视剧，2023，共 36 集）") && TencentVideo.brief(fh).equals("（电视剧，2023）"),
                "《繁花》的类型、年份、集数 " + TencentVideo.tag(fh));
        TencentVideo.Show fr = TencentVideo.pick(fixture("tencent-search-fanren.json"), "凡人修仙传", "", 0).show;
        check(TencentVideo.tag(fr).equals("（动漫，共 127 集）"), "没有年份就不写年份 " + TencentVideo.tag(fr));
        TencentVideo.Result pn = TencentVideo.pick(fixture("tencent-search-paonan.json"), "奔跑吧", "", 0);
        check(TencentVideo.also(pn.others, 2).equals("。还有《奔跑吧》（2017）、《奔跑吧·大湾区篇》（2026）"),
                "「还有」最多说两部 " + TencentVideo.also(pn.others, 2));
        check(TencentVideo.norm("《繁 花》ＡＢＣ12").equals(TencentVideo.norm("繁花abc12")),
                "比名字：书名号、空格、全角、大小写都不算 " + TencentVideo.norm("《繁 花》ＡＢＣ12"));

        // 搜索的回复缓存十分钟：同样的问题不再问一次
        final int[] calls = {0};
        Feeds.Post raw = (url, json, timeoutMs) -> { calls[0]++; return "第" + calls[0] + "次"; };
        final long[] now = {1000};
        Feeds.Post cached = Feeds.cachedPost(raw, 500, () -> now[0]);
        check(cached.post("u", "b", 100).equals("第1次") && calls[0] == 1, "第一次真的发出去");
        check(cached.post("u", "b", 100).equals("第1次") && calls[0] == 1, "同样的地址和请求体：用缓存里那份");
        check(cached.post("u", "b2", 100).equals("第2次") && calls[0] == 2, "请求体不一样就再发一次");
        now[0] += 600;
        check(cached.post("u", "b", 100).equals("第3次") && calls[0] == 3, "过了 ttl 就再发一次");
        check(Feeds.httpsPost() != null, "httpsPost 能用");
    }

    /** tencent-episode（2026-11-11）：「我想看别无选择第3集」——选集列表给出这一集的编号，链接直接放这一集。 */
    static void tencentEpisodes() throws Exception {
        // 请求体：翻回来还是那个 cid 和那一份 page_context
        org.json.JSONObject body = new org.json.JSONObject(TencentVideo.episodesBody("3z4n09p1pzelftp", "ctx=1"));
        org.json.JSONObject params = body.getJSONObject("page_params");
        check("3z4n09p1pzelftp".equals(params.getString("cid")) && "ctx=1".equals(params.getString("page_context"))
                        && "vsite_episode_list".equals(params.getString("page_id"))
                        && "web_vsite".equals(params.getString("req_from")) && "1".equals(params.getString("id_type"))
                        && "detail_operation".equals(params.getString("page_type"))
                        && body.getInt("has_cache") == 1,
                "选集列表的请求体：cid、page_context、page_id 都带着 " + body);
        check("".equals(new org.json.JSONObject(TencentVideo.episodesBody("3z4n09p1pzelftp", ""))
                .getJSONObject("page_params").getString("page_context")), "第一页不带 page_context");

        // 链接：这一集的编号拼在封面编号后面
        check(TencentVideo.playLink("3z4n09p1pzelftp", "p0027xysx65")
                        .equals("tenvideo2://?action=7&cover_id=3z4n09p1pzelftp&video_id=p0027xysx65"),
                "第 3 集的链接 " + TencentVideo.playLink("3z4n09p1pzelftp", "p0027xysx65"));
        for (String bad : new String[]{"", "abcde", "p0027xysx65p0027xysx65p002", "p0027-xysx65", "第3集"}) {
            boolean threw = false;
            try { TencentVideo.playLink("3z4n09p1pzelftp", bad); } catch (IllegalArgumentException e) { threw = true; }
            check(threw, "这一集的编号不对就抛出来，不拼出一个打不开的链接：" + bad);
        }

        // 《繁花》：30 集加 6 条花絮都在一页上，没有 tabs
        TencentVideo.Episodes fh = TencentVideo.episodes(fixture("tencent-episodes-fanhua.json"));
        check(fh.list.size() == 36, "繁花：36 条（30 集 + 6 条花絮）" + fh.list.size());
        check(fh.last() == 30, "繁花：编号最大的是第 30 集 " + fh.last());
        check(fh.tabs.isEmpty(), "繁花：一页装得下，没有 tabs");
        check("会员看全集".equals(fh.subTitle), "繁花：顶上写的那一句 " + fh.subTitle);
        TencentVideo.Episode e = fh.find(1);
        check(e != null && "a0047l69jnp".equals(e.vid), "繁花第 1 集 " + e);
        check(e != null && !e.vip, "繁花第 1 集不要会员");
        check(e != null && "繁花 第01集".equals(e.playTitle), "繁花第 1 集的 play_title " + (e == null ? "" : e.playTitle));
        check(e != null && "王家卫执导！顶级阵容演绎沪上风云".equals(e.about), "繁花第 1 集的一句话简介 " + (e == null ? "" : e.about));
        check(e != null && e.number == 1 && "1".equals(e.title) && e.seconds == 2730 && !e.trailer, "繁花第 1 集的编号和时长 " + e);
        e = fh.find(3);
        check(e != null && "w0047o2kopn".equals(e.vid) && e.vip, "繁花第 3 集要会员 " + e);
        e = fh.find(30);
        check(e != null && "j0047aj1c1n".equals(e.vid) && e.vip && e.seconds == 3171, "繁花第 30 集 " + e);
        check(fh.find(31) == null, "繁花没有第 31 集");
        check(fh.contextsFor(31).isEmpty(), "繁花没有下一页可问 " + fh.contextsFor(31).size());

        // 《别无选择》：31 集分两页（1–30、31–31）
        TencentVideo.Episodes bw = TencentVideo.episodes(fixture("tencent-episodes-biewu.json"));
        check(bw.list.size() == 30, "别无选择：这一页 30 集 " + bw.list.size());
        check(bw.tabs.size() == 2 && bw.tabs.get(0).begin == 1 && bw.tabs.get(0).end == 30 && bw.tabs.get(0).selected
                        && bw.tabs.get(1).begin == 31 && bw.tabs.get(1).end == 31 && !bw.tabs.get(1).selected
                        && !bw.tabs.get(1).context.isEmpty(),
                "别无选择：tabs 是 1–30（当前这页）和 31–31 " + bw.tabs);
        check("全31集".equals(bw.subTitle), "别无选择：顶上写着全 31 集 " + bw.subTitle);
        e = bw.find(3);
        check(e != null && "p0027xysx65".equals(e.vid) && !e.vip, "别无选择第 3 集 " + e);
        check(bw.find(31) == null, "第 31 集不在这页上");
        check(bw.contextsFor(31).size() == 1 && bw.contextsFor(31).get(0).equals(bw.tabs.get(1).context),
                "第 31 集：问 31–31 那一页 " + bw.contextsFor(31).size());

        // 《庆余年第二季》：1–30 和 31–36 两页，第二页上还挂着一条彩蛋
        TencentVideo.Episodes qy = TencentVideo.episodes(fixture("tencent-episodes-qingyunian2.json"));
        check(qy.list.size() == 30 && qy.last() == 30, "庆余年第二季：这一页 1–30 " + qy.list.size());
        e = qy.find(30);
        check(e != null && "y4100r10687".equals(e.vid) && e.vip, "庆余年第二季第 30 集 " + e);
        check(qy.find(31) == null && qy.contextsFor(31).size() == 1
                && qy.contextsFor(31).get(0).equals(qy.tabs.get(1).context), "第 31 集在 31–36 那一页");
        TencentVideo.Episodes qy2 = TencentVideo.episodes(fixture("tencent-episodes-qingyunian2-p2.json"));
        check(qy2.list.size() == 7, "第二页：31–36 加一条彩蛋 " + qy2.list.size());
        e = qy2.find(31);
        check(e != null && "u4100vc3n8u".equals(e.vid) && e.vip, "庆余年第二季第 31 集 " + e);
        check(qy2.find(36) != null && "v4100ydmxec".equals(qy2.find(36).vid), "第 36 集");
        check(qy2.find(37) == null, "彩蛋不是第 37 集");

        // 一部电影：一条，没有编号
        TencentVideo.Episodes ll = TencentVideo.episodes(fixture("tencent-episodes-liulang2.json"));
        check(ll.list.size() == 1 && ll.last() == 0, "一部电影：一条，没有编号 " + ll.list.size());
        check(ll.find(1) == null, "一部电影没有第 1 集");

        // 《奔跑吧》：名字是「第1期：…」「第1期加更：…」「第1期特别加更：…」，要的是正片那一期
        TencentVideo.Episodes pn = TencentVideo.episodes(fixture("tencent-episodes-paonan.json"));
        check(pn.list.size() == 30 && pn.last() == 0, "奔跑吧：一页 30 条，名字不是数字 " + pn.list.size());
        e = pn.find(1);
        check(e != null && "p41029oi70w".equals(e.vid), "第 1 期：正片，不是加更 " + e);
        check(e != null && e.title.startsWith("第1期："), "第 1 期的名字 " + (e == null ? "" : e.title));
        check(pn.find(10) != null && "j4102w8onm4".equals(pn.find(10).vid), "第 10 期");
        check(pn.find(11) == null, "第 11 期不在这页上");
        check(pn.contextsFor(11).size() == 1 && pn.contextsFor(11).get(0).equals(pn.tabs.get(1).context),
                "第 11 期：问另一页（1–30 就是当前这页）" + pn.contextsFor(11).size());
        TencentVideo.Episodes pn2 = TencentVideo.episodes(fixture("tencent-episodes-paonan-p2.json"));
        check(pn2.list.size() == 6, "第二页 6 条 " + pn2.list.size());
        check(pn2.find(11) != null && "n4102q9atln".equals(pn2.find(11).vid), "第 11 期");
        check(pn2.find(12) != null && "l4102bn6gub".equals(pn2.find(12).vid), "第 12 期");

        // 《三体》：30 集加 10 条彩蛋都在一页上
        TencentVideo.Episodes st = TencentVideo.episodes(fixture("tencent-episodes-santi.json"));
        check(st.list.size() == 40 && st.last() == 30, "三体：30 集加 10 条彩蛋 " + st.list.size());
        e = st.find(30);
        check(e != null && "o00450p4b98".equals(e.vid) && e.vip, "三体第 30 集 " + e);
        check(st.find(31) == null, "彩蛋不是第 31 集");

        // 678 集的短剧：名字是【…】第31集，23 页
        TencentVideo.Episodes lj = TencentVideo.episodes(fixture("tencent-episodes-lingjie.json"));
        check(lj.list.size() == 30 && lj.last() == 0, "灵界篇：名字是【…】第30集 " + lj.list.size());
        check(lj.find(30) != null && "c3548lseum8".equals(lj.find(30).vid), "第 30 集");
        check(lj.find(31) == null, "第 31 集不在这页上");
        List<String> ctx = lj.contextsFor(31);
        check(ctx.size() == 4 && lj.tabs.get(1).begin == 31 && ctx.get(0).equals(lj.tabs.get(1).context),
                "第 31 集：先问 31–60 那一页，一共最多 4 个 " + ctx.size());
        TencentVideo.Episodes lj2 = TencentVideo.episodes(fixture("tencent-episodes-lingjie-p2.json"));
        check(lj2.list.size() == 30, "第二页 30 条 " + lj2.list.size());
        check(lj2.find(31) != null && "p3548o0iinp".equals(lj2.find(31).vid), "第 31 集");
        check(lj2.find(60) != null && "a3549cabzcz".equals(lj2.find(60).vid), "第 60 集");

        // 一条预告不算一集；汉字和名字里的空格都认；筛选器（item_type 28）不是一集
        String odd = "{\"data\":{\"module_list_datas\":[{\"module_datas\":[{\"module_params\":{\"tabs\":\"\","
                + "\"sub_title\":\"全3集\"},\"item_data_lists\":{\"item_datas\":["
                + "{\"item_type\":\"1\",\"item_params\":{\"title\":\"1\",\"vid\":\"aaa111\",\"is_trailer\":\"1\"}},"
                + "{\"item_type\":\"1\",\"item_params\":{\"title\":\"第二集\",\"vid\":\"bbb222\"}},"
                + "{\"item_type\":\"1\",\"item_params\":{\"title\":\"第 3 集\",\"vid\":\"ccc333\"}},"
                + "{\"item_type\":\"28\",\"item_params\":{\"title\":\"普通话版\"}}]}}]}]}}";
        TencentVideo.Episodes oe = TencentVideo.episodes(odd);
        check(oe.list.size() == 3, "item_type 28 的筛选器不是一集 " + oe.list.size());
        check(oe.find(1) == null, "预告不是第 1 集");
        check(oe.find(2) != null && "bbb222".equals(oe.find(2).vid), "第二集：汉字写的也认");
        check(oe.find(3) != null && "ccc333".equals(oe.find(3).vid), "第 3 集：名字里的空格也认");
        check(oe.last() == 1 && "全3集".equals(oe.subTitle), "编号只认名字就是数字的那一条 " + oe.last());

        // 报错的回复是空的一页；不是选集列表的东西原样返回
        TencentVideo.Episodes none = TencentVideo.episodes("{\"ret\":1}");
        check(none.list.isEmpty() && none.tabs.isEmpty() && none.subTitle.isEmpty() && none.find(1) == null
                && none.contextsFor(1).isEmpty() && none.last() == 0, "报错的回复是空的一页");
        check(TencentVideo.slimEpisodes("{\"ret\":1}").equals("{\"ret\":1}")
                && TencentVideo.slimEpisodes("不是 JSON").equals("不是 JSON"), "不是选集列表的回复原样返回");

        // 切小以后读出来的还是同一页
        String[] files = {"tencent-episodes-fanhua.json", "tencent-episodes-biewu.json",
                "tencent-episodes-qingyunian2.json", "tencent-episodes-qingyunian2-p2.json",
                "tencent-episodes-liulang2.json", "tencent-episodes-paonan.json", "tencent-episodes-paonan-p2.json",
                "tencent-episodes-santi.json", "tencent-episodes-lingjie.json", "tencent-episodes-lingjie-p2.json"};
        for (String file : files) {
            String raw = fixture(file);
            TencentVideo.Episodes a = TencentVideo.episodes(raw);
            TencentVideo.Episodes b = TencentVideo.episodes(TencentVideo.slimEpisodes(raw));
            boolean same = a.list.size() == b.list.size() && a.tabs.size() == b.tabs.size()
                    && a.subTitle.equals(b.subTitle) && a.last() == b.last() && a.contextsFor(2).equals(b.contextsFor(2));
            for (int n = 1; n <= 40 && same; n++) {
                TencentVideo.Episode x = a.find(n), y = b.find(n);
                same = (x == null) == (y == null) && (x == null || (x.vid.equals(y.vid) && x.number == y.number
                        && x.seconds == y.seconds && x.vip == y.vip && x.trailer == y.trailer
                        && x.title.equals(y.title) && x.playTitle.equals(y.playTitle) && x.about.equals(y.about)));
            }
            check(same, "切小以后读出来的还是同一页：" + file);
        }

        check("三十一".equals(TencentVideo.chinese(31)) && "十".equals(TencentVideo.chinese(10))
                && "二十一".equals(TencentVideo.chinese(21)) && "九".equals(TencentVideo.chinese(9))
                && TencentVideo.chinese(0).isEmpty() && TencentVideo.chinese(100).isEmpty(), "1–99 的汉字写法");
    }
}
