package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Arrays;
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
 *
 * <p>The mirror owns exactly one column: the link is written through the
 * guarded single-column update ({@code linkKanbanItemIdIfAbsent}) and the
 * loaded entity is never mutated or saved — the snapshot may predate an
 * operator decision, so a full-row write would revert it (fix round 3).
 */
class KanbanReviewCardListenerTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID APPROVAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private ApprovalRepository approvalRepository;
    private AcpPermissionRequestRepository permissions;
    private KanbanRepository kanbanRepository;
    private KanbanService kanbanService;
    private KanbanReviewCardListener listener;

    @BeforeEach
    void setUp() {
        approvalRepository = mock(ApprovalRepository.class);
        permissions = mock(AcpPermissionRequestRepository.class);
        kanbanRepository = mock(KanbanRepository.class);
        kanbanService = mock(KanbanService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        listener = new KanbanReviewCardListener(
                approvalRepository, kanbanRepository, kanbanService, permissions, transactionManager, Runnable::run);
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
        verify(approvalRepository, never()).linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-1");
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

        // The link goes through the guarded single-column write with the exact
        // card id; the loaded entity stays untouched and is never saved.
        assertThat(approval.getKanbanItemId()).isNull();
        verify(approvalRepository).linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-1");
        verify(approvalRepository, never()).save(approval);
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
        assertThat(approval.getKanbanItemId()).isNull();
        verify(approvalRepository).linkKanbanItemIdIfAbsent(APPROVAL_ID, "todo-card");
        verify(approvalRepository, never()).save(approval);
        verify(kanbanService, never()).create(any());
    }

    // ---- behavior 2b: a settled ask never gains a card ----

    /**
     * The mirror reads the approval while it may still be PENDING and commits
     * after the operator's decision: a settled ask must not have a card created
     * or linked for it afterwards — nothing would ever settle that card
     * (a second decision is refused).
     */
    @Test
    void anAlreadySettledAskGetsNoCard() {
        Approval settled = approval();
        settled.setStatus(ApprovalStatus.APPROVED);
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(settled));

        listener.onApprovalRequested(event());

        verify(kanbanService, never()).create(any(CreateKanbanItemRequest.class));
        verify(approvalRepository, never()).linkKanbanItemIdIfAbsent(any(), any());
    }

    // ---- behavior 2c: the link-settle race is closed by a re-check (I1b) ----

    /**
     * The opposite interleaving of the settled-ask guard: the mirror read
     * PENDING and linked its card while the decision committed; the settle
     * listener had already run (its read saw no link) and left the card alone.
     * The mirror re-reads the approval once its write is done and settles the
     * card it just linked — approved reaches Done, exactly like the settle
     * listener would have.
     */
    @Test
    void anAskSettledWhileItsCardWasBeingLinkedSettlesTheCardImmediately() {
        Approval settledMeanwhile = approval();
        settledMeanwhile.setStatus(ApprovalStatus.APPROVED);
        when(approvalRepository.findById(APPROVAL_ID))
                .thenReturn(Optional.of(approval()), Optional.of(settledMeanwhile));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString()))
                .thenReturn(List.of(card("card-1", KanbanStatus.REVIEW)));
        when(approvalRepository.linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-1")).thenReturn(1);
        when(kanbanRepository.findById("card-1"))
                .thenReturn(Optional.of(card("card-1", KanbanStatus.REVIEW)));

        listener.onApprovalRequested(event());

        verify(kanbanService).transition("card-1", KanbanStatus.DONE, "ask approved");
    }

    @Test
    void anAskSettledWhileItsCardWasBeingCreatedSettlesTheNewCardImmediately() {
        Approval settledMeanwhile = approval();
        settledMeanwhile.setStatus(ApprovalStatus.DENIED);
        when(approvalRepository.findById(APPROVAL_ID))
                .thenReturn(Optional.of(approval()), Optional.of(settledMeanwhile));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString())).thenReturn(List.of());
        when(kanbanService.create(any(CreateKanbanItemRequest.class)))
                .thenReturn(card("card-new", KanbanStatus.REVIEW));
        when(approvalRepository.linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-new")).thenReturn(1);
        when(kanbanRepository.findById("card-new"))
                .thenReturn(Optional.of(card("card-new", KanbanStatus.REVIEW)));

        listener.onApprovalRequested(event());

        verify(kanbanService).transition("card-new", KanbanStatus.CANCELLED, "ask denied");
    }

    @Test
    void anAskStillPendingAfterTheWriteIsLeftToTheSettleListener() {
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(approval()));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString()))
                .thenReturn(List.of(card("card-1", KanbanStatus.REVIEW)));
        when(approvalRepository.linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-1")).thenReturn(1);

        listener.onApprovalRequested(event());

        // Still pending: the settle listener owns the later settle — the mirror
        // must not touch the board beyond its own write.
        verify(kanbanService, never()).transition(any(), any(), any());
    }

    @Test
    void aCardAlreadySettledByTheSettleListenerIsLeftAlone() {
        // The settle listener won the race after the link landed: the re-check
        // sees the card is no longer in Review and never fights the winner.
        Approval settledMeanwhile = approval();
        settledMeanwhile.setStatus(ApprovalStatus.APPROVED);
        when(approvalRepository.findById(APPROVAL_ID))
                .thenReturn(Optional.of(approval()), Optional.of(settledMeanwhile));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString()))
                .thenReturn(List.of(card("card-1", KanbanStatus.REVIEW)));
        when(approvalRepository.linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-1")).thenReturn(1);
        when(kanbanRepository.findById("card-1"))
                .thenReturn(Optional.of(card("card-1", KanbanStatus.DONE)));

        listener.onApprovalRequested(event());

        verify(kanbanService, never()).transition(any(), any(), any());
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

        // The auto-created card is backfilled through the same guarded
        // single-column write; the entity stays untouched and is never saved.
        assertThat(approval.getKanbanItemId()).isNull();
        verify(approvalRepository).linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-new");
        verify(approvalRepository, never()).save(approval);
    }

    @Test
    void aToolCallCardCarriesTheToolNameAndAnArgumentExcerpt() {
        // The native ask's ledger row names the tool and carries the argument
        // document: the card face must say what the operator is deciding, not
        // just "tool call" (D7).
        Approval approval = approval();
        approval.setSource(ApprovalSource.ACP_PERMISSION);
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(approval));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString())).thenReturn(List.of());
        when(permissions.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(
                AcpPermissionRequest.builder()
                        .toolName("run_agent")
                        .argumentsJson("{\"agentId\":\"9aaa1111-2222-3333-4444-555566667777\","
                                + "\"prompt\":\"research the release\"}")
                        .build()));
        when(kanbanService.create(any(CreateKanbanItemRequest.class)))
                .thenReturn(card("card-new", KanbanStatus.REVIEW));

        listener.onApprovalRequested(event());

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Review: tool call - run_agent (run 11111111)");
        assertThat(captor.getValue().getDescription()).startsWith("{\"agentId\"");
        verify(approvalRepository).linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-new");
    }

    @Test
    void aHugeArgumentDocumentIsTruncatedToAnExcerpt() {
        Approval approval = approval();
        approval.setSource(ApprovalSource.ACP_PERMISSION);
        String arguments = "{\"prompt\":\"" + "x".repeat(400) + "\"}";
        when(approvalRepository.findById(APPROVAL_ID)).thenReturn(Optional.of(approval));
        when(kanbanRepository.findByLinkedRunId(RUN_ID.toString())).thenReturn(List.of());
        when(permissions.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(
                AcpPermissionRequest.builder().toolName("run_agent").argumentsJson(arguments).build()));
        when(kanbanService.create(any(CreateKanbanItemRequest.class)))
                .thenReturn(card("card-new", KanbanStatus.REVIEW));

        listener.onApprovalRequested(event());

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo(arguments.substring(0, 200) + "...");
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
        verify(approvalRepository).linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-new");
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
        verify(approvalRepository).linkKanbanItemIdIfAbsent(APPROVAL_ID, "card-new");
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

    // ---- behavior 5: decisions never reach the board (R24.7) ----

    /**
     * R24.7: an ACP decision is published as an {@code ApprovalDecidedEvent}, and nothing on the
     * board path may react to it — a decision can never move a card (least of all to DONE). The
     * listener only ever handles {@code ApprovalRequestedEvent}.
     */
    @Test
    void decisionEvents_haveNoHandlerOnTheBoardListener() {
        assertThat(Arrays.stream(KanbanReviewCardListener.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .map(Class::getSimpleName))
                .doesNotContain("ApprovalDecidedEvent");
    }
}
