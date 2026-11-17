package xyz.erapi0neer.xingyi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves an exact song and credited artist against NetEase's public web catalog, on a worker. */
public final class NeteaseCatalog {
    private static final int MAX_BYTES = 256 * 1024;
    private static final String SEARCH_URL = "https://music.163.com/api/search/get/web";
    private static final String RIGHTS_URL = "https://music.163.com/api/song/enhance/player/url";
    private static final int ARTIST_LIMIT = 100;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "xingyi-music-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private static final Pattern[] VERSIONS = {
        Pattern.compile("\\blive\\b|concert|演唱会|演唱會|现场|現場|第[零〇一二三四五六七八九十百0-9]+期"),
        Pattern.compile("remix|混音|混錄"),
        Pattern.compile("\\binstrumental\\b|\\bkaraoke\\b|伴奏|纯音乐|純音樂"),
        Pattern.compile("\\bdemo\\b|小样|小樣"),
        Pattern.compile("\\bacoustic\\b|不插电|不插電")
    };
    private static final Pattern COMPILATION = Pattern.compile("精选|精選|合辑|合輯|合集|金曲|greatest hits|compilation|best of");
    private static final Pattern COVER = Pattern.compile("\\bcover(?:s|ed)?\\b|\\btribute\\b|翻唱|翻自|致敬|原唱[:：]");
    private static final Pattern LIVE_LABEL = Pattern.compile("\\s*\\((?:live(?:版)?|现场(?:版)?|現場(?:版)?)\\)$");
    private static final Pattern SPOKEN_LIVE = Pattern.compile("(?:\\s*\\((?:live(?:版)?|现场(?:版)?|現場(?:版)?)\\)|\\s+(?:live(?:版)?)|(?:现场版|現場版|live版))$");
    private static final Pattern STUDIO_ONLY = Pattern.compile("\\s*(?:\\((?:原版|原唱版|独唱版|录音室版)\\)|(?:原版|原唱版|独唱版|录音室版))$");
    /** A catalog name that is the song's title plus a bracketed subtitle: 「甲乙丙丁 (你我怎么两清)」 (2026-11-10 10:48,
     * NetEase writes alternate titles this way). Normalising folds 「（）」 into 「()」, so both brackets are one here. */
    private static final Pattern SUBTITLED = Pattern.compile("^(.+?)\\s*[(\\[]\\s*(.*?)\\s*[)\\]]$");
    /** The same with a dash instead of a bracket: 「无拘 - 永劫无间周年主题曲」. */
    private static final Pattern SUBTITLE_DASH = Pattern.compile("^(.+?)\\s+-\\s+(.+)$");
    /** Bracketed words that name another recording of the song rather than its subtitle — the versions
     * {@link #VERSIONS} / {@link #COVER} already tell apart, plus the ones only ever written inside a bracket.
     * The Latin words are fenced by ASCII letters and digits, not by {@code \b}: 「DJ版」 has no word boundary in
     * Java's {@code \b} before a Chinese character, and that is exactly how NetEase writes them. */
    private static final Pattern SUBTITLE_VERSION = Pattern.compile(
            "(?<![a-z0-9])(?:dj|remix|mix|live|cover|covers|covered|tribute|instrumental|karaoke|demo|acoustic)(?![a-z0-9])"
            + "|伴奏|纯音乐|純音樂|现场|現場|演唱会|演唱會|翻唱|翻自|致敬|混音|不插电|不插電|小样|小樣|女声|女聲|男声|男聲"
            + "|片段|慢速|加速|减速|減速|变调|變調|降调|降調|升调|升調|剧场版|劇場版|原版|原唱版|独唱版|獨唱版|录音室版|錄音室版"
            + "|[0-9]x");
    private NeteaseCatalog() { }

    public static final class Match {
        public final long id;
        public final String title, artist, album;
        public final List<String> displayTitles;
        /** NetEase's fee type: 0 / 8 free, 1 members only (others hear a 30-second sample or nothing), 4 a paid album. */
        public final int fee;
        private Match(long id, String title, String artist, String album, List<String> displayTitles, int fee) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.displayTitles = displayTitles;
            this.fee = fee;
        }
    }

    public static Match find(String title, String artist) throws IOException {
        if (!validTerm(title) || !validTerm(artist)) throw new IOException("请输入有效的歌名和歌手");
        return selectNamed(search(searchTitle(title) + " " + artist.trim(), 30), title, artist);
    }

    public static Match findByTitle(String title) throws IOException {
        if (!validTerm(title)) throw new IOException("请输入有效的歌名");
        return selectNamed(search(searchTitle(title), 30), title, null);
    }

    private static String searchTitle(String title) {
        String value = normalized(title);
        String stripped = STUDIO_ONLY.matcher(SPOKEN_LIVE.matcher(value).replaceFirst("")).replaceFirst("").trim();
        return stripped.isEmpty() ? value : stripped;
    }

    /**
     * The bracketed subtitle of a catalog name whose head is the title that was asked for: 「甲乙丙丁 (你我怎么两清)」 is
     * 甲乙丙丁 (2026-11-10 10:48 — netease_play answered not_found for the spoken title and the full catalog title then
     * played). Null when the name is something else, and null when the bracket names another recording of the song
     * (Live, 伴奏, DJ 版 …), which the version rules still keep apart.
     */
    private static String subtitleOf(String catalogName, String wanted) {
        if (wanted == null || wanted.isEmpty() || catalogName.equals(wanted)) return null;
        Matcher matcher = SUBTITLED.matcher(catalogName);
        if (!matcher.matches()) matcher = SUBTITLE_DASH.matcher(catalogName);
        if (!matcher.matches()) return null;
        String head = matcher.group(1).trim(), tail = matcher.group(2).trim();
        if (!head.equals(wanted) || tail.isEmpty()) return null;
        if (LIVE_LABEL.matcher(catalogName).find() || COVER.matcher(tail).find()
                || SUBTITLE_VERSION.matcher(tail).find()) return null;
        for (Pattern version : VERSIONS) if (version.matcher(tail).find()) return null;
        return tail;
    }

    /** Preserve exact studio picks. For an unspecified edition allow the singer's actual credited performance. */
    static Match selectNamed(JSONObject page, String title, String artist) throws IOException {
        String requested = normalized(title);
        String base = searchTitle(title);
        boolean liveRequested = !base.equals(requested) && SPOKEN_LIVE.matcher(requested).find();
        boolean studioOnly = !base.equals(requested) && STUDIO_ONLY.matcher(requested).find();
        if (artist != null && !liveRequested) {
            Match exact = select(page, studioOnly ? base : title, artist);
            if (exact != null || studioOnly) return exact;
        }
        String wanted = artist == null ? null : normalizedArtist(artist);
        JSONArray tracks = songs(page);
        Match best = null;
        int bestRank = -1;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null || track.optLong("id", 0) < 1 || track.optInt("status", 0) < 0
                    || !track.isNull("noCopyrightRcmd")) continue;
            String name = string(track, "name"), album = string(track.optJSONObject("album"), "name");
            String catalogName = normalized(name);
            boolean exact = LIVE_LABEL.matcher(catalogName).replaceFirst("").equals(base);
            if (album.isEmpty() || !(exact || subtitleOf(catalogName, base) != null)) continue;
            String edition = normalized(album + " " + name + " " + track.optString("alias", ""));
            if (COVER.matcher(edition).find() && !COVER.matcher(requested).find()) continue;
            boolean different = false;
            for (int v = 1; v < VERSIONS.length; v++)
                if (VERSIONS[v].matcher(edition).find() && !VERSIONS[v].matcher(requested).find()) different = true;
            if (different) continue;
            boolean live = LIVE_LABEL.matcher(catalogName).find() || VERSIONS[0].matcher(normalized(album)).find();
            if ((liveRequested && !live) || (live && (studioOnly || (wanted == null && !liveRequested)))) continue;
            JSONArray credits = track.optJSONArray("artists");
            String full = fullCredit(credits);
            if (full == null) continue;
            if (wanted != null && !normalizedArtist(full).equals(wanted)
                    && !java.util.Arrays.asList(normalizedArtist(full).split("/",-1)).contains(wanted)) continue;
            // With no singer, keep the provider's first unqualified title match, even for VIP songs.
            int rank = wanted == null ? 0 : (live ? 0 : 8) + (credits.length() == 1 ? 4 : 0);
            // the name that is the title itself wins over one that only carries it with a subtitle
            if (!exact) rank -= 3;
            if (best == null || rank > bestRank) {
                bestRank = rank;
                best = new Match(track.optLong("id"), name, full, album, displayTitles(track, name), track.optInt("fee", -1));
            }
        }
        return best;
    }

    /** An artist-only request leaves the song choice to the catalog. A match still needs player verification. */
    public static Match findByArtist(String artist) throws IOException {
        if (!validTerm(artist)) throw new IOException("请输入有效的歌手名");
        JSONObject page = search(artist.trim(), ARTIST_LIMIT);
        JSONArray ids = new JSONArray();
        JSONArray tracks = songs(page);
        for (int i = 0; i < Math.min(tracks.length(), ARTIST_LIMIT); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track != null && creditMatches(track.optJSONArray("artists"), normalized(artist), true) != null
                    && track.optLong("id", 0) > 0) ids.put(track.optLong("id"));
        }
        if (ids.length() == 0) return null;
        JSONObject rights = get(new URL(RIGHTS_URL + "?br=128000&ids=" + URLEncoder.encode(ids.toString(), "UTF-8")));
        return selectArtist(page, artist, rights);
    }

    private static JSONObject search(String keywords, int limit) throws IOException {
        String search = URLEncoder.encode(keywords, "UTF-8");
        return get(new URL(SEARCH_URL + "?type=1&limit=" + limit + "&offset=0&s=" + search));
    }

    private static JSONObject get(URL url) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(4500);
        connection.setReadTimeout(5500);
        connection.setRequestProperty("Referer", "https://music.163.com/");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        ScheduledFuture<?> timeout = TIMEOUTS.schedule(connection::disconnect, 12, TimeUnit.SECONDS);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("网易云曲库查询失败");
            if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("网易云曲库响应过大");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (System.nanoTime() >= deadline) throw new IOException("网易云曲库查询超时");
                    if (bytes.size() + count > MAX_BYTES) throw new IOException("网易云曲库响应过大");
                    bytes.write(buffer, 0, count);
                }
                if (System.nanoTime() >= deadline) throw new IOException("网易云曲库查询超时");
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (JSONException e) {
            throw new IOException("网易云曲库响应格式异常", e);
        } finally {
            timeout.cancel(false);
            connection.disconnect();
        }
    }

    private static JSONArray songs(JSONObject response) throws IOException {
        JSONObject result = response.optJSONObject("result");
        if (response.optInt("code", -1) != 200 || result == null) throw new IOException("网易云曲库未返回搜索结果");
        JSONArray songs = result.optJSONArray("songs");
        if (songs == null) {
            if (result.optInt("songCount", -1) == 0) return new JSONArray();
            throw new IOException("网易云曲库搜索结果格式异常");
        }
        return songs;
    }

    static Match select(JSONObject response, String title, String artist) throws IOException {
        return select(songs(response), title, artist, null);
    }

    static Match selectArtist(JSONObject response, String artist, JSONObject rights) throws IOException {
        JSONArray rows = rights.optJSONArray("data");
        if (rows == null || rights.optInt("code", -1) != 200) throw new IOException("网易云未返回播放权限信息");
        Map<Long, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) byId.put(row.optLong("id", 0), row);
        }
        return select(songs(response), null, artist, byId);
    }

    private static Match select(JSONArray songs, String title, String artist, Map<Long, JSONObject> rights) {
        boolean artistOnly = title == null;
        String wantedTitle = artistOnly ? "" : normalized(title);
        String wantedArtist = normalized(artist).replaceAll("\\s*/\\s*", "/");
        int bestRank = -1;
        Match best = null;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            String name = string(song, "name");
            String catalogName = normalized(name);
            boolean exactTitle = artistOnly || wantedTitle.equals(catalogName);
            if (!validTerm(name) || (!artistOnly && !exactTitle && subtitleOf(catalogName, wantedTitle) == null)) continue;
            if (song.optInt("status", 0) < 0 || !song.isNull("noCopyrightRcmd")) continue;
            Object id = song.opt("id");
            if (!(id instanceof Integer || id instanceof Long) || ((Number)id).longValue() < 1) continue;
            JSONArray credits = song.optJSONArray("artists");
            String credit = creditMatches(credits, wantedArtist, artistOnly);
            if (credit == null) continue;
            JSONObject album = song.optJSONObject("album");
            String albumName = string(album, "name"), normalizedAlbum = normalized(albumName);
            int fee = song.optInt("fee", -1);
            if (artistOnly && (normalizedAlbum.isEmpty() ||
                    !artistTrackAvailable(song, fee))) continue;
            String edition = artistOnly ? normalizedAlbum + " " + normalized(name) + " "
                    + normalized(song.optString("alias", "")) : normalizedAlbum;
            if (artistOnly && COVER.matcher(edition).find()) continue;
            boolean differentVersion = false;
            for (Pattern version : VERSIONS) {
                if (version.matcher(edition).find() && !version.matcher(wantedTitle).find()) {
                    differentVersion = true;
                    break;
                }
            }
            if (differentVersion) continue;
            // An individually purchased album can be inaccessible even when a streaming edition
            // of the same recording plays. The player still has to verify the actual playback.
            int rank = fee == 4 ? 0 : fee < 0 ? 3 : 6;
            // the name that is the title itself wins over one that only carries it with a subtitle
            if (!exactTitle) rank -= 3;
            if (artistOnly) {
                if (rights != null) {
                    int access = streamAccess(song, rights.get(((Number) id).longValue()));
                    if (access < 0) continue;
                    rank += access;
                }
                if (credits.length() == 1) rank += 10;
                if (fee == 0 || fee == 8) rank += 3;
            }
            if (!normalizedAlbum.isEmpty() && !COMPILATION.matcher(normalizedAlbum).find())
                rank += album.optInt("size", 0) > 1 ? 2 : 1;
            if (best == null || rank > bestRank) {
                bestRank = rank;
                best = new Match(((Number)id).longValue(), name, credit, albumName, displayTitles(song, name), fee);
            }
        }
        return best;
    }

    private static boolean artistTrackAvailable(JSONObject song, int fee) {
        if ((fee != 0 && fee != 1 && fee != 8) || song.optInt("status", 0) < 0
                || song.optLong("duration", 0) <= 0 || !song.isNull("noCopyrightRcmd")) return false;
        JSONObject privilege = song.optJSONObject("privilege");
        return privilege == null || (privilege.optInt("st", 0) >= 0 && privilege.optInt("pl", 1) > 0);
    }

    // Public rights do not use the TV's account. A membership-only song may be playable there.
    private static int streamAccess(JSONObject song, JSONObject row) {
        if (row == null || !row.isNull("freeTrialInfo")) return -1;
        int code = row.optInt("code", 0);
        if (code == -110 && song.optInt("fee", -1) == 1) return 0;
        if (code != 200 || string(row, "url").isEmpty()
                || row.optLong("time", 0) + 2500 < song.optLong("duration", 0)) return -1;
        return 30;
    }

    private static String creditMatches(JSONArray credits, String wanted, boolean artistOnly) {
        String joined = fullCredit(credits);
        if (joined == null) return null;
        String match = artistOnly ? string(credits.optJSONObject(0), "name") : joined;
        return normalizedArtist(match).equals(normalizedArtist(wanted)) ? joined : null;
    }

    private static String normalizedArtist(String artist) {
        // The catalog spells 黄霄云 as 黄霄雲. Preserve the provider spelling in the returned full credit.
        return normalized(artist).replace('雲','云').replaceAll("\\s*/\\s*", "/");
    }

    private static String fullCredit(JSONArray credits) {
        if (credits == null || credits.length() == 0) return null;
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < credits.length(); i++) {
            String name = string(credits.optJSONObject(i), "name");
            if (name.isEmpty()) return null;
            if (i > 0) full.append('/');
            full.append(name.trim());
        }
        return full.toString();
    }

    private static List<String> displayTitles(JSONObject song, String title) {
        Set<String> names = new LinkedHashSet<>();
        names.add(title);
        for (String key : new String[] {"transNames", "alias"}) {
            JSONArray values = song.optJSONArray(key);
            if (values == null) continue;
            for (int i = 0; i < values.length(); i++) {
                Object value = values.opt(i);
                if (!(value instanceof String)) continue;
                String subtitle = ((String)value).trim();
                if (!subtitle.isEmpty() && !normalized(subtitle).equals(normalized(title)))
                    names.add(title + " (" + subtitle + ")");
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    private static String string(JSONObject obj, String key) {
        Object value = obj == null ? null : obj.opt(key);
        return value instanceof String ? (String)value : "";
    }

    private static boolean validTerm(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= 200;
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
