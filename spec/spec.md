# Spec: Single owner for the run result root in SDD spec review (issue #112)

**Issue:** #112 — "SDD spec review reads result-root through a duplicated filesystem contract"
**Repository:** `HappyLiang12/aria-conductor` (labels: `bug`, `priority:P2`)
**Analysed revision:** `main` @ `825d3b91` (shallow clone at `repo/`)
**Author:** Operator (BA step)

## Problem Statement

### What the product does today

The SDD loop writes the BA spec to a file, and the spec-review gate is supposed to show the
reviewer that **full** file. Two modules cooperate, and they agree on nothing except a string:

1. **Capture side (`act-execution`).** The BA agent writes `spec.md` in its run workspace. When the
   run finalizes, `RunFinalizer` calls `RunWorkspaceService.capture(...)`, which exports the stopped
   workspace into `<resultRoot>/<runId>/`
   (`RunWorkspaceService.java:441-463`; `HostExecutionBackend.java:395-407` copies the workspace
   root contents directly into that directory, so `spec.md` lands at
   `<resultRoot>/<runId>/spec.md`).
2. **Read side (`act-knowledge`).** On `BaStepCompletedEvent`, `SpecReviewCoordinator` re-derives
   the same path from a value it resolves by itself — `@Value("${aria.workspaces.result-root:...}")`
   (`SpecReviewCoordinator.java:78`), `Path.of(...).toAbsolutePath().normalize()` (`:89`),
   `runResultRoot.resolve(runId.toString()).resolve("spec.md")` (`:297`) — and reads it
   (`:293-315`).

### The defect

The two sides are **two independent resolutions of one contract**: the property name, its default
value, the `<runId>` directory spelling and the `spec.md` file name are duplicated in
`SpecReviewCoordinator.java:78,89,297` and in
`CoreRuntimeConfiguration.java:91-96` + `RunWorkspaceService.java:149,441`. Nothing at compile
time, startup, or test time ties them together. Any change on one side silently degrades the
SPEC_REVIEW approval to the BA run's `finalOutput` — which is typically a two-line summary plus a
`SPEC_ID=` marker, not the spec the reviewer is being asked to approve
(`SpecReviewCoordinator.java:280-286`).

The degradation is silent for three reasons:

- `resolveSpecContent` (`:280-286`) falls through to `event.getFinalOutput()` with **no log at all**
  when the file simply isn't there; `Files.isRegularFile(...) == false` returns `null`
  (`:299-301`) and only a thrown exception logs anything (`:311-314`).
- A summary that starts with a heading survives `cleanSpecContent` (`:381-402`) and is stored as a
  SPEC knowledge item and opened as an approval that **looks** like a real spec
  (`:111-123`). The reviewer cannot tell degraded content from the artifact.
- The failure is not recorded anywhere — no warning, no approval reason, no run field.

### Divergences already present in the two resolutions

| # | Divergence | Read side | Capture side |
|---|---|---|---|
| 1 | `%NAME%` Windows token | used **literally** (`:89`) — no expansion, no refusal | expanded, or startup refused with the property name (`WorkspacePaths.java:56-89`, called from `CoreRuntimeConfiguration.java:94-95`) |
| 2 | Non-existent root | read misses silently | provisioned with `Files.createDirectories` (`CoreRuntimeConfiguration.java:106-113`) |
| 3 | Symlink/junction alias | lexical `normalize()` only | canonicalized with `toRealPath()` (`RunWorkspaceService.java:149`, `1025-1030`) |
| 4 | Blank value | resolves to the process working directory | refused (`WorkspacePaths.java:62-64`) |
| 5 | Default value | `./data/workspaces/results` duplicated as an annotation default (`:78`, `:92`) | same string, second copy |

Rows 1 and 4 are live failure modes: an operator who sets
`ARIA_WORKSPACES_RESULT_ROOT=%SystemDrive%\aria\results` gets a working capture and a permanently
degraded spec review, and the platform never says so. (The `%SystemDrive%` class of bug already
shipped once as F21 — see the `WorkspacePaths.java:39-51` javadoc.)

