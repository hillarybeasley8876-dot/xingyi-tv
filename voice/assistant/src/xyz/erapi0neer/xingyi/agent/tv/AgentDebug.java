package xyz.erapi0neer.xingyi.agent.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** adb-only test entry (DUMP permission). Ordered broadcasts get the engine state as result data:
 * am broadcast -n xyz.erapi0neer.xiaoaibridge/xyz.erapi0neer.xingyi.agent.tv.AgentDebug -a xyz.erapi0neer.xingyi.agent.SUBMIT --es text_b64 <base64> [--es source debug]
 * actions: SUBMIT, STATE, PERF (the last 300 tasks' timings to external files/perf.jsonl), CONSENT (--ez approve true),
 * CANCEL (stops the task or drops a pending question), HUD (renders
 * sample panels to external files/hud-*.png), MEMORY / HISTORY (--es query, --ei days, --ei limit: what the 星仪 app's
 * pages read, as {result, data}), FORGET (--es kind fact|alias|note|schedule|habits --es which …),
 * COMPILE (--es pkg <package>: compiles that app's code now, as the standby compile would; the outcome is in MEMGUARD's
 * recent),
 * TRANSCRIPT (the last task's model conversation to external files/last_transcript.json; with --ei back N the N-th
 * newest kept one to transcript-<N>.json, 0 = the last task), TRANSCRIPTS (every kept transcript, newest first, one
 * JSON object per line, to external files/transcripts.jsonl) */
public final class AgentDebug extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        AgentEngine engine = AgentEngine.get(context);
        String action = intent.getAction() == null ? "" : intent.getAction();
        String result = "ok";
        int request = -1;
        if (action.endsWith(".SUBMIT")) {
            String text = intent.getStringExtra("text");
            String b64 = intent.getStringExtra("text_b64");
            if (b64 != null) text = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
            String source = intent.getStringExtra("source");
            result = engine.submit(text, source == null ? "debug" : source);
            if ("started".equals(result) || "queued".equals(result)) request = engine.lastRequest();
        } else if (action.endsWith(".CONSENT")) {
            engine.consent(intent.getBooleanExtra("approve", false));
        } else if (action.endsWith(".CANCEL")) {
            engine.stop();
        } else if (action.endsWith(".MEMORY") || action.endsWith(".HISTORY")) {
            // what the 星仪 app's 记忆 / 历史 pages read, for checks from the PC without lighting up the screen
            String data = action.endsWith(".MEMORY") ? engine.memory().toString()
                    : engine.history(intent.getStringExtra("query"), intent.getIntExtra("days", 7), intent.getIntExtra("limit", 20)).toString();
            if (isOrderedBroadcast()) setResultData("{\"result\":\"ok\",\"data\":" + data + "}");
            return;
        } else if (action.endsWith(".PERF")) {
            // the last 300 tasks' timings (files/agent/perf.jsonl) to external files/perf.jsonl, for the PC to read
            // where requests are slow (2026-11-09; state() carries only the last 5)
            result = export(context, "perf.jsonl", String.valueOf(engine.rawFile("perf.jsonl")));
        } else if (action.endsWith(".FORGET_CHECKS")) {
            // the PC's checks out of the user's history and recent turns: since is required (ms), sources default to
            // the check sources; both files are exported first (history.before-forget.json, recent.before-forget.json)
            long since = intent.getLongExtra("since", -1);
            String list = intent.getStringExtra("sources");
            if (since <= 0) result = "since required";
            else {
                export(context, "history.before-forget.json", String.valueOf(engine.rawFile("history.json")));
                export(context, "recent.before-forget.json", String.valueOf(engine.rawFile("recent.json")));
                Set<String> sources = new HashSet<>(Arrays.asList((list == null ? "mcp,debug,test" : list).split(",")));
                result = engine.forgetChecks(since, sources);
            }
        } else if (action.endsWith(".FORGET")) {
            result = engine.forgetItem(intent.getStringExtra("kind"), intent.getStringExtra("which")).replace("\"", "'");
        } else if (action.endsWith(".COMPILE")) {
            // the standby compile's path, run once for the package the PC names (2026-11-11): it takes minutes, so the
            // guard starts it on a thread of its own and answers at once — started / busy / rejected. What came of it
            // is in MEMGUARD's recent and in files/agent/compiled.json, as after a standby compile.
            String pkg = intent.getStringExtra("pkg");
            result = pkg == null ? "pkg required" : MemoryGuard.get(context).compileOnDemand(pkg);
        } else if (action.endsWith(".HUD")) {
            result = renderSamples(context);
        } else if (action.endsWith(".MEMGUARD")) {
            // the memory guard: --es mode on|off switches it; plan (the default) says what 清理内存 and the guard would
            // stop now, stopping nothing. LocalAdb: never on the main thread.
            PendingResult pending = goAsync();
            String mode = intent.getStringExtra("mode");
            new Thread(() -> {
                String out;
                try {
                    MemoryGuard g = MemoryGuard.get(context);
                    if ("on".equals(mode) || "off".equals(mode)) g.setEnabled("on".equals(mode));
                    out = g.plan().toString();
                } catch (Exception e) {
                    String why = e.getMessage() == null ? "" : e.getMessage().replace("\"", "'").replace("\\", "/");
                    out = "{\"error\":\"" + e.getClass().getSimpleName() + " " + why + "\"}";
                }
                pending.setResultData(out);
                pending.finish();
            }, "agent-debug").start();
            return;
        } else if (action.endsWith(".TRANSCRIPTS")) {
            // every kept transcript, newest first, one compact JSON object per line: the whole ring in one file to pull
            // (2026-11-09: only the last task's transcript was kept, so an earlier failure could no longer be read)
            PendingResult pending = goAsync();
            new Thread(() -> {
                List<String> ring = compact(engine.transcripts());
                StringBuilder text = new StringBuilder();
                for (String e : ring) text.append(e).append('\n');
                String out = export(context, "transcripts.jsonl", text.toString());
                pending.setResultData("{\"result\":\"" + (out.endsWith(".jsonl") ? out + " " + ring.size() : out) + "\"}");
                pending.finish();
            }, "agent-debug").start();
            return;
        } else if (action.endsWith(".SCREEN") || action.endsWith(".TRANSCRIPT")) {
            // Reading the screen may fall back to a loopback-ADB dump: never on the main thread.
            PendingResult pending = goAsync();
            boolean screen = action.endsWith(".SCREEN");
            // --ei back N: the N-th newest kept transcript (0 = the last task) instead of last_transcript.json
            int back = screen ? -1 : intent.getIntExtra("back", -1);
            new Thread(() -> {
                String out;
                if (screen) out = dumpScreen(context);
                else if (back < 0) out = export(context, "last_transcript.json", engine.transcript());
                else {
                    List<String> ring = engine.transcripts();
                    out = back < ring.size() ? export(context, "transcript-" + back + ".json", ring.get(back)) : "none";
                }
                pending.setResultData("{\"result\":\"" + out + "\"}");
                pending.finish();
            }, "agent-debug").start();
            return;
        }
        if (isOrderedBroadcast())
            setResultData("{\"result\":\"" + result + "\",\"request\":" + request + ",\"state\":" + engine.state() + "}");
    }

    private static String dumpScreen(Context c) {
        try (TvTools tools = new TvTools(c)) {
            return export(c, "screen.txt", tools.read().text());
        } catch (Exception e) {
            return "error " + e.getClass().getSimpleName();
        }
    }

    /** The ring's entries as one line each: the files are pretty-printed, a .jsonl file must not be. */
    private static List<String> compact(List<String> entries) {
        List<String> out = new ArrayList<>();
        for (String e : entries) {
            try { out.add(new JSONObject(e).toString()); } catch (JSONException torn) { /* a half-written file */ }
        }
        return out;
    }

    /** Writes to the app's external files dir (adb pull /sdcard/Android/data/<pkg>/files/<name>). */
    private static String export(Context c, String name, String text) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return "no external dir";
        try (FileOutputStream o = new FileOutputStream(new File(dir, name))) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
            return name;
        } catch (Exception e) {
            return "error";
        }
    }

    /** The cards the tools hand to the panel, verbatim, so the samples are what the user will really see. */
    private static final String WEATHER_CARD = "{\"type\":\"weather\",\"place\":\"上海徐汇\",\"day\":\"明天 11月8日 周日\","
            + "\"sky\":\"阴\",\"lo\":14,\"hi\":18,\"rain\":20,\"hours\":[{\"h\":0,\"t\":15.1,\"p\":5},"
            + "{\"h\":3,\"t\":14.6,\"p\":5},{\"h\":6,\"t\":14.2,\"p\":10},{\"h\":9,\"t\":15.8,\"p\":15},"
            + "{\"h\":12,\"t\":17.6,\"p\":20},{\"h\":15,\"t\":17.9,\"p\":40},{\"h\":18,\"t\":16.4,\"p\":55},"
            + "{\"h\":21,\"t\":15.5,\"p\":5}]}";
    /** The same card on a dry day: no hour reaches 30 %, so no bar and no rain line under the hours. */
    private static final String DRY_CARD = "{\"type\":\"weather\",\"place\":\"上海徐汇\",\"day\":\"后天 11月9日 周一\","
            + "\"sky\":\"晴\",\"lo\":12,\"hi\":21,\"rain\":0,\"hours\":[{\"h\":0,\"t\":13.4,\"p\":0},"
            + "{\"h\":3,\"t\":12.6,\"p\":0},{\"h\":6,\"t\":12.2,\"p\":5},{\"h\":9,\"t\":15.1,\"p\":5},"
            + "{\"h\":12,\"t\":19.8,\"p\":10},{\"h\":15,\"t\":20.6,\"p\":15},{\"h\":18,\"t\":18.2,\"p\":10},"
            + "{\"h\":21,\"t\":15.9,\"p\":5}]}";
    private static final String STOCKS_CARD = "{\"type\":\"stocks\",\"rows\":[{\"name\":\"智谱\",\"price\":\"426.20\","
            + "\"unit\":\"港元\",\"pct\":\"-10.46\",\"when\":\"11 月 6 日收盘\"},{\"name\":\"MINIMAX\","
            + "\"price\":\"155.80\",\"unit\":\"港元\",\"pct\":\"-8.46\",\"when\":\"11 月 6 日收盘\"}]}";
    private static final String LIST_CARD = "{\"type\":\"list\",\"title\":\"B站热门\",\"items\":["
            + "{\"title\":\"【何同学】我们做了一个会自己走路的机器人，这次是真的\","
            + "\"meta\":\"老师好我叫何同学 · 312万播放\"},"
            + "{\"title\":\"一个人骑行 3000 公里回家，第 47 天我到了青海\","
            + "\"meta\":\"一直在骑的小周 · 168万播放\"},"
            + "{\"title\":\"【硬核】从零开始自制一台光刻机？我把它做出来了\","
            + "\"meta\":\"稚晖君 · 496万播放\"},"
            + "{\"title\":\"耗时 200 天，我把整个大学宿舍搬进了 Minecraft\","
            + "\"meta\":\"老番茄 · 287万播放\"},"
            + "{\"title\":\"2026 年高考数学真题解析（完整版）\","
            + "\"meta\":\"中国教育在线 · 92万播放\"}]}";

    private static JSONObject json(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String renderSamples(Context c) {
        List<String[]> steps = new ArrayList<>(Arrays.asList(
                new String[]{"用哔哩哔哩搜索“星辰亻旅行者”", "done"},
                new String[]{"在结果里找到他最新发布的视频", "in_progress"},
                new String[]{"打开并播放这个视频", "pending"},
                new String[]{"核对正在播放的标题", "pending"}));
        // the thinking chain under the step it belongs to (2026-11-08: 「思维链什么的也要优化」)
        List<String[]> bili = Arrays.asList(new String[]{"搜索B站：星辰亻旅行者", "ok"},
                new String[]{"读屏", "ok"}, new String[]{"点选「视频」标签", "live"});
        List<String[]> webSearch = Arrays.<String[]>asList(new String[]{"联网搜索：OpenAI 最近的新动向", "live"});
        // the 「…并播放他最新的视／频」 case: a goal that wraps, and a step long enough to wrap too
        List<String[]> longSteps = Arrays.asList(
                new String[]{"用哔哩哔哩搜索“星辰亻旅行者”并打开他的主页", "done"},
                new String[]{"在「视频」标签里按最新发布排序，找到他最新的一条", "in_progress"},
                new String[]{"打开这个视频并等到播放器真的开始播放，而不是停在开屏广告上", "pending"},
                new String[]{"核对正在播放的标题和 UP 主", "pending"});
        List<String[]> longTrace = Arrays.asList(new String[]{"打开「星辰亻旅行者」的主页", "ok"},
                new String[]{"点选「视频」标签", "ok"}, new String[]{"按「最新发布」排序", "live"});
        List<String[]> failedSteps = Arrays.asList(
                new String[]{"用哔哩哔哩搜索“星辰亻旅行者”", "done"},
                new String[]{"打开他最新发布的视频并播放", "failed"},
                new String[]{"核对正在播放的标题", "skipped"});
        List<String[]> failedTrace = Arrays.asList(new String[]{"打开播放页", "ok"},
                new String[]{"等待开播", "failed"});
        List<String[]> download = Arrays.asList(new String[]{"从 GitHub 官方发布页下载 TV Bro", "ok"},
                new String[]{"已下载 60%（90 / 150 MiB）", "live"});
        List<String[]> via = Arrays.asList(new String[]{"找「Via浏览器」的官方安装包", "ok"},
                new String[]{"从 res.viayoo.com 下载并核对Via浏览器", "ok"});
        // the 2026-11-05 evening conversation, for the thread above the task (「每次会覆盖上一次的结果」)
        List<String[]> asked = Arrays.<String[]>asList(new String[]{"我想知道最近有什么新动向",
                "你说的“新动向”是指 B站UP主“星辰亻旅行者”的最新动态，还是最近的整体热点新闻？"});
        List<String[]> thread = Arrays.<String[]>asList(asked.get(0), new String[]{"我说的是想听OpenAI这个公司的",
                "OpenAI 11月4日发布了 Images 2.6，11月3日任命 Jill Hazelbaker 为首席企业事务官。"});
        // one file each: hud-<name>.png
        String[] names = {"running", "running-long", "failed", "chain-noplan", "confirm-chips", "install-consent",
                "confirm", "done", "ask", "hint", "thread-early", "thread-done", "queued",
                "reading-early", "hint-calendar", "card-weather", "card-weather-dry", "card-stocks", "card-list"};
        Hud.State[] samples = {
                new Hud.State("running", "在B站搜索“星辰亻旅行者”并播放他最新的视频", steps, "点选「视频」标签", "",
                        null, "", "", null, bili),
                new Hud.State("running", "在B站搜索“星辰亻旅行者”并播放他最新的视频", longSteps, "按「最新发布」排序", "",
                        null, "", "", null, longTrace),
                new Hud.State("failed", "在B站搜索“星辰亻旅行者”并播放他最新的视频", failedSteps, "",
                        "没能播出来：B站一直停在开屏广告，你可以说“星仪继续”。", null, "", "", null, failedTrace),
                // running with nothing planned yet: the chain alone under the goal
                new Hud.State("running", "“打开浏览器看看”", null, "已下载 60%（90 / 150 MiB）", "",
                        null, "", "", null, download),
                new Hud.State("confirm", "安装哔哩哔哩官方客户端", Arrays.<String[]>asList(new String[]{"下载并校验官方安装包", "in_progress"}, new String[]{"安装并核对版本", "pending"}),
                        "", "需要你确认：从哔哩哔哩官方网站下载并安装客户端（约 100 MB）。可以就说“好的”，不做就说“算了”。"),
                new Hud.State("confirm", "“帮我装个Via浏览器”", null, "",
                        "安装「Via」（mark.via 6.5.0，3 MB）：从 res.viayoo.com 下载，开发者签名：Shanghai Zhiwei Network Technology Co., Ltd.（Via）。可以就说“好的”，不做就说“算了”。",
                        null, "", "", null, via),
                new Hud.State("confirm", "安装哔哩哔哩官方客户端", Arrays.<String[]>asList(new String[]{"下载并校验官方安装包", "in_progress"}, new String[]{"安装并核对版本", "pending"}),
                        "", "需要你确认。"),
                new Hud.State("done", "打开网易云音乐播放林俊杰的《不为谁而作的歌》", Arrays.<String[]>asList(new String[]{"网易云点歌并核对播放器", "done"}), "",
                        "已在网易云播放林俊杰的《不为谁而作的歌》，播放器显示的歌名和歌手都对上了。"),
                new Hud.State("ask", "“放那个”", null, "", "你说的“那个”是哪一首歌或哪部视频？说个名字我马上放。"),
                new Hud.State("hint", "", null, "",
                        "今天 20:00 提醒你：该吃药了\n随手记里有 2 件没划掉\n这个时候你常听林俊杰，说「放首林俊杰的歌」"),
                new Hud.State("running", "“我说的是想听OpenAI这个公司的”", null, "正在理解", "", asked, ""),
                new Hud.State("done", "“那谷歌呢”", null, "", "谷歌 11月4日发布了 Gemini 3.5 Flash，并宣布明年在印度新建数据中心。", thread, ""),
                new Hud.State("running", "“OpenAI最近有什么新动向”", null, "联网搜索：OpenAI 最近的新动向", "", null,
                        "明天上海天气怎么样", "", null, webSearch),
                // 2026-11-06 overnight pass: the first page of a streamed answer, read before the rest is written
                new Hud.State("reading", "“详细讲讲人类历史上的四次工业革命”", null, "",
                        "人类历史上通常说的四次工业革命，可以用“动力—能源—信息化—智能化”这条主线来看。第一次工业革命（约1760—1840）：蒸汽与机械。核心是瓦特改良蒸汽机，把它变成驱动一切机器的通用动力。",
                        null, "", "1/…"),
                new Hud.State("hint", "", null, "", "今天阴，14 到 19 度；明天有小雨\n明天立冬\n随手记里有 2 件没划掉"),
                // the three answer cards (2026-11-07), as the weather / stock / search tools produce them
                new Hud.State("done", "“明天天气怎么样”", null, "",
                        "上海徐汇明天（11 月 8 日 星期日）阴，14 到 18 度，降水概率 20%，东风 3 级", null, "", "",
                        json(WEATHER_CARD)),
                // the same panel on a dry day: nothing reaches 30 %, so the strip has no bars and no rain line
                new Hud.State("done", "“后天天气怎么样”", null, "",
                        "上海徐汇后天（11 月 9 日 星期一）晴，12 到 21 度，降水概率 0%，西风 2 级", null, "", "",
                        json(DRY_CARD)),
                new Hud.State("done", "“查询质朴和MINIMAX股价并且进行比较”", null, "",
                        "「质朴」按读音查的是智谱：智谱 426.20 港元，跌 10.46%；MINIMAX 155.80 港元，跌 8.46%（11 月 6 日收盘）",
                        null, "", "", json(STOCKS_CARD)),
                new Hud.State("done", "“B站有什么热门视频”", null, "",
                        "B站热门：第一，《我们做了一个会自己走路的机器人》；第二，《一个人骑行 3000 公里回家》", null, "", "",
                        json(LIST_CARD)),
        };
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < samples.length; i++) {
            Hud.Render r = Hud.render(c, samples[i]);
            File f = new File(dir, "hud-" + names[i] + ".png");
            try (FileOutputStream o = new FileOutputStream(f)) { r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, o); }
            catch (Exception e) { return "error"; }
            // max_y is where the last block ends and h the height the panel measured for it: a max_y at or past
            // h means a block was placed off the box, and a panel much taller than its blocks means they were
            // spread apart by a pass onDraw did not know about (2026-11-08: the chain drew over the steps below).
            out.append(f.getName()).append(' ').append(r.bitmap.getWidth()).append('x').append(r.bitmap.getHeight())
                    .append(" max_y=").append(Math.round(r.maxY)).append(" h=").append(r.height).append(';');
        }
        return out.toString();
    }
}
