package xyz.erapi0neer.xingyi;

/** Deciding to issue a play request must withstand buffering, ambiguity and changing tracks. */
public final class NeteaseAutomaticPreflightTest {
    private static SongOutcome.Observation sample(int state, long now, String title) {
        return new SongOutcome.Observation(state, state == 0 ? -1 : 100, now, title,
                state == 0 ? "" : "歌手", state == 0 ? "" : "专辑");
    }
    private static void check(boolean expected, SongOutcome.Observation first, SongOutcome.Observation second) {
        if (NeteaseMusic.stableAlternative(first, second) != expected)
            throw new AssertionError("Unsafe or missing preflight decision");
    }
    public static void main(String[] arguments) throws Exception {
        check(false,null,sample(0,2000,""));
        check(false,sample(6,1000,"原版"),sample(6,2000,"原版"));
        check(false,sample(3,1000,"现场版"),sample(3,2000,"原版"));
        check(false,sample(3,1000,"现场版"),sample(3,1100,"现场版"));
        check(false,sample(0,1000,""),sample(2,2000,"旧曲"));
        check(true,sample(0,1000,""),sample(0,2000,""));
        check(true,sample(2,1000,"旧曲"),sample(2,2000,"旧曲"));
        check(true,sample(3,1000,"现场版"),sample(3,2000,"现场版"));
        if (NeteaseMusic.stablePlaying(null,sample(3,3000,"原版"))
                || NeteaseMusic.stablePlaying(sample(3,1000,"原版"),sample(3,2050,"原版"))
                || NeteaseMusic.stablePlaying(sample(3,1000,"原版"),sample(2,3100,"原版"))
                || NeteaseMusic.stablePlaying(sample(3,1000,"原版"),sample(3,3100,"翻唱"))
                || !NeteaseMusic.stablePlaying(sample(3,1000,"原版"),sample(3,3100,"原版")))
            throw new AssertionError("A stale PLAYING read during Xiaomi's audio focus change cannot settle the request");
        String page="Sessions Stack - have 1 sessions:\npackage=com.netease.cloudmusic.tv\nactive=true\n"
                +"state=PlaybackState {state=3, position=0, speed=1.0, updated=100, actions=822}\n"
                +"metadata:size=6, description=空心 (Live版), 黄霄雲/刘端端, 天赐的声音第七季 第6期\n";
        String pair="@sample 200.00\n"+page+"@sample 202.18\n"+page+"@complete\n";
        SongOutcome.Observation[] p=LocalAdb.parseNetEasePair(pair);
        if (!NeteaseMusic.stablePlaying(p[0],p[1]) || !"黄霄雲/刘端端".equals(p[1].artist)) throw new AssertionError("paired target");
        try {LocalAdb.parseNetEasePair(pair.replace("@complete",""));throw new AssertionError("partial pair");}
        catch(java.io.IOException expected){}
        p=LocalAdb.parseNetEasePair(pair.replace("202.18","210.00"));
        if (NeteaseMusic.stablePlaying(p[0],p[1])) throw new AssertionError("delayed pair accepted");
        p=LocalAdb.parseNetEasePair(pair.replaceFirst("active=true","active=?"));
        if (p[0]!=null || NeteaseMusic.stableAlternative(p[0],p[1])) throw new AssertionError("corrupt session replay");
        System.out.println("PASS automatic NetEase preflight, fresh same-command pair and incomplete/stale rejection");
        if (arguments.length==1) {
            p=LocalAdb.parseNetEasePair(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(arguments[0])),"UTF-8"));
            if (p[0]==null || p[1]==null || (p[1].state==3 ? !NeteaseMusic.stablePlaying(p[0],p[1])
                    : !NeteaseMusic.stableAlternative(p[0],p[1]))) throw new AssertionError("real media snapshot not stable");
            System.out.println("PASS actual device pair: state="+p[1].state+", "+(p[1].sampledElapsedMs-p[0].sampledElapsedMs)+" ms");
        }
    }
}
