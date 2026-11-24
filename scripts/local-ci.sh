#!/usr/bin/env bash
# Local mirror of the GitHub CI gates that must be green before a PR push.
#
# The GitHub workflow splits each tier into hosted jobs; this script runs the
# same commands on this machine so a flake or a broken gate is found before a
# cloud run is spent.
#
# Usage:
#   scripts/local-ci.sh [frontend|java-unit|integration|e2e|affected|all] [options]
#
#   affected          run the gates implied by the diff against --base (default origin/main)
#   all               frontend + java-unit + integration (add --with-e2e for Playwright)
#   e2e               Playwright against an already-running stack (BASE_URL, default :5173)
#
# Options:
#   --base <ref>      diff base for `affected` (default $LOCAL_CI_BASE or origin/main)
#   --shard <n/4>     Playwright shard for `e2e`
#   --with-e2e        make `all` include the Playwright tier
#   --help            this text
#
# Exit codes: 0 green, 1 a gate failed, 2 a prerequisite is missing (e.g. no stack for e2e).
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DASH="$ROOT/agent-control-tower/act-dashboard"
BACKEND="$ROOT/agent-control-tower"
MODE=${1:-affected}
if [[ $# -gt 0 ]]; then shift; fi

BASE=${LOCAL_CI_BASE:-origin/main}
SHARD=""
WITH_E2E=0

usage() {
  sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

case "$MODE" in
  -h|--help|help) usage; exit 0 ;;
  frontend|java-unit|integration|e2e|affected|all) ;;
  *) echo "local-ci: unknown mode '$MODE'" >&2; usage >&2; exit 1 ;;
esac

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE=${2:?--base needs a ref}; shift 2 ;;
    --shard) SHARD=${2:?--shard needs n/4}; shift 2 ;;
    --with-e2e) WITH_E2E=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "local-ci: unknown option '$1'" >&2; exit 1 ;;
  esac
done

step() { printf '\n==> %s\n' "$*"; }
not_run() { printf '\nNOT RUN: %s\n' "$*"; }

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "local-ci: '$1' is required on PATH" >&2; exit 2; }
}

run_frontend() {
  need pnpm
  step "Frontend: install (frozen lockfile)"
  (cd "$DASH" && pnpm install --frozen-lockfile)
  step "Frontend: unit tests + coverage (the CI command)"
  (cd "$DASH" && pnpm test -- --coverage)
  step "Frontend: build"
  (cd "$DASH" && pnpm build)
}

run_java_unit() {
  need mvn
  step "Java: reactor install, skip tests"
  (cd "$BACKEND" && mvn -q -T 1C install -DskipTests)
  step "Java: unit tier (surefire, h2)"
  (cd "$BACKEND" && mvn test -Dspring.profiles.active=h2 -Dskip.integration.tests=true)
}

run_integration() {
  need mvn
  need pnpm
  step "Integration: build the Qoder ACP bridge"
  (cd "$ROOT/packages/qoder-acp-bridge" && pnpm install --frozen-lockfile && pnpm build)
  step "Java: reactor install, skip tests"
  (cd "$BACKEND" && mvn -q -T 1C install -DskipTests)
  step "Java: integration tier only (failsafe, h2)"
  (cd "$BACKEND" && mvn verify -Dskip.unit.tests=true -Dspring.profiles.active=h2)
}

run_e2e() {
  need curl
  need pnpm
  local base_url=${BASE_URL:-http://localhost:5173}
  local health=${LOCAL_CI_HEALTH_URL:-http://127.0.0.1:8080/actuator/health}
  if ! curl -fsS "$health" >/dev/null 2>&1; then
    echo "local-ci: backend health is unavailable at $health" >&2
    not_run "E2E needs a running stack; start it with scripts/start-backend.sh --skip-sandbox and scripts/start-frontend.sh"
    exit 2
  fi
  local args=(exec playwright test --workers=1)
  if [[ -n "$SHARD" ]]; then
    args+=(--shard="$SHARD")
  fi
  step "E2E: Playwright (BASE_URL=$base_url${SHARD:+, shard $SHARD})"
  (cd "$DASH" && CI=true BASE_URL="$base_url" pnpm "${args[@]}")
}

changed_files() {
  if ! git -C "$ROOT" rev-parse --verify --quiet "$BASE" >/dev/null; then
    echo "local-ci: base ref '$BASE' does not exist (fetch it or pass --base)" >&2
    exit 2
  fi
  git -C "$ROOT" diff --name-only "$BASE"...HEAD
}

run_affected() {
  local changed frontend_changed java_changed e2e_changed
  changed=$(changed_files)
  if [[ -z "$changed" ]]; then
    echo "local-ci: no changes against $BASE; nothing to run"
    return 0
  fi
  frontend_changed=$(grep -E '^agent-control-tower/act-dashboard/' <<<"$changed" || true)
  e2e_changed=$(grep -E '^agent-control-tower/act-dashboard/e2e/' <<<"$changed" || true)
  java_changed=$(grep -E '^(agent-control-tower/|packages/qoder-acp-bridge/|e2e/|scripts/|\.github/)' <<<"$changed" | grep -vE '^agent-control-tower/act-dashboard/' || true)

  if [[ -n "$frontend_changed" ]]; then
    run_frontend
  else
    not_run "frontend (no act-dashboard change)"
  fi
  if [[ -n "$java_changed" ]]; then
    run_java_unit
    run_integration
  else
    not_run "java-unit + integration (no backend/packaging change)"
  fi
  if [[ -n "$e2e_changed" ]]; then
    not_run "E2E specs changed; run 'scripts/local-ci.sh e2e' against a started stack (not started by affected)"
  elif [[ -n "$frontend_changed" ]]; then
    not_run "E2E (frontend changed); run 'scripts/local-ci.sh e2e' against a started stack"
  fi
}

case "$MODE" in
  frontend) run_frontend ;;
  java-unit) run_java_unit ;;
  integration) run_integration ;;
  e2e) run_e2e ;;
  affected) run_affected ;;
  all)
    run_frontend
    run_java_unit
    run_integration
    if [[ "$WITH_E2E" -eq 1 ]]; then run_e2e; else not_run "E2E; rerun 'all --with-e2e' against a started stack"; fi
    ;;
esac

printf '\nlocal-ci: %s gate complete for %s\n' "$MODE" "$(git -C "$ROOT" rev-parse --short HEAD)"
