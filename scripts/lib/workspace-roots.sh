#!/usr/bin/env bash
# Pure run-root resolution (F30) shared by start-backend.sh. Sourced, never executed
# on its own: the defaults live outside the checkout so a bare git command in a run's
# scratch directory cannot resolve to the operator repository.

# The user-scoped base the run roots default to, or a non-zero status when neither
# LOCALAPPDATA nor HOME is available.
resolve_workspace_base() {
    if [ -n "${LOCALAPPDATA:-}" ]; then
        printf '%s\n' "${LOCALAPPDATA}/AriaConductor/workspaces"
    elif [ -n "${HOME:-}" ]; then
        printf '%s\n' "${HOME}/.aria-conductor/workspaces"
    else
        return 1
    fi
}

# Prints "<runtime root>|<result root>". An operator-provided
# ARIA_WORKSPACES_RUNTIME_ROOT / ARIA_WORKSPACES_RESULT_ROOT always wins. The default
# base is only required when at least one root is unset: two operator roots are
# self-sufficient on a host without LOCALAPPDATA and without HOME.
resolve_workspace_roots() {
    local base=""
    if [ -z "${ARIA_WORKSPACES_RUNTIME_ROOT:-}" ] || [ -z "${ARIA_WORKSPACES_RESULT_ROOT:-}" ]; then
        base="$(resolve_workspace_base)" || return 1
    fi
    printf '%s|%s\n' \
        "${ARIA_WORKSPACES_RUNTIME_ROOT:-$base/runs}" \
        "${ARIA_WORKSPACES_RESULT_ROOT:-$base/results}"
}
