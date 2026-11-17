#!/bin/sh
# Acceptance for job memory: the pure API exists and stays free of android.*. Prints "MEMORY OK".
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/src/xyz/erapi0neer/xingyi/agent"
ok=1
check() { grep -q -F "$2" "$A/$1" 2>/dev/null || { echo "missing in $1: $2"; ok=0; }; }
check Memory.java 'public static String addFact(JSONObject profile, String fact)'
check Memory.java 'public static List<String> forget(JSONObject profile, String what)'
check Memory.java 'public static String addAlias(JSONObject profile, String heard, String written)'
check MemoryTools.java 'public interface Store'
check MemoryTools.java 'public static List<Tool> create(Store store)'
for t in '"remember"' '"forget"' '"remember_name"'; do check MemoryTools.java "$t"; done
for f in Memory.java MemoryTools.java; do grep -q "android\." "$A/$f" && { echo "$f must stay pure (no android.*)"; ok=0; }; done
[ $(wc -l < "$A/MemoryTools.java") -le 150 ] || { echo "MemoryTools.java over 150 lines"; ok=0; }
[ $ok = 1 ] && echo MEMORY OK
