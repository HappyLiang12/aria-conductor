#!/usr/bin/env bash
# =============================================================================
# Task C6b — qoder slice-c orchestrator (plan: docs/superpowers/plans/
# 2026-09-17-qoder-cli-provider.md, Task C6; brief: .superpowers/sdd/
# 2026-09-17-qoder-cli-provider/task-C6b-brief.md).
#
# Runs S1-S12 (spec-owned playwright steps + the bash-owned S5/S10 scenarios) and
# the regression lane, one log per step in $EVIDENCE_DIR, and a summary table.
#
# Invocation (from anywhere):
#   QODER_E2E_PAT=... EVIDENCE_DIR=/abs/path  bash e2e/qoder/slice-c/run-all.sh [flags]
#   QODER_E2E_PAT=... EVIDENCE_DIR=/abs/path QODER_E2E_MODEL=efficient \
#     bash e2e/qoder/slice-c/run-all.sh --rebuild-image
#
# Flags: --only <id[,id...]> | --dry-run | --skip-regression | --rebuild-image |
#        --no-preflight | --help
#
# PAT handling (binding): the PAT is never echoed, never written to argv, never
# printed into a log. It reaches the backend only through credential.sh's stdin
# pipe, is inherited by the playwright steps as an environment variable (their own
# PAT gate), and the final `leak-scan` step greps every evidence file for it
# (count-only output) and fails the run on a hit. `set -x` is never enabled.
# =============================================================================
set +x

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

. "$SCRIPT_DIR/../lib/common.sh"
. "$SCRIPT_DIR/../lib/stack.sh"
. "$SCRIPT_DIR/../lib/credential.sh"
. "$SCRIPT_DIR/../lib/scenarios.sh"

# ---------------------------------------------------------------------------
# Environment contract (frozen)
# ---------------------------------------------------------------------------
MODEL="${QODER_E2E_MODEL:-efficient}"
API_URL="${API_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL:-http://localhost:5173}"
OPENSANDBOX_URL="${OPENSANDBOX_URL:-http://localhost:8090}"
CONTAINER_RUNTIME="${CONTAINER_RUNTIME:-podman}"
QODER_SANDBOX_IMAGE="${QODER_SANDBOX_IMAGE:-aria-conductor/qoder-sandbox:0.1}"
export MODEL API_URL BASE_URL OPENSANDBOX_URL CONTAINER_RUNTIME QODER_SANDBOX_IMAGE
# The specs read these three from the environment (QODER_E2E=1 is their skip gate,
# QODER_E2E_PAT their PAT gate, QODER_E2E_MODEL their zero-credit guard).
export QODER_E2E_MODEL="$MODEL"

ONLY=""
DRY_RUN=0
SKIP_REGRESSION=0
REBUILD_IMAGE=0
NO_PREFLIGHT=0
FLAGS_SUMMARY=""

usage() {
  cat <<'EOF'
Usage: QODER_E2E_PAT=<pat> EVIDENCE_DIR=<abs dir> bash run-all.sh [flags]

  --only <id[,id...]>   run only the listed steps (comma separated; see the ids below)
  --dry-run             print every step's exact command, cwd and log path; execute nothing
  --skip-regression     drop every regression-* step from the plan (wins over --only)
  --rebuild-image       build (runtime) build -t aria-conductor/qoder-sandbox:0.1
                        agent-control-tower/qoder-sandbox as its own preflight step
  --no-preflight        skip the health/credential/model preflight (dry runs only)
  --help                this text

Step ids: preflight rebuild-image S1 S2 S3 S4 S5 S6 S7 S8 S9 S10 S11 S12
          regression-mvn-test regression-mvn-verify regression-vitest
          regression-build regression-playwright regression-container-runtime
          leak-scan

Env: QODER_E2E_PAT (required; the real PAT, never echoed), EVIDENCE_DIR (required,
     absolute, must exist), QODER_E2E_MODEL (default efficient; only efficient|lite
     are allowed unless QODER_E2E_ALLOW_PAID=1), API_URL, BASE_URL,
     OPENSANDBOX_URL, CONTAINER_RUNTIME (default podman), QODER_SANDBOX_IMAGE.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --only)
      shift
      [ $# -gt 0 ] || die "--only needs a value (comma-separated step ids)"
      ONLY="$1"
      ;;
    --only=*) ONLY="${1#--only=}" ;;
    --dry-run) DRY_RUN=1 ;;
    --skip-regression) SKIP_REGRESSION=1 ;;
    --rebuild-image) REBUILD_IMAGE=1 ;;
    --no-preflight) NO_PREFLIGHT=1 ;;
    --help | -h)
      usage
      exit 0
      ;;
    *)
      err "unknown argument: $1"
      usage >&2
      exit 2
      ;;
  esac
  shift
