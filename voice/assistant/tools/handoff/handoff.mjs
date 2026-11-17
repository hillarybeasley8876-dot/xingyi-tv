#!/usr/bin/env node
// tools/handoff/handoff.mjs — 一页「停在哪、下一步先做什么」：给凌晨三点接手的人看的那一页。
//
// handoff(states, opts) 是纯的：给它单子的状态和还没在真机上验过的记录，它给回一页 markdown，不碰磁盘也不问 git。
// 同一套规则的 Java 版是 src/xyz/erapi0neer/xingyi/agent/Handoff.java；tests/agent/HandoffChecks.java 拿同一批
// fixture 两边各跑一遍、比到字节一样——这一页是给人在最累的时候看的，两边排出两页不一样就等于没有这一页。
//
// 板子（tools/board/board.mjs）答的是「现在是什么情况」，这一页答另外三个问题：下一件先做什么、哪几件卡在人这里、
// 什么不许动。读的那一侧（normalize / cell / stamp / STUCK）和钱的算法（cost.mjs）都用它们自己的，这里不抄第二份。
//
// 自己也能跑：
//   node tools/handoff/handoff.mjs [--out <path>] [--jobs <dir>] [--unverified <path>] [--since <hours>] [--apk <path>]
//   [--built <path>]
// 读 $SKYRUSH_OUT/jobs（认 --jobs）和 tests/agent/unverified.json，写 --out（默认 status/HANDOFF.md），打印写到的路径。
// 检查用的不写盘模式（--print）：--states <file>（可给多次）[--unverified <file>] [--now <ms>] [--since-ms <ms>]
// [--apk-ms <ms>] [--built-ms <ms>]，页面打到 stdout。这一路不读默认的 unverified.json，免得仓库里那一份今天有明天没有。

import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { ACCEPTED, NONE, RUNNING, STUCK, cell, normalize, stamp } from '../board/board.mjs';
import { repair, resultEvents, spend } from '../cost/cost.mjs';

/** --since 的默认小时数：只看今天这一段。 */
export const DEFAULT_HOURS = 24;
/** 过了验收 24 小时还没合，算「我忘了」：跟 --since 无关——窗口调小也不该把忘掉的单藏起来。 */
export const FORGOT_MS = 24 * 3600 * 1000;
/** 默认的 unverified.json（相对仓库根）。 */
export const UNVERIFIED = 'tests/agent/unverified.json';
/** 下一步最多列三条：再多就不是「下一件先做什么」了。 */
export const MAX_NEXT = 3;

export const TITLE = '# 接手：此刻停在哪（生成于 ';
export const S_ONE = '## 一句话';
export const S_NEXT = '## 下一步（按顺序，最上面的先做）';
export const S_HUMAN = '## 卡在人这里的';
export const S_TV = '## 还没在真机上验过的';
export const S_HANDS_OFF = '## 不要动';
export const S_COST = '## 今天花了多少';

export const P_RUN = ' 单在跑';
export const P_HUMAN = ' 单卡在人这里';
export const P_TV = ' 条还没在真机上验过';
export const CLEAR = '没有在跑的、没有卡住人的、装上的东西都在真机上见过';
export const NO_UNVERIFIED = '一条都没有：装上的东西都在真机上见过';
export const NEXT_NONE = '没有必须现在做的：读 `HARNESS.md` 的最后一节';
export const MORE_HEAD = '（还有 ';
export const MORE_TAIL = ' 条没列出来：看 `status/BOARD.md`）';
export const LAST = '；最后一句：';
export const NO_NEEDS = '没有写';
export const NO_SCRIPT_LINE = '没有';
export const NO_SCRIPT_CMD = '没有脚本：照着 needs 手动走一遍';
export const BAD_ENTRY = '读不出来（不是一条记录）';
export const APK_NEWER = '比装的 APK 新';
export const APK_NONE = '手上还没有 APK';
export const INSTALL_CMD = '`cd ../xiaoai-bridge && sh build.sh && sh bridge.sh install`';
export const RED = '重跑验收 ';
export const TV_NEXT = '上电视验 ';
export const INSTALL = '装这一版 APK';
export const BUILT = '编好了还没装（';
export const ON_TV = '，电视上的是 ';
export const ON_TV_UNKNOWN = '，电视上的是哪版不知道';
export const HOURS = ' 小时还没合（我忘了）';
export const FORGOT = '过了验收 ';
/** 不要动：固定三行，写在这里才活得过上下文清空。 */
export const HANDS_OFF = [
  '- `tests/agent/AgentCoreTest.java` 里 `speechChecks()` 附近那段（打印它会把会话拦停）',
  '- `src/…/LocalAdb.java`、`assets/agent/contract.json`（lead 的）',
  '- 装应用、改系统设置：要用户点头',
];
/** 卡在人这里的五种为什么。名单是 board.mjs 的 STUCK（failed_round 也在里面：没人管它就不会再动）。 */
export const WHY = {
  needs_lead: '等我看一眼再决定',
  failed: '跑挂了，不会自己再动',
  failed_round: '一轮跑挂了，等我决定要不要再来一轮',
  outage: '渠道断了，等它回来',
  wrong_model: '用错了模型，得重跑',
};
export const WHY_OTHER = '这个状态没人管就不会再动';

