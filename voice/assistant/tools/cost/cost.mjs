#!/usr/bin/env node
// tools/cost/cost.mjs — 每单的真实成本自己记账，不再靠人 口算。
//
// 一轮跑完，runner 会在 log.jsonl 的末尾留一个 `result` 事件：total_cost_usd、usage、modelUsage、duration_ms、
// num_turns 都在里面。这个文件把那些数读成「一轮花了多少」，把一单的花销汇成一行，再把今天的账汇成一个数。
//
// 一件要紧的事：**同一个会话里的 result 事件是累计的**。verify-durable 的五个事件读 $4.82 / $6.15 / $6.95 /
// $9.07 / $9.94，这一单花的是 $9.94，不是 $36.93。所以一轮的账是相邻两个事件之差（roundDeltas），
// 第一个事件是它自己的全部。
//
// 纯的部分不碰磁盘：roundCost / roundDeltas / summary / line / dearestLines / repair。
// 自己也能跑：
//   node tools/cost/cost.mjs [--jobs <dir>] [--out <path>]
// 读得到 state.json 就用 state.json 里的 history[].cost，没有就从那一单的 log.jsonl 里补（retroactive repair）。
// 除了 --out 指到的那一个文件，别的什么都不写。

import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** 「最费的几单」最多列 5 单：再多就不是一眼能看完的账了。 */
export const DEAREST = 5;

/** 板子上写的是 $9.94，不是 $9.935437：记到分。 */
export const usd2 = (v) => (typeof v === 'number' && isFinite(v) ? Math.round(v * 100) / 100 : null);

const num = (v) => (typeof v === 'number' && isFinite(v) ? v : 0);

/** runner 没报成本就是没测到：null，不是 0。0 是「测到了，是零」。 */
const dollars = (v) => (typeof v === 'number' && isFinite(v) ? v : null);

/** 累计的字段：一次会话里每个 result 事件报的都是「到这个事件为止」的数。 */
const CUMULATIVE = ['inTok', 'cacheTok', 'outTok', 'thinkTok'];

/** 一个 result 事件 → 它自己报的账。不是 result 事件给 null。缺字段、非数字的字段算 0，usd 例外（null）。 */
export function roundCost(ev) {
  if (!ev || typeof ev !== 'object' || ev.type !== 'result') return null;
  const usage = ev.usage && typeof ev.usage === 'object' ? ev.usage : {};
  const det = usage.output_tokens_details && typeof usage.output_tokens_details === 'object'
    ? usage.output_tokens_details : {};
  const mu = ev.modelUsage && typeof ev.modelUsage === 'object' ? ev.modelUsage : {};
  const names = Object.keys(mu);
  const models = {};
  let inTok = 0, cacheTok = 0, outTok = 0, thinkTok = 0;
  for (const k of names) {
    const m = mu[k] && typeof mu[k] === 'object' ? mu[k] : {};
    const o = num(m.outputTokens);
    models[k] = { usd: dollars(m.costUSD), outTok: o };
    inTok += num(m.inputTokens);
    cacheTok += num(m.cacheReadInputTokens);
    outTok += o;
    thinkTok += num(m.thinkingTokens);
  }
  if (!names.length) {
    // 老日志没有 modelUsage：退到 usage。usage 里的令牌数是这一轮自己的，不是累计的，
    // 所以这种事件的 delta 由 dropped() 的「低了就算整条」兜住，不会算出负数。
    inTok = num(usage.input_tokens);
    cacheTok = num(usage.cache_read_input_tokens);
    outTok = num(usage.output_tokens);
    thinkTok = num(det.thinking_tokens);
  }
  return {
    usd: dollars(ev.total_cost_usd),
    ms: num(ev.duration_ms),
    turns: num(ev.num_turns),
    inTok, cacheTok, outTok, thinkTok,
    models,
  };
}

/** 会话换了（换模型就换会话）或者累计数往下掉了：这一条是整条事件自己的账，不是差。 */
function dropped(prev, cur) {
  if (cur.usd != null && prev.usd != null && cur.usd < prev.usd) return true;
  for (const k of CUMULATIVE) if (cur[k] < prev[k]) return true;
  for (const m of Object.keys(cur.models)) {
    const p = prev.models[m];
    if (!p) continue;
    const c = cur.models[m];
    if (c.usd != null && p.usd != null && c.usd < p.usd) return true;
    if (c.outTok < p.outTok) return true;
  }
  return false;
}

