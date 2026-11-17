package xyz.erapi0neer.xingyi.agent;

import java.util.Collections;

/** Checks for Dispatch (job dispatch): what a sentence means when it arrives while 星仪 is busy, asking, or idle. */
final class DispatchChecks {
    private DispatchChecks() { }

    static void run() throws Exception {
        consent();
        stopWhileBusy();
        stopWithQuestion();
        stopWhenIdle();
        sideWhileBusy();
        supersede();
        start();
        sideCommands();
        window();
        queue();
        questions();
        ownPanel();
    }

    /** own-ui（2026-11-11 21:12）：「为什么现在HARNESS面板没有打开」问的是星仪自己的面板，不是电视上的一个应用——星仪
     * 却去 list_apps、explore_app、open_app Fluid Lab（它的屏幕是黑的），把用户留在黑屏前。一个关于星仪自己界面的
     * 为什么 / 怎么问句，只用话回答，什么都不打开。 */
    private static void ownPanel() {
        asks("为什么现在HARNESS面板没有打开", true);
        asks("星仪的面板怎么不弹了", true);
        asks("为什么面板关了", true);
        asks("harness面板为什么不显示", true);
        asks("提示卡怎么没了", true);
        asks("你自己的弹窗为什么不弹了", true);
        // what is not about 星仪's own interface, as before
        asks("为什么B站打不开", false);
        asks("打开星仪", false);
        asks("星仪帮我打开B站", false);
        asks("面板", false);
        asks("为什么", true); // a question with nothing to do on the TV: asks-only, as before
        asks(null, false);
        asks("", false);
    }

    private static void asks(String utterance, boolean want) {
        boolean got = Dispatch.asksOnly(utterance);
        AgentCoreTest.check(got == want, "asksOnly(" + utterance + ") is " + want + (got == want ? "" : "（得到 " + got + "）"));
    }

    /** One routing row: every argument spelled out, so a check says exactly which situation it proves. */
    private static void is(Dispatch.Route want, boolean consentPending, Boolean consentReply, boolean stopWord,
                           boolean busy, boolean questionPending, boolean sideCommand, String label) {
        Dispatch.Route got = Dispatch.route(consentPending, consentReply, stopWord, busy, questionPending, sideCommand);
        AgentCoreTest.check(got == want, label + " → " + want + (got == want ? "" : "（得到 " + got + "）"));
    }

    // ---- a pending consent ------------------------------------------------------------------------

    private static void consent() {
        is(Dispatch.Route.CONSENT, true, Boolean.TRUE, false, true, false, false, "确认 while a consent is pending");
        is(Dispatch.Route.CONSENT, true, Boolean.FALSE, false, true, false, false, "算了 while a consent is pending");
        // 好的 beats a stop word: it is the answer to the question that is on screen.
        is(Dispatch.Route.CONSENT, true, Boolean.TRUE, true, true, false, false, "好的 while a consent is pending and busy");
        is(Dispatch.Route.CONSENT, true, Boolean.FALSE, false, false, true, false, "算了 while a consent is pending, not busy");
        is(Dispatch.Route.CONSENT, true, Boolean.TRUE, false, true, false, true, "确认 while a consent is pending and a side command");
        // Any other sentence declines the waiting step (the engine does that) and routes on as usual.
        is(Dispatch.Route.SUPERSEDE, true, null, false, true, false, false, "another sentence while a consent is pending");
        is(Dispatch.Route.SIDE, true, null, false, true, false, true, "音量调大 while a consent is pending");
        is(Dispatch.Route.STOP_TASK, true, null, true, true, false, false, "停 while a consent is pending");
        is(Dispatch.Route.START, true, null, false, false, false, false, "another sentence while a consent is pending, idle");
        is(Dispatch.Route.DROP_QUESTION, true, null, true, false, true, false, "停 while a consent is pending and a question is open");
        // No consent on screen: a yes/no is just a sentence like any other.
        is(Dispatch.Route.SUPERSEDE, false, Boolean.TRUE, false, true, false, false, "好的 with no consent pending, busy");
        is(Dispatch.Route.START, false, Boolean.TRUE, false, false, false, false, "好的 with no consent pending, idle");
        is(Dispatch.Route.IDLE_STOP, false, Boolean.FALSE, true, false, false, false, "停 with no consent pending, idle");
    }

