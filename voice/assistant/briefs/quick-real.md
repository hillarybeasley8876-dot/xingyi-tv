# Brief: quick-real — the quick commands the user really said and that missed

## Why (the TV's own records, 2026-11-05 … 11-09)
`QuickCommand.match` turns a simple TV command into one tool call with no model round (25–755 ms instead of 3–100 s).
The lead replayed every real sentence of the last 30 days (HISTORY + perf.jsonl, 121 distinct sentences) through it:
- 「切换到伴奏模式」 (11-09 20:11) missed — the 伴奏 verbs know 切到 / 换成 but not 切换到 — went to the model and took
  **105 s, 11 rounds**. 「嗯那个伴奏模式」 (11-08) missed on the leading 嗯.
- 「然后关闭显示器」 (11-09 20:41) missed twice over: the leading 然后, and 显示器 is not a word for the screen. 8.9 s.
- 「嗯开机」 missed (嗯, and 开机 is not a quick command): 4.6 s, 3 rounds.
- 「音量调到20%」 and 「音量调到最大」 missed (the % sign, 最大).
- 「查询制储的股价」「查询支付的股价」「查询MAX最新的股价」 *matched*, but with the verb inside the name: stock_quote got
  `{"query":"查询制储"}` (the lookup fails and the model redoes it). 「查一下MINI MAX最新的股价」 and 「我说的是MINI MAX最新的股价」
  missed (a space in the name; the correction lead 我说的是).
- 「回到桌面停止播放」 missed: 8.4 s, 4 rounds.

## What to build (only in `QuickCommand.java`; checks in `QuickChecks.java`)
1. **Leading fillers** — add to `LEAD` exactly: 嗯 呃 额 诶 哎 唉 哦 噢 啊 那个 那就 然后 我说的是 我是说 (longest first where
   one is a prefix of another). Do **not** add 再, 接着, 现在, 是, 就, 那 alone: 「再说一遍」 and 「接着放」 are commands
   themselves (stripping would break them), 现在 is part of many phrases, and 是 / 就 change what a sentence means
   (「是不是静音」).
2. **显示器 and 开机** — `OFF` gets 关闭显示器 关掉显示器 关显示器 显示器关 显示器关掉 显示器关闭 熄灭显示器 显示器熄灭 关闭电视屏幕
   关掉电视屏幕 (normalise() already turns 「把显示器关了」 into 「显示器关」). `SCREEN` gets 打开显示器 显示器打开 开显示器 点亮显示器
   开机 开电视 打开电视 电视打开. These two sets are checked before the 「打开<app>」 rule, keep it that way.
3. **伴奏 / 原唱 verbs** — in `BANZOU` add 切换到 切换成 切换为 切换 转到 转成 改成 改为 调为 设为 设成 用 开启 打开 开; in `YUANCHANG`
   add 切换回 切换到 切换成 切换为 切换 转回 转到 改回 改成 恢复 还原 回到. Longest alternatives first (regex alternation is
   ordered: 「切换到」 must be tried before 「切换」 and 「切」).
4. **Volume** — `SET`'s level may end in % or ％ and may be 百分之<number> (「20%」 → 20, 「百分之三十」 → 30, 「百分之百」 →
   100); the level 最大 / 最大声 / 最高 / 满 means 100. Also without a verb: 「音量最大」「声音最大」「音量开满」「声音开到最大」 →
   `set_volume {"level":100}`. Nothing for 最小 (0 or 1 is not clear).
5. **Stock names** — in `STOCK` an optional verb before the name, not part of it: 查询 查一下 查查 查下 查 看看 看一下 看下 问一下 帮我查一下
   帮我查 (longest first). A name may contain ASCII spaces between Latin letters/digits (「MINI MAX」): allow them in the
   name, then trim and collapse runs of spaces to one; a name that is empty, only spaces, or a single Latin letter is no
   match. The name stays at most 12 characters.
6. **Stop and go home** — 「回到桌面停止播放」「停止播放回到桌面」「回到桌面并停止播放」「停止播放并回到桌面」「停止播放然后回到桌面」
   「回到桌面然后停止播放」 → `media_control {"action":"exit"}` with **no** `app` (exit then acts on what is playing / in front).
   Reuse `HOME_LEAD` / `HOME_TAIL` (they already take 并 / 然后 / 再 / commas): the part that is left must be one of the
   `STOP` phrases. 「回到桌面关掉音乐」 stays as it is today (null: 音乐 is in `NOT_APP` on purpose).

## Checks (in `tests/agent/QuickChecks.java`)
Add a group `real()` called from `run()`, using the existing `is(...)` and `none(...)` helpers. Every line below is a
check (expected tool and exact args; `none` = must stay null):
- 然后关闭显示器 → screen_off {} ; 关闭显示器 → screen_off ; 把显示器关了 → screen_off ; 关掉电视屏幕 → screen_off
- 打开显示器 → wake_screen {} ; 嗯开机 → wake_screen ; 开机 → wake_screen ; 打开电视 → wake_screen
- 切换到伴奏模式 → netease_karaoke {"on":true} ; 切换成伴奏 → on:true ; 切换伴奏 → on:true ; 嗯那个伴奏模式 → on:true ;
  改成伴奏模式 → on:true ; 切换回原唱 → {"on":false} ; 切换到原唱模式 → on:false ; 恢复原唱 → on:false
- 音量调到20% → set_volume {"level":20} ; 音量调到百分之三十 → {"level":30} ; 音量调到最大 → {"level":100} ;
  声音开到最大 → {"level":100} ; 音量最大 → {"level":100}
- 查询制储的股价 → stock_quote {"query":"制储","final":true} ; 查询支付的股价 → {"query":"支付","final":true} ;
  查询MAX最新的股价 → {"query":"MAX","final":true} ; 查一下MINI MAX最新的股价 → {"query":"MINI MAX","final":true} ;
  我说的是MINI MAX最新的股价 → {"query":"MINI MAX","final":true} ; 茅台股价多少 → {"query":"茅台","final":true}
- 回到桌面停止播放 → media_control {"action":"exit"} ; 停止播放回到桌面 → same ; 停止播放然后回到桌面 → same
- 嗯暂停 → media_control {"action":"pause"} ; 呃下一首 → {"action":"next"} ; 那个音量调到20 → set_volume {"level":20} ;
  然后回到桌面 → press_keys {"keys":["home"]}
- still as before: 再说一遍 → replay {} ; 接着放 → media_control {"action":"play"}
- none: 那个人后来怎么样了 ; 我说的是画面右下角 ; 现在是伴奏模式你要切成原唱模式 ; 我说的是关闭屏幕不是关闭电视 ; 关 ; 嗯 ; 然后呢 ;
  是清理后台 ; 我想跳楼机 ; 我要唱跳楼机 ; 嗯TIBA这个人 ; 查询质朴和MINIMAX股价并且进行比较 ; 为什么MINI MAX最新股价跌这么多 ;
  回到桌面关掉音乐 ; 放演员的伴奏 ; 是不是静音 ; 查A的股价
If one of these expectations looks wrong to you after reading the code, do not change it: write why under `## Requests`.
Every existing check must keep passing unchanged.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse
  requests). No TV, no adb, no network.
- Android: no `Pattern.UNICODE_CHARACTER_CLASS` / `(?U)`; Chinese characters in patterns as literal text or `一-龥`.
- The XiaoAi bridge compiles `QuickCommand` and has its own JVM tests about which quick commands wait for XiaoAi
  (`Takeover.absoluteQuick`); the acceptance script runs them against your tree. Do not edit the bridge.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N ≥ 3720
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_quick_real.sh` → `QUICK REAL OK`
