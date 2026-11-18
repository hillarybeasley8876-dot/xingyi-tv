// Calibrates the 星仪 agent prompt + tool set against the real model on the gateway.
// The TV is simulated: each scenario scripts what the tools return. Nothing touches the TV.
// node calibrate.mjs [scenario...]     (key from env GATEWAY_API_KEY, never printed)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
// A scenario name used twice is silently replaced by the later one: 'vague' (「放那个」) never ran from 2026-11-04 to
// 11-18, and a new 'replaysong' replaced nothing only because the old one came later in the file.
{
  const names = [...readFileSync(fileURLToPath(import.meta.url), 'utf8').matchAll(/^ {2}([a-z0-9]+): \{ say: /gm)].map((m) => m[1]);
  const twice = [...new Set(names.filter((n, i) => names.indexOf(n) !== i))];
  if (twice.length) throw new Error(`scenario names used twice: ${twice.join(', ')}`);
}
// CAL_CONTRACT=<file>: another contract for an A/B (e.g. `git show <commit>:assets/agent/contract.json > old.json`)
const contract = JSON.parse(readFileSync(process.env.CAL_CONTRACT || join(here, '..', '..', 'assets', 'agent', 'contract.json'), 'utf8'));
if (process.env.CAL_CONTRACT) console.log(`# contract ${process.env.CAL_CONTRACT}`);
const key = process.env.GATEWAY_API_KEY;
if (!key) throw new Error('GATEWAY_API_KEY missing');
const MODEL = process.env.AGENT_MODEL || 'MiniMax-M3.1-Flash';
// AgentLoop's escalation: a task that is not done by round 5, or has failed twice, goes on with the strong model
const STRONG = process.env.STRONG_MODEL ?? 'gpt-5.4';
const ESCALATE_ROUND = 5, ESCALATE_FAILURES = 2;
const EFFORT = process.env.AGENT_EFFORT || 'low';

// What another model may be shown of the conversation: the fields every chat API knows (MiniMax adds reasoning ones)
function portable(messages) {
  return messages.map((m) => {
    const o = { role: m.role, content: m.content ?? '' };
    if (m.tool_calls) o.tool_calls = m.tool_calls;
    if (m.tool_call_id) o.tool_call_id = m.tool_call_id;
    return o;
  });
}
// Contract gates (2026-11-07): a data tool is sent only when its words are in the request or what it follows up;
// CAL_NO_GATES=1 sends every tool, as before, for an A/B
const GATES = Object.fromEntries(Object.entries(contract.gates ?? {}).map(([k, v]) => [k, new RegExp(v)]));
const gatedIn = (text) => new Set(Object.keys(GATES).filter((k) => GATES[k].test(text)));
// Contract.search / summary / linesFor (find_tools, 2026-11-07): gate words first, then shared two-character words
const DESCRIPTIONS = Object.fromEntries(contract.tools.map((t) => [t.function.name, t.function.description ?? '']));
function searchTools(query, max = 3) {
  const q = String(query ?? '').trim();
  const scored = [];
  for (const k of Object.keys(GATES)) {
    let score = q && GATES[k].test(q) ? 100 : 0;
    for (let i = 0; i + 2 <= q.length; i++) { const w = q.slice(i, i + 2); if (!/\s/.test(w) && DESCRIPTIONS[k].includes(w)) score++; }
    if (score > 0) scored.push([k, score]);
  }
  return scored.sort((a, b) => b[1] - a[1]).slice(0, max).map((x) => x[0]);
}
const toolsFor = (set) => contract.tools.filter((t) => !(t.function.name in GATES) || set.has(t.function.name));
const mentions = (line, name) => new RegExp(`(^|[^A-Za-z0-9_])${name}([^A-Za-z0-9_]|$)`).test(line);
const systemFor = (set) => contract.system.split('\n').filter((line) => {
  const named = Object.keys(GATES).filter((g) => mentions(line, g));
  return !named.length || named.some((g) => set.has(g));
}).join('\n');
async function chat(messages, model = MODEL, tools = contract.tools) {
  // the gateway's per-minute limit (429 user_rate_limited, 2026-11-09: three runs at once lost 140 of 177 scenarios)
  // is waited out; the time reported is the answer's own
  for (let attempt = 1; ; attempt++) {
    const t0 = Date.now();
    const r = await fetch('https://gateway.example.com/v1/chat/completions', {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
      body: JSON.stringify({ model, messages: model === MODEL ? messages : portable(messages), tools, tool_choice: 'auto',
        reasoning_effort: EFFORT, max_completion_tokens: 2048, stream: false }),
    });
    const text = await r.text();
    if (r.status === 429 && attempt < 6) {
      console.log(`  (rate limited, waiting ${15 * attempt} s)`);
      await new Promise((done) => setTimeout(done, 15000 * attempt));
      continue;
    }
    if (r.status !== 200) throw new Error(`HTTP ${r.status} ${text.slice(0, 200)}`);
    const msg = JSON.parse(text).choices[0].message;
    return { msg, ms: Date.now() - t0 };
  }
}

// Each scenario: utterance, optional previous task, and a tool simulator (name,args,state) -> result.
const screens = {
  launcher: '前台：Orrery 桌面 xyz.erapi0neer.orrery\n焦点：[3]\n[1] 卡片「网易云音乐」 可点\n[2] 卡片「哔哩哔哩」 可点\n[3] 卡片「云视听极光」 焦点 可点\n[4] 卡片「设置」 可点',
  biliHome: '前台：哔哩哔哩 tv.danmaku.bili/.MainActivityV2\n焦点：[2]\n[1] 文本「推荐」 已选\n[2] 按钮「搜索」 焦点 可点\n[3] 卡片「【4K】年度混剪 · 播放 120万」 可点\n[4] 卡片「猫咪日常 · 播放 30万」 可点',
  biliResults: (q) => `前台：哔哩哔哩 tv.danmaku.bili/.SearchResultActivity\n焦点：[1]\n[1] 文本框「${q}」 可输入\n[2] 标签「综合」 已选\n[3] 标签「用户」 可点\n[4] 卡片「UP主 ${q} · 粉丝 1.2万 · 视频 86」 可点\n[5] 卡片「${q}：第一期 城市夜景延时 · 播放 3.4万」 可点\n[6] 卡片「${q} 的 2026 年度总结 · 播放 1.1万」 可点`,
  biliPlayer: '前台：哔哩哔哩 tv.danmaku.bili/.VideoDetailsActivity\n焦点：[1]\n[1] 视频画面 可点\n[2] 文本「星辰亻旅行者：第一期 城市夜景延时」\n[3] 按钮「点赞」 可点',
  // what the 2026-11-05 runs saw: an UP's search results with play counts, one of them a MAD
  biliUp: '前台：哔哩哔哩 tv.danmaku.bili/com.bilibili.search2.main.BiliMainSearchActivity\n焦点：[1]\n[1] 文本框「星辰亻旅行者」 可输入\n[2] 标签「综合」 已选\n[3] 标签「用户(3)」 可点\n[4] 卡片「星辰亻旅行者 · 2.1万粉丝 · 73个视频」 可点\n[5] 卡片「示例作品27 · 星辰亻旅行者 · 4:21 · 2289 · 10月18日」 可点\n[6] 卡片「示例作品14 · 星辰亻旅行者 · 2:46 · 125.9万 · 2019年12月4日」 可点\n[7] 卡片「【示例PV】作品24 · 星辰亻旅行者 · 4:06 · 25.3万 · 10月1日」 可点\n[8] 卡片「【MAD/燃向】时代的回声 · 星辰亻旅行者 · 3:12 · 3.1万 · 9月2日」 可点\n[9] 按钮「查看全部73个视频」 可点',
};
// B站's search as bili_videos read it on 2026-11-07 (BiliVideos): each UP's uploads, newest first
const BILI_UPS = {
  '星辰亻旅行者': [
    { aid: 111528431382594, title: '示例作品15', date: '前天', views: 1081, tags: 'agent,harness' },
    { aid: 118921720599919, title: '【示例小镇 ｜示例】一段很长的示例视频标题，用来测试卡片截断，长度和原来的标题差不多', date: '10 月 18 日', views: 2030, tags: 'Godot' },
    { aid: 112394507326699, title: '示例作品27', date: '10 月 18 日', views: 2374, tags: '游戏' },
    { aid: 118453142134402, title: '【示例PV】作品24', date: '10 月 1 日', views: 253847, tags: 'PV,AI' },
    { aid: 923642204, title: '【示例MAD】作品17', date: '2020 年 8 月 31 日', views: 93530, tags: 'MAD' },
    { aid: 77831250, title: '示例作品14', date: '2019 年 12 月 4 日', views: 1259336, tags: 'MAD,静止系' },
    { aid: 25872358, title: '示例作品21', date: '2018 年 6 月 25 日', views: 99674, tags: 'MAD,静止系' }],
  '影视飓风': [
    { aid: 117568757109187, title: '动态视频｜2026，它来了…', date: '昨天', views: 130796, tags: '' },
    { aid: 117563807766583, title: '真的需要摸吗？MacBook Pro PROLED XDR上手', date: '前天', views: 1627274, tags: '数码' },
    { aid: 117519767570878, title: '动态视频｜苹果的触屏MacBook Pro来了，你会买么？', date: '10 月 29 日', views: 1450182, tags: '' },
    { aid: 117280105045764, title: '选哪个？iPhone 18 Pro&Duo深度上手', date: '9 月 16 日', views: 17120731, tags: '数码' },
    { aid: 116554742109232, title: '3台高速机VS刘谦手速！谁更胜一筹？', date: '5 月 11 日', views: 15747044, tags: '魔术' },
    { aid: 115218420078806, title: '外观变了，值得买吗？iPhone 17 Pro&Air评测', date: '2025 年 9 月 17 日', views: 16232807, tags: '数码' }],
  '老师好我叫何同学': [
    { aid: 117564042646971, title: '你可以这么用。MacBook PROLED XDR首发体验', date: '前天', views: 258844, tags: '' },
    { aid: 117478629841817, title: '【何同学】能折！但代价呢？iPhone Duo首发体验', date: '10 月 21 日', views: 2601126, tags: '' },
    { aid: 117280574807857, title: '【何同学】一镜到底。iPhone 18 Pro首发体验', date: '9 月 16 日', views: 5425246, tags: '' },
    { aid: 115819111519373, title: '【何同学】我们做了台魔法钢琴…', date: '1 月 1 日', views: 25059771, tags: '' },
    { aid: 771908203, title: '【何同学】我做了一个自己打字的键盘...', date: '2022 年 8 月 12 日', views: 28454257, tags: '' },
    { aid: 54737593, title: '【何同学】有多快？5G在日常使用中的真实体验', date: '2019 年 6 月 6 日', views: 33947396, tags: '' }],
};
const BILI_MIDS = { 星辰亻旅行者: 14770463, 影视飓风: 946974, 老师好我叫何同学: 163637592 };
// Grounding.BUILTIN / canonical: names XiaoAi mishears, written right before the model sees the request
const ALIASES = { 星辰旅行者: '星辰亻旅行者', 星辰人旅行者: '星辰亻旅行者' };
const canonical = (said) => Object.keys(ALIASES).sort((a, b) => b.length - a.length)
  .reduce((out, k) => (out.includes(ALIASES[k]) ? out : out.split(k).join(ALIASES[k])), String(said));
// Grounding.distance
function editDistance(a, b) {
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]);
  for (let j = 1; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++)
    d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
  return d[a.length][b.length];
}
const biliCount = (n) => (n >= 1e8 ? `${(n / 1e8).toFixed(1)} 亿` : n >= 1e4 ? `${Math.floor(n / 1e4)} 万` : `${n} 次`);
const ORD = ['第一', '第二', '第三', '第四', '第五', '第六', '第七', '第八', '第九', '第十'];
const biliNorm = (s) => String(s ?? '').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
// TvTools.bili_videos over BiliVideos: an UP by the user search (the same name, or one holding it), their uploads by
// date or plays, a kind of video by title or tag; a topic's videos with whose they are
function biliVideos(a) {
  const who = String(a.up ?? '').trim(), kw = String(a.keyword ?? '').trim();
  const up = Object.keys(BILI_UPS).find((n) => who && (biliNorm(n) === biliNorm(who) || (biliNorm(who).length >= 2 && biliNorm(n).includes(biliNorm(who)))))
    ?? Object.keys(BILI_UPS).find((n) => who && editDistance(biliNorm(n), biliNorm(who)) <= Math.max(1, Math.floor(biliNorm(who).length / 4)))
    ?? (!who ? Object.keys(BILI_UPS).find((n) => biliNorm(n) === biliNorm(kw)) : undefined);
  if (who && !up) return { ok: false, status: 'not_found', message: `B站上没找到叫「${who}」的 UP 主：名字可能听错了。用 bili_search 在B站里搜一次，或者问用户 UP 主的准确名字` };
  const order = ['latest', 'most_played', 'relevance'].includes(a.order) ? a.order : up ? 'latest' : 'relevance';
  const k0 = Math.max(1, Math.min(10, Number(a.count ?? 3)));
  const note = '要播放哪个就用 open_link 打开它的 link：B站会直接进到那个视频开始播放';
  if (!up) {
    const list = [{ aid: 113000000000001, title: `【4K】${kw}全流程攻略`, up: '爱玩的Kaka', date: '2024 年 12 月 27 日', views: 7963814 },
      { aid: 113000000000002, title: `${kw}最新消息汇总`, up: '子休狐', date: '昨天', views: 25678 }];
    const k = Math.min(k0, list.length), how = order === 'most_played' ? '播放最多的' : order === 'latest' ? '最新的' : '相关的';
    const answer = `B站上「${kw}」${how}${k > 1 ? ` ${k} 个` : ''}视频${k === 1 ? '是' : '：'}`
      + list.slice(0, k).map((v, i) => `${k === 1 ? '' : ORD[i] + '，'}《${v.title}》，${v.up}，${v.date}，${biliCount(v.views)}播放`).join('；');
    return { ok: true, status: 'observed', message: answer, answer, source: 'B站搜索', note,
      videos: list.map((v, i) => ({ n: i + 1, title: v.title, up: v.up, date: v.date, views: v.views, link: `bilibili://video/${v.aid}` })) };
  }
  const word = kw && biliNorm(kw) !== biliNorm(up) ? biliNorm(kw) : '';
  let list = BILI_UPS[up].filter((v) => !word || biliNorm(v.title + v.tags).includes(word));
  if (order === 'most_played') list = [...list].sort((x, y) => y.views - x.views);
  if (!list.length) { const answer = `${up}的视频里没找到「${kw}」`; return { ok: true, status: 'observed', message: answer, answer, videos: [], up: { name: up }, source: 'B站搜索' }; }
  const k = Math.min(k0, list.length), how = order === 'most_played' ? '播放最多的' : order === 'latest' ? '最新的' : '的';
  const item = (v) => (order === 'most_played' ? `《${v.title}》，${biliCount(v.views)}播放，${v.date}${k === 1 ? '发的' : ''}`
    : `《${v.title}》，${v.date}${k === 1 ? '发的' : ''}，${biliCount(v.views)}播放`);
  const answer = `${up}${how}${k > 1 ? ` ${k} 个` : ''}${word ? kw : ''}视频${k === 1 ? '是' : '：'}` + list.slice(0, k).map((v, i) => (k === 1 ? '' : ORD[i] + '，') + item(v)).join('；');
  // TvTools: a request for the UP's page says so in the note
  const pageNote = /频道|主页|空间/.test(a._said ?? '') ? '用户要看的是 UP 主的主页：用 open_link 打开 up.space，不要直接放视频' : note;
  return { ok: true, status: 'observed', message: answer, answer, up: { name: up, videos: BILI_UPS[up].length, space: `bilibili://space/${BILI_MIDS[up]}`, live: false }, source: 'B站搜索', note: pageNote,
    videos: list.slice(0, 10).map((v, i) => ({ n: i + 1, title: v.title, up, date: v.date, views: v.views, link: `bilibili://video/${v.aid}` })) };
}
// what a video link plays: the catalog's, the hot list's, else a generic title
const HOT_TITLES = { BV1nm4g6NE71: '《崩坏：因缘精灵》幸福PV：缘汽一下', BV1mpxR6KESh: '物理意义上烫烫的一集', BV1NS4t6oEXQ: '真的需要摸吗？MacBook Pro' };
function biliTitle(id) {
  for (const list of Object.values(BILI_UPS)) { const v = list.find((x) => String(x.aid) === String(id)); if (v) return v.title; }
  return HOT_TITLES[id] ?? null;
}
function sim(state) {
  return {
    plan: () => ({ ok: true, status: 'recorded' }),
    plan_update: () => ({ ok: true }),
    // TvTools.netease_karaoke (2026-11-09): on NetEase's player page it reads the mic label and flips 随心唱 when needed
    netease_karaoke: (a) => {
      if (!state.karaokeSim || state.fg !== 'com.netease.cloudmusic.tv') return { ok: false, status: 'rejected', message: '网易云的播放页不在前台：先打开正在放的歌的播放页' };
      const want = a.on === true || a.on === 'true';
      if (state.karaoke === want) return { ok: true, status: 'observed', message: want ? '本来就是伴奏：随心唱开着' : '本来就是原唱：随心唱关着' };
      state.karaoke = want; state.karaokeClicks = (state.karaokeClicks ?? 0) + 1;
      return { ok: true, status: 'observed', message: want ? '已切到伴奏：随心唱开着，按上键能调人声大小' : '已切回原唱：随心唱关了' };
    },
    ask_user: (a) => ({ ok: true, status: 'asked', note: '问题已朗读，等待用户回答，本轮结束' }),
    // the same apps the environment lists (2026-11-11: the old fixed list had no 无线投屏 or TV Bro, so a model that had
    // just read them in environment.apps was told they were not there)
    list_apps: () => ({ ok: true, apps: state.apps ?? ['网易云音乐', '哔哩哔哩', '云视听极光', '米家', '电视设置', '应用商店'] }),
    // the volume now: 10 as at the request, or what XiaoAi set it to when the scenario says it did (volumeNow)
    tv_status: () => ({ ok: true, volume: state.volumeNow ?? 10, volume_max: 100, muted: false, foreground: state.fg, now_playing: state.playing ?? null,
      bluetooth: '这台电视没有蓝牙', wifi: '已连接 Xiaomi_5G，IP 192.168.31.162，信号一般（-63 dBm），速率 144 Mbps',
      memory: '可用 310 MB / 共 2.0 GB（内存紧张）', memory_hogs: ['哔哩哔哩 1.3 GB'], storage: '剩余 3.1 GB / 共 16.0 GB', since_restart: '9 天 4 小时（待机不算重启）' }),
    // TvTools.free_memory: the user's apps in the background, the system's figures before and after
    free_memory: () => { state.freed = true;
      return { ok: true, status: 'observed', message: '清理好了：关掉了后台的哔哩哔哩，可用内存从 310 MB 到 1.5 GB。', available_before_mb: 310, available_after_mb: 1530, stopped: ['哔哩哔哩'] }; },
    screen_read: () => ({ ok: true, screen: state.screen }),
    // only the 随心唱 scenarios look at the screen; elsewhere screen_look stays unsimulated, as before
    screen_look: (a) => !state.karaokeSim ? { ok: false, status: 'rejected', message: '没有这个工具' }
      : state.screen === NE_TUTORIAL ? { ok: true, status: 'observed', answer: '网易云播放页上盖着一张遥控器按键教程，底部写着「点击任意键跳过 (28)」' }
      : state.controls ? { ok: true, status: 'observed', answer: `网易云全屏播放页，底部是控制栏（喜欢、循环、音效、极高、看MV、上一首、暂停、下一首、词、播放样式、播放列表），进度条上方右下角有一个麦克风图标，旁边写着「${state.karaoke ? '关闭随心唱' : '开启随心唱'}」` }
      : { ok: true, status: 'observed', answer: '网易云全屏播放页：左边黑胶唱片，右边滚动歌词，底部没有控制栏' },
    open_app: (a) => { if (/爱奇艺|奇异果/.test(a.app) && !state.iqiyi) return { ok: false, status: 'not_found',
        message: '电视上还没装「银河奇异果」（小米应用商店里有），可以调用 install_app 装好再打开' };
      if (/爱奇艺|奇异果/.test(a.app)) { state.fg = 'com.gitvdemo.video'; state.screen = '前台：银河奇异果 com.gitvdemo.video\n焦点：[1]\n[1] 按钮「搜索」 焦点 可点\n[2] 卡片「狂飙」 可点'; }
      else if (/哔哩|B站|bili/i.test(a.app)) { state.fg = 'tv.danmaku.bili'; state.screen = screens.biliHome; }
      else if (/网易云/.test(a.app)) { state.fg = 'com.netease.cloudmusic.tv'; state.screen = '前台：网易云音乐 com.netease.cloudmusic.tv\n[1] 按钮「搜索」 焦点 可点\n[2] 卡片「每日推荐」 可点'; }
      return { ok: true, status: 'observed', foreground: state.fg, screen: state.screen }; },
    bili_search: (a) => { (state.keywords ??= []).push(String(a.keyword)); state.fg = 'tv.danmaku.bili'; state.screen = state.results ?? screens.biliResults(a.keyword);
      return { ok: true, status: 'observed', message: `哔哩哔哩搜索页已显示“${a.keyword}”的结果`, screen: state.screen }; },
    screen_click: (a) => { (state.clicks ??= []).push(String(a.id));
      if (state.karaokeSim) {
        if (state.screen === NE_TUTORIAL) { state.screen = NE_PLAYER; state.controls = false; return { ok: true, status: 'observed', message: '教程关了', screen: state.screen }; }
        if (state.controls && String(a.id) === '14') { state.karaoke = !state.karaoke; state.karaokeClicks = (state.karaokeClicks ?? 0) + 1;
          return { ok: true, status: 'observed', message: '已点选「图标」', screen: state.screen }; }
        return { ok: true, status: 'observed', message: `已点选 [${a.id}]`, screen: state.screen };
      }
      // 云视听极光: the series card opens its detail page, 播放 there starts episode 1
      if (state.fg === 'com.ktcp.video') {
        const line = String(state.screen).split('\n').find((l) => l.startsWith(`[${a.id}]`)) ?? '';
        if (/卡片「[^」]*」 电视剧/.test(line)) {
          const title = line.replace(/^.*卡片「|」.*$/g, '');
          state.screen = `前台：云视听极光 com.ktcp.video/.DetailActivity\n焦点：[1]\n[1] 按钮「播放」 焦点 可点\n[2] 按钮「选集」 可点\n[3] 按钮「收藏」 可点\n[4] 文本「${title}」\n[5] 文本「更新至全39集 · VIP」`;
          return { ok: true, status: 'observed', message: `已点选「${title}」，进入详情页`, screen: state.screen };
        }
        if (/按钮「播放」/.test(line)) {
          const title = (String(state.screen).match(/\[4\] 文本「([^」]*)」/) ?? [])[1] ?? '';
          state.playing = { app: 'com.ktcp.video', state: 'playing', title: `${title} 第1集` };
          state.screen = `前台：云视听极光 com.ktcp.video/.PlayerActivity\n[1] 视频画面 可点`;
          return { ok: true, status: 'observed', message: `已点选「播放」，正在播放《${title} 第1集》`, playing: state.playing, screen: state.screen };
        }
      }
      if (state.screen === screens.biliUp && /^[5-8]$/.test(String(a.id))) { const title = state.screen.split('\n').find((l) => l.startsWith(`[${a.id}]`)).replace(/^.*「|」.*$/g, '');
        state.screen = screens.biliPlayer.replace('星辰亻旅行者：第一期 城市夜景延时', title); state.playing = { app: 'tv.danmaku.bili', state: 'playing', title };
        return { ok: true, status: 'observed', message: `已点选「${title}」，页面换成UnitedBizDetailsActivity，正在播放《${title}》`, playing: state.playing, screen: state.screen }; }
      if (state.screen.includes('SearchResultActivity') && String(a.id) === '5') { state.screen = screens.biliPlayer; state.playing = { app: 'tv.danmaku.bili', state: 'playing', title: '星辰亻旅行者：第一期 城市夜景延时' };
        return { ok: true, status: 'observed', message: '已点选，正在播放《星辰亻旅行者：第一期 城市夜景延时》', playing: state.playing, screen: state.screen }; }
      return { ok: true, status: 'observed', screen: state.screen }; },
    press_keys: (a) => { const keys = [].concat(a.keys ?? []).map(String); (state.keys ??= []).push(...keys);
      if (state.karaokeSim) {
        if (keys.includes('up')) { state.controls = false; state.screen = NE_TUTORIAL; }
        else if (state.screen === NE_TUTORIAL) { state.screen = NE_PLAYER; }
        else if (keys.includes('down') || keys.includes('center')) { state.controls = true; state.screen = NE_CONTROLS; }
      }
      return { ok: true, status: 'observed', screen: state.screen }; },
    screen_input: () => ({ ok: true, status: 'observed', screen: state.screen }),
    media_now_playing: () => ({ ok: true, session: state.playing ?? null }),
    // the X search (Grok): what it found on 2026-11-06 for Tibo's resets
    x_search: (a) => { (state.xsearches ??= []).push(String(a.query ?? ''));
      const answer = /thsottiaux|tibo|重置|reset/i.test(String(a.query)) ? 'Tibo 最新一条是北京时间11月2日早上7点27分的「Day 28/ Banked 2/2」，28天挑战的最后一天，第二次可以自己兑换的用量重置。'
        : '没有找到相关的帖子。';
      return { ok: true, status: 'observed', answer, searched: true, millis: 40000 }; },
    // NeteaseMusic.play only succeeds once the recording plays, and says which one; an artist alone (Contract.check drops
    // an empty title) is TvTools.playArtist: NetEase's voice search plays a run of the singer's songs (2026-11-07)
    netease_play: (a) => { const title = String(a.title ?? '').trim(), artist = String(a.artist ?? '').trim();
      (state.songArtists ??= []).push(artist);
      if (!title && artist) { state.playing = { app: 'com.netease.cloudmusic.tv', state: 'playing', title: artist === '周杰伦' ? '晴天' : '江南', artist, run: true };
        return { ok: true, status: 'observed', message: `网易云正在连续播放${artist}的歌，现在是《${state.playing.title}》，一共排了 30 首。`, session: state.playing, queue: 30 }; }
      // songs whose singer the model may not know (2026-11-08 on the TV): a guessed singer finds nothing, as NeteaseCatalog.find
      const singer = SINGERS[title];
      if (singer && artist && !artist.includes(singer)) return { ok: false, status: 'not_found', message: '网易云曲库没有找到同时符合歌名和歌手的版本。' };
      state.playing = { app: 'com.netease.cloudmusic.tv', state: 'playing', title: title || '江南', artist: singer || artist || '林俊杰', position: '0:01' };
      return { ok: true, status: 'played', message: `网易云音乐正在播放${state.playing.artist}的《${state.playing.title}》。`, track: state.playing }; },
    xiaoai_ask: (a) => ({ ok: true, status: 'submitted', execution_confirmed: false, message: '已转交小爱' }),
    // 云视听极光 (seen on the TV 2026-11-10): its pages are self-drawn, so the screen reads as nothing; the search page
    // opens with the keyword in its box, and a show opens by its cover id from Tencent Video's own search
    tencent_video_search: (a) => { (state.videoSearches ??= []).push(String(a.keyword)); state.fg = 'com.ktcp.video';
      state.screen = '前台：云视听极光 com.ktcp.video（SearchActivity）\n（没有读到界面元素：可能是视频画面、自绘界面或受保护画面，可以用 screen_look 看图）';
      return { ok: true, status: 'observed', message: `云视听极光的搜索页已打开，搜的是「${a.keyword}」。这个应用的界面读不出文字：要看结果用 screen_look，要直接打开某一部用 tencent_video_open`, screen: state.screen }; },
    tencent_video_open: (a) => { const title = String(a.title).replace(/[《》\s]/g, ''); (state.videoOpens ??= []).push({ title, play: a.play !== false, episode: Number.isInteger(a.episode) && a.episode >= 1 ? a.episode : 0 });
      const elsewhere = { '狂飙': ['爱奇艺', 39, '银河奇异果'], '甄嬛传': ['优酷', 76, '酷喵'] }[title];
      if (elsewhere) return { ok: false, status: 'not_here', platforms: [elsewhere[0]],
        message: `腾讯视频（云视听极光）没有《${title}》（电视剧）：它在${elsewhere[0]}，共 ${elsewhere[1]} 集。电视上没装${elsewhere[2]}（${elsewhere[0]}的电视版）`,
        note: `把上面这句话告诉用户就行：不要自己安装应用，也不要打开别的应用去找；用户说要装${elsewhere[2]}时再用 install_app` };
      state.fg = 'com.ktcp.video';
      state.screen = '前台：云视听极光 com.ktcp.video\n（没有读到界面元素：可能是视频画面、自绘界面或受保护画面，可以用 screen_look 看图）';
      // TvTools since 2026-11-11 (job tencent-episode): an episode asked for is opened by its own id, or said not to exist
      if (Number.isInteger(a.episode) && a.episode >= 1) return a.episode > 30
        ? { ok: false, status: 'no_episode', message: `《${title}》在腾讯视频没有第 ${a.episode} 集（一共 30 集）`,
            note: '把上面这句话告诉用户就行：不要改放别的集，也不要去别处搜；用户说了要看哪一集再打开' }
        : { ok: true, status: 'observed', message: `已在云视听极光播放《${title}》第 ${a.episode} 集；不是会员的话正片前有一两分钟广告` };
      return a.play === false
        ? { ok: true, status: 'observed', message: `已在云视听极光打开《${title}》（电视剧，2023，共 30 集）的详情页，按确认键全屏播放` }
        : { ok: true, status: 'observed', message: `已在云视听极光播放《${title}》（电视剧，2023，共 30 集）；不是会员的话正片前有一两分钟广告` }; },
    // as Memory.addFact: a fact the profile already has is not written again, and the answer says so (本来就记得)
    remember: (a) => { (state.remembered ??= []).push(a.fact);
      const norm = (s) => String(s ?? '').replace(/[\s，。,.!！?？、“”"「」]/g, '');
      const known = norm(a.fact) && (state.profile ?? '').split('\n').some((line) => norm(line.replace(/^\s*-\s*/, '')) === norm(a.fact));
      return known ? { ok: true, status: 'duplicate', message: `本来就记得：${a.fact}`, facts: 1 }
        : { ok: true, status: 'recorded', message: `记住了：${a.fact}`, facts: 1 }; },
    forget: (a) => ({ ok: true, status: 'forgotten', removed: [a.what], message: `已忘掉和「${a.what}」有关的 1 条记忆` }),
    remember_name: (a) => ({ ok: true, status: 'recorded', message: `记住了：「${a.heard}」写作「${a.written}」` }),
    set_volume: (a) => ({ ok: true, status: 'observed', message: `音量现在是 ${a.level}/100`, volume: a.level }),
    sleep_timer: (a) => ('minutes' in a ? { ok: true, status: 'observed', message: a.minutes === 0 ? '休眠定时已取消' : `已设置 ${a.minutes} 分钟后休眠` }
      : { ok: true, status: 'observed', message: '没有休眠定时' }),
    install_app: (a) => { if (/爱奇艺|奇异果|gitvdemo/.test(a.app ?? '')) { state.iqiyi = true;
        return { ok: true, status: 'installed', message: '已从小米应用商店装好银河奇异果，可以打开了', package: 'com.gitvdemo.video' }; }
      // 「你点头就装」 (jobs install-consent and find-installer, 2026-11-07): {url} downloads and reads, {token} installs
      if (a.token) { (state.installs ??= []).push('token');
        if (a.token !== 'c1') return { ok: false, status: 'unknown_token', message: '这个安装确认不存在或已经用过：重新下载，并把来源、签名、包名和大小告诉用户之后再装' };
        state.viaInstalled = true;
        return { ok: true, status: 'installed', label: 'Via', package: 'mark.via', message: '已安装「Via」（mark.via 6.5.0），在首页的「全部」里，也可以直接说“打开Via”' }; }
      if (a.url) { (state.installs ??= []).push('url');
        if (!/res\.viayoo\.com/.test(a.url)) return { ok: false, status: 'refused', message: '下载到的文件不是能读的 Android 安装包，没有安装' };
        return { ok: true, status: 'needs_consent', token: 'c1', label: 'Via', package: 'mark.via', version: '6.5.0', size_mb: 3,
          signer_org: 'Shanghai Zhiwei Network Technology Co., Ltd.', signer_cn: 'Via', host: 'res.viayoo.com', page_host: 'viayoo.com',
          note: '还没安装。签名上的公司是这个应用的开发者，就直接调用 install_app {"token":…}（只能用一次）：系统会把来源网站、开发者签名、包名和大小念给用户并请他说「好的」，同意了才装。签名上的公司明显不是开发者（个人名字、下载站、破解者），就不要调用，直接告诉用户这个安装包不可信。' }; }
      // job official-installs (2026-11-07): the browser from TV Bro's own GitHub releases, its key checked
      if (/浏览器|tv ?bro|上网/i.test(a.app ?? '')) { state.browser = true;
        return { ok: true, status: 'installed', message: '已从 TV Bro 在 GitHub 的官方发布页下载、核对开发者签名并装好 TV Bro 浏览器 2.1.6', package: 'com.phlox.tvwebbrowser',
          note: '浏览器装好了：要打开网页就用 open_link 打开网址（https://…），会在 TV Bro 里打开' }; }
      return { ok: false, status: 'not_found', message: `小米电视应用商店的目录里没有「${a.app}」` }; },
    explore_app: (a) => /哔哩|B站|bili/i.test(a.app ?? '')
      ? { ok: true, status: 'observed', message: '哔哩哔哩 8.91.1 能直接打开：bilibili://search、space、history、main/favorite、following、video、live、bangumi、rank、user_center、user_center/vip、pgc、feed/channel、music、article…等 40 个；https://www.bilibili.com、live.bilibili.com、m.bilibili.com…', package: 'tv.danmaku.bili' }
      : (state.apps ?? []).some((x) => a.app && (x === a.app || String(a.app).includes(x) || x.includes(String(a.app))))
        ? { ok: true, status: 'observed', message: `${a.app} 没有登记能直接打开的页面：用 open_app 打开它，再看屏幕` }
        : { ok: false, status: 'not_found', message: `电视上没装「${a.app}」：要用的话先用 install_app 装上` },
    app_note: (a) => { (state.appNotes ??= []).push(a.note); return { ok: true, status: 'recorded', message: `记下了：在${a.app}里，${a.note}` }; },
    // find_installer (job find-installer): the search model's real answers of 2026-11-07 23:5x (fixtures installer-*.txt)
    find_installer: (a) => { (state.finds ??= []).push(a.app);
      if (/via/i.test(a.app ?? '')) return { ok: true, status: 'observed', found: true, app: 'Via浏览器', developer: '上海知为网络科技有限公司',
        page: 'https://viayoo.com/zh-cn/docs/get-via.html', url: 'https://res.viayoo.com/v1/via-release-cn.apk', package: '', note: '官网下载页直达开发者自有 CDN 上的 APK。' };
      if (/红果/.test(a.app ?? '')) return { ok: false, status: 'not_found', developer: '北京笔墨留香科技有限公司', page: 'https://applink.novelquickapp.com/',
        message: '官网没有公开的安装包直链（比如只能扫码下载）：不要去下载站找；有官方网页版的就用浏览器打开' };
      return { ok: false, status: 'not_found', message: '官网没有公开的安装包直链（比如只能扫码下载）：不要去下载站找；有官方网页版的就用浏览器打开' }; },
    open_link: (a) => { (state.links ??= []).push(a.uri);
      // a web address that is not B站's: only a browser takes it (the TV has none until install_app 浏览器)
      if (/^https?:\/\//i.test(a.uri ?? '') && !/bilibili\.com/i.test(a.uri ?? '')) {
        if (!state.browser) return { ok: false, status: 'unavailable', message: '电视上没有应用能打开这个链接' };
        state.fg = 'com.phlox.tvwebbrowser';
        state.screen = `前台：TV Bro com.phlox.tvwebbrowser\n[1] 文本「${a.uri}」\n[2] 按钮「播放正片」 可点`;
        return { ok: true, status: 'observed', message: '已用TV Bro打开', screen: state.screen };
      }
      // a season by B站's own link (Douban's for a film; TvTools.openBiliVideo, as a video)
      const season = (String(a.uri ?? '').match(/^bilibili:\/\/(?:pgc|bangumi)\/season\/(\d+)/) ?? [])[1];
      if (season) {
        const title = { 44847: '流浪地球2', 28747: '凡人修仙传 第198话' }[season] ?? 'B站番剧';
        state.fg = 'tv.danmaku.bili';
        state.playing = { app: 'tv.danmaku.bili', state: 'playing', title };
        state.screen = screens.biliPlayer.replace('星辰亻旅行者：第一期 城市夜景延时', title);
        return { ok: true, status: 'observed', message: `已用哔哩哔哩打开，正在播放《${title}》`,
          playing: { app: '哔哩哔哩', package: 'tv.danmaku.bili', state: 'playing', title }, screen: state.screen };
      }
      // an episode by B站's web address (TvTools.openBiliVideo, as a video)
      const ep = (String(a.uri ?? '').match(/^https?:\/\/(?:www|m)\.bilibili\.com\/bangumi\/play\/ep(\d+)/) ?? [])[1];
      if (ep) {
        const title = { 3854822: '凡人修仙传 第198话', 3854823: '凡人修仙传 第199话', 3870001: '名侦探柯南 第1278话' }[ep] ?? 'B站番剧';
        state.fg = 'tv.danmaku.bili';
        state.playing = { app: 'tv.danmaku.bili', state: 'playing', title };
        state.screen = screens.biliPlayer.replace('星辰亻旅行者：第一期 城市夜景延时', title);
        return { ok: true, status: 'observed', message: `已用哔哩哔哩打开，正在播放《${title}》`,
          playing: { app: '哔哩哔哩', package: 'tv.danmaku.bili', state: 'playing', title }, screen: state.screen };
      }
      // a video by its aid or BV number (TvTools.openBiliVideo): the screen lit first, the player page, the new video playing
      if (/^bilibili:\/\/video\/(?:\d+|BV\w+)/.test(a.uri ?? '')) {
        state.screenOn = true;
        const title = biliTitle(a.uri.replace(/^bilibili:\/\/video\//, '').replace(/[?#/].*$/, '')) ?? 'B站视频';
        state.fg = 'tv.danmaku.bili';
        state.playing = { app: 'tv.danmaku.bili', state: 'playing', title };
        state.screen = screens.biliPlayer.replace('星辰亻旅行者：第一期 城市夜景延时', title);
        return { ok: true, status: 'observed', message: `已用哔哩哔哩打开，正在播放《${title}》`,
          playing: { app: '哔哩哔哩', package: 'tv.danmaku.bili', state: 'playing', title }, screen: state.screen };
      }
      if (/^bilibili:\/\/(history|main\/favorite|space|following)/.test(a.uri ?? '')) {
        state.fg = 'tv.danmaku.bili';
        const page = /history/.test(a.uri) ? '历史记录' : /favorite/.test(a.uri) ? '我的收藏' : /space/.test(a.uri) ? '个人空间' : '我的关注';
        state.screen = `前台：哔哩哔哩 tv.danmaku.bili\n焦点：[1]\n[1] 文本「${page}」\n[2] 卡片「【MAD/燃向】时代的回声 · 星辰亻旅行者」 可点`;
        return { ok: true, status: 'observed', message: `已打开${page}`, screen: state.screen };
      }
      return { ok: false, status: 'no_change', message: '这个链接没有打开任何页面' }; },
    // HintTools' gate since 2026-11-11 (job own-ui, Hints.switchAsked): the card is switched only when the sentence asks
    // for it; 「每次给你提示」「没有提示我看不到我的输入」 are about the panel
    set_hints: (a) => {
      const said = String(state.say ?? '');
      const about = /为什么|怎么|看不到|没有提示|每次|我的输入/.test(said);
      const off = !about && /提示/.test(said) && /别弹|不要弹|不用弹|别再弹|关掉|关闭|关了|取消|不要|别给我|停掉/.test(said);
      const on = !about && /提示/.test(said) && /打开|开启|恢复|重新弹|继续弹|要弹/.test(said);
      if ((a.on === false && !off) || (a.on !== false && !on)) return { ok: false, status: 'denied',
        message: '用户这句话不是在要求开关亮屏提示卡，不要改这个设置。他说的“提示”“面板”多半是星仪自己右上角的执行面板：照实回答面板的事。' };
      state.hintsSwitched = true;
      return { ok: true, status: 'recorded', message: a.on === false ? '好的，以后亮屏不弹提示了。' : '好的，亮屏时会弹提示卡。' }; },
    screen_off: () => { const was = state.screenOn; state.screenOn = false;
      // QuickCommand.SCREEN_OFF / SCREEN_OFF_ALREADY, the words TvTools.screen_off answers with
      return { ok: true, status: 'observed', message: was ? '屏幕关了，电视还在待机，叫一声小爱同学就能用' : '屏幕本来就是关着的，电视在待机' }; },
    wake_screen: () => { const was = state.screenOn; state.screenOn = true;
      return { ok: true, status: 'observed', message: was ? '屏幕本来就开着' : '屏幕已点亮' }; },
    schedule_task: (a) => { (state.schedules ??= []).push(a);
      const when = a.in_minutes ? `${a.in_minutes} 分钟后` : `${a.date ? a.date.slice(5).replace('-', '月') + '日' : a.repeat === 'daily' ? '每天' : a.repeat === 'weekdays' ? '工作日' : '明天'} ${a.time ?? ''}`;
      return { ok: true, status: 'scheduled', message: a.kind === 'remind' ? `好的，${when}提醒你：${a.what}。` : `好的，${when} ${a.what}。`, id: state.schedules.length }; },
    list_schedules: () => ({ ok: true, status: 'observed', message: (state.schedules ?? []).length ? `现在有 ${state.schedules.length} 个预约` : '现在没有预约。' }),
    cancel_schedule: (a) => ({ ok: true, status: 'cancelled', message: `取消了：${a.which}。` }),
    note_add: (a) => { (state.notes ??= []).push(a.text); return { ok: true, status: 'recorded', message: `记下了：${a.text}` }; },
    note_list: () => ({ ok: true, status: 'observed', message: state.noteList ?? '随手记里没有东西。' }),
    note_done: (a) => ({ ok: true, status: 'done', message: `划掉了：${a.which}。` }),
    // the public data feeds (Quotes, Rates, HotList; 2026-11-06)
    stock_quote: (a) => { (state.quotes ??= []).push(a);
      const q = String(a.query ?? '').trim();
      if (!q || /大盘|股市|A股/.test(q)) return { ok: true, answer: '上证指数 3636.80 点，跌 1.44%，跌了 53.11 点；深证成指 11221.47 点，跌 2.18%，跌了 249.62 点；创业板指 2478.42 点，跌 3.44%，跌了 88.38 点（9 点 49 分）' };
      if (/茅台/.test(q)) return { ok: true, answer: '贵州茅台 1453.08 元，涨 0.07%（9 点 49 分）' };
      if (/纳斯达克|纳指|美股|道琼斯|标普/.test(q)) return { ok: true, answer: '道琼斯 51523.06 点，跌 3.43%；纳斯达克 24315.00 点，跌 5.52%；标普500 7353.73 点，跌 4.36%（美东时间 11 月 3 日收盘）' };
      // 2026-11-07 on the TV: 智谱 heard as 「质朴」 is found by its sound (job names-by-sound), MiniMax by its name
      const one = (p) => /智谱/.test(p) ? '智谱 426.20 港元，跌 10.46%（11 月 6 日收盘）'
        : /质朴/.test(p) ? '「质朴」按读音查的是智谱：智谱 426.20 港元，跌 10.46%（11 月 6 日收盘）'
        : /minimax/i.test(p) ? 'MINIMAX 155.80 港元，跌 8.46%（11 月 6 日收盘）'
        // a ticker of its own: 「查询MAX最新的股价」 got MediaAlpha on the TV (2026-11-07 12:20)
        : /^max$/i.test(p) ? 'MediaAlpha 14.20 美元，跌 1.73%（美东时间 11 月 6 日收盘）' : null;
      const parts = q.replace(/mini\s*max/ig, 'MINIMAX').split(/[、,，;；\s]+|和|与/).filter(Boolean);
      if (parts.length && parts.every(one)) return { ok: true, answer: parts.map(one).join('；') };
      return { ok: false, status: 'not_found', message: `没找到「${q}」这只股票或指数：换成公司全名或股票代码再试；还不行就用 web_search` }; },
    exchange_rate: (a) => { (state.rates ??= []).push(a);
      // Rates.code takes ISO codes as well as names (USD/JPY from the model, 2026-11-07 run: the sim missed them)
      const name = (x) => ({ USD: '美元', JPY: '日元', EUR: '欧元', CNY: '人民币', HKD: '港币', GBP: '英镑' })[String(x).toUpperCase()] ?? String(x);
      const from = name(a.from ?? '美元'), to = name(a.to ?? '人民币'), n = Number(a.amount ?? 1);
      if (/欧元/.test(from) && /人民币|^$/.test(to)) return { ok: true, answer: `今天人民币汇率中间价：1 欧元兑 7.5191 元人民币${n !== 1 ? `；${n} 欧元约合 ${(n * 7.5191).toFixed(2)} 元人民币` : ''}（11 月 4 日 9 点 15 分公布）` };
      if (/日元/.test(to)) return { ok: true, answer: `${n} 美元约合 ${(n * 157.84).toFixed(2)} 日元（国际市场参考价，每天更新一次）` };
      return { ok: true, answer: `今天人民币汇率中间价：1 美元兑 6.7168 元人民币，人民币比上个交易日调贬 12 个基点${n !== 1 ? `；${n} 美元约合 ${(n * 6.7168).toFixed(2)} 元人民币` : ''}（11 月 4 日 9 点 15 分公布）` }; },
    // Huangli (Baidu's calendar card, 2026-11-07): 11-07 宜打扫 忌馀事勿取; 11-08 忌结婚、搬家
    almanac: (a) => { (state.almanacs ??= []).push(a);
      const thing = String(a.thing ?? '').trim(), date = String(a.date ?? '今天').trim();
      if (a.month && thing) return { ok: true, status: 'observed', final: true, answer: /结婚|嫁娶/.test(thing) ? '下个月宜结婚的日子有 3 天：12 月 4 日（周五）、12 月 17 日（周四）、12 月 24 日（周四）' : `下个月黄历上没有宜${thing}的日子`, source: '百度万年历' };
      if (/明天|11-0?5/.test(date)) return { ok: true, status: 'observed', final: true, answer: /搬家|结婚/.test(thing) ? `明天（11 月 5 日，农历九月廿七，癸未日）忌${thing}；这天宜安床、祭祀、入殓、移柩、补垣、开池等` : '明天（11 月 5 日，农历九月廿七，癸未日）宜安床、祭祀、入殓、移柩、补垣、开池、破土、迁坟；忌结婚、搬家、搬新房、开业、作灶、出火、收养子女、掘井等', source: '百度万年历' };
      return { ok: true, status: 'observed', final: true, answer: thing ? `今天（11 月 4 日，农历九月廿六，壬午日）宜打扫，忌馀事勿取：${thing}不宜` : '今天（11 月 4 日，农历九月廿六，壬午日）宜打扫；忌馀事勿取（其余的事都不宜）', source: '百度万年历' }; },
    // Alerts (the National Meteorological Center, 2026-11-07): no warning in Shanghai, no typhoon that night
    weather_alert: (a) => { (state.alerts ??= []).push(a);
      const place = String(a.place ?? '').trim();
      const ty = a.typhoon === true || /台风/.test(state.say ?? '');
      const alarms = /云南/.test(place) ? '云南现在有 28 条气象预警：普洱市景谷傣族彝族自治县发布了雷电黄色预警（今天凌晨 2 点 51 分）；文山壮族苗族自治州麻栗坡县发布了暴雨黄色预警（今天凌晨 2 点 51 分）；红河哈尼族彝族自治州屏边苗族自治县发布了暴雨黄色预警（今天凌晨 2 点 46 分）；另外还有 25 条，多是暴雨、雷电预警'
        : `${place || '上海'}现在没有生效的气象预警`;
      const answer = ty ? (/没有生效/.test(alarms) ? `现在没有活跃的台风，${place || '上海'}也没有生效的气象预警` : `现在没有活跃的台风。${alarms}`) : alarms;
      return { ok: true, status: 'observed', answer, source: '中央气象台' }; },
    // Bangumi (B站's anime and 国创 timelines, 2026-11-07)
    bili_bangumi: (a) => { (state.bangumi ??= []).push(a);
      const t = String(a.title ?? '').replace(/[《》]/g, '');
      if (!t) return { ok: true, status: 'observed', source: 'B站番剧更新表', answer: '今天B站更新的番剧和国创有 6 部：上午 9 点《万古至尊：李云霄传》第 34 话；上午 10 点《功夫熊猫：神龙骑士 第二季》第 18 话；上午 11 点《凡人修仙传》第 199 话；下午 5 点《好奇心俱乐部（Question Club）》第 14 话；晚上 7 点《我是不白吃》第 1102 话；晚上 7 点 30 分《名侦探柯南》第 1279 话',
        episodes: [{ title: '凡人修仙传', episode: '第199话', when: '今天上午 11 点', out: false, link: 'https://www.bilibili.com/bangumi/play/ep3854823' }],
        note: '要看哪一部就用 open_link 打开它的 link（还没更新的打不开）' };
      if (/凡人修仙传/.test(t)) return { ok: true, status: 'observed', source: 'B站番剧更新表', answer: '《凡人修仙传》每周六上午 11 点更新：最新一集是第 198 话（10 月 31 日）；下一集第 199 话今天上午 11 点更新',
        link: 'https://www.bilibili.com/bangumi/play/ep3854822', season_link: 'bilibili://bangumi/season/28747', note: '要放最新一集就用 open_link 打开 link' };
      if (/柯南/.test(t)) return { ok: true, status: 'observed', source: 'B站番剧更新表', answer: '《名侦探柯南》每周六晚上 7 点 30 分更新：最新一集是第 1278 话（10 月 31 日）；下一集第 1279 话今天晚上 7 点 30 分更新',
        link: 'https://www.bilibili.com/bangumi/play/ep3870001', season_link: 'bilibili://bangumi/season/33378', note: '要放最新一集就用 open_link 打开 link' };
      return { ok: false, status: 'not_found', message: `B站这两周的更新表上没有「${t}」：可能已经完结，或者不在B站。要找它就用 bili_search 在B站里搜，问别的事用 web_search` }; },
    // MusicNew (NetEase's artist albums, 2026-11-07)
    music_new: (a) => { (state.musicNew ??= []).push(a);
      const who = String(a.artist ?? '');
      if (/林俊杰|JJ/i.test(who)) return { ok: true, status: 'observed', source: '网易云音乐', artist: '林俊杰',
        answer: '林俊杰最近发了新歌：10 月 10 日上线的单曲《Rise Without A Sound》；之前是 2 月 27 日的单曲《我对缘分小心翼翼》、2025 年 10 月 3 日的单曲《为你揭晓》',
        releases: [{ title: 'Rise Without A Sound', kind: '单曲', songs: 1, date: '10 月 10 日' }, { title: '我对缘分小心翼翼', kind: '单曲', songs: 1, date: '2 月 27 日' }],
        note: '要放就用 netease_play：title 填单曲的名字（专辑填专辑里的歌名或者只填 artist），artist 填歌手' };
      if (/周杰伦|周董/.test(who)) return { ok: true, status: 'observed', source: '网易云音乐', artist: '周杰伦',
        answer: '周杰伦最近一次发歌是 3 月 26 日的专辑《太阳之子》（13 首），差不多 7 个月前；之前是 2025 年 6 月 6 日的单曲《即兴曲》',
        releases: [{ title: '太阳之子', kind: '专辑', songs: 13, date: '3 月 26 日' }] };
      return { ok: false, status: 'not_found', message: `网易云上没找到歌手「${who}」：名字可能听错了，问用户或者用 web_search` }; },
    // Movies.speakList (Douban's lists, 2026-11-07)
    movie_list: (a) => { (state.movieLists ??= []).push(a);
      const l = String(a.list ?? '电影');
      if (/韩剧/.test(l)) return { ok: true, status: 'observed', source: '豆瓣', answer: '近期热门韩剧：第一，《末路兼职》还没有评分，李宰旭、高旻示；第二，《我的有罪之人》8.5 分，金宥贞、郑京浩；第三，《现在不是出轨的问题》8.7 分，申敏儿、金英大' };
      if (/综艺/.test(l)) return { ok: true, status: 'observed', source: '豆瓣', answer: '近期热门综艺：第一，《后会友期》8.7 分，张萌、马天宇；第二，《现在就出发 第四季》8.0 分，沈腾、白敬亭；第三，《骑有此理 第四季》还没有评分，吴磊' };
      if (/剧|动画/.test(l)) return { ok: true, status: 'observed', source: '豆瓣', answer: '近期热门剧集：第一，《小城良方》8.0 分，肖战、倪大红；第二，《嫁金钗》7.0 分，田曦薇、闫桉；第三，《蒸蒸日上》7.8 分，王皓、高叶' };
      return { ok: true, status: 'observed', source: '豆瓣', answer: '正在上映的热门电影：《欢迎来龙餐馆》8.7 分；《奥德赛》8.6 分；《生化危机：爆发夜》7.3 分；《空枪》7.2 分；这几天新上映：《我们仅有一次的人生》（今天）、《心迷宫之三重罪》（今天）' }; },
    // Movies (Douban's mobile search, 2026-11-07)
    movie_rating: (a) => { (state.movies ??= []).push(a);
      const t = String(a.title ?? '').replace(/[《》]/g, '');
      // where it streams, how many episodes (TvTools: where=true or the request's words; a whole answer, marked final)
      if (a.where === true || /哪个平台|什么平台|在哪看|在哪能看|哪里能看|哪里看|哪能看|多少集|几集|片长/.test(state.say ?? '')) {
        if (/流浪地球2|流浪地球二/.test(t)) return { ok: true, status: 'observed', final: true, source: '豆瓣', answer: '《流浪地球2》（2023 年的电影，豆瓣 8.3 分）可以在腾讯视频、咪咕视频、爱奇艺、哔哩哔哩、优酷视频看（都是VIP免费观看），片长 173 分钟',
          vendors: [{ name: '腾讯视频', pay: 'VIP免费观看', tv: '电视上用云视听极光（tencent_video_search）' }, { name: '哔哩哔哩', pay: 'VIP免费观看', tv: '电视上用 open_link 打开它的 link', link: 'bilibili://pgc/season/44847' }],
          note: '用户要在电视上看：有B站的 link 就先用 open_link 打开它（一步进播放页）；没有再用 tencent_video_search 在云视听极光里搜腾讯视频的；别的平台照 tv 一栏说' };
        if (/狂飙/.test(t)) return { ok: true, status: 'observed', final: true, source: '豆瓣', answer: '《狂飙》（2023 年的电视剧，豆瓣 8.5 分）可以在爱奇艺看（VIP免费观看），共 39 集',
          vendors: [{ name: '爱奇艺', pay: 'VIP免费观看', tv: '电视上是银河奇异果（小米应用商店里有）' }] };
        if (/繁花/.test(t)) return { ok: true, status: 'observed', final: true, source: '豆瓣', answer: '《繁花》（2023 年的电视剧，豆瓣 8.7 分）可以在腾讯视频看（VIP免费观看），共 30 集',
          vendors: [{ name: '腾讯视频', pay: 'VIP免费观看', tv: '电视上用云视听极光（tencent_video_search）' }] };
      }
      if (/流浪地球2|流浪地球二/.test(t)) return { ok: true, status: 'observed', answer: '《流浪地球2》（2023 年的电影）豆瓣 8.3 分，143 万人评价；中国大陆，科幻、冒险、灾难，导演郭帆，主演吴京、刘德华', source: '豆瓣' };
      if (/狂飙/.test(t)) return { ok: true, status: 'observed', answer: '《狂飙》（2023 年的电视剧）豆瓣 8.5 分，107 万人评价；中国大陆，剧情、犯罪，导演徐纪周，主演张译、张颂文', source: '豆瓣' };
      if (/繁花/.test(t)) return { ok: true, status: 'observed', answer: '《繁花》（2023 年的电视剧）豆瓣 8.7 分，56 万人评价；中国大陆，剧情、爱情，导演王家卫，主演胡歌、马伊琍', source: '豆瓣' };
      return { ok: false, status: 'not_found', message: `豆瓣上没找到「${t}」：片名可能听错了，换个叫法再查，或者用 web_search` }; },
    bili_videos: (a) => { (state.biliVideos ??= []).push(a); const r = biliVideos({ ...a, _said: state.say });
      // TvTools: asked whether the UP is live (live=true, or the request's words), the answer says so
      if (r.ok && r.up && (a.live === true || /在直播|直播吗|直播没|开播|在播吗|播了吗|直播了吗/.test(state.say ?? ''))) {
        const v = r.videos[0];
        r.answer = r.message = v ? `${r.up.name}现在没在直播；最新的视频是${v.date}发的《${v.title}》` : `${r.up.name}现在没在直播`;
        r.final = true;
      }
      return r; },
    hot_list: (a) => { (state.hots ??= []).push(a);
      if (/网易|歌/.test(String(a.source ?? ''))) return { ok: true, answer: '网易云热歌榜前 3 首：第一，《Hong Kong》，The Weeknd；第二，《我不难过》，孙燕姿；第三，《明知故犯》，Max李玄',
        note: '用户要听其中哪一首，用 netease_play（title、artist）' };
      if (/b站|bili|哔哩/i.test(String(a.source ?? ''))) return { ok: true, answer: 'B站热门前 3 个：第一，《物理意义上烫烫的一集》，吴磊LEO，131 万播放；第二，《真的需要摸吗？MacBook Pro》，影视飓风，68 万播放；第三，《幸福PV》，崩坏因缘精灵，424 万播放',
        videos: [{ title: '物理意义上烫烫的一集', up: '吴磊LEO', link: 'bilibili://video/BV1mpxR6KESh' }, { title: '真的需要摸吗？MacBook Pro', up: '影视飓风', link: 'bilibili://video/BV1NS4t6oEXQ' }],
        note: '用户要看其中哪一个，用 open_link 打开它的 link（bilibili://video/BV号）' };
      return { ok: true, answer: '百度热搜前 5 条：第一，美股暴跌 遭遇历史性抛售；第二，东风-61已列装火箭军；第三，6G有啥不一样；第四，54岁大哥靠AI短剧还清负债；第五，多地迎来降温', topics: ['美股暴跌 遭遇历史性抛售', '东风-61已列装火箭军', '6G有啥不一样'] }; },
    nba_games: (a) => a.team ? { ok: true, answer: `${a.team}上一场是昨天上午 8 点 30 分，主场 135 比 105 赢了公牛；下一场是 11 月 8 日上午 9 点，客场打骑士` }
      : /明天|11-05/.test(String(a.day ?? '')) ? { ok: true, answer: '北京时间明天的 NBA 有 3 场：步行者对尼克斯（尼克斯主场），明天上午 8 点 30 分开始；湖人对国王（国王主场），明天上午 11 点开始；马刺对掘金（掘金主场），明天上午 10 点开始' }
      : /昨天|11-03/.test(String(a.day ?? '')) ? { ok: true, answer: '北京时间昨天的 NBA 有 14 场：76人 112 比 106 胜活塞；老鹰 120 比 105 胜奇才；凯尔特人 109 比 102 胜雄鹿；还有 11 场' }
      : { ok: true, answer: '北京时间今天的 NBA 有 2 场：魔术 16 比 13 领先太阳，第 1 节还剩 6 分 47 秒；热火对勇士（勇士主场），今天上午 11 点开始' },
    football_games: (a) => a.team && /申花|海港|泰山|国安|蓉城|中超/.test(a.team) ? { ok: true, answer: `${a.team}上一场是 11 月 2 日晚上 7 点 35 分，主场 2 比 1 赢了成都蓉城；下一场是 11 月 9 日晚上 7 点 35 分，客场打北京国安` }
      : a.team ? { ok: true, answer: `${a.team}上一场是 10 月 31 日晚上 8 点 30 分，客场 0 比 2 输给了切尔西；下一场是 11 月 8 日晚上 11 点，主场打阿斯顿维拉` }
      : { ok: true, answer: `北京时间${a.day ?? '今天'}的${a.league ?? '英超'}有 2 场：切尔西 2 比 0 胜曼联；布莱顿 1 比 0 胜曼城` },
    earthquake: (a) => a.nearby ? { ok: true, answer: '中国地震台网最近 2 小时在这附近没有测到地震。全国最近一次是今天 10 点 11 分西藏那曲市巴青县 3.4 级，震源深度 10 公里，离这里约 2780 公里' }
      : { ok: true, answer: '中国地震台网最近 3 次：今天 10 点 11 分西藏那曲市巴青县 3.4 级，震源深度 10 公里；今天 1 点 21 分斐济群岛地区 6.1 级，震源深度 560 公里；今天 0 点 56 分新疆和田地区和康县 4.8 级，震源深度 60 公里' },
    fuel_price: (a) => ({ ok: true, answer: `${a.province ?? '上海'}油价（10 月 30 日调价后）：92 号汽油 8.85 元一升，95 号 9.41 元，0 号柴油 8.57 元；这次每升涨了 0.28 元（92 号）` }),
    fund_nav: (a) => ({ ok: true, answer: '易方达蓝筹精选混合 11 月 3 日净值 1.4023，当天跌 1.00%' }),
    history_today: () => ({ ok: true, answer: '历史上的今天（11 月 4 日）：1867 年，居里夫人出生；1901 年，晚清政治家李鸿章逝世；1917 年，十月革命爆发；1949 年，中国科学院成立' }),
    world_clock: (a) => ({ ok: true, answer: `${a.place}现在是凌晨 0 点 30 分，比北京晚 ${/伦敦|英国/.test(a.place ?? '') ? 8 : 13} 小时` }),
    tv_guide: (a) => a.program ? { ok: true, answer: `《${a.program}》今天在CCTV-1 综合播出：19 点到 19 点 39 分` }
      : /晚/.test(String(a.part ?? '')) ? { ok: true, answer: `今晚${a.channel}：19 点 30 分《电视剧·繁花》；21 点《王牌对王牌》；22 点 30 分《综艺·快乐大本营》` }
      : { ok: true, answer: `${a.channel}现在在播《实况录像-2026年世界职业拳王争霸赛 12》（12 点 55 分开始，13 点 50 分结束）；接下来 13 点 50 分《体育新闻》` },
    news: (a) => /娱乐|军事/.test(String(a.category ?? '')) ? { ok: false, status: 'rejected', message: '这个类别没有现成的新闻源：用 web_search 查' }
      : /AI|ai|人工智能|大模型/.test(String(a.category ?? '')) ? { ok: true, answer: '量子位 AI 新闻（最新一条 15 点）：第一，DeepSeek带火稀疏计算，国产芯片公司加速突围：这家公司又融近10亿；第二，一边赛博修仙，一边卖家电！AI短剧开始冲双11业绩了？；第三，从RSI、Agent、Physical AI到整个系统栈，2026 奇点智能技术大会最新日程公布！' }
      : { ok: true, answer: `中新网${a.category ?? '最新'}新闻（最新一条 11 点 49 分）：第一，进博会聚焦全球经济再平衡；第二，鲁比奥否认计划离职竞选美国总统；第三，土耳其连环枪击案致8死1伤` },
    gold_price: () => ({ ok: true, answer: '国内金价（上海黄金交易所 Au99.99）872.20 元一克，比上个交易日跌 1.34%；国际金价（伦敦金）4058.96 美元一盎司，跌 0.73%（10 点 6 分）。金店的首饰金价比这个高，以各家牌价为准' }),
    lottery: (a) => /大乐透/.test(String(a.game ?? '')) ? { ok: true, answer: '大乐透第 26126 期（11 月 4 日 周三）：前区 05、11、17、25、34，后区 04、06；一等奖 9 注，每注 624.7 万元' }
      : { ok: true, answer: '双色球第 2026127 期（11 月 3 日 周二）：红球 13、14、19、25、29、32，蓝球 12；一等奖 3 注，每注 1000 万元' },
    // Weather.lookup for 上海徐汇 / 北京 (the scenarios' day is 2026-11-04, Wednesday)
    weather: (a) => { (state.weathers ??= []).push(a);
      const place = a.place && !/徐汇|上海/.test(a.place) ? a.place : '上海徐汇';
      const lines = {
        今天: '今天（11 月 4 日 星期三）多云，13 到 20 度，降水概率 0%，北风 3 级，现在 16 度，多云',
        明天: '明天（11 月 5 日 星期四）阴，14 到 19 度，降水概率 10%，东北风 3 级',
        后天: '后天（11 月 6 日 星期五）小雨，15 到 18 度，降水概率 85%，东风 4 级',
        今晚: '今晚（18 点到 24 点）多云，15 到 17 度，降水概率最高 10%', 明早: '明早（6 点到 10 点）阴，13 到 15 度，降水概率最高 20%' };
      // weekdays and dates, as Weather.pickDays takes them since 08:30
      const sat = '11 月 7 日 星期六 阴转小雨，15 到 18 度，降水概率 60%，北风 3 级', sun = '11 月 8 日 星期日 多云，12 到 17 度，降水概率 10%，北风 4 级';
      for (const k of ['周六', '这周六', '星期六', '这个周六', '2026-11-07']) lines[k] = sat;
      for (const k of ['周日', '这周日', '星期日', '星期天', '周天', '2026-11-08']) lines[k] = sun;
      lines['周末'] = sat + '；' + sun;
      const day = a.day && lines[a.day] ? [a.day] : !a.day ? ['今天'] : /这几天|最近/.test(a.day) ? ['今天', '明天', '后天'] : ['明天'];
      let answer = place + day.map((d) => lines[d]).join('；') + (a.air ? '；空气质量良，AQI 约 63（PM2.5 45）' : '') + '。';
      if (a.dress) answer = place + (a.day === '明天' ? '明天 14 到 19 度，穿外套或卫衣，早晚凉' : '今天 13 到 20 度，穿外套或卫衣，早晚凉') + '。';
      if (a.sun) answer = place + (a.day === '明天' ? '明天日出早上 6 点 14 分，日落下午 5 点整' : '今天日出早上 6 点 13 分，日落下午 5 点 1 分') + '。';
      // Weather.rainVerdict: the answer to 要带伞吗 / 会下雨吗
      if (a.rain) answer = place + ({ 今天: '今天应该不下雨，降水概率 0%，不用带伞（多云，13 到 20 度）', 明天: '明天应该不下雨，降水概率 10%，不用带伞（阴，14 到 19 度）',
        后天: '后天有小雨，降水概率 85%，出门带伞（15 到 18 度）', 今晚: '今晚应该不下雨，降水概率 10%，不用带伞（18 点到 24 点，多云，15 到 17 度）' }[a.day]
        ?? '现在没下雨，多云，16 度') + '。';
      return { ok: true, status: 'observed', message: answer, answer, source: 'Open-Meteo 气象模型预报' }; },
    // CalendarTools.look on 2026-11-04 (the scenarios' day), printed from the Java tool
    calendar: (a) => { (state.calendars ??= []).push(a);
      const f = String(a.find ?? ''), d = String(a.date ?? '');
      const CAL = {
        过年: '过年是 2027-02-06（星期六，农历丁未羊年正月初一），还有 94 天；春节放假 2027-02-05（星期五） 到 2027-02-13（星期六），共 9 天；调休上班：2027-01-31（星期日）、2027-02-14（星期日）',
        国庆: '国庆是 2027-10-01（星期五，农历丁未羊年九月初二），还有 331 天；国庆节放假 2027-10-01（星期五） 到 2027-10-07（星期四），共 7 天；调休上班：2027-09-26（星期日）、2027-10-09（星期六）',
        中秋: '中秋是 2027-09-15（星期三，农历丁未羊年八月十五），还有 315 天；中秋节放假 2027-09-15（星期三） 到 2027-09-15（星期三），共 1 天；不调休',
        冬至: '冬至是 2026-12-22（星期二，农历丙午马年冬月十四），还有 48 天',
        除夕: '除夕是 2027-02-05（星期五，农历丙午马年腊月廿九），还有 93 天',
        元旦: '元旦是 2027-01-01（星期五，农历丙午马年冬月廿四），还有 58 天；元旦放假 2027-01-01（星期五） 到 2027-01-03（星期日），共 3 天；不调休',
      };
      const key = Object.keys(CAL).find((k) => f.includes(k) || (k === '过年' && /春节|新年/.test(f)) || (k === '国庆' && /十一/.test(f)));
      if (key) return { ok: true, status: 'observed', message: CAL[key] };
      if (!f && !d) return { ok: true, status: 'observed', message: '下一个节日感恩节，2026-11-26，还有 22 天；下一个法定假日元旦，2027-01-01（星期五） 起放 3 天；下一个节气立冬，2026-11-07，还有 3 天' };
      if (/2026-12-25/.test(d)) return { ok: true, status: 'observed', message: '2026-12-25（星期五）是农历丙午马年冬月十七，圣诞节，工作日，还有 51 天' };
      return { ok: true, status: 'observed', message: '（模拟里没有这一问的数据）' }; },
    // Usage.summary of the TV's usage log (11-03 evening as the scenarios' 「昨晚」)
    self_check: () => ({ ok: true, status: 'observed', message: '自检正常：模型（备用网关，1.4 秒）、天气、本机调试、读屏都没问题。', problems: 0 }),
    tv_usage: (a) => { (state.usages ??= []).push(a);
      return { ok: true, status: 'observed', message: '昨天晚上电视开了 2 小时 12 分（亮着的是晚上 8 点 58 分到 11 点 10 分）：哔哩哔哩 1 小时 5 分钟（晚上 8 点 59 分到 10 点 4 分等），网易云音乐 20 分钟（晚上 10 点 10 分到 10 点 30 分）。',
        on_minutes: 132, apps: [{ app: '哔哩哔哩', minutes: 65 }, { app: '网易云音乐', minutes: 20 }] }; },
    recall_history: (a) => { (state.recalls ??= []).push(a.query ?? '');
      return { ok: true, status: 'observed', message: state.history ?? '最近 7 天还没有记录。' }; },
    netease_playlist: (a) => { state.playing = { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '可惜没如果', artist: '林俊杰' };
      return { ok: true, status: 'observed', message: '网易云正在播放《可惜没如果》 林俊杰', session: state.playing }; },
    // TvTools.media_control: the session a second after the key; a stop or pause it ignored is unconfirmed
    media_control: (a) => {
      // exit (2026-11-08): the playing stopped and the home in front, in one step
      if (a.action === 'exit') { state.exited = true; if (state.playing) state.playing = { ...state.playing, state: 'stopped' };
        const left = { 'xyz.erapi0neer.fluidlab': 'Fluid Lab', 'tv.danmaku.bili': '哔哩哔哩', 'com.ktcp.video': '云视听极光' }[state.fg];
        state.fg = 'xyz.erapi0neer.orrery'; state.screen = screens.launcher;
        return { ok: true, status: 'observed', message: left ? `已退出${left}，回到桌面` : '已退出网易云音乐，回到桌面；没有在放的了' }; }
      if (state.playing && ['stop', 'pause'].includes(a.action)) { const t = state.playing.title; state.playing = { ...state.playing, state: a.action === 'stop' ? 'stopped' : 'paused' };
        return { ok: true, status: 'observed', message: `网易云音乐 ${a.action === 'stop' ? '已停止' : '已暂停'}《${t}》` }; }
      // next / previous move to another song, from its start (until 2026-11-18 the same title came back)
      if (state.playing && ['next', 'previous'].includes(a.action)) {
        const title = String(state.playing.title).startsWith('江南') ? '可惜没如果 (If Only…)' : '江南 (River South)';
        state.playing = { ...state.playing, state: 'playing', title, position: '0:01' };
        return { ok: true, status: 'observed', message: `网易云音乐 正在播放《${title}》`, session: state.playing }; }
      return { ok: true, status: 'observed', message: state.playing ? `网易云音乐 正在播放《${state.playing.title}》` : '已发送' }; },
    open_settings: (a) => { state.fg = 'com.android.tv.settings'; state.screen = `前台：设置 com.android.tv.settings\n焦点：[1]\n[1] 文本「${a.page ?? '设置'}」`;
      return { ok: true, status: 'observed', message: `已打开设置：${a.page ?? 'home'}`, screen: state.screen }; },
    adjust_volume: () => ({ ok: true, status: 'observed', volume: 12 }),
    web_search: (a) => { if (state.searchFound) return { ok: false, status: 'rejected', message: SEARCH_FOUND };
      (state.searches ??= []).push(a.query);
      const q = String(a.query ?? '');
      const answer = /天气|气温|下雨/.test(q) ? (/明天|11月5日|5日/.test(q) ? '上海明天多云转小雨，15到19度，东北风3级。来源：中国天气网。' : '上海今天阴到多云，现在18度，夜里16到17度。来源：中国天气网。')
        : /金价|黄金/.test(q) ? '今天上海黄金交易所上海金基准价约每克921元，首饰金价每克1150元左右。来源：上海黄金交易所。'
        : /NBA|比赛|赛程/i.test(q) ? '北京时间今天上午有14场NBA常规赛，焦点是湖人对国王、凯尔特人对雄鹿。来源：NBA中国。'
        : /姚润|叠纸/.test(q) ? '没有找到可靠来源的报道，网上的说法未经证实，不转述。'
        : /杨永浩/.test(q) ? '没有找到权威媒体关于“杨永浩出事”的报道；网上同名的是门头沟区检察院检察长杨永浩，最近只有任职消息。'
        : /林俊杰/.test(q) && /最新|新歌|单曲|发表|发行/.test(q) ? '林俊杰最新发表的单曲是2026年10月24日上线的《时间的歌》。来源：网易云音乐、QQ音乐。'
        : /open ?ai|oppo/i.test(q) ? 'OpenAI 11月4日发布 Images 2.6，11月3日任命 Jill Hazelbaker 为首席企业事务官。来源：OpenAI 官网。'
        // the stories behind the read-out lists of hotthird / newsdetail (a real search finds them; 「没有找到」 sent the
        // model round in circles, 2026-11-06 calibration)
        : /6G/i.test(q) ? '“6G有啥不一样”是在聊6G和5G的区别：6G预计2030年前后商用，峰值速率是5G的10到100倍，时延更低，还要把卫星和地面网络连成一张网。来源：新华网。'
        : /鲁比奥/.test(q) ? '美国国务卿鲁比奥11月5日否认有意辞职参加2028年总统选举，称将继续在特朗普政府任职。来源：中新网。'
        : /智谱/.test(q) ? '智谱11月6日港股收跌10.46%，报426.2港元：市场普遍归因于配售摊薄、上市后涨幅过大后的回调，以及大模型板块整体走弱。来源：新浪财经、格隆汇。'
        : '没有找到明确的结果。';
      state.searchFound = !/没有找到|没找到|没查到|查不到|未找到|找不到|无法确认|没有相关|暂无/.test(answer);  // SearchBudget.result
      return { ok: true, status: 'observed', answer, searched: true, millis: 7200 }; },
  };
}
// the apps on the TV on 2026-11-11, as AgentEngine.environment lists them; NO_BROWSER is the TV of 2026-11-07, before
// TV Bro was installed (the 红果短剧 evening: with a browser there the model opens the link at once, which is right)
const REAL_APPS = ['Fluid Lab', 'Orrery', 'TV Bro', '云视听极光', '哔哩哔哩', '小爱同学', '无线投屏', '星仪查音量', '星仪电视助手', '电视管家', '电视设置', '相册', '网易云音乐'];
const NO_BROWSER = REAL_APPS.filter((a) => a !== 'TV Bro');
// what the TV's own 「关于用户」 block read on 2026-11-11 (remembered facts, then the habits learned from use)
const REAL_PROFILE = `- “星辰旅行者”指 B 站 UP 主“星辰亻旅行者”（中间有单人旁“亻”），搜索时用带“亻”的写法。
- 用户最喜欢的歌手是林俊杰
- 用户希望语音识别出错时，按发音和上下文推断成正确汉字（比如“跳楼机”对应《跳楼机》），不要把错误的转写原样照搬

（以下是根据使用记录自动整理的习惯，可能不准）
- 常用应用：网易云音乐（9次）
- 常听：林俊杰（4次）、李佳薇（3次）、蔡依林/周杰伦（2次）
- 常点的歌：《甲乙丙丁 (你我怎么两清)》（3次）
- 习惯音量：20 左右`;

// what AgentLoop appends under 「## 关于用户」: remembered facts, then the habits the engine learned (job habits)
const PROFILE = '- 用户最喜欢的歌手是林俊杰\n- 用户说的“我的频道”指 B 站 UP 主星辰亻旅行者\n\n（以下是根据使用记录自动整理的习惯，可能不准）\n'
  + '- 常用应用：哔哩哔哩（12次）、网易云音乐（7次）\n- 常听：林俊杰（6次）、薛之谦（3次），多在晚上\n- 习惯音量：30 左右';
// 网易云's full-screen player as 星仪 reads it (2026-11-08 on the TV): DOWN brings the control bar, whose lower right
// holds the 随心唱 (karaoke) mic icon; UP opens the remote tutorial; LEFT/RIGHT seek
const NE_PLAYER = '前台：网易云音乐 com.netease.cloudmusic.tv（PlayerActivityV2）\n[1] 文本「你要的全拿走 (Take Everything You Want)」 @69,16\n[2] 文本「胡彦斌」 @75,21';
const NE_CONTROLS = NE_PLAYER + '\n[3] 图标 @6,90\n[4] 图标 @11,90\n[5] 图标 @16,90\n[6] 按钮「极高」 @22,90\n[7] 按钮「看MV」 @30,90\n[8] 图标 @45,90\n[9] 图标 焦点 @50,90\n[10] 图标 @55,90\n[11] 图标 @78,90\n[12] 按钮「播放样式」 @86,90\n[13] 图标 @94,90\n[14] 图标 @94,74';
const NE_TUTORIAL = '前台：网易云音乐 com.netease.cloudmusic.tv（PlayerActivityV2）\n[1] 文本「「单击上键」唤起此教程」\n[2] 文本「「点击下键」展示更多操作」\n[3] 文本「点击任意键跳过 (28)」';
const PLAYING_HU = { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '你要的全拿走 (Take Everything You Want)', artist: '胡彦斌' };
// the singers of songs the scenarios name without one (the simulated catalog finds them by title alone)
const SINGERS = { '跳楼机': 'LBI利比', '如果可以': '韦礼安' };
// 2026-11-08 12:05 on the TV: XiaoAi was already playing a 酷狗 members' sample when the bridge stopped it and handed over
const KUGOU_NOTE = '小爱自己的处理：动作 Template.PlayInfo、Execution.Group、Template.Toast、PlaybackController.SetProperty、AudioPlayer.Play；'
  + '它本来要说「正在试听会员专属歌曲，歌曲播放页按确认键开通酷狗音乐TV会员，即可畅享全曲哦」；小爱自己要放的音频会被接力停掉，用户要听什么由你来放';
const PLAYING_JJ = { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '无拘 (永劫无间周年主题曲)', artist: '林俊杰' };
const scenarios = {
  bili: { say: '打开B站搜索星辰亻旅行者', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_search'], avoid: ['bili_videos', 'open_link'] } },
  biliplay: { say: '打开B站搜一下星辰亻旅行者，播放他最新那个视频', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_videos', 'open_link'], play: 'TV agent工程' } },
  song: { say: '打开网易云音乐播放林俊杰的不为谁而作的歌', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher },
  // nothing says what 「那个」 is: ask (the contract since 96f45c2: never filled from the habits). Named 'vague' until
  // 2026-11-18, where the later 'vague' replaced it, so it had never run since 11-04.
  vagueplay: { say: '放那个', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['ask_user'], avoid: ['netease_play', 'bili_search', 'open_link', 'open_app'] } },
  complain: { say: '你刚才为什么搜错了', fg: 'tv.danmaku.bili', screen: screens.biliHome,
    previous: { goal: '在哔哩哔哩搜索“星辰亻旅行者”', state: 'done', steps: [{ text: '打开B站搜索', status: 'done' }], summary: '已在哔哩哔哩搜索“星辰旅行者”' } },
  cont: { say: '继续执行', fg: 'tv.danmaku.bili', screen: screens.biliResults('星辰亻旅行者'),
    previous: { goal: '在B站搜索星辰亻旅行者并播放第一个视频', state: 'interrupted',
      steps: [{ text: '在哔哩哔哩搜索“星辰亻旅行者”', status: 'done' }, { text: '打开搜索结果里的第一个视频', status: 'pending' }, { text: '确认视频开始播放', status: 'pending' }],
      actions: [{ tool: 'bili_search', args: { keyword: '星辰亻旅行者' }, status: 'observed' }] } },
  ability: { say: '你能帮我干嘛', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher },
  mijia: { say: '把客厅的灯关了', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher },
  // web_search: live questions search once and answer; stable knowledge does not search; a follow-up keeps place and date
  // since 2026-11-06 the weather has a tool of its own (weather), not web_search
  weather: { say: '今天上海天气怎么样', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search'] } },
  gold: { say: '今天金价多少', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['gold_price'], avoid: ['web_search'] } },
  nba: { say: '今晚有什么NBA比赛', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['nba_games'], avoid: ['web_search'] } },
  followup: { say: '那明天呢', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search'], says: '明天' },
    recent: [{ said: '上海今天天气怎么样', replied: '上海今天阴到多云，现在18度。', minutes_ago: 1 }] },
  stable: { say: '李白是哪个朝代的', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { search: false } },
  // 2026-11-05 runs on the TV: a described song, an UP's most played video, a named kind of video
  // a singer's newest from NetEase (music_new, 2026-11-07), not a web search
  newsong: { say: '网易云音乐播放林俊杰最新发表的一个单曲', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['music_new', 'netease_play'], search: false, play: 'Rise Without A Sound' } },
  newsongask: { say: '林俊杰最近发新歌了吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['music_new'], search: false, avoid: ['netease_play', 'bili_videos'], says: 'Rise Without A Sound' } },
  jaynew: { say: '周杰伦最新的歌是哪首', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['music_new'], search: false, avoid: ['netease_play'], says: '太阳之子' } },
  mostplayed: { say: '打开B站搜索星辰亻旅行者播放它播放量最高的一个视频', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    results: screens.biliUp, expect: { called: ['bili_videos'], play: '示例作品' } },
  mad: { say: '哔哩哔哩播放星辰亻旅行者的MAD', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    results: screens.biliUp, expect: { called: ['bili_videos'], play: '静止系' } },
  // 星仪接管模式: XiaoAi already handled the request (its voice cut); 星仪 reports, never repeats a relative action
  // XiaoAi's volume change lands (measured on the TV: 20 → 30): the environment was read at 10, the TV is at 12 when
  // the model looks. Until 2026-11-18 tv_status said 10 here; that run M78 read it, saw XiaoAi's change missing and
  // adjusted again — right for that world, and not this one.
  // what counts is the answer (12, not 20) and no long detour. Until 11-18: avoid adjust_volume/set_volume — the model
  // now and then presses before looking (set_volume 20 once in 12 runs of 11-18), and the environment says 10 while the
  // TV is at 12 until the model looks; a read first is no worse than the TV's own truth, an answer of 20 is
  handedvol: { say: '音量调大一点', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, volumeNow: 12,
    note: '小爱自己的处理：动作 Speaker.AdjustVolume、Template.Toast；它本来要说「音量已调到12」',
    expect: { says: '12', notSays: ['20'], maxRounds: 3 } },
  // XiaoAi's skip landed: another song, 2 s in (until 2026-11-18 nothing was playing here at all). A second next is
  // refused by the engine (6d32cf8) with what plays now; what counts is that the answer names the song XiaoAi's skip
  // landed on, not one skipped to again, within two rounds (avoid media_control until 11-18: the refusal now carries
  // the song, and M78 presses first 4 times of 4 and answers in round 2, where it took 1-3 rounds before)
  handednext: { say: '下一首', fg: 'com.netease.cloudmusic.tv', screen: screens.launcher,
    playing: { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '可惜没如果 (If Only…)', artist: '林俊杰', position: '0:02' },
    recent: [{ said: '换一首歌', replied: '已经换好了，网易云正在放林俊杰的《江南》。', minutes_ago: 1 }],
    note: '小爱自己的处理：动作 PlaybackController.Next；它本来要说「好的」',
    expect: { says: ['可惜没如果'], notSays: ['江南', '多跳', '再切'], maxRounds: 3 } },
  // 2026-11-16 19:30: XiaoAi's skip did not land, 《江南》 1:40 in. The engine lets media_control through then (6d32cf8);
  // the model has to want it, and not say 「小爱已经切过了」
  handednextmissed: { say: '下一首歌', fg: 'com.netease.cloudmusic.tv', screen: screens.launcher,
    playing: { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '江南 (River South)', artist: '林俊杰', position: '1:40' },
    recent: [{ said: '换一首歌', replied: '已经换好了，网易云正在放林俊杰的《江南》。', minutes_ago: 1 }],
    note: '小爱自己的处理：动作 PlaybackController.Next、Template.Toast；它本来要说「好的」',
    expect: { called: ['media_control'], notSays: ['已经切过了', '小爱已经切'] } },
  // 2026-11-16 19:45 on the TV, in order: XiaoAi's StartOver did not land (the song was paused at 2:13); 星仪 pressed
  // play and said what was playing; to 「这是重播」 it said 「对，现在放的是重新从头播的」; 「但是没有重播」 got a plan to
  // switch on 单曲循环; 「重播不是单曲循环」 at last played the song again with netease_play — the replay
  restartsong: { say: '重播这首歌', fg: 'com.netease.cloudmusic.tv', screen: screens.launcher,
    playing: { app: 'com.netease.cloudmusic.tv', state: 'paused', title: '修炼爱情 (Practice Love)', artist: '林俊杰', position: '2:13' },
    note: '小爱自己的处理：动作 PlaybackController.StartOver',
    expect: { called: ['netease_play'], avoid: ['plan', 'screen_look'], notSays: ['单曲循环'] } },
  isreplay: { say: '这是重播', fg: 'com.netease.cloudmusic.tv', screen: screens.launcher,
    playing: { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '修炼爱情 (Practice Love)', artist: '林俊杰', position: '2:31' },
    note: '小爱自己的处理：动作 PlaybackController.StartOver、Suggestion.ShowContextSuggestions',
    recent: [{ said: '重播这首歌', replied: '网易云音乐 正在播放《修炼爱情 (Practice Love)》 林俊杰（因你而在）', minutes_ago: 0 }],
    expect: { called: ['netease_play'], notSays: ['重新从头播', '从头播的', '是重播'] } },
  notreplayed: { say: '但是没有重播', fg: 'com.netease.cloudmusic.tv', screen: screens.launcher,
    playing: { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '修炼爱情 (Practice Love)', artist: '林俊杰', position: '2:45' },
    note: '小爱自己的处理：动作 Template.Toast；它本来要说「我还不会唱呢，但是我先记下来啦，学会了就唱给你听~」',
    recent: [{ said: '重播这首歌', replied: '网易云音乐 正在播放《修炼爱情 (Practice Love)》 林俊杰（因你而在）', minutes_ago: 0 },
      { said: '这是重播', replied: '对，现在放的是重新从头播的《修炼爱情》，林俊杰的。', minutes_ago: 0 }],
    expect: { called: ['netease_play'], avoid: ['plan', 'screen_look', 'screen_read'], notSays: ['单曲循环'] } },
  notloop: { say: '重播不是单曲循环', fg: 'com.netease.cloudmusic.tv', screen: screens.launcher,
    playing: { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '修炼爱情 (Practice Love)', artist: '林俊杰', position: '3:02' },
    note: '小爱自己的处理：动作 PlaybackController.SetProperty、Template.Toast；它本来要说「好的，已设为列表循环模式」',
    recent: [{ said: '这是重播', replied: '对，现在放的是重新从头播的《修炼爱情》，林俊杰的。', minutes_ago: 1 },
      { said: '但是没有重播', replied: '已停止。', minutes_ago: 0 }],
    expect: { called: ['netease_play'], avoid: ['plan', 'screen_look'] } },
  handedbili: { say: '打开B站搜索星辰亻旅行者播放它播放量最高的一个视频', fg: 'com.xiaomi.voicecontrol', screen: screens.launcher,
    results: screens.biliUp, note: '小爱自己的处理：动作 Video.ShowSearchPage、Template.Toast；它本来要说「小爱没有找到星辰人旅行者的资源，为您推荐以下相关内容」',
    expect: { called: ['bili_videos'], play: '示例作品' } },
  // memory: explicit facts, recall, and vague requests resolved by facts and learned habits
  // A fact the profile does not have yet. Since 2026-11-11 a scenario without a profile of its own gets REAL_PROFILE,
  // which has this very fact: 2026-11-16 M78 answered 「已经记着了」 without calling remember — true, and the scenario
  // failed. The habits still name 林俊杰 (常听 4次): that is not the fact, and 「已经记着了」 would be untrue here.
  rememberfact: { say: '记住我最喜欢的歌手是林俊杰', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    profile: REAL_PROFILE.replace('- 用户最喜欢的歌手是林俊杰\n', ''), expect: { called: ['remember'] } },
  // the same sentence when the profile has it: remember (「本来就记得」) or saying so are both right; nothing else is
  rememberknown: { say: '记住我最喜欢的歌手是林俊杰', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, profile: REAL_PROFILE,
    expect: { avoid: ['forget', 'netease_play', 'open_app', 'plan'], says: ['记着', '记得', '记住', '记下'] } },
  recall: { say: '你记得我什么', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, profile: PROFILE,
    expect: { avoid: ['remember', 'forget'], says: '林俊杰' } },
  // the channel's page (bili_videos' up.space since 2026-11-07), or B站's search for it
  mychannel: { say: '打开B站看看我的频道', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, profile: PROFILE,
    expect: { channel: '星辰亻旅行者' } },
  usualsong: { say: '放首歌', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, profile: PROFILE,
    expect: { artist: '林俊杰' } },
  forgetit: { say: '忘掉我喜欢林俊杰这件事', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, profile: PROFILE,
    expect: { called: ['forget'] } },
  // scenes (2026-11-05 audit jobs): schedules, quick notes, history
  later: { say: '明天早上7点播放新闻联播', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['schedule_task'], avoid: ['plan', 'xiaoai_ask'], schedule: { kind: 'task', time: '07:00', what: '新闻联播' } } },
  nightly: { say: '每天晚上11点把电视关了', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['schedule_task'], avoid: ['sleep_timer'], schedule: { repeat: 'daily', time: '23:00' } } },
  remindpill: { say: '8点提醒我吃药', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, note: '小爱自己的处理：没有动作',
    expect: { called: ['schedule_task'], schedule: { kind: 'remind', time: '08:00' } } },
  xiaoaialarm: { say: '明天早上6点叫我起床', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    note: '小爱自己的处理：动作 Alarm.SetAlarm、Template.Toast；它本来要说「好的，已为你设置明天早上6点的闹钟」', expect: { avoid: ['schedule_task'] } },
  milk: { say: '帮我记一下明天要买牛奶', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['note_add'], avoid: ['remember', 'schedule_task', 'plan'], note: '牛奶' } },
  whatnotes: { say: '我让你记了什么', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    profile: '- 用户最喜欢的歌手是林俊杰\n\n（随手记，还没划掉）\n- 明天买牛奶（11月5日记）', results: null,
    noteList: '随手记里有 1 件：1 号，明天买牛奶（今天 10:05 记的）。',
    expect: { says: '牛奶' } },
  replay: { say: '昨天晚上看的那个视频再放一遍', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, results: screens.biliUp,
    history: '找到 1 条：昨天 22:58 你说「打开B站搜索星辰亻旅行者播放它播放量最高的一个视频」，星仪回答「已在B站播放《示例作品14》」（当时在放《示例作品14》）',
    expect: { called: ['recall_history'], play: '示例作品' } },
  // a scheduled task firing while the TV is in standby: a video needs the screen first, turning the TV off does not
  firedvideo: { say: '打开B站搜索星辰亻旅行者播放它播放量最高的一个视频', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    results: screens.biliUp, screenOff: true,
    note: '这是用户预约的定时任务（11月4日 22:10 定的「今天 23:20 打开B站搜索星辰亻旅行者播放它播放量最高的一个视频」），现在到点了：照这句话去做，做完用一句话说结果。',
    expect: { awake: true, play: '示例作品' } },
  // a bare 关 (2026-11-06 on the TV it was taken for 返回): on the desktop the TV off; while playing, stop
  bareoff: { say: '关', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['screen_off'], avoid: ['press_keys', 'xiaoai_ask'] } },
  bareoffplaying: { say: '关掉', fg: 'com.netease.cloudmusic.tv', screen: screens.launcher, playing: { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '她说', artist: '林俊杰' },
    expect: { called: ['media_control'], avoid: ['screen_off', 'xiaoai_ask'] } },
  firedoff: { say: '关闭电视', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, screenOff: true,
    note: '这是用户预约的定时任务（11月4日 22:10 定的「每天 23:00 关闭电视」），现在到点了：照这句话去做，做完用一句话说结果。',
    expect: { avoid: ['wake_screen'] } },
  missingapp: { say: '用爱奇艺看狂飙', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['install_app', 'open_app'], avoid: ['ask_user'] } },
  bilifav: { say: '打开B站看看我的收藏', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['open_link'] } },
  bilihistory: { say: '看看B站的观看历史', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['open_link'] } },
  // 2026-11-05 21:00 on the TV: B站 in front, a rumor about a person, then the clarification: web only, no app, no refusal
  rumor: { say: '今天听说那个杨永浩出事了好像网上都在传', fg: 'tv.danmaku.bili', screen: screens.biliPlayer,
    expect: { called: ['web_search'], avoid: ['bili_search', 'open_app', 'open_link', 'plan'], notSays: ['不能帮你', '不能传播'] } },
  yao: { say: '我说的是姚润浩叠纸的那个姚润浩', fg: 'tv.danmaku.bili', screen: screens.biliPlayer,
    recent: [{ said: '今天听说那个杨永浩出事了好像网上都在传', replied: '没找到权威媒体证实“杨永浩出事”，他最近的公开消息是当选门头沟区检察院检察长。', minutes_ago: 0 }],
    expect: { search: true, avoid: ['bili_search', 'open_app', 'open_link', 'plan'], says: '姚润', notSays: ['不能帮你', '不能传播', '无法帮你'] } },
  // 2026-11-05 22:57 on the TV: no subject heard, B站 in front, an UP主 in the habits: ask (or search), never open B站
  vague: { say: '我想知道最近有什么新动向', fg: 'tv.danmaku.bili', screen: screens.biliPlayer, profile: PROFILE,
    expect: { avoid: ['bili_search', 'open_app', 'open_link', 'netease_play'] } },
  // ...and the answer to 星仪's question: search the company and say it, no second question
  openai: { say: '我说的是想听OPPO OPEN AI这个公司的', fg: 'tv.danmaku.bili', screen: screens.biliPlayer, profile: PROFILE, answering: true,
    previous: { utterance: '我想知道最近有什么新动向', state: 'awaiting_user',
      question_asked: '你说的“新动向”是指B站UP主“星辰亻旅行者”的最新动态，还是最近的整体热点新闻？' },
    recent: [{ said: '我想知道最近有什么新动向', replied: '你说的“新动向”是指B站UP主“星辰亻旅行者”的最新动态，还是最近的整体热点新闻？', minutes_ago: 0 }],
    expect: { search: true, avoid: ['ask_user', 'bili_search', 'open_app', 'plan'], says: 'OpenAI' } },
  // 2026-11-06 00:05 on the TV: after 「TI BO这个人」 was worked out as Tibo (@thsottiaux), read his posts on X, not the web
  tibo: { say: '就是这个人然后你去查他的推特账号他经常会发一些重置相关的东西', fg: 'com.xiaomi.voicecontrol', screen: screens.launcher,
    recent: [{ said: '你要在TABLE的推特上面查', replied: 'TABLE 指的是哪个？给我账号全名或主页链接，我去那边找最新动态。', minutes_ago: 1 },
      { said: 'TI BO这个人', replied: '你说的应该是 Tibo Sottiaux，X 账号 @thsottiaux，在 OpenAI 负责 ChatGPT、Codex 相关产品和工程。', minutes_ago: 0 }],
    expect: { called: ['x_search'], avoid: ['web_search', 'bili_search', 'open_app', 'open_link'], says: 'Day 28' } },
  // 2026-11-06 overnight pass: the Chinese calendar from data (calendar), not the model's arithmetic or a web search
  newyear: { say: '还有几天过年', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['calendar'], avoid: ['web_search', 'plan'], says: '94' } },
  nationalday: { say: '明年国庆放几天假', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['calendar'], avoid: ['web_search', 'plan'], says: '7' } },
  winter: { say: '今年冬至是几号', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['calendar'], avoid: ['web_search'], says: '22' } },
  // arithmetic said aloud: does the model get it right without a tool?
  math1: { say: '1234乘以5678等于多少', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['web_search', 'plan'], says: ['7006652', '7,006,652', '七百万零六千六百五十二'] } },
  math2: { say: '一千二百八十块打八五折再减五十是多少钱', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['web_search', 'plan'], says: ['1038', '一千零三十八'] } },
  math3: { say: '3的20次方是多少', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['web_search', 'plan'], says: ['3486784401', '3,486,784,401', '三十四亿八千六百七十八万四千四百零一'] } },
  festremind: { say: '除夕那天早上9点提醒我贴春联', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['calendar', 'schedule_task'], schedule: { kind: 'remind', time: '09:00', date: '2027-02-05' } } },
  // relative days from 2026-11-04 (Wednesday): 下周三 11-11, 这周日 11-08, 下个月15号 is a Tuesday, 100 天后 2027-02-12
  nextwed: { say: '下周三是几号', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['web_search'], says: '11' } },
  thissun: { say: '这周日是几号', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['web_search'], says: '8' } },
  dec15: { say: '下个月15号是星期几', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['web_search'], says: '二' } },
  in100: { say: '100天以后是几月几号', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['web_search'], says: '12' } },
  // 2026-11-06 overnight pass: films and series go to 云视听极光 (Tencent Video's TV app), not B站
  // 2026-11-10 on the TV: 「我想看狂飙」 wandered two minutes through 云视听极光's search page, which has no 狂飙 (it is
  // iQiyi's). tencent_video_open asks Tencent's own search and says where a show is
  series: { say: '我想看狂飙', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search', 'screen_click', 'install_app'], says: '爱奇艺' } },
  seriesfan: { say: '我想看繁花', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search', 'screen_click', 'movie_rating'], says: '繁花', opensPlay: true } },
  seriespick: { say: '打开庆余年第二季，我自己挑一集', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search'], says: '庆余年', opensPlay: false } },
  // 2026-11-11 00:27 on the TV: 「我想看别无选择」 opened the detail page (play:false) — wanting to watch is playing
  seriesnochoice: { say: '我想看别无选择', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search'], opensPlay: true } },
  seriesput: { say: '放一下三体', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search'], opensPlay: true } },
  // 2026-11-11 00:50, silent runs on the sleeping TV at night: one of five went to bili_search, and the awake one at
  // 00:27 opened the detail page (play false) — the screen off and the quiet hours are part of what the model weighs
  seriesasleep: { say: '我想看别无选择', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, screenOff: true,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search', 'netease_play'], opensPlay: true } },
  // 2026-11-11 21:12-21:18 on the TV, the user's own words. Two requests had shown no panel (one fell into an install,
  // the other was a song); 星仪 took 「HARNESS 面板」 for an app, opened a test app with a black screen and left it
  // there, searched for an installer of "Harness", and switched the hint card off and on.
  panelwhy: { say: '为什么现在HARNESS面板没有打开', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    recent: [{ said: '开机', replied: '屏幕亮着呢', minutes_ago: 9 }],
    expect: { avoid: ['open_app', 'explore_app', 'list_apps', 'install_app', 'find_installer', 'web_search', 'set_hints', 'press_keys', 'screen_look'], says: '面板' } },
  panelfix: { say: '你帮自己修复呀', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    recent: [{ said: '为什么现在HARNESS面板没有打开', replied: '面板是好的：读屏服务正常，你这句话现在就显示在右上角的面板上。刚才没弹出来，可能是后台刚重启的那几秒。', minutes_ago: 0 }],
    expect: { avoid: ['open_app', 'explore_app', 'install_app', 'find_installer', 'web_search', 'set_hints', 'press_keys'], says: ['面板', '正常', '自检'] } },
  panelinstall: { say: '那你想办法给他装上', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    recent: [{ said: '为什么现在HARNESS面板没有打开', replied: '面板是好的：读屏服务正常，你这句话现在就显示在右上角的面板上。', minutes_ago: 1 },
      { said: '你帮自己修复呀', replied: '我刚做完自检：模型、天气、本机调试和读屏都正常，面板没有坏。', minutes_ago: 0 }],
    expect: { avoid: ['install_app', 'find_installer', 'web_search', 'open_app', 'explore_app', 'set_hints'], says: ['不用装', '自带', '不需要', '面板'] } },
  blackscreen: { say: '你看现在屏幕就是错的呀什么都没有', fg: 'xyz.erapi0neer.fluidlab', screen: '前台：Fluid Lab xyz.erapi0neer.fluidlab（LabActivity）\n（没有读到界面元素：可能是视频画面、自绘界面或受保护画面，可以用 screen_look 看图）',
    expect: { called: ['media_control'], exited: true, avoid: ['open_app', 'explore_app', 'install_app', 'find_installer', 'set_hints', 'screen_click', 'press_keys'], says: ['桌面'] } },
  hintsnot: { say: '你看我现在每次给你提示提示提示提示提示', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    recent: [{ said: '为什么现在HARNESS面板没有打开', replied: '面板是好的：读屏服务正常，你这句话现在就显示在右上角的面板上。', minutes_ago: 4 }],
    expect: { avoid: ['open_app', 'install_app', 'find_installer'], hintsKept: true } },
  noinput: { say: '现在就是因为没有提示我看不到我的输入你懂吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    recent: [{ said: '为什么现在HARNESS面板没有打开', replied: '面板是好的：读屏服务正常，你这句话现在就显示在右上角的面板上。', minutes_ago: 4 }],
    expect: { avoid: ['open_app', 'explore_app', 'install_app', 'find_installer', 'web_search'], hintsKept: true, says: '面板' } },
  // the switch itself still works when it is asked for
  hintsoff: { say: '别弹提示了', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['set_hints'] } },
  // 2026-11-11 (job tencent-episode): the episode the user names. On the TV a title of three characters or more with
  // its episode is a quick command; a two-character title is still the model's.
  seriesepisode: { say: '我想看繁花第5集', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search', 'press_keys', 'screen_look'], opensEpisode: 5, says: '第 5 集' } },
  seriesepisodecn: { say: '放三体第十二集', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search', 'press_keys'], opensEpisode: 12 } },
  // an episode that is not there is said so, and nothing else is opened in its place
  seriesnoepisode: { say: '我想看繁花第40集', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search', 'press_keys'], opensEpisode: 40,
      says: ['没有第 40 集', '没有第40集', '一共 30 集', '只有 30 集', '共 30 集'], notSays: ['已在云视听极光播放'] } },
  // no episode said: none is guessed
  seriesnoguess: { say: '我想看繁花', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], opensEpisode: 0, opensPlay: true } },
  serieswatch: { say: '看庆余年第二季', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_open'], avoid: ['bili_search', 'web_search', 'tencent_video_search'], opensPlay: true } },
  seriessearch: { say: '在云视听极光搜一下胡歌演的剧', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tencent_video_search'], avoid: ['bili_search', 'tencent_video_open'] } },
  aboutseries: { say: '狂飙这部剧讲的是什么', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['tencent_video_search', 'bili_search', 'open_app'] } },
  whosevideo: { say: '这个视频是谁发的', fg: 'tv.danmaku.bili', onScreen: true,
    screen: screens.biliPlayer + '\n[4] 文本「UP主 星辰亻旅行者 · 2.1万粉丝」\n[5] 按钮「关注」 可点',
    expect: { avoid: ['screen_read', 'screen_look', 'web_search'], says: '星辰亻旅行者' } },
  lastnight: { say: '昨晚我看了多久电视', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tv_usage'], avoid: ['web_search', 'plan', 'bili_search'], says: ['2 小时', '2小时', '两小时', '两个小时'] } },
  sleepnow: { say: '我要睡觉了，电视可以关了', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['screen_off'], avoid: ['xiaoai_ask', 'sleep_timer', 'plan'] } },
  // no closing offer or question (「要不要我放给你听？」): an answer costs another 「小爱同学」
  joke: { say: '讲个简短的冷笑话', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['web_search', 'plan'], notEnds: /要不要|还要再|再来一个|需要我|想听吗|想看吗/ } },
  newsongq: { say: '林俊杰最近发新歌了吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['music_new'], search: false, notEnds: /要不要|还要再|需要我|想听吗/ } },
  // the overnight tools must not be taken for the old ones, nor the old for the new
  tonight: { say: '今晚会下雨吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search'], says: '今晚' } },
  offlater: { say: '半小时后关电视', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['sleep_timer'], avoid: ['screen_off', 'schedule_task'] } },
  // 2026-11-11: this TV has no app that plays live channels. Three runs each took 6-10 rounds (17-60 s): switch_input
  // HDMI1 on a guess, install_app with thirty names, a live page in the browser. One tv_guide and a true sentence is
  // the answer. (On the TV 「我想看CCTV5」 itself is a quick command since job live-tv; this is the model's own path.)
  cctv: { say: '我想看CCTV5', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tv_guide'], avoid: ['switch_input', 'install_app', 'open_link', 'open_app', 'press_keys', 'web_search'], says: ['放不了', '没有', '没装', '看不了'] } },
  cctvnews: { say: '我想看中央台的新闻直播', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { avoid: ['switch_input', 'install_app', 'open_link', 'open_app', 'press_keys'], says: ['放不了', '没有', '没装', '看不了'] } },
  // an input the user names is still switched
  hdmibox: { say: '切到机顶盒看电视', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['switch_input'], avoid: ['install_app', 'open_link'] } },
  bluetooth: { say: '打开蓝牙', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher },
  cast: { say: '手机怎么投屏到电视上', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher },
  slow: { say: '电视怎么这么卡', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    // with free_memory (2026-11-07) a TV short of memory is cleaned up there and then, which also answers it
    expect: { called: ['tv_status'], avoid: ['open_settings', 'screen_read', 'screen_look', 'press_keys', 'open_app'], says: ['重启', '清理好了'] } },
  storage: { say: '电视存储还剩多少', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tv_status'], avoid: ['open_settings', 'screen_read'], says: ['3.1'] } },
  // bili_videos (2026-11-07): what an UP posted, read out; their newest or most played, opened by its link
  upask: { say: '影视飓风最近发了什么视频', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_videos'], avoid: ['bili_search', 'open_link', 'web_search', 'open_app'], says: '动态视频' } },
  upplay: { say: '放影视飓风最新的视频', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_videos', 'open_link'], avoid: ['bili_search'], play: '动态视频｜2026' } },
  uptop: { say: '何同学播放量最高的视频是哪个', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_videos'], avoid: ['open_link', 'bili_search', 'web_search'], says: '5G' } },
  // bili_bangumi (2026-11-07): what airs today, when a show airs, its newest episode played
  todayanime: { say: '今天有什么番更新', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_bangumi'], avoid: ['web_search', 'open_app', 'bili_search'], says: '凡人修仙传' } },
  fanrenwhen: { say: '凡人修仙传什么时候更新', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_bangumi'], avoid: ['web_search', 'open_app', 'bili_search'], says: '周六' } },
  fanrenplay: { say: '放凡人修仙传最新一集', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_bangumi', 'open_link'], avoid: ['bili_search', 'web_search'], play: '凡人修仙传 第198话' } },
  conanout: { say: '柯南更新了吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_bangumi'], avoid: ['web_search', 'open_link'], says: ['1279', '1278'] } },
  // movie_rating (Douban, 2026-11-07): a rating, and 「值得看吗」 answered from it
  // AI news from 量子位 (2026-11-07): the household's most asked kind; one company's moves stay a search
  ainews: { say: 'AI圈最近有什么新消息', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['news'], avoid: ['web_search', 'open_app'], says: 'DeepSeek' } },
  // 清理内存 (2026-11-07): 星仪 pressed 电视管家's 加速 for this and killed its own process; free_memory, one round
  freemem: { say: '清理电视的内存', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['free_memory'], avoid: ['open_app', 'plan', 'screen_click', 'press_keys'], says: '1.5 GB' } },
  freemem2: { say: '电视太卡了，帮我把后台清一下', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['free_memory'], avoid: ['open_app', 'plan', 'screen_click', 'press_keys'] } },
  whymem: { say: '为什么电视内存这么小', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['tv_status'], avoid: ['open_app', 'open_settings', 'web_search', 'plan'], says: ['哔哩哔哩', 'B站', 'B 站'] } },
  // 2026-11-07 22:46-22:48 on the TV: 红果短剧 has no TV app in the store; the user: 「为什么连安装浏览器这么简单的任务都做不了」
  browser: { say: '帮我装个浏览器', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, apps: NO_BROWSER,
    expect: { called: ['install_app'], avoid: ['web_search', 'ask_user'] } },
  hongguo: { say: '我想看红果短剧', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, apps: NO_BROWSER,
    expect: { called: ['install_app', 'open_link'], avoid: ['bili_search', 'tencent_video_search'], link: 'hongguoduanju.com' } },
  hongguoweb: { say: '那网页端你可以自己做一个映射呀', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, apps: NO_BROWSER,
    recent: [{ said: '我说的是红果短剧', replied: '你说的是红果短剧，但这台电视的应用商店里没有这个应用，暂时装不了。', minutes_ago: 2 },
      { said: '没有你不知道安卓的安装包吗', replied: '没找到红果短剧官方公开的电视版安卓安装包；小米应用商店里也没有。它有安卓版和网页端。', minutes_ago: 1 }],
    expect: { called: ['install_app', 'open_link'], link: 'hongguoduanju.com' } },
  // the TV as it is since 2026-11-08 (TV Bro installed): the web version is one open_link away, nothing to install
  hongguowebhas: { say: '那网页端你可以自己做一个映射呀', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    recent: [{ said: '我说的是红果短剧', replied: '你说的是红果短剧，但这台电视的应用商店里没有这个应用，暂时装不了。', minutes_ago: 2 },
      { said: '没有你不知道安卓的安装包吗', replied: '没找到红果短剧官方公开的电视版安卓安装包；小米应用商店里也没有。它有安卓版和网页端。', minutes_ago: 1 }],
    expect: { called: ['open_link'], link: 'hongguoduanju.com' } },
  // 「你点头就装」 (the user's choice, 2026-11-07 23:3x): find the maker's installer, read it, tell the user, then install
  viainstall: { say: '帮我装个Via浏览器', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['find_installer', 'install_app'], installs: ['url', 'token'], avoid: ['web_search'] } },
  // no direct link on 红果短剧's site (QR code only): no download site, the web version instead
  hongguoapk: { say: '下载红果短剧的安卓版装上', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['find_installer'], link: 'hongguoduanju.com', notInstalls: ['url'] } },
  // misheard (2026-11-07): 「关闭桌面召唤器」 (XiaoAi's recognition) took 10 steps through Orrery, keys and the settings page;
  // the user: 「是小爱的tts太垃圾了」. Nothing on this TV is called that: ask, do not try things on the screen
  misheard: { say: '关闭桌面召唤器', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    // 2026-11-08 the user: 「关闭桌面…」 is the screen off and the TV in standby — so screen_off is right, one question with
    // guesses is fair; poking around the TV is not
    expect: { calledAny: ['screen_off', 'ask_user'], avoid: ['press_keys', 'open_settings', 'open_app', 'screen_click', 'web_search'] } },
  // 2026-11-07 14:57 on the TV: 「查询智谱和MINIMAX股价…」 heard as 「质朴」; 星仪 asked what 质朴 was. stock_quote now finds it
  // by its sound (job names-by-sound): one round, no question
  zhipumis: { say: '查询质朴和MINIMAX股价并且进行比较', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['stock_quote'], avoid: ['ask_user', 'plan'], says: '智谱' } },
  // ...a minute later, still on that stock: 「最近为什么地震了」 (the crash) got China's latest earthquakes
  crashwhy: { say: '最近为什么地震了', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    recent: [{ said: '查询制储的股价', replied: '智谱 426.20 港元，跌 10.46%（11 月 6 日收盘）', minutes_ago: 0 }],
    expect: { search: true, avoid: ['earthquake'], says: '智谱' } },
  // ...and 「嗯TIBA这个人」 went to stock_quote as a ticker: it is Tibo, asked about on 11-06 (TI BO / TABLE then)
  tibaperson: { say: '嗯TIBA这个人', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    recent: [{ said: '我说的是为什么最近跌这么狠', replied: '最近这波大跌，主因不是单一利空，市场普遍归因为“再融资摊薄+高估值回落+盈利担忧”。', minutes_ago: 0 },
      { said: '查询制储的股价', replied: '智谱 426.20 港元，跌 10.46%（11 月 6 日收盘）', minutes_ago: 1 }],
    history: '找到 2 条：11月6日 0:05 你说「TI BO这个人」，星仪回答「你说的应该是 Tibo Sottiaux，X 账号 @thsottiaux，在 OpenAI 负责 ChatGPT、Codex 相关产品和工程。」；11月6日 0:06 你说「就是这个人然后你去查他的推特账号他经常会发一些重置相关的东西」，星仪回答「他 @thsottiaux 关于重置的推，最近一次是 11 月 2 日的“Day 28/ Banked 2/2”。」',
    // the sentence stops at 「这个人」: asking what about him (named) is as right as reading his newest post
    heard: '「TIBA」可能是11月6日说过的「Tibo」',
    expect: { called: ['recall_history'], avoid: ['stock_quote'] } },
  // 2026-11-07 12:20 on the TV: 「查询MAX最新的股价」 answered MediaAlpha (ticker MAX); 11:56 had asked about MINI MAX
  maxshort: { say: '查询MAX最新的股价', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    recent: [{ said: '为什么MINI MAX最新股价跌这么多', replied: '11月6日MiniMax港股大跌，更像板块性下杀，不是公司单一利空。', minutes_ago: 23 }],
    heard: '「MAX」可能是11月7日说过的「MiniMax」',
    expect: { called: ['stock_quote'], says: 'MINIMAX' } },
  // a singer's songs (2026-11-07): 「播放林俊杰的合集」 went 13 rounds through NetEase's screens; the artist alone is the run
  jjheji: { say: '播放林俊杰的合集', fg: 'com.netease.cloudmusic.tv', screen: '前台：网易云音乐 com.netease.cloudmusic.tv\n[1] 按钮「搜索」 焦点 可点\n[2] 卡片「每日推荐」 可点',
    playing: { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '街道 (The Streets)', artist: '林俊杰' },
    expect: { called: ['netease_play'], avoid: ['plan', 'open_app', 'press_keys', 'screen_click', 'web_search', 'explore_app'], run: true, artist: '林俊杰' } },
  jjhot: { say: '播放林俊杰热门的歌曲', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['netease_play'], avoid: ['plan', 'open_app', 'press_keys', 'web_search'], run: true, artist: '林俊杰' } },
  jjhexie: { say: '我让你播放林俊杰的歌应该是播放那个和谐为什么播放这么一点', fg: 'com.netease.cloudmusic.tv', screen: '前台：网易云音乐 com.netease.cloudmusic.tv\n[1] 按钮「搜索」 焦点 可点',
    playing: { app: 'com.netease.cloudmusic.tv', state: 'playing', title: '街道 (The Streets)', artist: '林俊杰' },
    expect: { called: ['netease_play'], avoid: ['plan', 'press_keys', 'screen_click', 'web_search'], run: true } },
  // 2026-11-08 12:05 on the TV: 「我想跳楼机」 played a run of 周杰伦 (NetEase has none of his songs: 邹沛沛《沉溺》), and
  // 「我要唱跳楼机」 first asked for 林俊杰's 《跳楼机》 (the user's favourite singer, not this song's): a song named without
  // its singer is the title alone
  tiaolouji: { say: '我想跳楼机', profile: PROFILE, fg: 'com.xiaomi.voicecontrol', screen: screens.launcher, playing: PLAYING_JJ, note: KUGOU_NOTE,
    recent: [{ said: '打开网易云', replied: '已打开网易云音乐', minutes_ago: 5 }],
    expect: { called: ['netease_play'], avoid: ['plan', 'open_app', 'press_keys', 'screen_click'], play: '跳楼机', notRun: true, guessed: ['林俊杰', '周杰伦'] } },
  changtlj: { say: '我要唱跳楼机', profile: PROFILE, fg: 'com.xiaomi.voicecontrol', screen: screens.launcher, playing: PLAYING_JJ,
    recent: [{ said: '打开网易云', replied: '已打开网易云音乐', minutes_ago: 5 }],
    expect: { called: ['netease_play'], avoid: ['plan', 'open_app', 'press_keys', 'screen_click'], play: '跳楼机', notRun: true, guessed: ['林俊杰', '周杰伦'] } },
  ruguo: { say: '我要唱如果可以', profile: PROFILE, fg: 'com.xiaomi.voicecontrol', screen: screens.launcher, playing: PLAYING_JJ,
    recent: [{ said: '我要唱跳楼机', replied: '网易云音乐正在播放LBI利比的《跳楼机》。', minutes_ago: 3 }],
    expect: { called: ['netease_play'], avoid: ['plan', 'open_app', 'press_keys', 'screen_click'], play: '如果可以', notRun: true, guessed: ['林俊杰', 'LBI'] } },
  // 14:22 the same day: 「伴奏模式」 / 「切成原唱」 — the user: 「就是麦克风图标啊 … 可以调整伴奏和人声的比例」
  banzou: { say: '把这首歌切成伴奏模式', fg: 'com.netease.cloudmusic.tv', screen: NE_PLAYER, playing: PLAYING_HU, karaokeSim: true, karaoke: false,
    expect: { karaoke: true, noUp: true, avoid: ['netease_play', 'web_search', 'open_app'] } },
  yuanchang: { say: '现在是伴奏模式，切回原唱', fg: 'com.netease.cloudmusic.tv', screen: NE_PLAYER, playing: PLAYING_HU, karaokeSim: true, karaoke: true,
    expect: { karaoke: false, noUp: true, avoid: ['netease_play', 'web_search', 'open_app'] } },
  // On the TV: 「我有点不舒服腿酸了」 got 「没太听清，是脚扭伤不舒服吗，还是别的？」 — the user had to say
  // 「我腿酸了呀」 before any help came: a body complaint is answered with care and first steps, not asked back
  footache: { say: '我有点不舒服腿酸了', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { avoid: ['ask_user', 'web_search', 'plan', 'open_app'], says: ['冰敷', '休息', '医院', '别走', '垫高', '抬高'] } },
  headache: { say: '我头好疼啊', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { avoid: ['ask_user', 'plan', 'open_app'], says: ['休息', '喝水', '医院', '药', '躺'] } },
  // 「关闭桌面不要关机」 (2026-11-08 14:56, 17:59; the user: the screen black, the TV in standby): 17:59 asked back instead
  deskoff: { say: '那个关闭桌面，不要关机', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['screen_off'], avoid: ['ask_user', 'media_control', 'press_keys'] } },
  deskoffdone: { say: '那个关闭桌面，不要关机', fg: 'com.xiaomi.voicecontrol', screen: screens.launcher, screenOff: true,
    note: '小爱自己的处理：动作 Application.CheckApps、System.CheckScreenUnlocked、Launcher.LaunchApp',
    expect: { avoid: ['ask_user', 'wake_screen', 'media_control', 'press_keys'], says: '待机' } },
  // 12:14 the same day: the bridge's song path took 「回到桌面退出网易云」 for a song (《布拉格广场》); it is 星仪's now
  exitnetease: { say: '回到桌面退出网易云', fg: 'com.netease.cloudmusic.tv', screen: '前台：网易云音乐 com.netease.cloudmusic.tv\n[1] 按钮「搜索」 焦点 可点',
    playing: PLAYING_JJ, expect: { exited: true, avoid: ['netease_play', 'web_search', 'plan', 'press_keys'] } },
  // find_tools (2026-11-07): requests whose words the gates miss find their tool instead of searching the web
  findsongs: { say: '林俊杰最近有什么作品', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['music_new'], avoid: ['web_search'], says: 'Rise Without A Sound' } },
  findmovies: { say: '最近有啥新上的电影', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['movie_list'], avoid: ['web_search'], says: '欢迎来龙餐馆' } },
  findanime: { say: '凡人修仙出新的一集了没', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_bangumi'], avoid: ['web_search'], says: ['198', '199'] } },
  nowshowing: { say: '最近有什么电影上映', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['movie_list'], avoid: ['web_search', 'open_app', 'tencent_video_search'], says: '欢迎来龙餐馆' } },
  goodkdrama: { say: '有什么好看的韩剧推荐一下', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['movie_list'], avoid: ['web_search', 'open_app'], says: '我的有罪之人' } },
  variety: { say: '最近有什么好看的综艺', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['movie_list'], avoid: ['web_search', 'open_app'], says: '后会友期' } },
  wherewatch: { say: '狂飙在哪个平台能看', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['movie_rating'], avoid: ['web_search', 'open_app'], says: '爱奇艺' } },
  episodes: { say: '繁花一共几集', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['movie_rating'], avoid: ['web_search'], says: '30' } },
  whereplay: { say: '流浪地球2在哪能看，帮我在电视上打开', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    // since tencent_video_open (2026-11-10) the film opens in one step; through movie_rating and B站's link is as good
    expect: { calledAny: ['tencent_video_open', 'open_link'], avoid: ['web_search'], says: '流浪地球2' } },
  moviescore: { say: '狂飙在豆瓣上评分多少', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['movie_rating'], avoid: ['web_search', 'open_app'], says: '8.5' } },
  worthwatch: { say: '流浪地球2值得看吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['movie_rating'], avoid: ['web_search', 'open_app', 'tencent_video_search'], says: '8.3' } },
  fanhuagood: { say: '繁花这部剧好看吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['movie_rating'], avoid: ['web_search', 'open_app'], says: '8.7' } },
  uplive: { say: '影视飓风现在在直播吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_videos'], avoid: ['open_link', 'bili_search', 'web_search'], says: ['没在直播', '没有在直播', '不在直播', '没有直播', '没在播', '没有开播', '没开播', '没有正在直播', '没在直播间'] } },
  upsecond: { say: '放第二个', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    lastList: { what: '影视飓风最新的视频', note: '刚读给用户的B站视频：用户说“放第二个”“看第一个”就是这里的编号，用 open_link 打开它的 link',
      items: [{ n: 1, title: '动态视频｜2026，它来了…', up: '影视飓风', date: '昨天', link: 'bilibili://video/117568757109187' },
        { n: 2, title: '真的需要摸吗？MacBook Pro PROLED XDR上手', up: '影视飓风', date: '前天', link: 'bilibili://video/117563807766583' },
        { n: 3, title: '动态视频｜苹果的触屏MacBook Pro来了，你会买么？', up: '影视飓风', date: '10 月 29 日', link: 'bilibili://video/117519767570878' }] },
    recent: [{ said: '影视飓风最近发了什么视频', replied: '影视飓风最新的 3 个视频：第一，《动态视频｜2026，它来了…》，昨天，13 万播放；第二，《真的需要摸吗？MacBook Pro PROLED XDR上手》，前天，162 万播放；第三，《动态视频｜苹果的触屏MacBook Pro来了，你会买么？》，10 月 29 日，145 万播放' }],
    expect: { called: ['open_link'], avoid: ['bili_videos', 'bili_search'], play: '真的需要摸吗' } },
  // a name XiaoAi mishears the same way each time is written right before the model sees it (Grounding.BUILTIN); any
  // other near miss is found by the UP search (BiliVideos.pickUser: a character off)
  upalias: { say: '放星辰人旅行者最新的视频', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_videos', 'open_link'], avoid: ['bili_search'], play: 'TV agent工程' } },
  upmisheard: { say: '放影视飓凤最新的视频', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    expect: { called: ['bili_videos', 'open_link'], avoid: ['bili_search'], play: '动态视频｜2026' } },
  bilisecond: { say: '放第二个', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    lastList: { what: 'B站热门', note: '刚读给用户的列表：用户说“第二个”“放第三个”就是这里的编号，用 open_link 打开它的 link',
      items: [{ n: 1, title: '《崩坏：因缘精灵》幸福PV：缘汽一下', up: '崩坏因缘精灵', link: 'bilibili://video/BV1nm4g6NE71' },
        { n: 2, title: '真的需要摸吗？MacBook Pro PROLED XDR上手', up: '影视飓风', link: 'bilibili://video/117563807766583' },
        { n: 3, title: '物理意义上烫烫的一集', up: '吴磊LEO', link: 'bilibili://video/BV1mpxR6KESh' }] },
    recent: [{ said: 'B站有什么热门视频', replied: 'B站热门前 3 个：第一，《崩坏：因缘精灵》幸福PV：缘汽一下，崩坏因缘精灵，426 万播放；第二，《真的需要摸吗？MacBook Pro PROLED XDR上手》，影视飓风，68 万播放；第三，《物理意义上烫烫的一集》，吴磊LEO，131 万播放' }],
    expect: { called: ['open_link'], avoid: ['bili_search', 'web_search', 'hot_list'] } },
  hotthird: { say: '第三条热搜是怎么回事', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    lastList: { what: '百度热搜', note: '刚读给用户的列表：用户问“第二条是怎么回事”就是这里的编号，用 web_search 查那一条',
      items: [{ n: 1, title: '美股暴跌 遭遇历史性抛售' }, { n: 2, title: '东风-61已列装火箭军' }, { n: 3, title: '6G有啥不一样' }] },
    expect: { called: ['web_search'], avoid: ['hot_list'] } },
  quake: { say: '刚才是不是地震了', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['earthquake'], avoid: ['web_search'] } },
  quakesc: { say: '四川最近有没有地震', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['earthquake'], avoid: ['web_search'] } },
  oil: { say: '现在加满一箱92号要多少钱一升', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['fuel_price'], avoid: ['web_search'] } },
  fund: { say: '易方达蓝筹今天涨了多少', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['fund_nav'], avoid: ['web_search'] } },
  history: { say: '历史上的今天发生过什么大事', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['history_today'], avoid: ['web_search'] } },
  worldny: { say: '纽约现在几点', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['world_clock'], avoid: ['web_search'] } },
  timediff: { say: '伦敦跟我们这儿差几个小时', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['world_clock'], avoid: ['web_search'] } },
  tvnow: { say: '央视五套现在播的是啥', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['tv_guide'], avoid: ['web_search'] } },
  tvfind: { say: '新闻联播几点开始', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['tv_guide'], avoid: ['web_search'] } },
  tvtonight: { say: '今晚湖南卫视有什么好看的', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['tv_guide'], avoid: ['web_search'] } },
  newstech: { say: '最近有什么科技新闻', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['news'], avoid: ['web_search'] } },
  newsent: { say: '最近有什么娱乐圈的新闻', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['web_search'] } },
  newsdetail: { say: '第二条是怎么回事', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    lastList: { what: '国际新闻', note: '刚读给用户的新闻标题：用户问“第二条怎么回事”先用它的 summary 说，要更多细节再用 web_search 查那一条',
      items: [{ n: 1, title: '进博会聚焦全球经济再平衡：开放合作是关键', summary: '正在上海举行的第九届中国国际进口博览会期间，全球经济再平衡议题受到关注。' },
        { n: 2, title: '鲁比奥否认计划离职竞选美国总统 称将为特朗普效力', summary: '美国国务卿鲁比奥5日否认有意辞职参加2028年总统选举，称将继续在特朗普政府任职。' },
        { n: 3, title: '土耳其连环枪击案致8死1伤 枪手被捕', summary: '土耳其南部一城市5日发生连环枪击事件，造成8人死亡1人受伤，枪手已被逮捕。' }] },
    expect: { avoid: ['news', 'hot_list'], says: ['鲁比奥'] } },
  chart: { say: '最近什么歌最火', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['hot_list'], avoid: ['web_search'] } },
  playfirst: { say: '放第二首', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    lastList: { what: '网易云热歌榜', note: '刚读给用户的歌：用户说“放第一首”“放第三首”就用 netease_play 播那一首（title 填歌名，artist 填歌手）',
      items: [{ n: 1, title: 'Hong Kong (feat. 王菲季候风)', artist: 'The Weeknd' }, { n: 2, title: '我不难过', artist: '孙燕姿' }, { n: 3, title: '明知故犯', artist: 'Max李玄' }] },
    expect: { called: ['netease_play'], avoid: ['hot_list', 'web_search'], playing: '我不难过' } },
  knicks: { say: '尼克斯昨天打得怎么样', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['nba_games'], avoid: ['web_search'] } },
  epl: { say: '曼联昨天赢了吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['football_games'], avoid: ['web_search', 'nba_games'] } },
  csl: { say: '申花最近一场比赛结果怎么样', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['football_games'], avoid: ['web_search'] } },
  cba: { say: '辽宁队昨天CBA赢了吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['web_search'], avoid: ['football_games', 'nba_games'] } },
  ssq: { say: '最近一期双色球开的什么号', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['lottery'], avoid: ['web_search'] } },
  goldring: { say: '周大福今天金价多少一克', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['web_search'] } },
  market: { say: '今天大盘怎么样', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['stock_quote'], avoid: ['web_search'] } },
  maotai: { say: '茅台现在股价多少', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['stock_quote'], avoid: ['web_search'], says: '1453' } },
  usdrop: { say: '美股昨天为什么跌这么多', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['web_search'] } },
  usd: { say: '一美元现在换多少人民币', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['exchange_rate'], avoid: ['web_search'] } },
  eur100: { say: '一百五十欧元合多少人民币啊', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['exchange_rate'], avoid: ['web_search'], says: ['1127', '1128'] } },
  usdjpy: { say: '一千美元能换多少日元', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['exchange_rate'], avoid: ['web_search'] } },
  hot: { say: '最近网上都在聊什么', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['hot_list'], avoid: ['web_search'] } },
  bilihot: { say: 'B站最近有什么热门视频', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['hot_list'], avoid: ['web_search', 'bili_search'] } },
  dress: { say: '北京明天穿什么', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search'] } },
  bjsat: { say: '北京周六天气怎么样', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search', 'calendar'] } },
  sunset: { say: '东京今天几点天黑', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search'] } },
  bjrain: { say: '北京明天会下雨吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search'], says: '北京' } },
  replaysong: { say: '昨天放的那首歌再放一遍', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher,
    history: '找到 1 条：昨天 21:10 你说「放首林俊杰的歌」，星仪回答「网易云正在播放林俊杰的《江南》」（当时在放《江南》）',
    expect: { called: ['recall_history', 'netease_play'], avoid: ['tv_usage'] } },
  tvtoday: { say: '电视今天开了多久', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['tv_usage'], avoid: ['recall_history', 'web_search'] } },
  festival: { say: '下一个节日是什么', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['calendar'], avoid: ['web_search'] } },
  broken: { say: '你今天怎么老是不灵', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['self_check'], avoid: ['plan', 'web_search'] } },
  abilities: { say: '星仪你都能做什么', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { avoid: ['web_search', 'plan', 'open_app'] } },
  weathertmr: { say: '明天天气怎么样', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search', 'ask_user', 'plan'], says: '明天' } },
  umbrella: { say: '后天出门要带伞吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search', 'plan'], says: ['伞', '要带', '带上'] } },
  airq: { say: '今天空气质量怎么样', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search'], says: '良' } },
  beijing: { say: '北京这几天天气怎么样', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather'], avoid: ['web_search'], says: '北京' } },
  // the simulated web_search knows nothing about typhoons, so a second try is fair: only the tool choice is checked
  typhoon: { say: '今年有台风要来上海吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather_alert'], avoid: ['weather', 'plan'] } },
  // almanac (2026-11-07): 宜忌 of a day, a thing on a day, a month's good days
  yiji: { say: '今天黄历宜什么忌什么', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['almanac'], avoid: ['web_search', 'calendar'], says: '打扫' } },
  movehouse: { say: '明天适合搬家吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['almanac'], avoid: ['web_search'], says: '忌搬家' } },
  moveremind: { say: '明天适合搬家吗，适合的话提醒我早上八点打包', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['almanac'], says: '忌' } },
  // the review, 2026-11-07: 「帮我记到随手记」 after the almanac's own final
  movenote: { say: '明天适合搬家吗？适合的话帮我记到随手记', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['almanac'], avoid: ['note_add'], says: '忌' } },
  weddingday: { say: '下个月哪天适合结婚', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['almanac'], avoid: ['web_search'], says: '12 月 4 日' } },
  alarmnow: { say: '现在有什么天气预警', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather_alert'], avoid: ['web_search', 'weather'], says: '没有' } },
  alarmyn: { say: '云南那边有什么预警', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather_alert'], avoid: ['web_search'], says: '28' } },
  typhoonnow: { say: '现在有台风吗', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['weather_alert'], avoid: ['web_search', 'weather'], says: '没有' } },
  nohints: { say: '别弹提示了', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['set_hints'], avoid: ['remember', 'plan'] } },
  // one-step requests: straight to the connector, no plan (AgentLoop.DIRECT)
  timer: { say: '半小时后关机', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['sleep_timer'], avoid: ['plan'] } },
  favorites: { say: '播放我喜欢的音乐', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['netease_playlist'], avoid: ['plan'] } },
  artist: { say: '放首周杰伦的歌', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { artist: '周杰伦', avoid: ['plan'] } },
  wifi: { say: '打开WiFi设置', fg: 'xyz.erapi0neer.orrery', screen: screens.launcher, expect: { called: ['open_settings'], avoid: ['plan'] } },
};

