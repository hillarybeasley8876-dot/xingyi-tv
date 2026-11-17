# Brief: rehearse-clock — 时间也能当局面：把「亮了，可是太晚」这一档逼出来

## Why (2026-11-14 03:05，`judge-rehearsal` 自己在 `## Requests` 里报的)

`tests/agent/realpath/rehearse.py` 现在能表达「一直没亮」和「一说就亮」，**表达不了「第 16 秒才亮」**——因为假的
`wait_for(pred, secs, step)` 只按 `secs/step` 的次数反复求 `pred()`，一次都不拨钟；局面里的序列是「每叫一次拨一格」，
分不出「第 3 次问的时候」和「第 30 次问的时候」。

后果是真的：`sleeping-episode` 的判据是 `p.wait_for(p.wake, 15, 0.3)` 和 `p.wait_for(PLAYER in resumed, 45-..., 0.5)`，
它的「15 秒」只在 `wait_for` 放弃的那一刻起作用。把 `WAKE_S, PLAYER_S = 15, 45` 改成 `150, 450`（也就是判据宽了十倍），
`rehearse.py` 照样 `REHEARSE OK`——这条判据的时间边界**一次都没被逼到过**。一个永远判过的判据和一个永远判不过的
判据一样坏，只是更难发现。

## What to build

### 1. `tests/agent/realpath/rehearse.py` — 假钟真的走

- 假 `wait_for(pred, secs, step)`：每求值一次 `pred()` **之前**把那个局的 `Clock` 往前拨 `step`（不是求完拨；第一次
  求值在 t=0，第二次在 t=step，依此类推）；`pred()` 为真立刻返回，次数用尽返回 `None`。`secs` 不再是次数，是
  **秒**：最多求值 `ceil(secs/step)+1` 次左右（取整方式写进 docstring，别留含糊）。
- `Seq` 加一个**时间门**：一条局面可以写成 `"resumed_seq": [{"at": 0, "v": BROWSER_ACT}, {"at": 16.0, "v": HOME_ACT}]`
  ——问的时候钟还没到 `at` 就发**上一个**已经到点的值（第一次之前发 `fallback`）；到点了就发它。老写法（一串裸值）
  **保持现在的行为**（每叫一次拨一格），否则现有 35 条局面全要重写——两种写法都要支持，docstring 写清。
- 假 `time.sleep()` 现在也是真的拨钟（`Clock.sleep`），保持；`mod.time = Clock()` 那行不动。

### 2. 补三条局面（`CASES`）

- `sleeping-episode` /「第 16 秒才亮（太晚）」/ `expect: false`：`awake_seq` 用时间门，`at: 16.0` 才变 `True`；
  `resumed_seq` 一开始就是 `PLAYER_ACT`。**在真机上这等价于 `WAKE_S` 超时**，判据必须判不过。
- `sleeping-episode` /「第 3 秒就亮了」/ `expect: true`：`at: 3.0` 才 `True`，播放页 `at: 12.0` 到前面——这是真机
  那次（h3.py 11:06）的时序。
- `sleeping-episode` /「亮了，可播放页第 60 秒才到（太晚）」/ `expect: false`：`awake_seq` 一开始 `True`，
  `resumed_seq` 门在 `60.0`。

### 3. 变异验证（必须做，写进报告）

在 `build/` 下的副本上把 `scenarios/sleeping-episode.py` 的 `WAKE_S, PLAYER_S = 15, 45` 改成 `150, 450`：
- 改之前（新局面加好、代码没改坏）：`REHEARSE OK`；
- 改坏之后：`rehearse.py` **必须红**，而且红的必须正是「第 16 秒才亮」那条（点名它）。
- 改回来必须绿。
顺便再验一次 `standby-home` 的 `WITHIN_S = 60`（报告说它本来就有洞没有；确认一下），结果写进报告。

### 4. 顺手把 `RealPathLedgerChecks.java` 拆了（538 行）

按它自己报的那份清单：`rehearse()` / `sides()` / `rehearseOnTree()` / `CASE` / `EXPECT` / `REHEARSE_UNREADABLE`
和那三个 check 方法整块搬到新文件 `tests/agent/RealPathRehearseChecks.java`（`final`、构造私有、`static void run()`），
`RealPathChecks.run()` 里加一行调用。**检查总数一条都不许变**（现在 5836）。

另外把 `RealPathChecks.java` 末尾那段挂错的 javadoc（335–350 行，本来是 `RealPathEvidenceChecks` 的类注释）
挪到 `RealPathEvidenceChecks.java` 的类头上。

## Rules
- 只碰：`tests/agent/realpath/rehearse.py`、`tests/agent/RealPathLedgerChecks.java`、
  `tests/agent/RealPathRehearseChecks.java`（**新建**）、`tests/agent/RealPathChecks.java`、
  `tests/agent/RealPathEvidenceChecks.java`（只接收那段 javadoc）、`tests/agent/realpath/check.py`
  （如果局面表的文本扫描要认时间门写法就改；不认也行——局面表是 Python 不是文本，别硬塞）。
- **不碰** `tests/agent/realpath/scenarios/*.py`（变异验证在 `build/` 副本上做）、`tests/agent/realpath/probe.py`、
  `tests/agent/evidence/`、`tests/agent/unverified.json`、`tests/agent/fixtures/`。
- 不开电视、不连 adb、不联网。

## Acceptance（自己全跑一遍再交）
- `sh tests/agent/run_core.sh` → `agent core: 5836 passed, 0 failed`（数字必须一样）
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_real_path.sh` → `REALPATH OK`（含 `REHEARSE OK`）
- `python tests/agent/realpath/rehearse.py` → 至少 38 行 `ok`，`REHEARSE OK`
- `wc -l tests/agent/RealPath*Checks.java` → 每个 ≤ 500 行
