# Waiting-Input Clarification Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a Qoder-core run's turn ends with a clarification question, the run enters an explicit `WAITING_INPUT` state; the operator's answer is re-prompted into the SAME ACP session and the run continues; finalize/deny ends it honestly.

**Architecture:** The turn loop lives inside `CoreExecutionService.execute`, between the prompt completing and `finalizeRuntime` (the runtime is destroyed there — a park after it returns would find no session). A `RunInputCoordinator` holds the parked future and publishes `RunWaitingForInputEvent` / `RunInputReceivedEvent`; `AgentLoopEngine` listeners own the business writes (status, ask, trajectory, usage). The `[NEED-INPUT]` seed rule is Qoder-only; the loop is gated to `spec.coreId()` = `qoder`.

**Tech Stack:** Java 21 / Spring Boot 3.3 (act-common events/ports/models, act-execution engine+runtime+approval, act-agent RunController, act-dashboard-api WS), React 19 + TanStack Query (act-dashboard).

**Spec:** `docs/superpowers/specs/2026-10-05-waiting-input-clarification-loop-design.md` (mechanics section corrected 2026-10-05 during planning — read it first)

## Global Constraints

- **Infinite wait (D3):** no timeout on the parked future. A follow-up turn gets a fresh per-turn deadline window; the frozen binding deadline applies to the first turn only.
- **Same session only (D1):** the follow-up prompt goes to the same `CoreSession` instance inside the open runtime. No re-open, no re-launch, no new run.
- **Precedence of identity on the loop gate:** the loop is gated to `"qoder".equals(spec.coreId())`. OpenCode behavior is byte-identical to today (pinned by test in Task 5).
- **CLARIFICATION asks (D5):** `AskType.QUESTION` + `ApprovalSource.CLARIFICATION`; never swept by `approvePendingByKanbanItemId` (LEGACY_GATE-only filter, already true — pinned by a negative test); never decidable via `/decide` (ApprovalGate refuses); settled APPROVED by `/answer` with the answer text, or DENIED by finalize/cancel sweeps.
- **No `RunCompletedEvent` while waiting** — the admission permit stays held (`RunAdmissionQueue.settle` reacts only to `RunCompletedEvent`).
- **Restart kills the child:** `recoverOrphanedRuns` marks WAITING_INPUT runs FAILED ("Run orphaned by backend restart"); `ZombieRunReaper` never touches WAITING_INPUT (it only queries RUNNING — pinned by test).
- Engine writes statuses directly via its own helpers; `RunService.VALID_TRANSITIONS` still declares the new transitions for any REST-driven path.
- No placeholders in commits: every task ends green on its named tests.
- Validation cadence: per-task impact tests below; wave integration after Tasks 5-6 and after Task 7; full regression at phase end (Task 9); pre-merge live operator drill (real Qoder clarification).

---

### Task 1: `RunStatus.WAITING_INPUT` and the state plumbing

**Files:**
- Modify: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/RunStatus.java`
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/RunService.java` (`VALID_TRANSITIONS`, lines 29-42)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java` (`recoverOrphanedRuns`, lines 586-605 — add WAITING_INPUT to the `findByStatusIn` list)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanService.java` (`runOutcome` switch lines 186-197: add `WAITING_INPUT` to the `ACTIVE` arm; `guardLinkedRunNotActive` lines 312-342: add `status == RunStatus.WAITING_INPUT` to the active set)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/housekeeping/HousekeepingService.java` (`ACTIVE_RUNS` set, line 1829-1830: add `RunStatus.WAITING_INPUT`)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/engine/ZombieRunReaperTest.java` (extend; create if absent)

**Interfaces:**
- Produces: `RunStatus.WAITING_INPUT`; transitions `RUNNING -> WAITING_INPUT`, `WAITING_INPUT -> {RUNNING, COMPLETED, FAILED, CANCELLED}` in `RunService.VALID_TRANSITIONS`. Later tasks rely on these exact names.

- [ ] **Step 1: Write the failing tests**

In `ZombieRunReaperTest` (follow the existing style; if the class does not exist, create it with `@ExtendWith(MockitoExtension.class)`, mocks for `RunRepository`, `AgentLoopEngine`, `ApplicationEventPublisher`, and construct the reaper via reflection on `timeoutMinutes` or a setter — mirror whatever the file already does):

```java
    @Test
    void waitingInputRunsAreNeverReaped() {
        Run waiting = new Run();
        waiting.setId(UUID.randomUUID());
        waiting.setAgentId(UUID.randomUUID());
        waiting.setStatus(RunStatus.WAITING_INPUT);
        waiting.setUpdatedAt(Instant.now().minus(Duration.ofHours(48)));

        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());

        reaper.reapZombieRuns();

        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        // The reaper queries RUNNING only; a WAITING_INPUT row is invisible to it by construction.
        verify(runRepository, never()).findByStatus(RunStatus.WAITING_INPUT);
    }
```

In the `KanbanServiceTest` (existing, `@ExtendWith(MockitoExtension.class)`):

```java
    @Test
    void runOutcomeMapsWaitingInputToActive() {
        assertThat(KanbanService.runOutcome(RunStatus.WAITING_INPUT)).isEqualTo("ACTIVE");
    }
```

In the `RunService` transition test file (find it: `grep -rl "VALID_TRANSITIONS\|InvalidStateTransitionException" agent-control-tower/act-agent/src/test --include=*.java`; if none, create `RunServiceTransitionTest` as a plain JUnit test constructing `RunService` with mocked repositories — mirror the constructor used by other act-agent tests):

```java
    @Test
    void waitingInputTransitions() {
        assertThatThrownBy(() -> service.updateRunStatusForTest(RunStatus.WAITING_INPUT)) // placeholder replaced below
    }
```

Concretely — because `validateTransition` is private, assert through the public behavior used by existing tests of that file; if the existing tests call `service.updateRunStatus(id, status)`, add:

```java
    @Test
    void runningCanEnterWaitingInputAndLeaveIt() {
        UUID id = UUID.randomUUID();
        Run run = runWithStatus(id, RunStatus.RUNNING);   // existing helper/fixture style of that test class
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateRunStatus(id, RunStatus.WAITING_INPUT);

        run.setStatus(RunStatus.WAITING_INPUT);
        service.updateRunStatus(id, RunStatus.RUNNING);   // WAITING_INPUT -> RUNNING must be legal

        run.setStatus(RunStatus.WAITING_INPUT);
        service.updateRunStatus(id, RunStatus.COMPLETED); // WAITING_INPUT -> COMPLETED must be legal
    }
```

If `updateRunStatus` publishes events that need stubbing, stub them exactly the way neighboring tests in that file do.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower && mvn -q test -pl act-common,act-agent,act-execution -Dtest="ZombieRunReaperTest,KanbanServiceTest,RunService*Test"`
Expected: COMPILATION ERROR on `RunStatus.WAITING_INPUT`.

- [ ] **Step 3: Implement**

`RunStatus.java`:

```java
public enum RunStatus {
    PENDING, INITIALIZING, RUNNING, PAUSED,
    /** The turn ended with a clarification question; the run parks until the operator answers or finalizes. */
    WAITING_INPUT,
    COMPLETED, FAILED, CANCELLED,
    /** Task-level run aborted by the engine (timeout / budget / approval denial). */
    ABORTED
}
```

`RunService.VALID_TRANSITIONS` — insert after the RUNNING line:

```java
        transitions.put(RunStatus.RUNNING, EnumSet.of(RunStatus.PAUSED, RunStatus.WAITING_INPUT, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED));
        transitions.put(RunStatus.WAITING_INPUT, EnumSet.of(RunStatus.RUNNING, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED));
