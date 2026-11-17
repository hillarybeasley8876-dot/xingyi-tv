package xyz.erapi0neer.xingyi;

import java.util.Arrays;
import java.util.List;

/** The captured Android 9 field layout with synthetic timelines. No device mutation. */
public final class SongOutcomeTest {
    private static final String TITLE = "不为谁而作的歌", ARTIST = "林俊杰", ALBUM = "上一首情歌 (国语精选)";
    private static final String STUDIO = TITLE + " (Twilight)";
    private static final String LIVE = TITLE + " (Live) (原唱 : 林俊杰)";
    private static final String GUESTS = "徐佳莹/林俊杰";
    private static final List<String> LABELS = Arrays.asList(TITLE, STUDIO);
    private static final long QUERY = 70000;
    private static int passed;

    private static String session(String title, String artist, String album, int state, long updated) {
        return "  package=com.netease.cloudmusic.tv\n  active=true\n  state=PlaybackState {state=" + state +
                ", position=472, buffered position=0, speed=1.0, updated=" + updated +
                ", actions=822, custom actions=[], active item id=-1, error=null}\n  metadata:size=6, description=" +
                title + ", " + artist + ", " + album + "\n";
    }

    private static SongOutcome.Verdict judge(String a, String b, long first, long second) {
        String header = "Sessions Stack - have 1 sessions:\n";
        return SongOutcome.judge(SongOutcome.parse(header+a, first), SongOutcome.parse(header+b, second),
                QUERY, TITLE, LABELS, ARTIST, ALBUM);
    }
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        passed++;
    }

    public static void main(String[] args) {
        String wrong = session(LIVE, GUESTS, "歌王之战", 3, 71000);
        String exact = session(STUDIO, ARTIST, ALBUM, 3, 10000);
        long first=72000, second=73500;
        SongOutcome.Verdict bad=SongOutcome.Verdict.WRONG_VERSION, ok=SongOutcome.Verdict.CORRECT, no=SongOutcome.Verdict.UNKNOWN;
        check(judge(wrong, wrong, first, second)==bad, "fresh corroborated Live duet");
        check(judge(wrong, exact, first, second)==ok, "the requested track supersedes a cover");
        check(judge("", exact, first, second)==ok, "already playing, even before voice");
        check(judge(wrong, wrong, first, first+50)==no, "two rapid reads are not two stable observations");
        check(judge(wrong, wrong, first, second+20000)==no, "observations too far apart");
        String stale=session(LIVE,GUESTS,"歌王之战",3,QUERY-5);
        check(judge(stale,stale,first,second)==no,"pre-existing cover cannot be attributed to this utterance");
        String newCover=session(LIVE,"另一歌手","第二张唱片",3,first+100);
        check(judge(wrong,newCover,first,second)==no,"different recordings in the two reads");
        String paused=wrong.replace("state=3,","state=2,");
        check(judge(paused,paused,first,second)==no,"paused metadata is not a wrong playback");
        check(judge(wrong,wrong.replace("speed=1.0","speed=0.0"),first,second)==no,"stalled clock");
        check(judge(wrong.replace("updated=71000,",""),wrong,first,second)==no,"untimed state change");
        String unknownEdition=session(TITLE,ARTIST,"另一张录音室专辑",3,71000);
        check(judge(unknownEdition,unknownEdition,first,second)==no,"another album can carry the same master");
        String unrelated=session("愿与愁",ARTIST,ALBUM,3,71000);
        check(judge(unrelated,unrelated,first,second)==no,"unrelated music may be a queue advance");
        String prefix=session(TITLE+"续篇",GUESTS,"歌王之战",3,71000);
        check(judge(prefix,prefix,first,second)==no,"substring alone is not the requested song");
        String future=wrong.replace("updated=71000","updated=74000");
        check(judge(future,future,first,second)==no,"future update/clock mismatch");
        check(judge(wrong,wrong,first+36000,second+36000)==no,"expired dialogue window");
        String late=wrong.replace("updated=71000","updated=83000");
        check(judge(late,late,83010,84510)==no,"a late queue change after the original command");
        String header="Sessions Stack - have 1 sessions:\n";
        SongOutcome.Observation alias=SongOutcome.parse(header+session(STUDIO,ARTIST,ALBUM,3,71000),first);
        check(SongOutcome.possiblyIntended(alias,TITLE,ARTIST,QUERY),"a fresh plausible exact request stops correction");
        String duet = session("空心 (Live版)","黄霄雲/刘端端","天赐的声音第七季 第6期",3,71000);
        SongOutcome.Observation joint=SongOutcome.parse(header+duet,first), repeat=SongOutcome.parse(header+duet,second);
        for (String wanted : new String[]{"刘端端","黄霄云",null}) {
            check(SongOutcome.possiblyIntended(joint,"空心",wanted,QUERY),"do not correct actual credited duet");
            check(SongOutcome.judge(joint,repeat,QUERY,"空心",Arrays.asList("空心"),wanted,null)==no,
                    "unresolved plausible duet is not proof of a wrong rendition");
        }
        check(!SongOutcome.possiblyIntended(joint,"空心","李端",QUERY),"no partial/fuzzy named singer");
        check(!SongOutcome.possiblyIntended(joint,"空心原版","刘端端",QUERY),"original-qualified title cannot accept Live");
        check(!SongOutcome.possiblyIntended(SongOutcome.parse(header+duet.replace("Live版","伴奏"),first),"空心","刘端端",QUERY),
                "backing track does not satisfy a singing request");
        check(!SongOutcome.possiblyIntended(SongOutcome.parse(header+exact,first),TITLE,ARTIST,QUERY),"a stale song cannot settle the new turn");
        String liveAlbum=session(STUDIO,ARTIST,"演唱会",3,71000);
        check(!SongOutcome.possiblyIntended(SongOutcome.parse(header+liveAlbum,first),TITLE,ARTIST,QUERY),"an alias does not excuse a live album");
        check(SongOutcome.judge(SongOutcome.parse(header+liveAlbum,first),SongOutcome.parse(header+liveAlbum,second),
                QUERY,TITLE,Arrays.asList(TITLE),ARTIST,null)==bad,"same solo singer and alias on explicit live album");
        check(SongOutcome.parse("Sessions Stack - have 2 sessions:\n"+wrong+exact,second)==null,"two active NetEase sessions ambiguous");
        check(SongOutcome.parse("Sessions Stack - have 0 sessions:\n",second).state==0,"complete empty stack");
        check(SongOutcome.parse("Sessions Stack - have 1 sessions:\npackage=other",second).state==0,"another package only");
        check(SongOutcome.parse("Sessions Stack - have 1 sessions:\n",second)==null,"truncated stack is not empty");
        check(SongOutcome.parse("command not found",second)==null,"failed shell read is not empty");
        check(SongOutcome.parse("Sessions Stack - have 1 sessions:\n"+wrong.replace("active=true","active=false"),second).state==0,"inactive old record");
        check(SongOutcome.parse("Sessions Stack - have 1 sessions:\npackage=com.netease.cloudmusic.tv",second)==null,"missing active flag");
        check(SongOutcome.parse("Sessions Stack - have 2 sessions:\n"+wrong,second)==null,"incomplete data cannot authorize a correction");
        System.out.println("PASS " + passed + " song outcome checks");
    }
}
