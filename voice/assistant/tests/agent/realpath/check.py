# -*- coding: utf-8 -*-
"""The offline check of the real-path probes: the registry, the scripts it names, and the shape of every evidence file
a run on the TV left behind. No TV, no adb, no network — this is the only part of tests/agent/realpath/ CI runs.

    python tests/agent/realpath/check.py            prints REALPATH OK (exit 0), or one sentence per problem (exit 1)
    python tests/agent/realpath/check.py --strict   the evidence under evidence/realpath/ counts as wrong too (exit 1)

The rules, in one place (RealPathChecks.java breaks each of them once on texts passed in, the way Reported.java's
checks break reported.json's rules):

  · every scenario in scenarios.json: an id that is unique kebab-case, a one-line `what`, a `needs` array, an `hours`
    between 1 and 10, an `unverified` that is "" or kebab-case, and a `script` that exists and has a main() in it;
  · every scenario in scenarios.json: at least one case in tests/agent/realpath/rehearse.py, and both sides of it — one
    that has to pass and one that has to not. A problem, --strict or not. (2026-11-14: the judges themselves had never
    been shown to be able to go red. hint-card-not-a-switch, 11-12 22:47, read a key the probe never carried, got None
    on both sides, and could not have passed however the TV behaved — a hundred runs of it would have stayed green.)
    The cases are read as text, not exec'd: only the one shape rehearse.py documents — `"scenario"`, `"name"` and
    `"expect"` next to each other — is seen, and a case written any other way counts as no case at all. Case names
    have to be unique and `expect` has to be True / False / None, so a report can point at one case. rehearse.py
    missing or unreadable is one sentence and nothing else: a rule that cannot read the file does not get to guess
    which scenario is short of cases;
  · every tests/agent/evidence/real-<id>.json that exists: parses, `id` equal to its own file name, an `at` that is an
    ISO timestamp and not in the future, a boolean `ok`, a `notes` array (and a `shots` array when it is there);
  · every evidence with "ok": false: scenarios.json's `unverified` for that id names an entry that is really in
    tests/agent/unverified.json — a verification that did not pass has to come back as a to-do, or it is lost;
  · the registry and the ledger have to say the same thing, both ways: a scenario with no evidence/real-<id>.json
    names, through its `unverified`, an entry that is really in tests/agent/unverified.json, and a scenario whose
    evidence says "ok": true has no entry of its own id left in that ledger — unless that evidence was written by some
    other script (see below), in which case the scenario script itself still has not run and the entry is right where
    it belongs. A problem either way, --strict or not (2026-11-12 21:40: three scenarios had never run on the TV, the
    ledger was empty because nobody had written it, and NEXT.md / bridge.sh status / HANDOFF all read that as 「一条
    都没有：都验过了」);
  · every scenario whose evidence says "ok": true but was NOT written by its own scenario script (`by`, the file name
    of the script that produced it, is not the scenario script's own file name), and every one whose evidence says
    nothing about who wrote it (no `by`, or `by` is ""): one line each, from probe_made(). Hints, not problems — the
    behaviour really was verified on the TV, and what is missing is a rerun of the script, which is the ledger's
    business, not a reason to turn the tree red. problems() does not return them, --strict does not count them
    (2026-11-13 01:40: five of the ten evidence files were written by hand-written probes — session7m.py, h1.py, h2.py,
    h3.py — while their scenario scripts had never run at all, and the files looked exactly like the ones the scripts
    had written themselves; hint-card-not-a-switch 11-12 22:47 had already been the same shape: the evidence was there,
    and the script could not have passed);
  · every *.json under tests/agent/evidence/realpath/: one WHERE line saying the loop above cannot see it there. A
    notice, not a problem, so today's tree still prints REALPATH OK; --strict turns the same lines into failures.
    (2026-11-12 20:30: five real runs of the sleeping-TV and standby paths were written there by hand, and a fresh
    session could not tell them from never-run.)
  · every key a scenario reads straight off p.state() / p.memory(): it has to be a top-level key the broadcast really
    carries, as recorded in tests/agent/fixtures/realpath/payload-shapes.json (shapes only, no content — the lead
    captured it on the TV). A problem, --strict or not. (2026-11-12 22:47: hint-card-not-a-switch read `hints` from
    STATE, which has never carried it — it is in MEMORY — so the scenario read None twice and could not pass however
    the TV behaved, and a 20-minute window on the real machine was spent finding that out.) When that recording is not
    in this tree there is no shape to compare a key against, so the rule says nothing and one line says it could not
    run: printed on every run, counted by nothing, --strict included. It is about what the tree was given, not about
    a scenario, and only recording the broadcasts on the TV fixes it.

What the shape rule does NOT see, on purpose: a read through a variable (`s = p.state()` and then `s.get("x")`).
This is a text scan, not a parse — telling which variable holds which broadcast would be guessing, and a check that
guesses is worse than one that says nothing. Written down here so nobody reads more into it than it does.
"""
import ast, glob, json, os, re, sys, time
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
ID = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")
AT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}")
SHAPES = ("tests", "agent", "fixtures", "realpath", "payload-shapes.json")
SCENARIOS = ("tests", "agent", "realpath", "scenarios")
REHEARSE = ("tests", "agent", "realpath", "rehearse.py")
REGISTRY = ("tests", "agent", "realpath", "scenarios.json")
# 一条局面：rehearse.py 写明了这三个键要挨在一起，扫不到别的写法。
CASE = re.compile(r'"scenario"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"([^"]+)"\s*,\s*"expect"\s*:\s*([^,\s\]}]+)')
EXPECT = re.compile(r'"expect"\s*:\s*([^,\s\]}]+)')
TOKENS = ("True", "False", "None")
REHEARSE_UNREADABLE = ("tests/agent/realpath/rehearse.py 读不出来（不在或者读不了），"
                       "没法核对哪条场景有没有离线局面")
