# 星仪引擎（voice/assistant）

住在小米电视里的智能体的核心：听懂一句话，必要时先写计划，再用工具真的去操作电视（读屏、点选、按键、开应用、点歌、
联网查），每一步都看结果、核对，最后用一句话汇报。「完成」要有证据；拿不准、没做成、需要人点头时都会说出来。

它跑在旁边的接力应用里（[`../../xiaoai-bridge`](../../xiaoai-bridge/README.md)：构建时把这里的引擎源码编进那个 APK），
也能编成独立的「星仪电视助手」应用（`build.sh`，使用说明见 [PRODUCT.md](PRODUCT.md)）。

## 目录

| 位置 | 是什么 |
| --- | --- |
| `src/xyz/erapi0neer/xingyi/agent/` | 引擎核心，纯 Java：任务循环 `AgentLoop`（计划 → 执行 → 观察 → 核对 → 汇报、轮数与时间预算、强模型升级）、工具门控与同意、语音文本处理、行情/天气/新闻等数据工具 |
| `src/xyz/erapi0neer/xingyi/agent/tv/` | 电视这一侧：`AgentEngine`（请求的生命周期、交还小爱、面板）、`TvTools`（读屏、点选、按键、应用、媒体……）、无障碍服务 `Eyes`、执行面板 `Hud`、内存守护 |
| `src/xyz/erapi0neer/xingyi/` | 模型线路 `ModelRouter`（官方 MiniMax 优先，网关兜底，网关忙时换备用模型）与 `MiniMaxClient`，以及独立应用的界面 |
| `assets/agent/contract.json` | 系统提示词和工具契约；[PROMPTS.md](PROMPTS.md) 是它的可读版（`tests/agent/prompts_doc.py` 生成） |
| `tests/agent/` | 离线检查（`*Checks.java`，由 `AgentCoreTest` 统一跑）、真模型校准 `calibrate.mjs`、真机场景 `realpath/` 与账本 `unverified.json` |
| `tools/m31w/` | 工人调度 harness：主控写任务卡，工人在独立 worktree 里改代码，验收命令由 runner 自己跑，不信工人的自述 |
| `briefs/` | 派给工人的任务卡和验收规格（`briefs/jobs/*.json`） |
| [HARNESS.md](HARNESS.md) | 设计笔记和踩过的坑：为什么这样做、哪里错过、怎么验出来的 |

## 在自己的机器上跑离线检查

不需要电视，也不需要模型密钥。需要：JDK 17（`JAVA_HOME`）、Node 22 以上、Python 3（带 `pillow`、`numpy`）、
Android API 35 的 `android.jar`（`ANDROID_JAR`，或 `ANDROID_HOME` 下有 `platforms/android-35`）。
Windows 用 Git Bash（作者的环境，全部验证过）；Linux / macOS 用 sh——脚本按两种环境写了，但在 Linux 上的完整运行还没验证过，遇到问题欢迎提 issue。

```sh
cd voice/assistant
sh tests/agent/run_core.sh                    # 引擎核心：最后一行是「agent core: N passed, 0 failed」
sh tests/agent/android_check.sh               # 全部源码对着 android.jar 编译（Android 的 org.json 和桌面版不一样）
sh tests/route/route_check.sh                 # 模型路由的契约：回退、暂停、重新探测、备用模型……
python tests/agent/realpath/screen_check.py   # 真机判据读截图（进度条、标题）的准确度
python tests/agent/realpath/rehearse.py       # 每个真机场景的判据：好的局面必须过，坏的必须不过
python tests/agent/realpath/check.py --strict # 真机证据与账本互相核对
python tests/agent/prompts_doc.py --check     # PROMPTS.md 和 contract.json 一致
sh ../../xiaoai-bridge/tests/run_all.sh       # 接力自己的 JVM 测试
```

脚本里的工具路径默认是作者那台 Windows 电脑的，别的机器靠上面几个环境变量（见 `tests/env.sh`）。

## 需要电视和模型的部分

- **真机场景**：`sh tests/agent/verify.sh --run` 跑账本里还没在真机上验过的条目，证据写到 `tests/agent/evidence/real-<id>.json`。
  要一台开了 ADB 调试的电视（地址在 `tests/agent/realpath/probe.py`）。
- **真模型校准**：`node tests/agent/calibrate.mjs [场景…]` 用真模型跑一批场景、对照期望。网关密钥从环境变量 `GATEWAY_API_KEY` 读，
  模型用 `AGENT_MODEL` 指定。
- **装到电视**：引擎随接力应用一起装，见 [`../../xiaoai-bridge/README.md`](../../xiaoai-bridge/README.md)；两条模型线路的配置用
  `provision_minimax.py` 写进应用的私有目录，密钥只经 adb 的标准输入传过去。
