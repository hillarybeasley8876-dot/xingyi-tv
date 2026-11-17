# Brief: ledger-vs-registry — 账本和注册表必须说的是同一件事

## Why (2026-11-12 21:40, 我自己犯的)

`tests/agent/unverified.json` 是「装上了、但还没在真机上验过」的唯一账本：`NEXT.md` 的「还没验的」、`bridge.sh status`
的 `unverified:` 行、`status/HANDOFF.md` 的「还没在真机上验过的」三处全从它生成。今天它空了，三处就都说
「一条都没有：装上的东西都在真机上见过」。

可 `tests/agent/realpath/scenarios.json` 里十条场景有三条从 69156e0（11-12）建仓起**一次真机都没跑过**，
证据文件也不存在：`panel-self-check`、`black-screen-home`、`hint-card-not-a-switch`。空账本的意思只是「没人写」，
不是「都验完了」。我刚刚手工把三条补进了账本（8566830），但手工补的下次还会漏——`UnverifiedChecks` 里那张
`known` 白名单就是这么漏的：注册表加场景时没人想起账本。

规则要机器来守：**注册表里的场景，没有证据文件就必须有一条待办；待办对应的场景证据已经 ok=true，待办就必须删掉。**
两头都卡，两个文件就不可能各说各话。

## What to build

### 1. `tests/agent/realpath/check.py` — 两头都查（不靠 `--strict`）

新函数 `todo_problems(root)`（或并进 `problems()` 末尾，名字随意但 docstring 要写清规矩），读
`tests/agent/realpath/scenarios.json`、`tests/agent/evidence/real-<id>.json`、`tests/agent/unverified.json` 三份：

- 场景 `<id>` 没有 `evidence/real-<id>.json`：
  - 注册表里它的 `unverified` 是空串 → 一句
    「`<id>` 还没有证据文件，scenarios.json 里它的 unverified 是空的（没跑过的场景要指着一条待办）」；
  - `unverified` 有值但 `unverified.json` 读不出来 → 一句，说读不出来；
  - `unverified` 有值、但不在 `unverified.json` 的 entries[].id 里 → 一句
    「`<id>` 还没有证据文件，可 unverified「x」不在 unverified.json 里」。
- `evidence/real-<id>.json` 存在且 `ok` 是 `true`、而 `unverified.json` 里还有 id 为 `<id>` 的条目 → 一句
  「`<id>` 已经验过了（real-<id>.json 是 ok:true），待办该删掉（跑 tests/agent/verify.sh --run 会自己删）」。
- 证据存在但 `ok` 是 `false`：由现有的 `link_problems` 管，这条不重复说。
- 这三句是**问题**（`problems()` 的返回值），不是 `WHERE` 那种 notice：`--strict` 加不加都算错。

docstring 的规矩清单里加一条，带上 2026-11-12 的由来（一句话）。

### 2. `tests/agent/realpath/scenarios.json` — 三条的 `unverified` 指到自己的待办

`panel-self-check` / `black-screen-home` / `hint-card-not-a-switch` 的 `unverified` 现在是 `""`，改成和 id 一样的
字符串（账本里那三条的 id 就是场景 id）。其余七条一个字不动。

### 3. `tests/agent/RealPathChecks.java` — 至少 8 条新检查

- 一个没跑过、`unverified` 空着的场景 → 一句，句子点名场景 id；
- 没跑过、`unverified` 指着账本里没有的 id → 一句，点名那个 id；
- 没跑过、`unverified` 指着账本里真有的 id → 一句都不说；
- 账本读不出来（不在 / 没有 entries / 不是 JSON）→ 各有清楚的句子，不悄悄放过；
- 有证据且 ok:true、账本里还留着这条 → 一句说该删掉；
- 有证据且 ok:false → 这条规矩不说话（由 `link` 管）；
- 有证据且 ok:true、账本里没有它 → 一句都不说；
- 仓库里真正那一份：`problems()` 现在是干净的（这条跑真树，注意别把 fixtures 的临时树当成真树）。

### 4. `tests/agent/UnverifiedChecks.java` — 白名单改成从注册表来

`sound()` 里那张手写的 `known`（9 个 id）删掉，改成从 `tests/agent/realpath/scenarios.json` 读场景 id；
账本里出现一个既不是场景 id 的条目 → 检查失败，句子说清「这条待办没有对应的场景（要么注册一个，要么把这条
从账本里删掉）」。注册表读不出来 → 检查失败，句子说清是哪个文件读不出来（别抛异常）。
这样以后注册表加场景，账本忘了加，`run_core.sh` 当场红。

### 5. 顺手：`tests/agent/accept_real_path.sh` 把这条规矩也跑一遍

不用加命令——`check.py --strict` 已经把它算进去了。只确认它的注释/输出没说错话（它现在说「加 --strict 时
不认放错地方的证据」，新规矩不加 `--strict` 也算错，别让它的话变成假的）。

## 规则
- 只碰：`tests/agent/realpath/check.py`、`tests/agent/realpath/scenarios.json`、`tests/agent/RealPathChecks.java`、
  `tests/agent/UnverifiedChecks.java`、`tests/agent/accept_real_path.sh`（只在必要时改注释）。
- **不碰** `tests/agent/unverified.json`、`tests/agent/evidence/`、`NEXT.md`、`status/`：前两个是 lead 的，
  后两个是生成的。要试写账本就在 `build/` 下的副本上试。
- 不开电视、不连 adb、不联网。
- 不改 `scenarios.json` 里其余七条的任何字段。

## 不在这一单（lead 的）
账本内容、真机跑那三条场景、`NEXT.md` / `status/` 重新生成。

## Acceptance（自己全跑一遍再交）
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`，N ≥ 5652
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_real_path.sh` → `REALPATH OK`
- `sh tests/agent/accept_verify_durable.sh` → `VERIFY DURABLE OK`
- 变异验证：把 `scenarios.json` 里 `panel-self-check` 的 `unverified` 改回 `""` → `check.py` 必须红并点名它；
  改回来 → `REALPATH OK`。（在 `build/` 的副本上做，别动真树。）
