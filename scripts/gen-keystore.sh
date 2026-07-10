#!/usr/bin/env bash
#
# Generate the local self-signed dev keystore used by `mvn -Psigning` (so the Administrator Launcher will
# load the extension — see docs/BUILD.md). The keystore holds a private key and is **gitignored — never
# commit it**; regenerate it anywhere with this script.
#
# Idempotent: does nothing if the keystore already exists. Needs `keytool` (any JDK); falls back to Docker.
set -euo pipefail

KS="${1:-certificate/keystore.jks}"
ALIAS="${KEYSTORE_ALIAS:-selfsigned}"
PASS="${KEYSTORE_PASS:-storepass}"
DNAME="CN=OIE Multi-Endpoint TCP Sender (dev self-signed), OU=dev, O=oie-contrib, C=US"

if [ -f "$KS" ]; then echo "keystore already exists: $KS (delete it to regenerate)"; exit 0; fi
mkdir -p "$(dirname "$KS")"

if command -v keytool >/dev/null 2>&1; then
  keytool -genkeypair -keyalg RSA -keysize 2048 -alias "$ALIAS" \
    -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -validity 3650 -storetype JKS -dname "$DNAME"
else
  echo "keytool not found — generating via Docker (eclipse-temurin:17-jdk) ..."
  MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd)":/work -w /work eclipse-temurin:17-jdk \
    keytool -genkeypair -keyalg RSA -keysize 2048 -alias "$ALIAS" \
      -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -validity 3650 -storetype JKS -dname "$DNAME"
fi
echo "generated $KS (dev self-signed; gitignored)"
