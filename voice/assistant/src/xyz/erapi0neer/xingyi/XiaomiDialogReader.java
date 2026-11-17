package xyz.erapi0neer.xingyi;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read the newest dialog only, after an explicit owner opt-in. All calls belong on a worker. */
public final class XiaomiDialogReader {
    private static final Uri HISTORY = Uri.parse("content://com.xiaomi.voicecontrol.provider.queryhistory/instruction");
    private static final String FILTER = "userId=? AND dialog_id=? AND pageSize=? AND timestamp";
    private static final String[] META = {"dialog_id", "timestamp"};
    private static final String[] DETAIL = {"dialog_id", "timestamp", "instruction"};
    private static final long FRESH_MS = 35000, SETTLE_MS = 2200;
    private final ContentResolver resolver;
    private final String owner;
    private long armWall, armElapsed, highWater;
    private Stamp baseline, pending;
    private byte[] pendingContent;
    private long pendingSince;
    private final ArrayDeque<String> delivered = new ArrayDeque<>();
    private SongCandidate lastSong;
    private String musicAnchorId, musicAnchorTitle;
    private long musicAnchorAt;
    private boolean armed;

    public static final class Event {
        public final String dialogId, original, cause;
        public final boolean musicSelection;
        public final long queryElapsedMs;
        Event(String id, String query, String cause, boolean selection, long when) {
            dialogId = id; original = query; this.cause = cause; musicSelection = selection; queryElapsedMs=when;
        }
    }

    static boolean continuation(String text) {
        return text!=null && text.trim().matches("(?:小爱同学[，, ]*)?(?:星仪[，, ]*)?(?:继续执行|确认执行)[。！!]?");
    }
    static boolean explicitConfirmation(String text) {
        return continuation(text) && text.contains("确认执行");
    }

    /** Kept on the TV. A candidate alone never authorizes model or catalog submission. */
    static final class SongCandidate {
        final String dialogId, original, title, artist;
        final long queryElapsedMs;
        SongCandidate(String id, String original, GenericRefusalText.Scope scope, long when) {
            dialogId = id; this.original = original; title = scope.title; artist = scope.artist; queryElapsedMs = when;
        }
        boolean same(SongCandidate other) {
            return other != null && dialogId.equals(other.dialogId) && original.equals(other.original)
                    && queryElapsedMs == other.queryElapsedMs;
        }
    }

    public XiaomiDialogReader(Context context, String ownerId) {
        if (ownerId == null || !ownerId.matches("[0-9]{1,24}")) throw new IllegalArgumentException("需要本机小米账号的数字 ID");
        resolver = context.getApplicationContext().getContentResolver();
        owner = ownerId;
    }

    /** Baseline projects only timestamps and IDs; the existing dialog's text is never requested. */
    public synchronized void arm() throws IOException {
        armed = false;
        armWall = System.currentTimeMillis();
        armElapsed = SystemClock.elapsedRealtime();
        baseline = metadata();
        highWater = armWall;
        pending = null;
        lastSong = null;
        musicAnchorId = musicAnchorTitle = null;
        delivered.clear();
        armed = true;
    }

    public synchronized Event poll() throws IOException {
        Observation o = observe(null);
        lastSong = o.song;
        if (o.assessment == null || !o.assessment.explicit) { pending = null; pendingContent = null; return null; }
        // Approval requires the live TV's native failure signal, not merely an account history entry.
        if (continuation(o.assessment.query)) { pending=null; pendingContent=null; return null; }
        long now = SystemClock.elapsedRealtime();
        if (!o.stamp.same(pending) || !java.util.Arrays.equals(pendingContent, o.fingerprint)) {
            pending = o.stamp; pendingContent = o.fingerprint; pendingSince = now; return null;
        }
        if (now - pendingSince < SETTLE_MS) return null;
        return consume(o, "explicit_refusal");
    }

