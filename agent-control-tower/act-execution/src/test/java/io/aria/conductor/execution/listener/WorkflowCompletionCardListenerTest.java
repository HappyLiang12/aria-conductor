package io.aria.conductor.execution.listener;

import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.event.WorkflowCancelledEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanPriority;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.kanban.KanbanTransitionService;
import io.aria.conductor.execution.kanban.TransitionRequest;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F24: when the chain completes, its finished step cards must leave REVIEW for DONE,
 * unless a human decision is still open on the card (a pending ask keeps it in REVIEW
 * — REVIEW then means "needs a human"). The live run left cards 9d57be41 / d3e7f97c /
 * c61d0bb7 in REVIEW with pendingAskCount=0 after the chain reached COMPLETED.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowCompletionCardListenerTest {

    @Mock private WorkflowService workflowService;
    @Mock private KanbanRepository kanbanRepository;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private KanbanTransitionService kanbanTransitionService;

    private WorkflowCompletionCardListener listener;

    private final UUID chainId = UUID.randomUUID();
    private final UUID baRunId = UUID.randomUUID();
    private final UUID qaRunId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        listener = new WorkflowCompletionCardListener(workflowService, kanbanRepository,
                approvalRepository, kanbanTransitionService, transactionManager, Runnable::run);
    }

    @Test
    void completedChain_movesEveryReviewCardWithoutAPendingAskToDone() {
        givenChainSteps(baRunId, qaRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        KanbanItem qaCard = card("qa-card", qaRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));
        when(kanbanRepository.findByLinkedRunId(qaRunId.toString())).thenReturn(List.of(qaCard));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "ba-card"))
                .thenReturn(List.of());
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "qa-card"))
                .thenReturn(List.of());

        listener.onWorkflowCompleted(completedEvent());

        ArgumentCaptor<TransitionRequest> request = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("ba-card"), request.capture());
        verify(kanbanTransitionService).transition(eq("qa-card"), any());
        assertThat(request.getValue().getStatus()).isEqualTo(KanbanStatus.DONE);
    }

    @Test
    void completedChain_settlesCardsOfSupersededAttemptsToo() {
        // R6: a DEFECT/SPEC_GAP reschedule overwrites step.runId, so the superseded
        // attempt's card stays in REVIEW unless every historical id is settled too.
        UUID supersededRunId = UUID.randomUUID();
        givenChainSteps(supersededRunId, baRunId);
        KanbanItem supersededCard = card("superseded-card", supersededRunId, KanbanStatus.REVIEW);
        KanbanItem currentCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(supersededRunId.toString()))
                .thenReturn(List.of(supersededCard));
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(currentCard));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "superseded-card"))
                .thenReturn(List.of());
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "ba-card"))
                .thenReturn(List.of());

        listener.onWorkflowCompleted(completedEvent());

        ArgumentCaptor<TransitionRequest> requests = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("superseded-card"), requests.capture());
        verify(kanbanTransitionService).transition(eq("ba-card"), requests.capture());
        assertThat(requests.getAllValues()).hasSize(2).allSatisfy(request ->
                assertThat(request.getStatus()).isEqualTo(KanbanStatus.DONE));
    }

    @Test
    void reviewCardWithAPendingAsk_staysInReview() {
        givenChainSteps(baRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "ba-card"))
                .thenReturn(List.of(Approval.builder().runId(baRunId).status(ApprovalStatus.PENDING).build()));

        listener.onWorkflowCompleted(completedEvent());

        verify(kanbanTransitionService, never()).transition(anyString(), any());
    }

    @Test
    void failedChain_movesAskLessReviewCardsOutOfTheColumn() {
        // F24: a dead chain leaves no review a human can perform; the ask-less card
        // must not sit in REVIEW forever (and keeps its runOutcome=FAILED on the DTO).
        givenChainSteps(baRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "ba-card"))
                .thenReturn(List.of());

        listener.onWorkflowCompleted(finishedEvent(WorkflowChain.Status.FAILED));

        ArgumentCaptor<TransitionRequest> request = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("ba-card"), request.capture());
        assertThat(request.getValue().getStatus()).isEqualTo(KanbanStatus.CANCELLED);
        assertThat(request.getValue().getComment())
                .isEqualTo(WorkflowCompletionCardListener.FAILURE_COMMENT);
    }

    @Test
    void failedChain_withAPendingAsk_isLeftInReview() {
        givenChainSteps(baRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "ba-card"))
                .thenReturn(List.of(Approval.builder().runId(baRunId).status(ApprovalStatus.PENDING).build()));

        listener.onWorkflowCompleted(finishedEvent(WorkflowChain.Status.FAILED));

        verify(kanbanTransitionService, never()).transition(anyString(), any());
    }

    @Test
    void cancelledChain_movesAskLessReviewCardsOutOfTheColumn() {
        givenChainSteps(baRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "ba-card"))
                .thenReturn(List.of());

        listener.onWorkflowCompleted(finishedEvent(WorkflowChain.Status.CANCELLED));

        ArgumentCaptor<TransitionRequest> request = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("ba-card"), request.capture());
        assertThat(request.getValue().getStatus()).isEqualTo(KanbanStatus.CANCELLED);
        assertThat(request.getValue().getComment())
                .isEqualTo(WorkflowCompletionCardListener.CANCELLATION_COMMENT);
    }

    @Test
    void cancelledChain_settlesCardsOfSupersededAttemptsToo() {
        // R6: the same overwritten-run-id hole on a cancelled chain; only the
        // superseded attempt's card was left in REVIEW forever.
        UUID supersededRunId = UUID.randomUUID();
        givenChainSteps(supersededRunId, baRunId);
        KanbanItem supersededCard = card("superseded-card", supersededRunId, KanbanStatus.REVIEW);
        KanbanItem currentCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(supersededRunId.toString()))
                .thenReturn(List.of(supersededCard));
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(currentCard));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "superseded-card"))
                .thenReturn(List.of());
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "ba-card"))
                .thenReturn(List.of());

        listener.onWorkflowCompleted(finishedEvent(WorkflowChain.Status.CANCELLED));

        ArgumentCaptor<TransitionRequest> requests = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("superseded-card"), requests.capture());
        verify(kanbanTransitionService).transition(eq("ba-card"), requests.capture());
        assertThat(requests.getAllValues()).hasSize(2).allSatisfy(request -> {
            assertThat(request.getStatus()).isEqualTo(KanbanStatus.CANCELLED);
            assertThat(request.getComment())
                    .isEqualTo(WorkflowCompletionCardListener.CANCELLATION_COMMENT);
        });
    }

    @Test
    void advancingChain_isIgnored() {
        listener.onWorkflowCompleted(new WorkflowAdvancedEvent(this, chainId, "sdd-loop",
                0, 1, WorkflowChain.Status.RUNNING));

        verifyNoInteractions(kanbanTransitionService);
        verifyNoInteractions(kanbanRepository);
    }

    // ---- R6: a manual chain cancel settles the chain's own cards ----

    @Test
    void cancelEvent_settlesInProgressAndReviewCardsToCancelled() {
        // R6: the in-flight card is still IN_PROGRESS at cancel time and the finished
        // cards sit in REVIEW; the cancelled chain owns both.
        givenChainSteps(baRunId, qaRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.IN_PROGRESS);
        KanbanItem qaCard = card("qa-card", qaRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));
        when(kanbanRepository.findByLinkedRunId(qaRunId.toString())).thenReturn(List.of(qaCard));

        listener.onWorkflowCancelled(cancelledEvent());

        ArgumentCaptor<TransitionRequest> requests = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("ba-card"), requests.capture());
        verify(kanbanTransitionService).transition(eq("qa-card"), requests.capture());
        assertThat(requests.getAllValues()).hasSize(2).allSatisfy(request -> {
            assertThat(request.getStatus()).isEqualTo(KanbanStatus.CANCELLED);
            assertThat(request.getComment())
                    .isEqualTo(WorkflowCompletionCardListener.CANCELLATION_COMMENT);
        });
    }

    @Test
    void cancelEvent_settlesACardEvenWithAPendingAsk() {
        // Ruling: the cancel is the operator's explicit stop, so a pending ask does not
        // keep the card — KanbanTransitionService.transition(card, CANCELLED) denies that
        // ask and stops the card's live run through the operator path.
        givenChainSteps(baRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));
        // lenient: the cancel path must not consult the approval repository at all.
        lenient().when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "ba-card"))
                .thenReturn(List.of(Approval.builder().runId(baRunId).status(ApprovalStatus.PENDING).build()));

        listener.onWorkflowCancelled(cancelledEvent());

        ArgumentCaptor<TransitionRequest> request = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("ba-card"), request.capture());
        assertThat(request.getValue().getStatus()).isEqualTo(KanbanStatus.CANCELLED);
        assertThat(request.getValue().getComment())
                .isEqualTo(WorkflowCompletionCardListener.CANCELLATION_COMMENT);
        verify(approvalRepository, never()).findByStatusAndKanbanItemId(any(), anyString());
    }

    @Test
    void cancelEvent_leavesDoneCardsAlone() {
        givenChainSteps(baRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.DONE);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));

        listener.onWorkflowCancelled(cancelledEvent());

        verify(kanbanTransitionService, never()).transition(anyString(), any());
    }

    @Test
    void cancelEvent_aRefusedTransition_doesNotEscapeTheListener() {
        givenChainSteps(baRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));
        when(kanbanTransitionService.transition(eq("ba-card"), any()))
                .thenThrow(new IllegalStateException("linked run already stopped"));

        assertThatCode(() -> listener.onWorkflowCancelled(cancelledEvent()))
                .doesNotThrowAnyException();
    }

    @Test
    void cardAlreadyOutOfReview_isLeftAlone() {
        givenChainSteps(baRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.IN_PROGRESS);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));

        listener.onWorkflowCompleted(completedEvent());

        verify(kanbanTransitionService, never()).transition(anyString(), any());
    }

    @Test
    void aRefusedTransition_doesNotEscapeTheListener() {
        givenChainSteps(baRunId);
        KanbanItem baCard = card("ba-card", baRunId, KanbanStatus.REVIEW);
        when(kanbanRepository.findByLinkedRunId(baRunId.toString())).thenReturn(List.of(baCard));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "ba-card"))
                .thenReturn(List.of());
        when(kanbanTransitionService.transition(eq("ba-card"), any()))
                .thenThrow(new IllegalStateException("linked run is still RUNNING"));

        assertThatCode(() -> listener.onWorkflowCompleted(completedEvent()))
                .doesNotThrowAnyException();
    }

    // ---- helpers ----

    private void givenChainSteps(UUID... runIds) {
        when(workflowService.stepRunIds(chainId)).thenReturn(List.of(runIds));
    }

    private KanbanItem card(String id, UUID runId, KanbanStatus status) {
        return KanbanItem.builder()
                .id(id)
                .title("step card " + id)
                .status(status)
                .priority(KanbanPriority.MEDIUM)
                .linkedRunId(runId.toString())
                .build();
    }

    private WorkflowAdvancedEvent completedEvent() {
        return finishedEvent(WorkflowChain.Status.COMPLETED);
    }

    private WorkflowAdvancedEvent finishedEvent(WorkflowChain.Status status) {
        return new WorkflowAdvancedEvent(this, chainId, "sdd-loop", 2, -1, status);
    }

    private WorkflowCancelledEvent cancelledEvent() {
        return new WorkflowCancelledEvent(this, chainId, "sdd-loop");
    }
}
