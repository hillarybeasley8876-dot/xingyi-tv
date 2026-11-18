"""Drive one 星仪 agent task on the TV through the DUMP-protected debug receiver and record what happened.

python tests/agent/tvtask.py "打开B站搜索星辰亻旅行者" [--source test] [--name bili] [--timeout 150]
source=test (the default) stays silent and leaves no trace in 星仪's memory (recent turns, history, habits);
debug is read aloud by XiaoAi and leaves no trace either; mcp/ui stay silent but are remembered like the user's own.
"""
import argparse, base64, json, os, re, subprocess, sys, time
from pathlib import Path

ADB = [r"H:\tools\platform-tools\adb.exe", "-s", "192.0.2.10:5555"]
RECEIVER = "xyz.erapi0neer.xiaoaibridge/xyz.erapi0neer.xingyi.agent.tv.AgentDebug"
EVIDENCE = Path(__file__).with_name("evidence")


def broadcast(action, *extra):
    cmd = ADB + ["shell", "am", "broadcast", "-n", RECEIVER, "-a", f"xyz.erapi0neer.xingyi.agent.{action}", *extra]
    out = subprocess.run(cmd, capture_output=True, timeout=30).stdout.decode("utf-8", "replace")
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    if not m:
        raise RuntimeError(f"no result data: {out[:300]}")
    return json.loads(m.group(1))


def show(task):
    if not task:
        return "(no task)"
    lines = [f"state={task.get('state')}  goal={task.get('goal', '')}"]
    for i, s in enumerate(task.get("steps", []), 1):
        lines.append(f"  {i}. [{s.get('status')}] {s.get('text')}" + (f"  ({s['note']})" if s.get("note") else ""))
    for a in task.get("actions", []):
        lines.append(f"  - {a.get('tool')}: {a.get('what')} -> {a.get('status')}{'' if a.get('ok') else ' ✗'}")
    if task.get("question_asked"):
        lines.append(f"  asked: {task['question_asked']}")
    lines.append(f"  reply: {task.get('last_reply', '')}")
    if task.get("error"):
        lines.append(f"  error: {task['error']}")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("text")
    ap.add_argument("--source", default="test")
    ap.add_argument("--name", default="")
    ap.add_argument("--timeout", type=float, default=150)
    a = ap.parse_args()
    before = (broadcast("STATE")["state"].get("task") or {}).get("id")
    t0 = time.time()
    first = broadcast("SUBMIT", "--es", "text_b64", base64.b64encode(a.text.encode()).decode(), "--es", "source", a.source)
    request = first.get("request", -1)
    print(f"submit -> {first['result']}" + (f" (request {request})" if isinstance(request, int) and request >= 0 else ""), flush=True)
    seen_busy, last_line, state = False, "", first["state"]
    killed_at, ids = None, []
    while time.time() - t0 < a.timeout:
        time.sleep(1.0)
        try:
            state = broadcast("STATE")["state"]
        except (RuntimeError, ValueError, subprocess.TimeoutExpired):
            print(f"{time.time() - t0:5.1f}s (no state: runtime process restarting)", flush=True)
            killed_at = killed_at or time.time()
            continue
        task = state.get("task") or {}
        if task.get("id") and task.get("id") not in ids and task.get("id") != before:
            ids.append(task["id"])
        if str(task.get("last_reply", "")).startswith("（被系统内存清理打断"):
            killed_at = killed_at or time.time()
        line = f"{time.time() - t0:5.1f}s busy={state['busy']} awaiting={state['awaiting'] or '-'} state={task.get('state')} steps={len(task.get('steps', []))} actions={len(task.get('actions', []))}"
        if line != last_line:
            print(line, flush=True)
            last_line = line
        seen_busy |= state["busy"]
        # the runtime tags the shown task with its request id: wait for ours, not for whichever task came next
        ours = state.get("request") == request if isinstance(request, int) and request >= 0 and "request" in state else True
        if ours and not state["busy"] and (seen_busy or task.get("id") not in (None, before)):
            if killed_at and time.time() - killed_at < 75 and task.get("state") == "interrupted":
                continue  # the runtime was killed; wait for it to come back and resume
            break
        if state["awaiting"] == "consent":
            print(f"  waiting for consent: {state['prompt']}", flush=True)
    elapsed = time.time() - t0
    print(f"--- {elapsed:.1f}s, eyes={state.get('eyes')}, tasks={ids}" + (" (runtime was killed and resumed)" if killed_at else ""))
    print(show(state.get("task")))
    if a.name:
        EVIDENCE.mkdir(exist_ok=True)
        (EVIDENCE / f"{a.name}.json").write_text(json.dumps({"utterance": a.text, "source": a.source, "elapsed_s": round(elapsed, 1), "task_ids": ids, "killed": bool(killed_at),
                                                             "state": state}, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
