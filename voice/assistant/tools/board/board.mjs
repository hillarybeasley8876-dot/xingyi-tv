#!/usr/bin/env node
// tools/board/board.mjs — 一页状态板：在跑什么、卡在哪、消耗多少、怎么停。
//
// board(states, opts) 是纯的：给它单子的状态（state.json 的内容，外加调用的人补上的 merged / updatedMs），
// 它给回一页 markdown，不碰磁盘也不问 git。同一套规则的 Java 版是
// src/xyz/erapi0neer/xingyi/agent/Board.java，tests/agent/BoardChecks.java 拿两边的字面量对着查，
// 免得两边各排各的、排出两页不一样的板子。
//
// 自己也能跑：
//   node tools/board/board.mjs --out <path> [--since <hours>] [--jobs <dir>] [--repo <dir>]
// 读 $SKYRUSH_OUT/jobs（没有就按主仓库 / worktree 两种位置找），写 --out（默认 status/BOARD.md），
// 打印写到的路径。谁已经合进主仓库，用 `git merge-base --is-ancestor <commit> HEAD`（--repo，默认主 worktree）问 git。

import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
// 「花在模型上」那一行的算法只有一份，在 tools/cost/cost.mjs：板子不自己算钱
import { DEAREST, dearestLines, repair, resultEvents, spend } from '../cost/cost.mjs';

/** 在跑但 90 分钟没写过 state.json 的，算卡住：一轮预算是 100 分钟，中间也会写状态。 */
export const STALE_MS = 90 * 60 * 1000;
/** 同时在跑最多 2 单（run.mjs 的 --parallel）。 */
export const MAX_RUNNING = 2;
/** --since 的默认小时数：只看今天。 */
export const DEFAULT_HOURS = 24;
/** state.json 里没写 maxRounds 时的兜底（run.mjs 的默认值）。 */
export const DEFAULT_MAX_ROUNDS = 4;

export const RUNNING = 'running';
export const ACCEPTED = 'accepted_mechanical';
/** 除了 needs_lead / failed / outage，failed_round 和 wrong_model 也是没人管就不会再动的单，
 * 板上不留它们就等于把它们弄丢了。 */
export const STUCK = ['needs_lead', 'failed', 'failed_round', 'outage', 'wrong_model'];

export const HEAD_RUN = '| 单 | 第几轮 | 这个会话已用工具次数 | 开始 | 最后一句（截 80 字） |';
export const HEAD_WAIT = '| 单 | 完成于 | 提交 | 验收 |';
export const HEAD_DONE = '| 单 | 提交 | 轮数 | 工具次数 |';
export const S_RUN = '## 在跑';
export const S_WAIT = '## 等合并（过了验收，我还没合）';
export const S_DONE = '## 今天完成并已合并';
export const S_STUCK = '## 卡住 / 需要我出手';
export const S_COST = '## 消耗';
export const NONE = '（没有）';
export const EMPTY_BOARD = '没有在跑的、没有等合并的。';

const two = (n) => (n < 10 ? '0' + n : String(n));

/** HH:mm（本机时间）。 */
export const clock = (ms) => {
  const d = new Date(ms);
  return two(d.getHours()) + ':' + two(d.getMinutes());
};

/** yyyy-MM-dd HH:mm（本机时间）。 */
export const stamp = (ms) => {
  const d = new Date(ms);
  return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate())
    + ' ' + two(d.getHours()) + ':' + two(d.getMinutes());
};

/** 进表格的一句话：换行压成一个空格，竖线转义（不然表格就断了），超过 max 个字就截掉。 */
export function cell(s, max) {
  let t = s == null ? '' : String(s).replace(/\s+/g, ' ').trim().replace(/\|/g, '\\|');
  if (!t) return '—';
  if (t.length > max) t = t.slice(0, max - 1) + '…';
  return t;
}

const num = (v) => (typeof v === 'number' && isFinite(v) ? v : 0);
const ms = (v) => (typeof v === 'number' && isFinite(v) ? v : (typeof v === 'string' ? (Date.parse(v) || 0) : 0));

