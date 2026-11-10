# Issue #112 — single owner for the run result root in SDD spec review (2026-11-10)

Spec: [spec/spec.md](../../spec/spec.md). Branch `sdd/0525d3df-d50b-4de7-9238-cd5d3bdb4865`, base
`9829eef8` (`main` @ `825d3b91` lineage), shallow clone at `repo/`.
Measured on this session's host: Windows 10 19045, x64, Maven offline (`mvn -o`) against the warm
local repository. Every command below was run and its output pasted from that run; nothing here is
inferred from "should pass".

## 1. What the change does

`act-execution` is now the only code that knows where a finished run's artifacts live. `act-knowledge`
asks for a lookup instead of re-deriving the path.

| File | Change |
|---|---|
| `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/port/RunArtifactReaderPort.java` | new port: `resolve(runId, fileName)` + `read(runId, fileName, maxChars)`, with `RunArtifactText` nested so the port stays one file |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/WorkspaceRunArtifactReader.java` | new implementation; constructed from the already-provisioned canonical result root |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunWorkspaceService.java` | +`Path resultRoot()` accessor (the instance `capture` writes under) |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreRuntimeConfiguration.java` | +`runArtifactReaderPort` bean built from `workspaces.resultRoot()` |
| `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinator.java` | dropped the `@Value` parameter, `runResultRoot` field and `readSpecFromRunWorkspace`; injects `ObjectProvider<RunArtifactReaderPort>`; WARN with the resolved path; degraded `reason` prefix; startup warning |
| `agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinatorTest.java` | port-based wiring + 7 new cases (AC3/AC4/AC7 + bound/empty/absent-reader) |
| `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/WorkspaceRunArtifactReaderTest.java` | new, 13 cases incl. link safety, character bound, capture parity, token refusal (AC5–AC7) |
| `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/sdd/SddSpecReviewResultRootTest.java` | new context test with a relocated `aria.workspaces.result-root` (AC2) |
| `scripts/lanes/act-execution/g4.txt` | the new act-execution class added to the shard plan (see §3) |

Divergences 1–4 from the spec's table are gone by construction rather than by mirroring: token
expansion, `Files.createDirectories` provisioning and `toRealPath()` canonicalization all happen once,
in `WorkspacePaths.configuredRoot` + `CoreRuntimeConfiguration.provisionedRoot`, and the reader receives
the resulting `Path` object.

## 2. Acceptance criteria, with the command that proves each

**AC1 — one configuration source.** Verified by the two greps the criterion names, run at HEAD:

```
$ grep -rn 'aria\.workspaces\.result-root' --include=*.java agent-control-tower/*/src/main
agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreRuntimeConfiguration.java:93:            @Value("${aria.workspaces.result-root:./data/workspaces/results}") String resultRoot) {
agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreRuntimeConfiguration.java:96:                provisionedRoot(resultRoot, "aria.workspaces.result-root"),
agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreRuntimeConfiguration.java:122:     * {@code aria.workspaces.result-root}.

$ grep -n 'result-root' agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinator.java
(no output, rc=1)
```

Three matches, one file. The property is declared once, at
`agent-control-tower/act-app/src/main/resources/application.yml:125`
(`result-root: ${ARIA_WORKSPACES_RESULT_ROOT:./data/workspaces/results}`). No other module composes an
artifact path: `grep -rn 'data/workspaces/results' --include=*.java agent-control-tower/*/src/main`
returns only that same `CoreRuntimeConfiguration.java:93` line, and
`grep -rn 'aria\.workspaces\.'` over main sources has no hits outside that file.

**AC2 / AC3 / AC4 / AC5 / AC6 / AC7 — the test tiers.** From the four AC8 commands in §4:
`SddSpecReviewResultRootTest` 2/0/0/0, `SpecReviewCoordinatorTest` 32/0/0/0,
`WorkspaceRunArtifactReaderTest` 13/0/0/0, all of act-execution and act-common green.

- **AC2**: the context boots with `aria.workspaces.result-root` relocated to a `@TempDir` via
  `@DynamicPropertySource`; `spec.md` is exported there by the **real** `RunWorkspaceService.capture`
  with an `ExecutionBackend` that follows the `HostExecutionBackend.exportWorkspace` contract. The
  stored SPEC version content and the approval content both equal the file text, and the test also
  asserts nothing was created under the default `./data/workspaces/results/<runId>`. A reader that
  resolved its own root would read the default location, find nothing, and fall back — which the
  second assertion pair catches.
- **AC3**: `SpecReviewCoordinatorTest.onBaStepCompleted_warnsWithTheResolvedPathRunIdAndFallbackWhenTheSpecFileIsAbsent`
  attaches a Logback `ListAppender` and asserts exactly one WARN containing the resolved absolute
  path, the run id, `finalOutput` (case-insensitive) and the output length.
- **AC4**: `…marksTheApprovalReasonWhenTheSpecFileIsAbsent` asserts the reason starts with
  `Spec file unavailable (showing run output) - `; `…leavesTheApprovalReasonUnmarkedWhenTheSpecFileWasRead`
  asserts the normal reason is byte-identical to the pre-change string. The existing text is kept
  verbatim after the prefix rather than re-cased, so the non-degraded UI copy does not move.
- **AC5**: the 25 pre-existing `SpecReviewCoordinatorTest` cases are unchanged apart from the
  constructor wiring, and still pass. `RunWorkspaceService`'s own tests are untouched (`git status`
  shows only the accessor addition) and pass inside the act-execution lane.
- **AC6**: `readsThroughADirectoryAliasOfTheResultRootItself`, `neverFollowsAFileLinkPlantedInPlaceOfTheArtifact`
  and `neverFollowsALinkedRunDirectoryOutOfTheArtifactRoot` cover the link contract, and
  `readsWhatRunWorkspaceServiceCaptureActuallyWrote` proves reader/writer agreement against a real
  capture — that test *is* the §5 "repo-level check". `anUnexpandedTokenOrBlankRootIsRefusedBeforeAnyReaderIsBuilt`
  proves a `%NAME%` token or blank root is refused by `WorkspacePaths.configuredRoot` before a reader
  can be built from it. **These three link tests ran rather than being skipped on this host**
  (`skipped="0"` for the class), so the platform-forbids-link caveat in AC6 did not apply here.
- **AC7**: `…stillApprovesFromFinalOutputWhenTheArtifactReadThrows` injects a reader whose `read`
  throws, and asserts the approval is still created from `finalOutput`;
  `reportsADirectoryInPlaceOfTheArtifactAsEmpty` covers the non-regular-file case. `resolveSpec`
  wraps the port call in `try/catch (RuntimeException)` so nothing escapes `onBaStepCompleted`.

**AC8** — see §4.

## 3. Lane plan

A new act-execution test class is not automatically scheduled: `lane-plan.mjs check` gates that the
committed fragments are an exact cover. First run, before the fix:

```
$ node scripts/lane-plan.mjs check          # rc=1
check: act-execution partition is NOT an exact cover (125 listed / 126 discovered / 111 measured)
   MISSING  io.aria.conductor.execution.runtime.WorkspaceRunArtifactReaderTest (in no fragment - it would never run.
   Remedy: `node scripts/lane-plan.mjs gen act-execution`, which packs a class with no measured cost at 0s)
