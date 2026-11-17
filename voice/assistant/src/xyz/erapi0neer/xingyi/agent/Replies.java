package xyz.erapi0neer.xingyi.agent;

import java.util.regex.Pattern;

/** Short spoken replies that steer a task rather than start one. */
public final class Replies {
    private static final String WAKE = "^(?:小爱同学|小爱|星仪)?[，,、\\s]*";
    private static final String END = "(?:吧|吗|呀|啊|了|的|一下)?[。！!？?，,\\s]*$";
    private static final Pattern YES = Pattern.compile(WAKE + "(?:确认|确定|同意|可以|好的?|行|没问题|执行|继续|是的?|对的?|嗯|装吧|安装|去吧|开始)(?:执行|安装|继续)?" + END);
    private static final Pattern NO = Pattern.compile(WAKE + "(?:取消|不要|不用|算了|别|不行|不同意|拒绝|否|不了|不装|不要了|先不要?)(?:了|安装|执行)?" + END);
    /** Also the ways of saying 「enough」 (2026-11-06 overnight pass: 「别说了」 while an answer was read went to the model,
     * which took a round to say 「好的」); a whole sentence only — 「算了，放首歌吧」 is a new request. */
    private static final Pattern STOP = Pattern.compile(WAKE + "(?:停|停止|停下|停下来|别做了|别弄了|不要做了|取消任务|退出|结束任务|中止|"
            + "别说|不用说|别念|别读|别讲|闭嘴|好了好了|行了行了|算了|不用了|没事|(?:好了|行了|好啦)[，,、\\s]?(?:别说|不用说|别念|别讲))" + END);
    private static final Pattern RESUME = Pattern.compile(WAKE + "(?:继续|接着|接着做|接着来|继续做|继续执行|继续刚才的(?:任务|事)?|接着刚才的(?:任务|事)?|把刚才的(?:任务|事)做完)" + END);

    private Replies() { }

    /** "继续" and friends: pick up the interrupted task. */
    public static boolean resume(String text) { return text != null && RESUME.matcher(text.trim()).matches(); }

    /** true = approve, false = decline, null = not a yes/no answer. */
    public static Boolean consent(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (NO.matcher(t).matches()) return Boolean.FALSE;
        if (YES.matcher(t).matches()) return Boolean.TRUE;
        return null;
    }

    public static boolean stop(String text) { return text != null && STOP.matcher(text.trim()).matches(); }
}
