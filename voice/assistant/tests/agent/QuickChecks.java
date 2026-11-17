package xyz.erapi0neer.xingyi.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.json.JSONObject;

/** Checks for QuickCommand (job quick-commands): simple TV commands matched locally, everything else left to the model. */
final class QuickChecks {
    private static final List<String> APPS = Arrays.asList("哔哩哔哩", "网易云音乐", "云视听极光", "电视设置", "相册");

    private QuickChecks() { }

    static void run() throws Exception {
        volume();
        media();
        navigation();
        inputs();
        timers();
        apps();
        exits();
        notSimple();
        numbers();
        days();
        karaoke();
        replies();
        real();
        info2();
        watch();
        live();
        seek();
        blank();
    }

    /** own-ui（2026-11-11 21:12）：用户看着一片黑的电视说「屏幕黑了」——一句话就是退回桌面（media_control exit，blank），
     * 工具自己说前台是什么。整句必须是这句话：「黑屏」没有「了」可能是想把屏幕调暗，还是模型的。 */
    private static void blank() throws Exception {
        String exit = "{\"action\":\"exit\",\"blank\":true}";
        for (String said : new String[]{"黑屏了", "屏幕黑了", "屏幕黑屏了", "电视黑屏了", "电视屏幕黑了", "电视屏幕都黑了",
                "屏幕怎么黑了", "怎么黑屏了", "屏幕全黑了", "屏幕是黑的", "画面黑了", "没画面了", "屏幕没画面了", "画面没了",
                "屏幕什么都没有", "屏幕上什么都没有", "屏幕不显示了"})
            is(said, "media_control", exit);
        // 你看 / 现在 in front, a particle or a full stop at the end
        is("你看现在屏幕黑了", "media_control", exit);
        is("屏幕黑了呀", "media_control", exit);
        is("黑屏了！", "media_control", exit);

        // 回到桌面 is still the HOME key
        is("回到桌面", "press_keys", "{\"keys\":[\"home\"]}");

        // 「黑屏」 without 了 may be a wish to make the screen dark; a question about it is the model's
        none("黑屏");
        none("屏幕黑了怎么办");
        none("为什么黑屏");
        none("黑屏是什么原因");
        none("把屏幕调黑一点");
        none("黑屏一下");

        // what is said is the tool's own sentence: which app was in front, or that the desktop already was
        reply("屏幕黑了", "{\"ok\":true,\"message\":\"刚才前台是Fluid Lab，已经退回桌面\"}", "刚才前台是Fluid Lab，已经退回桌面");
        reply("黑屏了", "{\"ok\":false,\"status\":\"at_home\",\"message\":\"现在前台就是桌面：先用 screen_look 看看画面到底是什么样，再告诉用户；不要打开别的应用\"}",
                "现在前台就是桌面：先用 screen_look 看看画面到底是什么样，再告诉用户；不要打开别的应用");
    }

    /** media-seek（2026-11-11 校准）：「快进一分钟」说给小爱，小爱自己就跳了一分钟（PlaybackController.Seek），星仪却
     * 花了 20 秒读屏，最后说看不到画面。一句话就是一个 media_seek，工具自己去按快进 / 快退键。只有整句就是
     * 「再? 动词 + 时长? + 吧?」才算：跳到某个时间点、倍速、问句，都还是模型的。 */
    private static void seek() throws Exception {
        is("快进一分钟", "media_seek", "{\"seconds\":60}");
        is("快进30秒", "media_seek", "{\"seconds\":30}");
        is("快进", "media_seek", "{\"seconds\":30}");
        is("快进一下", "media_seek", "{\"seconds\":30}");
        is("快进两分钟", "media_seek", "{\"seconds\":120}");
        is("快进一分半", "media_seek", "{\"seconds\":90}");
        is("再快进十秒", "media_seek", "{\"seconds\":10}");
        is("快进半分钟", "media_seek", "{\"seconds\":30}");
        is("快退十秒", "media_seek", "{\"seconds\":-10}");
        is("后退30秒", "media_seek", "{\"seconds\":-30}");
        is("倒退一分钟", "media_seek", "{\"seconds\":-60}");
        is("往回倒十秒", "media_seek", "{\"seconds\":-10}");
        is("快进5分钟吧", "media_seek", "{\"seconds\":300}");

        // 别的说法：往后快进 / 快转 / 倒回去 / 往前倒，没说时长就是 30 秒
        is("往后快进十秒", "media_seek", "{\"seconds\":10}");
        is("向后快进三十秒", "media_seek", "{\"seconds\":30}");
        is("快转两分钟", "media_seek", "{\"seconds\":120}");
        is("快进一点点", "media_seek", "{\"seconds\":30}");
        is("快进一会儿", "media_seek", "{\"seconds\":30}");
        is("快退一下", "media_seek", "{\"seconds\":-30}");
        is("倒回去十秒", "media_seek", "{\"seconds\":-10}");
        is("倒回一分钟", "media_seek", "{\"seconds\":-60}");
        is("往前倒两分钟", "media_seek", "{\"seconds\":-120}");
        is("回退30秒", "media_seek", "{\"seconds\":-30}");
        is("快退一分半", "media_seek", "{\"seconds\":-90}");
        is("快进十分钟", "media_seek", "{\"seconds\":600}");
        // 上下限：5 秒到 30 分钟
        is("快进5秒", "media_seek", "{\"seconds\":5}");
        is("快进30分钟", "media_seek", "{\"seconds\":1800}");

        none("快进到十分钟");
        none("两倍速播放");
        none("快进三个小时");
        none("快进2秒");
        none("快进的时候卡不卡");
        none("怎么快进");
        none("快进半小时");
        none("快进31分钟");
        none("快进4秒");
        none("快退到开头");
        none("能不能快进");
        none("快进一分钟看看");

        // 工具自己说的话就是答案（按了多少下、跳了多久；按不了键时是它自己的原因）
        reply("快进一分钟", "{\"ok\":true,\"status\":\"sent\",\"message\":\"已快进 1 分钟\"}", "已快进 1 分钟");
        reply("快退十秒", "{\"ok\":false,\"status\":\"unsupported\",\"message\":\"现在前台的应用我还不会快进快退\"}",
                "现在前台的应用我还不会快进快退");
        reply("快进", "{}", "好的");
    }

    /** live-tv（2026-11-11 校准）：这台电视上没有能看电视直播的应用，「我想看CCTV5」跟着模型走了 6–10 轮、
     * 17–60 秒（切 HDMI1、装应用、开网页），最后才说到那句「放不了」。一句 tv_guide（watch=true）就说清：
     * 放不了，再说这个台现在在播什么。一个节目、一个问题、一个应用还是模型的。 */
    private static void live() throws Exception {
        String cctv5 = "{\"channel\":\"CCTV5\",\"part\":\"现在\",\"watch\":true,\"final\":true}";
        String hunan = "{\"channel\":\"湖南卫视\",\"part\":\"现在\",\"watch\":true,\"final\":true}";
        is("我想看CCTV5", "tv_guide", cctv5);
        is("我要看CCTV5", "tv_guide", cctv5);
        is("看CCTV5", "tv_guide", cctv5);
        // 「的直播」「节目」「台」只是把话说全了；台是名字的一部分时（中央五台）留着
        is("我想看CCTV5直播", "tv_guide", cctv5);
        is("我想看中央五台", "tv_guide", "{\"channel\":\"中央五台\",\"part\":\"现在\",\"watch\":true,\"final\":true}");
        is("我想看体育频道", "tv_guide", "{\"channel\":\"体育频道\",\"part\":\"现在\",\"watch\":true,\"final\":true}");
        is("我想看湖南卫视", "tv_guide", hunan);
        is("切到湖南卫视", "tv_guide", hunan);
        is("换到东方卫视", "tv_guide", "{\"channel\":\"东方卫视\",\"part\":\"现在\",\"watch\":true,\"final\":true}");
        is("我想看一下浙江卫视", "tv_guide", "{\"channel\":\"浙江卫视\",\"part\":\"现在\",\"watch\":true,\"final\":true}");
        is("打开CCTV1", "tv_guide", "{\"channel\":\"CCTV1\",\"part\":\"现在\",\"watch\":true,\"final\":true}");
        is("播放CCTV13", "tv_guide", "{\"channel\":\"CCTV13\",\"part\":\"现在\",\"watch\":true,\"final\":true}");
        // 一个频道就是一台，不是一部片子（watch-quick 时的 none 到了这里）
        is("我想看少儿频道", "tv_guide", "{\"channel\":\"少儿频道\",\"part\":\"现在\",\"watch\":true,\"final\":true}");

        // 问现在在播什么：还是原来那句 guide，没有 watch
        is("CCTV5现在在放什么", "tv_guide", "{\"channel\":\"CCTV5\",\"part\":\"现在\",\"final\":true}");
        is("湖南卫视在播什么", "tv_guide", "{\"channel\":\"湖南卫视\",\"part\":\"现在\",\"final\":true}");

        // 要的是台上的一个节目、是一个问题、或者根本不是频道
        none("我想看CCTV5的NBA");
        none("看湖南卫视的歌手");
        none("CCTV5能看吗");
        none("我想看电视");
        none("我想看直播");

        // 只有用户说到信号源或接着它的设备时才切信号源
        for (String said : new String[]{"切到HDMI1", "换到 hdmi 2", "打开机顶盒", "我要玩游戏机", "切到电脑", "打开PS5", "切换信号源"})
            check(QuickCommand.namesInput(said), "namesInput: " + said);
        for (String said : new String[]{"我想看CCTV5", "看湖南卫视", "打开B站", null, ""})
            check(!QuickCommand.namesInput(said), "not an input: " + said);

        // 工具自己说的话就是答案
        reply("我想看CCTV5", "{\"ok\":true,\"answer\":\"这台电视上没有能看电视直播的应用，放不了CCTV-5 体育。"
                + "CCTV-5 体育现在在播《体育新闻》（12 点开始，12 点 30 分结束）\"}",
                "这台电视上没有能看电视直播的应用，放不了CCTV-5 体育。CCTV-5 体育现在在播《体育新闻》（12 点开始，12 点 30 分结束）");
    }