// Skills (contract.json "skills"): 应用经验 picked for the first message, or attached to the tool that reaches the app
const SKILLS = contract.skills ?? [];
const lower = (s) => String(s ?? '').toLowerCase();
function pickSkills(said, foreground, context) {
  // as Skills.select: the request's words (and a short follow-up's previous request), not the app in front
  const text = lower(said) + '\n' + lower(context), own = lower(said);
  return SKILLS.filter((k) => !(k.unless ?? []).some((w) => w && own.includes(lower(w)))
    && k.match.some((w) => w && text.includes(lower(w))));
}
function skillFor(tool, args) {
  const target = lower((args?.app ?? '') + '\n' + (args?.uri ?? ''));
  return SKILLS.find((k) => k.tools.includes(tool) || ((tool === 'open_app' || tool === 'open_link')
    && (k.packages.some((p) => p && target.includes(lower(p))) || k.match.some((w) => w && target.includes(lower(w)))))) ?? null;
}
const followUp = (said) => String(said).trim().length <= 10 || /^(继续|接着做|星仪继续|继续执行)/.test(String(said).trim());
// SearchBudget.FOUND: a search that found its conclusion closes the task's budget
const SEARCH_FOUND = '上一次联网已经查到了结论，直接用它回答（它没说到的细节就如实说没查到），不要为了更具体再查：每查一次用户要多等 5 到 10 秒。';
// AgentLoop.invoke's plan gate: reads, memory tools and one-step connectors run without a plan
const READS = new Set(['screen_read', 'wait_for', 'screen_look', 'list_apps', 'tv_status', 'web_search', 'x_search', 'media_now_playing',
  'remember', 'forget', 'remember_name', 'list_schedules', 'note_add', 'note_list', 'note_done', 'recall_history', 'set_hints',
  'explore_app', 'app_note', 'calendar', 'weather', 'tv_usage', 'self_check', 'stock_quote', 'exchange_rate', 'hot_list', 'gold_price', 'lottery', 'nba_games', 'football_games', 'world_clock', 'tv_guide', 'news', 'earthquake', 'fuel_price', 'fund_nav', 'history_today', 'bili_videos', 'movie_rating', 'bili_bangumi', 'weather_alert', 'almanac', 'movie_list', 'music_new']);
