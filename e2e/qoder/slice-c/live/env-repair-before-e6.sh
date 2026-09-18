#!/usr/bin/env bash
# =============================================================================
# Operator-side environment repair before the third harness execution (E6).
#
# Failure class (same as the R76 repair recorded in stack-restore-after-s10.log):
# a freshly created sandbox container gets host-mapped ports from podman, but
# Windows has no listener on them (execd port refuses / answers empty) while the
# VM, sshd and the podman socket are healthy — the WSL port relay died. The
# stack's own health checks stay green, so this is only visible at sandbox
# creation time ("Sandbox exec channel still not ready after 90s", and
# SANDBOX_EXECD_DISTRIBUTION_FAILED broken pipe inside the sandbox).
#
# Known repair (project memory + R76): podman machine stop + start, then restart
# the stack. Do NOT reinstall podman. Machine restart stops the containers of the
# foreign worktree C:/Users/User/.qoder/worktrees/app/55ec8d — disclosed, not
# touched otherwise.
#
# Run:  bash e2e/qoder/slice-c/live/env-repair-before-e6.sh > e2e/qoder/slice-c/live/env-repair-before-e6.log 2>&1
# =============================================================================
set +x

REPO_ROOT=/d/project/aria-conductor
cd "$REPO_ROOT" || exit 2

echo "=== env repair before E6 (podman machine restart + stack restart) ==="
echo "--- timestamp: $(date '+%Y-%m-%d %H:%M:%S %z') ---"
echo "--- container inventory before the restart (podman ps -a): ---"
MSYS_NO_PATHCONV=1 podman ps -a
echo "--- podman machine list (pre): ---"
MSYS_NO_PATHCONV=1 podman machine list

echo "--- podman machine stop/start (R76 repair): ---"
MSYS_NO_PATHCONV=1 podman machine stop
echo "stop exit=$?"
MSYS_NO_PATHCONV=1 podman machine start
echo "start exit=$?"

echo "--- podman ps after machine restart: ---"
MSYS_NO_PATHCONV=1 podman ps -a

echo "--- stack restart: QODER_MODEL=efficient BACKEND_PORT=8097 SERVER_PORT=8097 VITE_PORT=5273 ARIA_MCP_PORT=8097 OPENSANDBOX_PORT=8090 APPROVALS_TIMEOUT_MS=120000 CONTAINER_RUNTIME=podman PACK_CREDENTIAL_KEY=<inline from pack-key.txt> pwsh -NoProfile -File scripts/start.ps1 -Provider qoder ---"
export QODER_MODEL=efficient
export BACKEND_PORT=8097 SERVER_PORT=8097 VITE_PORT=5273
export ARIA_MCP_PORT=8097 OPENSANDBOX_PORT=8090
export APPROVALS_TIMEOUT_MS=120000 CONTAINER_RUNTIME=podman
export PACK_CREDENTIAL_KEY="$(cat /c/Users/User/c6-run/pack-key.txt)"
pwsh -NoProfile -File scripts/start.ps1 -Provider qoder
echo "start.ps1 exit=$?"
