#!/usr/bin/env bash
# =============================================================================
# Task C6b — qoder slice-c harness: shared logging + step machinery.
#
# Binding conventions (task-C6b-brief.md, "Logging conventions" / "PAT redaction
# rules"):
#   - every step writes $EVIDENCE_DIR/<id>.run<N>.log (N auto-increments; a
#     failed run's evidence is never overwritten);
#   - a log begins with the exact command line + cwd + git sha and ends with
#     EXIT=<code>;
#   - the run prints a summary table and writes $EVIDENCE_DIR/SUMMARY.run<N>.md;
#   - steps continue after a failure (collect all results); only env/preflight
#     failures abort;
#   - `set -x` is never enabled anywhere in this harness: QODER_E2E_PAT lives in
#     the environment and xtrace would print it. Every command that must reach a
#     log is appended explicitly through run_cmd / run_in_dir instead.
#
# The same step machinery serves the bash-owned steps (S5, S10, regression lane)
# and the spec-owned steps (S1-S4, S6-S9, S11, S12 -> npx playwright).
# =============================================================================
set +x

C6B_LOG_PREFIX="[c6b]"

log()  { printf '%s %s\n' "$C6B_LOG_PREFIX" "$*"; }
warn() { printf '%s WARNING: %s\n' "$C6B_LOG_PREFIX" "$*" >&2; }
err()  { printf '%s ERROR: %s\n' "$C6B_LOG_PREFIX" "$*" >&2; }
die()  { err "$*"; exit 2; }

