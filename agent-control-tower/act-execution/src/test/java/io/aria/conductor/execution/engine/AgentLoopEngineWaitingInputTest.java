package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunInputReceivedEvent;
import io.aria.conductor.common.event.RunWaitingForInputEvent;
import io.aria.conductor.common.event.TurnCompletedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.runtime.RunInputCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The engine's waiting-input bookkeeping listeners (2026-10-05 Plan B task 6):
 * a parked run is persisted WAITING_INPUT with a linked CLARIFICATION ask, the
 * operator's input flips it back to RUNNING, intermediate turns accumulate
 * usage/iteration/trajectory exactly like the final turn, an externally
 * cancelled run is woken through the coordinator's sticky termination intent,
 * and completeRun settles any ask the loop never got to answer.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopEngineWaitingInputTest {

    @Mock RunRepository runRepository;
    @Mock ApprovalRepository approvalRepository;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock KanbanRepository kanbanRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RunInputCoordinator inputCoordinator;

    private AgentLoopEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AgentLoopEngine(
                runRepository, null /* AgentRepository */, null /* AdkProviderRegistry */,
                null /* SessionStateManager */, null /* ActionExecutionPipeline */,
                null /* CircuitBreaker */, null /* ApprovalGate */, null /* PermissionCoordinator */,
                null /* PromptCallRepository */, trajectoryRepository, null /* ToolCallRepository */,
                eventPublisher, null /* WorkflowService */, null /* WorkflowChainRepository */,
                null /* AgentToolResolver */, null /* AgentSkillResolver */, null /* ToolRegistry */,
                null /* KnowledgeContextProvider */, null /* WorkspaceManager */,
                null /* HarnessProfileService */, null /* ToolSteeringGuard */,
                approvalRepository, null /* TaskDeadlineProperties */,
                null /* coreExecutionServiceProvider */, null /* DoDService */,
                null /* KanbanService */, null /* coreRunLauncherProvider */,
                null /* RunAdmissionQueue */, kanbanRepository, inputCoordinator);
    }

    private static Run runIn(UUID runId, UUID agentId, RunStatus status) {
        Run run = new Run();
        run.setId(runId);
        run.setAgentId(agentId);
        run.setStatus(status);
        return run;
    }

    @Test
    void waitingEventPersistsWaitingInputAndCreatesTheLinkedClarificationAsk() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Run run = runIn(runId, agentId, RunStatus.RUNNING);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> inv.getArgument(0));
        KanbanItem card = new KanbanItem();
        card.setId("card-1");
        card.setLinkedRunId(runId.toString());
        when(kanbanRepository.findByLinkedRunId(runId.toString())).thenReturn(List.of(card));
        // Mirror JPA's @PrePersist: the persisted ask leaves save() with an id.
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> {
            Approval a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });
        engine.addToActiveContextsForTest(runId, agentId);

        engine.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which DB?"));

        ArgumentCaptor<Approval> ask = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(ask.capture());
        assertThat(ask.getValue().getAskType()).isEqualTo(Approval.AskType.QUESTION);
        assertThat(ask.getValue().getSource()).isEqualTo(ApprovalSource.CLARIFICATION);
        assertThat(ask.getValue().getKanbanItemId()).isEqualTo("card-1");
        assertThat(ask.getValue().getContent()).isEqualTo("Which DB?");
        assertThat(ask.getValue().getExpiresAt()).isNull();
        assertThat(run.getStatus()).isEqualTo(RunStatus.WAITING_INPUT);
        ArgumentCaptor<ApprovalRequestedEvent> published =
                ArgumentCaptor.forClass(ApprovalRequestedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().getRunId()).isEqualTo(runId);
        assertThat(published.getValue().getApprovalSource()).isEqualTo(ApprovalSource.CLARIFICATION.name());
    }

    @Test
    void inputReceivedFlipsTheRunBackToRunning() {
        UUID runId = UUID.randomUUID();
        Run run = runIn(runId, UUID.randomUUID(), RunStatus.WAITING_INPUT);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> inv.getArgument(0));
        engine.addToActiveContextsForTest(runId, run.getAgentId());

        engine.onRunInputReceived(new RunInputReceivedEvent(this, runId));

        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void turnCompletedAccumulatesUsageIterationAndTrajectory() {
        UUID runId = UUID.randomUUID();
        RunContext ctx = engine.addToActiveContextsForTest(runId, UUID.randomUUID());
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
    void externalCancelRecordsTheStickyTerminationIntentToWakeTheParkedRun() {
        UUID runId = UUID.randomUUID();
        engine.addToActiveContextsForTest(runId, UUID.randomUUID());

        engine.onRunCompletedExternally(new RunCompletedEvent(this, runId, UUID.randomUUID(), RunStatus.CANCELLED));

        verify(inputCoordinator).recordTerminationIntent(runId);
    }

    @Test
    void aNonCancelledCompletionNeverWakesTheCoordinator() {
        UUID runId = UUID.randomUUID();
        engine.addToActiveContextsForTest(runId, UUID.randomUUID());

        engine.onRunCompletedExternally(new RunCompletedEvent(this, runId, UUID.randomUUID(), RunStatus.COMPLETED));

        verify(inputCoordinator, org.mockito.Mockito.never()).recordTerminationIntent(any());
        verify(inputCoordinator, org.mockito.Mockito.never()).requestFinalize(any());
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
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        engine.completeRun(ctx, RunStatus.COMPLETED);

        ArgumentCaptor<Approval> saved = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(a -> {
            assertThat(a.getStatus()).isEqualTo(ApprovalStatus.DENIED);
            assertThat(a.getReason()).isEqualTo("finalized by operator");
            assertThat(a.getDecidedAt()).isNotNull();
        });
    }
}