const DIRECT = new Set(['netease_play', 'netease_playlist', 'netease_karaoke', 'bili_search', 'tencent_video_search', 'tencent_video_open', 'set_volume', 'adjust_volume', 'media_control',
  'sleep_timer', 'switch_input', 'open_app', 'open_settings', 'xiaoai_ask', 'schedule_task', 'cancel_schedule', 'wake_screen', 'screen_off', 'open_link', 'free_memory', 'install_app']);
const NEED_PLAN = '还没有调用 plan，操作被拒绝。一步就能做完的请求直接用连接器（netease_play、bili_search、set_volume、open_app 等）；'
  + '要点选、按键、输入或打开链接的任务，先用 plan 写出你对用户意图的理解和步骤再操作；如果用户只是在提问、抱怨或聊天，直接用文字回答，不要操作。';
// AgentLoop's guards from the 2026-11-05 evening: a question that names no app gets no app (Dispatch.asksOnly), and an
// answer to 星仪's question is not met with a second one (Request.answering)
const OPENS = new Set(['bili_search', 'tencent_video_search', 'tencent_video_open', 'open_app', 'open_link', 'netease_play', 'netease_playlist', 'open_settings', 'install_app']);
const QUESTION = /什么|怎么样|怎样|多少|几点|几号|几度|几个|哪|谁|为什么|为啥|是不是|有没有|会不会|对不对|好不好|吗|[呢么嘛][？?]?$|[？?]$|^(?:我想知道|想知道|告诉我|问一下|问问|查一下|查查|帮我查|你知道)|天气|气温|新闻|动向|消息|比分|汇率|股价/;
const ACTION = /放(?!的)|播(?!的|放的)|打开|关掉|关闭|关机|调|切到|切换|换到|安装|下载|删|设置|设个|提醒|记住|记一下|预约|定时|暂停|退出|返回|点开|[Bb]站|哔哩|爱奇艺|腾讯视频|优酷|芒果|抖音|网易云|应用|软件/;
const asksOnly = (said) => QUESTION.test(String(said).trim()) && !ACTION.test(String(said).trim());
const JUST_ASKING = '用户只是在提问，没说要在哪个应用里看：不要打开应用，用 web_search 查（结论就是回答时填 final=true），或者直接回答。用户想在应用里看，会自己说“打开…”“放…”。';
const NO_SECOND_ASK = '用户刚回答了你上一个问题，不要再问：按他的回答和最可能的理解直接去做或回答，另一种理解用半句话带上（例如“要是你说的是…，再告诉我”）。';
// AgentLoop.searchThenAct: a search for a request that asks to play, open or remind is a step, not the answer
const THEN_ACT = /播放(?!量|数|次数|最多|最高|最火)|播一|放一?[首下个集部遍]|放给|放(?:他|她|它|那)|打开|点开|提醒我|预约|定时|记一下|收藏|下载|安装|(?<!播)放[^，。？！]{0,12}?(?:视频|第[一二三四五六七八九十两0-9]+[个首条集部])|看[^，。？！]{0,12}?视频|设个?提醒|定个?提醒|记到|记下|加到|日程|我要看|我想看|想看|帮我放|给我放|给我播/;
// AgentLoop's later words for an episode (2026-11-07): 「放凡人修仙传最新一集」
const THEN_ACT_EPISODE = /(?<!播)放[^，。？！]{0,12}?最新一[集话期]|看[^，。？！]{0,12}?(?:视频|最新一[集话期])/;
// AgentLoop.DATA: every data tool's answer waits when the request goes on to act (2026-11-07 review)
const DATA_TOOLS = ['weather', 'stock_quote', 'exchange_rate', 'hot_list', 'gold_price', 'lottery', 'nba_games', 'football_games', 'world_clock', 'tv_guide', 'news', 'earthquake', 'fuel_price', 'fund_nav', 'history_today', 'bili_videos', 'movie_rating', 'bili_bangumi', 'weather_alert', 'almanac', 'movie_list', 'music_new'];
// AgentLoop.searchAnswer: a final search alone in its round that found a conclusion is the answer; a why-question is
// never closed by a data tool's own words (2026-11-07: 「最近为什么地震了」 read out China's earthquakes)
const WHY = /为什么|为啥|怎么回事|怎么又|怎么老|怎么总|咋回事|咋又/;
function searchAnswer(calls, argsOf, results, said) {
  const data = ['weather', 'stock_quote', 'exchange_rate', 'hot_list', 'gold_price', 'lottery', 'nba_games', 'football_games', 'world_clock', 'tv_guide', 'news', 'earthquake', 'fuel_price', 'fund_nav', 'history_today', 'bili_videos', 'movie_rating', 'bili_bangumi', 'weather_alert', 'almanac', 'movie_list', 'music_new'];
  if (data.includes(calls[0]?.function.name) && WHY.test(String(said ?? ''))) return null;
  // a data tool may declare its own answer final (bili_videos asked whether an UP is live)
  const declared = data.includes(calls[0]?.function.name) && results[0]?.final === true;
  if (calls.length !== 1 || !['web_search', 'x_search', ...data].includes(calls[0].function.name) || (argsOf[0]?.final !== true && !declared)) return null;
  const r = results[0], a = String(r?.answer ?? '').trim();
  if (data.includes(calls[0].function.name)) return r?.ok && a ? a : null;
  return r?.ok && r.searched && a && !/没有找到|没找到|没查到|查不到|未找到|找不到|无法确认|没有相关|暂无/.test(a) ? a : null;
}
// TvTools' gates for what XiaoAi already did with this sentence (environment.note lists its instructions)
function handedDenial(name, args, note, state, sc) {
  if (!note) return null;
  if ((name === 'adjust_volume' || name === 'set_volume') && /Speaker\./.test(note))
    return '小爱已经为这句话调过音量了，再调一次会翻倍；用 tv_status 看现在的音量，告诉用户结果。';
  if (name === 'media_control' && /next|previous/.test(args.action ?? '') && /PlaybackController\.|AudioPlayer\.|VideoPlayer\./.test(note)) {
    // since 6d32cf8 (MediaSessions.xiaoaiSkip): XiaoAi's skip is believed unless the song playing is the one of the
    // request and was more than 3 s in then; never with 云视听极光 in front
    const secs = (s) => { const m = /^(\d+):(\d\d)$/.exec(String(s ?? '')); return m ? Number(m[1]) * 60 + Number(m[2]) : -1; };
    const now = state?.playing, then = sc?.playing;
    const missed = now && then && now.state === 'playing' && now.title === then.title && secs(then.position) > 3 && state.fg !== 'com.ktcp.video';
    if (!missed) return '小爱已经为这句话切过了，再切一次会多跳一个；' + (now
      ? `现在网易云音乐 ${now.state === 'paused' ? '已暂停' : '正在播放'}《${now.title}》${now.artist ? ' ' + now.artist : ''}${now.position ? '，放到 ' + now.position : ''}：照这个告诉用户结果。`
      : '用 media_now_playing 看现在放的是什么，告诉用户结果。');
  }
  return null;
}
// AgentLoop.directAnswer / spoken
const MEMORY = new Set(['remember', 'forget', 'remember_name']);
const ANSWERS = new Set(['tencent_video_open', 'netease_karaoke', 'netease_play', 'netease_playlist', 'set_volume', 'adjust_volume', 'media_control', 'sleep_timer',
  'schedule_task', 'cancel_schedule', 'note_add', 'note_done', 'set_hints', 'screen_off', 'free_memory']);
