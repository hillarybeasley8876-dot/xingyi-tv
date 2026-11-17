package xyz.erapi0neer.xiaoaibridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The last few question/answer turns, so a follow-up like "那明天呢？" keeps its context for a few minutes. */
final class Conversation {
    static final long WINDOW_MS = 180000;
    static final int MAX_TURNS = 3;
    private final ArrayDeque<Object[]> turns = new ArrayDeque<>();

    synchronized void add(String question, String answer, long epochMillis) {
        turns.addLast(new Object[]{question, answer, epochMillis});
        while (turns.size() > MAX_TURNS) turns.removeFirst();
    }

    /** {question, answer} pairs newer than the window, oldest first; a copy. */
    synchronized List<String[]> recent(long nowMillis) {
        List<String[]> out = new ArrayList<>();
        for (Object[] t : turns) {
            if (nowMillis - (Long) t[2] < WINDOW_MS) out.add(new String[]{(String) t[0], (String) t[1]});
        }
        return out;
    }

    synchronized void clear() { turns.clear(); }
}
