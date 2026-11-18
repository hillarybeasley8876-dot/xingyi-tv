# 星仪 XINGYI

> 住在电视里的智能体。

开源的小米电视语音 harness：小爱做不到的事交给它——它先理解，再用工具真的去操作电视，边做边把过程显示在屏幕右上角，做完由小爱读出结果。

**宣传页：<https://hillarybeasley8876-dot.github.io/xingyi-tv/>**

## 它做了什么

- **接管小爱**：唤醒和识别留给小爱，理解、执行、核对交给星仪，结果再借小爱的嗓子读出来
- **真的操作电视**：读屏、点选、按键、装应用，走本机 ADB + 无障碍服务；70 多个工具，其中 20 多个公开数据工具按需发送
- **快**：100+ 句式不经模型，真机 25–300 ms；连接预热、提示缓存、一步连接器
- **可信**：引擎强制「完成要有证据」——没有证据的 `finish` 会被作废；敏感操作（安装/支付/删除）要用户点头，60 秒不答视为不做
- **活着**：2 GB 内存的电视上，内存守护 + 20 秒心跳闹钟 + 进程被杀后任务自动续做；固件每次开机都把 ADB 调试复位成关，守卫在开机后 409 ms 写回
- **桌面也开源**：Orrery「终端」——Chen 吸引子粒子流体桌面，393,216 个粒子，compute shader，在 Mali-G31 MP2 上锁 30 fps

## 架构

```text
你说话 → 小爱(唤醒·识别) → 接力(拦截·收集·转交,无界面)
      → 星仪引擎(理解→计划→执行→观察→核对→汇报)
      → 电视(本机 ADB·无障碍服务) → 小爱读出结果
```

运行时住在「接力」应用里：它没有任何界面，从不进小米电视 PeriodicCleaner 的清理名单——这是在 2 GB 电视上活下来的第一课。

## 源码目录

| 目录 | 是什么 | 怎么验 |
|---|---|---|
| `voice/assistant` | 星仪引擎：计划循环、工具、证据校验、面板 | `sh tests/agent/run_core.sh`（5,911 项离线检查）；真机场景与判据在 `tests/agent/realpath`；工人调度在 `tools/m31w` |
| `xiaoai-bridge` | 小爱接力：日志监听、接管、快速命令、本机 ADB 与遥控服务 | 见目录内 README（构建要设 `ANDROID_SDK` / `JAVA_HOME` / `KS`，keystore 不公开，用自己的） |
| `launcher` | Orrery 桌面：Chen 吸引子粒子流体 + 排版层 | `sh build.sh`；MiSans 不随仓库，见 `launcher/assets/fonts/README.md` |

没有公开的：签名 keystore、MiSans 字体、真机截图和原始运行记录；网关地址与密钥用占位（`gateway.example.com` / `GATEWAY_API_KEY`）。

## 验证文化

- 核心离线检查 5,911 项，装机前必须过闸
- 200 多个真模型校准场景（`node tests/agent/calibrate.mjs` 跑完会打印 `scenarios: N`，以那行为准）
- 真机场景带证据文件；`unverified.json` 账本两头互相校验——账本空着，不等于都验完了

## License

MIT
