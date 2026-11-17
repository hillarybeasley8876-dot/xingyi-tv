// music_intent.mjs — worker job: port tests/ref/music_ref.py parse() to MusicIntent.java.
//   cd ~/.claude/scripts/m3-dispatch && node engine.mjs --job H:/tools/mitv/voice/assistant/tests/m3jobs/music_intent.mjs --out runs/xingyi-intent
import { makeJob } from './music_common.mjs';

export default makeJob({
  cls: 'MusicIntent',
  test: 'MusicIntentTest',
  part: 'intent',
  goal: '电视语音助手要听懂"网易云音乐播放薛之谦的你还要我怎样"这类点歌指令。把 Python 参考实现里的 parse()(连同它用到的常量和辅助函数)移植成 Java 类 MusicIntent:静态方法 parse(String) 返回解析结果,不是点歌请求时返回 null。',
  extra: [
    'Java 版与参考实现的对应关系:',
    '- 参考实现返回 dict;Java 返回 MusicIntent 对象:kind 对应 dict["kind"](同名枚举),netease / explicitPlay / content / artist / splits / wantsVersion 一一对应。',
    '- content、artist 永远不为 null(没有就是 "");splits 永远不为 null,用 Collections.unmodifiableList 包起来,每个元素是长度为 2 的新 String[]{artist, title}。',
    '- intent() 辅助函数产生的结果:content=""、artist=""、splits 为空列表、wantsVersion=false、explicitPlay=netease(调用方之后可能改写 explicitPlay,照参考实现做)。',
    '- parse(null) 返回 null。',
  ].join('\n'),
  steps: [
    '先通读参考实现,列出所有常量(FILLERS、NETEASE、CONNECTORS、VERBS、QUANT、PARTICLES、PAUSE、RESUME、NEXT、PREV、FAV、FM、DAILY、EXCLUDE、ARTIST、VERSION、STRONG、PRONOUNS),原样搬到 Java 的 static final 字段里,列表保持原顺序。',
    '移植 strip_prefix / strip_suffix:注意它们是"每次剥掉一个、然后从头重新扫描整张表"的循环,且只有在剩余部分非空时才剥(len(s) > len(w))。',
    '按参考实现 parse() 的语句顺序逐段移植:NFKC → 《》书名号提取 → 去引号 → 标点换空格 → 合并空白、去两端空白、小写 → 剥前缀 → 网易云短语 → 剥语气词 → 整句指令 → 动词 → 量词 → 语气词 → 排除词 → 收藏/FM/日推 → 歌手 → splits → 结果。',
    '对照下面的示例和常见错误自查一遍,再输出。',
  ],
  examples: [
    { in: '网易云音乐播放薛之谦的你还要我怎样', out: 'kind=SONG netease=true explicitPlay=true content="薛之谦的你还要我怎样" artist="" splits=[{薛之谦|你还要我怎样},{|薛之谦的你还要我怎样}] wantsVersion=false' },
    { in: '打开网易云音乐然后播放林俊杰的不为谁而作的歌', out: 'kind=SONG content="林俊杰的不为谁而作的歌" splits=[{林俊杰|不为谁而作的歌},{林俊杰的不为谁而作|歌},{|林俊杰的不为谁而作的歌}](ARTIST 正则匹配到的歌手里含"的",所以不是 ARTIST)' },
    { in: '我想听薛之谦的歌', out: 'kind=ARTIST netease=false explicitPlay=true content="薛之谦的歌" artist="薛之谦" splits=[{薛之谦|歌},{|薛之谦的歌}]' },
    { in: '听不清楚', out: 'kind=SONG explicitPlay=false content="不清楚"("听"不是强动词,前面也没有"我想/我要"等)' },
    { in: '放大音量', out: 'null(动词"放"后的内容以"大"开头,且没提网易云)' },
    { in: '网易云下一首', out: 'kind=NEXT netease=true explicitPlay=true content="" splits=[]' },
  ],
  pitfalls: [
    '不要把 VERBS 按别的方式排序:必须"长的在前、同长度保持原顺序",否则"播放一首"会被"播放"抢先匹配。',
    '量词 QUANT 只剥一次(剥掉第一个匹配的就停),不是循环剥。',
    'wanted 的判断:stripped = 剥前缀之前的字符串里被剥掉的那段前缀,看它是否包含 我想/我要/想要/帮我/给我 之一。',
    'splits 里"的"的位置按字符(码点)扫描,最多 3 个;歌手部分以"唱"结尾时去掉这个"唱";长度条件 2..15 按码点算。',
    '最后一项总是 {"", content}:只有当列表为空或最后一项不等于 {"", content} 时才追加,不要重复追加。',
    '不要用 String.trim() 代替参考实现的 strip()(全角空格等不同);写自己的 strip 辅助函数。',
  ],
});
