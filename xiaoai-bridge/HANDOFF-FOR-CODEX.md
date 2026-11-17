# 交接说明（已过时）

2026-11-04 用户把星仪 harness 交给了 Claude（「你来吧 codex主要做的是harness…」）。现在：

- 星仪的智能体运行时（引擎、工具、无障碍服务「星仪 · 操作电视」、执行面板）编进本应用（`xyz.erapi0neer.xiaoaibridge`），源码仍在 `H:\tools\mitv\voice\assistant\src\xyz\erapi0neer\xingyi\agent\`。原因和规则见 `voice\assistant\HARNESS.md`。
- 原来经 `VOICE_QUERY`/`VOICE_REVIEW` 把请求交给星仪前台界面的做法、预览白名单、自动确认已删除。
- `voice\assistant` 现在是 git 仓库，第一条提交是 Codex 留下的 0.10.1 原样快照（那份源码停在改到一半、编译不过的状态）。

如果 Codex 再接手这个项目，请先读 `voice\assistant\HARNESS.md` 和两个仓库的 `git log`，不要回到旧的前台转交方式。
