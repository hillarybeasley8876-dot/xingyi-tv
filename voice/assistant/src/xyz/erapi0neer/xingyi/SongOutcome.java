package xyz.erapi0neer.xingyi;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evidence about one foreground request. Merely waiting for an absent song is never a mismatch. */
final class SongOutcome {
    enum Verdict { CORRECT, WRONG_VERSION, UNKNOWN }
    private static final String PLAYER = "package=com.netease.cloudmusic.tv";
    private static final Pattern STACK = Pattern.compile("Sessions Stack - have ([0-9]{1,4}) sessions:");
    private static final Pattern STATE = Pattern.compile("^state=PlaybackState \\{state=([0-9]+),");
    private static final Pattern UPDATED = Pattern.compile("(?:^|,\\s*)updated=([0-9]+)(?:,|\\})");
    private static final Pattern SPEED = Pattern.compile("(?:^|,\\s*)speed=([^,}]+)");
    private static final Pattern METADATA = Pattern.compile("^metadata:\\s*size=[1-9][0-9]*, description=(.*)$");
    private static final Pattern QUALIFIERS = Pattern.compile("(?:\\([^()]{1,100}\\)\\s*){1,4}");
    private static final Pattern LIVE_ONLY = Pattern.compile("\\((?:live(?:版)?|现场(?:版)?|現場(?:版)?)\\)");
    private static final Pattern ALTERNATE = Pattern.compile(
            "\\b(?:live|remix|cover|karaoke|instrumental|demo|acoustic|concert)\\b|演唱[会會]|[现現][场場]|翻唱|伴奏|不插[电電]|[纯純]音[乐樂]");
    private static final long MIN_GAP = 1000, MAX_GAP = 9000, TURN_WINDOW = 35000, MAX_START_DELAY = 12000;

    /** Android's playback update and our sample time are both elapsedRealtime milliseconds. */
    static final class Observation {
        final int state;
        final long updatedElapsedMs, sampledElapsedMs;
        final String title, artist, album;
        Observation(int state, long updated, long sampled, String title, String artist, String album) {
            this.state = state;
            updatedElapsedMs = updated;
            sampledElapsedMs = sampled;
            this.title = title;
            this.artist = artist;
            this.album = album;
        }
    }

    private static final class Session {
        boolean active, seenState, knownActive;
        int state = -1;
        long updated = -1;
        String[] fields;
        void add(String line) {
            if (line.startsWith("active=")) {
                active = line.equals("active=true");
                knownActive = active || line.equals("active=false");
            } else if (line.startsWith("state=")) {
                if (seenState) { state = -1; return; }
                seenState = true;
                Matcher s = STATE.matcher(line);
                Matcher t = UPDATED.matcher(line);
                Matcher speed = SPEED.matcher(line);
                try {
                    if (s.find()) state = Integer.parseInt(s.group(1));
                    if (t.find()) updated = Long.parseLong(t.group(1));
                    if (state == 3 && speed.find() && !(Double.parseDouble(speed.group(1)) > 0))
                        state = -1;
                } catch (NumberFormatException malformed) { state = -1; }
            } else if (line.startsWith("metadata:")) {
                Matcher m = METADATA.matcher(line);
                fields = m.matches() ? m.group(1).split(", ", -1) : null;
            }
        }
        Observation observation(long sampled) {
            if (!active || state < 0 || fields == null || fields.length != 3) return null;
            for (String f : fields) if (f.trim().isEmpty() || "null".equals(f) || f.length() > 500) return null;
            return new Observation(state, updated, sampled, fields[0], fields[1], fields[2]);
        }
    }

    static Observation parse(String snapshot, long sampledElapsedMs) {
        if (snapshot == null || sampledElapsedMs < 0) return null;
        Session current = null;
        Observation found = null;
        int expected = 0, listed = 0;
        boolean hasStack = false, ambiguous = false;
        for (String row : (snapshot + "\nEND").split("\n")) {
            String line = row.trim();
            Matcher stack = STACK.matcher(line);
            if (stack.matches()) {
                hasStack = true;
                expected += Integer.parseInt(stack.group(1));
            } else if (line.startsWith("package=") || line.equals("END")) {
                if (current != null && !current.knownActive) ambiguous = true;
                if (current != null && current.active) {
                    Observation candidate = current.observation(sampledElapsedMs);
                    if (candidate == null || found != null) return null; // ambiguous or incomplete
                    found = candidate;
                }
                if (line.startsWith("package=")) listed++;
                current = PLAYER.equals(line) ? new Session() : null;
            } else if (current != null) current.add(line);
        }
        if (ambiguous || !hasStack || expected != listed) return null;
        if (found != null) return found;
        // A complete Android 9 stack establishes absence. Empty/truncated output proves nothing.
        return new Observation(0,-1,sampledElapsedMs,"","","");
    }

