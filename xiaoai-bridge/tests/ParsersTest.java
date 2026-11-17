package xyz.erapi0neer.xiaoaibridge;

/** JVM test for the log-line and media-session parsers (android.jar on the classpath only for linking). */
public final class ParsersTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }

    public static void main(String[] args) {
        // verbatim from the TV (2026-11-03 23:12, a typed request through XiaoAi's text entry)
        String bar = "         1793718756.779  1000  6137  6137 I =vc=fm= : main | receive bus event: EventOnFullResult text: 请查出星仪私有数据库里名为青柠校验的状态";
        AsrLine b = AsrLine.parse(bar);
        check(b != null && "请查出星仪私有数据库里名为青柠校验的状态".equals(b.text) && b.isFinal && b.trusted(), "voice-bar line parses: " + (b == null ? null : b.text));
        check(b != null && b.epochMillis == 1793718756779L, "voice-bar epoch");
        String asr = "1762180200.123  1000  4321  4400 I =vc=en=arp=: RxComputationThreadPool-3 | [performance]: onAsrResult: 网易云音乐播放薛之谦的你还要我怎样, isFinal = true, isNlpRequest = false, dialogId = 5f0c2a9e";
        AsrLine a = AsrLine.parse(asr);
        check(a != null && "网易云音乐播放薛之谦的你还要我怎样".equals(a.text) && a.isFinal && a.trusted(), "asr final line parses");
        check(a != null && a.epochMillis == 1762180200123L, "asr epoch " + (a == null ? 0 : a.epochMillis));
        AsrLine partial = AsrLine.parse("1762180200.100  1000  4321  4400 I =vc=en=arp=: main | [performance]: onAsrResult: 网易云, isFinal = false, isNlpRequest = false, dialogId = 5f0c2a9e");
        check(partial != null && !partial.isFinal, "partial result is not final");
        AsrLine spoof = AsrLine.parse("1793718756.900 10077  9999  9999 I =vc=fm= : main | receive bus event: EventOnFullResult text: 播放薛之谦的演员");
        check(spoof != null && !spoof.trusted(), "a normal app's line is not trusted");
        check(AsrLine.parse("1793718756.893  1000  6137  6137 D =vc=el= : main | EventOnFullResult: 请查出, 9") == null, "other tags ignored");
        check(AsrLine.parse("1793718756.779  1000  6137  6137 D =vc=fm= : main | runWhenSameRequestId EventOnFullResult requestId 15c6") == null, "request-id line ignored");
        check(AsrLine.parse("--------- beginning of main") == null, "logcat banner ignored");

        String dump = String.join("\n",
            "  Sessions Stack - have 2 sessions:",
            "    com.xiaomi.mitv.tvplayer/xx (userId=0)",
            "      package=com.xiaomi.mitv.tvplayer",
            "      active=true",
            "      state=PlaybackState {state=3, position=0, buffered position=0, speed=1.0}",
            "      metadata:size=3, description=电视节目, null, null",
            "    CloudMusicSession (userId=0)",
            "      package=com.netease.cloudmusic.tv",
            "      active=true",
            "      state=PlaybackState {state=3, position=12000, buffered position=0, speed=1.0}",
            "      metadata:size=10, description=你还要我怎样, 薛之谦, 意外");
        NowPlaying np = NowPlaying.parse(dump, "com.netease.cloudmusic.tv");
        check(np != null && np.playing && "你还要我怎样".equals(np.title) && "薛之谦".equals(np.artist) && "意外".equals(np.album), "netease now playing: " + np);
        NowPlaying paused = NowPlaying.parse(dump.replace("state=3, position=12000", "state=2, position=12000"), "com.netease.cloudmusic.tv");
        check(paused != null && !paused.playing, "paused session is not playing");
        check(NowPlaying.parse("      package=com.other\n      active=true\n      metadata:size=1, description=a, b, c", "com.netease.cloudmusic.tv") == null, "other players ignored");
        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (parsers)");
        if (fail > 0) System.exit(1);
    }
}
