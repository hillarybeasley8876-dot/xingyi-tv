# Brief: standby-home-events — 判据读系统的事件顺序，不读熄屏时永远是空的那个字段

## Why (2026-11-14 10:41，真机上刚发生的)

`tests/agent/realpath/scenarios/standby-home.py` 在真机上第一次自己跑，报的是「浏览器一次都没浮到最上面：守护没事可做，
这次验不了」。可系统自己的记录（`dumpsys usagestats`，原文存在 `tests/agent/fixtures/realpath/usagestats-standby-2026-11-14.txt`，
**一个字都没改**）说的是另一回事：

```
10:38:55 浏览器到前面 → 按 HOME → 桌面回到最上面
10:39:05 MOVE_TO_FOREGROUND com.phlox.tvwebbrowser      ← 熄屏那一刻浏览器浮上来了（和熄屏同一秒，排在它前面）
10:39:05 SCREEN_NON_INTERACTIVE
10:39:06 MOVE_TO_BACKGROUND com.phlox.tvwebbrowser      ← 1 秒后退到后台（熄屏后所有界面都暂停）
10:39:33 MOVE_TO_FOREGROUND xyz.erapi0neer.orrery         ← 守护把桌面放回去：熄屏后第 28 秒
10:40:47 SCREEN_INTERACTIVE / orrery                     ← 场景收尾重新点亮
```

**守护做对了，是场景看错了地方**：它熄屏后每 2 秒读一次 `p.resumed()`，读的是 `mResumedActivity`——熄屏时所有界面
都暂停，这个字段是空的；浏览器只浮上来 1 秒，第一次读（熄屏后 2 秒）时已经错过。

这是这个场景**第三次**栽在同一个坑里：11-12 守护自己先是拿「桌面进程死没死」当旁证，后来用 20 秒心跳**取样**漏掉了
1.2 秒的窗口，最后改成读系统按毫秒记下的事件顺序才对（`MemoryCare.surfacedAfter`）。场景的判据该读同一份记录。

顺带两件今天量出来的事：这台电视**不认** `KEYCODE_WAKEUP`(224)——10:17 按了没亮，用 `POWER`(26) 才亮；也**不认**
`KEYCODE_SLEEP`(223)——10:38 按了 5 秒还醒着，是退回 26 才熄的。场景现在先按 223/224 再退回 26，每次白等 5~8 秒，
还把「按 HOME → 熄屏」的间隔从真实情形的一两秒拉成了七秒。

## What to build

### 1. `tests/agent/realpath/probe.py` — 读系统的事件顺序

- `tv_datetime()`：电视自己的 `date '+%Y-%m-%d %H:%M:%S'`（usagestats 的时间是电视的本地时间、精确到秒）。
- `parse_usage(text, since)`：从 `dumpsys usagestats` 的文字里取 `MOVE_TO_FOREGROUND` / `MOVE_TO_BACKGROUND` /
  `SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE` 四种事件，`time ≥ since` 的，**保持原来的先后**（同一秒里的先后
  就是系统记的先后，不许重排），去掉一字不差的重复行。返回 `[{"at": "2026-11-14 10:39:05", "type": ..., "pkg": ...}]`。
  这是纯函数：fixture 文件的文字直接喂给它就能测。
- `usage_events(since)`：真机版——在电视上先用日期/小时前缀 `grep` 掉不相关的行再拉回来（整份 dump 很长），
  交给 `parse_usage`。
- `attempt(sid, notes)`：「这次验不了」时用。把 notes 逐句 `say()` 出来（跑的日志里看得见），再写一份
  `build/realpath-attempts/<sid>-<YYYYmmdd-HHMMSS>.json`（`{"id", "at", "notes"}`）。**不写 `tests/agent/evidence/`**：
  没验成就没有证据，可看到了什么不能丢。（今天这次场景返回 `None`，它 90 秒看到的东西一句都没留下——这份时间线
  是我事后从系统记录里拼回来的。）

### 2. `tests/agent/realpath/scenarios/standby-home.py` — 判据换成事件顺序

- 开浏览器之前记 `since = p.tv_datetime()`。
- 熄屏：只用 `KEYCODE_POWER`(26)，**只在醒着时按**；点亮：只用 26，**只在熄着时按**（26 是来回切的键，这两个
  前提一个都不能少）。docstring 写清 223/224 这台不认、哪天量的。按 HOME 和熄屏之间别人为拖长（`sleep(1.5)` 保留，
  223 那 5 秒没了）。
