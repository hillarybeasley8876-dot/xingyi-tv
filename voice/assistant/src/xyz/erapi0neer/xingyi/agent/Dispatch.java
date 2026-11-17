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
