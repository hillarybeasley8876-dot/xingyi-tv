# -*- coding: utf-8 -*-
"""probe.evidence() 写出的 `by` 对不对 —— 这个字段回答的是「这份证据是不是那个场景脚本自己跑出来的」。

    python tests/agent/realpath/probe_by.py        打印 PROBE BY OK（有一条不对就打印出来，exit 1）

Java 那边测不了 Python（RealPathChecks 只读文本，不跑脚本），所以这三条自己测，每条都跑一个真的子进程：

  · 正常脚本：verify.sh 是 `python <路径>/<id>.py` 跑的，sys.argv[0] 是那个路径，写出来的 by 是文件名 `<id>.py`；
  · 显式 by="y.py"：给了就用给的，哪怕 argv[0] 是别的脚本；
  · 没有脚本名：`python -c`、sys.argv 是空的 —— 写 ""，不知道就不猜。

全部写进 build/probe-by/ 下的临时目录，真仓库的 tests/agent/evidence/ 一个字节都不碰。
"""
import json, os, shutil, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
OUT = os.path.join(ROOT, "build", "probe-by")
SID = "by-selftest"

# 一个假场景脚本：只调 evidence()，证据目录从环境变量拿，绝不写进真仓库那份。
FAKE = ("# -*- coding: utf-8 -*-\n"
        "import os, sys\n"
        "sys.path.insert(0, sys.argv[1])\n"
        "import probe\n"
        "probe.EVIDENCE = os.environ['PROBE_BY_EVIDENCE']\n"
        "by = os.environ.get('PROBE_BY_BY')\n"
        "probe.evidence('%s', True, ['自测'], [], by=by if by is not None else None)\n" % SID)
# 没有脚本名的两种：python -c（argv[0] 是 "-c"）和 sys.argv 被清空。
INLINE = ("import os, sys\n"
          "sys.path.insert(0, %r)\n"
          "import probe\n"
          "probe.EVIDENCE = os.environ['PROBE_BY_EVIDENCE']\n"
          "%s\n"
          "probe.evidence(%r, True, ['自测'], [])\n")


def run(args, env_extra=None, cwd=None):
    """One real subprocess; its stdout+stderr come back as one string."""
    env = dict(os.environ)
    env["PROBE_BY_EVIDENCE"] = OUT
    env["PYTHONIOENCODING"] = "utf-8"
    env.update(env_extra or {})
    r = subprocess.run([sys.executable] + args, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=env, cwd=cwd, timeout=60)
    return r.returncode, ((r.stdout or "") + (r.stderr or "")).strip()


def wrote(label, code, out):
    """What the run wrote into the temporary evidence directory; None when it wrote nothing."""
    if code != 0:
        print("FAIL %s：子进程退出码 %d\n%s" % (label, code, out))
        return None
    path = os.path.join(OUT, "real-%s.json" % SID)
    if not os.path.isfile(path):
        print("FAIL %s：没有写出 %s\n%s" % (label, path, out))
        return None
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    os.remove(path)                       # 下一条重新写，别把上一条的 by 当成这一条的
    return doc


def check(doc, want, label):
    got = None if doc is None else doc.get("by")
    if got != want:
        print("FAIL %s：by 是 %r，要的是 %r" % (label, got, want))
        return 0
    print("ok   %s：by = %r" % (label, got))
    return 1


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT, exist_ok=True)
    deep = os.path.join(OUT, "scenarios")
    os.makedirs(deep, exist_ok=True)
    for p in (os.path.join(OUT, "x.py"), os.path.join(deep, "y.py")):
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(FAKE)
    ok = 0

    # 1. 正常脚本：cwd 里 `python x.py`，sys.argv[0] 就是 "x.py"
    code, out = run(["x.py", HERE], cwd=OUT)
    ok += check(wrote("正常脚本", code, out), "x.py", "正常脚本（sys.argv[0] 是 x.py）")

    # 2. verify.sh 的跑法：给的是整条路径，写出来的还得是文件名
    code, out = run([os.path.join(deep, "y.py"), HERE], cwd=ROOT)
    ok += check(wrote("整条路径", code, out), "y.py", "给的是整条路径（verify.sh 就是这么跑的）")

    # 3. 显式 by：给了就用给的
    code, out = run(["x.py", HERE], {"PROBE_BY_BY": "z.py"}, cwd=OUT)
    ok += check(wrote("显式 by", code, out), "z.py", "显式 by=\"z.py\"（argv[0] 是 x.py）")

    # 4. 没有脚本名：python -c，和 sys.argv 被清空 —— 都是不知道，写 ""
    code, out = run(["-c", INLINE % (HERE, "", SID)], cwd=ROOT)
    ok += check(wrote("python -c", code, out), "", "python -c（没有脚本名）")
    code, out = run(["-c", INLINE % (HERE, "sys.argv = []", SID)], cwd=ROOT)
    ok += check(wrote("argv 是空的", code, out), "", "sys.argv 是空的")

    shutil.rmtree(OUT, ignore_errors=True)
    if ok != 5:
        print("probe by: %d/5 条不对" % (5 - ok))
        return 1
    print("PROBE BY OK")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except AttributeError:
        pass
    sys.exit(main())
