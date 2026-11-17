package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * bili_videos (2026-11-07): an UP主's newest, most played or one kind of video from B站's search, and the loop around
 * it (the list, then open_link; a video seen playing is the answer). Fixtures saved from the search that night.
 */
final class BiliChecks {
    private BiliChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    /** 2026-11-07 02:00 in Beijing. */
    private static final long NOW = 1793988000000L;

    static void run() {
        try {
            search();
            speech();
            liveName();
            loop();
            loading();
            grounded();
        } catch (Exception e) {
            check(false, "bili checks threw " + e);
        }
    }

    private static final List<String> asked = new ArrayList<>();

    /** The search as the fixtures saw it; what was asked for is kept in `asked`. */
    private static Feeds.Fetch fake() throws IOException {
        Map<String, String> pages = new HashMap<>();
        pages.put(BiliVideos.userUrl("影视飓风"), fixture("bili-user-ysjf.json"));
        pages.put(BiliVideos.videoUrl("影视飓风", "latest"), fixture("bili-video-ysjf-latest.json"));
        pages.put(BiliVideos.videoUrl("影视飓风", "most_played"), fixture("bili-video-ysjf-click.json"));
        pages.put(BiliVideos.videoUrl("影视飓风", "relevance"), fixture("bili-video-ysjf-latest.json"));
        pages.put(BiliVideos.userUrl("星辰亻旅行者"), fixture("bili-user-xclxz.json"));
        pages.put(BiliVideos.videoUrl("星辰亻旅行者", "latest"), fixture("bili-video-xclxz-latest.json"));
        pages.put(BiliVideos.videoUrl("星辰亻旅行者", "most_played"), fixture("bili-video-xclxz-click.json"));
        pages.put(BiliVideos.videoUrl("星辰亻旅行者", "relevance"), fixture("bili-video-xclxz-rank.json"));
        // the name and the word together: others' videos only (none of theirs in that search)
        pages.put(BiliVideos.videoUrl("星辰亻旅行者 加速世界", "relevance"), fixture("bili-video-xclxz-mad.json"));
        pages.put(BiliVideos.userUrl("何同学"), fixture("bili-user-htx.json"));
        pages.put(BiliVideos.userUrl("星辰人旅行者"), fixture("bili-user-none.json"));
        pages.put(BiliVideos.videoUrl("黑神话", "relevance"), fixture("bili-video-hsh.json"));
        asked.clear();
        return (url, charset, timeoutMs) -> {
            asked.add(url);
            String p = pages.get(url);
            if (p == null) throw new IOException("no fixture for " + url);
            return p;
        };
    }

