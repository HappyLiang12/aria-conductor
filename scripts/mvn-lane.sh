#!/usr/bin/env bash
# Deterministic Maven test lanes for the inner development loop and for parallel sharding.
#
#   scripts/mvn-lane.sh inner  <module> [-Dtest=PATTERN]     fast: offline, incremental, coverage off
#   scripts/mvn-lane.sh gate   <module> [-Dtest=PATTERN]     upstream refresh + this module, coverage ON
#   scripts/mvn-lane.sh clean  <module>                      gate, preceded by clean (migrations/pom edits)
#   scripts/mvn-lane.sh itg    <module> [-Dit.test=PATTERN]  failsafe (integration) tier only
#   scripts/mvn-lane.sh refresh                              incremental install of every module, no tests
#   scripts/mvn-lane.sh timing                               the @Tag("timing") classes, serially
#   scripts/mvn-lane.sh shard K                              K concurrent lanes over the unit tier, then timing
#
# Fan-out, measured on Happy (i5-12500 6C/12T, 16 GB DDR5 in ONE channel, SATA SSD): serial
# `clean test` 199 s, shard 2 93.8-96.4 s over two runs, shard 3 78.5-86.3 s over five runs, shard 4
# 74.7-78.3 s over two.
# So K=3 is the default worth reaching for and K=4 buys 0.2-11.6 s more - the two samples do not overlap,
# but only by 0.2 s at the closest edge, so it is a few seconds rather than a step change. The single
# memory channel, not the 12 threads, is what flattens the curve: at K=4 the four lanes each ran 7.6-22.4 s
# longer than their planned cost, in the wrong order (the lightest planned lane finished second-slowest in
# both runs), and the box was down to 2.8 GB free at the worst moment. K=1 is the whole tier in one lane,
# useful as the reference run. CI runs four groups on four separate runners
# (.github/workflows/ci.yml:113-139) where nothing is shared, which is why the hosted lane can be
# wider than any local one.
#
# Isolation contract for shard lanes:
#   - one shared `install -DskipTests` first, so no lane pays the compile and none needs to
#   - a lane runs `surefire:test` as a GOAL only: it never compiles, which is what makes it safe for
#     two lanes to share one module's target/ (act-execution is split across lanes on purpose)
#   - each lane gets -DtempDir=lane<i>, so even surefire's boot jars cannot collide
#   - ARIA_TEST_DB_SUFFIX=_l<i> gives each lane its own in-memory schema name
#   - the class lists come from scripts/lanes (committed, cost-derived) and `lane-plan.mjs check`
#     refuses to start Maven unless they are an exact cover; `verify` then re-checks the run against the
#     printed results AND against the compiled target/test-classes, so a class that ran in no lane fails
#     the run even when nobody thought to plan it. The test *total* is reported, never gated - it is a
#     snapshot, and one added @Test method must not redden the lane.
#   - the "timing" tag is excluded from every lane and replayed serially afterwards, so the
#     suite stays complete while nothing that bounds elapsed time runs under contention
#   - coverage is enforced only by gate/clean (unit) and itg (failsafe), each of which deletes
#     target/jacoco.exec first. The other lanes invoke the surefire GOAL so `jacoco:report`/`check` never
#     run - `check`'s skip is bound to ${skip.unit.tests}, NOT to ${jacoco.skip}, so the flag alone would
#     not have stopped it grading a stale accumulated exec - and they also pass -Djacoco.skip=true because
#     `prepare-agent` binds to `initialize`, which a `test-compile` lane does execute, and without the flag
#     the agent attaches and appends to that same file
#   - each shard lane gets its own -Djqwik.database, because jqwik's default file sits next to the module
#     and two lanes running jqwik classes would race on it
#
# Optional environment:
#   MVN_REPO=<path>   private local repository. Use it for any scratch or throwaway checkout: the
#                     shared install writes act-*.jar into whatever repo it is given, and pointing a
#                     bench run at a copy of ~/.m2 keeps the developer's own SNAPSHOT jars untouched.
#   LANE_ID=<name>    suffix the JaCoCo exec file so parallel coverage runs cannot share it
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

