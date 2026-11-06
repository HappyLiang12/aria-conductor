#!/usr/bin/env bash
# E2E scenario tests for container-runtime resolution (scripts/lib/container-runtime.sh).
# Zero external dependencies: stub docker/podman CLIs are injected via a temp PATH,
# and every scenario runs in a fresh bash process so a real docker/podman
# on the host can never leak into the test.
# Run: bash e2e/container-runtime-e2e.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
LIB_PATH="$PROJECT_ROOT/scripts/lib/container-runtime.sh"

STUB_ROOT="$(mktemp -d)"
trap 'rm -rf "$STUB_ROOT"' EXIT
FAILURES=0

pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1 ($2)"; FAILURES=$((FAILURES + 1)); }

# run_scenario <CONTAINER_RUNTIME value> <stub>[:dead] ...
# Creates stub CLIs ("name" = running, "name:dead" = engine down) in an isolated
# dir, then resolves in a fresh bash whose PATH only exposes that dir.
run_scenario() {
    local runtime_env="$1"; shift
    local dir="$STUB_ROOT/$(date +%s)-$RANDOM"
    mkdir -p "$dir"
    for spec in "$@"; do
        [ -n "$spec" ] || continue
        local name="${spec%:*}" mode="${spec#*:}"
        [ "$spec" = "$name" ] && mode="ok"
        if [ "$mode" = "dead" ]; then
            printf '#!/bin/bash\nexit 1\n' > "$dir/$name"
        else
            printf '#!/bin/bash\n[ "$1" = "info" ] && exit 0\nexit 1\n' > "$dir/$name"
        fi
        chmod +x "$dir/$name"
    done
    cat > "$dir/scenario.sh" <<EOF
export PATH="$dir"
export CONTAINER_RUNTIME="$runtime_env"
source "$LIB_PATH"
resolve_container_runtime
rc=\$?
echo "RESULT rc=\${rc} runtime=\${CONTAINER_RT:-} mode=\${CONTAINER_RT_MODE:-}"
exit \$rc
EOF
    local out
    out="$(bash "$dir/scenario.sh" 2>&1)"
    local rc=$?
    echo "$out"
    return $rc
}

echo "Container-runtime resolution scenarios:"

out="$(run_scenario "docker" "docker")"
case "$out" in *"rc=0 runtime=docker mode=explicit"*) pass "explicit docker + docker available";; *) fail "explicit docker + docker available" "$out";; esac

out="$(run_scenario "podman" "podman")"
case "$out" in *"rc=0 runtime=podman mode=explicit"*) pass "explicit podman + podman available";; *) fail "explicit podman + podman available" "$out";; esac

out="$(run_scenario "docker" "")"
case "$out" in *rc=1*"docker is not available"*|*"docker is not available"*rc=1*) pass "explicit docker + CLI missing -> hard error (rc=1)";; *) fail "explicit docker + CLI missing -> hard error (rc=1)" "$out";; esac

out="$(run_scenario "podman" "podman:dead")"
case "$out" in *rc=1*"podman is not available"*|*"podman is not available"*rc=1*) pass "explicit podman + engine not running -> hard error with podman hint (rc=1)";; *) fail "explicit podman + engine not running -> hard error (rc=1)" "$out";; esac

out="$(run_scenario "nerdctl" "docker")"
case "$out" in *rc=1*"is invalid"*|*"is invalid"*rc=1*) pass "explicit invalid value -> hard error (rc=1)";; *) fail "explicit invalid value -> hard error (rc=1)" "$out";; esac

out="$(run_scenario "" "docker" "podman")"
case "$out" in *"rc=0 runtime=docker mode=auto"*) pass "auto + docker running -> docker";; *) fail "auto + docker running -> docker" "$out";; esac

out="$(run_scenario "" "podman")"
case "$out" in *"rc=0 runtime=podman mode=auto"*) pass "auto + only podman running -> podman";; *) fail "auto + only podman running -> podman" "$out";; esac

out="$(run_scenario "" "")"
case "$out" in *"rc=0 runtime= mode=auto"*) pass "auto + neither available -> null runtime (rc=0)";; *) fail "auto + neither available -> null runtime (rc=0)" "$out";; esac

echo "load_dotenv scenarios:"

dotenv_dir="$STUB_ROOT/dotenv"
mkdir -p "$dotenv_dir"
cat > "$dotenv_dir/.env" <<'EOF'
# comment line
CONTAINER_RUNTIME=podman
SANDBOX_SOCKET=/run/user/1000/podman/podman.sock
INVALID LINE WITHOUT EQUALS
1BAD_NAME=should-be-skipped
EOF

out="$(bash -c "
export CONTAINER_RUNTIME=docker
source '$LIB_PATH'
load_dotenv '$dotenv_dir'
echo RESULT runtime=\$CONTAINER_RUNTIME socket=\${SANDBOX_SOCKET:-}
")"
case "$out" in *"runtime=docker socket=/run/user/1000/podman/podman.sock"*) pass "load_dotenv parses KEY=VALUE, preserves existing env";; *) fail "load_dotenv parsing" "$out";; esac

empty_dir="$STUB_ROOT/noenv"
mkdir -p "$empty_dir"
out="$(bash -c "
source '$LIB_PATH'
load_dotenv '$empty_dir'
echo RESULT ok
")"
case "$out" in *"RESULT ok"*) pass "load_dotenv missing .env is a no-op";; *) fail "load_dotenv missing .env" "$out";; esac

crtlf_dir="$STUB_ROOT/crtlf"
mkdir -p "$crtlf_dir"
printf 'CONTAINER_RUNTIME=podman\r\nSANDBOX_SOCKET=/run/user/1000/podman/podman.sock\r\n' > "$crtlf_dir/.env"
out="$(bash -c "
source '$LIB_PATH'
load_dotenv '$crtlf_dir'
echo RESULT runtime=\$CONTAINER_RUNTIME socket=\${SANDBOX_SOCKET:-}
")"
case "$out" in *"runtime=podman socket=/run/user/1000/podman/podman.sock"*) pass "load_dotenv strips CRLF line endings";; *) fail "load_dotenv CRLF" "$out";; esac

echo ""
if [ "$FAILURES" -gt 0 ]; then
    echo "$FAILURES scenario(s) FAILED"
    exit 1
fi
echo "All scenarios PASSED"
exit 0
