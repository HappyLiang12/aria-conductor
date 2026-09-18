#!/usr/bin/env bash
# =============================================================================
# Task C6b — scenario bodies for the qoder slice-c harness.
#
# Contains:
#   - the spec-owned step runner (npx playwright, cwd agent-control-tower/act-dashboard);
#   - the two bash-owned live scenarios:
#       S5  — cancel a run while an ACP permission ask is pending, then prove the
#             sandbox survived and the CLI stopped gracefully;
#       S10 — kill the backend while an ask is pending, restart the stack, prove the
#             startup sweep marked the run interrupted and expired the ask without
#             replay;
#   - the regression lane bodies;
#   - step_body <id>, the dispatcher used by common.sh's run_step.
#
# Everything in this file was written from source reading; S5/S10 must be
# re-verified live by the coordinator (see task-C6b-report.md, "live verification").
# Source anchors (2026-09-18):
#   - S5 cancel:   act-agent/.../controller/RunController.java:84-86
#                  -> RunService.cancelRun (act-agent/.../service/RunService.java:146-157)
#                     sets CANCELLED and publishes RunCompletedEvent;
#                  -> AcpPermissionCoordinator.onRunCompleted
#                     (act-execution/.../approval/AcpPermissionCoordinator.java:545-555)
#                     expires the run's PENDING ACP asks with REASON_RUN_ENDED and
#                     delivers the cancel (DELIVERY_CANCELLED, or DELIVERED when the
#                     live bridge accepted it — both terminal).
#                  Graceful stop vs sandbox kill: QoderAdkProvider.stopRun
#                  (act-execution/.../adk/qoder/QoderAdkProvider.java:449-470) — the
#                  sandbox is killed ONLY when pump.awaitStopped(stopGrace) fails.
#   - S10 startup sweep: AgentLoopEngine.recoverOrphanedRuns
#                  (act-execution/.../engine/AgentLoopEngine.java:357-378) marks
#                  RUNNING/INITIALIZING runs FAILED with "Run orphaned by backend
#                  restart"; AcpPermissionCoordinator.expireInterruptedPendingApprovals
#                  (…/AcpPermissionCoordinator.java:476-490) expires PENDING ACP asks
#                  with REASON_RESTART_INTERRUPTED = "restart-interrupted (no session
#                  replay)". Both are ApplicationReadyEvent listeners whose relative
#                  order is NOT pinned, so the losing reason is REASON_RUN_ENDED =
#                  "run ended before decision" (published by the sweep through
#                  RunCompletedEvent) — the harness accepts exactly these two.
# =============================================================================

DASHBOARD_DIR="$REPO_ROOT/agent-control-tower/act-dashboard"
MODULE_DIR="$REPO_ROOT/agent-control-tower"

POLL_INTERVAL="${QODER_E2E_POLL_INTERVAL_SEC:-5}"

# ---------------------------------------------------------------------------
# small step-body helpers
# ---------------------------------------------------------------------------
require_cmd_step() { # cmd -> records a step failure when missing
  if ! command -v "$1" >/dev/null 2>&1; then
    step_fail "required command not found on PATH: $1"
    return 1
  fi
  return 0
}

json_safe() { # value, what
  case "$1" in
    *'"'* | *'\'*)
      die "$2 contains a double quote or a backslash; refusing to build a JSON literal with it"
      ;;
  esac
}

# ---------------------------------------------------------------------------
# Spec-owned steps: npx playwright test <filter> --grep "<S<n>:>"
#
# The frozen env contract only says "export API_URL/BASE_URL for the playwright
# steps"; the specs additionally gate on QODER_E2E=1 (skip otherwise), read
# QODER_E2E_PAT (PAT gate) and QODER_E2E_MODEL (their own zero-credit guard), so
# those three are inherited from the runner's exported environment (QODER_E2E_PAT
# is never written into any command text or log line — see run-all.sh).
# CI=1 keeps the dashboard specs headless (playwright.config.ts: headless: !!CI).
# ---------------------------------------------------------------------------
run_spec_step() { # grep-pattern (e.g. "S1:"), file filter (e.g. e2e/qoder-*.spec.ts)
  local grep_pat="$1" filter="$2"
  ( cd "$DASHBOARD_DIR" &&
    CI=1 QODER_E2E=1 API_URL="$API_URL" BASE_URL="$BASE_URL" EVIDENCE_DIR="$EVIDENCE_DIR" \
      npx playwright test $filter --grep "$grep_pat" ) 2>&1 | tee -a "$SLOG"
  return ${PIPESTATUS[0]}
}

