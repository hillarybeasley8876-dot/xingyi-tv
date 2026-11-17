# Brief: verify-wait — 场景说「等 20 分钟再来」，工具等，不该是人等

## Why (2026-11-12 22:06)

`sh tests/agent/verify.sh --run` 按账本顺序跑，但每条场景开头都有 `probe.refuse()`：电视要醒着、桌面在前、
**最近 20 分钟没人动过**。场景一跑就要跟电视说话（tvtask / 小爱），那本身算一次按键，所以紧接着的下一条必然
看到「最后一次按键 0.1 分钟前」→ 退出 3 → 这一轮到此为止。

结果是：**一条 `--run` 只能验掉第一条**，剩下的今晚是我手写了一个 bash 循环，每 40 秒读一次
`dumpsys power` 的 `mLastUserActivityTime`，等满 20 分钟再跑下一条——两次 20 分钟都是人（我）在等。
账本上还有两条待办，这种等法每多一条待办就多一次。工具已经有「哪些还没验」的清单了，等的这部分也该归它。

## What to build

`tests/agent/verify.sh` 加两个开关（**只碰这一个文件**）：

- `--wait <分钟>`：整轮 `--run` 的等待预算，默认 `0`（今天的行为一个字不变）。预算用完之后遇见的拒绝就照旧
  印 `REFUSED` 并跳过。
- `--every <秒>`：重试间隔，默认 `60`。每次重试前打印一行
  `WAITING <id>  电视还没空闲 N 分钟（再等 M 秒，预算还剩 K 分钟）`——N 从上一条的拒绝理由/自己读
  `dumpsys power` 拿不到就写「不清楚」，**不要猜**。

行为：

1. 场景退出 3 = 拒绝（电视有人在用 / 待机 / 读屏没开）。预算允许时，等 `--every` 秒再跑**同一条**，
   直到它给出 0 / 1（真跑完）或预算用完。
2. 预算用完还拒绝：印 `REFUSED <id>  等了 X 分钟电视还是没空闲，条目留在文件里`，`status` 仍为 0
   （拒绝不是失败，这是 `78a37e3` 定的）。
3. 退出 1（真失败）和退出 0 但没证据：立刻停这条，照旧 `FAILED` / `NO EVIDENCE`，不重试。
4. 一轮结束打一行总结：`SUMMARY verified N, refused M, failed K`（三个数就是这一轮的实情）。
5. `--wait 0`（默认）时输出和现在逐字相同——`accept_verify_durable.sh` 现在跑的是无参数路径，不能变。
6. 用法注释（文件头 `#` 那段）补上两个开关和「退出 3 是拒绝」的一句话。

sleep 本身不能算按键（它不碰设备），但要 `sleep` 可被测试打断：读环境变量
`XINGYI_VERIFY_SLEEP`（给了就 `sleep 0`），这样离线测试几秒就能跑完 20 分钟的等待。

## 测试（同一个文件里，不加新脚本）

`tests/agent/accept_verify_durable.sh` 加一节离线演练，用 `build/` 下的假账本 + 假脚本（`UNVERIFIED_JSON`
已经支持指向别的账本）：

- 假脚本第一次 `exit 3`、第二次 `exit 0` 并写证据：`--run --wait 1 --every 0`（配合 `XINGYI_VERIFY_SLEEP=1`）
  → 输出里先 `REFUSED`/`WAITING`、再 `VERIFIED`，账本里那一条被删掉，`SUMMARY verified 1, refused 0, failed 0`。
- 假脚本一直 `exit 3`：`--wait 0` → 一条 `REFUSED`、账本没动、退出码 0、`SUMMARY verified 0, refused 1, failed 0`。
- 假脚本 `exit 1`：立刻 `FAILED`、不重试（输出里没有第二行 `RUNNING`）、退出码 1。
- 假脚本 `exit 0` 不写证据：`NO EVIDENCE`、不重试、退出码 1。
- 无参数那条老路径：输出与今天逐字相同（`WAITING`/`RUNNING` 顺序、没有 `SUMMARY` 行）。

测试用的假脚本和假账本只能建在 `build/` 下，跑完清掉。

## 规则
- 只碰：`tests/agent/verify.sh`、`tests/agent/accept_verify_durable.sh`。
- 不碰 `tests/agent/unverified.json`、`tests/agent/evidence/`、`tests/agent/realpath/`、`NEXT.md`、`status/`。
- 不开电视、不连 adb、不联网：真电视一晚只等一两次，测试全在假账本上跑。
- sh 要 POSIX（Git Bash 的 dash 语义），别用 bash 专有语法。

## Acceptance（自己全跑一遍再交）
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`，N ≥ 5696
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_verify_durable.sh` → `VERIFY DURABLE OK`（含新的离线演练那几行）
- `sh tests/agent/verify.sh` 无参数 → 和今天一样列出两条 `WAITING`（`black-screen-home`、`hint-card-not-a-switch`）
- 真机不要动：这一单不跑任何场景。
