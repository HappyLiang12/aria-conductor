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
ADK_PROVIDER="${ADK_PROVIDER:-opencode}"
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
        echo "ERROR: Neither docker nor podman is available. The opencode provider requires a container runtime for the OpenSandbox server. Install Docker or podman, or use --skip-sandbox / ADK_PROVIDER=langchain."
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

    # Default OpenSandbox URL for local dev (host port 8090)
    export OPENCODE_SANDBOX_SERVER_URL="${OPENCODE_SANDBOX_SERVER_URL:-http://localhost:8090}"
fi

# ── LLM credentials (injected into sandbox env for opencode provider) ──
if [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -n "${LLM_API_KEY:-}" ]; then
    export DEEPSEEK_API_KEY="$LLM_API_KEY"
fi

if [ -z "${GH_TOKEN:-}" ]; then
    echo "WARN: GH_TOKEN is not set; BA/Dev agents cannot read issues or clone repos in the sandbox."
fi

echo "Starting Aria Conductor backend..."
echo "  Profile: $PROFILE"
echo "  ADK Provider: $ADK_PROVIDER"
echo "  Port: 8080"
if [ "$ADK_PROVIDER" = "opencode" ]; then
    echo "  OpenSandbox: ${OPENCODE_SANDBOX_SERVER_URL:-http://localhost:8090}"
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
mvn spring-boot:run -pl act-app \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.jvmArguments="--enable-preview -Djdk.httpclient.keepalive.timeout=3600 -Djdk.httpclient.connectionPoolSize=8" \
    -Dspring-boot.run.arguments="--adk.default-provider=$ADK_PROVIDER"
