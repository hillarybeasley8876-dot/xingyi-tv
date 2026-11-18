package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 常提到的名字 — the names this user keeps coming back to, and the other ways XiaoAi's recognition has written them
 * (files/agent/lexicon.json; pure logic, the TV side only loads and saves the JSONObject).
 *
 * <p>Why (2026-11-19, the user: 「记忆归纳整理系统也需要优化…比如经常理解 tibo，而不是让我读 T I B O」). After 30 days and
 * 190 requests the memory held four facts, one alias and four lines of counted habits, and none of the names the user
 * asked about most (Tibo, MiniMax, OpenAI, 智谱). 2026-11-06 00:04: 「看一下 Tibo 的推特」 was written 「TABLE」 three
 * times, 星仪 asked 「TABLE 指的是哪个」, and only 「TI BO 这个人」, said letter by letter, was understood. The next day it
 * was 「TIBA」. {@link Heard} looks the last 30 days up again for every request and only knows two strict shapes (a part
 * of a longer name, one letter wrong); it keeps nothing, so the same repair is paid for again next week.
 *
 * <p>This class keeps what such an exchange teaches: the name as the answer spelled it, how often and when it came up,
 * and each other spelling the recognition gave it — learned where the user had to say it again. A request is then
 * checked against it: a spelling learned before, or one that reads like a name that comes up often, is handed to the
 * model as a {@link Heard.Hint}, and the profile block lists the names that come up most.
 *
 * <p><b>This file is the lead's stub: the API and its contract.</b> The implementation is the job
 * {@code lexicon-core} (briefs/lexicon-core.md); tests/agent/LexiconOracleChecks.java is its oracle and runs once
 * {@link #IMPLEMENTED} is true.
 */
public final class Lexicon {
    /** The oracle runs only when this is true: master stays green while the job is out. */
    static final boolean IMPLEMENTED = false;

    /** The file is read for every request on a 2 GB TV: few names, few spellings each, few hints, a short line. */
    public static final int MAX_NAMES = 60, MAX_HEARD = 6, MAX_HINTS = 3, MAX_LINE_NAMES = 6;
    /** A spelling is tied to a name said again only this soon after the turn that did not get it. */
    public static final long REPAIR_WINDOW_MS = 3 * 60_000L;
    /** A name nobody came back to for this long is dropped. */
    public static final long FORGET_AFTER_MS = 90L * 24 * 3600_000L;

    private Lexicon() { }

    /** One finished turn as the history keeps it. */
    public static final class Turn {
        /** What the recognition wrote, and what 星仪 answered. */
        public final String said, replied;
        /** The task's state when it is known (answered, done, cancelled, awaiting_user, failed, interrupted), else "":
         * then the reply tells (「已停止。」 is a cancelled turn, an answer that ends in a question asked back). */
        public final String state;
        /** When it ended (ms). Turns are learned once, in this order. */
        public final long at;
        /** Names the task's tools acted on, spelled right (a stock's name, an UP主, a singer, an account); may be empty. */
        public final List<String> names;

        public Turn(String said, String replied, String state, long at, List<String> names) {
            this.said = said == null ? "" : said;
            this.replied = replied == null ? "" : replied;
            this.state = state == null ? "" : state;
            this.at = at;
            this.names = names == null ? new ArrayList<String>() : names;
        }
    }

    /** An empty lexicon. */
    public static JSONObject empty() { return new JSONObject(); }

    /**
     * Learns from the turn that just finished: {@code recent} is oldest first and its last element is that turn; the
     * ones before it are only looked at for a repair (a word an earlier turn did not get, said again now). A turn is
     * learned once: one whose {@code at} is not later than the last one learned changes nothing. Never throws.
     */
    public static void learn(JSONObject lexicon, List<Turn> recent) { }

    /**
     * The words of a request that are another spelling of a name in the lexicon, at most {@link #MAX_HINTS}: a spelling
     * learned before, a name written with spaces or letter by letter, a Chinese transliteration that reads like a
     * Latin name, or a near miss of a name that came up at least twice. Nothing for a name written as the lexicon has
     * it, and nothing for a name not used within {@link #FORGET_AFTER_MS} before {@code now}.
     */
    public static List<Heard.Hint> hints(JSONObject lexicon, String utterance, long now) { return new ArrayList<>(); }

    /** The hints as one line for the model (environment.heard_like), "" when there are none. */
    public static String line(List<Heard.Hint> hints) { return ""; }

    /** At most two short lines for 「关于用户」: the names that come up most, newest and most frequent first. */
    public static List<String> lines(JSONObject lexicon, long now) { return new ArrayList<>(); }

    /** Drops every name that mentions {@code what} (and with it its spellings), and every spelling that is
     * {@code what}. @return how many names and spellings went */
    public static int forget(JSONObject lexicon, String what) { return 0; }

    /** What the 星仪 app's memory page shows: {name, count, last, heard:[…]} per name, most used first. */
    public static JSONArray names(JSONObject lexicon) { return new JSONArray(); }
}