done

# ---------------------------------------------------------------------------
# Fail-fast environment validation (env failures abort before anything runs)
# ---------------------------------------------------------------------------
if [ -z "${EVIDENCE_DIR:-}" ]; then
  die "EVIDENCE_DIR is not set. The harness writes every step log there: export EVIDENCE_DIR=<absolute path> (create it first)."
fi
case "$EVIDENCE_DIR" in
  /*) ;;
  [A-Za-z]:[/\\]*) EVIDENCE_DIR="${EVIDENCE_DIR//\\//}" ;; # Windows drive path, Git Bash accepts C:/...
  *)
    die "EVIDENCE_DIR must be an absolute path (got '$EVIDENCE_DIR')."
    ;;
esac
[ -d "$EVIDENCE_DIR" ] || die "EVIDENCE_DIR '$EVIDENCE_DIR' does not exist — create it first (mkdir -p)."
WORK_DIR="$EVIDENCE_DIR/.c6b-work"
export EVIDENCE_DIR WORK_DIR

# Zero-credit guard on the env pin (mirrors QoderSandboxHarness.java:57-61,172-177).
# Runs before anything is created or executed: a paid model refuses immediately.
enforce_zero_credit_env_guard || exit 2

if [ "$DRY_RUN" -eq 0 ] && [ -z "${QODER_E2E_PAT:-}" ]; then
  die "QODER_E2E_PAT is not set. It is required for every live run (the preflight loads it into the runtime credential store) — export the real qoder PAT inline: QODER_E2E_PAT=... bash $0. (--dry-run does not need it.)"
fi

require_cmd node
require_cmd curl
require_cmd git

if [ "$DRY_RUN" -eq 0 ]; then
  mkdir -p "$WORK_DIR" || die "cannot create the harness work dir under EVIDENCE_DIR: $WORK_DIR"
fi

FLAGS_SUMMARY="dry-run=$DRY_RUN skip-regression=$SKIP_REGRESSION rebuild-image=$REBUILD_IMAGE no-preflight=$NO_PREFLIGHT only=${ONLY:-<all>}"

# ---------------------------------------------------------------------------
# Plan (frozen order; ids are stable and log names derive from them)
# ---------------------------------------------------------------------------
PLAN_IDS=()
STEP_CMD=()
STEP_CWD=()
STEP_LABEL=()
STEP_STATUS=()

add_step() { # id, cmd-text, cwd, label
  PLAN_IDS+=("$1")
  STEP_CMD[$1]="$2"
  STEP_CWD[$1]="$3"
  STEP_LABEL[$1]="$4"
}

add_step preflight \
  "curl GET $API_URL/actuator/health + GET $BASE_URL + GET $OPENSANDBOX_URL/health + GET/PUT $API_URL/api/v1/adk/providers/qoder/credential (masked; PAT piped via stdin)" \
  "$REPO_ROOT" "stack health + qoder credential + live zero-credit model pin"
add_step rebuild-image \
  "$CONTAINER_RUNTIME build -t $QODER_SANDBOX_IMAGE agent-control-tower/qoder-sandbox" \
  "$REPO_ROOT" "rebuild the pinned qoder sandbox image (plan Step 2 / C2 R13)"
add_step S1 "npx playwright test e2e/qoder-*.spec.ts --grep \"S1:\"" "$DASHBOARD_DIR" "spec-owned scenario S1"
add_step S2 "npx playwright test e2e/qoder-*.spec.ts --grep \"S2:\"" "$DASHBOARD_DIR" "spec-owned scenario S2"
add_step S3 "npx playwright test e2e/qoder-*.spec.ts --grep \"S3:\"" "$DASHBOARD_DIR" "spec-owned scenario S3"
add_step S4 "npx playwright test e2e/qoder-*.spec.ts --grep \"S4:\"" "$DASHBOARD_DIR" "spec-owned scenario S4"
add_step S5 "bash-owned: REST scenario — seed qoder run, wait for the pending ACP ask, POST /api/v1/runs/{id}/cancel, assert run CANCELLED + ask EXPIRED + sandbox survived with no qodercli process" \
  "$REPO_ROOT" "cancel during pending permission (sandbox survival)"
add_step S6 "npx playwright test e2e/qoder-*.spec.ts --grep \"S6:\"" "$DASHBOARD_DIR" "spec-owned scenario S6"
add_step S7 "npx playwright test e2e/qoder-*.spec.ts --grep \"S7:\"" "$DASHBOARD_DIR" "spec-owned scenario S7"
add_step S8 "npx playwright test e2e/qoder-*.spec.ts --grep \"S8:\"" "$DASHBOARD_DIR" "spec-owned scenario S8"
add_step S9 "npx playwright test e2e/qoder-*.spec.ts --grep \"S9:\"" "$DASHBOARD_DIR" "spec-owned scenario S9"
add_step S10 "bash-owned: kill the backend recorded in .run/backend.pid mid-ask, restart the stack (stop.ps1 + QODER_MODEL=$MODEL pwsh -NoProfile -File scripts/start.ps1 -Provider qoder), assert the startup-sweep interruption state + expired ask without replay" \
  "$REPO_ROOT" "backend restart interrupts a pending ask (S10)"
add_step S11 "npx playwright test e2e/qoder-*.spec.ts --grep \"S11:\"" "$DASHBOARD_DIR" "spec-owned scenario S11"
add_step S12 "npx playwright test e2e/qoder-governance-e2e.spec.ts --grep \"S12:\"" "$DASHBOARD_DIR" "spec-owned governance scenario S12"
add_step regression-mvn-test "mvn clean test -Dspring.profiles.active=h2" "$MODULE_DIR" "regression: backend unit/integration suite (Maven lane)"
add_step regression-mvn-verify "mvn verify" "$MODULE_DIR" "regression: full Maven verify (Maven lane)"
add_step regression-vitest "pnpm test" "$DASHBOARD_DIR" "regression: dashboard vitest suite"
add_step regression-build "pnpm build" "$DASHBOARD_DIR" "regression: dashboard typecheck + build"
add_step regression-playwright "npx playwright test   (QODER_E2E / QODER_E2E_PAT / QODER_E2E_MODEL removed from the child env)" "$DASHBOARD_DIR" "regression: pre-existing Playwright suites only"
add_step regression-container-runtime "pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 && bash e2e/container-runtime-e2e.sh" "$REPO_ROOT" "regression: container runtime selection (AGENTS.md)"
add_step leak-scan "grep -F -c -f <chmod 600 PAT tempfile> over every file in \$EVIDENCE_DIR (count-only; tempfile deleted right after)" "$REPO_ROOT" "PAT leak scan over the evidence dir"

# ---------------------------------------------------------------------------
# Selection: --only, then --skip-regression, then the conditional preflight steps
# ---------------------------------------------------------------------------
declare -A REQUESTED=()
ONLY_IDS=()
if [ -n "$ONLY" ]; then
  for id in $(printf '%s' "$ONLY" | tr ',' ' '); do
    ONLY_IDS+=("$id")
    REQUESTED[$id]=1
  done
  # Unknown --only ids are a usage error (fail fast, nothing executed).
  for id in "${ONLY_IDS[@]}"; do
    found=""
    for known in "${PLAN_IDS[@]}"; do
      if [ "$id" = "$known" ]; then
        found=yes
        break
      fi
    done
    [ -n "$found" ] || die "unknown step id in --only: '$id' (valid: ${PLAN_IDS[*]})"
  done
fi

is_requested() { # id -> 0 when --only is absent or names the id
  [ -z "$ONLY" ] && return 0
  [ -n "${REQUESTED[$1]:-}" ]
}

is_regression_id() { # id
  case "$1" in
    regression-mvn-test | regression-mvn-verify | regression-vitest | regression-build | regression-playwright | regression-container-runtime) return 0 ;;
  esac
  return 1
}

# should_run: the executed plan. Notes:
#  - preflight runs for every live invocation (also under --only: the live steps need
#    the health/credential/model checks) unless --no-preflight/--dry-run;
#  - rebuild-image runs only with --rebuild-image;
#  - --skip-regression bypasses every regression step, even one named by --only.
should_run() { # id
  local id="$1"
  case "$id" in
    preflight)
      [ "$DRY_RUN" -eq 0 ] && [ "$NO_PREFLIGHT" -eq 0 ] && return 0
      return 1
      ;;
    rebuild-image)
      # Selected by --rebuild-image, or explicitly by --only rebuild-image.
      [ "$REBUILD_IMAGE" -eq 1 ] || is_requested "$id" || return 1
      ;;
    leak-scan)
      return 0 ;; # always: the final PAT guard
  esac
  if is_regression_id "$id" && [ "$SKIP_REGRESSION" -eq 1 ]; then
    if is_requested "$id"; then
      warn "--skip-regression: dropping $id (it was named by --only)"
    fi
    return 1
  fi
  is_requested "$id"
}

for id in "${PLAN_IDS[@]}"; do
  STEP_STATUS[$id]="SKIPPED"
done

SELECTED=()
for id in "${PLAN_IDS[@]}"; do
  should_run "$id" && SELECTED+=("$id")
done

# ---------------------------------------------------------------------------
# Dry run: print the plan, execute nothing (no health checks, no network, no PAT)
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" -eq 1 ]; then
  PREFLIGHT_HINT="skipped by --dry-run (with a live run: GET $API_URL/actuator/health == UP, GET $BASE_URL reachable, GET $OPENSANDBOX_URL/health reachable, credential configured, live model in {efficient,lite})"
  log "dry-run: plan order: ${SELECTED[*]}"
  print_dry_run
  exit 0
fi

# ---------------------------------------------------------------------------
# Execute (sequentially; failures are collected, only preflight aborts)
# ---------------------------------------------------------------------------
log "qoder slice-c harness — $(git_sha) — model=$MODEL api=$API_URL dashboard=$BASE_URL sandbox=$OPENSANDBOX_URL"
log "evidence dir: $EVIDENCE_DIR"
log "plan: ${SELECTED[*]}"

for id in "${SELECTED[@]}"; do
  STEP_STATUS[$id]="PENDING"
done

ABORTED_EARLY="no"
for id in "${SELECTED[@]}"; do
  run_step "$id"
  if [ "$id" = "preflight" ] && [ "${STEP_STATUS[preflight]}" = "FAIL" ]; then
    warn "preflight failed — aborting the plan (env/preflight failures are the only mid-plan abort)"
    ABORTED_EARLY="yes"
    break
  fi
done

# Anything in the plan that never started stays SKIPPED (e.g. after a preflight abort).
for id in "${PLAN_IDS[@]}"; do
  [ "${STEP_STATUS[$id]}" = "PENDING" ] && STEP_STATUS[$id]="SKIPPED"
done

# The PAT leak guard is unconditional: a preflight abort after the credential PUT (or a
# crash) may still have leaked, so leak-scan runs even when the plan was aborted.
if [ "$ABORTED_EARLY" = "yes" ] && [ "${STEP_STATUS[leak-scan]}" = "SKIPPED" ]; then
  STEP_STATUS[leak-scan]="PENDING"
  run_step leak-scan
  [ "${STEP_STATUS[leak-scan]}" = "PENDING" ] && STEP_STATUS[leak-scan]="SKIPPED"
fi

# ---------------------------------------------------------------------------
# Summary + exit code
# ---------------------------------------------------------------------------
overall_rc
ORC=$?
write_summary "$ORC"
exit "$ORC"
