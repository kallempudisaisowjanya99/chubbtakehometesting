#!/usr/bin/env bash
# Runs a k6 script in Docker (no local k6 install needed) against the running stack.
#
#   ./run.sh [script] [profile]      e.g.  ./run.sh claims-journey.js load
#
# Profiles: smoke (default), load, stress, soak. See lib/profiles.js.
# Results: printed summary, plus results/<script>-<profile>.json.
# Override the target with BASE_URL (default: the BFF on the host, port 8090).
set -euo pipefail

SCRIPT="${1:-claims-journey.js}"
PROFILE="${2:-smoke}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
BASE_URL="${BASE_URL:-http://host.docker.internal:8090}"

cd "$(dirname "$0")"
mkdir -p results

# Git Bash on Windows: give Docker a Windows path and stop MSYS rewriting container paths.
HOST_DIR="$(pwd)"
if command -v cygpath >/dev/null 2>&1; then
  HOST_DIR="$(cygpath -w "$HOST_DIR")"
  export MSYS_NO_PATHCONV=1
fi

docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -v "$HOST_DIR:/scripts" -w /scripts \
  -e BASE_URL="$BASE_URL" -e PROFILE="$PROFILE" \
  -e CLAIMANT_EMAIL -e CLAIMANT_PASSWORD -e ADMIN_EMAIL -e ADMIN_PASSWORD \
  "$K6_IMAGE" run \
  --summary-export "results/${SCRIPT%.js}-${PROFILE}.json" \
  "$SCRIPT"