git_sha()       { git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown"; }
now_utc()       { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
elapsed_since() { echo $(( SECONDS - $1 )); }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found on PATH: $1"
}

# ---------------------------------------------------------------------------
# Step registry (set up by run-all.sh, driven here)
# ---------------------------------------------------------------------------
declare -a PLAN_IDS=()      # every id in the frozen plan order, selected or not
declare -A STEP_CMD=()      # id -> exact command text (log header + dry-run)
declare -A STEP_CWD=()      # id -> cwd shown in the header / dry-run
declare -A STEP_LABEL=()    # id -> one-line human label
declare -A STEP_STATUS=()   # id -> PASS | FAIL | SKIPPED
declare -A STEP_LOG=()      # id -> absolute log path
SLOG=""                     # current step log (absolute)
WORK_DIR=""                 # scratch dir for HTTP bodies; under EVIDENCE_DIR

# Next free log path for a step id: never overwrites an earlier run's evidence.
next_log_path() {
  local id="$1" n=1
  while [ -e "$EVIDENCE_DIR/$id.run$n.log" ]; do n=$((n + 1)); done
  printf '%s/%s.run%d.log' "$EVIDENCE_DIR" "$id" "$n"
}

# Appends a line to the current step log (no stdout noise).
step_note() { [ -n "$SLOG" ] && printf '  %s\n' "$*" >>"$SLOG" || true; }

# Records the exact command about to run into the step log (and echoes it).
log_cmd() {
  printf '+ %s\n' "$*" | tee -a "$SLOG"
}

# Marks a failed assertion inside a bash-owned step: recorded in the log, printed,
# and the step function returns non-zero for the caller's accumulator.
step_fail() {
  printf '# FAIL: %s\n' "$*" >>"$SLOG"
  err "[$STEP_CURRENT] $*"
  return 1
}

STEP_CURRENT=""

step_begin() { # id
  local id="$1"
  STEP_CURRENT="$id"
  SLOG="$(next_log_path "$id")"
  STEP_LOG[$id]="$SLOG"
  {
    printf '# step: %s (%s)\n' "$id" "${STEP_LABEL[$id]:-}"
    printf '# command: %s\n' "${STEP_CMD[$id]:-<bash step>}"
    printf '# cwd: %s\n' "${STEP_CWD[$id]:-unknown}"
    printf '# git: %s\n' "$(git_sha)"
    printf '# started: %s\n' "$(now_utc)"
    printf '# evidence: %s\n' "$EVIDENCE_DIR"
    printf '# ---- output follows ----\n'
  } >"$SLOG"
  log "== step $id: ${STEP_LABEL[$id]:-}"
  log "   log: $SLOG"
}

step_end() { # id, rc
  local id="$1" rc="$2"
  printf '\nEXIT=%d\n' "$rc" >>"$SLOG"
  if [ "$rc" -eq 0 ]; then
    STEP_STATUS[$id]="PASS"
    log "   $id: PASS"
  else
    STEP_STATUS[$id]="FAIL"
    warn "$id failed (EXIT=$rc) — continuing; see $SLOG"
  fi
}

run_step() { # id
  local id="$1"
  [ "${STEP_STATUS[$id]:-}" = "SKIPPED" ] && return 0
  STEP_CWD[$id]="${STEP_CWD[$id]:-$PWD}"
  step_begin "$id"
  local rc=0
  step_body "$id" || rc=$?
  step_end "$id" "$rc"
  return 0
}

# ---------------------------------------------------------------------------
# Command execution helpers (tee the child's combined output into $SLOG)
# ---------------------------------------------------------------------------
run_cmd() {
  "$@" 2>&1 | tee -a "$SLOG"
  return ${PIPESTATUS[0]}
}

run_in_dir() { # dir cmd...
  local dir="$1"
  shift
  ( cd "$dir" && "$@" ) 2>&1 | tee -a "$SLOG"
  return ${PIPESTATUS[0]}
}

# ---------------------------------------------------------------------------
# Maven lane exclusivity (brief: regression-mvn-test and regression-mvn-verify
# must never run concurrently with each other or with anything else Maven).
# `flock` is absent from this Git Bash (verified 2026-09-18), so the lane uses an
# atomic mkdir lock with a stale-owner check. A second harness instance running a
# Maven step waits for the lane instead of interleaving builds in the same repo.
# ---------------------------------------------------------------------------
MAVEN_LANE_LOCK="${TMPDIR:-/tmp}/aria-c6b-maven-lane.lock"
MAVEN_LANE_WAIT_SEC="${QODER_E2E_MAVEN_LANE_WAIT_SEC:-3600}"

acquire_maven_lane() {
  local start=$SECONDS
  while true; do
    if mkdir "$MAVEN_LANE_LOCK" 2>/dev/null; then
      echo "$$" >"$MAVEN_LANE_LOCK/pid"
      log "maven lane acquired ($MAVEN_LANE_LOCK, pid $$)"
      return 0
    fi
    local owner=""
    [ -f "$MAVEN_LANE_LOCK/pid" ] && owner="$(tr -d ' \r\n' <"$MAVEN_LANE_LOCK/pid")"
    if [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then
      warn "maven lane: stale lock of pid $owner — reclaiming"
      rm -rf "$MAVEN_LANE_LOCK"
      continue
    fi
    if [ "$(elapsed_since "$start")" -ge "$MAVEN_LANE_WAIT_SEC" ]; then
      die "maven lane busy for ${MAVEN_LANE_WAIT_SEC}s (owner pid ${owner:-unknown}, $MAVEN_LANE_LOCK); refusing to run two Maven lanes concurrently"
    fi
    log "maven lane busy (owner pid ${owner:-unknown}) — waiting 15s"
    sleep 15
  done
}

release_maven_lane() {
  [ -d "$MAVEN_LANE_LOCK" ] || return 0
  local owner=""
  [ -f "$MAVEN_LANE_LOCK/pid" ] && owner="$(tr -d ' \r\n' <"$MAVEN_LANE_LOCK/pid")"
  if [ "$owner" = "$$" ] || [ -z "$owner" ]; then
    rm -rf "$MAVEN_LANE_LOCK"
  fi
}

# ---------------------------------------------------------------------------
# Summary (stdout table + $EVIDENCE_DIR/SUMMARY.run<N>.md)
# ---------------------------------------------------------------------------
SUMMARY_EXTRA_LINES=()
LEAK_SCAN_RC=0

write_summary() { # overall_rc
  local overall="$1" n=1
  while [ -e "$EVIDENCE_DIR/SUMMARY.run$n.md" ]; do n=$((n + 1)); done
  local path="$EVIDENCE_DIR/SUMMARY.run$n.md"

  local total=0 pass=0 fail=0 skip=0 id
  for id in "${PLAN_IDS[@]}"; do
    total=$((total + 1))
    case "${STEP_STATUS[$id]:-SKIPPED}" in
      PASS) pass=$((pass + 1)) ;;
      FAIL) fail=$((fail + 1)) ;;
      *)    skip=$((skip + 1)) ;;
    esac
  done

  {
    printf '# qoder slice-c harness summary (run %d)\n\n' "$n"
    printf -- '- git: %s\n' "$(git_sha)"
    printf -- '- evidence dir: %s\n' "$EVIDENCE_DIR"
    printf -- '- provider guard: QODER_E2E_MODEL=%s (zero-credit pin)%s\n' "$MODEL" \
      "${QODER_E2E_ALLOW_PAID:+ (QODER_E2E_ALLOW_PAID=$QODER_E2E_ALLOW_PAID)}"
    printf -- '- api: %s   dashboard: %s   opensandbox: %s\n' "$API_URL" "$BASE_URL" "$OPENSANDBOX_URL"
    printf -- '- flags: %s\n' "${FLAGS_SUMMARY:-<none>}"
    printf -- '- finished: %s\n' "$(now_utc)"
    printf '\n| step | log | status |\n|------|-----|--------|\n'
    for id in "${PLAN_IDS[@]}"; do
      local st="${STEP_STATUS[$id]:-SKIPPED}"
      local lg=""
      [ -n "${STEP_LOG[$id]:-}" ] && lg="$(basename "${STEP_LOG[$id]}")"
      printf '| %s | %s | %s |\n' "$id" "${lg:-—}" "$st"
    done
    printf '\n- executed: %d pass, %d fail, %d skipped (of %d in plan)\n' "$pass" "$fail" "$skip" "$total"
    local line
    for line in "${SUMMARY_EXTRA_LINES[@]}"; do
      printf -- '- %s\n' "$line"
    done
    printf -- '- PAT leak scan (grep -F -c -f <pat> over every evidence file): %s\n' \
      "$([ "$LEAK_SCAN_RC" -eq 0 ] && echo "0 hits in every file" || echo "HITS FOUND — run failed")"
    printf '\nOVERALL: %s\n' "$([ "$overall" -eq 0 ] && echo PASS || echo FAIL)"
  } >"$path"

  printf '\n'
  printf '==================================================================\n'
  printf '  qoder slice-c harness — summary (run %d)\n' "$n"
  printf '==================================================================\n'
  printf '  %-28s %-34s %s\n' "STEP" "LOG" "STATUS"
  for id in "${PLAN_IDS[@]}"; do
    local st="${STEP_STATUS[$id]:-SKIPPED}"
    local lg=""
    [ -n "${STEP_LOG[$id]:-}" ] && lg="$(basename "${STEP_LOG[$id]}")"
    printf '  %-28s %-34s %s\n' "$id" "${lg:-—}" "$st"
  done
  local line
  for line in "${SUMMARY_EXTRA_LINES[@]}"; do
    printf '  note: %s\n' "$line"
  done
  printf '  summary file: %s\n' "$path"
  printf '  OVERALL: %s\n' "$([ "$overall" -eq 0 ] && echo PASS || echo FAIL)"
  printf '==================================================================\n'
}