/** 「验收」和「为什么」两栏要说的话：调用的人自己写的 note 最优先，其次是渠道断掉时 runner 记的 error，
 * 再没有就看最后一轮的验收——过了几条、哪条没过、为什么。 */
function noteOf(s) {
  if (s.note != null) return String(s.note);
  if (s.error != null && String(s.error) !== '') return String(s.error);
  const h = Array.isArray(s.history) ? s.history : [];
  const last = h.length ? h[h.length - 1] : null;
  const acc = last && Array.isArray(last.acc) ? last.acc : [];
  if (!acc.length) return '';
  const bad = acc.filter((a) => a && !a.pass);
  if (!bad.length) return acc.length + '/' + acc.length + ' 过：' + acc.map((a) => String(a.name)).join('、');
  const why = bad.map((a) => String(a.name)
    + (Array.isArray(a.why) && a.why.length ? '（' + a.why.join('；') + '）' : '')).join('；');
  return bad.length + '/' + acc.length + ' 没过：' + why;
}

/** 一条 state.json → 板上的一单。history[].tools 加起来是这一单用掉的工具次数；
 * history 本身原样带着，「花在模型上」那一行钱从每一轮的 cost 加出来。 */
export function normalize(state) {
  const s = state || {};
  const h = Array.isArray(s.history) ? s.history : [];
  let tools = 0;
  for (const r of h) tools += num(r && r.tools);
  return {
    id: String(s.id == null ? '?' : s.id),
    status: String(s.status == null ? 'new' : s.status),
    rounds: num(s.rounds),
    maxRounds: num(s.maxRounds),
    tools,
    history: h,
    startedMs: ms(s.startedMs != null ? s.startedMs : s.startedAt),
    updatedMs: ms(s.updatedMs != null ? s.updatedMs : (s.updatedAt != null ? s.updatedAt : s.startedAt)),
    commit: s.commit ? String(s.commit).slice(0, 7) : '',
    merged: !!s.merged,
    last: String(s.lastText == null ? (s.last == null ? '' : s.last) : s.lastText),
    note: noteOf(s),
  };
}

const commitOf = (j) => (j.commit ? j.commit : '—');
const runRow = (j) => [j.id, j.rounds + '/' + (j.maxRounds > 0 ? j.maxRounds : DEFAULT_MAX_ROUNDS),
  String(j.tools), clock(j.startedMs), cell(j.last, 80)];
const waitRow = (j) => [j.id, clock(j.updatedMs), commitOf(j), cell(j.note, 120)];
const doneRow = (j) => [j.id, commitOf(j), String(j.rounds), String(j.tools)];

function separator(head) {
  const bars = (head.match(/\|/g) || []).length;
  let s = '|';
  for (let i = 0; i < bars - 1; i++) s += ' --- |';
  return s;
}

function table(head, rows) {
  if (!rows.length) return NONE + '\n';
  let s = head + '\n' + separator(head) + '\n';
  for (const r of rows) s += '|' + r.map((c) => ' ' + c + ' |').join('') + '\n';
  return s;
}

/** 这一单为什么卡住；不卡的单给 null。 */
function whyOf(j, now) {
  if (j.status === RUNNING) {
    if (now - j.updatedMs <= STALE_MS) return null;
    return '超过 90 分钟没更新（最后 ' + clock(j.updatedMs) + '）';
  }
  if (STUCK.indexOf(j.status) < 0) return null;
  const c = cell(j.note, 120);
  return c === '—' ? 'state.json 里没写为什么' : c;
}

function stuckLines(jobs, now) {
  let s = '';
  let n = 0;
  for (const j of jobs) {
    const why = whyOf(j, now);
    if (!why) continue;
    s += '- ' + j.id + '  ' + j.status + '  ' + why + '\n';
    n++;
  }
  return n ? s : NONE + '\n';
}

/** 今天的账：单数、轮数、工具次数，最费的那一单；再往下是同一个窗口花掉的钱和最费的几单。
 * 工具次数是成本的替身，钱才是成本——两行都留着，替身那一行一个字不改。 */