function directAnswer(calls, argsOf, results) {
  if (!calls.length || !closesOnEvidence(calls, argsOf, results)) return null;
  const said = [];
  for (const [i, c] of calls.entries()) {
    const n = c.function.name, r = results[i], message = String(r.message ?? '').trim();
    let m;
    if (MEMORY.has(n)) m = message.replace(/用户/g, '你');
    // AgentLoop.playedLink: a link seen playing a video is the answer
    else if (n === 'open_link' && r.playing) m = r.playing.title ? `正在播放《${r.playing.title}》` : message;
    else if (!ANSWERS.has(n)) return null;
    else if (n === 'set_volume') m = `音量调到 ${argsOf[i]?.level} 了`;
    else if (n === 'adjust_volume') m = r.muted ? '已静音' : 'volume' in r ? `音量 ${r.volume}` : message;
    else if (n === 'media_control') m = message === '已发送' ? '好的' : message;
    else if (n === 'netease_karaoke') m = message; // its message says the state it found or set (2026-11-09)
    else m = message;
    if (!m) return null;
    said.push(m);
  }
  return said.join('；');
}

// AgentLoop.closesOnEvidence: a finish(done) beside actions stands only when each action's result is its own evidence
const META = new Set(['plan', 'plan_update', 'ask_user', 'finish', 'find_tools']);
const EVIDENT = new Set(['tencent_video_open', 'netease_karaoke', 'netease_play', 'netease_playlist', 'bili_search', 'set_volume', 'adjust_volume', 'media_control',
  'open_app', 'open_link', 'open_settings', 'sleep_timer', 'switch_input', 'install_app', 'xiaoai_ask', 'remember', 'forget', 'remember_name',
  'schedule_task', 'cancel_schedule', 'note_add', 'note_done', 'wake_screen', 'screen_off', 'set_hints', 'free_memory']);
