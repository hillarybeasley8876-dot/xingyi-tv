package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** 「别弹提示了」「打开提示」: the quiet card 星仪 shows when the screen comes on (Hints) can be turned off and on by
 * voice; the flag lives in profile.json ("hints", on unless set to false), where the 星仪 app's 记忆 page switches it
 * too. The tool changes only 星仪's own setting, so it runs without a plan, and its message is the whole answer. */
public final class HintTools {
    private HintTools() { }

    public static List<Tool> create(MemoryTools.Store profile) {
        Tool t = new Tool() {
            @Override public String name() { return "set_hints"; }
            @Override public Gate gate(JSONObject a, Turn turn) {
                // 只有用户这句话真的在要求开关亮屏提示卡时才改这个设置（2026-11-11：用户说的是星仪右上角的执行面板，
                // set_hints 却把亮屏提示关了又开）。没有 turn 就没有句子可判（只有测试这么调）：照旧放行。
                if (turn == null) return Gate.read();
                int asked = Hints.switchAsked(turn.utterance());
                if (asked == (a.optBoolean("on", true) ? 1 : -1)) return Gate.read();
                return Gate.deny("用户这句话不是在要求开关亮屏提示卡，不要改这个设置。"
                        + "他说的“提示”“面板”多半是星仪自己右上角的执行面板：照实回答面板的事。");
            }
            @Override public String describe(JSONObject a, Turn turn) { return a.optBoolean("on", true) ? "打开亮屏提示" : "关掉亮屏提示"; }
            @Override public JSONObject run(JSONObject a, Turn turn) {
                boolean on = a.optBoolean("on", true);
                try {
                    JSONObject p = profile.load();
                    if (p == null) p = new JSONObject();
                    p.put("hints", on);
                    profile.save(p);
                    return new JSONObject().put("ok", true).put("status", "recorded")
                            .put("message", on ? "好的，亮屏时会弹提示卡。" : "好的，以后亮屏不弹提示了。");
                } catch (Exception e) {
                    String why = e.getMessage() == null ? e.toString() : e.getMessage();
                    try { return new JSONObject().put("ok", false).put("status", "error").put("message", "设置没存上：" + why); }
                    catch (JSONException ignored) { return new JSONObject(); }
                }
            }
        };
        return Collections.singletonList(t);
    }
}
