package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** Checks for job notes-history (see briefs/notes-history.md): the 随手记 store and its three tools, the 30-day log
 * and recall_history. Everything runs on a fixed clock: 2026-11-05 14:30 in Asia/Shanghai. */
final class NoteChecks {
    private NoteChecks() { }

    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = nov(5, 14, 30);

    static void run() throws Exception {
        notesAdd();
        notesList();
        notesDone();
        notesSummary();
        historyAdd();
        historyQuery();
        historyDescribe();
        noteTools();
        recallTool();
        loopAnswer();
        brokenEntry();
    }

    /** Lead review: an entry without text (a hand-edited or torn file) normalizes to "", which every note contains. */
    private static void brokenEntry() throws Exception {
        JSONObject notes = new JSONObject().put("next_id", 2)
                .put("items", new JSONArray().put(new JSONObject().put("id", 1).put("at", NOW)));
        eq(Notes.add(notes, "明天买牛奶", NOW), "记下了：明天买牛奶", "a broken entry does not swallow every new note");
        check(notes.getJSONArray("items").length() == 2, "and the new note is in");
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static void eq(String got, String want, String label) {
        check(want.equals(got), label + ": got [" + got + "] want [" + want + "]");
    }

    private static long at(int month, int day, int hour, int minute) {
        Calendar c = new GregorianCalendar(TZ);
        c.clear();
        c.set(2026, month, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static long nov(int day, int hour, int minute) { return at(Calendar.NOVEMBER, day, hour, minute); }

    private static String repeat(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('字');
        return b.toString();
    }

    /** A notes file that already holds something: id, text, when, repeated. */
    private static JSONObject store(Object... idTextAt) throws JSONException {
        JSONArray items = new JSONArray();
        for (int i = 0; i < idTextAt.length; i += 3)
            items.put(new JSONObject().put("id", idTextAt[i]).put("text", idTextAt[i + 1]).put("at", idTextAt[i + 2]));
        return new JSONObject().put("next_id", 99).put("items", items);
    }

    private static JSONArray items(JSONObject o) { return o.optJSONArray("items"); }

    /** The texts of a store or log, in order. */
    private static List<String> said(JSONObject o) {
        List<String> out = new ArrayList<>();
        JSONArray a = items(o);
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optJSONObject(i).optString("text", a.optJSONObject(i).optString("said")));
        return out;
    }

    /** What Notes.add says when it refuses; "" when it did not refuse. */
    private static String refuses(JSONObject notes, String text) throws Exception {
        try { return Notes.add(notes, text, NOW); } catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    // ---- Notes.add ------------------------------------------------------------------------------

    static void notesAdd() throws Exception {
        JSONObject n = new JSONObject();
        eq(Notes.add(n, "明天买牛奶", NOW), "记下了：明天买牛奶", "a note is recorded with the words the user said");
        check(items(n).length() == 1 && items(n).getJSONObject(0).getInt("id") == 1, "first note gets id 1");
        check(items(n).getJSONObject(0).getLong("at") == NOW && n.getInt("next_id") == 2, "the time it was taken is kept");
        eq(Notes.add(n, "  给妈妈   打电话  ", NOW), "记下了：给妈妈 打电话", "trimmed and inner spaces collapsed");
        check(items(n).length() == 2 && items(n).getJSONObject(1).getInt("id") == 2, "ids count up");

        eq(refuses(n, ""), "要记什么？", "nothing to remember is refused");
        eq(refuses(n, "   "), "要记什么？", "blank is nothing to remember");
        eq(refuses(n, null), "要记什么？", "null is nothing to remember");
        eq(refuses(n, "。。。"), "要记什么？", "punctuation only is nothing to remember");
        eq(refuses(n, repeat(81)), "太长了，挑重点说一句", "81 characters refused");
        check(refuses(n, repeat(80)).startsWith("记下了："), "80 characters still fit");
        eq(refuses(n, "wifi密码是abcd1234"), "这类信息（密码、验证码、证件号）不记", "a password is never stored");
        eq(refuses(n, "验证码是8899"), "这类信息（密码、验证码、证件号）不记", "a verification code is never stored");
        eq(refuses(n, "我的银行卡号是6222020200112233"), "这类信息（密码、验证码、证件号）不记", "a card number is never stored");
        eq(refuses(n, "我家门牌号是1234567"), "这类信息（密码、验证码、证件号）不记", "six digits in a row is a secret");
        check(refuses(n, "我的房间号是1203").startsWith("记下了："), "four digits are just a number");
        eq(said(n).toString(), "[明天买牛奶, 给妈妈 打电话, " + repeat(80) + ", 我的房间号是1203]",
                "only the accepted notes are in the file");

        eq(Notes.add(n, "明天买牛奶。", NOW), "已经记着了：明天买牛奶", "the recognizer's 。 is the same note");
        eq(Notes.add(n, "明天 买牛奶", NOW), "已经记着了：明天买牛奶", "spaces are not a new note");
        eq(Notes.add(n, "买牛奶", NOW), "已经记着了：明天买牛奶", "a note contained in a pending one is not added twice");
        eq(Notes.add(n, "明天买牛奶和面包", NOW), "已经记着了：明天买牛奶", "a note containing a pending one is not added twice");
        check(items(n).length() == 4, "no duplicate ever reaches the file");

        JSONObject full = new JSONObject();
        for (int i = 1; i <= Notes.MAX_ITEMS; i++) Notes.add(full, "记" + i + "件事", NOW);
        check(items(full).length() == Notes.MAX_ITEMS, "50 notes fit");
        Notes.add(full, "新的一条", NOW);
        check(items(full).length() == Notes.MAX_ITEMS && !said(full).contains("记1件事")
                && said(full).get(Notes.MAX_ITEMS - 1).equals("新的一条"), "the 51st drops the oldest");

        JSONObject old = store(1, "很久以前的", NOW - 61 * DAY, 2, "还新鲜的", NOW - 59 * DAY);
        eq(Notes.add(old, "今天的", NOW), "记下了：今天的", "a fresh note is added next to an expiring one");
        check(!said(old).contains("很久以前的") && said(old).contains("还新鲜的") && items(old).length() == 2,
                "notes older than 60 days go on every add: " + said(old));
    }

    // ---- Notes.list -----------------------------------------------------------------------------

    static void notesList() throws Exception {
        JSONObject n = store(1, "明天买牛奶", nov(5, 10, 5), 4, "给妈妈打电话", nov(4, 21, 40));
        eq(Notes.list(n, NOW, TZ), "随手记里有 2 件：1 号，明天买牛奶（今天 10:05 记的）；4 号，给妈妈打电话（昨天 21:40 记的）。",
                "the list is spoken oldest first with the number and the time it was taken");
        eq(Notes.list(new JSONObject(), NOW, TZ), "随手记里没有东西。", "an empty 随手记 says so");
        eq(Notes.list(store(), NOW, TZ), "随手记里没有东西。", "a file with no items says so");
        JSONObject broken = new JSONObject().put("items", "not an array");
        eq(Notes.list(broken, NOW, TZ), "随手记里没有东西。", "a broken file reads as empty");
        JSONObject days = store(1, "今天早上的", nov(5, 0, 10), 2, "昨天深夜的", nov(4, 23, 50),
                3, "前天上午的", nov(3, 9, 0), 4, "月初的", nov(1, 8, 12));
        eq(Notes.list(days, NOW, TZ), "随手记里有 4 件：1 号，今天早上的（今天 0:10 记的）；2 号，昨天深夜的（昨天 23:50 记的）；"
                + "3 号，前天上午的（前天 9:00 记的）；4 号，月初的（11月1日 8:12 记的）。",
                "今天/昨天/前天 across midnight, older as a date, minutes always two digits");
        JSONObject stale = store(1, "过期了的", NOW - 61 * DAY, 2, "还没过期的", NOW - 59 * DAY);
        eq(Notes.list(stale, NOW, TZ), "随手记里有 1 件：2 号，还没过期的（9月7日 14:30 记的）。", "an expired note is not listed");
    }

    // ---- Notes.done -----------------------------------------------------------------------------

    static void notesDone() throws Exception {
        JSONObject n = store(1, "明天买牛奶", NOW, 2, "给妈妈打电话", NOW, 3, "买牛奶和面包", NOW);
        eq(Notes.done(n, "2").toString(), "[给妈妈打电话]", "crossed off by number");
        eq(said(n).toString(), "[明天买牛奶, 买牛奶和面包]", "the others stay");
        eq(Notes.done(n, "牛奶").toString(), "[明天买牛奶, 买牛奶和面包]", "crossed off by a word of the text");
        eq(said(n).toString(), "[]", "nothing is left");
        eq(Notes.done(n, "牛奶").toString(), "[]", "crossing off twice removes nothing");

        JSONObject all = store(1, "一", NOW, 2, "二", NOW, 3, "三", NOW);
        check(Notes.done(all, "all").size() == 3, "all crosses off everything");
        JSONObject cn = store(1, "一", NOW, 2, "二", NOW);
        check(Notes.done(cn, "全部").size() == 2, "全部 crosses off everything");
        JSONObject cn2 = store(1, "一", NOW, 2, "二", NOW);
        check(Notes.done(cn2, "所有").size() == 2, "所有 crosses off everything");

        JSONObject k = store(1, "明天买牛奶", NOW, 2, "买牛奶", NOW);
        eq(Notes.done(k, "买牛奶和面包").toString(), "[买牛奶]", "a note contained in what the user said is crossed off");
        eq(said(k).toString(), "[明天买牛奶]", "the longer note is not touched");

        JSONObject none = store(1, "明天买牛奶", NOW);
        check(Notes.done(none, "薛之谦").isEmpty() && Notes.done(none, "9").isEmpty() && Notes.done(none, "").isEmpty()
                && Notes.done(none, "   ").isEmpty() && Notes.done(none, null).isEmpty(),
                "a number nobody has, an unknown word and nothing at all remove nothing");
        check(items(none).length() == 1, "and leave the file alone");
        check(Notes.done(new JSONObject(), "all").isEmpty() && Notes.done(null, "all").isEmpty(),
                "crossing off in an empty or missing file is safe");
    }

    // ---- Notes.summary --------------------------------------------------------------------------

    static void notesSummary() throws Exception {
        JSONObject n = new JSONObject();
        for (int i = 1; i <= 12; i++) Notes.add(n, "事" + i + "号", nov(1, 8, 0) - (12 - i) * DAY);
        List<String> lines = Notes.summary(n, NOW, TZ);
        check(lines.size() == 10, "only the newest 10 pending notes reach 「关于用户」: " + lines.size());
        check(lines.get(0).startsWith("事3号") && lines.get(9).startsWith("事12号"), "the newest 10, oldest first: " + lines);
        eq(lines.get(0), "事3号（10月23日记）", "each line carries the date it was taken");
        check(Notes.summary(new JSONObject(), NOW, TZ).isEmpty(), "nothing pending, no lines");
        JSONObject longOne = store(1, repeat(50), nov(5, 9, 0));
        List<String> one = Notes.summary(longOne, NOW, TZ);
        check(one.size() == 1 && one.get(0).length() == 40 && one.get(0).equals(repeat(32) + "（11月5日记）"),
                "a long note is cut so the line stays at 40 characters, the date kept: " + one);
        JSONObject stale = store(1, "过期了的", NOW - 61 * DAY, 2, "今天的", NOW);
        eq(Notes.summary(stale, NOW, TZ).toString(), "[今天的（11月5日记）]", "expired notes are not in the summary");
    }

    // ---- the 30-day log -------------------------------------------------------------------------

    private static JSONObject entry(long at, String said, String replied, String playing, String app) throws JSONException {
        JSONObject e = new JSONObject().put("at", at).put("said", said).put("replied", replied).put("state", "done");
        if (playing != null) e.put("playing", playing);
        if (app != null) e.put("app", app);
        return e;
    }

    private static JSONObject log(JSONObject... entries) throws JSONException {
        return new JSONObject().put("items", new JSONArray(Arrays.asList(entries)));
    }

    /** The log every query check runs on, oldest first as the engine writes it: 40 and 20 days ago, the 1st,
     * the night before last, last night and this afternoon. */
    private static JSONObject days() throws JSONException {
        return log(entry(NOW - 40 * DAY, "很久以前搜的视频", "好的", null, null),
                entry(NOW - 20 * DAY, "二十天前搜的视频", "好的", null, null),
                entry(nov(1, 19, 0), "看新闻联播", "已经开始播放", "新闻联播", "CCTV"),
                entry(nov(3, 20, 0), "把音量调到30", "音量调到30了", null, null),
                entry(nov(4, 22, 58), "打开B站看猫咪视频", "正在播放《猫咪日常》", "猫咪日常", "哔哩哔哩"),
                entry(nov(5, 13, 30), "放一首林俊杰的歌", "正在放《江南》", "江南", "网易云音乐"));
    }

    static void historyAdd() throws Exception {
        JSONObject l = new JSONObject();
        History.add(l, "放一首林俊杰的歌", "正在放《江南》", "done", "江南", "网易云音乐", NOW);
        JSONObject e = items(l).getJSONObject(0);
        check(items(l).length() == 1 && e.getLong("at") == NOW && e.getString("said").equals("放一首林俊杰的歌")
                && e.getString("replied").equals("正在放《江南》") && e.getString("state").equals("done")
                && e.getString("playing").equals("江南") && e.getString("app").equals("网易云音乐"),
                "a finished task is written down with what was said, answered and playing");
        History.add(l, "", "好的", "done", null, null, NOW);
        History.add(l, null, "好的", "done", null, null, NOW);
        History.add(l, "   ", "好的", "done", null, null, NOW);
        check(items(l).length() == 1, "an empty utterance is not a task and is not written down");
        History.add(l, "把音量调到30", "音量调到30了", "done", null, "", NOW);
        JSONObject plain = items(l).getJSONObject(1);
        check(!plain.has("playing") && !plain.has("app") && plain.getString("state").equals("done"),
                "nothing playing and no app are simply left out");
        History.add(l, "打开B站", "打开了", "", "  ", null, NOW);
        check(!items(l).getJSONObject(2).has("state") && !items(l).getJSONObject(2).has("playing"),
                "a blank state or playing is left out too");

        JSONObject cuts = new JSONObject();
        History.add(cuts, repeat(100), repeat(200), "done", repeat(100), repeat(100), NOW);
        JSONObject c = items(cuts).getJSONObject(0);
        check(c.getString("said").length() == 80 && c.getString("replied").length() == 120
                && c.getString("playing").length() == 60 && c.getString("app").length() == 60,
                "said 80, replied 120, playing and app 60 characters");

        JSONObject full = new JSONObject();
        for (int i = 1; i <= History.MAX_ITEMS + 5; i++) History.add(full, "第" + i + "次", "好的", "done", null, null, NOW);
        check(items(full).length() == History.MAX_ITEMS, "the log keeps at most 500 entries");
        check(!items(full).getJSONObject(0).getString("said").equals("第1次")
                && items(full).getJSONObject(History.MAX_ITEMS - 1).getString("said").equals("第505次"),
                "the oldest entries go first");
        JSONObject old = log(entry(NOW - 31 * DAY, "三十一天前的", "好的", null, null),
                entry(NOW - 29 * DAY, "二十九天前的", "好的", null, null));
        History.add(old, "今天的", "好的", "done", null, null, NOW);
        check(said(old).toString().equals("[二十九天前的, 今天的]"), "entries older than 30 days go on every add: " + said(old));
    }

    static void historyQuery() throws Exception {
        JSONObject l = days();
        List<JSONObject> week = History.query(l, null, 0, NOW);
        check(week.size() == 4 && week.get(0).getString("said").equals("放一首林俊杰的歌"),
                "no days means a week, newest first: " + said(log(week.toArray(new JSONObject[0]))));
        check(week.get(3).getString("said").equals("看新闻联播"), "the oldest of the week comes last");
        check(History.query(l, "   ", 7, NOW).size() == 4, "a blank query lists the newest");
        check(History.query(l, null, 1, NOW).size() == 2, "one day reaches back to yesterday evening");
        check(History.query(l, null, 30, NOW).size() == 5, "30 days reaches the 20-day-old task");
        check(History.query(l, null, 99, NOW).size() == 5, "more than 30 days is clamped to 30");
        check(History.query(l, null, 30, NOW).get(4).getString("said").equals("二十天前搜的视频"),
                "the 40-day-old task is never reachable");
        check(History.query(new JSONObject(), "视频", 30, NOW).isEmpty() && History.query(null, null, 7, NOW).isEmpty(),
                "an empty or missing log has nothing to recall");

        eq(said(log(History.query(l, "林俊杰", 7, NOW).toArray(new JSONObject[0]))).toString(), "[放一首林俊杰的歌]",
                "the whole query matches what the user said");
        eq(said(log(History.query(l, "猫咪日常", 7, NOW).toArray(new JSONObject[0]))).toString(), "[打开B站看猫咪视频]",
                "the whole query matches what was playing");
        eq(said(log(History.query(l, "哔哩哔哩", 7, NOW).toArray(new JSONObject[0]))).toString(), "[打开B站看猫咪视频]",
                "the whole query matches the app");
        eq(said(log(History.query(l, "视频", 30, NOW).toArray(new JSONObject[0]))).toString(),
                "[打开B站看猫咪视频, 二十天前搜的视频]", "a query matching two tasks returns both, newest first");
        check(History.query(l, "薛之谦", 30, NOW).isEmpty(), "a word nobody said finds nothing");
        eq(said(log(History.query(l, "林俊杰 江南", 7, NOW).toArray(new JSONObject[0]))).toString(), "[放一首林俊杰的歌]",
                "when the whole query is not there, every part must be");
        eq(said(log(History.query(l, "林俊杰，江南", 7, NOW).toArray(new JSONObject[0]))).toString(), "[放一首林俊杰的歌]",
                "Chinese punctuation splits the query too");
        check(History.query(l, "音量 新闻", 7, NOW).isEmpty(), "parts that live in different tasks match nothing");
        eq(said(log(History.query(l, "林俊的歌", 7, NOW).toArray(new JSONObject[0]))).toString(), "[放一首林俊杰的歌]",
                "a misheard query still finds the task by its two-character pieces");
        check(History.query(l, "林俊的猫", 7, NOW).isEmpty(), "one piece out of three is not enough to match");
        check(History.query(l, "的那", 7, NOW).isEmpty(), "pieces that are only filler words match nothing");

        JSONObject many = new JSONObject();
        for (int i = 1; i <= 12; i++) History.add(many, "看视频" + i, "好的", "done", null, null, NOW - (12 - i) * 60 * 1000L);
        List<JSONObject> top = History.query(many, "视频", 7, NOW);
        check(top.size() == 8, "at most 8 entries come back: " + top.size());
        check(top.get(0).getString("said").equals("看视频12") && top.get(7).getString("said").equals("看视频5"),
                "and they are the newest ones: " + said(log(top.toArray(new JSONObject[0]))));
    }

    static void historyDescribe() throws Exception {
        JSONObject l = days();
        JSONArray a = items(l);
        eq(History.describe(a.getJSONObject(5), NOW, TZ),
                "今天 13:30 你说「放一首林俊杰的歌」，星仪回答「正在放《江南》」（当时在放《江南》）",
                "today, with what was playing");
        eq(History.describe(a.getJSONObject(4), NOW, TZ),
                "昨天 22:58 你说「打开B站看猫咪视频」，星仪回答「正在播放《猫咪日常》」（当时在放《猫咪日常》）",
                "yesterday, with what was playing");
        eq(History.describe(a.getJSONObject(3), NOW, TZ), "前天 20:00 你说「把音量调到30」，星仪回答「音量调到30了」",
                "the night before yesterday, nothing playing: no empty 《》");
        eq(History.describe(a.getJSONObject(2), NOW, TZ),
                "11月1日 19:00 你说「看新闻联播」，星仪回答「已经开始播放」（当时在放《新闻联播》）",
                "older than 前天 reads as a date");
    }

    // ---- the tools ------------------------------------------------------------------------------

    /** notes.json in memory: load hands out a copy, so a tool that does not save really changes nothing. */
    static final class NotesFile implements NoteTools.Store {
        JSONObject notes = new JSONObject();
        int saves;
        Exception failure;
        boolean onSave;
        @Override public JSONObject load() throws Exception {
            if (failure != null && !onSave) throw failure;
            return new JSONObject(notes.toString());
        }
        @Override public void save(JSONObject n) throws Exception {
            if (failure != null && onSave) throw failure;
            saves++;
            notes = new JSONObject(n.toString());
        }
    }

    /** history.json, read only, as the engine writes it. */
    static final class HistoryLog implements HistoryTools.Store {
        JSONObject log;
        Exception failure;
        HistoryLog(JSONObject log) { this.log = log; }
        @Override public JSONObject load() throws Exception {
            if (failure != null) throw failure;
            return new JSONObject(log.toString());
        }
    }

    private static Tool tool(List<Tool> tools, String name) {
        for (Tool t : tools) if (t.name().equals(name)) return t;
        return null;
    }

    static void noteTools() throws Exception {
        NotesFile store = new NotesFile();
        List<Tool> tools = NoteTools.create(store, () -> NOW, TZ);
        check(tools.size() == 3 && tool(tools, "note_add") != null && tool(tools, "note_list") != null
                && tool(tools, "note_done") != null, "three tools: note_add, note_list, note_done");
        for (Tool t : tools) check(t.gate(new JSONObject(), null) == Tool.Gate.read(), t.name() + " needs no plan");
        Tool add = tool(tools, "note_add"), list = tool(tools, "note_list"), done = tool(tools, "note_done");
        eq(add.describe(AgentCoreTest.o("text", "明天买牛奶"), null), "记下：明天买牛奶", "note_add describes itself");
        eq(list.describe(new JSONObject(), null), "看随手记", "note_list describes itself");
        eq(done.describe(AgentCoreTest.o("which", "牛奶"), null), "划掉：牛奶", "note_done describes itself");

        JSONObject r = add.run(AgentCoreTest.o("text", "明天买牛奶"), null);
        check(r.getBoolean("ok") && r.getString("status").equals("recorded"), "note_add records a new note");
        eq(r.getString("message"), "记下了：明天买牛奶", "note_add says what it kept, in one speakable sentence");
        check(store.saves == 1 && items(store.notes).length() == 1, "the note reached the file");
        JSONObject again = add.run(AgentCoreTest.o("text", "明天买牛奶"), null);
        check(again.getBoolean("ok") && again.getString("status").equals("exists"), "note_add knows it already has it");
        eq(again.getString("message"), "已经记着了：明天买牛奶", "and says so instead of writing it twice");
        check(store.saves == 1, "a duplicate is not written to the file");
        JSONObject empty = add.run(AgentCoreTest.o("text", " "), null);
        check(!empty.getBoolean("ok") && empty.getString("status").equals("rejected")
                && empty.getString("message").equals("要记什么？"), "note_add refuses nothing to remember");
        JSONObject tooLong = add.run(AgentCoreTest.o("text", repeat(81)), null);
        check(!tooLong.getBoolean("ok") && tooLong.getString("status").equals("rejected")
                && tooLong.getString("message").equals("太长了，挑重点说一句"), "note_add refuses an endless note");
        JSONObject secret = add.run(AgentCoreTest.o("text", "wifi密码是abcd1234"), null);
        check(!secret.getBoolean("ok") && secret.getString("status").equals("rejected")
                && secret.getString("message").contains("不记"), "note_add refuses a password: " + secret.getString("message"));
        check(store.saves == 1, "nothing refused was written");

        JSONObject shown = list.run(new JSONObject(), null);
        check(shown.getBoolean("ok") && shown.getString("status").equals("observed"), "note_list reads the file");
        eq(shown.getString("message"), "随手记里有 1 件：1 号，明天买牛奶（今天 14:30 记的）。", "note_list reads the list out");
        JSONObject first = shown.getJSONArray("items").getJSONObject(0);
        check(shown.getJSONArray("items").length() == 1 && first.getInt("id") == 1
                && first.getString("text").equals("明天买牛奶") && first.getString("when").equals("今天 14:30"),
                "and hands the model the numbers and times: " + first);
        JSONObject none = tool(NoteTools.create(new NotesFile(), () -> NOW, TZ), "note_list").run(new JSONObject(), null);
        check(none.getBoolean("ok") && none.getString("message").equals("随手记里没有东西。")
                && none.getJSONArray("items").length() == 0, "an empty 随手记 is not an error: " + none.getString("message"));

        add.run(AgentCoreTest.o("text", "给妈妈打电话"), null);
        JSONObject crossed = done.run(AgentCoreTest.o("which", "牛奶"), null);
        check(crossed.getBoolean("ok") && crossed.getString("status").equals("done"), "note_done crosses off by a word");
        eq(crossed.getString("message"), "划掉了：明天买牛奶。", "note_done says what went, in one speakable sentence");
        check(store.saves == 3 && items(store.notes).length() == 1, "the file lost the note");
        JSONObject missed = done.run(AgentCoreTest.o("which", "薛之谦"), null);
        check(!missed.getBoolean("ok") && missed.getString("status").equals("not_found"), "note_done says when there was nothing");
        eq(missed.getString("message"), "随手记里没有这件。随手记里有 1 件：2 号，给妈妈打电话（今天 14:30 记的）。",
                "and reads out what is still there, so the user can pick");
        check(store.saves == 3, "nothing crossed off, nothing written");

        store.failure = new java.io.IOException("磁盘满了");
        JSONObject broken = add.run(AgentCoreTest.o("text", "买面包"), null);
        check(!broken.getBoolean("ok") && broken.getString("status").equals("error"), "a broken file is reported, not thrown");
        check(broken.getString("message").equals("随手记没存上：磁盘满了"), "the error is speakable: " + broken.getString("message"));
        check(list.run(new JSONObject(), null).getString("status").equals("error"), "note_list reports it too");
        check(done.run(AgentCoreTest.o("which", "all"), null).getString("status").equals("error"),
                "note_done reports it instead of throwing");
        store.onSave = true;
        JSONObject unsaved = add.run(AgentCoreTest.o("text", "买面包"), null);
        check(!unsaved.getBoolean("ok") && unsaved.getString("status").equals("error"), "a failed write is reported too");
        check(done.run(AgentCoreTest.o("which", "all"), null).getString("status").equals("error"),
                "a crossed-off note that cannot be written is reported, not thrown");
        store.failure = null;
        store.onSave = false;
        JSONObject after = add.run(AgentCoreTest.o("text", "买面包"), null);
        check(after.getBoolean("ok") && store.saves == 4, "and works again once the file is back");
    }

    static void recallTool() throws Exception {
        HistoryLog store = new HistoryLog(days());
        List<Tool> tools = HistoryTools.create(store, () -> NOW, TZ);
        check(tools.size() == 1 && tools.get(0).name().equals("recall_history"), "one tool: recall_history");
        Tool recall = tools.get(0);
        check(recall.gate(new JSONObject(), null) == Tool.Gate.read(), "recall_history needs no plan");
        eq(recall.describe(AgentCoreTest.o("query", "视频"), null), "回忆和「视频」有关的事", "recall_history describes itself");
        eq(recall.describe(new JSONObject(), null), "回忆最近做过的事", "and without a query");

        JSONObject found = recall.run(AgentCoreTest.o("query", "猫咪日常"), null);
        check(found.getBoolean("ok") && found.getString("status").equals("observed"), "recall_history finds what was watched");
        eq(found.getString("message"), "找到 1 条：昨天 22:58 你说「打开B站看猫咪视频」，星仪回答「正在播放《猫咪日常》」（当时在放《猫咪日常》）",
                "and describes it for the model to read out");
        check(found.getJSONArray("items").length() == 1
                && found.getJSONArray("items").getJSONObject(0).getString("playing").equals("猫咪日常"),
                "the entries themselves come back too");
        JSONObject two = recall.run(AgentCoreTest.o("query", "视频", "days", 30), null);
        check(two.getJSONArray("items").length() == 2 && two.getString("message").startsWith("找到 2 条：")
                && two.getString("message").contains("；"), "two hits are joined with a semicolon: " + two.getString("message"));
        JSONObject no = recall.run(AgentCoreTest.o("query", "薛之谦"), null);
        check(no.getBoolean("ok") && no.getString("status").equals("observed") && no.getJSONArray("items").length() == 0,
                "finding nothing is not a failure");
        eq(no.getString("message"), "最近 7 天没有找到和「薛之谦」有关的记录。", "and says what it looked for, over how many days");
        eq(recall.run(AgentCoreTest.o("query", "薛之谦", "days", 30), null).getString("message"),
                "最近 30 天没有找到和「薛之谦」有关的记录。", "the days the user asked for are the days it reports");
        eq(recall.run(AgentCoreTest.o("query", "薛之谦", "days", "30"), null).getString("message"),
                "最近 30 天没有找到和「薛之谦」有关的记录。", "a model that sends days as a string is still understood");
        Tool nothing = HistoryTools.create(new HistoryLog(new JSONObject()), () -> NOW, TZ).get(0);
        eq(nothing.run(new JSONObject(), null).getString("message"), "最近 7 天还没有记录。",
                "an empty log says there is nothing yet");

        HistoryLog brokenLog = new HistoryLog(new JSONObject());
        brokenLog.failure = new java.io.IOException("读不了");
        Tool broken = HistoryTools.create(brokenLog, () -> NOW, TZ).get(0);
        JSONObject bad = broken.run(AgentCoreTest.o("query", "视频"), null);
        check(!bad.getBoolean("ok") && bad.getString("status").equals("error")
                && bad.getString("message").startsWith("历史记录没读出来："), "a file that cannot be read is reported, not thrown");
    }

    // ---- through the loop -----------------------------------------------------------------------

    /** 「帮我记一下明天买牛奶」 must cost one model round: note_add is read-gated and answers by itself. */
    static void loopAnswer() throws Exception {
        NotesFile store = new NotesFile();
        Tool add = tool(NoteTools.create(store, () -> NOW, TZ), "note_add");
        AgentCoreTest.Script m = new AgentCoreTest.Script().calls("note_add", AgentCoreTest.o("text", "明天买牛奶"));
        Ledger t = AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, add)
                .run(new AgentLoop.Request("帮我记一下明天买牛奶", "test"));
        check(m.seen.size() == 1, "one model round, no plan and no second round: " + m.seen.size());
        check(t.state == Ledger.State.ANSWERED, "the task ends answered, not planned: " + t.state);
        eq(t.reply, "记下了：明天买牛奶", "the tool's own sentence is what the user hears");
        check(store.saves == 1 && items(store.notes).length() == 1, "and the note really reached the file");
        check(t.actions.size() == 1 && t.actions.get(0).optBoolean("read")
                && t.actions.get(0).optString("tool").equals("note_add"), "the call is logged as a read");
    }

    // ---- end ----
}