# ---------------------------------------------------------------------------
# Seed helpers (S5/S10 share the shape; mirrors e2e/fixtures.ts:202-214)
# ---------------------------------------------------------------------------

# Creates a qoder ADK agent with the task-level approval gate explicitly disabled
# (AgentLoopEngine.java:806-828 reads config.taskApprovalRequired === false), so the
# run cannot be blocked on the legacy task gate and the only pending ask is the ACP
# permission ask this scenario is about. Echoes the agent id (empty on failure).
create_qoder_agent() { # outfile, name
  local out="$1" name="$2" code
  json_safe "$name" "agent name"
  printf '{"name":"%s","agentType":"ADK","role":"dev","model":"efficient","adkProvider":"qoder","config":{"taskApprovalRequired":false}}' \
    "$name" >"$out.req.json"
  step_note "POST /api/v1/agents request: $(cat "$out.req.json")"
  code="$(api_post_json "/agents" "$out.req.json" "$out" || true)"
  step_note "POST /api/v1/agents -> HTTP ${code:-<transport error>}"
  step_note "response: $(head -c 500 "$out" 2>/dev/null)"
  case "$code" in
    200 | 201) ;;
    *) return 1 ;;
  esac
  json_get "$out" id
}

# Write-triggering prompt. Mirrors the A4-verified recipe (e2e/qoder/slice-a/
# 04-permissions.md: "each turn prompts the CLI to write a new file with the Write
# tool"; the resulting session/request_permission has kind=edit, tool=Write), with
# the target under /workspace so the write lands inside the authorized workspace.
write_trigger_prompt() { # label
  printf 'Use the Write tool (not a shell command) to create the file /workspace/%s.txt whose content is exactly: ready' "$1"
}

# Starts a run for the agent. Echoes the run id (empty on failure).
start_qoder_run() { # outfile, agentId, prompt
  local out="$1" agent="$2" prompt="$3" code
  json_safe "$agent" "agent id"
  json_safe "$prompt" "run prompt"
  printf '{"agentId":"%s","promptSeed":"%s","maxIterations":3}' "$agent" "$prompt" >"$out.req.json"
  step_note "POST /api/v1/runs request: $(cat "$out.req.json")"
  code="$(api_post_json "/runs" "$out.req.json" "$out" || true)"
  step_note "POST /api/v1/runs -> HTTP ${code:-<transport error>}"
  step_note "response: $(head -c 500 "$out" 2>/dev/null)"
  case "$code" in
    200 | 201 | 202) ;;
    *) return 1 ;;
  esac
  json_get "$out" id
}

# ---------------------------------------------------------------------------
# Bounded polls (all of them print progress into the step log via step_note —
# their stdout is reserved for the captured result)
# ---------------------------------------------------------------------------

# Waits for the run's first PENDING ACP_PERMISSION ask. Echoes the approval id.
wait_for_pending_ask() { # runId, timeout_sec
  local runid="$1" timeout="$2" start=$SECONDS code id
  while [ "$(elapsed_since "$start")" -lt "$timeout" ]; do
    code="$(api_get "/approvals?status=PENDING" "$WORK_DIR/approvals.json" || true)"
    if [ "$code" = "200" ]; then
      id="$(json_find_pending_acp_ask "$WORK_DIR/approvals.json" "$runid")"
      if [ -n "$id" ]; then
        printf '%s' "$id"
        return 0
      fi
      step_note "poll /approvals?status=PENDING (t+$((SECONDS - start))s): no ACP ask for run $runid yet"
    else
      step_note "poll /approvals?status=PENDING -> HTTP ${code:-<transport error>} (retrying)"
    fi
    sleep "$POLL_INTERVAL"
  done
  # Diagnostic for the timeout path: what does the pending list / the run look like?
  api_get "/approvals?status=PENDING" "$WORK_DIR/approvals.timeout.json" >/dev/null 2>&1 || true
  step_note "pending approvals at timeout: $(head -c 600 "$WORK_DIR/approvals.timeout.json" 2>/dev/null)"
  return 1
}

# Waits for the run to reach a terminal state. Echoes "<status>|<errorMessage>".
wait_run_terminal() { # runId, timeout_sec
  local runid="$1" timeout="$2" start=$SECONDS code st f="$WORK_DIR/run.json"
  while [ "$(elapsed_since "$start")" -lt "$timeout" ]; do
    code="$(api_get "/runs/$runid" "$f" || true)"
    if [ "$code" = "200" ]; then
      st="$(json_get "$f" status)"
      step_note "poll GET /runs/$runid -> ${st:-<empty>} (t+$((SECONDS - start))s)"
      case "$st" in
        COMPLETED | FAILED | ABORTED | CANCELLED)
          printf '%s|%s' "$st" "$(json_get "$f" errorMessage)"
          return 0
          ;;
      esac
    else
      step_note "poll GET /runs/$runid -> HTTP ${code:-<transport error>} (retrying)"
    fi
    sleep "$POLL_INTERVAL"
  done
  return 1
}