function costLines(jobs, cutoff, running) {
  let n = 0, rounds = 0, tools = 0, most = null;
  const window = [];
  for (const j of jobs) {
    if (j.updatedMs < cutoff) continue;
    n++;
    rounds += j.rounds;
    tools += j.tools;
    if (!most || j.tools > most.tools) most = j;
    window.push({ id: j.id, history: j.history });
  }
  let s = n === 0
    ? '- 今天：没有单跑过。\n'
    : '- 今天：' + n + ' 个单、' + rounds + ' 轮、' + tools
      + ' 次工具调用（按每轮 `history[].tools` 汇总）；最费的一次：`' + most.id + '`（' + most.tools + ' 次）\n';
  // 钱是同一批单子的钱（同一个 --since 窗口），不是全天的账
  s += spend(window) + '\n';
  const dearest = dearestLines(window, DEAREST);
  if (dearest) {
    s += '- 最费的几单：\n';
    for (const l of dearest.split('\n')) if (l) s += '  ' + l + '\n';
  }
  s += '- 上限：同时在跑最多 ' + MAX_RUNNING + ' 单';
  if (running.length > MAX_RUNNING) {
    s += '，现在 ' + running.length + ' 单在跑，超了：' + running.map((j) => j.id).join('、');
  }
  return s + '\n';
}

/** 一页板子。hours 是 --since：只管「等合并」和「今天完成并已合并」两张表。 */
export function board(states, opts) {
  const o = opts || {};
  const now = typeof o.now === 'number' && isFinite(o.now) ? o.now : Date.now();
  const hours = typeof o.hours === 'number' && isFinite(o.hours) ? o.hours : DEFAULT_HOURS;
  const jobs = (Array.isArray(states) ? states : []).map(normalize);
  if (!jobs.length) return '# 状态板 ' + stamp(now) + '\n\n' + EMPTY_BOARD + '\n';
  const cutoff = now - hours * 3600 * 1000;
  // 新的在上；同一毫秒的单按 id 排，板上才不会每次刷新顺序都变
  jobs.sort((a, b) => (a.updatedMs !== b.updatedMs ? (a.updatedMs > b.updatedMs ? -1 : 1)
    : (a.id < b.id ? -1 : (a.id > b.id ? 1 : 0))));
  const running = jobs.filter((j) => j.status === RUNNING);
  const waiting = jobs.filter((j) => j.status === ACCEPTED && !j.merged && j.updatedMs >= cutoff);
  const done = jobs.filter((j) => j.status === ACCEPTED && j.merged && j.updatedMs >= cutoff);
  let out = '# 状态板 ' + stamp(now) + '\n';
  out += '\n' + S_RUN + '\n\n' + table(HEAD_RUN, running.map(runRow));
  out += '\n' + S_WAIT + '\n\n' + table(HEAD_WAIT, waiting.map(waitRow));
  out += '\n' + S_DONE + '\n\n' + table(HEAD_DONE, done.map(doneRow));
  out += '\n' + S_STUCK + '\n\n' + stuckLines(jobs, now);
  out += '\n' + S_COST + '\n\n' + costLines(jobs, cutoff, running);
  return out;
}

// ---- 自己跑：读盘、问 git、写文件 ----

const ROOT = resolve(import.meta.dirname, '..', '..');

function fail(msg) {
  process.stderr.write('board: ' + msg + '\n');
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

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const m = /^--([a-z-]+)(?:=([\s\S]*))?$/.exec(argv[i]);
    if (!m) continue;
    if (m[2] !== undefined) out[m[1]] = m[2];
    else if (i + 1 < argv.length && !/^--/.test(argv[i + 1])) out[m[1]] = argv[++i];
    else out[m[1]] = '';
  }
  return out;
}

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