# p.state() / p.memory(), whitespace and newlines anywhere in between, then .get("k") or ["k"] straight off it.
READ_KEY = re.compile(r"p\s*\.\s*(state|memory)\s*\(\s*\)\s*"
                      r"(?:\.\s*get\s*\(\s*(['\"])([^'\"]+)\2\s*\)|\[\s*(['\"])([^'\"]+)\4\s*\])")
SHAPE_UNREADABLE = ("payload-shapes.json 读不出来（形状 fixture 不在或者不是 JSON），没法核对场景读的字段")


def read(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return None


def scenarios(text):
    """The registry's entries, or None when it cannot be read as one."""
    try:
        arr = json.loads(text).get("scenarios")
    except (ValueError, AttributeError):
        return None
    return arr if isinstance(arr, list) else None


def has_main(path):
    """Whether the script has a main() of its own — a scenario nobody can run is not a scenario."""
    text = read(path)
    if text is None:
        return False
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return False
    return any(isinstance(n, ast.FunctionDef) and n.name == "main" for n in tree.body)


def registry_problems(text, root):
    """Everything wrong with scenarios.json, as sentences; empty when it is sound."""
    out = []
    entries = scenarios(text)
    if entries is None:
        return ["scenarios.json 读不出来（要是一个 JSON，里面有 scenarios 数组）"]
    seen = set()
    for i, e in enumerate(entries):
        who = "第 %d 个场景" % (i + 1)
        if not isinstance(e, dict):
            out.append(who + "：不是一条记录（scenarios 里要都是对象）")
            continue
        sid = e.get("id")
        if not isinstance(sid, str) or not sid:
            out.append(who + "：id 是空的")
            continue
        who = sid
        if not ID.match(sid):
            out.append(who + "：id 不是 kebab-case（只小写字母、数字和中划线）")
        elif sid in seen:
            out.append(who + "：id 和前面的一条一样（每个场景要自己的 id）")
        seen.add(sid)
        what = e.get("what")
        if not isinstance(what, str) or not what.strip():
            out.append(who + "：what 是空的（这个场景验什么，要写一句）")
        elif "\n" in what.strip():
            out.append(who + "：what 不止一行（要一句话）")
        needs = e.get("needs")
        if not isinstance(needs, list) or not needs:
            out.append(who + "：needs 不是数组，或者里面是空的（前置条件要写出来）")
        elif not all(isinstance(n, str) and n.strip() for n in needs):
            out.append(who + "：needs 里有一条不是一句非空的话")
        hours = e.get("hours")
        if not isinstance(hours, int) or isinstance(hours, bool) or not 1 <= hours <= 10:
            out.append(who + "：hours「%s」不是 1 到 10 的整数（这个场景自己说要多长）" % (hours,))
        un = e.get("unverified")
        if not isinstance(un, str):
            out.append(who + "：unverified 不是字符串（可以是空串）")
        elif un and not ID.match(un):
            out.append(who + "：unverified「%s」不是 kebab-case" % un)
        out += script_problems(who, e.get("script"), root)
    return out


def script_problems(who, script, root):
    if not isinstance(script, str) or not script.strip():
        return [who + "：script 是空的（要说清脚本在哪）"]
    path = os.path.join(root, script.replace("/", os.sep))
    if not os.path.isfile(path):
        return [who + "：script「%s」在仓库里没有" % script]
    if not has_main(path):
        return [who + "：script「%s」 里没有 main()（跑不起来的场景不算场景）" % script]
    return []


def parse_at(at):
    """The evidence's own time as a datetime, or None when it is not an ISO timestamp."""
    if not isinstance(at, str) or not AT.match(at.strip()):
        return None
    s = at.strip()
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(s)
    except ValueError:
        return None


def unverified_ids(text):
    """The ids in tests/agent/unverified.json; None when the file is not there or has no entries array."""
    if text is None:
        return None
    try:
        arr = json.loads(text).get("entries")
    except (ValueError, AttributeError):
        return None
    if not isinstance(arr, list):
        return None
    return [e.get("id") for e in arr if isinstance(e, dict)]


def link_problems(who, unverified_name, unverified_text):
    """A verification that did not pass has to come back as a to-do, or it is lost (2026-11-11: 「暂停已验证」)."""
    if not unverified_name:
        return [who + "：这次没过，可 scenarios.json 里它的 unverified 是空的（没过的验证要留下一条待办）"]
    ids = unverified_ids(unverified_text)
    if ids is None:
        return [who + "：tests/agent/unverified.json 读不出来（不在，或者里面没有 entries 数组），"
                     "没法确认「%s」这条待办" % unverified_name]
    if unverified_name not in ids:
        return [who + "：unverified「%s」不在 tests/agent/unverified.json 里（待办没了，或者名字改了）" % unverified_name]
    return []


def says_ok(text):
    """Whether an evidence file says "ok": true. Anything else — unreadable, not JSON, no boolean ok — is not a pass."""
    if text is None:
        return False
    try:
        d = json.loads(text)
    except ValueError:
        return False
    return isinstance(d, dict) and d.get("ok") is True


def by_of(text):
    """The `by` in one evidence file: the file name of the script that produced it. "" when the file says nothing —
    no `by` at all (every file written before 2026-11-13), an empty one (`python -c` wrote it), or anything that is
    not a string. "" means 「不知道」 and is never read as 「the scenario script」."""
    if text is None:
        return ""
    try:
        d = json.loads(text)
    except ValueError:
        return ""
    by = d.get("by") if isinstance(d, dict) else None
    return by if isinstance(by, str) else ""


def script_name(entry):
    """The scenario script's own file name — what `by` has to be for the evidence to be that script's own run. Taken
    from the registry's `script`; `<id>.py` when the registry names none (registry_problems already says that is
    wrong, and this rule still has to know what it is comparing against)."""
    s = entry.get("script")
    if isinstance(s, str) and s.strip():
        name = os.path.basename(s.strip().replace("/", os.sep))
        if name:
            return name
    sid = entry.get("id")
    return sid + ".py" if isinstance(sid, str) and sid else ""


def ran_itself(text, script):
    """Whether the evidence was produced by the scenario's own script: `by` is that file name. An evidence that says
    nothing about who wrote it counts as its own run — that is the rule as it stood before `by` existed, and turning
    「I cannot tell」 into 「the to-do may go」 would lose the to-do."""
    by = by_of(text)
    return not by or by == script


def probe_made(root=ROOT):
    """One line per scenario whose passing evidence was not written by its own scenario script — hints, NOT problems.

    probe.evidence() records `by`, the file name of the script that produced the evidence, and that is the only thing
    that tells the two kinds of evidence apart: a scenario script that ran on the TV and passed, and a hand-written
    probe that exercised the same behaviour while the script itself has never run at all. 2026-11-13 01:40: five of
    the ten files were the second kind (session7m.py, h1.py, h2.py, h3.py) and looked exactly like the first.

    Only a passing evidence is looked at: what these lines are about is 「行为验过了，可这个脚本自己还没跑通过」, and an
    evidence that says ok:false is link_problems' and the ledger's business. A scenario with no evidence file is
    todo_problems' business and is not repeated here.

    Hints, on purpose: the behaviour really was verified, the tree is not broken, and what is missing is a rerun —
    `sh tests/agent/verify.sh --run --only <id>` — which is the ledger's business. problems() does not return these,
    failures() does not count them, and --strict does not turn them into failures.
    """
    text = read(os.path.join(root, "tests", "agent", "realpath", "scenarios.json"))
    entries = scenarios(text) if text is not None else None
    if entries is None:
        return []                        # registry_problems already says what is wrong with the registry
    base = os.path.join(root, "tests", "agent", "evidence")
    out = []
    for e in entries:
        if not isinstance(e, dict) or not isinstance(e.get("id"), str) or not e["id"]:
            continue
        sid = e["id"]
        path = os.path.join(base, "real-%s.json" % sid)
        if not os.path.isfile(path):
            continue
        body = read(path)
        if not says_ok(body):
            continue
        by, script = by_of(body), script_name(e)
        if not by:
            out.append("%s 的证据没写是谁跑出来的（老文件）：分不清是脚本还是探针" % sid)
        elif by != script:
            out.append("%s 的证据是 %s 跑出来的，不是场景脚本 %s：行为验过了，可这个脚本自己还没跑通过"
                       "（sh tests/agent/verify.sh --run --only %s 能重跑它）" % (sid, by, script, sid))
    return out


def todo_problems(root=ROOT):
    """The registry and the ledger have to say the same thing, both ways (2026-11-12 21:40: three scenarios had never
    run on the TV, the ledger was empty because nobody had written it down, and NEXT.md / bridge.sh status / HANDOFF
    all read that empty ledger as 「一条都没有：装上的东西都在真机上见过」).

      · a scenario with no evidence/real-<id>.json: its `unverified` names an entry that is really in
        tests/agent/unverified.json — a scenario nobody has run is a to-do, or it disappears;
      · a scenario whose evidence says "ok": true AND was written by its own scenario script (`by` is that script's
        file name, or the file is old and says nothing): no entry of its own id is left in
        tests/agent/unverified.json — the run that verified it takes the to-do out again (tests/agent/verify.sh --run
        does that). When `by` is some other script the entry stays and nothing is said: the behaviour was verified by
        a hand-written probe, the scenario script itself still has not run, and the to-do is exactly right where it is
        (probe_made() is what says that, as a hint).

    Problems, not notices: --strict or not, they count. Evidence with "ok": false is link_problems' business and is
    not repeated here, and a registry that cannot be read is registry_problems' — this one stays quiet about both. A
    ledger that cannot be read is complained about in the first direction only (there the scenario's own `unverified`
    is the thing that has to be confirmed); the second direction needs the ledger to say anything at all, so it stays
    quiet instead of guessing."""
    text = read(os.path.join(root, "tests", "agent", "realpath", "scenarios.json"))
    entries = scenarios(text) if text is not None else None
    if entries is None:
        return []
    ids = unverified_ids(read(os.path.join(root, "tests", "agent", "unverified.json")))
    base = os.path.join(root, "tests", "agent", "evidence")
    out = []
    for e in entries:
        if not isinstance(e, dict) or not isinstance(e.get("id"), str) or not e["id"]:
            continue                      # registry_problems already says what is wrong with this one
        sid = e["id"]
        path = os.path.join(base, "real-%s.json" % sid)
        if os.path.isfile(path):
            body = read(path)
            if says_ok(body) and ids is not None and sid in ids and ran_itself(body, script_name(e)):
                out.append("%s 已经验过了（real-%s.json 是 ok:true），待办该删掉"
                           "（跑 tests/agent/verify.sh --run 会自己删）" % (sid, sid))
            continue
        un = e.get("unverified")
        un = un if isinstance(un, str) else ""
        if not un:
            out.append("%s 还没有证据文件，scenarios.json 里它的 unverified 是空的（没跑过的场景要指着一条待办）" % sid)
        elif ids is None:
            out.append("%s 还没有证据文件，可 tests/agent/unverified.json 读不出来（不在，或者里面没有 entries 数组），"
                       "没法确认「%s」这条待办" % (sid, un))
        elif un not in ids:
            out.append("%s 还没有证据文件，可 unverified「%s」不在 unverified.json 里（待办没了，或者名字改了）"
                       % (sid, un))
    return out


def evidence_problems(text, name, unverified_name, unverified_text, now_ms):
    """Everything wrong with one evidence file, as sentences; empty when it is sound. `name` is the id in its own file
    name, `unverified_name` the registry's `unverified` for it (None when the registry has no such scenario)."""
    who = "real-%s.json" % name
    try:
        d = json.loads(text)
    except ValueError:
        return [who + "：读不出来（不是 JSON）"]
    if not isinstance(d, dict):
        return [who + "：不是一个对象"]
    out = []
    eid = d.get("id")
    if not isinstance(eid, str) or not eid:
        out.append(who + "：id 是空的")
    elif eid != name:
        out.append(who + "：id「%s」和自己的文件名不一样（这个文件叫 real-%s.json）" % (eid, name))
    at = d.get("at")
    when = parse_at(at)
    if when is None:
        out.append(who + "：at「%s」不是 ISO 时间（要像 2026-11-12T02:40:11+08:00）" % (at,))
    else:
        now = datetime.fromtimestamp(now_ms / 1000.0).astimezone()
        if (when if when.tzinfo else when.astimezone()) > now:
            out.append(who + "：at「%s」是将来的时间（现在是 %s）" % (at, now.isoformat(timespec="seconds")))
    ok = d.get("ok")
    if not isinstance(ok, bool):
        out.append(who + "：ok「%s」不是 true / false" % (ok,))
    if not isinstance(d.get("notes"), list):
        out.append(who + "：notes 不是数组（看到了什么，要一条一条写下来）")
    if "shots" in d and not isinstance(d.get("shots"), list):
        out.append(who + "：shots 不是数组")
    if unverified_name is None:
        out.append(who + "：scenarios.json 里没有 %s 这个场景（证据文件要有对应的场景）" % name)
    elif ok is False:
        out += link_problems(who, unverified_name, unverified_text)
    return out


def read_shapes(root):
    """The recorded shapes as a dict, or None when this tree has no payload-shapes.json (or it is not a JSON object)."""
    try:
        shapes = json.loads(read(os.path.join(root, *SHAPES)))
    except (TypeError, ValueError):
        return None
    return shapes if isinstance(shapes, dict) else None


def broadcast_keys(shapes, which):
    """The top-level keys that one broadcast really carries, sorted; [] when the fixture has no such broadcast."""
    d = shapes.get(which)
    return sorted(d) if isinstance(d, dict) else []


def reads(text):
    """The (broadcast, key) pairs a scenario reads straight off p.state() / p.memory(), in the order they appear.

    A read through a variable (`s = p.state()` and then `s.get("x")`) is NOT seen and is out of this version's scope:
    this is a text scan, not a parse, and saying which variable holds which broadcast would be guessing. Scanned, not
    exec'd and not imported — the point is to read what the file says, without running anybody's scenario."""
    out = []
    for m in READ_KEY.finditer(text or ""):
        out.append((m.group(1).upper(), m.group(3) if m.group(3) is not None else m.group(5)))
    return out


def shape_problems(root=ROOT):
    """Every key a scenario reads off p.state() / p.memory() that the broadcast does not carry, as sentences; empty
    when each one is really there. Problems, not notices — --strict or not, they count.

    The keys come from tests/agent/fixtures/realpath/payload-shapes.json, the shapes (no content) of the two debug
    broadcasts as captured on the TV. 2026-11-12 22:47: hint-card-not-a-switch read `hints` from STATE, which has never
    carried it — it is in MEMORY — so the scenario read None twice and could not pass however the TV behaved, and the
    registry and the evidence files were both the right shape while that was true.

    Nothing at all comes back when the recording is not in this tree: there is no shape to compare a key against, and
    a rule that could not run is not evidence that a scenario is wrong. shape_missing() says that, once, on every run,
    so it is never silent about it either.

    Reads through a variable are out of scope: see reads()."""
    shapes = read_shapes(root)
    if shapes is None:
        return []
    out, seen = [], set()
    for path in sorted(glob.glob(os.path.join(root, *SCENARIOS, "*.py"))):
        text = read(path)
        if text is None:
            continue                     # a script nobody can read is script_problems' business
        who = os.path.relpath(path, root).replace(os.sep, "/")
        for which, key in reads(text):
            if not key or (who, which, key) in seen:
                continue                 # one sentence per script per key, however often it is read
            seen.add((who, which, key))
            keys = broadcast_keys(shapes, which)
            if key in keys:
                continue
            out.append("%s：从 %s 里读「%s」，可那个广播不带这个键（真机形状里%s）；"
                       "2026-11-12 22:47 就是这么白跑了一次"
                       % (who, which, key, "只有 " + "、".join(keys) if keys else "这一路一个键都没有"))
    return out


def shape_missing(root=ROOT):
    """One line when the shape recording is not in this tree, so the rule above could not run at all; None when it is
    there. Printed on every run and never counted — not a problem and not a --strict notice either: it is about what
    this tree was given, not about a scenario, and the one thing that fixes it (recording the two broadcasts on the TV)
    is not something a run of the checks can do. 2026-11-13: the recording was left untracked in one checkout, so every
    other tree had no shape rule at all; this line is what keeps that visible."""
    return None if read_shapes(root) is not None else SHAPE_UNREADABLE


def rehearse_cases(text):
    """The cases written down in rehearse.py, as (scenario id, case name, the expect word).

    Text scan, nothing exec'd and nothing imported: a scenario's judge is run by rehearse.py, never by this. Only the
    one shape rehearse.py documents — `"scenario"`, `"name"` and `"expect"` next to each other — is seen. A case
    written any other way counts as no case at all: guessing which dict is a case is how a rule stops meaning
    anything, and the fix is to write it the way the file says to."""
    return [(m.group(1), m.group(2), m.group(3)) for m in CASE.finditer(text or "")]


def rehearse_coverage(entries, cases):
    """One sentence per scenario whose judge has not been shown to be able to go red (or to go green), in registry
    order; empty when each one has both sides.

    「至少一条」 is not enough on its own: one `expect: True` case proves the judge says yes, and says nothing about
    whether it can ever say no. Both sides are asked for, once each."""
    out = []
    for e in entries:
        if not isinstance(e, dict) or not isinstance(e.get("id"), str) or not e["id"]:
            continue                      # registry_problems already says what is wrong with this one
        sid = e["id"]
        mine = [c for c in cases if c[0] == sid]
        if not mine:
            out.append("%s 没有离线局面：判据正反两面都没被证明过（tests/agent/realpath/rehearse.py 里加几条："
                       "一条 expect: True、一条 expect: False 或 None）" % sid)
            continue
        words = set(c[2] for c in mine)
        if words - set(TOKENS):
            continue                      # expect 写坏了：rehearse_table_problems 已经说过，别再补一句说错的话
        if "True" not in words:
            out.append("%s 只有「该不过」的局面，没有「该过」的：判据绿的那一面没被证明过"
                       "（tests/agent/realpath/rehearse.py 里加一条 expect: True 的）" % sid)
        elif not words & {"False", "None"}:
            out.append("%s 只有「该过」的局面，没有「该不过」的：判据红的那一面没被证明过"
                       "（tests/agent/realpath/rehearse.py 里加一条 expect: False 或 None 的）" % sid)
    return out


def rehearse_table_problems(text, cases, entries):
    """The case table itself: names have to be unique, `expect` has to be True / False / None, and a case has to point
    at a scenario the registry knows. Problems — a report that cannot name one case is not a report."""
    out, seen, named = [], set(), {}
    for m in CASE.finditer(text or ""):
        named[m.start(3)] = m.group(2)
    for _, name, _ in cases:
        if name in seen:
            out.append("局面名「%s」和前面的一条一样（每条局面要自己的名字，报告里要能点名）" % name)
        seen.add(name)
    for m in EXPECT.finditer(text or ""):
        token = m.group(1)
        if token in TOKENS:
            continue
        who = named.get(m.start(1))
        out.append("有一条局面的 expect 是「%s」%s：只能是 True / False / None"
                   % (token, "（局面「%s」）" % who if who else ""))
    if entries is None:
        return out                        # registry_problems already says the registry cannot be read
    ids = set(e["id"] for e in entries if isinstance(e, dict) and isinstance(e.get("id"), str))
    for sid, name, _ in cases:
        if sid not in ids:
            out.append("局面「%s」指着场景「%s」，可 scenarios.json 里没有这个场景（局面表和注册表得说同一件事）"
                       % (name, sid))
    return out


def rehearse_problems(root=ROOT):
    """Everything wrong with what rehearse.py says about the registry, as sentences; empty when every scenario has both
    sides of its judge proven offline. Problems, not notices — --strict or not, they count.

    rehearse.py missing or unreadable is one sentence and nothing else: a rule that cannot read the file does not get
    to guess which scenario is short of cases. A registry that cannot be read is registry_problems' business, and then
    only the case-table rules here run."""
    text = read(os.path.join(root, *REHEARSE))
    if text is None:
        return [REHEARSE_UNREADABLE]
    cases = rehearse_cases(text)
    reg = read(os.path.join(root, *REGISTRY))
    entries = scenarios(reg) if reg is not None else None
    out = list(rehearse_coverage(entries, cases)) if entries is not None else []
    return out + rehearse_table_problems(text, cases, entries)


def problems(root=ROOT, now_ms=None):
    """Everything wrong with the registry, with every evidence file under it, with what the registry and the ledger
    say about each other (todo_problems), and with the keys the scenarios read off the two debug broadcasts
    (shape_problems), as sentences."""
    text = read(os.path.join(root, "tests", "agent", "realpath", "scenarios.json"))
    if text is None:
        return ["tests/agent/realpath/scenarios.json 不在"]
    out = registry_problems(text, root)
    entries = scenarios(text) or []
    un = {}
    for e in entries:
        if isinstance(e, dict) and isinstance(e.get("id"), str):
            un[e["id"]] = e.get("unverified") if isinstance(e.get("unverified"), str) else ""
    un_text = read(os.path.join(root, "tests", "agent", "unverified.json"))
    ms = now_ms if now_ms is not None else time.time() * 1000
    for path in sorted(glob.glob(os.path.join(root, "tests", "agent", "evidence", "real-*.json"))):
        name = os.path.basename(path)[len("real-"):-len(".json")]
        body = read(path)
        if body is None:
            out.append("real-%s.json 读不出来" % name)
            continue
        out += evidence_problems(body, name, un.get(name), un_text, ms)
    out += todo_problems(root)
    out += shape_problems(root)
    out += rehearse_problems(root)
    return out


def misplaced(root=ROOT):
    """One line per *.json under tests/agent/evidence/realpath/ (however deep): evidence the loop in problems() never
    sees, because that loop only looks at evidence/real-*.json one level down. A notice — problems() does not return
    these, so the tree as it stands is not broken; --strict is what makes them failures."""
    base = os.path.join(root, "tests", "agent", "evidence", "realpath")
    out = []
    for path in glob.glob(os.path.join(base, "**", "*.json"), recursive=True):
        out.append("WHERE %s：放在 evidence/realpath/ 里，检查看不到"
                   "（要放在 evidence/ 根下、文件名 real-<场景 id>.json）" % os.path.basename(path))
    return sorted(out)


def failures(problems_, notices, strict=False):
    """How many things count as wrong: the problems, plus the misplaced evidence when --strict was given."""
    return len(problems_) + (len(notices) if strict else 0)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    unknown = [a for a in argv if a != "--strict"]
    if unknown:
        print("不认识的参数：%s（这个脚本只认 --strict）" % " ".join(unknown))
        return 2
    strict = "--strict" in argv
    out, where = problems(), misplaced()
    made, gone = probe_made(), shape_missing()
    for line in out:
        print(line)
    for line in where:
        print(line)
    for line in made:                  # 提示，不是问题：证据是真的，缺的是重跑一次
        print(line)
    if gone:
        print(gone)
    n = failures(out, where, strict)
    if n:
        print("realpath: %d 处不对%s"
              % (n, "（加 --strict 时不认放错地方的证据）" if strict and where else ""))
        return 1
    print("REALPATH OK")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
