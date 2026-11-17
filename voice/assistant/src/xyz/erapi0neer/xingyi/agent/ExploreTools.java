package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import java.util.function.LongSupplier;
import org.json.*;

/** The two tools over an app's own entry points (2026-11-05, 「自己对应用的mcp探索」): explore_app reads what an
 * installed app publishes — the deep links in its manifest — and app_note keeps what 星仪 found out by trying one,
 * so the next request about that app starts from what worked instead of from the remote control. Neither changes
 * anything on the TV, so both are read-gated and work without a plan. The host supplies the dump and the app list. */
public final class ExploreTools {

    /** The TV's `dumpsys package <pkg>`; the command is fixed and read-only, the core only sees its text. */
    public interface Dumper { String dump(String pkg) throws Exception; }

    /** The installed apps: a name or a package resolves to a package, a package to the name on the TV. */
    public interface Apps {
        String resolve(String app);
        String label(String pkg);
    }

    private ExploreTools() { }

    public static List<Tool> create(Dumper dumper, Apps apps, MemoryTools.Store notes) {
        return create(dumper, apps, notes, null);
    }

    /** @param clock for the checks; null is the wall clock */
    public static List<Tool> create(Dumper dumper, Apps apps, MemoryTools.Store notes, LongSupplier clock) {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Explore(dumper, apps, notes));
        tools.add(new Note(apps, notes, clock));
        return tools;
    }

    /** 「看看哔哩哔哩能直接打开哪些页面」: the app's own deep links, and what 星仪 already learned about it. */
    private static final class Explore implements Tool {
        private final Dumper dumper;
        private final Apps apps;
        private final MemoryTools.Store store;
        Explore(Dumper dumper, Apps apps, MemoryTools.Store store) { this.dumper = dumper; this.apps = apps; this.store = store; }
        @Override public String name() { return "explore_app"; }
        @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
        @Override public String describe(JSONObject a, Turn t) { return "看看" + app(a) + "能直接打开哪些页面"; }
        @Override public JSONObject run(JSONObject a, Turn t) {
            String said = app(a), pkg = apps.resolve(said);
            if (pkg == null || pkg.isEmpty()) {
                return failed("not_found", "电视上没装「" + said + "」：要用的话先用 install_app 装上");
            }
            try {
                JSONObject parsed = AppIntents.parse(dumper.dump(pkg), pkg);
                String version = parsed.optString("version");
                StringBuilder message = new StringBuilder(name(pkg, said));
                if (!version.isEmpty()) message.append(" ").append(version);
                message.append(" 能直接打开：").append(AppIntents.summary(parsed, 900));
                List<String> known = known(store, pkg);
                if (!known.isEmpty()) message.append("。之前摸索出来的经验：").append(join(known));
                return new JSONObject().put("ok", true).put("status", "observed").put("message", message.toString())
                        .put("package", pkg).put("links", parsed.optJSONObject("links"))
                        .put("notes", new JSONArray(known));
            } catch (Exception e) {
                return failed("error", "没能看到「" + said + "」能打开哪些页面：" + why(e));
            }
        }
        private String name(String pkg, String said) {
            String label = apps.label(pkg);
            return label == null || label.trim().isEmpty() ? said : label.trim();
        }
    }

    /** 「记一下：在哔哩哔哩里 bilibili://history 直接打开观看历史」. */
    private static final class Note implements Tool {
        private final Apps apps;
        private final MemoryTools.Store store;
        private final LongSupplier clock;
        Note(Apps apps, MemoryTools.Store store, LongSupplier clock) { this.apps = apps; this.store = store; this.clock = clock; }
        @Override public String name() { return "app_note"; }
        @Override public Gate gate(JSONObject a, Turn t) { return Gate.read(); }
        @Override public String describe(JSONObject a, Turn t) {
            return "记下" + app(a) + "的经验：" + Memory.clean(a == null ? "" : a.optString("note"));
        }
        @Override public JSONObject run(JSONObject a, Turn t) {
            String said = app(a), pkg = apps.resolve(said);
            String label = pkg == null ? said : label(pkg, said);
            // an app that is not installed yet still deserves its experience: it is filed under its own words
            if (pkg == null || pkg.isEmpty()) pkg = "?" + said;
            try {
                JSONObject book = store.load();
                String message = AppNotes.add(book, pkg, label, a == null ? "" : a.optString("note"), now());
                boolean fresh = !message.startsWith("已经记着了");
                if (fresh) store.save(book);
                return new JSONObject().put("ok", true).put("status", fresh ? "recorded" : "exists").put("message", message);
            } catch (IllegalArgumentException refused) {
                return failed("rejected", refused.getMessage());
            } catch (Exception e) {
                return failed("error", "经验没存上：" + why(e));
            }
        }
        private String label(String pkg, String said) {
            String label = apps.label(pkg);
            return label == null || label.trim().isEmpty() ? said : label.trim();
        }
        private long now() { return clock == null ? System.currentTimeMillis() : clock.getAsLong(); }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String app(JSONObject a) { return a == null ? "" : a.optString("app").trim(); }

    /** What 星仪 already learned about this app; a store that cannot be read costs the answer nothing else. */
    private static List<String> known(MemoryTools.Store store, String pkg) {
        if (store == null) return Collections.emptyList();
        try {
            return AppNotes.notes(store.load(), pkg);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static String join(List<String> notes) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) b.append(i == 0 ? "" : "；").append(notes.get(i));
        return b.toString();
    }

    private static String why(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }

    private static JSONObject failed(String status, String message) {
        try {
            return new JSONObject().put("ok", false).put("status", status).put("message", message == null ? "出错了" : message);
        } catch (JSONException impossible) {
            return new JSONObject();
        }
    }
}
