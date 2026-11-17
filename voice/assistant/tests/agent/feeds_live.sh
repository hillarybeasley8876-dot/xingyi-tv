#!/bin/sh
# Live check of every public data feed the tools read, fetched with the TV's own fetcher (Feeds.https()) and read by the
# real parsers. Needs the network; run before installing a build that touches the feeds, and now and then (the
# services change). Prints "FEEDS LIVE OK" when every feed answered and parsed.
set -eu
cd "$(dirname "$0")/../.."
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
OUT=build/feeds-live
JSON=tests/lib/json-20240303.jar
rm -rf "$OUT"
mkdir -p "$OUT"
"$JDK/javac.exe" -J-XX:ActiveProcessorCount=2 --release 8 -encoding UTF-8 -nowarn -Xlint:-options -cp "$JSON" -d "$OUT" \
    src/xyz/erapi0neer/xingyi/agent/*.java tests/agent/FeedsLive.java
# IPv4 only: this PC's IPv6 route times out for some of the hosts (open.er-api.com), which Java SE does not fall back
# from; the TV's Android stack does
"$JDK/java.exe" -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT;$JSON" xyz.erapi0neer.xingyi.agent.FeedsLive
