#!/bin/sh
# Acceptance for job transcript-ring: the bridge builds with this tree's sources and its JVM suite passes against them;
# the engine keeps a ring of real tasks' transcripts and the debug receiver exports it. Builds into the bridge's
# build-wt-transcript-ring/ so parallel jobs do not collide. Prints "TRANSCRIPT RING OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
BRIDGE=/h/tools/mitv/xiaoai-bridge
mkdir -p "$ROOT/build"
ok=1
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-transcript-ring sh "$BRIDGE/build.sh" > "$ROOT/build/accept-bridge-build.log" 2>&1; then
    echo "bridge build FAILED:"; grep -v -E "^(注|ע):" "$ROOT/build/accept-bridge-build.log" | tail -25; ok=0
fi
if ! XINGYI="$ROOT" BRIDGE_BUILD=build-wt-transcript-ring sh "$BRIDGE/tests/run_all.sh" > "$ROOT/build/accept-bridge-jvm.log" 2>&1; then
    echo "bridge JVM suite FAILED:"; grep -E "FAIL|error|错误" "$ROOT/build/accept-bridge-jvm.log" | head -25; ok=0
fi
has() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
has TranscriptRing.java 'public static String fileName(long startedAt, String id)'
has TranscriptRing.java 'public static List<String> toDelete(Map<String, Long> sizes, int maxCount, long maxBytes)'
has TranscriptRing.java 'public static JSONObject entry(Ledger t, JSONArray messages, long wallMs)'
has tv/AgentEngine.java 'saveRing('
has tv/AgentEngine.java 'TranscriptRing.'
has tv/AgentDebug.java '.TRANSCRIPTS'
has tv/AgentDebug.java '"back"'
grep -q 'ring()' "$ROOT/tests/agent/PerfChecks.java" || { echo "PerfChecks has no ring() group"; ok=0; }
grep -q "import android\." "$A/TranscriptRing.java" && { echo "TranscriptRing.java must stay pure (no android.*)"; ok=0; }
[ $ok = 1 ] && echo TRANSCRIPT RING OK