```

(keep the old RUNNING line replaced by the one above; leave everything else untouched).

`AgentLoopEngine.recoverOrphanedRuns` — change the query list to
`List.of(RunStatus.RUNNING, RunStatus.INITIALIZING, RunStatus.WAITING_INPUT)`.

`KanbanService.runOutcome` — the `ACTIVE` arm becomes
`case RUNNING, PENDING, INITIALIZING, PAUSED, WAITING_INPUT -> "ACTIVE";`
`guardLinkedRunNotActive` — add `|| status == RunStatus.WAITING_INPUT` to the active-status condition.

`HousekeepingService.ACTIVE_RUNS` — add `RunStatus.WAITING_INPUT` to the set.

- [ ] **Step 4: Run the tests**

Run: `cd agent-control-tower && mvn -q test -pl act-common,act-agent,act-execution -Dtest="ZombieRunReaperTest,KanbanServiceTest,RunService*Test,AgentLoopEngineTest,HousekeepingServiceTest"`
Expected: PASS. If any switch over `RunStatus` elsewhere fails to compile (exhaustive switches), the compiler names the file — add WAITING_INPUT to the same arm as PAUSED there (expected candidates: none outside the files above; verify with `grep -rn "case PAUSED" agent-control-tower --include=*.java | grep -v target`).

- [ ] **Step 5: Commit**

```bash
git add -A agent-control-tower
git commit -m "feat(runtime): WAITING_INPUT run state with transitions, recovery and board guards"
```

---

### Task 2: `ApprovalSource.CLARIFICATION` and the decision guards

**Files:**
- Modify: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/ApprovalSource.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/ApprovalGate.java` (`decideApproval`, approx lines 252-289 — add a refusal for CLARIFICATION rows)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/ApprovalGateTest.java` (extend) and the kanban bulk-approve negative test (in `KanbanServiceTest` or the `KanbanTransitionService` test — find with `grep -rl "approvePendingByKanbanItemId" agent-control-tower/*/src/test --include=*.java`)

**Interfaces:**
- Produces: `ApprovalSource.CLARIFICATION`; `ApprovalGate.decideApproval` throws `IllegalStateException("CLARIFICATION asks are answered via /approvals/{id}/answer or finalized via /runs/{id}/finalize")` when the row's source is CLARIFICATION (surfaces as HTTP 409 on `/decide`).

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void decideRefusesClarificationAsks() {
        Approval ask = Approval.builder()
                .runId(UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION)
                .source(ApprovalSource.CLARIFICATION)
                .build();
        when(approvalRepository.findById(ask.getId())).thenReturn(Optional.of(ask));

        assertThatThrownBy(() -> approvalGate.decideApproval(ask.getId(), true, "why"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/answer");
    }
```

(Adapt to the existing `ApprovalGateTest` rigging — reuse its mocked `ApprovalRepository` and constructor; mirror an existing decide test and change the row.)

Bulk-approve negative test (where `approvePendingByKanbanItemId` is exercised against a DB — the act-app integration tests or a `@DataJpaTest`; follow the file the grep found):

```java
    @Test
    void cardBulkApproveNeverTouchesClarificationAsks() {
        // persisted card with one LEGACY_GATE ask and one CLARIFICATION ask, both PENDING
        int swept = repository.approvePendingByKanbanItemId(item.getId(), "accepted by card decision", Instant.now());
        assertThat(swept).isEqualTo(1);
        assertThat(repository.findById(clarificationAsk.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
    }
```

If no DB-backed test for the bulk query exists yet, create the smallest `@DataJpaTest` in act-app following `DataJpaTestBase` (act-test-support), persisting the two rows via `TestPersistableApproval`-style builders the existing tests use.

- [ ] **Step 2: Run to verify failure**

Run: `cd agent-control-tower && mvn -q test -pl act-common,act-execution -Dtest="ApprovalGateTest"`
Expected: COMPILATION ERROR on `ApprovalSource.CLARIFICATION`.

- [ ] **Step 3: Implement**

`ApprovalSource.java`:

```java
public enum ApprovalSource {
    LEGACY_GATE,
    ACP_PERMISSION,
    /** Platform-detected clarification question holding a run in WAITING_INPUT (2026-10-05). */
    CLARIFICATION
}
```

`ApprovalGate.decideApproval` — first guard, before any state read beyond the row load (place it right after the row is fetched, matching the method's existing validation order):

```java
        if (approval.getSource() == ApprovalSource.CLARIFICATION) {
            throw new IllegalStateException(
                    "CLARIFICATION asks are answered via /approvals/{id}/answer or finalized via /runs/{id}/finalize");
        }
```

- [ ] **Step 4: Run the tests**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest="ApprovalGateTest,PermissionCoordinatorTest" && mvn -q test -pl act-app -Dtest="*Kanban*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A agent-control-tower
git commit -m "feat(approval): CLARIFICATION ask provenance, undecidable via /decide, never bulk-swept"
```

---

### Task 3: Clarification detector

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/ClarificationQuestions.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/ClarificationQuestionsTest.java`

**Interfaces:**
- Produces: `public static String awaitingInput(String finalOutput)` — returns the question text when the turn ends with a clarification, else `null`.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationQuestionsTest {

    @Test
    void markerOnTheLastLineIsTheQuestion() {
        String output = "Working on it.\nDone so far:\n- step 1\n[NEED-INPUT] Which database should I use?";
        assertThat(ClarificationQuestions.awaitingInput(output))
                .isEqualTo("Which database should I use?");
    }

    @Test
    void bareMarkerFallsBackToTheWholeOutput() {
        assertThat(ClarificationQuestions.awaitingInput("before\n[NEED-INPUT]"))
                .isEqualTo("before");
    }

    @Test
    void trailingQuestionMarkOnTheLastLineIsTheQuestion() {
        assertThat(ClarificationQuestions.awaitingInput("Progress notes\nShould I pin the model to efficient?"))
                .isEqualTo("Should I pin the model to efficient?");
    }

    @Test
    void fullwidthQuestionMarkIsDetected() {
        assertThat(ClarificationQuestions.awaitingInput("準備好了\n要用哪個 model？"))
                .isEqualTo("要用哪個 model？");
    }

    @Test
    void plainCompletionIsNotAQuestion() {
        assertThat(ClarificationQuestions.awaitingInput("All done. Tests pass. See report.md")).isNull();
        assertThat(ClarificationQuestions.awaitingInput("Finished. The historical name was \"?\"")).isNull();
    }

    @Test
    void questionMarkInTheMiddleDoesNotCount() {
        assertThat(ClarificationQuestions.awaitingInput("Asked myself why? Then solved it.")).isNull();
    }

    @Test
    void blankAndNullAreNeverQuestions() {
        assertThat(ClarificationQuestions.awaitingInput(null)).isNull();
        assertThat(ClarificationQuestions.awaitingInput("   \n  ")).isNull();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=ClarificationQuestionsTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

```java
package io.aria.conductor.execution.runtime;

/**
 * Detects a turn that ended with a clarification question (2026-10-05 spec §3).
 * Primary signal: the last non-empty line starts with the [NEED-INPUT] marker
 * the prompt seed instructs the core to use. Fallback: the last non-empty line
 * ends with a question mark (ASCII or fullwidth). A miss degrades to today's
 * behavior — the run completes and the review card carries the outcome; a
 * false positive only parks the run with the question visible, and the
 * operator finalizes manually.
 */
public final class ClarificationQuestions {

