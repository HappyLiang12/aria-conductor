#!/usr/bin/env bash
# =============================================================================
# Task C6b — qoder credential preflight + PAT redaction rules (brief §"Env
# contract" / §"PAT redaction rules").
#
# The B8 API surface (agent-control-tower/.../execution/controller/
# QoderCredentialController.java):
#   GET  /api/v1/adk/providers/qoder/credential        -> {providerId, configured,
#        patMasked, updatedAt, model}   (503 KEY_NOT_CONFIGURED when the store
#        cannot encrypt/decrypt because PACK_CREDENTIAL_KEY is missing)
#   PUT  /api/v1/adk/providers/qoder/credential {"pat":...} -> same masked shape
#
# Hard rules implemented here:
#   - the PAT never reaches argv (the PUT body is piped via stdin: `printf ...
#     | curl --data-binary @-`), never stdout/stderr, never a log file;
#   - the PUT/GET responses are asserted for the mask shape and MUST NOT contain
#     the PAT — a response echoing it fails the preflight;
#   - the final leak scan greps every evidence file for the PAT (fixed-string,
#     count only) and fails the run when any count is non-zero.
# =============================================================================

credential_url() { printf '%s/api/v1/adk/providers/qoder/credential' "$API_URL"; }

# PUT QODER_E2E_PAT into the runtime credential store. Prints the HTTP code.
# The PAT travels on stdin only (brief's exact pipe form).
put_pat() { # outfile
  local out="$1"
  printf '{"pat":"%s"}' "$QODER_E2E_PAT" | curl -sS --connect-timeout 10 --max-time 60 \
    -o "$out" -w '%{http_code}' \
    -X PUT -H 'Content-Type: application/json' --data-binary @- "$(credential_url)"
}

# Fails (dies) when the given response body contains the PAT. Never prints body.
# The PAT must never reach the argv of any forked process: it is written to a chmod
# 600 temp file and handed to grep as a pattern FILE (same mechanism as the leak scan).
assert_body_free_of_pat() { # file, what
  local file="$1" what="$2" patfile found=0
  if [ -s "$file" ]; then
    patfile="$(mktemp "${TMPDIR:-/tmp}/c6b-pat-assert.XXXXXX")" || die "cannot create the PAT pattern file for the body check"
    chmod 600 "$patfile" 2>/dev/null || true
    trap 'rm -f "$patfile"' RETURN
    printf '%s' "$QODER_E2E_PAT" >"$patfile"
    if grep -F -q -f "$patfile" "$file"; then found=1; fi
    rm -f "$patfile"
    trap - RETURN
  fi
  if [ "$found" -eq 1 ]; then
    die "$what echoed the PAT back — refusing to continue (never log a credential response verbatim)"
  fi
}

