package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The gate's entry point ({@code tests/agent/reported.sh}): the ledger's own checks first, then the ledger as a table,
 * then every sentence that is wrong with it — exit 1 when there is any. The git step needs the ids and revisions, and
 * shell JSON is a bad idea, so {@code --entries} prints one line per entry whose check file and label are sound:
 * {@code id⇥fix⇥before⇥file⇥label}, with a {@code -} where an entry has no revision before the fix (an empty field
 * would fold two adjacent tabs and shift {@code file} into {@code before}) and that entry's {@code void_because} as a
 * sixth column, so the gate can say why there is no proof without reading JSON itself. (Step 2 has already refused
 * anything that is not sound; the filter is belt and braces.)
 *
 * <p>An argument ending in {@code .json} is the ledger to read — {@code accept_reported2.sh} points the gate at a
 * one-entry ledger in a temporary directory. Check files and evidence are still read under the repository root.
 */
public final class ReportedMain {
    private ReportedMain() { }

    private static final String LEDGER = "tests/agent/reported.json";

    public static void main(String[] args) throws Exception {
        boolean entries = false;
        String ledger = LEDGER;
        Path root = Paths.get(".");
        for (String a : args) {
            if ("--entries".equals(a)) entries = true;
            else if (a.endsWith(".json")) ledger = a;
            else if (!a.startsWith("--")) root = Paths.get(a);
        }
        String json;
        try {
            json = new String(Files.readAllBytes(Paths.get(ledger)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println(ledger + " 读不出来（" + e + "）");
            System.exit(1);
            return;
        }
        if (entries) {
            // \n, not println: a \r left on the label would make the gate's grep find nothing and call it absent
            for (String line : entries(json, root)) System.out.print(line + "\n");
            return;
        }
        ReportedChecks.run(root);
        System.out.print(Reported.table(json));
        List<String> problems = Reported.problems(json, root, System.currentTimeMillis());
        for (String p : problems) System.out.println(p);
        if (!problems.isEmpty()) System.exit(1);
    }

    /** {@code id⇥fix⇥before⇥file⇥label} for every entry whose check file is there and really holds the label. */
    private static List<String> entries(String json, Path root) throws Exception {
        List<String> out = new ArrayList<>();
        JSONArray a = new JSONObject(json).optJSONArray("entries");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            JSONObject c = e == null ? null : e.optJSONObject("check");
            if (c == null) continue;
            String file = c.optString("file", ""), label = c.optString("label", "");
            if (!file.startsWith("tests/agent/") || label.isEmpty()) continue;
            String text = read(root.resolve(file));
            if (text == null || !text.contains(label)) continue;
            String before = e.optString("before", "");
            String line = e.optString("id", "") + "\t" + e.optString("fix", "") + "\t" + (before.isEmpty() ? "-" : before)
                    + "\t" + file + "\t" + label;
            // no revision to look at: the sentence that says why goes along, so the gate can print it
            if (before.isEmpty()) line += "\t" + oneLine(e.optString("void_because", ""));
            out.add(line);
        }
        return out;
    }

    /** A reason that carried a newline would end the gate's line and shift every column after it. */
    private static String oneLine(String s) {
        return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String read(Path p) {
        try {
            return Files.isRegularFile(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