```

After the prescribed `gen`:

```
$ node scripts/lane-plan.mjs check          # rc=0
check: act-execution exact cover OK - 126 shardable classes, 4 fragments, and the measured run's 111 classes are all accounted for
check: act-execution fragmentCostSum=68.84s expected=68.84s (classSum=76.8s - 3 timing classes at 8.0s, replayed serially)
```

The regeneration is a two-line change to `g4.txt` only (the new class and its `# classes=` header);
`g1`–`g3` and `scripts/lanes/measured-costs.json` are byte-identical, and the cost sum is unchanged
because the class carries no measured cost. The `UNMEASURED` notice lines for
`SddSpecReviewResultRootTest` and other new/late classes are advisory, not failures — that is what the
`rc=0` above records.

## 4. Commands run, with their captured summaries

All four are the exact AC8 commands, re-run last against the final file states (comment-only edits in
`act-common` and `act-knowledge` landed after the first passes, so those tiers were run again rather
than reported from the earlier pass). `mvn-lane` prints one line per step; the per-class numbers come
from the surefire XML it wrote in the same run, and the coverage numbers from its JaCoCo CSV.

```
$ bash scripts/mvn-lane.sh gate act-knowledge
gate:refresh:act-knowledge   wall=   9.9s rc=0  Total time:  8.648 s
gate:act-knowledge           wall=  20.9s rc=0  Total time: 19.598 s

$ bash scripts/mvn-lane.sh gate act-execution
gate:refresh:act-execution   wall=  22.9s rc=0  Total time: 21.621 s
gate:act-execution           wall= 105.8s rc=0  Total time:  01:44 min

$ bash scripts/mvn-lane.sh gate act-common
gate:refresh:act-common      wall=   2.9s rc=0  Total time:  1.674 s
gate:act-common              wall=  14.1s rc=0  Total time: 12.799 s

$ bash scripts/mvn-lane.sh inner act-app -Dtest=SddSpecReviewResultRootTest
inner:act-app                wall=  16.8s rc=0  Total time: 15.588 s
```