# Preflight: stack health + credential configured + live zero-credit model.
# Runs as the dedicated `preflight` step; a failure here aborts the run per the
# brief (env/preflight failures are the only mid-plan aborts).
preflight_checks() {
  local wf="$WORK_DIR/preflight" rc=0
  local cred_file
  mkdir -p "$wf"
  cred_file="$wf/cred0.json"

  # ---- 1. stack health ------------------------------------------------------
  log_cmd "GET $API_URL/actuator/health (expect '\"status\":\"UP\"')"
  local code body="$wf/health.json"
  code="$(curl -sS --connect-timeout 10 --max-time 30 -o "$body" -w '%{http_code}' "$API_URL/actuator/health" || true)"
  step_note "backend health: HTTP ${code:-<transport error>} body=$(tr -d '\n' <"$body" 2>/dev/null | head -c 200)"
  if [ "$code" != "200" ] || ! grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' "$body" 2>/dev/null; then
    step_fail "backend not healthy at $API_URL/actuator/health (HTTP ${code:-transport-error})"
    print_start_command_hint
    return 1
  fi

  log_cmd "GET $BASE_URL (dashboard reachable)"
  code="$(curl -sS --connect-timeout 10 --max-time 30 -o "$wf/dashboard.html" -w '%{http_code}' "$BASE_URL" || true)"
  step_note "dashboard: HTTP ${code:-<transport error>}"
  if [ -z "$code" ] || [ "$code" -ge 400 ]; then
    step_fail "dashboard not reachable at $BASE_URL (HTTP ${code:-transport-error})"
    print_start_command_hint
    rc=1
  fi

  log_cmd "GET $OPENSANDBOX_URL/health (OpenSandbox server reachable)"
  code="$(curl -sS --connect-timeout 10 --max-time 30 -o "$wf/opensandbox.json" -w '%{http_code}' "$OPENSANDBOX_URL/health" || true)"
  step_note "opensandbox: HTTP ${code:-<transport error>} body=$(tr -d '\n' <"$wf/opensandbox.json" 2>/dev/null | head -c 120)"
  if [ -z "$code" ] || [ "$code" -ge 400 ]; then
    step_fail "OpenSandbox server not reachable at $OPENSANDBOX_URL/health (HTTP ${code:-transport-error})"
    print_start_command_hint
    rc=1
  fi

  # ---- 2. credential configured (load the PAT through the B8 API if needed) --
  log_cmd "GET $(credential_url) (masked status)"
  code="$(curl -sS --connect-timeout 10 --max-time 30 -o "$wf/cred0.json" -w '%{http_code}' "$(credential_url)" || true)"
  step_note "credential status: HTTP ${code:-<transport error>} body=$(head -c 300 "$wf/cred0.json" 2>/dev/null)"
  if [ "$code" = "503" ]; then
    step_fail "credential store unreachable (HTTP 503 KEY_NOT_CONFIGURED): PACK_CREDENTIAL_KEY is missing — the B8 store refuses the development fallback; set the key and restart the backend"
    return 1
  fi
  if [ "$code" != "200" ]; then
    step_fail "credential status answered HTTP ${code:-transport-error} (expected 200; a 404 usually means the running backend predates the B8 credential API — restart it with the documented command below)"
    print_start_command_hint
    return 1
  fi
  assert_body_free_of_pat "$wf/cred0.json" "the credential GET response" || return 1

  local configured masked want_masked reloaded=""
  configured="$(json_get "$wf/cred0.json" configured)"
  masked="$(json_get "$wf/cred0.json" patMasked)"
  # The store must hold THIS run's PAT, not merely some credential: a leftover
  # credential would make S6's mask assertion fail even though masking works.
  # Compare tails only — the value and the tail are never logged.
  want_masked="****${QODER_E2E_PAT: -4}"
  if [ "$configured" = "true" ] && [ "$masked" != "$want_masked" ]; then
    reloaded="yes"
  fi
  if [ "$configured" != "true" ] || [ -n "$reloaded" ]; then
    log_cmd "PUT $(credential_url)   (body piped via stdin from \$QODER_E2E_PAT — never argv, never echoed)"
    code="$(put_pat "$wf/cred_put.json" || true)"
    step_note "credential PUT: HTTP ${code:-<transport error>} body=$(head -c 300 "$wf/cred_put.json" 2>/dev/null)"
    if [ "$code" != "200" ]; then
      step_fail "credential PUT answered HTTP ${code:-transport-error} (expected 200 with the masked status)"
      return 1
    fi
    assert_body_free_of_pat "$wf/cred_put.json" "the credential PUT response" || return 1
    code="$(curl -sS --connect-timeout 10 --max-time 30 -o "$wf/cred1.json" -w '%{http_code}' "$(credential_url)" || true)"
    assert_body_free_of_pat "$wf/cred1.json" "the credential re-GET response" || return 1
    configured="$(json_get "$wf/cred1.json" configured)"
    cred_file="$wf/cred1.json"
    step_note "credential re-check: HTTP ${code:-<transport error>} configured=$configured"
    if [ -n "$reloaded" ]; then
      step_note "credential store did not hold the run PAT — reloaded (value never logged)"
    fi
  fi
  if [ "$configured" != "true" ]; then
    step_fail "the qoder runtime credential is not configured after loading QODER_E2E_PAT (design §6.1 / plan C0.4)"
    return 1
  fi

  # ---- 3. live model must be a zero-credit pin ------------------------------
  LIVE_MODEL="$(json_get "$cred_file" model)"
  step_note "live configured model: QODER_MODEL='$LIVE_MODEL'"
  log_cmd "live model pin check: '$LIVE_MODEL' in {efficient, lite} (plan Global Constraint)"
  case "$LIVE_MODEL" in
    efficient | lite) : ;;
    *)
      if [ "${QODER_E2E_ALLOW_PAID:-}" = "1" ]; then
        warn "live model '$LIVE_MODEL' is not zero-credit — continuing because QODER_E2E_ALLOW_PAID=1"
      else
        step_fail "the RUNNING backend is configured with model '$LIVE_MODEL', not a zero-credit model (efficient|lite). The plan's zero-credit Global Constraint forbids this run: restart the stack with QODER_MODEL=efficient. Set QODER_E2E_ALLOW_PAID=1 only to use a paid model explicitly."
        return 1
      fi
      ;;
  esac

  return "$rc"
}