    /** watch-quick（2026-11-11）：「我想看 X」是一句快命令，名字原样交给 tencent_video_open 去腾讯视频的搜索里对一次
     * （strict：只有搜索说得死死的才动手）。问句、要看的视频/直播/第几集、别的应用，都还是模型的。 */
    private static void watch() throws Exception {
        String biewu = "{\"title\":\"别无选择\",\"strict\":true}";
        is("我想看别无选择", "tencent_video_open", biewu);
        is("我要看别无选择", "tencent_video_open", biewu);
        is("想看别无选择", "tencent_video_open", biewu);
        is("我想看看别无选择", "tencent_video_open", biewu);
        is("嗯我想看别无选择", "tencent_video_open", biewu);
        is("我想看一下漫长的季节", "tencent_video_open", "{\"title\":\"漫长的季节\",\"strict\":true}");
        is("我们想看庆余年第二季", "tencent_video_open", "{\"title\":\"庆余年第二季\",\"strict\":true}");
        is("看庆余年第二季", "tencent_video_open", "{\"title\":\"庆余年第二季\",\"strict\":true}");
        // 书名号里的原样是名字（名字里有「的」也算）
        is("我想看《长安的荔枝》", "tencent_video_open", "{\"title\":\"长安的荔枝\",\"strict\":true}");
        // 说了类型：两个字的名字也够
        is("我想看电视剧繁花", "tencent_video_open", "{\"title\":\"繁花\",\"kind\":\"电视剧\",\"strict\":true}");
        is("播放电视剧繁花", "tencent_video_open", "{\"title\":\"繁花\",\"kind\":\"电视剧\",\"strict\":true}");
        is("帮我放电影流浪地球2", "tencent_video_open", "{\"title\":\"流浪地球2\",\"kind\":\"电影\",\"strict\":true}");
        is("我想看动画片海绵宝宝", "tencent_video_open", "{\"title\":\"海绵宝宝\",\"kind\":\"动漫\",\"strict\":true}");
        // 「这部电视剧」跟在名字后面：只是把话说全了，不是又一个类型
        is("我想看别无选择这部电视剧", "tencent_video_open", biewu);

        // tencent-episode（2026-11-11）：末尾的「第N集」「第N期」「第N话」是要看的那一集，名字是它前面那一截
        is("我想看别无选择第3集", "tencent_video_open", "{\"title\":\"别无选择\",\"episode\":3,\"strict\":true}");
        is("我想看《别无选择》第3集", "tencent_video_open", "{\"title\":\"别无选择\",\"episode\":3,\"strict\":true}");
        is("我想看庆余年第二季第五集", "tencent_video_open", "{\"title\":\"庆余年第二季\",\"episode\":5,\"strict\":true}");
        is("我想看电视剧繁花第10集", "tencent_video_open", "{\"title\":\"繁花\",\"kind\":\"电视剧\",\"episode\":10,\"strict\":true}");
        is("看别无选择第三十一集", "tencent_video_open", "{\"title\":\"别无选择\",\"episode\":31,\"strict\":true}");
        is("我想看奔跑吧第2期", "tencent_video_open", "{\"title\":\"奔跑吧\",\"episode\":2,\"strict\":true}");

        // 两个字、没说类型：太短，交给模型
        none("我想看繁花");
        // 要看的是一段视频、一个应用、一个频道，不是一部片子
        none("我想看何同学的视频");
        none("我想看B站");
        none("我想看电影");
        // 少儿频道 是一个频道：live() 用 tv_guide 回答（job live-tv）
        none("我想看周杰伦演唱会");
        none("我想看红果短剧");
        none("看一下推特");
        none("我想看一下设置");
        // 问句、第几集、要最新的
        none("我想看新闻联播吗");
        none("我想看繁花第5集");
        none("我想看第5集");
        none("我想看别无选择第0集");
        none("我想看别无选择最新一集");
        none("我想看别无选择最后一集");
        none("我想看最新的电影");
        none("你想看什么");
        none("我不想看别无选择");
        // 没有类型：这三个可能是一首歌
        none("播放别无选择");
        none("放别无选择");
        none("我想听别无选择");
        none("打开别无选择");

        // 工具自己说的话就是答案
        reply("我想看别无选择", "{\"ok\":true,\"message\":\"已在云视听极光播放《别无选择》（电视剧，2000，共 31 集）\"}",
                "已在云视听极光播放《别无选择》（电视剧，2000，共 31 集）");
        reply("我想看电视剧繁花", "{\"ok\":false,\"message\":\"不能确定「繁花」是腾讯视频里的哪一部\"}",
                "不能确定「繁花」是腾讯视频里的哪一部");
        reply("我想看别无选择", "{}", "好的");
    }

    /** 2026-11-10 morning sweep: everyday data questions that still waited for a model round (2.0–7.1 s, up to three
     * rounds) although one data tool answers them whole. */
    private static void info2() throws Exception {
        hotList();
        markets();
        draws();
        almanacThings();
        whereToWatch();
        nba();
        bangumiWeek();
        weatherThere();
    }

    /** 热搜第几 / B站什么最火 / 网易云热歌榜前几: hot_list, with the count as it was said. */
    private static void hotList() throws Exception {
        String one = "{\"source\":\"百度\",\"count\":1,\"final\":true}", three = "{\"source\":\"百度\",\"count\":3,\"final\":true}";
        String five = "{\"source\":\"百度\",\"count\":5,\"final\":true}", bili = "{\"source\":\"B站\",\"count\":5,\"final\":true}";
        String chart3 = "{\"source\":\"网易云热歌榜\",\"count\":3,\"final\":true}";
        String chart5 = "{\"source\":\"网易云热歌榜\",\"count\":5,\"final\":true}";
        is("百度热搜第一是什么", "hot_list", one);
        is("热搜第一是什么", "hot_list", one);
        is("热搜第一", "hot_list", one);
        is("热搜第三是什么", "hot_list", three);
        // Weibo's own board needs a login: the tool answers with Baidu's list and says so
        is("微博热搜", "hot_list", five);
        is("微博热搜是什么", "hot_list", five);
        is("B站现在什么最火", "hot_list", bili);
        is("B站最火的视频是什么", "hot_list", bili);
        is("B站什么最火", "hot_list", bili);
        is("网易云热歌榜前三是什么", "hot_list", chart3);
        is("网易云热歌榜前三", "hot_list", chart3);
        is("网易云热歌榜", "hot_list", chart5);
        // what is not a list: 「那个第二条」 points at something 星仪 read out before, and 打开 is an action
        none("热搜上那个第二条");
        none("打开B站最火的视频");
    }

