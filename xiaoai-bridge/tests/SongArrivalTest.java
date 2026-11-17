package xyz.erapi0neer.xiaoaibridge;

/** SongArrival: is what NetEase reports now the request arriving, or the song that was already playing?
 * (2026-11-08 14:21: 布拉格广场 was taken as the answer to 「我要听你要的全拿走」.) */
public final class SongArrivalTest {
    private static int pass, fail;

    private static void check(boolean ok, String what) {
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what); }
    }

    private static NowPlaying np(boolean playing, String artist, String title) {
        return new NowPlaying(playing, title, artist, "album");
    }

    /** The song NetEase had before the request on 2026-11-08, and the same song playing after it. */
    private static final String SQUARE = "布拉格广场 (单元剧《上班女郎》主题曲)";
    private static final String JOLIN = "蔡依林/周杰伦";

    public static void main(String[] args) {
        NowPlaying beforePaused = np(false, JOLIN, SQUARE);
        NowPlaying squarePlaying = np(true, JOLIN, SQUARE);

        // ---- the case seen on the TV: NetEase started cold and resumed its old song first ----
        check(!SongArrival.arrived(beforePaused, squarePlaying, "你要的全拿走", null),
                "the resumed old song is not the requested one");
        check(!SongArrival.arrived(beforePaused, squarePlaying, null, "胡彦斌 你要的全拿走"),
                "the resumed old song is not a keyword search hit either");
        check(!SongArrival.arrived(beforePaused, squarePlaying, null, null),
                "the resumed old song is nothing the user asked for");
        check(!SongArrival.arrived(squarePlaying, squarePlaying, null, ""),
                "empty keywords do not make the old song the answer");

        // ---- the request arriving ----
        NowPlaying wanted = np(true, "胡彦斌", "你要的全拿走");
        check(SongArrival.arrived(beforePaused, wanted, "你要的全拿走", "胡彦斌 你要的全拿走"),
                "the requested song arriving is a success");
        check(SongArrival.arrived(beforePaused, np(true, "胡彦斌", "你要的全拿走 (Live)"), "你要的全拿走", null),
                "a (Live) version of the requested title counts (same rule as before)");
        check(SongArrival.arrived(beforePaused, np(true, "胡彦斌", "你要的全拿走（伴奏）"), "你要的全拿走", null),
                "a （伴奏） suffix on the requested title counts too");
        check(!SongArrival.arrived(beforePaused, np(true, "祺夜", "你要的全拿走 (Live)"), "明年今日", null),
                "another song is not the requested title");

        // ---- nothing was playing before ----
        check(SongArrival.arrived(null, wanted, "你要的全拿走", null), "with nothing before, the pick arriving is a success");
        check(SongArrival.arrived(null, squarePlaying, null, "布拉格广场"), "with nothing before, any playback is the answer");
        check(SongArrival.arrived(null, squarePlaying, null, null), "with nothing before, even the keywords are not needed");

        // ---- not playing / nothing to read ----
        check(!SongArrival.arrived(beforePaused, beforePaused, null, "布拉格广场"), "a paused song has not arrived");
        check(!SongArrival.arrived(beforePaused, np(false, "胡彦斌", "你要的全拿走"), "你要的全拿走", null),
                "the right title paused is not a success");
        check(!SongArrival.arrived(beforePaused, null, null, "布拉格广场"), "no session at all is not a success");
        check(!SongArrival.arrived(null, null, "你要的全拿走", null), "no session and no wanted title is not a success");

        // ---- the user asked for exactly what was already there ----
        check(SongArrival.arrived(beforePaused, squarePlaying, null, "布拉格广场"),
                "the paused song the user asked for by name is the answer");
        check(SongArrival.arrived(beforePaused, squarePlaying, null, "我要听蔡依林的布拉格广场"),
                "keywords naming the song and its singer are the answer");
        check(SongArrival.arrived(beforePaused, squarePlaying, null, "播放蔡依林/周杰伦"),
                "keywords naming only the singer are the answer");
        check(SongArrival.arrived(beforePaused, squarePlaying, SQUARE, null),
                "asking for the very title that is playing is a success");

        // ---- an artist run ----
        check(SongArrival.arrived(beforePaused, np(true, "周杰伦", "七里香"), null, "周杰伦"),
                "another song by the requested artist is the answer");
        check(SongArrival.arrived(beforePaused, np(true, "林俊杰", "江南"), null, "周杰伦"),
                "a first song that is not the old one is the answer");

        // ---- covers and the same recording ----
        check(SongArrival.arrived(np(true, "胡彦斌", "你要的全拿走"), np(true, "祺夜", "你要的全拿走"), null, "你要的全拿走"),
                "another artist's recording of the same title is a different song (a cover arrived)");
        check(!SongArrival.arrived(np(true, "胡彦斌", "你要的全拿走"), np(true, "胡彦斌", "你要的全拿走"), null, "五月天"),
                "the very same recording is not a new arrival for another request");
        check(!SongArrival.arrived(np(true, "Jay Chou", "七里香"), np(true, "jay  chou", "七里香"), null, null),
                "the same recording written with another case and spaces is not a new arrival");

        // ---- 2026-11-09 review: a version picked while the original plays; a title searched while the singer plays ----
        NowPlaying actor = np(true, "薛之谦", "演员");
        check(!SongArrival.arrived(actor, actor, "演员 (伴奏)", null), "the original already playing is not the 伴奏 picked");
        check(SongArrival.arrived(actor, np(true, "薛之谦", "演员 (伴奏)"), "演员 (伴奏)", null), "the 伴奏 arriving is the answer");
        check(!SongArrival.arrived(actor, actor, "演员 (Live)", null), "nor is it the (Live) picked");
        check(SongArrival.arrived(actor, actor, "演员", null), "the original picked while it plays is the answer");
        check(SongArrival.arrived(beforePaused, squarePlaying, "布拉格广场", null),
                "the old song asked for by its plain title is the answer (its suffix is no version)");
        NowPlaying jiangnan = np(true, "林俊杰", "江南");
        check(!SongArrival.arrived(jiangnan, jiangnan, null, "林俊杰 修炼爱情"),
                "another song of the singer searched for is not answered by the one already playing");
        check(SongArrival.arrived(jiangnan, jiangnan, null, "林俊杰"), "the singer alone is answered by his song playing");
        check(SongArrival.arrived(jiangnan, jiangnan, null, "我想听林俊杰的歌"), "「我想听林俊杰的歌」 too");
        check(!SongArrival.arrived(jiangnan, jiangnan, null, "林俊杰修连爱情"), "nor by a misheard title of his");

        // ---- fallbackKeywords ----
        check("胡彦斌 你要的全拿走".equals(SongArrival.fallbackKeywords("胡彦斌", "你要的全拿走", "你要 全拿走")),
                "a confident pick is searched for by its own singer and title");
        check("你要的全拿走".equals(SongArrival.fallbackKeywords("", "你要的全拿走", "你要 全拿走")),
                "a pick without a singer is searched for by its title");
        check("布拉格广场".equals(SongArrival.fallbackKeywords(null, " 布拉格广场 ", null)),
                "a null singer falls back to the title alone");
        check("你要 全拿走".equals(SongArrival.fallbackKeywords(null, null, "你要 全拿走")),
                "without a pick the intent's own keywords are used");
        check("".equals(SongArrival.fallbackKeywords(null, "", null)), "nothing at all gives empty keywords");
        check("周杰伦".equals(SongArrival.fallbackKeywords(null, null, "周杰伦")), "an artist run keeps its keywords");

        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (SongArrival)");
        if (fail > 0) System.exit(1);
    }
}
