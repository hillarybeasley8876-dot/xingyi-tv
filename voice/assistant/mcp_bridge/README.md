# 星仪电视 MCP 服务（2.0）

让电脑上的 MCP 客户端（Claude Code、Claude Desktop、Codex 等）把事情交给电视上的星仪去做。服务进程跑在这台已与电视配对的 Windows 电脑上，经 adb 连到电视里的星仪后台（`xyz.erapi0neer.xiaoaibridge`）。星仪界面不用在前台；电视上照常显示右上角的执行面板，但这个来源不出声。

| 工具 | 作用 |
|---|---|
| `tv_do(request, wait_seconds?)` | 用一句中文把要做的事交给星仪（打开应用、搜索、点歌、改设置、读屏幕、回答屏幕上的内容…），等到结束，返回目标、步骤、实际执行的操作和星仪的结论。返回 `awaiting=consent` 时问用户后调用 `tv_consent`；返回 `awaiting=answer` 时星仪在追问，用用户的回答再调用一次 `tv_do`。 |
| `tv_state()` | 星仪此刻在做什么、是否在等确认或回答、最近一项任务。 |
| `tv_screen()` | 电视屏幕上此刻有什么（应用、页面、带编号的界面元素），和星仪看到的一样。 |
| `tv_consent(approve)` | 回答星仪的确认请求。只在用户同意时传 true。 |
| `tv_cancel()` | 停下星仪正在做的事。 |
| `tv_status()` | 只读电视的实际媒体音量。 |

## 接入

- `H:\tools\mitv\.mcp.json` 是项目级配置：在这个目录下打开的 Claude Code 会话会提示启用 `xingyi-tv`。
- 其他客户端可以导入同目录的 [client-config.json](client-config.json)。
- 电视地址变了就在服务器环境变量里设 `XINGYI_TV=电视IP:5555`。电脑和电视要在同一局域网。

当前安装使用项目内的 Python 虚拟环境及官方 `mcp==1.30.0`：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe check_v2.py "现在音量是多少"
```

`check_v2.py` 用官方 Python 客户端走真实 stdio：握手、列工具、读状态和屏幕，可选再跑一次 `tv_do`。2026-11-04 真机：`tv_do("看看现在电视屏幕上是什么，再把音量调到11")` 返回 3 步计划、2 个已执行操作和结论「屏幕上是网易云音乐的《Kronos · 削除》播放页，音量已调到11。」。

## 电视连不上时

adb 在网络上对「电视关机 / 换了 IP / 调试关了 / 没授权」说的是同几句话，`tv_link.py` 用两个 TCP 探测把它们分开：5555 被拒绝说明有设备在线只是端口关着，没有任何应答才是不在网络里；6095 是小米电视自己的遥控服务（`/request?action=isalive` 回设备名），调试关掉时它还在，同时用来确认「这是我们的电视」。读类工具（`tv_state`、`tv_screen`、`tv_status`）失败时先 `adb connect` 重连一次，再探测，返回的 `link` 是 `adb_closed` / `offline` / `host_unclear` / `ok`（端口开着但 adb 状态不是 device，附 `adb_state`），`message` 是给用户的一句话。`tv_status` 在连不上时返回 `connected=false` 加原因，不当错误。`tv_do` 和 `tv_consent` 只在发送前检查链路，发送失败后不重发。

离线测试（只用标准库）：`python -m unittest test_tv_link`。


1.0 版（Codex 做的）用 `xingyi_submit/xingyi_result`：先把星仪界面拉到前台，再按旧 harness 的操作预览逐项确认。2.0 直接对接星仪后台的引擎（理解→计划→执行→核对），只有敏感操作才需要确认。旧的小爱技能接入调研仍在 [xiaoai_skill](xiaoai_skill/README.md)。
