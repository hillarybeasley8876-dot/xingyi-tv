package xyz.erapi0neer.xingyi;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/** Picks the NetEase recording a music request means, from NetEase web search pages. */
public final class MusicResolver {
    public interface Search {
        JSONObject songs(String keywords) throws IOException;
    }

    private static final int INSENSITIVE =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern VERSION = Pattern.compile(
            "live|演唱会|现场|remix|混音|伴奏|纯音乐|翻唱|cover|dj",
            INSENSITIVE
    );

    private static final Pattern ALBUM_VERSION = Pattern.compile(
            "live|concert|演唱会|現場|现场|第[零〇一二三四五六七八九十百0-9]+期|remix|混音|伴奏|纯音乐|翻唱|cover|翻自|(?<![a-z])dj(?![a-z])",
            INSENSITIVE
    );

    private static final Pattern COMPILATION = Pattern.compile(
            "精选|精選|合辑|合輯|合集|金曲|greatest hits|compilation|best of",
            INSENSITIVE
    );

    private static final Pattern BRACKET_SUFFIX = Pattern.compile(
            "\\s*[(\\[【][^()\\[\\]【】]*[)\\]】]\\s*$"
    );

    private static final Pattern DASH_SUFFIX = Pattern.compile(
            "\\s+-\\s+.*$"
    );

    private static final Pattern UNTAGGED_TITLE = Pattern.compile(
            "伴奏|纯音乐|铃声"
    );

    public static final class Pick {
        public final long id;
        public final String title, artist, album;
        public final int score;
        public final boolean confident;

