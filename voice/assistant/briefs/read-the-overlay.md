# Brief: read-the-overlay — 浮层的字读不到，但画面读得到

## Why (2026-11-14 11:10，真机上刚量出来的)

`pause-through-xiaoai` 今天在真机上跑，`wait_for_show` 等了 **247 秒**，notes 记的是「广告一直没放完（浮层上一次都没出现进度）」。
可同一时间系统自己的记录说广告 11:04:13 就结束了（`player piid:127 released` → 新播放器 135 起来），正片一直在放。

根因今天查清了：**云视听极光的播放页整页是 SurfaceView 画的，`uiautomator dump` 在上面只给一个空节点**。手写探针
`assistant-out/tmp/lead/k1114/overlay_probe.py` 在正片里每 20 秒暂停一次、dump 一次，15 次全部一样：

```
dump status: UI hierchary dumped to: /sdcard/ui.xml | nodes 1 | texts 0 | descs 0
```

可**屏幕上明明有字**：左下 `00:05:30`、右下 `00:47:13`、左上 `别无选择 第5集`。也就是说：

- `probe.ui_text()` 在播放页永远返回空串；
- `pause-through-xiaoai` / `seek-one-minute` / `episode-open` 三条的判据全押在 `ui_text()` 上，**从写出来那天就不可能成功**；
- 11-12 那次能读出「00:02:46 → 00:03:48」，是我用眼睛看截图读的，不是脚本读的。

同一类错的第三次：判决要读的东西，得真的是脚本能读到的。这一次的读法是**读画面**——截图里的字是固定的字体、固定的位置。

lead 已经把这 11 帧存成 fixture（`tests/agent/fixtures/realpath/player/`，960×540，一共 2.2 MB，已提交）：
9 帧有进度条（人手读出的位置写在 `ground-truth.json` 的 `position_s`，`duration_s` 是 2833），2 帧没有（`position_s: null`，
广告卡片挡住 / 还没进正片）。**这批 fixture 和 ground-truth 不能改**——它们是判卷标准。

## What to build

### 1. `tests/agent/realpath/screen.py`（新建，纯函数 + 一个命令行）

读一帧截图，回答三件事。只依赖 `PIL` 和 `numpy`（这台机器上都有：PIL 10.4.0），**不装 OCR、不联网**。

- `bar(path)` → `{"x0": 左端, "x1": 填充右端, "x2": 轨右端, "y": 基线}` 或 `None`
  进度条是左下那一行：左边一个播放三角、`00:MM:SS`、一条亮线（已看过的部分）接一条暗线（没看的），右边的
  `00:47:13` 是总长。整帧宽 960。做法：只在下面约 12% 的那一条里找「一条水平的高亮线」，全亮像素 ≥ 40 的一段，
  取它的左右端；找不到就是 `None`。阈值、行范围写成常量，注释里写清这一帧 960 宽、原分辨率 1920。
- `position(path)` → `{"s": 秒数, "bar": ..., "since": "00:04:01", "total": "00:47:13"}` 或 `None`
  先用 `bar()` 拿到 `x0 / x1 / x2`，比例就是 `(x1 - x0) / (x2 - x0)`，乘 `duration_s` 就是秒数。**总长从哪来**：
  右下角 `00:47:13` 那几个字也读不出来（同一张图、同一套字），所以 `duration_s` 由调用方传（场景里就是 2833，
  这集的长度在 `ground-truth.json` 里）；不给就只能返回比例，`s` 为 `None`。返回里 `since`/`total` 两个字段
  **先写成 None**——标题和时长都靠第 3 条那个模板比对，这一版不做数字 OCR（见下）。
- `has_show_text(path)` → `True / False`
  判断画面里有没有 `别无选择 第5集` 那一行字（左上角）。做法：和 fixture 里一帧已知有它的
  （`player-00h04m01.png`）在**同一块区域**做模板比对（`numpy`，灰度，缩放到同一尺寸之后比相关性/差异比例），
  超过阈值算有。广告卡片挡住的时候那块区域是别的画面，相关性会掉下去。阈值拿这 11 帧定，写进注释。
- 命令行：`python tests/agent/realpath/screen.py <png>` → 打印 `bar=… position_s=… has_show_text=…`。
  给场景之外的调试用。