const num = (v) => (typeof v === 'number' && isFinite(v) ? v : 0);
const str = (v) => (v == null ? '' : String(v));
const HOUR = 3600 * 1000;

/** 一单花掉的美元：history 里有 cost.usd 的轮次加起来；一轮都没有就是没测到（null），不是 0。
 * cost.mjs 的 jobUsd 没有导出，这里和它一个算法；spend() 拿 history 自己再算一遍，两边对不上就说明这里错了。
 * 不归到分：归到分是 cost.mjs 的 usd2 的事，早归一次就会和它差一分钱。 */
function usdOf(h) {
  let usd = null;
  for (const r of (Array.isArray(h) ? h : [])) {
    const c = r && r.cost;
    if (!c || typeof c.usd !== 'number' || !isFinite(c.usd)) continue;
    usd = (usd == null ? 0 : usd) + c.usd;
  }
  return usd;
}

/** 验收红在一条命令上：最后一轮里没过的那一条，命令从这一轮自己带的 cmd 或者这一单的 accept 清单
 * （briefs/jobs/<id>.json 的形状）里按名字找。找不到命令就不算「红在一条命令上」：没命令可重跑的事，
 * 这一页不假装能重跑。 */
function redOf(state) {
  const s = state || {};
  const h = Array.isArray(s.history) ? s.history : [];
  const last = h.length ? h[h.length - 1] : null;
  const acc = last && Array.isArray(last.acc) ? last.acc : (Array.isArray(s.acc) ? s.acc : []);
  const spec = Array.isArray(s.accept) ? s.accept : [];
  for (const a of acc) {
    if (!a || a.pass !== false) continue;
    const name = str(a.name);
    let cmd = str(a.cmd);
    if (!cmd) {
      for (const p of spec) if (p && str(p.name) === name && p.cmd) { cmd = str(p.cmd); break; }
    }
    if (cmd) return { name, cmd };
  }
  return { name: '', cmd: '' };
}

/** 一条 state.json → 这一页要的那几个数。读的那一侧是 board.mjs 的 normalize（id / status / rounds / tools /
 * updatedMs / merged / last / history 都在它里面），这里只补上钱和验收红没红。 */
export function jobOf(state) {
  const n = normalize(state);
  const red = redOf(state);
  return {
    id: n.id, status: n.status, rounds: n.rounds, tools: n.tools, updatedMs: n.updatedMs,
    merged: n.merged, last: n.last, history: n.history,
    costUsd: usdOf(n.history), redName: red.name, redCmd: red.cmd,
  };
}

/** 前提一条条写出来，、 分开（和 Unverified.needsOf 一个排法）。不是数组就是没写。 */
function needsOf(e) {
  const needs = e && Array.isArray(e.needs) ? e.needs : null;
  if (!needs) return '';
  let s = '';
  for (const it of needs) {
    if (it == null) continue;
    if (s) s += '、';
    s += String(it);
  }
  return s;
}

/** 一条 unverified.json 的记录（原文）→ 这一页要的四样东西。读不动的也占一行：读不出来比不说好。 */
export function entryOf(json, at) {
  let e = null;
  try { e = JSON.parse(String(json)); } catch (err) { e = null; }
  if (!e || typeof e !== 'object' || Array.isArray(e)) return { at, bad: true, id: '', feature: '', needs: '', script: '' };
  return { at, bad: false, id: str(e.id), feature: str(e.feature), needs: needsOf(e), script: str(e.script) };
}

