package xyz.erapi0neer.xingyi;

import java.util.List;

/** A spoken/typed music request, parsed without any network access. */
public final class MusicIntent {
    public enum Kind { SONG, ARTIST, FAVORITES, PRIVATE_FM, DAILY, OPEN_APP, PAUSE, RESUME, NEXT, PREVIOUS }

    public final Kind kind;
    public final boolean netease;
    public final boolean explicitPlay;
    public final String content;
    public final String artist;
    public final List<String[]> splits;
    public final boolean wantsVersion;

    private MusicIntent(Kind kind, boolean netease, boolean explicitPlay, String content, String artist,
                        List<String[]> splits, boolean wantsVersion) {
        this.kind = kind; this.netease = netease; this.explicitPlay = explicitPlay; this.content = content;
        this.artist = artist; this.splits = splits; this.wantsVersion = wantsVersion;
    }

    public static MusicIntent parse(String utterance) {
        throw new UnsupportedOperationException("stub");
    }
}