# Nothing here hardcodes a module weight, a timing class name or a `-Dtest` pattern any more: the
# packing lives in scripts/lane-plan.mjs and the partition in scripts/lanes/, because it needs the
# per-class measurements, float arithmetic, and a completeness guard that no shell recomputation on
# a different host would reproduce.
need_node() { command -v node >/dev/null 2>&1 || die "this lane needs node on PATH (scripts/lane-plan.mjs)"; }
load_timing_plan() {
  local line
  need_node
  line=$(node "$ROOT/scripts/lane-plan.mjs" timing-plan) || die "lane-plan.mjs timing-plan failed"
  TIMING_MODULES=$(printf '%s' "$line" | cut -f2)
  TIMING_CLASSES=$(printf '%s' "$line" | cut -f4)
  [ -n "$TIMING_MODULES" ] || die "timing-plan found no modules with @Tag(\"timing\") classes"
}

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
    # `test-compile` then the surefire GOAL, not the `test` phase: the phase would also run jacoco:report
    # and jacoco:check, and `check`'s skip is bound to ${skip.unit.tests} rather than ${jacoco.skip}, so it
    # would grade whatever target/jacoco.exec an earlier run left behind. jacoco.skip is still passed
    # because prepare-agent binds to `initialize`, which `test-compile` does run: without it the coverage
    # agent attaches and appends to that same exec file. Filtered runs under-cover by design; `gate` is the
    # lane that enforces the ratchet, and it deletes the exec first.
    # The no-match escape is surefire.failIfNoSpecifiedTests, not failIfNoSpecifiedTests.
    run_logged "inner:$ARG2" "$LOG_DIR/inner.log" -pl "$ARG2" test-compile surefire:test \
      -Djacoco.skip=true -Dtest.argline.base= -Dsurefire.failIfNoSpecifiedTests=false \
      -Djqwik.database="$LOG_DIR/jqwik-inner-db" \
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
    load_timing_plan
    echo "mvn-lane: replaying the wall-clock-bounded classes serially: $TIMING_CLASSES"
    # same reason as `inner`: goal, not phase, and jacoco.skip so `initialize` attaches no agent.
    # -Djqwik.database matters even here: with `-Dgroups=timing` every jqwik class is filtered out, yet the
    # engine still created act-execution/.jqwik-database (measured), so the redirect is what keeps the
    # module directory clean rather than the set of tests that actually run.
    run_logged timing "$LOG_DIR/timing.log" -pl "$TIMING_MODULES" test-compile surefire:test \
      -Djacoco.skip=true -Dtest.argline.base= -Dgroups=timing \
      -Djqwik.database="$LOG_DIR/jqwik-timing-db" \
      "${PROFILE[@]}" "${REPO_OPTS[@]}" ${EXTRA[@]+"${EXTRA[@]}"}
    exit $?
    ;;

  shard)
    K="${ARG2:-2}"
    case "$K" in 1|2|3|4) : ;; *) die "shard takes 1..4 (see the fan-out note in this file's header)" ;; esac
    need_node
    # The guard runs before Maven, so a partition that no longer covers the suite cannot burn a run.
    node "$ROOT/scripts/lane-plan.mjs" check \
      || die "scripts/lanes is not an exact cover of the suite - regenerate the fragment(s) with \`node scripts/lane-plan.mjs gen <module>\`, then re-measure if a class is new"
    plan=$(node "$ROOT/scripts/lane-plan.mjs" plan "$K" "$LOG_DIR") || die "lane-plan.mjs plan failed"

    lane_n=0; lane_mods=(); lane_inc=(); lane_logs=()
    while IFS=$'\t' read -r name cost modules includes classes; do
      lane_n=$((lane_n + 1))
      lane_mods[$lane_n]="$modules"
      lane_inc[$lane_n]="$LOG_DIR/$includes"
      lane_logs[$lane_n]="$LOG_DIR/shard-lane$lane_n.log"
      printf '  %-6s %6ss  %3s classes  %s\n' "$name" "$cost" "$classes" "$modules"
    done <<< "$plan"
    [ "$lane_n" -eq "$K" ] || die "lane-plan produced $lane_n lanes for K=$K"

    echo "mvn-lane: shared compile once, then $K test lanes + the serial timing lane"
    run_logged "shard:refresh" "$LOG_DIR/shard-refresh.log" -T 1C install -DskipTests "${REPO_OPTS[@]}" \
      || die "shared install failed; shards would read stale SNAPSHOT jars"

    t0=$(date +%s.%N)
    pids=()
    for ((i = 1; i <= K; i++)); do
      # `surefire:test` as a GOAL, not the `test` phase: two lanes may share act-execution's target/
      # only because neither compiles into it; the shared install already made classes and test
      # resources. `-Dtest.argline.base=` stops the pom's late-bound `@{argLine}` (JaCoCo's slot, which
      # prepare-agent never filled here) from reaching java as a literal - the failure PIT hit, see
      # pom.xml. The includes file is the lane's whole class list, because the property is per Maven
      # process, not per module: naming only the fragments would run nothing else in the lane.
      # jqwik writes a database next to the module by default (act-execution/.jqwik-database), which is a
      # shared write between lanes; -Djqwik.database gives each lane its own (verified: the file lands
      # exactly where it is pointed). No JaCoCo goal runs here at all, so no stale exec data is graded.
      ARIA_TEST_DB_SUFFIX="_l$i" mvn -o -B -pl "${lane_mods[$i]}" surefire:test \
        -Dsurefire.includesFile="${lane_inc[$i]}" -DtempDir="lane$i" \
        -Djqwik.database="$LOG_DIR/jqwik-lane$i-db" \
        -Dtest.argline.base= -DexcludedGroups=timing \
        "${PROFILE[@]}" ${REPO_OPTS[@]+"${REPO_OPTS[@]}"} \
        >"${lane_logs[$i]}" 2>&1 &
      pids+=($!)
    done
    rc=0
    for ((i = 1; i <= K; i++)); do
        lrc=0
        wait "${pids[$((i - 1))]}" || lrc=$?
        [ "$lrc" -ne 0 ] && rc=1
        echo "  lane$i rc=$lrc $(sum_tests "${lane_logs[$i]}") log=${lane_logs[$i]}"
    done

    echo "mvn-lane: shards done, replaying the timing lane serially"
    bash "$SCRIPT" timing || rc=1
    # the loud end: exact cover per lane, no timing class under contention, totals that add up
    node "$ROOT/scripts/lane-plan.mjs" verify "$LOG_DIR/timing.log" "${lane_logs[@]}" || rc=1
    t1=$(date +%s.%N)
    echo "shard total wall=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", b-a}')s rc=$rc (K=$K)"
    exit $rc
    ;;

  *) die "unknown lane '$LANE' (use inner|gate|clean|itg|refresh|timing|shard)" ;;
esac
