#!/usr/bin/env bash
# Operator-side stack restoration before E9 (the R81-fix acceptance matrix).
# Runs in the repo root. The stack was stopped by the operator to free the Maven
# lane for the R81 fix; this script records the container inventory, re-verifies
# the docker-compat archive endpoint (the E7 failure class), and restarts the
# stack under the R62 env.
#
# Run:  bash e2e/qoder/slice-c/live/env-restore-before-e9.sh 2>&1 | tee e2e/qoder/slice-c/live/env-restore-before-e9.log
set -u
REPO_ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$REPO_ROOT"
WORK="$(dirname "$0")/.e9-restore-work"
mkdir -p "$WORK"

echo "=== operator-side stack restoration before E9 (disclosed in the evidence doc) ==="
echo "=== timestamp: $(date '+%Y-%m-%d %H:%M:%S %z') ==="
echo "--- pre-restart container inventory (podman ps -a): ---"
MSYS_NO_PATHCONV=1 podman ps -a --format "{{.ID}} {{.Image}} {{.Status}} {{.Names}}" 2>&1
echo "--- archive-endpoint probe (the E7 failure class; 500 broken pipe before a machine restart, 200 after): ---"
MSYS_NO_PATHCONV=1 podman machine ssh < "$(dirname "$0")/probe-opensandbox-archive.sh" 2>&1 | grep -E "^HTTP|curl present"
echo "--- stack restart: QODER_MODEL=efficient BACKEND_PORT=8097 SERVER_PORT=8097 VITE_PORT=5273 ARIA_MCP_PORT=8097 OPENSANDBOX_PORT=8090 APPROVALS_TIMEOUT_MS=120000 CONTAINER_RUNTIME=podman PACK_CREDENTIAL_KEY=<inline from pack-key.txt> pwsh -NoProfile -File scripts/start.ps1 -Provider qoder ---"
pwsh -NoProfile -File scripts/stop.ps1 > "$WORK/stop.out" 2>&1
echo "stop.ps1 exit=$?"
tail -6 "$WORK/stop.out"
env QODER_MODEL=efficient BACKEND_PORT=8097 SERVER_PORT=8097 VITE_PORT=5273 ARIA_MCP_PORT=8097 OPENSANDBOX_PORT=8090 APPROVALS_TIMEOUT_MS=120000 CONTAINER_RUNTIME=podman \
  PACK_CREDENTIAL_KEY="$(cat /c/Users/User/c6-run/pack-key.txt)" \
  pwsh -NoProfile -File scripts/start.ps1 -Provider qoder > "$WORK/start.out" 2>&1
echo "start.ps1 exit=$?"
tail -30 "$WORK/start.out"
