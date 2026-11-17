# Brief: wait-out-the-ad — 先等广告放完，再动手

## Why (2026-11-14 10:20，lead 读 11-12 凌晨那次真机日志时发现的；这一处是我上一张 brief 写错了)

云视听极光非会员开一集，正片前有「一两分钟广告」（工具自己的回答原话）。11-12 03:16 那次真机验证（证据
`tests/agent/evidence/s713-2026-11-12.txt`）之所以「暂停」「快进一分钟」都验过了，是因为手写探针 `session7m.py`
在开播后**干等了 124 秒**才开始动手：

```
[ 74.8] tvtask 「我想看别无选择第5集」 4.2 s
[ 74.8]     reply: 已在云视听极光播放《别无选择》第 5 集；不是会员的话正片前有一两分钟广告
[194.7] after the ad: state none          ← 广告刚结束那一下，音频服务里这个应用一个播放器都没有
[202.3]    overlay t-episode: key -> paused / key -> started
[209.2] xiaoai_say 「暂停」 6.7 s
[211.8] P 「暂停」 said to XiaoAi -> state: paused
```

现在三条注册场景都**没有**等广告：

- `pause-through-xiaoai.py`：开播后只等「音频服务说 started」——**广告本身就是 started**，于是「暂停」是在广告里说的。
  广告能不能被暂停、暂停了浮层会不会出来，都不知道（从来没量过）。在广告里没停住，它会写一份「没过」的证据，
  notes 还会说「小爱的语音助手和星仪的键可能又互相抵消了」——**一份指错原因的失败报告**。
  （`PLAYER_S, AUDIO_S = 25, 40` 那行注释写着「正片前有一两分钟广告，别缩短」，可 `AUDIO_S` 根本不等广告。
  这是我上一张 brief 写错了：我让它「照 seek-one-minute 那样等」，可 seek 自己也不等。）
- `seek-one-minute.py`：浮层读 `TRIES, GAP_S = 6, 10` 次，大约一分半就放弃；广告两分钟就判「没过」。
- `episode-open.py`：`for i in range(8)` 加 `CAP_S = 170`，每一圈都在广告里按暂停、读浮层；广告要是被暂停拉长，
  170 秒正好擦边。

真机一次 20 分钟窗口；判错一次不光白费这 20 分钟，还会把一份真的证据换成一份指错原因的失败。

## What to build

### 1. `tests/agent/realpath/probe.py` — 一个等广告的助手

`wait_for_show(notes, cap_s=240, quiet_s=100, every_s=15)`：从「播放页在前、音频服务在放」那一刻起调它。

- 前 `quiet_s` 秒**一个键都不按**（广告里按暂停可能把广告拉长，也可能根本不认）；这段时间每 5 秒读一次
  `audio_state()`，只有**状态变了**才往 `notes` 记一句（`t+NN s 音频服务说 xxx`）——广告→正片那一下的 `none`
  就是这么被看见的。
- 之后每 `every_s` 秒：`play_pause("paused")`、等 1.2 秒、`ui_text()`、`play_pause("started")`；浮层文字里有
  `\d\d:\d\d:\d\d`（正片的进度）就算正片在放了，返回 `{"at": 秒, "text": 浮层文字, "resumed_at": 继续放的时刻}`。
  每一次看浮层都往 `notes` 记一句（第几次、多少秒、看到什么，文字截 100 字）。
  `play_pause` 回来 `no key` / `still none`（正好卡在广告→正片那一下）不算错，下一圈再看。
- 到 `cap_s` 还没看到进度：往 `notes` 记一句「广告一直没放完（等了 N 秒，浮层上一次都没出现进度），这次验不了」，
  返回 `None`。**不猜**：没看到进度就是没看到。
- docstring 写清：为什么先静等、为什么不靠音频服务判断广告完没完（广告和正片在音频服务里都叫 started），
  以及 s713 那段时间线。

### 2. 三条场景改成「先等广告，再动手」

