#!/usr/bin/env bash
# Portable qmd runtime for the aria-memory-vault.
# Works with podman or docker on macOS, Linux, and Windows (Git Bash/WSL).
# Runtime selection follows the repository contract in scripts/lib/container-runtime.sh
# (CONTAINER_RUNTIME, docker first, then podman) instead of a local probe.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIR="$SCRIPT_DIR"
source "$SCRIPT_DIR/../lib/container-runtime.sh"

IMAGE="aria-qmd:2.9.0"
CONTAINER="aria-qmd"
VAULT_DIR="${ARIA_MEMORY_VAULT_DIR:-$HOME/aria-memory-vault}"
export ARIA_MEMORY_VAULT_DIR="$VAULT_DIR"

resolve_container_runtime || exit 1
if [ -z "${CONTAINER_RT:-}" ]; then
  echo "ERROR: podman or docker is required. Install one, or set CONTAINER_RUNTIME to 'docker' or 'podman'." >&2
  exit 1
fi
RT="$CONTAINER_RT"

# The image predicate is `image inspect`: it exists on both runtimes, while
# `podman image exists` is podman-only (docker answers "unknown command", exit 1).
ensure_image() {
  "$RT" image inspect "$IMAGE" >/dev/null 2>&1 || "$RT" build -t "$IMAGE" "$DIR"
}

run_qmd() {
  "$RT" run --rm -v "$VAULT_DIR:/vault:z" -v aria-qmd-home:/root -w /vault "$IMAGE" "$@"
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

case "$cmd" in
  build)
    "$RT" build -t "$IMAGE" "$DIR"
    ;;
  init)
    [ -d "$VAULT_DIR" ] || { echo "vault not found: $VAULT_DIR" >&2; exit 1; }
    ensure_image
    "$RT" volume create aria-qmd-home >/dev/null 2>&1 || true
    run_qmd collection add /vault/memory/verified --name memory-verified || true
    run_qmd collection add /vault/knowledge/verified --name knowledge-verified || true
    run_qmd update
    run_qmd embed
    ;;
  start)
    [ -d "$VAULT_DIR" ] || { echo "vault not found: $VAULT_DIR" >&2; exit 1; }
    ensure_image
    "$RT" rm -f "$CONTAINER" >/dev/null 2>&1 || true
    "$RT" run -d --name "$CONTAINER" \
      -p 127.0.0.1:8181:8181 \
      -v "$VAULT_DIR:/vault:z" \
      -v aria-qmd-home:/root \
      -w /vault \
      "$IMAGE" mcp --http --host 0.0.0.0 >/dev/null
    wait_health
    ;;
  stop)
    "$RT" rm -f "$CONTAINER"
    ;;
  status)
    "$RT" ps --filter "name=$CONTAINER" --format '{{.Names}} {{.Status}}'
    curl -fsS http://127.0.0.1:8181/health || true
    echo
    ;;
  logs)
    "$RT" logs --tail 100 "$CONTAINER"
    ;;
  *)
    echo "usage: $0 {build|init|start|stop|status|logs}" >&2
    exit 2
    ;;
esac
