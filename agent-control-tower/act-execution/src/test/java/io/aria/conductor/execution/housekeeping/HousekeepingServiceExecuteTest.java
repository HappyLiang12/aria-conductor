package io.aria.conductor.execution.housekeeping;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.event.HousekeepingProgressEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.repository.AgentSessionRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Housekeeping S2 (execute): FK-safe chunked purge, per-item receipts,
 * idempotency, single-flight, category side effects and event emission.
 */
@ExtendWith(MockitoExtension.class)
class HousekeepingServiceExecuteTest {

    @Mock RunRepository runRepository;
    @Mock KanbanRepository kanbanRepository;
    @Mock AgentRepository agentRepository;
    @Mock ApprovalRepository approvalRepository;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ToolCallRepository toolCallRepository;
    @Mock PromptCallRepository promptCallRepository;
    @Mock AgentSessionRepository agentSessionRepository;
    @Mock KanbanService kanbanService;
    @Mock AgentService agentService;
    @Mock RunService runService;
    @Mock ApprovalGate approvalGate;
    @Mock ApplicationEventPublisher eventPublisher;

    /** Executes the callback directly (no real transaction needed in unit tests). */
    TransactionTemplate tx = new TransactionTemplate(new NoopTransactionManager());

    HousekeepingService service;

    @BeforeEach
    void setUp() {
        service = new HousekeepingService(runRepository, kanbanRepository, agentRepository,
                approvalRepository, trajectoryRepository, toolCallRepository, promptCallRepository,
                agentSessionRepository, kanbanService, agentService, runService, approvalGate,
                eventPublisher, tx);
        lenient().when(runRepository.findByStatusIn(anyList())).thenReturn(List.of());
        lenient().when(runRepository.findByStatus(any())).thenReturn(List.of());
        lenient().when(kanbanRepository.findByStatus(any())).thenReturn(List.of());
        lenient().when(agentRepository.findAll()).thenReturn(List.of());
        lenient().when(approvalRepository.findByStatus(any())).thenReturn(List.of());
    }

    private CategoryReceipt receipt(HousekeepingReceipt r, String key) {
        return r.categories().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void kanbanCategory_perItemReceiptClearedSkippedFailed() {
        KanbanItem ok = new KanbanItem();
        ok.setId("k-ok");
        ok.setStatus(KanbanStatus.DONE);
        KanbanItem gone = new KanbanItem();
        gone.setId("k-gone");
        gone.setStatus(KanbanStatus.DONE);
        KanbanItem boom = new KanbanItem();
        boom.setId("k-boom");
        boom.setStatus(KanbanStatus.CANCELLED);
        when(kanbanRepository.findByStatus(KanbanStatus.DONE)).thenReturn(List.of(ok, gone));
        when(kanbanRepository.findByStatus(KanbanStatus.CANCELLED)).thenReturn(List.of(boom));
        Mockito.doNothing().when(kanbanService).delete("k-ok");
        Mockito.doThrow(new RuntimeException("db hiccup")).when(kanbanService).delete("k-boom");
        // k-gone vanished between scan and execute (idempotency): delete throws not-found
        Mockito.doThrow(new IllegalArgumentException("KanbanItem not found: k-gone"))
                .when(kanbanService).delete("k-gone");

        HousekeepingReceipt r = service.execute(
                new HousekeepingRequest(List.of("kanban"), false, Exclusions.empty(), true));

        CategoryReceipt kanban = receipt(r, "kanban");
        assertThat(kanban.cleared()).as("receipt=%s", r).isEqualTo(1);
        assertThat(kanban.skipped()).isEqualTo(1);
        assertThat(kanban.failed()).isEqualTo(1);
    }

    @Test
    void runsPurge_chunksAt200WithChildrenBeforeRuns() {
        Instant old = Instant.now().minus(30, ChronoUnit.HOURS);
        List<Run> runs = java.util.stream.IntStream.range(0, 201)
                .mapToObj(i -> {
                    Run run = new Run();
                    run.setId(UUID.randomUUID());
                    run.setStatus(RunStatus.COMPLETED);
                    run.setCreatedAt(old);
                    run.setUpdatedAt(old);
                    return run;
                }).toList();
        when(runRepository.findByStatusIn(anyList())).thenReturn(runs);

        HousekeepingReceipt r = service.execute(
                new HousekeepingRequest(List.of("runs"), false, Exclusions.empty(), true));

        assertThat(receipt(r, "runs").cleared()).isEqualTo(201);
        // 201 ids → two chunks of ≤200
        verify(trajectoryRepository, times(2)).deleteByRunIdInBulk(anyList());
        verify(runRepository, times(2)).deleteByIdInBulk(anyList());
        // children deleted before the parent runs within each chunk
        InOrder order = Mockito.inOrder(trajectoryRepository, toolCallRepository,
                promptCallRepository, approvalRepository, agentSessionRepository, runRepository);
        order.verify(trajectoryRepository).deleteByRunIdInBulk(anyList());
        order.verify(toolCallRepository).deleteByRunIdInBulk(anyList());
        order.verify(promptCallRepository).deleteByRunIdInBulk(anyList());
        order.verify(approvalRepository).deleteByRunIdInBulk(anyList());
        order.verify(agentSessionRepository).deleteByRunIdInBulk(anyList());
        order.verify(runRepository).deleteByIdInBulk(anyList());
    }

    @Test
    void stuckCategory_cancelsRunAndExpiresItsApprovals() {
        Instant now = Instant.now();
        Run stuck = new Run();
        stuck.setId(UUID.randomUUID());
        stuck.setStatus(RunStatus.PAUSED);
        stuck.setCreatedAt(now.minus(92, ChronoUnit.MINUTES));
        stuck.setUpdatedAt(now.minus(92, ChronoUnit.MINUTES));
        when(runRepository.findByStatus(RunStatus.PAUSED)).thenReturn(List.of(stuck));
        Approval pending = new Approval();
        pending.setId(UUID.randomUUID());
        pending.setRunId(stuck.getId());
        pending.setStatus(ApprovalStatus.PENDING);
        pending.setRequestedAt(now.minus(92, ChronoUnit.MINUTES));
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of(pending));

        service.execute(new HousekeepingRequest(List.of("stuck"), true, Exclusions.empty(), true));

        verify(runService).cancelRun(stuck.getId());
        verify(approvalGate).cancelAllPendingForRun(stuck.getId());
    }

