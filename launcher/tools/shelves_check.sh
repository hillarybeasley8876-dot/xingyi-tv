#!/bin/sh
# PC check for Shelves (plain Java, no Android): sh tools/shelves_check.sh
set -e
cd "$(dirname "$0")/.."
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
rm -rf build/shelves-check && mkdir -p build/shelves-check
"$JDK/javac.exe" -encoding UTF-8 -d build/shelves-check src/xyz/erapi0neer/orrery/Shelves.java tools/ShelvesCheck.java
"$JDK/java.exe" -Dfile.encoding=UTF-8 -cp build/shelves-check xyz.erapi0neer.orrery.ShelvesCheck
