# 待派（我 plan，M78 做，我 review）

按用户 2026-11-12 的定位：「详细工作外包给 m78 做，你负责 plan 和 review」。一单一个主题，验收必须是能跑的命令。

- 今晚完成并已合并（11-12 凌晨）：`self-repair`、`reported`、`reported-2`、`verify-durable`、`real-path`、`standby-home-2`、
  `standby-home-3`、`board`。提交号见 `run.mjs board` 的「今天完成并已合并」一栏。
- 已合并：`board-money`（4fb8397 + 17b276c）：「消耗」一节写钱（14 单 / $64.41）。**这单我犯了个错**：派单时验收写成
  `sh accept_cost.sh`（脚本在 `tests/agent/`），工人后四轮全死在这一条上，白烧 $3.7；`mkspec.py` 现在会把裸文件名补成
  `tests/agent/<name>`（3a53075 + de097ff）。
- 已合并：`resume`（a480f95）：假工人 + 假 claude 复演 `run.mjs` 的续跑规则（证明过：被杀那轮不算完成、续跑用
  `--resume` 同一会话、第一句话是「The lead resumes this job.」、换模型换会话）。顺手问出 `run.mjs` 轮数上限的真实行为：
  跑满轮后重启会把最后一轮**再跑一次**（`rounds >= maxRounds` 夹到 `maxRounds-1`），不是直接 `needs_lead`——那是宽限，
  但注释里没写，`resume` 的检查按实测写。
- 今晚完成并已合并：`cost`（1db2344；真账 72 单 / $249.10，最费 `phone-console` $10.26）、
  `board` 接进 `run.mjs`（f026e38 + a9e2323：每轮的 `history[].cost` 落盘）。
- 顺手修的：`accept_board.sh`/`accept_cost.sh` 把 `/h/...` 交给 node.exe、aapt2，runner 的 shell 带着
  `MSYS_NO_PATHCONV=1` 时它们读成 `H:\h\...`（8557712）；`spec cost` 的验收路径多了层目录（8b77b69）。
- 看全局：`node tools/m31w/run.mjs board` 或 `node tools/board/board.mjs --out status/BOARD.md`——在跑、等合并、今天完成、
  卡住、消耗五段。这一页只留「之后做什么」。

## 审 2f02281（reported）时查出来的，记在这里免得忘

- `panel-never-came-up`、`self-check-blind-to-panel` 的「修复前不存在」是空的：检查在 `ReportedChecks.java`，
  而这个文件是 2f02281 自己建的，`c2ea46d^` 整个文件不存在，`reported.sh` 打一句 `ABSENT … did not exist at` 就放行。
- `pause-…` 的 label 是 `jiguangNeedsKey`、`black-screen-…` 是 `static void blank()`——符号名，不是一条检查；它们在修复前
  早就在。`reported-2` 改成：文件不在 `before` → `VOID` 且失败；label 必须是一条 ≥12 字符的整句；没对照的条目
  `before` 留空 + `void_because`，gate 打 `NO PROOF`，不再冒充验过。
- `accept_verify_durable.sh` 有一个我自己的错：`NEXT.md` 不在 worker 的 owns 里，验收前被回滚，`--check` 必然
  OUT OF DATE。改成验收先跑生成器再 `--check`（1588615）。
- `board` 的 spec 原本 owns `tools/m31w/board.mjs`、`tests/agent/fixtures/board/`，两者都是 lead 的，派不出去；
  改到 `tools/board/`、`tests/agent/board-fixtures/`（31cbe3b）。

## 一单一个 SkillChecks 末尾行

改 `tests/agent/SkillChecks.java` 末尾一行的单，一次只跑一单（`reported-2`、`standby-home-*` 不碰它，可以并行）。
`board-money` 正在跑；它之后是 `resume`（见下）。

## 下一批

用户的尺子：验收通过率、人工介入量、每单成本、恢复能力、失败是否透明，复杂度也算钱。这一批五样都做了：

1. ~~`cost`~~ 已合并（1db2344），`run.mjs` 每轮落 `history[].cost`。查出来的事实：`log.jsonl` 每轮一个
   `result` 事件，同一次会话里是**累计**的（verify-durable 五轮读 $4.82→$9.94，这单 $9.94 不是 $36.93）。
2. ~~`resume`~~ 已合并（a480f95）：假 `claude.exe` + 假工人复演「被杀那轮不算完成、续跑同一会话、换模型换会话」，
   `RESUME OK`。
