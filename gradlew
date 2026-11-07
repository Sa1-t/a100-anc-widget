#!/bin/sh
# Thin launcher for this project: uses the Gradle 8.7 distribution bundled in
# ./gradle-dist (downloaded with curl) and the JDK 17 in ./jdk17, so no
# download is needed at build time. The stock Gradle wrapper downloader cannot
# get through this machine's egress proxy (Java HttpURLConnection fails the
# CONNECT tunnel), which is why the wrapper jar is not used here.
set -e
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export JAVA_HOME="$DIR/jdk17"
export ANDROID_HOME="$DIR/android-sdk"
# Proxy for Gradle's dependency downloads; password is taken from the current
# environment on every run (it rotates per shell session).
PASS=$(printf '%s' "$http_proxy" | sed -n 's|.*://[^:]*:\([^@]*\)@.*|\1|p')
if [ -n "$PASS" ]; then
  export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Dhttp.proxyHost=hatch-egress-proxy -Dhttps.proxyHost=hatch-egress-proxy -Dhttp.proxyPort=3128 -Dhttps.proxyPort=3128 -Dhttp.proxyUser=hatch-runtime -Dhttps.proxyUser=hatch-runtime -Dhttp.proxyPassword=$PASS -Dhttps.proxyPassword=$PASS"
fi
exec "$DIR/gradle-dist/bin/gradle" --no-daemon "$@"
