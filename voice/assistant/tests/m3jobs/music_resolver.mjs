// music_resolver.mjs — worker job: port tests/ref/music_ref.py resolver half to MusicResolver.java.
//   cd ~/.claude/scripts/m3-dispatch && node engine.mjs --job H:/tools/mitv/voice/assistant/tests/m3jobs/music_resolver.mjs --out runs/xingyi-resolver
import { makeJob } from './music_common.mjs';

export default makeJob({
  cls: 'MusicResolver',
  test: 'MusicResolverTest',
  testArgs: ['H:/tools/mitv/voice/assistant/tests/fixtures/netease_search.json'],
  part: 'resolver',
  goal: '电视语音助手已经把点歌指令解析成 content + splits(候选的{歌手,歌名}对),现在要在网易云的搜索结果里挑出用户要的那一首。把 Python 参考实现的 resolver 部分(norm / base_title / title_tagged / lev / similarity / score_song / queries / resolve 以及它们用到的正则)移植成 Java 类 MusicResolver。',
  extra: [
    'Java 版与参考实现的对应关系:',
    '- resolve(content, splits, wantsVersion, search):参考实现里的 it["content"] / it["splits"] / it["wantsVersion"] 就是这三个参数。search.songs(keywords) 返回网易云网页搜索接口的整个 JSON 响应,形如 {"code":200,"result":{"songCount":n,"songs":[{"id":..,"name":..,"artists":[{"name":..}],"album":{"name":..,"size":..},"fee":..,"alias":[..],"transNames":[..]}]}}。',
    '- queries(content, splits) 对应参考实现的 queries(it);norm / baseTitle / titleTagged / similarity 对应 norm / base_title / title_tagged / similarity;albumTagged(album) 就是 ALBUM_VERSION 正则 find()。',
    '- score_song 返回的 dict 对应 Pick:id(long)、title(歌名原文)、artist(所有歌手名用 "/" 连接)、album(专辑名,没有就 "")、score、confident。',
    '- 选择规则:按 (confident, score) 比较,先比 confident(true 胜),再比 score;只有严格更好才替换(先出现的保持)。每个查询结束后,若当前最好的已经 confident 就停止,不再发下一个查询。',
    '参考实现没有、Java 版必须补上的错误处理(测试会检查):',
    '- 响应的 code 是 405 或 406(网易云限流)→ 抛 new IOException("rate_limited");其它非 200 → 抛 new IOException("search_failed")。code 字段缺失按 200 处理。',
    '- code 为 200 但没有 result 或 result 里没有 songs → 当作空列表。',
    '- 某个查询(search.songs 本身抛 IOException,或上面的 code 检查抛出)失败时:如果之前已经有候选(best 不为 null)就直接返回这个候选,否则把异常抛出去。',
    '- 歌曲 id 用 optLong;name / album.name / artists[].name 缺失按 "" 处理;alias、transNames 可能缺失或为 null。',
  ].join('\n'),
  steps: [
    '先移植正则常量 VERSION、ALBUM_VERSION、COMPILATION(注意 re.I → CASE_INSENSITIVE | UNICODE_CASE,ALBUM_VERSION 里的 (?<![a-z])dj(?![a-z]) 原样保留)。',
    '移植 norm、base_title(循环到不再变化;两个正则依次替换;全部剥光时返回上一轮的值)、title_tagged、lev、similarity(按码点计算编辑距离)。',
    '移植 score_song:对每个 split 计算 tm(3/2/1,0 则跳过这个 split)、am、版本惩罚,取最高分的 split;最后加排名奖励 max(0, 20 - rank)、合辑 -15、fee==4 -10。rank 是这首歌在【这一次查询】结果里的下标(从 0 开始)。',
    '移植 queries 和 resolve,再加上面"错误处理"一节的行为。',
    '对照示例和常见错误自查,再输出。',
  ],
  examples: [
    { in: 'content="薛之谦的你还要我怎样" splits=[{薛之谦|你还要我怎样},{|薛之谦的你还要我怎样}]', out: 'queries = ["薛之谦 你还要我怎样", "薛之谦的你还要我怎样", "薛之谦"];第一个查询就找到 id=27955653(confident=true,score=400),不再发第二个查询' },
    { in: 'norm("  G.E.M.邓紫棋 ")', out: '"gem邓紫棋"(去掉空白、标点、符号,并小写)' },
    { in: 'baseTitle("稻香 (钢琴版) [原唱: 周杰伦]")', out: '"稻香"(从末尾一层层剥掉括号)' },
    { in: 'titleTagged("光年之外 (热爱版)")', out: 'true(括号后缀里有"版")' },
    { in: 'similarity("你还要我怎么样", "你还要我怎样")', out: '0.857142857…(编辑距离 1,最长 7 个码点:1 - 1/7)' },
  ],
  pitfalls: [
    '不要在找到 confident 候选后继续发查询:测试会核对实际发出的查询列表。',
    'tm==0 的 split 直接跳过(continue),不参与 best 的比较;所有 split 都跳过时这首歌没有分(返回 null)。',
    'am:split 的歌手为空时,看是否有某个歌手名(norm 后长度≥2)出现在 norm(content) 里 → 1,否则 0;歌手不为空时,有歌手名 norm 后等于它或包含它 → 2,否则 -1。',
    'confident 的条件里 "tagged 且不 wantsVersion" 一票否决。',
    '分数只有 > 0 的候选才参与选择。',
    'Android 的 org.json:optJSONObject / optJSONArray / optString / optLong / optInt 不抛异常,优先用它们;new JSONObject(..) 和 getXxx 会抛受检 JSONException。',
  ],
});
