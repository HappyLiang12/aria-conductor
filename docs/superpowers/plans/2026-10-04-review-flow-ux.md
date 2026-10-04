# Review Flow UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Outcome-aware REVIEW cards with card-layer batch decisions (2026-10-03 design) plus the 2026-10-04 qoder permission-ask UX amendment: native web tools auto-approve, settle propagation (card + notification) on every ask settle, a corrected native-ask lifecycle, and tool context on tool-call cards.

**Architecture:** Backend enrichment + transactional sweep in the kanban services; a corrected `Approval.source` stamp that makes the existing native guards effective; settle propagation riding the existing `ApprovalDecidedEvent` / `ApprovalExpiredEvent` pair through new listeners (kanban mirror + notifications); frontend chips, batch actions and queue copy.

**Tech Stack:** Java 21 / Spring Boot 3.3 (act-execution, act-aria, act-common, act-app), React 19 + Vitest (act-dashboard), Flyway (h2 MySQL-mode + MariaDB).

**Spec:** `docs/superpowers/specs/2026-10-03-review-flow-ux-design.md` (including the 2026-10-04 amendment section). Read it before starting any task.

## Global Constraints

- Java package root `io.aria.conductor`; act-execution holds the kanban + approval machinery; act-aria holds notifications; act-common holds models/events/repositories; act-app holds Flyway migrations.
- Never weaken an assertion to go green; every test must be watched RED before its implementation (the exception: pure wiring tasks where a compile error is the RED, noted in the task).
- The native permission lifecycle is fail-closed: no session-wide or remembered grants (one-use grants stay one-use); native asks are `ApprovalSource.ACP_PERMISSION` and must never be settled by the legacy card sweeps (`denyPendingByKanbanItemId`, `markStaleByKanbanItemId`, `cancelAllPendingForRun`).
- The D2 sweep settles `REVIEW_REQUEST` asks only (source `LEGACY_GATE`); a card click must never decide a native ask.
- Operator-only endpoints keep the operator bearer; new listeners never require authority (they react to already-authorised writes).
- UI copy: existing surfaces are English; keep new copy English and consistent with the surrounding text.
- Frontend must stay green: `cd agent-control-tower/act-dashboard && npx vitest run <paths>` and `pnpm build` at wave ends.
- Backend module test commands: `cd agent-control-tower && mvn -B test -pl <module> -Dtest=<Class>` (spot), full module runs at wave ends.
- No `§` symbols in docs; use plain "spec section N" phrasing.
- Commit style: `feat(kanban): ...`, `feat(aria): ...`, `fix(execution): ...`, `docs(...)`; one commit per task minimum.

---

## File Structure

| File | Responsibility | Task |
|------|----------------|------|
| `act-execution/.../kanban/KanbanItem.java` | carry a transient `runOutcome` | T1 |
| `act-execution/.../kanban/KanbanService.java` | batched outcome enrichment on `list` | T1 |
| `act-execution/.../repository/ApprovalRepository.java` | approve-path bulk settle; drop the stale-mark query after T2 | T2 |
| `act-execution/.../kanban/KanbanTransitionService.java` | centralised ask sweep on every REVIEW exit | T2 |
| `act-common/.../event/ApprovalExpiredEvent.java` | carry the tool name | T3 |
| `act-execution/.../approval/PermissionCoordinator.java` | source stamp, expiry event publish, `cancelPendingForRun` | T3 |
| `act-execution/.../engine/AgentLoopEngine.java` | call the native run-end settle beside the gate sweep | T3 |
| `act-execution/.../kanban/KanbanReviewCardListener.java` | settled-ask guard + tool context in the card | T4 |
| `act-execution/.../kanban/ApprovalSettleCardListener.java` | new: settle the linked tool-call card on decide/expire | T4 |
| `act-aria/.../persistence/AriaNotificationRepository.java` | mark-requested-read bulk update | T5 |
| `act-aria/.../service/NotificationService.java` | expose the flip | T5 |
| `act-aria/.../listener/NotificationTriggerListener.java` | flip on decide/expire; expiry text gains the tool name | T5 |
| `act-dashboard/src/types/index.ts` | `NotificationType` += `approval.expired`; `KanbanItem.runOutcome` | T5/T6 |
| `act-dashboard/src/components/NotificationBell.tsx` | icon for `approval.expired` | T5 |
| `act-dashboard/src/components/ReviewPanels.tsx` (`ShortApprovalView`) + `TaskDrawer.tsx` | outcome chip + copy | T6 |
| `act-dashboard/src/components/KanbanBoard.tsx` | REVIEW column batch actions | T7 |
| `act-dashboard/src/components/ReviewQueue.tsx` | accurate label + ask content rows | T8 |
| `act-app/src/main/resources/db/migration/V66__settle_stale_review_asks.sql` | data repair | T9 |

---

### Task 1: `runOutcome` on the kanban listing

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanItem.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanService.java` (the `list` method, around the `pendingAskCount` block)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanServiceRunOutcomeTest.java` (new; read `KanbanServiceAskCountTest.java` first and mirror its Mockito fixture)

**Interfaces:**
- Produces: `KanbanItem.setRunOutcome(String)` / `getRunOutcome()`; `KanbanService.list` returns items carrying `runOutcome` in `COMPLETED | FAILED | ACTIVE | CANCELLED | UNKNOWN`; `static String runOutcome(RunStatus)` (package-visible for tests).

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

class KanbanServiceRunOutcomeTest {

    @Test
    void listEnrichesEachLinkedRunOutcomeFromOneBatchedQuery() {
        KanbanRepository kanbanRepository = Mockito.mock(KanbanRepository.class);
        ApprovalRepository approvalRepository = Mockito.mock(ApprovalRepository.class);
        RunRepository runRepository = Mockito.mock(RunRepository.class);
        KanbanService service = new KanbanService(kanbanRepository, approvalRepository, runRepository);

        UUID completedRun = UUID.randomUUID();
        UUID failedRun = UUID.randomUUID();
        KanbanItem completedCard = item("c1", completedRun.toString());
        KanbanItem failedCard = item("c2", failedRun.toString());
        KanbanItem unlinked = item("c3", null);
        KanbanItem ghost = item("c4", UUID.randomUUID().toString());
        when(kanbanRepository.findAll()).thenReturn(List.of(completedCard, failedCard, unlinked, ghost));
        when(approvalRepository.countPendingByKanbanItemIds(anyCollection())).thenReturn(List.of());
        when(runRepository.findAllById(anyCollection())).thenReturn(List.of(
                run(completedRun, RunStatus.COMPLETED), run(failedRun, RunStatus.FAILED)));

        List<KanbanItem> items = service.list(null);

        assertThat(items).extracting(KanbanItem::getRunOutcome)
                .containsExactly("COMPLETED", "FAILED", "UNKNOWN", "UNKNOWN");
    }