    public static final String MARKER = "[NEED-INPUT]";

    private ClarificationQuestions() {
    }

    public static String awaitingInput(String finalOutput) {
        if (finalOutput == null || finalOutput.isBlank()) {
            return null;
        }
        String trimmed = finalOutput.strip();
        int newline = trimmed.lastIndexOf('\n');
        String last = (newline >= 0 ? trimmed.substring(newline + 1) : trimmed).trim();
        if (last.startsWith(MARKER)) {
            String question = last.substring(MARKER.length()).trim();
            return question.isEmpty() ? trimmed : question;
        }
        return (last.endsWith("?") || last.endsWith("？")) ? last : null;
    }
}
```

- [ ] **Step 4: Run to verify pass, then commit**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=ClarificationQuestionsTest`
Expected: PASS.

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/ClarificationQuestions.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/ClarificationQuestionsTest.java
git commit -m "feat(runtime): clarification-question detector ([NEED-INPUT] marker, question-mark fallback)"
```

---

### Task 4: Input events and `RunInputCoordinator`

**Files:**
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/RunWaitingForInputEvent.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/RunInputReceivedEvent.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/TurnCompletedEvent.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunInputCoordinator.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunInputCoordinatorTest.java`

**Interfaces:**
- Produces (exact shapes Task 5-7 consume):
  - `RunWaitingForInputEvent(Object source, UUID runId, String question)` with `getRunId()`, `getQuestion()`.
  - `RunInputReceivedEvent(Object source, UUID runId)` with `getRunId()`.
  - `TurnCompletedEvent(Object source, UUID runId, String finalOutput, Long inputTokens, Long outputTokens, String observedModel)` with getters.
  - `RunInputCoordinator` (`@Service`): `CompletableFuture<OperatorInput> requestInput(UUID runId, String question)`; `void submitAnswer(UUID runId, String answer)` (throws `IllegalStateException` when not waiting); `boolean requestFinalize(UUID runId)` (false when not waiting); `record OperatorInput(String answer, boolean finalizeRequested)`.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.event.RunWaitingForInputEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RunInputCoordinatorTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final RunInputCoordinator coordinator = new RunInputCoordinator(publisher);

    @Test
    void requestInputPublishesTheWaitingEventAndParksUntilAnAnswerArrives() throws Exception {
        UUID runId = UUID.randomUUID();
        CompletableFuture<RunInputCoordinator.OperatorInput> future = coordinator.requestInput(runId, "Which DB?");

        verify(publisher).publishEvent(any(RunWaitingForInputEvent.class));
        assertThat(future).isNotDone();

        coordinator.submitAnswer(runId, "postgres");

        RunInputCoordinator.OperatorInput input = future.get(1, TimeUnit.SECONDS);
        assertThat(input.answer()).isEqualTo("postgres");
        assertThat(input.finalizeRequested()).isFalse();
    }

    @Test
    void finalizeCompletesTheParkedFutureWithTheSignal() throws Exception {
        UUID runId = UUID.randomUUID();
        CompletableFuture<RunInputCoordinator.OperatorInput> future = coordinator.requestInput(runId, "q?");

        assertThat(coordinator.requestFinalize(runId)).isTrue();

        assertThat(future.get(1, TimeUnit.SECONDS).finalizeRequested()).isTrue();
    }

    @Test
    void submitAnswerOnARunThatIsNotWaitingIsRefused() {
        assertThatThrownBy(() -> coordinator.submitAnswer(UUID.randomUUID(), "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(coordinator.requestFinalize(UUID.randomUUID())).isFalse();
    }

    @Test
    void aSecondRequestForTheSameRunReplacesTheParkedFuture() throws Exception {
        UUID runId = UUID.randomUUID();
        CompletableFuture<RunInputCoordinator.OperatorInput> first = coordinator.requestInput(runId, "q1?");
        CompletableFuture<RunInputCoordinator.OperatorInput> second = coordinator.requestInput(runId, "q2?");

        coordinator.submitAnswer(runId, "the answer");

        assertThat(second.get(1, TimeUnit.SECONDS).answer()).isEqualTo("the answer");
        assertThat(first).isNotDone();
    }
}
```

- [ ] **Step 2: Run to verify failure, then implement**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=RunInputCoordinatorTest` — COMPILATION ERROR.

The three event classes follow the `RunCompletedEvent` pattern exactly (`act-common/.../event/RunCompletedEvent.java`: `@Getter`, extends `ApplicationEvent`, two-constructor shape). `RunInputCoordinator`:

```java
package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.event.RunWaitingForInputEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parks one waiting run per process (2026-10-05 spec §2/§5): the coordinated
 * run thread parks on the future, the operator's answer or finalize signal
 * completes it. No timeout by design (operator decision D3 — infinite wait);
 * the run's admission slot stays held for the same reason. A re-request for a
 * run that is somehow already parked replaces the parked future — the loop
 * never stacks two waiters for one run, and the superseded future simply
 * never completes.
 */
@Service
public class RunInputCoordinator {

    /** What woke the parked run: the operator's answer, or a finalize signal. */
    public record OperatorInput(String answer, boolean finalizeRequested) {
    }

    private record PendingInput(CompletableFuture<OperatorInput> future) {
    }

    private final ApplicationEventPublisher eventPublisher;
    private final Map<UUID, PendingInput> pending = new ConcurrentHashMap<>();

    public RunInputCoordinator(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Publishes {@link RunWaitingForInputEvent} and returns the future the run parks on. */
    public CompletableFuture<OperatorInput> requestInput(UUID runId, String question) {
        CompletableFuture<OperatorInput> future = new CompletableFuture<>();
        pending.put(runId, new PendingInput(future));
        eventPublisher.publishEvent(new RunWaitingForInputEvent(this, runId, question));
        return future;
    }

    /** Wakes the parked run with the operator's answer; refused when not waiting. */
    public void submitAnswer(UUID runId, String answer) {
        PendingInput waiting = pending.get(runId);
        if (waiting == null) {
            throw new IllegalStateException("Run " + runId + " is not waiting for operator input");
        }
        waiting.future().complete(new OperatorInput(answer, false));
    }

    /** Wakes the parked run with the finalize signal; false when the run is not waiting. */
    public boolean requestFinalize(UUID runId) {
        PendingInput waiting = pending.get(runId);
        if (waiting == null) {
            return false;
        }
        waiting.future().complete(new OperatorInput(null, true));
        return true;
    }
}
```

- [ ] **Step 3: Run to verify pass, then commit**

Run: `cd agent-control-tower && mvn -q test -pl act-common,act-execution -Dtest="RunInputCoordinatorTest"`
Expected: PASS.

```bash
git add -A agent-control-tower
git commit -m "feat(runtime): run input coordinator and waiting-input events"
```

---

### Task 5: The waiting loop inside `CoreExecutionService.execute`

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreExecutionService.java` (fields ~94-116: add `RunInputCoordinator inputs`, `ApplicationEventPublisher eventPublisher`, `TaskDeadlineProperties taskDeadlines`; `execute` lines 165-218: the loop)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/QoderCoreSession.java` (`prompt`, line ~1182: append the seed rule)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/CoreExecutionServiceTest.java` (extend — the `RecordingSession` double is the fixture)

**Interfaces:**
- Consumes: `ClarificationQuestions.awaitingInput` (Task 3), `RunInputCoordinator` (Task 4), events (Task 4).
- Produces: `execute` may park and re-prompt; the returned `CoreResult` is the LAST turn's result. The engine-visible contract of `execute` is otherwise unchanged.

- [ ] **Step 1: Write the failing integration tests (recording-session doubles)**

First READ the current `execute` and the test rig (`CoreExecutionServiceTest.java` — constructor, `spec()`, `task()`, `RecordingSession`, `RecordingBackend`, `ManualDeadlineScheduler`). Then add:

```java
    @Test
    void questionEndingTurnParksForTheAnswerAndRepromptsTheSameSessionWithoutFinalizing() {
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Partial progress\n[NEED-INPUT] Which database?", usage(), false),
                new CoreResult("session-1", "All done", usage(), false)));

        // Answer arrives from another thread, as the REST route would.
        Thread answerer = new Thread(() -> {
            org.awaitility.Awaitility.await().atMost(5, java.time.Duration.SECONDS)
                    .until(() -> published(RunWaitingForInputEvent.class));
            coordinator.submitAnswer(RUN, "postgres");
        });
        answerer.start();
        CoreResult result = service.execute(spec(), adapter, task());
        answerer.join();

        assertThat(result.finalOutput()).isEqualTo("All done");
        // One launch, one session, TWO prompts — same session, no re-open, no finalize in between.
        assertThat(recordedSteps).containsSubsequence("launch", "open-session", "prompt", "prompt", "stop-writers");
        assertThat(recordedSteps.indexOf("prompt", 2)).isGreaterThan(recordedSteps.indexOf("open-session"));
        assertThat(backend.launchCalls).isEqualTo(1);
        assertThat(recordedSteps).containsExactlyInAnyOrder("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "prompt", "stop-writers", "capture", "destroy", "release");
        // The follow-up task is the bare answer: session context, no re-sent system/history.
        assertThat(session.secondPrompt.userPrompt()).isEqualTo("postgres");
        assertThat(session.secondPrompt.systemPrompt()).isBlank();
        assertThat(session.secondPrompt.history()).isEmpty();
        // The engine was told about the waiting state and the intermediate turn.
        assertThat(published(RunWaitingForInputEvent.class)).isNotEmpty();
        assertThat(published(TurnCompletedEvent.class)).isNotEmpty();
    }

    @Test
    void finalizeSignalEndsTheRunWithTheQuestionTurnAsTheResult() throws Exception {
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Hmm\n[NEED-INPUT] Continue?", usage(), false)));
        Thread finalizer = new Thread(() -> {
            org.awaitility.Awaitility.await().atMost(5, java.time.Duration.SECONDS)
                    .until(() -> published(RunWaitingForInputEvent.class));
            coordinator.requestFinalize(RUN);
        });
        finalizer.start();
        CoreResult result = service.execute(spec(), adapter, task());
        finalizer.join();

        assertThat(result.finalOutput()).contains("[NEED-INPUT] Continue?");
        assertThat(result.cancelled()).isFalse();
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
    }

    @Test
    void nonQoderCoresNeverParkEvenOnQuestionShapedOutput() {
        // spec() variant with coreId "opencode" and a question-ending result
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Should I proceed?", usage(), false)));

        CoreResult result = service.execute(opencodeSpec(), adapter, task());

        assertThat(result.finalOutput()).isEqualTo("Should I proceed?");
        assertThat(recordedSteps).containsSubsequence("prompt", "stop-writers");
        assertThat(published(RunWaitingForInputEvent.class)).isEmpty();
    }

    @Test
    void aFollowUpTurnGetsAFreshPerTurnDeadlineWindow() {
        // With the ManualDeadlineScheduler, assert schedule() is called twice and
        // the second call carries a LATER instant than the first (now + window).
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "[NEED-INPUT] q?", usage(), false),
                new CoreResult("session-1", "done", usage(), false)));
        Thread answerer = new Thread(() -> {
            org.awaitility.Awaitility.await().atMost(5, java.time.Duration.SECONDS)
                    .until(() -> published(RunWaitingForInputEvent.class));
            coordinator.submitAnswer(RUN, "yes");
        });
        answerer.start();
        service.execute(spec(), adapter, task());
        answerer.join();

        assertThat(deadlineScheduler.scheduledInstants()).hasSize(2);
        assertThat(deadlineScheduler.scheduledInstants().get(1))
                .isAfter(deadlineScheduler.scheduledInstants().get(0));
    }