    static void search() throws Exception {
        BiliVideos.Found ys = BiliVideos.find(fake(), "影视飓风", "", "latest", 3);
        check(ys.user != null && ys.user.mid == 946974 && ys.videos.size() == 3 && asked.size() == 1,
                "an UP's three newest: their card, one request (" + asked.size() + ")");
        check(ys.videos.get(0).title.equals("动态视频｜2026，它来了…") && ys.videos.get(0).link().equals("bilibili://video/117568757109187"),
                "newest first, with its link: " + ys.videos.get(0).title);
        BiliVideos.Found more = BiliVideos.find(fake(), "影视飓风", "", "latest", 5);
        check(asked.size() == 2 && more.videos.size() == 3 && more.videos.get(0).aid == 117568757109187L,
                "five newest: the card and the search on their name (whose twenty newest hold one of theirs)");
        boolean own = true, newest = true;
        for (int i = 0; i < more.videos.size(); i++) {
            own &= more.videos.get(i).mid == 946974;
            if (i > 0) newest &= more.videos.get(i - 1).pubdate >= more.videos.get(i).pubdate;
        }
        check(own && newest, "only their own uploads (19 of the search's 20 newest are others'), newest first");

        BiliVideos.Found top = BiliVideos.find(fake(), "影视飓风", "", "most_played", 3);
        check(asked.size() == 2 && top.videos.get(0).views == 17120731 && top.videos.get(0).title.equals("选哪个？iPhone 18 Pro&Duo深度上手"),
                "most played: theirs, not 飓多多StormCrew's 2310 万 one; &amp; read as &: " + top.videos.get(0).title);
        boolean desc = true;
        for (int i = 1; i < top.videos.size(); i++) desc &= top.videos.get(i - 1).views >= top.videos.get(i).views && top.videos.get(i).mid == 946974;
        check(desc, "by plays, theirs only");

        BiliVideos.Found asKeyword = BiliVideos.find(fake(), "", "影视飓风", "latest", 3);
        check(asKeyword.user != null && asKeyword.user.name.equals("影视飓风") && asKeyword.videos.get(0).aid == 117568757109187L
                && asked.equals(Arrays.asList(BiliVideos.videoUrl("影视飓风", "latest"), BiliVideos.userUrl("影视飓风"))),
                "an UP's name given as a keyword: still their own uploads, not others' videos about them");

        BiliVideos.Found named = BiliVideos.find(fake(), "", "影视飓风", "", 3);
        check(named.user != null && "latest".equals(named.order), "an UP's name as a keyword, no order said: their newest, not the topic's best match: " + named.order);

        BiliVideos.Found he = BiliVideos.find(fake(), "何同学", "", "latest", 1);
        check(he.user != null && he.user.name.equals("老师好我叫何同学") && he.videos.get(0).title.startsWith("你可以这么用"),
                "何同学 is 老师好我叫何同学 (the search's first that holds the name)");
        check(he.user.space().equals("bilibili://space/163637592"), "their page's link: " + he.user.space());
        check(!he.user.live && he.user.liveLink().equals("bilibili://live/5867219"), "not live; their room: " + he.user.liveLink());

        BiliVideos.Found none = BiliVideos.find(fake(), "星辰人旅行者", "", "latest", 3);
        check(none.user == null && none.videos.isEmpty() && none.asked.equals("星辰人旅行者"), "a misheard name with no UP: nothing, not a stranger's videos");

        BiliVideos.Found mad = BiliVideos.find(fake(), "星辰亻旅行者", "MAD", "latest", 3);
        boolean all = !mad.videos.isEmpty();
        for (BiliVideos.Video v : mad.videos) all &= (v.title + "," + v.tags).toUpperCase().contains("MAD") && v.mid == 14770463;
        check(all && mad.videos.size() == 4 && mad.videos.get(0).aid == 112039605214353L, "their MADs: by title or tag, newest first: " + mad.videos.size());

        BiliVideos.Found missing = BiliVideos.find(fake(), "星辰亻旅行者", "加速世界", "relevance", 3);
        check(missing.user != null && missing.videos.isEmpty() && asked.size() == 3, "a word none of theirs has: the name and the word searched together too, then nothing");

        BiliVideos.Found topic = BiliVideos.find(fake(), "", "黑神话", "", 3);
        check(topic.user == null && topic.videos.size() == 20 && topic.videos.get(0).up.equals("爱玩的Kaka") && !topic.videos.get(0).title.contains("<em")
                && "relevance".equals(topic.order), "a topic: the search's own order, highlight tags gone");

        List<BiliVideos.User> users = new ArrayList<>(BiliVideos.users(fixture("bili-user-ysjf.json")));
        check(BiliVideos.pickUser(users, "影视飓凤") != null && BiliVideos.pickUser(users, "影视飓凤").mid == 946974, "one character off still finds the UP");
        users.add(0, new BiliVideos.User("示例小号", 7, 3, 1, Collections.<BiliVideos.Video>emptyList()));
        check(BiliVideos.pickUser(users, "示例小号").mid == 7, "punctuation in a name does not count");
        check(BiliVideos.pickUser(users, "猫") == null, "one character holds too many names");

        JSONArray rows = BiliVideos.rows(BiliVideos.find(fake(), "星辰亻旅行者", "", "latest", 3), NOW);
        check(rows.getJSONObject(0).getString("duration").equals("3:05") && rows.getJSONObject(0).getString("link").equals("bilibili://video/111528431382594")
                && rows.getJSONObject(0).getString("date").equals("前天"), "rows: 3:5 is 3:05, the link, the day: " + rows.getJSONObject(0));
    }