const entriesOf = (list) => list.map((raw, i) => entryOf(raw, i + 1));

/** 一句话：一句就是一句——几段用 ； 接起来，末尾一个 。，中间一个 。 都没有。 */
function oneLine(running, human, tv) {
  const parts = [];
  if (running > 0) parts.push(running + P_RUN);
  if (human > 0) parts.push(human + P_HUMAN);
  if (tv > 0) parts.push(tv + P_TV);
  return (parts.length ? parts.join('；') : CLEAR) + '。';
}

const redItem = (j) => RED + j.id + ' · ' + j.redName + ' 没过，这一单已经跑了 ' + j.rounds + ' 轮、'
  + j.tools + ' 次工具 · `' + j.redCmd + '`';
const tvItem = (e) => TV_NEXT + e.id + ' · ' + e.feature + ' 还没在真机上见过 · '
  + (e.script ? '`' + e.script + '`' : NO_SCRIPT_CMD);
const installItem = (n, apkMs) => INSTALL + ' · ' + n + ' 单在这个窗口里合进来了，'
  + (apkMs > 0 ? APK_NEWER : APK_NONE) + ' · ' + INSTALL_CMD;
const builtItem = (builtMs, apkMs) => INSTALL + ' · ' + BUILT + clock(builtMs)
  + (apkMs > 0 ? ON_TV + clock(apkMs) : ON_TV_UNKNOWN) + '）· ' + INSTALL_CMD;

const pad2 = (n) => (n < 10 ? '0' : '') + n;
/** MM-dd HH:mm，按北京时间：不跟着跑这一页的那台机的时区走，Handoff.java 的 clock 同一个算法，两边才排得出同一页。 */
export function clock(ms) {
  const d = new Date(ms + 8 * HOUR);
  return pad2(d.getUTCMonth() + 1) + '-' + pad2(d.getUTCDate()) + ' ' + pad2(d.getUTCHours()) + ':'
    + pad2(d.getUTCMinutes());
}

/** 下一步：验收红 → 上电视验 → 装这一版，都在这一页的头里，按这个顺序，最多三条。
 * 装机提醒最多一条：有单子合进来时用那一条，没有才看编出来的是不是比装着的新。 */
function nextLines(red, tv, mergedNewer, apkMs, builtMs) {
  const items = [];
  for (const j of red) items.push(redItem(j));
  const first = tv.filter((e) => !e.bad && e.id)[0];
  if (first) items.push(tvItem(first));
  if (mergedNewer > 0) items.push(installItem(mergedNewer, apkMs));
  else if (builtMs > 0 && builtMs > apkMs) items.push(builtItem(builtMs, apkMs));
  if (!items.length) return NEXT_NONE + '\n';
  let s = '';
  for (let i = 0; i < Math.min(items.length, MAX_NEXT); i++) s += (i + 1) + '. ' + items[i] + '\n';
  if (items.length > MAX_NEXT) s += MORE_HEAD + (items.length - MAX_NEXT) + MORE_TAIL + '\n';
  return s;
}

/** 这一单为什么卡在人这里。accepted_mechanical 过了验收却没合：那就是我忘了。 */
function whyOf(j, now) {
  if (j.status === ACCEPTED) return FORGOT + Math.floor((now - j.updatedMs) / HOUR) + HOURS;
  return WHY[j.status] || WHY_OTHER;
}

function humanLines(human, now) {
  if (!human.length) return NONE + '\n';
  let s = '';
  for (const j of human) s += '- ' + j.id + '：' + whyOf(j, now) + LAST + cell(j.last, 100) + '\n';
  return s;
}

function tvLines(tv) {
  if (!tv.length) return NO_UNVERIFIED + '\n';
  let s = '';
  for (const e of tv) {
    s += e.bad
      ? '- 第 ' + e.at + ' 条：' + BAD_ENTRY + '\n'
      : '- ' + e.id + '：' + e.feature + '（要 ' + (e.needs || NO_NEEDS) + '；脚本 '
        + (e.script || NO_SCRIPT_LINE) + '）\n';
  }
  return s;
}

