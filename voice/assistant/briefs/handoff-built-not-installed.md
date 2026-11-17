# Brief: 交接页要说出「编好了还没装」——不只是「有单子合进来了」

## 为什么
`status/HANDOFF.md` 是给凌晨三点接手的人看的那一页。它的「下一步」里只有一种情况会出现「装这一版 APK」：
**某一单在窗口里合进来了，而且比电视上装的那版 APK 新**（`mergedNewer > 0`）。

2026-11-16 12:15 抓到的洞：lead 自己提交了一条修复（不是工人的单子），重编了 APK（12:15），电视上装的是 12:07
那版。交接页写的是「没有必须现在做的」——一个装机正等着人点头，这一页却说什么都不用做。

「电视上装的是哪版」现在已经知道了（`xiaoai-bridge/build/installed-apk.json`，`bridge.sh install` 写的；
CLI 的 `apkMsOf` 先读它）。缺的是另一半：**磁盘上最新编出来的那版是什么时候编的**，以及「编出来的比装着的新」
本身就该是一条下一步。

## 要做的
同一条规则有两份实现，**两边必须逐字节一样**（`tests/agent/HandoffChecks.java` 拿同一批 fixture 两边各跑一遍比字节）：
- `tools/handoff/handoff.mjs`：纯函数 `handoff(states, opts)` 和它下面的 `nextLines` / `installItem`；
- `src/xyz/erapi0neer/xingyi/agent/Handoff.java`：`page(...)` / `nextLines` / `installItem`。

1) 新输入 `builtMs`：磁盘上最新编出来的 APK 的时刻（毫秒；0 = 不知道 / 没有）。
   - JS：`opts.builtMs`，和 `apkMs` 一样的校验（数字、有限，否则当 0）。
   - Java：`page(...)` 加一个 `long builtMs` 参数（保留一个旧签名的重载，传 0，免得别的调用方要改）。
2) 规则：
   - 已有规则不变：`mergedNewer > 0` 时照旧出 `installItem(mergedNewer, apkMs)`。
   - 新增：`mergedNewer == 0` 且 `builtMs > 0` 且 `builtMs > apkMs` 时，出一条
     `INSTALL + " · 编好了还没装（" + 编的时刻 + "，电视上的是 " + 装的那版的时刻 + "）· " + INSTALL_CMD`。
     时刻格式 `MM-dd HH:mm`，按北京时间（UTC+8），JS 和 Java 用同一个格式化；`apkMs == 0` 时括号里后半句写
     「电视上的是哪版不知道」。
   - 两条装机提醒**最多出一条**（有单子合进来时用老的那条）。
   - 仍然受「最多三条」限制，顺序：验收红 → 上电视验 → 装这一版，不变。
3) CLI（`handoff.mjs` 的 IO 那一层，不在纯函数里）：
   - 新函数 `builtMsOf(root)`：桥那边 `build*/xiaoai-bridge.apk` 里最新的 mtime（就是 `apkMsOf` 原来退回去猜的
     那一段，抽出来复用）；`--built <path>` 可以显式指一个文件。
   - 打印模式（`--print`）加 `--built-ms <ms>`，和 `--apk-ms` 一样。
4) `tests/agent/HandoffChecks.java`：加 fixture 用例，JS 与 Java 两边都跑、比字节：
   - (a) 没有单子、`builtMs > apkMs` → 下一步里有「编好了还没装」那一条；
   - (b) `builtMs == apkMs` → 没有装机提醒（「没有必须现在做的」）；
   - (c) 有一单合进来且比 `apkMs` 新、`builtMs` 也更新 → **只有一条**装机提醒，是老的那条；
   - (d) `apkMs == 0`、`builtMs > 0` → 那一条里写「电视上的是哪版不知道」。
   现有的用例全部保持原样通过。

## 规矩
- 只改：`tools/handoff/handoff.mjs`、`src/xyz/erapi0neer/xingyi/agent/Handoff.java`、`tests/agent/HandoffChecks.java`、
  `tests/agent/handoff-fixtures/` 下新增的 fixture 文件。
- `Handoff.java` 保持纯 Java（无 `android.*`）。
- 不碰电视、不发 adb。
- 中文注释继续中文。

## 验收
- `sh tests/agent/run_core.sh` 末行 `agent core: N passed, 0 failed`；
- `sh tests/agent/android_check.sh` 末行 `ANDROID CHECK OK`；
- `sh tests/agent/accept_handoff.sh` 末行 `HANDOFF OK`。
- 变异自检（做完再验，只回滚自己的改动）：把新规则删掉 → (a)(d) 必须红；把「最多一条」去掉 → (c) 必须红。
