package xyz.erapi0neer.xingyi;

import java.io.IOException;
import java.util.List;
import org.json.JSONObject;

/** Picks the NetEase recording a music request means, from NetEase web search pages. */
public final class MusicResolver {
    public interface Search { JSONObject songs(String keywords) throws IOException; }

    public static final class Pick {
        public final long id;
        public final String title, artist, album;
        public final int score;
        public final boolean confident;
        Pick(long id, String title, String artist, String album, int score, boolean confident) {
            this.id = id; this.title = title; this.artist = artist; this.album = album; this.score = score; this.confident = confident;
        }
    }

    public static Pick resolve(String content, List<String[]> splits, boolean wantsVersion, Search search) throws IOException {
        throw new UnsupportedOperationException("stub");
    }
    public static List<String> queries(String content, List<String[]> splits) { throw new UnsupportedOperationException("stub"); }
    public static String norm(String s) { throw new UnsupportedOperationException("stub"); }
    public static String baseTitle(String s) { throw new UnsupportedOperationException("stub"); }
    public static boolean titleTagged(String name) { throw new UnsupportedOperationException("stub"); }
    public static boolean albumTagged(String album) { throw new UnsupportedOperationException("stub"); }
    public static double similarity(String a, String b) { throw new UnsupportedOperationException("stub"); }
    private MusicResolver() { }
}
