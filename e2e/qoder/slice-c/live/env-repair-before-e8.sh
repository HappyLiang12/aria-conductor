#!/usr/bin/env bash
# Operator-side environment repair before E8 (R79-class recurrence, disclosed).
# Runs in the repo root. The podman machine stop/start was already performed by
# the operator; this script records the inventory, re-verifies the repaired
# docker-compat archive endpoint, and restarts the stack under the R62 env.
#
# v2 note: the first run (log env-repair-before-e8.log) piped the two pwsh
# commands into tail and the tail stalled on the inherited-pipe trap (the same
# mechanism as R79): the stack still came up (health 200/200/200 at 20:07).
# v2 redirects to files and tails the files.
set -u
REPO_ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$REPO_ROOT"
WORK="$(dirname "$0")/.repair-work"
mkdir -p "$WORK"

echo "=== operator-side stack restoration before E8 (disclosed in the evidence doc) ==="
echo "=== timestamp: $(date '+%Y-%m-%d %H:%M:%S %z') ==="
echo "--- pre-restart container inventory (podman ps -a): ---"
MSYS_NO_PATHCONV=1 podman ps -a --format "{{.ID}} {{.Image}} {{.Status}} {{.Names}}" 2>&1
echo "--- machine restart already done by the operator: podman machine stop (exit 0) + start (exit 0) ---"
echo "--- archive-endpoint probe (the E7 failure class; 500 broken pipe before the restart, 200 after): ---"
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
