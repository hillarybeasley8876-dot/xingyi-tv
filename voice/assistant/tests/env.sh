# Where the tools are, for the offline checks. Sourced by run_core.sh, android_check.sh, route/route_check.sh and the
# relay's tests/run_all.sh, never run on its own. The defaults are this project's own Windows machine (Git Bash);
# anywhere else set JAVA_HOME (a JDK 17), and ANDROID_JAR (or ANDROID_HOME / ANDROID_SDK) and NODE when needed.
#
#   SEP          the class path separator: ';' on Windows, ':' elsewhere
#   P path       the path as the JVM wants it (the Windows form under Git Bash, unchanged elsewhere)
#   JDK          the JDK's bin directory (javac, java)
#   ANDROID_JAR  android.jar of API 35, for the checks that compile against Android (need_android says so if missing)
#   NODE         node, v22 or later (on this machine a pinned 24.22.2: 24.15 crashed reading a stream)
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) XINGYI_WINDOWS=1 ;;
    *) XINGYI_WINDOWS=0 ;;
esac
if [ "$XINGYI_WINDOWS" = 1 ]; then
    SEP=';'
    P() { cygpath -w "$1"; }
    # JAVA_HOME may be a Windows path with a trailing backslash (it is on this machine)
    _xingyi_jh=$(cygpath -u "${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}")
else
    SEP=':'
    P() { printf '%s' "$1"; }
    _xingyi_jh=${JAVA_HOME:-}
fi
if [ -n "$_xingyi_jh" ]; then
    JDK="${_xingyi_jh%/}/bin"
else
    JDK=$(dirname "$(command -v javac || echo ./javac)")
fi
if [ -z "${ANDROID_JAR:-}" ]; then
    for _xingyi_jar in "${ANDROID_SDK:-/h/tools/android-sdk}/android-35/android.jar" \
            "${ANDROID_HOME:-/nonexistent}/platforms/android-35/android.jar" \
            "${ANDROID_SDK_ROOT:-/nonexistent}/platforms/android-35/android.jar"; do
        if [ -f "$_xingyi_jar" ]; then ANDROID_JAR=$_xingyi_jar; break; fi
    done
fi
ANDROID_JAR=${ANDROID_JAR:-}
if [ -z "${NODE:-}" ]; then
    NODE=/h/tools/node/node-v24.22.2-win-x64/node.exe
    [ -x "$NODE" ] || NODE=node
fi
need_android() {
    if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
        echo "android.jar (API 35) not found: set ANDROID_JAR, or ANDROID_HOME with platforms/android-35" >&2
        exit 1
    fi
}
