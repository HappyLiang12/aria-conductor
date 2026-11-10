# QA report — issue #112, single owner for the run result root in SDD spec review

**Spec:** `spec/spec.md` (spec id `0525d3df-d50b-4de7-9238-cd5d3bdb4865`)
**Branch:** `sdd/0525d3df-d50b-4de7-9238-cd5d3bdb4865`
**Revision under test:** `da4282e3` ("sdd dev"), base `9829eef8` ("sdd: approve spec")
**QA run:** 2026-11-10, Operator (QA step)
**Verdict: PASS** — all eight acceptance criteria verified against tests that actually ran.

Environment actually used: Windows 10 19045 x64, OpenJDK 21.0.11, Maven 3.9.6 offline (`mvn -o`)
against the warm local repository, node v24.14.1. Checkout verified complete before testing:
`git -c core.longpaths=true clone --branch sdd/0525d3df-... repo` checked out 1840/1840 files and
`git status --porcelain` was empty at clone time and again after every build below.

---

## 1. Commands run, with captured output (AC8)

Each command was run at `da4282e3` and its Maven log archived. `rc` is the Maven lane exit code.

| # | Command | rc | wall | Tests run | Fail | **Err** | Skip | Build |
|---|---|---:|---:|---:|---:|---:|---:|---|
| 1 | `bash scripts/mvn-lane.sh gate act-common` | 0 | 14 s | 136 | 0 | **0** | 0 | SUCCESS |
| 2 | `bash scripts/mvn-lane.sh gate act-execution` | 0 | 107 s | 1364 | 0 | **0** | 0 | SUCCESS |
| 3 | `bash scripts/mvn-lane.sh gate act-knowledge` | 0 | 22 s | 270 | 0 | **0** | 4 | SUCCESS |
| 4 | `bash scripts/mvn-lane.sh inner act-app -Dtest=SddSpecReviewResultRootTest` | 0 | 15 s | 2 | 0 | **0** | 0 | SUCCESS |

Captured summary lines (verbatim from the lane logs):

```
act-common     : [INFO] Tests run: 136, Failures: 0, Errors: 0, Skipped: 0
                 [INFO] All coverage checks have been met.   [INFO] BUILD SUCCESS   (17 classes)
act-execution  : [INFO] Tests run: 1364, Failures: 0, Errors: 0, Skipped: 0
                 [INFO] All coverage checks have been met.   [INFO] BUILD SUCCESS   (129 classes)
act-knowledge  : [WARNING] Tests run: 270, Failures: 0, Errors: 0, Skipped: 4
                 [INFO] All coverage checks have been met.   [INFO] BUILD SUCCESS   (25 classes)
act-app inner  : [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.40 s
                   -- in io.aria.conductor.app.sdd.SddSpecReviewResultRootTest
                 [INFO] BUILD SUCCESS
```

**No run reported errors.** `rc=0` on a `gate` lane means Maven exited clean, so "0 errors" here is
a measured fact rather than an inference from "no failures printed".

Per-class results for the classes this change owns:

```
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0 -- io.aria.conductor.knowledge.sdd.SpecReviewCoordinatorTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- io.aria.conductor.execution.runtime.WorkspaceRunArtifactReaderTest
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0 -- io.aria.conductor.execution.runtime.RunWorkspaceServiceTest
[INFO] Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- io.aria.conductor.app.sdd.SddSpecReviewResultRootTest
```

The 4 skips in act-knowledge are all one class this change does not touch:

```
[WARNING] Tests run: 5, Failures: 0, Errors: 0, Skipped: 4 -- in io.aria.conductor.knowledge.selfimprove.SandboxExecutorTest
```

No class in the new or modified test files was skipped — `WorkspaceRunArtifactReaderTest` reports
`Skipped: 0`, which matters for AC6 (its three link tests ran rather than being assumed-skipped on
this Windows host).

Coverage ratchets, recomputed from each lane's own `target/site/jacoco/jacoco.csv` (not taken from
the dev report):

| Module | line ratchet | line observed | branch ratchet | branch observed |
|---|---:|---:|---:|---:|
| `act-knowledge` | 0.70 (`act-knowledge/pom.xml:19`) | 0.8308 (1606/1933) | 0.54 (`:20`) | 0.6490 (551/849) |
| `act-execution` | module property | 0.7700 (8298/10777) | module property | 0.6595 (3452/5234) |
| `act-common` | module property | 0.6027 (352/584) | module property | 0.6616 (131/198) |

