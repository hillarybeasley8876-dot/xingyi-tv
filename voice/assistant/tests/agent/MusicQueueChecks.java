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
