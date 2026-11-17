package xyz.erapi0neer.xiaoaibridge;

import java.util.ArrayList;
import java.util.List;

/** JVM test: XiaoAi's player start / completion lines (logcat 2026-11-06) reach the listener, for page-by-page reading. */
public final class PlayerEventsTest {
    public static void main(String[] args) {
        final List<String> events = new ArrayList<>();
        XiaoaiLog log = new XiaoaiLog(new XiaoaiLog.Listener() {
            public void onRequest(XiaoaiLog.Dialog d) { }
            public void onInstruction(XiaoaiLog.Dialog d, String name, String text) { }
            public void onTtsStart(XiaoaiLog.Dialog d, long millis) { }
            @Override public void onPlayer(boolean start, boolean manual) { events.add(start ? "start" : manual ? "stopped" : "done"); }
        });
        log.entry(1793901443678L, "=vc=mm=mod=", "Thread-1 | onPlayStart");
        log.entry(1793901443746L, "=vc=mm=mod=", "Thread-1 | onPlayStop isByManual true");
        log.entry(1793901443780L, "=vc=mm=mod=", "Thread-1 | onPlayComplete byManual true");
        log.entry(1793901450000L, "=vc=mm=mod=", "Thread-1 | onPlayStart");
        log.entry(1793901455000L, "=vc=mm=mod=", "Thread-1 | onPlayComplete byManual false");
        log.entry(1793901455100L, "=vc=inst=speak=", "Thread-1 | onPlayStart");   // another module's line: not the player
        String got = String.join(",", events);
        boolean ok = got.equals("start,stopped,start,done");
        System.out.println((ok ? "PlayerEventsTest: 1 passed, 0 failed" : "PlayerEventsTest: FAIL got " + got));
        if (!ok) System.exit(1);
    }
}
