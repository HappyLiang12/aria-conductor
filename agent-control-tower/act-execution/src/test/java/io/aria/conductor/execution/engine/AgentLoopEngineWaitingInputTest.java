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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
                runRepository, null /* AgentRepository */, null /* SessionStateManager */,
                null /* ApprovalGate */, null /* PermissionCoordinator */, trajectoryRepository,
                eventPublisher, null /* WorkflowService */, null /* WorkflowChainRepository */,
                null /* AgentSkillResolver */, null /* KnowledgeContextProvider */,
                null /* WorkspaceManager */, approvalRepository,
                null /* coreExecutionServiceProvider */, null /* DoDService */,
                null /* coreRunLauncherProvider */, null /* RunAdmissionQueue */,
                kanbanRepository, inputCoordinator);
        stubStatusWrites();
        stubCompletionWrites();
    }

    /** Mirrors the engine's private TERMINAL_STATUSES. */
    private static final List<RunStatus> TERMINAL_STATUSES = List.of(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

    /** Mirrors the repository's conditional update onto the shared in-memory run double. */
    private void stubStatusWrites() {
        lenient().when(runRepository.updateStatusUnlessTerminal(any(), any(), any(), anyList()))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    RunStatus status = invocation.getArgument(1);
                    List<RunStatus> terminal = invocation.getArgument(3);
                    return runRepository.findById(id)
                            .filter(current -> !terminal.contains(current.getStatus()))
                            .map(current -> {
                                current.setStatus(status);
                                return 1;
                            })
                            .orElse(0);
                });
    }

    /** Mirrors the repository's conditional completion update onto the shared in-memory run double. */
    private void stubCompletionWrites() {
        lenient().when(runRepository.completeIfNotTerminal(any(), any(), anyInt(), anyLong(),
                        any(), any(), any(), any(), any(), any(), anyList()))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    RunStatus status = invocation.getArgument(1);
                    Run current = runRepository.findById(id).orElse(null);
                    if (current == null || TERMINAL_STATUSES.contains(current.getStatus())) {
                        return 0;
                    }
                    current.setStatus(status);
                    current.setIterationCount(invocation.getArgument(2));
                    current.setTotalTokensUsed(invocation.getArgument(3));
                    current.setCompletedAt(invocation.getArgument(4));
                    String finalOutput = invocation.getArgument(5);
                    if (finalOutput != null) {
                        current.setFinalOutput(finalOutput);
                    }
                    String errorMessage = invocation.getArgument(6);
                    if (errorMessage != null) {
                        current.setErrorMessage(errorMessage);
                    }
                    String errorCode = invocation.getArgument(7);
                    if (errorCode != null) {
                        current.setErrorCode(errorCode);
                    }
                    String errorSurface = invocation.getArgument(8);
                    if (errorSurface != null) {
                        current.setErrorSurface(errorSurface);
                    }
                    current.setUpdatedAt(invocation.getArgument(9));
                    return 1;
                });
    }

    private record CompletionWrite(RunStatus status, String finalOutput, String errorMessage,
                                   String errorCode, String errorSurface) {
    }

    /** The exact payload of the one conditional completion write completeRun issued. */
    private CompletionWrite captureCompletionWrite(UUID runId, RunContext ctx) {
        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        ArgumentCaptor<String> finalOutput = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorCode = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorSurface = ArgumentCaptor.forClass(String.class);
        verify(runRepository).completeIfNotTerminal(eq(runId), status.capture(),
                eq(ctx.getIterationCount()), eq(ctx.getTotalTokensUsed()), any(Instant.class),
                finalOutput.capture(), errorMessage.capture(), errorCode.capture(),
                errorSurface.capture(), any(Instant.class), eq(TERMINAL_STATUSES));
        return new CompletionWrite(status.getValue(), finalOutput.getValue(), errorMessage.getValue(),
                errorCode.getValue(), errorSurface.getValue());
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
        engine.addToActiveContextsForTest(runId, run.getAgentId());

        engine.onRunInputReceived(new RunInputReceivedEvent(this, runId));

        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void waitingEventNeverOverwritesATerminalStatus() {
        // Cancel-vs-park residue: the operator's cancel committed CANCELLED and
        // the waiting listener's direct write races in behind it. A terminal
        // state must never be overwritten by any direct status write — otherwise
        // completeRun's external-cancel guard misses and the run ends COMPLETED
        // despite the cancel.
        UUID runId = UUID.randomUUID();
        Run run = runIn(runId, UUID.randomUUID(), RunStatus.CANCELLED);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        engine.addToActiveContextsForTest(runId, run.getAgentId());

        engine.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which DB?"));

        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        verify(runRepository, org.mockito.Mockito.never()).save(any(Run.class));
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
        when(runRepository.findById(runId)).thenReturn(Optional.of(
                runIn(runId, ctx.getAgentId(), RunStatus.RUNNING)));
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

    @Test
    void completeRunSettlesCancelledWaitingRunAsksWithTheCancelledReason() {
        UUID runId = UUID.randomUUID();
        RunContext ctx = engine.addToActiveContextsForTest(runId, UUID.randomUUID());
        Approval ask = Approval.builder().runId(runId).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION).source(ApprovalSource.CLARIFICATION).build();
        when(approvalRepository.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING, ApprovalSource.CLARIFICATION))
                .thenReturn(List.of(ask));
        when(runRepository.findById(runId)).thenReturn(Optional.of(
                runIn(runId, ctx.getAgentId(), RunStatus.RUNNING)));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        engine.completeRun(ctx, RunStatus.CANCELLED);

        // A cancelled parked run's question is not "finalized by operator": the
        // settle reason names the honest ending (drill step 2 showed the broad
        // sweep's "Run cancelled" expiry winning over a finalize-only message).
        ArgumentCaptor<Approval> saved = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(a -> {
            assertThat(a.getStatus()).isEqualTo(ApprovalStatus.DENIED);
            assertThat(a.getReason()).isEqualTo("run cancelled");
        });
    }

    @Test
    void completeRunCancelledPersistsTheRecordedTerminationReason() {
        UUID runId = UUID.randomUUID();
        RunContext ctx = engine.addToActiveContextsForTest(runId, UUID.randomUUID());
        when(runRepository.findById(runId)).thenReturn(Optional.of(
                runIn(runId, ctx.getAgentId(), RunStatus.RUNNING)));
        when(approvalRepository.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING, ApprovalSource.CLARIFICATION))
                .thenReturn(List.of());
        when(inputCoordinator.consumeTerminationFailure(runId))
                .thenReturn(new RunInputCoordinator.TerminationFailure(
                        "Run deadline reached at 2026-10-20T12:00:00Z"
                                + " (aria.tasks.deadline-minutes in Configure > System Config)."
                                + " Re-dispatch with a longer deadline or split the task.",
                        "RUN_DEADLINE_REACHED", "SYSTEM_CONFIG"));

        engine.completeRun(ctx, RunStatus.CANCELLED);

        CompletionWrite written = captureCompletionWrite(runId, ctx);
        assertThat(written.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(written.errorMessage())
                .isEqualTo("Run deadline reached at 2026-10-20T12:00:00Z"
                        + " (aria.tasks.deadline-minutes in Configure > System Config)."
                        + " Re-dispatch with a longer deadline or split the task.");
        assertThat(written.errorCode()).isEqualTo("RUN_DEADLINE_REACHED");
        assertThat(written.errorSurface()).isEqualTo("SYSTEM_CONFIG");

        // The dashboard reads the same repair identity from the completion event.
        ArgumentCaptor<RunCompletedEvent> published = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().getErrorCode()).isEqualTo("RUN_DEADLINE_REACHED");
        assertThat(published.getValue().getErrorSurface()).isEqualTo("SYSTEM_CONFIG");
        assertThat(published.getValue().getErrorMessage()).contains("Run deadline reached");
    }

    @Test
    void completeRunCompletedConsumesButDoesNotPersistAStaleTerminationFailure() {
        UUID runId = UUID.randomUUID();
        RunContext ctx = engine.addToActiveContextsForTest(runId, UUID.randomUUID());
        Run run = runIn(runId, ctx.getAgentId(), RunStatus.RUNNING);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(approvalRepository.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING, ApprovalSource.CLARIFICATION))
                .thenReturn(List.of());
        // A refused deadline stop leaves the run free to finish normally; the recorded
        // intent must be consumed (not leak) and must not label the COMPLETED run.
        when(inputCoordinator.consumeTerminationFailure(runId))
                .thenReturn(new RunInputCoordinator.TerminationFailure(
                        "Run deadline reached at 2026-10-20T12:00:00Z",
                        "RUN_DEADLINE_REACHED", "SYSTEM_CONFIG"));

        engine.completeRun(ctx, RunStatus.COMPLETED);

        verify(inputCoordinator).consumeTerminationFailure(runId);
        CompletionWrite written = captureCompletionWrite(runId, ctx);
        assertThat(written.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(written.errorMessage()).isNull();
        assertThat(written.errorCode()).isNull();
        assertThat(written.errorSurface()).isNull();

        ArgumentCaptor<RunCompletedEvent> published = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().getErrorMessage()).isNull();
        assertThat(published.getValue().getErrorCode()).isNull();
        assertThat(published.getValue().getErrorSurface()).isNull();
    }

    @Test
    void completeRunFailureWithoutAMessagePersistsAnHonestFallbackInsteadOfNull() {
        UUID runId = UUID.randomUUID();
        RunContext ctx = engine.addToActiveContextsForTest(runId, UUID.randomUUID());
        ctx.addError((String) null);
        when(runRepository.findById(runId)).thenReturn(Optional.of(
                runIn(runId, ctx.getAgentId(), RunStatus.RUNNING)));
        when(approvalRepository.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING, ApprovalSource.CLARIFICATION))
                .thenReturn(List.of());

        engine.completeRun(ctx, RunStatus.FAILED);

        CompletionWrite written = captureCompletionWrite(runId, ctx);
        assertThat(written.status()).isEqualTo(RunStatus.FAILED);
        assertThat(written.errorMessage()).isEqualTo(
                "Run failed without an error message; check the backend log for the failure details.");
    }

    @Test
    void completeRunBoundsAnOversizedFailureBeforePersistingIt() {
        UUID runId = UUID.randomUUID();
        RunContext ctx = engine.addToActiveContextsForTest(runId, UUID.randomUUID());
        ctx.addError("x".repeat(5000));
        when(runRepository.findById(runId)).thenReturn(Optional.of(
                runIn(runId, ctx.getAgentId(), RunStatus.RUNNING)));
        when(approvalRepository.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING, ApprovalSource.CLARIFICATION))
                .thenReturn(List.of());

        engine.completeRun(ctx, RunStatus.FAILED);

        CompletionWrite written = captureCompletionWrite(runId, ctx);
        // error_message is VARCHAR(2000): bounded below the column limit with an
        // honest marker naming exactly how much text was dropped.
        assertThat(written.errorMessage())
                .hasSize(1887)
                .endsWith("... [truncated, 3150 more characters]");
    }

    @Test
    void completeRun_whenAnotherWriterOwnsTheTerminalTransition_publishesNoCompletionEvent() {
        // I1: the conditional update lost because a cancel (or another completion)
        // already committed the terminal transition and published its event. This
        // completion owns neither the row nor the event.
        UUID runId = UUID.randomUUID();
        RunContext ctx = engine.addToActiveContextsForTest(runId, UUID.randomUUID());
        // doReturn (not when) so the setup's mirroring answer is not executed while stubbing.
        doReturn(0).when(runRepository).completeIfNotTerminal(any(), any(), anyInt(), anyLong(),
                any(), any(), any(), any(), any(), any(), anyList());
        when(approvalRepository.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING, ApprovalSource.CLARIFICATION))
                .thenReturn(List.of());

        engine.completeRun(ctx, RunStatus.COMPLETED);

        verify(runRepository).completeIfNotTerminal(eq(runId), eq(RunStatus.COMPLETED),
                eq(ctx.getIterationCount()), eq(ctx.getTotalTokensUsed()), any(Instant.class),
                isNull(), isNull(), isNull(), isNull(), any(Instant.class), eq(TERMINAL_STATUSES));
        verify(eventPublisher, never()).publishEvent(any(RunCompletedEvent.class));
    }
}
