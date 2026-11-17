package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** Checks for Habits (job habits): what 星仪 learns from what it does, and the summary every prompt carries. */
final class HabitChecks {
    private static final long NOW = 1_700_000_000_000L, DAY = 86_400_000L;

    private HabitChecks() { }

    static void run() throws Exception {
        transcript();
        events();
        recording();
        forgetting();
        summary();
        bands();
        caps();
        tools();
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static void eq(String got, String want, String label) {
        check(want.equals(got), label + ": got [" + got + "] want [" + want + "]");
    }

    private static JSONObject o(Object... kv) throws JSONException { return AgentCoreTest.o(kv); }

    private static JSONObject call(String id, String name, String args) throws JSONException {
        return o("id", id, "type", "function", "function", o("name", name, "arguments", args));
    }

    private static JSONObject tool(String id, Object result) throws JSONException {
        return o("role", "tool", "tool_call_id", id, "content", result instanceof String ? result : result.toString());
    }

    /** The parts of one {name, args, ok, result} that matter, in a fixed order (a JSONObject prints its keys in any). */
    private static String describe(List<JSONObject> calls, int i) {
        JSONObject c = calls.get(i), r = c.optJSONObject("result");
        return c.optString("name") + " " + c.optJSONObject("args").optString("title") + " ok=" + c.optBoolean("ok")
                + " status=" + r.optString("status") + " message=" + r.optString("message");
    }

    // ---- callsFromTranscript ---------------------------------------------------------------------

    static void transcript() throws Exception {
        JSONArray t = new JSONArray()
                .put(o("role", "system", "content", "你是星仪"))
                .put(o("role", "user", "content", "{\"utterance\":\"放首歌\"}"))
                .put(o("role", "assistant", "content", "", "tool_calls", new JSONArray()
                        .put(call("a1", "netease_play", "{\"artist\":\"林俊杰\",\"title\":\"晴天\"}"))
                        .put(call("a2", "open_app", "{\"app\":\"哔哩哔哩\"}"))
                        .put(call("a3", "set_volume", "{这不是 JSON"))
                        .put(call("a4", "bili_search", "{\"keyword\":\"星辰亻旅行者\"}"))
                        .put(call("a5", "switch_input", "{\"name\":\"HDMI2\"}"))))
                .put(tool("a1", o("ok", true, "status", "observed", "message", "正在播放")))
                .put(tool("a2", o("ok", false, "status", "unavailable", "message", "电视上没有这个应用")))
                .put(tool("a3", o("ok", true, "status", "ok")))
                .put(tool("a4", "这不是 JSON 的结果"))
                .put(tool("a5", new JSONArray().put("也不是对象")))
                .put(o("role", "assistant", "content", "", "tool_calls", new JSONArray()
                        .put(call("b1", "set_volume", "{\"level\":30}"))));
        List<JSONObject> calls = Habits.callsFromTranscript(t);
        check(calls.size() == 1, "only the one call that really succeeded is returned: " + calls.size());
        eq(describe(calls, 0), "netease_play 晴天 ok=true status=observed message=正在播放", "the call comes back whole");
        check(calls.get(0).getJSONObject("args").getString("artist").equals("林俊杰"), "arguments parsed into an object");

        check(Habits.callsFromTranscript(null).isEmpty(), "no transcript, no calls");
        check(Habits.callsFromTranscript(new JSONArray()).isEmpty(), "an empty transcript has no calls");
        check(Habits.callsFromTranscript(new JSONArray().put(o("role", "user", "content", "你好"))).isEmpty(),
                "a chat-only transcript has no calls");
        check(Habits.callsFromTranscript(new JSONArray().put("不是消息")).isEmpty(), "junk in the transcript is skipped");

        // a call whose result never arrived, and one whose arguments are a JSON array instead of an object
        JSONArray odd = new JSONArray()
                .put(o("role", "assistant", "tool_calls", new JSONArray()
                        .put(call("c1", "open_app", "{\"app\":\"相册\"}"))
                        .put(call("c2", "open_app", "[1,2]"))
                        .put(call("", "open_app", "{\"app\":\"相册\"}"))
                        .put(o("id", "c4", "function", "没有 name"))))
                .put(tool("c2", o("ok", true)))
                .put(tool("c4", o("ok", true)));
        check(Habits.callsFromTranscript(odd).isEmpty(), "a missing result, array arguments and a nameless call are skipped");

        JSONArray two = new JSONArray()
                .put(o("role", "assistant", "tool_calls", new JSONArray()
                        .put(call("d1", "open_app", "{\"app\":\"相册\"}"))
                        .put(call("d2", "set_volume", "{\"level\":30}"))))
                .put(tool("d1", o("ok", true, "status", "observed")))
                .put(tool("d2", o("ok", true, "status", "observed")));
        List<JSONObject> pair = Habits.callsFromTranscript(two);
        check(pair.size() == 2 && pair.get(0).optString("name").equals("open_app")
                && pair.get(1).optString("name").equals("set_volume"), "two successful calls come back in order");
        check(pair.get(1).getJSONObject("args").getInt("level") == 30, "integer arguments survive");
        check(Habits.callsFromTranscript(new JSONArray().put(o("role", "assistant", "content", "没有工具调用"))).isEmpty(),
                "an answer with no tool calls gives nothing");
    }

    // ---- events ----------------------------------------------------------------------------------

    /** 「kind=value」 of every event, in order, for a call that ran. */
    private static String kept(String tool, JSONObject args, JSONObject result) {
        List<JSONObject> list = Habits.events(tool, args, result, NOW, 21);
        StringBuilder s = new StringBuilder();
        for (JSONObject e : list) {
            if (s.length() > 0) s.append(", ");
            s.append(e.optString("kind")).append('=').append(e.optString("value"))
                    .append(" t=").append(e.optLong("t") == NOW).append(" h=").append(e.optInt("h"));
        }
        return s.toString();
    }

    private static String kept(String tool, JSONObject args) throws JSONException { return kept(tool, args, null); }

    static void events() throws Exception {
        eq(kept("open_app", o("app", "哔哩哔哩")), "app=哔哩哔哩 t=true h=21", "an opened app is a habit");
        eq(kept("open_app", o("app", "  网易云音乐  ")), "app=网易云音乐 t=true h=21", "the value is trimmed");
        eq(kept("open_app", o("app", "   ")), "", "a blank app name is not a habit");
        eq(kept("open_app", o()), "", "an open_app without an app is not a habit");
        eq(kept("open_app", null), "", "a call without arguments gives nothing");

        eq(kept("bili_search", o("keyword", "星辰亻旅行者")), "bili=星辰亻旅行者 t=true h=21", "a B站 search word is a habit");
        eq(kept("bili_search", o("keyword", "")), "", "an empty search word is not");

        eq(kept("netease_play", o("artist", "林俊杰", "title", "晴天")),
                "artist=林俊杰 t=true h=21, song=晴天 t=true h=21", "artist and song are two habits");
        eq(kept("netease_play", o("artist", "林俊杰")), "artist=林俊杰 t=true h=21", "only an artist asked for");
        eq(kept("netease_play", o("title", "晴天")), "song=晴天 t=true h=21", "only a song asked for");
        eq(kept("netease_play", o("artist", " ", "title", "晴天")), "song=晴天 t=true h=21", "a blank artist is dropped");
        eq(kept("netease_play", o()), "", "a call with neither is not a habit");

        eq(kept("netease_playlist", o("kind", "favorites")), "playlist=favorites t=true h=21", "a playlist is a habit");
        eq(kept("netease_playlist", o("kind", "fm")), "playlist=fm t=true h=21", "so is the private FM");
        eq(kept("netease_playlist", o("name", "我喜欢的音乐")), "playlist=我喜欢的音乐 t=true h=21", "a playlist named in words");
        eq(kept("netease_playlist", o()), "", "a playlist call without a kind is not");

        eq(kept("set_volume", o("level", 30)), "volume=30 t=true h=21", "the volume the user asked for is a habit");
        eq(kept("set_volume", o("level", 0)), "volume=0 t=true h=21", "muted by volume 0 is still a level");
        eq(kept("set_volume", o("level", 100)), "volume=100 t=true h=21", "100 is a level");
        eq(kept("set_volume", o("level", 150)), "", "150 is not a level this TV has");
        eq(kept("set_volume", o("level", -1)), "", "a negative level is not");
        eq(kept("set_volume", o("level", 2.5)), "", "half a level is not a level");
        eq(kept("set_volume", o("level", "30")), "volume=30 t=true h=21", "a level the model wrote as a string");
        eq(kept("set_volume", o("level", "三十")), "", "a level in words is not read");
        eq(kept("set_volume", o()), "", "set_volume without a level is not a habit");

        eq(kept("adjust_volume", o("direction", "up"), o("ok", true, "volume", 25)), "volume=25 t=true h=21",
                "the volume an adjust really left behind is a habit");
        eq(kept("adjust_volume", o("direction", "mute"), o("ok", true)), "", "a mute reports no level, so nothing is kept");
        eq(kept("adjust_volume", o("direction", "up"), o("ok", true, "volume", 150)), "", "an impossible level is dropped");
        eq(kept("adjust_volume", o("direction", "up"), null), "", "no result, no level");

        eq(kept("switch_input", o("name", "HDMI2")), "input=HDMI2 t=true h=21", "the HDMI input used is a habit");
        eq(kept("switch_input", o("name", " ")), "", "a blank input is not");

        eq(kept("screen_read", o()), "", "reading the screen is not a habit");
        eq(kept("web_search", o("query", "明天天气")), "", "what the user searched the web for is never recorded");
        eq(kept("screen_click", o("id", 3)), "", "clicking is not a habit");
        eq(kept("", o()), "", "no tool, no habit");
        eq(kept(null, o()), "", "a nameless tool gives nothing");
    }

    // ---- record ----------------------------------------------------------------------------------

    private static JSONObject file(JSONObject... events) throws JSONException {
        return new JSONObject().put("events", new JSONArray(Arrays.asList(events)));
    }

    private static JSONArray list(JSONObject habits) { return habits.optJSONArray("events"); }

    static void recording() throws Exception {
        JSONObject h = new JSONObject();
        Habits.record(h, Collections.singletonList(Habits.event("app", "哔哩哔哩", NOW, 21)), NOW);
        check(list(h).length() == 1 && list(h).getJSONObject(0).optString("value").equals("哔哩哔哩"),
                "the first event creates the list");
        Habits.record(h, Arrays.asList(Habits.event("app", "网易云音乐", NOW, 21), Habits.event("bili", "猫", NOW, 21)), NOW);
        check(list(h).length() == 3, "more events are appended");

        Habits.record(h, Collections.<JSONObject>emptyList(), NOW);
        check(list(h).length() == 3, "an empty batch changes nothing");
        JSONObject bare = new JSONObject();
        Habits.record(bare, Collections.<JSONObject>emptyList(), NOW);
        check(!bare.has("events"), "an empty batch does not even create the list");
        Habits.record(null, Collections.singletonList(Habits.event("app", "x", NOW, 1)), NOW);
        Habits.record(bare, null, NOW);
        check(true, "recording into nothing never throws");

        JSONObject old = file(Habits.event("app", "九十一", NOW - 91 * DAY, 21), Habits.event("app", "八十九", NOW - 89 * DAY, 21));
        Habits.record(old, Collections.singletonList(Habits.event("app", "今天", NOW, 21)), NOW);
        check(list(old).length() == 2 && list(old).getJSONObject(0).optString("value").equals("八十九"),
                "an event older than 90 days is dropped, 89 days is kept");

        JSONObject full = new JSONObject();
        for (int i = 0; i < Habits.MAX_EVENTS; i++)
            Habits.record(full, Collections.singletonList(Habits.event("app", "应用" + i, NOW - i * 1000L, 12)), NOW);
        check(list(full).length() == Habits.MAX_EVENTS, "400 events fit");
        Habits.record(full, Collections.singletonList(Habits.event("app", "最新的", NOW, 12)), NOW);
        check(list(full).length() == Habits.MAX_EVENTS, "the 401st does not make the file grow");
        check(list(full).getJSONObject(0).optString("value").equals("应用1")
                && list(full).getJSONObject(Habits.MAX_EVENTS - 1).optString("value").equals("最新的"),
                "the oldest went, the newest is last");
    }

    // ---- forget ----------------------------------------------------------------------------------

    static void forgetting() throws Exception {
        JSONObject h = file(Habits.event("artist", "林俊杰", NOW, 21), Habits.event("artist", "薛之谦", NOW, 21),
                Habits.event("song", "林俊杰的晴天", NOW, 21), Habits.event("artist", "JJ", NOW, 21),
                Habits.event("artist", "林 俊杰", NOW, 21), Habits.event("playlist", "favorites", NOW, 21));
        int gone = Habits.forget(h, "林俊杰");
        check(gone == 3, "every event that mentions 林俊杰 goes: " + gone);
        check(list(h).length() == 3, "the rest is kept: " + list(h));
        check(list(h).getJSONObject(0).optString("value").equals("薛之谦")
                && list(h).getJSONObject(2).optString("value").equals("favorites"), "and stays in order");

        JSONObject c = file(Habits.event("artist", "JJ", NOW, 21), Habits.event("artist", "jj lin", NOW, 21),
                Habits.event("artist", "林 俊杰", NOW, 21), Habits.event("app", "哔哩哔哩", NOW, 21));
        check(Habits.forget(c, "jj") == 2, "letters match whatever their case, and spaces are ignored");
        check(Habits.forget(c, "林俊杰") == 1, "a value written with a space in it still matches");
        check(list(c).length() == 1, "only the unrelated app is left");

        JSONObject p = file(Habits.event("playlist", "favorites", NOW, 21), Habits.event("playlist", "fm", NOW, 21));
        check(Habits.forget(p, "我喜欢的音乐") == 1, "the playlist the user says by name goes");
        check(list(p).length() == 1 && list(p).getJSONObject(0).optString("value").equals("fm"), "the other playlist stays");

        JSONObject keep = file(Habits.event("artist", "林俊杰", NOW, 21));
        check(Habits.forget(keep, "") == 0 && Habits.forget(keep, "   ") == 0 && Habits.forget(keep, null) == 0,
                "an empty what removes nothing");
        check(list(keep).length() == 1, "and really removes nothing");
        check(Habits.forget(keep, "薛之谦") == 0, "a name nobody played removes nothing");
        check(Habits.forget(new JSONObject(), "林俊杰") == 0, "forgetting from an empty file is safe");
        check(Habits.forget(file(), "林俊杰") == 0, "forgetting from a file with no events is safe");
        check(Habits.forget(null, "林俊杰") == 0, "forgetting from nothing is safe");
    }

    // ---- summarize -------------------------------------------------------------------------------

    /** One hour in each band, so {@code n} events of one value land in {@code n} different bands: a file built this
     * way never trips the time-band rule by accident. */
    private static final int[] SPREAD = {21, 8, 10, 12, 15, 18, 1};

    /** {@code n} events of one kind and value, spread over the day, {@code startBand} days of the cycle apart. */
    private static void put(JSONArray events, String kind, String value, int n, int daysAgo, int startBand) {
        for (int i = 0; i < n; i++)
            events.put(Habits.event(kind, value, NOW - daysAgo * DAY - i * 3600_000L, SPREAD[(startBand + i) % 7]));
    }

    private static JSONObject day(String kind, String value, int n, int daysAgo) throws JSONException {
        JSONArray events = new JSONArray();
        put(events, kind, value, n, daysAgo, 0);
        return new JSONObject().put("events", events);
    }

    private static String summary(JSONObject habits) {
        List<String> lines = Habits.summarize(habits, NOW);
        StringBuilder s = new StringBuilder();
        for (String l : lines) s.append(s.length() == 0 ? "" : " | ").append(l);
        return s.toString();
    }

    static void summary() throws Exception {
        check(Habits.summarize(new JSONObject(), NOW).isEmpty(), "nothing recorded, nothing said");
        check(Habits.summarize(null, NOW).isEmpty(), "no file, nothing said");
        check(Habits.summarize(day("app", "哔哩哔哩", 2, 0), NOW).isEmpty(), "two uses are not a habit yet");

        JSONArray events = new JSONArray();
        put(events, "app", "哔哩哔哩", 12, 0, 0);
        put(events, "app", "网易云音乐", 7, 0, 1);
        put(events, "app", "云视听极光", 3, 0, 2);
        put(events, "app", "相册", 2, 0, 3);
        put(events, "bili", "星辰亻旅行者", 5, 0, 0);
        put(events, "bili", "猫咪日常", 2, 0, 1);
        put(events, "bili", "只搜过一次", 1, 0, 2);
        put(events, "artist", "林俊杰", 6, 0, 0);
        put(events, "artist", "薛之谦", 3, 0, 1);
        put(events, "artist", "周杰伦", 2, 0, 2);
        put(events, "song", "晴天", 3, 0, 0);
        put(events, "song", "江南", 2, 0, 1);
        put(events, "song", "只点过一次", 1, 0, 2);
        put(events, "playlist", "favorites", 4, 0, 0);
        put(events, "input", "HDMI2", 4, 0, 0);
        put(events, "input", "HDMI1", 1, 0, 1);
        for (int v : new int[]{20, 30, 40}) events.put(Habits.event("volume", String.valueOf(v), NOW - 3600_000L, 12));
        JSONObject all = new JSONObject().put("events", events);
        List<String> lines = Habits.summarize(all, NOW);
        eq(summary(all), "常用应用：哔哩哔哩（12次）、网易云音乐（7次）、云视听极光（3次）"
                        + " | B站常搜：星辰亻旅行者（5次）、猫咪日常（2次）"
                        + " | 常听：林俊杰（6次）、薛之谦（3次）、周杰伦（2次）"
                        + " | 常点的歌：《晴天》（3次）、《江南》（2次）"
                        + " | 常听歌单：我喜欢的音乐（4次）"
                        + " | 习惯音量：30 左右"
                        + " | 常用信号源：HDMI2（4次）",
                "every line, most telling first, and nothing below the thresholds");
        check(lines.size() == 7, "seven kinds, seven lines: " + lines.size());

        check(summary(day("app", "哔哩哔哩", 3, 0)).equals("常用应用：哔哩哔哩（3次）"), "three uses of an app are a habit");
        check(summary(day("app", "哔哩哔哩", 40, 31)).isEmpty(), "a month-old app is no longer a habit");
        check(summary(day("app", "哔哩哔哩", 3, 29)).equals("常用应用：哔哩哔哩（3次）"), "29 days ago still counts");
        check(summary(day("bili", "星辰亻旅行者", 2, 0)).equals("B站常搜：星辰亻旅行者（2次）"), "two searches are a habit");
        check(summary(day("bili", "星辰亻旅行者", 1, 0)).isEmpty(), "one search is not");
        check(summary(day("artist", "林俊杰", 2, 0)).equals("常听：林俊杰（2次）"), "two plays of an artist are a habit");
        check(summary(day("song", "晴天", 2, 0)).equals("常点的歌：《晴天》（2次）"), "two plays of a song are a habit");
        check(summary(day("song", "晴天", 1, 0)).isEmpty(), "one play is not");
        check(summary(day("playlist", "fm", 2, 0)).equals("常听歌单：私人FM（2次）"), "the private FM is said by name");
        check(summary(day("playlist", "favorites", 1, 0)).isEmpty(), "one playlist play is not a habit");
        check(summary(day("input", "HDMI2", 2, 0)).equals("常用信号源：HDMI2（2次）"), "two switches are a habit");
        check(summary(day("input", "HDMI2", 1, 0)).isEmpty(), "one switch is not");

        JSONObject equal = file(
                Habits.event("app", "先用的", NOW - 10 * DAY, 12), Habits.event("app", "先用的", NOW - 9 * DAY, 15),
                Habits.event("app", "先用的", NOW - 8 * DAY, 18),
                Habits.event("app", "后用的", NOW - 3 * DAY, 12), Habits.event("app", "后用的", NOW - 2 * DAY, 15),
                Habits.event("app", "后用的", NOW - 1 * DAY, 18));
        eq(summary(equal), "常用应用：后用的（3次）、先用的（3次）", "equal counts: the one used most recently comes first");
    }

    // ---- the time of day -------------------------------------------------------------------------

    /** One kind, one value, {@code n} events at the hours given. */
    private static JSONObject hours(String kind, String value, int... hours) throws JSONException {
        JSONArray events = new JSONArray();
        for (int i = 0; i < hours.length; i++) events.put(Habits.event(kind, value, NOW - i * 3600_000L, hours[i]));
        return new JSONObject().put("events", events);
    }

    static void bands() throws Exception {
        check(summary(hours("app", "哔哩哔哩", 21, 21, 21, 12, 9)).equals("常用应用：哔哩哔哩（5次）"),
                "3 of 5 in the evening says nothing");
        check(summary(hours("app", "哔哩哔哩", 21, 21, 21, 21, 9)).equals("常用应用：哔哩哔哩（5次），多在晚上"),
                "4 of 5 in the evening is a habit of the evening");
        check(summary(hours("app", "哔哩哔哩", 21, 21, 21, 21)).equals("常用应用：哔哩哔哩（4次）"),
                "four evenings are not enough to say when");
        check(summary(hours("app", "哔哩哔哩", 21, 21, 12, 12, 9)).equals("常用应用：哔哩哔哩（5次）"),
                "an even spread says nothing");
        check(summary(hours("app", "哔哩哔哩", 21, 21, 20, 23, 22, 22, 8, 8)).equals("常用应用：哔哩哔哩（8次），多在晚上"),
                "a kind with many events still needs the band to dominate");

        for (int h = 0; h <= 23; h++) {
            String band = h <= 5 ? "凌晨" : h <= 8 ? "早上" : h <= 11 ? "上午" : h <= 13 ? "中午" : h <= 17 ? "下午" : h <= 19 ? "傍晚" : "晚上";
            eq(summary(hours("app", "哔哩哔哩", h, h, h, h, h)), "常用应用：哔哩哔哩（5次），多在" + band, "hour " + h + " is " + band);
        }

        // the band belongs to the kind it was seen for, not to the whole file
        JSONObject both = hours("app", "哔哩哔哩", 21, 21, 21, 21, 21);
        JSONArray mixed = both.getJSONArray("events");
        for (int i = 0; i < 5; i++) mixed.put(Habits.event("artist", "林俊杰", NOW - i * 3600_000L, 8));
        eq(summary(both), "常用应用：哔哩哔哩（5次），多在晚上 | 常听：林俊杰（5次），多在早上",
                "each line carries its own time of day");

        JSONObject quiet = hours("volume", "30", 22, 22, 22, 22, 22);
        eq(summary(quiet), "习惯音量：30 左右，多在晚上", "the volume line can carry a band too");
    }

    // ---- the caps --------------------------------------------------------------------------------

    static void caps() throws Exception {
        JSONArray events = new JSONArray();
        put(events, "app", "哔哩哔哩", 12, 0, 0);
        put(events, "bili", "星辰亻旅行者", 5, 0, 0);
        put(events, "artist", "林俊杰", 6, 0, 0);
        put(events, "song", "晴天", 3, 0, 0);
        put(events, "playlist", "favorites", 4, 0, 0);
        put(events, "input", "HDMI2", 4, 0, 0);
        put(events, "volume", "30", 5, 0, 0);
        List<String> lines = Habits.summarize(new JSONObject().put("events", events), NOW);
        check(lines.size() <= Habits.MAX_LINES, "never more than 8 lines: " + lines.size());
        for (String l : lines) check(l.length() <= Habits.MAX_LINE_CHARS, "line fits in 40 characters (" + l.length() + "): " + l);

        // three artists with long names: the line keeps only what fits
        JSONArray longNames = new JSONArray();
        put(longNames, "artist", "这是一个非常非常非常长的歌手名字真的", 6, 0, 0);
        put(longNames, "artist", "第二个同样很长很长的歌手名字啊", 4, 0, 1);
        put(longNames, "artist", "第三个也不短的歌手名字哦哦哦", 3, 0, 2);
        List<String> cut = Habits.summarize(new JSONObject().put("events", longNames), NOW);
        check(cut.size() == 1 && cut.get(0).length() <= Habits.MAX_LINE_CHARS, "a long line is cut to 40 characters: " + cut);
        check(cut.get(0).equals("常听：这是一个非常非常非常长的歌手名字真的（6次）"),
                "the most-played artist is the one that survives: " + cut.get(0));

        JSONArray longWord = new JSONArray();
        put(longWord, "bili", "一个特别特别长的搜索词长到说不完的那种搜索词", 4, 0, 0);
        put(longWord, "bili", "另一个也很长很长的搜索词长到说不完的", 2, 0, 1);
        List<String> word = Habits.summarize(new JSONObject().put("events", longWord), NOW);
        check(word.size() == 1 && word.get(0).length() <= Habits.MAX_LINE_CHARS && word.get(0).contains("…"),
                "a long search word is shortened, not dropped: " + word);
        check(!word.get(0).contains("另一个"), "the second word does not fit and is left out: " + word.get(0));
    }

    // ---- the memory tools ------------------------------------------------------------------------

    private static Tool tool(List<Tool> tools, String name) {
        for (Tool t : tools) if (t.name().equals(name)) return t;
        return null;
    }

    static void tools() throws Exception {
        MemoryChecks.Mem store = new MemoryChecks.Mem(), habits = new MemoryChecks.Mem();
        JSONArray events = new JSONArray();
        put(events, "artist", "林俊杰", 3, 0, 0);
        put(events, "artist", "薛之谦", 2, 0, 1);
        habits.profile = new JSONObject().put("events", events);

        List<Tool> withHabits = MemoryTools.create(store, habits);
        check(withHabits.size() == 3 && tool(withHabits, "forget") != null, "the same three tools, with a habits store");
        Tool remember = tool(withHabits, "remember"), forget = tool(withHabits, "forget");
        remember.run(o("fact", "我喜欢林俊杰"), null);
        check(store.saves == 1 && habits.saves == 0, "remembering writes the profile only");

        JSONObject f = forget.run(o("what", "林俊杰"), null);
        check(f.getBoolean("ok") && f.getString("status").equals("forgotten"), "forget still forgets the fact");
        JSONArray removed = f.getJSONArray("removed");
        check(removed.length() == 2 && removed.getString(0).equals("我喜欢林俊杰")
                && removed.getString(1).equals("使用记录：3 条"), "what went is reported, habit records included: " + removed);
        eq(f.getString("message"), "已忘掉和「林俊杰」有关的 4 条记忆", "the message counts the records too");
        check(habits.saves == 1 && list(habits.profile).length() == 2, "the habit file lost them and was written once");
        check(store.saves == 2, "the profile was written once more");

        JSONObject onlyHabits = forget.run(o("what", "薛之谦"), null);
        check(onlyHabits.getBoolean("ok") && onlyHabits.getString("status").equals("forgotten"),
                "forget works when only habit records matched");
        check(onlyHabits.getJSONArray("removed").length() == 1
                && onlyHabits.getJSONArray("removed").getString(0).equals("使用记录：2 条"), "and says how many: " + onlyHabits);
        eq(onlyHabits.getString("message"), "已忘掉和「薛之谦」有关的 2 条记忆", "and counts them");
        check(store.saves == 2, "an unchanged profile is not written again");
        check(habits.saves == 2 && list(habits.profile).length() == 0, "the habit file is empty now");

        JSONObject none = forget.run(o("what", "周杰伦"), null);
        check(none.getBoolean("ok") && none.getString("status").equals("nothing"), "nothing anywhere is still nothing");
        eq(none.getString("message"), "没有和「周杰伦」有关的记忆", "and says so as before");
        check(habits.saves == 2, "no habit record went, so nothing was written");

        // without a habits store the tool behaves exactly as it did before this job
        MemoryChecks.Mem plain = new MemoryChecks.Mem();
        List<Tool> before = MemoryTools.create(plain);
        Tool plainForget = tool(before, "forget");
        tool(before, "remember").run(o("fact", "我喜欢林俊杰"), null);
        JSONObject pf = plainForget.run(o("what", "林俊杰"), null);
        check(pf.getJSONArray("removed").length() == 1 && pf.getJSONArray("removed").getString(0).equals("我喜欢林俊杰"),
                "create(store) reports only the facts: " + pf.getJSONArray("removed"));
        eq(pf.getString("message"), "已忘掉和「林俊杰」有关的 1 条记忆", "and counts only the facts");
        eq(plainForget.run(o("what", "薛之谦"), null).getString("message"), "没有和「薛之谦」有关的记忆",
                "and knows nothing about habit records");

        // a habits file that cannot be read must not cost the user the fact they asked to forget
        MemoryChecks.Mem broken = new MemoryChecks.Mem(), badHabits = new MemoryChecks.Mem();
        badHabits.failure = new java.io.IOException("磁盘满了");
        List<Tool> fragile = MemoryTools.create(broken, badHabits);
        tool(fragile, "remember").run(o("fact", "我喜欢林俊杰"), null);
        JSONObject still = tool(fragile, "forget").run(o("what", "林俊杰"), null);
        check(still.getBoolean("ok") && still.getString("status").equals("forgotten"), "a broken habit file does not break forget");
        check(still.getJSONArray("removed").length() == 1, "and is simply not reported: " + still.getJSONArray("removed"));
        check(broken.saves == 2 && badHabits.saves == 0, "the profile was still written");
    }
}