    // ---- 停 ----------------------------------------------------------------------------------------

    private static void stopWhileBusy() {
        is(Dispatch.Route.STOP_TASK, false, null, true, true, false, false, "停 while a task is running");
        // A running task is stopped, never is the pending question dropped from under it.
        is(Dispatch.Route.STOP_TASK, false, null, true, true, true, false, "停 while busy and a question is open");
        is(Dispatch.Route.STOP_TASK, false, null, true, true, false, true, "停 while busy, said as a side command");
        is(Dispatch.Route.STOP_TASK, false, null, true, true, true, true, "停 while busy, question open, side command");
    }

    private static void stopWithQuestion() {
        is(Dispatch.Route.DROP_QUESTION, false, null, true, false, true, false, "停 while a question is open");
        is(Dispatch.Route.DROP_QUESTION, false, null, true, false, true, true, "停 while a question is open, side command");
    }

    private static void stopWhenIdle() {
        is(Dispatch.Route.IDLE_STOP, false, null, true, false, false, false, "停 with nothing running");
        is(Dispatch.Route.IDLE_STOP, false, null, true, false, false, true, "停 with nothing running, side command");
    }

    // ---- beside a running task --------------------------------------------------------------------

    private static void sideWhileBusy() {
        is(Dispatch.Route.SIDE, false, null, false, true, false, true, "音量调大 while a task is running");
        is(Dispatch.Route.SIDE, false, null, false, true, true, true, "音量调大 while busy and a question is open");
        // Nothing is running: there is nothing to run beside, so it is an ordinary request.
        is(Dispatch.Route.START, false, null, false, false, false, true, "音量调大 while idle");
        is(Dispatch.Route.START, false, null, false, false, true, true, "音量调大 while a question is open, not busy");
    }

    private static void supersede() {
        is(Dispatch.Route.SUPERSEDE, false, null, false, true, false, false, "a new request while a task is running");
        is(Dispatch.Route.SUPERSEDE, false, null, false, true, true, false, "a new request while busy and a question is open");
    }

    private static void start() {
        is(Dispatch.Route.START, false, null, false, false, false, false, "a request with nothing running");
        is(Dispatch.Route.START, false, null, false, false, true, false, "an answer to an open question");
    }

    // ---- which commands may run beside a task -----------------------------------------------------

    /** The real sentence, matched the way the engine matches it beside a task (no app labels to open). */
    private static void beside(String utterance, boolean want) {
        boolean got = Dispatch.side(QuickCommand.match(utterance, Collections.<String>emptySet()));
        AgentCoreTest.check(got == want, utterance + (want ? " runs beside a task" : " does not run beside a task")
                + (got == want ? "" : "（得到 " + got + "）"));
    }

    private static void sideCommands() {
        beside("音量调大一点", true);
        beside("音量调小一点", true);
        beside("声音调大", true);
        beside("音量调到20", true);
        beside("音量调到三十五", true);
        beside("静音", true);
        beside("取消静音", true);
        beside("小爱同学，音量调到二十吧。", true);
        beside("暂停", false);
        beside("继续播放", false);
        beside("下一首", false);
        beside("停止播放", false);
        beside("回到桌面", false);
        beside("返回", false);
        beside("打开相册", false);
        beside("HDMI1", false);
        beside("30分钟后关机", false);
        // Not a command at all: nothing to run beside the task.
        beside("播放周杰伦的晴天", false);
        beside("打开B站搜索星辰亻旅行者", false);
        beside("", false);
        AgentCoreTest.check(!Dispatch.side(null), "side(null) is false");
    }

    // ---- a question while a task is running waits its turn ----------------------------------------------

