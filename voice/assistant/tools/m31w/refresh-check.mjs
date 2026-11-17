#!/usr/bin/env node
// tools/m31w/refresh-check.mjs — catchUp（refresh.mjs）在一个临时仓库 + worktree 上真跑一遍。打印 REFRESH OK。
//
// 要证明的就是 run.mjs 靠它的那几件事：lead 在派单之后提交的文件，下一轮 worker 看得见；worker 自己没提交的活一个字
// 都不动；跟不上的时候（worker 改的文件主分支也改了、两边分叉）一个字都不动并且说出原因；以及最要紧的一条——快进之后
// lead 再改自己的文件，这一单合并回主分支照样干净（逐个 checkout lead 文件的做法在这里会冲突，所以没用它）。
// 不碰真仓库：全部在系统临时目录里，跑完删掉。
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { catchUp } from './refresh.mjs';

let failed = 0;
const check = (ok, label) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}`);
  if (!ok) failed++;
};
const git = (cwd, ...args) => {
  const r = spawnSync('git', args, { cwd, encoding: 'utf8' });
  return { ok: r.status === 0, out: (r.stdout ?? '').trim(), err: (r.stderr ?? '').trim() };
};
const put = (dir, rel, text) => {
  mkdirSync(dirname(join(dir, rel)), { recursive: true });
  writeFileSync(join(dir, rel), text);
};
const read = (dir, rel) => (existsSync(join(dir, rel)) ? readFileSync(join(dir, rel), 'utf8') : null);

const top = mkdtempSync(join(tmpdir(), 'refresh-check-'));
const repo = join(top, 'repo');
const wt = join(top, 'wt');
try {
  mkdirSync(repo);
  git(repo, 'init', '-q', '-b', 'master');
  git(repo, 'config', 'user.name', 'check');
  git(repo, 'config', 'user.email', 'check@local');
  git(repo, 'config', 'core.autocrlf', 'false');
  put(repo, 'HARNESS.md', 'one\n');
  put(repo, 'src/Owned.java', 'class Owned {}\n');
  put(repo, 'tests/agent/evidence/old.json', '{}\n');
  git(repo, 'add', '-A');
  git(repo, 'commit', '-qm', 'base');
  git(repo, 'worktree', 'add', '-q', '-B', 'job/t', wt, 'master');

  // ---- A: lead commits after the job was cut; the worker has work of its own, committed nowhere -------------------
  put(wt, 'src/Owned.java', 'class Owned { int worker; }\n');
  put(wt, 'status/improve/t.md', 'my notes\n');
  put(repo, 'tests/agent/evidence/real-x.json', '{"ok": true}\n');
  put(repo, 'tests/agent/fixtures/realpath/payload-shapes.json', '{"STATE": {}}\n');
  git(repo, 'add', '-A');
  git(repo, 'commit', '-qm', 'lead: evidence and shapes after the cut');
  const a = catchUp(wt, 'master');
  check(a.moved === true && a.why === '', `A 主分支往前走了，worker 的分支快进过去（moved=${a.moved} why=${a.why}）`);
  check(a.files.includes('tests/agent/evidence/real-x.json') && a.files.includes('tests/agent/fixtures/realpath/payload-shapes.json'),
    `A 报的文件就是 lead 那次提交的两个（${a.files.join(', ')}）`);
  check(read(wt, 'tests/agent/fixtures/realpath/payload-shapes.json') !== null, 'A lead 的形状录制现在在 worktree 里');
  check(read(wt, 'src/Owned.java') === 'class Owned { int worker; }\n', 'A worker 没提交的改动一个字没动');
  check(read(wt, 'status/improve/t.md') === 'my notes\n', 'A worker 没跟踪的文件还在');

  // ---- B: nothing new on master -> nothing happens, nothing to say ----------------------------------------------
  const b = catchUp(wt, 'master');
  check(b.moved === false && b.why === '' && b.files.length === 0, `B 再来一次：主分支没动，什么都不做也不报（moved=${b.moved} why=${b.why}）`);

  // ---- C: master changed the very file the worker is editing -> refuse, touch nothing ---------------------------
  const headC = git(wt, 'rev-parse', 'HEAD').out;
  put(repo, 'src/Owned.java', 'class Owned { int lead; }\n');
  git(repo, 'commit', '-qam', 'lead touches the owned file');
  const c = catchUp(wt, 'master');
  check(c.moved === false && c.why !== '', `C worker 正在改的文件主分支也改了：拒绝，并说出原因（${c.why.slice(0, 90)}）`);
  check(git(wt, 'rev-parse', 'HEAD').out === headC && read(wt, 'src/Owned.java') === 'class Owned { int worker; }\n',
    'C 拒绝的时候分支和 worker 的改动都原样');

  // ---- D: the job already has a commit of its own and master has not moved since -> nothing to catch up --------
  git(wt, 'checkout', '-q', '--', 'src/Owned.java');          // drop the clashing edit so the job can be committed
  const reset = catchUp(wt, 'master');                          // ...and catch up with C's commit first
  check(reset.moved === true, 'D（准备）去掉冲突的改动之后能跟上');
  put(wt, 'src/Owned.java', 'class Owned { int lead; int worker; }\n');
  git(wt, 'add', '-A');
  git(wt, 'commit', '-qm', 'job: the work');
  const d = catchUp(wt, 'master');
  check(d.moved === false && d.why === '', `D 这一单站在主分支前面：没有可跟的，也不报错（why=${d.why}）`);

  // ---- G: after catching up, the lead edits its own file again; the job still merges back cleanly ---------------
  put(repo, 'HARNESS.md', 'one\ntwo (the lead, after the job caught up)\n');
  git(repo, 'commit', '-qam', 'lead edits HARNESS again');
  const m = git(repo, 'merge', '--no-ff', '-q', '-m', 'Merge job/t', 'job/t');
  check(m.ok, `G 快进之后 lead 再改自己的文件，这一单合并回主分支照样干净（${m.ok ? 'clean' : m.err.slice(0, 120)}）`);
  check(read(repo, 'HARNESS.md') === 'one\ntwo (the lead, after the job caught up)\n'
      && read(repo, 'src/Owned.java') === 'class Owned { int lead; int worker; }\n',
    'G 合并之后两边的活都在：lead 的 HARNESS.md 和 worker 的 Owned.java');

  // ---- E: diverged (the job has a commit, master moved too) -> --ff-only refuses, nothing changes --------------
  git(wt, 'reset', '-q', '--hard', 'HEAD');
  put(wt, 'src/More.java', 'class More {}\n');
  git(wt, 'add', '-A');
  git(wt, 'commit', '-qm', 'job: more');
  const headE = git(wt, 'rev-parse', 'HEAD').out;
  put(repo, 'briefs/next.md', 'next\n');
  git(repo, 'add', '-A');
  git(repo, 'commit', '-qm', 'lead: a brief');
  const e = catchUp(wt, 'master');
  check(e.moved === false && e.why !== '' && git(wt, 'rev-parse', 'HEAD').out === headE,
    `E 两边分叉：--ff-only 拒绝，分支原样，原因交回去（${e.why.slice(0, 80)}）`);

  // ---- F: a base that does not exist -----------------------------------------------------------------------------
  const f = catchUp(wt, 'no-such-branch');
  check(f.moved === false && f.why.includes('no-such-branch'), `F 主分支名字不存在：不动，并点名（${f.why}）`);
  check(catchUp(wt, '').moved === false && catchUp('', 'master').moved === false, 'F 参数是空的：不动');
} finally {
  spawnSync('git', ['worktree', 'remove', '--force', wt], { cwd: repo });
  rmSync(top, { recursive: true, force: true });
}

if (failed) {
  console.log(`refresh: ${failed} 条没过`);
  process.exit(1);
}
console.log('REFRESH OK');