3. ~~`bridge-line`~~ 已做（ecfb404）：`bridge.sh status` 打印 `unverified:`。
4. ~~`handoff`~~ 已合并（bfe0be7 + 640e1ba）：`node tools/m31w/run.mjs handoff` → `status/HANDOFF.md`，
   一句话 / 下一步（最多三条）/ 卡在人这里的 / 还没在真机上验过的 / 不要动 / 今天花了多少。
5. ~~真机~~ 11-12 当天全部验完并删空：暂停、快进、第 5 集、待机回桌面（两个方向）、说话时上屏、待机开播。

## 19:30 之后做了什么（不用再派单）

- 翻了一周真实历史（128 条：68 answered / 27 done / 13 awaiting_user / 10 interrupted / 9 cancelled / 1 failed）。
  唯一 failed 是网关余额，已解决；10 条 interrupted 里最像缺陷的是 11-08 的「切原唱/伴奏」三连败。
- 复现它：19:33 一次 unconfirmed（伴随网关超时），19:52 重跑两次全对——**不是缺陷**；结论写进 HARNESS.md
  「一次故障别急着当缺陷」。
- 变成可重复检查：`tests/agent/realpath/scenarios/karaoke-switch.py`，注册进 `scenarios.json`（第 7 个场景）。

## 21:40 之后（账本和注册表对不上，已修数据、正在修规则）

- 发现：`status/HANDOFF.md` 说「一条都没有」，可注册表里 `panel-self-check`、`black-screen-home`、
  `hint-card-not-a-switch` 从 69156e0 建仓起一次真机都没跑过、也没有证据文件。账本空着不代表都验完了，只是没人写。
  这三条现在回了 `unverified.json`（8566830），`NEXT.md` 的「还没验的」和 HANDOFF 都重新生成了。
- 规则漏在哪：`UnverifiedChecks` 里有一张手写的 `known` 白名单，注册表加场景时没人想起账本，检查也不报错。
- 派了 `ledger-vs-registry`（`briefs/ledger-vs-registry.md`）：`check.py` 两头都查（没证据就必须有待办；证据 ok:true
  待办就必须删掉），`known` 白名单删掉改成从注册表读。验收 `REALPATH OK` + `VERIFY DURABLE OK`。
- 账本现在三条待办，都要真机跑：`sh tests/agent/verify.sh --run`（醒着、桌面在前、20 分钟没人动；跑完证据落到
  `tests/agent/evidence/real-<id>.json`，`verify.sh` 自己把条目删掉）。

- 已合并 `verify-wait`（23:02）：`verify.sh --run` 一条拒绝就整轮停下，20 分钟的等待是我手写 bash 循环在等——两次。
  这单给 `verify.sh` 加 `--wait <分钟> --every <秒>`：同一条场景拒绝后按间隔重试，预算用完印 `REFUSED … 等了 X 分钟
  电视还是没空闲`；一轮结束打 `SUMMARY verified N, refused M, failed K`。测试全在 `build/` 的假账本上跑（`sleep` 用
  `XINGYI_VERIFY_SLEEP=1` 短路），不碰电视。验收 `VERIFY DURABLE OK`，离线演练 A–F（先拒后过 / 一直拒 / 真失败不重试 / 退出 0 没证据 /
  无参数路径逐字不变）；`agent core: 5696 passed, 0 failed`。
- 真机进展：22:06 `panel-self-check` 过；22:26 `black-screen-home` 过（Fluid Lab 黑屏在前 →「屏幕黑了」→ 1.6 秒桌面
  回最前）。只剩 `hint-card-not-a-switch` 一条待办，等 22:46 的空闲窗口。

## 11-14 凌晨：证据、判据、时钟——三样都在离线证明

- 已合并 `evidence-provenance`（03:00）：十份真机证据里有五份是手写探针跑的，不是场景脚本跑的。证据文件加了
  `by`（谁跑出来的），`check.py` 把「行为验过了」和「脚本能重跑」分开说；那五条补回账本。
- 已合并 `realpath-split`（02:35，我手工切的）：1338 行的检查文件拆成五个（351/312/286/315/300 行），5732→5792。
  派单时 `owns` 漏了三个新文件名，工人建出来就被判越界撤回——**新建文件必须写进 owns**。
- 已合并 `judge-rehearsal`（03:05）：`realpath/rehearse.py`，十条场景各摆几种局面，好的必过、坏的必不过，35 条。
  它自己又报回来一个洞：`sleeping-episode` 的「15 秒内亮屏」离线逼不到（`WAKE_S` 改成 150 照样绿）。
