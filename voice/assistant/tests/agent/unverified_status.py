"""One line for bridge.sh status: 「还没在真实路径验：3 条（ktcp-pause-real, …）」, or nothing at all when nothing is
waiting. The text comes from Unverified.line(json, 3) in the Java core, through build/agent-core-test — the same
formatter the checks and unverified_doc.py read, so the three cannot drift apart.

The bridge calls it as

    python /h/tools/mitv/voice/assistant/tests/agent/unverified_status.py

and echoes its stdout as a `unverified:` line when it is not empty. It never breaks the caller: a ledger that cannot
be read says so in one line instead of taking `bridge.sh status` down with it.
"""
import os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from unverified_doc import JSON, java        # the same JDK/classpath plumbing, the same Java formatter

MAX = 3


def line():
    try:
        return java('line', JSON, str(MAX)).strip()
    except SystemExit as e:                  # java() 用 SystemExit 报「跑不起来」
        return '还没验的清单读不出来（%s）' % e
    except Exception as e:
        return '还没验的清单读不出来（%s）' % e


if __name__ == '__main__':
    out = line()
    # 写 UTF-8 的字节：bridge 在什么代码页下读它，都不该把这句话变成乱码
    if out:
        sys.stdout.buffer.write((out + '\n').encode('utf-8'))
