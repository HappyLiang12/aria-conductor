#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_ROOT/agent-control-tower"

# Shared container-runtime helpers (load_dotenv, resolve_container_runtime)
# shellcheck source=lib/container-runtime.sh
source "$SCRIPT_DIR/lib/container-runtime.sh"
load_dotenv "$PROJECT_ROOT"

# Parse arguments
PROFILE="${SPRING_PROFILES_ACTIVE:-h2}"
ADK_PROVIDER="${ADK_PROVIDER:-}"
SKIP_SANDBOX="${SKIP_SANDBOX:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"

while [[ $# -gt 0 ]]; do
    case $1 in
        --profile=*) PROFILE="${1#*=}"; shift ;;
        --provider=*) ADK_PROVIDER="${1#*=}"; shift ;;
        --skip-sandbox) SKIP_SANDBOX="true"; shift ;;
        --skip-build) SKIP_BUILD="true"; shift ;;
        *) shift ;;
    esac
done

# --skip-sandbox means no container runtime is available: default the provider to the
# Host-capable core qoder unless it was set explicitly (--provider= or the
# ADK_PROVIDER environment variable); an explicit provider always wins.
# Host mode needs no Docker or Podman at all.
if [ "$SKIP_SANDBOX" = "true" ] && [ -z "$ADK_PROVIDER" ]; then
    ADK_PROVIDER="qoder"
    echo "  --skip-sandbox: defaulting ADK provider to qoder (Host mode needs no container runtime)."
fi
ADK_PROVIDER="${ADK_PROVIDER:-opencode}"

# ── Run-owned absolute paths (F30: outside the checkout, never cwd-relative) ──
# A run's worktree, generated configuration and retained results are owned by the
# backend's run roots, and the qoder bridge entry is a committed artifact of the
# repository. The run roots default to a user-scoped location OUTSIDE this checkout
# ($LOCALAPPDATA/AriaConductor/workspaces on Windows; $HOME/.aria-conductor/workspaces
# otherwise), so a bare git command in a run's scratch directory cannot resolve to the
# operator repository. Paths are absolute and do not depend on the working directory
# the Spring Boot process happens to run in (mvn spring-boot:run -pl act-app runs in
# act-app/). An operator-provided value (including one loaded from .env) always wins.
# shellcheck source=lib/workspace-roots.sh
source "$SCRIPT_DIR/lib/workspace-roots.sh"
workspace_roots="$(resolve_workspace_roots)" || {
    echo "ERROR: cannot resolve the workspace roots: set LOCALAPPDATA or HOME, or set ARIA_WORKSPACES_RUNTIME_ROOT and ARIA_WORKSPACES_RESULT_ROOT explicitly." >&2
    exit 1
}
export ARIA_WORKSPACES_RUNTIME_ROOT="${workspace_roots%%|*}"
export ARIA_WORKSPACES_RESULT_ROOT="${workspace_roots#*|}"
echo "  Workspace runtime root: $ARIA_WORKSPACES_RUNTIME_ROOT"
echo "  Workspace result root : $ARIA_WORKSPACES_RESULT_ROOT"

# ── Qoder ACP bridge dist preflight (F31) ──
# A stale packages/qoder-acp-bridge/dist after a merge makes the run-owned core fail
# with "E_USAGE Unknown flag: --child-env-file" while the API surfaces only the generic
# control-secret handshake failure. The check is gated on the qoder provider (the bridge
# only runs for a qoder core): a fresh dist is left untouched, a stale one is rebuilt
# when pnpm is available, otherwise the script stops with the exact commands.
BRIDGE_DIR="$PROJECT_ROOT/packages/qoder-acp-bridge"
BRIDGE_FRESHNESS_HELPER="$SCRIPT_DIR/lib/bridge-freshness.mjs"

