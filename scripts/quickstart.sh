#!/usr/bin/env bash
set -euo pipefail

echo "========================================="
echo "  Aria Conductor - Quick Start"
echo "========================================="
echo ""

# Check Docker
if command -v docker &> /dev/null && docker compose version &> /dev/null 2>&1; then
    echo "Docker detected. Starting with Docker Compose..."
    echo ""

    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
    cd "$PROJECT_ROOT"

    if [ ! -f ".env" ]; then
        if [ -f ".env.example" ]; then
            echo "Creating .env from .env.example..."
            cp .env.example .env
            echo ""
            echo "IMPORTANT: Edit .env and set your LLM_API_KEY before continuing."
            echo "  nano .env  (or your preferred editor)"
            echo ""
            read -p "Press Enter after setting your API key (or Ctrl+C to abort)..."
        fi
    fi

    echo "Building and starting services..."
    echo "  (includes OpenSandbox server for opencode agent runtime)"
    docker compose up -d --build

    echo ""
    echo "Services starting:"
    echo "  Dashboard:       http://localhost:3000"
    echo "  Backend:         http://localhost:8080"
    echo "  ADK:             http://localhost:9300"
    echo "  OpenSandbox:     http://localhost:8090"
    echo "  Swagger:         http://localhost:8080/swagger-ui.html"
    echo ""
    echo "Default ADK provider: opencode (sandbox-isolated agent runtime)"
    echo ""
    echo "Check status: docker compose ps"
    echo "View logs:    docker compose logs -f"
    echo "Stop:         docker compose down"
else
    echo "Docker not found. Falling back to local development mode."
    echo ""
    echo "NOTE: OpenCode sandbox mode requires Docker for the OpenSandbox server."
    echo "      Without Docker, only langchain ADK provider is available."
    echo ""

    # Check prerequisites
    MISSING=""
    command -v java &> /dev/null || MISSING="$MISSING java"
    command -v mvn &> /dev/null || MISSING="$MISSING maven"
    command -v node &> /dev/null || MISSING="$MISSING node"
    command -v pnpm &> /dev/null || MISSING="$MISSING pnpm"
    command -v python3 &> /dev/null || command -v python &> /dev/null || MISSING="$MISSING python"

    if [ -n "$MISSING" ]; then
        echo "Missing prerequisites:$MISSING"
        echo "Install them and try again, or install Docker for the easiest setup."
        exit 1
    fi

    echo "All prerequisites found. Starting services..."
    echo ""

    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

    echo "Starting backend (langchain provider, Docker required for opencode)..."
    ADK_PROVIDER=langchain SKIP_SANDBOX=true bash "$SCRIPT_DIR/start-backend.sh" &
    BACKEND_PID=$!

    sleep 5

    echo "Starting frontend..."
    bash "$SCRIPT_DIR/start-frontend.sh" &
    FRONTEND_PID=$!

    echo ""
    echo "Services starting:"
    echo "  Dashboard:  http://localhost:5173"
    echo "  Backend:    http://localhost:8080"
    echo ""
    echo "To use opencode provider, install Docker and re-run this script."
    echo ""
    echo "Press Ctrl+C to stop all services."

    trap "kill $BACKEND_PID $FRONTEND_PID 2>/dev/null" EXIT
    wait
fi
