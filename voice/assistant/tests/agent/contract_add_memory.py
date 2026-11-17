"""Adds the memory tools (job memory) and the memory rules to the contract. Run once; refuses to add twice."""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'assets', 'agent', 'contract.json')
c = json.load(open(P, encoding='utf-8'))
names = {t['function']['name'] for t in c['tools']}
assert 'remember' not in names, 'memory tools already in the contract'

c['tools'] += [
    {"type": "function", "function": {
        "name": "remember",
        "description": "记住一条关于用户的长期信息（偏好、称呼、常看的UP主/歌手/节目、家里的设备和习惯）。用户说“记住…”“以后…”“我喜欢…”“我叫…”时用；"
                       "一次性的事、密码、验证码、证件号、银行卡不记。记下的内容之后每次对话都会出现在「关于用户」里。",
        "parameters": {"type": "object", "properties": {
            "fact": {"type": "string", "description": "一句完整的中文事实，写成第三人称，例如“用户喜欢林俊杰的歌”“用户说的‘我的频道’指 B 站 UP 主星辰亻旅行者”。"}},
            "required": ["fact"]}}},
    {"type": "function", "function": {
        "name": "forget",
        "description": "忘掉和某件事有关的记忆（用户说“忘掉…”“别记…”“不要再记…”时用），连同相关的名字写法一起删掉。",
        "parameters": {"type": "object", "properties": {
            "what": {"type": "string", "description": "要忘掉的关键词，例如“林俊杰”。"}},
            "required": ["what"]}}},
    {"type": "function", "function": {
        "name": "remember_name",
        "description": "记住一个名字的正确写法：语音常把它听错（比如把“星辰亻旅行者”听成“星辰人旅行者”）。用户纠正名字、或你确认了正确写法时用；"
                       "以后听到错的写法会先自动改成对的再交给你。",
        "parameters": {"type": "object", "properties": {
            "heard": {"type": "string", "description": "语音听成的错误写法"},
            "written": {"type": "string", "description": "正确的写法"}},
            "required": ["heard", "written"]}}},
]

c['system'] = c['system'].rstrip() + (
    "\n\n## 记忆\n"
    "- 「关于用户」里是你记住的长期信息，照着办：用户说“我的频道”“老规矩”“我喜欢的那个歌手”这类话时，先看这里。\n"
    "- 用户明确让你记，或说出稳定的偏好、称呼时，用 remember 记一句；用户说忘掉时用 forget；用户纠正名字的写法时用 remember_name。"
    "这三个工具不需要 plan，记完用一句话告诉用户，例如“记住了”。\n"
    "- 不记一次性的请求，不记密码、验证码、证件号、银行卡和别人的隐私。用户问“你记得我什么”时，照「关于用户」如实说，没有就说没有。\n"
    "- recent_turns 是最近几轮对话：用户说“刚才那个”“再来一个”“换一首”“那明天呢”时，先看它再理解。")

with open(P, 'w', encoding='utf-8', newline='\n') as f:
    f.write(json.dumps(c, ensure_ascii=False, indent=1) + '\n')
print('contract:', len(c['tools']), 'tools')