    @Test
    void approvalsCategory_decidesRejected() {
        Instant now = Instant.now();
        Run terminal = new Run();
        terminal.setId(UUID.randomUUID());
        terminal.setStatus(RunStatus.FAILED);
        terminal.setCreatedAt(now.minus(26, ChronoUnit.HOURS));
        terminal.setUpdatedAt(now.minus(26, ChronoUnit.HOURS));
        Approval old = new Approval();
        old.setId(UUID.randomUUID());
        old.setRunId(terminal.getId());
        old.setStatus(ApprovalStatus.PENDING);
        old.setRequestedAt(now.minus(25, ChronoUnit.HOURS));
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of(old));
        when(runRepository.findById(terminal.getId())).thenReturn(java.util.Optional.of(terminal));

        service.execute(new HousekeepingRequest(List.of("approvals"), false, Exclusions.empty(), true));

        verify(approvalGate).decideApproval(eq(old.getId()), eq(false), anyString());
    }

    @Test
    void agentsCategory_retiresTargets() {
        Agent e2e = Agent.builder().id(UUID.randomUUID()).name("e2e-agent-9").role("dev")
                .healthStatus(HealthStatus.HEALTHY).build();
        when(agentRepository.findAll()).thenReturn(List.of(e2e));

        service.execute(new HousekeepingRequest(List.of("agents"), false, Exclusions.empty(), true));

        verify(agentService).retireAgent(e2e.getId());
    }

    @Test
    void idempotentSecondExecute_clearsNothing() {
        Instant old = Instant.now().minus(30, ChronoUnit.HOURS);
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setStatus(RunStatus.COMPLETED);
        run.setCreatedAt(old);
        run.setUpdatedAt(old);
        when(runRepository.findByStatusIn(anyList())).thenReturn(List.of(run));

        HousekeepingReceipt first = service.execute(
                new HousekeepingRequest(List.of("runs"), false, Exclusions.empty(), true));
        assertThat(receipt(first, "runs").cleared()).isEqualTo(1);

        // state now empty → re-run is a no-op, no throw
        when(runRepository.findByStatusIn(anyList())).thenReturn(List.of());
        HousekeepingReceipt second = service.execute(
                new HousekeepingRequest(List.of("runs"), false, Exclusions.empty(), true));
        assertThat(receipt(second, "runs").cleared()).isZero();
    }

    @Test
    void singleFlight_secondConcurrentExecuteRejected() throws Exception {
        KanbanItem item = new KanbanItem();
        item.setId("k-slow");
        item.setStatus(KanbanStatus.DONE);
        when(kanbanRepository.findByStatus(KanbanStatus.DONE)).thenReturn(List.of(item));
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(inv -> {
            inFlight.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(kanbanService).delete("k-slow");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Object> secondOutcome = new AtomicReference<>();
        try {
            pool.submit(() -> service.execute(
                    new HousekeepingRequest(List.of("kanban"), false, Exclusions.empty(), true)));
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> {
                try {
                    secondOutcome.set(service.execute(
                            new HousekeepingRequest(List.of("kanban"), false, Exclusions.empty(), true)));
                } catch (Exception e) {
                    secondOutcome.set(e);
                }
            });
            Thread.sleep(300);
            assertThat(secondOutcome.get()).isInstanceOf(IllegalStateException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void emitsProgressPerCategoryAndFinalAuditEvent() {
        KanbanItem done = new KanbanItem();
        done.setId("k-1");
        done.setStatus(KanbanStatus.DONE);
        when(kanbanRepository.findByStatus(KanbanStatus.DONE)).thenReturn(List.of(done));

        service.execute(new HousekeepingRequest(List.of("kanban"), false, Exclusions.empty(), true));

        ArgumentCaptor<HousekeepingProgressEvent> progress =
                ArgumentCaptor.forClass(HousekeepingProgressEvent.class);
        verify(eventPublisher, times(1)).publishEvent(progress.capture());
        assertThat(progress.getValue().getCategory()).isEqualTo("kanban");
        assertThat(progress.getValue().getCleared()).isEqualTo(1);

        ArgumentCaptor<AuditLogEvent> audit = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(eventPublisher).publishEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo("HOUSEKEEPING_EXECUTED");
    }

    @Test
    void executeWithoutConfirm_rejected() {
        assertThatThrownBy(() -> service.execute(
                new HousekeepingRequest(List.of("kanban"), false, Exclusions.empty(), false)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(kanbanService, never()).delete(anyString());
    }

    /** Minimal transaction manager so TransactionTemplate runs callbacks inline. */
    static class NoopTransactionManager
            extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }
    }
}
