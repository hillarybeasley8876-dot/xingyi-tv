package xyz.erapi0neer.xingyi.agent;

import java.util.regex.Pattern;

/**
 * What a sentence means when it arrives while 星仪 is busy, waiting for an answer, or idle (2026-11-05 audit).
 * Before this every new sentence cancelled the running task, 「停」 did nothing while a question was pending, and a
 * task superseded by a newer request still said 「已停止。」. The routing is here, not in the engine, so it is checked
 * offline; AgentEngine only carries the routes out.
 */
public final class Dispatch {
    private Dispatch() { }

    public enum Route { CONSENT, STOP_TASK, DROP_QUESTION, IDLE_STOP, SIDE, QUEUE, SUPERSEDE, START }

    /** How long a question stays open for an answer; after that the question and its panel are gone. */
    public static final long ANSWER_WINDOW_MS = 180_000;

    /** The routing before questions could wait their turn: a sentence that is not a side command supersedes. */
    public static Route route(boolean consentPending, Boolean consentReply, boolean stopWord, boolean busy,
                              boolean questionPending, boolean sideCommand) {
        return route(consentPending, consentReply, stopWord, busy, questionPending, sideCommand, false);
    }

    /**
     * consentReply: Replies.consent(text) while a consent is pending (null = not a yes/no or nothing pending).
     * A pending consent with another sentence keeps today's behavior: the waiting step is declined (the caller
     * does that) and the sentence routes on as if no consent were pending.
     * queueable: queueable(text, quick) — a question that waits for the running task instead of ending it.
     */
    public static Route route(boolean consentPending, Boolean consentReply, boolean stopWord, boolean busy,
                              boolean questionPending, boolean sideCommand, boolean queueable) {
        if (consentPending && consentReply != null) return Route.CONSENT;
        if (stopWord) {
            if (busy) return Route.STOP_TASK;              // never drops a question while a task is running
            return questionPending ? Route.DROP_QUESTION : Route.IDLE_STOP;
        }
        if (busy) return sideCommand ? Route.SIDE : queueable ? Route.QUEUE : Route.SUPERSEDE;
        return Route.START;
    }

    /**
     * Commands that may run beside a running task without stopping it: the volume, nothing else. Everything else
     * (暂停, 下一首, 回到桌面, 打开…) is about what the task is doing, so it supersedes the task as before.
     */
    public static boolean side(QuickCommand q) {
        if (q == null) return false;
        // the volume, and since 2026-11-06 the quick answers that only read (the clock, the calendar, the volume level,
        // 再说一遍, the weather, what is playing): before, any quick command superseded the running task, so asking
        // 「现在几点」 during a search ended the search
        switch (q.tool) {
            case "set_volume": case "adjust_volume": case "clock": case "weather": case "media_now_playing": case "self_check":
            case "stock_quote": case "exchange_rate": case "hot_list": case "gold_price": case "lottery": case "nba_games": case "football_games":
            case "world_clock": case "tv_guide": case "news": case "earthquake": case "fuel_price": case "fund_nav": case "history_today":
            case "movie_rating": case "bili_bangumi": case "weather_alert": case "almanac": case "movie_list": case "music_new": case "tv_usage":
                return true;
            default:
                return false;
        }
    }

    /** 「不对」「我说的是…」「换成…」「算了」: the user changes the request that is running, so it is replaced. */
    private static final Pattern CORRECTION = Pattern.compile(
            "^(?:不对|不是|错了|算了|不用了|不要了|换成|改成|重新)|我说的是|我是说|我问的是|我要的是|我想要的是|我想听的是|我想看的是|"
                    + "我想问的是|我想知道的是|不是这个|不是那个|应该是");
    /** A question about the world: what 星仪 answers in words, doing nothing on the TV. */
    private static final Pattern QUESTION = Pattern.compile(
            "什么|怎么样|怎样|多少|几点|几号|几度|几个|哪|谁|为什么|为啥|是不是|有没有|会不会|对不对|好不好|吗|[呢么嘛][？?]?$|[？?]$|"
                    + "^(?:我想知道|想知道|告诉我|问一下|问问|查一下|查查|帮我查|你知道)|天气|气温|新闻|动向|消息|比分|汇率|股价");
    /** Words that ask for something to be done on the TV, even when phrased as a question (「能不能放首歌」), and the apps
     * it would be done in (「B站上有什么新视频」). 「放的是什么」 asks about what is playing, so it is not one. */
    private static final Pattern ACTION = Pattern.compile(
            "放(?!的)|播(?!的|放的)|打开|关掉|关闭|关机|调|切到|切换|换到|安装|下载|删|设置|设个|提醒|记住|记一下|预约|定时|暂停|退出|"
                    + "返回|点开|[Bb]站|哔哩|爱奇艺|腾讯视频|优酷|芒果|抖音|网易云|应用|软件");
    /** 星仪自己的界面：右上角那块执行面板（用户叫它「HARNESS 面板」）、亮屏提示卡、弹窗。 */
    private static final Pattern OWN_UI = Pattern.compile("面板|提示卡|弹窗|HARNESS|星仪|你自己", Pattern.CASE_INSENSITIVE);
    /** 「为什么…」「怎么不弹了」: a question about why it is the way it is. */
    private static final Pattern WHY = Pattern.compile("为什么|为啥|怎么回事|怎么|咋");

