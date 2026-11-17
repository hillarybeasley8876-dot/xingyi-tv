"""Run a command at below-normal CPU priority (children inherit it): python lowprio.py <program> [args...]"""
import subprocess, sys
sys.exit(subprocess.call(sys.argv[1:], creationflags=0x00004000))  # BELOW_NORMAL_PRIORITY_CLASS