### Blast radius

Every SDD chain whose BA run is bound to a repo and every deployment that relocates storage
(docker-compose, hosted CI, the `scripts/lib/workspace-roots.sh:19-29` default base, the
`e2e/agent-core/live-matrix.mjs:402-421` pins). The approval gate is a **governance** control: a
reviewer approving a summary instead of the spec means the DEV/QA steps are seeded from
`{specRef}` knowledge (`:329-338`) and the branch commit `spec/spec.md` (`:358`) that no human
actually read in full.

### Prior art in the repo

- `docs/superpowers/plans/2026-10-24-current-main-p1-hardening.md:235-237` already scoped this as
  "Task 3: #112 — SDD result-root contract", with the same acceptance shape: *one configuration
  source, non-default root test, warning with resolved path on fallback*. It was deferred, not
  resolved.
- `docs/reviews/2026-11-08-live-sdd-round2-findings.md:19` records a live SDD chain run **for
  issue #112 itself** ("Unify Result-Root Configuration for Spec Review"), whose DEV/QA work landed
  on the chain branch only. The condition is still present on `main` at `825d3b91`, confirmed by
  the code above.
- Evidence correction: the issue cites `application.yml:113`; in this revision the property is at
  `agent-control-tower/act-app/src/main/resources/application.yml:125`
  (`result-root: ${ARIA_WORKSPACES_RESULT_ROOT:./data/workspaces/results}`). The issue also cites
  `docs/reviews/2026-10-21-pr109-legacy-execution-retirement/architecture-review.md` (PR #110),
  which does **not exist** in the `main` checkout — the risk-review finding R-03 is therefore
  cited from code, not from that document.

## Proposed Solution

**Make `act-execution` the only code that knows where a run's artifacts live, and give
`act-knowledge` a lookup instead of a filesystem walk.** This is the issue's second option, and it
is preferred over "one shared `@Value`" because sharing the string still leaves two readers with
different canonicalization (table rows 1-4 above).

### 1. New cross-module port in `act-common`

Add `io.aria.conductor.common.port.RunArtifactReaderPort`, following the established convention for
execution-owned ports consumed by other modules (`RunBindingReaderPort.java:11-28`,
`RunRuntimeControlPort`, `RunInputPort`, `KnowledgeCapturePort`, `SchedulerPort` — all in
`act-common/src/main/java/io/aria/conductor/common/port/`):

```java
public interface RunArtifactReaderPort {
    /** The canonical location an artifact would be read from, for diagnostics. */
    Path resolve(UUID runId, String fileName);

    /** bounded read; empty when the run has no such regular file. */
    Optional<RunArtifactText> read(UUID runId, String fileName, long maxChars);
}

/** path is always present, including when the read came up empty, so callers can log it. */
public record RunArtifactText(Path path, String content) {}
```

Non-goals for this change: no new REST endpoint, no artifact listing/search API, no DB column
recording the artifact path, no change to `capture`, retention, the manifest, or the
secret-scrubbing walks.

### 2. Implementation owned by `act-execution`

`WorkspaceRunArtifactReader` (new, in `execution/runtime/`), constructed with the **already
provisioned canonical** `resultRoot` — the exact `Path` instance
`CoreRuntimeConfiguration.java:94-96` hands to `RunWorkspaceService`. It therefore inherits token
expansion, provisioning and canonicalization instead of re-implementing them, and it can never
disagree with the writer. The read is byte-bounded by `maxChars` (the caller passes 50 KB), uses
UTF-8, is link-safe (only a regular file at the resolved path is read; a symlink/junction escaping
the artifact root is not followed), and returns empty instead of throwing.

Expose it as a bean in `CoreRuntimeConfiguration` next to `runWorkspaceService`, and add
`Path resultRoot()` access (or keep the value as a `@Bean`-level local) so both the writer and the
reader are built from one resolution of `aria.workspaces.result-root`. `RunWorkspaceService` keeps
its explicit constructor (its javadoc at `:50-54` states it deliberately takes no Spring
annotations) — the new reader wraps the same root rather than turning the service into a Spring
bean.

### 3. `SpecReviewCoordinator` consumes the port

- Delete the `@Value` parameter, the `runResultRoot` field (`:66,78,89`) and the local path
  arithmetic (`:293-315`); inject `RunArtifactReaderPort` instead. `act-knowledge` already depends
  on both `act-common` and `act-execution` (`act-knowledge/pom.xml`), so no new module edge is
  needed.
- `resolveSpecContent` (`:280-286`) becomes: read through the port → present ⇒ use it; absent ⇒
  **`log.warn` naming the resolved absolute path, the run id and the fallback**
  (`finalOutput length N`), then use `event.getFinalOutput()`.
- The port is consumed as an optional dependency (`ObjectProvider`, matching
  `RunBindingReaderPort`'s precedent): with no reader bean, the coordinator logs **one** startup
  warning — "SDD spec review will use run final output; no artifact reader is wired" — and keeps
  working. A non-SDD deployment must not fail to boot.
- Keep `MAX_SPEC_CONTENT_LENGTH` (50 KB) as the caller's bound and keep `cleanSpecContent`, the
  idempotency guards, the 5-section behaviour, the branch handoff and the timeout semantics
  untouched.

### 4. Fallback becomes visible where it matters

A degraded review is a governance event, so beyond the warning: when the content came from
`finalOutput`, the approval's `reason` is prefixed
`Spec file unavailable (showing run output) - spec ready for review: <name>` so the reviewer sees
the degradation in the UI. (Gated on Question Q4.)

### 5. Guard against reintroduction

`WorkspacePaths.configuredRoot` stays the only entry point for `aria.workspaces.*` values, and a
test asserts the reader and `RunWorkspaceService` agree. Add a repo-level check in the change's
test (not a build plugin) rather than a runtime "assert both resolvers match" startup dance: with
one resolver there is nothing left to compare, which is why the assertion option in the issue body
is treated as the fallback plan, not the chosen one.

### Files in scope

| File | Change |
|---|---|
| `act-common/.../common/port/RunArtifactReaderPort.java` | new port + `RunArtifactText` |
| `act-execution/.../execution/runtime/WorkspaceRunArtifactReader.java` | new implementation |
| `act-execution/.../execution/runtime/CoreRuntimeConfiguration.java` | build the reader from the provisioned root; expose the bean |
| `act-knowledge/.../knowledge/sdd/SpecReviewCoordinator.java` | drop `@Value`/`runResultRoot`/`readSpecFromRunWorkspace`; inject the port; warn with path |
| `act-knowledge/src/test/.../SpecReviewCoordinatorTest.java` | port-based wiring; new fallback-warning + non-default-root tests |
| `act-execution/src/test/.../` | new reader unit test incl. link safety and token handling |
| `act-app/src/test/...` | one context test with a non-default `aria.workspaces.result-root` |
| `docs/reviews/2026-11-xx-issue-112-result-root-contract.md` | report with the evidence commands (AGENTS.md evidence discipline) |

## Acceptance Criteria

**AC1 — one configuration source**
`aria.workspaces.result-root` is read from exactly one place in main source
(`CoreRuntimeConfiguration`). `grep -rn 'aria\.workspaces\.result-root' --include=*.java
agent-control-tower/*/src/main` returns only `CoreRuntimeConfiguration.java`, the property itself is
declared once in `application.yml:125`, and
`grep -n 'result-root' SpecReviewCoordinator.java` returns nothing. No other module composes a
workspace artifact path.

**AC2 — a non-default root is read, end to end**
A test boots the Spring context with `aria.workspaces.result-root` pointed at a non-default
`@TempDir` path, writes `<root>/<baRunId>/spec.md` through the **capture** path
(`RunWorkspaceService.capture`, or the same `HostExecutionBackend.exportWorkspace` contract), then
publishes `BaStepCompletedEvent` and asserts the stored SPEC knowledge content and the
`SPEC_REVIEW` approval content equal the **file** text (cleaned, `SPEC_ID=` stripped), and are
**not** equal to the event's `finalOutput`. The test must fail if the reader resolves its own root.

**AC3 — fallback logs the resolved path**
When `spec.md` is absent, `SpecReviewCoordinator` logs at WARN level containing: the resolved
absolute path, the run id, and that `finalOutput` is being used. Proven by a log-capture assertion
(`ListAppender`) in `SpecReviewCoordinatorTest`, not by eyeballing.

**AC4 — degraded content is distinguishable**
With AC4 enabled (see Q4): the approval created on the fallback path carries the
`Spec file unavailable` prefix in `reason`, and the normal path does not.

**AC5 — existing behaviour preserved**
All pre-existing `SpecReviewCoordinatorTest` cases stay green with only the constructor wiring
changed, including: full-file read (`:132-148`), missing-file fallback (`:151-159`),
path-is-a-directory fallback (`:161-171`), idempotency, rejection re-scheduling, startup recovery,
cancellation expiry, `resubmitApproval`, the 50 KB bound, tool-call-chatter stripping
(`stripToolCallChatter`), and the branch/spec commit. `RunWorkspaceService`'s capture, manifest,
lease and bounded-walk tests are untouched and green.

**AC6 — canonicalization parity, by construction**
The reader is built from the same `Path` instance used by `capture`. A test with a result root
reached through a symlink/junction alias (skipped where the platform forbids link creation, and
recorded as skipped rather than passed) proves the reader resolves to the canonical directory. A
result root containing an unexpanded `%NAME%` token makes the context fail at startup naming
`aria.workspaces.result-root` — the same behavior the capture side already has, now applied to the
read side too.

**AC7 — no new failure mode in the event path**
Every filesystem failure inside the reader is reported as empty/`Optional` (with the warning),
never as an exception propagated out of `onBaStepCompleted`: the BA step is already complete and a
throw would leave the chain `RUNNING` with no approval. Proven by a test that makes the read throw
(`chmod`-style permission failure, or a directory substituted for the file) and asserts the
approval is still created from `finalOutput`.

**AC8 — verification actually run**
Reported numbers come from commands that ran, per AGENTS.md "Evidence Discipline":
`bash scripts/mvn-lane.sh gate act-knowledge`, `bash scripts/mvn-lane.sh gate act-execution`,
`bash scripts/mvn-lane.sh gate act-common`, plus the `act-app` context test
(`bash scripts/mvn-lane.sh inner act-app -Dtest=<new test class>`). Coverage ratchets
(`act-knowledge/pom.xml`: 0.70 line / 0.54 branch) still pass. No tier is reported as verified
without being run.

## Error Handling

| Condition | Handling | User-visible result |
|---|---|---|
| `spec.md` absent | `read` → `Optional.empty`; WARN with resolved path + run id; fall back to `finalOutput` | Review proceeds; log names the exact path to check (AC3) |
| Path exists but is not a regular file (directory, FIFO, link-like entry) | Same as absent — never followed, never read; WARN with path | Existing `SpecReviewCoordinatorTest:161-171` behavior kept |
| `IOException` while opening/reading (permissions, deletion mid-read, transient I/O) | Caught inside the reader → `Optional.empty` + WARN naming path and `e.getMessage()`; **never** propagated to the listener | Approval still created from `finalOutput`; chain never stalls (AC7) |
| File shorter than the bound / empty | Empty or blank content counts as unavailable → fallback with WARN (a 0-byte `spec.md` must not produce a blank spec) | Reviewer sees the run output, not a blank page |
| File larger than `MAX_SPEC_CONTENT_LENGTH` (50 KB) | Reader accepts a `maxChars` bound and stops at it; content is char-bounded exactly as today, then `cleanSpecContent` truncates identically | Same 50 KB cap; DB-bloat guard unchanged |
| `runId` null on the event | Reader is not called; fallback + WARN naming the run id as `null` | Preserves `:294-296` |
| Unexpanded `%NAME%` token or blank root in the property | Refused at startup by `WorkspacePaths.configuredRoot` (`WorkspacePaths.java:62-87`) with the property name and the missing variable — now also on the read side, since one resolution feeds both | Backend fails fast with an actionable message instead of reviewing summaries forever |
| Result root is a symlink/junction alias | Reader resolves through the canonicalized root adopted at construction (`toRealPath()`), so it reads the directory the writer wrote | Parity by construction (AC6) |
| Reader bean absent (deployment without the execution wiring) | `ObjectProvider` → no port; **one** startup WARN; every review uses `finalOutput` | Boot never fails; degradation is announced at startup, not silently |
| Capture reported an incomplete bundle (`ArtifactBundle.complete()==false`, `RunWorkspaceService.java:447-452`) | The file may legitimately be missing; WARN wording must say the artifact is absent for this run, pointing at the run's result directory (out of scope: reading bundle state from `act-knowledge`) | Operator can tell "export failed" from "BA wrote nothing" |
| Read succeeds but the whole content is tool-call chatter | Unchanged: `stripToolCallChatter` + heading extraction; if the result is blank, it is stored blank exactly as today | No behavior change in this issue |
| Fallback while `finalOutput` is null/blank | `cleanSpecContent(null)` → null; approval is created with null content exactly as today, and the WARN still names the path. **Not** in scope to add a new "spec missing" state | Preserved; flagged as a known gap, not a regression |
| Chain cancelled / approval expired while reading | Unchanged: `onWorkflowCancelled` (`:211-229`) and `resubmitApproval` (`:232-271`) still govern lifecycle | No new state machine edges |

Transaction and idempotency rules that must not be broken: `onBaStepCompleted` stays `@Transactional`
with the filesystem read **outside** any DB write ordering assumption (read first, then
`upsertSpecKnowledge`, then save the approval, then pause the chain); the
"pending SPEC_REVIEW approval ⇒ already handled" guard (`:100-107`) still short-circuits before any
filesystem access, so a replayed event performs no I/O.

## Questions

- Q1: Confirm the direction — `RunArtifactReaderPort` in `act-common` served by `act-execution`
  (recommended; kills the duplication) vs. a shared `@ConfigurationProperties` root bean read by
  both modules (smaller diff, keeps two readers and the table rows 1-4 divergences) vs. only the
  startup assertion plus logging that the issue lists as its minimum bar?
- Q2: Is the breaking change to `SpecReviewCoordinator`'s public constructor acceptable (replace
  the `String runResultRoot` parameter with the port; only `SpecReviewCoordinatorTest.java:104-107`
  constructs it today), or must a deprecated overload accepting the raw root string be kept?
- Q3: Scope — result-root only, or also declare `aria.workspaces.*` resolution the exclusive
  property of `act-execution` (so `runtime-root` gets the same rule before a second consumer
  appears)?
- Q4: Should the degraded path be surfaced in the review UI via the approval `reason` prefix
  (AC4), or is the WARN log the only channel you want in this fix?
- Q5: Do you require a live SDD verification (a real BA run with `ARIA_WORKSPACES_RESULT_ROOT`
  relocated, asserting the approval shows the full file) as part of the DoD, or are the automated
  AC2/AC3 tests sufficient?
- Q6: Should `application.yml` keep the duplicated default string `./data/workspaces/results` in
  both `act-app/src/main/resources/application.yml:125` and the `@Value` annotations, or move the
  defaults entirely into the yml (single source of the default value too)?