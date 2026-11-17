#!/usr/bin/env node
// tools/resume/rehearse.mjs — 把 tools/m31w/run.mjs 的续跑规则，用一个假工人真跑一遍。
//
// run.mjs 自己说它会接着跑被杀掉的单（`if (st.rounds > st.history.length) st.rounds = st.history.length;`、
// `st.sessionStarted ? '--resume' : '--session-id'`），可到现在没有任何一条检查证明过这件事：2026-11-11 晚上
// board-money 六轮里四轮被一个验收路径的笔误杀掉，单子停在 needs_lead 都没人发现。这个文件不改 run.mjs（它是
// lead 的），只是**开一个假单去驱动它**：一份假 state.json、一份假 brief、一个假的 claude（tools/resume/stub.cs
// 编出来的 exe），然后看它第二次起来的时候从第几轮开始、带的是 --resume 还是 --session-id、第一句话说的是什么。
//
// 为什么 SKYRUSH_OUT / SKYRUSH_WT / SKYRUSH_ENV 一定要指到 scratch 里：真的 voice/assistant-out/jobs 里放的是
// **别的会话正在跑的单**（state.json、prompt-N.md、log.jsonl 都是活的）。一次演练要是写进去，就会把别人的单掐掉
// 或者把它的轮数改掉——这是这个工具唯一能真正伤到别人的地方。所以这里的 job id 一律带 rehearse- 前缀，用的
// worktree 也建在 scratch 里，演练完（除非 --keep）连目录带 worktree 注册和 job/rehearse-* 分支一起删掉。
//
//   node tools/resume/rehearse.mjs [--out <dir>] [--kill-after <sec>] [--resume] [--report] [--json] [--keep]
//   node tools/resume/rehearse.mjs --build-stub-only        只编假 claude，看看这台机器编不编得出来
//
// --kill-after N：等假工人落下第一笔日志（这一轮真的开始了）之后再等 N 秒，把 run.mjs 连它的子进程一起
// taskkill /T /F —— 杀在轮中间，不是杀在启动中间。--resume：拿同一份 state 再起一次 run.mjs。
// 不带 --json 就把人看得懂的那份打出来；带 --json 只打 JSON（tests/agent/resume/check.py 读的就是它）。
import { spawn, spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const RUN_MJS = join(ROOT, 'tools', 'm31w', 'run.mjs');
/**
 * 真的那一份 jobs 目录：别的会话正在跑的单都在里面，演练一样东西都不许落在它里面（见文件头）。
 * ROOT 未必是主检出（一个 job 的 worktree 里 ROOT 是 assistant-wt/<id>），所以问 git 要主检出的位置：主检出是
 * <voice>/assistant，真的 out 是它的兄弟 <voice>/assistant-out —— run.mjs 自己也是从主检出这么算的。
 */
const MAIN = (() => {
  const r = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: ROOT, encoding: 'utf8' });
  const common = (r.stdout ?? '').trim();
  return r.status === 0 && common ? dirname(resolve(common)) : resolve(join(ROOT, '..', '..'));
})();
const REAL_OUT = resolve(join(MAIN, '..', 'assistant-out'));
const SCRATCH_BASE = join(REAL_OUT, 'tmp', 'resume');
/** Windows 自带的 C# 编译器：假 claude 只能是真 exe，理由见 tools/resume/stub.cs 开头。 */
const CSC = ['C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe',
  'C:/Windows/Microsoft.NET/Framework/v4.0.30319/csc.exe'];
/** run.mjs 的三句话，报告和检查都按字面认它们（preamble.md 里没有这三句，已查）。 */
const CONTINUATION = 'The lead resumes this job.';
const BRIEF_MARK = '# YOUR BRIEF';
const FRESH_MARK = '# CONTINUATION: a fresh session';
const FEEDBACK_MARK = '# Feedback from the lead';
const MARK_FILE = 'rehearse-round.txt';
const OLD_MODEL = 'MiniMax-M3.1-Flash';

