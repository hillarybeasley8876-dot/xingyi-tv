package xyz.erapi0neer.xingyi;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Regression fixtures for exact song selection. Pass --live to add a real provider lookup. */
public final class NeteaseCatalogTest {
    private static JSONObject song(long id, String title, String artist, String album, int size) throws Exception {
        return new JSONObject().put("id", id).put("name", title)
                .put("artists", new JSONArray().put(new JSONObject().put("name", artist)))
                .put("album", new JSONObject().put("name", album).put("size", size));
    }
    private static JSONObject page(JSONObject... songs) throws Exception {
        JSONArray rows = new JSONArray();
        for (JSONObject row : songs) rows.put(row);
        return new JSONObject().put("code", 200).put("result", new JSONObject().put("songs", rows));
    }
    private static void check(boolean condition, String expected) {
        if (!condition) throw new AssertionError(expected);
    }
    private static void checkArtistChoices() throws Exception {
        String artist = "林俊杰";
        JSONObject studio = song(30, "江南", artist, "第二天堂", 12).put("fee", 1).put("duration", 250000);
        JSONObject free = song(40, "歌", artist, "录音室专辑", 8).put("fee", 0).put("duration", 210000);
        JSONObject duet = new JSONObject(studio.toString()).put("id", 31);
        duet.getJSONArray("artists").put(new JSONObject().put("name", "嘉宾"));
        JSONObject guest = new JSONObject(studio.toString()).put("artists", new JSONArray()
                .put(new JSONObject().put("name", "嘉宾")).put(new JSONObject().put("name", artist)));
        JSONObject live = new JSONObject(studio.toString()).put("name", "江南 (Live)");
        JSONObject cover = new JSONObject(studio.toString()).put("alias", new JSONArray().put("Cover 其他歌手"));
        JSONObject purchase = new JSONObject(studio.toString()).put("fee", 4);
        JSONObject blocked = new JSONObject(studio.toString()).put("privilege", new JSONObject().put("st", -200));
        JSONObject unavailable = new JSONObject(studio.toString()).put("status", -1);
        JSONObject noAlbum = new JSONObject(studio.toString()).put("album", new JSONObject());
        check(NeteaseCatalog.select(page(guest, live, cover, purchase, blocked, unavailable, noAlbum), null, artist) == null,
                "only a streamable, non-variant solo recording with album metadata may be chosen for an artist");
        check(NeteaseCatalog.select(page(studio), null, "林俊") == null, "artist-only search requires a full credit");
        NeteaseCatalog.Match chosen = NeteaseCatalog.select(page(studio, free), null, artist);
        check(chosen.id == 40 && "歌".equals(chosen.title) && artist.equals(chosen.artist)
                && "录音室专辑".equals(chosen.album), "prefer streaming without VIP, returning provider metadata");
        JSONObject otherStudio = new JSONObject(studio.toString()).put("id", 45);
        check(NeteaseCatalog.select(page(studio, otherStudio), null, artist).id == 30, "preserve provider order for ties");
        JSONObject full = new JSONObject().put("id", 30).put("code", 200).put("url", "https://example.invalid/song")
                .put("time", 250000);
        JSONObject duetFull = new JSONObject(full.toString()).put("id", 31);
        JSONObject rights = new JSONObject().put("code", 200).put("data", new JSONArray().put(full).put(duetFull));
        check(NeteaseCatalog.selectArtist(page(duet, studio), artist, rights).id == 30,
                "prefer the solo recording when both have full streams");
        full.put("code", 404);
        NeteaseCatalog.Match lead = NeteaseCatalog.selectArtist(page(studio, duet), artist, rights);
        check(lead.id == 31 && (artist + "/嘉宾").equals(lead.artist), "verified full first-billed recording when solo unavailable");
        full.put("code", -110);
        check(NeteaseCatalog.selectArtist(page(studio, duet), artist, rights).id == 31, "full stream before membership-only");
        check(NeteaseCatalog.selectArtist(page(studio), artist, rights).id == 30, "TV membership may unlock a solo recording");
        full.put("code", 200).put("freeTrialInfo", new JSONObject());
        check(NeteaseCatalog.selectArtist(page(studio), artist, rights) == null, "no preview even if a URL exists");
        full.remove("freeTrialInfo");
        full.put("time", 30000);
        check(NeteaseCatalog.selectArtist(page(studio), artist, rights) == null, "partial-length URL is not a full stream");
    }
    /** NetEase writes a song's alternate title in brackets after its name (2026-11-10 10:48 on the TV: 「播放李佳薇的
     * 甲乙丙丁」 was a miss until the catalog's full 「甲乙丙丁 (你我怎么两清)」 was asked for). */
    private static void checkSubtitled() throws Exception {
        JSONObject jia = song(11, "甲乙丙丁 (你我怎么两清)", "李佳薇", "爱的风暴", 10).put("fee", 1);
        check(NeteaseCatalog.select(page(jia), "甲乙丙丁", "李佳薇").id == 11,
                "the catalog's subtitled name is the spoken title (find's exact pass)");
        check(NeteaseCatalog.selectNamed(page(jia), "甲乙丙丁", "李佳薇").id == 11,
                "and through find's own selection");
        JSONObject ruguo = song(12, "如果可以 (电影《月老》主题曲)", "韦礼安", "如果可以", 3).put("fee", 1);
        check(NeteaseCatalog.select(page(ruguo), "如果可以", "韦礼安").id == 12,
                "a film-theme subtitle does not hide the song");
        check(NeteaseCatalog.selectNamed(page(ruguo), "如果可以", null).id == 12,
                "a bare title with no singer still finds it (findByTitle)");
        JSONObject prague = song(13, "布拉格广场 (单元剧《上班女郎》主题曲)", "蔡依林", "看我72变", 12).put("fee", 1);
        check(NeteaseCatalog.select(page(prague), "布拉格广场", "蔡依林").id == 13, "a series-theme subtitle too");
        JSONObject wuju = song(14, "无拘 (永劫无间周年主题曲)", "周深", "无拘", 1).put("fee", 1);
        check(NeteaseCatalog.select(page(wuju), "无拘", "周深").id == 14, "a game-theme subtitle too");
        JSONObject dashed = song(15, "无拘 - 永劫无间周年主题曲", "周深", "无拘", 1).put("fee", 1);
        check(NeteaseCatalog.select(page(dashed), "无拘", "周深").id == 15, "the same subtitle after a dash");
        JSONObject fullWidth = song(16, "江南（某某版）", "林俊杰", "第二天堂", 12).put("fee", 1);
        check(NeteaseCatalog.select(page(fullWidth), "江南", "林俊杰").id == 16, "full-width brackets are brackets too");

        // a version in the bracket is another recording, never the song's subtitle
        JSONObject original = song(20, "演员", "薛之谦", "绅士", 10).put("fee", 1);
        JSONObject backing = song(21, "演员 (伴奏)", "薛之谦", "绅士", 10).put("fee", 1);
        JSONObject live = song(22, "演员 (Live)", "薛之谦", "绅士", 10).put("fee", 1);
        JSONObject dj = song(23, "演员 (DJ版)", "薛之谦", "绅士", 10).put("fee", 1);
        JSONObject female = song(24, "演员 (女声版)", "薛之谦", "绅士", 10).put("fee", 1);
        check(NeteaseCatalog.select(page(backing, live, original), "演员", "薛之谦").id == 20,
                "演员 takes the original, not 伴奏 or Live");
        check(NeteaseCatalog.select(page(backing), "演员", "薛之谦") == null, "伴奏 alone is not 演员");
        check(NeteaseCatalog.select(page(live), "演员", "薛之谦") == null, "nor is a Live in the bracket");
        check(NeteaseCatalog.select(page(dj), "演员", "薛之谦") == null, "nor a DJ 版");
        check(NeteaseCatalog.select(page(female), "演员", "薛之谦") == null, "nor a 女声版");
        check(NeteaseCatalog.selectNamed(page(backing, dj, female), "演员", "薛之谦") == null,
                "none of them through find's own selection either");
        // and an exact name wins over one that only carries the title with a subtitle
        JSONObject plain = song(30, "江南", "林俊杰", "第二天堂", 12).put("fee", 1);
        JSONObject subtitled = song(31, "江南 (某某版)", "林俊杰", "第二天堂", 12).put("fee", 1);
        check(NeteaseCatalog.select(page(subtitled, plain), "江南", "林俊杰").id == 30,
                "an exact 江南 wins over 江南 (某某版) even when the catalog lists that one first");
        check(NeteaseCatalog.selectNamed(page(subtitled, plain), "江南", "林俊杰").id == 30,
                "and through find's own selection");
        check(NeteaseCatalog.select(page(subtitled), "江南", "林俊杰").id == 31,
                "the subtitled one is still 江南 when it is the only one there");
        // a name that is not the title plus a subtitle stays a different song
        JSONObject tagged = song(40, "演员(薛之谦)完整版", "小酒", "演员", 1).put("fee", 1);
        JSONObject plural = song(41, "演员们", "薛之谦", "绅士", 10).put("fee", 1);
        check(NeteaseCatalog.select(page(tagged), "演员", "薛之谦") == null,
                "a bracket in the middle of an upload's name is not a subtitle");
        check(NeteaseCatalog.select(page(plural), "演员", "薛之谦") == null, "演员们 is not 演员");
        check(NeteaseCatalog.select(page(original), "演员 (伴奏)", "薛之谦") == null,
                "asking for the 伴奏 edition does not take the original");
        check(NeteaseCatalog.select(page(original), "演员 (某某版)", "薛之谦") == null,
                "asking for an unknown edition does not take the plain name either");
    }