    static void speech() throws Exception {
        String ys = BiliVideos.speak(BiliVideos.find(fake(), "影视飓风", "", "latest", 3), 3, NOW);
        check(ys.equals("影视飓风最新的 3 个视频：第一，《动态视频｜2026，它来了…》，昨天，13 万播放；第二，《真的需要摸吗？MacBook Pro PROLED XDR上手》，前天，162 万播放；"
                + "第三，《动态视频｜苹果的触屏MacBook Pro来了，你会买么？》，10 月 29 日，145 万播放"), "three newest: " + ys);
        String top = BiliVideos.speak(BiliVideos.find(fake(), "星辰亻旅行者", "", "most_played", 1), 1, NOW);
        check(top.equals("星辰亻旅行者播放最多的视频是《示例作品14》，125 万播放，2019 年 12 月 4 日发的"),
                "the most played one, cut between words: " + top);
        String topic = BiliVideos.speak(BiliVideos.find(fake(), "", "黑神话", "relevance", 2), 2, NOW);
        check(topic.equals("B站上「黑神话」相关的 2 个视频：第一，《【4K】黑神话悟空全成就保姆级攻略【已完结】》，爱玩的Kaka，2024 年 12 月 27 日，796 万播放；"
                + "第二，《黑神话策划被泡面砸脸，二十多名玩家甚至摸到小区门口》，子休狐，昨天，2 万播放"),
                "a topic's videos say whose they are: " + topic);
        String mad = BiliVideos.speak(BiliVideos.find(fake(), "星辰亻旅行者", "MAD", "latest", 2), 2, NOW);
        check(mad.startsWith("星辰亻旅行者最新的 2 个MAD视频：第一，《【示例PV】作品18》，10 月 12 日，3039 次播放；第二，"), "their MADs: " + mad);
        check(BiliVideos.speak(BiliVideos.find(fake(), "星辰人旅行者", "", "latest", 3), 3, NOW).equals("B站上没找到叫「星辰人旅行者」的 UP 主"), "no such UP");
        check(BiliVideos.speak(BiliVideos.find(fake(), "星辰亻旅行者", "加速世界", "relevance", 3), 3, NOW).equals("星辰亻旅行者的视频里没找到「加速世界」"), "none of that kind");

        // live now: said first (the fixture's 何同学 with is_live set)
        Feeds.Fetch live = (url, cs, t) -> {
            if (url.equals(BiliVideos.userUrl("何同学"))) return fixture("bili-user-htx.json").replaceFirst("\"is_live\":0", "\"is_live\":1");
            throw new IOException("no fixture for " + url);
        };
        String onAir = BiliVideos.speak(BiliVideos.find(live, "何同学", "", "latest", 1), 1, NOW);
        check(onAir.startsWith("老师好我叫何同学现在正在直播。老师好我叫何同学最新的视频是《你可以这么用。"), "live now, then the newest: " + onAir);
        check(BiliVideos.speakLive(BiliVideos.find(live, "何同学", "", "latest", 1), NOW).equals("何同学正在直播"), "asked whether live: live, said as the user said the name");
        String off = BiliVideos.speakLive(BiliVideos.find(fake(), "影视飓风", "", "latest", 1), NOW);
        check(off.equals("影视飓风现在没在直播；最新的视频是昨天发的《动态视频｜2026，它来了…》"), "asked whether live: not, and the newest: " + off);
        check(BiliVideos.LIVE_ASKED.matcher("影视飓风现在在直播吗").find() && BiliVideos.LIVE_ASKED.matcher("何同学开播了没").find()
                && !BiliVideos.LIVE_ASKED.matcher("放影视飓风最新的视频").find(), "which requests ask whether an UP is live");

        JSONObject read = BiliVideos.readOut(BiliVideos.find(fake(), "影视飓风", "", "latest", 3), 3, NOW);
        check(read.getString("what").equals("影视飓风最新的视频") && read.getJSONArray("items").length() == 3
                && read.getJSONArray("items").getJSONObject(1).getInt("n") == 2
                && read.getJSONArray("items").getJSONObject(1).getString("link").equals("bilibili://video/117563807766583"), "the list as read, for 「放第二个」");
        check(BiliVideos.describe("影视飓风", "", "").equals("查B站上影视飓风最新的视频") && BiliVideos.describe("星辰亻旅行者", "MAD", "most_played").equals("查B站上星辰亻旅行者播放最多的MAD视频")
                && BiliVideos.describe("", "黑神话", null).equals("查B站上「黑神话」的视频"), "what the HUD shows: " + BiliVideos.describe("星辰亻旅行者", "MAD", "most_played"));

        // days in Beijing's words
        long s = NOW / 1000;
        check(BiliVideos.when(s - 3600, NOW).equals("今天") && BiliVideos.when(s - 3 * 3600, NOW).equals("昨天") && BiliVideos.when(s - 27 * 3600, NOW).equals("前天")
                && BiliVideos.when(s - 10 * 86400, NOW).equals("10 月 28 日") && BiliVideos.when(1575426840L, NOW).equals("2019 年 12 月 4 日"),
                "when: " + BiliVideos.when(s - 3 * 3600, NOW) + " / " + BiliVideos.when(s - 10 * 86400, NOW));
        check(BiliVideos.cut("短标题").equals("短标题") && BiliVideos.cut("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十").length() == 36, "cut");
    }

    /** The search as the fixtures have it, with that spelling of a name going to that card (the full name, a misheard one). */
    private static Feeds.Fetch card(String said, String fixtureName) throws IOException {
        Feeds.Fetch rest = fake();
        String url = BiliVideos.userUrl(said), page = fixture(fixtureName);
        return (u, cs, t) -> u.equals(url) ? page : rest.get(u, cs, t);
    }

