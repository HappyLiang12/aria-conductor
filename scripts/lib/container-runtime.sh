#!/usr/bin/env bash
# Shared container-runtime resolution for Aria Conductor startup scripts.
# Source from another script: source "$SCRIPT_DIR/lib/container-runtime.sh"
# Requires bash 4.0+ (uses ${var,,} and ${!name+x} parameter expansions).

# load_dotenv <project_root>: loads KEY=VALUE pairs from <project_root>/.env
# into the environment. Existing environment variables are never overwritten.
load_dotenv() {
    local project_root="$1"
    local env_file="$project_root/.env"
    local line name value
    [ -f "$env_file" ] || return 0
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$'\r'}"
        case "$line" in
            ''|'#'*) continue ;;
        esac
        case "$line" in
            *=*) ;;
            *) continue ;;
        esac
        name="${line%%=*}"
        value="${line#*=}"
        case "$name" in
            ''|[!A-Za-z_]*|*[!A-Za-z0-9_]*) continue ;;
        esac
        if [ -z "${!name+x}" ]; then
            export "$name=$value"
        fi
    done < "$env_file"
}

# runtime_cli_ok <runtime>: true when the CLI exists AND `info` succeeds.
runtime_cli_ok() {
    command -v "$1" >/dev/null 2>&1 && "$1" info >/dev/null 2>&1
}

# resolve_container_runtime: sets CONTAINER_RT and CONTAINER_RT_MODE globals.
# Strict mode (CONTAINER_RUNTIME set): invalid value or unavailable CLI is a
# hard error (prints to stderr, returns 1).
# Auto mode: docker first, then podman; CONTAINER_RT empty when neither is usable.
resolve_container_runtime() {
    local explicit="${CONTAINER_RUNTIME:-}"
    if [ -n "$explicit" ]; then
        local rt
        rt="${explicit,,}"
        if [ "$rt" != "docker" ] && [ "$rt" != "podman" ]; then
            echo "ERROR: CONTAINER_RUNTIME='$explicit' is invalid. Use 'docker' or 'podman'." >&2
            return 1
        fi
        if ! runtime_cli_ok "$rt"; then
            if [ "$rt" = "podman" ]; then
                echo "ERROR: CONTAINER_RUNTIME=podman is set but podman is not available. Install podman, ensure a machine is running ('podman machine start'), then retry." >&2
            else
                echo "ERROR: CONTAINER_RUNTIME=docker is set but docker is not available. Install/start Docker, or switch CONTAINER_RUNTIME to podman." >&2
            fi
            return 1
        fi
        CONTAINER_RT="$rt"
        CONTAINER_RT_MODE="explicit"
        return 0
    fi
    if runtime_cli_ok docker; then CONTAINER_RT="docker"; CONTAINER_RT_MODE="auto"; return 0; fi
    if runtime_cli_ok podman; then CONTAINER_RT="podman"; CONTAINER_RT_MODE="auto"; return 0; fi
    CONTAINER_RT=""
    CONTAINER_RT_MODE="auto"
    return 0
}
