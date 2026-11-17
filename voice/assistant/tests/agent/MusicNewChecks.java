package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** music_new (2026-11-07): a singer's newest releases on NetEase, as saved that night. */
final class MusicNewChecks {
    private MusicNewChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    /** 2026-11-07 02:00 in Beijing. */
    private static final long NOW = 1793988000000L;

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "music_new checks threw " + e);
        }
    }

    static void checks() throws Exception {
        Object[] jj = MusicNew.artist(fixture("netease-artist-jj.json"), "林俊杰");
        check(jj != null && (Long) jj[0] == 3684L && "林俊杰".equals(jj[1]), "the singer by name");
        check((Long) MusicNew.artist(fixture("netease-artist-jj.json"), "JJ Lin")[0] == 3684L, "or by an alias");
        check((Long) MusicNew.artist(fixture("netease-artist-jay.json"), "周董")[0] == 6452L, "周董 is 周杰伦");
        check(MusicNew.artist("{\"code\":200,\"result\":{}}", "某某") == null, "no singer");

        List<MusicNew.Release> rs = MusicNew.releases(fixture("netease-albums-jj.json"));
        String s = MusicNew.speak("林俊杰", rs, 3, NOW);
        check(s.equals("林俊杰最近发了新歌：10 月 10 日上线的单曲《Rise Without A Sound》；之前是 2 月 27 日的单曲《我对缘分小心翼翼》、2025 年 10 月 3 日的单曲《为你揭晓》"),
                "newest first, within a month: " + s);
        s = MusicNew.speak("周杰伦", MusicNew.releases(fixture("netease-albums-jay.json")), 2, NOW);
        check(s.equals("周杰伦最近一次发歌是 3 月 26 日的专辑《太阳之子》（13 首），差不多 7 个月前；之前是 2025 年 6 月 6 日的单曲《即兴曲》"), "older: an album with its songs: " + s);
        check(MusicNew.speak("某某", new java.util.ArrayList<MusicNew.Release>(), 3, NOW).equals("网易云上没查到某某发过的歌"), "none");
        check(MusicNew.rows(rs, NOW).getJSONObject(0).getString("title").equals("Rise Without A Sound"), "rows for netease_play");
    }
}
