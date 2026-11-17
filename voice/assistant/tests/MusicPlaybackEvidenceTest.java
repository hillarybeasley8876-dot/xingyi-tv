package xyz.erapi0neer.xingyi;

import java.util.Arrays;
import java.util.List;

public final class MusicPlaybackEvidenceTest {
    private static final String PLAYER = "com.netease.cloudmusic.tv";
    private static final String TITLE = "不为谁而作的歌";
    private static final String ARTIST = "林俊杰";
    private static final String ALBUM = "上一首情歌 (国语精选)";
    private static final List<String> TITLES = Arrays.asList(TITLE, TITLE + " (Twilight)");
    private static int checked;

    private static String session(String player, boolean active, int state, String description) {
        return "package=" + player + "\nactive=" + active
                + "\nstate=PlaybackState {state=" + state + ", position=1402, speed=1.0}"
                + "\nmetadata:size=5, description=" + description + "\n";
    }

    private static void expect(boolean expected, String dump) {
        expect(expected, dump, ALBUM);
    }

    private static void expect(boolean expected, String dump, String album) {
        checked++;
        if (expected != MusicPlaybackEvidence.matches(dump, TITLES, ARTIST, album))
            throw new AssertionError("case " + checked);
    }

    public static void main(String[] args) {
        String metadata = TITLE + ", " + ARTIST + ", " + ALBUM;
        expect(true, session(PLAYER, true, 3, metadata));
        expect(true, session(PLAYER, true, 3, TITLE + " (Twilight), " + ARTIST + ", " + ALBUM));
        expect(false, session(PLAYER, true, 3, TITLE + ", " + ARTIST + ", 和自己对话"));
        expect(false, session(PLAYER, true, 3, TITLE + " (Twilight), " + ARTIST + ", null"));
        expect(false, session(PLAYER, true, 3, TITLE + ", " + ARTIST + "/另一位歌手, " + ALBUM));
        expect(true, session(PLAYER, true, 3, TITLE + ", " + ARTIST + ", null"), "");
        expect(false, session(PLAYER, false, 3, metadata));
        expect(false, session(PLAYER, true, 2, metadata));
        expect(false, session(PLAYER, true, 3, TITLE + " (Live), " + ARTIST + ", " + ALBUM));
        expect(false, session(PLAYER, true, 3, TITLE + ", 翻唱者, " + ARTIST));
        expect(false, session(PLAYER, true, 3, TITLE + ", " + ARTIST + ", 另一歌手, 合辑"));
        expect(false, session(PLAYER, true, 3, "null")
                + session("org.other.player", true, 3, metadata));
        expect(false, session("org.other.player", true, 3, metadata)
                + session(PLAYER, true, 0, metadata));
        expect(false, session(PLAYER, true, 3, "上首歌, " + ARTIST + ", null")
                + session(PLAYER, false, 3, metadata));
        expect(false, "");
        System.out.println("PASS " + checked + " playback evidence cases");
    }
}
