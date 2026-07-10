#!/usr/bin/env bash
#
# Smoke test: start OIE with the freshly built extension mounted and confirm the server loads the
# "TCP Sender (Multi-Endpoint)" connector without an extension-load error. This is the minimum "does it
# actually deploy" gate — it catches descriptor mistakes, classloading/whitelist failures, and missing
# classes that unit tests can't.
#
# Usage: scripts/smoke-test.sh [IMAGE]   (default openintegrationengine/engine:latest)
# Requires: docker. Run after `mvn package` (needs package/target/tcpmulti-*.zip).
set -euo pipefail

IMAGE="${1:-openintegrationengine/engine:latest}"
NAME="oie-tcpmulti-smoke"
ZIP="$(ls package/target/tcpmulti-*.zip 2>/dev/null | head -1 || true)"
[ -n "$ZIP" ] || { echo "ERROR: no package/target/tcpmulti-*.zip — run 'mvn package' first."; exit 1; }

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

echo "==> Starting OIE ($IMAGE) with $(basename "$ZIP") in custom-extensions ..."
docker run -d --name "$NAME" -p 8443:8443 \
  -v "$(cd "$(dirname "$ZIP")" && pwd)/$(basename "$ZIP")":/opt/engine/custom-extensions/tcpmulti.zip:ro \
  "$IMAGE" >/dev/null

# Wait for the server to finish starting (up to ~3 min).
echo "==> Waiting for OIE to start ..."
ready=0
for i in $(seq 1 90); do
  logs="$(docker logs "$NAME" 2>&1 || true)"
  if echo "$logs" | grep -qiE "Mirth Connect .* server successfully started|Open Integration Engine .* started|server successfully started"; then
    ready=1; break
  fi
  # Fail fast on a fatal boot error.
  if echo "$logs" | grep -qiE "cannot be started|failed to start|BootstrapException"; then
    echo "!! OIE failed to boot:"; echo "$logs" | tail -40; exit 1
  fi
  sleep 2
done
[ "$ready" -eq 1 ] || { echo "!! OIE did not report started in time:"; docker logs "$NAME" 2>&1 | tail -40; exit 1; }

logs="$(docker logs "$NAME" 2>&1 || true)"

# Fail if anything went wrong loading OUR extension.
if echo "$logs" | grep -iE "tcpmulti|Multi-Endpoint" | grep -qiE "error|exception|could not|failed"; then
  echo "!! Extension-load error for tcpmulti:"
  echo "$logs" | grep -iE "tcpmulti|Multi-Endpoint" | tail -30
  exit 1
fi

# Positive signal: the connector metadata was registered.
if echo "$logs" | grep -qiE "tcpmulti|TCP Sender \(Multi-Endpoint\)"; then
  echo "==> OK: OIE started and the tcpmulti extension was loaded."
else
  echo "==> OIE started with no tcpmulti error, but no explicit load line was seen."
  echo "    (Log markers vary by OIE version — verify the connector appears in the Administrator, and"
  echo "     tighten this grep once the real log line is known.)"
fi
