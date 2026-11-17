package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Two decisions the floating panel (tv/Hud) makes about what it draws, kept here so they can be checked without a
 * TV (panel-stopped, 2026-11-09 review): whether a step of the plan is still drawn as the one running, and whether
 * the words under a data card are that card's caption or the answer itself.
 */
public final class PanelRules {
    private PanelRules() { }

    /**
     * How many characters fit on the two caption lines under a card. The caption is 13 units of text in the panel's
     * inner column of 328 units (the box is 384 wide with 28 of padding each side), so a full-width character takes
     * about 13 units and a line holds 25 of them; two lines hold 50, and 48 leaves room for the wider ones. Latin
     * letters and digits are narrower, so a caption of that length is never cut.
     */
    static final int CAPTION_CHARS = 48;

    /** A name a card shows must be at least this long to make an answer its caption: a one-character place or sky
     * (「晴」, 「阴」) appears in half the answers there are. */
    private static final int NAME_MIN = 2;

    /**
     * Whether the step in this state is drawn as the one running now: only while the panel still moves. On a panel
     * that has stopped, failed or finished, the step that was in progress is drawn the way any unfinished step is —
     * its own number, no arc round it — because the work is over and nothing is running any more.
     */
    public static boolean stepDrawnRunning(boolean live, String stepState) {
        return live && ("in_progress".equals(stepState) || "pending".equals(stepState));
    }

    /**
     * Whether the reply is the card's caption: the two quiet lines under it that repeat what the card already shows,
     * instead of the answer itself. It is a caption when it fits those two lines, or when it names what the card
     * shows (the place or sky of a day's weather, a quote's name, a list's title or one of its items). Anything else
     * is the answer, and the panel draws it as the body — a card an earlier step left on the ledger is then left
     * out altogether rather than cutting the real answer to two lines under it.
     */
    public static boolean replyIsCaption(String reply, JSONObject card) {
        if (reply == null || reply.trim().isEmpty()) return true;   // nothing to draw, either way
        if (card == null) return false;                             // no card: nothing to be a caption of
        if (fits(reply)) return true;
        for (String key : keys(card)) if (reply.contains(key)) return true;
        return false;
    }

    /** Whether the words fit the two caption lines: at most {@link #CAPTION_CHARS} characters over at most two
     * lines, a line break of the reply's own taking a line whatever its length. */
    private static boolean fits(String reply) {
        int breaks = 0;
        for (int i = 0; i < reply.length(); i++) if (reply.charAt(i) == '\n') breaks++;
        return breaks <= 1 && reply.length() <= CAPTION_CHARS;
    }

    /** What the card shows that an answer about it would name: the place and sky of a day's weather, the name of
     * each quote, a list's own title and the title of each of its items. */
    static List<String> keys(JSONObject card) {
        List<String> out = new ArrayList<>();
        String type = str(card, "type");
        if ("weather".equals(type)) {
            add(out, str(card, "place"));
            add(out, str(card, "sky"));
        } else if ("stocks".equals(type)) {
            JSONArray rows = card.optJSONArray("rows");
            for (int i = 0; rows != null && i < rows.length(); i++) add(out, str(rows.optJSONObject(i), "name"));
        } else if ("list".equals(type)) {
            add(out, str(card, "title"));
            JSONArray items = card.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) add(out, str(items.optJSONObject(i), "title"));
        }
        return out;
    }

    private static void add(List<String> out, String s) { if (s.length() >= NAME_MIN) out.add(s); }

    /** A string field, "" for a missing or JSON-null one: Android's org.json gives "null" for a JSON null. */
    private static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        String s = o.optString(key, "");
        return "null".equals(s) ? "" : s;
    }
}
