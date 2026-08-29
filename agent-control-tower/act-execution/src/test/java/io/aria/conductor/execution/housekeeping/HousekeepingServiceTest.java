package io.aria.conductor.execution.housekeeping;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategorySummary;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Housekeeping S2 (scan): the five category rules, boundaries, exclusions and
 * the bounded preview — all derived from existing repository queries.
 */
@ExtendWith(MockitoExtension.class)
class HousekeepingServiceTest {

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
    @Mock TransactionTemplate transactionTemplate;

    HousekeepingService service;

    @BeforeEach
    void setUp() {
        service = new HousekeepingService(runRepository, kanbanRepository, agentRepository,
                approvalRepository, trajectoryRepository, toolCallRepository, promptCallRepository,
                agentSessionRepository, kanbanService, agentService, runService, approvalGate,
                eventPublisher, transactionTemplate);
        lenient().when(runRepository.findByStatusIn(anyList())).thenReturn(List.of());
        lenient().when(runRepository.findByStatus(any())).thenReturn(List.of());
        lenient().when(kanbanRepository.findByStatus(any())).thenReturn(List.of());
        lenient().when(agentRepository.findAll()).thenReturn(List.of());
        lenient().when(approvalRepository.findByStatus(any())).thenReturn(List.of());
    }

    private Run run(UUID id, RunStatus status, Instant createdAt) {
        Run r = new Run();
        r.setId(id);
        r.setStatus(status);
        r.setCreatedAt(createdAt);
        r.setUpdatedAt(createdAt);
        r.setAgentId(UUID.randomUUID());
        return r;
    }

    private CategorySummary cat(ScanResult res, String key) {
        return res.categories().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void runsCategory_onlyTerminalOlderThan24h() {
        Instant now = Instant.now();
        UUID old = UUID.randomUUID(), fresh = UUID.randomUUID(), exact = UUID.randomUUID();
        when(runRepository.findByStatusIn(anyList())).thenReturn(List.of(
                run(old, RunStatus.COMPLETED, now.minus(25, ChronoUnit.HOURS)),
                run(fresh, RunStatus.FAILED, now.minus(1, ChronoUnit.HOURS)),
                // 30s shy of 24h old: must stay excluded despite scan-time clock drift
                run(exact, RunStatus.CANCELLED, now.minus(24, ChronoUnit.HOURS).plusSeconds(30))));

        ScanResult res = service.scan(true, Exclusions.empty());

        CategorySummary runs = cat(res, "runs");
        assertThat(runs.count()).isEqualTo(1);
        assertThat(runs.preview()).extracting(HousekeepingModel.CategoryItem::id)
                .containsExactly(old.toString());
    }

    @Test
    void stuckCategory_pausedOver30mWithPendingApproval_onlyWhenIncluded() {
        Instant now = Instant.now();
        UUID stuckOld = UUID.randomUUID(), stuckNew = UUID.randomUUID();
        when(runRepository.findByStatus(RunStatus.PAUSED)).thenReturn(List.of(
                run(stuckOld, RunStatus.PAUSED, now.minus(92, ChronoUnit.MINUTES)),
                run(stuckNew, RunStatus.PAUSED, now.minus(10, ChronoUnit.MINUTES))));
        Approval pending = new Approval();
        pending.setId(UUID.randomUUID());
        pending.setRunId(stuckOld);
        pending.setStatus(ApprovalStatus.PENDING);
        pending.setRequestedAt(now.minus(92, ChronoUnit.MINUTES));
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of(pending));

        ScanResult with = service.scan(true, Exclusions.empty());
        assertThat(cat(with, "stuck").count()).isEqualTo(1);

        ScanResult without = service.scan(false, Exclusions.empty());
        assertThat(cat(without, "stuck").count()).isZero();
    }

    @Test
    void kanbanCategory_doneAndCancelled() {
        KanbanItem done = new KanbanItem();
        done.setId("k-done");
        done.setStatus(KanbanStatus.DONE);
        done.setTitle("done card");
        KanbanItem cancelled = new KanbanItem();
        cancelled.setId("k-can");
        cancelled.setStatus(KanbanStatus.CANCELLED);
        cancelled.setTitle("cancelled card");
        when(kanbanRepository.findByStatus(KanbanStatus.DONE)).thenReturn(List.of(done));
        when(kanbanRepository.findByStatus(KanbanStatus.CANCELLED)).thenReturn(List.of(cancelled));

        ScanResult res = service.scan(true, Exclusions.empty());

        CategorySummary kanban = cat(res, "kanban");
        assertThat(kanban.count()).isEqualTo(2);
        assertThat(kanban.preview()).extracting(HousekeepingModel.CategoryItem::id)
                .containsExactlyInAnyOrder("k-done", "k-can");
    }