# Prints a reason and returns 0 when the bridge dist is stale/missing, or returns 1
# when it is fresh. The shared node helper decides via the dist/.build-inputs hash
# manifest when node is available; without node, or for a checkout that predates the
# manifest, the legacy mtime comparison is the documented fallback.
bridge_dist_problem() {
    local dir="$1" reason="" status=0 newer=() file name joined=""
    local dist_main="$dir/dist/main.js"

    if [ ! -f "$dist_main" ]; then
        printf 'dist/main.js is missing under %s\n' "$dir"
        return 0
    fi

    if command -v node >/dev/null 2>&1 && [ -f "$BRIDGE_FRESHNESS_HELPER" ]; then
        if reason="$(node "$BRIDGE_FRESHNESS_HELPER" check "$dir" 2>/dev/null)"; then
            return 1
        else
            status=$?
        fi
        if [ "$status" -eq 1 ]; then
            printf '%s\n' "$reason"
            return 0
        fi
    fi

    if [ -d "$dir/src" ]; then
        while IFS= read -r -d '' file; do
            if [ "$file" -nt "$dist_main" ]; then
                newer+=("${file#"$dir"/}")
            fi
        done < <(find "$dir/src" -type f -print0)
    fi
    for name in package.json tsconfig.json pnpm-lock.yaml; do
        if [ -f "$dir/$name" ] && [ "$dir/$name" -nt "$dist_main" ]; then
            newer+=("$name")
        fi
    done
    if [ "${#newer[@]}" -gt 0 ]; then
        for file in "${newer[@]}"; do
            if [ -z "$joined" ]; then joined="$file"; else joined="$joined, $file"; fi
        done
        printf 'dist/main.js is older than: %s\n' "$joined"
        return 0
    fi
    return 1
}

if [ "$ADK_PROVIDER" = "qoder" ]; then
    bridge_problem=""
    if bridge_problem="$(bridge_dist_problem "$BRIDGE_DIR")"; then
        echo "WARNING: the qoder bridge dist is not fresh: $bridge_problem"
        if command -v pnpm >/dev/null 2>&1; then
            echo "  Rebuilding the bridge: pnpm install --frozen-lockfile && pnpm build"
            # Clear dist first: tsc writes into it, and a failed build must not leave a
            # partial dist that looks usable (tsconfig also sets noEmitOnError).
            rm -rf "$BRIDGE_DIR/dist"
            if ! (cd "$BRIDGE_DIR" && pnpm install --frozen-lockfile && pnpm build); then
                echo "ERROR: rebuilding the qoder bridge failed in $BRIDGE_DIR" >&2
                exit 1
            fi
            # Record the build inputs so the next start compares hashes, not mtimes.
            if command -v node >/dev/null 2>&1 && [ -f "$BRIDGE_FRESHNESS_HELPER" ]; then
                if ! node "$BRIDGE_FRESHNESS_HELPER" write "$BRIDGE_DIR" >/dev/null; then
                    echo "WARNING: could not write the bridge build manifest; the next start falls back to the mtime check."
                fi
            fi
            if bridge_problem="$(bridge_dist_problem "$BRIDGE_DIR")"; then
                echo "ERROR: the qoder bridge is still not fresh after a rebuild: $bridge_problem" >&2
                exit 1
            fi
            echo "  qoder bridge rebuilt: dist/main.js is fresh."
        else
            echo "ERROR: the qoder bridge dist is not fresh and pnpm is not installed. Rebuild it manually (do not use npm or yarn):" >&2
            echo "  cd $BRIDGE_DIR" >&2
            echo "  pnpm install --frozen-lockfile" >&2
            echo "  pnpm build" >&2
            exit 1
        fi
    fi
fi

if [ -z "${ARIA_CORES_QODER_BRIDGE_ENTRY:-}" ] && [ -f "$BRIDGE_DIR/dist/main.js" ]; then
    export ARIA_CORES_QODER_BRIDGE_ENTRY="$BRIDGE_DIR/dist/main.js"
fi

# Prerequisites check
check_command() {
    if ! command -v "$1" &> /dev/null; then
        echo "ERROR: $1 is not installed. $2"
        exit 1
    fi
}

check_command "java" "Install JDK 21: https://adoptium.net/"
check_command "mvn" "Install Maven 3.9+: https://maven.apache.org/"

java -version 2>&1 | grep -q "21" || echo "WARNING: JDK 21 recommended. Current version may not be compatible."

# Container runtime status (required only for the opencode provider)
echo "Container runtimes:"
for rt in docker podman; do
    if command -v "$rt" &> /dev/null; then
        if "$rt" info &> /dev/null; then
            echo "  $rt: running"
        else
            echo "  WARNING: $rt is installed but not running. Required only for the opencode provider."
        fi
    else
        echo "  $rt: not installed"
    fi