    @Test
    void runOutcomeMapsEveryStatusClass() {
        assertThat(KanbanService.runOutcome(RunStatus.RUNNING)).isEqualTo("ACTIVE");
        assertThat(KanbanService.runOutcome(RunStatus.PENDING)).isEqualTo("ACTIVE");
        assertThat(KanbanService.runOutcome(RunStatus.INITIALIZING)).isEqualTo("ACTIVE");
        assertThat(KanbanService.runOutcome(RunStatus.PAUSED)).isEqualTo("ACTIVE");
        assertThat(KanbanService.runOutcome(RunStatus.CANCELLED)).isEqualTo("CANCELLED");
        assertThat(KanbanService.runOutcome(RunStatus.ABORTED)).isEqualTo("CANCELLED");
        assertThat(KanbanService.runOutcome(RunStatus.COMPLETED)).isEqualTo("COMPLETED");
        assertThat(KanbanService.runOutcome(RunStatus.FAILED)).isEqualTo("FAILED");
        assertThat(KanbanService.runOutcome(null)).isEqualTo("UNKNOWN");
    }

    private static KanbanItem item(String id, String linkedRunId) {
        KanbanItem item = new KanbanItem();
        item.setId(id);
        item.setStatus(KanbanStatus.REVIEW);
        item.setLinkedRunId(linkedRunId);
        return item;
    }

    private static Run run(UUID id, RunStatus status) {
        return Run.builder().id(id).status(status).build();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=KanbanServiceRunOutcomeTest`
Expected: compile failure — `KanbanService` has no 3-arg constructor, `getRunOutcome`/`runOutcome` do not exist.

- [ ] **Step 3: Write minimal implementation**

`KanbanItem.java` — beside the transient `pendingAskCount` field (same annotation style):

```java
    /** Derived at listing time from the linked run's status; never persisted. */
    @Transient
    private String runOutcome;
```

`KanbanService.java`:
1. Add `private static final Logger log = LoggerFactory.getLogger(KanbanService.class);` if absent, the `RunRepository` field (`io.aria.conductor.agent.repository.RunRepository`), and extend the constructor parameter list with it.
2. Extend `list(KanbanStatus)` after the `pendingAskCount` block:

```java
        if (!items.isEmpty()) {
            List<UUID> linkedRunIds = items.stream()
                    .map(KanbanItem::getLinkedRunId)
                    .filter(linkedRunId -> linkedRunId != null && !linkedRunId.isBlank())
                    .map(linkedRunId -> {
                        try {
                            return UUID.fromString(linkedRunId);
                        } catch (IllegalArgumentException e) {
                            return null; // a corrupt link is history, not an outcome
                        }
                    })
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            Map<UUID, RunStatus> statuses = linkedRunIds.isEmpty() ? Map.of()
                    : runRepository.findAllById(linkedRunIds).stream()
                            .collect(Collectors.toMap(Run::getId, Run::getStatus));
            items.forEach(item -> {
                RunStatus status = item.getLinkedRunId() == null ? null : parseStatusOrNull(statuses, item.getLinkedRunId());
                item.setRunOutcome(runOutcome(status));
            });
        }
```

with the helpers:

```java
    /** The four display classes of a linked run; UNKNOWN when there is no resolvable run. */
    static String runOutcome(RunStatus status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELLED, ABORTED -> "CANCELLED";
            case RUNNING, PENDING, INITIALIZING, PAUSED -> "ACTIVE";
        };
    }

