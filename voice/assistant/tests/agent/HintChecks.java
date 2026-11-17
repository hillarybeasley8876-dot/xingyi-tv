package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** Checks for Hints (job hints): the quiet card 星仪 puts up by itself when the screen comes on. Every moment is
 * built with a Calendar in Asia/Shanghai, so nothing here depends on the machine's own zone or clock. */
final class HintChecks {
    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    /** 2026-11-05 is a Thursday, 9:00 in the morning. */
    private static final long NOW = at(5, 9, 0), HOUR = 3_600_000L, DAY = 24 * HOUR;

    private HintChecks() { }

    static void run() throws Exception {
        wholeCard();
        comingUp();
        pendingNotes();
        thisHour();
        cutting();
        broken();
        whenAllowed();
        switchAsked();
        hintGate();
    }

    /** own-ui（2026-11-11 21:12–21:18）：用户说的「提示」是星仪右上角的执行面板，不是亮屏提示卡。只有真的在要求开关
     * 这张卡的句子才算（+1 开、−1 关）；问为什么、说看不到自己的输入，一律 0。 */
    private static void switchAsked() {
        for (String said : new String[]{"别弹提示了", "关掉提示", "把亮屏提示关了", "不要提示了", "别再弹提示了",
                "停掉提示卡", "不要提示", "别给我弹提示"})
            check(Hints.switchAsked(said) == -1, "asks for the card to stop: " + said);
        for (String said : new String[]{"打开提示", "恢复亮屏提示", "开启提示卡", "重新弹提示", "继续弹提示", "要弹提示"})
            check(Hints.switchAsked(said) == 1, "asks for the card to show: " + said);
        for (String said : new String[]{"现在就是因为没有提示我看不到我的输入你懂吗", "你看我现在每次给你提示提示提示提示提示",
                "为什么没有提示", "提示", "提示卡", "把提示关了怎么办", "你好", null, ""})
            check(Hints.switchAsked(said) == 0, "says nothing about the switch: " + said);
    }

    /** The tool's gate: set_hints runs only for the sentence that asked for it, and saves nothing otherwise. */
    private static void hintGate() throws Exception {
        JSONObject[] profile = {new JSONObject().put("facts", new JSONArray().put("用户喜欢猫"))};
        MemoryTools.Store store = new MemoryTools.Store() {
            @Override public JSONObject load() { return profile[0]; }
            @Override public void save(JSONObject p) { profile[0] = p; }
        };
        Tool t = HintTools.create(store).get(0);

        Tool.Gate denied = t.gate(o("on", false), turn("你看我现在每次给你提示提示提示提示提示"));
        check(denied.risk == Tool.Gate.Risk.DENY, "每次给你提示… is not a request to switch the card");
        check(denied.reason.contains("面板"), "and the model is told it is about 星仪's own panel: " + denied.reason);
        check(!profile[0].has("hints"), "a denied call saves nothing");
        check(t.gate(o("on", false), turn("别弹提示了")).risk == Tool.Gate.Risk.READ, "别弹提示了 asks for it: the call runs");
        check(t.gate(o("on", true), turn("别弹提示了")).risk == Tool.Gate.Risk.DENY, "the other way round is refused");
        check(t.gate(o("on", true), turn("打开提示")).risk == Tool.Gate.Risk.READ, "打开提示 asks for it: the call runs");
        check(t.gate(o("on", false), turn("现在就是因为没有提示我看不到我的输入你懂吗")).risk == Tool.Gate.Risk.DENY,
                "看不到我的输入 is not a request to switch the card");
        check(t.gate(o("on", false), turn(null)).risk == Tool.Gate.Risk.DENY, "no sentence at all: refused");

        // through the loop: the model hears why, and the profile is untouched
        AgentCoreTest.Script m = new AgentCoreTest.Script()
                .calls("plan", AgentCoreTest.plan("关掉亮屏提示", "关掉亮屏提示"), "set_hints", o("on", false))
                .text("面板的事我说给你听");
        AgentCoreTest.loop(m, new AgentCoreTest.Events(), (w, l) -> true, t)
                .run(new AgentLoop.Request("你看我现在每次给你提示提示提示提示提示", "test"));
        check(!profile[0].has("hints"), "the loop saved nothing for that sentence");
        check(m.seen.toString().contains("不要改这个设置"), "the model was told why");
        check(profile[0].optJSONArray("facts").length() == 1, "and the facts are still there");
    }

