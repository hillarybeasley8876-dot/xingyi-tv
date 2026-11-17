package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The ledger of what the user reported ({@code tests/agent/reported.json}): one entry per thing that went wrong on the
 * TV, the check that holds it, the commit that fixed it and the revision where that check was not there yet — so a
 * check nobody has ever seen missing cannot pass for proof (reported, 2026-11-12; the user's yardstick: 反馈真实).
 *
 * <p>Everything wrong with the ledger, as sentences; empty when it is sound. Nothing here throws: a ledger that cannot
 * be read is itself one sentence.
 */
public final class Reported {
    /** ids are read aloud in reports and used as file names by nothing — kebab-case, nothing else. */
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern WHEN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern FIX = Pattern.compile("[0-9a-f]{7}");
    /** Checks and evidence live under the test tree; anything else is not something the gate can run. */
    private static final String UNDER = "tests/agent/";

    private Reported() { }

    /**
     * Everything wrong with the ledger, as sentences; empty when it is sound.
     *
     * @param json  the ledger's text
     * @param root  the repository root ({@code tests/agent/…} paths are read under it)
     * @param nowMs the clock, so a date that has not happened yet is caught
     */
    public static List<String> problems(String json, Path root, long nowMs) {
        List<String> out = new ArrayList<>();
        JSONArray entries;
        try {
            entries = new JSONObject(json).optJSONArray("entries");
        } catch (JSONException e) {
            out.add("reported.json 读不出来（不是 JSON：" + e.getMessage() + "）");
            return out;
        }
        if (entries == null) {
            out.add("reported.json 里没有 entries 数组");
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
        if (e.optString("said", "").isEmpty()) out.add(who + "：said 是空的（用户原话要有）");
        when(out, who, e.optString("when", ""), nowMs);
        if (e.optString("symptom", "").isEmpty()) out.add(who + "：symptom 是空的（出了什么错要写一句）");
        check(out, who, e.optJSONObject("check"), root);
        commit(out, who, e);
        verified(out, who, e, root);
    }

    /** The date the user said it: a real day, and not one that has not come yet (the clock is the caller's). */
    private static void when(List<String> out, String who, String when, long nowMs) {
        if (!WHEN.matcher(when).matches()) {
            out.add(who + "：when「" + when + "」不是 YYYY-MM-DD");
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
            out.add(who + "：when「" + when + "」不是哪一天");
            return;
        }
        if (day.get(Calendar.YEAR) != y || day.get(Calendar.MONTH) != m - 1 || day.get(Calendar.DAY_OF_MONTH) != d) {
            out.add(who + "：when「" + when + "」不是哪一天");
            return;
        }
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);
        int ty = now.get(Calendar.YEAR), tm = now.get(Calendar.MONTH) + 1, td = now.get(Calendar.DAY_OF_MONTH);
        if (y > ty || (y == ty && (m > tm || (m == tm && d > td))))
            out.add(who + "：when「" + when + "」是以后的日子（今天是 " + ty + "-" + tm + "-" + td + "）");
    }

    /** The check itself: a file under the test tree, and the label really in it — a label nobody can find is no check. */
    private static void check(List<String> out, String who, JSONObject check, Path root) {
        if (check == null) {
            out.add(who + "：check 是空的（要说清检查在哪个文件、哪一条）");
            return;
        }
        String file = check.optString("file", ""), label = check.optString("label", "");
        if (!file.startsWith(UNDER)) {
            out.add(who + "：check.file「" + file + "」不在 " + UNDER + " 下面");
            return;
        }
        String text = read(root.resolve(file));
        if (text == null) {
            out.add(who + "：check.file「" + file + "」在仓库里没有");
            return;
        }
        if (label.isEmpty()) {
            out.add(who + "：check.label 是空的");
            return;
        }
        // the label not being in the file at all is the older, blunter sentence, and it stands alone
        if (!text.contains(label)) {
            out.add(who + "：check.label「" + label + "」不在 " + file + " 里（检查没了，或者名字改了）");
            return;
        }
        // a symbol (jiguangNeedsKey, static void blank()) is in the file long before the fix: only a check's own
        // sentence, whole in double quotes, says which check is meant — and it has to be long enough to be one
        if (label.length() < 12) out.add(who + "：check.label 太短，证不出一条检查");
        else if (!text.contains("\"" + label + "\""))
            out.add(who + "：check.label 不是一条检查的句子（文件里找不到 \"" + label + "\"）");
    }

    /**
     * The commit that fixed it, and the revision before it — where {@code reported.sh} looks for the check. An entry
     * that has no such revision says so in {@code void_because}: a file the fix itself created is not proof of absence,
     * so 「没有修复前对照」 is a sentence the entry carries, never something the gate may assume.
     */
    private static void commit(List<String> out, String who, JSONObject e) {
        String fix = e.optString("fix", "");
        if (!FIX.matcher(fix).matches()) out.add(who + "：fix「" + fix + "」不是 7 位的提交号");
        String before = e.optString("before", ""), why = e.optString("void_because", "");
        if (before.isEmpty()) {
            if (why.isEmpty()) out.add(who + "：before 是空的，得用 void_because 说清为什么这条没有修复前对照");
        } else if (!before.endsWith("^")) {
            out.add(who + "：before「" + before + "」不是那个提交的上一版（要以 ^ 结尾）");
        }
        if (!before.isEmpty() && !why.isEmpty())
            out.add(who + "：before 不是空的，void_because 得删掉（有修复前对照，就不是没有证明）");
    }

    /** What was really run: a test, the TV itself (and then the evidence is in the repository), or nothing — said why. */
    private static void verified(List<String> out, String who, JSONObject e, Path root) {
        String v = e.optString("verified", "");
        if (!"test".equals(v) && !"real".equals(v) && !"none".equals(v)) {
            out.add(who + "：verified「" + v + "」不是 test / real / none");
            return;
        }
        String evidence = e.optString("evidence", "");
        if ("real".equals(v)) {
            if (evidence.isEmpty()) out.add(who + "：verified 是 real，evidence 却没写");
            else if (!evidence.startsWith(UNDER)) out.add(who + "：evidence「" + evidence + "」不在 " + UNDER + " 下面");
            else if (read(root.resolve(evidence)) == null) out.add(who + "：evidence「" + evidence + "」在仓库里没有");
        }
        if ("none".equals(v) && e.optString("unverified_because", "").isEmpty())
            out.add(who + "：verified 是 none，unverified_because 却没写（为什么没验要说一句）");
    }

    /** The file's text, or null when it is not there (a directory, a missing path, an unreadable file). */
    private static String read(Path p) {
        try {
            if (!Files.isRegularFile(p)) return null;
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
    /**
     * One line per entry for the report: 「2026-11-11 id · 用户说「…」 → 检查 file · 修于 fix · 验证 test」; an entry with
     * no revision before the fix ends its middle with 「没有修复前对照」 instead of 「修于 fix」.
     */
    public static String table(String json) {
        JSONArray entries;
        try {
            entries = new JSONObject(json).optJSONArray("entries");
        } catch (JSONException e) {
            entries = null;
        }
        if (entries == null) return "（reported.json 读不出来，没有台账可以列）\n";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) {
                b.append("第 ").append(i + 1).append(" 条不是一条记录\n");
                continue;
            }
            JSONObject c = e.optJSONObject("check");
            String before = e.optString("before", "");
            b.append(e.optString("when", "")).append(' ').append(e.optString("id", ""))
                    .append(" · 用户说「").append(e.optString("said", "")).append("」 → 检查 ")
                    .append(c == null ? "" : c.optString("file", ""))
                    // an entry with no revision before the fix has no 修于 to show: it says that instead
                    .append(before.isEmpty() ? " · 没有修复前对照" : " · 修于 " + e.optString("fix", ""))
                    .append(" · 验证 ").append(e.optString("verified", "")).append('\n');
        }
        return b.toString();
    }

    /** The git revision whose copy of the check's file must NOT hold the label: {@code "833d99c^"}. */
    public static String beforeOf(String fix) {
        return fix == null ? "" : fix + "^";
    }
}