function readStates(dir) {
  let names;
  try { names = readdirSync(dir); } catch (e) { fail('读不到单子目录 ' + dir + '：' + e.message); }
  const out = [];
  for (const id of names) {
    const p = join(dir, id, 'state.json');
    if (!existsSync(p)) continue;
    try {
      const st = JSON.parse(readFileSync(p, 'utf8'));
      if (!st.id) st.id = id;
      // 「完成于」和「卡住」都看 state.json 最后一次变的时刻
      st.updatedMs = statSync(p).mtimeMs;
      fillCost(st, join(dir, id, 'log.jsonl'));
      out.push(st);
    } catch (e) {
      process.stderr.write('board: ' + p + ' 读不动，跳过：' + e.message + '\n');
    }
  }
  return out;
}

/** 每一轮都没 cost 的单子（run.mjs 还没往 history 里写 cost 时跑的那些）从 log.jsonl 里补账：
 * 「今晚花了多少」不该因为 state.json 是旧格式就答不上来。没有 log.jsonl 就照实算没测到，不编。 */
function fillCost(st, logPath) {
  const h = Array.isArray(st.history) ? st.history : [];
  for (const r of h) if (r && r.cost !== undefined) return;
  if (!existsSync(logPath)) return;
  let events;
  try {
    events = resultEvents(readFileSync(logPath, 'utf8'));
  } catch (e) {
    process.stderr.write('board: ' + logPath + ' 读不动，这一单按没测到成本算：' + e.message + '\n');
    return;
  }
  st.history = repair(st, events);
}

function mergedSet(repo, commits) {
  const merged = new Set();
  let warned = false;
  const git = (...args) => spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8' });
  for (const c of new Set(commits)) {
    const r = git('merge-base', '--is-ancestor', c, 'HEAD');
    if (r.error) {
      if (!warned) {
        process.stderr.write('board: 问不了 git（' + r.error.message + '），所有单都算还没合\n');
        warned = true;
      }
      break;
    }
    if (r.status === 0) { merged.add(c); continue; }
    // A job's branch tip can sit one commit past what was merged, that commit touching only its own status file
    // (reported-2, 2026-11-12: 5832a9a over 106df02). All of its code is in HEAD; listing it as 等合并 is a lie.
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

/** 一单最多跑几轮不在 state.json 里，在 spec 里（briefs/jobs/<id>.json）；读不到就用 run.mjs 的默认值。 */
function fillMaxRounds(states, root) {
  for (const st of states) {
    if (num(st.maxRounds) > 0) continue;
    const p = join(root, 'briefs', 'jobs', st.id + '.json');
    if (!existsSync(p)) continue;
    try {
      const spec = JSON.parse(readFileSync(p, 'utf8'));
      if (num(spec.maxRounds) > 0) st.maxRounds = spec.maxRounds;
    } catch (e) {
      process.stderr.write('board: ' + p + ' 读不动，轮数上限按 ' + DEFAULT_MAX_ROUNDS + ' 算：' + e.message + '\n');
    }
  }
}

function main() {
  const a = parseArgs(process.argv.slice(2));
  const hours = a.since === undefined || a.since === '' ? DEFAULT_HOURS : Number(a.since);
  if (!isFinite(hours) || hours < 0) fail('--since 要的是小时数，给的是：' + a.since);
  const repo = repoDir(a.repo, ROOT);
  const dir = jobsDir(a.jobs, ROOT, repo);
  const states = readStates(dir);
  fillMaxRounds(states, ROOT);
  const commits = states.filter((s) => s.commit).map((s) => String(s.commit));
  const merged = mergedSet(repo, commits);
  for (const s of states) if (s.commit) s.merged = merged.has(String(s.commit));
  const out = a.out ? win(a.out) : join(ROOT, 'status', 'BOARD.md');
  try {
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, board(states, { hours }), 'utf8');
  } catch (e) {
    fail('写不了 ' + out + '：' + e.message);
  }
  console.log(out);
}

const MAIN = typeof import.meta.main === 'boolean' ? import.meta.main
  : (process.argv[1] ? resolve(win(process.argv[1])) === fileURLToPath(import.meta.url) : false);
if (MAIN) main();

