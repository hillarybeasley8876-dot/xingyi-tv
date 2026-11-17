# -*- coding: utf-8 -*-
"""续跑规则的离线检查：读一份 tools/resume/rehearse.mjs --report --json 的报告，把不对的地方一句一句说出来。
不碰电视、不联网、不起 run.mjs —— 这是 CI 跑的那一半；真把 runner 跑一遍是 tests/agent/accept_resume.sh 的事。

    python tests/agent/resume/check.py                  看 tests/agent/resume/reports/：good.json 要一句都不说，
                                                        每份 broken-*.json 要恰好说一句、说的正是它自己坏的那条
    python tests/agent/resume/check.py <报告.json>...    只看给的这几份

好了打 RESUME OK（退出 0）；有问题就把问题打出来（退出 1）。

规矩（ResumeChecks.java 在传进去的文本上把每一条各弄坏一次）：

  · 形状：一个 JSON 对象，jobs 是非空数组；每个单有 id / model / maxRounds / state0 / launches / final / stub，
    每次起来（launch）有 beginRound / rounds / stateBefore / stateAfter / stub[]；**一个单一次都没起来过就是不对**；
  · 不许写进别人的单：out / wt / env / claude 都要在 scratch 里面，谁都不许是 realOut、也不许在 realOut/jobs 里面，
    路径里不许出现 assistant-out/jobs；job id 一律 rehearse- 开头；
  · 被杀掉的那一轮不算跑过：跟着一次被杀的起来之后的那一次，stateBefore.rounds 要大于 stateBefore.history
    （真的有一轮没记账，这条规矩才不是空话），beginRound 要和被杀那次一样，最后 rounds == history；
  · 每一轮的账：final.history == state0.history + 假工人跑完的次数，没跑完的次数 == 被杀的次数；
  · 一轮真的跑到了验收：每次起来记下的 accept 条数要等于这一次假工人跑完的次数，每一条 pass 都是 true（被杀的那次
    起来一条都没有 —— 这正是「没跑完的那一轮不记账」的样子）；
  · 接着跑说的那句话：--resume 起来的，第一句是 `The lead resumes this job.`、不是 brief 的第一句，
    且 --resume 带的是 stateBefore 里记着的那个 sessionId；会话还没起来过的（--session-id），第一句是 brief 的
    第一句，不含续跑句；
  · 换了模型不接着跑：stateBefore.sessionStarted 而 sessionModel 和单子的 model 不一样时，不许 --resume，
    要 --session-id 且是一个新的 id（不是 stateBefore 那个），第一句是 brief 的第一句加「fresh session」那段；
  · 轮数到了顶：stateBefore.rounds >= maxRounds 的那一次，beginRound 不越过 maxRounds，stateAfter.rounds 也不越过，
    一次最多再跑一轮，最后落在 accepted_mechanical 或 needs_lead。

实测的一条（2026-11-12，这台机器上真跑出来的）：轮数已经到顶的单，run.mjs **会把第 maxRounds 轮再跑一次**
（beginRound == maxRounds，不是 maxRounds - 1），跑不过才落 needs_lead。所以这里的封顶规矩写成「不越过 maxRounds」。
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPORTS = os.path.join(HERE, "reports")
CONTINUATION = "The lead resumes this job."
BRIEF_MARK = "# YOUR BRIEF"
FRESH_MARK = "# CONTINUATION: a fresh session"
DONE = ("accepted_mechanical", "needs_lead")


def read(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return None


def num(o, k):
    """一个字段当非负整数看；不是整数就是 None（形状不对，后面的规矩就不看了）。"""
    v = o.get(k) if isinstance(o, dict) else None
    return v if isinstance(v, int) and not isinstance(v, bool) and v >= 0 else None


def text(o, k):
    v = o.get(k) if isinstance(o, dict) else None
    return v if isinstance(v, str) else None


def norm(p):
    return p.replace("\\", "/").rstrip("/") if isinstance(p, str) else ""


# ---- 不许写进别人的单：这是这个工具唯一会真正伤到别人的地方 ----------------------------------------------

def safety(r):
    bad = []
    scratch, real = norm(r.get("scratch")), norm(r.get("realOut"))
    for k in ("out", "wt", "env", "claude"):
        p = norm(r.get(k))
        if not p:
            bad.append("%s 没写" % k)
            continue
        if scratch and p != scratch and not p.startswith(scratch + "/"):
            bad.append("%s（%s）不在 scratch（%s）里面" % (k, p, scratch))
        if real and (p == real or p.startswith(real + "/jobs")):
            bad.append("%s（%s）落在真的 jobs 目录（%s/jobs）里" % (k, p, real))
        if "assistant-out/jobs" in p:
            bad.append("%s（%s）的路径里有 assistant-out/jobs" % (k, p))
    if scratch and real and (scratch == real or scratch.startswith(real + "/jobs")):
        bad.append("scratch（%s）自己就在真的 jobs 目录里" % scratch)
    if scratch and "assistant-out/jobs" in scratch:
        bad.append("scratch（%s）的路径里有 assistant-out/jobs" % scratch)
    return ["演练写到了不该写的地方：" + "；".join(bad)] if bad else []


# ---- 被杀掉的那一轮 -------------------------------------------------------------------------------

def killed_round(who, launches, fin):
    out = []
    for i in range(1, len(launches)):
        prev, cur = launches[i - 1], launches[i]
        if prev.get("killed") is not True:
            continue
        pa, sb = prev["stateAfter"], cur["stateBefore"]
        if not (num(pa, "rounds") > num(pa, "history")) or not (num(sb, "rounds") > num(sb, "history")):
            out.append("单 %s：第 %d 次起来之前，state 是 rounds=%s history=%s —— 没有一轮跑完却没记账，"
                       "这份报告证明不了「没跑完的那一轮会再跑一次」" % (who, i + 1, num(sb, "rounds"), num(sb, "history")))
            continue
        bad = []
        if num(cur, "beginRound") != num(prev, "beginRound"):
            bad.append("上一次从第 %s 轮被杀，这一次从第 %s 轮开始" % (num(prev, "beginRound"), num(cur, "beginRound")))
        if num(fin, "rounds") != num(fin, "history"):
            bad.append("最后 rounds=%s 而 history=%s" % (num(fin, "rounds"), num(fin, "history")))
        if bad:
            out.append("单 %s：被杀的那一轮没有原样再跑一次，没跑完的那一轮被当成跑过了 —— %s"
                       % (who, "；".join(bad)))
    return out


# ---- 起来一次：接着跑、换模型、还是第一次 ------------------------------------------------------------

def launch_rules(who, i, l, model, maxRounds, fin):
    n = i + 1
    sb = l["stateBefore"]
    runs = l["stub"]
    if not runs:
        return ["单 %s 第 %d 次起来：假工人一次都没被叫起来（stub 是空的，这一轮没真跑过）" % (who, n)]
    first = runs[0]
    argv = first.get("argv") if isinstance(first.get("argv"), list) else []
    prompt = first.get("prompt") if isinstance(first.get("prompt"), str) else ""
    sid = first.get("sessionId")
    resumed, fresh = "--resume" in argv, "--session-id" in argv
    changed = sb.get("sessionStarted") is True and text(sb, "sessionModel") not in (None, model)
    bad = []
    if changed:
        # 别的模型开的会话：thinking block 那边接着读不了，换一个新 session 从头说
        if resumed:
            bad.append("用了 --resume")
        if not fresh:
            bad.append("没有 --session-id")
        elif sid == text(sb, "sessionId"):
            bad.append("--session-id 带的是 state 里原来那个 %s，不是新的" % sid)
        if prompt.startswith(CONTINUATION):
            bad.append("第一句是续跑句，不是 brief 的第一句加「fresh session」那段")
        elif BRIEF_MARK not in prompt:
            bad.append("第一句不是 brief 的第一句")
        elif FRESH_MARK not in prompt:
            bad.append("第一句里没有「fresh session」那段")
        head = "单 %s 第 %d 次起来（换了模型）：state 里记的会话是 %s 开的，单子要的是 %s —— " % (
            who, n, text(sb, "sessionModel"), model)
    elif resumed:
        if sb.get("sessionStarted") is not True:
            bad.append("state 说会话还没起来过，却用了 --resume")
        if sid != text(sb, "sessionId"):
            bad.append("--resume 带的是 %s，state 里记的是 %s" % (sid, text(sb, "sessionId")))
        if BRIEF_MARK in prompt:
            bad.append("第一句是 brief 的第一句，不是续跑句")
        elif not prompt.startswith(CONTINUATION):
            bad.append("第一句不是「%s」那句" % CONTINUATION)
        head = "单 %s 第 %d 次起来（接着跑）：" % (who, n)
    elif fresh:
        if sb.get("sessionStarted") is True:
            bad.append("state 说会话起来过、模型也没换，却开了新会话（该 --resume）")
        if sid != text(sb, "sessionId"):
            bad.append("--session-id 带的是 %s，state 里记的是 %s" % (sid, text(sb, "sessionId")))
        if prompt.startswith(CONTINUATION):
            bad.append("第一句是续跑句，不是 brief 的第一句")
        elif BRIEF_MARK not in prompt:
            bad.append("第一句不是 brief 的第一句")
        if FRESH_MARK in prompt:
            bad.append("第一句里有「fresh session」那段，可这个会话是第一次起来")
        head = "单 %s 第 %d 次起来（第一次跑）：" % (who, n)
    else:
        return ["单 %s 第 %d 次起来：假工人拿到的 argv 里既没有 --resume 也没有 --session-id（%s）"
                % (who, n, " ".join(str(a) for a in argv[:12]))]
    out = [head + "；".join(bad)] if bad else []
    # 轮数到顶：run.mjs 实测会把第 maxRounds 轮再跑一次，所以规矩是「不越过 maxRounds」（文件头）
    if num(sb, "rounds") >= maxRounds:
        over = []
        if num(l, "beginRound") is None or num(l, "beginRound") > maxRounds:
            over.append("从第 %s 轮开始" % num(l, "beginRound"))
        rounds = l.get("rounds") if isinstance(l.get("rounds"), list) else []
        past = [r for r in rounds if isinstance(r, int) and not isinstance(r, bool) and r > maxRounds]
        if past:
            over.append("跑到了第 %s 轮" % "、".join(str(r) for r in past))
        if num(l["stateAfter"], "rounds") > maxRounds:
            over.append("state 记成了 rounds=%s" % num(l["stateAfter"], "rounds"))
        if len(rounds) > 1:
            over.append("一次起来跑了 %d 轮（到顶的单最多再跑一轮）" % len(rounds))
        if text(fin, "status") not in DONE:
            over.append("最后落在 %s（该是 accepted_mechanical 或 needs_lead）" % text(fin, "status"))
        if over:
            out.append("单 %s 第 %d 次起来：state 说 rounds=%s，已经到顶（maxRounds=%d），却 %s —— 轮数跑出了封顶"
                       % (who, n, num(sb, "rounds"), maxRounds, "；".join(over)))
    return out


# ---- 一个单：形状、账、每一句话 ------------------------------------------------------------------------

def state_ok(s):
    return (isinstance(s, dict) and num(s, "rounds") is not None and num(s, "history") is not None
            and isinstance(s.get("sessionStarted"), bool) and isinstance(s.get("sessionId"), str))


def job(j):
    if not isinstance(j, dict):
        return ["jobs 里有一条不是对象"]
    who = text(j, "id") or "(没有 id)"
    out = []
    if not who.startswith("rehearse-"):
        out.append("单 %s：id 不是 rehearse- 开头的（演练的单不许和真单混在一个目录里）" % who)
    launches = j.get("launches")
    if not isinstance(launches, list) or not launches:
        out.append("单 %s：一次 run.mjs 都没起来过（launches 是空的，这份报告什么也没证明）" % who)
        return out
    model, maxRounds = text(j, "model"), num(j, "maxRounds")
    s0, fin, stub = j.get("state0"), j.get("final"), j.get("stub")
    if (model is None or maxRounds is None or not state_ok(s0) or not state_ok(fin)
            or not isinstance(stub, dict) or num(stub, "runs") is None or num(stub, "finished") is None):
        return out + ["单 %s：报告的形状不对（model / maxRounds / state0 / final / stub.runs / stub.finished）" % who]
    for i, l in enumerate(launches):
        if (not isinstance(l, dict) or num(l, "beginRound") is None or not state_ok(l.get("stateBefore"))
                or not state_ok(l.get("stateAfter")) or not isinstance(l.get("stub"), list)):
            return out + ["单 %s 第 %d 次起来：形状不对（beginRound / stateBefore / stateAfter / stub）" % (who, i + 1)]

    # 每一轮的账：跑完一次记一条，没跑完的一次都不记
    finished, want = num(stub, "finished"), num(s0, "history") + num(stub, "finished")
    if num(fin, "history") != want:
        out.append("单 %s：假工人跑完 %d 轮，history 该是 %d（state0 的 %d 加跑完的 %d），记的是 %s —— 有一轮没记账，"
                   "或者有一轮没跑完却记了账" % (who, finished, want, num(s0, "history"), finished, num(fin, "history")))
    killed = sum(1 for l in launches if l.get("killed") is True)
    unfinished = num(stub, "runs") - finished
    if unfinished != killed:
        out.append("单 %s：假工人被叫起来 %d 次、跑完 %d 次，%d 次没跑完，可被杀掉的起来是 %d 次（对不上）"
                   % (who, num(stub, "runs"), finished, unfinished, killed))

    out += killed_round(who, launches, fin)
    for i, l in enumerate(launches):
        out += launch_rules(who, i, l, model, maxRounds, fin)
        out += accept_rule(who, i, l)
    return out


def accept_rule(who, i, l):
    """一轮要真的跑到 runner 自己的验收：跑完几次就要有几条账，每一条都过了。"""
    n = i + 1
    acc = l.get("accept")
    if not isinstance(acc, list):
        return ["单 %s 第 %d 次起来：没有 accept（这一轮有没有跑到验收，报告里没说）" % (who, n)]
    runs = l.get("stub") if isinstance(l.get("stub"), list) else []
    finished = sum(1 for s in runs if isinstance(s, dict) and s.get("finished") is True)
    if len(acc) != finished:
        return ["单 %s 第 %d 次起来：假工人跑完 %d 轮，验收记了 %d 条（被杀的那一轮不该有账，跑完的也不该没有）"
                % (who, n, finished, len(acc))]
    bad = [a for a in acc if not (isinstance(a, dict) and a.get("pass") is True)]
    if bad:
        return ["单 %s 第 %d 次起来：%d 轮里 %d 轮验收没过 —— 这一轮没有真的跑完，报告却当它跑完了"
                % (who, n, len(acc), len(bad))]
    return []


def problems(report):
    """一份演练报告里所有不对的地方，一句一句；没问题就一句都不说。"""
    try:
        r = json.loads(report)
    except ValueError:
        return ["报告读不出来（要是一个 JSON 对象）"]
    if not isinstance(r, dict):
        return ["报告不是一个对象"]
    out = safety(r)
    jobs = r.get("jobs")
    if not isinstance(jobs, list) or not jobs:
        out.append("报告里一个单都没有（jobs 要是一个非空数组）")
        return out
    for j in jobs:
        out += job(j)
    return out


# ---- 自己跑：没给路径就看 tests/agent/resume/reports/，好的那份一句都不说，坏的那些各说一句 ---------------

# (文件, 这份坏在哪儿) —— good.json 是一份真的演练报告（2026-11-12 在这台机器上跑出来的），不是手写的
FIXTURES = [
    ("good.json", None),
    ("killed-round-counted.json", "没跑完的那一轮被当成跑过了"),
    ("brief-prompt-where-continuation-belongs.json", "第一句是 brief 的第一句"),
    ("continuation-where-brief-belongs.json", "第一句是续跑句"),
    ("resume-after-model-change.json", "用了 --resume"),
    ("round-past-the-cap.json", "轮数跑出了封顶"),
    ("no-launch-at-all.json", "一次 run.mjs 都没起来过"),
    ("writes-into-the-real-jobs-dir.json", "落在真的 jobs 目录"),
]


def fixtures():
    out = []
    for name, needle in FIXTURES:
        t = read(os.path.join(REPORTS, name))
        if t is None:
            out.append("tests/agent/resume/reports/%s 不在" % name)
            continue
        ps = problems(t)
        if needle is None:
            if ps:
                out.append("%s 该一句都不说，可它说了 %d 句：%s" % (name, len(ps), " | ".join(ps)))
        elif len(ps) != 1:
            out.append("%s 该恰好说一句、说的是「%s」，可它说了 %d 句：%s" % (name, needle, len(ps), " | ".join(ps) or "(一句都没说)"))
        elif needle not in ps[0]:
            out.append("%s 说的那句里没有「%s」：%s" % (name, needle, ps[0]))
    return out


def main():
    paths = [a for a in sys.argv[1:] if not a.startswith("-")]
    out = []
    if paths:
        for p in paths:
            t = read(p)
            if t is None:
                out.append("%s 读不出来" % p)
            else:
                out += ["%s：%s" % (os.path.basename(p), s) for s in problems(t)]
    else:
        out = fixtures()
    if out:
        for line in out:
            print(line)
        print("resume: %d 处不对" % len(out))
        return 1
    print("RESUME OK")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
