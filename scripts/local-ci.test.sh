#!/usr/bin/env bash
# Argument-contract smoke test for scripts/local-ci.sh (no gates are run).
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CI="$ROOT/scripts/local-ci.sh"

out=$(bash "$CI" --help)
grep -q 'affected' <<<"$out"
grep -q 'java-unit' <<<"$out"
grep -q 'integration' <<<"$out"
grep -q 'e2e' <<<"$out"

if bash "$CI" not-a-mode >/dev/null 2>&1; then
  echo 'unknown mode was accepted' >&2
  exit 1
fi

if bash "$CI" frontend --shard >/dev/null 2>&1; then
  echo 'a missing --shard value was accepted' >&2
  exit 1
fi

echo 'local-ci argument contract: ok'