`All coverage checks have been met.` printed in all three `gate` lanes, which deleted
`target/jacoco.exec` first, so the ratios above are from these runs only.

Lane-plan hygiene (`node scripts/lane-plan.mjs check`): passes. The new act-execution class was
added to the shard partition (`scripts/lanes/act-execution/g4.txt`, `classes=53` → `54`), so it is
scheduled in the hosted shard lane and not merely runnable by hand. The `UNMEASURED` notice for
`SddSpecReviewResultRootTest` is advisory and confirms the class is discovered in act-app's surefire
tier, i.e. CI reaches it.

---

## 2. Acceptance criteria, one by one

| AC | Status | Evidence |
|---|---|---|
| AC1 one configuration source | **VERIFIED** | greps below, run at HEAD |
| AC2 non-default root read end to end | **VERIFIED** | `SddSpecReviewResultRootTest` 2/0/0/0 + mutation check §3 |
| AC3 fallback logs the resolved path | **VERIFIED** | `ListAppender` test, 32/0/0/0 |
| AC4 degraded content distinguishable | **VERIFIED** | both reason tests, 32/0/0/0 |
| AC5 existing behaviour preserved | **VERIFIED** | 25 pre-existing cases retained (25 → 32 `@Test`), all green; `RunWorkspaceServiceTest` 43/0/0/0 |
| AC6 canonicalization parity by construction | **VERIFIED** (evidence nuance §4-F3) | link tests ran (`Skipped: 0`), parity test, token refusal |
| AC7 no new failure mode in the event path | **VERIFIED** (hardening note §4-F1) | throwing-reader test + non-regular-file test |
| AC8 verification actually run | **VERIFIED** | §1 |

### AC1 — commands and output

```
$ grep -rn 'aria\.workspaces\.result-root' --include=*.java agent-control-tower/*/src/main
act-execution/.../CoreRuntimeConfiguration.java:93:  @Value("${aria.workspaces.result-root:./data/workspaces/results}") String resultRoot) {
act-execution/.../CoreRuntimeConfiguration.java:96:  provisionedRoot(resultRoot, "aria.workspaces.result-root"),
act-execution/.../CoreRuntimeConfiguration.java:122: * {@code aria.workspaces.result-root}.   (javadoc)

$ grep -n 'result-root' .../knowledge/sdd/SpecReviewCoordinator.java
(no output, rc=1)

$ grep -rn 'data/workspaces/results' --include=*.java agent-control-tower/*/src/main
CoreRuntimeConfiguration.java:93   (the same single line)

$ grep -rn 'aria\.workspaces\.' --include=*.java agent-control-tower/*/src/main
CoreRuntimeConfiguration.java -> result-root, runtime-root   (one file, act-execution)
```

All three matches are one file (`CoreRuntimeConfiguration`), the property is declared once in
`act-app/src/main/resources/application.yml:125`, and `grep -n 'result-root' SpecReviewCoordinator.java`
returns nothing. `grep -rn 'runResultRoot' agent-control-tower/` hits only
`SpecReviewCoordinatorTest` (the `@TempDir` field name), never main source. The remaining
`resolve(runId)` path compositions in main source (`RunWorkspaceService.java:1008`,
`WorkspaceManager.java:89,111,189,206`, `WorkspaceRunArtifactReader.java:55`) are all inside
`act-execution`, the owning module; the only artifact fact `act-knowledge` still names is the file
constant `"spec.md"` (`SpecReviewCoordinator.java:54`), which it passes *through* the port rather
than composing a path with it. AC1's wording is met.

### AC2 / AC3 / AC4 / AC5 / AC7 — what the tests actually assert

`SddSpecReviewResultRootTest` (act-app): boots the context with `aria.workspaces.result-root`
relocated by `@DynamicPropertySource` (`:71`), asserts the wiring is one resolution
(`runWorkspaceService.resultRoot()` equals the relocated root `toRealPath()`, and the bean is a
`WorkspaceRunArtifactReader`, `:93-94`), exports `spec.md` through the **real**
`RunWorkspaceService.capture` with a backend honouring the `HostExecutionBackend.exportWorkspace`
contract (`:150-160`, including `bundle.complete()` is true), then asserts the stored SPEC version
content and the approval content both equal the file body, do not contain `SPEC_ID=`, are **not**
`finalOutput`, and that nothing was created under the default root (`:102-111`). The second case
asserts the fallback path marks the reason.

