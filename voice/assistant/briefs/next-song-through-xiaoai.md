# Brief: next-song-through-xiaoai — 对小爱说「下一首」：歌真的换了，而且只换一首

## Why (2026-11-18)

2026-11-16 19:30 真机：网易云在放林俊杰的《江南》，用户说「下一首歌」。小爱自己下发了 `PlaybackController.Next`，
可网易云**没动**（16 秒后用户再开口时还是《江南》）。星仪想自己切，被「小爱已经为这句话切过了，再切一次会多跳一个」
拦住，最后回答「小爱已经切过了，现在还是网易云在放林俊杰的《江南》」——自相矛盾，用户只好说「就是现在的歌然后下一首」。
同一个指令在云视听极光上是到了的（11-11「下一集」停在第 6 集，没跳两集），所以光看指令名说明不了切没切。

修在 `6d32cf8`（只有离线证据）：

- `MediaSessions.xiaoaiSkip(atRequest, now, requestElapsed, nowElapsed)`：换了曲目 = LANDED；同一首、而且在请求之前
  3 秒以上就在放了 = MISSED；别的（暂停、没有位置、没有 session、请求前后才开始的一首）= UNKNOWN，照旧相信小爱。
- `TvTools` 的 `media_control` 门：只有 MISSED 才放星仪的 next/previous 过去（云视听极光在前时永远不放）；按键之前还会
  等到请求后 4 秒，小爱的那一下要是晚到了，就不按（`skipLandedLate`，结果里写「小爱的那一下切过去了，星仪没有再切」）。
- `AgentEngine` 记下每个交过来的请求是什么时候到的（`arrived`），排队的请求也从小爱动手那一刻算。

「对小爱说下一首，网易云的歌换了、而且只换一首」这件事**在真机上没有证据**。按这个仓库的规矩：没在真机上验过的说法
进账本，配一条真机场景去验。这一单就做这条场景。**这一单不开电视**：场景写好、离线演练过、进了账本就交；装机和真跑
由 lead 等用户点头以后做。

## What to build

### 1. `tests/agent/realpath/scenarios/next-song-through-xiaoai.py`（新文件）

照 `pause-through-xiaoai.py` / `karaoke-switch.py` 的写法（文档字符串四段：前置条件、做什么、算过、收尾；`check(r)`
返回 `(ok, notes, shots)`，`ok is None` = 这次验不了、不写证据）。

- **前置条件**：`p.refuse()`（亮屏、桌面在前、20 分钟没人动）；不满足返回 3，一个字节都不碰电视。
- **开播**：`r = p.Restore(apps=[NETEASE])`，开之前 `r.started(NETEASE)`；音量压到 3（`p.set_volume(3)`，Restore 放回去）。
  用 `p.run_tool("tvtask.py", "放林俊杰的歌", 40)` 放一串（只给歌手 = 连续播放一串，「下一首」才有地方去）。
  等 `p.session(NETEASE)` 的 `state == 3`（在放），再**多放 10 秒**（同一首），让「小爱的切歌没到」能从位置上看出来：
  门要的是「请求之前 3 秒以上就在放」。开播没起来（25 秒里一直不是 3）：返回 `False`，notes 写清卡在哪一步——这是
  真跑了没跑成，和「现在不能跑」不是一回事。
- **说「下一首」**：记下 T1 = 当前 session 的 `meta`（标题、歌手、专辑那一串）。用 `p.start_tool("xiaoai_say.py", "下一首", …)`
  **不阻塞地**把这句话说给小爱（真实路径：小爱自己的指令照样会下发），同时每 0.3 秒读一次 `p.session(NETEASE)`，
  从说之前一直读到说完 15 秒，把**标题变化的序列**记下来（去掉连续重复的），最后 `p.finish(proc)` 拿到星仪的回答。
- **算过**：说完以后标题**恰好变了一次**（T1 → T2，之后不再变），最后读到的 session 在放（`state == 3`）而且是 T2。
  - 一次都没变：`False`，notes「没切过去：小爱的切歌没到，星仪也没切」——这就是 11-16 的样子。
  - 变了两次或更多：`False`，notes「多跳了：…」，把序列写进去。
  - 中途网易云停了 / session 没了：`False`，写清楚。
  - notes 里都带上星仪的回答（最后两行 `reply:` / `error:`，照 `pause-through-xiaoai.py` 的 `said()`）和标题序列。
- **收尾**：`r.close()`（停网易云、音量放回、回桌面、把说给小爱的话用 FORGET_CHECKS 从历史里拿掉）。

### 2. `tests/agent/realpath/rehearse.py` — 假 probe 支持 `session()`，加这条场景的局面

假 probe 现在没有 `session`。加一个，由局面的 facts 驱动（比如 `session_seq`：按假时钟的时间点给出 session，或者按
调用次数拨格——你挑一种，和场景里「每 0.3 秒读一次、读 15 秒」对得上就行，写清楚注释）。`start_tool` / `finish` /
`run_tool` 在假 probe 里已经有的就照用，没有就补上（不碰真 probe）。

至少这几条局面，`expect` 写对：

1. 小爱的切歌到了（或星仪切的），标题变一次、之后一直是 T2 在放 → `True`
2. 11-16 的样子：说完 15 秒标题一直是 T1 → `False`
3. 多跳了：T1 → T2 → T3 → `False`
4. 开播没起来：session 一直不是 3 → `False`
5. 说完网易云停了（state 变 2 或 session 没了）→ `False`

`python tests/agent/realpath/rehearse.py` 必须 `REHEARSE OK`，输出里能看到这条场景的每一条局面。

### 3. 登记

- `tests/agent/realpath/scenarios.json`：加一条 `{"id": "next-song-through-xiaoai", "what": …, "needs": ["亮屏", "桌面在前",
  "20 分钟没人动"], "script": …, "hours": 2, "unverified": "next-song-through-xiaoai"}`（照别的条目，`RealPathChecks` 会查格式）。
- `tests/agent/unverified.json`：加**一条**待办 `{"id": "next-song-through-xiaoai", "feature": "对小爱说「下一首」：网易云的歌换了，而且只换一首",
  "why": "6d32cf8 只有离线证据（MediaSessions.xiaoaiSkip 的 14 条检查）；11-16 19:30 真机上小爱的切歌没到、星仪被拦住。要装上带 6d32cf8 的包以后跑",
  "needs": […同上…], "since": "2026-11-18", "script": …}`。别的条目不动（现在是空的）。

## Rules

- 只碰：`tests/agent/realpath/scenarios/next-song-through-xiaoai.py`（新）、`tests/agent/realpath/rehearse.py`、
  `tests/agent/realpath/scenarios.json`、`tests/agent/unverified.json`。
- **不碰** `src/`、`assets/`、`tests/agent/realpath/probe.py`、`tests/agent/verify.sh`、`tests/agent/evidence/`、
  `tests/agent/fixtures/`、`tests/agent/accept_*.sh`。
- **不开电视、不连 adb、不联网。** `tests/agent/AgentCoreTest.java` 不要打开读（会话会被拦停）；要查它就用 `grep -c` / 短的 `grep -o`。

## Acceptance（自己全跑一遍再交）

- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed`，N ≥ 5925
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_next_song.sh` → `NEXT SONG OK`（场景在、登记了、演练过 ≥5 条局面、`check.py --strict` 过）
