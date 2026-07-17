#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
FRONTEND_DIR="$PROJECT_ROOT/agent-control-tower/act-dashboard"

# Prerequisites check
check_command() {
    if ! command -v "$1" &> /dev/null; then
        echo "ERROR: $1 is not installed. $2"
        exit 1
    fi
}

check_command "node" "Install Node.js 20+: https://nodejs.org/"
check_command "pnpm" "Install pnpm: npm install -g pnpm"

echo "Starting Aria Conductor frontend..."
cd "$FRONTEND_DIR"

if [ ! -d "node_modules" ]; then
    echo "Installing dependencies..."
    pnpm install --frozen-lockfile
fi

echo "  Dev server: http://localhost:5173"
pnpm dev