# Waits for an approval to leave PENDING. Echoes
# "<status>|<reason>|<deliveryState>|<decidedAt>".
wait_approval_terminal() { # approvalId, timeout_sec
  local id="$1" timeout="$2" start=$SECONDS code st f="$WORK_DIR/approval.json"
  while [ "$(elapsed_since "$start")" -lt "$timeout" ]; do
    code="$(api_get "/approvals/$id" "$f" || true)"
    if [ "$code" = "200" ]; then
      st="$(json_get "$f" status)"
      step_note "poll GET /approvals/$id -> ${st:-<empty>} delivery=$(json_get "$f" deliveryState) (t+$((SECONDS - start))s)"
      case "$st" in
        PENDING) ;;
        '')
          step_note "approval body unreadable/empty: $(head -c 300 "$f" 2>/dev/null)"
          ;;
        *)
          printf '%s|%s|%s|%s' "$st" "$(json_get "$f" reason)" "$(json_get "$f" deliveryState)" "$(json_get "$f" decidedAt)"
          return 0
          ;;
      esac
    else
      step_note "poll GET /approvals/$id -> HTTP ${code:-<transport error>} (retrying)"
    fi
    sleep "$POLL_INTERVAL"
  done
  return 1
}

# Approval snapshot for the "no replay" checks: "<count>|<status>|<deliveryState>|<decidedAt>"
approval_facts() { # approvalId, outfile
  local id="$1" out="$2" code
  code="$(api_get "/approvals/$id" "$out" || true)"
  printf '%s|%s|%s|%s' "$code" "$(json_get "$out" status)" "$(json_get "$out" deliveryState)" "$(json_get "$out" decidedAt)"
}