/** 卡在人这里的：board.mjs 的 STUCK 五个状态，加上过了验收 24 小时还没合的单（我忘了的）。 */
const isHuman = (j, now) => (STUCK.indexOf(j.status) >= 0)
  || (j.status === ACCEPTED && !j.merged && now - j.updatedMs > FORGOT_MS);

/** 今天的账：窗口里的单子交给 cost.mjs 的 spend（合计和「合计是下限」那句都是它的）。 */
const costJobs = (jobs, cutoff) => jobs.filter((j) => j.updatedMs >= cutoff).map((j) => ({ id: j.id, history: j.history }));

/** 新的在上；同一毫秒的单按 id 排，这一页才不会每次刷新顺序都变。 */
function sort(jobs) {
  jobs.sort((a, b) => (a.updatedMs !== b.updatedMs ? (a.updatedMs > b.updatedMs ? -1 : 1)
    : (a.id < b.id ? -1 : (a.id > b.id ? 1 : 0))));
  return jobs;
}

/**
 * 一页「停在哪、下一步先做什么」。六段，顺序固定，每段都在。
 *
 * @param states state.json 的内容（外加调用的人补上的 merged / updatedMs）
 * @param opts   now（毫秒）、since（窗口多长，毫秒）、apkMs（装在电视上的 APK 的时刻，0 = 手上没有 APK）、
 *               builtMs（磁盘上最新编出来的 APK 的时刻，0 = 不知道 / 没有）、
 *               unverified（unverified.json 里每条记录的**原文**，一条一个字符串）
 */
export function handoff(states, opts) {
  const o = opts || {};
  const now = typeof o.now === 'number' && isFinite(o.now) ? o.now : Date.now();
  const since = typeof o.since === 'number' && isFinite(o.since) ? o.since : DEFAULT_HOURS * HOUR;
  const apkMs = typeof o.apkMs === 'number' && isFinite(o.apkMs) ? o.apkMs : 0;
  const builtMs = typeof o.builtMs === 'number' && isFinite(o.builtMs) ? o.builtMs : 0;
  const jobs = sort((Array.isArray(states) ? states : []).map(jobOf));
  const tv = entriesOf(Array.isArray(o.unverified) ? o.unverified : []);
  const cutoff = now - since;
  const running = jobs.filter((j) => j.status === RUNNING);
  const human = jobs.filter((j) => isHuman(j, now));
  // 在跑的单不算「重跑验收」：它自己还会跑下一轮，这一页不催人去做 runner 正在做的事
  const red = jobs.filter((j) => j.redCmd && j.status !== RUNNING);
  const mergedNewer = jobs.filter((j) => j.merged && j.updatedMs >= cutoff && j.updatedMs > apkMs);

  let out = TITLE + stamp(now) + '）\n';
  out += '\n' + S_ONE + '\n\n' + oneLine(running.length, human.length, tv.length) + '\n';
  out += '\n' + S_NEXT + '\n\n' + nextLines(red, tv, mergedNewer.length, apkMs, builtMs);
  out += '\n' + S_HUMAN + '\n\n' + humanLines(human, now);
  out += '\n' + S_TV + '\n\n' + tvLines(tv);
  out += '\n' + S_HANDS_OFF + '\n\n' + HANDS_OFF.join('\n') + '\n';
  out += '\n' + S_COST + '\n\n' + spend(costJobs(jobs, cutoff)) + '\n';
  return out;
}

// ---- 自己跑：读盘、问 git、写文件 ----

const ROOT = resolve(import.meta.dirname, '..', '..');

function fail(msg) {
  process.stderr.write('handoff: ' + msg + '\n');
  process.exit(1);
}

/** 这台机的 Windows 环境里有 MSYS_NO_PATHCONV=1，Git Bash 传给 node 的 /h/... 是原样的，
 * 而 node 在 Windows 上把它当成当前盘下的 \h\...。这里翻回 H:/...（只在原样读不到时）。 */
function win(p) {
  if (process.platform !== 'win32' || p == null) return p;
  const s = String(p).replace(/\\/g, '/');
  const m = /^\/([A-Za-z])(\/.*|)$/.exec(s);
  if (!m) return s;
  const w = m[1].toUpperCase() + ':' + m[2];
  return existsSync(s) ? s : w;
}