    private static RunStatus parseStatusOrNull(Map<UUID, RunStatus> statuses, String linkedRunId) {
        try {
            return statuses.get(UUID.fromString(linkedRunId));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=KanbanServiceRunOutcomeTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanItem.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanService.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanServiceRunOutcomeTest.java
git commit -m "feat(kanban): enrich the item listing with the linked run outcome"
```

---

### Task 2: Ask sweep on every REVIEW exit

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/ApprovalRepository.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanTransitionService.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanTransitionServiceSweepTest.java` (new, Mockito; mirror the constructor deps from the class)
- Test: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/KanbanTransitionIntegrityIntegrationTest.java` (extend: one native-negative case)

**Interfaces:**
- Consumes: the existing `denyPendingByKanbanItemId` (LEGACY_GATE-only).
- Produces: `ApprovalRepository.approvePendingByKanbanItemId(String itemId, String reason, Instant now)`; `KanbanTransitionService.transition` settles pending REVIEW asks on every REVIEW exit — APPROVED on `DONE`, DENIED on `TODO` (rework) and `CANCELLED`.

- [ ] **Step 1: Write the failing unit test**

```java
package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KanbanTransitionServiceSweepTest {

    private KanbanRepository kanbanRepository;
    private KanbanService kanbanService;
    private ApprovalRepository approvalRepository;
    private KanbanTransitionService service;

    @BeforeEach
    void setUp() {
        kanbanRepository = Mockito.mock(KanbanRepository.class);
        kanbanService = Mockito.mock(KanbanService.class);
        approvalRepository = Mockito.mock(ApprovalRepository.class);
        service = new KanbanTransitionService(kanbanRepository, kanbanService,
                Mockito.mock(RunService.class), Mockito.mock(RunRepository.class),
                Mockito.mock(AgentRepository.class), Mockito.mock(AgentPickerService.class),
                Mockito.mock(AgentPickupEligibility.class), approvalRepository,
                Mockito.mock(ApplicationEventPublisher.class));
    }

    @Test
    void acceptingAReviewCardApprovesItsPendingAsks() {
        KanbanItem card = reviewCard("card-1");
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(card));

        service.transition("card-1", TransitionRequest.builder().status(KanbanStatus.DONE).build());

        verify(approvalRepository).approvePendingByKanbanItemId(eq("card-1"),
                eq("accepted by card decision"), any(Instant.class));
    }

    @Test
    void reworkingAReviewCardDeniesItsPendingAsks() {
        KanbanItem card = reviewCard("card-1");
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(card));

        // requestChanges runs first inside transition(TODO); pickup will fail on
        // mocks afterwards — the sweep call must already have happened.
        try {
            service.transition("card-1", TransitionRequest.builder().status(KanbanStatus.TODO).build());
        } catch (RuntimeException ignored) {
            // pickup is mocked out; the assertions below are the contract
        }

        verify(approvalRepository).denyPendingByKanbanItemId(eq("card-1"),
                eq("superseded by request changes"), any(Instant.class));
        verify(approvalRepository, Mockito.never()).markStaleByKanbanItemId(any(), any());
    }

    private static KanbanItem reviewCard(String id) {
        KanbanItem item = new KanbanItem();
        item.setId(id);
        item.setStatus(KanbanStatus.REVIEW);
        return item;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=KanbanTransitionServiceSweepTest`
Expected: compile failure — `approvePendingByKanbanItemId` does not exist; the rework verify would fail against today's `markStaleByKanbanItemId` call.

- [ ] **Step 3: Write minimal implementation**

`ApprovalRepository.java` — beside `denyPendingByKanbanItemId`, mirroring its javadoc/comment style:

```java
    /** Bulk approve of a card's PENDING review asks (the operator accepted the
     *  work): single-statement bulk update, no entity load; callers must be
     *  @Transactional and results bypass the persistence context. Only
     *  {@code LEGACY_GATE} rows are swept: rows with {@code source = ACP_PERMISSION}
     *  are owned by the ACP permission coordinator and must never be decided by
     *  a legacy card sweep. */
    @Modifying
    @Query("update Approval a set a.status = io.aria.conductor.common.model.ApprovalStatus.APPROVED, " +
           "a.reason = :reason, a.decidedAt = :now " +
           "where a.kanbanItemId = :itemId and a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING " +
           "and a.source = io.aria.conductor.common.model.ApprovalSource.LEGACY_GATE")
    int approvePendingByKanbanItemId(@Param("itemId") String itemId,
                                     @Param("reason") String reason,
                                     @Param("now") Instant now);
```

Delete `markStaleByKanbanItemId` (its only caller is being replaced; keep `denyPendingByKanbanItemId`).

`KanbanTransitionService.java`:
1. In `transition`, the DONE case becomes:

```java
            case DONE -> {
                approvalRepository.approvePendingByKanbanItemId(id, "accepted by card decision", Instant.now());
                yield kanbanService.transition(id, KanbanStatus.DONE, request.getComment());
            }
```

2. In `requestChanges`, replace `approvalRepository.markStaleByKanbanItemId(item.getId(), Instant.now());` with:

```java
        approvalRepository.denyPendingByKanbanItemId(item.getId(), "superseded by request changes", Instant.now());
```

3. Update the `requestChanges` javadoc sentence about the "stale-ask sweep" to name the deny sweep.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=KanbanTransitionServiceSweepTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Add the native-negative integration case**

In `KanbanTransitionIntegrityIntegrationTest` (act-app), add one test in the class's own fixture style: persist a REVIEW card plus two PENDING approvals on it — one `source = LEGACY_GATE` (builder default), one `source = ACP_PERMISSION` — transition the card to DONE, then assert: the legacy row is `APPROVED`, the native row is still `PENDING`.

Run: `cd agent-control-tower && mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test=KanbanTransitionIntegrityIntegrationTest -Djacoco.skip=true`
Expected: PASS (all cases including the new one).

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/ApprovalRepository.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanTransitionService.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanTransitionServiceSweepTest.java \
        agent-control-tower/act-app/src/test/java/io/aria/conductor/app/KanbanTransitionIntegrityIntegrationTest.java
git commit -m "feat(kanban): settle review asks on every review exit (approve/rework/cancel)"
```

---

### Task 3: Native ask lifecycle — source stamp, expiry event, run-end settle

**Files:**
- Modify: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/ApprovalExpiredEvent.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/PermissionCoordinator.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java` (run-end hook, line ~1814 beside `approvalGate.cancelAllPendingForRun(...)`)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/PermissionCoordinatorTest.java` (extend)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/ApprovalGateTest.java` (extend)

**Interfaces:**
- Consumes: `ApprovalSource.ACP_PERMISSION`; the coordinator's existing `expire(...)`, `permissions` repository, `eventPublisher`.
- Produces: `ApprovalExpiredEvent(source, approvalId, runId, reason, toolName)` (+ the 4-arg overload kept, toolName null); `PermissionCoordinator.cancelPendingForRun(UUID runId)` returning the settled count; every native row persisted with `source = ACP_PERMISSION`.

- [ ] **Step 1: Write the failing tests**

In `PermissionCoordinatorTest` (mirror the class's existing fixture — it already builds a coordinator with mocked gate/repos/publisher):

```java
    @Test
    void registerStampsNativeAsksWithTheAcpPermissionSource() {
        // arrange a register() call exactly like the existing register tests
        // ... (reuse the fixture's NativePermission builder)
        coordinator.register(ask());

        ArgumentCaptor<Approval> saved = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(ApprovalSource.ACP_PERMISSION);
    }

    @Test
    void cancelPendingForRunSettlesOnlyPendingAsksWithTheRunEndedReason() {
        AcpPermissionRequest pending = pendingRow(RUN_ID, "run_agent");
        AcpPermissionRequest settled = pendingRow(RUN_ID, "WebSearch");
        Approval pendingApproval = approval(pending.getApprovalId(), ApprovalStatus.PENDING);
        Approval settledApproval = approval(settled.getApprovalId(), ApprovalStatus.APPROVED);
        when(permissions.findByRunId(RUN_ID)).thenReturn(List.of(pending, settled));
        when(approvals.findById(pending.getApprovalId())).thenReturn(Optional.of(pendingApproval));
        when(approvals.findById(settled.getApprovalId())).thenReturn(Optional.of(settledApproval));

        int settledCount = coordinator.cancelPendingForRun(RUN_ID);

        assertThat(settledCount).isEqualTo(1);
        assertThat(pendingApproval.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(pendingApproval.getReason()).isEqualTo("run ended");
        assertThat(settledApproval.getStatus()).isEqualTo(ApprovalStatus.APPROVED); // untouched
        ArgumentCaptor<ApprovalExpiredEvent> event = ArgumentCaptor.forClass(ApprovalExpiredEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getToolName()).isEqualTo("run_agent");
    }

    @Test
    void expiryPublishesTheExpiredEventWithTheToolName() {
        AcpPermissionRequest row = pendingRow(RUN_ID, "run_agent");
        Approval approval = approval(row.getApprovalId(), ApprovalStatus.PENDING);
        when(permissions.findByRunId(RUN_ID)).thenReturn(List.of(row));
        when(approvals.findById(row.getApprovalId())).thenReturn(Optional.of(approval));
        when(row.getExpiresAt()).thenReturn(Instant.now().minusSeconds(5));

        coordinator.expirePendingForRun(RUN_ID, Instant.now());

        ArgumentCaptor<ApprovalExpiredEvent> event = ArgumentCaptor.forClass(ApprovalExpiredEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getToolName()).isEqualTo("run_agent");
    }
```

(The snippets use the fixture's helper names — `ask()`, `pendingRow(...)`, `approval(...)` — adapt them to the helpers the class actually has; the assertions are the contract.)

In `ApprovalGateTest`:

```java
    @Test
    void cancelAllPendingForRunLeavesNativeAsksAlone() {
        Approval nativeAsk = Approval.builder().id(UUID.randomUUID()).runId(RUN_ID)
                .status(ApprovalStatus.PENDING)
                .source(ApprovalSource.ACP_PERMISSION)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .build();
        when(approvalRepository.findByRunId(RUN_ID)).thenReturn(List.of(nativeAsk));

        gate.cancelAllPendingForRun(RUN_ID);

        assertThat(nativeAsk.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        verify(approvalRepository, never()).save(nativeAsk);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="PermissionCoordinatorTest,ApprovalGateTest"`
Expected: compile failure — `cancelPendingForRun` and `ApprovalExpiredEvent#getToolName` do not exist; the source-stamp assertion fails.

- [ ] **Step 3: Write minimal implementation**

`ApprovalExpiredEvent.java` — add the field, a 5-arg constructor, keep the 4-arg for the legacy gate:

```java
    private final String toolName;

    public ApprovalExpiredEvent(Object source, UUID approvalId, UUID runId, String reason) {
        this(source, approvalId, runId, reason, null);
    }

    public ApprovalExpiredEvent(Object source, UUID approvalId, UUID runId, String reason, String toolName) {
        super(source);
        this.approvalId = approvalId;
        this.runId = runId;
        this.reason = reason;
        this.toolName = toolName;
    }
```

`PermissionCoordinator.java`:
1. In `register`, the `Approval.builder()` chain gains:

```java
                .source(ApprovalSource.ACP_PERMISSION)
```

(import `io.aria.conductor.common.model.ApprovalSource`.)

2. Split `expire` so every expiry publishes the event:

```java
    private void expire(Approval approval, AcpPermissionRequest row, Instant now) {
        expireWith(approval, row, now, EXPIRY_REASON);
    }

    /**
     * The single native settle-by-timeout path. Publishes {@link ApprovalExpiredEvent}
     * with the ask's tool name so the operator learns what was skipped.
     */
    private void expireWith(Approval approval, AcpPermissionRequest row, Instant now, String reason) {
        approval.setStatus(ApprovalStatus.EXPIRED);
        approval.setReason(reason);
        approval.setDecidedAt(now);
        approvals.save(approval);
        markDeliveryExpired(row);
        approvalGate.cancelPendingApproval(approval.getId());
        eventPublisher.publishEvent(new ApprovalExpiredEvent(this, approval.getId(),
                approval.getRunId(), approval.getReason(), row.getToolName()));
    }
```

3. New run-end settle (place beside `expirePendingForRun`):

```java
    /**
     * Settles every still-PENDING native ask of a run whose runtime has ended
     * (the run can no longer receive a reply, so the ask is adjudicated, never
     * left behind): EXPIRED with the recorded reason "run ended", the waiter
     * released, and {@link ApprovalExpiredEvent} published. Already-settled
     * asks are never rewritten.
     */
    @Transactional
    public int cancelPendingForRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        int settled = 0;
        for (AcpPermissionRequest row : permissions.findByRunId(runId)) {
            Approval approval = approvals.findById(row.getApprovalId()).orElse(null);
            if (approval == null || approval.getStatus() != ApprovalStatus.PENDING) {
                continue;
            }
            expireWith(approval, row, clock.instant(), "run ended");
            settled++;
        }
        return settled;
    }
```

`AgentLoopEngine.java` — beside `approvalGate.cancelAllPendingForRun(ctx.getRunId());` (line ~1814):

```java
            approvalGate.cancelAllPendingForRun(ctx.getRunId());
            permissionCoordinator.cancelPendingForRun(ctx.getRunId());
```

Add the `PermissionCoordinator` constructor dependency (import `io.aria.conductor.execution.approval.PermissionCoordinator`) and update the engine's construction sites/tests that build it.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="PermissionCoordinatorTest,ApprovalGateTest"`
Expected: PASS. Then the full approval package: `mvn -B test -pl act-execution -Dtest="io.aria.conductor.execution.approval.*"` — PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/ApprovalExpiredEvent.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/PermissionCoordinator.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/PermissionCoordinatorTest.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/ApprovalGateTest.java
git commit -m "fix(execution): stamp native asks as ACP_PERMISSION; settle and announce them at run end"
```

---

### Task 4: Settle the tool-call card + carry tool context (D7 backend)

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/ApprovalSettleCardListener.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanReviewCardListener.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/ApprovalSettleCardListenerTest.java` (new; mirror `KanbanReviewCardListenerTest`'s fixture)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanReviewCardListenerTest.java` (extend: settled-ask guard + enriched card text)

**Interfaces:**
- Consumes: `ApprovalDecidedEvent` (fields `approvalId`, `decision` as `ApprovalStatus`, `approvalType`), `ApprovalExpiredEvent` (+`toolName` from T3), `ApprovalRepository`, `AcpPermissionRequestRepository` (act-common, finder `findByApprovalId`), `KanbanService.transition`, `KanbanRepository`.
- Produces: `ApprovalSettleCardListener.onDecided` / `.onExpired` — native asks only (`approval.source == ACP_PERMISSION`); REVIEW card → DONE on APPROVED, CANCELLED otherwise; card title `Review: tool call - <tool> (run <id8>)` with an arguments description.

- [ ] **Step 1: Write the failing tests**

`ApprovalSettleCardListenerTest` (constructor: `ApprovalRepository`, `AcpPermissionRequestRepository`, `KanbanRepository`, `KanbanService`; call the listener methods directly):

```java
    @Test
    void approvedNativeAskMovesItsReviewCardToDone() {
        UUID approvalId = UUID.randomUUID();
        Approval approval = nativeAsk(approvalId, ApprovalStatus.APPROVED);
        KanbanItem card = reviewCard("card-1");
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(card));

        listener.onDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.APPROVED));

        verify(kanbanService).transition("card-1", KanbanStatus.DONE, "ask approved");
    }

    @Test
    void expiredNativeAskMovesItsReviewCardToCancelled() {
        UUID approvalId = UUID.randomUUID();
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(nativeAsk(approvalId, ApprovalStatus.EXPIRED)));
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(reviewCard("card-1")));

        listener.onExpired(new ApprovalExpiredEvent(this, approvalId, UUID.randomUUID(), "run ended", "run_agent"));

        verify(kanbanService).transition("card-1", KanbanStatus.CANCELLED, "ask expired");
    }

    @Test
    void legacyAskDecisionsNeverTouchCards() {
        UUID approvalId = UUID.randomUUID();
        Approval legacy = Approval.builder().id(approvalId).runId(UUID.randomUUID())
                .status(ApprovalStatus.APPROVED).kanbanItemId("card-1")
                .source(ApprovalSource.LEGACY_GATE).build();
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(legacy));

        listener.onDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.APPROVED));

        verify(kanbanService, never()).transition(any(), any(), any());
    }

    @Test
    void aCardAlreadyPastReviewIsLeftAlone() {
        UUID approvalId = UUID.randomUUID();
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(nativeAsk(approvalId, ApprovalStatus.APPROVED)));
        KanbanItem done = reviewCard("card-1");
        done.setStatus(KanbanStatus.DONE);
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(done));

        listener.onExpired(new ApprovalExpiredEvent(this, approvalId, UUID.randomUUID(), "run ended", "WebSearch"));

        verify(kanbanService, never()).transition(any(), any(), any());
    }
```

(`nativeAsk(...)` builds `Approval.builder()...source(ApprovalSource.ACP_PERMISSION).kanbanItemId("card-1").approvalType(TOOL_CALL)`; `reviewCard(...)` mirrors the sibling test class.)

`KanbanReviewCardListenerTest` additions:

```java
    @Test
    void anAlreadySettledAskGetsNoCard() {
        // approval with status APPROVED + no linked card
        // linkReviewCard must not create a card and must not link
        verify(kanbanService, never()).create(any(CreateKanbanItemRequest.class));
        verify(approvalRepository, never()).linkKanbanItemIdIfAbsent(any(), any());
    }

    @Test
    void aToolCallCardCarriesTheToolNameAndAnArgumentExcerpt() {
        // approval id linked to an AcpPermissionRequest(toolName "run_agent",
        // argumentsJson "{\"agentId\":\"9aaa...\"...}") and no existing card
        // capture the CreateKanbanItemRequest:
        assertThat(captor.getValue().getTitle()).isEqualTo("Review: tool call - run_agent (run " + run8 + ")");
        assertThat(captor.getValue().getDescription()).startsWith("{\"agentId\"");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="ApprovalSettleCardListenerTest,KanbanReviewCardListenerTest"`
Expected: compile failure for the new class; the guard/enrichment assertions fail.

- [ ] **Step 3: Write the implementation**

`ApprovalSettleCardListener.java` — a focused best-effort listener; synchronous `@EventListener` is acceptable here (the writes are small); wrap everything in try/catch and log:

```java
package io.aria.conductor.execution.kanban;

/**
 * Settle propagation (review-flow amendment, D6): when a native permission ask
 * settles (decided or expired), its auto-created review card reaches a terminal
 * state instead of lingering. Only native asks are handled: review asks are the
 * card layer's own vocabulary and keep their existing flows.
 */
@Slf4j
@Component
public class ApprovalSettleCardListener {

    private final ApprovalRepository approvals;
    private final KanbanRepository kanban;
    private final KanbanService kanbanService;

    public ApprovalSettleCardListener(ApprovalRepository approvals, KanbanRepository kanban,
            KanbanService kanbanService) {
        this.approvals = approvals;
        this.kanban = kanban;
        this.kanbanService = kanbanService;
    }

    @EventListener
    public void onDecided(ApprovalDecidedEvent event) {
        settle(event.getApprovalId(),
                event.getDecision() == ApprovalStatus.APPROVED ? KanbanStatus.DONE : KanbanStatus.CANCELLED,
                event.getDecision() == ApprovalStatus.APPROVED ? "ask approved" : "ask denied");
    }

    @EventListener
    public void onExpired(ApprovalExpiredEvent event) {
        settle(event.getApprovalId(), KanbanStatus.CANCELLED, "ask expired");
    }

    private void settle(UUID approvalId, KanbanStatus target, String comment) {
        try {
            Approval approval = approvals.findById(approvalId).orElse(null);
            if (approval == null || approval.getSource() != ApprovalSource.ACP_PERMISSION
                    || approval.getKanbanItemId() == null) {
                return;
            }
            KanbanItem card = kanban.findById(approval.getKanbanItemId()).orElse(null);
            if (card == null || card.getStatus() != KanbanStatus.REVIEW) {
                return; // the operator already moved it; never override a human action
            }
            kanbanService.transition(card.getId(), target, comment);
        } catch (RuntimeException e) {
            log.warn("Ask {} settled but its review card could not be settled: {}", approvalId, e.getMessage());
        }
    }
}
```

`KanbanReviewCardListener.linkReviewCard` — before linking/creating, skip an already-settled ask; `createCard` — enrich the text. The link step reads the `Approval` (status); add: `if (approval.getStatus() != ApprovalStatus.PENDING) return;`. In `createCard`, fetch the ACP row through the (newly injected) `AcpPermissionRequestRepository permissions`:

```java
        String toolName = permissions.findByApprovalId(approval.getId())
                .map(AcpPermissionRequest::getToolName).orElse("tool call");
        String arguments = permissions.findByApprovalId(approval.getId())
                .map(AcpPermissionRequest::getArgumentsJson).orElse(null);
        String excerpt = arguments == null ? null
                : arguments.length() > 200 ? arguments.substring(0, 200) + "..." : arguments;
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder()
                .title("Review: tool call - " + toolName + " (run " + approval.getRunId().toString().substring(0, 8) + ")")
                .description(excerpt)
                .status(KanbanStatus.REVIEW)
                .linkedRunId(approval.getRunId().toString())
                .build());
```

(Keep the rest of `createCard`/`linkReviewCard` as-is; the single ACP fetch can be hoisted.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="ApprovalSettleCardListenerTest,KanbanReviewCardListenerTest,KanbanListenerTransactionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/ApprovalSettleCardListener.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanReviewCardListener.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/ApprovalSettleCardListenerTest.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanReviewCardListenerTest.java
git commit -m "feat(kanban): settle tool-call cards with their asks; tool name and arguments on the card"
```

---

### Task 5: Notification flip + expiry text + type union

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/persistence/AriaNotificationRepository.java`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/NotificationService.java`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/listener/NotificationTriggerListener.java`
- Modify: `agent-control-tower/act-dashboard/src/types/index.ts` (`NotificationType` union)
- Modify: `agent-control-tower/act-dashboard/src/components/NotificationBell.tsx` (icon map)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/listener/NotificationTriggerListenerApprovalSettleTest.java` (new, Mockito)
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/NotificationBell.test.tsx` (extend)

**Interfaces:**
- Consumes: `ApprovalDecidedEvent`, `ApprovalExpiredEvent` (+`toolName`).
- Produces: `NotificationService.markRequestedReadForApproval(String approvalId)`; `AriaNotificationRepository.markApprovalRequestedRead(resourceId)` (bulk update, returns rows touched).

- [ ] **Step 1: Write the failing tests**

```java
package io.aria.conductor.aria.listener;

class NotificationTriggerListenerApprovalSettleTest {

    private NotificationService notifications;
    private NotificationTriggerListener listener;

    @BeforeEach
    void setUp() {
        notifications = Mockito.mock(NotificationService.class);
        listener = new NotificationTriggerListener(notifications);
    }

    @Test
    void decidedApprovalFlipsItsRequestedNotification() {
        UUID approvalId = UUID.randomUUID();
        listener.onApprovalDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.APPROVED));
        verify(notifications).markRequestedReadForApproval(approvalId.toString());
    }

    @Test
    void expiredApprovalFlipsTheRequestAndNamesTheTool() {
        UUID approvalId = UUID.randomUUID();
        listener.onApprovalExpired(new ApprovalExpiredEvent(this, approvalId, UUID.randomUUID(),
                "run ended", "run_agent"));
        verify(notifications).markRequestedReadForApproval(approvalId.toString());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).create(eq("approval.expired"), eq("Approval expired"),
                body.capture(), eq("APPROVAL"), eq(approvalId.toString()));
        assertThat(body.getValue()).contains("run_agent");
    }
}
```

`NotificationBell.test.tsx` — add a case asserting an `approval.expired` notification renders with its icon and does not crash the feed (mirror the existing `run.batch.completed` case).

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=NotificationTriggerListenerApprovalSettleTest`
Expected: compile failure — `onApprovalDecided` and `markRequestedReadForApproval` do not exist.
Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/NotificationBell.test.tsx`
Expected: FAIL on the new case.

- [ ] **Step 3: Write the implementation**

`AriaNotificationRepository` — mirror the exact entity/field naming of its existing `@Query` methods:

```java
    @Modifying
    @Query("update AriaNotification n set n.read = true " +
           "where n.type = 'approval.requested' and n.resourceId = :resourceId and n.read = false")
    int markApprovalRequestedRead(@Param("resourceId") String resourceId);
```

`NotificationService`:

```java
    /** D6: a settled ask's "waiting for your decision" notification stops being unread. */
    @Transactional
    public int markRequestedReadForApproval(String approvalId) {
        return notificationRepository.markApprovalRequestedRead(approvalId);
    }
```

`NotificationTriggerListener`:

```java
    @EventListener
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        notificationService.markRequestedReadForApproval(event.getApprovalId().toString());
    }

    @EventListener
    public void onApprovalExpired(ApprovalExpiredEvent event) {
        notificationService.markRequestedReadForApproval(event.getApprovalId().toString());
        notificationService.create("approval.expired",
                "Approval expired",
                "Approval " + event.getApprovalId() + " expired without a decision ("
                        + (event.getReason() != null ? event.getReason() : "no reason recorded") + ")."
                        + (event.getToolName() != null ? " Tool call skipped: " + event.getToolName() + "." : ""),
                "APPROVAL", event.getApprovalId().toString());
    }
```

(This replaces the existing `onApprovalExpired` body.)

`types/index.ts` union gains `| 'approval.expired'`. `NotificationBell.tsx` icon map gains `'approval.expired': '⌛'`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=NotificationTriggerListenerApprovalSettleTest`
Expected: PASS.
Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/NotificationBell.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/persistence/AriaNotificationRepository.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/NotificationService.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/listener/NotificationTriggerListener.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/listener/NotificationTriggerListenerApprovalSettleTest.java \
        agent-control-tower/act-dashboard/src/types/index.ts \
        agent-control-tower/act-dashboard/src/components/NotificationBell.tsx \
        agent-control-tower/act-dashboard/src/components/__tests__/NotificationBell.test.tsx
git commit -m "feat(aria): flip settled asks' notifications; expiry notice names the tool"
```

---

### Task 5b: Native web tools auto-approve (D5)

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/mcp/McpProperties.java` (default list + javadoc)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/mcp/PlatformMcpAutoApproval.java` (javadoc: the explicit list is now the provenance gate for native names)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/PermissionCoordinator.java` (`autoApprovalCovers`)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/mcp/PlatformMcpAutoApprovalTest.java` and `.../approval/PermissionCoordinatorTest.java` (extend)

**Interfaces:**
- Produces: a `NATIVE_TOOL` ask whose normalized name is on `aria.mcp.auto-approve-read-tools` auto-settles through the existing single-allow-once reply path; the platform prefix is no longer required (the operator's explicit listing is the provenance); an unlisted native ask keeps the per-call approval.

- [ ] **Step 1: Write the failing tests** — in `PermissionCoordinatorTest`, mirror its register/auto-approve fixture: a native ask named `WebSearch` (no `mcp__aria-conductor__` prefix) with one ALLOW_ONCE option registers APPROVED and delivers the reply; `SomeOtherTool` stays PENDING; `WebSearch` with zero/multiple ALLOW_ONCE options stays PENDING. In `PlatformMcpAutoApprovalTest`: the default list contains `WebSearch` and `WebFetch` (assert via the configured defaults, not a hardcoded copy of the whole list); `run_agent` is absent.

- [ ] **Step 2: RED run**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="PlatformMcpAutoApprovalTest,PermissionCoordinatorTest"`
Expected: the new assertions fail (native `WebSearch` stays PENDING today).

- [ ] **Step 3: Implementation**

`McpProperties.autoApproveReadTools` default list gains `"WebSearch", "WebFetch"` at the end of the existing `List.of(...)`; update the field javadoc: the list also names the cores' own read-only tools (the 2026-10-04 operator decision), and every name on it auto-settles only through the one-option allow-once path.

`PermissionCoordinator.autoApprovalCovers`:

```java
    private boolean autoApprovalCovers(NativePermission request) {
        if (!platformMcpAutoApproval.allows(request.toolName())) {
            return false;
        }
        if (request.target() == PermissionTarget.PLATFORM_MCP) {
            return true;
        }
        // A listed NATIVE_TOOL auto-settles only through the single allow-once
        // option its reply will name -- the exact shape the platform-MCP branch
        // already required. The operator's explicit listing is the provenance
        // gate for a bare native name (the CLI's own WebSearch/WebFetch carries
        // no platform prefix by construction).
        return request.target() == PermissionTarget.NATIVE_TOOL
                && singleAllowOnceOptionId(request.options()).isPresent();
    }
```

Update the method's javadoc: a native ask NOT on the list is never covered; a listed native ask is covered when it offers a single allow-once option (the same fail-closed selection the manual ALLOW_ONCE decision uses).

- [ ] **Step 4: GREEN run**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="PlatformMcpAutoApprovalTest,PermissionCoordinatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/mcp/McpProperties.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/mcp/PlatformMcpAutoApproval.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/PermissionCoordinator.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/mcp/PlatformMcpAutoApprovalTest.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/PermissionCoordinatorTest.java
git commit -m "feat(execution): auto-approve listed native read-only tools (WebSearch/WebFetch)"
```

---

### Task 6: Outcome chips and copy on the board

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/types/index.ts` (`KanbanItem`)
- Modify: `agent-control-tower/act-dashboard/src/components/ReviewPanels.tsx` (`ShortApprovalView`, line ~172)
- Modify: `agent-control-tower/act-dashboard/src/components/TaskDrawer.tsx`
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/ReviewPanels.test.tsx` (extend)

**Interfaces:**
- Consumes: `KanbanItem.runOutcome` from T1 (`'COMPLETED' | 'FAILED' | 'ACTIVE' | 'CANCELLED' | 'UNKNOWN' | null`).
- Produces: an outcome chip on REVIEW cards and outcome-aware copy where the old "Run completed" text rendered.

- [ ] **Step 1: Write the failing tests** — in `ReviewPanels.test.tsx`, mirror the existing fixtures: with `runOutcome: 'FAILED'` the card shows the failed chip and the rework copy; with `'COMPLETED'` the green chip and the sign-off copy; with `'ACTIVE'`/`null` no misleading completion claim.

- [ ] **Step 2: Run to verify RED**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/ReviewPanels.test.tsx`
Expected: FAIL (chip text not found).

- [ ] **Step 3: Implement** — types:

```ts
  runOutcome?: 'COMPLETED' | 'FAILED' | 'ACTIVE' | 'CANCELLED' | 'UNKNOWN' | null;
```

`ShortApprovalView` + `TaskDrawer`: render a chip from `runOutcome` with classes `chip ok|bad|busy|dim` (follow the file's existing chip class names; read the component first), and switch the copy: FAILED → "Run failed — rework or accept", COMPLETED → the existing sign-off text, ACTIVE → "Run still in progress", CANCELLED → "Run cancelled", UNKNOWN/null → no completion claim.

- [ ] **Step 4: Run to verify GREEN**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/ReviewPanels.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/types/index.ts \
        agent-control-tower/act-dashboard/src/components/ReviewPanels.tsx \
        agent-control-tower/act-dashboard/src/components/TaskDrawer.tsx \
        agent-control-tower/act-dashboard/src/components/__tests__/ReviewPanels.test.tsx
git commit -m "feat(dashboard): outcome chips and honest copy on review cards"
```

---

### Task 7: Batch decisions on the REVIEW column

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/KanbanBoard.tsx` (REVIEW column header, line ~302-316; `ConfirmDialog` from `./ConfirmDialog`)
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/KanbanBoard.test.tsx` (extend)

**Interfaces:**
- Consumes: `transitionKanbanItem(id, { status })` (api/kanban), `ConfirmDialog`.
- Produces: three header buttons — "Accept all completed" (status DONE), "Rework all failed" (status TODO), "Cancel all" (status CANCELLED) — each behind a confirm dialog stating the count (Rework: "will dispatch N new runs"); execution = sequential loop over the filtered cards with `Promise.allSettled`-style per-item results and a summary line.

- [ ] **Step 1: Write the failing tests** — in `KanbanBoard.test.tsx`: with 2 FAILED + 1 COMPLETED REVIEW cards, the header offers the three buttons with counts ("Accept all completed (1)", "Rework all failed (2)"); clicking Accept opens the confirm dialog stating "1 card"; confirming calls `transitionKanbanItem` once with `{ status: 'DONE' }`. Add a partial-failure case: one transition rejects → summary reports "1 failed" and the other still ran.

- [ ] **Step 2: Run to verify RED**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/KanbanBoard.test.tsx`
Expected: FAIL (buttons absent).

- [ ] **Step 3: Implement** — in the `isGate` column header block:

```tsx
{col.isGate && columnItems.length > 0 && (
  <div className="batch-actions">
    <button onClick={() => setBatchAction({ kind: 'ACCEPT', items: columnItems.filter((it) => it.runOutcome === 'COMPLETED') })}>
      Accept all completed ({columnItems.filter((it) => it.runOutcome === 'COMPLETED').length})
    </button>
    <button onClick={() => setBatchAction({ kind: 'REWORK', items: columnItems.filter((it) => it.runOutcome === 'FAILED') })}>
      Rework all failed ({columnItems.filter((it) => it.runOutcome === 'FAILED').length})
    </button>
    <button onClick={() => setBatchAction({ kind: 'CANCEL', items: columnItems })}>
      Cancel all ({columnItems.length})
    </button>
  </div>
)}
```

plus a `ConfirmDialog` whose message states the count (and for REWORK: "this will dispatch N new runs"), and the executor mapping ACCEPT→`{ status: 'DONE' }`, REWORK→`{ status: 'TODO' }`, CANCEL→`{ status: 'CANCELLED' }` — sequential `for` loop, per-item try/catch, then one summary line via the component's existing error/toast surface. Follow the file's state conventions (`useState`, TanStack mutations); disable the buttons while a batch runs; refresh the list after.

- [ ] **Step 4: Run to verify GREEN**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/KanbanBoard.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/KanbanBoard.tsx \
        agent-control-tower/act-dashboard/src/components/__tests__/KanbanBoard.test.tsx
git commit -m "feat(dashboard): card-layer batch decisions on the review column"
```

---

### Task 8: Review Queue rows tell the truth

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/ReviewQueue.tsx`
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/ReviewQueue.test.tsx` (extend)

**Interfaces:**
- Consumes: the queue already renders `toolName`/`NativePermissionKindPill`/`NativePermissionFacts` for native asks.
- Produces: REVIEW_REQUEST rows render an accurate label ("Review") and the ask `content` (which carries "Run … (FAILED)") instead of the generic null-reason sentence; native rows unchanged.

- [ ] **Step 1: Write the failing test** — a `REVIEW_REQUEST`-style approval (`requestedAs` native permission absent, `content` set, `reason` null) renders the content text and NOT "Awaiting human verification before tool execution proceeds."; a native ask keeps its pill + facts (regression guard).

- [ ] **Step 2: Run to verify RED**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/ReviewQueue.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement** — in the row body: when not a native permission, render `approval.content ?? approval.reason ?? 'Review requested — open the run for context.'` with the existing `desc` styling, and label the row `Review` (class `pill warn`) instead of the generic fallback sentence. Keep single decisions and the native rendering untouched.

- [ ] **Step 4: Run to verify GREEN**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/ReviewQueue.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/ReviewQueue.tsx \
        agent-control-tower/act-dashboard/src/components/__tests__/ReviewQueue.test.tsx
git commit -m "feat(dashboard): accurate review-request rows in the queue"
```

---

### Task 9: V66 data repair for stuck review asks

**Files:**
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V66__settle_stale_review_asks.sql`
- Test: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/ReviewAskRepairMigrationIntegrationTest.java` (new; mirror `RunDispatchGroupMigrationIntegrationTest` for context/fixture style)

**Interfaces:**
- Produces: every PENDING `LEGACY_GATE` ask whose card already sits in DONE/CANCELLED becomes `DENIED` with reason "settled by card state"; native (`ACP_PERMISSION`) rows are untouched.

- [ ] **Step 1: Write the failing IT** — mirror `RunDispatchGroupMigrationIntegrationTest`: boot the app context, insert (raw SQL, as that test does for its pre-state) one REVIEW card in DONE + one in REVIEW, each with a PENDING LEGACY ask, plus a PENDING ACP ask on the DONE card; assert after migration: DONE-card legacy ask → DENIED/"settled by card state"; REVIEW-card legacy ask → still PENDING; ACP ask → still PENDING.

- [ ] **Step 2: Run to verify RED**

Run: `cd agent-control-tower && mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test=ReviewAskRepairMigrationIntegrationTest -Djacoco.skip=true`
Expected: FAIL (no V66; the DONE-card ask is still PENDING).

- [ ] **Step 3: Write the migration**

```sql
-- Review-flow amendment: settle review asks whose card already left REVIEW
-- (DONE/CANCELLED) - the stale-ask class observed live 2026-10-03/04.
-- LEGACY_GATE only: native ACP permission asks own their lifecycle and must
-- never be decided by a card-state repair.
UPDATE approvals
SET status = 'DENIED',
    reason = 'settled by card state',
    decided_at = CURRENT_TIMESTAMP
WHERE status = 'PENDING'
  AND source = 'LEGACY_GATE'
  AND kanban_item_id IN (
      SELECT id FROM kanban_items WHERE status IN ('DONE', 'CANCELLED')
  );
```

- [ ] **Step 4: Run to verify GREEN**

Run: `cd agent-control-tower && mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test="ReviewAskRepairMigrationIntegrationTest,RunDispatchGroupMigrationIntegrationTest" -Djacoco.skip=true`
Expected: PASS (both).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/db/migration/V66__settle_stale_review_asks.sql \
        agent-control-tower/act-app/src/test/java/io/aria/conductor/app/ReviewAskRepairMigrationIntegrationTest.java
git commit -m "fix(kanban): repair stale review asks whose card already settled (V66)"
```

---

### Task 10: Regression + live verification

**Files:** none (verification task; evidence goes to `docs/reviews/`).

- [ ] **Step 1: Module regression**

Run: `cd agent-control-tower && mvn -B test -pl act-execution,act-aria,act-agent -Djacoco.skip=true`
Expected: all green (act-execution grows by the new tests; the full counts are pinned in the run's report).

- [ ] **Step 2: ITs**

Run: `cd agent-control-tower && mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test="KanbanTransitionIntegrityIntegrationTest,RunDispatchGroupMigrationIntegrationTest,ReviewAskRepairMigrationIntegrationTest,RunAdmissionIntegrationTest,KanbanAutoDispatchIntegrationTest" -Djacoco.skip=true`
Expected: all green.

- [ ] **Step 3: Frontend**

Run: `cd agent-control-tower/act-dashboard && npx vitest run && pnpm build`
Expected: all green, build clean.

- [ ] **Step 4: Live drill (stack from the current worktree, qoder/HOST agents as configured)**

1. Dispatch one research-heavy run (qoder/HOST agent, prompt asking it to web-search): assert `grep -c "Native permission request" .run/backend.log` does NOT grow (WebSearch auto-settles — no ask), and `GET /api/v1/approvals?status=PENDING` stays free of new rows.
2. Dispatch one `run_agent` ask and leave it unanswered past the bridge window: assert an `approval.expired` notification appears carrying the tool name, the tool-call card reached CANCELLED, and the original `approval.requested` notification flipped read (unread count does not grow).
3. Decide one ask live: assert its card reaches DONE and its notification flips.
4. Accept / Rework / Cancel one REVIEW card each through the dashboard batch buttons: cards move, the card's review asks settle (queue drains), FAILED/COMPLETED chips render per outcome.

- [ ] **Step 5: Evidence doc + commit**

Write `docs/reviews/2026-10-04-review-flow-ux-live-verification.md` with the captured outputs and any NOT-verified items, then commit it and the verification notes.

```bash
git add docs/reviews/2026-10-04-review-flow-ux-live-verification.md
git commit -m "docs(reviews): review-flow UX live verification"
```

---

## Self-Review

**Spec coverage:** D1 (failed cards labelled + split actions) → T6 (chips/copy) + T7 (actions); D2 (card-layer batch + sweep) → T2 + T7; D3 (confirm dialogs) → T7; D4 (client-side loop, no endpoint) → T7; spec backend 1 (`runOutcome`) → T1; backend 3 (repair migration) → T9; D5 (native web auto-approve) → Task 5b (between T5 and T6); D6 (settle propagation) → T3 + T4 + T5; D7 (ask context) → T4 (card) + T8 (queue). Testing section → each task + T10; live drill → T10.

**Placeholder scan:** no TBD/TODO placeholders; every step carries its code or a named template to mirror (test-fixture helper names in T3/T4 are explicitly flagged to adapt to the class's actual helpers).

**Type consistency:** `runOutcome` string union consistent across T1/T6; `approvePendingByKanbanItemId` signature identical in T2 repository and service; `ApprovalExpiredEvent` 5-arg constructor used in T3 (coordinator) and read in T4/T5; `markRequestedReadForApproval` name identical in T5 service and listener; `ApprovalSettleCardListener` constructor deps match T4 imports.
