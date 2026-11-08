#!/usr/bin/env bash
# Plain assertions for scripts/lib/workspace-roots.sh (F30). No test framework;
# run with: bash scripts/lib/workspace-roots.test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=workspace-roots.sh
source "$SCRIPT_DIR/workspace-roots.sh"

pass=0
fail=0

assert_eq() {
    local expected="$1" actual="$2" label="$3"
    if [ "$expected" = "$actual" ]; then
        pass=$((pass + 1))
        printf 'PASS: %s\n' "$label"
    else
        fail=$((fail + 1))
        printf 'FAIL: %s (expected %q, got %q)\n' "$label" "$expected" "$actual" >&2
    fi
}

# 1. LOCALAPPDATA wins on Windows.
LOCALAPPDATA='C:\Users\test\AppData\Local' HOME='/home/test'
assert_eq 'C:\Users\test\AppData\Local/AriaConductor/workspaces/runs|C:\Users\test\AppData\Local/AriaConductor/workspaces/results' \
    "$(resolve_workspace_roots)" 'LOCALAPPDATA base'

# 2. HOME is the fallback when LOCALAPPDATA is empty.
unset LOCALAPPDATA
HOME='/home/test'
assert_eq '/home/test/.aria-conductor/workspaces/runs|/home/test/.aria-conductor/workspaces/results' \
    "$(resolve_workspace_roots)" 'HOME fallback'

# 3. Operator-provided roots win over both defaults.
ARIA_WORKSPACES_RUNTIME_ROOT='/srv/aria/runs' ARIA_WORKSPACES_RESULT_ROOT='/srv/aria/results'
assert_eq '/srv/aria/runs|/srv/aria/results' "$(resolve_workspace_roots)" 'operator roots win'
unset ARIA_WORKSPACES_RUNTIME_ROOT ARIA_WORKSPACES_RESULT_ROOT

# 4. One operator root wins without losing the other default.
ARIA_WORKSPACES_RUNTIME_ROOT='/srv/aria/runs'
assert_eq '/srv/aria/runs|/home/test/.aria-conductor/workspaces/results' \
    "$(resolve_workspace_roots)" 'one operator root keeps the other default'
unset ARIA_WORKSPACES_RUNTIME_ROOT

# 5. Both operator roots are honored even when no default base exists (F30: no
# LOCALAPPDATA and no HOME). The operator value always wins, so the base is not needed.
assert_eq '/srv/aria/runs|/srv/aria/results' \
    "$(LOCALAPPDATA='' HOME='' \
        ARIA_WORKSPACES_RUNTIME_ROOT='/srv/aria/runs' \
        ARIA_WORKSPACES_RESULT_ROOT='/srv/aria/results' \
        resolve_workspace_roots)" \
    'both operator roots, no base'

# 6. With no base, an unset root is still an error: only a fully operator-provided
# pair can skip the default base.
if (LOCALAPPDATA='' HOME='' ARIA_WORKSPACES_RUNTIME_ROOT='/srv/aria/runs' \
        ARIA_WORKSPACES_RESULT_ROOT='' resolve_workspace_roots) >/dev/null 2>&1; then
    fail=$((fail + 1)); printf 'FAIL: missing result root without a base must fail\n' >&2
else
    pass=$((pass + 1)); printf 'PASS: missing result root without a base still fails\n'
fi

# 7. The bash entry point consumes the helper; the old in-checkout literal is gone.
start_script="$SCRIPT_DIR/../start-backend.sh"
if grep -q 'workspace-roots.sh' "$start_script"; then
    pass=$((pass + 1)); printf 'PASS: start-backend.sh sources the helper\n'
else
    fail=$((fail + 1)); printf 'FAIL: start-backend.sh does not source the helper\n' >&2
fi
if grep -q 'act-app/data/workspaces' "$start_script"; then
    fail=$((fail + 1)); printf 'FAIL: start-backend.sh still defaults inside the checkout\n' >&2
else
    pass=$((pass + 1)); printf 'PASS: start-backend.sh no longer defaults inside the checkout\n'
fi

printf 'RESULT: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