- 在跑 `rehearse-clock`：让假钟真的走，把「第 16 秒才亮」这一档逼出来；顺带拆 `RealPathLedgerChecks`（538 行）。
- 账本现在 5 条待办（`pause-through-xiaoai`、`episode-open`、`seek-one-minute`、`standby-home`、`sleeping-episode`），
  都要真机重跑，电视醒着时：`sh tests/agent/verify.sh --run --wait 45 --every 60`。

## 记着但没派单的小毛病（不值得单开一单）

- **网关 429 一次就整句失败（2026-11-14 13:12 真机）**：`RouteFailure.Kind.RATE_LIMIT` 是 `routeLevel=false`，
  官方线路又在「后台重新检查中」，于是网关一个瞬时的 429 就把整句判死，回给用户一句技术话。
  值得做的是「429 隔 1.5 秒重试一次」——代价是每次撞上限多等 1.5 秒，而网关是按分钟限流的（
  真机那条 429 很可能就是被并发挤出来的）。**这是一条取舍，不是缺陷**，要么重试一次、要么把话交还给小爱时说得更像人话。
  13:12 那次「交还给小爱」的路径其实是对的，只是记录写错了，已修（efe0511）。
- ~~`AgentEngine.state()` 的 `xiaoai_ask_ms_ago` 在「从来没问过用户」时返回 `now - 0` ≈ 1.79e12~~ **已修（436b2eb）**：
  `xiaoaiAskAt == 0 ? -1 : now - xiaoaiAskAt`，`ReportedChecks.stateAge` 读源码守四条（为何不是 0：回声守卫拿 0 当
  「很久以前」）。`agent core: 5840 passed, 0 failed`。
- ~~`xiaoai-bridge` 工作区里有三个 11-04 的未跟踪源文件~~ **已入库（c4d191b）**：`Conversation` / `Realtime` /
  `ResponsesText` 加各自的测试。`build.sh` 和 `run_all.sh` 都靠 `find src`，磁盘上有、仓库里没有，等于新克隆编出来的
  不是同一套文件。三个类暂未接进 `BridgeService`，各自是完整的、测过的单元。

## 11-14 中午：浮层的字根本读不到，三条场景的判据从来不可能成功

- **`uiautomator dump` 在云视听极光播放页只给一个空节点**（整页 SurfaceView 画的；探针 15 次全是 `nodes 1 | texts 0`），
  可屏幕上明明有 `00:05:30`、`00:47:13`、`别无选择 第5集`。`ui_text()` 在播放页永远空串，
  `pause-through-xiaoai` / `seek-one-minute` / `episode-open` 三条的判据从写出来那天就不可能成立；11-12 那些数字
  是我用眼睛看截图读的——**「脚本读得到」这件事一次都没验过**。
- 已存 fixture：`tests/agent/fixtures/realpath/player/`（11 帧 960×540 真机截图 + `ground-truth.json`，进度是人手读的）。
- 已合并 `read-the-overlay`（`2cdd000`，12:02）：`screen.py` 读进度条「已看 / 全长」比例（按亮度算覆盖率，亚像素；
  字形 OCR 试过，54 个字形错 6 个，不用），标题用字形掩膜（自己写明分不开第 5 集和第 3 集）；
  `screen_check.py` 拿 11 帧判卷（`SCREEN OK`），假 probe 回真 png 所以 `REHEARSE OK` 的 42 条走的是真的读法。
  **判卷标准本身也错过一格**：`player-00h04m40.png` 是 286 不是 280（画面自己的时钟 `00:04:46`），已改 fixture；
  工人为了不动 fixture 加的那张 `LABEL_FIX` 覆盖已删（`38fce7a`）。
- 已合并 `wait-out-the-ad`（先等广告放完再动手）、`standby-home-events`（判据读系统事件顺序、`attempt()` 留下验不了时的
  现场）、`rehearse-clock`（假钟真的走）、`verify-wait-classes`（拒绝分「等得到/等不到」）。
- 账本剩三条（`pause-through-xiaoai`、`episode-open`、`seek-one-minute`），判据现在读画面了，12:06 起在电视上跑。

## 现在该做什么（2026-11-12 19:30 之后）

没有排着的单。手上有两条路，按用户当天的说法挑：

- **装这一版**：`cd ../xiaoai-bridge && sh tests/run_all.sh && sh build.sh && sh bridge.sh install`——`cost`/`resume`/`handoff`
  是工具和测试，不进 APK，所以这一步只是常规收尾；装应用要用户点头。
- **找下一件真的事**：最好的来源是电视的真实历史（`AgentDebug HISTORY --ei days 7`），今晚已经从中挖出过
  「面板跳不出来」「自检说正常」「待机回桌面」这些。别再自己发明场景。