/** 和 board.mjs 的 parseArgs 一个算法，只是同一个开关给多次时留成一串（--states 要能连着给几个 fixture）。 */
function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const m = /^--([a-z-]+)(?:=([\s\S]*))?$/.exec(argv[i]);
    if (!m) continue;
    const v = m[2] !== undefined ? m[2]
      : (i + 1 < argv.length && !/^--/.test(argv[i + 1]) ? argv[++i] : '');
    if (out[m[1]] === undefined) out[m[1]] = v;
    else if (Array.isArray(out[m[1]])) out[m[1]].push(v);
    else out[m[1]] = [out[m[1]], v];
  }
  return out;
}

const one = (v) => (Array.isArray(v) ? v[v.length - 1] : v);
const many = (v) => (v === undefined ? [] : (Array.isArray(v) ? v : [v]));

const hasStates = (d) => {
  try { return readdirSync(d).some((f) => existsSync(join(d, f, 'state.json'))); } catch (e) { return false; }
};

/** 单子在哪：--jobs，其次 $SKYRUSH_OUT/jobs，再按 run.mjs 的算法（主仓库旁边）和 worktree 的位置各试一次。 */
function jobsDir(explicit, root, repo) {
  const cands = [];
  if (explicit) cands.push(win(explicit));
  if (process.env.SKYRUSH_OUT) cands.push(join(win(process.env.SKYRUSH_OUT), 'jobs'));
  cands.push(join(root, '..', 'assistant-out', 'jobs'));
  cands.push(join(root, '..', '..', 'assistant-out', 'jobs'));
  if (repo) cands.push(join(repo, '..', 'assistant-out', 'jobs'));
  for (const c of cands) if (hasStates(c)) return c;
  for (const c of cands) if (c && existsSync(c)) return c;
  return cands.length ? cands[0] : join(root, '..', 'assistant-out', 'jobs');
}

/** 合没合看主仓库的 HEAD，不看某一单自己的分支：第一条 worktree 就是主仓库。 */
function repoDir(explicit, root) {
  if (explicit) return win(explicit);
  const r = spawnSync('git', ['-C', root, 'worktree', 'list', '--porcelain'], { encoding: 'utf8' });
  const m = /^worktree (.+)$/m.exec(r.stdout == null ? '' : r.stdout);
  return m ? m[1].trim() : root;
}

/** 一单一单读：state.json 的原文（外加 mtime 当 updatedMs），history 里缺 cost 的从 log.jsonl 补，
 * 验收的命令从 briefs/jobs/<id>.json 的 accept 清单补（state.json 里只留了 name / pass / why）。 */
function readStates(dir, root) {
  let names;
  try { names = readdirSync(dir); } catch (e) { fail('读不到单子目录 ' + dir + '：' + e.message); }
  const out = [];
  for (const id of names) {
    const p = join(dir, id, 'state.json');
    if (!existsSync(p)) continue;
    try {
      const st = JSON.parse(readFileSync(p, 'utf8'));
      if (!st.id) st.id = id;
      st.updatedMs = statSync(p).mtimeMs;
      fillCost(st, join(dir, id, 'log.jsonl'));
      fillAccept(st, root);
      out.push(st);
    } catch (e) {
      process.stderr.write('handoff: ' + p + ' 读不动，跳过：' + e.message + '\n');
    }
  }
  return out;
}

/** 每一轮都没 cost 的单子从 log.jsonl 里补账：这一页说「今天花了多少」，不该因为 state.json 是旧格式就答不上来。 */
function fillCost(st, logPath) {
  const h = Array.isArray(st.history) ? st.history : [];
  for (const r of h) if (r && r.cost !== undefined) return;
  if (!existsSync(logPath)) return;
  let events;
  try {
    events = resultEvents(readFileSync(logPath, 'utf8'));
  } catch (e) {
    process.stderr.write('handoff: ' + logPath + ' 读不动，这一单按没测到成本算：' + e.message + '\n');
    return;
  }
  st.history = repair(st, events);
}

/** 验收的命令不在 state.json 里，在 spec 里（briefs/jobs/<id>.json 的 accept[]）。读不到就不补：
 * 少了它，「重跑验收」那一条就不出现，不编一条命令出来。 */
