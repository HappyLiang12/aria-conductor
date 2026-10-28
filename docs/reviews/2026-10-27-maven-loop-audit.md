# Maven build/test loop audit — measured 2026-10-27

Scope: `agent-control-tower` reactor (10 modules), unit tier (`mvn test`) and the local
iteration loop. Governing question: where does the 7:48 go, and what is worth changing.

Baseline under discussion: **468 s** (7:48) for a full `mvn test`; the developer's own
paired numbers are `mvn -o clean -DskipTests install` = **48.9 s** (compile only) and
`mvn -o -pl act-app clean test` = **67 s**.

## 0. Method and honesty constraints

Every number below is either **MEASURED** in this session (commands in §8), **DEVELOPER-MEASURED**
(furnished inputs), or labelled **INFERRED**. Nothing is a model estimate presented as a
measurement. All runs were `mvn -o -B`, no Docker, on the i5-8400/16 GB/SATA box with Qoder
running concurrently, so absolute values carry contention noise; same-condition A/B pairs are
the trustworthy part.

Interpretation caveat that matters most: the wall-clock cost of a test that waits on a cron
tick is **not** a property of the machine. No CPU, RAM or disk purchase shortens it.

## 1. Measured phase breakdown

### 1.1 Module runs (incremental unless stated)

| run | command | reactor time | classes | methods | Σ class time |
|-----|---------|--------------|---------|---------|--------------|
| m1 | `-pl act-aria test` | 116 s | 24 | 176 | 107.3 s |
| m2 | `-pl act-aria test -Dtest=!TaskSchedulerSchedulerPortTest -Djacoco.skip=true` | **8.9 s** | 23 | 173 | ~9.6 s |
| m3 | `-pl act-app test` | 59.6 s | 20 | 63 | 53.2 s |
| m9 | `-pl act-app clean test` (same as m3 + clean) | **71 s** | 20 | 63 | 56.1 s |
| m4 | `-pl act-execution test` | 69 s | 101 | 945 | 62.7 s |
| m5 | `-pl act-common test` (coverage on) | 15.4 s | 16 | 119 | — |
| m6 | `-pl act-common test -Djacoco.skip=true` | **11.2 s** | 16 | 119 | — |
| m8 | `-pl act-common test -DskipTests` | 2.5 s (wall 4.5 s) | — | — | — |
| x1 | `-pl act-app test -Dtest=DevSqlControllerH2ProfilePresenceTest` | 20.1 s | 1 | 1 | — |
| x2 | `-pl act-app test -Dtest=RunControllerRegressionTest,KanbanHitlMigrationTest` | 24.6 s | 2 | 3 | — |

MEASURED. Full `mvn test` was never run in this session.

### 1.2 Where the time actually is

| cost centre | measured value | share of 468 s | evidence |
|-------------|----------------|------------------|----------|
| **one cron-waiting test class** `act-aria/.../scheduler/TaskSchedulerSchedulerPortTest` | **97.7 s** (3 methods, in a module whose other 23 classes total 9.6 s) | **20.9 %** | m1 vs m2; surefire XML |
| compile + resource copy (clean) | 48.9 s whole reactor; act-app alone +11.4 s | 10.4 % | developer-measured; m3 vs m9 |
| Spring context boots, unit tier | 38.7 s (act-app, 6 boots) + 13.6 s (act-execution, 5 boots) = **52.3 s** | 11.2 % | m3/m4 `Started … in N seconds` |
| ├─ of which cold-JVM premium for the *first* context in a fork | ~10 s per module (14.34 s as first vs 4.16 s as second) | — | x2 |
| ├─ of which marginal per extra distinct context | 3.3–5.1 s (act-app), 1.2–1.6 s (act-execution) | — | m3/m4 |
| JaCoCo agent + report + check | 4.2 s on a 11.2 s module run (**+37 %**) — act-common only | ~10 % if it holds per module (**INFERRED**, not measured on act-aria/act-execution) | m5 vs m6 |
| Maven fixed cost per module invocation | 2.5 s reactor; 2.0 s of it is process launch (wall 4.5 − reactor 2.5) | ~25 s over 10 modules (**INFERRED** from the single-module measurement) | m8 |
| Flyway DDL in the unit tier | **1.4 s** total (7 runs × 70 migrations, 0.106–0.441 s each) | 0.3 % | m3 log |
| remaining (test bodies + the two INFERRED rows above) | 269.1 s by subtraction | 57.5 % | 468 − 97.7 − 48.9 − 52.3 |
| └ genuine test logic, if JaCoCo (~20–35 s) and Maven fixed cost (~25 s) hold at their measured per-module magnitudes | ≈ 210–225 s for ~1 900 unit methods | ≈ 45–48 % | **INFERRED**, not directly measured |

### 1.3 Hypotheses tested and refuted

