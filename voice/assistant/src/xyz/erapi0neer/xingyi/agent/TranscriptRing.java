package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The ring of the last real tasks' full model transcripts, kept in files/agent/transcripts/ as
 * "<startedAt>-<id>.json". Until 2026-11-09 only the last one was kept (last_transcript.json), so a slow task's
 * 11 rounds were gone as soon as the next request came: the TV keeps the last 12 now, bounded by bytes too, because
 * a 2 GB box must not fill up with transcripts. Pure: the naming, the parsing and what to drop live here, the
 * Android side only reads and writes the files. */
public final class TranscriptRing {
    private TranscriptRing() { }

    /** How many tasks' transcripts the TV keeps. */
    public static final int MAX_COUNT = 12;
    /** And how many bytes of them in total, so one huge task cannot fill the box. */
    public static final long MAX_BYTES = 3_000_000L;

    /** "<startedAt>-<id>.json", the id reduced to letters and digits so it is always a safe file name (an id of only
     * symbols becomes "task": the startedAt still tells the tasks apart). */
    public static String fileName(long startedAt, String id) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; id != null && i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) safe.append(c);
        }
        return startedAt + "-" + (safe.length() == 0 ? "task" : safe.toString()) + ".json";
    }

    /** The number before the first "-" of "<digits>-<something>.json", or -1 when the name is not one of ours: the
     * ring directory may hold a stray file, and such a name must never be mistaken for a task. */
    public static long startedAt(String fileName) {
        if (fileName == null || !fileName.endsWith(".json")) return -1;
        int dash = fileName.indexOf('-');
        int json = fileName.length() - ".json".length();
        // at least one digit before the dash and one character between the dash and ".json"
        if (dash <= 0 || dash + 1 >= json) return -1;
        String head = fileName.substring(0, dash);
        for (int i = 0; i < head.length(); i++) if (head.charAt(i) < '0' || head.charAt(i) > '9') return -1;
        try {
            return Long.parseLong(head);
        } catch (NumberFormatException tooLong) {
            return -1;
        }
    }

    /** The names that are ours, newest first — by the number, not by the text ("9-…" is older than "10-…"). */
    public static List<String> newestFirst(Collection<String> names) {
        List<String> out = new ArrayList<>();
        if (names != null) for (String n : names) if (startedAt(n) >= 0) out.add(n);
        Collections.sort(out, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                long x = startedAt(a), y = startedAt(b);
                // same millisecond (two tasks cannot start in the same ms, but a copied file can): the name decides,
                // so the order never depends on how the directory happened to be listed
                if (x == y) return b.compareTo(a);
                return x > y ? -1 : 1;
            }
        });
        return out;
    }

    /** What to delete from the ring directory: the oldest first until at most maxCount files are left and they add up
     * to at most maxBytes; the newest file is never dropped, even when it alone is over maxBytes (a task that just ran
     * is the one being looked at). Names that are not ours are always deleted. */
    public static List<String> toDelete(Map<String, Long> sizes, int maxCount, long maxBytes) {
        List<String> out = new ArrayList<>();
        if (sizes == null || sizes.isEmpty()) return out;
        List<String> ours = newestFirst(sizes.keySet());
        Map<String, Long> byName = new HashMap<>(sizes);
        long total = 0;
        for (String n : ours) total += size(byName.get(n));
        // ours is newest first, so walking it backwards drops the oldest first and stops before the newest one
        int kept = ours.size();
        for (int i = ours.size() - 1; i >= 1 && (kept > maxCount || total > maxBytes); i--) {
            out.add(ours.get(i));
            total -= size(byName.get(ours.get(i)));
            kept--;
        }
        for (String n : sizes.keySet()) if (startedAt(n) < 0) out.add(n);
        return out;
    }

    private static long size(Long bytes) { return bytes == null ? 0 : bytes; }

    /** One task's transcript as it is saved: what was asked, how it ended and the whole model conversation, so the
     * failure of a task that took 105 s can be read from the file hours later. */
    public static JSONObject entry(Ledger t, JSONArray messages, long wallMs) throws JSONException {
        JSONObject o = new JSONObject()
                .put("id", t.id)
                .put("at", t.startedAt)
                .put("utterance", t.utterance)
                .put("source", t.source)
                .put("state", t.state.name().toLowerCase(Locale.ROOT))
                .put("reply", t.reply);
        if (t.error != null && !t.error.isEmpty()) o.put("error", t.error);
        return o.put("wall_ms", wallMs).put("messages", messages);
    }
}
