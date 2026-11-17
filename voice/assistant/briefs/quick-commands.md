# Brief: quick commands — simple TV commands run their tool directly, without the model

## Why
Since 2026-11-05 every request XiaoAi hears goes to 星仪 (星仪接管模式: XiaoAi only listens; the bridge cuts its voice).
The user: 「简单的命令直接调用tools完成 复杂的 再联网」. Today even 「音量调大一点」 waits for a model round (2–5 s).
Simple, unambiguous TV commands must be matched locally and run their tool at once; everything else still goes to the
model exactly as before.

XiaoAi may already have done a relative action for the same sentence (the bridge passes its instruction names; see
`TvTools.xiaoaiDid` and the gates of `adjust_volume` / `media_control`, which DENY a second 调大 / 下一首). The quick path
must respect those gates: a DENY means "already done" — report the current state instead of acting.

## What to build
1. `src/xyz/erapi0neer/xingyi/agent/QuickCommand.java` (pure Java, package `xyz.erapi0neer.xingyi.agent`, no android.*):
   - fields `public final String tool; public final JSONObject args;`
   - `public static QuickCommand match(String utterance, Collection<String> appLabels)` → a command, or null when the
     sentence is not EXACTLY one simple command (when in doubt return null: the model handles it).
   - normalisation before matching: trim; drop a leading 「小爱同学」「小爱」「星仪」 and 「请」「麻烦」「帮我」「给我」「把」;
     drop trailing 「吧」「呀」「啊」「呢」「了」「一下」 and punctuation/spaces (。！？!?，,、 and ASCII/full-width space).
   - `static int number(String s)`: digits or Chinese numerals 零〇一二两三四五六七八九十百 (e.g. 二十→20, 十五→15,
     三十五→35, 一百→100, 两→2, 十→10); -1 when it is not a number.
   - the commands (tool names and arguments are the contract's, `assets/agent/contract.json`):
     | sentence (after normalisation) | tool | args |
     |---|---|---|
     | 音量/声音 + 调到/调成/设为/设置为/设成/开到/到 + N (0–100, clamp) | set_volume | {"level": N} |
     | 音量/声音 + (调/开/放)? + 大/高 + (一点/一些/点/些)?; 大声/高声 + (一点/点)?; 音量加/音量加大 | adjust_volume | {"direction":"up","steps":10} |
     | the same with 小/低; 小声/低声 (一点/点)?; 音量减/音量减小 | adjust_volume | {"direction":"down","steps":10} |
     | 静音 | adjust_volume | {"direction":"mute"} |
     | 取消静音 / 打开声音 / 恢复声音 / 开声音 | adjust_volume | {"direction":"unmute"} |
     | 暂停 / 暂停播放 / 先暂停 | media_control | {"action":"pause"} |
     | 继续播放 / 恢复播放 / 接着放 / 接着播 / 接着看 (NOT a bare 继续: that resumes 星仪's last task) | media_control | {"action":"play"} |
     | 下一首/下一集/下一个/下一曲/切歌/换一首/换首歌 | media_control | {"action":"next"} |
     | 上一首/上一集/上一个/上一曲 | media_control | {"action":"previous"} |
     | 停止播放 / 别放了 / 不放了 | media_control | {"action":"stop"} |
     | 回到桌面 / 返回桌面 / 回桌面 / 回到主页 / 返回主页 / 回主页 / 回到首页 / 返回首页 | press_keys | {"keys":["home"]} |
     | 返回 / 返回上一页 / 返回上一级 | press_keys | {"keys":["back"]} |
     | (切换到/切到/换到/打开)? + HDMI + 1/2/3 or 一/二/三 (any case, optional space) | switch_input | {"name":"HDMI1"} … |
     | N + 分钟 + (后/以后/之后) + 关机/关电视/休眠/待机/睡眠; N + 小时 + … (→ 60·N minutes; 1–600) | sleep_timer | {"minutes": N} |
     | 打开 + X, where X (after aliases B站/b站/哔哩→哔哩哔哩, 网易云→网易云音乐, 云视听→云视听极光, 设置→电视设置) is one of `appLabels` exactly, or exactly one label contains X and X has ≥ 2 characters | open_app | {"app": the label} |
   - Anything with more after the command is NOT simple and returns null: 「打开B站搜索星辰亻旅行者」「暂停然后打开网易云」
     「音量调到20然后播放晴天」「播放周杰伦的晴天」「音量是多少」「调大一点」(no 音量/声: could be brightness) 「继续」.
   - `public String reply(JSONObject result)`: the short sentence spoken after the tool ran (≤ 20 characters, Chinese):
     volume tools with `result.volume` → 「音量 N」 (mute → 「已静音」, unmute → 「已取消静音」); open_app → 「已打开<app>」;
     switch_input → 「已切到 HDMI N」; sleep_timer → 「好的，N 分钟后关机」; others → 「好的」.
2. `TvTools.java`: if `apps()` is private, make it package-private (do not change what it returns).
3. `AgentEngine.java` (the host; Android side): in `run(...)`, right after the `TvTools` is created and `xiaoaiDid` is set,
   and before the `AgentLoop` is built, for a fresh request (`resumeCount == 0`):
   `QuickCommand q = QuickCommand.match(text, tools.apps().keySet())`; when q != null call a new
   `private boolean runQuick(QuickCommand q, TvTools tools, String text, String source, int gen)` and `return` when it
   returns true. `runQuick`:
   - finds the tool named `q.tool` in `tools.tools`; builds a minimal `Tool.Turn` (utterance=text, source, cancelled =
     `gen != currentGeneration()`, progress no-op, say → the engine's existing speech path, round = 1);
   - `gate(q.args, turn)`: DENY → nothing runs: for `adjust_volume` read the volume with the `tv_status` tool and speak
     「音量 N」, for `media_control` speak 「好的」; then record and return true. The same "already done" path for
     `press_keys` when `tools.xiaoaiDid` has a name starting with `UIController.` (XiaoAi pressed the key itself: a second
     Back would leave two pages). READ/ACT → `run(q.args, turn)`;
     SENSITIVE → return false (the model asks for consent as usual);
   - a result with `ok=true` → speak `q.reply(result)` through the engine's existing `speak` (it already stays silent
     for ui/mcp sources), record the exchange so it shows in `recent_turns` and the panel like any task (create a `Ledger`
     for the utterance, end it as answered/done with the reply, `store.remember(...)`; look at how `run()` does it), return
     true. `ok=false` or an exception → return false so the model takes over with no change in behaviour.
   - must not throw; keep it under 40 lines.

## Checks
`tests/agent/QuickChecks.java` (an empty stub; `AgentCoreTest.main` already calls `QuickChecks.run()`). Pure parts only,
at least 40 checks with labels: every row of the table (tool and exact args), each 「not simple」 example → null, the
normalisation (「小爱同学，音量调到二十吧。」 → set_volume 20), `number()` (二十 20, 十五 15, 三十五 35, 一百 100, 两 2,
十 10, 七 7, 「abc」 -1), `open_app` with labels {哔哩哔哩, 网易云音乐, 云视听极光, 电视设置, 相册}: 「打开B站」→哔哩哔哩,
「打开相册」→相册, 「打开设置」→电视设置, 「打开抖音」→ null, and `reply()` for each tool (use fake result JSONs).

## Acceptance
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 328 (288 before this job).
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`.
- `sh tests/agent/accept_quick.sh` → builds the TV app with your sources (the XiaoAi bridge's `build.sh`), runs the
  bridge's JVM suite against them and checks the wiring is there; prints `QUICK OK`.
