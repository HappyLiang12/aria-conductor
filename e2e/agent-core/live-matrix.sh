#!/usr/bin/env bash
# Live four-combination matrix runner (Task 20) — POSIX shell entry point.
#
#   bash e2e/agent-core/live-matrix.sh --core qoder --mode HOST --model efficient \
#     --workspace <dir> --repo <git repository> --evidence <file> [--pat-file <file>] \
#     [--paid-opt-in] [--scenarios a,b] [--timeout-ms n] [--expiry-budget-ms n] \
#     [--backend-task-deadline-minutes n] [--base-url url] [--no-backend]
#
# Fails closed BEFORE launching anything: every required prerequisite (credential, CLI binary,
# container image / OpenSandbox server, base repository, toolchain) is checked here and a missing
# one is a named refusal, never a skip. The Qoder core is pinned to `efficient` unless the
# operator passes --paid-opt-in (or ARIA_LIVE_PAID_MODEL_OPT_IN=1).
#
# The Qoder PAT is read from a file, never printed, never passed in argv: this script only checks
# that the file exists and is non-empty, and the Node driver reads it into memory.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

CORE=""; MODE=""; MODEL=""; WORKSPACE=""; REPO=""; EVIDENCE=""; PAT_FILE=""
PAID_OPT_IN="${ARIA_LIVE_PAID_MODEL_OPT_IN:-0}"
PASSTHRU=()
QODER_PINNED_MODEL="efficient"
SANDBOX_ENDPOINT="${ARIA_LIVE_SANDBOX_URL:-http://127.0.0.1:8090}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --core) CORE="${2:-}"; shift 2 ;;
        --mode) MODE="$(printf '%s' "${2:-}" | tr '[:lower:]' '[:upper:]')"; shift 2 ;;
        --model) MODEL="${2:-}"; shift 2 ;;
        --workspace) WORKSPACE="${2:-}"; shift 2 ;;
        --repo) REPO="${2:-}"; shift 2 ;;
        --evidence) EVIDENCE="${2:-}"; shift 2 ;;
        --pat-file) PAT_FILE="${2:-}"; shift 2 ;;
        --paid-opt-in) PAID_OPT_IN="1"; PASSTHRU+=("--paid-opt-in"); shift ;;
        --scenarios|--timeout-ms|--base-url|--expiry-budget-ms|--backend-task-deadline-minutes) PASSTHRU+=("$1" "${2:-}"); shift 2 ;;
        --no-backend|--keep-backend) PASSTHRU+=("$1"); shift ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) echo "live-matrix: unknown argument: $1" >&2; exit 2 ;;
    esac
done

refuse() {
    echo "live-matrix: MISSING PREREQUISITE: $1" >&2
    echo "live-matrix refused before launching anything (core=${CORE:-<unset>} mode=${MODE:-<unset>})" >&2
    exit 2
}

blocked() {
    for reason in "$@"; do
        echo "live-matrix: MISSING PREREQUISITE: $reason" >&2
    done
    echo "live-matrix BLOCKED by the environment before launching anything (core=${CORE:-<unset>} mode=${MODE:-<unset>})" >&2
    exit 3
}

[[ -n "$CORE" ]] || refuse "--core is required"
[[ -n "$MODE" ]] || refuse "--mode is required"
[[ -n "$MODEL" ]] || MODEL="$QODER_PINNED_MODEL"
[[ -n "$WORKSPACE" ]] || refuse "--workspace (the Direct-mode working directory) is required"
[[ -n "$REPO" ]] || refuse "--repo (the operator-admitted git repository) is required"
[[ -n "$EVIDENCE" ]] || refuse "--evidence (the raw output destination) is required"

case "$CORE" in qoder|opencode) ;; *) refuse "--core must be qoder or opencode (got $CORE)" ;; esac
case "$MODE" in HOST|SANDBOX) ;; *) refuse "--mode must be HOST or SANDBOX (got $MODE)" ;; esac

if [[ "$CORE" == "qoder" && "$MODEL" != "$QODER_PINNED_MODEL" && "$PAID_OPT_IN" != "1" ]]; then
    refuse "model-pin refusal: the qoder core is pinned to '$QODER_PINNED_MODEL' (requested '$MODEL'); a paid model needs the explicit opt-in (--paid-opt-in or ARIA_LIVE_PAID_MODEL_OPT_IN=1)"
