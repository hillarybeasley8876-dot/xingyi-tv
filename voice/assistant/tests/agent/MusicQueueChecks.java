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
