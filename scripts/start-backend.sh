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

# --skip-sandbox means no sandbox is available for the opencode provider to warm:
# default the provider to langchain unless it was set explicitly (--provider= or
# the ADK_PROVIDER environment variable); an explicit provider always wins.
if [ "$SKIP_SANDBOX" = "true" ] && [ -z "$ADK_PROVIDER" ]; then
    ADK_PROVIDER="langchain"
    echo "  --skip-sandbox: defaulting ADK provider to langchain (no sandbox to warm)."
fi
ADK_PROVIDER="${ADK_PROVIDER:-opencode}"

# ── Qoder provider: pin MCP token auth, reject an unauthenticated override ──
# A qoder sandbox shares the host network with the backend, so the local default
# (aria.mcp.auth-mode=none, an open operator API) is a blocked configuration for it
# (design Section 6.2): qoder mode pins token auth and mints a local token file. An
# explicit override to any other mode is refused rather than silently degraded. The
# default opencode path is untouched - no pinning, no token, no extra files.
MCP_TOKEN_FILE="$PROJECT_ROOT/.run/mcp-token"
if [ "$ADK_PROVIDER" = "qoder" ]; then
    if [ -n "${ARIA_MCP_AUTH_MODE:-}" ] && [ "$(printf '%s' "$ARIA_MCP_AUTH_MODE" | tr '[:upper:]' '[:lower:]')" != "token" ]; then
        echo "ERROR: Refusing to start the qoder provider with ARIA_MCP_AUTH_MODE=$ARIA_MCP_AUTH_MODE." >&2
        echo "       qoder sandboxes can reach the backend's operator APIs, so an unauthenticated MCP" >&2
        echo "       endpoint is a blocked configuration (design Section 6.2)." >&2
        echo "       Unset ARIA_MCP_AUTH_MODE (or set it to 'token') and retry." >&2
        exit 1
    fi
    export ARIA_MCP_AUTH_MODE=token
    if [ -z "${ARIA_MCP_TOKEN:-}" ]; then
        if command -v openssl >/dev/null 2>&1; then
            ARIA_MCP_TOKEN="$(openssl rand -hex 32)"
        else
            # od -An -tx1 renders 32 bytes as 64 hex digits; strip the separating blanks.
            ARIA_MCP_TOKEN="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
        fi
    fi
    export ARIA_MCP_TOKEN
    mkdir -p "$(dirname "$MCP_TOKEN_FILE")"
    printf '%s' "$ARIA_MCP_TOKEN" > "$MCP_TOKEN_FILE"
    chmod 600 "$MCP_TOKEN_FILE" 2>/dev/null || true
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

# ── OpenSandbox server (required for the opencode and qoder providers) ──
if { [ "$ADK_PROVIDER" = "opencode" ] || [ "$ADK_PROVIDER" = "qoder" ]; } && [ "$SKIP_SANDBOX" != "true" ]; then
    echo "Checking OpenSandbox server..."
    if ! resolve_container_runtime; then
        exit 1
    fi
    if [ -z "$CONTAINER_RT" ]; then
        echo "ERROR: Neither docker nor podman is available. The $ADK_PROVIDER provider requires a container runtime for the OpenSandbox server. Install Docker or podman, or use --skip-sandbox / ADK_PROVIDER=langchain."
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

if [ -z "${GITHUB_TOKEN:-}" ]; then
    echo "WARN: GITHUB_TOKEN is not set; BA/Dev agents cannot read issues or clone repos in the sandbox. Set GITHUB_TOKEN and restart the backend: the credential is resolved once at startup. The dashboard has no credential editor yet; an operator with API access can store it via POST /api/v1/packs/pack-git-0001/credentials and restart."
fi

echo "Starting Aria Conductor backend..."
echo "  Profile: $PROFILE"
echo "  ADK Provider: $ADK_PROVIDER"
echo "  Port: 8080"
if [ "$ADK_PROVIDER" = "opencode" ]; then
    echo "  OpenSandbox: ${OPENCODE_SANDBOX_SERVER_URL:-http://localhost:8090}"
fi
if [ "$ADK_PROVIDER" = "qoder" ]; then
    # qoder.sandbox-server-url is fixed in application.yml (no env override), and the
    # sandbox image is built separately: podman build -t aria-conductor/qoder-sandbox:0.1 agent-control-tower/qoder-sandbox
    echo "  OpenSandbox: http://localhost:8090 (image aria-conductor/qoder-sandbox:0.1)"
    echo "  MCP auth: token (token file: $MCP_TOKEN_FILE)"
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
