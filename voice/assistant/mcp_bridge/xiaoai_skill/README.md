# 小爱技能调用星仪 MCP

这个回调供**已获准在目标设备调试的自定义小爱技能**使用。流程是小爱开放平台识别意图，向技能的 HTTPS 地址发出带签名的请求，回调通过官方 MCP 客户端调用这台电脑上的星仪服务，再把文字答复交给小爱播报。小米将技能分发到哪些型号取决于平台配置。请先在控制台确认这台电视能作为测试设备。[技能类型](https://developers.xiaoai.mi.com/documents/Home?type=/api/doc/render_markdown/SkillAccess/des) · [自定义技能](https://developers.xiaoai.mi.com/documents/Home?type=/api/doc/render_markdown/SkillAccess/skill/CustomSkillsMain)

| 平台意图名 | 语料示例 | 实际操作 |
|---|---|---|
| `query_volume` | “电视现在音量是多少” | 调用 MCP 的 `tv_status`，读到数值才播报。 |
| `play_song` | “用网易云播放{artist}的{title}” | 歌手和歌名均为必填槽位。调用 `xingyi_submit`，收到受理回执后请用户到电视核对操作预览。 |

未带意图的技能启动会询问用户要查音量还是点歌。助手只读取已配置槽位，不转发原始语音。未返回点歌回执时不会自动重试。小爱侧的语音入口采用平台分配的调用名称。

## 先用小爱，需要时再交给星仪

平常直接对“小爱同学”说原来的命令。小爱明确答复做不了、并且没有执行该操作时，再点名星仪并重说完整指令。若控制台实际注册的调用名称是“星仪管家”，第二句话可以是“小爱同学，让星仪管家用网易云播放林俊杰的不为谁而作的歌”。也可以先说“小爱同学，打开星仪管家”，听到提示后再说歌手和歌名。前一句话或小爱失败的原因不会自动传到这个技能。

官方自定义技能采用显式唤醒，要求调用名称。配置语料时只添加表中承诺的动作。这里没有发现针对电视系统全部失败指令的全局回调。已受理、等待确认、超时或执行状态不明时，先检查电视上的预览或播放状态，避免重复点歌。技能对已处理、无法处理或无人继续回应的请求会结束会话，之后的普通唤醒仍交给小爱。[官方自定义技能文档第 4 节](https://developers.xiaoai.mi.com/documents/Home?type=/api/doc/render_markdown/SkillAccess/skill/CustomSkillsMain)

## 私人调试配置

1. 在小爱技能控制台创建自定义技能并确认目标设备，在交互模型里添加上表的两个意图及槽位。选择 HTTPS 回复，使用平台给本技能分配的 KeyId/SecretKey。测试中仅授权自己的家庭账号。[官方 HTTPS 协议](https://developers.xiaoai.mi.com/documents/Home?type=/api/doc/render_markdown/SkillAccess/skill/fulu/HTTPS) · [官方签名文档](https://developers.xiaoai.mi.com/documents/Home?type=/api/doc/render_markdown/SkillAccess/skill/fulu/Signature)
2. 将下面的 JSON 保存到当前 Windows 用户的私有目录（例如个人 AppData 下），用该技能实际的 `session.application.app_id` 和自家账号在此技能中的 `session.user.user_id` 替换占位符。这两个值取自获授权的技能调试请求；模拟器的用户标识可能与真机不同。不要将密钥、原始请求或私人配置提交到项目。

```json
{
  "host": "your-skill.example.com",
  "app_id": "SKILL_APP_ID",
  "owner_id": "ACCOUNT_ID_SCOPED_TO_THIS_SKILL",
  "key_id": "PLATFORM_KEY_ID",
  "secret_base64": "BASE64_SECRET_FROM_XIAOMI"
}
```

3. 在已配对电视、可以通过 ADB 读到电视的家庭电脑上运行回调。回调使用电脑项目的 Python 虚拟环境，自行启动本机 MCP 子进程；它只在 `127.0.0.1:8765` 监听。在云服务器单独运行这条命令不会产生到家中电视的连接。

```powershell
cd H:\tools\mitv\voice\assistant\mcp_bridge
.\.venv\Scripts\python.exe .\xiaoai_skill\gateway.py --config "$env:LOCALAPPDATA\Xingyi\skill.json"
```

4. 配置经授权的 HTTPS 入口，将固定路径 `/xiaoai/skill` 上的 POST 原样反代到该电脑的回环监听地址，保留请求体及签名相关头。回调核对域名、`Content-MD5`、HMAC、签名时间、技能 ID 和用户 ID。验签使用原始 JSON 字节。配置固定路径，不附加查询参数。哈希化请求编号在配置文件旁短时持久化，用于防止重复点歌。公网入口、平台凭据、真实账号授权及电视调试绑定均须由部署环境提供。
5. 在平台的真实测试设备上喊“小爱同学，打开〈调用名称〉”，再问“电视现在音量是多少”。先核对读数，再测试名为 `play_song` 的点歌指令和电视上的确认。

## 已验证的范围

运行 `.\.venv\Scripts\python.exe .\xiaoai_skill\verify.py` 可重现验收。本机验证了小米公开的两条签名向量、模拟签名事件进入真实 MCP stdio 客户端、`tv_status` 工具调用和小爱回复的数据结构。电视当前离线，回复准确提示无法读取。点歌动作、重放和授权边界用受控请求及模拟点歌回执验收。仍需小米平台真实签名、真实目标电视语音调用与在线设备读回才能确认完整链路。[本机结果](verification.json)