const argv = process.argv.slice(2);
const flag = (name, dflt) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : dflt; };
const has = (name) => argv.includes(name);
const posix = (p) => p.replace(/\\/g, '/');
const iso = () => new Date().toISOString();
const stamp = () => new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
const readJson = (p) => { try { return JSON.parse(readFileSync(p, 'utf8')); } catch { return null; } };
const writeJson = (p, o) => writeFileSync(p, JSON.stringify(o, null, 1));
const ensure = (p) => { mkdirSync(p, { recursive: true }); return p; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** The scratch layout. Everything the rehearsal touches lives under `scratch`, and `out` is what run.mjs is told. */
function scratchDir() {
  const dir = resolve(flag('--out', join(SCRATCH_BASE, `rehearse-${stamp()}`)));
  const rel = relative(REAL_OUT, dir);
  if (dir === REAL_OUT || (!rel.startsWith('..') && rel.split(/[\\/]/)[0] === 'jobs')) {
    throw new Error(`--out ${dir} would write into the real jobs directory; the rehearsal only writes into ${SCRATCH_BASE}`);
  }
  return dir;
}

function makeScratch() {
  const dir = scratchDir();
  const sc = {
    dir, id: `rehearse-${stamp()}`,
    out: ensure(join(dir, 'out')),
    wt: ensure(join(dir, 'wt')),
    env: ensure(join(dir, 'env')),
    stubDir: ensure(join(dir, 'stub')),
    logs: ensure(join(dir, 'logs')),
  };
  sc.stub = join(sc.stubDir, 'claude-stub.exe');
  sc.stubLog = join(sc.dir, 'stub.log');
  sc.accept = join(sc.dir, 'accept-round.mjs');
  writeFileSync(sc.accept, ACCEPT_SRC);
  return sc;
}

/** 验收命令只做一件事：假工人在这一轮真的在 worktree 里落了标记没有。 */
const ACCEPT_SRC = `import { existsSync, readFileSync } from 'node:fs';
if (!existsSync('${MARK_FILE}')) { console.log('no ${MARK_FILE} in ' + process.cwd()); process.exit(1); }
console.log('rehearse round ok: ' + readFileSync('${MARK_FILE}', 'utf8').trim());
`;

/** The fake claude. Compiled once per rehearsal into the scratch dir; the .exe itself is never committed. */
function buildStub(sc) {
  const cs = join(HERE, 'stub.cs');
  const csc = CSC.find((p) => existsSync(p));
  if (!csc) throw new Error(`no C# compiler (looked for ${CSC.join(', ')}): the fake claude has to be a real .exe, see tools/resume/stub.cs`);
  const r = spawnSync(csc, ['/nologo', `/out:${sc.stub}`, cs], { encoding: 'utf8' });
  if (r.status !== 0 || !existsSync(sc.stub)) throw new Error(`csc failed (${r.status}): ${(r.stdout || '') + (r.stderr || '')}`);
  return sc.stub;
}

if (has('--build-stub-only')) {
  const sc = makeScratch();
  buildStub(sc);
  console.log(`stub built: ${posix(sc.stub)}`);
  process.exit(0);
}

// ---- 假工人留下的账：一次 BEGIN…END 就是它被叫起来一次 ---------------------------------------------------

function stubRecords(logPath) {
  const out = [];
  if (!existsSync(logPath)) return out;
  for (const line of readFileSync(logPath, 'utf8').split('\n')) {
    const begin = /^BEGIN (\d+)$/.exec(line);
    if (begin) { out.push({ n: +begin[1], argv: '', stdin: '', mark: '', end: false }); continue; }
    if (!out.length) continue;
    const cur = out[out.length - 1];
    const kv = /^(ARGV|STDIN|MARK) (\S*)$/.exec(line);
    // argv and stdin are base64 in the log: no console code page can corrupt a Chinese prompt on the way here
    if (kv) cur[kv[1].toLowerCase()] = Buffer.from(kv[2], 'base64').toString('utf8');
    const end = /^END (\d+)$/.exec(line);
    if (end && +end[1] === cur.n) cur.end = true;
  }
  return out.map((r) => ({ ...r, argv: r.argv ? r.argv.split(' ') : [] }));
}

/** The session id the runner passed, whichever flag it used. */
function sessionIdOf(args) {
  for (const f of ['--resume', '--session-id']) {
    const i = args.indexOf(f);
    if (i >= 0 && i + 1 < args.length) return args[i + 1];
  }
  return null;
}

// ---- 一个假单：state.json、brief、spec，都写在 scratch 里 ------------------------------------------------

const BRIEF_SRC = `# Brief: rehearsal

This job does not exist. It is a rehearsal: tools/resume/rehearse.mjs drives the real runner with it, so that the
continuation rules (which round a restarted job begins with, --resume or --session-id, which prompt) are proved
against the runner itself and not against a comment about it.
FIRST-PROMPT-MARKER
`;

function makeJob(sc, name, opts) {
  const id = `${sc.id}-${name}`;
  const dir = ensure(join(sc.out, 'jobs', id));
  const briefPath = join(ensure(join(sc.dir, 'briefs', 'jobs')), `${id}.json`);
  writeFileSync(briefPath, opts.brief ?? BRIEF_SRC);
  // run.mjs reads the brief as join(ROOT, job.brief): a path under the repo root, so the scratch one is relative
  const briefRel = posix(relative(ROOT, briefPath));
  if (resolve(ROOT, briefRel) !== resolve(briefPath)) {
    throw new Error(`the brief at ${briefPath} is not reachable from the repo root (${briefRel}); use --out on the same drive`);
  }
  const spec = {
    id, title: `rehearsal ${name}`, model: opts.model, brief: briefRel,
    owns: [MARK_FILE], maxRounds: opts.maxRounds, maxTurns: 4, timeoutMin: 5,
    isolation: 'worktree', sparse: [`/${MARK_FILE}`],
    accept: [{ name: 'round', cmd: `"${posix(process.execPath)}" "${posix(sc.accept)}"`, must: ['rehearse round ok'] }],
  };
  const specPath = join(sc.dir, `spec-${name}.json`);
  writeJson(specPath, spec);
  writeJson(join(dir, 'state.json'), opts.state);
  return { id, name, dir, specPath, spec, briefRel, model: opts.model, maxRounds: opts.maxRounds, state0: opts.state };
}

function baseState(id, extra) {
  return { id, title: `rehearsal ${id}`, status: 'new', rounds: 0, sessionId: randomUUID(), sessionStarted: false,
    history: [], ...extra };
}

function roundEntry(round) {
  return { round, end: 'ok', tools: 0, models: {}, wrongModel: false, stray: [], acc: [{ name: 'round', pass: true, why: [] }] };
}

// ---- 一次 run.mjs：起来之前、起来之后，各拍一张快照 --------------------------------------------------------

function snapshot(dir) {
  const prompts = {};
  for (const f of readdirSync(dir)) {
    const m = /^prompt-(\d+)\.md$/.exec(f);
    if (m) { const s = statSync(join(dir, f)); prompts[+m[1]] = `${s.mtimeMs}:${s.size}`; }
  }
  return { state: readJson(join(dir, 'state.json')), prompts };
}

function readPrompts(dir) {
  const out = {};
  for (const f of readdirSync(dir)) {
    const m = /^prompt-(\d+)\.md$/.exec(f);
    if (m) out[+m[1]] = readFileSync(join(dir, f), 'utf8');
  }
  return out;
}

function stateOf(st) {
  if (!st) return null;
  return { rounds: st.rounds ?? 0, history: Array.isArray(st.history) ? st.history.length : 0,
    sessionStarted: !!st.sessionStarted, sessionModel: st.sessionModel ?? null, sessionId: st.sessionId ?? null,
    status: st.status ?? null };
}

function killTree(pid) {
  spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' });
}

async function waitFor(fn, timeoutMs) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) { if (fn()) return true; await sleep(100); }
  return false;
}