# Computes the overall exit code from the plan statuses (no step body involved).
overall_rc() {
  local rc=0 id
  for id in "${PLAN_IDS[@]}"; do
    [ "${STEP_STATUS[$id]:-SKIPPED}" = "FAIL" ] && rc=1
  done
  [ "$LEAK_SCAN_RC" -eq 0 ] || rc=1
  return "$rc"
}

# ---------------------------------------------------------------------------
# Dry-run (must execute NOTHING and exit 0)
# ---------------------------------------------------------------------------
# True when the id belongs to the executed plan (SELECTED is filled by run-all.sh).
is_selected() {
  local want="$1" id
  for id in "${SELECTED[@]:-}"; do
    [ "$id" = "$want" ] && return 0
  done
  return 1
}

print_dry_run() {
  local id
  printf '\n[dry-run] qoder slice-c harness plan — nothing is executed\n'
  printf '[dry-run] evidence dir: %s\n' "$EVIDENCE_DIR"
  printf '[dry-run] git: %s\n\n' "$(git_sha)"
  for id in "${PLAN_IDS[@]}"; do
    if is_selected "$id"; then
      printf '[dry-run] %s  (would run)\n' "$id"
    else
      printf '[dry-run] %s  (not in this run'"'"'s plan)\n' "$id"
    fi
    printf '          cmd: %s\n' "${STEP_CMD[$id]:-<not set>}"
    printf '          cwd: %s\n' "${STEP_CWD[$id]:-<not set>}"
    printf '          log: %s\n' "$(next_log_path "$id")"
  done
  printf '\n[dry-run] preflight: %s\n' "${PREFLIGHT_HINT:-<not set>}"
  printf '[dry-run] done — no step was executed, no health/credential probe was attempted.\n'
}
