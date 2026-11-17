package xyz.erapi0neer.xingyi.agent;

import java.util.regex.Pattern;

/** How often one task may search the web. Calibration against the real model (2026-11-04 23:20) showed it will call
 * web_search up to 8 times for a single question when the results look inconsistent, at ~7 s each: the harness caps it
 * at one search per model round and two per task, and says why in the refusal. A search that found its conclusion
 * closes the budget early (2026-11-05: 「今晚有什么NBA比赛」 searched a second time only to get start times). */
public final class SearchBudget {
    static final String SAME_ROUND = "这一轮已经联网查过了，先看上一次的结果再决定。";
    static final String TASK_DONE = "这个任务已经联网查了 2 次，用已经查到的结果回答；查不到就如实告诉用户，不要再查。";
    static final String FOUND = "上一次联网已经查到了结论，直接用它回答（它没说到的细节就如实说没查到），不要为了更具体再查："
            + "每查一次用户要多等 5 到 10 秒。";
    private final int perTask;
    /** How the search model says it came back empty-handed — or with less than was asked: 2026-11-06 「我这边没拿到更晚
     *且更可靠的原帖」 counted as a conclusion and the budget refused the second search. */
    private static final Pattern EMPTY = Pattern.compile("没有找到|没找到|没查到|查不到|未找到|找不到|无法确认|没有相关|暂无|"
            + "没拿到|拿不到|没能找到|没能查到|没能拿到|未能找到|未能查到|未能获取|无法获取|无法访问|看不到原|不确定是不是最新|"
            + "可能还有更新");

    public SearchBudget() { this(2); }

    /** @param perTask how many searches one task may run */
    public SearchBudget(int perTask) { this.perTask = perTask; }

    private int lastRound = -1;
    private int used;
    private boolean found;

    /** null and the search is recorded; otherwise the sentence the model gets instead of a search. */
    public String check(int round) {
        if (used >= perTask) return perTask == 2 ? TASK_DONE : "这个任务已经查过了，用查到的结果回答；查不到就如实告诉用户，不要再查。";
        if (found) return FOUND;
        if (round == lastRound) return SAME_ROUND;
        lastRound = round;
        used++;
        return null;
    }

    /** What the search that just ran came back with: a conclusion closes the budget, nothing found leaves one more try. */
    public void result(String answer, boolean searched) { found = answered(answer, searched); }

    static boolean answered(String answer, boolean searched) {
        return searched && answer != null && !answer.trim().isEmpty() && !EMPTY.matcher(answer).find();
    }

    /** How many searches this task has actually run. */
    public int used() { return used; }
}
