#!/usr/bin/env bash
# =============================================================================
# Operator-side smoke test — executed once after the S10 restart failure was
# repaired by hand (podman machine restart + stack restart; see R76 in
# .superpowers/sdd/2026-09-17-qoder-cli-provider/progress.md).
#
# Purpose (two claims, both required before the supplementary harness run):
#   1. the restored stack's sandbox path works end to end with the real qoder CLI
#      on the zero-credit model (agent -> run -> sandbox -> bridge -> ACP ask);
#   2. the restarted backend carries the short approvals TTL the S4 scenario
#      needs (APPROVALS_TIMEOUT_MS=120000 -> ask flips EXPIRED well inside 300s).
#
# It seeds through the harness's own helpers (no duplicated REST logic) and never
# touches the PAT: the credential lives in the backend store.
#
# Run:  bash e2e/qoder/slice-c/live/smoke-post-restore.sh 2>&1 | tee smoke-post-restore.log
# =============================================================================
set +x

REPO_ROOT=/d/project/aria-conductor
cd "$REPO_ROOT" || exit 2
export API_URL="${API_URL:-http://localhost:8097}"
export BASE_URL="${BASE_URL:-http://localhost:5273}"
export OPENSANDBOX_URL="${OPENSANDBOX_URL:-http://localhost:8090}"
export CONTAINER_RUNTIME="${CONTAINER_RUNTIME:-podman}"
export QODER_SANDBOX_IMAGE="${QODER_SANDBOX_IMAGE:-aria-conductor/qoder-sandbox:0.1}"
export MODEL="${QODER_E2E_MODEL:-efficient}"
export EVIDENCE_DIR="${EVIDENCE_DIR:-$REPO_ROOT/e2e/qoder/slice-c/live}"
unset SLOG

. e2e/qoder/lib/common.sh
. e2e/qoder/lib/stack.sh
. e2e/qoder/lib/scenarios.sh

# common.sh initializes WORK_DIR="" — set it only after sourcing.
export WORK_DIR="$EVIDENCE_DIR/.smoke-work"
mkdir -p "$WORK_DIR"

echo "# smoke run — restored stack after the S10 restart failure — $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "# API_URL=$API_URL model=$MODEL git=$(git -C "$REPO_ROOT" rev-parse --short HEAD)"

label="smoke-post-restore-$(date -u +%Y%m%d-%H%M%S)"

echo "== 1. seed agent + run (real qoder, zero-credit model) =="
agent_id="$(create_qoder_agent "$WORK_DIR/agent.json" "$label")"
if [ -z "$agent_id" ]; then echo "SMOKE FAIL: agent creation failed"; exit 1; fi
run_id="$(start_qoder_run "$WORK_DIR/run.json" "$agent_id" "$(write_trigger_prompt "$label")")"
if [ -z "$run_id" ]; then echo "SMOKE FAIL: run start failed"; exit 1; fi
echo "   agent=$agent_id run=$run_id"

echo "== 2. wait (<=180s) for a PENDING ACP_PERMISSION ask of the run =="
t0=$SECONDS
ask_id="$(wait_for_pending_ask "$run_id" 180)"
if [ -z "$ask_id" ]; then
  echo "SMOKE FAIL: no PENDING ACP ask appeared within 180s (sandbox path broken)"
  exit 1
fi
echo "   pending ask=$ask_id after $((SECONDS - t0))s"

api_get "/approvals/$ask_id" "$WORK_DIR/ask.json" >/dev/null 2>&1 || true
echo "   status=$(json_get "$WORK_DIR/ask.json" status) requestedAt=$(json_get "$WORK_DIR/ask.json" requestedAt) expiresAt=$(json_get "$WORK_DIR/ask.json" expiresAt)"

echo "== 3. poll until EXPIRED (<=300s) — proves the restarted backend's short TTL =="
t1=$SECONDS
deadline=$((SECONDS + 300))
status=""
while [ "$SECONDS" -lt "$deadline" ]; do
  api_get "/approvals/$ask_id" "$WORK_DIR/ask.json" >/dev/null 2>&1 || true
  status="$(json_get "$WORK_DIR/ask.json" status)"
  echo "   t+$((SECONDS - t1))s status=$status"
  [ "$status" = "EXPIRED" ] && break
  sleep 5
done
if [ "$status" != "EXPIRED" ]; then
  echo "SMOKE FAIL: ask did not expire within 300s — the restarted backend does NOT carry the short approvals TTL (restart the stack with APPROVALS_TIMEOUT_MS=120000)"
  exit 1
fi
echo "   ask EXPIRED after ~$((SECONDS - t1))s (TTL + sweep)"

echo "== 4. run outcome (<=180s, recorded not asserted as success) =="
t2=$SECONDS
run_status=""
final=""
while [ "$SECONDS" -lt $((t2 + 180)) ]; do
  api_get "/runs/$run_id" "$WORK_DIR/run.json" >/dev/null 2>&1 || true
  run_status="$(json_get "$WORK_DIR/run.json" status)"
  case "$run_status" in
    COMPLETED | FAILED | CANCELLED) break ;;
  esac
  sleep 5
done
final="$(json_get "$WORK_DIR/run.json" finalOutput)"
echo "   run status=$run_status finalOutput=${final:0:160}"

echo "SMOKE PASS: sandbox path works and the ask expired inside the short TTL"
exit 0
