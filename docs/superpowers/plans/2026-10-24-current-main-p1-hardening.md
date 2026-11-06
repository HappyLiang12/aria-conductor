# Current-main P1 hardening: verified cancel and run progress

Status: implemented locally; merged wave gate green. Live HOST/SANDBOX QA, hosted CI, MariaDB execution, and the full `act-app` context remain NOT VERIFIED.
Baseline: `9ab7ebd4` (`main`, PR #108)
Authorities: GitHub issues #111 and #103; `docs/reviews/2026-10-21-pr109-legacy-execution-retirement/architecture-review.md` (branch `docs/pr109-risk-review`); the superseding design `docs/superpowers/specs/2026-09-22-agent-core-execution-modes-design.md`.

This plan covers two P1 lanes only:

1. **Task 1 — #111:** external cancel records `CANCELLED` without requesting a verified core stop.
2. **Task 2 — #103:** run-owned cores emit no `RunProgressEvent`, so the Live Activity Stream is empty.

Task 3 (#112 result-root contract), Task 4 (#113 pack credential E2E/decision), and Task 5 (#1 REST auth design) are intentionally not dispatched in this wave.

## Global Constraints

- TDD is mandatory. Write the failing test first, run it, then implement the minimal change.
- Implementers never run `git add`, `git commit`, `git branch`, `git checkout`, `git reset`, `git stash`, or `git push`. They edit files and report the diff; the Lead integrates and commits.
- One writer per file. The file ownership table below is binding.
- Maven is a shared, exclusive lane. Before any `mvn` command, acquire the shared lock:
  ```bash
  LOCK=/c/Users/User/AppData/Local/Temp/aria-conductor-maven.lock
  while ! mkdir "$LOCK" 2>/dev/null; do sleep 2; done
  trap 'rmdir "$LOCK"' EXIT
  ```
  Remove the lock on every exit path. Never run two Maven processes concurrently.
- Do not read `.env` or credentials. Tests use synthetic values only.
- Do not delete, skip, or weaken an existing test to get green. If an existing expectation is legitimately invalidated, stop and report the conflict.
- No fallback, no broad catch-and-ignore, no `@Disabled`, no `failIfNoTests=false` masking a wrong selector.
- Assertions use exact values/counts, not `any()` or non-null-only checks.
- All commands run from the implementer's isolated worktree unless stated otherwise.
- The Lead owns the integration worktree `D:/project/aria-conductor-p1` and the final commit.

## File Ownership

| Lane | Owns (only these files) | Must not touch |
|---|---|---|
| Task 1 (#111) | `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/port/RunRuntimeControlPort.java`; `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoordinatedRunRuntimeControl.java`; `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/RunService.java`; `agent-control-tower/act-agent/src/test/java/io/aria/conductor/agent/service/RunServiceTest.java`; `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/CoordinatedRunRuntimeControlTest.java`; `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/listener/RunExecutionListenerTest.java` | `CoreExecutionService.java`; `OpenCodeCoreSession.java`; `OpenCodeHttpClient.java`; `CoreRuntimeConfiguration.java`; `application.yml`; any progress file |
| Task 2 (#103) | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClient.java`; new `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/progress/CoreProgressPump.java`; `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreSession.java`; `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreExecutionService.java`; `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClientTest.java`; new `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/progress/CoreProgressPumpTest.java`; `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/CoreExecutionServiceTest.java` | `RunRuntimeControlPort.java`; `CoordinatedRunRuntimeControl.java`; `RunService.java`; `RunServiceTest.java`; `CoordinatedRunRuntimeControlTest.java`; `RunExecutionListenerTest.java`; `CoreRuntimeConfiguration.java`; `application.yml` |
| Task 3 (#112, later) | `SpecReviewCoordinator.java`; `CoreRuntimeConfiguration.java`; `RunWorkspaceService.java`; `application.yml`; their tests | Task 1/2 files |
| Task 4 (#113, later) | Playwright spec + `e2e/agent-core/coverage-map.json` | production Java/TS |
| Task 5 (#1, design only) | design doc | product code |

### Task 1 fix round 1 ownership expansion

The independent review found C1/C2 (stale `findById` terminal race), I1 (duplicate
`RunCompletedEvent`), I2 (30 s verified stop inside the DB transaction), and I3
(`AriaConversationController.deleteConversation` direct CANCELLED write). Fix
round 1 additionally owns:

- `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/repository/RunRepository.java`
- new `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/RunCancellationWriter.java`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaConversationController.java`
- tests: `RunServiceTest`, a new `RunCancellationWriterTest`, a new H2 `RunRepositoryCancellationIntegrationTest`, the affected `AgentLoopEngine*Test`, `AriaConversationControllerTest`
- javadoc-only updates in `RunRuntimeControlPort` / `CoordinatedRunRuntimeControl`

Task 2 files (`CoreExecutionService`, `OpenCodeCoreSession`, `OpenCodeHttpClient`,
progress classes, `CoreRuntimeConfiguration`, `application.yml`) remain out of
scope for this fix.

### Task 2 fix round 1 ownership expansion

The independent review found I-1 (failsafe/contract tier not run; the existing
`CoreAdapterContractTest` exact event count is timing-dependent with the new pump),
I-2 (no dedicated `OpenCodeCoreSession` wiring test), and I-3 (future completes before
the pump stops). Fix round 1 additionally owns:

- `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/core/CoreAdapterContractTest.java`
- new `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreSessionTest.java`

Task 1 files and Task 2's existing seven files remain as previously assigned. The
contract-test edit must preserve its exact completion-contract assertion, not delete it.

## Preflight Conflict Scan

| Pair | Shared file/interface? | Finding | Ruling |
|---|---|---|---|
| Task 1 / Task 2 | `CoreExecutionService` | Task 1 calls the existing `CoreExecutionService.cancel` only through `CoordinatedRunRuntimeControl`; it does not edit `CoreExecutionService`. Task 2 owns `CoreExecutionService` for progress mapping. | No conflict; Task 2 owns the file. Task 1 must not edit it. |
| Task 1 / Task 2 | `RunRuntimeControlPort` | Only Task 1 edits the port; Task 2 does not use it. | No conflict. |
| Task 1 / Task 2 | `RunProgressEvent` / progress classes | Only Task 2 touches progress. | No conflict. |
| Task 1 / Task 2 | Maven `act-execution` | Both run tests in the same module. | Shared Maven lock; test runs are serialized. |
| Task 2 / Task 3 | `CoreRuntimeConfiguration`, `application.yml` | Task 2 must not register a bean in `CoreRuntimeConfiguration`; it wires the pump inside `OpenCodeCoreSession` and maps events in `CoreExecutionService`. Task 3 owns the config files. | No conflict if Task 2 honors this. |
| Task 1 / Task 3 | none | Disjoint. | No conflict. |
| Existing test expectations | `RunServiceTest` cancel cases | The current tests assert a recorded cancel for every status; the new tests only change the coordinator-owned RUNNING case. | Keep existing tests unchanged unless a new verified-cancel expectation legitimately supersedes one; report any such conflict. |

## Task 1: #111 — cancel requests a verified core stop

### Goal

For a run whose runtime is owned by this process, `RunService.cancelRun` must request and verify the core stop before persisting `CANCELLED`. A refused stop must leave the run in its prior state and surface the exact refusal reason. A run no runtime owns keeps the recorded transition.

### Current evidence

- `RunService.cancelRun` (`RunService.java:219-230`) only sets `CANCELLED`, sets `completedAt`, saves, and publishes `RunCompletedEvent`.
- `RunExecutionListener` (`RunExecutionListener.java:32-49`) calls `AgentLoopEngine.cancelRun`, which explicitly only mirrors bookkeeping and does not stop the core (`AgentLoopEngine.java:519-537`).
- `RunRuntimeControlPort` has `owns`, `pause`, `resume` only (`RunRuntimeControlPort.java:17-45`).
- `CoordinatedRunRuntimeControl` implements pause/resume only (`CoordinatedRunRuntimeControl.java:52-70`).
- `CoreExecutionService.cancel` already performs the verified stop and returns `ControlAck(STOPPED, true)` only after a matching `StopProof`; a refusal returns an unverified frozen state (`CoreExecutionService.java:863-934`). It has no production caller today.
- `RunService.pauseRun` / `resumeRun` show the verified-control pattern: resolve the port, if `owns`, call the control before persisting, re-read and re-validate (`RunService.java:159-216`).

### Interfaces and decisions

- Add `void cancel(UUID runId)` to `RunRuntimeControlPort`. Javadoc: throws `IllegalStateException` when the run-owned runtime did not confirm a verified stop; the caller persists nothing on that path.
- Implement `cancel` in `CoordinatedRunRuntimeControl`:
  - `CoreExecutionService control = requireCoordinator(runId, "cancel");`
  - `ControlAck ack = await(control.cancel(runId), runId, "cancel");`
  - Accept only `ack.state() == ControlState.STOPPED && ack.verified()`.
  - Otherwise throw `IllegalStateException("Run " + runId + " was not stopped: " + refusalReason(control, runId, "the run-owned core did not verify the stop"))`.
- In `RunService.cancelRun`:
  - Validate the transition first.
  - Resolve `RunRuntimeControlPort`; if non-null and `control.owns(run.getAgentId(), id)`, call `control.cancel(id)` **before** setting `CANCELLED`.
  - After a successful verified stop, re-read the run and re-validate the transition against the state that stands. This preserves the pause/resume race discipline: if the run finalized while the stop was in flight, do not overwrite it.
  - If the runtime refuses the stop, the exception propagates; do not save `CANCELLED`, do not set `completedAt`, do not publish `RunCompletedEvent`.
  - If no runtime owns the run (PENDING/INITIALIZING/parked/exited), keep the existing recorded transition exactly.
- Do not change `RunExecutionListener` behavior in this task. Its `agentLoopEngine.cancelRun` call remains bookkeeping; add a test documenting that it does not claim the stop. The verified stop now happens in `RunService`.
- Do not edit `CoreExecutionService`; the existing verified cancel is the implementation.

### TDD sequence

1. Add RED tests before production changes:
   - `RunServiceTest`:
     - `cancelRun_ofACoordinatorOwnedRun_verifiesTheRuntimeBeforePersistingCancelled` — `runtimeControl.owns(agentId, id)` true; `runtimeControl.cancel(id)` called; saved run is `CANCELLED` with `completedAt` and exactly one `RunCompletedEvent`.
     - `cancelRun_whenTheRuntimeRefuses_doesNotPersistCancelledAndSurfacesTheReason` — `runtimeControl.cancel(id)` throws `IllegalStateException("...not stopped: core reported RUNNING (verified=false)")`; assert the exception, assert no save that changes status, assert no completion event, assert run remains RUNNING.
     - `cancelRun_ofARunNoRuntimeOwns_keepsTheRecordedTransition` — `owns` false; no `cancel` call; existing recorded transition still persists and publishes.
     - `cancelRun_whenTheRunFinalizesDuringTheVerifiedStop_doesNotOverwriteTerminalState` — arrange the repository to return `COMPLETED` after the control returns; assert the second `validateTransition` refuses and no `CANCELLED` write happens.
   - `CoordinatedRunRuntimeControlTest`:
     - `cancel_passesOnAVerifiedStoppedAck` — `control.cancel(runId)` returns `ControlAck(STOPPED, true)`; no exception.
     - `cancel_refusalSurfacesTheExactPendingControlReason` — `ControlAck(RUNNING, false)` + pending control reason; assert message contains `was not stopped` and the exact reason.
     - `cancel_withoutCoordinator_refuses` — no coordinator available; assert explicit refusal.
   - `RunExecutionListenerTest`:
     - `onRunCompleted_cancelled_stillCallsTheEngineForBookkeeping` — verifies `agentLoopEngine.cancelRun(runId)` and no other interaction; document that the stop is not this listener's responsibility.
2. Run the RED commands under the Maven lock:
   ```bash
   cd agent-control-tower
   mvn test -pl act-agent -Dtest=RunServiceTest -Djacoco.skip=true
   mvn test -pl act-execution -Dtest=CoordinatedRunRuntimeControlTest,RunExecutionListenerTest -Djacoco.skip=true
   ```
   Expected RED: missing `RunRuntimeControlPort.cancel`/behavior, not a compile error in an unrelated test.
3. Implement the three production changes.
4. Run GREEN:
   ```bash
   mvn test -pl act-agent -Dtest=RunServiceTest -Djacoco.skip=true
   mvn test -pl act-execution -Dtest=CoordinatedRunRuntimeControlTest,RunExecutionListenerTest,CoreExecutionServiceTest -Djacoco.skip=true
   mvn test -pl act-agent,act-execution -Dspring.profiles.active=h2
   ```
5. Report exact counts and any skipped tests.

### Acceptance

- A coordinator-owned RUNNING run cannot reach `CANCELLED` without a `ControlAck(STOPPED, true)`.
- A refused stop leaves the run unchanged and surfaces the exact reason.
- A non-owned run keeps the recorded transition.
- A terminal race is not overwritten.
- Existing cancel/pause/resume tests stay green unchanged unless a documented conflict is reported first.
- No edits outside the owned files.

### Handoff

Report: status, files changed, RED command + failure reason, GREEN command + counts, any skipped test, concerns. Do not commit.

## Task 2: #103 — restore run progress for run-owned OpenCode

### Goal

Restore a `RunProgressEvent` producer for run-owned OpenCode sessions in both HOST and SANDBOX modes, so `GET /api/v1/runs/{id}/progress` and the live `run.progress` WS stream carry thinking/tool fragments again. Qoder progress is out of scope for this task.

### Current evidence

- `RunProgressEvent` and its persistence/broadcast consumer still exist (`RunProgressEvent.java`, `EventBroadcastListener.onRunProgress`).
- `RunProgressControllerTest`, `EventBroadcastListenerTest`, `AgentDrawer` and `observability-live.spec.ts` still expect progress.
- No production `new RunProgressEvent` publisher remains. `git log -S"OpenCodeProgressPump"` shows it was deleted in `60d542f1` (#109).
- The deleted pump (`5d16e349:agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeProgressPump.java`) is the behavioral reference: poll `GET /session/{id}/message`, diff parts, coalesce consecutive THINKING, monotonic seq, degrade on errors, stop idempotent.
- The current `OpenCodeHttpClient` has `openSession`, `abortSession`, `sendPrompt`, `parseMessageResult`, `isHealthy`, but no `listMessages`/`MessageSnapshot`/`PartSnapshot`.
- `OpenCodeCoreSession.prompt` currently forwards only the final `opencode.message` CoreEvent (`OpenCodeCoreSession.java:123-137`).
- `CoreExecutionService` has `eventPublisher` and `handleEvent`; `handleEvent` forwards every CoreEvent and handles `permission.request` (`CoreExecutionService.java:1058-1063`).

### Design

Keep the layers clean and avoid `CoreRuntimeConfiguration`:

1. Port the minimal `listMessages` API into the current `OpenCodeHttpClient`:
   - `public record MessageSnapshot(String id, List<PartSnapshot> parts)`
   - `public record PartSnapshot(String id, String type, String text, String toolName, String state)`
   - `public static final Duration LIST_MESSAGES_TIMEOUT = Duration.ofSeconds(10);`
   - `public List<MessageSnapshot> listMessages(String sessionId)` using `sendNoRetry("GET", "/session/" + sessionId + "/message", null, LIST_MESSAGES_TIMEOUT)`; return an empty list on any non-2xx, I/O failure, or malformed body. Never throw into the task path.
   - Port the parsing shape from the deleted client at `5d16e349` (message `info.id`, `parts[]` with `id`, `type`, `text`, `tool`/`name`, `state.status`).
2. Add `runtime/progress/CoreProgressPump`:
   - Constructor: `OpenCodeHttpClient client`, `String sessionId`, `UUID runId`, `UUID agentId`, `Consumer<ProgressFragment> sink`, poll interval, coalesce window.
   - `ProgressFragment` record: `RunProgressEvent.Kind kind`, `String content`, `String toolName`, `long seq`, `int iteration` (0 in this task).
   - Behavior copied from the deleted pump: diff new parts, coalesce consecutive THINKING within a batch (last wins), never coalesce tool events, monotonic seq (`AtomicLong`), degrade with backoff on poll errors, swallow sink exceptions, `start`/`stop` idempotent.
   - Do not use a Spring bean; the session creates/stops it per prompt.
3. `OpenCodeCoreSession.prompt`:
   - Before `sendPrompt`, create and start a pump using the session's `client`, `sessionId`, `spec.runId()`, `spec.agentId()`, and a sink that calls `events.accept(new CoreEvent("opencode.progress", runId, sessionId, null, payloadJson))`.
   - The payload JSON carries `kind`, `content`, `toolName`, `seq`, `iteration`.
   - Stop the pump in every completion path (success, failure, cancellation) and in `close()`. Do not emit after stop.
   - Keep the existing final `opencode.message` event unchanged.
4. `CoreExecutionService.handleEvent`:
   - After the existing `runtimes.forward(...)`, if `event.type().equals("opencode.progress")`, parse the payload, validate `kind` against `RunProgressEvent.Kind`, and publish `new RunProgressEvent(this, runtime.spec().runId(), runtime.spec().agentId(), iteration, kind, content, toolName, seq)`.
   - Invalid/unknown payloads are logged and dropped; they never break the run.
   - Do not change permission/approval handling.
5. Do not touch `CoreRuntimeConfiguration`, `application.yml`, `RunProgressEvent`, `EventBroadcastListener`, `RunController`, or the UI.

### TDD sequence

1. Port/extend tests first:
   - `OpenCodeHttpClientTest`: `listMessages` parses a valid body; non-2xx returns empty; malformed body returns empty; missing fields stay null. Add a test for the exact `GET /session/{id}/message` path with WireMock.
   - `CoreProgressPumpTest` (new): port the deleted pump tests — diff only new parts, coalesce thinking, degraded empty snapshot does not reset/replay, tool events not coalesced, server errors emit nothing and do not throw, sink exceptions do not kill the pump, stop idempotent and halts emission. Use exact event counts/kinds/seq.
   - `CoreExecutionServiceTest`: a fake session emits an `opencode.progress` CoreEvent; assert exactly one `RunProgressEvent` published with the exact runId/agentId/kind/content/toolName/seq; an unknown kind is dropped without a publish; a progress event does not interfere with permission handling.
2. Run RED under the Maven lock:
   ```bash
   cd agent-control-tower
   mvn test -pl act-execution -Dtest=OpenCodeHttpClientTest,CoreProgressPumpTest,CoreExecutionServiceTest -Djacoco.skip=true
   ```
   Expected RED: missing `listMessages`/pump/progress mapping, not unrelated failures.
3. Implement.
4. Run GREEN:
   ```bash
   mvn test -pl act-execution -Dtest=OpenCodeHttpClientTest,CoreProgressPumpTest,CoreExecutionServiceTest -Djacoco.skip=true
   mvn test -pl act-execution -Dspring.profiles.active=h2
   ```
5. Live verification is a separate QA gate (not this implementer's Maven lane): start the local stack, run a run-owned OpenCode run, assert `GET /runs/{id}/progress` returns rows and the drawer shows non-idle activity.

### Acceptance

- `listMessages` degrades to an empty list and never throws into the prompt path.
- The pump emits only new parts, coalesces thinking, keeps tool fragments, monotonic seq, and stops cleanly.
- `CoreExecutionService` publishes `RunProgressEvent` for `opencode.progress` CoreEvents only.
- Existing permission/approval tests and the full act-execution suite remain green.
- No edits outside the owned files.

### Handoff

Report: status, files changed, RED command + failure reason, GREEN command + counts, any skipped test, concerns. Do not commit.

## Task 3: #112 — SDD result-root contract (later wave)

Not dispatched in this wave. Files: `SpecReviewCoordinator.java`, `CoreRuntimeConfiguration.java`, `RunWorkspaceService.java`, `application.yml`, tests. Acceptance: one configuration source, non-default root test, warning with resolved path on fallback. Task 2 must not touch these files.

## Task 4: #113 — retained pack credential E2E or retire (later wave)

Not dispatched in this wave. Decision first: add one Playwright credential-path spec or retire pack management and update `e2e/agent-core/coverage-map.json`. No production code until the decision is recorded.

## Task 5: #1 — REST auth design (separate lane)

Not dispatched in this wave. Design/decision only; `SECURITY.md:15-17` still documents unauthenticated `/api/v1/**`. Do not mix with the P1 bug fixes.
