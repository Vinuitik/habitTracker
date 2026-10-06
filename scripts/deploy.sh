#!/usr/bin/env bash
# Rebuilds and restarts javaapp from the working tree, then waits for its healthcheck.
# sw.js VERSION is stamped with the git SHA for the build only, so every deploy makes the
# service worker byte-different and installed PWAs pick it up (registerSW.js checks on load/focus).
set -euo pipefail
cd "$(dirname "$0")/.."

SW=habitTracker/src/main/resources/static/sw.js
SHA=$(git rev-parse --short HEAD)
HEALTH_TIMEOUT_S=120

export HOST_TIMEZONE="${HOST_TIMEZONE:-$(cat /etc/timezone 2>/dev/null || echo UTC)}"

cp "$SW" "$SW.bak"
trap 'mv -f "$SW.bak" "$SW"' EXIT
sed -i "s/^const VERSION = '[^']*'/const VERSION = '$SHA'/" "$SW"

echo "deploy: building javaapp @ $SHA"
docker compose up -d --build javaapp

echo "deploy: waiting for javaapp to be healthy"
for ((i = 0; i < HEALTH_TIMEOUT_S; i += 2)); do
  status=$(docker inspect -f '{{.State.Health.Status}}' javaapp 2>/dev/null || echo missing)
  [ "$status" = healthy ] && { echo "deploy: OK ($SHA)"; exit 0; }
  sleep 2
done
echo "deploy: FAILED — javaapp not healthy after ${HEALTH_TIMEOUT_S}s (status: $status). Check: docker compose logs javaapp" >&2
exit 1
