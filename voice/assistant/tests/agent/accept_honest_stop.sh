#!/bin/sh
# Acceptance for job honest-stop: the API the lead wires against exists verbatim.
cd "$(dirname "$0")/../.."
A=src/xyz/erapi0neer/xingyi/agent
ok=1
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check Tool.java 'void say(String text);'
check AgentLoop.java 'public interface Observer'
check AgentLoop.java 'String now() throws Exception;'
check AgentLoop.java 'public void observe(Observer o)'
check AgentLoop.java 'rounds = 20, calls = 36, failures = 3'
check Ledger.java 'void action(String tool, String what, String status, boolean ok, boolean read)'
[ $ok = 1 ] && echo API_OK
