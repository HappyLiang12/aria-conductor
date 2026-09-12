package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KanbanReviewCardListener}: every approval must be
 * surfaced as a Review-column card (spec 4.3) — linked to the card already
 * associated with the run, or auto-created for orphan approvals.
 */
class KanbanReviewCardListenerTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID APPROVAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private ApprovalRepository approvalRepository;
    private KanbanRepository kanbanRepository;
    private KanbanService kanbanService;
    private KanbanReviewCardListener listener;

    @BeforeEach
    void setUp() {
        approvalRepository = mock(ApprovalRepository.class);
        kanbanRepository = mock(KanbanRepository.class);
        kanbanService = mock(KanbanService.class);
        listener = new KanbanReviewCardListener(approvalRepository, kanbanRepository, kanbanService);
    }

    private Approval approval() {
        return Approval.builder()
                .id(APPROVAL_ID)
                .runId(RUN_ID)
                .status(ApprovalStatus.PENDING)
                .content("Agent wants to delete the branch")
                .build();
    }

    private ApprovalRequestedEvent event() {
        return new ApprovalRequestedEvent(this, APPROVAL_ID, RUN_ID, null, "TOOL_CALL");
    }

    private KanbanItem card(String id, KanbanStatus status) {
        return KanbanItem.builder().id(id).title("Card " + id)
                .status(status).priority(KanbanPriority.MEDIUM)
                .linkedRunId(RUN_ID.toString()).build();
    }

    // ---- behavior 1: already linked ----

    @Test
    void alreadyLinkedApproval_isNoOp() {
        Approval linked = approval();
        linked.setKanbanItemId("card-1");
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(linked));

        listener.onApprovalRequested(event());

        verifyNoInteractions(kanbanRepository, kanbanService);
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void unknownApprovalId_isNoOp() {
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.empty());

        listener.onApprovalRequested(event());

        verifyNoInteractions(kanbanRepository, kanbanService);
        verify(approvalRepository, never()).save(any());
    }

    // ---- behavior 2: card exists for the run ----

    @Test
    void existingCardForRun_linksApprovalWithoutCreating() {
        Approval approval = approval();
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(approval));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString()))
                .thenReturn(List.of(card("card-1", KanbanStatus.IN_PROGRESS)));

        listener.onApprovalRequested(event());

        assertThat(approval.getKanbanItemId()).isEqualTo("card-1");
        verify(approvalRepository).save(approval);
        verify(kanbanService, never()).create(any());
    }

    @Test
    void doneCardForRun_isSkipped_todoCardIsLinkedInstead() {
        Approval approval = approval();
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(approval));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString()))
                .thenReturn(List.of(card("done-card", KanbanStatus.DONE), card("todo-card", KanbanStatus.TODO)));

        listener.onApprovalRequested(event());

        // DONE cards are finished work; the ask must land on an active card.
        assertThat(approval.getKanbanItemId()).isEqualTo("todo-card");
        verify(approvalRepository).save(approval);
        verify(kanbanService, never()).create(any());
    }

    // ---- behavior 3: orphan approval ----

    @Test
    void orphanApproval_createsReviewCardAndBackfillsLink() {
        Approval approval = approval();
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(approval));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString())).thenReturn(List.of());
        when(kanbanService.create(any(CreateKanbanItemRequest.class)))
                .thenReturn(card("card-new", KanbanStatus.REVIEW));

        listener.onApprovalRequested(event());

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        CreateKanbanItemRequest request = captor.getValue();
        assertThat(request.getTitle()).isEqualTo("Review: tool call (run 11111111)");
        assertThat(request.getDescription()).isEqualTo("Agent wants to delete the branch");
        assertThat(request.getStatus()).isEqualTo(KanbanStatus.REVIEW);
        assertThat(request.getLinkedRunId()).isEqualTo(RUN_ID.toString());

        assertThat(approval.getKanbanItemId()).isEqualTo("card-new");
        verify(approvalRepository).save(approval);
    }

    @Test
    void orphanApproval_askTypeTitles_useReadableForm() {
        Approval specReview = Approval.builder()
                .id(APPROVAL_ID)
                .runId(RUN_ID)
                .status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .content("spec ready")
                .build();
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(specReview));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString())).thenReturn(List.of());
        when(kanbanService.create(any(CreateKanbanItemRequest.class)))
                .thenReturn(card("card-new", KanbanStatus.REVIEW));

        listener.onApprovalRequested(event());

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Review: spec review (run 11111111)");
    }

    @Test
    void orphanApproval_nullApprovalType_fallsBackToGenericTitle() {
        Approval noType = Approval.builder()
                .id(APPROVAL_ID)
                .runId(RUN_ID)
                .status(ApprovalStatus.PENDING)
                .approvalType(null)
                .content("mystery ask")
                .build();
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(noType));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString())).thenReturn(List.of());
        when(kanbanService.create(any(CreateKanbanItemRequest.class)))
                .thenReturn(card("card-new", KanbanStatus.REVIEW));

        listener.onApprovalRequested(event());

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Review: approval (run 11111111)");
    }

    // ---- behavior 4: defensive listener (mirrors RunKanbanAutoCreator) ----

    @Test
    void nullRunId_skipsLinkageWithoutTouchingRepositories() {
        ApprovalRequestedEvent event = new ApprovalRequestedEvent(this, APPROVAL_ID, null, null, "TOOL_CALL");

        listener.onApprovalRequested(event);

        verifyNoInteractions(approvalRepository, kanbanRepository, kanbanService);
    }

    @Test
    void repositoryFailure_isSwallowed() {
        when(approvalRepository.findById(APPROVAL_ID)).thenThrow(new IllegalStateException("db down"));

        listener.onApprovalRequested(event());

        verify(kanbanService, never()).create(any());
        verify(approvalRepository, never()).save(any());
    }
}
