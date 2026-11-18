package xyz.erapi0neer.xingyi.agent;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;

/** A singer's songs as a run (2026-11-07: 「播放林俊杰的合集」 got one song, then 13 rounds of keys in NetEase). */
final class MusicQueueChecks {
    private MusicQueueChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static void run() {
        try {
            contract();
            sessions();
            skipWords();
            prewarm();
            loop();
        } catch (Exception e) {
            check(false, "music queue checks threw " + e);
        }
    }

    static void contract() throws Exception {
        Contract c = AgentCoreTest.contract;
        JSONObject artistOnly = c.check("netease_play", AgentCoreTest.o("artist", "林俊杰", "title", ""));
        check(artistOnly.optString("artist").equals("林俊杰") && !artistOnly.has("title"), "an optional field sent empty is dropped, not refused: " + artistOnly);
        boolean refused = false;
        try { c.check("bili_search", AgentCoreTest.o("keyword", " ")); } catch (IllegalArgumentException e) { refused = e.getMessage().contains("keyword"); }
        check(refused, "a required one sent empty is still refused");
        // plan's steps as the model sent them on the TV (2026-11-07 「关闭桌面不要关机」: three refusals, four rounds lost)
        org.json.JSONArray objects = new org.json.JSONArray().put(AgentCoreTest.o("text", "读屏看桌面")).put(AgentCoreTest.o("text", "找到退出入口"));
        JSONObject p1 = c.check("plan", AgentCoreTest.o("goal", "关闭桌面", "steps", objects, "done_when", "关掉了"));
        JSONObject p2 = c.check("plan", AgentCoreTest.o("goal", "关闭桌面", "steps", "打开 Orrery，找到退出入口，确认已关闭", "done_when", "关掉了"));
        JSONObject p3 = c.check("plan", AgentCoreTest.o("goal", "关闭桌面", "steps", "[\"打开 Orrery\",\"确认\"]", "done_when", "关掉了"));
        check("读屏看桌面".equals(p1.getJSONArray("steps").getString(0)) && p2.getJSONArray("steps").length() == 3
                && "打开 Orrery".equals(p2.getJSONArray("steps").getString(0)) && p3.getJSONArray("steps").length() == 2,
                "steps as [{text}], as one string between commas, as a JSON list in a string: all taken: " + p1 + p2 + p3);
        boolean nested = false;
        try { c.check("plan", AgentCoreTest.o("goal", "x", "steps", new org.json.JSONArray().put(AgentCoreTest.o("a", 1, "b", 2)), "done_when", "y")); }
        catch (IllegalArgumentException e) { nested = true; }
        check(nested, "an object that is not one text is still refused");
        String sys = c.systemFor(new HashSet<String>());
        check(sys.contains("netease_play 只填 artist") && sys.contains("和谐"), "the rule: a singer's songs or 合集 is netease_play with the artist only; 和谐 is 合集 misheard");
        check(c.function("netease_play").optString("description").contains("连续播放一串"), "and the tool says what an artist-only call does");
        boolean skill = false;
        for (Skills.Skill s : c.skills) if (s.name.equals("网易云音乐") && s.text.contains("只填 artist")) skill = true;
        check(skill, "NetEase's app note agrees (it used to send 「最火的」 to a web search)");
        for (Skills.Skill s : c.skills) if (s.name.equals("电视设置")) check(!s.text.isEmpty() && !Skills.select(c.skills, "清理电视的内存", "", "").contains(s),
                "「清理电视的内存」 no longer brings the settings note");
    }