function closesOnEvidence(calls, argsOf, results) {
  return calls.every((c, i) => {
    const n = c.function.name, r = results[i];
    if (META.has(n)) return true;
    if (!r?.ok || r.status === 'requested' || r.status === 'no_change') return false;
    if (n === 'screen_click') return 'playing' in r;
    return EVIDENT.has(n) && (n !== 'sleep_timer' || 'minutes' in (argsOf[i] ?? {}));
  });
}

async function run(name) {
  const sc = scenarios[name];
  const state = { karaokeSim: sc.karaokeSim, karaoke: sc.karaoke, say: sc.say, playing: sc.playing, fg: sc.fg, screen: sc.screen, results: sc.results, history: sc.history, noteList: sc.noteList,
    screenOn: !sc.screenOff, volumeNow: sc.volumeNow };
  if (!process.env.CAL_CLEAN_ENV) state.apps = sc.apps ?? REAL_APPS;
  // a TV that has TV Bro opens a web address in it (the TV since 2026-11-08). Until 2026-11-18 only install_app 浏览器
  // gave the simulation a browser: hongguowebhas (TV Bro installed) opened the right site in round 1 on 11-11, was told
  // 「电视上没有应用能打开这个链接」 and spent four rounds on TV Bro; hongguoapk ran to round 10 the same way on 11-16.
  state.browser = (state.apps ?? []).includes('TV Bro');
  const tools = sim(state);
  const env = { time: '2026-11-04 23:20 星期三', foreground: sc.fg, volume: '10/100',
    // as AgentEngine.environment since 2026-11-18: paused or playing, and where the track is when the session says
    now_playing: sc.playing ? `${sc.playing.app === 'com.netease.cloudmusic.tv' ? '网易云音乐' : '哔哩哔哩'} ${sc.playing.state === 'paused' ? '已暂停' : '正在播放'}《${sc.playing.title}》`
      + (sc.playing.artist && sc.playing.position ? ` ${sc.playing.artist}` : '') + (sc.playing.position ? `，放到 ${sc.playing.position}` : '') : null,
    screen: sc.screenOff ? '关着（电视在待机）' : '开着',
    // AgentEngine.environment since 2026-11-06: where the TV is, the Chinese calendar, the quiet hours (23:00-7:00)
    place: '上海徐汇', calendar: '农历九月廿六（丙午马年）',
    quiet: '深夜，家里人可能在睡觉：回答说短一点，不要主动点亮屏幕或大声放东西' };
  if (process.env.CAL_OLD_ENV) { delete env.place; delete env.calendar; delete env.quiet; }
  // The environment as the TV really sends it (2026-11-11): the foreground with its label, the list of installed apps
  // (AgentEngine.environment puts both in; the calibration never had them), and what the TV knows about its user
  // unless the scenario brings its own. Without them 「我想看别无选择」 was right 12 times of 12; with them it went to
  // bili_search or to netease_play (「林俊杰的《别无选择》」) 7 times of 16 — as it did on the TV. CAL_CLEAN_ENV=1 gives
  // the old, bare environment for an A/B. A scenario recorded in another world says so with `apps`.
  if (!process.env.CAL_CLEAN_ENV) {
    const LABELS = { 'xyz.erapi0neer.orrery': '桌面（Orrery，xyz.erapi0neer.orrery）', 'tv.danmaku.bili': '哔哩哔哩（tv.danmaku.bili）',
      'com.netease.cloudmusic.tv': '网易云音乐（com.netease.cloudmusic.tv）', 'com.ktcp.video': '云视听极光（com.ktcp.video）',
      'com.xiaomi.voicecontrol': '小爱同学（com.xiaomi.voicecontrol）', 'com.xiaomi.mitv.settings': '电视设置（com.xiaomi.mitv.settings）' };
    env.foreground = LABELS[sc.fg] ?? sc.fg;
    env.apps = sc.apps ?? REAL_APPS;
    if (!sc.profile) sc.profile = REAL_PROFILE;
  }
  state.profile = sc.profile;
  for (const k of (process.env.CAL_DROP ?? '').split(',').filter(Boolean)) delete env[k];
  if (process.env.CAL_TIME_LUNAR) { env.time += '，农历九月廿六'; delete env.calendar; }
  if (sc.note) env.note = sc.note;
  if (sc.lastList) env.last_list = sc.lastList;
  // AgentEngine since 2026-11-07 (job heard-like): words that look like a name said or answered before (Heard.line)
  if (sc.heard) env.heard_like = sc.heard;
  // AgentEngine since 2026-11-06: a question about the screen comes with the screen
  if (sc.onScreen) env.on_screen = sc.screen;
  const user = { utterance: canonical(sc.say), environment: env, previous_task: sc.previous ?? null };
  if (sc.recent) user.recent_turns = sc.recent;
  const context = followUp(sc.say) ? [sc.recent?.at(-1)?.said, sc.previous?.utterance, sc.previous?.goal].filter(Boolean).join('\n') : '';
  const guided = new Set();
  const first = pickSkills(sc.say, sc.fg, context);
  if (first.length) { user['应用经验'] = first.map((k) => k.text); first.forEach((k) => guided.add(k.name)); }
  if (first.length) console.log(`  (应用经验: ${first.map((k) => k.name).join(', ')})`);
  // the profile rides in a user message of its own ahead of the utterance, as in AgentLoop (PROFILE_HEADER)
  // AgentLoop.gateContext: the request, what a follow-up follows, the task a reply answers, the list just read out
  const gated = process.env.CAL_NO_GATES ? null
    : gatedIn([sc.say, context || (sc.answering && sc.previous ? `${sc.previous.utterance ?? ''}\n${sc.previous.goal ?? ''}` : ''), sc.lastList?.what ?? ''].join('\n'));
  const toolsNow = () => (gated ? toolsFor(new Set([...gated, ...(state.found ?? [])])) : contract.tools);
  const messages = [{ role: 'system', content: gated ? systemFor(gated) : contract.system },
    ...(sc.profile ? [{ role: 'user', content: '「关于用户」\n' + sc.profile }] : []),
    { role: 'user', content: JSON.stringify(user) }];
  console.log(`\n=== ${name}: ${sc.say}`);
  if (gated && gated.size) console.log(`  (data tools: ${[...gated].join(', ')})`);
  let total = 0;
  for (let round = 1; round <= 10; round++) {
    state.rounds = round;   // the model rounds this scenario took (expect.maxRounds)
    if (!state.strong && STRONG && STRONG !== MODEL && (round >= ESCALATE_ROUND || (state.failures ?? 0) >= ESCALATE_FAILURES)) {
      state.strong = true;
      console.log(`  R${round} (escalated to ${STRONG}: ${round >= ESCALATE_ROUND ? 'not done by round ' + ESCALATE_ROUND : 'failed twice'})`);
    }
    const { msg, ms } = await chat(messages, state.strong ? STRONG : MODEL, state.strong ? contract.tools : toolsNow());
    total += ms;
    messages.push(msg);
    const calls = msg.tool_calls ?? [];
    if (!calls.length) { state.final = String(msg.content).replace(/<think>[\s\S]*?<\/think>/g, '').trim(); console.log(`  R${round} ${ms}ms FINAL: ${state.final}`); break; }
    // as AgentLoop: plan first, then the rest as given, ask_user/finish last; tool messages go back in the model's order
    const rank = (c) => (c.function.name === 'plan' ? 0 : c.function.name === 'finish' || c.function.name === 'ask_user' ? 2 : 1);
    const order = [...calls.keys()].sort((a, b) => rank(calls[a]) - rank(calls[b]) || a - b);
    const results = [], argsOf = [];
    const asking = !sc.answering && calls.some((c) => c.function.name === 'ask_user');
    let ending = null;
    for (const i of order) {
      const c = calls[i];
      let args = {}; try { args = JSON.parse(c.function.arguments || '{}'); } catch { args = { _bad: c.function.arguments }; }
      argsOf[i] = args;
      (state.called ??= []).push(c.function.name);
      console.log(`  R${round} ${ms}ms ${c.function.name} ${JSON.stringify(args)}`);
      if (ending) { results[i] = { ok: false, status: 'skipped', message: '本轮已结束，未执行' }; continue; }
      if (c.function.name === 'ask_user' && sc.answering) { results[i] = { ok: false, status: 'rejected', message: NO_SECOND_ASK }; continue; }
      if (c.function.name === 'finish' || c.function.name === 'ask_user') { ending = { name: c.function.name, args }; results[i] = { ok: true, status: 'recorded', message: '已记录' }; continue; }
      if (c.function.name === 'find_tools') {
        const found = searchTools(args.query);
        if (!found.length) { results[i] = { ok: true, status: 'none', message: `没有和「${args.query}」对上的工具。下面是只在用得上时才发的工具，有合适的就用它的名字再 find_tools 一次；都不合适就用 web_search`,
          tools: Object.keys(GATES).map((k) => ({ name: k, description: DESCRIPTIONS[k].split(/[：。]/)[0] })) }; continue; }
        (state.found ??= []).push(...found);
        const rules = contract.system.split('\n').filter((l) => found.some((n) => new RegExp(`(^|[^A-Za-z0-9_])${n}([^A-Za-z0-9_]|$)`).test(l))).map((l) => l.trim());
        results[i] = { ok: true, status: 'found', message: `这些工具从下一轮起可以直接调用：${found.join('、')}`,
          tools: contract.tools.filter((t) => found.includes(t.function.name)).map((t) => t.function), ...(rules.length ? { rules } : {}) };
        console.log(`  R${round} (find_tools found ${found.join(', ')})`);
        continue;
      }
      if (c.function.name === 'plan') state.planned = true;
      const read = READS.has(c.function.name) || (c.function.name === 'sleep_timer' && !('minutes' in args));
      if (!read && !META.has(c.function.name) && !state.planned && !(DIRECT.has(c.function.name) && !asking)) {
        results[i] = { ok: false, status: 'rejected', message: NEED_PLAN }; continue;
      }
      if (OPENS.has(c.function.name) && asksOnly(sc.say)) { results[i] = { ok: false, status: 'rejected', message: JUST_ASKING }; continue; }
      const denied = handedDenial(c.function.name, args, sc.note, state, sc);
      if (denied) { results[i] = { ok: false, status: 'denied', message: denied }; continue; }
      const fn = tools[c.function.name];
      results[i] = fn ? fn(args) : { ok: false, status: 'rejected', message: '没有这个工具' };
      const reached = skillFor(c.function.name, args);
      if (reached && !guided.has(reached.name)) { guided.add(reached.name); results[i]['应用经验'] = reached.text;
        console.log(`  R${round} (应用经验 ${reached.name} attached to ${c.function.name})`); }
    }
    if (ending?.name === 'finish' && ending.args.status === 'done' && !closesOnEvidence(calls, argsOf, results)) {
      results[calls.findIndex((c) => c.function.name === 'finish')] = { ok: false, status: 'deferred',
        message: '这次 finish 没有生效：同一轮的操作没有成功，或者它的结果还要你看。看完上面的结果再决定怎么收尾' };
      console.log(`  R${round} (finish set aside: the round's results still need a look)`);
      ending = null;
    }
    calls.forEach((c, i) => { if (results[i] && results[i].ok === false && !META.has(c.function.name)) state.failures = (state.failures ?? 0) + 1; });
    calls.forEach((c, i) => messages.push({ role: 'tool', tool_call_id: c.id, content: JSON.stringify(results[i]) }));
    if (ending) { if (ending.name === 'finish') state.final = String(ending.args.reply ?? ''); break; }  // both end the task
    // as AgentLoop: after round 1 a round of memory tools alone is not the answer (what was found has not been said)
    const memoryOnly = round > 1 && calls.every((c) => MEMORY.has(c.function.name));
    const answer = state.planned ? null : (memoryOnly || /为什么|为啥|怎么回事|怎么又|怎么老|怎么总|咋回事|咋又/.test(sc.say) ? null
      : directAnswer(calls, argsOf, results))
      ?? (calls.length === 1 && (['web_search', 'x_search', ...DATA_TOOLS].includes(calls[0].function.name) || results[0]?.final === true) && (THEN_ACT.test(sc.say) || THEN_ACT_EPISODE.test(sc.say)) ? null
        : searchAnswer(calls, argsOf, results, sc.say));
    if (answer) { state.final = answer; console.log(`  R${round} answered from the tools: ${answer}`); break; }
  }
  console.log(`  total model time ${total} ms`);
  if (sc.expect) {
    const searches = state.searches ?? [];
    const problems = [];
    if (sc.expect.search && searches.length !== 1) problems.push(`expected one web_search, got ${searches.length}`);
    if (sc.expect.search === false && searches.length) problems.push(`searched for stable knowledge: ${searches.join(' / ')}`);
    if (sc.expect.queryHas && !searches.some((q) => String(q).includes(sc.expect.queryHas))) problems.push(`no query names ${sc.expect.queryHas}: ${searches.join(' / ')}`);
    if (sc.expect.notEnds && sc.expect.notEnds.test(String(state.final ?? '').trim().slice(-14))) problems.push(`ends with an offer or a question: ${state.final}`);
    for (const word of sc.expect.query ?? []) if (!searches.some((q) => word.split('|').some((w) => q.includes(w)))) problems.push(`query lacks ${word}`);
    const clicks = state.clicks ?? [];
    if (sc.expect.click && clicks[0] !== sc.expect.click) problems.push(`first click ${clicks[0] ?? 'none'}, expected ${sc.expect.click} (all: ${clicks.join(',')})`);
    if (sc.expect.play && !String(state.playing?.title ?? '').includes(sc.expect.play)) problems.push(`playing ${state.playing?.title ?? 'nothing'}, expected ${sc.expect.play}`);
    for (const tool of sc.expect.avoid ?? []) if ((state.called ?? []).includes(tool)) problems.push(`repeated XiaoAi's action with ${tool}`);
    for (const tool of sc.expect.called ?? []) if (!(state.called ?? []).includes(tool)) problems.push(`never called ${tool}`);
    if (sc.expect.opensEpisode != null && !(state.videoOpens ?? []).some((v) => v.episode === sc.expect.opensEpisode)) problems.push(`tencent_video_open episode was not ${sc.expect.opensEpisode}: ${JSON.stringify(state.videoOpens ?? [])}`);
    if (sc.expect.opensPlay != null && !(state.videoOpens ?? []).some((v) => v.play === sc.expect.opensPlay)) problems.push(`tencent_video_open play was not ${sc.expect.opensPlay}: ${JSON.stringify(state.videoOpens ?? [])}`);
    // says: one string, or several ways of saying it (7006652 / 7,006,652 / 一千零三十八)
    const says = sc.expect.says == null ? [] : [].concat(sc.expect.says);
    // a name in Latin letters is the same name however it is cased (MINIMAX / MiniMax)
    if (says.length && !says.some((w) => String(state.final ?? '').toLowerCase().includes(String(w).toLowerCase()))) problems.push(`answer lacks ${says.join(' / ')}: ${state.final}`);
    for (const w of sc.expect.notSays ?? []) if (String(state.final ?? '').includes(w)) problems.push(`answer says ${w}: ${state.final}`);
    if (sc.expect.keyword && !(state.keywords ?? []).some((k) => k.includes(sc.expect.keyword))) problems.push(`no search for ${sc.expect.keyword} (${(state.keywords ?? []).join(',')})`);
    if (sc.expect.awake && !state.screenOn) problems.push('the screen stayed off');
    if (sc.expect.link && !(state.links ?? []).some((l) => String(l).includes(sc.expect.link))) problems.push(`no link to ${sc.expect.link}: ${(state.links ?? []).join(',')}`);
    for (const step of sc.expect.installs ?? []) if (!(state.installs ?? []).includes(step)) problems.push(`install_app never called with ${step}: ${(state.installs ?? []).join(',')}`);
    for (const step of sc.expect.notInstalls ?? []) if ((state.installs ?? []).includes(step)) problems.push(`install_app called with ${step}`);
    if (sc.expect.channel && !(state.keywords ?? []).some((k) => k.includes(sc.expect.channel))
      && !(state.links ?? []).includes(`bilibili://space/${BILI_MIDS[sc.expect.channel]}`)) problems.push(`neither the page nor a search of ${sc.expect.channel}: ${(state.links ?? []).join(',')}`);
    const sched = (state.schedules ?? [])[0];
    if (sc.expect.schedule) for (const [k, v] of Object.entries(sc.expect.schedule))
      if (!sched || !String(sched[k] ?? '').includes(v)) problems.push(`schedule ${k} ${sched ? JSON.stringify(sched[k]) : 'none'}, expected ${v}`);
    if (sc.expect.note && !(state.notes ?? []).some((n) => n.includes(sc.expect.note))) problems.push(`no note with ${sc.expect.note}: ${(state.notes ?? []).join(',')}`);
    if (sc.expect.artist && !String(state.playing?.artist ?? '').includes(sc.expect.artist)) problems.push(`played ${state.playing?.artist ?? 'nothing'}, expected ${sc.expect.artist}`);
    if (sc.expect.run && !state.playing?.run) problems.push(`expected a run of the singer's songs (netease_play with the artist only), got ${JSON.stringify(state.playing ?? null)}`);
    if ('karaoke' in sc.expect && state.karaoke !== sc.expect.karaoke) problems.push(`随心唱 is ${state.karaoke ? 'on' : 'off'}, expected ${sc.expect.karaoke ? 'on' : 'off'}`);
    if (sc.expect.noUp && (state.keys ?? []).includes('up')) problems.push('pressed up (the remote tutorial)');
    if (sc.expect.calledAny && !sc.expect.calledAny.some((tool) => (state.called ?? []).includes(tool))) problems.push(`called none of ${sc.expect.calledAny.join(' / ')}`);
    if (sc.expect.exited && !state.exited) problems.push('never left the app (media_control exit)');
    if (sc.expect.maxRounds && state.rounds > sc.expect.maxRounds) problems.push(`${state.rounds} model rounds, at most ${sc.expect.maxRounds} wanted`);
    // the model may still reach for set_hints: what counts is that the engine's gate left the setting alone
    if (sc.expect.hintsKept && state.hintsSwitched) problems.push('the hint card was switched although nobody asked for it');
    if (sc.expect.notRun && state.playing?.run) problems.push(`played a run of ${state.playing.artist} instead of the song`);
    for (const w of sc.expect.guessed ?? []) if ((state.songArtists ?? []).some((a) => a.includes(w))) problems.push(`netease_play with a singer the user never said: ${(state.songArtists ?? []).join(',')}`);
    console.log(problems.length ? `  EXPECT FAIL: ${problems.join('; ')}` : '  EXPECT OK');
  }
}
const pick = process.argv.slice(2);
for (const n of (pick.length ? pick : Object.keys(scenarios))) {
  try { await run(n); } catch (e) { console.log(`  ERROR ${e.message}`); }
}