    public static void main(String[] args) throws Exception {
        JSONObject showTrack = song(3407803435L, "空心 (Live版)", "黄霄雲", "天赐的声音第七季 第6期", 12);
        showTrack.getJSONArray("artists").put(new JSONObject().put("name","刘端端"));
        JSONObject backing = new JSONObject(showTrack.toString()).put("id",3407805464L).put("name","空心 (伴奏)");
        JSONObject taggedUpload = song(3L,"空心(刘端端&黄霄雲)完整版","小酒","空心",1);
        JSONObject original = song(435288399L,"空心","光泽","光泽",10).put("fee",1);
        JSONObject options = page(backing,taggedUpload,showTrack,original,song(42,"空心","其他歌手","合辑",1));
        for (String artist : new String[]{"黄霄云","黄霄雲","刘端端"}) {
            NeteaseCatalog.Match named = NeteaseCatalog.selectNamed(options,"空心",artist);
            check(named.id == 3407803435L && "黄霄雲/刘端端".equals(named.artist)
                    && named.displayTitles.contains("空心 (Live版)"), "official full credit and live metadata");
        }
        check(NeteaseCatalog.selectNamed(options,"空心",null).id == 435288399L,"bare title keeps provider ranked exact edition");
        check(NeteaseCatalog.selectNamed(options,"空心现场版","黄霄云").id == 3407803435L,"explicit spoken live edition");
        check(NeteaseCatalog.selectNamed(options,"空心原版","刘端端")==null,"never substitute duet live for requested original");
        check(NeteaseCatalog.selectNamed(options,"空心","端端")==null,"never approximate an artist name");
        check(NeteaseCatalog.selectNamed(page(showTrack,song(43,"空心","黄霄雲","录音室专辑",1)),"空心","黄霄云").id == 43,
                "matching solo studio has priority over live collaboration");
        check(NeteaseCatalog.selectNamed(page(backing,taggedUpload),"空心","刘端端")==null,"no accompaniment or name embedded in an upload");
        JSONObject compilation = song(1, "Song", "Artist", "Best of Artist", 30);
        JSONObject album = song(5000000000L, "Song", "Artist", "Original Album", 12);
        JSONObject show = song(2, "Song", "Artist", "音乐节目 第十二期", 9);
        JSONObject single = song(3, "Song", "Artist", "Song", 1);
        JSONObject otherArtist = song(4, "Song", "Artist 2", "Covers", 8);
        JSONObject remix = song(5, "Song", "Artist", "Extended Remixes", 9);
        JSONObject live = song(6, "Song (Live)", "Artist", "Live at Home", 9);
        JSONObject duet = song(7, "Song", "Guest", "Guest Album", 9);
        duet.getJSONArray("artists").put(new JSONObject().put("name", "Artist"));
        JSONObject fixtures = page(compilation, show, single, otherArtist, remix, live, duet, album);
        NeteaseCatalog.Match normal = NeteaseCatalog.select(fixtures, "Ｓｏｎｇ", "artist");
        check(normal != null && normal.id == 5000000000L, "original album and 64-bit song id");
        check("Song".equals(normal.title) && "Artist".equals(normal.artist)
                && "Original Album".equals(normal.album), "provider metadata");
        check(NeteaseCatalog.select(page(show, live, remix, otherArtist), "Song", "Artist") == null,
                "unrequested live/remix/cover not selected");
        check(NeteaseCatalog.select(fixtures, "Song （Live）", "Artist").id == 6, "explicit live version");
        check(NeteaseCatalog.select(page(duet), "Song", "Artist") == null, "do not replace a solo recording with a duet");
        check("Guest/Artist".equals(NeteaseCatalog.select(page(duet), "Song", "Guest/Artist").artist),
                "preserve an explicitly requested duet");
        check(NeteaseCatalog.select(fixtures, "Song", "Art") == null, "partial artist name rejected");
        check(NeteaseCatalog.select(page(compilation, single), "Song", "Artist").id == 3, "single before anthology");
        album.put("fee", 4).put("alias", new JSONArray().put("Another edition"));
        compilation.put("fee", 1).put("transNames", new JSONArray().put("Official translation"));
        NeteaseCatalog.Match playable = NeteaseCatalog.select(page(album, compilation), "Song", "Artist");
        check(playable.id == 1, "regular streaming edition before a separately sold album");
        check(playable.displayTitles.equals(java.util.Arrays.asList("Song", "Song (Official translation)")),
                "only the selected song's published alternate labels are accepted");
        JSONObject empty = new JSONObject().put("code", 200).put("result", new JSONObject().put("songCount", 0));
        check(NeteaseCatalog.select(empty, "Song", "Artist") == null, "known empty response");
        try {
            NeteaseCatalog.select(new JSONObject().put("code", 500), "Song", "Artist");
            throw new AssertionError("provider error reported as a catalog miss");
        } catch (IOException expected) { }
        checkArtistChoices();
        checkSubtitled();
        System.out.println("PASS exact-title and artist-only catalog checks");
        if (args.length > 0 && "--live".equals(args[0])) {
            for (String artist : new String[]{"刘端端","黄霄云",null}) {
                NeteaseCatalog.Match m = artist==null ? NeteaseCatalog.findByTitle("空心") : NeteaseCatalog.find("空心",artist);
                check(m != null && (artist == null ? "空心".equals(m.title) : "黄霄雲/刘端端".equals(m.artist)), "live 空心 catalog");
                System.out.println(m.id + " | " + m.title + " | " + m.artist + " | " + m.album);
            }
            NeteaseCatalog.Match match = NeteaseCatalog.find("不为谁而作的歌", "林俊杰");
            check(match != null && "不为谁而作的歌".equals(match.title) && "林俊杰".equals(match.artist),
                    "live exact title and artist");
            System.out.println(match.id + " | " + match.title + " | " + match.artist + " | " + match.album);
            for (String artist : new String[] {"林俊杰", "周杰伦"}) {
                NeteaseCatalog.Match artistMatch = NeteaseCatalog.findByArtist(artist);
                check(artistMatch != null && (artistMatch.artist.equals(artist) || artistMatch.artist.startsWith(artist + "/"))
                        && !artistMatch.album.isEmpty(), "live artist-only lookup");
                System.out.println(artistMatch.id + " | " + artistMatch.title + " | " + artistMatch.artist + " | " + artistMatch.album);
            }
        }
    }
}
