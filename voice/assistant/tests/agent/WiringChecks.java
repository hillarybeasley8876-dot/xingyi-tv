package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** The pure parts of the TV wiring: search budget, the observer's sentence, player pages, Turn.round (job tv-wiring). */
final class WiringChecks {
    private static final String SAME_ROUND = "这一轮已经联网查过了，先看上一次的结果再决定。";
    private static final String TASK_DONE = "这个任务已经联网查了 2 次，用已经查到的结果回答；查不到就如实告诉用户，不要再查。";

    static void run() throws Exception {
        searchBudget();
        observation();
        playerPages();
        turnRound();
    }

    static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    // ---- SearchBudget ----------------------------------------------------------------------------

    static void searchBudget() {
        SearchBudget b = new SearchBudget();
        check(b.check(1) == null, "the first search of the task is allowed");
        check(SAME_ROUND.equals(b.check(1)), "a second search in the same round is refused, with the round sentence");
        check(b.check(2) == null, "a new round may search again");
        check(TASK_DONE.equals(b.check(3)), "the third search of the task is refused, with the task sentence");
        check(TASK_DONE.equals(b.check(4)), "and it stays refused in later rounds");
        check(b.used() == 2, "only the two searches that ran are counted: " + b.used());

        SearchBudget second = new SearchBudget();
        check(second.check(1) == null && second.check(2) == null, "each task has its own budget");
        check(second.used() == 2, "a fresh budget starts empty");

        // a conclusion closes the budget; nothing found leaves the second try (2026-11-05: NBA searched twice)
        SearchBudget found = new SearchBudget();
        check(found.check(1) == null, "the first search runs");
        found.result("北京时间今天上午有14场NBA常规赛，焦点是湖人对国王。来源：NBA中国。", true);
        check(SearchBudget.FOUND.equals(found.check(2)) && found.used() == 1, "a search that found its answer closes the budget");
        SearchBudget empty = new SearchBudget();
        empty.check(1);
        empty.result("没有找到明确的结果。", true);
        check(empty.check(2) == null && empty.used() == 2, "nothing found: one more try");
        check(SearchBudget.answered("上海今天多云，18 度", true), "a conclusion counts as answered");
        check(!SearchBudget.answered("上海今天多云", false) && !SearchBudget.answered("  ", true) && !SearchBudget.answered(null, true),
                "no search, a blank or a missing answer does not");
        check(!SearchBudget.answered("暂无该航班的实时信息", true), "暂无 / 查不到 … are empty-handed");
    }

    // ---- Observation ------------------------------------------------------------------------------

    static void observation() {
        check(Observation.describe(null, "示例小镇").isEmpty(), "no app: nothing is claimed about the screen");
        check(Observation.describe("", "示例小镇").isEmpty(), "an empty app label says nothing");
        check(Observation.describe("   ", "示例小镇").isEmpty(), "a blank app label says nothing");
        check("前台是哔哩哔哩".equals(Observation.describe("哔哩哔哩", null)), "app only, no session");
        check("前台是哔哩哔哩".equals(Observation.describe("哔哩哔哩", "")), "app only, empty title");
        check("前台是哔哩哔哩，正在播放《示例小镇》".equals(Observation.describe("哔哩哔哩", "示例小镇")),
                "app and title in one speakable line");
        check("前台是网易云音乐，正在播放《不为谁而作的歌》".equals(Observation.describe("网易云音乐", "不为谁而作的歌 ")),
                "the title is trimmed");
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 31; i++) longTitle.append('金');
        String cut = Observation.describe("哔哩哔哩", longTitle.toString());
        check(cut.equals("前台是哔哩哔哩，正在播放《" + longTitle.substring(0, 30) + "…》"),
                "a title of 31 chars is cut to 30 plus …: " + cut);
        check(Observation.describe("哔哩哔哩", longTitle.substring(0, 30)).contains(longTitle.substring(0, 30) + "》"),
                "a title of exactly 30 chars is left alone");
        String line = Observation.describe("哔哩哔哩", "示例小镇");
        check(!line.contains("tv.danmaku.bili") && !line.contains("Activity") && !line.contains("."),
                "the line never carries a package or class name: " + line);
    }

    // ---- PlayerPages ------------------------------------------------------------------------------

    static void playerPages() {
        check(PlayerPages.isPlayer("tv.danmaku.bili", "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"),
                "Bilibili's real player page is a player");
        check(PlayerPages.isPlayer("tv.danmaku.bili", "tv.danmaku.bili.ui.VideoDetailsActivity"),
                "Bilibili's VideoDetailsActivity is a player");
        check(PlayerPages.isPlayer("tv.danmaku.bili", ".PlayerActivity"), "a relative PlayerActivity is a player");
        check(PlayerPages.isPlayer("com.netease.cloudmusic.tv", "com.netease.cloudmusic.tv.activity.PlayerActivityV2"),
                "NetEase's player page is a player");
        check(!PlayerPages.isPlayer("tv.danmaku.bili", ".MainActivityV2"), "Bilibili's home page is not a player");
        check(!PlayerPages.isPlayer("tv.danmaku.bili", "tv.danmaku.bili.ui.SearchResultActivity"),
                "Bilibili's search results are not a player");
        check(!PlayerPages.isPlayer("xyz.erapi0neer.orrery", "xyz.erapi0neer.orrery.MainActivity"),
                "the Orrery desktop is not a player");
        check(!PlayerPages.isPlayer("com.netease.cloudmusic.tv", ".MainActivity"), "NetEase's home page is not a player");
        check(!PlayerPages.isPlayer(null, "PlayerActivity") && !PlayerPages.isPlayer("tv.danmaku.bili", null),
                "a missing package or activity is not a player");
        check(!PlayerPages.isPlayer("tv.danmaku.bili", ""), "an unknown activity is not a player");
    }

    // ---- Turn.round -------------------------------------------------------------------------------

    /** A read-only tool that remembers which model round it was called in. */
    static final class Rounds implements Tool {
        final List<Integer> seen = new ArrayList<>();
        @Override public String name() { return "screen_read"; }
        @Override public Gate gate(JSONObject args, Turn turn) { return Gate.read(); }
        @Override public String describe(JSONObject args, Turn turn) { return "看一下屏幕"; }
        @Override public JSONObject run(JSONObject args, Turn turn) throws Exception {
            seen.add(turn.round());
            return new JSONObject().put("ok", true).put("status", "observed").put("message", "已观察");
        }
    }

    static void turnRound() throws Exception {
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("看两眼", "看一眼", "再看一眼"), "screen_read", AgentCoreTest.o())
                .calls("screen_read", AgentCoreTest.o())
                .calls("finish", AgentCoreTest.o("status", "done", "reply", "看完了"));
        Rounds read = new Rounds();
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, read)
                .run(new AgentLoop.Request("看两眼屏幕", "test"));
        check(read.seen.size() == 2, "the read ran once per round: " + read.seen);
        check(read.seen.get(0) == 1, "the call batched with the plan is round 1: " + read.seen);
        check(read.seen.get(1) == 2, "the next round is round 2: " + read.seen);
        check(t.state == Ledger.State.DONE, "the task finished: " + t.state);
    }
}