# ---------------------------------------------------------------------------
# S5 — cancel during a pending permission ask
# ---------------------------------------------------------------------------
scenario_s5_cancel_during_pending() {
  local rc=0 wf="$WORK_DIR/s5"
  local ask_timeout="${QODER_E2E_S5_ASK_TIMEOUT_SEC:-180}"
  local cancel_timeout="${QODER_E2E_S5_CANCEL_TIMEOUT_SEC:-90}"
  local expire_timeout="${QODER_E2E_S5_ASK_EXPIRE_TIMEOUT_SEC:-120}"
  local proc_timeout="${QODER_E2E_S5_PROC_TIMEOUT_SEC:-120}"
  local proc_confirm_timeout="${QODER_E2E_S5_PROC_CONFIRM_TIMEOUT_SEC:-60}"
  local label="c6b-s5-$(date -u +%Y%m%d-%H%M%S)"
  mkdir -p "$wf"

  require_cmd_step node || rc=1
  require_cmd_step curl || rc=1
  require_cmd_step "$CONTAINER_RUNTIME" || rc=1
  [ "$rc" -eq 0 ] || return 1

  # ---- 0. baseline: sandbox containers before this run ------------------------
  log_cmd "$CONTAINER_RUNTIME ps --filter ancestor=$QODER_SANDBOX_IMAGE --format '{{.ID}} {{.Names}} {{.Image}}'   (baseline, before the run)"
  sandbox_container_ids >"$wf/ids.before.txt"
  sandbox_container_lines >"$wf/containers.before.txt"
  step_note "0. baseline sandboxes from image $QODER_SANDBOX_IMAGE:"
  while IFS= read -r l; do step_note "     $l"; done <"$wf/containers.before.txt"

  # ---- 1. seed the qoder agent ------------------------------------------------
  step_note "1. seed: POST /api/v1/agents {agentType:ADK, adkProvider:qoder, config:{taskApprovalRequired:false}}"
  local agent_id
  agent_id="$(create_qoder_agent "$wf/agent.json" "$label")"
  if [ -z "$agent_id" ]; then
    step_fail "could not create the qoder ADK agent (see the POST /agents response above)"
    return 1
  fi
  step_note "   agent id: $agent_id"

  # ---- 2. start a run with a write-triggering prompt --------------------------
  local prompt
  prompt="$(write_trigger_prompt "$label")"
  step_note "2. start run: POST /api/v1/runs {agentId:$agent_id, promptSeed:<write trigger>}"
  local run_id
  run_id="$(start_qoder_run "$wf/run.json" "$agent_id" "$prompt")"
  if [ -z "$run_id" ]; then
    step_fail "could not start the qoder run (see the POST /runs response above)"
    return 1
  fi
  step_note "   run id: $run_id"

  # ---- 3. wait for the pending ACP ask ---------------------------------------
  step_note "3. waiting (<=${ask_timeout}s) for a PENDING ACP_PERMISSION ask of run $run_id"
  local ask_id
  ask_id="$(wait_for_pending_ask "$run_id" "$ask_timeout")"
  if [ -z "$ask_id" ]; then
    step_fail "no PENDING ACP_PERMISSION ask appeared for run $run_id within ${ask_timeout}s (write not triggered, or the run failed before asking — run state above)"
    return 1
  fi
  step_note "   pending ask id: $ask_id"

  # ---- 4. find this run's sandbox container ----------------------------------
  log_cmd "$CONTAINER_RUNTIME ps --filter ancestor=$QODER_SANDBOX_IMAGE --format '{{.ID}} {{.Names}} {{.Image}}'   (now)"
  sandbox_container_ids >"$wf/ids.after.txt"
  sandbox_container_lines >"$wf/containers.after.txt"
  local cid=""
  cid="$(comm -13 <(sort "$wf/ids.before.txt") <(sort "$wf/ids.after.txt") 2>/dev/null | tr -d '\r' | head -1)"
  if [ -n "$cid" ]; then
    step_note "4. new sandbox container for this run: $cid"
  else
    # F7: never guess between several candidates — a wrong pick would point the
    # graceful-stop proof (and every later exec probe) at another run's sandbox.
    local candidates candidate_count
    candidates="$(tr -d '\r' <"$wf/ids.after.txt" | sed '/^[[:space:]]*$/d')"
    candidate_count="$(printf '%s\n' "$candidates" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
    if [ "${candidate_count:-0}" -gt 1 ]; then
      step_fail "no NEW sandbox container appeared for this run, and ${candidate_count} containers match image $QODER_SANDBOX_IMAGE — the fallback is ambiguous, refusing to guess; candidates: $(printf '%s' "$candidates" | tr '\n' ' ')"
      return 1
    fi
    cid="$(printf '%s' "$candidates" | head -1)"
    step_note "4. no NEW container since the baseline; single candidate from the image, using it: ${cid:-<none>}"
  fi
  if [ -z "$cid" ]; then
    step_note "fallback_kill=yes (no sandbox container from $QODER_SANDBOX_IMAGE exists while the ask is pending — the sandbox was already gone)"
    step_fail "the run's sandbox container does not exist while its permission ask is pending — the sandbox was killed outside the graceful path (fallback_kill=yes)"
    return 1
  fi

  # ---- 5. positive control: the probe must SEE the waiting qodercli ----------
  # Without this control an empty post-cancel scan proves nothing. `ps`/`pgrep` are
  # absent from the pinned image (A4 evidence: "/bin/sh: 1: ps: not found"), so
  # stack.sh scans /proc comm values. Set QODER_E2E_S5_PROC_CONFIRM_TIMEOUT_SEC=0 to
  # skip the control (the negative check then only reports, and the report must say so).
  local procs="" confirm_start=$SECONDS confirm_ok=""
  if [ "$proc_confirm_timeout" -gt 0 ]; then
    while [ "$(elapsed_since "$confirm_start")" -lt "$proc_confirm_timeout" ]; do
      procs="$(qodercli_procs_in_container "$cid")"
      if [ -n "$procs" ]; then confirm_ok="yes"; break; fi
      step_note "5. positive control: no qodercli visible in $cid yet (t+$((SECONDS - confirm_start))s)"
      sleep "$POLL_INTERVAL"
    done
    if [ -n "$confirm_ok" ]; then
      step_note "5. positive control OK — qodercli waiting inside the sandbox: $(printf '%s' "$procs" | tr '\n' ' ' | head -c 300)"
    else
      step_fail "positive control FAILED: no qodercli process was observable inside sandbox $cid within ${proc_confirm_timeout}s while its permission ask is pending — an empty post-cancel scan cannot prove a graceful stop"
      rc=1
    fi
  else
    step_note "5. positive control skipped (QODER_E2E_S5_PROC_CONFIRM_TIMEOUT_SEC=0)"
  fi

  # ---- 6. cancel the run ------------------------------------------------------
  log_cmd "POST $API_URL/api/v1/runs/$run_id/cancel"
  step_note "6. cancelling run $run_id (RunController.java:84-86 -> RunService.cancelRun -> CANCELLED)"
  local code
  code="$(api_post_empty "/runs/$run_id/cancel" "$wf/cancel.json" || true)"
  step_note "   POST /runs/$run_id/cancel -> HTTP ${code:-<transport error>} body=$(head -c 300 "$wf/cancel.json" 2>/dev/null)"
  if [ "$code" != "200" ]; then
    step_fail "run cancel answered HTTP ${code:-transport-error} (expected 200)"
    rc=1
  fi

  # ---- 7. the run must reach a terminal state --------------------------------
  local facts st msg
  if facts="$(wait_run_terminal "$run_id" "$cancel_timeout")"; then
    st="${facts%%|*}"
    msg="${facts#*|}"
    step_note "7. run terminal state: $st (errorMessage='$msg')"
    case "$st" in
      CANCELLED | ABORTED) ;;
      *)
        step_fail "run reached $st, expected CANCELLED (RunService.cancelRun) or ABORTED"
        rc=1
        ;;
    esac
  else
    step_fail "run did not reach a terminal state within ${cancel_timeout}s of the cancel"
    rc=1
  fi

  # ---- 8. the ask must be EXPIRED with the cancel reason ---------------------
  local ask_st ask_reason ask_delivery ask_decided
  if facts="$(wait_approval_terminal "$ask_id" "$expire_timeout")"; then
    ask_st="${facts%%|*}"
    facts="${facts#*|}"
    ask_reason="${facts%%|*}"
    facts="${facts#*|}"
    ask_delivery="${facts%%|*}"
    ask_decided="${facts#*|}"
    step_note "8. ask terminal state: status=$ask_st reason='$ask_reason' deliveryState=$ask_delivery decidedAt=$ask_decided"
    if [ "$ask_st" != "EXPIRED" ]; then
      step_fail "ask status is $ask_st, expected EXPIRED"
      rc=1
    fi
    case "$ask_reason" in
      "run ended before decision" | "expired before decision") ;;
      *)
        step_fail "ask reason is '$ask_reason', expected 'run ended before decision' (AcpPermissionCoordinator.onRunCompleted) or 'expired before decision'"
        rc=1
        ;;
    esac
    case "$ask_delivery" in
      DELIVERED | CANCELLED) ;;
      *)
        step_fail "ask deliveryState is '$ask_delivery', expected a terminal state (DELIVERED when the live bridge accepted the cancel, CANCELLED otherwise)"
        rc=1
        ;;
    esac
  else
    step_fail "ask $ask_id did not leave PENDING within ${expire_timeout}s of the cancel"
    rc=1
  fi

  # ---- 9. sandbox survival + graceful CLI stop -------------------------------
  local fallback_kill="no" observed_clear="" proc_start=$SECONDS
  while [ "$(elapsed_since "$proc_start")" -lt "$proc_timeout" ]; do
    if ! container_exists "$cid"; then
      fallback_kill="yes"
      step_note "9. sandbox $cid disappeared during the stop window"
      break
    fi
    procs="$(qodercli_procs_in_container "$cid")"
    if [ -z "$procs" ]; then
      observed_clear="yes"
      break
    fi
    step_note "9. qodercli still present in sandbox $cid: $(printf '%s' "$procs" | tr '\n' ' ' | head -c 300)"
    sleep "$POLL_INTERVAL"
  done

  if [ "$fallback_kill" = "yes" ]; then
    step_note "fallback_kill=yes — the sandbox was removed instead of stopping the CLI gracefully (QoderAdkProvider.stopRun fallback, act-execution/.../QoderAdkProvider.java:462-470)"
    step_fail "fallback_kill=yes: the sandbox container was killed; the 'sandbox killed only by fallback' criterion failed"
    rc=1
  else
    if [ -n "$observed_clear" ]; then
      step_note "9. graceful stop proven: no qodercli process in $cid after the cancel"
      if ! container_exists "$cid"; then
        fallback_kill="yes"
        step_note "fallback_kill=yes (container vanished right after the scan)"
        step_fail "the sandbox container vanished immediately after the graceful stop was observed (fallback_kill=yes)"
        rc=1
      else
        step_note "9. sandbox $cid still exists after the cancel — cancel did not kill the sandbox"
      fi
    else
      step_note "fallback_kill=no (the container survived, the CLI did not exit within ${proc_timeout}s)"
      step_fail "qodercli was still running inside sandbox $cid ${proc_timeout}s after the cancel (probe: /proc comm scan; pgrep absent in the image) — graceful stop not proven"
      rc=1
    fi
  fi
  step_note "s5-summary: label=$label agent=$agent_id run=$run_id ask=$ask_id sandbox=$cid fallback_kill=$fallback_kill"

  return "$rc"
}