    static void sessions() {
        String dump = "Sessions Stack - have 1 sessions:\n  MediaSession com.netease.cloudmusic.tv/NeteaseMusic (userId=0)\n"
                + "    ownerPid=1, ownerUid=10050, userId=0\n    package=com.netease.cloudmusic.tv\n    active=true\n"
                + "    state=PlaybackState {state=3, position=12000, buffered position=0, speed=1.0, updated=5}\n"
                + "    metadata:size=7, description=江南, 林俊杰, 第二天堂\n    queueTitle=林俊杰, size=30\n";
        List<MediaSessions.Session> all = MediaSessions.parse(dump);
        check(all.size() == 1 && all.get(0).queue == 30 && "江南".equals(all.get(0).title) && "playing".equals(all.get(0).state),
                "the session's queue size is read: " + (all.isEmpty() ? "none" : all.get(0).queue));
        check(MediaSessions.parse(dump.replace("    queueTitle=林俊杰, size=30\n", "")).get(0).queue == 0, "no queue line: 0");
        skips();
    }

    private static MediaSessions.Session song(String pkg, String title, String state, long position, long updated) {
        MediaSessions.Session s = new MediaSessions.Session();
        s.pkg = pkg; s.title = title; s.artist = "林俊杰"; s.state = state; s.position = position; s.updated = updated;
        return s;
    }

