package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** The three tools that let the user edit the profile by voice: 「记住我喜欢林俊杰」「忘掉我喜欢林俊杰」
 * 「不是星辰人旅行者，是星辰亻旅行者」. They change nothing on the TV, so they are read-gated and work without a plan;
 * the host supplies the file access through {@link Store}. */
public final class MemoryTools {

    /** Where profile.json lives; the TV side reads and writes the file, the checks keep it in memory. */
    public interface Store {
        JSONObject load() throws Exception;
        void save(JSONObject profile) throws Exception;
    }

    private MemoryTools() { }

    public static List<Tool> create(Store store) { return create(store, null); }

    /** @param habits where habits.json lives, or null: with it, 「忘掉林俊杰」 also drops what 星仪 learned by itself */
    public static List<Tool> create(Store store, Store habits) {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Base(store, habits, "remember") {
            @Override public String describe(JSONObject a, Turn t) { return "记住：" + Memory.clean(a.optString("fact")); }
            @Override boolean apply(JSONObject profile, JSONObject a, JSONObject r) throws Exception {
                String fact = Memory.clean(a.optString("fact"));
                String status = Memory.addFact(profile, fact);
                if (status.startsWith("rejected")) { rejected(r, why(status)); return false; }
                boolean dup = status.equals("duplicate");
                JSONArray facts = profile.optJSONArray("facts");
                r.put("ok", true).put("status", dup ? "duplicate" : "recorded")
                        .put("message", (dup ? "本来就记得：" : "记住了：") + fact)
                        .put("facts", facts == null ? 0 : facts.length());
                return !dup;
            }
        });
        tools.add(new Base(store, habits, "forget") {
            @Override public String describe(JSONObject a, Turn t) { return "忘掉：" + Memory.clean(a.optString("what")); }
            @Override boolean apply(JSONObject profile, JSONObject a, JSONObject r) throws Exception {
                String what = Memory.clean(a.optString("what"));
                List<String> removed = Memory.forget(profile, what);
                if (removed.isEmpty()) {
                    r.put("ok", true).put("status", "nothing").put("message", "没有和「" + what + "」有关的记忆");
                    return false;
                }
                r.put("ok", true).put("status", "forgotten").put("removed", new JSONArray(removed))
                        .put("message", forgotten(what, removed.size()));
                return true;
            }
        });
        tools.add(new Base(store, habits, "remember_name") {
            @Override public String describe(JSONObject a, Turn t) {
                return "记住写法：" + Memory.clean(a.optString("heard")) + " → " + Memory.clean(a.optString("written"));
            }
            @Override boolean apply(JSONObject profile, JSONObject a, JSONObject r) throws Exception {
                String heard = Memory.clean(a.optString("heard")), written = Memory.clean(a.optString("written"));
                String status = Memory.addAlias(profile, heard, written);
                if (status.startsWith("rejected")) { rejected(r, why(status)); return false; }
                boolean dup = status.equals("duplicate");
                r.put("ok", true).put("status", dup ? "duplicate" : "recorded")
                        .put("message", (dup ? "本来就记得：" : "记住了：") + "「" + heard + "」写作「" + written + "」");
                return !dup;
            }
        });
        return tools;
    }

    /** Load, edit, save only when something actually changed; a broken file is reported, never thrown at the loop. */
    private abstract static class Base implements Tool {
        private final Store store;
        private final Store habits;
        private final String name;
        Base(Store store, Store habits, String name) { this.store = store; this.habits = habits; this.name = name; }
        @Override public String name() { return name; }
        @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
        @Override public JSONObject run(JSONObject a, Turn t) {
            try {
                JSONObject profile = store.load();
                JSONObject r = new JSONObject();
                boolean changed = apply(profile, a, r);
                if (changed) store.save(profile);
                dropHabits(a, r);
                return r;
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                try { return new JSONObject().put("ok", false).put("status", "error").put("message", "记忆没存上：" + why); }
                catch (JSONException ignored) { return new JSONObject(); }
            }
        }
        /** Fills the result; @return true when the profile changed and must be written back. */
        abstract boolean apply(JSONObject profile, JSONObject a, JSONObject r) throws Exception;

        /** forget also drops what 星仪 learned by itself about the same thing, and says how much went. The habit file
         * is best-effort: a broken one must not cost the user the fact they just asked to forget. */
        private void dropHabits(JSONObject a, JSONObject r) {
            if (habits == null || !"forget".equals(name)) return;
            try {
                JSONObject h = habits.load();
                int gone = Habits.forget(h, Memory.clean(a.optString("what")));
                if (gone <= 0) return;
                habits.save(h);
                JSONArray removed = r.optJSONArray("removed");
                int said = removed == null ? 0 : removed.length();
                if (removed == null) removed = new JSONArray();
                removed.put("使用记录：" + gone + " 条");
                r.put("ok", true).put("status", "forgotten").put("removed", removed)
                        .put("message", forgotten(Memory.clean(a.optString("what")), said + gone));
            } catch (Exception ignored) { }
        }
    }

    /** Spoken as the answer when forgetting was all the user asked for, so it says what went, not only how much. */
    static String forgotten(String what, int count) { return "已忘掉和「" + what + "」有关的 " + count + " 条记忆"; }

    private static void rejected(JSONObject r, String message) throws JSONException {
        r.put("ok", false).put("status", "rejected").put("message", message);
    }

    private static String why(String status) {
        if (status.endsWith(":secret")) return "这类信息（密码、验证码、证件号）不记";
        if (status.endsWith(":too_long")) return "这条太长了，记不住";
        if (status.endsWith(":same")) return "两个写法一样，不用记";
        if (status.endsWith(":full")) return "名字写法记满了，先忘掉一些再记";
        return "没听清要记什么";
    }
}
