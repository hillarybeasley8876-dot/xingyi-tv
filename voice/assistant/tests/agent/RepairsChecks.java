package xyz.erapi0neer.xingyi.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Checks for job self-repair (see briefs/self-repair.md): what self_check tries when one of the four things it looks
 * at is broken, what each repair is called, and the two sentences the user hears — the mended/unfixable report and the
 * 正常 one that now names what was broken and fixed just now. */
final class RepairsChecks {
    private RepairsChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static final List<String> NONE = Collections.emptyList();

    static void run() throws Exception {
        plans();
        steps();
        cannots();
        reports();
        normals();
    }

    /** One step per symptom, in the order it is tried; nothing to try for anything else. */
    static void plans() throws Exception {
        check(Repairs.plan(Repairs.EYES).equals(Arrays.asList("rebind-eyes")), "读屏: rebind-eyes");
        check(Repairs.plan(Repairs.PANEL).equals(Arrays.asList("reset-panel")), "面板: reset-panel");
        check(Repairs.plan(Repairs.ADB).equals(Arrays.asList("enable-adb")), "本机调试: enable-adb");
        check(Repairs.plan(Repairs.XIAOI).equals(Arrays.asList("hide-xiaoai")), "小爱的卡片: hide-xiaoai");
        check(Repairs.plan(Repairs.EYES).size() == 1, "one step is enough for 读屏");
        check(Repairs.plan(Repairs.ADB).size() == 1, "one step is enough for 本机调试");
        check(Repairs.plan("").isEmpty(), "an empty symptom: nothing to try");
        check(Repairs.plan("model").isEmpty(), "an unknown symptom: nothing to try");
        check(Repairs.plan(null).isEmpty(), "no symptom at all: nothing to try");
        check(Repairs.plan("EYES").isEmpty(), "the keys are the lowercase ones");
        check("eyes".equals(Repairs.EYES) && "panel".equals(Repairs.PANEL), "the symptom keys: eyes, panel");
        check("adb".equals(Repairs.ADB) && "xiaoi".equals(Repairs.XIAOI), "the symptom keys: adb, xiaoi");
    }

    /** What each step is called to the user. */
    static void steps() throws Exception {
        check("读屏服务重新连上".equals(Repairs.stepSays("rebind-eyes")), "rebind-eyes: 读屏服务重新连上");
        check("面板窗口重新加上".equals(Repairs.stepSays("reset-panel")), "reset-panel: 面板窗口重新加上");
        check("ADB 调试写回打开".equals(Repairs.stepSays("enable-adb")), "enable-adb: ADB 调试写回打开");
        check("小爱的卡片又关掉".equals(Repairs.stepSays("hide-xiaoai")), "hide-xiaoai: 小爱的卡片又关掉");
        check(Repairs.stepSays("").isEmpty(), "an empty step has no name");
        check(Repairs.stepSays("restart-tv").isEmpty(), "an unknown step has no name");
        check(Repairs.stepSays(null).isEmpty(), "no step at all has no name");
        check(Repairs.stepSays(Repairs.EYES).isEmpty(), "a symptom is not a step");
    }

    /** What is said instead of 正常 when the repair did not work: the name, plus what the user can do. */
    static void cannots() throws Exception {
        check(Repairs.cannot(Repairs.EYES).startsWith("读屏服务（") && Repairs.cannot(Repairs.EYES).contains("无障碍"),
                "读屏 unfixable: the user turns it on in 无障碍");
        check("面板（重启电视后恢复）".equals(Repairs.cannot(Repairs.PANEL)), "面板 unfixable: 重启电视后恢复");
        check("ADB 调试（要在设置里的账号与安全里重新打开）".equals(Repairs.cannot(Repairs.ADB)),
                "本机调试 unfixable: the user opens it in 设置");
        check("小爱的卡片（要重启电视）".equals(Repairs.cannot(Repairs.XIAOI)), "小爱的卡片 unfixable: 要重启电视");
        check(Repairs.cannot("model").isEmpty(), "an unknown symptom has no note");
        check(Repairs.cannot(null).isEmpty(), "no symptom at all has no note");
        check(!Repairs.cannot(Repairs.EYES).equals(Repairs.stepSays("rebind-eyes")),
                "what could not be mended is not what was mended");
    }