# ---------------------------------------------------------------------------
# S10 — backend restart while an ask is pending
# ---------------------------------------------------------------------------
scenario_s10_restart_interrupt() {
  local rc=0 wf="$WORK_DIR/s10"
  local ask_timeout="${QODER_E2E_S10_ASK_TIMEOUT_SEC:-180}"
  local down_timeout="${QODER_E2E_S10_DOWN_TIMEOUT_SEC:-90}"
  local start_timeout="${QODER_E2E_S10_START_TIMEOUT_SEC:-1500}"
  local run_timeout="${QODER_E2E_S10_RUN_TIMEOUT_SEC:-180}"
  local ask_expire_timeout="${QODER_E2E_S10_APPROVAL_TIMEOUT_SEC:-180}"
  local label="c6b-s10-$(date -u +%Y%m%d-%H%M%S)"
  mkdir -p "$wf"

  require_cmd_step node || rc=1
  require_cmd_step curl || rc=1
  require_cmd_step pwsh || rc=1
  require_cmd_step timeout || rc=1
  [ "$rc" -eq 0 ] || return 1

  # ---- 1. seed (same shape as S5) --------------------------------------------
  step_note "1. seed: POST /api/v1/agents (qoder ADK, taskApprovalRequired=false) + POST /api/v1/runs (write trigger)"
  local agent_id run_id
  agent_id="$(create_qoder_agent "$wf/agent.json" "$label")"
  if [ -z "$agent_id" ]; then
    step_fail "could not create the qoder ADK agent (see the POST /agents response above)"
    return 1
  fi
  run_id="$(start_qoder_run "$wf/run.json" "$agent_id" "$(write_trigger_prompt "$label")")"
  if [ -z "$run_id" ]; then
    step_fail "could not start the qoder run (see the POST /runs response above)"
    return 1
  fi
  step_note "   agent=$agent_id run=$run_id"

  # ---- 2. wait for the pending ACP ask ---------------------------------------
  step_note "2. waiting (<=${ask_timeout}s) for a PENDING ACP_PERMISSION ask of run $run_id"
  local ask_id
  ask_id="$(wait_for_pending_ask "$run_id" "$ask_timeout")"
  if [ -z "$ask_id" ]; then
    step_fail "no PENDING ACP_PERMISSION ask appeared for run $run_id within ${ask_timeout}s"
    return 1
  fi
  step_note "   pending ask id: $ask_id"

  # ---- 3. pre-kill facts ------------------------------------------------------
  local before_run before_ask
  before_run="$(api_get "/runs/$run_id" "$wf/run.before.json" >/dev/null 2>&1; printf '%s' "$(json_get "$wf/run.before.json" status)")"
  before_ask="$(api_get "/approvals/$ask_id" "$wf/ask.before.json" >/dev/null 2>&1; printf '%s|%s|%s' \
    "$(json_get "$wf/ask.before.json" status)" "$(json_get "$wf/ask.before.json" deliveryState)" "$(json_get "$wf/ask.before.json" decidedAt)")"
  step_note "3. before the kill: run=$before_run ask=$before_ask"
  case "$before_run" in
    RUNNING | INITIALIZING | PENDING) ;;
    *)
      step_fail "run status before the kill is '$before_run', expected RUNNING/INITIALIZING — the restart sweep only touches those (AgentLoopEngine.java:358-359)"
      rc=1
      ;;
  esac
  case "$before_ask" in
    "PENDING|"*) ;;
    *)
      step_fail "ask before the kill is '$before_ask', expected PENDING"
      rc=1
      ;;
  esac

  # ---- 4. kill the backend (only the pid recorded in .run/backend.pid) -------
  step_note "4. killing the backend recorded in .run/backend.pid (taskkill /T /F — the documented stop path semantics)"
  if ! kill_recorded_backend; then
    step_fail "could not kill the recorded backend — SKIPPING the remaining restart assertions"
    return 1
  fi
  if wait_backend_down "$down_timeout"; then
    step_note "   backend is down"
  else
    step_fail "the backend still answers $API_URL/actuator/health ${down_timeout}s after the kill"
    rc=1
  fi

  # ---- 5. full restart (stop.ps1 frees 5173, then the documented start) ------
  # start.ps1 phase 4 aborts when the frontend port 5173 is still held
  # (scripts/start.ps1:290-310, `throw "Port $port is in use"` on non-tty stdin), so a
  # full restart = kill (done) + stop.ps1 (recorded pids, opensandbox compose stop,
  # removes .run/) + the documented start command. The h2 database lives in
  # agent-control-tower/act-app/data/ (not .run/), so the run/ask rows survive.
  step_note "5. stop.ps1 (releases the frontend port) then start.ps1 -Provider qoder with QODER_MODEL=$MODEL"
  log_cmd "cd $REPO_ROOT && pwsh -NoProfile -File scripts/stop.ps1"
  ( cd "$REPO_ROOT" && pwsh -NoProfile -File scripts/stop.ps1 ) 2>&1 | tee -a "$SLOG"
  step_note "   stop.ps1 exit=${PIPESTATUS[0]}"

  log_cmd "cd $REPO_ROOT && QODER_MODEL=$MODEL timeout $start_timeout pwsh -NoProfile -File scripts/start.ps1 -Provider qoder   (QODER_E2E_PAT stripped from the child env)"
  ( cd "$REPO_ROOT" && env -u QODER_E2E_PAT QODER_MODEL="$MODEL" timeout "$start_timeout" \
      pwsh -NoProfile -File scripts/start.ps1 -Provider qoder ) 2>&1 | tee -a "$SLOG"
  local start_rc=${PIPESTATUS[0]}
  step_note "   start.ps1 exit=$start_rc (124 = the ${start_timeout}s timeout fired)"

  local restarted="no"
  if [ "$start_rc" -eq 0 ] && wait_backend_up "$start_timeout"; then
    restarted="yes"
    step_note "   stack restarted"
  else
    step_fail "the stack did not come back up (start.ps1 exit=$start_rc); the remaining post-restart assertions were skipped"
    rc=1
  fi

  if [ "$restarted" = "yes" ]; then
    # ---- 6. the startup sweep must have marked the run FAILED -----------------
    local facts st msg
    if facts="$(wait_run_terminal "$run_id" "$run_timeout")"; then
      st="${facts%%|*}"
      msg="${facts#*|}"
      step_note "6. run after restart: status=$st errorMessage='$msg'"
      if [ "$st" != "FAILED" ]; then
        step_fail "run status after restart is $st, expected FAILED (AgentLoopEngine.recoverOrphanedRuns)"
        rc=1
      fi
      case "$msg" in
        *"Run orphaned by backend restart"*) ;;
        *)
          step_fail "run errorMessage '$msg' does not carry the startup-sweep marker 'Run orphaned by backend restart'"
          rc=1
          ;;
      esac
    else
      step_fail "run $run_id was still not terminal ${run_timeout}s after the restart"
      rc=1
    fi

    # ---- 7. the ask must be EXPIRED with the interrupt reason, no replay ------
    local ask_st ask_reason ask_delivery ask_decided
    if facts="$(wait_approval_terminal "$ask_id" "$ask_expire_timeout")"; then
      ask_st="${facts%%|*}"
      facts="${facts#*|}"
      ask_reason="${facts%%|*}"
      facts="${facts#*|}"
      ask_delivery="${facts%%|*}"
      ask_decided="${facts#*|}"
      step_note "7. ask after restart: status=$ask_st reason='$ask_reason' deliveryState=$ask_delivery decidedAt=$ask_decided"
      if [ "$ask_st" != "EXPIRED" ]; then
        step_fail "ask status after restart is $ask_st, expected EXPIRED"
        rc=1
      fi
      case "$ask_reason" in
        "restart-interrupted (no session replay)" | "run ended before decision") ;;
        *)
          step_fail "ask reason after restart is '$ask_reason', expected 'restart-interrupted (no session replay)' (expireInterruptedPendingApprovals) or 'run ended before decision' (the run-completed sweep racing it — the two ApplicationReadyEvent listeners are unordered)"
          rc=1
          ;;
      esac
      case "$ask_delivery" in
        CANCELLED) ;;
        DELIVERED)
          step_fail "ask deliveryState after restart is DELIVERED — something delivered a decision to a dead process (replay)"
          rc=1
          ;;
        *)
          step_fail "ask deliveryState after restart is '$ask_delivery', expected the terminal CANCELLED (no bridge exists after a restart, so a delivery attempt must fail and settle as cancelled)"
          rc=1
          ;;
      esac
      if [ -z "$ask_decided" ]; then
        step_fail "ask decidedAt is empty after the expiry — the EXPIRED transition did not record a decision time"
        rc=1
      fi
    else
      step_fail "ask $ask_id was still PENDING ${ask_expire_timeout}s after the restart"
      rc=1
    fi

    # ---- 8. no replay: one ask row, stable terminal state, no new decide ------
    local count
    count="$(api_get "/approvals?status=EXPIRED" "$wf/approvals.expired.json" >/dev/null 2>&1; json_count_approvals_for_run "$wf/approvals.expired.json" "$run_id")"
    step_note "8. approval rows for run $run_id (status=EXPIRED list): ${count:-?}"
    if [ "$count" != "1" ]; then
      step_fail "expected exactly 1 approval row for run $run_id after the restart, found '$count' (a replay would create or duplicate asks)"
      rc=1
    fi
    local f1 f2
    f1="$(approval_facts "$ask_id" "$wf/ask.recheck1.json")"
    sleep 5
    f2="$(approval_facts "$ask_id" "$wf/ask.recheck2.json")"
    step_note "8. stability recheck: $f1  ->  $f2"
    if [ "$f1" != "$f2" ]; then
      step_fail "the ask record changed between two reads after the restart ($f1 -> $f2) — a delivery/replay attempt is still mutating it"
      rc=1
    fi
  fi

  step_note "s10-summary: label=$label agent=$agent_id run=$run_id ask=$ask_id restarted=$restarted"
  return "$rc"
}