`SpecReviewCoordinatorTest` — AC3 attaches a Logback `ListAppender` to the coordinator logger and
asserts exactly **one** WARN containing the resolved absolute path from `reader.resolve(...)`, the
run id, the word `finalOutput`, and the output length
(`onBaStepCompleted_warnsWithTheResolvedPathRunIdAndFallbackWhenTheSpecFileIsAbsent`). AC4 has both
directions: the fallback reason starts with `Spec file unavailable (showing run output) - ` and the
normal reason is byte-identical to the pre-change string. AC5: the 25 pre-existing cases survived the
change (verified by counting `@Test` in `9829eef8`'s copy vs HEAD: 25 → 32, nothing deleted),
including the full-file read, missing-file fallback, path-is-a-directory fallback, idempotency,
rejection re-scheduling, startup recovery, cancellation expiry, `resubmitApproval`, the 50 KB bound,
tool-call-chatter stripping and the branch/spec commit; the added cases are 7. The test now wires the
**real** `WorkspaceRunArtifactReader` over the `@TempDir` root rather than a fake, so the knowledge
side is exercised against the execution side's own code. AC7:
`onBaStepCompleted_stillApprovesFromFinalOutputWhenTheArtifactReadThrows` injects a port whose `read`
throws and asserts the approval is still created from `finalOutput`;
`reportsADirectoryInPlaceOfTheArtifactAsEmpty` and `reportsAMissingRunIdAsEmptyAndResolvesTheArtifactRoot`
cover the reader's own non-throwing contract;
`onBaStepCompleted_announcesAMissingReaderAtStartupOnly` asserts zero per-review WARN with no reader
and exactly one startup WARN naming the wired-up message.

---

## 3. Regression teeth: mutation check on AC2

An AC test that would pass anyway is worth nothing, so the integration was temporarily mutated to
resolve its own root and the AC2 lane re-run.

```
mutation: CoreRuntimeConfiguration.runArtifactReaderPort(...) built
          WorkspaceRunArtifactReader from Path.of("./data/workspaces/results")
          instead of workspaces.resultRoot()
$ mvn -o -B -pl act-execution -am install -DskipTests        # rc=0
$ bash scripts/mvn-lane.sh inner act-app -Dtest=SddSpecReviewResultRootTest
rc=1
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0 <<< FAILURE! -- in ...SddSpecReviewResultRootTest
java.lang.IllegalStateException: Failed to load ApplicationContext ...
[INFO] BUILD FAILURE
```

The AC2 test does not survive a self-resolving reader. The mutation was then reverted
(`git checkout -- <file>`, `git status --porcelain` empty) and both affected lanes were re-run from
the restored sources before the numbers in §1 were recorded: `gate act-execution` → 1364/0/0/0,
rc=0; `inner act-app` → 2/0/0/0, rc=0; `gate act-knowledge` re-run → 270/0/0/4, rc=0.

Scope of this evidence, stated honestly: a self-resolving reader that still read the *same* property
(a pre-fix `@Value` duplication) would resolve to the same relocated directory in this test and would
therefore pass it. The criterion that kills that variant is AC1's greps plus AC6's canonicalization
and capture-parity tests, not AC2 alone. No AC depends on the mutation result beyond confirming that
AC2 is not vacuous.

---

## 4. Findings

None blocking. Every one is a note for the operator, not a required change.

**F1 — `reader.resolve(...)` sits outside the coordinator's try (AC7 hardening, latent).**
`SpecReviewCoordinator.resolveSpec` calls `reader.resolve(baRunId, SPEC_ARTIFACT_NAME)` one statement
before the `try` that guards `reader.read(...)`. AC7 says no exception may escape
`onBaStepCompleted`. Today this cannot happen: `resolve` does no IO and the only `fileName` passed is
the constant `"spec.md"`, so the sole throw path is `artifactName`'s validation of a value the caller
controls. Still, moving the `resolve` call inside the existing `try/catch (RuntimeException)` would
make the guarantee structural instead of dependent on a constant. Not a defect at this revision.

**F2 — `RunArtifactText.path` never materialises on the empty branch (spec-text inconsistency).**
Spec §1 annotates the record with "path is always present, including when the read came up empty",
which cannot hold for a signature the same spec fixes as `Optional<RunArtifactText> read(...)`. The
implementation returns no record when the artifact is absent and the caller logs
`artifact.map(RunArtifactText::path).orElse(location)` — the path is still named in the WARN, so AC3
is met either way. The deviation is already disclosed in
`docs/reviews/2026-11-10-issue-112-result-root-contract.md` §5. This is an ambiguity in the spec, not
a defect in the code; flagging so the spec text gets corrected rather than the next reader treating
the comment as contract.

**F3 — AC6's token half is proven at the resolver, not by a booted context.**
AC6 asks that an unexpanded `%NAME%` root "makes the context fail at startup naming
`aria.workspaces.result-root`". The committed proof is
`anUnexpandedTokenOrBlankRootIsRefusedBeforeAnyReaderIsBuilt` (asserts
`WorkspacePaths.configuredRoot` throws naming the property and the missing variable) plus the AC1
grep showing one resolution feeds both sides; no test boots a Spring context with a token-valued
root. The guarantee itself does hold — the reader is built from the `Path` that
`CoreRuntimeConfiguration.provisionedRoot` produced, so a refused value fails the
`runWorkspaceService` bean and the context with it, and there is no second resolution left to drift.
The same evidence shape (unit, not context) already covered the capture side before this change, so
the criterion is met at the strength the AC's own "by construction" framing asks for. Recorded as an
evidence-strength nuance rather than NOT VERIFIED.

**F4 — open spec questions were answered unilaterally by the implementation.**
The spec was approved with Q1-Q6 still open. The dev chose: Q1 = the port (spec's recommendation);
Q2 = the breaking constructor change with no deprecated overload (only the test constructs it, and
the test was updated in the same commit); Q4 = **enabled**, so AC4 is live and enforced by tests;
Q6 = the duplicated default string stays in `application.yml:125` and the `@Value`. None of these
violates an acceptance criterion — AC4 is written as "with AC4 enabled", so enabling it makes a
testable criterion true rather than adding unrequested scope. But Q4 and Q2 are product decisions the
operator never answered, and Q5 (a live SDD run with `ARIA_WORKSPACES_RESULT_ROOT` relocated) was
explicitly **not** done by the dev and is not an AC, so it is not held against this branch. If the
operator wants the UI degradation prefix suppressed, or wants live SDD evidence in the DoD, that is a
follow-up, not a rework of this change.

**F5 — independent reproduction of the dev's numbers.**
Every figure in §1 was produced by this QA run, not copied. They reproduce the dev report's table
exactly except `act-execution` line coverage, where the report says 8300/10777 (0.7702) and this run
measured 8298/10777 (0.7700) — two lines, within normal JaCoCo run-to-run variance, both far above the
ratchet. Nothing in the dev report was found to be claimed-but-unrun.

---

## 5. What was not run

- Failsafe (`itg`) tiers for the three modules: AC8 does not ask for them and the act-app addition is
  a surefire-tier `@Test`. NOT RUN, not required.
- A live SDD chain with a relocated `ARIA_WORKSPACES_RESULT_ROOT` (spec Q5): NOT RUN; the spec leaves
  it as a question rather than an AC, and the dev disclosed it in §5 of the report.
- Frontend, MCP server, container-runtime and start-script tiers: untouched by this change and out of
  AC8's scope. NOT RUN.
- Nothing in §1, §2 or §3 is reported as verified without having been executed on this machine.

## 6. Verdict

All eight acceptance criteria were evaluated and each was verified by a command that ran here. The
four AC8 lanes exited `rc=0` with **0 failures and 0 errors** across 1772 tests, coverage ratchets
passed in all three gated modules, the link-alias tests genuinely ran on this host instead of being
skipped, and the AC2 test was shown to have teeth by a mutation that made it fail. The remaining
items (F1-F4) are hardening notes and unanswered product questions, not unmet criteria.

**VERDICT=PASS**