    static boolean isExact(Observation s, List<String> displayTitles, String creditedArtist, String album) {
        if (s == null || s.state != 3 || displayTitles == null || !same(s.artist, creditedArtist)) return false;
        if (album != null && !album.trim().isEmpty() && !same(s.album, album)) return false;
        for (String label : displayTitles) if (same(s.title, label)) return true;
        return false;
    }

    static Verdict judge(Observation first, Observation second, long queryElapsedMs, String title,
                         List<String> displayTitles, String creditedArtist, String album) {
        if (isExact(second, displayTitles, creditedArtist, album)
                && !isWrongVersion(second,title,displayTitles,creditedArtist)) return Verdict.CORRECT;
        // Before catalog resolution a credited co-performance can be exactly what the caller meant.
        if (album == null && plausibleCredit(second,title,creditedArtist)) return Verdict.UNKNOWN;
        if (first == null || second == null || queryElapsedMs < 0 || first.state != 3 || second.state != 3)
            return Verdict.UNKNOWN;
        long gap = second.sampledElapsedMs - first.sampledElapsedMs;
        if (gap < MIN_GAP || gap > MAX_GAP || first.sampledElapsedMs < queryElapsedMs ||
                second.sampledElapsedMs - queryElapsedMs > TURN_WINDOW) return Verdict.UNKNOWN;
        if (!newSince(first, queryElapsedMs) || !newSince(second, queryElapsedMs)
                || second.updatedElapsedMs < first.updatedElapsedMs || !same(first.title, second.title)
                || !same(first.artist, second.artist) || !same(first.album, second.album)) return Verdict.UNKNOWN;
        return isWrongVersion(second, title, displayTitles, creditedArtist) ? Verdict.WRONG_VERSION : Verdict.UNKNOWN;
    }

    private static boolean newSince(Observation s, long start) {
        // A pre-existing playing cover could be unrelated to this request. Never restart it on elapsed time alone.
        return s.updatedElapsedMs >= start && s.updatedElapsedMs <= s.sampledElapsedMs
                && s.updatedElapsedMs - start <= MAX_START_DELAY;
    }

    static boolean isWrongVersion(Observation s, String title, List<String> displayTitles, String artist) {
        if (s == null || s.state != 3 || norm(artist).isEmpty() || norm(title).isEmpty()) return false;
        String actual = norm(s.title), base = norm(title);
        boolean exactLabel = actual.equals(base);
        if (displayTitles != null) for (String label : displayTitles) exactLabel |= same(label, s.title);
        String suffix = actual.startsWith(base) ? actual.substring(base.length()).trim() : "";
        boolean tagged = !suffix.isEmpty() && QUALIFIERS.matcher(suffix).matches();
        if (!exactLabel && !tagged) return false;
        if (!same(s.artist, artist)) return true;
        // A new album name alone, or an unknown parenthesized translation, cannot establish a wrong recording.
        return !ALTERNATE.matcher(base).find() && ((tagged && ALTERNATE.matcher(suffix).find())
                || ((exactLabel || tagged) && ALTERNATE.matcher(norm(s.album)).find()));
    }

    /** Enough evidence to stop considering a correction, never proof of a catalog release. */
    static boolean possiblyIntended(Observation s, String title, String artist, long start) {
        if (s == null || s.state != 3 || !newSince(s,start)) return false;
        if (plausibleCredit(s,title,artist)) return true;
        if (!same(s.artist,artist)) return false;
        String base=norm(title), actual=norm(s.title);
        if (base.isEmpty() || !actual.startsWith(base)) return false;
        String rest=actual.substring(base.length()).trim();
        return (rest.isEmpty() || QUALIFIERS.matcher(rest).matches())
                && !isWrongVersion(s,title,null,artist);
    }

    /** Only suppresses redundant dispatch. A positive completion still requires exact catalog metadata. */
    static boolean plausibleCredit(Observation s, String title, String artist) {
        if (s == null || s.state != 3 || norm(title).isEmpty()) return false;
        String actual = norm(s.title), requested = norm(title);
        if (!actual.startsWith(requested)) return false;
        String suffix = actual.substring(requested.length()).trim();
        if (!suffix.isEmpty() && !LIVE_ONLY.matcher(suffix).matches()) return false;
        String wanted = norm(artist).replace('雲','云');
        if (wanted.isEmpty()) return true;
        // A separately billed performer is valid for "某人的歌". Keep the solo/album
        // test for already catalog-resolved recordings, and treat explicit cover labels separately.
        String[] performers = norm(s.artist).replace('雲','云').split("\\s*/\\s*");
        for (String performer : performers) if (wanted.equals(performer)) {
            return !suffix.isEmpty() || !ALTERNATE.matcher(norm(s.album)).find();
        }
        return false;
    }

    private static boolean same(String a, String b) {
        String x = norm(a);
        return !x.isEmpty() && x.equals(norm(b));
    }
    private static String norm(String s) {
        return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }
    private SongOutcome() {}
}