/** cur 减去 prev：令牌和美元是累计的所以取差，ms 和 num_turns 本来就是这一轮自己的，照抄。 */
function minus(prev, cur) {
  const models = {};
  for (const m of Object.keys(cur.models)) {
    const c = cur.models[m], p = prev.models[m];
    models[m] = p
      ? { usd: c.usd == null || p.usd == null ? null : c.usd - p.usd, outTok: c.outTok - p.outTok }
      : { usd: c.usd, outTok: c.outTok };
  }
  return {
    usd: cur.usd == null || prev.usd == null ? null : cur.usd - prev.usd,
    ms: cur.ms,
    turns: cur.turns,
    inTok: cur.inTok - prev.inTok,
    cacheTok: cur.cacheTok - prev.cacheTok,
    outTok: cur.outTok - prev.outTok,
    thinkTok: cur.thinkTok - prev.thinkTok,
    models,
  };
}

/** 两条账加起来（重试轮多出来的零头算在最后一轮头上，用的就是它）。 */
function add(a, b) {
  const models = {};
  for (const m of Object.keys(a.models || {})) models[m] = { usd: a.models[m].usd, outTok: a.models[m].outTok };
  for (const m of Object.keys(b.models || {})) {
    const p = models[m], c = b.models[m];
    models[m] = p
      ? { usd: p.usd == null || c.usd == null ? null : p.usd + c.usd, outTok: p.outTok + c.outTok }
      : { usd: c.usd, outTok: c.outTok };
  }
  return {
    usd: a.usd == null || b.usd == null ? null : a.usd + b.usd,
    ms: num(a.ms) + num(b.ms),
    turns: num(a.turns) + num(b.turns),
    inTok: num(a.inTok) + num(b.inTok),
    cacheTok: num(a.cacheTok) + num(b.cacheTok),
    outTok: num(a.outTok) + num(b.outTok),
    thinkTok: num(a.thinkTok) + num(b.thinkTok),
    models,
  };
}

/** 一串 result 事件 → 一轮一条的账。第一条是它自己的全部，之后每条是它和前一条之差；永远不会是负的。
 * 不是 result 的事件跳过（它不算一轮）。 */
export function roundDeltas(events) {
  const list = Array.isArray(events) ? events : [];
  const out = [];
  let prev = null, prevSid = null;
  for (const ev of list) {
    const cur = roundCost(ev);
    if (!cur) continue;
    const sid = ev && typeof ev.session_id === 'string' ? ev.session_id : null;
    const restart = prev !== null && ((sid !== null && prevSid !== null && sid !== prevSid) || dropped(prev, cur));
    out.push(prev === null || restart ? cur : minus(prev, cur));
    prev = cur;
    prevSid = sid;
  }
  return out;
}

/** 一串 result 事件里**最后一个**事件的账，也就是刚跑完这一轮花掉的。run.mjs 往 history 里写的就是它。 */
export function delta(events) {
  const d = roundDeltas(events);
  return d.length ? d[d.length - 1] : null;
}

/** 一单：history 里有 cost 的按 cost 算，一单都没 cost 就是没测到（unknown），不是 0。 */
function jobUsd(history) {
  let usd = null;
  for (const r of history) {
    const c = r && r.cost;
    if (!c || typeof c.usd !== 'number' || !isFinite(c.usd)) continue;
    usd = (usd == null ? 0 : usd) + c.usd;
  }
  return usd;
}

/** 今天的账，as data。`jobs` 是 `[{ id, history }]`，history 的每一项是 run.mjs 写的那一条外加 `cost`。 */
export function summary(jobs) {
  const list = Array.isArray(jobs) ? jobs : [];
  let rounds = 0, tools = 0, usd = 0, outTok = 0;
  const msu = {}, unknown = [], dear = [];
  for (const j of list) {
    const h = j && Array.isArray(j.history) ? j.history : [];
    const id = String(j && j.id != null ? j.id : '?');
    rounds += h.length;
    for (const r of h) {
      tools += num(r && r.tools);
      const c = r && r.cost;
      if (!c) continue;
      outTok += num(c.outTok);
      const ms = c.models && typeof c.models === 'object' ? c.models : {};
      for (const m of Object.keys(ms)) {
        const u = ms[m] && ms[m].usd;
        if (typeof u === 'number' && isFinite(u)) msu[m] = (msu[m] == null ? 0 : msu[m]) + u;
      }
    }
    const own = jobUsd(h);
    if (own == null) { unknown.push(id); continue; }
    usd += own;
    dear.push({ id, usd: usd2(own) });
  }
  // 同样贵的两单按 id 排，板子才不会每次刷新顺序都变
  dear.sort((a, b) => (b.usd - a.usd) || (a.id < b.id ? -1 : (a.id > b.id ? 1 : 0)));
  const byModel = {};
  for (const m of Object.keys(msu).sort()) byModel[m] = usd2(msu[m]);
  return {
    jobs: list.length, rounds, tools,
    usd: usd2(usd), outTok,
    msu: byModel,
    dearest: dear.slice(0, DEAREST),
    unknown,
  };
}