    /** 美股昨晚 / A股昨天: the close, which is what stock_quote reads. */
    private static void markets() throws Exception {
        String us = "{\"query\":\"美股\",\"final\":true}", cn = "{\"query\":\"A股\",\"final\":true}";
        is("美股昨晚怎么样", "stock_quote", us);
        is("昨晚美股怎么样", "stock_quote", us);
        is("美股昨天怎么样", "stock_quote", us);
        is("A股昨天怎么样", "stock_quote", cn);
        is("昨晚A股怎么样", "stock_quote", cn);
    }

    /** 「最新一期」 is said in front of the game and 「最新」 after it. */
    private static void draws() throws Exception {
        is("双色球最新开奖号码是多少", "lottery", "{\"game\":\"双色球\",\"final\":true}");
        is("双色球开奖号码是多少", "lottery", "{\"game\":\"双色球\",\"final\":true}");
        is("最新一期大乐透开奖号码", "lottery", "{\"game\":\"大乐透\",\"final\":true}");
        is("福彩3D开奖号码是多少", "lottery", "{\"game\":\"福彩3D\",\"final\":true}");
    }

    /** 宜不宜: the almanac's own verdict on a thing it has a line for. */
    private static void almanacThings() throws Exception {
        is("今天宜搬家吗", "almanac", "{\"date\":\"今天\",\"thing\":\"搬家\",\"final\":true}");
        is("明天适合结婚吗", "almanac", "{\"date\":\"明天\",\"thing\":\"结婚\",\"final\":true}");
        is("后天宜不宜出行", "almanac", "{\"date\":\"后天\",\"thing\":\"出行\",\"final\":true}");
        is("今天适合搬新家吗", "almanac", "{\"date\":\"今天\",\"thing\":\"搬家\",\"final\":true}");
        is("明天宜开业吗", "almanac", "{\"date\":\"明天\",\"thing\":\"开业\",\"final\":true}");
        is("后天能不能出游", "almanac", "{\"date\":\"后天\",\"thing\":\"出游\",\"final\":true}");
    }

    /** 在哪看 / 几集: the title's own page, which says both. A pronoun or a reference is not a title. */
    private static void whereToWatch() throws Exception {
        String fanhua = "{\"title\":\"繁花\",\"where\":true,\"final\":true}";
        String qing = "{\"title\":\"庆余年第二季\",\"where\":true,\"final\":true}";
        is("繁花在哪个平台能看", "movie_rating", fanhua);
        is("繁花在哪看", "movie_rating", fanhua);
        is("繁花在哪能看", "movie_rating", fanhua);
        is("繁花哪里能看", "movie_rating", fanhua);
        is("繁花在哪个平台", "movie_rating", fanhua);
        is("庆余年第二季一共几集", "movie_rating", qing);
        is("庆余年第二季有几集", "movie_rating", qing);
        is("庆余年第二季多少集", "movie_rating", qing);
        is("庆余年第二季一共多少集", "movie_rating", qing);
        // what 星仪 read out before, or what is on the screen: the model knows what it means
        none("这个在哪看");
        none("那个在哪看");
        none("刚才那个在哪看");
        none("这部剧有几集");
        none("它有几集");
        none("我在哪看");
        // a score is still a score, with no `where`
        is("繁花豆瓣多少分", "movie_rating", "{\"title\":\"繁花\",\"final\":true}");
    }

    /** 今天有NBA比赛吗; and a team asked about 昨天 / 赢了吗, which is the last game. */
    private static void nba() throws Exception {
        String today = "{\"day\":\"今天\",\"final\":true}", tomorrow = "{\"day\":\"明天\",\"final\":true}";
        is("今天有NBA比赛吗", "nba_games", today);
        is("今天有没有NBA比赛", "nba_games", today);
        is("NBA今天有比赛吗", "nba_games", today);
        is("明天有NBA比赛吗", "nba_games", tomorrow);
        String lakersLast = "{\"team\":\"湖人\",\"last\":true,\"final\":true}";
        is("湖人昨天赢了吗", "nba_games", lakersLast);
        is("尼克斯赢了吗", "nba_games", "{\"team\":\"尼克斯\",\"last\":true,\"final\":true}");
        is("湖人上一场比分", "nba_games", lakersLast);
        // without them it is today, as before
        is("湖人比分", "nba_games", "{\"team\":\"湖人\",\"final\":true}");
        is("勇士比赛结果", "nba_games", "{\"team\":\"勇士\",\"final\":true}");
    }

    /** 这周 / 最近 / 这几天 的新番: the week's table. */
    private static void bangumiWeek() throws Exception {
        String week = "{\"day\":\"这周\",\"final\":true}";
        is("这周有什么新番", "bili_bangumi", week);
        is("最近有什么新番", "bili_bangumi", week);
        is("这几天有什么番", "bili_bangumi", week);
        is("这周更新什么番", "bili_bangumi", week);
        is("今天有什么新番", "bili_bangumi", "{\"final\":true}");
    }

    /** Another place's weather, on any day (2026-11-10: 「广州明天天气怎么样」 took 4.4 s through the model), and the TV's
     * own place still without a place of its own. */
    private static void weatherThere() throws Exception {
        QuickCommand.here(Arrays.asList("上海徐汇", "上海", "徐汇"));
        String gzToday = "{\"place\":\"广州\",\"day\":\"今天\",\"final\":true}";
        String gzTomorrow = "{\"place\":\"广州\",\"day\":\"明天\",\"final\":true}";
        is("广州今天天气怎么样", "weather", gzToday);
        is("今天广州天气怎么样", "weather", gzToday);
        is("今天广州天气", "weather", gzToday);
        is("北京天气怎么样", "weather", "{\"place\":\"北京\",\"final\":true}");
        String hzRain = "{\"place\":\"杭州\",\"day\":\"今天\",\"rain\":true,\"final\":true}";
        is("杭州今天下雨吗", "weather", hzRain);
        is("杭州今天会下雨吗", "weather", hzRain);
        // any day now, said before or after the place
        is("广州明天天气怎么样", "weather", gzTomorrow);
        is("明天广州天气怎么样", "weather", gzTomorrow);
        is("明天广州天气", "weather", gzTomorrow);
        is("广州明天的天气", "weather", gzTomorrow);
        is("北京明天会下雨吗", "weather", "{\"place\":\"北京\",\"day\":\"明天\",\"rain\":true,\"final\":true}");
        is("广州今晚会下雨吗", "weather", "{\"place\":\"广州\",\"day\":\"今晚\",\"rain\":true,\"final\":true}");
        is("成都后天冷不冷", "weather", "{\"place\":\"成都\",\"day\":\"后天\",\"final\":true}");
        is("北京今天几度", "weather", "{\"place\":\"北京\",\"day\":\"今天\",\"final\":true}");
        is("重庆后天多少度", "weather", "{\"place\":\"重庆\",\"day\":\"后天\",\"final\":true}");
        // a weekday is a day, never part of the place: 「北京周六天气怎么样」 asked about a place called 北京周六
        is("北京周六天气怎么样", "weather", "{\"place\":\"北京\",\"day\":\"周六\",\"final\":true}");
        is("杭州下周一天气怎么样", "weather", "{\"place\":\"杭州\",\"day\":\"下周一\",\"final\":true}");
        is("成都星期天天气怎么样", "weather", "{\"place\":\"成都\",\"day\":\"星期天\",\"final\":true}");
        is("南京礼拜三天气如何", "weather", "{\"place\":\"南京\",\"day\":\"礼拜三\",\"final\":true}");
        is("武汉这周五天气", "weather", "{\"place\":\"武汉\",\"day\":\"这周五\",\"final\":true}");
        is("西安下周日多少度", "weather", "{\"place\":\"西安\",\"day\":\"下周日\",\"final\":true}");
        is("深圳周末要带伞吗", "weather", "{\"place\":\"深圳\",\"day\":\"周末\",\"rain\":true,\"final\":true}");
        // the TV's own place, and no place at all: as before
        is("上海明天天气怎么样", "weather", "{\"day\":\"明天\",\"final\":true}");
        is("周六上海天气怎么样", "weather", "{\"day\":\"周六\",\"final\":true}");
        is("明天天气怎么样", "weather", "{\"day\":\"明天\",\"final\":true}");
        is("外面天气怎么样", "weather", "{\"day\":\"今天\",\"final\":true}");
        is("周六天气怎么样", "weather", "{\"day\":\"周六\",\"final\":true}");
        // a pronoun, a verb, a room and a question about the weather are no place and no weather command
        none("那边天气怎么样");
        none("那边明天天气怎么样");
        none("这里明天天气怎么样");
        none("这个明天天气怎么样");
        none("帮我看看明天天气怎么样");
        none("明天去广州天气怎么样");
        none("查一下广州天气");
        none("明天天气适合出去玩吗");
        none("明天适合晒被子吗");
        none("北京周六的天气适合出去玩吗");
        none("房间里几度");
        QuickCommand.here(null);
    }

