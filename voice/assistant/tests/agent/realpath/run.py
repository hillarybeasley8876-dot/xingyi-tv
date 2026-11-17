# -*- coding: utf-8 -*-
"""Runs the real-path scenarios on the TV, one at a time.

    python tests/agent/realpath/run.py --list          what each scenario needs and how long it says it takes
    python tests/agent/realpath/run.py --only <id>     one scenario
    python tests/agent/realpath/run.py --all           all six, in the registry's order

--list (and no argument at all) touches nothing: it reads scenarios.json and prints it. --only / --all run the scripts,
which drive the real TV through probe.py; each of them refuses — returns 3, says why, writes no evidence — when the TV
is asleep, when something other than the screen it starts from is in front, or when the last key is under 20 minutes
old. Two runs at once are refused (a lock file next to the registry): two scenarios on one TV would be reading each
other's screens. A set stops at the first 3, because every scenario after it would be looking at a TV that is not in
the state it needs.

`hours` is the registry's own 1–10 scale: how long the scenario says it takes, bigger is longer.
"""
import argparse, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
REGISTRY = os.path.join(HERE, "scenarios.json")
LOCK = os.path.join(HERE, ".running.lock")
EVIDENCE = os.path.join(ROOT, "tests", "agent", "evidence")


def registry():
    with open(REGISTRY, encoding="utf-8") as f:
        return json.load(f)["scenarios"]


def listing(entries):
    """What each scenario needs and how long it says it takes. Reads the registry and nothing else."""
    print("%d 个真实路径的场景（scenarios.json）：" % len(entries))
    for e in entries:
        print("  %-22s %s" % (e["id"], e["what"]))
        print("  %-22s 需要：%s · hours %s · 没验的待办：%s"
              % ("", "、".join(e.get("needs") or []), e.get("hours"), e.get("unverified") or "（没有）"))
        print("  %-22s 脚本：%s" % ("", e.get("script", "")))


def evidence_ok(sid):
    """What the run left behind: real-<id>.json's ok, or None when it wrote none (a refusal writes nothing)."""
    try:
        with open(os.path.join(EVIDENCE, "real-%s.json" % sid), encoding="utf-8") as f:
            return json.load(f).get("ok")
    except (OSError, ValueError):
        return None


def lock():
    """Only one run at a time."""
    try:
        fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        raise SystemExit("另一次 realpath 还在跑（锁在 %s）；一次只跑一个场景" % LOCK)
    os.write(fd, ("%d\n" % os.getpid()).encode("utf-8"))
    os.close(fd)


def unlock():
    try:
        os.remove(LOCK)
    except OSError:
        pass


def run_one(e):
    """One scenario, on the TV. @return 0 passed, 1 did not pass, 3 could not run."""
    print("== %s · %s" % (e["id"], e["what"]), flush=True)
    print("   需要：%s（这个场景自己说 hours %s）" % ("、".join(e.get("needs") or []), e.get("hours")), flush=True)
    code = subprocess.run([sys.executable, os.path.join(ROOT, e["script"].replace("/", os.sep))]).returncode
    if code == 3:
        print("   %s：这次跑不了（3）——前置条件不满足，上面写了为什么，没有写证据文件" % e["id"], flush=True)
        return 3
    if code not in (0, 1, 3):
        # 0/1/3 是场景自己的说法；别的退出码是脚本崩了（ImportError、语法错、main() 抛了没人接）。
        # 这时候不能去读证据文件：上一次跑留下的 real-<id>.json 还躺在那儿，ok=true 的那份会把这个
        # 崩溃说成「验过了」——崩溃不是判据，算过不了。
        print("   %s：脚本异常退出 %d，不算验过（证据文件不看：可能是上一次留下的）" % (e["id"], code), flush=True)
        return 1
    ok = evidence_ok(e["id"])
    print("   %s：证据 real-%s.json 的 ok = %s" % (e["id"], e["id"], "（没写）" if ok is None else str(ok).lower()),
          flush=True)
    return 0 if ok is True else 1


def main():
    ap = argparse.ArgumentParser(description="真实路径的场景：列出它们，或者一个一个地在电视上跑")
    ap.add_argument("--list", action="store_true", help="列出场景和它们要什么（不碰电视；不给参数也是这个）")
    ap.add_argument("--only", metavar="ID", help="只跑一个场景")
    ap.add_argument("--all", action="store_true", help="按注册表的顺序全跑一遍")
    a = ap.parse_args()
    entries = registry()
    if a.list or not (a.only or a.all):
        listing(entries)
        return 0
    chosen = entries
    if a.only:
        chosen = [e for e in entries if e.get("id") == a.only]
        if not chosen:
            print("scenarios.json 里没有「%s」这个场景" % a.only)
            return 1
    lock()
    bad = False
    try:
        for e in chosen:
            code = run_one(e)
            if code == 3:
                print("到这里停下：%s 跑不了，后面的场景要看的那台电视不在该在的样子" % e["id"], flush=True)
                return 3
            bad = bad or code != 0
    finally:
        unlock()
    return 1 if bad else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