fi
PASSTHRU+=("--model" "$MODEL")

# ── credential prerequisite ──
if [[ "$CORE" == "qoder" ]]; then
    PAT_FILE="${PAT_FILE:-${ARIA_LIVE_PAT_FILE:-${ARIA_PROBE_PAT_FILE:-${USERPROFILE:-$HOME}/AppData/Local/aria-conductor-secrets/.qoder-pat}}}"
    if [[ ! -f "$PAT_FILE" ]]; then
        refuse "qoder runtime credential missing: no readable PAT file at $PAT_FILE (--pat-file/ARIA_LIVE_PAT_FILE)"
    fi
    if [[ ! -s "$PAT_FILE" ]]; then
        refuse "qoder runtime credential missing: the PAT file at $PAT_FILE is empty"
    fi
    PASSTHRU+=("--pat-file" "$PAT_FILE")
else
    if [[ -z "${DEEPSEEK_API_KEY:-}" && -z "${LLM_API_KEY:-}" && -z "${OPENCODE_API_KEY:-}" && ! -s "$REPO_ROOT/agent-control-tower/.env" ]]; then
        refuse "opencode model-provider credential missing: no run-owned credential in this environment (DEEPSEEK_API_KEY/LLM_API_KEY/OPENCODE_API_KEY unset and agent-control-tower/.env empty)"
    fi
fi

# ── CLI / binary prerequisite ──
if [[ "$CORE" == "qoder" ]]; then
    QODER_CLI="${ARIA_LIVE_QODER_CLI:-${ARIA_PROBE_QODER_CLI:-}}"
    if [[ -z "$QODER_CLI" ]]; then
        for candidate in "$USERPROFILE/.qoder/bin/qodercli/qodercli.exe" "$HOME/.qoder/bin/qodercli/qodercli.exe"; do
            [[ -f "$candidate" ]] && QODER_CLI="$candidate" && break
        done
    fi
    [[ -n "$QODER_CLI" && -f "$QODER_CLI" ]] || refuse "qoder CLI binary missing: no qodercli executable found (ARIA_LIVE_QODER_CLI or the operator install path)"
else
    command -v opencode >/dev/null 2>&1 || refuse "opencode binary missing: no opencode executable on PATH (ARIA_LIVE_OPENCODE)"
fi

# ── SANDBOX prerequisite: container runtime + OpenSandbox server + image ──
if [[ "$MODE" == "SANDBOX" ]]; then
    sandbox_unmet=()
    if ! curl -s -o /dev/null -m 10 "$SANDBOX_ENDPOINT/health"; then
        sandbox_unmet+=("OpenSandbox server not reachable at $SANDBOX_ENDPOINT (no 2xx /health response)")
    fi
    images=""
    for rt in docker podman; do
        if command -v "$rt" >/dev/null 2>&1; then
            images="$images$( "$rt" images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -i sandbox || true)"
        fi
    done
    [[ -n "$images" ]] || sandbox_unmet+=("no sandbox image present in the local docker/podman image stores")
    if [[ ${#sandbox_unmet[@]} -gt 0 ]]; then
        blocked "${sandbox_unmet[@]}"
    fi
fi

# ── base repository / workspace prerequisite ──
[[ -d "$REPO" ]] || refuse "--repo does not exist: $REPO"
git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1 || refuse "--repo is not a git repository: $REPO"
[[ -d "$WORKSPACE" ]] || refuse "--workspace does not exist: $WORKSPACE"

# ── toolchain prerequisite ──
command -v node >/dev/null 2>&1 || refuse "node is not on PATH (the live matrix driver needs it)"
command -v java >/dev/null 2>&1 || refuse "java is not on PATH"
command -v mvn >/dev/null 2>&1 || refuse "mvn is not on PATH"

exec node "$SCRIPT_DIR/live-matrix.mjs" --core "$CORE" --mode "$MODE" \
    --workspace "$WORKSPACE" --repo "$REPO" --evidence "$EVIDENCE" "${PASSTHRU[@]}"
