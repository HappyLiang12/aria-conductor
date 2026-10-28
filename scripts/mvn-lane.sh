#!/usr/bin/env bash
# Deterministic Maven test lanes for the inner development loop and for parallel sharding.
#
#   scripts/mvn-lane.sh inner  <module> [-Dtest=PATTERN]     fast: offline, incremental, coverage off
#   scripts/mvn-lane.sh gate   <module> [-Dtest=PATTERN]     upstream refresh + this module, coverage ON
#   scripts/mvn-lane.sh clean  <module>                      gate, preceded by clean (migrations/pom edits)
#   scripts/mvn-lane.sh itg    <module> [-Dit.test=PATTERN]  failsafe (integration) tier only
#   scripts/mvn-lane.sh refresh                              incremental install of every module, no tests
#   scripts/mvn-lane.sh timing                               the @Tag("timing") classes, serially
#   scripts/mvn-lane.sh shard K                              K concurrent module-disjoint lanes, then timing
#
# Fan-out cap: keep K <= 2 on a 6-core / 16 GB dev box, 3 only when nothing else is running.
# Each lane forks one test JVM that loads a whole Spring graph (measured: ~1 GB RSS per act-app
# lane, and ~4 GB free on this box during a Maven run), and forkCount stays 1 inside a lane
# because four classes assert on real elapsed time. K > 3 makes lanes starve each other, which
# shows up as timing flakes rather than as a slower number. CI proves 4 in parallel because
# .github/workflows/ci.yml:113-124 runs four module groups on four separate runners.
#
# Isolation contract for shard lanes:
#   - one shared `install -DskipTests` first, so no lane pays the ~49 s compile
#   - lanes get disjoint module lists, so two processes never write one module's target/
#   - ARIA_TEST_DB_SUFFIX=_l<i> gives each lane its own in-memory schema name
#   - the "timing" tag is excluded from every lane and replayed serially afterwards, so the
#     suite stays complete while nothing that bounds elapsed time runs under contention
#   - JaCoCo runs only on the gate/itg lanes, which delete target/jacoco.exec first
#
# Optional environment:
#   MVN_REPO=<path>        private local repository, for concurrent lanes across worktrees
#   LANE_ID=<name>         suffix the JaCoCo exec file so parallel coverage runs cannot share it
set -u