/** 「今天：67 单、74 轮、$260.78」；有单子没测到成本就加一句「合计是下限」——没测到的不当成 0。 */
export function line(jobs) {
  const s = summary(jobs);
  let out = '今天：' + s.jobs + ' 单、' + s.rounds + ' 轮、$' + (s.usd == null ? '0.00' : s.usd.toFixed(2));
  if (s.unknown.length) out += '（' + s.unknown.length + ' 单没有成本，合计是下限）';
  return out;
}

/**
 * The board's 「消耗」 line: 「- 花在模型上：$249.10（72 单；3 单没有成本，合计是下限）」, or 「算不出来」 when nothing was measured.
 * A null total is never printed as $0.00 — that says 「measured, and it was zero」, which is a different claim.
 */
export function spend(jobs) {
  const s = summary(jobs);
  if (s.jobs === 0) return '- 花在模型上：没有单跑过。';
  if (s.usd == null || s.jobs - s.unknown.length === 0) {
    return '- 花在模型上：算不出来（' + s.jobs + ' 单都没有记成本）';
  }
  let out = '- 花在模型上：$' + s.usd.toFixed(2) + '（' + s.jobs + ' 单';
  if (s.unknown.length) out += '；' + s.unknown.length + ' 单没有成本，合计是下限';
  return out + '）';
}

/** 最费的 n 单，一单一行 `- <id> $9.94 (5 轮)`。n 超过 5 按 5 算；没成本的单排不进来。 */
export function dearestLines(jobs, n) {
  const list = Array.isArray(jobs) ? jobs : [];
  const rows = [];
  for (const j of list) {
    const h = j && Array.isArray(j.history) ? j.history : [];
    const own = jobUsd(h);
    if (own == null) continue;
    rows.push({ id: String(j && j.id != null ? j.id : '?'), usd: usd2(own), rounds: h.length });
  }
  rows.sort((a, b) => (b.usd - a.usd) || (a.id < b.id ? -1 : (a.id > b.id ? 1 : 0)));
  const take = Math.min(typeof n === 'number' && isFinite(n) ? Math.max(0, Math.floor(n)) : DEAREST, DEAREST);
  let out = '';
  for (const r of rows.slice(0, take)) out += '- ' + r.id + ' $' + r.usd.toFixed(2) + ' (' + r.rounds + ' 轮)\n';
  return out;
}

/** 补账：state.json 里已经有 cost 的轮次照用，缺的按 log.jsonl 里 result 事件的先后一一对上。
 * 重试轮也会往 log.jsonl 里写 result 事件、但不往 history 里 push，多出来的零头算在最后一轮头上——钱不丢。 */
export function repair(state, events) {
  const st = state && typeof state === 'object' ? state : {};
  const h = (Array.isArray(st.history) ? st.history : [])
    .map((r) => (r && typeof r === 'object' ? Object.assign({}, r) : {}));
  if (h.length && h.every((r) => r.cost !== undefined)) return h;
  const d = roundDeltas(events);
  for (let i = 0; i < h.length; i++) if (h[i].cost === undefined && d[i]) h[i].cost = d[i];
  let extra = null;
  for (let i = h.length; i < d.length; i++) extra = extra ? add(extra, d[i]) : d[i];
  if (extra) {
    if (!h.length) h.push({ round: 0, tools: 0, cost: extra });
    else h[h.length - 1].cost = h[h.length - 1].cost ? add(h[h.length - 1].cost, extra) : extra;
  }
  return h;
}
/** log.jsonl 的原文 → 里面的 result 事件（按先后）。行残缺就跳过，不猜。 */
export function resultEvents(text) {
  const out = [];
  for (const line of String(text == null ? '' : text).split('\n')) {
    // 先按字面找一遍，省得给几万行日志都做一次 JSON.parse；带空格的写法（"type": "result"）也认
    if (!/"type"\s*:\s*"result"/.test(line)) continue;
    let ev;
    try { ev = JSON.parse(line); } catch (e) { continue; }
    if (ev && ev.type === 'result') out.push(ev);
  }
  return out;
}