    /** Did XiaoAi's own 「下一首」 land (2026-11-16 19:30: it did not, and 星仪 was kept from the only skip that worked). */
    static void skips() {
        final String NE = "com.netease.cloudmusic.tv";
        final MediaSessions.Skip LANDED = MediaSessions.Skip.LANDED, MISSED = MediaSessions.Skip.MISSED, UNKNOWN = MediaSessions.Skip.UNKNOWN;
        // 《江南》 began at 10 s; 「下一首歌」 reached 星仪 at 40 s; the gate looks at 44 s and 《江南》 is still on
        MediaSessions.Session jiangnan = song(NE, "江南 (River South)", "playing", 0, 10_000);
        check(MediaSessions.xiaoaiSkip(jiangnan, song(NE, "江南 (River South)", "playing", 0, 10_000), 40_000, 44_000) == MISSED,
                "11-16 19:30: the same song, 34 s in, 4 s after the request: XiaoAi's skip never landed, 星仪 may skip");
        check(MediaSessions.xiaoaiSkip(jiangnan, song(NE, "江南 (River South)", "playing", 33_000, 43_900), 40_000, 44_000) == MISSED,
                "the same when NetEase reports its position every second");
        check(MediaSessions.xiaoaiSkip(null, song(NE, "江南 (River South)", "playing", 0, 10_000), 40_000, 40_200) == MISSED,
                "a quick command reads no session at the request: the position alone tells");
        // XiaoAi's skip landed: before the session was read at the request, or after it
        MediaSessions.Session next = song(NE, "可惜没如果", "playing", 0, 40_500);
        check(MediaSessions.xiaoaiSkip(next, song(NE, "可惜没如果", "playing", 0, 40_500), 40_000, 44_000) == UNKNOWN,
                "a song that began after XiaoAi's instruction is never taken for a missed skip");
        check(MediaSessions.xiaoaiSkip(jiangnan, next, 40_000, 44_000) == LANDED, "another song than at the request: landed");
        check(MediaSessions.xiaoaiSkip(null, song(NE, "可惜没如果", "playing", 0, 39_300), 40_000, 44_000) == UNKNOWN,
                "a song begun 0.7 s before the request (the bridge holds it up to 0.9 s): not missed");
        // a request that waited 10 s behind another task: the time counts from when it came, so the landed skip holds
        check(MediaSessions.xiaoaiSkip(next, song(NE, "可惜没如果", "playing", 0, 40_600), 40_000, 53_000) == UNKNOWN,
                "a queued request measures from its arrival: 12.4 s into a song that came 13 s ago is not missed");
        check(MediaSessions.xiaoaiSkip(jiangnan, song(NE, "江南 (River South)", "playing", 0, 37_000), 40_000, 44_000) == UNKNOWN,
                "exactly the margin (played 7 s, 4 s since the request + 3 s): not missed");
        check(MediaSessions.xiaoaiSkip(jiangnan, song(NE, "江南 (River South)", "playing", 0, 36_999), 40_000, 44_000) == MISSED,
                "one millisecond past it: missed");
        // whatever cannot be told is left as before: XiaoAi is believed
        check(MediaSessions.xiaoaiSkip(jiangnan, song(NE, "江南 (River South)", "paused", 20_000, 30_000), 40_000, 44_000) == UNKNOWN,
                "paused: no position to go by");
        check(MediaSessions.xiaoaiSkip(jiangnan, null, 40_000, 44_000) == UNKNOWN, "nothing with a session (云视听极光 keeps none)");
        check(MediaSessions.xiaoaiSkip(jiangnan, song(NE, "江南 (River South)", "playing", 30_000, 0), 40_000, 44_000) == UNKNOWN,
                "no update time printed");
        check(MediaSessions.xiaoaiSkip(jiangnan, song(NE, "江南 (River South)", "playing", 0, 10_000), 0, 44_000) == UNKNOWN,
                "no request time");
        check(!MediaSessions.sameTrack(song(NE, "江南", "playing", 0, 1), song("tv.danmaku.bili", "江南", "playing", 0, 1))
                && MediaSessions.sameTrack(song(NE, "江南", "playing", 0, 1), song(NE, "江南", "paused", 5, 9))
                && !MediaSessions.sameTrack(null, song(NE, "江南", "playing", 0, 1)),
                "the same track: app, title and artist; its state and position do not matter");
        // a song that began right at the request (under 3 s in): a skip that landed and one that never came read the
        // same here, so 星仪 leaves it to XiaoAi (2026-11-18 audit: the test did not say this at all)
        MediaSessions.Session fresh = song(NE, "可惜没如果", "playing", 0, 40_000);
        check(MediaSessions.xiaoaiSkip(fresh, song(NE, "可惜没如果", "playing", 1_500, 41_500), 40_000, 43_000) == UNKNOWN,
                "a track 1.5 s in at the request, still playing: UNKNOWN, XiaoAi is believed");
        check(MediaSessions.xiaoaiSkip(fresh, song(NE, "可惜没如果", "playing", 9_000, 49_000), 40_000, 51_000) == UNKNOWN,
                "nor does its later position make it MISSED while it is the track of the request");
        // what only that rule decides (2026-11-18 review: the two checks above hold with the rule deleted — a track that
        // began at the request is never 3 s ahead of the time since by position alone): the same just-begun track read
        // again after its position jumped ahead, a resume point or a seek
        check(MediaSessions.xiaoaiSkip(fresh, song(NE, "可惜没如果", "playing", 30_000, 41_000), 40_000, 42_000) == UNKNOWN,
                "a track that had just begun at the request stays UNKNOWN when its position jumps ahead");
        // where the track is, for 「这是重播吗」 (2026-11-16 19:45: 「对，现在放的是重新从头播的」 with nothing to go by)
        check(MediaSessions.at(song(NE, "修炼爱情", "playing", 0, 10_000), 143_000).equals("，放到 2:13"),
                "playing: the reported position plus the time since: " + MediaSessions.at(song(NE, "修炼爱情", "playing", 0, 10_000), 143_000));
        check(MediaSessions.at(song(NE, "修炼爱情", "paused", 125_400, 30_000), 900_000).equals("，放到 2:05"),
                "paused: where it stopped, however long ago");
        check(MediaSessions.at(song(NE, "修炼爱情", "playing", 6_000, 50_000), 51_200).equals("，放到 0:07"), "seconds keep two digits");
        check(MediaSessions.at(song(NE, "修炼爱情", "playing", 30_000, 0), 51_200).isEmpty()
                && MediaSessions.at(song(NE, "修炼爱情", "buffering", 30_000, 50_000), 51_200).isEmpty()
                && MediaSessions.at(null, 51_200).isEmpty() && MediaSessions.played(song(NE, "x", "stopped", 1, 2), 3) == -1,
                "no update time, buffering, stopped or no session: nothing said");
    }