# ---------------------------------------------------------------------------
# Regression lane
# ---------------------------------------------------------------------------
regression_mvn_test() {
  require_cmd_step mvn || return 1
  acquire_maven_lane
  local rc=0
  run_in_dir "$MODULE_DIR" mvn clean test -Dspring.profiles.active=h2 || rc=$?
  release_maven_lane
  return "$rc"
}

regression_mvn_verify() {
  require_cmd_step mvn || return 1
  acquire_maven_lane
  local rc=0
  run_in_dir "$MODULE_DIR" mvn verify || rc=$?
  release_maven_lane
  return "$rc"
}

regression_vitest() {
  require_cmd_step pnpm || return 1
  run_in_dir "$DASHBOARD_DIR" pnpm test
}

regression_build() {
  require_cmd_step pnpm || return 1
  run_in_dir "$DASHBOARD_DIR" pnpm build
}

# Pre-existing Playwright suites only: QODER_E2E is removed from the environment so
# every qoder-*.spec.ts skips (the specs' own gate). CI=1 keeps them headless; the
# project's api-suite 600 s timeout is part of playwright.config.ts and stays untouched.
regression_playwright() {
  require_cmd_step npx || return 1
  ( cd "$DASHBOARD_DIR" &&
    env -u QODER_E2E -u QODER_E2E_PAT -u QODER_E2E_MODEL CI=1 npx playwright test ) 2>&1 | tee -a "$SLOG"
  return ${PIPESTATUS[0]}
}