    /** A verified native failure can skip settling only for its own complete, current history turn. */
    synchronized Event claimConfirmedRefusal(String verifiedFullId) throws IOException {
        if (!armed || verifiedFullId == null || !verifiedFullId.matches("[0-9a-fA-F]{32}")
                || delivered.contains(verifiedFullId)) return null;
        Observation current = observe(verifiedFullId);
        if (current.assessment == null || !current.assessment.explicit || !current.stamp.same(metadata())) return null;
        return consume(current, "confirmed_refusal");
    }

    /** A fixed local confirmation still needs the opted-in owner's same newest query. Probe without consuming
     * so an unaddressed "continue" with no visible preview can remain Xiaomi's, or receive a later refusal. */
    synchronized Event inspectLocalConfirmation(String verifiedId,String original,long recognizedAt) throws IOException {
        if(!armed || !continuation(original) || verifiedId==null || !verifiedId.matches("[0-9a-fA-F]{32}")
                || delivered.contains(verifiedId))return null;
        Observation o=observe(verifiedId,original);
        if(o.stamp==null || !o.stamp.same(metadata()))return null;
        long storedAt=armElapsed+o.stamp.first-armWall;
        if(Math.abs(recognizedAt-storedAt)>2500)return null;
        return new Event(verifiedId,original,"local_confirmation",false,recognizedAt);
    }
    synchronized void noteConfirmationHandled(String id) {
        if(!delivered.contains(id)){
            if(delivered.size()==64)delivered.removeFirst();
            delivered.addLast(id);
        }
    }

    synchronized SongCandidate pollSongCandidate() {
        return lastSong != null && !delivered.contains(lastSong.dialogId) ? lastSong : null;
    }

    /** Local player evidence can take time to load. Revalidate the newest dialog before committing. */
    synchronized Event claimSongCorrection(SongCandidate candidate) throws IOException {
        if (candidate == null || delivered.contains(candidate.dialogId)) return null;
        Observation current = observe(null);
        if (!candidate.same(current.song)) return null;
        return consume(current,"fresh_wrong_recording");
    }

    private Event consume(Observation current, String cause) {
        String id = current.stamp.id, original = current.assessment.query;
        if (delivered.contains(id)) return null;
        if (delivered.size() == 64) delivered.removeFirst();
        delivered.addLast(id);
        if (current.assessment.musicSelection) {
            GenericRefusalText.Scope scope = GenericRefusalText.musicSelection(original);
            if (scope != null) rememberMusic(current.stamp,scope.title);
        }
        return new Event(id, original, cause, current.assessment.musicSelection,armElapsed+current.stamp.first-armWall);
    }

    /** A diagnostic for a supplied synthetic question. It neither emits nor consumes an Event. */
    public synchronized JSONObject diagnose(String expectedSyntheticQuery) throws IOException {
        if (expectedSyntheticQuery == null || SharedTextDraft.text(expectedSyntheticQuery) == null)
            throw new IllegalArgumentException("需要本次测试使用的受控问题");
        Observation o = observe(null);
        try {
            JSONObject result = new JSONObject();
            boolean match = o.assessment != null && expectedSyntheticQuery.equals(o.assessment.query);
            result.put("matched", match);
            if (!match) return result.put("reason", o.assessment == null ? o.reason : "different_or_missing_test_query");
            return result.put("reason", o.assessment.reason).put("explicit_unsupported", o.assessment.explicit)
                    .put("instructions", new JSONArray(o.assessment.types)).put("tts_categories", new JSONArray(o.assessment.categories));
        } catch (org.json.JSONException e) { throw new IOException("诊断结果格式异常"); }
    }

    private Observation observe(String requiredId) throws IOException {
        return observe(requiredId,null);
    }

