#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_ROOT/agent-control-tower"

# Parse arguments
PROFILE="${SPRING_PROFILES_ACTIVE:-h2}"
ADK_PROVIDER="${ADK_PROVIDER:-langchain}"
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

# ── OpenSandbox server (required for opencode provider) ──
if [ "$ADK_PROVIDER" = "opencode" ] && [ "$SKIP_SANDBOX" != "true" ]; then
    echo "Checking OpenSandbox server..."
    if ! docker ps &>/dev/null; then
        echo "ERROR: Docker is not running. Start Docker Desktop first, or use --skip-sandbox."
        exit 1
    fi

    if ! docker ps --filter "name=aria-opensandbox" --format "{{.Names}}" 2>/dev/null | grep -q "aria-opensandbox"; then
        echo "Starting OpenSandbox server (docker compose)..."
        (cd "$PROJECT_ROOT" && docker compose up -d opensandbox-server)
        sleep 3
    fi

    # Default OpenSandbox URL for local dev (host port 8090)
    export OPENCODE_SANDBOX_SERVER_URL="${OPENCODE_SANDBOX_SERVER_URL:-http://localhost:8090}"
fi

# ── LLM credentials (injected into sandbox env for opencode provider) ──
if [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -n "${LLM_API_KEY:-}" ]; then
    export DEEPSEEK_API_KEY="$LLM_API_KEY"
fi

echo "Starting Aria Conductor backend..."
echo "  Profile: $PROFILE"
echo "  ADK Provider: $ADK_PROVIDER"
echo "  Port: 8080"
if [ "$ADK_PROVIDER" = "opencode" ]; then
    echo "  OpenSandbox: ${OPENCODE_SANDBOX_SERVER_URL:-http://localhost:8090}"
fi

cd "$BACKEND_DIR"

if [ "$SKIP_BUILD" != "true" ] && [ ! -f "act-app/target/act-app-0.1.0-SNAPSHOT.jar" ]; then
    echo "Building..."
    mvn clean install -DskipTests -B
fi

echo "Launching Spring Boot..."
mvn spring-boot:run -pl act-app \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.jvmArguments="--enable-preview" \
    -Dspring-boot.run.arguments="--adk.default-provider=$ADK_PROVIDER"
