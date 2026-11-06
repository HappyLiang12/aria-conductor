package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApprovalSettleCardListener}: a native permission ask
 * settling (decided or expired) settles its auto-created review card
 * (review-flow amendment, D6), so no zombie card survives its ask.
 *
 * <p>Only native asks ({@code source = ACP_PERMISSION}) are handled: review
 * asks are the card layer's own vocabulary and keep their existing flows. A
 * card the operator already moved past REVIEW is left alone — the settle never
 * overrides a human action.
 */
class ApprovalSettleCardListenerTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ApprovalRepository approvalRepository;
    private KanbanRepository kanbanRepository;
    private KanbanService kanbanService;
    private ApprovalSettleCardListener listener;

    @BeforeEach
    void setUp() {
        approvalRepository = mock(ApprovalRepository.class);
        kanbanRepository = mock(KanbanRepository.class);
        kanbanService = mock(KanbanService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        listener = new ApprovalSettleCardListener(
                approvalRepository, kanbanRepository, kanbanService, transactionManager);
    }

    private Approval nativeAsk(UUID approvalId, ApprovalStatus status) {
        return Approval.builder()
                .id(approvalId)
                .runId(RUN_ID)
                .status(status)
                .kanbanItemId("card-1")
                .source(ApprovalSource.ACP_PERMISSION)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .build();
    }

    private KanbanItem reviewCard(String id) {
        return KanbanItem.builder().id(id).title("Card " + id)
                .status(KanbanStatus.REVIEW).priority(KanbanPriority.MEDIUM)
                .linkedRunId(RUN_ID.toString()).build();
    }

    // ---- behavior 1: a decision settles the card ----

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
    void deniedNativeAskMovesItsReviewCardToCancelled() {
        UUID approvalId = UUID.randomUUID();
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(nativeAsk(approvalId, ApprovalStatus.DENIED)));
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(reviewCard("card-1")));

        listener.onDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.DENIED));

        verify(kanbanService).transition("card-1", KanbanStatus.CANCELLED, "ask denied");
    }

    @Test
    void expiredNativeAskMovesItsReviewCardToCancelled() {
        UUID approvalId = UUID.randomUUID();
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(nativeAsk(approvalId, ApprovalStatus.EXPIRED)));
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(reviewCard("card-1")));

        listener.onExpired(new ApprovalExpiredEvent(this, approvalId, UUID.randomUUID(), "run ended", "run_agent"));

        verify(kanbanService).transition("card-1", KanbanStatus.CANCELLED, "ask expired");
    }

    // ---- behavior 2: native asks only ----

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

    // ---- behavior 3: the operator's move wins ----

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

    // ---- behavior 4: the settle never breaks the publisher ----

    /**
     * A refused transition (e.g. REVIEW → DONE while the linked run is still
     * active) must be logged and swallowed: the listener runs inside the
     * transaction of the path that published the decide, so an escaping failure
     * would roll the operator's own decision back.
     */
    @Test
    void aRefusedTransitionIsSwallowedAndNeverEscapesTheListener() {
        UUID approvalId = UUID.randomUUID();
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(nativeAsk(approvalId, ApprovalStatus.APPROVED)));
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(reviewCard("card-1")));
        when(kanbanService.transition("card-1", KanbanStatus.DONE, "ask approved"))
                .thenThrow(new IllegalArgumentException("Cannot move to Done: linked run is still RUNNING"));

        assertThatCode(() -> listener.onDecided(
                new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.APPROVED)))
                .doesNotThrowAnyException();
    }
}