```

Adapt to the rig: `session.scriptedResults`/`secondPrompt` are additions to `RecordingSession` (a `Deque<CoreResult>` polled by `prompt()` falling back to the current default; a second `CoreTask prompted2` field), `coordinator` is the real `RunInputCoordinator` wired into the service under test, `published(Class)` collects events via a recording `ApplicationEventPublisher` (replace the existing publisher mock with a recording stub that ALSO satisfies existing verify calls), `deadlineScheduler.scheduledInstants()` extends `ManualDeadlineScheduler`. `RunInputCoordinator.submitAnswer` is called from the test thread — a direct call after the future exists is also fine if Awaitility proves flaky on the event probe; keep the thread only if the park actually blocks (it does — `join()` on the prompt). Use `opencodeSpec()` = `spec()` with coreId `opencode` and a stub adapter whose `coreId()` returns `opencode` (the adapters registry is already mocked in the rig).

Awaitility: check it is on the act-execution test classpath (`grep -n awaitility agent-control-tower/act-execution/pom.xml agent-control-tower/pom.xml`); if absent, use a `CompletableFuture.runAsync(...)` + `future.join()`-driven answer instead — the parking future is the synchronization point, so a plain `CompletableFuture.runAsync(() -> coordinator.submitAnswer(RUN, "postgres"))` started BEFORE `service.execute(...)` races the park safely (submitAnswer before park throws — unacceptable; so poll the event with a 50ms loop instead of adding a dependency).

- [ ] **Step 2: Run to verify failure**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=CoreExecutionServiceTest`
Expected: new tests FAIL (single prompt, no events); existing tests still PASS.

- [ ] **Step 3: Implement the loop**

In `CoreExecutionService.execute` (replacing lines ~766-777 — keep `openRuntime` and everything from `finalizeRuntime` onward untouched):

```java
        RunRuntimeRegistry.RunRuntime runtime = openRuntime(spec, adapter);
        CoreResult result = null;
        String failure = null;
        try {
            CoreTask nextTask = task;
            boolean firstTurn = true;
            while (true) {
                ScheduledFuture<?> deadlineTask = firstTurn
                        ? scheduleDeadline(runtime)
                        : scheduleDeadline(runtime, clock.instant().plus(taskDeadlines.getDeadline()));
                CoreResult turnResult;
                try {
                    turnResult = runtime.session().prompt(nextTask, event -> handleEvent(runtime, event))
                            .toCompletableFuture().join();
                } catch (CompletionException e) {
                    failure = messageOf(e.getCause());
                    break;
                } catch (RuntimeException e) {
                    failure = messageOf(e);
                    break;
                } finally {
                    deadlineTask.cancel(false);
                }
                if (turnResult.cancelled()) {
                    result = turnResult;
                    break;
                }
                String question = "qoder".equals(spec.coreId())
                        ? ClarificationQuestions.awaitingInput(turnResult.finalOutput())
                        : null;
                if (question == null) {
                    result = turnResult;
                    break;
                }
                eventPublisher.publishEvent(new TurnCompletedEvent(this, spec.runId(),
                        turnResult.finalOutput(),
                        turnResult.usage() == null ? null : turnResult.usage().inputTokens(),
                        turnResult.usage() == null ? null : turnResult.usage().outputTokens(),
                        turnResult.usage() == null ? null : turnResult.usage().observedModel()));
                RunInputCoordinator.OperatorInput input =
                        inputs.requestInput(spec.runId(), question).join(); // parks the virtual thread, unbounded (D3)
                if (input.finalizeRequested()) {
                    result = turnResult;
                    break;
                }
                eventPublisher.publishEvent(new RunInputReceivedEvent(this, spec.runId()));
                nextTask = new CoreTask("", List.of(), input.answer());
                firstTurn = false;
            }
        } finally {
            // nothing to cancel here; per-turn deadlines are cancelled in the loop
        }
```