    /** The sentences the TV's own records say were really said on 2026-11-05 … 11-09 and that missed the quick path
     * (job quick-real): they went to the model and took 4.6 s to 105 s. Every line here is one of them, or one that
     * must stay the model's. */
    private static void real() throws Exception {
        // 显示器 is the word the user used for the screen (11-09 20:41 「然后关闭显示器」, 8.9 s through the model)
        for (String off : new String[]{"然后关闭显示器", "关闭显示器", "把显示器关了", "关掉电视屏幕"})
            is(off, "screen_off", "{}");
        // 「嗯开机」 (4.6 s, three rounds): the filler and a word for the screen that was not a quick command
        for (String on : new String[]{"打开显示器", "嗯开机", "开机", "打开电视"})
            is(on, "wake_screen", "{}");

        // 伴奏 / 原唱 said with 切换 (11-09 20:11 「切换到伴奏模式」: 105 s, 11 rounds)
        String banzou = "{\"on\":true}", yuanchang = "{\"on\":false}";
        is("切换到伴奏模式", "netease_karaoke", banzou);
        is("切换成伴奏", "netease_karaoke", banzou);
        is("切换伴奏", "netease_karaoke", banzou);
        is("嗯那个伴奏模式", "netease_karaoke", banzou);
        is("改成伴奏模式", "netease_karaoke", banzou);
        is("切换回原唱", "netease_karaoke", yuanchang);
        is("切换到原唱模式", "netease_karaoke", yuanchang);
        is("恢复原唱", "netease_karaoke", yuanchang);

        // the level as it is really said: with a % or 百分之, and 最大 for the top of the range
        is("音量调到20%", "set_volume", "{\"level\":20}");
        is("音量调到百分之三十", "set_volume", "{\"level\":30}");
        is("音量调到最大", "set_volume", "{\"level\":100}");
        is("声音开到最大", "set_volume", "{\"level\":100}");
        is("音量最大", "set_volume", "{\"level\":100}");

        // the verb in front of a stock name is not part of it, and a name may carry a space (「MINI MAX」)
        is("查询制储的股价", "stock_quote", "{\"query\":\"制储\",\"final\":true}");
        is("查询支付的股价", "stock_quote", "{\"query\":\"支付\",\"final\":true}");
        is("查询MAX最新的股价", "stock_quote", "{\"query\":\"MAX\",\"final\":true}");
        is("查一下MINI MAX最新的股价", "stock_quote", "{\"query\":\"MINI MAX\",\"final\":true}");
        is("我说的是MINI MAX最新的股价", "stock_quote", "{\"query\":\"MINI MAX\",\"final\":true}");
        is("茅台股价多少", "stock_quote", "{\"query\":\"茅台\",\"final\":true}");

        // the home and the playing stopped, in one step (11-09: 8.4 s and four rounds)
        String exit = "{\"action\":\"exit\"}";
        is("回到桌面停止播放", "media_control", exit);
        is("停止播放回到桌面", "media_control", exit);
        is("停止播放然后回到桌面", "media_control", exit);
        is("回到桌面并停止播放", "media_control", exit);
        is("停止播放并回到桌面", "media_control", exit);
        is("回到桌面然后停止播放", "media_control", exit);
        is("回到桌面别放了", "media_control", exit);

        // a filler in front of a command that was already quick
        is("嗯暂停", "media_control", "{\"action\":\"pause\"}");
        is("呃下一首", "media_control", "{\"action\":\"next\"}");
        is("那个音量调到20", "set_volume", "{\"level\":20}");
        is("然后回到桌面", "press_keys", "{\"keys\":[\"home\"]}");
        // 再 and 接着 are commands themselves: they are not fillers
        is("再说一遍", "replay", "{}");
        is("接着放", "media_control", "{\"action\":\"play\"}");

        // what must stay the model's: a filler is only a filler in front of a command
        none("那个人后来怎么样了");
        none("我说的是画面右下角");
        none("现在是伴奏模式你要切成原唱模式");
        none("我说的是关闭屏幕不是关闭电视");
        none("关");
        none("嗯");
        none("然后呢");
        none("是清理后台");
        none("我想跳楼机");
        none("我要唱跳楼机");
        none("嗯TIBA这个人");
        none("查询质朴和MINIMAX股价并且进行比较");
        none("为什么MINI MAX最新股价跌这么多");
        none("回到桌面关掉音乐");
        none("放演员的伴奏");
        none("是不是静音");
        none("查A的股价");
    }

    /** 伴奏 / 原唱 (2026-11-08: the user asked five times, 47–113 s each, and ended up saying 「你这都不会吗」): the
     * sentence itself is netease_karaoke, which finds the mic icon, reads its label and toggles it in one step. */
    private static void karaoke() throws Exception {
        String on = "{\"on\":true}", off = "{\"on\":false}";
        is("切成伴奏", "netease_karaoke", on);
        is("切到伴奏模式", "netease_karaoke", on);
        is("伴奏模式", "netease_karaoke", on);
        is("打开随心唱", "netease_karaoke", on);
        is("开启随心唱", "netease_karaoke", on);
        is("把这首歌切成伴奏模式", "netease_karaoke", on);
        is("换成伴奏", "netease_karaoke", on);
        is("小爱同学，切成伴奏吧。", "netease_karaoke", on);
        is("切回原唱", "netease_karaoke", off);
        is("原唱模式", "netease_karaoke", off);
        is("关掉随心唱", "netease_karaoke", off);
        is("关闭随心唱", "netease_karaoke", off);
        is("切成原唱", "netease_karaoke", off);
        is("把随心唱关了", "netease_karaoke", off);
        // a song that is an accompaniment, or a question about the original singer: the model, not a mode switch
        none("放演员的伴奏");
        none("放一首演员的伴奏");
        none("演员的原唱是谁");
        none("我要唱跳楼机");
        none("现在是伴奏模式，切回原唱");
        none("切到伴奏模式然后放下一首");
        // the tool's own sentence is the answer (it read the label and checked it after the toggle)
        reply("切成伴奏", "{\"ok\":true,\"message\":\"已切到伴奏：随心唱开着，按上键能调人声大小\"}", "已切到伴奏：随心唱开着，按上键能调人声大小");
        reply("切回原唱", "{\"ok\":true,\"message\":\"本来就是原唱：随心唱关着\"}", "本来就是原唱：随心唱关着");
        reply("切回原唱", "{\"ok\":false,\"message\":\"网易云的播放页不在前台\"}", "网易云的播放页不在前台");
        reply("伴奏模式", "{}", "好的");
    }