    /**
     * A sentence that may wait for the running task instead of ending it: a question about the world (天气、新闻、谁、
     * 多少…) that is not a correction of what is running and asks nothing of the TV. 2026-11-05 on the TV: 「每次会覆盖
     * 上一次的结果」 — a question asked while the last one was still being searched cancelled it, and its answer was lost.
     */
    public static boolean queueable(String text, QuickCommand quick) {
        // 再说一遍 while an answer is being written or read: after it, it repeats that answer (review 2026-11-06: beside
        // the task it repeated the answer before, and the long one stopped being read)
        if (quick != null && "replay".equals(quick.tool)) return true;
        if (quick != null || text == null) return false;
        String s = text.trim();
        if (s.isEmpty() || correction(s)) return false;
        return QUESTION.matcher(s).find() && !ACTION.matcher(s).find();
    }

    /** A question about the world that names no app and asks nothing of the TV: answered in words, no app opens
     * (2026-11-05 on the TV: 「我想知道最近有什么新动向」 opened B站 to look up an UP主 from the habits).
     *
     * <p>Since 2026-11-11 also a why / how question about 星仪's own interface — 「为什么现在HARNESS面板没有打开」,
     * 「星仪的面板怎么不弹了」. It carries an action word (打开, 关), but there is nothing on the TV to open: 星仪 took it
     * for an app, opened Fluid Lab — whose screen is black — and left the user in front of it. AgentLoop then refuses
     * open_app / open_link for an asks-only request, so the model answers in words. */
    public static boolean asksOnly(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty()) return false;
        if (QUESTION.matcher(s).find() && !ACTION.matcher(s).find()) return true;
        return WHY.matcher(s).find() && OWN_UI.matcher(s).find();
    }

    /** The whole sentence is a request for music: an optional 网易云, an optional 我想 / 帮我 / 请, a verb, an optional
     * 一首 / 点, an optional 「<whose or which> 的」, then 歌 / 歌曲 / 音乐 / FM — and nothing after it but 「然后我要唱」.
     * What stands between the verb and 的 may not ask, negate or report (吗 什 谁 哪 不 别 没 是 说 过 了 着), compare
     * or count (像 怎 几 量 次), speak to 星仪 (你), or point at a place on the screen or in an app (里 上 下 左 右 边
     * 起 开 关): 「听起来像周杰伦的歌」「播放量最高的歌」「我想听你唱的歌」「点右边的音乐」「放QQ音乐里的歌」.
     * The whole sentence: songRequest matches all of it (matches(), not find()), so the pattern carries no anchors. */
    private static final Pattern SONG_REQUEST = Pattern.compile(
            "(?:网易云(?:音乐)?)?(?:请|帮我|给我|我想要|我想|我要|想|要)?再?(?:播放|放|听|唱|来|点)(?:一?[首点些])?"
            + "(?:[^，。！？,.!?吗呢么嘛啥什谁哪不别没是说过了着像怎几你里量次起上下左右边开关]{1,10}的)?"
            + "(?:歌曲?|音乐|fm)(?:(?:然后)?我(?:要|想|来)唱)?",
            Pattern.CASE_INSENSITIVE);
    /** How the sentence ended before normalise took its particles away: 「放歌了？」「听歌呢」 ask or tell, they do not
     * request. 吧 / 啊 / 呀 are how a request ends. */
    private static final Pattern ASKS_OR_TELLS = Pattern.compile("[了呢吗么嘛][。.！!？?~～]*$|[？?]$");
    /** Another player named: its songs are not NetEase's to start. */
    private static final Pattern OTHER_PLAYER = Pattern.compile("酷狗|酷我|咪咕|QQ音乐|qq音乐|B站|b站|哔哩|抖音|汽水音乐|喜马拉雅",
            Pattern.CASE_INSENSITIVE);

    /**
     * 「放首歌」「来首周杰伦的歌」「我要唱林俊杰的歌」「播放林俊杰热门的歌曲」「听音乐」: a plain request for music and nothing
     * else in the sentence. Only such a sentence brings NetEase up while the model plans (the engine's song prewarm).
     *
     * <p>2026-11-19, review of that prewarm before it reached the TV: its own pattern — a verb, up to ten characters,
     * a noun — took 「你会唱歌吗」「别放歌了」「现在放的是什么歌」「听说林俊杰发新歌了」 for requests, and of the ten
     * sentences it matched in the user's own 30 days six were complaints or remarks (「我要放歌的时候你肯定要开伴奏」).
     * Each of those would have opened an app nobody asked for (2026-11-05: 「不开任何软件」). A request this misses
     * (「放轻音乐」, a song named without 歌) only loses the head start; netease_play brings NetEase up as before.
     */
    public static boolean songRequest(String text) {
        if (text == null) return false;
        // what the review of 2026-11-19 ran against the first version and found taken for requests: a sentence that
        // asks or tells (「放歌了？」「听歌呢」), one that names another player (「播放酷狗的歌」), a verb that is no
        // verb of playing (「听起来像周杰伦的歌」「点右边的音乐」「播放量最高的歌」), 「我要唱歌的时候开伴奏」
        if (ASKS_OR_TELLS.matcher(text.trim()).find() || OTHER_PLAYER.matcher(text).find()) return false;
        String s = QuickCommand.normalise(text);
        return SONG_REQUEST.matcher(s).matches() || SING.matcher(s).matches();
    }

    /** 「我要唱跳楼机」「我要唱如果可以」: how this user asks for a song to sing along to, named without the word 歌.
     * The TV's perf log (2026-11-19): of the six real requests that waited 6-13 s for netease_play, five began
     * 我要唱 / 我想…唱. What follows 唱 is the song's name: not 歌 going on into something else (「我要唱歌的时候开伴奏」
     * — 「我要唱歌」 alone is SONG_REQUEST's), not 到 / 给 (「唱到几点」「唱给妈妈听」), and nothing in it asks, negates,
     * reports or sets a condition. */
    private static final Pattern SING = Pattern.compile(
            "(?:我要|我想要|我想|我来)唱(?!歌|首歌|一首|到|给|的)[^，。！？,.!?吗呢么嘛啥什谁哪不别没是说过了着给你但时候话记得把]{1,12}");

    private static final Pattern BILI = Pattern.compile("b站|bilibili|哔哩", Pattern.CASE_INSENSITIVE);
    /** A question whatever else the sentence holds: why, whether, how much, how long, which, who. */
    private static final Pattern BILI_ASKS = Pattern.compile(
            "为什么|为啥|怎么回事|是不是|有没有|会不会|能不能|多少|多久|多长时间|几个?小时|几分钟|什么|哪|谁|吗|[呢么嘛][？?]?$|[？?]$");
    /** 怎么 / 咋 ask how or why — unless the sentence opens or searches something (「B站搜怎么做红烧肉」). */
    private static final Pattern BILI_HOW = Pattern.compile("怎么|咋");
    private static final Pattern BILI_DOES = Pattern.compile("打开|搜|播放|播|放|看|进|去");
    private static final Pattern NOT_NOW = Pattern.compile("^(?:先)?(?:别|不要|不用|不想|不必)");

    /** B站 named at all (the song prewarm stays out of such a sentence). */
    public static boolean namesBili(String text) { return text != null && BILI.matcher(text).find(); }

    /**
     * A sentence that is going to use B站, so B站 may be started while the model plans (15 s cold on this TV, 1.3 GB):
     * it names B站 and neither asks about it (「为什么刚刚B站打开这么慢」「B站会员多少钱」「B站怎么这么卡」「看看哔哩哔哩
     * 能直接打开哪些页面」) nor says not to (「别打开B站」). The prewarm of 2026-11-05 started B站 for any sentence that
     * named it; of the three real sentences of 30 days that set it off, one wanted it.
     */
    public static boolean biliRequest(String text) {
        if (!namesBili(text)) return false;
        String s = QuickCommand.normalise(text);
        if (NOT_NOW.matcher(s).find() || BILI_ASKS.matcher(text.trim()).find()) return false;
        return !BILI_HOW.matcher(s).find() || BILI_DOES.matcher(s).find();
    }

    /** Words that point at the screen: 这个视频, 这是谁, 屏幕上, 现在放的. */
    private static final Pattern ON_SCREEN = Pattern.compile(
            "这个|这是|这部|这首|这集|这位|这人|这张|屏幕|画面|界面|上面(?:写|显示)|现在(?:放|播|看|显示)的|正在(?:放|播|看|显示)的");

    /** 「这个视频讲的什么」「这是谁」「屏幕上写的什么」: a question about what is on the screen. Its text then comes with
     * the request (environment.on_screen), as Gemini and Alexa+ see what is on the TV, instead of costing a screen_read
     * round (2026-11-06 overnight pass). */
    public static boolean aboutScreen(String text) {
        return asksOnly(text) && ON_SCREEN.matcher(text).find();
    }

    /** 「不对，是…」「我说的是…」: it changes the request before it. */
    public static boolean correction(String text) {
        return text != null && CORRECTION.matcher(text.trim()).find();
    }
}