done
if resolve_container_runtime; then
    if [ -n "$CONTAINER_RT" ]; then
        if [ "$CONTAINER_RT_MODE" = "explicit" ]; then
            echo "  Container runtime: $CONTAINER_RT (explicit: CONTAINER_RUNTIME)"
        else
            echo "  Container runtime: $CONTAINER_RT (auto-detected)"
        fi
    else
        echo "  WARNING: No container runtime available. Required only for the opencode provider."
    fi
fi

# ── OpenSandbox server (required for opencode provider) ──
if [ "$ADK_PROVIDER" = "opencode" ] && [ "$SKIP_SANDBOX" != "true" ]; then
    echo "Checking OpenSandbox server..."
    if ! resolve_container_runtime; then
        exit 1
    fi
    if [ -z "$CONTAINER_RT" ]; then
        echo "ERROR: Neither docker nor podman is available. The opencode provider in Sandbox mode requires a container runtime for the OpenSandbox server. Install Docker or podman, or run in Host mode with --skip-sandbox (qoder)."
        exit 1
    fi

    sandbox_list="$("$CONTAINER_RT" ps --filter "name=aria-opensandbox" --format "{{.Names}}" 2>/dev/null || true)"
    if ! printf '%s' "$sandbox_list" | grep -q "aria-opensandbox"; then
        echo "Starting OpenSandbox server ($CONTAINER_RT compose)..."
        if ! (cd "$PROJECT_ROOT" && "$CONTAINER_RT" compose up -d opensandbox-server); then
            echo "ERROR: Failed to start OpenSandbox server" >&2
            if [ "$CONTAINER_RT" = "podman" ]; then
                echo "podman hint: verify the socket is enabled (podman machine ssh 'systemctl --user is-active podman.socket') and SANDBOX_SOCKET in .env matches its VM path." >&2
            fi
            exit 1
        fi
        sleep 3
    fi

    # Default OpenSandbox URL for local dev (host port 8090). The IPv4 loopback, never "localhost": the direct execd endpoints the SDK dials carry the server-advertised host, so a "localhost" base exposes the IPv6-loopback resolution flake.
    export OPENCODE_SANDBOX_SERVER_URL="${OPENCODE_SANDBOX_SERVER_URL:-http://127.0.0.1:8090}"
fi

# ── LLM credentials (injected into sandbox env for opencode provider) ──
if [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -n "${LLM_API_KEY:-}" ]; then
    export DEEPSEEK_API_KEY="$LLM_API_KEY"
fi

if [ -z "${GITHUB_TOKEN:-}" ]; then
    echo "WARN: GITHUB_TOKEN is not set; BA/Dev agents cannot read issues or clone repos in the sandbox. Set GITHUB_TOKEN and restart the backend: the credential is resolved once at startup. The dashboard has no credential editor yet; an operator with API access can store it via POST /api/v1/packs/pack-git-0001/credentials and restart."
fi

echo "Starting Aria Conductor backend..."
echo "  Profile: $PROFILE"
echo "  ADK Provider: $ADK_PROVIDER"
echo "  Port: 8080"
if [ "$ADK_PROVIDER" = "opencode" ]; then
    echo "  OpenSandbox: ${OPENCODE_SANDBOX_SERVER_URL:-http://127.0.0.1:8090}"
fi

cd "$BACKEND_DIR"

if [ "$SKIP_BUILD" != "true" ]; then
    echo "Installing backend modules (mvn install -DskipTests -q)..."
    mvn install -DskipTests -q
fi

echo "Launching Spring Boot..."
# R-F6 mitigation: JDK 21 HttpClient HTTP/1.1 idle-connection keep-alive tuning.
# The default keepalive.timeout (1200s = 20min) is below the 15-31min opencode task window,
# so idle connections get dropped mid-task; raising it (plus a larger connection pool) reduces
# those drops. NOTE: this cannot fix opencode serve's own timeout on the sandbox side.
# java.net.preferIPv4Stack: "localhost" resolves to the IPv6 loopback ([::1]) and the JDK client
# dials the first resolved address, so the direct execd endpoints intermittently fail at connect
# time ("Failed to connect to localhost/[0:0:0:0:0:0:0:1]:<port>"); pinning IPv4 kills that flake.
mvn spring-boot:run -pl act-app \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.jvmArguments="--enable-preview -Djdk.httpclient.keepalive.timeout=3600 -Djdk.httpclient.connectionPoolSize=8 -Djava.net.preferIPv4Stack=true" \
    -Dspring-boot.run.arguments="--adk.default-provider=$ADK_PROVIDER"