    /**
     * Which sentences bring NetEase up while the model plans (AgentEngine.startNeteaseEarly): a plain request for music
     * and no other. 2026-11-19, review of faf4177 + f7d8719 before they reached the TV: the pattern there had no check
     * at all, and its commit said 「你会唱歌吗」 was left alone when it matched. Missing a request costs the head start;
     * a false one opens an app nobody asked for.
     */
    static void prewarm() throws Exception {
        for (String yes : new String[] {"放首歌", "来首周杰伦的歌", "我要唱林俊杰的歌", "播放林俊杰热门的歌曲",
                "网易云音乐播放林俊杰最新的歌", "播放音乐", "听音乐", "我想听歌", "放点音乐", "嗯放首歌吧", "再来一首歌",
                "放一首好听的歌", "播放我喜欢的音乐", "帮我放首歌", "点歌", "我想播放林俊杰的歌然后我要唱", "听FM"})
            check(Dispatch.songRequest(yes), "a plain request for music: " + yes);
        // 「我要唱 X」 is how this user asks for a song to sing along to, with or without the word 歌 (the TV's perf log:
        // of the six real requests that waited 6-13 s for netease_play, five began 我要唱 / 我想…唱 — 「我要唱跳楼机」
        // 13.3 s, 「我要唱如果可以」 12.3 s — and neither pattern, old or new, started NetEase early for those two)
        for (String sing : new String[] {"我要唱跳楼机", "我要唱如果可以", "我想唱首歌", "我来唱晴天", "嗯我要唱江南吧"})
            check(Dispatch.songRequest(sing), "我要唱 X is a request for that song: " + sing);
        for (String no : new String[] {"我要唱", "我想唱给妈妈听", "我要唱的不是这首", "我想唱但是没有伴奏", "我要唱歌你是不是该给我调到伴奏",
                "我说我要唱跳楼机", "他要唱跳楼机", "我要唱什么好呢"})
            check(!Dispatch.songRequest(no), "not a song to sing: " + no);
        // what the user said in those 30 days that the old pattern took for a request (reworded where it was private)
        for (String no : new String[] {"我不是跟你说过如果我要唱歌的话你要开伴奏吗", "我要放歌的时候你肯定要开伴奏",
                "我说我要唱歌你是不是该给我调到伴奏", "我让你播放林俊杰的歌应该是播放那个和谐为什么播放这么一点",
                "目前我说的是播放林俊杰的合集就是你此刻播放的街道这一首歌不对", "你说什么留着劲给我唱歌"})
            check(!Dispatch.songRequest(no), "a complaint or a remark, not a request: " + no);
        // questions, refusals and other things that only mention a song
        for (String no : new String[] {"你会唱歌吗", "这首歌是谁唱的", "听说林俊杰发新歌了", "现在放的是什么歌", "放点音乐视频",
                "刚才放的那首歌叫什么", "我听不清歌词", "帮我听听这是什么歌", "别放歌了", "不想听歌了", "唱歌的是谁", "听歌识曲",
                "我喜欢听歌", "周杰伦唱过哪些歌", "放的什么音乐", "放错歌了", "下一首歌", "我想听你唱歌", "暂停音乐", "关掉音乐",
                "林俊杰最近发新歌了吗", "放首歌然后把音量调到最大", "", "   ",
                // what only the words barred between the verb and 的 turn away
                "放不了周杰伦的歌", "听过他的歌", "播放的是谁的歌",
                // 2026-11-19 review, each run against the first version and taken for a request: a verb that is no
                // verb of playing, 星仪 asked to sing, a count or a kind asked about, another player, a sentence that
                // asks or tells (its particle is gone after normalise), 「我要唱歌」 going on into something else
                "听起来像周杰伦的歌", "听上去挺老的歌", "点右边的音乐", "点一下右边的音乐", "点设置里的音乐", "我想听你唱的歌",
                "来首你唱的歌", "想听怎样的歌", "要听几首歌", "播放量最高的歌", "放QQ音乐里的歌", "播放酷狗的歌", "放歌了？",
                "听歌呢", "放歌呢", "我要唱歌的时候开伴奏", "我要唱歌的话开伴奏", "我要唱歌记得开伴奏",
                "我想唱歌的时候把人声关小", "我要唱到几点", "我想唱歌家里人睡了"})
            check(!Dispatch.songRequest(no), "no request to play music: 「" + no + "」");
        check(!Dispatch.songRequest(null), "null is no request");
        String engine = source("src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java");
        int from = engine.indexOf("private void startNeteaseEarly("), to = engine.indexOf("app.startActivity", from);
        check(from > 0 && to > from && engine.substring(from, to).contains("!Dispatch.songRequest(text)) return;"),
                "the engine's song prewarm asks Dispatch.songRequest before it starts anything");
        check(!engine.contains("SONG_ASK") && !engine.contains("SONG_WORDS") && !engine.contains("SONG_NOUN"),
                "and the pattern it had of its own is gone");
        // B站's prewarm (on the TV since 11-05) by the same measure: Dispatch.biliRequest. Of the three real sentences
        // of 30 days that set the old one off — any sentence naming B站 — one wanted B站.
        for (String yes : new String[] {"打开B站", "打开B站搜索星辰亻旅行者", "B站搜一下影视飓风", "在哔哩哔哩看他最新的视频",
                "打开B站搜索MINI MAX H3相关的视频播放播放量最高的那一个", "打开B站搜索怎么做红烧肉的视频", "bilibili"})
            check(Dispatch.biliRequest(yes), "a sentence that is going to use B站: " + yes);
        for (String no : new String[] {"为什么刚刚B站打开这么慢", "看看哔哩哔哩能直接打开哪些页面", "B站怎么这么卡", "B站是不是坏了",
                "B站会员多少钱", "别打开B站", "先不要开B站", "昨天看了多久B站", "B站怎么又卡了", "B站有什么好看的吗", "打开网易云", "", null})
            check(!Dispatch.biliRequest(no), "B站 is not about to be used: " + no);
        check(Dispatch.namesBili("B站") && Dispatch.namesBili("哔哩哔哩") && !Dispatch.namesBili("网易云") && !Dispatch.namesBili(null),
                "namesBili: the ways it is written");
        check(AgentLoop.asksWhy("为什么刚刚B站打开这么慢") && !AgentLoop.asksWhy("打开B站搜索星辰亻旅行者"), "asksWhy tells a why from a request");
        int bili = engine.indexOf("private void startBilibiliEarly("), biliEnd = engine.indexOf("app.startActivity", bili);
        check(bili > 0 && biliEnd > bili && engine.substring(bili, biliEnd).contains("!Dispatch.biliRequest(text)) return;"),
                "B站's prewarm asks Dispatch.biliRequest before it starts anything");
        check(!engine.contains("BILI_WORDS") && !engine.contains("ABOUT_TIME"), "and the patterns it had of its own are gone");
    }

