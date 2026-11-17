#!/bin/sh
# Acceptance for job web-search-core: the API the lead wires against exists verbatim, and the core stays free of android.*.
cd "$(dirname "$0")/../.."
W=src/xyz/erapi0neer/xingyi/agent/WebSearch.java
R=src/xyz/erapi0neer/xingyi/agent/ResponsesText.java
ok=1
for line in 'public Result search(String query, String now, int timeoutMs) throws IOException' \
            'public static Transport https()' \
            'public interface Transport' \
            'public static final String DEFAULT_MODEL = "gpt-5.4-mini";' \
            'public WebSearch(String baseUrl, String apiKey, String model, Transport transport)'; do
    grep -q -F "$line" "$W" 2>/dev/null || { echo "missing in WebSearch.java: $line"; ok=0; }
done
for line in 'package xyz.erapi0neer.xingyi.agent;' 'public static String extract(String json)' 'public static boolean usedSearch(String json)'; do
    grep -q -F "$line" "$R" 2>/dev/null || { echo "missing in ResponsesText.java: $line"; ok=0; }
done
if grep -n 'import android' "$W" "$R" 2>/dev/null; then echo "android import in the core package"; ok=0; fi
[ $ok = 1 ] && echo API_OK
