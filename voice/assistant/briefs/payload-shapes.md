# Brief: payload-shapes — 场景从广播里读的每个键，离线就要能核对它在真机上存在

## Why (2026-11-12 22:47，白跑了一次真机窗口)

`tests/agent/realpath/scenarios/hint-card-not-a-switch.py` 写的是 `p.state().get("hints")`。可 `AgentEngine.state()`
从来不放 `hints`——它在 `memory()` 里（`MEMORY` 广播）。于是那条场景前后两次都读到 `None`，**无论电视怎么表现都不可能
通过**；22:47 真机跑掉一次 20 分钟窗口才发现，而当时 `python tests/agent/realpath/check.py --strict` 一路
`REALPATH OK`。

`check.py` 只看注册表和证据文件的形状，读不出「探针问的字段在真机上有没有」。这一类错可以离线核对：把电视上两路
广播**带的键**（只要形状，不要内容）录成 fixture，检查器比对每个场景里 `p.state()` / `p.memory()` 之后读的键。

lead 已经录好了 fixture：`tests/agent/fixtures/realpath/payload-shapes.json`
（`{"STATE": {...keys...}, "MEMORY": {...}}`，值一律 `null` / 容器形状，没有一句用户内容）。**不要改它**——它是
2026-11-13 01:52 在真机上抓的；要更新是 lead 的事。

## What to build

### 1. `tests/agent/realpath/check.py` — 读的键必须在形状里

新函数 `shape_problems(root)`，并进 `problems()`（是**问题**，不是 notice：`--strict` 加不加都算错）：

- 读 `tests/agent/fixtures/realpath/payload-shapes.json`；读不出来 → 一句
  「payload-shapes.json 读不出来（形状 fixture 不在或者不是 JSON），没法核对场景读的字段」。
- 对 `tests/agent/realpath/scenarios/*.py` 每个脚本做**文本扫描**（不要 exec，不要 import）：
  找出 `p.state()` 或 `p.memory()` 之后紧跟的 `.get("<键>")` 或 `["<键>"]`（允许中间有空白/换行），
  键必须出现在 fixture 对应那一路的**顶层**键里。缺了 → 一句
  「`<脚本>`：从 `<STATE|MEMORY>` 里读「<键>」，可那个广播不带这个键（真机形状里只有 a、b、c…）；2026-11-12
  22:47 就是这么白跑了一次」。
- 变量再读（`s = p.state()` 然后 `s.get("x")`）不在这一版的范围里：**别猜**，扫不到就不说。这一条在 docstring 里
  写明，免得以后有人以为它查得比实际多。
- 同一条错误只报一次（两个场景读同一个不存在的键，报两句——每条点名自己的脚本）。

### 2. `tests/agent/RealPathChecks.java` — 至少 8 条新检查

- 读的键在形状里 → 一句都不说；
- 读 `STATE` 里没有的键 → 一句，点名脚本和键；
- 读 `MEMORY` 里有的键 → 一句都不说（`hints` 这个具体例子）；
- `.get("x")`、`["x"]`、`.get( "x" )`（带空格）三种写法都认；
- 形状文件读不出来 → 一句说读不出来，不抛异常；
- 仓库里真正那一份：`problems()` 干净（跑真树）；
- 脚本里根本不碰这两路广播 → 一句都不说；
- 变量再读（`s = p.state(); s.get("x")`）→ 一句都不说（写明了不做，就别假装做了）。

### 3. 变异验证（自己跑，证据写进报告）

把 `tests/agent/realpath/scenarios/hint-card-not-a-switch.py` 的 `p.memory()` 改回 `p.state()`（在 `build/` 的副本上，
或用临时脚本），`check.py` 必须红并点名 `hints`；改回来 → `REALPATH OK`。

## 规则
- 只碰：`tests/agent/realpath/check.py`、`tests/agent/RealPathChecks.java`。
- **不碰** `tests/agent/fixtures/realpath/payload-shapes.json`、`tests/agent/realpath/scenarios/*`、
  `tests/agent/evidence/`、`tests/agent/unverified.json`。
- 不开电视、不连 adb、不联网。

## Acceptance（自己全跑一遍再交）
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`，N ≥ 5696
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_real_path.sh` → `REALPATH OK`
- `python tests/agent/realpath/check.py --strict` → `REALPATH OK`（真树）
