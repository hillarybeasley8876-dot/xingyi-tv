"""Local stdio MCP server for the paired Xiaomi TV: lets MCP clients on this PC hand tasks to 星仪's agent.

The agent runs on the TV inside the bridge app (xyz.erapi0neer.xiaoaibridge); this server reaches it over adb
through its DUMP-protected debug receiver, so 星仪's screen does not have to be in front. Requests from here are
silent on the TV (source "mcp"); the progress panel still shows on screen.
"""
import asyncio
import base64
import json
import re
import runpy
import time
from functools import partial
from pathlib import Path
from subprocess import TimeoutExpired

import mcp.types as types
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server

import tv_link


remote = runpy.run_path(str(Path(__file__).resolve().parent.parent / "voice_remote.pyw"))
adb = remote["adb"]
target = remote["TV"]
server = Server("xingyi-tv", version="2.0.0")
DEBUG = "xyz.erapi0neer.xiaoaibridge/xyz.erapi0neer.xingyi.agent.tv.AgentDebug"
FILES = "/sdcard/Android/data/xyz.erapi0neer.xiaoaibridge/files"
lock = asyncio.Lock()


def tool(name, description, fields, required, read):
    return types.Tool(
        name=name, description=description,
        inputSchema={"type": "object", "properties": fields, "required": required, "additionalProperties": False},
        annotations=types.ToolAnnotations(readOnlyHint=read, destructiveHint=False, idempotentHint=read,
                                         openWorldHint=not read),
    )


@server.list_tools()
async def list_tools():
    return [
        tool("tv_do", "Ask 星仪, the agent on the user's Xiaomi TV, to do something on the TV (open apps, search, play "
             "music, change settings, read the screen, answer questions about what is on it). Write the request the "
             "way the user would say it, in Chinese. Waits for the outcome and returns the plan, the actions that "
             "actually ran and 星仪's reply. If it returns awaiting=consent, ask the user and call tv_consent; if "
             "awaiting=answer, 星仪 asked a question: call tv_do again with the user's answer.",
             {"request": {"type": "string", "minLength": 1, "maxLength": 500},
              "wait_seconds": {"type": "integer", "minimum": 5, "maximum": 150}}, ["request"], False),
        tool("tv_state", "What 星仪 is doing now, whether it waits for consent or an answer, and the latest task.",
             {}, [], True),
        tool("tv_screen", "What is on the TV screen right now, as the agent reads it (app, page, numbered elements).",
             {}, [], True),
        tool("tv_consent", "Answer 星仪's pending consent request (approve=true to go ahead). Only with the user's say-so.",
             {"approve": {"type": "boolean"}}, ["approve"], False),
        tool("tv_cancel", "Stop the task 星仪 is running.", {}, [], False),
        tool("tv_status", "Read the TV's actual media volume and range without touching the screen. When the TV "
             "cannot be reached it still answers (connected=false) and says why: TV off the network, adb debugging "
             "switched off on the TV, not authorised, or the TV's address changed.", {}, [], True),
    ]


def shell(*args, timeout, retry=True):
    """adb shell on the TV. A failed command gets the link checked (one reconnect, or a TvLinkError saying why) and,
    for commands that are safe to repeat, one more try. Commands that must not run twice pass retry=False."""
    code, text = adb("-s", target, "shell", *args, timeout=timeout)
    if code and retry:
        tv_link.ensure_link(adb, target)
        code, text = adb("-s", target, "shell", *args, timeout=timeout)
    return code, text


def broadcast(action, *extra, once=False):
    """once=True for requests with effects (submit, consent): the link is checked before sending, never after a
    failed send, so a request is not delivered twice."""
    if once:
        tv_link.ensure_link(adb, target)
    code, text = shell("am", "broadcast", "-n", DEBUG, "-a", f"xyz.erapi0neer.xingyi.agent.{action}", *extra,
                       timeout=20, retry=not once)
    m = re.search(r'data="(.*)"\s*$', text.strip(), re.S)
    if code or not m:
        if once:
            tv_link.ensure_link(adb, target)  # a dead link or just a restarting runtime?
        raise RuntimeError("星仪后台没有响应（电视离线，或后台正在被系统重启）。")
    return json.loads(m.group(1))