// ---- 自己跑：读盘、打表 ----

const ROOT = resolve(import.meta.dirname, '..', '..');

function fail(msg) {
  process.stderr.write('cost: ' + msg + '\n');
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

/** 单子在哪：--jobs，其次 $SKYRUSH_OUT/jobs，再按主仓库旁边和 worktree 里的位置各试一次。 */
function jobsDir(explicit) {
  const cands = [];
  if (explicit) cands.push(win(explicit));
  if (process.env.SKYRUSH_OUT) cands.push(join(win(process.env.SKYRUSH_OUT), 'jobs'));
  cands.push(join(ROOT, '..', 'assistant-out', 'jobs'));
  cands.push(join(ROOT, '..', '..', 'assistant-out', 'jobs'));
  for (const c of cands) if (hasStates(c)) return c;
  for (const c of cands) if (c && existsSync(c)) return c;
  return cands.length ? cands[0] : join(ROOT, '..', 'assistant-out', 'jobs');
}

/** 一单一单读：state.json 的 history（缺 cost 的从 log.jsonl 补）。读不动的说一声再跳过，不装作没跑过。 */
function readJobs(dir) {
  let names;
  try { names = readdirSync(dir); } catch (e) { fail('读不到单子目录 ' + dir + '：' + e.message); }
  const out = [];
  for (const id of names.sort()) {
    const sp = join(dir, id, 'state.json');
    if (!existsSync(sp)) continue;
    let st;
    try { st = JSON.parse(readFileSync(sp, 'utf8')); } catch (e) {
      process.stderr.write('cost: ' + sp + ' 读不动，跳过：' + e.message + '\n');
      continue;
    }
    const lp = join(dir, id, 'log.jsonl');
    let events = [];
    if (existsSync(lp)) {
      try { events = resultEvents(readFileSync(lp, 'utf8')); } catch (e) {
        process.stderr.write('cost: ' + lp + ' 读不动，这一单按没测到成本算：' + e.message + '\n');
      }
    }
    out.push({ id: String(st.id == null ? id : st.id), history: repair(st, events) });
  }
  return out;
}

/** 一张表 + 一行合计 + 最费的几单。没测到成本的单写 `$?`，不写 $0.00。 */
export function report(jobs) {
  const list = Array.isArray(jobs) ? jobs : [];
  const s = summary(list);
  let out = '# 消耗\n\n| 单 | 轮 | 工具 | 成本 |\n| --- | --- | --- | --- |\n';
  for (const j of list) {
    const h = Array.isArray(j.history) ? j.history : [];
    let tools = 0;
    for (const r of h) tools += num(r && r.tools);
    const own = jobUsd(h);
    out += '| ' + String(j.id) + ' | ' + h.length + ' | ' + tools + ' | '
      + (own == null ? '$?' : '$' + usd2(own).toFixed(2)) + ' |\n';
  }
  out += '\n' + line(list) + '\n';
  out += '工具调用 ' + s.tools + ' 次，输出令牌 ' + s.outTok + '\n';
  const d = dearestLines(list, DEAREST);
  if (d) out += '\n最费的几单：\n' + d;
  if (s.unknown.length) out += '\n没有成本的单：' + s.unknown.join('、') + '\n';
  return out;
}

function main() {
  const a = parseArgs(process.argv.slice(2));
  const dir = jobsDir(a.jobs);
  const text = report(readJobs(dir));
  process.stdout.write(text);
  if (a.out) {
    const out = win(a.out);
    try {
      mkdirSync(dirname(out), { recursive: true });
      writeFileSync(out, text, 'utf8');
    } catch (e) { fail('写不了 ' + out + '：' + e.message); }
  }
}

const MAIN = typeof import.meta.main === 'boolean' ? import.meta.main
  : (process.argv[1] ? resolve(win(process.argv[1])) === fileURLToPath(import.meta.url) : false);
if (MAIN) main();
