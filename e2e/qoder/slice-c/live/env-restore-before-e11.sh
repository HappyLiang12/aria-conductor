#!/usr/bin/env bash
# Operator-side stack restoration before E11 (the post-fix acceptance matrix).
# Runs in the repo root. The stack has been running the pre-fix build since the
# E10 window; this restarts it so the running backend carries the whole fix wave
# (G1b/G2a/G3a/G4/G6 + R81). Records the container inventory, re-verifies the
# docker-compat archive endpoint (the E7/E9 failure class), then stop + start
# under the R62 env with PACK_CREDENTIAL_KEY injected.
#
# Run: bash e2e/qoder/slice-c/live/env-restore-before-e11.sh 2>&1 | tee e2e/qoder/slice-c/live/env-restore-before-e11.log
set -u
REPO_ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$REPO_ROOT"
WORK="$(dirname "$0")/.e11-restore-work"
mkdir -p "$WORK"

echo "=== operator-side stack restoration before E11 (disclosed in the evidence doc) ==="
echo "=== timestamp: $(date '+%Y-%m-%d %H:%M:%S %z') ==="
echo "--- git revision the stack will be built from: $(git -C "$REPO_ROOT" rev-parse --short HEAD) ---"
echo "--- pre-restart container inventory (podman ps -a): ---"
MSYS_NO_PATHCONV=1 podman ps -a --format "{{.ID}} {{.Image}} {{.Status}} {{.Names}}" 2>&1
echo "--- archive-endpoint probe (the E7/E9 failure class; 500 broken pipe when the WSL VM is wedged): ---"
MSYS_NO_PATHCONV=1 podman machine ssh < "$(dirname "$0")/probe-opensandbox-archive.sh" 2>&1 | grep -E "^HTTP|curl present"
echo "--- stack restart under the R62 env (PACK_CREDENTIAL_KEY inline, never echoed) ---"
pwsh -NoProfile -File scripts/stop.ps1 > "$WORK/stop.out" 2>&1
echo "stop.ps1 exit=$?"
tail -6 "$WORK/stop.out"
env QODER_MODEL=efficient BACKEND_PORT=8097 SERVER_PORT=8097 VITE_PORT=5273 ARIA_MCP_PORT=8097 OPENSANDBOX_PORT=8090 APPROVALS_TIMEOUT_MS=120000 CONTAINER_RUNTIME=podman \
  PACK_CREDENTIAL_KEY="$(cat /c/Users/User/c6-run/pack-key.txt)" \
  pwsh -NoProfile -File scripts/start.ps1 -Provider qoder > "$WORK/start.out" 2>&1
echo "start.ps1 exit=$?"
tail -30 "$WORK/start.out"