# AGENTS.md verification command (one log, two commands, && between them).
regression_container_runtime() {
  require_cmd_step pwsh || return 1
  require_cmd_step bash || return 1
  ( cd "$REPO_ROOT" &&
    MSYS_NO_PATHCONV=1 pwsh -NoProfile -File e2e/container-runtime-e2e.ps1 &&
    bash e2e/container-runtime-e2e.sh ) 2>&1 | tee -a "$SLOG"
  return ${PIPESTATUS[0]}
}

# Preflight step: rebuild the pinned sandbox image (plan Step 2 / C2 ruling R13 —
# Ensure-QoderSandboxImage never rebuilds an existing tag and the bridge dist/ is
# not committed, so a fresh image needs an explicit build).
run_rebuild_image() {
  require_cmd_step "$CONTAINER_RUNTIME" || return 1
  run_in_dir "$REPO_ROOT" "$CONTAINER_RUNTIME" build -t "$QODER_SANDBOX_IMAGE" agent-control-tower/qoder-sandbox
}

# ---------------------------------------------------------------------------
# Dispatcher (common.sh run_step calls step_body <id>)
# ---------------------------------------------------------------------------
step_body() { # id
  case "$1" in
    preflight) preflight_checks ;;
    rebuild-image) run_rebuild_image ;;
    S1 | S2 | S3 | S4 | S6 | S7 | S8 | S9 | S11) run_spec_step "${1}:" "e2e/qoder-*.spec.ts" ;;
    S5) scenario_s5_cancel_during_pending ;;
    S10) scenario_s10_restart_interrupt ;;
    S12) run_spec_step "S12:" "e2e/qoder-governance-e2e.spec.ts" ;;
    regression-mvn-test) regression_mvn_test ;;
    regression-mvn-verify) regression_mvn_verify ;;
    regression-vitest) regression_vitest ;;
    regression-build) regression_build ;;
    regression-playwright) regression_playwright ;;
    regression-container-runtime) regression_container_runtime ;;
    leak-scan)
      # The counts are both printed (stdout) and kept in the step log; the scan sets
      # LEAK_SCAN_RC for overall_rc, so it must NOT run in a pipeline subshell.
      local lf="$WORK_DIR/leakscan.out"
      assert_no_pat_in_evidence >"$lf" 2>&1
      cat "$lf" >>"$SLOG"
      cat "$lf"
      return 0
      ;;
    *)
      step_fail "step_body: no body registered for id '$1'"
      return 1
      ;;
  esac
}
