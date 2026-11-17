package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What is installed but not yet verified on the real path ({@code tests/agent/unverified.json}): one entry per thing
 * the silent checks cannot stand in for, so 「还没验」 is a record on disk and not a memory of a conversation
 * (verify-durable, 2026-11-12; the user's yardstick: 进度可恢复).
 *
 * <p>Nothing here throws except {@link #without}, which has to refuse a file it cannot read: a run that passed writes
 * the file back, and a half-written ledger is worse than none.
 */
public final class Unverified {
    /** ids are read out in reports and picked with {@code --only} — kebab-case, nothing else. */
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern WHEN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** 探针脚本在仓库外的草稿树里（{@code voice/assistant-out/…}），所以 root 下找不到时往上找三级。 */
    private static final int UP = 3;
    /** {@link #list}'s field separator (US, 0x1f): a tab makes the shell's read swallow an empty field. */
    private static final char SEP = 0x1f;

    private Unverified() { }

    /**
     * Everything wrong with the file, as sentences; empty when it is sound. The signature is spelled out in full
     * ({@code java.util.List}, {@code java.nio.file.Path}) because that is the literal shape the job's gate greps for.
     *
     * @param json  the file's text
     * @param root  the repository root ({@code script} paths are read under it, then above it)
     * @param nowMs the clock, so a date that has not happened yet is caught
     */
    public static java.util.List<String> problems(String json, java.nio.file.Path root, long nowMs) {
        List<String> out = new ArrayList<>();
        JSONArray entries;
        try {
            entries = new JSONObject(json).optJSONArray("entries");
        } catch (JSONException e) {
            out.add("unverified.json 读不出来（不是 JSON：" + e.getMessage() + "）");
            return out;
        }
        if (entries == null) {
            out.add("unverified.json 里没有 entries 数组");
            return out;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) {
                out.add("第 " + (i + 1) + " 条不是一条记录（entries 里要都是对象）");
                continue;
            }
            one(out, e, i, root, nowMs, ids);
        }
        return out;
    }

    private static void one(List<String> out, JSONObject e, int i, Path root, long nowMs, Set<String> ids) {
        String id = e.optString("id", "");
        // an id that is not one cannot name the entry in a sentence: that entry is named by where it stands
        String who = ID.matcher(id).matches() ? id : "第 " + (i + 1) + " 条";
        if (id.isEmpty()) out.add(who + "：id 是空的");
        else if (!ID.matcher(id).matches()) out.add(who + "：id「" + id + "」不是 kebab-case（只小写字母、数字和中划线）");
        else if (!ids.add(id)) out.add(who + "：id 和前面的一条一样（每条要自己的 id）");
        if (e.optString("feature", "").isEmpty()) out.add(who + "：feature 是空的（要写清是哪件事还没验）");
        if (e.optString("why", "").isEmpty()) out.add(who + "：why 是空的（为什么静音检查代替不了它，要说一句）");
        needs(out, who, e.optJSONArray("needs"));
        when(out, who, e.optString("since", ""), nowMs);
        script(out, who, e.optString("script", ""), root);
    }

    /** 前提要一条条写出来：跑它的人得知道得先放一集、还是得有人在旁边。 */
    private static void needs(List<String> out, String who, JSONArray needs) {
        if (needs == null) {
            out.add(who + "：needs 不是数组（前提要一条条写，比如「放一集」「亮屏」）");
            return;
        }
        for (int i = 0; i < needs.length(); i++) {
            Object it = needs.opt(i);
            if (!(it instanceof String) || ((String) it).isEmpty())
                out.add(who + "：needs 里第 " + (i + 1) + " 项是空的（每条前提要写出来）");
        }
    }

    /** 从哪天起就没验过：a real day, and not one that has not come yet (the clock is the caller's). */
    private static void when(List<String> out, String who, String when, long nowMs) {
        if (!WHEN.matcher(when).matches()) {
            out.add(who + "：since「" + when + "」不是 YYYY-MM-DD");
            return;
        }
        int y = Integer.parseInt(when.substring(0, 4)), m = Integer.parseInt(when.substring(5, 7)),
                d = Integer.parseInt(when.substring(8, 10));
        Calendar day = Calendar.getInstance();
        day.clear();
        day.setLenient(false);
        try {
            day.set(y, m - 1, d);
            day.getTime();
        } catch (RuntimeException ex) {
            out.add(who + "：since「" + when + "」不是哪一天");
            return;
        }
        if (day.get(Calendar.YEAR) != y || day.get(Calendar.MONTH) != m - 1 || day.get(Calendar.DAY_OF_MONTH) != d) {
            out.add(who + "：since「" + when + "」不是哪一天");
            return;
        }
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);
        int ty = now.get(Calendar.YEAR), tm = now.get(Calendar.MONTH) + 1, td = now.get(Calendar.DAY_OF_MONTH);
        if (y > ty || (y == ty && (m > tm || (m == tm && d > td))))
            out.add(who + "：since「" + when + "」是以后的日子（今天是 " + ty + "-" + tm + "-" + td + "）");
    }

    /** 空的是诚实的（还没有东西能验它）；写了路径，那个路径就得真在磁盘上。 */
    private static void script(List<String> out, String who, String script, Path root) {
        if (script.isEmpty()) return;
        if (find(root, script) == null)
            out.add(who + "：script「" + script + "」在磁盘上没有（写一个不存在的路径，等于说已经有东西能验它）");
    }

    /** The script as a file: under root, or under one of the three directories above it; null when it is nowhere. */
    static Path find(Path root, String script) {
        if (root == null || script == null || script.isEmpty()) return null;
        Path p;
        try {
            p = Paths.get(script);
        } catch (RuntimeException e) {
            return null;
        }
        if (p.isAbsolute()) return Files.isRegularFile(p) ? p : null;
        // 只往上找 UP 级：一个笔误不该在很远的地方碰巧有个同名文件就算过（"." 这种相对 root 先变成绝对路径，
        // 不然它没有 parent，一级也上不去）
        Path dir = root.toAbsolutePath().normalize();
        for (int i = 0; i <= UP && dir != null; i++, dir = dir.getParent()) {
            Path at = dir.resolve(script);
            if (Files.isRegularFile(at)) return at;
        }
        return null;
    }

    /** The markdown section for NEXT.md: 「## 还没验的」 then one bullet per entry, oldest first. */
    public static String markdown(String json) {
        StringBuilder b = new StringBuilder("## 还没验的\n\n");
        for (JSONObject e : sorted(json)) {
            String script = e.optString("script", "");
            b.append("- **").append(e.optString("id", "")).append("** ").append(e.optString("feature", ""))
                    .append(" —— ").append(e.optString("why", ""))
                    .append("（要 ").append(needsOf(e)).append("；脚本 ").append(script.isEmpty() ? "没有" : script)
                    .append("）\n");
        }
        return b.toString();
    }

    /** One line for bridge.sh status: 「还没在真实路径验：3 条（ktcp-pause-real, ktcp-seek-real, …）」 or 「」 when none. */
    public static String line(String json, int max) {
        List<JSONObject> es = sorted(json);
        if (es.isEmpty()) return "";
        int n = Math.min(Math.max(max, 0), es.size());
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) ids.append(", ");
            ids.append(es.get(i).optString("id", ""));
        }
        if (es.size() > n) ids.append(n > 0 ? ", …" : "…");
        return "还没在真实路径验：" + es.size() + " 条（" + ids + "）";
    }

    /**
     * One line per entry in {@code since} order, for verify.sh: id, since, script, needs（、 分隔）. Fields are
     * separated by {@link #SEP} and not by a tab: the shell's {@code read} swallows an empty field between two
     * tabs, and an empty script is the normal case here.
     */
    public static String list(String json) {
        StringBuilder b = new StringBuilder();
        for (JSONObject e : sorted(json)) {
            b.append(e.optString("id", "")).append(SEP).append(e.optString("since", "")).append(SEP)
                    .append(e.optString("script", "")).append(SEP).append(needsOf(e)).append('\n');
        }
        return b.toString();
    }

    /** The entries, oldest {@code since} first; ties keep the order they have in the file (a stable sort). */
    private static List<JSONObject> sorted(String json) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray entries;
        try {
            entries = new JSONObject(json).optJSONArray("entries");
        } catch (JSONException e) {
            return out;
        }
        if (entries == null) return out;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e != null) out.add(e);
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return a.optString("since", "").compareTo(b.optString("since", ""));
            }
        });
        return out;
    }

    private static String needsOf(JSONObject e) {
        JSONArray needs = e.optJSONArray("needs");
        if (needs == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < needs.length(); i++) {
            Object it = needs.opt(i);
            if (it == null) continue;
            if (b.length() > 0) b.append('、');
            b.append(it.toString());
        }
        return b.toString();
    }

    /**
     * The same file with one entry gone — what a verification that passed writes back. The rest of the text is left
     * exactly as it was (the file is read by people); an id that is not there gives the file back unchanged.
     */
    public static String without(String json, String id) throws JSONException {
        JSONArray entries = new JSONObject(json).optJSONArray("entries");
        if (entries == null || id == null || id.isEmpty()) return json;
        boolean there = false;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e != null && id.equals(e.optString("id", ""))) there = true;
        }
        if (!there) return json;
        int[] span = spanOf(json, id);
        if (span == null) throw new JSONException("id 是「" + id + "」的那一条，在原文里找不到它的那一段");
        int back = span[0], fwd = span[1];
        while (back > 0 && space(json.charAt(back - 1))) back--;
        while (fwd < json.length() && space(json.charAt(fwd))) fwd++;
        int from, to;
        if (fwd < json.length() && json.charAt(fwd) == ',') {
            // 后面还有条目：连它后面那个逗号一起吃掉，前面的换行和缩进留给下一条
            from = back;
            to = fwd + 1;
        } else if (back > 0 && json.charAt(back - 1) == ',') {
            from = back - 1;
            to = span[1];
        } else {
            from = back;
            to = span[1];
        }
        return json.substring(0, from) + json.substring(to);
    }

    /** The {@code [start, end)} of that entry's text inside the entries array; null when it is not in the text. */
    private static int[] spanOf(String json, String id) {
        int at = arrayAt(json);
        if (at < 0) return null;
        int depth = 0, start = -1;
        boolean inStr = false, esc = false;
        for (int i = at; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    if (id.equals(idOf(json.substring(start, i + 1)))) return new int[] {start, i + 1};
                    start = -1;
                }
            } else if (c == ']' && depth == 0) {
                return null;
            }
        }
        return null;
    }

    /** Where the entries array's text starts (just after its {@code [}), or -1 when there is no such array. */
    private static int arrayAt(String json) {
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                int end = stringEnd(json, i);
                if (depth == 1 && "\"entries\"".equals(json.substring(i, end))) {
                    int at = end;
                    while (at < json.length() && space(json.charAt(at))) at++;
                    if (at < json.length() && json.charAt(at) == ':') at++;
                    while (at < json.length() && space(json.charAt(at))) at++;
                    if (at < json.length() && json.charAt(at) == '[') return at + 1;
                    return -1;
                }
                i = end - 1;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return -1;
    }

    /** The index just past the string literal that starts at {@code i}. */
    private static int stringEnd(String json, int i) {
        for (int j = i + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '\\') j++;
            else if (c == '"') return j + 1;
        }
        return json.length();
    }

    private static String idOf(String text) {
        try {
            return new JSONObject(text).optString("id", "");
        } catch (JSONException e) {
            return "";
        }
    }

    private static boolean space(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /**
     * So the shell and the Python read the same formatting, and not a second copy of it:
     * {@code markdown <file> | line <file> <max> | list <file> | without <file> <id> | problems <file> <root>}.
     */
    public static void main(String[] args) throws Exception {
        String what = args.length > 0 ? args[0] : "";
        String json = args.length > 1 ? read(args[1]) : "";
        if ("markdown".equals(what)) {
            System.out.print(markdown(json));
        } else if ("line".equals(what)) {
            System.out.println(line(json, args.length > 2 ? Integer.parseInt(args[2]) : 3));
        } else if ("list".equals(what)) {
            System.out.print(list(json));
        } else if ("without".equals(what)) {
            System.out.print(without(json, args[2]));
        } else if ("problems".equals(what)) {
            Path root = Paths.get(args.length > 2 ? args[2] : ".");
            for (String p : problems(json, root, System.currentTimeMillis())) System.out.println(p);
        } else {
            System.err.println("用法：markdown <file> | line <file> <max> | list <file> | without <file> <id> | problems <file> <root>");
            System.exit(2);
        }
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