    private Observation observe(String requiredId,String exactControl) throws IOException {
        if (!armed) throw new IllegalStateException("观察未启用");
        long now = System.currentTimeMillis();
        if (Math.abs((now - armWall) - (SystemClock.elapsedRealtime() - armElapsed)) > 3000) {
            arm();
            return new Observation("clock_changed");
        }
        Stamp s = metadata();
        if (s == null) return new Observation("no_latest_rows");
        if (requiredId != null && !requiredId.equals(s.id)) return new Observation("different_dialog");
        // The provider ignores its timestamp argument. Only ask for the instruction column
        // after we have verified the whole newest group is post-opt-in and still fresh.
        if ((baseline != null && baseline.id.equals(s.id)) || s.first <= armWall
                || s.last > now + 1000 || now - s.first > FRESH_MS || s.last < highWater)
            return new Observation("outside_window");
        highWater = s.last;
        List<String> rows = new ArrayList<>(s.times.size());
        try (Cursor c = query(DETAIL)) {
            if (c.getCount() != s.times.size()) return new Observation("group_changed");
            int chars = 0;
            while (c.moveToNext()) {
                // No content from a different or pre-enable group can reach getString(2).
                if (!s.id.equals(c.getString(0)) || s.times.get(rows.size()).longValue() != c.getLong(1))
                    return new Observation("group_changed");
                String row = c.getString(2);
                if (row == null || row.length() > 32768 || (chars += row.length()) > 131072)
                    return new Observation("row_size");
                rows.add(row);
            }
        } catch (RuntimeException e) { throw readFailure(); }
        if(exactControl!=null && !singleControl(s.id,rows,exactControl))return new Observation("different_control");
        XiaomiRefusalGate.Assessment a = XiaomiRefusalGate.assess(s.id, rows);
        if (a.musicTitle != null) {
            rememberMusic(s,a.musicTitle);
        } else if (!a.musicSelection && musicAnchorId != null && !musicAnchorId.equals(s.id)
                && s.first > musicAnchorAt && s.first - musicAnchorAt < 60000) {
            GenericRefusalText.Scope selection = GenericRefusalText.musicSelection(a.query);
            if (selection != null && selection.title.equalsIgnoreCase(musicAnchorTitle)) {
                // The poller may miss an intervening conversation. Check the previous native
                // page's ID and timestamp without reading that page's utterance.
                Stamp preceding = metadata(s.id);
                if (preceding != null && musicAnchorId.equals(preceding.id) && preceding.first == musicAnchorAt)
                    a = XiaomiRefusalGate.assess(s.id,rows,musicAnchorTitle);
            }
        }
        byte[] signature = null;
        if (a.explicit) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                for (String row : rows) { digest.update(row.getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte)0); }
                signature = digest.digest();
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("缺少摘要算法", impossible); }
        }
        SongCandidate song = songCandidate(s.id, armElapsed + (s.first - armWall), a, rows);
        return new Observation(s, a, signature, song);
    }

    private static SongCandidate songCandidate(String id, long queryElapsedMs,
                                              XiaomiRefusalGate.Assessment a, List<String> rows) {
        GenericRefusalText.Scope scope = a.musicSelection ? GenericRefusalText.musicSelection(a.query) : GenericRefusalText.scope(a.query);
        if (scope == null || scope.kind != GenericRefusalText.Kind.SONG || a.explicit
                || !("not_exclusive_unsupported".equals(a.reason) || "no_exclusive_supported_refusal".equals(a.reason)
                    || "no_explicit_refusal".equals(a.reason) || "no_complete_spoken_result".equals(a.reason)))
            return null;
        int queryCount = 0;
        // A history page is a projection, not an action journal. Use it only for the user's
        // sole song request; playback evidence must separately establish the wrong result.
        try {
            for (String raw : rows) {
                org.json.JSONTokener parser = new org.json.JSONTokener(raw);
                Object value = parser.nextValue();
                if (!(value instanceof JSONObject) || parser.nextClean() != 0) return null;
                JSONObject row = (JSONObject)value, h = row.getJSONObject("header"), p = row.getJSONObject("payload");
                if (!id.equals(h.opt("dialog_id")) || flag(p,"multi_intention") || flag(p,"multi_intention_non_speak")) return null;
                String type = h.optString("namespace") + "." + h.optString("name");
                switch (type) {
                    case "Template.Query":
                        if (++queryCount != 1 || !a.query.equals(p.opt("text"))) return null;
                        break;
                    case "SpeechSynthesizer.Speak":
                        Object category = p.opt("tts_category");
                        if (category != null && !"SUCC".equals(category) && !"SAME_STATE".equals(category)) return null;
                        break;
                    case "Template.Toast":
                    case "Template.ToastStream":
                    case "Nlp.LargeLanguageModelContent": break;
                    default: return null;
                }
            }
        } catch (Exception malformed) { return null; }
        return queryCount == 1 ? new SongCandidate(id, a.query, scope, queryElapsedMs) : null;
    }

    private static boolean flag(JSONObject row, String name) {
        return row.has(name) && !Boolean.FALSE.equals(row.opt(name));
    }
    private static boolean singleControl(String id,List<String> rows,String expected) {
        int queries=0;
        try {
            for(String raw:rows) {
                org.json.JSONTokener parser=new org.json.JSONTokener(raw);
                Object value=parser.nextValue();
                if(!(value instanceof JSONObject)||parser.nextClean()!=0)return false;
                JSONObject h=((JSONObject)value).getJSONObject("header"), p=((JSONObject)value).getJSONObject("payload");
                if(!id.equals(h.opt("dialog_id"))||flag(p,"multi_intention")||flag(p,"multi_intention_non_speak"))return false;
                if("Dialog".equals(h.opt("namespace")) && "IllegalContent".equals(h.opt("name")))return false;
                if("Template".equals(h.opt("namespace")) && "Query".equals(h.opt("name")))
                    if(++queries!=1 || !expected.equals(p.opt("text")))return false;
            }
        } catch(Exception invalid){return false;}
        return queries==1;
    }

    private Cursor query(String[] projection) throws IOException {
        return query(projection,"");
    }
    private Cursor query(String[] projection,String before) throws IOException {
        Cursor c = resolver.query(HISTORY, projection, FILTER, new String[]{owner, before, "1", "0"}, null);
        if (c == null) throw readFailure();
        return c;
    }

    private Stamp metadata() throws IOException {
        return metadata("");
    }
    private void rememberMusic(Stamp stamp,String title) {
        musicAnchorId = stamp.id; musicAnchorAt = stamp.first; musicAnchorTitle = title;
    }
    private Stamp metadata(String before) throws IOException {
        try (Cursor c = query(META,before)) {
            if (c.getCount() <= 0 || c.getCount() > XiaomiRefusalGate.MAX_ROWS) return null;
            String id = null;
            List<Long> times = new ArrayList<>();
            while (c.moveToNext()) {
                String next = c.getString(0);
                long time = c.getLong(1);
                if (next == null || next.isEmpty() || next.length() > 256 || time <= 0
                        || (id != null && !id.equals(next)) || (!times.isEmpty() && time < times.get(times.size()-1))) return null;
                id = next;
                times.add(time);
            }
            return times.isEmpty() ? null : new Stamp(id,times);
        } catch (RuntimeException e) { throw readFailure(); }
    }

    private static IOException readFailure() { return new IOException("无法读取小爱向本机提供的指令记录"); }

    private static final class Stamp {
        final String id;
        final List<Long> times;
        final long first, last;
        Stamp(String id, List<Long> times) { this.id = id; this.times = times; first = times.get(0); last = times.get(times.size()-1); }
        boolean same(Stamp other) { return other != null && id.equals(other.id) && times.equals(other.times); }
    }
    private static final class Observation {
        final Stamp stamp;
        final XiaomiRefusalGate.Assessment assessment;
        final String reason;
        final byte[] fingerprint;
        final SongCandidate song;
        Observation(String reason) { this.reason=reason; stamp=null; assessment=null; fingerprint=null; song=null; }
        Observation(Stamp stamp, XiaomiRefusalGate.Assessment assessment, byte[] fingerprint, SongCandidate song) {
            this.stamp=stamp; this.assessment=assessment; reason=assessment.reason; this.fingerprint=fingerprint; this.song=song;
        }
    }
}