def summary(state):
    task = state.get("task") or {}
    out = {"busy": state.get("busy"), "awaiting": state.get("awaiting") or None,
           "prompt": state.get("prompt") or None, "state": task.get("state"), "request": task.get("utterance"),
           "goal": task.get("goal") or None, "reply": task.get("last_reply") or None}
    if task.get("steps"):
        out["steps"] = [f"[{s.get('status')}] {s.get('text')}" for s in task["steps"]]
    if task.get("actions"):
        out["actions"] = [f"{'✓' if a.get('ok') else '✗'} {a.get('what')} ({a.get('status')})" for a in task["actions"]]
    return {k: v for k, v in out.items() if v is not None}


def do(request, wait):
    before = (broadcast("STATE")["state"].get("task") or {}).get("id")
    first = broadcast("SUBMIT", "--es", "text_b64", base64.b64encode(request.encode()).decode(), "--es", "source", "mcp",
                      once=True)
    routed, request = first.get("result"), first.get("request", -1)
    if routed in ("approved", "declined", "cancelled", "idle", "side"):
        return {"routed": routed, **summary(first["state"])}
    end, state = time.time() + wait, first["state"]
    while time.time() < end:
        time.sleep(1.0)
        try:
            state = broadcast("STATE")["state"]
        except tv_link.TvLinkError as error:
            raise tv_link.TvLinkError(f"{error} 任务可能仍在电视上执行；恢复连接后用 tv_state 查看，不要重复提交。",
                                      **error.data) from error
        except RuntimeError:
            continue  # the runtime may be restarting after a low-memory kill; it resumes the task itself
        task = state.get("task") or {}
        if isinstance(request, int) and request >= 0 and "request" in state:
            # the runtime tags the shown task with its request: wait for ours, not for whichever task came next
            if state["request"] == request and (state.get("awaiting") or not state.get("busy")):
                break
        elif state.get("awaiting") or (not state.get("busy") and task.get("id") not in (None, before)):
            break
    out = summary(state)
    if state.get("busy") and not state.get("awaiting"):
        out["note"] = "还在执行，稍后用 tv_state 查看结果；不要重复提交。"
    return out


def screen():
    broadcast("SCREEN")
    code, text = shell("cat", f"{FILES}/screen.txt", timeout=10)
    if code:
        raise RuntimeError("没有读到电视屏幕。")
    return {"screen": text}


def read_volume():
    """The volume when adb answers; when the link is down, connected=false with the cause (not an error: asking
    whether the TV can be reached is what this tool is for)."""
    try:
        code, text = shell("media", "volume", "--stream", "3", "--get", timeout=8)
    except tv_link.TvLinkError as error:
        return {"connected": False, "message": str(error), **error.data}
    reading = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", text)
    if code or not reading:
        raise RuntimeError("电视没有返回可核对的媒体音量。")
    volume, minimum, maximum = map(int, reading.groups())
    return {"connected": True, "volume": volume, "minimum": minimum, "maximum": maximum}


def result(data, error=False):
    return types.CallToolResult(content=[types.TextContent(type="text", text=json.dumps(data, ensure_ascii=False))],
                                structuredContent=data, isError=error)


@server.call_tool()
async def call_tool(name, arguments):
    try:
        if name == "tv_do":
            async with lock:
                wait = int(arguments.get("wait_seconds") or 90)
                return result(await asyncio.to_thread(do, arguments["request"].strip(), wait))
        if name == "tv_state":
            return result(summary((await asyncio.to_thread(broadcast, "STATE"))["state"]))
        if name == "tv_screen":
            return result(await asyncio.to_thread(screen))
        if name == "tv_consent":
            state = await asyncio.to_thread(partial(broadcast, "CONSENT", "--ez", "approve",
                                                    "true" if arguments["approve"] else "false", once=True))
            return result(summary(state["state"]))
        if name == "tv_cancel":
            return result(summary((await asyncio.to_thread(broadcast, "CANCEL"))["state"]))
        if name == "tv_status":
            return result(await asyncio.to_thread(read_volume))
        return result({"message": "未知工具。"}, True)
    except tv_link.TvLinkError as error:
        return result({"message": str(error), **error.data}, True)
    except (OSError, TimeoutExpired):
        return result({"message": "与电视通信中断，操作结果未知，请先用 tv_state 查看。"}, True)
    except (RuntimeError, ValueError, KeyError) as error:
        return result({"message": str(error)}, True)


async def main():
    async with stdio_server() as (reader, writer):
        await server.run(reader, writer, server.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
