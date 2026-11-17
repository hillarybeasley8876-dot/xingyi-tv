package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.function.LongSupplier;
import org.json.*;

/** The one tool over the 30-day log: 「昨天晚上放的那个视频再放一遍」, when recent_turns no longer knows it. It only
 * reads 星仪's own file, so it is read-gated and works without a plan; the host supplies the file through
 * {@link Store}. The model answers from the result, so the descriptions are what it reads out. */
public final class HistoryTools {

    /** Where history.json lives; the TV side reads the file, the checks keep it in memory. Read-only: only the
     * engine appends, once per finished task. */
    public interface Store {
        JSONObject load() throws Exception;
    }

    private HistoryTools() { }

    public static List<Tool> create(Store store, LongSupplier clock, TimeZone tz) {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool() {
            @Override public String name() { return "recall_history"; }
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) {
                String q = a == null ? "" : a.optString("query").trim();
                return q.isEmpty() ? "回忆最近做过的事" : "回忆和「" + q + "」有关的事";
            }
            @Override public JSONObject run(JSONObject a, Turn t) {
                try {
                    JSONObject args = a == null ? new JSONObject() : a;
                    long now = clock == null ? System.currentTimeMillis() : clock.getAsLong();
                    int days = History.window(days(args));
                    String q = args.optString("query").trim();
                    List<JSONObject> found = History.query(store.load(), q, days, now);
                    JSONObject r = new JSONObject().put("ok", true).put("status", "observed");
                    if (found.isEmpty()) {
                        return r.put("message", q.isEmpty() ? "最近 " + days + " 天还没有记录。"
                                : "最近 " + days + " 天没有找到和「" + q + "」有关的记录。").put("items", new JSONArray());
                    }
                    StringBuilder b = new StringBuilder("找到 " + found.size() + " 条：");
                    JSONArray items = new JSONArray();
                    for (int i = 0; i < found.size(); i++) {
                        b.append(i == 0 ? "" : "；").append(History.describe(found.get(i), now, tz));
                        items.put(found.get(i));
                    }
                    return r.put("message", b.toString()).put("items", items);
                } catch (Exception e) {
                    String why = e.getMessage() == null ? e.toString() : e.getMessage();
                    try {
                        return new JSONObject().put("ok", false).put("status", "error")
                                .put("message", "历史记录没读出来：" + why);
                    } catch (JSONException impossible) { return new JSONObject(); }
                }
            }
        });
        return tools;
    }

    /** The contract says days is an integer, but a model that sends "7" must still be understood. */
    private static int days(JSONObject args) {
        Object d = args.opt("days");
        if (d instanceof Number) return ((Number) d).intValue();
        try { return Integer.parseInt(String.valueOf(d).trim()); }
        catch (NumberFormatException none) { return 0; }
    }
}
