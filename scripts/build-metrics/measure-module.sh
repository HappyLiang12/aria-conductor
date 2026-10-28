#!/usr/bin/env bash
# Time one Maven lane and print its per-class / per-context-boot breakdown.
#   bash scripts/build-metrics/measure-module.sh <label> <module> [goal] [extra mvn args...]
# Logs go to agent-control-tower/target/build-metrics/ (ignored), so nothing here is a
# committed artifact: re-run the command to regenerate the evidence.
set -u
LABEL="$1"; shift
MODULE="$1"; shift
GOAL="${1:-test}"; [ $# -gt 0 ] && shift

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
POM_DIR="$ROOT/agent-control-tower"
OUT="$POM_DIR/target/build-metrics"
mkdir -p "$OUT"
LOG="$OUT/$LABEL.log"
cd "$POM_DIR" || exit 2

T0=$(date +%s.%N)
mvn -o -B -pl "$MODULE" "$GOAL" "$@" >"$LOG" 2>&1
RC=$?
T1=$(date +%s.%N)
echo "label=$LABEL module=$MODULE goal=$GOAL wall=$(awk -v a="$T0" -v b="$T1" 'BEGIN{printf "%.1f", b-a}')s rc=$RC log=$LOG"
RUN_SINCE="${T0%.*}" node "$ROOT/scripts/build-metrics/summarize.mjs" "$MODULE" "$LOG" | head -32