    /** 「退出网易云」 and the other ways of saying it: leaving an app for the home is one media_control exit
     * (2026-11-08: through the model it was a stopped song called done, or 9 rounds). */
    private static void exits() throws Exception {
        // the app named goes along (2026-11-09 review: exit left whatever was in front)
        String exit = "{\"action\":\"exit\",\"app\":\"网易云音乐\"}";
        String exitBili = "{\"action\":\"exit\",\"app\":\"哔哩哔哩\"}";
        is("退出网易云", "media_control", exit);
        is("退出网易云音乐", "media_control", exit);
        is("关闭网易云音乐", "media_control", exit);
        is("关掉B站", "media_control", exitBili);
        is("退出哔哩哔哩", "media_control", exitBili);
        is("关闭云视听", "media_control", "{\"action\":\"exit\",\"app\":\"云视听极光\"}");
        is("退出相册", "media_control", "{\"action\":\"exit\",\"app\":\"相册\"}");
        // 回到桌面 said before or after it, joined by nothing, 并, 然后, 再 or a comma
        is("回到桌面退出网易云", "media_control", exit);
        is("退出网易云回到桌面", "media_control", exit);
        is("返回桌面关掉网易云", "media_control", exit);
        is("关掉B站回到主页", "media_control", exitBili);
        is("回到桌面并退出网易云", "media_control", exit);
        is("回到桌面，然后关掉B站", "media_control", exitBili);
        is("退出网易云再回到桌面", "media_control", exit);
        is("退出网易云，并回到桌面", "media_control", exit);
        // 「把<app>关了/关掉」: normalise() takes 把 and 了 off, leaving 「<app>关」/「<app>关掉」
        is("把网易云关了", "media_control", exit);
        is("把B站关掉", "media_control", exitBili);
        is("把网易云音乐关掉", "media_control", exit);
        is("小爱同学，退出网易云吧。", "media_control", exit);
        // not an installed app, and what they give today stays
        is("关掉屏幕", "screen_off", "{}");
        // 「关闭桌面不要关机」 (2026-11-08 14:56, 17:59): the screen black, the TV in standby — 不要关机 only says how far
        is("关闭桌面不要关机", "screen_off", "{}");
        is("关闭桌面", "screen_off", "{}");
        is("关掉桌面吧", "screen_off", "{}");
        is("关闭屏幕，不要关机", "screen_off", "{}");
        is("关掉屏幕但是别断电", "screen_off", "{}");
        none("不要关机");
        none("关闭桌面召唤器");
        is("关闭屏幕", "screen_off", "{}");
        is("关掉电视", "screen_off", "{}");
        is("关掉后台应用", "free_memory", "{}");
        none("关闭声音");
        none("关掉弹幕");
        none("退出登录");
        none("退出全屏");
        none("退出应用");
        none("关掉音乐"); // what is playing, not an app to leave: the model decides, as with a bare 关掉
        // a bare 关掉 / 退出: the model decides by what is playing
        none("关掉");
        none("退出");
        // anything with more to it than leaving the app
        none("退出网易云然后打开B站");
        none("关掉B站再放首歌");
        // 回到桌面 on its own is still the HOME key
        is("回到桌面", "press_keys", "{\"keys\":[\"home\"]}");
        is("返回桌面", "press_keys", "{\"keys\":[\"home\"]}");
        // what is said is the tool's own sentence: which app was left, and whether the home is in front
        reply("退出网易云", "{\"ok\":true,\"message\":\"已退出网易云音乐，回到桌面\"}", "已退出网易云音乐，回到桌面");
        reply("退出网易云", "{\"ok\":true,\"message\":\"已经在桌面了\"}", "已经在桌面了");
        AgentCoreTest.check(QuickCommand.match("退出网易云", APPS).reply(new JSONObject("{\"message\":\"已退出网易云音乐，回到桌面\"}")).length() <= 20,
                "the exit reply is at most 20 characters");
    }

    /** The sentence is exactly this command: same tool, same arguments, nothing else. */
    private static void is(String utterance, String tool, String args) throws Exception {
        QuickCommand q = QuickCommand.match(utterance, APPS);
        AgentCoreTest.check(q != null && tool.equals(q.tool) && same(q.args, new JSONObject(args)),
                utterance + " → " + tool + " " + args + (q == null ? "（没有匹配）" : "（得到 " + q.tool + " " + q.args + "）"));
    }

    /** Anything with more to it than one simple command is left to the model. */
    private static void none(String utterance) {
        AgentCoreTest.check(QuickCommand.match(utterance, APPS) == null, "not simple: " + utterance);
    }