function fillAccept(st, root) {
  if (Array.isArray(st.accept)) return;
  const p = join(root, 'briefs', 'jobs', st.id + '.json');
  if (!existsSync(p)) return;
  try {
    const spec = JSON.parse(readFileSync(p, 'utf8'));
    if (Array.isArray(spec.accept)) st.accept = spec.accept;
  } catch (e) {
    process.stderr.write('handoff: ' + p + ' 读不动，验收命令按没有算：' + e.message + '\n');
  }
}

/** 合没合：问 git merge-base --is-ancestor。规则和 board.mjs 的 mergedSet 一样（包括「分支尖上只多一个
 * status/improve/ 的提交就算合了」那一条），board.mjs 没把它导出，所以这里有一份。 */
function mergedSet(repo, commits) {
  const merged = new Set();
  let warned = false;
  const git = (...args) => spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8' });
  for (const c of new Set(commits)) {
    const r = git('merge-base', '--is-ancestor', c, 'HEAD');
    if (r.error) {
      if (!warned) {
        process.stderr.write('handoff: 问不了 git（' + r.error.message + '），所有单都算还没合\n');
        warned = true;
      }
      break;
    }
    if (r.status === 0) { merged.add(c); continue; }
    // A job's branch tip can sit one commit past what was merged, that commit touching only its own status file.
    // All of its code is in HEAD; saying 「装这一版」 for it is a lie.
    if (onlyStatus(git, c)) merged.add(c);
  }
  return merged;
}

/** True when every commit from HEAD to tip `c` is reachable except a tip that touches nothing but status/improve/. */
function onlyStatus(git, c) {
  const tip = git('rev-list', '-n', '1', c);
  if (tip.status !== 0) return false;
  const parent = git('rev-parse', c + '^');
  if (parent.status !== 0) return false; // a root commit: no parent to compare against
  const files = git('diff', '--name-only', parent.stdout.trim(), tip.stdout.trim());
  if (files.status !== 0) return false;
  const lines = files.stdout.split('\n').map((s) => s.trim()).filter(Boolean);
  if (!lines.length) return false;
  if (!lines.every((p) => p.startsWith('status/improve/'))) return false;
  const rest = git('merge-base', '--is-ancestor', parent.stdout.trim(), 'HEAD');
  return rest.status === 0;
}

/** unverified.json → 每条记录的原文（这一页要的是原文，Handoff.java 那边收的也是原文）。
 * 读不动就当没有：这一页的主干是单子，读不动一份旁证不该让整页排不出来。 */
function readUnverified(path) {
  if (!path || !existsSync(path)) return [];
  try {
    const j = JSON.parse(readFileSync(path, 'utf8'));
    const entries = j && Array.isArray(j.entries) ? j.entries : [];
    return entries.map((e) => JSON.stringify(e));
  } catch (e) {
    process.stderr.write('handoff: ' + path + ' 读不动，还没验的那一段按没有算：' + e.message + '\n');
    return [];
  }
}

/** 装在电视上的那一版 APK 是什么时候编的：--apk 指的那个文件，其次 $XINGYI_APK，再其次 bridge.sh install 记下的
 * installed-apk.json（桥的根目录下；装上去的那一版），都没有才退回 builtMsOf 的猜法：桥那边 build* 里最新的
 * xiaoai-bridge.apk。一个都没有就是 0（手上还没有 APK），不是「装过了」。 */
function apkMsOf(explicit, root) {
  const cands = [];
  if (explicit) cands.push(win(explicit));
  if (process.env.XINGYI_APK) cands.push(win(process.env.XINGYI_APK));
  if (cands.length === 0) {
    // what bridge.sh install wrote down (2026-11-16): the APK that is on the TV. Guessing from the newest APK on disk
    // took a build nobody installed for the installed one, and this page then said nothing was waiting on the user.
    for (let up = 1; up <= 3; up++) {
      // at the bridge's root, not under build/: build.sh deletes build/ first, and the record went with it (11-18)
      for (const marker of [join(root, ...Array(up).fill('..'), 'xiaoai-bridge', 'installed-apk.json'),
                            join(root, ...Array(up).fill('..'), 'xiaoai-bridge', 'build', 'installed-apk.json')]) {
        try {
          const m = JSON.parse(readFileSync(marker, 'utf8'));
          if (typeof m.built_ms === 'number' && isFinite(m.built_ms) && m.built_ms > 0) return m.built_ms;
        } catch (e) { /* no record here: try the next place, then fall back to the old guess */ }
      }
    }
  }
  // 指名了哪个文件就是它：再和最新编的取大，指的那一版就永远比不过磁盘上的，「编好了还没装」就永远出不来
  if (cands.length === 0) return builtMsOf(root);
  let best = 0;
  for (const c of cands) best = Math.max(best, mtimeMs(c));
  return best;
}

