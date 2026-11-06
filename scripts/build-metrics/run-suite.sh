#!/usr/bin/env bash
# One full unit-tier suite run with wall clock, ambient load sample and the reactor summary.
#   bash scripts/build-metrics/run-suite.sh <label> [worktree-dir] [extra mvn args...]
# Defaults to the clean-test command AGENTS.md documents, so before/after pairs stay comparable.
# Logs land in agent-control-tower/target/build-metrics/ (ignored) - regenerate, do not cite.
set -u
LABEL="$1"; shift
WT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"; [ $# -gt 0 ] && shift
[ -d "$WT/agent-control-tower" ] || { echo "no reactor pom under $WT/agent-control-tower"; exit 2; }
OUT="$WT/agent-control-tower/target/build-metrics"
mkdir -p "$OUT"
LOG="$OUT/$LABEL.log"

ambient() {
  powershell -NoProfile -Command '$os=Get-CimInstance Win32_OperatingSystem; $c=Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average; "cpu_pct=" + [int]$c.Average + " freeGB=" + [math]::Round($os.FreePhysicalMemory/1MB,1)' \
    2>/dev/null | tr -d '\r' | tail -1
}

echo "label=$LABEL worktree=$WT"
echo "ambient-before: $(ambient)"
cd "$WT/agent-control-tower" || exit 2
T0=$(date +%s.%N)
mvn -o -B clean test -Dspring.profiles.active=h2 "$@" >"$LOG" 2>&1
RC=$?
T1=$(date +%s.%N)
echo "label=$LABEL wall=$(awk -v a="$T0" -v b="$T1" 'BEGIN{printf "%.1f", b-a}')s rc=$RC"
echo "ambient-after: $(ambient)"
grep -E "Total time:|Finished at:" "$LOG"
echo "--- reactor per-module summary ---"
grep -E "^\[INFO\] ACT .*(SUCCESS|FAILURE|SKIPPED)" "$LOG" | sed 's/\[INFO\] //'
echo "--- unit totals (module summary lines summed) ---"
tr -d '\r' < "$LOG" | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' \
  | grep -v -- '-- in' \
  | awk -F'[ ,]+' '{r+=$4; f+=$6; e+=$8; s+=$10} END{printf "run=%d fail=%d err=%d skip=%d\n", r, f, e, s}'
exit $RC
