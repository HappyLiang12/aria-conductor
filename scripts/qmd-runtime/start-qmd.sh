#!/usr/bin/env bash
# Portable qmd runtime for the aria-memory-vault.
# Works with podman or docker on macOS, Linux, and Windows (Git Bash/WSL).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="aria-qmd:2.9.0"
CONTAINER="aria-qmd"
VAULT_DIR="${ARIA_MEMORY_VAULT_DIR:-$HOME/aria-memory-vault}"
export ARIA_MEMORY_VAULT_DIR="$VAULT_DIR"

runtime() {
  if command -v podman >/dev/null 2>&1; then echo podman
  elif command -v docker >/dev/null 2>&1; then echo docker
  else echo "podman or docker is required" >&2; exit 1; fi
}

run_qmd() {
  local rt; rt="$(runtime)"
  "$rt" run --rm -v "$VAULT_DIR:/vault:z" -v aria-qmd-home:/root -w /vault "$IMAGE" "$@"
}

wait_health() {
  for _ in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:8181/health >/dev/null 2>&1; then
      echo "qmd MCP healthy at http://127.0.0.1:8181/mcp"
      return 0
    fi
    sleep 1
  done
  echo "qmd did not become healthy; check: $0 logs" >&2
  return 1
}

cmd="${1:-start}"
rt="$(runtime)"

case "$cmd" in
  build)
    "$rt" build -t "$IMAGE" "$DIR"
    ;;
  init)
    [ -d "$VAULT_DIR" ] || { echo "vault not found: $VAULT_DIR" >&2; exit 1; }
    "$rt" volume create aria-qmd-home >/dev/null 2>&1 || true
    run_qmd collection add /vault/memory/verified --name memory-verified || true
    run_qmd collection add /vault/knowledge/verified --name knowledge-verified || true
    run_qmd update
    run_qmd embed
    ;;
  start)
    [ -d "$VAULT_DIR" ] || { echo "vault not found: $VAULT_DIR" >&2; exit 1; }
    "$rt" image exists "$IMAGE" >/dev/null 2>&1 || "$rt" build -t "$IMAGE" "$DIR"
    "$rt" rm -f "$CONTAINER" >/dev/null 2>&1 || true
    "$rt" run -d --name "$CONTAINER" \
      -p 127.0.0.1:8181:8181 \
      -v "$VAULT_DIR:/vault:z" \
      -v aria-qmd-home:/root \
      -w /vault \
      "$IMAGE" mcp --http --host 0.0.0.0 >/dev/null
    wait_health
    ;;
  stop)
    "$rt" rm -f "$CONTAINER"
    ;;
  status)
    "$rt" ps --filter "name=$CONTAINER" --format '{{.Names}} {{.Status}}'
    curl -fsS http://127.0.0.1:8181/health || true
    echo
    ;;
  logs)
    "$rt" logs --tail 100 "$CONTAINER"
    ;;
  *)
    echo "usage: $0 {build|init|start|stop|status|logs}" >&2
    exit 2
    ;;
esac
