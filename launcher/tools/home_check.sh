#!/bin/sh
# PC check for HomeLayout (plain Java, no Android): sh tools/home_check.sh
set -e
cd "$(dirname "$0")/.."
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
rm -rf build/home-check && mkdir -p build/home-check
"$JDK/javac.exe" -encoding UTF-8 -d build/home-check src/xyz/erapi0neer/orrery/HomeLayout.java tools/HomeLayoutCheck.java
"$JDK/java.exe" -Dfile.encoding=UTF-8 -cp build/home-check xyz.erapi0neer.orrery.HomeLayoutCheck
