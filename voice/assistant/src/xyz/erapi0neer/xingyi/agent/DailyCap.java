package xyz.erapi0neer.xingyi.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * At most so many uses a day of a search that costs real money — x_search (grok-4.7 reading X, ~0.3 USD a call) and
 * web_search (gpt-5.4 with web search) — whatever asks for them: a schedule that fires every few minutes, a loop the
 * per-task budget does not see. The count lives in a small file, so a restarted process keeps it (2026-11-06
 * overnight pass). Nothing else is metered.
 */
public final class DailyCap {
    private final File file;
    private final int max;
    private final String what;

    /** @param what how the refusal names the search (「X 上」「联网」) */
    public DailyCap(File file, int max, String what) {
        this.file = file; this.max = max; this.what = what;
    }

    /** null and the use is counted for day (yyyy-MM-dd); otherwise the sentence the model gets instead of a search. */
    public synchronized String take(String day) {
        JSONObject o = read();
        int used = day.equals(o.optString("day")) ? o.optInt("used", 0) : 0;
        if (used >= max)
            return "今天" + what + "已经查了 " + max + " 次，到了每天的上限（每次都要花钱）：用已经知道的回答，或者如实告诉用户今天查不了、明天再试。";
        try {
            write(new JSONObject().put("day", day).put("used", used + 1));
        } catch (Exception ignored) {
            // an unwritable file must not stop the search: the cap is a guard, not a gate
        }
        return null;
    }

    /** How many uses day has seen. */
    public synchronized int used(String day) {
        JSONObject o = read();
        return day.equals(o.optString("day")) ? o.optInt("used", 0) : 0;
    }

    private JSONObject read() {
        try {
            if (!file.isFile()) return new JSONObject();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[512];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            }
            return new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void write(JSONObject o) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("no directory " + dir);
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(file)) {
            if (!file.delete() || !tmp.renameTo(file)) throw new IOException("cannot replace " + file);
        }
    }
}