/**
 * One run.mjs launch on one fake job. With `killAfterSec` the runner is killed mid-round: the wait starts when the
 * fake worker has written its first record, i.e. when the round really began, so a slow `git worktree add` cannot
 * turn the kill into a kill before the round.
 */
async function launch(sc, job, n, opts = {}) {
  const before = snapshot(job.dir);
  const runsBefore = stubRecords(sc.stubLog).length;
  const logPath = join(sc.logs, `${job.name}-${n}.out`);
  const env = { ...process.env, SKYRUSH_OUT: sc.out, SKYRUSH_WT: sc.wt, SKYRUSH_ENV: sc.env, SKYRUSH_CLAUDE: sc.stub,
    REHEARSE_STUB_LOG: sc.stubLog, REHEARSE_STUB_SLEEP: String(opts.stubSleep ?? 0), REHEARSE_STUB_MARK: MARK_FILE };
  const t0 = Date.now();
  const proc = spawn(process.execPath, [RUN_MJS, 'run', job.specPath],
    { cwd: ROOT, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  proc.stdout.on('data', (c) => { out += c; });
  proc.stderr.on('data', (c) => { out += c; });
  proc.on('error', (e) => { out += `[rehearse] spawn error: ${e.message}\n`; });
  const closed = new Promise((res) => proc.on('close', (code) => res(code)));
  let killed = false, roundStarted = null, killedAtMs = null;
  if (opts.killAfterSec != null) {
    roundStarted = await waitFor(() => stubRecords(sc.stubLog).length > runsBefore, 120000);
    if (roundStarted) await sleep(opts.killAfterSec * 1000);
    killedAtMs = Date.now() - t0;
    killTree(proc.pid);
    killed = true;
  }
  const code = await closed;
  writeFileSync(logPath, out);
  const after = snapshot(job.dir);
  const prompts = readPrompts(job.dir);
  // a prompt file this launch wrote (new, or rewritten with another prompt) is a round it started
  const rounds = Object.keys(after.prompts).map(Number)
    .filter((k) => before.prompts[k] !== after.prompts[k]).sort((a, b) => a - b);
  // the history entries this launch added: one per round that got as far as the runner's own acceptance. A killed
  // round has none — that is the whole point of the kill scenario, so it is written down rather than assumed.
  const histBefore = Array.isArray(before.state?.history) ? before.state.history.length : 0;
  const hist = Array.isArray(after.state?.history) ? after.state.history.slice(histBefore) : [];
  const accept = hist.map((h) => ({ round: h.round, end: h.end ?? null,
    pass: (h.acc ?? []).every((a) => a.pass === true) && (h.stray ?? []).length === 0,
    why: (h.acc ?? []).filter((a) => a.pass !== true).map((a) => `${a.name}: ${(a.why ?? []).join('; ')}`),
    stray: h.stray ?? [] }));
  const stub = stubRecords(sc.stubLog).slice(runsBefore).map((r) => {
    const round = Object.keys(prompts).map(Number).find((k) => prompts[k] === r.stdin) ?? null;
    return { round, finished: r.end, resume: r.argv.includes('--resume'), sessionId: sessionIdOf(r.argv),
      argv: r.argv, prompt: r.stdin, promptFile: round == null ? null : `prompt-${round}.md`, mark: r.mark };
  });
  return { n, killed, roundStarted, killedAtMs, exitCode: code, ms: Date.now() - t0,
    stateBefore: stateOf(before.state), stateAfter: stateOf(after.state),
    beginRound: rounds.length ? rounds[0] : null, rounds, stub, accept, log: posix(relative(sc.dir, logPath)) };
}

// ---- 三个场景：杀掉再起来、换了模型、轮数到了顶 ----------------------------------------------------------
// 每一个都是 run.mjs 自己那句话的对面：它说它会这样，那它到底是不是这样。

const MODEL = flag('--model', 'MiniMax-M78');

async function scenarioKill(sc, killAfterSec, resume) {
  const job = makeJob(sc, 'kill', { model: MODEL, maxRounds: 3, state: baseState(`${sc.id}-kill`) });
  const launches = [await launch(sc, job, 1, killAfterSec == null ? {} : { killAfterSec, stubSleep: killAfterSec + 60 })];
  // the second launch is the whole point: same state.json, nothing else changed
  if (resume) launches.push(await launch(sc, job, 2, { stubSleep: 0 }));
  return finishJob(sc, job, 'kill', launches);
}

/** 记着的会话是别的模型开的：run.mjs 说它不 resume，换一个新 session-id 接着做。 */
async function scenarioModel(sc) {
  const id = `${sc.id}-model`;
  const job = makeJob(sc, 'model', { model: MODEL, maxRounds: 3, state: baseState(id,
    { rounds: 1, status: 'failed_round', sessionStarted: true, sessionModel: OLD_MODEL, history: [roundEntry(1)] }) });
  return finishJob(sc, job, 'model', [await launch(sc, job, 1, { stubSleep: 0 })]);
}

/** 轮数已经到顶：run.mjs 说它不会跑到封顶外面去，没过就落 needs_lead。 */
async function scenarioCap(sc) {
  const id = `${sc.id}-cap`;
  const job = makeJob(sc, 'cap', { model: MODEL, maxRounds: 2, state: baseState(id,
    { rounds: 2, status: 'failed_round', sessionStarted: true, sessionModel: MODEL,
      history: [roundEntry(1), roundEntry(2)] }) });
  return finishJob(sc, job, 'cap', [await launch(sc, job, 1, { stubSleep: 0 })]);
}

function finishJob(sc, job, scenario, launches) {
  const stub = { runs: 0, finished: 0 };
  for (const l of launches) for (const s of l.stub) { stub.runs++; if (s.finished) stub.finished++; }
  return { id: job.id, scenario, model: job.model, maxRounds: job.maxRounds, brief: job.briefRel,
    spec: posix(relative(sc.dir, job.specPath)), state0: stateOf(job.state0), launches,
    final: stateOf(readJson(join(job.dir, 'state.json'))), stub };
}

function buildReport(sc, jobs, t0) {
  return { tool: 'tools/resume/rehearse.mjs', at: iso(), ms: Date.now() - t0,
    runMjs: posix(relative(ROOT, RUN_MJS)), node: posix(process.execPath), model: MODEL,
    scratch: posix(sc.dir), out: posix(sc.out), wt: posix(sc.wt), env: posix(sc.env), claude: posix(sc.stub),
    realOut: posix(REAL_OUT), killAfterSec: flag('--kill-after', null) == null ? null : +flag('--kill-after'),
    resumed: has('--resume'), jobs };
}

// ---- 打出来给人看 --------------------------------------------------------------------------------------

/** Which of run.mjs's three sentences a prompt is. Derived from the text — the report carries the prompt, not this. */
function promptKind(p) {
  if (p.startsWith(CONTINUATION)) return 'continuation';
  if (p.startsWith(FEEDBACK_MARK)) return 'feedback';
  if (p.includes(FRESH_MARK)) return 'fresh-session';
  if (p.includes(BRIEF_MARK)) return 'first-prompt';
  return 'unknown';
}

const short = (id) => (id ? String(id).slice(0, 8) : '-');
const firstLine = (p) => (p.split('\n')[0] || '').slice(0, 110);

function printHuman(r) {
  const L = [`rehearsal of ${r.runMjs}: ${r.jobs.length} fake job(s), ${r.ms}ms`, `  scratch      ${r.scratch}`,
    `  SKYRUSH_OUT  ${r.out}`, `  SKYRUSH_WT   ${r.wt}`, `  SKYRUSH_ENV  ${r.env}`, `  fake claude  ${r.claude}`];
  for (const j of r.jobs) {
    L.push('', `job ${j.id}  (${j.scenario})  model=${j.model}  maxRounds=${j.maxRounds}  brief=${j.brief}`);
    L.push(`  fixture: rounds=${j.state0.rounds} history=${j.state0.history} sessionStarted=${j.state0.sessionStarted}` +
      ` sessionModel=${j.state0.sessionModel ?? '-'} session=${short(j.state0.sessionId)}`);
    for (const l of j.launches) {
      L.push(`  launch ${l.n}: ${l.killed ? `KILLED mid-round after ${l.killedAtMs}ms` : `exit ${l.exitCode}`}` +
        ` (${l.ms}ms, log ${l.log})`);
      L.push(`    state before: rounds=${l.stateBefore.rounds} history=${l.stateBefore.history}` +
        ` sessionStarted=${l.stateBefore.sessionStarted} sessionModel=${l.stateBefore.sessionModel ?? '-'}`);
      L.push(`    BEGAN AT ROUND ${l.beginRound}   (prompt files written: ${l.rounds.join(',') || 'none'})`);
      for (const s of l.stub) {
        L.push(`    fake worker, round ${s.round ?? '?'}: ${s.resume ? '--resume' : '--session-id'} ${short(s.sessionId)}` +
          ` finished=${s.finished} prompt=${s.promptFile ?? '(no prompt file matches it)'}`);
        L.push(`      prompt [${promptKind(s.prompt)}]: ${firstLine(s.prompt)}`);
      }
      L.push(`    state after:  rounds=${l.stateAfter.rounds} history=${l.stateAfter.history}` +
        ` status=${l.stateAfter.status} sessionStarted=${l.stateAfter.sessionStarted} sessionModel=${l.stateAfter.sessionModel ?? '-'}`);
      L.push(`    acceptance:    ${l.accept.length ? l.accept.map((a) => `round ${a.round} ${a.pass ? 'PASS' : 'FAIL' + (a.why.length ? ' (' + a.why.join('; ') + ')' : '')}`).join(', ') : 'none (the round never got that far)'}`);
    }
    L.push(`  final: rounds=${j.final.rounds} history=${j.final.history} status=${j.final.status}` +
      ` | fake worker ran ${j.stub.runs} time(s), finished ${j.stub.finished}`);
  }
  console.log(L.join('\n'));
}

function printShort(r) {
  for (const j of r.jobs) {
    const begins = j.launches.map((l) => `launch ${l.n} began at round ${l.beginRound}` +
      `${l.killed ? ' (killed)' : ''} -> ${l.stub.map((s) => (s.resume ? '--resume' : '--session-id')).join(',') || 'no run'}`).join('; ');
    console.log(`${j.scenario.padEnd(6)} ${j.id}: ${begins}; final rounds=${j.final.rounds} history=${j.final.history} ${j.final.status}`);
  }
}

// ---- 收场：worktree 注册、job/rehearse-* 分支、scratch 目录，除非 --keep ------------------------------------

/** 这个 job 的 worktree 或者分支还在不在（reset 之后问一次）。 */
function stillThere(sc, id) {
  const wl = spawnSync('git', ['worktree', 'list', '--porcelain'], { cwd: ROOT, encoding: 'utf8' }).stdout ?? '';
  const br = spawnSync('git', ['branch', '--list', `job/${id}`], { cwd: ROOT, encoding: 'utf8' }).stdout ?? '';
  return wl.includes(posix(join(sc.wt, id))) || br.trim() !== '';
}

function cleanup(sc, jobs, keep) {
  const notes = [];
  const env = { ...process.env, SKYRUSH_OUT: sc.out, SKYRUSH_WT: sc.wt, SKYRUSH_ENV: sc.env };
  for (const j of jobs) {
    const r = spawnSync(process.execPath, [RUN_MJS, 'reset', join(sc.dir, j.spec)], { cwd: ROOT, encoding: 'utf8', env });
    const said = `${r.stdout || ''}${r.stderr || ''}`.trim().split('\n').filter(Boolean).slice(-1)[0] ?? '';
    let note = `reset ${j.id}: ${said || `exit ${r.status}`}`;
    // run.mjs 的 reset 是唯一一份实现；它要是没办成（worktree 还没建就被杀了、spec 它不收），worktree 和分支也得走，
    // 不然下一次演练会撞在同一个名字上，而 job/rehearse-* 分支会一直挂在别人的仓库里
    if (stillThere(sc, j.id)) {
      spawnSync('git', ['worktree', 'remove', '--force', join(sc.wt, j.id)], { cwd: ROOT, stdio: 'ignore' });
      spawnSync('git', ['branch', '-D', `job/${j.id}`], { cwd: ROOT, stdio: 'ignore' });
      note += stillThere(sc, j.id) ? ' — STILL THERE, remove it by hand' : ' (+ worktree/branch removed by hand)';
    }
    notes.push(note);
  }
  spawnSync('git', ['worktree', 'prune'], { cwd: ROOT, stdio: 'ignore' });
  if (keep) notes.push(`--keep: left ${posix(sc.dir)} (the worktrees and job/rehearse-* branches are gone all the same)`);
  else { rmSync(sc.dir, { recursive: true, force: true }); notes.push(`removed ${posix(sc.dir)}`); }
  return notes;
}

async function main() {
  const t0 = Date.now();
  const sc = makeScratch();
  buildStub(sc);
  if (has('--dry-run')) {
    // nothing is launched: this only shows what a rehearsal would write, and where
    const job = makeJob(sc, 'kill', { model: MODEL, maxRounds: 3, state: baseState(`${sc.id}-kill`) });
    console.log(JSON.stringify({ scratch: posix(sc.dir), out: posix(sc.out), wt: posix(sc.wt), env: posix(sc.env),
      claude: posix(sc.stub), state: posix(join(job.dir, 'state.json')), brief: job.briefRel,
      spec: posix(job.specPath), specBody: readJson(job.specPath), stateBody: job.state0 }, null, 1));
    console.log(`\nreal jobs directory, untouched: ${posix(join(REAL_OUT, 'jobs'))}`);
    return;
  }
  const killAfter = flag('--kill-after', null);
  const jobs = [await scenarioKill(sc, killAfter == null ? null : +killAfter, has('--resume')),
    await scenarioModel(sc), await scenarioCap(sc)];
  const report = buildReport(sc, jobs, t0);
  writeJson(join(sc.dir, 'report.json'), report);
  if (has('--json')) console.log(JSON.stringify(report, null, 1));
  else if (has('--report')) printHuman(report);
  else printShort(report);
  const notes = cleanup(sc, jobs, has('--keep'));
  for (const n of notes) (has('--json') ? process.stderr : process.stdout).write(`  ${n}\n`);
}

main().catch((e) => { console.error('REHEARSAL FAILED', e.stack ?? e); process.exit(2); });