    /** 「何同学在直播吗」 (2026-11-10 sweep): the channel's full name read straight into the sentence sounded like
     * 「老师好，我叫何同学，现在没在直播」. The name the user said is used when it is part of the channel's. */
    static void liveName() throws Exception {
        Feeds.Fetch onAir = (url, cs, t) -> {
            if (url.equals(BiliVideos.userUrl("何同学"))) return fixture("bili-user-htx.json").replaceFirst("\"is_live\":0", "\"is_live\":1");
            throw new IOException("no fixture for " + url);
        };
        BiliVideos.Found he = BiliVideos.find(fake(), "何同学", "", "latest", 1);
        check(he.user.name.equals("老师好我叫何同学") && BiliVideos.saidName(he).equals("何同学"), "何同学 in 老师好我叫何同学: the asked name: " + BiliVideos.saidName(he));
        String off = BiliVideos.speakLive(he, NOW);
        check(off.startsWith("何同学现在没在直播；最新的视频是"), "not live, said with the asked name: " + off);
        check(BiliVideos.speakLive(BiliVideos.find(onAir, "何同学", "", "latest", 1), NOW).equals("何同学正在直播"), "live, said with the asked name");

        BiliVideos.Found full = BiliVideos.find(card("老师好我叫何同学", "bili-user-htx.json"), "老师好我叫何同学", "", "latest", 1);
        check(full.user != null && BiliVideos.saidName(full).equals("老师好我叫何同学"), "the whole name said: the channel's own");
        check(BiliVideos.speakLive(full, NOW).startsWith("老师好我叫何同学现在没在直播；"), "and it is read as it is: " + BiliVideos.speakLive(full, NOW));

        BiliVideos.Found ys = BiliVideos.find(fake(), "影视飓风", "", "latest", 1);
        check(BiliVideos.saidName(ys).equals("影视飓风"), "the same name: the channel's own");
        // a misheard name is not part of the channel's: the channel's own name is said, not the wrong word
        BiliVideos.Found misheard = BiliVideos.find(card("影视飓凤", "bili-user-ysjf.json"), "影视飓凤", "", "latest", 1);
        check(misheard.user != null && BiliVideos.saidName(misheard).equals("影视飓风"), "a name that is not part of the channel's: the channel's own: " + BiliVideos.saidName(misheard));
        check(BiliVideos.speakLive(misheard, NOW).startsWith("影视飓风现在没在直播；最新的视频是"), "and it is read as it is: " + BiliVideos.speakLive(misheard, NOW));
        // a topic (no UP主) is not touched
        check(BiliVideos.speakLive(BiliVideos.find(fake(), "", "黑神话", "relevance", 1), NOW).startsWith("B站上「黑神话」"), "a topic still reads as a topic");
        // the list answer is unchanged: the UP's own name there (the newest video's title follows it)
        check(BiliVideos.speak(he, 1, NOW).startsWith("老师好我叫何同学最新的视频是"), "the list answer keeps the channel's name: " + BiliVideos.speak(he, 1, NOW));
    }