Then keep the existing `try { finalizeRuntime(runtime); } ...` block and everything after EXACTLY as-is (it already reads `result`/`failure`). Check `UsageSnapshot` component names (`inputTokens`, `outputTokens`, `observedModel` — verify against `UsageSnapshot.java` and `QoderCoreSession.completeResult`).

For `scheduleDeadline(runtime, Instant)`: read the existing `scheduleDeadline(runtime)` body (near line 756) and add an overload differing only in the scheduled instant; keep the original method delegating to the overload with `spec.deadline()`. (`TaskDeadlineProperties` — read `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/config/TaskDeadlineProperties.java` or wherever the grep `grep -rn "class TaskDeadlineProperties" agent-control-tower --include=*.java` lands; use its real getter for the deadline `Duration` — `AgentLoopEngine.java:27-35` references it as the default 45-minute task deadline.)

Wake hooks:
- Verified stop while parked: in `CoreExecutionService.stop` (the verified-stop control path — `grep -n "public CompletionStage<ControlAck> stop\|recordVerifiedStop" agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreExecutionService.java` to locate it), after a VERIFIED stop, call `inputs.requestFinalize(runId)` so a parked run wakes and walks the normal finalize chain.
- External cancel without a coordinator stop (REST `cancelRun`) is handled by the engine listener in Task 6.

`QoderCoreSession.prompt` — after `String text = CorePromptComposition.joined(...)` (line ~1182):

```java
        // The clarification contract is Qoder-specific (2026-10-05 spec §3): the
        // core is told exactly how to mark a turn that needs operator input, so
        // the coordinator can park instead of finalizing. Other cores are
        // untouched.
        text = text + (text.isBlank() ? "" : "\n\n")
                + "If you need the operator's answer to a clarifying question before you can"
                + " continue, end your turn with the question as the final line starting with"
                + " [NEED-INPUT]. Never use [NEED-INPUT] for anything else.";
```

(`text` is effectively final going into `client.prompt(text)` — assign to a new variable if the compiler objects.)

- [ ] **Step 4: Run the tests**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest="CoreExecutionServiceTest,ClarificationQuestionsTest,RunInputCoordinatorTest"`
Expected: PASS — new tests green, ALL pre-existing `CoreExecutionServiceTest` tests green unchanged (the no-question path is byte-identical).

- [ ] **Step 5: Commit**

```bash
git add -A agent-control-tower
git commit -m "feat(runtime): clarification turn loop parks the coordinated run for operator input"
```

---

### Task 6: Engine listeners — WAITING_INPUT persistence, ask creation, per-turn accounting, cancel wake

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java` (new `@EventListener` methods near the existing lifecycle listeners; `completeRun` lines 1818-1827: add the CLARIFICATION sweep)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/KanbanRepository.java` (add `findByLinkedRunId(String)` if absent — check with `grep -n "linkedRunId" agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/KanbanRepository.java`)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/engine/AgentLoopEngineWaitingInputTest.java` (new — construct the engine with mocked collaborators exactly like the existing `AgentLoopEngineTest` does; read its setup first)

**Interfaces:**
- Consumes: events (Task 4), `RunService`-independent engine helpers `updateRunStatusDirect`, `recordUsage`-equivalent arithmetic, `recordTaskTrajectory` (all existing — READ their bodies first and mirror the arithmetic).
- Produces: on `RunWaitingForInputEvent`: run → `WAITING_INPUT`, CLARIFICATION ask created (linked to the card by `linkedRunId` when one exists), `ApprovalRequestedEvent` published. On `RunInputReceivedEvent`: run → `RUNNING`. On `TurnCompletedEvent`: usage/iteration/trajectory recorded. On `RunCompletedEvent(CANCELLED)`: `inputs.requestFinalize` wake. `completeRun` sweeps PENDING CLARIFICATION asks to DENIED ("finalized by operator" / event status reason).

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void waitingEventPersistsWaitingInputAndCreatesTheLinkedClarificationAsk() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Run run = new Run(); run.setId(runId); run.setAgentId(agentId); run.setStatus(RunStatus.RUNNING);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        KanbanItem card = new KanbanItem(); card.setId("card-1"); card.setLinkedRunId(runId.toString());
        when(kanbanRepository.findByLinkedRunId(runId.toString())).thenReturn(List.of(card));
        engine.addToActiveContextsForTest(runId, agentId); // mirror how existing tests seed activeContexts; if no seam exists, expose a package-private test helper

        engine.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which DB?"));

        ArgumentCaptor<Approval> ask = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(ask.capture());
        assertThat(ask.getValue().getAskType()).isEqualTo(Approval.AskType.QUESTION);
        assertThat(ask.getValue().getSource()).isEqualTo(ApprovalSource.CLARIFICATION);
        assertThat(ask.getValue().getKanbanItemId()).isEqualTo("card-1");
        assertThat(ask.getValue().getContent()).isEqualTo("Which DB?");
        assertThat(ask.getValue().getExpiresAt()).isNull();
        assertThat(run.getStatus()).isEqualTo(RunStatus.WAITING_INPUT);
        verify(eventPublisher).publishEvent(any(ApprovalRequestedEvent.class));
    }

    @Test
    void inputReceivedFlipsTheRunBackToRunning() {
        UUID runId = UUID.randomUUID();
        Run run = new Run(); run.setId(runId); run.setAgentId(UUID.randomUUID()); run.setStatus(RunStatus.WAITING_INPUT);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        engine.addToActiveContextsForTest(runId, run.getAgentId());

        engine.onRunInputReceived(new RunInputReceivedEvent(this, runId));

        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void turnCompletedAccumulatesUsageIterationAndTrajectory() {
        UUID runId = UUID.randomUUID();
        RunContext ctx = engine.addToActiveContextsForTest(runId, UUID.randomUUID()); // returns the seeded ctx
        long before = ctx.getTotalTokensUsed();

        engine.onTurnCompleted(new TurnCompletedEvent(this, runId, "intermediate answer", 10L, 5L, "efficient"));

        // Mirrors recordUsage's arithmetic (input+output into totalTokensUsed):
        assertThat(ctx.getTotalTokensUsed()).isEqualTo(before + 15L);
        assertThat(ctx.getIterationCount()).isEqualTo(1);
        ArgumentCaptor<SessionTrajectory> row = ArgumentCaptor.forClass(SessionTrajectory.class);
        verify(trajectoryRepository).save(row.capture());
        assertThat(row.getValue().getRole()).isEqualTo("assistant");
        assertThat(row.getValue().getContent()).isEqualTo("intermediate answer");
    }

    @Test
    void externalCancelWakesTheParkedRun() {
        UUID runId = UUID.randomUUID();
        engine.addToActiveContextsForTest(runId, UUID.randomUUID());

        engine.onRunCompletedExternally(new RunCompletedEvent(this, runId, UUID.randomUUID(), RunStatus.CANCELLED));

        verify(inputCoordinator).requestFinalize(runId);
    }

    @Test
    void completeRunSettlesPendingClarificationAsksDenied() {
        UUID runId = UUID.randomUUID();
        RunContext ctx = engine.addToActiveContextsForTest(runId, UUID.randomUUID());
        Approval ask = Approval.builder().runId(runId).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION).source(ApprovalSource.CLARIFICATION).build();
        when(approvalRepository.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING, ApprovalSource.CLARIFICATION))
                .thenReturn(List.of(ask));
        when(runRepository.findById(runId)).thenReturn(Optional.of(new Run()));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        engine.completeRun(ctx, RunStatus.COMPLETED);

        ArgumentCaptor<Approval> saved = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(a -> {
            assertThat(a.getStatus()).isEqualTo(ApprovalStatus.DENIED);
            assertThat(a.getReason()).isEqualTo("finalized by operator");
        });
    }
