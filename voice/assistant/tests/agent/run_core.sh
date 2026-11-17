#!/bin/sh
# Offline checks for the agent core (pure Java + org.json). Usage: sh tests/agent/run_core.sh
set -eu
cd "$(dirname "$0")/../.."
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
OUT=build/agent-core-test
JSON=tests/lib/json-20240303.jar
rm -rf "$OUT"
mkdir -p "$OUT"
"$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 --release 8 -encoding UTF-8 -nowarn -Xlint:-options -cp "$JSON" -d "$OUT" \
    src/xyz/erapi0neer/xingyi/agent/*.java tests/agent/*.java
# the phone page's thread merge (PhoneThread in assets/agent/phone.html): node runs the page's own script; first, so
# the last line stays the Java checks' count that the install gate reads
/h/tools/node/node-v24.22.2-win-x64/node.exe tests/agent/phone_thread_test.mjs
"$JDK/java.exe" -XX:ActiveProcessorCount=2 -cp "$OUT;$JSON" xyz.erapi0neer.xingyi.agent.AgentCoreTest
