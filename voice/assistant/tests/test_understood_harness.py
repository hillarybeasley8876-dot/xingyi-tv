"""Exercises production AgentHarness with a scripted model and a counting tool runtime, offline."""
from pathlib import Path

from test_confirmed_harness import run_host_case


if __name__ == "__main__":
    source = Path(__file__).with_name("UnderstoodHarnessTest.java")
    run_host_case("UnderstoodHarnessTest", source.read_text(encoding="utf-8"))
