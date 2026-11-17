package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.function.LongSupplier;
import org.json.*;

/** The three tools that let the user book, read and drop what 星仪 does later: 「明天早上7点放新闻联播」「我定了哪些」
 * 「把明天那个取消」. They change nothing on the TV, so they run without a plan, and schedule_task / cancel_schedule
 * answer by themselves — their {@code message} is spoken as the whole reply, so it must read as one Chinese sentence.
 * The host supplies the file access and the clock through {@link Store} and a {@link LongSupplier}. */
public final class ScheduleTools {

    /** Where schedules.json lives; the TV side reads and writes the file, the checks keep it in memory. */
    public interface Store {
        JSONObject load() throws Exception;
        void save(JSONObject book) throws Exception;
    }

    private static final String DUP = "已经预约过了：";

    private ScheduleTools() { }

    public static List<Tool> create(Store store, LongSupplier clock, TimeZone tz) {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Base(store, clock, tz, "schedule_task") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.act(); }
            @Override public String describe(JSONObject a, Turn t) { return "预约：" + Schedules.clean(a.optString("what")); }
            @Override boolean apply(JSONObject book, JSONObject a, JSONObject r, long now) throws Exception {
                JSONArray before = book.optJSONArray("items");
                int count = before == null ? 0 : before.length();
                String message;
                try {
                    message = Schedules.add(book, a, now, tz);
                } catch (IllegalArgumentException rejected) {
                    r.put("ok", false).put("status", "rejected").put("message", rejected.getMessage());
                    return false;
                }
                JSONArray items = book.getJSONArray("items");
                boolean fresh = items.length() > count;
                JSONObject item = fresh ? items.getJSONObject(items.length() - 1) : booked(items, message, now, tz);
                r.put("ok", true).put("status", fresh ? "scheduled" : "exists").put("message", message);
                if (item != null) r.put("id", item.optInt("id")).put("at", item.optLong("at"));
                return fresh;
            }
        });
        tools.add(new Base(store, clock, tz, "list_schedules") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
            @Override public String describe(JSONObject a, Turn t) { return "看预约"; }
            @Override boolean apply(JSONObject book, JSONObject a, JSONObject r, long now) throws Exception {
                r.put("ok", true).put("status", "observed").put("message", Schedules.list(book, now, tz))
                        .put("items", rows(book, now, tz));
                return false;
            }
        });
        tools.add(new Base(store, clock, tz, "cancel_schedule") {
            @Override public Gate gate(JSONObject a, Turn t) { return Gate.act(); }
            @Override public String describe(JSONObject a, Turn t) { return "取消预约：" + Schedules.clean(a.optString("which")); }
            @Override boolean apply(JSONObject book, JSONObject a, JSONObject r, long now) throws Exception {
                List<String> gone = Schedules.cancel(book, a.optString("which"), now, tz);
                if (gone.isEmpty()) {
                    r.put("ok", false).put("status", "not_found")
                            .put("message", "没有找到要取消的预约。" + Schedules.list(book, now, tz));
                    return false;
                }
                StringBuilder said = new StringBuilder("取消了：");
                for (int i = 0; i < gone.size(); i++) said.append(i == 0 ? "" : "；").append(gone.get(i));
                r.put("ok", true).put("status", "cancelled").put("message", said.append("。").toString())
                        .put("removed", new JSONArray(gone));
                return true;
            }
        });
        return tools;
    }

    /** What list_schedules reports item by item, in the order they will run, so the model can name an id when the
     * user wants one cancelled. */
    private static JSONArray rows(JSONObject book, long now, TimeZone tz) throws JSONException {
        JSONArray out = new JSONArray();
        for (JSONObject item : Schedules.all(book)) {
            out.put(new JSONObject().put("id", item.optInt("id")).put("kind", item.optString("kind"))
                    .put("what", item.optString("what")).put("when", Schedules.when(item, now, tz)));
        }
        return out;
    }

    /** The item {@code add} refused to book twice, found by the description it just spoke. */
    private static JSONObject booked(JSONArray items, String message, long now, TimeZone tz) {
        if (!message.startsWith(DUP)) return null;
        String said = message.substring(DUP.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && Schedules.describe(item, now, tz).equals(said)) return item;
        }
        return null;
    }

    /** Load, edit, save only when something actually changed; a broken file is reported, never thrown at the loop. */
    private abstract static class Base implements Tool {
        private final Store store;
        private final LongSupplier clock;
        private final TimeZone tz;
        private final String name;
        Base(Store store, LongSupplier clock, TimeZone tz, String name) {
            this.store = store; this.clock = clock; this.tz = tz; this.name = name;
        }
        @Override public String name() { return name; }
        @Override public JSONObject run(JSONObject a, Turn t) {
            try {
                JSONObject book = store.load();
                if (book == null) book = new JSONObject();
                JSONObject r = new JSONObject();
                if (apply(book, a, r, clock.getAsLong())) store.save(book);
                return r;
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                try { return new JSONObject().put("ok", false).put("status", "error").put("message", "预约没存上：" + why); }
                catch (JSONException ignored) { return new JSONObject(); }
            }
        }
        /** Fills the result; @return true when the book changed and must be written back. */
        abstract boolean apply(JSONObject book, JSONObject a, JSONObject r, long now) throws Exception;
    }
}