print_start_command_hint() {
  step_note "stack is not up — documented start command (from the repository root):"
  step_note "  PACK_CREDENTIAL_KEY=... APPROVALS_TIMEOUT_MS=120000 QODER_MODEL=efficient pwsh -NoProfile -File scripts/start.ps1 -Provider qoder"
  step_note "  PACK_CREDENTIAL_KEY is required for the credential API (without it every credential route answers 503 KEY_NOT_CONFIGURED)"
  step_note "  APPROVALS_TIMEOUT_MS must be <= 180000 for S4 (the expiry scenario needs a short approval TTL)"
  err "start the stack first (from the repository root): PACK_CREDENTIAL_KEY=... APPROVALS_TIMEOUT_MS=120000 QODER_MODEL=efficient pwsh -NoProfile -File scripts/start.ps1 -Provider qoder"
}

# ---------------------------------------------------------------------------
# Final leak scan (brief: "write the PAT to a chmod 600 temp file, run
# `grep -c -f <tmpfile> \"$EVIDENCE_DIR\"/*` (count-only output — all counts must
# be 0), delete the temp file. Print only the counts.")
# ---------------------------------------------------------------------------
assert_no_pat_in_evidence() {
  LEAK_SCAN_RC=0
  LEAK_SCAN_OFFENDERS=""
  [ -n "${QODER_E2E_PAT:-}" ] || { SUMMARY_EXTRA_LINES+=("PAT leak scan skipped: QODER_E2E_PAT not set"); return 0; }

  local patfile total=0 hits=0 f c
  patfile="$(mktemp "${TMPDIR:-/tmp}/c6b-pat.XXXXXX")" || die "cannot create the leak-scan temp file"
  chmod 600 "$patfile" 2>/dev/null || true
  printf '%s' "$QODER_E2E_PAT" >"$patfile"

  printf '\n# PAT leak scan (fixed-string, count only; the pattern file is deleted right after)\n'
  printf '# pattern file: %s (chmod 600, deleted after the scan)\n' "$patfile"

  # Every regular file under the evidence dir (logs, summaries, spec artifacts).
  while IFS= read -r f; do
    c="$(grep -F -c -f "$patfile" "$f" 2>/dev/null || true)"
    [ -n "$c" ] || c=0
    total=$((total + 1))
    printf '# leak-scan: %s -> %s\n' "${f#"$EVIDENCE_DIR"/}" "$c"
    if [ "$c" != "0" ]; then
      hits=$((hits + 1))
      LEAK_SCAN_OFFENDERS="$LEAK_SCAN_OFFENDERS ${f#"$EVIDENCE_DIR"/}"
    fi
  done < <(find "$EVIDENCE_DIR" -type f 2>/dev/null | sort)

  rm -f "$patfile"
  printf '# leak-scan: %d file(s) scanned, %d file(s) contain the PAT\n' "$total" "$hits"

  if [ "$hits" -ne 0 ]; then
    LEAK_SCAN_RC=1
    err "PAT leak scan FAILED — the PAT appears in:$LEAK_SCAN_OFFENDERS"
    SUMMARY_EXTRA_LINES+=("PAT leak scan: HITS in$LEAK_SCAN_OFFENDERS — evidence must be regenerated after the leak is fixed")
  else
    SUMMARY_EXTRA_LINES+=("PAT leak scan: 0 hits across $total evidence file(s)")
  fi
}
