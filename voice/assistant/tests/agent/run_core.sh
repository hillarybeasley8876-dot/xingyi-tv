#!/bin/sh
# Offline checks for the agent core (pure Java + org.json). Usage: sh tests/agent/run_core.sh
set -eu
cd "$(dirname "$0")/../.."
. tests/env.sh   # JDK, NODE, SEP: this machine by default, JAVA_HOME / NODE elsewhere
OUT=build/agent-core-test
JSON=tests/lib/json-20240303.jar
rm -rf "$OUT"
mkdir -p "$OUT"
"$JDK/javac" -J-XX:ActiveProcessorCount=2 --release 8 -encoding UTF-8 -nowarn -Xlint:-options -cp "$JSON" -d "$OUT" \
    src/xyz/erapi0neer/xingyi/agent/*.java tests/agent/*.java
# the phone page's thread merge (PhoneThread in assets/agent/phone.html): node runs the page's own script; first, so
# the last line stays the Java checks' count that the install gate reads
"$NODE" tests/agent/phone_thread_test.mjs
"$JDK/java" -XX:ActiveProcessorCount=2 -cp "$OUT$SEP$JSON" xyz.erapi0neer.xingyi.agent.AgentCoreTest
