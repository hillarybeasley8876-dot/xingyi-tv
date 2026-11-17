"""The last few tasks' timings from the TV (state().perf_recent): wall, rounds, first round, when the answer first showed
and was first spoken. python tests/agent/perf_recent.py [n]"""
import json, sys, os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tvtask import broadcast  # noqa: E402


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    st = broadcast("STATE")["state"]
    for p in st.get("perf_recent", [])[-n:]:
        print("%-28s quick=%-5s wall=%6d rounds=%d first_round=%5d shown=%6s spoken=%6s slowest=%s %d" % (
            p.get("said", "")[:28], p.get("quick"), p.get("wall_ms", 0), p.get("rounds", 0), p.get("first_round_ms", 0),
            p.get("first_shown_ms", "-"), p.get("first_speech_ms", "-"), p.get("slowest_tool", ""), p.get("slowest_tool_ms", 0)))


if __name__ == "__main__":
    main()