**不要做数字 OCR**。lead 试过「按列切字形 + 模板匹配」，9 帧 54 个字形里错 6 个（`2` 和 `4` 在某一帧长得几乎一样），
不可靠。进度条比例没有这个问题：2833 秒铺在一条线上，一分钟就是 2.1%，一帧 960 宽、轨道约 700 px，一分钟约 15 px，
而这条线是纯色、边界锐利，亚像素抖动不影响。**判据要的是「差了多少秒」，不是「现在几分几秒」。**

### 2. `tests/agent/realpath/screen_check.py`（新建）— 用 fixture 判卷

`python tests/agent/realpath/screen_check.py` → `SCREEN OK`。对 `ground-truth.json` 里每一帧：

- 有 `position_s` 的 9 帧：`position()` 读出的秒数和它差 ≤ 4 秒（一分钟 15 px，四秒约 1 px，放宽到一像素）；
  `bar()` 不是 `None`；`has_show_text()` 为 `True`。
- `position_s` 是 `null` 的 2 帧：`position()` 是 `None`（没有进度条），`has_show_text()` 为 `False`（至少
  `player-no-bar-01.png` 必须是 False——另一帧 `player-no-bar-14.png` 如果是 True 就说明它其实已经进了正片而没有进度条，
  那时把它的要求改成「只看 `position()` 是 None」，并在报告里写清为什么）。
- 一条都不过就打 `FAIL <帧>：…` 并退出 1；全过打 `SCREEN OK`。

### 3. 三条场景改成读画面（这次只改「怎么读」，判据不动）

- `probe.py`：把 `ui_text()` 的 docstring 改对——它在播放页读不到东西（SurfaceView），要读播放页就用
  `screen.position()`；再加一个 `frame()`：`screencap` 到电视上再 `pull` 回来，返回本地临时 png 的路径
  （`tests/agent/realpath/` 下一份 `frame-<时间戳>.png` 也行，落在 `build/` 下更好，别进仓库）。
- `pause-through-xiaoai.py`：`wait_for_show` 那一步不要再等 `ui_text()`——等 `screen.position(frame)` 能读出进度
  （就是「正片在放」），或者 `screen.has_show_text(frame)` 为真。改法尽量小：`wait_for_show` 现在是 `probe.py` 里
  的函数，把它改成用「截图 + screen.py」判断；三条场景调它的地方不用动。
- `seek-one-minute.py`、`episode-open.py`：它们自己的 `overlay()` / `look()` 用 `ui_text()` 读浮层的地方，
  换成先 `frame()` 再 `screen.position()` / `screen.has_show_text()`。判据（「已快进」+ 差 45–90 秒；浮层上有第5集）
  一个字不改，**`episode-open` 的「浮层上写着第 5 集」改成 `has_show_text()`**（模板里那行字就是「别无选择 第5集」，
  而它要判的正是这一集，不是别的）。
- `rehearse.py`：假 probe 里 `ui_text` 旁边加 `frame`（返回 fixture 里某一帧的路径，局面写 `frame_seq`），
  `wait_for_show` 的假实现改成按局面给的帧序列走。原来三条「广告一直没放完」的局面改成用「一直是 no-bar 的帧」。

### 4. 变异验证（自己跑，写进报告）

把 `screen.py` 里进度条的行范围或阈值改坏（`build/` 副本），`screen_check.py` 必须红并点名帧；改回来必须
`SCREEN OK`。再验一条：把 `ground-truth.json` 里某帧的 `position_s` 加 10 秒，必须红（证明判卷真的在看数）。

## Rules
- 只碰：`tests/agent/realpath/screen.py`（新）、`tests/agent/realpath/screen_check.py`（新）、
  `tests/agent/realpath/probe.py`、`tests/agent/realpath/scenarios/pause-through-xiaoai.py` /
  `seek-one-minute.py` / `episode-open.py`、`tests/agent/realpath/rehearse.py`、`tests/agent/accept_real_path.sh`（加一行 `SCREEN OK`）。
- **不碰** `tests/agent/fixtures/`（fixture 和 ground-truth 是判卷标准）、`tests/agent/evidence/`、
  `tests/agent/unverified.json`、`tests/agent/verify.sh`、`tests/agent/RealPath*.java`、`src/`。
- 不开电视、不连 adb、不联网。`screen.py` 只吃本地 png。

## Acceptance（自己全跑一遍再交）
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`，N ≥ 5836
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_real_path.sh` → `REALPATH OK`（含 `SCREEN OK`、`REHEARSE OK`、`USAGE OK`）
- `python tests/agent/realpath/screen_check.py` → `SCREEN OK`，11 帧逐帧的读数误差打出来