- 熄屏以后**静等**观察窗口（90 秒，守护心跳 20 秒，够两次以上），不再每 2 秒读 `resumed()`。
- 窗口结束、点亮之前：`ev = p.usage_events(since)`，判据和守护自己的规则一致（`MemoryCare.surfacedAfter`）：
  - `off` = `since` 之后第一个 `SCREEN_NON_INTERACTIVE`；**没有** → 屏幕没关成，`None`（验不了）。
  - 「浮上来了」= `[off - 1 s, off + 10 s]` 里第一个 `MOVE_TO_FOREGROUND`，包名**不是**桌面（`orrery`）；**没有** →
    守护没事可做，`None`。
  - 「放回去了」= 浮上来那一条之后、第一个桌面的 `MOVE_TO_FOREGROUND`；在 `off + 60 s` 以内 → **过**；没有、或者
    晚于 60 秒（比如只在重新点亮那一下才回来）→ **不过**。
  - notes 里逐行写下窗口里的事件（时间、类型、包名），再写一句判了什么。
- `ok is None` 时：`p.attempt(ID, notes)`，然后照旧 `could not run:` + 返回 3。

### 3. 三条等广告的场景也把「验不了」留下来

`pause-through-xiaoai.py`、`seek-one-minute.py`、`episode-open.py` 在 `main()` 里 `ok is None` 那个分支，返回 3 之前加
`p.attempt(ID, notes)`。**只加这一行**，别的一个字不动（它们此刻正排在电视上跑，合并会在两条之间进行）。

### 4. `tests/agent/realpath/rehearse.py` — standby-home 的局面按事件写

- 假 probe 加 `tv_datetime` / `usage_events` / `attempt`（`attempt` 只记不写盘）；`usage_events` 吃局面里给的事件表。
- standby-home 原来那几条局面是按「熄屏时 `resumed()` 看得见浏览器」写的——**这个前提在真机上不成立**，重写：
  1. 今天的真实时序（浏览器 10:39:05 浮上来、桌面 10:39:33 回来）→ `True`。**直接用 fixture 文件喂 `parse_usage`
     造出这条局面的事件表**，不要手抄。
  2. 浮上来了，桌面只在重新点亮时（熄屏后 102 秒）才回来 → `False`。
  3. 浮上来了，桌面熄屏后 62 秒回来 → `False`（逼到 60 秒那道界）。
  4. 熄屏后什么都没浮上来 → `None`。
  5. 根本没有 `SCREEN_NON_INTERACTIVE`（屏幕没关成）→ `None`。
- 变异验证（`build/` 下的副本）：把 60 改成 999，第 2、3 条必须红；把 `off - 1 s` 的「- 1」去掉（窗口从 `off` 起算），
  第 1 条必须红（今天浏览器那一条记在熄屏**之前**、同一秒）。两次输出写进报告。

### 5. `parse_usage` 的离线自测

`tests/agent/realpath/probe_by.py` 旁边那种单独能跑的小脚本：`python tests/agent/realpath/usage_check.py` →
`USAGE OK`。用 fixture 文件证：取出 11 行里那 4 种事件、先后不变、`since` 过滤对、重复行去掉；再喂几段坏文字
（空、乱码、没有 time= 的行）不抛异常。`tests/agent/accept_real_path.sh` 里加一行跑它。

## Rules
- 只碰：`tests/agent/realpath/probe.py`（只加上面四个函数）、`tests/agent/realpath/scenarios/standby-home.py`、
  `tests/agent/realpath/scenarios/pause-through-xiaoai.py` / `seek-one-minute.py` / `episode-open.py`（**各只加一行**）、
  `tests/agent/realpath/rehearse.py`、`tests/agent/realpath/usage_check.py`（新建）、`tests/agent/accept_real_path.sh`（只加一行）。
- **不碰** `tests/agent/fixtures/`（lead 的；fixture 已经放好了）、`tests/agent/evidence/`、`tests/agent/unverified.json`、
  `tests/agent/verify.sh`（此刻正在跑）、`tests/agent/RealPath*.java`、`src/`。
- 不开电视、不连 adb、不联网。

## Acceptance（自己全跑一遍再交）
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`，N ≥ 5836
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_real_path.sh` → `REALPATH OK`（含 `REHEARSE OK`、`USAGE OK`）
- `python tests/agent/realpath/rehearse.py` → standby-home 至少 5 行 `ok`，`REHEARSE OK`