    private static String source(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
        return n;
    }

    /**
     * What the user hears after a skip where no model puts it into words — the quick command 「下一首」「上一首」.
     * 2026-11-18, review of cbed01d before it reached the TV: the gate's reason, which is written for the model
     * (「小爱已经为这句话切过了，再切一次会多跳一个；现在…：照这个告诉用户结果。」), was about to be read out as it is on every
     * 「下一首」 XiaoAi had carried out itself — most of them. The reason stays the model's; the user gets afterSkip.
     */
    static void skipWords() throws Exception {
        final String NE = "com.netease.cloudmusic.tv";
        MediaSessions.Session a = song(NE, "江南", "playing", 60_000, 1), b = song(NE, "愿与愁", "playing", 500, 2);
        check("好的，现在是林俊杰的《愿与愁》".equals(MediaSessions.afterSkip(a, b)),
                "another track than at the request: named as the new one: " + MediaSessions.afterSkip(a, b));
        check("现在放的是林俊杰的《愿与愁》".equals(MediaSessions.afterSkip(b, b)),
                "the track of the request is still on (XiaoAi's skip began it, or never came): named, no switch claimed: " + MediaSessions.afterSkip(b, b));
        check("好的，现在是林俊杰的《愿与愁》".equals(MediaSessions.afterSkip(null, b)), "nothing was read at the request: what plays now");
        MediaSessions.Session paused = song(NE, "愿与愁", "paused", 500, 2), untitled = song(NE, "", "playing", 500, 2);
        check("好的".equals(MediaSessions.afterSkip(a, null)) && "好的".equals(MediaSessions.afterSkip(a, paused))
                && "好的".equals(MediaSessions.afterSkip(a, untitled)), "no session, not playing, no title: 「好的」, nothing made up");
        MediaSessions.Session duo = song(NE, "布拉格广场", "playing", 500, 2), solo = song(NE, "纯音乐", "playing", 500, 2);
        duo.artist = "蔡依林/周杰伦";
        solo.artist = "";
        check("好的，现在是蔡依林、周杰伦的《布拉格广场》".equals(MediaSessions.afterSkip(a, duo))
                && "好的，现在是《纯音乐》".equals(MediaSessions.afterSkip(a, solo)), "two singers are read with 、; no singer: the title alone");
        for (MediaSessions.Session now : new MediaSessions.Session[] {b, duo, solo, paused, null})
            for (MediaSessions.Session before : new MediaSessions.Session[] {a, b, null}) {
                String said = MediaSessions.afterSkip(before, now);
                check(!said.isEmpty() && said.length() <= 40 && !said.contains("用户") && !said.contains("media_")
                        && !said.contains("小爱已经") && !said.contains("照这个"), "nothing in it is written for the model: " + said);
            }
        // the gate keeps the two apart: a reason for the model, words for the user where there is no model
        Tool.Gate plain = Tool.Gate.deny("给模型的话"), both = Tool.Gate.deny("给模型的话", "给用户的话");
        check(plain.said.isEmpty() && "给模型的话".equals(plain.reason) && "给用户的话".equals(both.said)
                && "给模型的话".equals(both.reason) && both.risk == Tool.Gate.Risk.DENY && Tool.Gate.deny("x", null).said.isEmpty()
                && Tool.Gate.act().said.isEmpty() && Tool.Gate.read().said.isEmpty() && Tool.Gate.sensitive("x").said.isEmpty(),
                "Gate.deny(why, said): the reason stays the model's, said is the user's, and it is empty everywhere else");
        // the two places that have to use them are in tv/, which does not run on the PC: read in the source
        String engine = source("src/xyz/erapi0neer/xingyi/agent/tv/AgentEngine.java");
        String tv = source("src/xyz/erapi0neer/xingyi/agent/tv/TvTools.java");
        int from = engine.indexOf("private boolean runQuick("), to = engine.indexOf("private boolean answerHere(");
        check(from > 0 && to > from, "runQuick is where it was (the checks below read its body)");
        String quick = from > 0 && to > from ? engine.substring(from, to) : "";
        check(quick.contains("!gate.said.isEmpty() ? gate.said"), "the quick command says the gate's words for the user");
        check(quick.indexOf("!gate.said.isEmpty() ? gate.said") < quick.indexOf("? alreadyDone(q)"),
                "and before the 「好的」 every other refusal gets: placed after it the branch would never be reached");
        check(count(quick, "? gate.reason") == 1 && quick.contains("TvTools.ASLEEP_TEST.equals(gate.reason) ? gate.reason"),
                "and a refusal's reason is read out only for the PC's silent test, never a tool's reason as it is");
        // a quick command that a newer request took over while it waited: no model loop on a cancelled request, whose
        // 「已停止。」 was read out over the request that had taken its place (2026-11-19 review). 停 still gets its answer.
        String gone = "if (cancelled(gen) && gen < supersededBelow) return;";
        check(count(engine, "supersededBelow = floor;") == 2
                && engine.indexOf(gone) > engine.indexOf("if (quick != null && runQuick(quick, tools, text, source, gen)) return;")
                && engine.indexOf(gone) < engine.indexOf("startBilibiliEarly(text, tools);"),
                "a superseded quick command returns before anything is started or said");
        check(tv.contains("playingNow(now), MediaSessions.afterSkip(atRequest, now))"),
                "media_control's refusal of a second skip carries afterSkip for the user");
        // XiaoAi's skip landing late: the deciding is MediaSessions.LateSkip, which runs here (2026-11-19 review: the
        // loop had only two checks, both of its source text; comparing with the later read, dropping the stop, or
        // anchoring the wait on the request alone all stayed green)
        final MediaSessions.LateSkip.Next WAIT = MediaSessions.LateSkip.Next.WAIT, LANDED = MediaSessions.LateSkip.Next.LANDED,
                PRESS = MediaSessions.LateSkip.Next.PRESS, STOP = MediaSessions.LateSkip.Next.STOP;
        check(MediaSessions.LateSkip.look(a, b, b, 1_000, 5_000, false) == LANDED,
                "a skip that landed between the gate's read and the wait's first one: told against the track of the request");
        check(MediaSessions.LateSkip.look(a, a, a, 1_000, 5_000, false) == WAIT, "the track of the request still on, time left: wait");
        check(MediaSessions.LateSkip.look(a, a, b, 3_000, 5_000, false) == LANDED, "another track mid-wait: landed, no key");
        check(MediaSessions.LateSkip.look(a, a, a, 5_000, 5_000, false) == PRESS, "the time is up and nothing changed: the key is pressed");
        check(MediaSessions.LateSkip.look(a, a, a, 5_000, 5_000, true) == STOP,
                "a stop that comes in the last beat of the wait still holds the key back");
        check(MediaSessions.LateSkip.look(a, a, b, 3_000, 5_000, true) == STOP, "stopped: nothing is reported either");
        check(MediaSessions.LateSkip.look(null, a, b, 3_000, 5_000, false) == LANDED
                && MediaSessions.LateSkip.look(null, a, a, 3_000, 5_000, false) == WAIT,
                "no track read at the request: the wait's first read is the baseline");
        check(MediaSessions.LateSkip.look(null, null, b, 1_000, 5_000, false) == PRESS, "nothing to tell a landing by: press, as before");
        check(MediaSessions.LateSkip.look(a, a, null, 3_000, 5_000, false) == WAIT
                && MediaSessions.LateSkip.look(a, a, null, 5_000, 5_000, false) == PRESS, "a read that failed is no landing");
        check(MediaSessions.LateSkip.until(1_000, 2_000, 4_000, 1_500) == 5_000, "a quick command waits until four seconds after the request");
        check(MediaSessions.LateSkip.until(1_000, 9_000, 4_000, 1_500) == 10_500,
                "after a slow model round the press still gives XiaoAi a second and a half");
        check(tv.contains("MediaSessions.LateSkip.look(atRequest, first, s, SystemClock.elapsedRealtime(), until, t.cancelled())")
                && tv.contains("MediaSessions.LateSkip.until(requestElapsed, SystemClock.elapsedRealtime(), SKIP_GRACE_MS, SKIP_LATE_MS)")
                && tv.contains("case STOP: throw new InterruptedException();"),
                "TvTools' wait is that deciding with the clock around it, and a STOP presses nothing");
        check(!tv.contains("（小爱的那一下切过去了，星仪没有再切）"),
                "and what it returns is said like any other skip: the remark for the model is not in the message");
    }

    static void loop() throws Exception {
        // 「播放林俊杰的合集」: one direct call with the artist only; the connector's sentence is the answer
        AgentCoreTest.Fake play = new AgentCoreTest.Fake("netease_play", Tool.Gate.act());
        play.reply = AgentCoreTest.o("ok", true, "status", "observed", "message", "网易云正在连续播放林俊杰的歌，现在是《江南》，一共排了 30 首。");
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls("netease_play", AgentCoreTest.o("artist", "林俊杰", "title", "")).text("（不该到这一轮）");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, play).run(new AgentLoop.Request("播放林俊杰的合集", "test"));
        check(play.runs.size() == 1 && !play.runs.get(0).has("title") && t.reply.contains("连续播放") && m.next == 1,
                "one round, title dropped, the run is the answer: " + t.reply);
    }
}
