package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.function.LongSupplier;
import org.json.*;

/** The three tools over the 随手记: 「帮我记一下明天买牛奶」「我让你记了什么」「牛奶买了，划掉吧」. They change only
 * 星仪's own file, so they are read-gated and work without a plan; the host supplies the file access through
 * {@link Store}. note_add and note_done answer by themselves, so their message is one speakable sentence. */
public final class NoteTools {

    /** Where notes.json lives; the TV side reads and writes the file, the checks keep it in memory. */
    public interface Store {
        JSONObject load() throws Exception;
        void save(JSONObject notes) throws Exception;
    }

    private NoteTools() { }

    public static List<Tool> create(Store store, LongSupplier clock, TimeZone tz) {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Base(store, clock, tz, "note_add") {
            @Override public String describe(JSONObject a, Turn t) { return "记下：" + Notes.clean(a.optString("text")); }
            @Override boolean apply(JSONObject notes, JSONObject a, JSONObject r) throws Exception {
                String said = Notes.add(notes, a.optString("text"), now());
                boolean fresh = said.startsWith("记下了：");
                r.put("ok", true).put("status", fresh ? "recorded" : "exists").put("message", said);
                return fresh;
            }
        });
        tools.add(new Base(store, clock, tz, "note_list") {
            @Override public String describe(JSONObject a, Turn t) { return "看随手记"; }
            @Override boolean apply(JSONObject notes, JSONObject a, JSONObject r) throws Exception {
                JSONArray live = Notes.live(notes, now());
                JSONArray items = new JSONArray();
                for (int i = 0; i < live.length(); i++) {
                    JSONObject it = live.optJSONObject(i);
                    if (it == null) continue;
                    items.put(new JSONObject().put("id", it.optInt("id")).put("text", it.optString("text"))
                            .put("when", Notes.when(it.optLong("at"), now(), tz)));
                }
                r.put("ok", true).put("status", "observed").put("message", Notes.list(notes, now(), tz)).put("items", items);
                return false;
            }
        });
        tools.add(new Base(store, clock, tz, "note_done") {
            @Override public String describe(JSONObject a, Turn t) { return "划掉：" + Notes.clean(a.optString("which")); }
            @Override boolean apply(JSONObject notes, JSONObject a, JSONObject r) throws Exception {
                List<String> gone = Notes.done(notes, a.optString("which"));
                if (gone.isEmpty()) {
                    r.put("ok", false).put("status", "not_found")
                            .put("message", "随手记里没有这件。" + Notes.list(notes, now(), tz));
                    return false;
                }
                StringBuilder b = new StringBuilder("划掉了：");
                for (int i = 0; i < gone.size(); i++) b.append(i == 0 ? "" : "、").append(gone.get(i));
                r.put("ok", true).put("status", "done").put("message", b.append("。").toString())
                        .put("removed", new JSONArray(gone));
                return true;
            }
        });
        return tools;
    }

    /** Load, edit, save only when something actually changed; a broken file is reported, never thrown at the loop. */
    private abstract static class Base implements Tool {
        private final Store store;
        private final LongSupplier clock;
        final TimeZone tz;
        private final String name;
        Base(Store store, LongSupplier clock, TimeZone tz, String name) {
            this.store = store; this.clock = clock; this.tz = tz; this.name = name;
        }
        @Override public String name() { return name; }
        @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
        long now() { return clock == null ? System.currentTimeMillis() : clock.getAsLong(); }
        @Override public JSONObject run(JSONObject a, Turn t) {
            try {
                JSONObject notes = store.load();
                JSONObject r = new JSONObject();
                if (apply(notes, a == null ? new JSONObject() : a, r)) store.save(notes);
                return r;
            } catch (IllegalArgumentException refused) {
                return failed("rejected", refused.getMessage());
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                return failed("error", "随手记没存上：" + why);
            }
        }
        /** Fills the result; @return true when the notes changed and must be written back. */
        abstract boolean apply(JSONObject notes, JSONObject a, JSONObject r) throws Exception;

        private JSONObject failed(String status, String message) {
            try { return new JSONObject().put("ok", false).put("status", status).put("message", message); }
            catch (JSONException impossible) { return new JSONObject(); }
        }
    }
}