/** 磁盘上最新编出来的那一版 APK 是什么时候编的：--built 指的那个文件，没给就是桥那边 build* 里最新的
 * xiaoai-bridge.apk（桥在仓库旁边：主仓库是上一级，worktree 里要往上两三级）。一个都没有就是 0。
 * 它比 apkMsOf 新，就是一版编好了还没装。 */
function builtMsOf(root, explicit) {
  if (explicit) return mtimeMs(win(explicit));
  let best = 0;
  for (let up = 1; up <= 3; up++) {
    const bridge = join(root, ...Array(up).fill('..'), 'xiaoai-bridge');
    try {
      for (const d of readdirSync(bridge)) {
        if (!/^build/.test(d)) continue;
        const p = join(bridge, d, 'xiaoai-bridge.apk');
        if (existsSync(p)) best = Math.max(best, mtimeMs(p));
      }
    } catch (e) { /* 这一层没有桥：往上一层再找 */ }
  }
  return best;
}

/** 文件的 mtime，取整到毫秒：bridge.sh 记的 built_ms 是 int(mtime*1000)，不取整的话装上去的那一版
 * 会比它自己记下的时刻多出零点几毫秒，这一页就天天说「编好了还没装」。文件不在就是 0。 */
function mtimeMs(p) {
  try { return Math.floor(statSync(p).mtimeMs); } catch (e) { return 0; }
}

function main() {
  const a = parseArgs(process.argv.slice(2));
  const statesFiles = many(a.states);
  if (a.print !== undefined || statesFiles.length) {
    // 不写盘的一路：给检查用。unverified 默认是空的，免得仓库里那一份今天有明天没有。
    const states = [];
    for (const f of statesFiles) {
      const p = win(f);
      if (!existsSync(p)) fail('读不到 ' + p);
      const j = JSON.parse(readFileSync(p, 'utf8'));
      for (const s of (Array.isArray(j) ? j : (Array.isArray(j.states) ? j.states : []))) states.push(s);
    }
    const opts = {
      now: a.now === undefined ? Date.now() : Number(a.now),
      since: a['since-ms'] === undefined ? DEFAULT_HOURS * HOUR : Number(a['since-ms']),
      apkMs: a['apk-ms'] === undefined ? 0 : Number(a['apk-ms']),
      builtMs: a['built-ms'] === undefined ? 0 : Number(a['built-ms']),
      unverified: readUnverified(a.unverified === undefined ? null : win(one(a.unverified))),
    };
    process.stdout.write(handoff(states, opts));
    return;
  }
  const hours = a.since === undefined || one(a.since) === '' ? DEFAULT_HOURS : Number(one(a.since));
  if (!isFinite(hours) || hours < 0) fail('--since 要的是小时数，给的是：' + one(a.since));
  const repo = repoDir(a.repo, ROOT);
  const dir = jobsDir(one(a.jobs), ROOT, repo);
  const states = readStates(dir, ROOT);
  const merged = mergedSet(repo, states.filter((s) => s.commit).map((s) => String(s.commit)));
  for (const s of states) if (s.commit) s.merged = merged.has(String(s.commit));
  const unv = readUnverified(a.unverified === undefined ? join(ROOT, UNVERIFIED) : win(one(a.unverified)));
  const text = handoff(states, {
    now: Date.now(), since: hours * HOUR, apkMs: apkMsOf(one(a.apk), ROOT), builtMs: builtMsOf(ROOT, one(a.built)),
    unverified: unv,
  });
  const out = a.out ? win(one(a.out)) : join(ROOT, 'status', 'HANDOFF.md');
  try {
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, text, 'utf8');
  } catch (e) {
    fail('写不了 ' + out + '：' + e.message);
  }
  console.log(out);
}

const MAIN = typeof import.meta.main === 'boolean' ? import.meta.main
  : (process.argv[1] ? resolve(win(process.argv[1])) === fileURLToPath(import.meta.url) : false);
if (MAIN) main();