    /** bili_videos' up: a name from the user, not 关于用户's UP put in its place (2026-11-07 on the TV, once in eight). */
    static void grounded() throws Exception {
        String profile = "- “星辰旅行者”指 B 站 UP 主“星辰亻旅行者”（中间有单人旁“亻”），搜索时用带“亻”的写法。";
        AgentCoreTest.Fake swapped = videos();
        AgentCoreTest.Script swap = new AgentCoreTest.Script()
                .calls("bili_videos", AgentCoreTest.o("up", "星辰亻旅行者", "final", true))
                .calls("bili_videos", AgentCoreTest.o("up", "何同学", "final", true))
                .text("这一轮不该被问到");
        AgentLoop.Request r = new AgentLoop.Request("何同学最新的视频是什么", "test");
        r.profile = profile;
        AgentCoreTest.loop(swap, new AgentCoreTest.Events(), (w, l) -> true, swapped).run(r);
        check(swapped.runs.size() == 1 && "何同学".equals(swapped.runs.get(0).optString("up")) && swap.seen.size() == 2,
                "关于用户's UP in place of the one asked for is turned back, the asked one runs: " + swapped.runs);

        // a 我 that is not 「我的」: 关于用户's UP is still not the one asked for (the review, 2026-11-07)
        AgentCoreTest.Fake helped = videos();
        AgentCoreTest.Script help = new AgentCoreTest.Script()
                .calls("bili_videos", AgentCoreTest.o("up", "星辰亻旅行者"))
                .calls("bili_videos", AgentCoreTest.o("up", "何同学"))
                .text("好");
        AgentLoop.Request hr = new AgentLoop.Request("帮我放何同学最新的视频", "test");
        hr.profile = profile;
        AgentCoreTest.loop(help, new AgentCoreTest.Events(), (w, l) -> true, helped).run(hr);
        check(helped.runs.size() == 1 && "何同学".equals(helped.runs.get(0).optString("up")), "帮我… is no 我的: " + helped.runs);

        // an UP this task found (on the hot list) counts as said
        AgentCoreTest.Fake hot = new AgentCoreTest.Fake("hot_list", Tool.Gate.read());
        hot.reply = AgentCoreTest.o("ok", true, "status", "observed", "answer", "B站热门前 1 个：第一，《真的需要摸吗》，影视飓风，68 万播放",
                "videos", new JSONArray().put(AgentCoreTest.o("title", "真的需要摸吗", "up", "影视飓风")));
        AgentCoreTest.Fake fromHot = videos();
        AgentCoreTest.loop(new AgentCoreTest.Script().calls("hot_list", AgentCoreTest.o("source", "B站")).calls("bili_videos", AgentCoreTest.o("up", "影视飓风")).text("好"),
                new AgentCoreTest.Events(), (w, l) -> true, hot, fromHot).run(new AgentLoop.Request("B站热门第一的UP主还发了什么视频", "test"));
        check(fromHot.runs.size() == 1, "an UP from this task's hot list is not turned back");

        AgentCoreTest.Fake mine = videos();
        AgentLoop.Request own = new AgentLoop.Request("打开B站看看我的频道最新的视频", "test");
        own.profile = profile;
        AgentCoreTest.loop(new AgentCoreTest.Script().calls("bili_videos", AgentCoreTest.o("up", "星辰亻旅行者")).text("好"),
                new AgentCoreTest.Events(), (w, l) -> true, mine).run(own);
        check(mine.runs.size() == 1, "「我的频道」: 关于用户's UP");

        AgentCoreTest.Fake fuller = videos();
        AgentCoreTest.loop(new AgentCoreTest.Script().calls("bili_videos", AgentCoreTest.o("up", "老师好我叫何同学")).text("好"),
                new AgentCoreTest.Events(), (w, l) -> true, fuller).run(new AgentLoop.Request("何同学最新的视频是什么", "test"));
        check(fuller.runs.size() == 1, "the UP's full name for what the user called them");

        AgentCoreTest.Fake heard = videos();
        AgentLoop.Request misheard = new AgentLoop.Request("放星辰人旅行者最新的视频", "test");
        misheard.aliases = Grounding.withBuiltins(null);
        AgentCoreTest.loop(new AgentCoreTest.Script().calls("bili_videos", AgentCoreTest.o("up", "星辰旅行者")).text("好"),
                new AgentCoreTest.Events(), (w, l) -> true, heard).run(misheard);
        check(heard.runs.size() == 1 && "星辰亻旅行者".equals(heard.runs.get(0).optString("up")),
                "a misheard name: its written form, and the model's near miss put right: " + heard.runs);

        AgentCoreTest.Fake follow = videos();
        AgentLoop.Request then = new AgentLoop.Request("他播放量最高的是哪个", "test");
        then.recent = new JSONArray().put(AgentCoreTest.o("said", "影视飓风最近发了什么视频", "replied", "影视飓风最新的 3 个视频：…"));
        AgentCoreTest.loop(new AgentCoreTest.Script().calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "order", "most_played")).text("好"),
                new AgentCoreTest.Events(), (w, l) -> true, follow).run(then);
        check(follow.runs.size() == 1, "「他」: the UP of the turn before");

        // an UP the model wrote right for a misheard one stays as written (the search finds a character off too)
        AgentCoreTest.Fake right = videos();
        AgentCoreTest.loop(new AgentCoreTest.Script().calls("bili_videos", AgentCoreTest.o("up", "影视飓风")).text("好"),
                new AgentCoreTest.Events(), (w, l) -> true, right).run(new AgentLoop.Request("放影视飓凤最新的视频", "test"));
        check(right.runs.size() == 1 && "影视飓风".equals(right.runs.get(0).optString("up")), "the model's spelling of a misheard UP is kept: " + right.runs);
    }

    private static AgentCoreTest.Fake videos() throws Exception {
        AgentCoreTest.Fake f = new AgentCoreTest.Fake("bili_videos", Tool.Gate.read());
        f.reply = AgentCoreTest.o("ok", true, "status", "observed", "message", "影视飓风最新的视频是《动态视频｜2026，它来了…》，昨天发的，13 万播放",
                "answer", "影视飓风最新的视频是《动态视频｜2026，它来了…》，昨天发的，13 万播放",
                "videos", new JSONArray().put(AgentCoreTest.o("n", 1, "title", "动态视频｜2026，它来了…", "link", "bilibili://video/117568757109187")));
        return f;
    }

    private static AgentCoreTest.Fake link(boolean playing) throws Exception {
        AgentCoreTest.Fake f = new AgentCoreTest.Fake("open_link", Tool.Gate.act());
        f.reply = playing ? AgentCoreTest.o("ok", true, "status", "observed", "message", "已用哔哩哔哩打开，正在播放《动态视频｜2026，它来了…》",
                "playing", AgentCoreTest.o("app", "哔哩哔哩", "state", "playing", "title", "动态视频｜2026，它来了…"))
                : AgentCoreTest.o("ok", true, "status", "observed", "message", "已用哔哩哔哩打开视频的播放页，还没读到播放状态");
        return f;
    }

    private static JSONArray one(String name) throws Exception {
        return new JSONArray().put(AgentCoreTest.o("id", "a", "function", AgentCoreTest.o("name", name, "arguments", "{\"final\":true}")));
    }

    /** What openBiliVideo says when the player page is up and the video has not started yet (2026-11-10 23:10). */
    private static final String LOADING = "已用哔哩哔哩打开这个视频的播放页，视频还在加载，稍等就会自己开始播放";

    private static AgentCoreTest.Fake stillLoading() throws Exception {
        AgentCoreTest.Fake f = new AgentCoreTest.Fake("open_link", Tool.Gate.act());
        f.reply = AgentCoreTest.o("ok", true, "status", "observed", "message", LOADING, "loading", true,
                "note", "B站会自己开始播放：不要点屏幕、不要按键（确认键是暂停），直接把这句话告诉用户");
        return f;
    }

    /**
     * A B站 page that is still loading is the answer (2026-11-10 23:10 on the TV): the video starts by itself, and what
     * the model did after that page — media_now_playing, a plan, a click on 展开更多 that opened the UP's page, and a
     * play/pause that paused the video that had started meanwhile — only got in its way.
     */
    static void loading() throws Exception {
        JSONObject yes = AgentCoreTest.o("ok", true, "loading", true);
        check(AgentLoop.loadingLink("open_link", yes), "a link whose page is still loading");
        check(!AgentLoop.loadingLink("open_link", AgentCoreTest.o("ok", true)) && !AgentLoop.loadingLink("open_link", null)
                && !AgentLoop.loadingLink("open_link", AgentCoreTest.o("ok", false, "loading", true))
                && !AgentLoop.loadingLink("open_link", AgentCoreTest.o("loading", true))
                && !AgentLoop.loadingLink("screen_click", yes),
                "not without loading, not a failure, not another tool, not no result");
        check(AgentLoop.playedLink("open_link", AgentCoreTest.o("playing", AgentCoreTest.o("title", "x")))
                && !AgentLoop.playedLink("open_link", yes), "playedLink is unchanged: a loading page is not one seen playing");

        // 「放影视飓风最新的视频」: the list, the link, and the page still loading is the answer (two rounds, not three)
        AgentCoreTest.Fake open = stillLoading();
        AgentCoreTest.Script load = new AgentCoreTest.Script()
                .calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "count", 1))
                .calls("open_link", AgentCoreTest.o("uri", "bilibili://video/117568757109187"))
                .text("这一轮不该被问到");
        Ledger lt = AgentCoreTest.loop(load, new AgentCoreTest.Events(), (w, l) -> true, videos(), open).run(new AgentLoop.Request("放影视飓风最新的视频", "test"));
        check(load.seen.size() == 2 && open.runs.size() == 1 && LOADING.equals(lt.reply)
                        && (lt.state == Ledger.State.ANSWERED || lt.state == Ledger.State.DONE),
                "a page still loading is the answer, and the model is not asked again: " + lt.reply + " / " + load.seen.size() + " / " + lt.state);

        // a video seen playing still ends with 「正在播放《…》」
        AgentCoreTest.Script play = new AgentCoreTest.Script()
                .calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "count", 1))
                .calls("open_link", AgentCoreTest.o("uri", "bilibili://video/117568757109187"))
                .text("这一轮不该被问到");
        Ledger pt = AgentCoreTest.loop(play, new AgentCoreTest.Events(), (w, l) -> true, videos(), link(true)).run(new AgentLoop.Request("放影视飓风最新的视频", "test"));
        check(play.seen.size() == 2 && "正在播放《动态视频｜2026，它来了…》".equals(pt.reply), "a video seen playing is still 「正在播放《…》」: " + pt.reply);

        // a page with neither playing nor loading: the model looks, as before
        AgentCoreTest.Script unsure = new AgentCoreTest.Script()
                .calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "count", 1))
                .calls("open_link", AgentCoreTest.o("uri", "bilibili://video/117568757109187"))
                .text("视频页打开了");
        AgentCoreTest.loop(unsure, new AgentCoreTest.Events(), (w, l) -> true, videos(), link(false)).run(new AgentLoop.Request("放影视飓风最新的视频", "test"));
        check(unsure.seen.size() == 3, "an opened page that is neither playing nor loading still goes back to the model: " + unsure.seen.size());

        // a planned task (directAnswer never runs with a plan): the loading page ends it too
        AgentCoreTest.Fake plannedOpen = stillLoading();
        AgentCoreTest.Script planned = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("放影视飓风最新的视频", "查最新的视频", "打开它的播放页"))
                .calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "count", 1))
                .calls("open_link", AgentCoreTest.o("uri", "bilibili://video/117568757109187"))
                .text("这一轮不该被问到");
        Ledger plt = AgentCoreTest.loop(planned, new AgentCoreTest.Events(), (w, l) -> true, videos(), plannedOpen).run(new AgentLoop.Request("放影视飓风最新的视频", "test"));
        check(planned.seen.size() == 3 && plannedOpen.runs.size() == 1 && LOADING.equals(plt.reply) && plt.state == Ledger.State.DONE,
                "a planned task ends on the loading page as well: " + plt.reply + " / " + planned.seen.size() + " / " + plt.state);

        // the loading link beside another call in the same round: still the answer, nothing is pressed after it
        AgentCoreTest.Fake beside = stillLoading();
        AgentCoreTest.Fake read = new AgentCoreTest.Fake("screen_read", Tool.Gate.read());
        read.reply = AgentCoreTest.o("ok", true, "status", "observed", "message", "屏幕上是哔哩哔哩的播放页");
        AgentCoreTest.Script both = new AgentCoreTest.Script()
                .calls("open_link", AgentCoreTest.o("uri", "bilibili://video/117568757109187"), "screen_read", AgentCoreTest.o())
                .text("这一轮不该被问到");
        Ledger bt = AgentCoreTest.loop(both, new AgentCoreTest.Events(), (w, l) -> true, beside, read).run(new AgentLoop.Request("放影视飓风最新的视频", "test"));
        check(both.seen.size() == 1 && beside.runs.size() == 1 && LOADING.equals(bt.reply),
                "a round with the loading link and a read beside it ends on it too: " + bt.reply + " / " + both.seen.size());
    }

    static void loop() throws Exception {
        // 「放影视飓风最新的视频」: the list, the link, and the video seen playing is the answer (two rounds, not three)
        AgentCoreTest.Fake open = link(true);
        AgentCoreTest.Script play = new AgentCoreTest.Script()
                .calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "count", 1))
                .calls("open_link", AgentCoreTest.o("uri", "bilibili://video/117568757109187"))
                .text("这一轮不该被问到");
        Ledger t = AgentCoreTest.loop(play, new AgentCoreTest.Events(), (w, l) -> true, videos(), open).run(new AgentLoop.Request("放影视飓风最新的视频", "test"));
        check(play.seen.size() == 2 && open.runs.size() == 1 && "正在播放《动态视频｜2026，它来了…》".equals(t.reply),
                "list, link, playing: the answer without a third round: " + t.reply + " / " + play.seen.size());

        // the model marked the list final, but the request asks to play it: it gets its round to open the video
        AgentCoreTest.Fake open2 = link(true);
        AgentCoreTest.Script marked = new AgentCoreTest.Script()
                .calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "final", true))
                .calls("open_link", AgentCoreTest.o("uri", "bilibili://video/117568757109187"))
                .text("这一轮不该被问到");
        AgentCoreTest.loop(marked, new AgentCoreTest.Events(), (w, l) -> true, videos(), open2).run(new AgentLoop.Request("放影视飓风最新的视频", "test"));
        check(open2.runs.size() == 1, "a final list for 放… does not end the task: the video is opened");

        // only a question: the list is the answer
        AgentCoreTest.Script ask = new AgentCoreTest.Script().calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "final", true)).text("这一轮不该被问到");
        Ledger q = AgentCoreTest.loop(ask, new AgentCoreTest.Events(), (w, l) -> true, videos()).run(new AgentLoop.Request("影视飓风最近发了什么视频", "test"));
        check(ask.seen.size() == 1 && q.reply.startsWith("影视飓风最新的视频是"), "影视飓风最近发了什么视频: the list, read as it is");

        // the tool may say its answer is the whole answer (asked whether an UP is live)
        AgentCoreTest.Fake liveTool = new AgentCoreTest.Fake("bili_videos", Tool.Gate.read());
        liveTool.reply = AgentCoreTest.o("ok", true, "status", "observed", "message", "影视飓风现在没在直播", "answer", "影视飓风现在没在直播", "final", true);
        AgentCoreTest.Script liveAsk = new AgentCoreTest.Script().calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "live", true)).text("这一轮不该被问到");
        Ledger onAirAsk = AgentCoreTest.loop(liveAsk, new AgentCoreTest.Events(), (w, x) -> true, liveTool).run(new AgentLoop.Request("影视飓风现在在直播吗", "test"));
        check(liveAsk.seen.size() == 1 && "影视飓风现在没在直播".equals(onAirAsk.reply), "a data tool's own final: the answer in one round");
        AgentCoreTest.Fake notFinal = new AgentCoreTest.Fake("web_search", Tool.Gate.read());
        notFinal.reply = AgentCoreTest.o("ok", true, "status", "observed", "answer", "x", "searched", true, "final", true);
        check(AgentLoop.searchAnswer(one("web_search").put(0, AgentCoreTest.o("id", "a", "function", AgentCoreTest.o("name", "web_search", "arguments", "{}"))),
                new JSONObject[]{notFinal.reply}) == null, "only a data tool may declare itself final, not a search");

        // a page opened but not seen playing: the model looks
        AgentCoreTest.Script unsure = new AgentCoreTest.Script()
                .calls("bili_videos", AgentCoreTest.o("up", "影视飓风", "count", 1))
                .calls("open_link", AgentCoreTest.o("uri", "bilibili://video/117568757109187"))
                .text("视频页打开了");
        AgentCoreTest.loop(unsure, new AgentCoreTest.Events(), (w, l) -> true, videos(), link(false)).run(new AgentLoop.Request("放影视飓风最新的视频", "test"));
        check(unsure.seen.size() == 3, "an opened page not seen playing: the model's round");

        check(AgentLoop.playedLink("open_link", AgentCoreTest.o("playing", AgentCoreTest.o("title", "x"))) && !AgentLoop.playedLink("open_link", AgentCoreTest.o("ok", true))
                && !AgentLoop.playedLink("screen_click", AgentCoreTest.o("playing", AgentCoreTest.o("title", "x"))), "playedLink");
        check(AgentLoop.searchThenAct("播放星辰亻旅行者播放量最高的视频", one("bili_videos")) && AgentLoop.searchThenAct("放影视飓风最新的视频", one("bili_videos"))
                && AgentLoop.searchThenAct("我想看影视飓风最新的视频", one("bili_videos")) && AgentLoop.searchThenAct("放B站热门第一个", one("hot_list")),
                "playing or watching what a list finds is a step");
        check(!AgentLoop.searchThenAct("影视飓风最近发了什么视频", one("bili_videos")) && !AgentLoop.searchThenAct("B站有什么热门视频", one("hot_list"))
                && !AgentLoop.searchThenAct("明天天气怎么样", one("weather")), "asking what there is is an answer");
        // the review, 2026-11-07: a data tool's final before what the request goes on to ask
        check(AgentLoop.searchThenAct("网易云音乐播放林俊杰最新发表的一个单曲", one("music_new")) && AgentLoop.searchThenAct("明天适合搬家吗？适合的话帮我记到随手记", one("almanac"))
                && AgentLoop.searchThenAct("下周六适合结婚吗，适合就设个提醒", one("almanac")) && AgentLoop.searchThenAct("流浪地球2在哪能看，我要看", one("movie_rating"))
                && AgentLoop.searchThenAct("放凡人修仙传最新一集", one("bili_bangumi")) && AgentLoop.searchThenAct("看柯南最新一集", one("bili_bangumi"))
                && !AgentLoop.searchThenAct("凡人修仙传最新一集是第几集", one("bili_bangumi")),
                "a data tool's answer before an act is a step");
        // the calibration that night: 「何同学播放量最高的视频是哪个」 took a second round for 播放
        check(!AgentLoop.searchThenAct("何同学播放量最高的视频是哪个", one("bili_videos")) && !AgentLoop.searchThenAct("B站播放最多的视频有哪些", one("bili_videos"))
                && AgentLoop.searchThenAct("播放影视飓风播放量最高的视频", one("bili_videos")) && AgentLoop.searchThenAct("放他播放量最高的那个视频", one("bili_videos")),
                "播放量 is a count, 播放 a request");
    }
}
