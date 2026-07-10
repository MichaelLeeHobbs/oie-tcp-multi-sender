#!/usr/bin/env bash
#
# Install the OIE engine jars this plugin compiles against into the local Maven repo.
#
# WHY: OIE/Mirth do not publish a complete set of Maven artifacts. The core jars exist on a community
# repo, but the connector we subclass (com.mirth.connect.connectors.tcp.*) is built as a *separate
# extension jar* that is NOT on any Maven repo. The authoritative source for ALL of them — at the exact
# OIE version — is the official Docker image, so we extract them from there and `install:install-file`.
#
# Usage:  scripts/install-oie-artifacts.sh [IMAGE] [MC_VERSION]
#   IMAGE       default: openintegrationengine/engine:latest
#   MC_VERSION  Maven version to install the jars under; must match <mc.version> in pom.xml (default 4.5.2)
#
# Requires: docker, mvn, (java 17).
set -euo pipefail

IMAGE="${1:-openintegrationengine/engine:latest}"
MC_VERSION="${2:-4.5.2}"
GROUP="com.mirth.connect"

echo "==> Extracting OIE jars from image: $IMAGE  (installing as $GROUP:*:$MC_VERSION)"
docker pull "$IMAGE"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cid="$(docker create "$IMAGE")"
# The install root inside the OIE image is /opt/engine (see engine-docker README: appdata is /opt/engine/appdata).
docker cp "$cid:/opt/engine" "$work/engine"
docker rm -f "$cid" >/dev/null

root="$work/engine"
[ -d "$root" ] || { echo "ERROR: /opt/engine not found in image; adjust the path in this script."; exit 1; }

# jarPattern -> artifactId. Core jars live under server-lib/client-lib; the TCP connector jars live under
# extensions/tcp/. We locate each by name so exact paths/versions don't matter.
declare -A ARTIFACTS=(
  ["mirth-server.jar"]="mirth-server"
  ["donkey-server.jar"]="donkey-server"
  ["mirth-client.jar"]="mirth-client"
  ["mirth-client-core.jar"]="mirth-client-core"
  ["tcp-server.jar"]="tcp-server"
  ["tcp-shared.jar"]="tcp-shared"
  ["tcp-client.jar"]="tcp-client"
)

missing=0
for pattern in "${!ARTIFACTS[@]}"; do
  artifact="${ARTIFACTS[$pattern]}"
  jar="$(find "$root" -type f -name "$pattern" | head -1 || true)"
  if [ -z "$jar" ]; then
    echo "  !! MISSING: $pattern  (no file matched under $root)"
    missing=1
    continue
  fi
  echo "  -> $artifact  <=  ${jar#$root/}"
  mvn -q -B org.apache.maven.plugins:maven-install-plugin:3.1.1:install-file \
    -Dfile="$jar" -DgroupId="$GROUP" -DartifactId="$artifact" -Dversion="$MC_VERSION" -Dpackaging=jar
done

if [ "$missing" -ne 0 ]; then
  echo ""
  echo "ERROR: one or more jars were not found. The OIE image layout may differ from the assumed names."
  echo "       List candidates with:  find '$root' -name '*.jar' | sort"
  echo "       Then update the ARTIFACTS map (or names) above."
  exit 1
fi

echo "==> Done. Installed core + TCP-connector jars for $GROUP at version $MC_VERSION."