Aggregated surefire results for those runs:

| module / class | classes | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| act-knowledge (gate) | 25 | 270 | 0 | 0 | 4 |
| &nbsp;&nbsp;└ `SpecReviewCoordinatorTest` | 1 | 32 | 0 | 0 | 0 |
| act-execution (gate) | 129 | 1364 | 0 | 0 | 0 |
| &nbsp;&nbsp;└ `WorkspaceRunArtifactReaderTest` | 1 | 13 | 0 | 0 | 0 |
| act-common (gate) | 17 | 136 | 0 | 0 | 0 |
| act-app `-Dtest=SddSpecReviewResultRootTest` (inner) | 1 | 2 | 0 | 0 | 0 |

`rc=0` on a `gate` lane means Maven exited clean, so "0 errors" is a measured fact here, not an
inference from "no failures printed".

The 4 skips in act-knowledge are all in
`io.aria.conductor.knowledge.selfimprove.SandboxExecutorTest`, a class this change does not touch. No
class in the new or modified test files was skipped.

Coverage ratchets (`jacoco:check` ran inside each `gate`; the observed ratios are from the JaCoCo CSV
of the same runs):

| module | line ratchet | line observed | branch ratchet | branch observed |
|---|---:|---:|---:|---:|
| act-knowledge | 0.70 | 0.8308 (1606/1933) | 0.54 | 0.6490 (551/849) |
| act-execution | 0.58 | 0.7702 (8300/10777) | 0.42 | 0.6601 (3455/5234) |
| act-common | 0.55 | 0.6027 (352/584) | 0.60 | 0.6616 (131/198) |

## 5. Not verified / known gaps

- **Q5 (live SDD run with `ARIA_WORKSPACES_RESULT_ROOT` relocated): NOT VERIFIED.** No live chain was
  executed in this session; AC2 is proven by the booted Spring context plus the production capture
  path, not by a real BA agent.
- Failsafe tiers (`itg`) for the three gated modules were not run; the act-app change is a `@Test`
  class in the surefire tier, matched by the `inner` command AC8 names.
- The reader keeps `Optional<RunArtifactText>` for `read`, so an absent artifact yields no record and
  the caller logs `resolve(...)` — this differs from the spec's illustrative comment "path is always
  present, including when the read came up empty", which cannot hold for an `Optional` return. AC3's
  requirement (the WARN names the resolved absolute path) is met either way; `resolveSpec` logs
  `artifact.map(RunArtifactText::path).orElse(location)`.
- Pre-existing, out of scope: a fallback while `finalOutput` is null or blank still opens an approval
  with null/blank content, and `ArtifactBundle.complete() == false` is not visible to `act-knowledge`.
  The WARN wording says the artifact is absent for this run, which is the most either can say.
- The `maxChars` buffer is allocated up front, matching the previous inline reader. It is safe because
  the only caller passes 50 KB; a future caller passing an arbitrary bound would need a streaming
  bound instead.