    @Test
    void agentsCategory_e2ePrefixOrUnhealthy_butNotAlreadyRetired() {
        Agent e2e = Agent.builder().id(UUID.randomUUID()).name("e2e-agent-1").role("dev")
                .healthStatus(HealthStatus.HEALTHY).build();
        Agent keeper = Agent.builder().id(UUID.randomUUID()).name("SDD BA Agent").role("ba")
                .healthStatus(HealthStatus.HEALTHY).build();
        Agent retired = Agent.builder().id(UUID.randomUUID()).name("e2e-agent-old").role("dev")
                .healthStatus(HealthStatus.RETIRED).build();
        Agent unhealthy = Agent.builder().id(UUID.randomUUID()).name("prod-agent").role("qa")
                .healthStatus(HealthStatus.UNHEALTHY).build();
        when(agentRepository.findAll()).thenReturn(List.of(e2e, keeper, retired, unhealthy));

        ScanResult res = service.scan(true, Exclusions.empty());

        CategorySummary agents = cat(res, "agents");
        assertThat(agents.count()).isEqualTo(2);
        assertThat(agents.preview()).extracting(HousekeepingModel.CategoryItem::id)
                .containsExactlyInAnyOrder(e2e.getId().toString(), unhealthy.getId().toString());
    }

    @Test
    void approvalsCategory_pendingOver24hWithNonActiveRun() {
        Instant now = Instant.now();
        UUID terminalRun = UUID.randomUUID(), activeRun = UUID.randomUUID();
        Approval oldTerminal = new Approval();
        oldTerminal.setId(UUID.randomUUID());
        oldTerminal.setRunId(terminalRun);
        oldTerminal.setStatus(ApprovalStatus.PENDING);
        oldTerminal.setRequestedAt(now.minus(25, ChronoUnit.HOURS));
        Approval oldActive = new Approval();
        oldActive.setId(UUID.randomUUID());
        oldActive.setRunId(activeRun);
        oldActive.setStatus(ApprovalStatus.PENDING);
        oldActive.setRequestedAt(now.minus(25, ChronoUnit.HOURS));
        Approval fresh = new Approval();
        fresh.setId(UUID.randomUUID());
        fresh.setRunId(terminalRun);
        fresh.setStatus(ApprovalStatus.PENDING);
        fresh.setRequestedAt(now.minus(1, ChronoUnit.HOURS));
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING))
                .thenReturn(List.of(oldTerminal, oldActive, fresh));
        when(runRepository.findById(terminalRun))
                .thenReturn(java.util.Optional.of(run(terminalRun, RunStatus.FAILED, now.minus(26, ChronoUnit.HOURS))));
        when(runRepository.findById(activeRun))
                .thenReturn(java.util.Optional.of(run(activeRun, RunStatus.RUNNING, now)));

        ScanResult res = service.scan(true, Exclusions.empty());

        CategorySummary approvals = cat(res, "approvals");
        assertThat(approvals.count()).isEqualTo(1);
        assertThat(approvals.preview()).extracting(HousekeepingModel.CategoryItem::id)
                .containsExactly(oldTerminal.getId().toString());
    }

    @Test
    void exclusions_filterEveryCategory() {
        Instant now = Instant.now();
        UUID keptOut = UUID.randomUUID();
        when(runRepository.findByStatusIn(anyList())).thenReturn(List.of(
                run(keptOut, RunStatus.COMPLETED, now.minus(30, ChronoUnit.HOURS))));
        KanbanItem done = new KanbanItem();
        done.setId("k-keep");
        done.setStatus(KanbanStatus.DONE);
        when(kanbanRepository.findByStatus(KanbanStatus.DONE)).thenReturn(List.of(done));

        ScanResult res = service.scan(true, new Exclusions(
                List.of(keptOut.toString()), List.of("k-keep"), List.of(), List.of()));

        assertThat(cat(res, "runs").count()).isZero();
        assertThat(cat(res, "kanban").count()).isZero();
    }

    @Test
    void previewBoundedTo20PerCategory() {
        Instant now = Instant.now();
        when(runRepository.findByStatusIn(anyList())).thenReturn(
                java.util.stream.IntStream.range(0, 25)
                        .mapToObj(i -> run(UUID.randomUUID(), RunStatus.COMPLETED, now.minus(30, ChronoUnit.HOURS)))
                        .toList());

        ScanResult res = service.scan(true, Exclusions.empty());

        assertThat(cat(res, "runs").count()).isEqualTo(25);
        assertThat(cat(res, "runs").preview()).hasSize(20);
    }
}
