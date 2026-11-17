package xyz.erapi0neer.xiaoaibridge;

import xyz.erapi0neer.xingyi.MusicResolver;

/**
 * Whether what NetEase reports now is the request arriving.
 *
 * A cold NetEase start resumes the song it had before the request and only switches later (2026-11-08 14:21: the
 * bridge saw 蔡依林/周杰伦《布拉格广场》 0.1 s after sending the play intent and called that a success). What was
 * playing when the request was taken is therefore never the answer — unless the user asked for exactly that.
 */
final class SongArrival {
    private SongArrival() { }

    /** The two titles are the same song: 「布拉格广场 (单元剧《上班女郎》主题曲)」 is 布拉格广场. */
    static boolean sameTitle(String a, String b) {
        return MusicResolver.norm(MusicResolver.baseTitle(a)).equals(MusicResolver.norm(MusicResolver.baseTitle(b)));
    }

    /**
     * before: what NetEase was playing when the request was taken (null: nothing was there).
     * wantedTitle: the song the request named, or null for a keyword search / an artist / 私人 FM / 我喜欢的音乐.
     * keywords: what the user asked for, used when the song playing is the one that was already there.
     */
    static boolean arrived(NowPlaying before, NowPlaying now, String wantedTitle, String keywords) {
        if (now == null || !now.playing) return false;
        if (wantedTitle != null && !wantedTitle.isEmpty()) {
            if (!sameTitle(now.title, wantedTitle)) return false;
            // the song that was already there is the pick only as the same recording: 「演员 (伴奏)」 picked while 《演员》
            // played has not arrived yet (2026-11-09 review: the base titles matched and the original was taken)
            return before == null || !sameSong(before, now)
                    || MusicResolver.titleTagged(now.title) == MusicResolver.titleTagged(wantedTitle);
        }
        // no song named: anything new counts, the song that was already there does not
        if (before == null || !sameSong(before, now)) return true;
        return named(keywords, now);
    }

    /** What to search NetEase for when the picked song would not play: the pick itself, not the sentence's split. */
    static String fallbackKeywords(String pickArtist, String pickTitle, String intentKeywords) {
        String title = pickTitle == null ? "" : pickTitle.trim();
        if (title.isEmpty()) return intentKeywords == null ? "" : intentKeywords;
        String artist = pickArtist == null ? "" : pickArtist.trim();
        return artist.isEmpty() ? title : artist + " " + title;
    }

    /** Same recording: the same title by the same singer (case, spaces and punctuation aside). */
    private static boolean sameSong(NowPlaying a, NowPlaying b) {
        return sameTitle(a.title, b.title) && MusicResolver.norm(a.artist).equals(MusicResolver.norm(b.artist));
    }

    /** What is left of the keywords once the singer is taken out, when it says no more than 「his songs」
     * (「播放」「的歌」「我要听…的新歌」). */
    private static final java.util.regex.Pattern ONLY_THE_SINGER = java.util.regex.Pattern.compile(
            "^(?:小爱同学|我要听|我想听|想听|我要|我想|帮我|给我|播放|播|放|听|来|一首|首|点|些)*(?:唱?的)?"
                    + "(?:歌|歌曲|音乐|热门歌曲|热门歌|热歌|新歌|好歌|老歌|经典歌曲|经典老歌|代表作|成名曲|所有歌|全部歌曲|专辑)?(?:吧|啊|呀)?$");

    /** The keywords name the song that is playing, or only its singer: the user asked for what was already there.
     * Keywords with a title of their own are not answered by another song of that singer (2026-11-09 review: 林俊杰
     * 《江南》 playing, 「林俊杰 修炼爱情」 searched for, and 《江南》 taken as the answer). */
    private static boolean named(String keywords, NowPlaying now) {
        String said = MusicResolver.norm(keywords);
        if (said.isEmpty()) return false;
        String full = MusicResolver.norm(now.title), base = MusicResolver.norm(MusicResolver.baseTitle(now.title));
        String artist = MusicResolver.norm(now.artist);
        if (!base.isEmpty() && said.contains(base)) return true;
        if (!full.isEmpty() && said.contains(full)) return true;
        return !artist.isEmpty() && said.contains(artist) && ONLY_THE_SINGER.matcher(said.replace(artist, "")).matches();
    }
}
