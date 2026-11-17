#!/usr/bin/env node
// tools/m31w/refresh.mjs — 每一轮开始前，把 worker 的分支快进到 lead 的分支现在的位置。
//
// 2026-11-12 22:55 和 2026-11-13 02:00 各翻了一次同一件事：worker 的 worktree 是**派单那一刻**从主检出切出去的，之后
// lead 提交的证据文件（tests/agent/evidence/real-panel-self-check.json）和形状录制
// （tests/agent/fixtures/realpath/payload-shapes.json）它永远看不见，于是第 1 轮必然红，红的原因还不在它改的代码里。
//
// 为什么是快进（--ff-only），不是把 lead 的文件一个个 checkout 进来：checkout 进来的文件会跟着这一单的提交走，lead
// 之后再改同一个文件（HARNESS.md、briefs/ 天天在改），合并回主分支时就是两边各加了一版 → 冲突。快进让这一单的分支
// 真的站在主分支现在的位置上，合并回去时 lead 的文件两边一样，什么都不用解。
// --ff-only 要么干净地快进，要么一个字都不动：worker 改过的文件主分支也改了、或者这一单已经有自己的提交，它都拒绝，
// 这一轮就照旧在原来的位置上跑（和没有这个函数时一样），拒绝的原因原样交回去给 run.mjs 打日志。
//
// 单独一个文件是为了能单独测：tools/m31w/refresh-check.mjs 拿一个临时仓库 + worktree 真跑它。
import { spawnSync } from 'node:child_process';

/** 跑一条 git：成没成看退出码，不看输出——`git merge --ff-only` 成功时可以一个字都不打（2026-11-13 02:2x 就是把
 * 「没有输出」当成了失败，文件明明取进来了，函数却说什么都没取）。 */
function git(args, cwd) {
  const r = spawnSync('git', ['-c', 'core.quotepath=false', ...args], { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  return { ok: r.status === 0, stdout: r.stdout ?? '', stderr: r.stderr ?? '' };
}

/**
 * @param cwd   worker 的 worktree
 * @param base  lead 的分支（run.mjs 派单时 worktree 就是从它切出去的），一般是 master
 * @return { moved, files, why }：moved 是这一轮有没有快进；files 是主分支在这段时间里改过的文件；
 *         why 只在「主分支确实往前走了、可这一轮没能跟上」时有值（拒绝的原因，原样）
 */
export function catchUp(cwd, base) {
  const none = { moved: false, files: [], why: '' };
  if (!cwd || !base || base === 'HEAD') return none;
  const head = git(['rev-parse', 'HEAD'], cwd);
  const tip = git(['rev-parse', '--verify', '--quiet', base], cwd);
  if (!head.ok || !tip.ok) return { ...none, why: `找不到 ${base}` };
  const from = head.stdout.trim();
  const to = tip.stdout.trim();
  if (from === to) return none;
  // 这一单已经站在主分支前面（它有自己的提交、而主分支没动）：没有可跟的
  if (git(['merge-base', '--is-ancestor', to, from], cwd).ok) return none;
  const files = git(['diff', '--name-only', from, to], cwd).stdout.split('\n').filter(Boolean);
  const m = git(['merge', '--ff-only', '-q', to], cwd);
  if (!m.ok) {
    const why = (m.stderr || m.stdout).trim().split('\n').filter(Boolean).slice(0, 4).join(' | ');
    return { moved: false, files, why: why || 'git merge --ff-only 拒绝了' };
  }
  return { moved: true, files, why: '' };
}