```

Fill the remaining gaps strictly by mirroring the file's existing fixtures: `engine.addToActiveContextsForTest(...)` is a NEW package-private seam this task adds to `AgentLoopEngine` (`RunContext addToActiveContextsForTest(UUID runId, UUID agentId)` building a minimal `RunContext` the same way `startRunInternal` does — reuse the engine's existing test setup for `Agent`, `AgentSession`, and constructor mocks); adapt stubbed collaborators (`runRepository`, `kanbanRepository`, `approvalRepository`, `trajectoryRepository`, `eventPublisher` as a recording publisher, `inputCoordinator` as a `RunInputCoordinator` mock) to however `AgentLoopEngineTest` already constructs them. The `turnCompleted` token arithmetic MUST be reconciled with `recordUsage`'s real body (read it — if it already sums input+output, reuse that method on a `UsageSnapshot` instead of hand-summing).

- [ ] **Step 2: Run to verify failure**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=AgentLoopEngineWaitingInputTest`
Expected: COMPILATION ERROR (no listeners yet).

- [ ] **Step 3: Implement**

```java
    /** Parked-run bookkeeping (2026-10-05): the coordinator parked the run; persist it. */
    @EventListener
    public void onRunWaitingForInput(RunWaitingForInputEvent event) {
        RunContext ctx = activeContexts.get(event.getRunId());
        if (ctx == null) {
            return;
        }
        updateRunStatusDirect(event.getRunId(), RunStatus.WAITING_INPUT);
        try {
            String cardId = kanbanRepository.findByLinkedRunId(event.getRunId().toString()).stream()
                    .findFirst().map(KanbanItem::getId).orElse(null);
            Approval ask = Approval.builder()
                    .runId(event.getRunId())
                    .status(ApprovalStatus.PENDING)
                    .approvalType(Approval.ApprovalType.TOOL_CALL)
                    .askType(Approval.AskType.QUESTION)
                    .source(ApprovalSource.CLARIFICATION)
                    .kanbanItemId(cardId)
                    .content(event.getQuestion())
                    .build();
            approvalRepository.save(ask);
            eventPublisher.publishEvent(new ApprovalRequestedEvent(this, ask.getId(), event.getRunId(), null));
        } catch (Exception e) {
            log.warn("Failed to create the clarification ask for {}: {}", event.getRunId(), e.getMessage());
        }
        log.info("Run {} waiting for operator input (ask created)", event.getRunId());
    }

    /** The operator answered; the coordinator re-prompts — the run is live again. */
    @EventListener
    public void onRunInputReceived(RunInputReceivedEvent event) {
        if (activeContexts.containsKey(event.getRunId())) {
            updateRunStatusDirect(event.getRunId(), RunStatus.RUNNING);
        }
    }

    /** Intermediate-turn accounting: the final turn is recorded by executeCoreRun as today. */
    @EventListener
    public void onTurnCompleted(TurnCompletedEvent event) {
        RunContext ctx = activeContexts.get(event.getRunId());
        if (ctx == null) {
            return;
        }
        // Mirror recordUsage's arithmetic for the intermediate turn (read recordUsage and reuse it
        // by constructing a UsageSnapshot if that keeps the arithmetic in one place).
        if (event.getOutputTokens() != null) {
            ctx.setTotalTokensUsed(ctx.getTotalTokensUsed() + event.getOutputTokens());
        }
        if (event.getInputTokens() != null) {
            ctx.setTotalTokensUsed(ctx.getTotalTokensUsed() + event.getInputTokens());
        }
        ctx.incrementIteration();
        if (event.getFinalOutput() != null && !event.getFinalOutput().isBlank()) {
            ctx.setLastAssistantResponse(event.getFinalOutput());
            recordTaskTrajectory(ctx, event.getFinalOutput(),
                    event.getOutputTokens() == null ? 0 : event.getOutputTokens().intValue());
        }
    }

    /** An externally cancelled run must not stay parked: wake it so the finalize chain runs. */
    @EventListener
    public void onRunCompletedExternally(RunCompletedEvent event) {
        if (event.getStatus() == RunStatus.CANCELLED) {
            inputCoordinator.requestFinalize(event.getRunId());
        }
    }
```

(The exact arithmetic for `onTurnCompleted` MUST mirror `recordUsage` — read it and adjust so totals match the per-turn snapshot semantics; `recordUsage` may already add input+output.)

In `completeRun`, next to the existing sweeps (lines 1818-1827), add:

```java
        try {
            for (Approval ask : approvalRepository.findByRunIdAndStatusAndSource(
                    ctx.getRunId(), ApprovalStatus.PENDING, ApprovalSource.CLARIFICATION)) {
                ask.setStatus(ApprovalStatus.DENIED);
                ask.setReason("finalized by operator");
                ask.setDecidedAt(Instant.now());
                approvalRepository.save(ask);
            }
        } catch (Exception e) {
            log.warn("Failed to settle clarification asks for {}: {}", ctx.getRunId(), e.getMessage());
        }
```

Add `KanbanRepository#findByLinkedRunId(String)` (Spring Data derived query; `linked_run_id` is the column — verify against `KanbanItem`), the `RunInputCoordinator` field + constructor wiring, and the `inputCoordinator` naming consistent with the listener above.