    private static void queue() {
        Dispatch.Route got = Dispatch.route(false, null, false, true, false, false, true);
        AgentCoreTest.check(got == Dispatch.Route.QUEUE, "a question while a task is running waits for it → QUEUE（得到 " + got + "）");
        got = Dispatch.route(false, null, false, true, true, false, true);
        AgentCoreTest.check(got == Dispatch.Route.QUEUE, "a question while busy and a question is open → QUEUE（得到 " + got + "）");
        // Nothing running: a question just starts.
        got = Dispatch.route(false, null, false, false, false, false, true);
        AgentCoreTest.check(got == Dispatch.Route.START, "a question with nothing running → START（得到 " + got + "）");
        // 停 and the volume keep their routes whatever the sentence looks like.
        got = Dispatch.route(false, null, true, true, false, false, true);
        AgentCoreTest.check(got == Dispatch.Route.STOP_TASK, "停 while busy is never queued（得到 " + got + "）");
        got = Dispatch.route(false, null, false, true, false, true, true);
        AgentCoreTest.check(got == Dispatch.Route.SIDE, "the volume while busy still runs beside（得到 " + got + "）");
        got = Dispatch.route(true, Boolean.TRUE, false, true, false, false, true);
        AgentCoreTest.check(got == Dispatch.Route.CONSENT, "好的 while a consent is pending is the consent（得到 " + got + "）");
    }

    /** The real sentence, the way the engine decides it while busy. */
    private static void waits(String utterance, boolean want) {
        boolean got = Dispatch.queueable(utterance, QuickCommand.match(utterance, Collections.<String>emptySet()));
        AgentCoreTest.check(got == want, utterance + (want ? " waits for the running task" : " replaces the running task")
                + (got == want ? "" : "（得到 " + got + "）"));
    }

    private static void questions() {
        // 「明天上海天气怎么样」 is a quick read since 2026-11-10 (another city, any day): it is answered beside the running
        // task instead of waiting for it; a question about that weather still waits
        waits("明天上海天气怎么样", false);
        beside("明天上海天气怎么样", true);
        waits("明天上海的天气适合出去玩吗", true);
        waits("OpenAI最近有什么新动向", true);
        waits("那谷歌呢", true);
        waits("今天早上凯尔特人谁赢了", true);
        // a quick exchange rate since 2026-11-06: it is answered beside the task, at once
        beside("一美元能换多少人民币", true);
        waits("一美元能换多少欧元再换回来亏多少", true);
        waits("杜甫是哪个朝代的诗人", true);
        waits("姚润浩出什么事了？", true);
        waits("我想知道最近的新闻", true);
        // a quick question answers beside the task (Dispatch.side), it neither waits nor replaces it
        waits("现在放的是什么歌", false);
        beside("现在放的是什么歌", true);
        beside("现在几点", true);
        beside("明天天气怎么样", true);
        beside("今天农历几号", true);
        beside("再说一遍", false);
        waits("再说一遍", true);
        beside("星仪自检", true);
        beside("早上好", true);
        beside("关电视", false);
        beside("回到桌面", false);
        waits("帮我看看明天天气怎么样", true);
        waits("搜一下OpenAI最近的新闻", true);
        // A correction replaces what is running, however it is phrased (2026-11-05 on the TV).
        waits("我说的是想听OpenAI这个公司的", false);
        waits("我想听的是OPEN AI最新的动向", false);
        waits("不对，是叠纸的那个姚润浩", false);
        waits("算了，查一下明天的天气", false);
        // Asking for something to be done is a new request, as before.
        waits("能不能放首周杰伦的歌", false);
        waits("打开B站看看有什么新视频", false);
        waits("帮我搜一下猫和老鼠", false);
        waits("B站上有什么好看的新视频", false);
        waits("明天早上7点提醒我开会好吗", false);
        waits("音量调大一点", false);
        waits("暂停", false);
        waits("回到桌面", false);
        // Not a question at all.
        waits("播放周杰伦的晴天", false);
        waits("好的", false);
        waits("", false);
        AgentCoreTest.check(!Dispatch.queueable(null, null), "queueable(null) is false");
        AgentCoreTest.check(Dispatch.correction("我是说那个公司"), "我是说… is a correction");
        AgentCoreTest.check(!Dispatch.correction("今天天气怎么样"), "a plain question is not a correction");
    }

    private static void window() {
        AgentCoreTest.check(Dispatch.ANSWER_WINDOW_MS == 180_000L, "the answer window is 180 s");
    }
}
