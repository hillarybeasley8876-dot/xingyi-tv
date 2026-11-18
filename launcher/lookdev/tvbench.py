"""Push look.json / shader variants to Orrery on the TV, hot-reload, read the renderer's fps line.

python tvbench.py '[{"count": 262144}, {"count": 131072}]'           look.json overrides (dotted keys allowed)
python tvbench.py '[{}]' --shader final.frag=path/to/variant.frag      also push a shader file for these runs
python tvbench.py --clear                                                remove every override (back to the APK's assets)
"""
import json, os, subprocess, sys, time, copy

ADB = ["H:/tools/platform-tools/adb.exe", "-s", "192.0.2.10:5555"]
D = "/sdcard/Android/data/xyz.erapi0neer.orrery/files"
HERE = os.path.dirname(os.path.abspath(__file__))
LOOK = os.path.join(HERE, "..", "assets", "flow", "look.json")
env = dict(os.environ, MSYS_NO_PATHCONV="1")


def adb(*a, check=True):
    return subprocess.run(ADB + list(a), capture_output=True, text=True, env=env, check=check).stdout


def reload():
    adb("shell", "am", "broadcast", "-a", "xyz.erapi0neer.orrery.RELOAD")


def stats(settle=6.5):
    adb("shell", "rm", "-f", D + "/flow-stats.txt")
    time.sleep(settle)
    for _ in range(10):
        s = adb("shell", "cat", D + "/flow-stats.txt", check=False).strip()
        if s and "No such file" not in s:
            return s
        time.sleep(1)
    return "(no stats)"


def apply(base, v):
    p = copy.deepcopy(base)
    for k, val in v.items():
        node = p
        parts = k.split(".")
        for q in parts[:-1]:
            node = node[q]
        node[parts[-1]] = val
    return p


def main():
    args = sys.argv[1:]
    if "--clear" in args:
        adb("shell", "rm", "-rf", D + "/flow")
        reload()
        print("overrides cleared;", stats())
        return
    variants = json.loads(args[0])
    shaders = [args[i + 1] for i, a in enumerate(args) if a == "--shader"]
    adb("shell", "mkdir", "-p", D + "/flow")
    for s in shaders:
        name, path = s.split("=", 1)
        adb("push", os.path.abspath(path), D + "/flow/" + name)
    base = json.load(open(LOOK, encoding="utf-8"))
    tmp = os.path.join(HERE, "out", "bench_look.json")
    os.makedirs(os.path.dirname(tmp), exist_ok=True)
    for v in variants:
        with open(tmp, "w", newline="\n") as f:
            json.dump(apply(base, v), f)
        adb("push", tmp, D + "/flow/look.json")
        reload()
        time.sleep(2.0)                 # rebuild, then measure a clean window
        print(json.dumps(v), "=>", stats(), flush=True)


if __name__ == "__main__":
    main()