    /** The report: what was mended, what could not be, or that there was nothing to mend. */
    static void reports() throws Exception {
        List<String> eyes = Arrays.asList("rebind-eyes"), panel = Arrays.asList("reset-panel"),
                xiaoi = Arrays.asList("xiaoi");
        check("没有要修的：一切正常。".equals(Repairs.report(NONE, NONE)), "nothing mended, nothing broken");
        check("修好了：读屏服务重新连上。".equals(Repairs.report(eyes, NONE)), "mended only");
        check("修不了的：小爱的卡片（要重启电视）。".equals(Repairs.report(NONE, xiaoi)), "unfixable only");
        check("修好了：读屏服务重新连上。修不了的：小爱的卡片（要重启电视）。".equals(Repairs.report(eyes, xiaoi)),
                "mended and unfixable, mended first");
        check("修好了：读屏服务重新连上、面板窗口重新加上。修不了的：小爱的卡片（要重启电视）。"
                .equals(Repairs.report(Arrays.asList("rebind-eyes", "reset-panel"), xiaoi)), "two steps, joined by 、");
        check("修好了：读屏服务重新连上、面板窗口重新加上。".equals(Repairs.report(Arrays.asList("rebind-eyes", "reset-panel"), NONE)),
                "the two steps the evening's own failure needed");
        check(("修不了的：" + Repairs.cannot(Repairs.EYES) + "、ADB 调试（要在设置里的账号与安全里重新打开）。")
                .equals(Repairs.report(NONE, Arrays.asList(Repairs.EYES, Repairs.ADB))), "two unfixable, each with what to do");
        check("修好了：读屏服务重新连上。".equals(Repairs.report(Arrays.asList("读屏服务重新连上"), NONE)),
                "a caller that already holds the Chinese label keeps it");
        check("修不了的：小爱的卡片（要重启电视）。".equals(Repairs.report(NONE, Arrays.asList("小爱的卡片（要重启电视）"))),
                "an unfixable label written out is not doubled");
        check("修好了：重启了后台。".equals(Repairs.report(Arrays.asList("重启了后台"), NONE)),
                "a step with no name of its own is said as it came");
        check("没有要修的：一切正常。".equals(Repairs.report(null, null)), "no lists at all: nothing to mend");
        check("修好了：读屏服务重新连上。".equals(Repairs.report(eyes, Arrays.asList(""))), "an empty unfixable entry is not said");
        check(Repairs.report(eyes, xiaoi).endsWith("。"), "the report ends with a full stop");
    }

    /** The 正常 sentence: today's shape, and what was broken and mended just now named after it. */
    static void normals() throws Exception {
        List<String> ok = Arrays.asList("模型（备用网关，0.6 秒）", "天气", "本机调试", "读屏", "面板");
        check("自检正常：模型（备用网关，0.6 秒）、天气、本机调试、读屏、面板都没问题。".equals(Repairs.normal(ok, NONE)),
                "the sentence TvTools has always spoken");
        check("自检正常：模型（备用网关，0.6 秒）、天气、本机调试、读屏、面板都没问题，其中 面板窗口重新加上 刚才坏了、已经修好。"
                .equals(Repairs.normal(ok, Arrays.asList("reset-panel"))), "a mended check says so");
        check("自检正常：模型（备用网关，0.6 秒）、天气、本机调试、读屏、面板都没问题，其中 读屏服务重新连上、面板窗口重新加上 刚才坏了、已经修好。"
                .equals(Repairs.normal(ok, Arrays.asList("rebind-eyes", "reset-panel"))), "two mended checks");
        check("自检正常：读屏、面板都没问题，其中 小爱的卡片又关掉 刚才坏了、已经修好。"
                .equals(Repairs.normal(Arrays.asList("读屏", "面板"), Arrays.asList("hide-xiaoai"))), "without the model line");
        check(Repairs.normal(ok, NONE).startsWith("自检正常："), "the shape: 自检正常：");
        check(Repairs.normal(ok, NONE).endsWith("都没问题。"), "the shape: …都没问题。");
        check(Repairs.normal(ok, Arrays.asList("enable-adb")).contains("刚才坏了、已经修好"), "the shape: the mended clause");
        check(!Repairs.normal(ok, NONE).contains("刚才坏了"), "nothing mended, nothing said about it");
        check(Repairs.normal(ok, NONE).equals(Repairs.normal(ok, null)), "a missing mended list is an empty one");
        check("自检正常：读屏都没问题。".equals(Repairs.normal(Arrays.asList("读屏"), NONE)), "one check that is fine");
        check("自检正常：都没问题。".equals(Repairs.normal(NONE, NONE)), "no check at all still reads as a sentence");
        check("自检正常：读屏都没问题，其中 读屏服务重新连上 刚才坏了、已经修好。"
                .equals(Repairs.normal(Arrays.asList("读屏"), Arrays.asList("读屏服务重新连上"))),
                "a mended label already written out is not doubled");
        check(Repairs.normal(ok, NONE).indexOf("、") > 0, "the checks are joined by 、");
    }
}