- [ ] **Step 4: Run the tests**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest="AgentLoopEngine*Test,CoreExecutionServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A agent-control-tower
git commit -m "feat(engine): waiting-input bookkeeping — status, clarification ask, per-turn accounting, cancel wake"
```

---

### Task 7: `/answer` extension and `/runs/{id}/finalize`

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/ApprovalAnswerService.java` (inject `RunInputCoordinator`, `SessionTrajectoryRepository`; CLARIFICATION branch)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java` (`answer` route lines 347-352: map `IllegalStateException` → 409)
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/port/RunInputPort.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoordinatedRunInputPort.java`
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/controller/RunController.java` (finalize endpoint, after line 87)
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/RunService.java` (inject `ObjectProvider<RunInputPort>` mirroring `runtimeControlProvider`)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/ApprovalAnswerServiceTest.java` (extend; create if absent), `agent-control-tower/act-agent/src/test/java/io/aria/conductor/agent/controller/RunControllerTest.java` (extend; create if absent — mirror the existing act-agent controller-test style)

**Interfaces:**
- Consumes: `RunInputCoordinator` (Task 4).
- Produces: `RunInputPort { boolean requestFinalize(UUID runId); }`; `POST /api/v1/runs/{id}/finalize` → 202 `{runId, status:"finalizing"}` when parked, 409 `{"error": "Run ... is not waiting for operator input"}` otherwise. `/answer` on a CLARIFICATION ask: settles APPROVED (reason = answer), writes a `SessionTrajectory` user row, calls `submitAnswer`; blank answer → 400; not-waiting → 409.

- [ ] **Step 1: Write the failing tests**

`ApprovalAnswerServiceTest` (new; `@ExtendWith(MockitoExtension.class)` — constructor is `@RequiredArgsConstructor`, so build with mocks):

```java
    @Test
    void answeringAClarificationSettlesTheAskAndWakesTheRun() {
        Approval ask = Approval.builder()
                .runId(RUN_ID).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION)
                .source(ApprovalSource.CLARIFICATION)
                .build();
        when(approvalRepository.findById(ask.getId())).thenReturn(Optional.of(ask));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(trajectoryRepository.findMaxTurnNumberByRunId(RUN_ID)).thenReturn(3);

        service.answer(ask.getId(), "postgres", true, null);

        ArgumentCaptor<SessionTrajectory> row = ArgumentCaptor.forClass(SessionTrajectory.class);
        verify(trajectoryRepository).save(row.capture());
        assertThat(row.getValue().getRole()).isEqualTo("user");
        assertThat(row.getValue().getTurnNumber()).isEqualTo(4);
        assertThat(row.getValue().getContent()).isEqualTo("postgres");
        verify(inputs).submitAnswer(RUN_ID, "postgres");
        ArgumentCaptor<Approval> saved = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(saved.getValue().getReason()).isEqualTo("postgres");
    }

    @Test
    void blankAnswerToAClarificationIsRefused() {
        Approval ask = Approval.builder().runId(RUN_ID).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION).source(ApprovalSource.CLARIFICATION).build();
        when(approvalRepository.findById(ask.getId())).thenReturn(Optional.of(ask));

        assertThatThrownBy(() -> service.answer(ask.getId(), "  ", true, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(inputs);
    }

    @Test
    void wakingANonWaitingRunSurfacesIllegalState() {
        Approval ask = Approval.builder().runId(RUN_ID).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION).source(ApprovalSource.CLARIFICATION).build();
        when(approvalRepository.findById(ask.getId())).thenReturn(Optional.of(ask));
        doThrow(new IllegalStateException("Run " + RUN_ID + " is not waiting for operator input"))
                .when(inputs).submitAnswer(RUN_ID, "postgres");

        assertThatThrownBy(() -> service.answer(ask.getId(), "postgres", true, null))
                .isInstanceOf(IllegalStateException.class);
    }
```

RunController test (mirror the existing act-agent controller-test rig; if none exists, test through `RunService`):

```java
    @Test
    void finalizeReturnsAcceptedWhenTheRunIsWaitingAndConflictWhenNot() {
        when(inputPort.requestFinalize(RUN_ID)).thenReturn(true);
        mvc.perform(post("/api/v1/runs/{id}/finalize", RUN_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("finalizing"));

        when(inputPort.requestFinalize(RUN_ID)).thenReturn(false);
        mvc.perform(post("/api/v1/runs/{id}/finalize", RUN_ID))
                .andExpect(status().isConflict());
    }
```

- [ ] **Step 2: Run to verify failure, then implement**

`ApprovalAnswerService.answer` — after the existing ACP_PERMISSION refusal (line ~1380) and BEFORE the `approved != null` QUESTION check, insert the CLARIFICATION branch (it returns its own path; the legacy code below stays untouched):

```java
        if (approval.getSource() == ApprovalSource.CLARIFICATION) {
            if (Boolean.FALSE.equals(approved)) {
                throw new IllegalArgumentException(
                        "Deny a waiting run via POST /runs/{id}/finalize, not /answer");
            }
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException(
                        "An answer is required to continue a run waiting for input");
            }
            approval.setAnswer(answer);
            approval.setStatus(ApprovalStatus.APPROVED);
            approval.setReason(answer);
            approval.setDecidedAt(Instant.now());
            Approval settled = approvalRepository.save(approval);
            int nextTurn = trajectoryRepository.findMaxTurnNumberByRunId(approval.getRunId()) + 1;
            trajectoryRepository.save(SessionTrajectory.builder()
                    .runId(approval.getRunId())
                    .turnNumber(nextTurn)
                    .role("user")
                    .content(answer)
                    .build());
            inputs.submitAnswer(approval.getRunId(), answer);
            return settled;
        }
