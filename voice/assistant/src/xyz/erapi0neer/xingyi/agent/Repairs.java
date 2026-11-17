package xyz.erapi0neer.xingyi.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** What self_check does about a symptom instead of only reporting it (2026-11-12 01:30: 「主要是harness目前的问题是
 * 他修不了系统的问题」 — the panel did not come up and 星仪 said 「自检正常」 three times). The four repairs here were
 * measured on this TV from inside the bridge, which holds WRITE_SECURE_SETTINGS and its own loopback ADB; anything
 * that needs root, a firmware decision or a reboot is said as such ({@link #cannot}) rather than as 正常.
 *
 * <p>The words live here, in the pure core, so they can be checked without a TV; {@code tv/SelfRepair} does the work.</p> */
public final class Repairs {
    private Repairs() { }

    /** The four things self_check looks at, and the keys its result is grouped by. */
    public static final String EYES = "eyes", PANEL = "panel", ADB = "adb", XIAOI = "xiaoi";

    private static final String REBIND_EYES = "rebind-eyes", RESET_PANEL = "reset-panel",
            ENABLE_ADB = "enable-adb", HIDE_XIAOI = "hide-xiaoai";

    /** The steps that mend this symptom, in the order they are tried; more than one when the first may not be enough.
     * An unknown symptom has nothing to try. */
    public static List<String> plan(String symptom) {
        if (EYES.equals(symptom)) return Arrays.asList(REBIND_EYES);
        if (PANEL.equals(symptom)) return Arrays.asList(RESET_PANEL);
        if (ADB.equals(symptom)) return Arrays.asList(ENABLE_ADB);
        if (XIAOI.equals(symptom)) return Arrays.asList(HIDE_XIAOI);
        return Collections.emptyList();
    }

    /** What a step is called to the user. "" for a step that is not one of these. */
    public static String stepSays(String step) {
        if (REBIND_EYES.equals(step)) return "读屏服务重新连上";
        if (RESET_PANEL.equals(step)) return "面板窗口重新加上";
        if (ENABLE_ADB.equals(step)) return "ADB 调试写回打开";
        if (HIDE_XIAOI.equals(step)) return "小爱的卡片又关掉";
        return "";
    }

    /** The symptom's name plus what the user can do about it — what is said instead of pretending it is fine. */
    public static String cannot(String symptom) {
        if (EYES.equals(symptom)) return "读屏服务（星仪重连了几次也没接上；要在设置的无障碍里重新打开）";
        if (PANEL.equals(symptom)) return "面板（重启电视后恢复）";
        if (ADB.equals(symptom)) return "ADB 调试（要在设置里的账号与安全里重新打开）";
        if (XIAOI.equals(symptom)) return "小爱的卡片（要重启电视）";
        return "";
    }

    /** 「修好了：读屏服务重新连上、面板窗口重新加上。修不了的：小爱的卡片（要重启电视）」 */
    public static String report(List<String> fixed, List<String> unfixable) {
        String mended = says(fixed, true), lost = says(unfixable, false);
        if (mended.isEmpty() && lost.isEmpty()) return "没有要修的：一切正常。";
        if (lost.isEmpty()) return "修好了：" + mended + "。";
        if (mended.isEmpty()) return "修不了的：" + lost + "。";
        return "修好了：" + mended + "。修不了的：" + lost + "。";
    }

    /** 「自检正常：模型（备用网关，0.6 秒）、天气、本机调试、读屏、面板都没问题。」 — the shape TvTools has always
     * spoken, with what was broken and mended just now named after it. */
    public static String normal(List<String> ok, List<String> mended) {
        StringBuilder s = new StringBuilder("自检正常：").append(plain(ok)).append("都没问题");
        String was = says(mended, true);
        if (!was.isEmpty()) s.append("，其中 ").append(was).append(" 刚才坏了、已经修好");
        return s.append("。").toString();
    }

    /** Entries are step ids ({@code steps}) or symptoms, and whatever the caller wrote when there is no name for it —
     * a caller that already holds the Chinese label ({@code tv/SelfRepair} puts it in its result) must not lose it. */
    private static String says(List<String> entries, boolean steps) {
        List<String> out = new ArrayList<>();
        for (String e : clean(entries)) {
            String said = steps ? stepSays(e) : cannot(e);
            out.add(said.isEmpty() ? e : said);
        }
        return String.join("、", out);
    }

    /** The entries as they are, without a name of their own. */
    private static String plain(List<String> entries) { return String.join("、", clean(entries)); }

    private static List<String> clean(List<String> entries) {
        List<String> out = new ArrayList<>();
        if (entries != null) for (String e : entries) if (e != null && !e.isEmpty()) out.add(e);
        return out;
    }
}