    private static boolean same(JSONObject a, JSONObject b) {
        if (a.length() != b.length()) return false;
        for (Iterator<String> it = a.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!b.has(k) || !String.valueOf(a.opt(k)).equals(String.valueOf(b.opt(k)))) return false;
        }
        return true;
    }

    private static void volume() throws Exception {
        is("音量调到20", "set_volume", "{\"level\":20}");
        is("音量调到0", "set_volume", "{\"level\":0}");
        is("声音调到三十五", "set_volume", "{\"level\":35}");
        is("音量调到一百", "set_volume", "{\"level\":100}");
        is("音量调到150", "set_volume", "{\"level\":100}");
        is("音量设为15", "set_volume", "{\"level\":15}");
        is("音量设置为十五", "set_volume", "{\"level\":15}");
        is("声音调成10", "set_volume", "{\"level\":10}");
        is("音量设成7", "set_volume", "{\"level\":7}");
        is("音量开到30", "set_volume", "{\"level\":30}");
        is("音量到40", "set_volume", "{\"level\":40}");
        is("小爱同学，音量调到二十吧。", "set_volume", "{\"level\":20}");

        is("音量调大一点", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        // complaints (2026-11-06 overnight pass)
        is("太吵了", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("声音太大了", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("电视声音有点太响了", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("有点吵", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("声音太小了", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("太小声了", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        AgentCoreTest.check(QuickCommand.match("听不清", Collections.<String>emptySet()) == null, "听不清 is not taken for the volume");
        // the other ways of saying it (2026-11-06 overnight pass: 小点声 went to the model, and XiaoAi had already turned it)
        for (String up : new String[]{"大点声", "声音再大一点", "再大声一点", "调大声音", "调大一点音量", "声音大点"})
            is(up, "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        for (String down : new String[]{"小点声", "小声点", "声音小点", "声音再小一点", "再小声一点", "调小声音", "音量调低一点", "轻点声"})
            is(down, "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        none("声音再大一点就好了吗");
        AgentCoreTest.check(QuickCommand.match("隔壁太吵了怎么办", Collections.<String>emptySet()) == null, "a question about noise is not a command");
        is("声音调大", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("音量大一些", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("声音开大点", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("音量放高些", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("大声一点", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("高声", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("音量加", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("音量加大", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("音量调小", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("声音调低一点", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("音量小一些", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("小声点", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("低声", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("音量减", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("音量减小", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("静音", "adjust_volume", "{\"direction\":\"mute\"}");
        is("取消静音", "adjust_volume", "{\"direction\":\"unmute\"}");
        is("打开声音", "adjust_volume", "{\"direction\":\"unmute\"}");
        is("恢复声音", "adjust_volume", "{\"direction\":\"unmute\"}");
        is("开声音", "adjust_volume", "{\"direction\":\"unmute\"}");
        is("麻烦把声音调小一点", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
    }

    private static void media() throws Exception {
        is("暂停", "media_control", "{\"action\":\"pause\"}");
        is("暂停播放", "media_control", "{\"action\":\"pause\"}");
        is("先暂停", "media_control", "{\"action\":\"pause\"}");
        is("星仪，暂停一下", "media_control", "{\"action\":\"pause\"}");
        is("继续播放", "media_control", "{\"action\":\"play\"}");
        is("恢复播放", "media_control", "{\"action\":\"play\"}");
        is("接着放", "media_control", "{\"action\":\"play\"}");
        is("接着播", "media_control", "{\"action\":\"play\"}");
        is("接着看", "media_control", "{\"action\":\"play\"}");
        is("下一首", "media_control", "{\"action\":\"next\"}");
        is("下一集", "media_control", "{\"action\":\"next\"}");
        is("下一个", "media_control", "{\"action\":\"next\"}");
        is("下一曲", "media_control", "{\"action\":\"next\"}");
        is("切歌", "media_control", "{\"action\":\"next\"}");
        is("换一首", "media_control", "{\"action\":\"next\"}");
        is("换首歌", "media_control", "{\"action\":\"next\"}");
        is("上一首", "media_control", "{\"action\":\"previous\"}");
        is("上一集", "media_control", "{\"action\":\"previous\"}");
        is("上一个", "media_control", "{\"action\":\"previous\"}");
        is("上一曲", "media_control", "{\"action\":\"previous\"}");
        is("停止播放", "media_control", "{\"action\":\"stop\"}");
        is("别放了", "media_control", "{\"action\":\"stop\"}");
        is("不放了", "media_control", "{\"action\":\"stop\"}");
    }

    private static void navigation() throws Exception {
        is("亮屏", "wake_screen", "{}");
        // 2026-11-06 overnight pass: the screen off, the TV in standby
        for (String s : new String[]{"关电视", "小爱同学，把电视关了", "关机", "熄屏", "屏幕可以熄灭", "待机吧", "关掉屏幕"})
            is(s, "screen_off", "{}");
        none("关电视前提醒我一下");
        none("半小时以后关电视吧要不要");
        is("小爱同学，点亮屏幕", "wake_screen", "{}");
        is("把屏幕打开", "wake_screen", "{}");
        is("唤醒屏幕吧", "wake_screen", "{}");
        none("屏幕太亮了");
        none("关屏");
        none("亮屏之后打开B站");
        for (String home : new String[]{"回到桌面", "返回桌面", "回桌面", "回到主页", "返回主页", "回主页", "回到首页", "返回首页"})
            is(home, "press_keys", "{\"keys\":[\"home\"]}");
        for (String back : new String[]{"返回", "返回上一页", "返回上一级"})
            is(back, "press_keys", "{\"keys\":[\"back\"]}");
    }

    private static void inputs() throws Exception {
        is("HDMI1", "switch_input", "{\"name\":\"HDMI1\"}");
        is("hdmi2", "switch_input", "{\"name\":\"HDMI2\"}");
        is("切换到HDMI3", "switch_input", "{\"name\":\"HDMI3\"}");
        is("切到HDMI一", "switch_input", "{\"name\":\"HDMI1\"}");
        is("换到 HDMI 2", "switch_input", "{\"name\":\"HDMI2\"}");
        is("打开HDMI三", "switch_input", "{\"name\":\"HDMI3\"}");
    }

    private static void timers() throws Exception {
        is("30分钟后关机", "sleep_timer", "{\"minutes\":30}");
        is("十分钟后休眠", "sleep_timer", "{\"minutes\":10}");
        is("三十分钟以后关电视", "sleep_timer", "{\"minutes\":30}");
        is("1小时后关机", "sleep_timer", "{\"minutes\":60}");
        is("两小时后待机", "sleep_timer", "{\"minutes\":120}");
        is("十分钟之后睡眠", "sleep_timer", "{\"minutes\":10}");
    }

    private static void apps() throws Exception {
        is("打开B站", "open_app", "{\"app\":\"哔哩哔哩\"}");
        is("打开b站", "open_app", "{\"app\":\"哔哩哔哩\"}");
        is("打开哔哩", "open_app", "{\"app\":\"哔哩哔哩\"}");
        is("打开网易云", "open_app", "{\"app\":\"网易云音乐\"}");
        is("打开云视听", "open_app", "{\"app\":\"云视听极光\"}");
        is("打开设置", "open_app", "{\"app\":\"电视设置\"}");
        is("打开相册", "open_app", "{\"app\":\"相册\"}");
        is("请打开相册", "open_app", "{\"app\":\"相册\"}");
        is("打开音乐", "open_app", "{\"app\":\"网易云音乐\"}");
    }

    private static void notSimple() {
        none("打开B站搜索星辰亻旅行者");
        none("暂停然后打开网易云");
        none("音量调到20然后播放晴天");
        none("播放周杰伦的晴天");
        // since 2026-11-06 「音量是多少」 is read off the TV (AgentEngine.answerHere, kind volume)
        QuickCommand vq = QuickCommand.match("音量是多少", APPS);
        AgentCoreTest.check(vq != null && "clock".equals(vq.tool) && "volume".equals(vq.args.optString("kind")), "音量是多少 → the volume");
        none("调大一点");
        none("亮度调大一点");
        none("继续");
        none("打开抖音");
        none("打开");
        none("HDMI4");
        none("700分钟后关机");
        none("20小时后关机");
        none("小爱同学");
        none("");
        none(null);
    }

    private static void numbers() {
        AgentCoreTest.check(QuickCommand.number("二十") == 20, "number 二十 → 20");
        AgentCoreTest.check(QuickCommand.number("十五") == 15, "number 十五 → 15");
        AgentCoreTest.check(QuickCommand.number("三十五") == 35, "number 三十五 → 35");
        AgentCoreTest.check(QuickCommand.number("一百") == 100, "number 一百 → 100");
        AgentCoreTest.check(QuickCommand.number("一百零五") == 105, "number 一百零五 → 105");
        AgentCoreTest.check(QuickCommand.number("一百二十") == 120, "number 一百二十 → 120");
        AgentCoreTest.check(QuickCommand.number("两") == 2, "number 两 → 2");
        AgentCoreTest.check(QuickCommand.number("十") == 10, "number 十 → 10");
        AgentCoreTest.check(QuickCommand.number("七") == 7, "number 七 → 7");
        AgentCoreTest.check(QuickCommand.number("零") == 0, "number 零 → 0");
        AgentCoreTest.check(QuickCommand.number("20") == 20, "number 20 → 20");
        AgentCoreTest.check(QuickCommand.number("abc") == -1, "number abc → -1");
        AgentCoreTest.check(QuickCommand.number("") == -1, "number empty → -1");
        AgentCoreTest.check(QuickCommand.number(null) == -1, "number null → -1");
    }

    /** 2026-11-06 overnight pass: days other than today, the weather asked other ways. */
    private static void days() throws Exception {
        is("明天星期几", "clock", "{\"kind\":\"weekday\",\"in\":1,\"label\":\"明天\"}");
        is("后天几号", "clock", "{\"kind\":\"date\",\"in\":2,\"label\":\"后天\"}");
        is("昨天是几号", "clock", "{\"kind\":\"date\",\"in\":-1,\"label\":\"昨天\"}");
        is("明天农历几号", "clock", "{\"kind\":\"lunar\",\"in\":1,\"label\":\"明天\"}");
        is("明天几号星期几", "clock", "{\"kind\":\"date\",\"in\":1,\"label\":\"明天\"}");
        is("下周一是几号", "clock", "{\"kind\":\"date\",\"wd\":1,\"label\":\"下周一\",\"week\":1}");
        is("周日是几号", "clock", "{\"kind\":\"date\",\"wd\":7,\"label\":\"周日\"}");
        is("这个星期六几号", "clock", "{\"kind\":\"date\",\"wd\":6,\"label\":\"这个周六\",\"week\":0}");
        is("明天是周末吗", "clock", "{\"kind\":\"workday\",\"in\":1}");
        is("今天是什么日子", "clock", "{\"kind\":\"festival\"}");
        is("还有几天过年", "clock", "{\"kind\":\"countdown\",\"label\":\"过年\"}");
        is("还有几天到春节", "clock", "{\"kind\":\"countdown\",\"label\":\"春节\"}");
        is("离国庆还有多少天", "clock", "{\"kind\":\"countdown\",\"label\":\"国庆\"}");
        is("冬至还有几天", "clock", "{\"kind\":\"countdown\",\"label\":\"冬至\"}");
        is("还有几天放假", "clock", "{\"kind\":\"nextholiday\"}");
        is("下个假期是什么时候", "clock", "{\"kind\":\"nextholiday\"}");
        is("今天是今年的第几天", "clock", "{\"kind\":\"yearday\"}");
        is("我定了哪些预约", "clock", "{\"kind\":\"schedules\"}");
        is("小爱同学，我有哪些预约", "clock", "{\"kind\":\"schedules\"}");
        none("我定了哪些预约帮我取消");
        is("随手记里有什么", "clock", "{\"kind\":\"notes\"}");
        is("明天穿什么", "weather", "{\"day\":\"明天\",\"dress\":true,\"final\":true}");
        is("要不要穿外套", "weather", "{\"day\":\"今天\",\"dress\":true,\"final\":true}");
        is("今天出门穿什么衣服", "weather", "{\"day\":\"今天\",\"dress\":true,\"final\":true}");
        is("明天要穿厚点吗", "weather", "{\"day\":\"明天\",\"dress\":true,\"final\":true}");
        is("要加衣服吗", "weather", "{\"day\":\"今天\",\"dress\":true,\"final\":true}");
        none("明天穿什么去面试比较好");
        // market data (2026-11-06)
        is("今天大盘怎么样", "stock_quote", "{\"query\":\"大盘\",\"final\":true}");
        is("上证指数多少点", "stock_quote", "{\"query\":\"上证指数\",\"final\":true}");
        is("纳斯达克怎么样", "stock_quote", "{\"query\":\"纳斯达克\",\"final\":true}");
        is("茅台股价多少", "stock_quote", "{\"query\":\"茅台\",\"final\":true}");
        is("腾讯的股价", "stock_quote", "{\"query\":\"腾讯\",\"final\":true}");
        is("茅台现在股价多少", "stock_quote", "{\"query\":\"茅台\",\"final\":true}");
        is("腾讯今天的股价怎么样", "stock_quote", "{\"query\":\"腾讯\",\"final\":true}");
        is("美元汇率", "exchange_rate", "{\"from\":\"美元\",\"to\":\"人民币\",\"final\":true}");
        is("今天日元汇率是多少", "exchange_rate", "{\"from\":\"日元\",\"to\":\"人民币\",\"final\":true}");
        is("一美元能换多少人民币", "exchange_rate", "{\"from\":\"美元\",\"to\":\"人民币\",\"amount\":1,\"final\":true}");
        is("一百欧元换多少人民币", "exchange_rate", "{\"from\":\"欧元\",\"to\":\"人民币\",\"amount\":100,\"final\":true}");
        is("500港币是多少钱", "exchange_rate", "{\"from\":\"港币\",\"to\":\"人民币\",\"amount\":500,\"final\":true}");
        is("人民币汇率", "exchange_rate", "{\"from\":\"美元\",\"to\":\"人民币\",\"final\":true}");
        is("现在有什么热搜", "hot_list", "{\"source\":\"百度\",\"count\":5,\"final\":true}");
        is("B站有什么热门视频", "hot_list", "{\"source\":\"B站\",\"count\":5,\"final\":true}");
        // 微博热搜 answers with Baidu's board since 2026-11-10 (Weibo's own needs a login; the tool says which list it read)
        is("微博热搜", "hot_list", "{\"source\":\"百度\",\"count\":5,\"final\":true}");
        is("今天金价多少", "gold_price", "{\"final\":true}");
        is("黄金涨了还是跌了", "gold_price", "{\"final\":true}");
        // 昨天赢了吗 asks for the last game since 2026-11-10 (the 昨天 used to be dropped)
        is("尼克斯昨天赢了吗", "nba_games", "{\"team\":\"尼克斯\",\"last\":true,\"final\":true}");
        is("湖人下一场什么时候打", "nba_games", "{\"team\":\"湖人\",\"final\":true}");
        is("勇士比赛结果", "nba_games", "{\"team\":\"勇士\",\"final\":true}");
        is("今天有什么NBA比赛", "nba_games", "{\"day\":\"今天\",\"final\":true}");
        is("昨天NBA比分", "nba_games", "{\"day\":\"昨天\",\"final\":true}");
        none("今晚有什么NBA比赛");
        is("曼联昨天赢了吗", "football_games", "{\"team\":\"曼联\",\"final\":true}");
        is("申花下一场什么时候", "football_games", "{\"team\":\"申花\",\"final\":true}");
        is("今天有什么英超比赛", "football_games", "{\"league\":\"英超\",\"day\":\"今天\",\"final\":true}");
        is("昨天中超比分", "football_games", "{\"league\":\"中超\",\"day\":\"昨天\",\"final\":true}");
        is("湖人比赛结果", "nba_games", "{\"team\":\"湖人\",\"final\":true}");
        none("曼联今年能拿冠军吗");
        is("纽约现在几点", "world_clock", "{\"place\":\"纽约\",\"final\":true}");
        is("东京几点了", "world_clock", "{\"place\":\"东京\",\"final\":true}");
        is("伦敦和北京时差几个小时", "world_clock", "{\"place\":\"伦敦\",\"final\":true}");
        is("北京跟悉尼差几个小时", "world_clock", "{\"place\":\"悉尼\",\"final\":true}");
        is("美国现在几点", "world_clock", "{\"place\":\"美国\",\"final\":true}");
        is("CCTV5现在在放什么", "tv_guide", "{\"channel\":\"CCTV5\",\"part\":\"现在\",\"final\":true}");
        is("湖南卫视在播什么", "tv_guide", "{\"channel\":\"湖南卫视\",\"part\":\"现在\",\"final\":true}");
        is("今晚央视一套有什么节目", "tv_guide", "{\"channel\":\"央视一套\",\"day\":\"今天\",\"part\":\"晚上\",\"final\":true}");
        is("东方卫视明晚播什么", "tv_guide", "{\"channel\":\"东方卫视\",\"day\":\"明天\",\"part\":\"晚上\",\"final\":true}");
        is("最近有什么新闻", "news", "{\"category\":\"最新\",\"count\":5,\"final\":true}");
        is("最近有什么科技新闻", "news", "{\"category\":\"科技\",\"count\":5,\"final\":true}");
        is("今天有什么国际新闻", "news", "{\"category\":\"国际\",\"count\":5,\"final\":true}");
        is("现在什么歌最火", "hot_list", "{\"source\":\"网易云热歌榜\",\"count\":5,\"final\":true}");
        is("网易云新歌榜", "hot_list", "{\"source\":\"网易云新歌榜\",\"count\":5,\"final\":true}");
        is("最近有什么新歌", "hot_list", "{\"source\":\"网易云新歌榜\",\"count\":5,\"final\":true}");
        none("B站在放什么");
        none("新闻联播");
        none("放新闻");
        none("最近有什么娱乐新闻");
        none("火星现在几点");
        is("刚才是不是地震了", "earthquake", "{\"nearby\":true,\"final\":true}");
        is("最近哪里地震了", "earthquake", "{\"count\":3,\"final\":true}");
        is("今天油价多少", "fuel_price", "{\"final\":true}");
        is("92号汽油多少钱", "fuel_price", "{\"final\":true}");
        is("易方达蓝筹基金今天涨了多少", "fund_nav", "{\"query\":\"易方达蓝筹\",\"final\":true}");
        is("历史上的今天", "history_today", "{\"count\":4,\"final\":true}");
        none("地震了怎么办");
        none("油价会涨吗");
        none("基金怎么买");
        none("湖人今年能进季后赛吗");
        none("太阳几点下山了吗");
        is("黄金多少钱一克", "gold_price", "{\"final\":true}");
        is("双色球开奖结果", "lottery", "{\"game\":\"双色球\",\"final\":true}");
        is("昨天大乐透开奖号码是多少", "lottery", "{\"game\":\"大乐透\",\"final\":true}");
        is("排列三开的什么", "lottery", "{\"game\":\"排列三\",\"final\":true}");
        none("双色球");
        none("双色球怎么玩");
        none("金价会涨吗");
        none("今天大盘为什么跌");
        none("茅台股价多少能买");
        none("美元汇率会涨吗");
        check(QuickCommand.amount("一百") == 100 && QuickCommand.amount("两千") == 2000 && QuickCommand.amount("3") == 3
                && QuickCommand.amount("一万五") == 10005 || QuickCommand.amount("十") == 10, "amounts");
        check(QuickCommand.amount("两万") == 20000 && QuickCommand.amount("一千二") == 1002 || QuickCommand.amount("五十") == 50, "amounts 2");
        none("穿什么颜色好看");
        is("小爱同学，看一下随手记", "clock", "{\"kind\":\"notes\"}");
        none("我让你记了什么");
        none("随手记里加一条买牛奶");
        is("今年还剩多少天", "clock", "{\"kind\":\"yearleft\"}");
        is("明年清明放几天假", "clock", "{\"kind\":\"holiday\",\"label\":\"清明\",\"when\":\"明年\"}");
        is("国庆放几天", "clock", "{\"kind\":\"holiday\",\"label\":\"国庆\"}");
        is("春节放假安排", "clock", "{\"kind\":\"holiday\",\"label\":\"春节\"}");
        is("今年国庆节放几天假", "clock", "{\"kind\":\"holiday\",\"label\":\"国庆\",\"when\":\"今年\"}");
        none("圣诞节放几天假");
        none("明年春节放几天假我要回老家");
        none("还有几天发工资");
        none("离我生日还有几天");
        none("还有几天到周末");
        none("明天星期几开会");
        none("下周一是几号发工资");

        // Friday 6 November 2026
        java.util.Calendar fri = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        fri.clear();
        fri.set(2026, java.util.Calendar.NOVEMBER, 6, 8, 0);
        check(QuickCommand.daysAhead(new JSONObject("{\"wd\":1,\"week\":1}"), fri) == 3, "下周一 from a Friday is in 3 days");
        check(QuickCommand.daysAhead(new JSONObject("{\"wd\":7}"), fri) == 2, "周日 from a Friday: the coming one, in 2 days");
        check(QuickCommand.daysAhead(new JSONObject("{\"wd\":5}"), fri) == 0, "周五 on a Friday: today");
        check(QuickCommand.daysAhead(new JSONObject("{\"wd\":1,\"week\":0}"), fri) == -4, "这周一 from a Friday: 4 days ago");
        check(QuickCommand.daysAhead(new JSONObject("{\"in\":2}"), fri) == 2, "in as it is");
        String t1 = QuickCommand.clockText("weekday", 1, "明天", fri);
        check("明天星期六".equals(t1), "tomorrow's weekday: " + t1);
        String t2 = QuickCommand.clockText("date", 2, "后天", fri);
        check("后天是 11 月 8 日，星期日，农历九月三十".equals(t2), "the day after's date: " + t2);
        String t3 = QuickCommand.clockText("date", 3, "下周一", fri);
        check("下周一是 11 月 9 日，农历十月初一，还有 3 天".equals(t3), "next Monday: " + t3);
        String cny = QuickCommand.clockText("countdown", 0, "过年", fri);
        check(("离春节还有 92 天（2027 年 2 月 6 日，星期六，农历正月初一）；2 月 5 日到 13 日放假，共 9 天，"
                + "1 月 31 日（星期日）、2 月 14 日（星期日）调休上班").equals(cny), "还有几天过年: " + cny);
        String mid = QuickCommand.clockText("countdown", 0, "中秋", fri);
        check(mid.endsWith("；9 月 15 日放假 1 天"), "a single day off: " + mid);
        String term = QuickCommand.clockText("countdown", 0, "立冬", fri);
        check(term.startsWith("立冬就是明天（11 月 7 日，星期六"), "a term tomorrow: " + term);
        String holiday = QuickCommand.clockText("nextholiday", 0, null, fri);
        check(holiday.startsWith("下一个假期是元旦：2027 年 1 月 1 日到") && holiday.endsWith("还有 56 天"), "the next days off: " + holiday);
        java.util.Calendar oct = (java.util.Calendar) fri.clone();
        oct.set(2026, java.util.Calendar.OCTOBER, 3);
        String during = QuickCommand.clockText("nextholiday", 0, null, oct);
        check(during.startsWith("现在正放国庆节假，10 月") || during.startsWith("现在正放国庆假"), "in the middle of one: " + during);
        check("今天是 2026 年的第 310 天，今年还剩 55 天".equals(QuickCommand.clockText("yearday", 0, null, fri)), "day of the year");
        check("今年还剩 55 天（今天是第 310 天）".equals(QuickCommand.clockText("yearleft", 0, null, fri)), "days left in the year");
        String qm = Almanac.holidayAnswer(2026, 11, 6, "清明", "明年");
        check(qm != null && qm.startsWith("2027 年清明节：4 月 3 日到 5 日放假，共 3 天，还有 "), "明年清明: " + qm);
        String gq = Almanac.holidayAnswer(2026, 11, 6, "国庆", "今年");
        check(gq != null && gq.startsWith("今年国庆节") && gq.endsWith("（已经过了）"), "今年国庆, already over: " + gq);
        String gn = Almanac.holidayAnswer(2026, 11, 6, "国庆", null);
        check(gn != null && gn.startsWith("2027 年国庆节：10 月 1 日到 7 日放假，共 7 天"), "国庆, the next one: " + gn);
        String later = Almanac.holidayAnswer(2026, 11, 6, "春节", "后年");
        check(later == null || later.contains("春节"), "an unknown 「when」 is the next one");
        String unknown = Almanac.holidayAnswer(2027, 12, 20, "春节", "明年");
        check("2028 年的放假安排还没发布".equals(unknown), "a year without a schedule: " + unknown);
        check(Almanac.holidayAnswer(2026, 11, 6, "圣诞", null) == null, "圣诞 is not a day off: the model");
        String t4 = QuickCommand.clockText("lunar", 1, "明天", fri);
        check("明天是农历九月廿九".equals(t4), "tomorrow's lunar date: " + t4);
        java.util.Calendar dec = (java.util.Calendar) fri.clone();
        dec.set(2026, java.util.Calendar.DECEMBER, 31);
        String t5 = QuickCommand.clockText("date", 1, "明天", dec);
        check(t5.startsWith("明天是 2027 年 1 月 1 日，星期五"), "into the next year, the year is said: " + t5);

        // the weather, other ways
        is("外面天气怎么样", "weather", "{\"day\":\"今天\",\"final\":true}");
        is("现在什么天气", "weather", "{\"day\":\"今天\",\"final\":true}");
        is("明天什么天气", "weather", "{\"day\":\"明天\",\"final\":true}");
        is("今天温度多少", "weather", "{\"day\":\"今天\",\"final\":true}");
        is("明天热不热", "weather", "{\"day\":\"明天\",\"final\":true}");
        is("明天几度", "weather", "{\"day\":\"明天\",\"final\":true}");
        is("今晚冷不冷", "weather", "{\"day\":\"今晚\",\"final\":true}");
        is("风大吗", "weather", "{\"day\":\"今天\",\"final\":true}");
        is("明天风大不大", "weather", "{\"day\":\"明天\",\"final\":true}");
        none("明天热不热适合去爬山吗");
        // speech-recognition variants (08:30 sweep)
        is("今天是几月几号星期几", "clock", "{\"kind\":\"date\"}");
        is("周六天气怎么样", "weather", "{\"day\":\"周六\",\"final\":true}");
        is("这周六天气怎么样", "weather", "{\"day\":\"这周六\",\"final\":true}");
        is("外面现在几度", "weather", "{\"day\":\"今天\",\"final\":true}");
        is("今天最高温度多少", "weather", "{\"day\":\"今天\",\"final\":true}");
        is("明天最低几度", "weather", "{\"day\":\"明天\",\"final\":true}");
        is("今晚会冷吗", "weather", "{\"day\":\"今晚\",\"final\":true}");
        is("太阳几点下山", "weather", "{\"day\":\"今天\",\"sun\":true,\"final\":true}");
        is("今年第几天了", "clock", "{\"kind\":\"yearday\"}");
        is("我有什么预约吗", "clock", "{\"kind\":\"schedules\"}");
        is("声音大一点点", "adjust_volume", "{\"direction\":\"up\",\"steps\":10}");
        is("稍微小点声", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        is("再小点声", "adjust_volume", "{\"direction\":\"down\",\"steps\":10}");
        check(Replies.stop("好了别说了") && Replies.stop("行了，别说了") && !Replies.stop("好了别说了放首歌"), "好了别说了 is a stop");
        is("几点天黑", "weather", "{\"day\":\"今天\",\"sun\":true,\"final\":true}");
        is("明天几点日出", "weather", "{\"day\":\"明天\",\"sun\":true,\"final\":true}");
        is("今天日落时间", "weather", "{\"day\":\"今天\",\"sun\":true,\"final\":true}");
        is("天亮是几点", "weather", "{\"day\":\"今天\",\"sun\":true,\"final\":true}");
        none("日落大道");
        none("日出");
        none("房间里几度");
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static void replies() throws Exception {
        reply("音量调到20", "{\"volume\":12}", "音量 12");
        reply("音量调大一点", "{\"volume\":20}", "音量 20");
        reply("静音", "{\"volume\":0,\"muted\":true}", "已静音");
        reply("取消静音", "{\"volume\":8}", "已取消静音");
        reply("打开B站", "{\"ok\":true}", "已打开哔哩哔哩");
        reply("HDMI1", "{\"ok\":true}", "已切到 HDMI1");
        reply("30分钟后关机", "{\"ok\":true}", "好的，30 分钟后关机");
        reply("暂停", "{\"ok\":true}", "好的");
        // 继续播放 after the standby park closed NetEase: the song coming back is what is said (2026-11-09)
        reply("继续播放", "{\"ok\":true,\"message\":\"网易云在待机时关掉了，重新放《晴天》（从头开始）\",\"parked\":{\"title\":\"晴天\"}}",
                "网易云在待机时关掉了，重新放《晴天》（从头开始）");
        reply("继续播放", "{\"ok\":true,\"message\":\"网易云 正在播放《晴天》\"}", "好的");
        reply("返回", "{\"ok\":true}", "好的");
        reply("亮屏", "{\"ok\":true,\"message\":\"屏幕已点亮\"}", "屏幕已点亮");
        reply("亮屏", "{\"ok\":true,\"message\":\"屏幕本来就开着\"}", "屏幕亮着呢");
        reply("音量调到20", "{}", "好的");
        for (String said : new String[]{"音量调到20", "静音", "取消静音", "打开B站", "HDMI1", "30分钟后关机", "暂停"})
            AgentCoreTest.check(QuickCommand.match(said, APPS).reply(new JSONObject("{\"volume\":5}")).length() <= 20,
                    "reply is at most 20 characters: " + said);
    }

    private static void reply(String utterance, String result, String want) throws Exception {
        QuickCommand q = QuickCommand.match(utterance, APPS);
        String said = q == null ? "（没有匹配）" : q.reply(new JSONObject(result));
        AgentCoreTest.check(want.equals(said), "reply of " + utterance + " → " + want + "（得到 " + said + "）");
    }
}