- **"70 Flyway migrations per context is the act-app cost."** Refuted: 490 migrate statements
  executed in m3 and all seven runs together took 1.4 s. `application-test.yml:17-22` enables
  Flyway, but H2 DDL is not the bottleneck.
- **"@DirtiesContext is dirtying the cache."** Refuted for this tier: exactly 3 occurrences
  (`AgentLoopInjectionIntegrationTest.java:45`, `KanbanPickupIntegrationTest.java:48`,
  `OpenCodeTaskExecutionIntegrationTest.java:64`), all class-level `AFTER_CLASS`, all in the
  failsafe lane. Cost in `mvn test`: zero.
- **"Awaitility polling / Thread.sleep is the generic wait."** Mostly refuted: 21 sleep statements
  are in the unit tier and the biggest is 1 100 ms; 49 `atMost(...)` sites have no `pollInterval`,
  no `pollDelay` and no `Awaitility.settings()` anywhere, so defaults (100 ms) apply and the
  aggregate overshoot is ~2.5 s. The 97.7 s in §1.2 is not a sleep — see §2.
- **"The context cache is overflowing in the unit tier."** Refuted: the default size is 16 and the
  largest unit-tier module footprint is 6 distinct contexts (act-app), so no eviction occurs.
  Sizing the cache up therefore cannot help `mvn test`. It may help `mvn verify` (~19 distinct
  fingerprints in act-app's failsafe lane — INFERRED from static reading, not measured).
- **"`-T 1C` should speed the reactor."** Already measured by the developer as no gain (48.3 vs
  48.9 s); the module graph is serial and the cost is inside test bodies, not in plugin scheduling.

## 2. Root cause of the single largest item

`TaskSchedulerSchedulerPortTest.java:44` and `:65` set `scheduleExpression = "* * * * *"` with
`scheduleType = "ONE_SHOT"`. `TaskSchedulerSchedulerPort.java:65-68` takes the ONE_SHOT branch and
computes the fire instant from `parseDuration(...)`, which calls `normalizeCron(...)`
(`TaskSchedulerSchedulerPort.java:106-108`) — a 5-field expression is padded to `"0 * * * * *"`,
i.e. second 0 of the next minute. So each of the two tests waits 0–60 s of real time for a
scheduler tick, bounded by `latch.await(65, SECONDS)` (`:56`) and `timeout(65000)` (`:73`).
Measured consequence: 97.7 s of the module's 107.3 s.

This is a fixture that accidentally opted into minute granularity, not a product defect: the
production code pads correctly and the assertion is honest.

## 3. Decisions on the seven candidate levers

**(a) Inner-loop slicing vs full suite — ADOPT.** Module and class slicing is the loop; the full
suite is a gate. Not because local runs are cheap, but because CI is structurally faster: the unit
tier is split across four runners (`.github/workflows/ci.yml:113-124`, command at `:139`) and the
integration lane runs the entire failsafe tier (`:176`). Measured single-class inner run: 10.4 s;
measured worst-case single-module run: 69 s.

**(b) Spring context reuse — PARTLY ADOPT, PARTLY PROPOSAL.** The mechanism is confirmed real
(3.3–5.1 s per extra distinct context, 52.3 s across the two heaviest unit modules), but every way
of removing a distinct context changes what the test asserts, so §4 ships none of it. Two sites are
candidates with exact diffs in §5.

**(c) Deterministic waits instead of sleeps/polling — ADOPT the one that matters.** The cron class
is worth 97.7 s; all remaining unit-tier sleeps and Awaitility overshoot together are worth <6 s,
so a repo-wide sweep is not justified by measurement.

**(d) Incremental builds instead of `clean` — ADOPT, with a targeted guard.** `clean` costs
+11.4 s (act-app measured) and ~49 s per full run. The reason it was being used as a reflex is the
stale-`target/` Flyway failure, whose mechanism is now pinned down and reproduced (§6.1): Flyway
reads `classpath:db/migration`, which is `target/classes/db/migration`, and
`maven-resources-plugin` copies but never deletes. The lane script detects that exact condition
and names the offending files, so `clean` is no longer needed as an oracle.

**(e) Maven daemon (mvnd) — REJECT for now.** mvnd is not installed here, so no mvnd number was
measured; what was measured is its addressable ceiling — the 2.0 s gap between wall (4.5 s) and
reactor (2.5 s) for one module invocation. It cannot touch the dominant costs: surefire still forks
a fresh test JVM, so the ~10 s cold-context premium and all boot/test time remain, and a full
reactor boots Maven once anyway. 2 s per invocation is not this loop's problem.

**(f) forkCount / JUnit parallel — PROPOSAL ONLY, and likely negative on this machine.** Seven
genuinely wall-clock-bounded assertions exist; five are in the unit tier and are the real payload
of the `pom.xml:206-211` comment: `CoreE2eEndpointReadinessTest.java:126,148,168`,
`OpenSandboxSdkTest.java:303-309,326-336`, `RunWorkspaceServiceTest.java:1040-1046,1067-1073`.
(The comment blames `LlmClientRetry*`, which is an `*IntegrationTest` and therefore not in this
tier.) Beyond timing: each extra fork reloads the Spring graph, and the box already has ~4 GB free,
so forkCount=2 risks swapping — which would slow every context boot measured in §1.2. Also
considered and rejected: JUnit method-level parallelism, because the test profile points every
context at one shared `jdbc:h2:mem:act_test;DB_CLOSE_DELAY=-1` (`application-test.yml:5`), so
parallel methods would interleave on the same schema.

**(g) Push full `verify` to CI — ADOPT as discipline, change no CI file.** CI already runs both
tiers and the mandatory harness packaging IT (`ci.yml:91-93`). Editing the workflow is doubly
costly: `e2e/agent-core/ci-contract.test.mjs:172-174,228-244` asserts literal lane commands, matrix
groups and shard counts, so a "speed up CI" edit must update that committed contract test too. The
loop change is local only, and the report of what ran must say so.

## 4. Prioritized decision table

Savings are stated against the 468 s baseline for the full unit tier, and separately as
*iteration wait* for the loop. `CR` = a clean-room / CI-matrix run is required before trusting it.

| # | item | wall-clock saving | evidence | effort | flakiness / isolation risk | CR |
|---|------|-------------------|----------|--------|-----------------------------|-----|
| 1 | Re-pick the cron fixture to a per-second expression (§5 P-1) | **−97.7 s** full tier (468→370 s by subtraction); act-aria 116→~27 s | 97.7 s MEASURED (m1 vs m2); the act-aria total is EXTRAPOLATED | 1 file, 4 lines | LOW — same branch, same assertion; loses nothing currently covered | no |
| 2 | Slice locally; full tiers only at CI gate (§3a/§3g) | loop 468→10–69 s per iteration; gate unchanged | MEASURED | 0 (done: script + AGENTS.md) | LOW — risk is *reporting* an unrun tier, not flakiness | no |
| 3 | Drop `clean` from the inner loop; use the migration staleness guard | −11.4 s per act-app run, ~−49 s per full run | MEASURED | 0 (done) | MED — other stale artefacts (generated sources) still need `clean`; the `clean`/`itg` lanes keep it | once per release |
| 4 | Skip JaCoCo on filtered/inner runs, enforce it on the gate lane | −4.2 s per module measured (act-common); −15–35 s full tier | MEASURED (per module) / INFERRED (aggregate) | 0 (done) | LOW, but see the integrity note in §6.2 | no |
| 5 | Convert `SddGoldenChainRegressionTest` `@MockBean`→`@MockitoBean` so it shares the base context (§5 P-2) | −3.3 s | MEASURED marginal boot | 1 line | MED — mock lifecycle becomes per-method; stubbing in `@BeforeAll` moves | yes |
| 6 | Merge the two DevSql profile assertions off full-boot contexts (§5 P-3) | −24.0 s | MEASURED boot cost | 2 test rewrites | MED-HIGH — weakens "bean absent in the real graph" | yes |
| 7 | Merge act-execution's 3 extra unit contexts (inner `@TestConfiguration`s) | −4.1 s | MEASURED marginal boot | 3 files | HIGH — these tests exist to prove listener/transaction wiring variants | yes |
| 8 | Raise `spring.test.context.cache.maxSize` behind a property flag | 0 for `mvn test` (6 contexts < 16, verified); unknown for `mvn verify` | MEASURED absence of eviction; IT-side INFERRED | pom edit | MED — retained contexts keep H2 handles/ports alive; the `h2`-profile file DB lock is already a documented failure mode (`DevSqlControllerProfileTest.java:27-37`) | yes |
| 9 | `forkCount=2` on act-execution after quarantining the 5 timing tests | up to −30 s | UNMEASURED (extrapolated from 62.7 s Σ class time) | pom + 5 quarantines | HIGH — CPU contention is exactly what the bounds in §3f cannot survive; RAM-negative at 4 GB free | yes |
| 10 | Sweep remaining `Thread.sleep` / Awaitility polling | ≤ −6 s | MEASURED ceilings are small | ~12 files | MED | yes |
| 11 | mvnd | −2.0 s per invocation | MEASURED ceiling | install | LOW | no |
| 12 | Move `ModuleBoundaryTest` (5.6 s, 10 % of act-app) to the gate/nightly tier | −5.6 s | MEASURED | 1 move | LOW value — it is a cheap architectural guard | no |

Items 1–4 are the decision: taken. They are the four with measured payoffs and no isolation change.

## 5. Proposals with exact diffs (NOT applied)

### P-1 cron fixture → per-second expression (recommended first, −97.7 s)

`act-aria/src/test/java/io/aria/conductor/aria/scheduler/TaskSchedulerSchedulerPortTest.java`

```diff
         ScheduledJob job = new ScheduledJob();
         job.setId("test-job");
-        job.setScheduleExpression("* * * * *");
+        // 6-field (seconds first) so the ONE_SHOT delay computed by
+        // TaskSchedulerSchedulerPort.parseDuration is <= 1s instead of up to 60s.
+        job.setScheduleExpression("* * * * * *");
         job.setScheduleType("ONE_SHOT");
@@
-        assertTrue(latch.await(65, TimeUnit.SECONDS),
-                "onFire callback was not invoked within 65 seconds");
+        assertTrue(latch.await(5, TimeUnit.SECONDS),
+                "onFire callback was not invoked within 5 seconds");
@@
@@
-        entity.setScheduleExpression("* * * * *");
+        entity.setScheduleExpression("* * * * * *");
         entity.setScheduleType("ONE_SHOT");
@@
-        verify(scheduledJobService, timeout(65000).atLeastOnce()).onFire("rec1");
+        verify(scheduledJobService, timeout(5000).atLeastOnce()).onFire("rec1");
```

Branch under test is unchanged (ONE_SHOT → `parseDuration` → `taskScheduler.schedule(runnable,
Instant)`); only the time quantum changes. The 5-field padding behaviour of `normalizeCron`
(`TaskSchedulerSchedulerPort.java:106-108`) is **not** asserted by this class today (both slow
methods take the ONE_SHOT branch, not the `CronTrigger` branch), so nothing is lost; if that
padding should be pinned, assert it directly on a schedule+cancel pair, which costs ~0 s.
Verification: `bash scripts/mvn-lane.sh inner act-aria -Dtest=TaskSchedulerSchedulerPortTest`,
expect ~5 s instead of ~98 s, then `bash scripts/mvn-lane.sh gate act-aria`.

### P-2 share the base context instead of forcing a new one (−3.3 s)

`act-app/src/test/java/io/aria/conductor/app/sdd/SddGoldenChainRegressionTest.java:41`

```diff
-    @MockBean
+    @MockitoBean
     private CoreRunLauncher coreRunLauncher;
```

Rationale: `@MockBean` participates in the context cache key, so this class cannot reuse the
`BaseH2IntegrationTest` context and pays a full boot (measured 3.344 s). `@MockitoBean` (Spring
Boot 3.4 / Framework 6.3, already used 91× in this repo's failsafe lane) replaces the bean per test
without a separate cache key. Risk: mocks are re-set per method, so any stubbing done in a static
`@BeforeAll` or captured in a class rule must move to `@BeforeEach`. Requires a clean-room run.
The same conversion applies to the six remaining `@MockBean` sites, all in the failsafe lane
(`ActIntegrationTest.java:64`, `KanbanPickupIntegrationTest.java:56`,
`GitPipelineIntegrationTest.java:86,88`, `SddWorkflowIntegrationTest.java:86,102`), which is where
the ~19-fingerprint cache pressure actually lives. Repo-wide there are 7 real annotation sites
(the two extra grep hits in `GitPipelineIntegrationTest.java:53,55` are javadoc prose, not code) and
`SddGoldenChainRegressionTest.java:41` is the **only** one in the unit tier.

### P-3 stop booting the whole application to assert one conditional bean (−24.0 s)

`act-app/src/test/java/io/aria/conductor/app/dev/DevSqlControllerProfileTest.java` holds two full
boots whose assertions are `getBean(...)` presence/absence. `ApplicationContextRunner` (or a
`@ContextConfiguration` limited to the auto-configuration under test) asserts the same conditional
wiring for ~50 ms each. This is a semantics change — the current version proves absence inside the
real `ActApplication` graph, which the runner version does not — so it is the developer's call, not
mine. Measured cost being removed: 19.7 s + 4.3 s.

### P-4 context-cache flag (propose only; measure the failsafe lane first)

`agent-control-tower/pom.xml`, `<properties>` near `:45` and both plugin `<configuration>` blocks:

```diff
+    <test.context.cache.max.size>16</test.context.cache.max.size>
...
       <plugin>
         <groupId>org.apache.maven.plugins</groupId>
         <artifactId>maven-surefire-plugin</artifactId>
         <configuration>
+          <systemPropertyVariables>
+            <spring.test.context.cache.max.size>${test.context.cache.max.size}</spring.test.context.cache.max.size>
+          </systemPropertyVariables>
...
       <plugin>
         <groupId>org.apache.maven.plugins</groupId>
         <artifactId>maven-failsafe-plugin</artifactId>
         <configuration>
+          <systemPropertyVariables>
+            <spring.test.context.cache.max.size>${test.context.cache.max.size}</spring.test.context.cache.max.size>
+          </systemPropertyVariables>
```

Default `16` is Spring's own value, so no run changes unless someone passes
`-Dtest.context.cache.max.size=64`. Do **not** raise the default blind: retained contexts hold H2
handles and, for the `h2` profile, a file-based datasource that already fails with "The file is
locked" when a local stack is running. Precondition: one measured `bash scripts/mvn-lane.sh itg
act-app` run with and without the flag; adopt only if eviction is observed.

### P-5 forkCount=2 experiment (do not attempt on 16 GB)

```diff
-          <forkCount>1</forkCount>
+          <forkCount>${test.fork.count}</forkCount>
```
with `<test.fork.count>1</test.fork.count>` as the default, plus a `@Tag("timing")` quarantine on
the five classes in §3f and `-DexcludedGroups=timing` for the parallel experiment. Only meaningful
after P-1 (which removes the 97.7 s outlier) and on a machine with headroom for two Spring graphs.

## 6. Two integrity findings

### 6.1 stale `target/` really does break the build, and it is now detectable

MEASURED positive control: copying `V63__approval_source.sql` to
`target/classes/db/migration/V63__synthetic_duplicatename.sql` (a filename absent from `src`, which
is exactly what a renumbered migration leaves behind) made `mvn -pl act-app test` fail with
`Found more than one migration with version 63`, and `scripts/mvn-lane.sh` printed
`STALE migrations in target/classes (deleted from src): …` **before** the run. The synthetic file
was deleted; the migration directory is back to 70 files and no tracked build file was modified.
History confirms the shape: `3fcdb8d4` added `V59__runtime_credential.sql`, `fdff463d` deleted it in
favour of `V60__runtime_credentials.sql` while `V59__agent_execution_bindings.sql` took the V59 slot.
Today `src` and `target` both hold the same 70 files, so there is no live duplicate.

### 6.2 `-Djacoco.skip=true` does not disarm the coverage ratchet

MEASURED twice (m2, m6): with `jacoco.skip` set, `report` logs "Skipping JaCoCo execution" but
`check` still runs and logs "Loading execution data file … jacoco.exec", and the build succeeds —
because the JaCoCo agent **appends** by default, so `check` graded coverage accumulated by earlier
commits. The root pom's own advice to "add `-Djacoco.skip=true`" for filtered runs (`pom.xml:257-260`)
is therefore safe for *speed* but not for *evidence*: a filtered run can clear the act-aria 0.86 /
act-execution 0.58 ratchets on data it did not produce. Mitigation shipped: the `gate` and `itg`
lanes delete `target/jacoco.exec` before running, so enforced coverage always comes from that run.
A repo-level fix (deleting the exec in `prepare-agent`, or `-Djacoco.skip` also gating `check`) is a
CI-semantics change and is left as a proposal.

## 7. Inner-loop command card (what the agent should actually run)

```bash
# 1. edit code, then verify the smallest thing that changed
bash scripts/mvn-lane.sh inner act-aria   -Dtest=ScheduledJobServiceTest      # ~10 s
bash scripts/mvn-lane.sh inner act-execution                                   # 69 s, coverage off

# 2. before a commit / wave: this module, upstream refreshed, ratchet enforced
bash scripts/mvn-lane.sh gate act-app                                          # ~59 s incremental

# 3. when the change is in the integration tier or in a migration
bash scripts/mvn-lane.sh itg act-agent -Dit.test=WorkflowChainRepositorySliceIntegrationTest
bash scripts/mvn-lane.sh itg act-app   -Dit.test=MigrationIntegrationTest       # the only real Flyway guard

# 4. never run locally: the full unit tier and the full verify — CI does both, sooner
#    (unit split over 4 runners at ci.yml:113-139; whole failsafe tier at :176)
```

Rules that come with the commands: say which lane ran; do not call a slice "tests pass"; after
touching `act-app/src/main/resources/db/migration/` use `itg`, not `inner`/`gate`; if the lane
script prints `STALE migrations in target/classes`, delete the directory it names instead of
reaching for `mvn clean`.

## 8. Reproduction commands

```bash
cd /d/project/aria-conductor
bash spark-output/build-metrics/mt.sh m1-aria-full act-aria test -Dspring.profiles.active=h2
bash spark-output/build-metrics/mt.sh m3-app-full  act-app   test -Dspring.profiles.active=h2
bash spark-output/build-metrics/mt.sh m4-exec-full act-execution test -Dspring.profiles.active=h2
bash spark-output/build-metrics/ab.sh   # m5/m6/m8 act-common A/B + m9 act-app clean pair
bash spark-output/build-metrics/x.sh    # x1/x2 context-boot isolation
node spark-output/build-metrics/summarize.mjs <module> <logfile>   # per-class + boot decomposition
```
Per-class numbers come from `target/{surefire,failsafe}-reports/TEST-*.xml`; boot numbers from the
`Started <Class> in N seconds (process running for M)` lines, which is why they are counted, not
guessed. Logs retained under `spark-output/build-metrics/logs/`.

## 9. Hardware

What the measurements say the loop is made of: **21 %** one accidental real-time wait,
**11 %** Spring context boot (class loading + bean wiring + JIT, single-threaded),
**10 %** compile, **45–48 %** genuine test bodies on one forked JVM, and the remaining
**~10–13 %** coverage instrumentation plus Maven's per-module fixed cost — the last two rows are
extrapolated from single-module probes (§1.2) and labelled INFERRED. `forkCount=1` means **one lane uses roughly one to two cores**;
the developer's own sampling shows 66–79 % CPU of 6 threads *including Qoder*, i.e. the box is not
CPU-saturated by Maven — it is serialised by design and memory-starved in practice (~4 GB free).

Consequences for buying:

- extra cores pay off only when **several lanes/worktrees run at once** (the Qoder parallel-lane
  workflow), never for a single suite;
- **single-core speed** is what shortens one suite — javac, and the ~10 s cold-context premium and
  3.3–5.1 s marginal boots, are all latency-bound on one thread;
- **RAM ≥ 32 GB** is the cheapest real multi-lane win here, because at 16 GB the second concurrent
  Spring graph plus Qoder forces paging, and paging slows every boot measured above;
- **NVMe** matters for jar/class loading and for `clean` rebuilds, not for test logic;
- GPU and memory *bandwidth* are irrelevant to this loop.

| option | verdict for this repo |
|--------|----------------------|
| keep i5-8400, +64 GB DDR4 + NVMe | Cheapest. Removes the paging ceiling so 2–3 lanes plus Qoder coexist. **No credible single-run speedup** — the machine is not the binding constraint for the 468 s figure. Buy only if lane count is the goal. |
| other desktop: 12th-gen i5 → 64 GB DDR5 + NVMe | **Recommended build box.** +25–30 % single-core and 6C/12T, so both one-suite latency and lane concurrency improve; DDR5 bandwidth helps class loading. Stays on Windows, so the repo's Windows-specific tests (`WorkspaceManagerTest.java:267,281` shells `cmd /c mklink`), the `.ps1` scripts and the podman/WSL stack keep working unchanged. |
| MacBook Pro M5 Pro 32 GB | Fastest single core, so the shortest *one* suite — but 32 GB caps the parallel-lane point of the purchase, and macOS moves the sandbox lanes into a VM with slower bind mounts; Windows-only tests need `@EnabledOnOs` guards. Good travel machine, wrong machine for 3 concurrent Maven lanes. |
| Mac mini M6 32 GB | Same single-core advantage, lower price, same 32 GB ceiling and same macOS/podman porting tax. Fine if the loop is one lane at a time. |
| Mac Studio M5 Max / Ultra | The only Apple option that fixes capacity as well as latency, and silent. Still pays the container bind-mount and OS-porting tax, and costs far more than the 12th-gen-i5 box for a workload whose top item is a 60 s cron wait. |
| NVIDIA DGX Spark (128 GB, ~1 TB/s, CUDA, Linux) | **Wrong tool for this problem.** Maven gains nothing from bandwidth or GPU; Grace single-thread is below a 12th-gen i5 for javac/class-loading work; Linux+aarch64 means revalidating the Windows-only tests, `.ps1` entry points and the podman/WSL assumptions. Justifiable only for running local models as product capability, not for build latency. |

Bottom line: the four adopted items take the full unit tier from 468 s to ~370 s and the inner
iteration from ~468 s to 10–69 s **without buying hardware**. If buying, buy 64 GB + NVMe on the
12th-gen i5 (capacity plus single-core, no platform migration); buy Mac Studio-class capacity only
if the lane count goes to 4+; do not buy anything expecting it to shrink the cron wait, which is
code, not silicon.

## 10. Files touched in this audit

| file | state |
|------|-------|
| `scripts/mvn-lane.sh` | ADDED (untracked). Lanes `inner`/`gate`/`clean`/`itg`/`refresh`; migration staleness guard; deletes `target/jacoco.exec` before coverage-enforcing lanes; prints wall seconds. Every lane was executed: `inner` 10.4 s (act-common, 4 tests) and 10.9 s (act-app, 1 test), `gate` 17.2 s (act-common, 119 tests, ratchet met on a freshly deleted exec) and 52.5 s test step + 6.5 s no-op `-am` refresh (act-app, 63 tests, 0.80/0.60 met), `itg` 14.1 s (act-agent, 4 ITs), staleness guard positive control in §6.1. |
| `AGENTS.md` | EDITED. Corrected the Flyway high-risk row (the guard is an `*IntegrationTest`, so `mvn test -pl act-app` could never have caught the V59 failure it was mapped to) and added the "Inner loop vs gate" table. |
| `docs/reviews/2026-10-27-maven-loop-audit.md` | ADDED (this file). |
| `spark-output/build-metrics/{mt.sh,ab.sh,x.sh,summarize.mjs,logs/*}` | ADDED. Measurement harness + logs, disposable; nothing under it is imported by the build. |
| poms, `.github/workflows/*`, test sources | **UNTOUCHED.** `git status --porcelain` shows no modified tracked file other than `AGENTS.md`; no commits, no branches. |

## 11. Open questions for the developer

1. P-1 is a 4-line change worth 97.7 s per full run. It alters what a test waits on, so it needs
   your ruling (and your own rule is that a fix round gets reviewed). Apply it?
2. `mvn -pl act-app verify` was never run here, so the "~19 distinct fingerprints vs cache size 16"
   eviction claim for the failsafe lane is INFERRED. One measured `itg act-app` run (~5–8 min) would
   settle P-4 and the 4 failsafe `@MockBean` sites. Run it?
3. JaCoCo per-module overhead was measured on act-common only (4.2 s). Extrapolating to the big
   modules gives 15–35 s, which is worth measuring properly on act-execution and act-aria before it
   goes in a decision. Run the pair?
4. Do you want a PowerShell twin of `scripts/mvn-lane.sh`? The repo convention is `.ps1`/`.sh`
   pairs, but two implementations drift, and the agent loop runs Git Bash.
5. Should `mvn test` keep being the documented "All Java tests + coverage" command at all, given CI
   runs the same tier in four parallel lanes and finishes sooner? I left the row in place and added
   the lane table rather than deleting a command you may use for pre-push confidence.
6. `pom.xml:206-211` blames "core HTTP client health probes and retry delays" for `forkCount=1`. The
   actual unit-tier timing assertions are the five classes in §3f. Fix the comment while touching
   the pom, or leave it?

---

# Implementation follow-up (2026-10-28, branch `perf/test-lane-sharding`)

The audit's item 1 (P-1) and the barrier work were implemented on a branch off `origin/main`
(merge-base `605ecbba`). Everything in this section is MEASURED on the same box unless labelled.

## Measured before / after, same command

| run | command | wall | reactor | unit tests |
|-----|---------|------|---------|------------|
| baseline @605ecbba | `mvn -o -B clean test -Dspring.profiles.active=h2` | **430.1 s** | 07:07 | 2323 run / 8 skipped |
| after (P-1 applied) | same | **332.2 s** | 05:30 | 2326 run / 8 skipped |

The delta is 97.9 s against the 97.7 s the audit attributed to the single cron class, and ACT Aria
fell from 112 s to 19.3 s while every other module moved by less than its noise band. The suite
grew by exactly the 3 tests added, so nothing was traded away for the speed.

## P-1: the cron wait

`TaskSchedulerSchedulerPortTest` now schedules at second granularity (`* * * * * *`, which
`normalizeCron` passes through unpadded) instead of the 5-field form that `normalizeCron`
(`TaskSchedulerSchedulerPort.java:106-108`) pads to `0 * * * * *`, i.e. second 0 of the next
minute. Class time 97.7 s -> 3.708 s, six tests:

| test | time | what it proves |
|------|------|----------------|
| `schedule_invokesOnFireCallback_whenJobFires` | 0.988 s | ONE_SHOT path fires the callback (was a 65 s ceiling) |
| `recoverActiveJobs_schedulesRecoveredJobs_withCallback` | 0.499 s | recovery path fires |
| `schedule_recurringJob_firesOnFireCallback_eachCronMatch` | 0.699 s | NEW: the `CronTrigger` branch, previously unexercised |
| `schedule_acceptsFiveFieldCron_byPaddingToSecondGranularity` | 0.003 s | NEW: the 5-field padding contract, at zero wall cost |
| `cancel_beforeFirstMatch_preventsTheFireCallback` | 1.516 s | negative control: nothing fires on its own |
| `recoverActiveJobs_shouldNotThrow_whenTableDoesNotExist` | 0.002 s | unchanged |

**Positive control, run and reverted**: with `TaskSchedulerSchedulerPort.fireCallback` emptied
(the trigger can never fire), the three firing tests went RED and the three non-firing tests stayed
green; the production file was restored to its original blob `b1834ecdca1e74cce420a9278de48f18e4ac210b`.
That is the proof the firing assertions are not vacuous.

Decision recorded under ambiguity: the alternative the brief allowed was an injectable clock/trigger.
Rejected, because a fake clock replaces the real `ThreadPoolTaskScheduler` + `CronTrigger` wiring
that is the thing under contract; second granularity keeps the production scheduling path and only
changes the fixture's time quantum. Timeouts were narrowed (65 s -> 5 s), never widened.

## Barriers

1. **Shared H2 schema.** `application-test.yml:5` is now `jdbc:h2:mem:act_test${aria.test.db.suffix:}`.
   Default empty = byte-identical to before; a suffix (not a name override) keeps
   `TestProfileConfigurationTest:21`'s `contains("jdbc:h2:mem:act_test")` true, verified by running
   that class with `ARIA_TEST_DB_SUFFIX=_probe` (2 tests green, resolved URL logged as
   `jdbc:h2:mem:act_test_probe`).
   Correction to the audit's premise: an H2 **in-memory** database lives inside one JVM, so separate
   Maven lanes could never collide on `act_test`. The knob matters for two lanes over the *same*
   module, which the shard design forbids anyway. The genuinely cross-process H2 hazard is the
   `h2` profile's file database `./data/act_db` (`application-h2.yml:14`) - and no test opens it:
   `H2ProfileConfigurationTest:32` asserts the URL string only, `DevSqlControllerH2ProfilePresenceTest`
   overrides it to in-memory, and every failsafe class without its own `@ActiveProfiles` inherits
   `{test, noop-llm}` from `BaseH2IntegrationTest:11`. The baseline unit-tier log contains no
   `data/act_db` reference.
2. **Fixed ports.** None found in the Maven tiers. Every socket a test opens binds port 0
   (`CoreE2eEndpointReadinessTest.java:43-45`, `CoreE2eProcessBackend.java:408-410`,
   `HostExecutionBackend.java:673-675`), and greps for `bind(...)` with a non-zero literal and for
   `new ServerSocket(<port>)` return nothing. The literals that do exist are not binds: `4096` is the
   production default for a *remote* core (`OpenCodeProperties.java:42`) reused as fixture data
   (`SandboxExecutionBackendTest.java:69`, `launch.test.mjs:35`), and `8090/9999/47279` appear inside
   stubbed messages and origin strings. Fixed ports that are real single-instance barriers live
   outside these lanes - `scripts/start*.ps1|sh` (dev stack) and the browser harness (vite 5273,
   API 8097) - so per-lane port allocation was not implemented, because there was nothing to
   parameterize inside the sharded tier. Reporting this rather than inventing a fix is deliberate.
3. **Wall-clock assertions.** Four classes are tagged `@Tag("timing")`:
   `CoreE2eEndpointReadinessTest` (loopback HttpServer), `OpenCodeCoreAdapterTest` (:117 10 s and
   :221 1 s over the readiness probe), `OpenSandboxSdkTest` (:336 1.5 s fail-fast bound, :309 the
   paired 2 s backoff), `RunWorkspaceServiceTest` (:1045, :1072 5 s git timeouts). Verified wiring:
   `-DexcludedGroups=timing` runs 0 of `OpenSandboxSdkTest`'s 16 tests, the same command without it
   runs all 16; `timing` replays exactly the 4 classes in 29-41 s. No threshold was touched.
   Examined and deliberately NOT tagged: `LaunchObservationTest` (:308/:354/:376 bound pure in-memory
   object calls against a 1 s ceiling, ~1000x margin), `SandboxLaunchFaultTest` (scripted SDK, same
   reasoning), `OwnedProcessControllerTest:158` and `HostExecutionBackendIntegrationTest:375`
   (logical stop-proof predicates, not durations), `ApprovalExpiryCheckerTest` (clock monotonicity).
   Rule applied: tag only where a contended resource (socket, subprocess, backoff sleep) could
   plausibly blow the bound; otherwise the class stays in the parallel lanes.
4. **Repeated compilation.** `shard` runs one `-T 1C install -DskipTests` and then lanes with disjoint
   module lists; module weights are the measured per-module reactor times above, packed
   longest-processing-first (measured split at K=2: 160.9 s vs 165.7 s of weight).
5. **Coverage isolation.** JaCoCo runs only on `gate`/`itg`; those lanes delete `target/jacoco.exec`
   first (the append-mode hole in section 6.2), and `LANE_ID` redirects the exec file via
   `-Djacoco.destFile`. `MVN_REPO` points a lane at a private local repository.

## Sharding, measured

All three numbers are the same script path (`shard K` = shared install + K lanes + serial timing lane),
so K=1 is the honest single-lane reference; the `refresh` line is quoted separately because the K=1
run happened straight after a `clean` and so paid 64.2 s of recompilation while K=2/K=3 were warm.

| K | lanes + timing lane | speedup vs K=1 | tests |
|---|---------------------|----------------|-------|
| 1 | **250.6 s** | 1.00x | 2256 + 70 = 2326 |
| 2 | **181.4 s** (+11.4 s refresh) | 1.38x | 2256 + 70 = 2326 |
| 3 | **156.3 s** (+12.4 s refresh) | 1.60x | 2256 + 70 = 2326 |

Completeness is arithmetic, not trust: lanes + timing = 2326 = the single-process total.
Sub-linear scaling is the finding. This box has 6C/6T at 53-76 % ambient CPU with another session's
Node builds running, ~5 GB free, and each lane forks one JVM that loads a whole Spring graph -
so K=3 spends its extra lanes on contention. The recommendation follows the measurement: default
shard K to 2, use 3 only when the machine is otherwise idle, and let CI's four runners carry K=4.

INFERRED, not measured here: behaviour on a 64 GB / 12-core machine (where K=3-4 may become
near-linear), and whether `timing` classes would flake at K>3. Neither was tested.