- **`pause-through-xiaoai.py`**：开播（`r.started(p.KTCP)` 之后、`tvtask` 之前）先 `p.set_volume(3)`（广告响，
  `Restore` 会放回去，episode-open 一直就是这么做的）；播放页在前、音频在放之后调 `p.wait_for_show(notes)`；
  `None` → `check()` 返回 `(None, notes, shots)`。`main()` 照 `standby-home.py` / `episode-open.py` 的样子：
  `ok is None` → `p.say(ID, "could not run:", notes[-1])`、返回 3、**不写证据**。之后才读 `before`、说「暂停」，
  判据不变。顺手把 `AUDIO_S` 那行错的注释改对。
- **`seek-one-minute.py`**：同样先压音量、再 `wait_for_show`；`None` → 同上「这次验不了」。看到正片之后用现有的
  `overlay(notes, shots, "before")` 读一次「之前」（它会再暂停一下，没关系，`walked` 会把两次暂停之间片子自己走的
  时间扣掉）。原来的 `TRIES / GAP_S` 循环删掉。判据（「已快进」+ 差 45–90 秒）不变。
- **`episode-open.py`**：保留它自己的判据（浮层上有「第5集」→ 过；有《别无选择》却没有第 5 集 → 不过；
  一次都没提到这部剧 → 验不了），但「等广告」换成 `wait_for_show`：拿到正片以后看它返回的浮层文字，必要时再按
  原来的方式看一两次（每次之间 15 秒）。`CAP_S` 跟着 `wait_for_show` 的 `cap_s` 走，不再各算各的。

`standby-home.py`、`sleeping-episode.py` 和别的场景**一个字都不动**（standby-home 此刻正在电视上跑）。

### 3. `tests/agent/realpath/rehearse.py` — 假 probe 也要会等广告

- 假 probe 加一个 `wait_for_show`：按局面里的事实走，用局面自己的假钟（`rehearse-clock` 已经让钟真的走了）。
  局面可以写「广告在第 N 秒放完」（`show_at`）或者「广告一直没放完」（不写 / `None`）；到点以后返回带进度的浮层
  文字（`ui_text_seq` 的写法就行，进度用 `00:02:46` 这种）。
- 三条场景各加一条**「广告一直没放完」→ `expect: None`**；原来那些「该过」的局面要把广告放完的时刻写进去，照样过。
- 变异验证（`build/` 下的副本，**不碰真文件**）：把 `pause-through-xiaoai.py` 里 `wait_for_show` 那一行删掉，
  「广告一直没放完」那条局面必须红（它会在广告里说暂停，判出 True 或 False，而不是 None）；改回来必须绿。
  seek 也做一次同样的变异。两次的输出写进报告。

## Rules
- 只碰：`tests/agent/realpath/probe.py`（只加 `wait_for_show` 和它要用的小东西，别的函数一个字都不动）、
  `tests/agent/realpath/scenarios/pause-through-xiaoai.py`、`tests/agent/realpath/scenarios/seek-one-minute.py`、
  `tests/agent/realpath/scenarios/episode-open.py`、`tests/agent/realpath/rehearse.py`。
- **不碰** `standby-home.py`、`sleeping-episode.py` 和其它场景、`tests/agent/evidence/`、`tests/agent/unverified.json`、
  `tests/agent/fixtures/`、`tests/agent/verify.sh`、`tests/agent/RealPath*.java`。
- 不开电视、不连 adb、不联网。三条场景只在 rehearse 的假 probe 下跑。

## Acceptance（自己全跑一遍再交）
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`，N ≥ 5836
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_real_path.sh` → `REALPATH OK`（含 `REHEARSE OK`）
- `python tests/agent/realpath/rehearse.py` → 至少 41 行 `ok`（原来 38 + 三条「广告一直没放完」），`REHEARSE OK`
- `python tests/agent/realpath/check.py --strict` → `REALPATH OK`
