#!/usr/bin/env bash
#
# Regenerate a deployable test channel XML from GenerateChannel.java using the REAL engine model +
# serializer (guaranteeing valid XStream output). Compiles + runs in a temurin-17 container against the
# extracted engine jars + the built plugin jar, so it's reproducible in CI.
#
#   test-harness/generate-channel.sh [FAILOVER|STICKY]   -> writes test-harness/channel-<strategy>.xml
#
# Prereqs: the plugin built (mvn package -> shared/target/tcpmulti-shared-*.jar) and the engine extracted
# (scripts/install-oie-artifacts.sh leaves it at ../_oie-extract/engine; override with OIE_EXTRACT).
set -euo pipefail

STRATEGY="${1:-FAILOVER}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
EX="${OIE_EXTRACT:-$ROOT/../_oie-extract/engine}"
OUTFILE="channel-$(echo "$STRATEGY" | tr '[:upper:]' '[:lower:]').xml"

[ -d "$EX/server-lib" ] || { echo "!! no engine extract at $EX — run scripts/install-oie-artifacts.sh first"; exit 1; }
ls "$ROOT/shared/target/"tcpmulti-shared-*.jar >/dev/null 2>&1 || { echo "!! build the plugin first (mvn package)"; exit 1; }

MSYS_NO_PATHCONV=1 docker run --rm \
  -e STRATEGY="$STRATEGY" -e OUTFILE="$OUTFILE" \
  -v "$EX":/engine:ro -v "$ROOT":/plugin:ro -v "$HERE":/out \
  eclipse-temurin:17-jdk bash -c '
    set -e
    # server-lib has nested dirs (donkey/, commons/, ...) — java -cp wildcards are non-recursive, so build
    # a full colon list of every server-lib jar, plus the connector/datatype extension jars we need.
    CP="$(find /engine/server-lib -name '*.jar' | paste -sd:)"
    CP="$CP:/engine/extensions/vm/*:/engine/extensions/tcp/*:/engine/extensions/datatype-raw/*:/plugin/shared/target/tcpmulti-shared-1.0.0.jar"
    mkdir -p /tmp/gc
    javac -cp "$CP" -d /tmp/gc /plugin/test-harness/GenerateChannel.java
    java  -cp "$CP:/tmp/gc" GenerateChannel "$STRATEGY" > "/out/$OUTFILE"
  '
echo "==> wrote $HERE/$OUTFILE"
