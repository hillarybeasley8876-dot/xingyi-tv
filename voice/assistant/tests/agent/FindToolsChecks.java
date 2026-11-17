package xyz.erapi0neer.xingyi.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** find_tools (2026-11-07, after Claude's tool search): a data tool the gates left out, found by the model. */
final class FindToolsChecks {
    private FindToolsChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static Set<String> names(JSONArray tools) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < tools.length(); i++) out.add(tools.optJSONObject(i).optJSONObject("function").optString("name"));
        return out;
    }

    static void run() {
        try {
            search();
            loop();
        } catch (Exception e) {
            check(false, "find_tools checks threw " + e);
        }
    }

    static void search() throws Exception {
        Contract c = AgentCoreTest.contract;
        List<String> songs = c.search("歌手新歌", 3), anime = c.search("番剧更新", 3), almanac = c.search("黄历宜忌", 3), stock = c.search("查股价", 3);
        check(!songs.isEmpty() && "music_new".equals(songs.get(0)), "歌手新歌 → music_new first: " + songs);
        check(!anime.isEmpty() && "bili_bangumi".equals(anime.get(0)), "番剧更新 → bili_bangumi: " + anime);
        check(!almanac.isEmpty() && "almanac".equals(almanac.get(0)), "黄历宜忌 → almanac: " + almanac);
        check(!stock.isEmpty() && "stock_quote".equals(stock.get(0)), "查股价 → stock_quote: " + stock);
        check(c.search("电影评分", 3).contains("movie_rating") && c.search("", 3).isEmpty(), "电影评分 → movie_rating; nothing asked, nothing found");
        check(c.names().contains("find_tools") && !c.gated().contains("find_tools") && c.toolsFor(new HashSet<String>()).toString().contains("find_tools"),
                "find_tools is in every request's list");
        check(c.system.contains("find_tools") && c.systemFor(new HashSet<String>()).contains("find_tools"), "and its rule in every system prompt");
        check(!c.linesFor(java.util.Collections.singleton("music_new")).isEmpty() && c.function("music_new").optString("name").equals("music_new")
                && c.summary("almanac").length() < c.function("almanac").optString("description").length(), "rule lines, definitions, summaries");
    }

    static void loop() throws Exception {
        Contract c = AgentCoreTest.contract;
        check(c.gatedIn("林俊杰最近有什么作品").isEmpty(), "the request has no gate word (the case find_tools is for)");
        AgentCoreTest.Fake newest = new AgentCoreTest.Fake("music_new", Tool.Gate.read());
        newest.reply = AgentCoreTest.o("ok", true, "status", "observed", "answer", "林俊杰最近发了新歌：10 月 10 日上线的单曲《Rise Without A Sound》");
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("find_tools", AgentCoreTest.o("query", "歌手最近发的歌"))
                .calls("music_new", AgentCoreTest.o("artist", "林俊杰"))
                .text("林俊杰 10 月 10 日发了单曲《Rise Without A Sound》。");
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, newest).run(new AgentLoop.Request("林俊杰最近有什么作品", "test"));
        check(newest.runs.size() == 1 && t.reply.contains("Rise"), "found, then used: " + t.reply);
        check(!names(m.toolsSeen.get(0)).contains("music_new") && names(m.toolsSeen.get(1)).contains("music_new"), "sent from the round after the search on");
        check(m.seen.get(0).getJSONObject(0).getString("content").equals(m.seen.get(1).getJSONObject(0).getString("content")),
                "the system prompt is the same in both rounds (the cached prefix)");
        JSONObject found = null;
        JSONArray second = m.seen.get(1);
        for (int i = 0; i < second.length(); i++) if ("tool".equals(second.getJSONObject(i).optString("role"))) found = new JSONObject(second.getJSONObject(i).getString("content"));
        check(found != null && "found".equals(found.optString("status")) && found.getJSONArray("tools").getJSONObject(0).optString("name").equals("music_new")
                && found.has("rules"), "the result: the tool's definition and its rule lines");

        AgentCoreTest.Script none = new AgentCoreTest.Script().calls("find_tools", AgentCoreTest.o("query", "随便聊聊")).text("好的");
        AgentCoreTest.loop(none, new AgentCoreTest.Events(), (w, l) -> true).run(new AgentLoop.Request("陪我聊聊天", "test"));
        JSONObject listed = null;
        JSONArray after = none.seen.get(1);
        for (int i = 0; i < after.length(); i++) if ("tool".equals(after.getJSONObject(i).optString("role"))) listed = new JSONObject(after.getJSONObject(i).getString("content"));
        check(listed != null && "none".equals(listed.optString("status")) && listed.getJSONArray("tools").length() == c.gated().size(),
                "nothing matched: every gated tool by name");
    }
}