    /** A turn that says one sentence, the way AgentEngine hands it to a tool. */
    private static Tool.Turn turn(final String said) {
        return new Tool.Turn() {
            @Override public String utterance() { return said; }
            @Override public String source() { return "test"; }
            @Override public boolean cancelled() { return false; }
            @Override public void progress(String p) { }
            @Override public void say(String s) { }
            @Override public int round() { return 1; }
        };
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static void eq(String got, String want, String label) {
        check(want.equals(got), label + ": got [" + got + "] want [" + want + "]");
    }

    private static JSONObject o(Object... kv) throws JSONException { return AgentCoreTest.o(kv); }

    private static long at(int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    /** A schedule item as the book stores it; describe() reads the clock back off `at`. */
    private static JSONObject item(String kind, String what, long at) throws JSONException {
        return o("id", 1, "kind", kind, "what", what, "repeat", "once", "at", at, "created", NOW);
    }

    private static JSONObject book(JSONObject... items) throws JSONException {
        return o("items", new JSONArray(Arrays.asList(items)));
    }

    private static JSONObject note(String text, long at) throws JSONException {
        return o("id", 1, "text", text, "at", at);
    }

    private static JSONObject notes(JSONObject... items) throws JSONException {
        return o("items", new JSONArray(Arrays.asList(items)));
    }

    private static JSONObject ev(String kind, String value, long t, int h) throws JSONException {
        return o("t", t, "h", h, "kind", kind, "value", value);
    }

    private static JSONObject habits(JSONObject... events) throws JSONException {
        return o("events", new JSONArray(Arrays.asList(events)));
    }

    /** Three events of one kind and value, on three different days, all at the given local hour. */
    private static JSONObject three(String kind, String value, int h) throws JSONException {
        return habits(ev(kind, value, NOW - HOUR, h), ev(kind, value, NOW - DAY, h), ev(kind, value, NOW - 2 * DAY, h));
    }

    private static List<String> card(JSONObject habits, JSONObject notes, JSONObject book) {
        return Hints.card(habits, notes, book, NOW, TZ);
    }

    private static List<String> cardAt(long now, JSONObject habits, JSONObject notes, JSONObject book) {
        return Hints.card(habits, notes, book, now, TZ);
    }

    // ---- the card as a whole ---------------------------------------------------------------------

    static void wholeCard() throws Exception {
        check(card(null, null, null).isEmpty(), "nothing at all: no card");
        JSONObject book = book(item("remind", "该吃药了", NOW + 11 * HOUR));
        eq(card(null, null, book).get(0), "今天 20:00 提醒你：该吃药了", "only a schedule: that one line");
        check(card(null, null, book).size() == 1, "only a schedule: exactly one line");
        eq(card(null, notes(note("买牛奶", NOW - HOUR)), null).get(0), "随手记：买牛奶", "only a note: that one line");
        check(card(null, notes(note("买牛奶", NOW - HOUR)), null).size() == 1, "only a note: exactly one line");
        JSONObject artist = three("artist", "林俊杰", 9);
        eq(card(artist, null, null).get(0), "这个时候你常听林俊杰，说「放首林俊杰的歌」", "only a habit: that one line");
        check(card(artist, null, null).size() == 1, "only a habit: exactly one line");

        List<String> all = card(artist, notes(note("买牛奶", NOW - HOUR)), book);
        check(all.size() == 3, "all three sources: three lines");
        eq(all.get(0), "今天 20:00 提醒你：该吃药了", "all three: the schedule comes first");
        eq(all.get(1), "随手记：买牛奶", "all three: the 随手记 comes second");
        eq(all.get(2), "这个时候你常听林俊杰，说「放首林俊杰的歌」", "all three: the habit comes last");
        check(card(artist, notes(note("买牛奶", NOW - HOUR), note("给妈妈打电话", NOW - DAY)),
                book(item("remind", "该吃药了", NOW + HOUR), item("task", "关电视", NOW + 2 * HOUR))).size() == 3,
                "never more than three lines");
    }

    // ---- 1. what is coming up -------------------------------------------------------------------

    static void comingUp() throws Exception {
        check(!card(null, null, book(item("remind", "该吃药了", NOW + 11 * HOUR))).isEmpty(),
                "11 hours ahead is still coming up");
        check(card(null, null, book(item("remind", "该吃药了", NOW + 13 * HOUR))).isEmpty(),
                "13 hours ahead is too far to mention");
        check(!card(null, null, book(item("remind", "该吃药了", NOW + 12 * HOUR))).isEmpty(),
                "exactly 12 hours ahead still counts");
        check(card(null, null, book(item("remind", "该吃药了", NOW - HOUR))).isEmpty(),
                "an item whose time has passed is not coming up");
        check(card(null, null, book(item("remind", "该吃药了", NOW))).isEmpty(),
                "an item due this very moment is not coming up");
        List<String> two = card(null, null, book(item("task", "关电视", NOW + 11 * HOUR), item("remind", "该吃药了", NOW + 5 * HOUR)));
        eq(two.get(0), "今天 14:00 提醒你：该吃药了", "two items: the soonest one");
        check(card(null, null, book(item("task", "关电视", NOW + 13 * HOUR), item("task", "开灯", NOW + 20 * HOUR))).isEmpty(),
                "two items, both too far: nothing");
        eq(card(null, null, book(item("task", "播放新闻联播", NOW + 2 * HOUR))).get(0), "今天 11:00 播放新闻联播",
                "a task is said without 「提醒你」");
        check(card(null, null, o()).isEmpty(), "a book without items: nothing");
        check(card(null, null, book()).isEmpty(), "a book with an empty list: nothing");

        // 20:00 on the 5th, the same item tomorrow morning: 「明天 7:00 播放新闻联播」 is 11 hours away.
        long evening = at(5, 20, 0);
        JSONObject tomorrow = book(item("task", "播放新闻联播", at(6, 7, 0)));
        eq(cardAt(evening, null, null, tomorrow).get(0), "明天 7:00 播放新闻联播", "tomorrow morning, from the evening before");
        check(cardAt(evening, null, null, book(item("task", "播放新闻联播", at(6, 9, 0)))).isEmpty(),
                "tomorrow 9:00 is 13 hours from 20:00: too far");
        JSONObject daily = o("items", new JSONArray().put(o("id", 1, "kind", "task", "what", "关电视", "repeat", "daily",
                "hour", 23, "minute", 0, "at", at(5, 23, 0), "created", NOW)));
        eq(cardAt(evening, null, null, daily).get(0), "每天 23:00 关电视", "a repeating item keeps its own wording");
    }

    // ---- 2. what is still on the 随手记 ---------------------------------------------------------

    static void pendingNotes() throws Exception {
        check(card(null, notes(), null).isEmpty(), "no notes: no line");
        check(card(null, o(), null).isEmpty(), "a notes file without items: no line");
        eq(card(null, notes(note("买牛奶", NOW - HOUR)), null).get(0), "随手记：买牛奶", "one note is named");
        eq(card(null, notes(note("买牛奶", NOW - HOUR), note("给妈妈打电话", NOW - DAY)), null).get(0),
                "随手记里有 2 件没划掉", "two notes are counted");
        eq(card(null, notes(note("买牛奶", NOW), note("给妈妈打电话", NOW), note("交电费", NOW)), null).get(0),
                "随手记里有 3 件没划掉", "three notes are counted");
        check(card(null, notes(note("买牛奶", NOW - 61 * DAY)), null).isEmpty(), "a note older than 60 days is ignored");
        eq(card(null, notes(note("买牛奶", NOW - HOUR), note("旧事", NOW - 61 * DAY)), null).get(0), "随手记：买牛奶",
                "one fresh note beside an expired one is still alone");
        eq(card(null, notes(note("刚好六十天", NOW - 60 * DAY)), null).get(0), "随手记：刚好六十天",
                "a note exactly 60 days old has not expired yet");
        check(card(null, notes(o("id", 1, "at", NOW)), null).isEmpty(), "a note without text is nothing to show");
    }

    // ---- 3. what the user does at this hour ------------------------------------------------------

    static void thisHour() throws Exception {
        eq(card(three("artist", "林俊杰", 9), null, null).get(0), "这个时候你常听林俊杰，说「放首林俊杰的歌」",
                "an artist played at this hour");
        eq(card(three("bili", "星辰亻旅行者", 9), null, null).get(0), "这个时候你常在B站看星辰亻旅行者，说「B站搜星辰亻旅行者」",
                "a B站 search at this hour");
        eq(card(three("app", "哔哩哔哩", 9), null, null).get(0), "这个时候你常开哔哩哔哩", "an app opened at this hour");
        check(!card(three("app", "哔哩哔哩", 8), null, null).isEmpty(), "the hour before counts");
        check(!card(three("app", "哔哩哔哩", 10), null, null).isEmpty(), "the hour after counts");
        check(card(three("app", "哔哩哔哩", 7), null, null).isEmpty(), "two hours before is another time of day");
        check(card(three("app", "哔哩哔哩", 11), null, null).isEmpty(), "two hours after is another time of day");

        // midnight: 23 and 0 are neighbours, on both sides.
        long lateEvening = at(5, 23, 30), afterMidnight = at(5, 0, 30);
        JSONObject midnight = habits(ev("app", "哔哩哔哩", at(5, 0, 10), 0), ev("app", "哔哩哔哩", at(4, 0, 10), 0),
                ev("app", "哔哩哔哩", at(3, 0, 10), 0));
        check(!cardAt(lateEvening, midnight, null, null).isEmpty(), "23:30 sees the habit of the hour after midnight");
        JSONObject beforeMidnight = habits(ev("app", "哔哩哔哩", at(4, 23, 10), 23), ev("app", "哔哩哔哩", at(3, 23, 10), 23),
                ev("app", "哔哩哔哩", at(2, 23, 10), 23));
        check(!cardAt(afterMidnight, beforeMidnight, null, null).isEmpty(), "00:30 sees the habit of the hour before midnight");
        check(!cardAt(afterMidnight, midnight, null, null).isEmpty(), "00:30 sees the habit of midnight itself");

        // how often, and on how many days
        check(card(habits(ev("app", "哔哩哔哩", NOW - HOUR, 9), ev("app", "哔哩哔哩", NOW - DAY, 9)), null, null).isEmpty(),
                "two events are not a habit");
        JSONObject oneDay = habits(ev("app", "哔哩哔哩", NOW - HOUR, 9), ev("app", "哔哩哔哩", NOW - 2 * HOUR, 9),
                ev("app", "哔哩哔哩", NOW - 3 * HOUR, 9));
        check(card(oneDay, null, null).isEmpty(), "three events on one day are not a habit");
        JSONObject twoDays = habits(ev("app", "哔哩哔哩", NOW - HOUR, 9), ev("app", "哔哩哔哩", NOW - 2 * HOUR, 9),
                ev("app", "哔哩哔哩", NOW - DAY, 9));
        eq(card(twoDays, null, null).get(0), "这个时候你常开哔哩哔哩", "three events on two days are a habit");

        // kinds that cannot be suggested out loud
        check(card(three("volume", "30", 9), null, null).isEmpty(), "a volume is not a habit to suggest");
        check(card(three("song", "晴天", 9), null, null).isEmpty(), "a song is not a habit to suggest");
        check(card(three("input", "HDMI2", 9), null, null).isEmpty(), "an input is not a habit to suggest");
        check(card(three("playlist", "favorites", 9), null, null).isEmpty(), "a playlist is not a habit to suggest");

        // which group wins
        JSONObject busy = habits(ev("artist", "林俊杰", NOW - 2 * HOUR, 9), ev("artist", "林俊杰", NOW - DAY, 9),
                ev("artist", "林俊杰", NOW - 2 * DAY, 9), ev("artist", "林俊杰", NOW - 3 * DAY, 9),
                ev("artist", "周杰伦", NOW - HOUR, 9), ev("artist", "周杰伦", NOW - DAY - HOUR, 9),
                ev("artist", "周杰伦", NOW - 2 * DAY - HOUR, 9));
        eq(card(busy, null, null).get(0), "这个时候你常听林俊杰，说「放首林俊杰的歌」",
                "the group with the most events wins, even when the other was used more recently");
        JSONObject tie = habits(ev("artist", "林俊杰", NOW - HOUR, 9), ev("artist", "林俊杰", NOW - DAY, 9),
                ev("artist", "林俊杰", NOW - 2 * DAY, 9), ev("artist", "周杰伦", NOW - 2 * HOUR, 9),
                ev("artist", "周杰伦", NOW - DAY - HOUR, 9), ev("artist", "周杰伦", NOW - 2 * DAY - HOUR, 9));
        eq(card(tie, null, null).get(0), "这个时候你常听林俊杰，说「放首林俊杰的歌」",
                "as many events each: the one used most recently wins");
        check(card(tie, null, null).size() == 1, "only one habit line, however many groups qualify");

        // how far back it looks
        JSONObject old = habits(ev("app", "哔哩哔哩", NOW - 40 * DAY, 9), ev("app", "哔哩哔哩", NOW - 41 * DAY, 9),
                ev("app", "哔哩哔哩", NOW - 42 * DAY, 9));
        check(card(old, null, null).isEmpty(), "events older than 30 days are ignored");
        JSONObject mostlyOld = habits(ev("app", "哔哩哔哩", NOW - HOUR, 9), ev("app", "哔哩哔哩", NOW - 40 * DAY, 9),
                ev("app", "哔哩哔哩", NOW - 41 * DAY, 9));
        check(card(mostlyOld, null, null).isEmpty(), "one fresh event does not revive two old ones");
    }

    // ---- no line over 40 characters -------------------------------------------------------------

    static void cutting() throws Exception {
        String longWhat = "把这件非常非常长的事情从头到尾说一遍说一遍说一遍说一遍说一遍说一遍说一遍";
        String schedule = card(null, null, book(item("remind", longWhat, NOW + 2 * HOUR))).get(0);
        check(schedule.length() == 40, "a long schedule line is cut to 40 characters: " + schedule.length());
        check(schedule.endsWith("…"), "a cut schedule line says it was cut");
        String longNote = "买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶";
        String note = card(null, notes(note(longNote, NOW - HOUR)), null).get(0);
        check(note.length() == 40, "a long note is cut to 40 characters: " + note.length());
        check(note.startsWith("随手记："), "a cut note still says what it is");
        String exact = card(null, notes(note("买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶买牛奶", NOW - HOUR)), null).get(0);
        check(exact.length() == 40 && !exact.endsWith("…"), "a line of exactly 40 characters is left alone");

        String artist = card(three("artist", "一个非常非常长的歌手名字真的非常长", 9), null, null).get(0);
        check(artist.length() <= 40 && artist.contains("…"), "a long artist name is cut to fit: " + artist.length());
        check(artist.endsWith("的歌」"), "a cut artist line keeps the sentence: " + artist);
        String bili = card(three("bili", "一个非常非常长的搜索词真的非常长", 9), null, null).get(0);
        check(bili.length() <= 40 && bili.contains("…"), "a long B站 keyword is cut to fit: " + bili.length());
        check(bili.endsWith("」"), "a cut B站 line keeps the sentence: " + bili);
        StringBuilder longApp = new StringBuilder();
        while (longApp.length() < 40) longApp.append("哔哩");
        String app = card(three("app", longApp.toString(), 9), null, null).get(0);
        check(app.length() == 40 && app.endsWith("…"), "a long app name is cut: " + app.length());
        check(card(three("artist", "林俊杰", 9), null, null).get(0).length() <= 40, "a short artist line fits as it is");
    }

    // ---- broken files never throw ---------------------------------------------------------------

    static void broken() throws Exception {
        check(card(null, null, null).isEmpty(), "three missing files: an empty card, no exception");
        check(card(o("events", "not an array"), null, null).isEmpty(), "events that are not an array: no habit line");
        check(card(o("events", new JSONArray().put(5).put("x")), null, null).isEmpty(), "events that are not objects");
        check(card(null, o("items", 5), null).isEmpty(), "notes that are not an array: no 随手记 line");
        check(card(null, null, o("items", new JSONArray().put(5).put("x"))).isEmpty(), "book items that are not objects");
        check(card(o("events", new JSONArray().put(o("t", "x", "h", "y", "kind", 5, "value", 7))), null, null).isEmpty(),
                "an event of the wrong types is skipped");
        check(card(habits(o("kind", "app", "value", "哔哩哔哩")), null, null).isEmpty(), "an event without a time is skipped");
        check(card(habits(ev("app", "哔哩哔哩", NOW - HOUR, 99)), null, null).isEmpty(), "an event at hour 99 is skipped");
        check(card(habits(ev("app", "", NOW - HOUR, 9), ev("app", "  ", NOW - DAY, 9)), null, null).isEmpty(),
                "events without a value are skipped");

        JSONObject good = note("买牛奶", NOW - HOUR);
        check(card(null, o("items", new JSONArray().put(7).put(good)), null).size() == 1,
                "a broken note beside a good one still leaves the good one");
        List<String> partial = card(o("events", 5), notes(note("买牛奶", NOW - HOUR)),
                book(item("remind", "该吃药了", NOW + 2 * HOUR)));
        check(partial.size() == 2, "a broken habits file only costs its own line");
        eq(partial.get(0), "今天 11:00 提醒你：该吃药了", "the schedule line survives a broken habits file");
        check(Hints.card(null, null, null, NOW, null).isEmpty(), "no time zone: an empty card, no exception");
        check(noThrow(), "no time zone: the machine's own is used and nothing throws");
    }

    /** The machine's own zone is not Asia/Shanghai, so only "it did not throw" can be said about a null zone. */
    private static boolean noThrow() throws JSONException {
        try {
            Hints.card(null, null, book(item("remind", "该吃药了", NOW + 2 * HOUR)), NOW, null);
            Hints.allowed(0, NOW, null);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- when a card may show -------------------------------------------------------------------

    static void whenAllowed() throws Exception {
        check(!Hints.allowed(0, at(5, 1, 0), TZ), "1:00 is too late at night for a card");
        check(!Hints.allowed(0, at(5, 3, 30), TZ), "3:30 is too late at night for a card");
        check(!Hints.allowed(0, at(5, 5, 59), TZ), "5:59 is still night");
        check(Hints.allowed(0, at(5, 6, 0), TZ), "6:00 is morning enough");
        check(Hints.allowed(0, at(5, 0, 59), TZ), "0:59 is before the quiet hours");
        check(Hints.allowed(0, at(5, 23, 0), TZ), "23:00 is fine");
        check(Hints.allowed(0, NOW, TZ), "the first card ever may show");
        check(Hints.allowed(-1, NOW, TZ), "no last card recorded: it may show");
        check(!Hints.allowed(NOW - (2 * HOUR + 59 * 60_000L), NOW, TZ), "2 h 59 min after the last card is too soon");
        check(Hints.allowed(NOW - 3 * HOUR, NOW, TZ), "3 h after the last card is long enough");
        check(!Hints.allowed(NOW - 3 * HOUR + 1, NOW, TZ), "a millisecond short of 3 h is too soon");
        check(!Hints.allowed(NOW - 5 * HOUR, at(5, 2, 0), TZ), "the quiet hours beat a long enough gap");
        check(Hints.MIN_GAP_MS == 3 * 3_600_000L, "the gap between cards is three hours");
    }
}