```

Constructor gains `RunInputCoordinator inputs` and `SessionTrajectoryRepository trajectoryRepository` (`@RequiredArgsConstructor` — add the fields).

`ApprovalController.answer` — wrap the call:

```java
        try {
            return ResponseEntity.ok(approvalAnswerService.answer(id, request.answer(), request.approved(), request.reason()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
```

(The return type changes to `ResponseEntity<Object>` — update the signature.)

`RunInputPort` (act-common/port, mirrors `RunRuntimeControlPort`'s doc style):

```java
package io.aria.conductor.common.port;

import java.util.UUID;

/**
 * Port for waking a run parked in WAITING_INPUT (2026-10-05 spec §5). Lives in
 * act-common so the run store (act-agent) can expose the finalize route without
 * depending on the execution module.
 */
public interface RunInputPort {

    /**
     * Wakes the parked run with the finalize signal.
     *
     * @return false when the run is not parked in this process — the caller
     *         answers 409 instead of pretending something was scheduled
     */
    boolean requestFinalize(UUID runId);
}
```

`CoordinatedRunInputPort`:

```java
package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.port.RunInputPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CoordinatedRunInputPort implements RunInputPort {

    private final RunInputCoordinator inputs;

    public CoordinatedRunInputPort(RunInputCoordinator inputs) {
        this.inputs = inputs;
    }

    @Override
    public boolean requestFinalize(UUID runId) {
        return inputs.requestFinalize(runId);
    }
}
```

`RunController`:

```java
    @PostMapping("/{id}/finalize")
    public ResponseEntity<Object> finalizeRun(@PathVariable UUID id) {
        RunInputPort inputPort = runInputProvider == null ? null : runInputProvider.getIfAvailable();
        if (inputPort == null || !inputPort.requestFinalize(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("error",
                            "Run " + id + " is not waiting for operator input"));
        }
        return ResponseEntity.accepted()
                .body(java.util.Map.of("runId", id.toString(), "status", "finalizing"));
    }
```

(`RunController` gains `private final ObjectProvider<RunInputPort> runInputProvider;` via constructor injection — mirror how other controllers there receive optional beans, or inject `ObjectProvider` directly.)

- [ ] **Step 3: Run the tests**

Run: `cd agent-control-tower && mvn -q test -pl act-execution,act-agent -Dtest="ApprovalAnswerServiceTest,ApprovalControllerTest,RunControllerTest,RunService*Test"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A agent-control-tower
git commit -m "feat(approval): answer-to-continue wakes the parked run; finalize route ends it honestly"
```

---

### Task 8: Dashboard — status surfaces, waiting decision panel, WS

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/types/index.ts` (line 1861 `RunStatus` union: add `'WAITING_INPUT'`; `askType` union stays — `source` is not exposed today; add optional `source?: 'LEGACY_GATE' | 'ACP_PERMISSION' | 'CLARIFICATION'` to `Approval` ONLY if the backend `toDetail` exposes it — check `ApprovalController.toDetail` lines 223-237; if absent, skip and branch the UI on `askType === 'QUESTION' && item linked run waiting` instead — simplest discriminator: add `source` to `toDetail` in the backend as part of this task)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java` (`toDetail`: include `.source(...)` — read the builder and add the line)
- Modify: `agent-control-tower/act-dashboard/src/components/StatusBadge.tsx` (`statusColors`: `WAITING_INPUT: '#ffb74d'`)
- Modify: `agent-control-tower/act-dashboard/src/pages/OpsPage.tsx` (`STATUS_TONE`: `WAITING_INPUT: { pill: 'pill warn', dot: 'var(--amber)', label: 'Waiting for your answer' }`)
- Modify: `agent-control-tower/act-dashboard/src/components/ReviewPanels.tsx` (DecisionPanel: CLARIFICATION branch — Answer & continue + Finalize instead of Approve/Deny)
- Modify: `agent-control-tower/act-dashboard/src/api/approvals.ts` (add `finalizeRun`)
- Modify: `agent-control-tower/act-dashboard-api/src/main/java/io/aria/conductor/dashboard/listener/EventBroadcastListener.java` (add `onRunWaitingForInput` → `broadcast("run.waiting_input", ...)`)
- Test: vitest for the DecisionPanel branch (colocated `*.test.tsx`, follow existing panel tests)

**Interfaces:**
- Consumes: `/answer` (Task 7), `POST /runs/{id}/finalize` (Task 7), `Approval.source` (this task).
- Produces: `finalizeRun(runId): Promise<void>` in `api/approvals.ts`.

- [ ] **Step 1: Backend — expose `source` on the ask detail**

In `ApprovalController.toDetail`, add the source mapping next to `askType` (read the builder; `.source(approval.getSource() == null ? null : approval.getSource().name())`). Extend the existing `ApprovalControllerTest` detail test with `.andExpect(jsonPath("$.source").value("CLARIFICATION"))` for a CLARIFICATION fixture (or add one).

- [ ] **Step 2: Write the failing vitest**

Create/extend `agent-control-tower/act-dashboard/src/components/ReviewPanels.test.tsx` following the existing test setup (`grep -rl "DecisionPanel" agent-control-tower/act-dashboard/src --include=*.test.tsx` first; if none, render with `@testing-library/react` — check `package.json` devDependencies for the testing-library packages and mirror an existing component test's providers: QueryClientProvider wrapper):

```tsx
  it('CLARIFICATION asks render Answer & continue + Finalize instead of Approve/Deny', () => {
    const ask = { id: 'a1', runId: 'r1', status: 'PENDING', askType: 'QUESTION', source: 'CLARIFICATION', content: 'Which DB?' } as never;
    render(<DecisionPanel item={{ id: 'c1' } as never} pendingAsks={[ask]} />);
    expect(screen.getByRole('button', { name: /answer & continue/i })).toBeTruthy();
    expect(screen.getByRole('button', { name: /finalize/i })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
  });
```

- [ ] **Step 3: Implement**

`api/approvals.ts`:

```ts
/** Finalize a run parked in WAITING_INPUT (2026-10-05): ends it with the question preserved. */
export async function finalizeRun(runId: string): Promise<void> {
  await client.post(`/api/v1/runs/${runId}/finalize`);
}
```

`ReviewPanels.tsx` DecisionPanel — inside the ask map, branch the action row:

```tsx
          <div className="ask-actions">
            {ask.source === 'CLARIFICATION' ? (
              <>
                <button
                  className="btn primary"
                  disabled={resolveAsk.isPending || !(answers[ask.id] ?? '').trim()}
                  onClick={() => resolve({ ask, approved: true, answer: answers[ask.id] })}
                >
                  ▶ Answer &amp; continue
                </button>
                <button
                  className="btn"
                  disabled={finalize.isPending}
                  onClick={() => finalize.mutate(ask.runId)}
                >
                  ■ Finalize
                </button>
              </>
            ) : (
              <>
                <button className="btn primary" disabled={resolveAsk.isPending} onClick={() => resolve({ ask, approved: true, answer: answers[ask.id] || undefined })}>Approve</button>
                <button className="btn" disabled={resolveAsk.isPending} onClick={() => resolve({ ask, approved: false, answer: answers[ask.id] || undefined })}>Deny</button>
              </>
            )}
          </div>
```

Add the mutation next to `resolveAsk`:

```tsx
  const finalize = useMutation({
    mutationFn: (runId: string) => finalizeRun(runId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    },
    onError: (err: unknown) => setError(apiErrorMessage(err, 'Finalize rejected — please retry.')),
  });
```

Also exclude CLARIFICATION asks from `batchApprovable` (they are never bulk-resolved):

```tsx
  const batchApprovable = pendingAsks.filter((ask) =>
    nativePermissionOf(ask) === null && ask.source !== 'CLARIFICATION');
```

The `resolveAsk` QUESTION branch already routes to `answerAsk` — keep it (approved=true + the answer text is exactly the contract).

`EventBroadcastListener`:

```java
    @EventListener
    public void onRunWaitingForInput(RunWaitingForInputEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("runId", event.getRunId().toString());
        String question = event.getQuestion();
        data.put("question", question != null && question.length() > 500 ? question.substring(0, 500) + "..." : question);
        broadcast("run.waiting_input", data);
    }
```

- [ ] **Step 4: Run vitest + build, and the backend detail test**

Run: `cd agent-control-tower/act-dashboard && pnpm test -- --run && pnpm build && cd .. && mvn -q test -pl act-execution -Dtest=ApprovalControllerTest && mvn -q test -pl act-dashboard-api -Dtest="EventBroadcastListener*Test"`
Expected: PASS (create/extend the WS listener test to mirror the existing `onRunCompleted` test if one exists).

- [ ] **Step 5: Commit**

```bash
git add -A agent-control-tower
git commit -m "feat(dashboard): waiting-input status surfaces, answer-and-continue panel, waiting WS broadcast"
```

---

### Task 9: Wave verification and the live operator drill

**Files:** none created; verification only.

- [ ] **Step 1: Full module regression**

Run: `cd agent-control-tower && mvn -q clean test -Dspring.profiles.active=h2`
Expected: PASS (full Java matrix; WAITING_INPUT touches engine/runtime/kanban/approval — treat any status-switch compile error as a missed consumer from Task 1).

- [ ] **Step 2: Frontend + MCP**

Run: `cd agent-control-tower/act-dashboard && pnpm test -- --run && pnpm build && cd .. && cd packages/mcp-server && pnpm test`
Expected: PASS.

- [ ] **Step 3: Leftover sweep**

Run: `grep -rn "WAITING_INPUT" agent-control-tower --include=*.java | grep -v target | grep -v test` — eyeball that every consumer is intentional; run `grep -rn "AskType.QUESTION" agent-control-tower --include=*.java | grep -v target` and confirm every producer of QUESTION asks is either the legacy path (unchanged) or the CLARIFICATION ask (Task 6).

- [ ] **Step 4: Live operator drill (pre-merge, real stack)**

Start the local stack with a Qoder HOST agent (operator's usual `scripts/start.ps1` flow). Dispatch a task whose prompt seed says: "Before doing anything, ask me one clarifying question about the scope and stop." Expected observable chain:
1. The run enters WAITING_INPUT; the board card shows the waiting tone; the ask appears in the Review Queue with the question text.
2. The backend log shows one prompt turn, no finalize between the turn end and the answer.
3. Answer via the card's Answer & continue → run returns RUNNING, a second prompt hits the SAME session, the task continues to completion.
4. Second dispatch: answer nothing, click Finalize after the question → run COMPLETED with the question in the outcome; admission slot freed.
5. Third dispatch: cancel while waiting → run CANCELLED, no orphaned core child (`Get-Process qodercli` returns to baseline on Windows HOST).

Capture the drill evidence (log tail + board screenshots) into the PR description.

- [ ] **Step 5: Final commit (if the drill produced fixes)**

```bash
git status && git add -A agent-control-tower && git commit -m "fix(runtime): waiting-input drill fixes" || true
```

(Only if the drill produced changes; no empty commits.)
