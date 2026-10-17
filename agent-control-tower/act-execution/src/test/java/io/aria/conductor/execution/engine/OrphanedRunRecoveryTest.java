package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.HarnessProfileService;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import io.aria.conductor.execution.runtime.TaskDeadlineProperties;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.circuit.CircuitBreaker;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.harness.ToolSteeringGuard;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.pipeline.ActionExecutionPipeline;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.tool.AgentSkillResolver;
import io.aria.conductor.execution.tool.AgentToolResolver;
import io.aria.conductor.execution.tool.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F7 regression: on backend restart, runs left in RUNNING or INITIALIZING are orphaned
 * (their execution thread died with the previous JVM). Startup recovery must mark them
 * FAILED with a clear message and publish RunCompletedEvent so downstream listeners
 * (workflow chainer, kanban, WS broadcast) reconcile instead of leaving chains stuck.
 */
@ExtendWith(MockitoExtension.class)
class OrphanedRunRecoveryTest {

    @Mock private RunRepository runRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AdkProviderRegistry adkProviderRegistry;
    @Mock private SessionStateManager sessionStateManager;
    @Mock private ActionExecutionPipeline actionPipeline;
    @Mock private CircuitBreaker circuitBreaker;
    @Mock private ApprovalGate approvalGate;
    @Mock private PromptCallRepository promptCallRepository;
    @Mock private SessionTrajectoryRepository trajectoryRepository;
    @Mock private ToolCallRepository toolCallRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowChainRepository workflowChainRepository;
    @Mock private AgentToolResolver agentToolResolver;
    @Mock private AgentSkillResolver agentSkillResolver;
    @Mock private ToolRegistry toolRegistry;
    @Mock private KnowledgeContextProvider knowledgeProvider;
    @Mock private WorkspaceManager workspaceManager;
    @Mock private HarnessProfileService harnessProfileService;
    @Mock private ToolSteeringGuard toolSteeringGuard;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private TaskDeadlineProperties taskDeadlineProperties;
    @Mock private org.springframework.beans.factory.ObjectProvider<CoreExecutionService> coreExecutionServiceProvider;
    @Mock private DoDService dodService;
    @Mock private KanbanService kanbanService;

    @InjectMocks
    private AgentLoopEngine engine;

    private Run run(RunStatus status) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setAgentId(UUID.randomUUID());
        run.setStatus(status);
        run.setCreatedAt(Instant.now());
        return run;
    }

    @Test
    void runningAndInitializingRuns_shouldBeMarkedFailedAndPublishEvent() {
        Run running = run(RunStatus.RUNNING);
        Run initializing = run(RunStatus.INITIALIZING);
        when(runRepository.findByStatusIn(List.of(
                RunStatus.RUNNING, RunStatus.INITIALIZING, RunStatus.WAITING_INPUT)))
                .thenReturn(List.of(running, initializing));

        engine.recoverOrphanedRuns();

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        for (Run r : saved.getAllValues()) {
            assertThat(r.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(r.getErrorMessage()).isEqualTo("Run orphaned by backend restart");
            assertThat(r.getCompletedAt()).isNotNull();
        }

        ArgumentCaptor<RunCompletedEvent> events = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
        List<RunCompletedEvent> published = events.getAllValues();
        assertThat(published).extracting(RunCompletedEvent::getRunId)
                .containsExactlyInAnyOrder(running.getId(), initializing.getId());
        assertThat(published).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(e.getAgentId()).isNotNull();
        });
    }

    @Test
    void noOrphanedRuns_shouldDoNothing() {
        when(runRepository.findByStatusIn(List.of(
                RunStatus.RUNNING, RunStatus.INITIALIZING, RunStatus.WAITING_INPUT)))
                .thenReturn(List.of());

        engine.recoverOrphanedRuns();

        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void waitingInputRunsAreAdjudicatedOnRestart() {
        // A run parked for operator input cannot be revived in-memory after a JVM
        // restart (its runtime died with the previous process), so startup
        // recovery must adjudicate it too instead of leaving it parked forever.
        Run parked = run(RunStatus.WAITING_INPUT);
        when(runRepository.findByStatusIn(List.of(
                RunStatus.RUNNING, RunStatus.INITIALIZING, RunStatus.WAITING_INPUT)))
                .thenReturn(List.of(parked));

        engine.recoverOrphanedRuns();

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getValue().getErrorMessage()).isEqualTo("Run orphaned by backend restart");
        assertThat(saved.getValue().getCompletedAt()).isNotNull();

        ArgumentCaptor<RunCompletedEvent> events = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue().getRunId()).isEqualTo(parked.getId());
        assertThat(events.getValue().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void waitingInputRunRecoveryAlsoSettlesItsPendingClarificationAsks() {
        // A parked run's runtime died with the previous JVM: recovery adjudicates
        // the run FAILED, and its PENDING CLARIFICATION ask can never be answered
        // anymore (/answer 409s -- the coordinator has no entry after the restart,
        // /decide refuses the source, housekeeping skips CLARIFICATION). Recovery
        // must therefore settle the ask the way completeRun does, with the honest
        // restart reason, so the operator's ReviewQueue row is resolvable.
        Run parked = run(RunStatus.WAITING_INPUT);
        when(runRepository.findByStatusIn(List.of(
                RunStatus.RUNNING, RunStatus.INITIALIZING, RunStatus.WAITING_INPUT)))
                .thenReturn(List.of(parked));
        Approval ask = Approval.builder().runId(parked.getId()).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION).source(ApprovalSource.CLARIFICATION).build();
        when(approvalRepository.findByRunIdAndStatusAndSource(
                parked.getId(), ApprovalStatus.PENDING, ApprovalSource.CLARIFICATION))
                .thenReturn(List.of(ask));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        engine.recoverOrphanedRuns();

        ArgumentCaptor<Approval> settled = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(settled.capture());
        assertThat(settled.getValue().getStatus()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(settled.getValue().getReason()).isEqualTo("run orphaned by restart");
        assertThat(settled.getValue().getDecidedAt()).isNotNull();
        // The run itself is still adjudicated exactly as before.
        assertThat(parked.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(parked.getErrorMessage()).isEqualTo("Run orphaned by backend restart");
    }
}