        Pick(long id, String title, String artist, String album, int score, boolean confident) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.score = score;
            this.confident = confident;
        }
    }

    public static Pick resolve(
            String content,
            List<String[]> splits,
            boolean wantsVersion,
            Search search
    ) throws IOException {
        Pick best = null;

        for (String query : queries(content, splits)) {
            try {
                JSONObject response = search.songs(query);
                checkResponse(response);

                JSONObject result = response.optJSONObject("result");
                JSONArray songs = result == null
                        ? null
                        : result.optJSONArray("songs");

                if (songs != null) {
                    for (int rank = 0; rank < songs.length(); rank++) {
                        JSONObject song = songs.optJSONObject(rank);
                        Pick pick = scoreSong(
                                song,
                                rank,
                                content,
                                splits,
                                wantsVersion
                        );

                        if (pick != null && pick.score > 0 && better(pick, best)) {
                            best = pick;
                        }
                    }
                }
            } catch (IOException failure) {
                if (best != null) {
                    return best;
                }
                throw failure;
            }

            if (best != null && best.confident) {
                break;
            }
        }

        return best;
    }

    public static List<String> queries(String content, List<String[]> splits) {
        List<String> queries = new ArrayList<String>();
        String[] first = null;

        for (String[] split : splits) {
            if (split[0] != null && !split[0].isEmpty()) {
                first = split;
                break;
            }
        }

        if (first != null) {
            queries.add(first[0] + " " + first[1]);
        }

        if (!queries.contains(content)) {
            queries.add(content);
        }

        if (first != null && !queries.contains(first[0])) {
            queries.add(first[0]);
        }

        return new ArrayList<String>(
                queries.subList(0, Math.min(3, queries.size()))
        );
    }

    public static String norm(String s) {
        String normalized = Normalizer.normalize(
                s == null ? "" : s,
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT);

        StringBuilder result = new StringBuilder();
        int[] codePoints = normalized.codePoints().toArray();

        for (int codePoint : codePoints) {
            if (!isWhitespace(codePoint) && !isPunctuationOrSymbol(codePoint)) {
                result.appendCodePoint(codePoint);
            }
        }

        return result.toString();
    }

    public static String baseTitle(String s) {
        String title = strip(Normalizer.normalize(
                s == null ? "" : s,
                Normalizer.Form.NFKC
        ));
        String previous = null;

        while (previous == null || !previous.equals(title)) {
            previous = title;
            title = BRACKET_SUFFIX.matcher(title).replaceAll("");
            title = strip(title);
            title = DASH_SUFFIX.matcher(title).replaceAll("");
            title = strip(title);
        }

        return !title.isEmpty() ? title : previous;
    }

    public static boolean titleTagged(String name) {
        String full = strip(Normalizer.normalize(
                name == null ? "" : name,
                Normalizer.Form.NFKC
        ));
        String base = baseTitle(full);

        if (base.equals(full)) {
            return UNTAGGED_TITLE.matcher(full).find();
        }

        int suffixStart = full.offsetByCodePoints(0, codePointLength(base));
        String suffix = full.substring(suffixStart);
        return VERSION.matcher(suffix).find() || suffix.indexOf('版') >= 0;
    }

    public static boolean albumTagged(String album) {
        return ALBUM_VERSION.matcher(album).find();
    }

    public static double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }

        int maximum = Math.max(codePointLength(a), codePointLength(b));
        return 1.0 - ((double) lev(a, b)) / (double) maximum;
    }

    private static Pick scoreSong(
            JSONObject song,
            int rank,
            String content,
            List<String[]> splits,
            boolean wantsVersion
    ) {
        String name = jsonString(song, "name");
        JSONObject albumObject = song.optJSONObject("album");
        String album = jsonString(albumObject, "name");

        String titleNormalized = norm(baseTitle(name));
        String fullNormalized = norm(name);

        Set<String> aliases = new HashSet<String>();
        addAliases(aliases, song.optJSONArray("alias"));
        addAliases(aliases, song.optJSONArray("transNames"));

        List<String> credits = new ArrayList<String>();
        List<String> artistNames = new ArrayList<String>();
        JSONArray artists = song.optJSONArray("artists");

        if (artists != null) {
            for (int i = 0; i < artists.length(); i++) {
                JSONObject artist = artists.optJSONObject(i);
                String artistName = jsonString(artist, "name");
                credits.add(norm(artistName));
                artistNames.add(artistName);
            }
        }

        String contentNormalized = norm(content);
        boolean tagged = titleTagged(name) || albumTagged(album);
        int titleLength = codePointLength(titleNormalized);

        boolean hasBest = false;
        int bestScore = 0;
        boolean bestConfident = false;

        for (String[] split : splits) {
            String titlePartNormalized = norm(split[1]);
            String artistPartNormalized = norm(split[0]);
            int titleMatch;

            if (!titlePartNormalized.isEmpty()
                    && (titlePartNormalized.equals(titleNormalized)
                    || titlePartNormalized.equals(fullNormalized)
                    || aliases.contains(titlePartNormalized))) {
                titleMatch = 3;
            } else if (titleLength >= 2
                    && contentNormalized.contains(titleNormalized)) {
                titleMatch = 2;
            } else if (similarity(
                    titlePartNormalized,
                    titleNormalized
            ) >= 0.8) {
                titleMatch = 1;
            } else {
                continue;
            }

            int artistMatch;
            if (artistPartNormalized.isEmpty()) {
                artistMatch = 0;
                for (String credit : credits) {
                    if (codePointLength(credit) >= 2
                            && contentNormalized.contains(credit)) {
                        artistMatch = 1;
                        break;
                    }
                }
            } else {
                artistMatch = -1;
                for (String credit : credits) {
                    if (credit.equals(artistPartNormalized)
                            || credit.contains(artistPartNormalized)) {
                        artistMatch = 2;
                        break;
                    }
                }
            }

            int adjustment = tagged
                    ? (wantsVersion ? 30 : -150)
                    : 0;
            int score = titleMatch * 100 + artistMatch * 40 + adjustment;
            boolean confident =
                    ((titleMatch == 3 && artistMatch >= 0)
                            || (titleMatch == 2 && artistMatch >= 1)
                            || (titleMatch == 1 && artistMatch == 2))
                            && !(tagged && !wantsVersion);

            if (!hasBest || score > bestScore) {
                hasBest = true;
                bestScore = score;
                bestConfident = confident;
            }
        }

        if (!hasBest) {
            return null;
        }

        int finalScore = bestScore + Math.max(0, 20 - rank);
        if (COMPILATION.matcher(album).find()) {
            finalScore -= 15;
        }
        if (song.optInt("fee", 0) == 4) {
            finalScore -= 10;
        }

        return new Pick(
                song.optLong("id"),
                name,
                joinNames(artistNames),
                album,
                finalScore,
                bestConfident
        );
    }

    private static int lev(String a, String b) {
        int[] aCodePoints = a.codePoints().toArray();
        int[] bCodePoints = b.codePoints().toArray();
        int[] previous = new int[bCodePoints.length + 1];

        for (int j = 0; j <= bCodePoints.length; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= aCodePoints.length; i++) {
            int[] current = new int[bCodePoints.length + 1];
            current[0] = i;

            for (int j = 1; j <= bCodePoints.length; j++) {
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                int substitution = previous[j - 1]
                        + (aCodePoints[i - 1] != bCodePoints[j - 1] ? 1 : 0);
                current[j] = Math.min(
                        deletion,
                        Math.min(insertion, substitution)
                );
            }

            previous = current;
        }

        return previous[previous.length - 1];
    }

    private static void checkResponse(JSONObject response) throws IOException {
        if (response == null) {
            throw new IOException("search_failed");
        }

        int code = response.isNull("code")
                ? 200
                : response.optInt("code", 200);

        if (code == 405 || code == 406) {
            throw new IOException("rate_limited");
        }
        if (code != 200) {
            throw new IOException("search_failed");
        }
    }

    private static boolean better(Pick candidate, Pick current) {
        if (current == null) {
            return true;
        }
        if (candidate.confident != current.confident) {
            return candidate.confident;
        }
        return candidate.score > current.score;
    }

    private static void addAliases(Set<String> aliases, JSONArray values) {
        if (values == null) {
            return;
        }

        for (int i = 0; i < values.length(); i++) {
            String value = jsonString(values, i);
            if (!value.isEmpty()) {
                aliases.add(norm(value));
            }
        }
    }

    private static String joinNames(List<String> names) {
        StringBuilder joined = new StringBuilder();

        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                joined.append('/');
            }
            joined.append(names.get(i));
        }

        return joined.toString();
    }

    private static String jsonString(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return "";
        }
        return object.optString(key, "");
    }

    private static String jsonString(JSONArray array, int index) {
        if (array == null || array.isNull(index)) {
            return "";
        }
        return array.optString(index, "");
    }

    private static int codePointLength(String value) {
        return value.codePoints().toArray().length;
    }

    private static String strip(String value) {
        int start = 0;
        int end = value.length();

        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }

        if (start == end) {
            return "";
        }

        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        return value.substring(start, end);
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    private static boolean isPunctuationOrSymbol(int codePoint) {
        int type = Character.getType(codePoint);

        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }

    private MusicResolver() { }
}