LANE="${1:-}"; ARG2="${2:-}"
# Shift one slot per argument that exists: a one-word lane (refresh, timing) must leave EXTRA
# empty, otherwise the lane name itself reaches Maven as a lifecycle phase.
if [ $# -gt 0 ]; then shift; fi
if [ $# -gt 0 ]; then shift; fi
EXTRA=("$@")

die() { echo "mvn-lane: $*" >&2; exit 2; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
POM_DIR="$ROOT/agent-control-tower"
cd "$POM_DIR" || die "cannot enter $POM_DIR (the reactor pom lives there, not at repo root)"
LOG_DIR="$POM_DIR/target/mvn-lane"
mkdir -p "$LOG_DIR"
PROFILE=(-Dspring.profiles.active=h2)
REPO_OPTS=()
[ -n "${MVN_REPO:-}" ] && REPO_OPTS+=("-Dmaven.repo.local=$MVN_REPO")

# Per-module weights in seconds, measured on this box at 605ecbba (`clean test`, reactor summary)
# and reduced by the 97.7 s cron wait that P-1 removed from act-aria. They only order the modules,
# so a stale weight costs balance, never correctness.
declare -A WEIGHTS=(
  [act-execution]=125 [act-app]=77 [act-knowledge]=32 [act-common]=27.5
  [act-agent]=19.3 [act-dashboard-api]=15.2 [act-aria]=14 [act-mcp]=14.6
  [act-test-support]=2
)
MODULES=("${!WEIGHTS[@]}")
TIMING_MODULES="act-app,act-execution"
# Classes that bound real elapsed time; kept out of parallel lanes, replayed by the timing lane.
TIMING_CLASSES="CoreE2eEndpointReadinessTest,OpenCodeCoreAdapterTest,OpenSandboxSdkTest,RunWorkspaceServiceTest"

[ -n "$LANE" ] || die "usage: mvn-lane.sh {inner|gate|clean|itg|refresh|timing|shard} [module|K]"

# --- integrity guard ---------------------------------------------------------------------------
# JaCoCo's agent appends, so a filtered run can clear the module's coverage ratchet with data
# collected by an earlier commit. Delete the exec file whenever a lane enforces coverage.
fresh_exec() {
  local m
  for m in "$@"; do rm -f "$POM_DIR/$m/target/jacoco.exec"; done
}

jacoco_opts() {
  # LANE_ID separates exec files when two coverage runs cannot share one.
  if [ -n "${LANE_ID:-}" ]; then
    printf -- '-Djacoco.destFile=target/jacoco-%s.exec\n' "$LANE_ID"
  fi
}

# --- stale-target guard ------------------------------------------------------------------------
# Flyway reads classpath:db/migration, which is target/classes/db/migration, and
# maven-resources-plugin copies but never deletes. A migration renamed or dropped on another
# branch therefore survives in target/ and Flyway aborts with "Found more than one migration
# with version N" - the 2026-10-27 V59 incident, reproduced deliberately in this audit.
check_migrations() {
  local src="$POM_DIR/act-app/src/main/resources/db/migration"
  local tgt="$POM_DIR/act-app/target/classes/db/migration"
  [ -d "$src" ] || return 0
  local dups stale
  dups=$(basename -a "$src"/*.sql 2>/dev/null | sed -E 's/^V([0-9]+)__.*/\1/' | sort | uniq -d | tr '\n' ' ')
  [ -n "${dups// /}" ] && echo "mvn-lane: DUPLICATE migration versions in src: $dups (fix the filenames)"
  if [ -d "$tgt" ]; then
    stale=$(comm -13 <(basename -a "$src"/*.sql 2>/dev/null | sort) \
                     <(basename -a "$tgt"/*.sql 2>/dev/null | sort) | tr '\n' ' ')
    if [ -n "${stale// /}" ]; then
      echo "mvn-lane: STALE migrations in target/classes (deleted from src): $stale"
      echo "mvn-lane: remedy: rm -rf \"$tgt\" (or the clean lane) - do not guess with a full clean"
    fi
  fi
  return 0
}

run_logged() {
  # run_logged <label> <logfile> <mvn args...>  -> prints wall seconds, keeps the log
  local label="$1" log="$2"; shift 2
  local t0 t1 rc
  t0=$(date +%s.%N)
  mvn -o -B "$@" >"$log" 2>&1
  rc=$?
  t1=$(date +%s.%N)
  printf '%-28s wall=%6.1fs rc=%d  %s\n' "$label" \
    "$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", b-a}')" "$rc" \
    "$(grep -oE 'Total time:.*' "$log" | head -1)"
  return $rc
}

# Sum the per-module summary lines (the ones without "-- in"), because one lane builds several
# modules and Maven prints one total per module.
sum_tests() {
  tr -d '\r' < "$1" | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' \
    | grep -v -- '-- in' \
    | awk -F'[ ,]+' '{r+=$4; f+=$6; e+=$8; s+=$10} END{printf "run=%d fail=%d err=%d skip=%d", r, f, e, s}'
}

case "$ARG2" in
  act-app|act-app/*) check_migrations ;;
esac

case "$LANE" in
  refresh)
    run_logged refresh "$LOG_DIR/refresh.log" -T 1C install -DskipTests "${REPO_OPTS[@]}"
    exit $?
    ;;

  inner)
    [ -n "$ARG2" ] || die "inner needs a module"
    # Filtered runs under-cover by design (documented at pom.xml:257-260). The no-match escape
    # is surefire.failIfNoSpecifiedTests, not failIfNoSpecifiedTests.
    run_logged "inner:$ARG2" "$LOG_DIR/inner.log" -pl "$ARG2" test \
      -Djacoco.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
      "${PROFILE[@]}" "${REPO_OPTS[@]}" ${EXTRA[@]+"${EXTRA[@]}"}
    exit $?
    ;;

  gate|clean)
    [ -n "$ARG2" ] || die "gate needs a module"
    if [ "$LANE" = clean ]; then
      mvn -o -B -pl "$ARG2" clean >/dev/null || die "clean failed"
    fi
    fresh_exec "$ARG2"
    # Two steps, same as the hosted lane (ci.yml:137 then :139): refresh the reactor's SNAPSHOT
    # jars so -pl cannot read a stale installed jar, then run this module with the ratchet on.
    run_logged "gate:refresh:$ARG2" "$LOG_DIR/gate-refresh.log" \
      -pl "$ARG2" -am install -DskipTests "${REPO_OPTS[@]}" || die "upstream refresh failed"
    run_logged "gate:$ARG2" "$LOG_DIR/gate.log" -pl "$ARG2" test \
      "${PROFILE[@]}" "${REPO_OPTS[@]}" $(jacoco_opts) ${EXTRA[@]+"${EXTRA[@]}"}
    exit $?
    ;;

  itg)
    [ -n "$ARG2" ] || die "itg needs a module"
    fresh_exec "$ARG2"
    # The Flyway tier guard lives here, not in the unit tier: MigrationIntegrationTest is an
    # *IntegrationTest, which surefire excludes (pom.xml:202).
    run_logged "itg:$ARG2" "$LOG_DIR/itg.log" -pl "$ARG2" verify -Dskip.unit.tests=true \
      "${PROFILE[@]}" "${REPO_OPTS[@]}" $(jacoco_opts) ${EXTRA[@]+"${EXTRA[@]}"}
    exit $?
    ;;

  timing)
    echo "mvn-lane: replaying the wall-clock-bounded classes serially: $TIMING_CLASSES"
    run_logged timing "$LOG_DIR/timing.log" -pl "$TIMING_MODULES" test \
      -Djacoco.skip=true -Dgroups=timing \
      "${PROFILE[@]}" "${REPO_OPTS[@]}" ${EXTRA[@]+"${EXTRA[@]}"}
    exit $?
    ;;

  shard)
    K="${ARG2:-2}"
    case "$K" in 1|2|3|4) : ;; *) die "shard takes 1..4 (K > 3 starves lanes on this box)" ;; esac

    # Longest-processing-time packing: heaviest module first, each module into the currently
    # lightest lane. Modules are indivisible, so no two lanes ever write one module's target/.
    sorted=($(for m in "${MODULES[@]}"; do printf '%s %s\n' "${WEIGHTS[$m]}" "$m"; done \
              | sort -rn -k1 | awk '{print $2}'))
    lane_lists=(); lane_loads=()
    for ((i = 0; i < K; i++)); do lane_lists[$i]=""; lane_loads[$i]=0; done
    for m in "${sorted[@]}"; do
      lightest=0
      for ((i = 1; i < K; i++)); do
        awk -v a="${lane_loads[$i]}" -v b="${lane_loads[$lightest]}" 'BEGIN{exit !(a<b)}' \
          && lightest=$i
      done
      [ -z "${lane_lists[$lightest]}" ] && lane_lists[$lightest]="$m" \
        || lane_lists[$lightest]="${lane_lists[$lightest]},$m"
      lane_loads[$lightest]=$(awk -v a="${lane_loads[$lightest]}" -v w="${WEIGHTS[$m]}" \
        'BEGIN{printf "%.1f", a+w}')
    done

    echo "mvn-lane: shared compile once, then $K test lanes + the serial timing lane"
    for ((i = 0; i < K; i++)); do
      echo "  lane$((i + 1)) load=${lane_loads[$i]}s modules: ${lane_lists[$i]}"
    done
    run_logged "shard:refresh" "$LOG_DIR/shard-refresh.log" -T 1C install -DskipTests "${REPO_OPTS[@]}" \
      || die "shared install failed; shards would read stale SNAPSHOT jars"

    t0=$(date +%s.%N)
    pids=()
    for ((i = 0; i < K; i++)); do
      ARIA_TEST_DB_SUFFIX="_l$((i + 1))" mvn -o -B -pl "${lane_lists[$i]}" test \
        -Djacoco.skip=true -DexcludedGroups=timing -Dsurefire.failIfNoSpecifiedTests=false \
        "${PROFILE[@]}" ${REPO_OPTS[@]+"${REPO_OPTS[@]}"} \
        >"$LOG_DIR/shard-lane$((i + 1)).log" 2>&1 &
      pids+=($!)
    done
    rc=0
    for ((i = 0; i < K; i++)); do
        lrc=0
        wait "${pids[$i]}" || lrc=$?
        [ "$lrc" -ne 0 ] && rc=1
        echo "  lane$((i + 1)) rc=$lrc $(sum_tests "$LOG_DIR/shard-lane$((i + 1)).log") log=$LOG_DIR/shard-lane$((i + 1)).log"
    done

    echo "mvn-lane: shards done, replaying the timing lane serially"
    bash "$SCRIPT" timing || rc=1
    t1=$(date +%s.%N)
    echo "shard total wall=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", b-a}')s rc=$rc (K=$K)"
    exit $rc
    ;;

  *) die "unknown lane '$LANE' (use inner|gate|clean|itg|refresh|timing|shard)" ;;
esac
