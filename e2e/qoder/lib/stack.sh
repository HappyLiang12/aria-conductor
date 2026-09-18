#!/usr/bin/env bash
# =============================================================================
# Task C6b — stack/API/container helpers for the qoder slice-c harness.
#
# Everything here is read-only against the live stack except:
#   - the credential preflight PUT (credential.sh, PAT via stdin);
#   - S10's documented restart mechanism (kill the pid recorded in
#     .run/backend.pid, then stop.ps1 + start.ps1 — see scenario_s10_*).
# The harness never kills by port and never touches a process other than the
# recorded backend pid (brief §"Scope fences").
# =============================================================================

# ---------------------------------------------------------------------------
# JSON helpers (node is a hard dependency of this repo's toolchain)
# ---------------------------------------------------------------------------
json_get() { # file, dot-path (e.g. status, optionsJson[0] not supported)
  node -e '
    const fs = require("fs");
    let v;
    try { v = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch (e) { process.exit(0); }
    for (const p of process.argv[2].split(".")) {
      if (v == null) break;
      v = (Array.isArray(v) && /^[0-9]+$/.test(p)) ? v[Number(p)] : v[p];
    }
    if (v === undefined || v === null) process.exit(0);
    process.stdout.write(typeof v === "object" ? JSON.stringify(v) : String(v));
  ' "$1" "$2"
}

json_len() { # file -> array length or ?
  node -e '
    const fs = require("fs");
    try { const d = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
          process.stdout.write(Array.isArray(d) ? String(d.length) : "?"); }
    catch (e) { process.stdout.write("?"); }
  ' "$1"
}

# First PENDING approval whose runId matches and whose source is ACP_PERMISSION
# (the ApprovalDetail shape of ApprovalController.java:75-102 carries both).
json_find_pending_acp_ask() { # listfile, runId -> approval id or empty
  node -e '
    const fs = require("fs");
    let list;
    try { list = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch (e) { process.exit(0); }
    if (!Array.isArray(list)) process.exit(0);
    const hit = list.find(a => a && a.runId === process.argv[2]
      && a.source === "ACP_PERMISSION" && a.status === "PENDING");
    if (hit) process.stdout.write(String(hit.id));
  ' "$1" "$2"
}

json_count_approvals_for_run() { # listfile, runId -> count
  node -e '
    const fs = require("fs");
    let list;
    try { list = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch (e) { process.stdout.write("?"); process.exit(0); }
    if (!Array.isArray(list)) { process.stdout.write("?"); process.exit(0); }
    process.stdout.write(String(list.filter(a => a && a.runId === process.argv[2]).length));
  ' "$1" "$2"
}

# ---------------------------------------------------------------------------
# REST helpers (the dashboard API has no auth in local-dev; e2e/fixtures.ts
# calls `${API_URL}/api/v1` the same way from Playwright)
# ---------------------------------------------------------------------------
curl_code() { # outfile url...
  curl -sS --connect-timeout 10 --max-time 60 -o "$1" -w '%{http_code}' "$2"
}

api_get() { # path, outfile -> http code
  curl_code "$2" "$API_URL/api/v1$1"
}

api_post_json() { # path, bodyfile, outfile -> http code
  curl -sS --connect-timeout 10 --max-time 60 -o "$3" -w '%{http_code}' \
    -X POST -H 'Content-Type: application/json' --data-binary @"$2" "$API_URL/api/v1$1"
}

api_post_empty() { # path, outfile -> http code
  curl -sS --connect-timeout 10 --max-time 60 -o "$2" -w '%{http_code}' \
    -X POST "$API_URL/api/v1$1"
}

backend_health_code() { # outfile -> http code or 000
  curl -sS --connect-timeout 5 --max-time 15 -o "$1" -w '%{http_code}' "$API_URL/actuator/health" 2>/dev/null || echo "000"
}

# ---------------------------------------------------------------------------
# Sandbox containers (the qoder provider creates one OpenSandbox sandbox per
# agent from the pinned image; the image tag is authoritative in
# act-app/src/main/resources/application.yml (`qoder.image`)).
# ---------------------------------------------------------------------------
sandbox_container_lines() {
  "$CONTAINER_RUNTIME" ps --filter "ancestor=$QODER_SANDBOX_IMAGE" \
    --format '{{.ID}} {{.Names}} {{.Image}}' 2>/dev/null || true
}

sandbox_container_ids() {
  sandbox_container_lines | awk 'NF {print $1}'
}

container_exists() { # id
  "$CONTAINER_RUNTIME" inspect --type container "$1" >/dev/null 2>&1
}

# qodercli processes inside a sandbox container. `ps`/`pgrep` are NOT present in
# the pinned image (node:22-slim has no procps; A4 evidence
# e2e/qoder/slice-a/04-permissions.md records `/bin/sh: 1: ps: not found`), so
# the authoritative check is a /proc scan on the process NAME (comm) — the
# bridge spawns `qodercli --acp ...` without a shell
# (qoder-sandbox/bridge/src/acp-client.ts:440-445). Matching comm avoids
# matching this very probe's shell, whose argv contains the word "qodercli".
qodercli_procs_in_container() { # container id -> matching lines (empty = none)
  "$CONTAINER_RUNTIME" exec "$1" sh -c '
    if command -v pgrep >/dev/null 2>&1; then
      pgrep -x qodercli 2>/dev/null || true
    else
      for d in /proc/[0-9]*; do
        [ -r "$d/comm" ] || continue
        n=$(cat "$d/comm" 2>/dev/null)
        case "$n" in
          qodercli*)
            c=$(tr "\0" " " <"$d/cmdline" 2>/dev/null)
            echo "pid=${d#/proc/} comm=$n $c"
            ;;
        esac
      done
    fi
  ' 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Zero-credit model guard on the ENV value (mirrors QoderSandboxHarness.java:57-61,
# 172-177). The live configured model is verified separately in the preflight.
# ---------------------------------------------------------------------------
enforce_zero_credit_env_guard() {
  case "$MODEL" in
    efficient | lite) return 0 ;;
  esac
  if [ "${QODER_E2E_ALLOW_PAID:-}" = "1" ]; then
    warn "QODER_E2E_MODEL='$MODEL' is not a zero-credit model — continuing because QODER_E2E_ALLOW_PAID=1"
    return 0
  fi
  err "QODER_E2E_MODEL='$MODEL' is not a zero-credit model {efficient, lite}; local E2E Qoder runs must use a 0.00x-credit model (plan Global Constraint — mirrors QoderSandboxHarness). Set QODER_E2E_ALLOW_PAID=1 to use a paid model explicitly."
  return 1
}

# ---------------------------------------------------------------------------
# S10 restart helpers
# ---------------------------------------------------------------------------
# The documented start command: the plan's C0.8 invocation example, with the
# zero-credit pin in the environment (Spring relaxed binding: QODER_MODEL ->
# qoder.model, QoderProperties.model, surfaced by the B8 credential GET).
START_CMD_TEXT="QODER_MODEL=efficient pwsh -NoProfile -File scripts/start.ps1 -Provider qoder"

# Kills the pwsh wrapper start.ps1 recorded in .run/backend.pid together with its
# whole tree (that wrapper is the parent of mvn spring-boot:run -> the JVM; see
# scripts/start.ps1:345-351). Read-only against the filesystem; kills ONLY that
# pid. Returns 0 when the process is gone.
kill_recorded_backend() {
  local pidfile="$REPO_ROOT/.run/backend.pid" pid rc
  if [ ! -f "$pidfile" ]; then
    err "no .run/backend.pid — this stack was not started by scripts/start.ps1; S10 cannot pick the backend to interrupt. (Start it with: $START_CMD_TEXT)"
    return 1
  fi
  pid="$(tr -d ' \r\n' <"$pidfile")"
  case "$pid" in
    '' | *[!0-9]*)
      err ".run/backend.pid does not contain a numeric pid (content='$pid')"
      return 1
      ;;
  esac
  log_cmd "taskkill /PID $pid /T /F   (the pwsh wrapper recorded in .run/backend.pid and its tree: mvn spring-boot:run + the JVM)"
  MSYS_NO_PATHCONV=1 taskkill /PID "$pid" /T /F >>"$SLOG" 2>&1
  rc=$?
  step_note "taskkill exit=$rc"
  return 0
}

wait_backend_down() { # seconds -> 0 when /actuator/health stops answering
  local deadline="$1" start=$SECONDS wf="$WORK_DIR/health"
  mkdir -p "$wf"
  while [ "$(elapsed_since "$start")" -lt "$deadline" ]; do
    local code
    code="$(backend_health_code "$wf/down.json")"
    step_note "waiting for the backend to go down: HTTP ${code:-000}"
    if [ -z "$code" ] || [ "$code" = "000" ] || [ "$code" -ge 500 ]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_backend_up() { # seconds -> 0 when health reports UP
  local deadline="$1" start=$SECONDS wf="$WORK_DIR/health"
  mkdir -p "$wf"
  while [ "$(elapsed_since "$start")" -lt "$deadline" ]; do
    local code
    code="$(backend_health_code "$wf/up.json")"
    if [ "$code" = "200" ] && grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' "$wf/up.json" 2>/dev/null; then
      step_note "backend is UP (HTTP 200, status UP)"
      return 0
    fi
    step_note "waiting for the backend health: HTTP ${code:-000}"
    sleep 5
  done
  return 1
}
