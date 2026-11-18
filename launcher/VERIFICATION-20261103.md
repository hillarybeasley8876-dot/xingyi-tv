# Orrery 真机验收 — 2026-11-03

> 截图不随仓库公开（根 README 里写明的约定），本文只保留当时的结论；文中提到的 png 都在私有开发目录里。

设备：MiTV-ASTP0，192.0.2.10:5555。验收时段约 09:50–09:54（Asia/Shanghai）。

## 实测结果

本轮使用电视真实 framebuffer 截图；没有使用 SNAPSHOT 离屏合成代替实际显示。未修改桌面源码或系统设置。

| 检查项 | 结果与证据 |
| --- | --- |
| 从待机唤醒 | KEYCODE_WAKEUP 短暂唤醒后又回 Asleep。确认 Asleep 后发送一次 KEYCODE_POWER，正常亮屏并持续运行。不要在未知状态重复 POWER，以免关屏。 |
| 主屏真实显示 | `verification-20261103/home-power.png`：1920×1080，Mercury 视频背景、卡片、时钟和网络状态显示完整，状态栏隐藏。 |
| 背景正在播放 | `loop-a.png`、`loop-b.png` 间隔约 6 秒（含截图耗时），金属球形态及节拍位置变化；右上背景区域 RGB 平均绝对像素差 9.94/255。该指标证明采样间有变化，不是独立的逐帧播放质量测量。 |
| 遥控器左右选择 | `right-hdmi2.png`、`left-hdmi1.png`：右键 HDMI1→2，左键返回1，标题/计数器/前景卡片一致。 |
| 菜单键设置 | `settings.png`：进入小米设置首页；无设置项被改动。 |
| HOME 返回 | 设置、HDMI、云视听极光退出后均回 Orrery，视频及卡片显示恢复。`home-return.png`、`final-home.png`。 |
| HDMI 1/2/3 入口 | 使用卡片遥控器确定键进入，三次活动记录均为 `com.xiaomi.mitv.tvplayer/.ExternalSourceActivity`。见 `hdmi1/2/3-activity.txt`。这验证入口调用成功，不等于验证外部信号内容；未有独立信号源/端口读回证据。 |
| HDMI 截图限制 | HDMI1 的 screencap 返回空文件，未取得画面；不能将该截图用于屏幕理解或信号质量验收。 |
| 普通应用入口 | 云视听极光卡片可进入 `com.ktcp.video/.activity.onepage.HomeSingleTaskActivity`，`cloud-app.png` 为其加载页。未登录、购买或播放节目；内容加载完成及播放需另测。 |
| 安装应用刷新 | 后台安装接口测试包后，返回桌面卡片总数从12增为13，并显示其入口，安装广播刷新生效。 |
| 资源与流畅度 | 含截图和进出应用的约44秒采样：2392帧，UI绘制 p50=11ms、p90=20ms、p95=21ms、p99=29ms，janky=29.01%，deadline missed=13。后续累计采样 janky 降到25.15%。不得将其宣传为稳定60fps；切换及截图有干扰，存在掉帧。PSS=33099KB（约32.3MiB），图形缓存约16.6MB。 |
| 崩溃 | 本次 crash log 查询未见 Orrery 崩溃记录；未做长时间稳定性/重启自启动验收。 |

## 尚不能认定通过的项目

- 无缝循环接缝、连续 60fps、长时间运行：目前只有间隔截图和 gfxinfo 采样，尚未逐帧录像分析。
- HDMI 外部信号本身及各端口实际输入内容。
- 云视听极光节目搜索/播放、应用商店和 U 盘完整交互。
- 从断电重启后 HOME 默认入口恢复。

所有截图和原始 dumpsys 输出位于 `verification-20261103/`。UI 已交回主控/麦克风验收代理，释放时电视保持亮屏，Orrery 前台。
