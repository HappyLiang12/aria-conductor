package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.KanbanItemTransitionedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KanbanReviewAskCreatorTest {

    private ApprovalRepository approvalRepository;
    private KanbanRepository kanbanRepository;
    private RunRepository runRepository;
    private KanbanReviewAskCreator creator;

    private KanbanItem card;
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        approvalRepository = mock(ApprovalRepository.class);
        kanbanRepository = mock(KanbanRepository.class);
        runRepository = mock(RunRepository.class);
        creator = new KanbanReviewAskCreator(approvalRepository, kanbanRepository, runRepository);

        card = KanbanItem.builder().id("c1").title("add CSV export")
                .status(KanbanStatus.REVIEW).priority(KanbanPriority.MEDIUM)
                .assignee("dev-agent").linkedRunId(runId.toString()).build();
        when(kanbanRepository.findById("c1")).thenReturn(Optional.of(card));
    }

    private KanbanItemTransitionedEvent event(String from, String to) {
        return new KanbanItemTransitionedEvent(this, "c1", from, to);
    }

    @Test
    void createsReviewRequestAskOnRunCompletion() {
        Run run = Run.builder().id(runId).status(RunStatus.COMPLETED)
                .promptSeed("Kanban task: add CSV export").totalTokensUsed(1234)
                .iterationCount(3).completedAt(java.time.Instant.now()).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "c1"))
                .thenReturn(List.of());

        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));

        ArgumentCaptor<Approval> captor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(captor.capture());
        Approval ask = captor.getValue();
        assertThat(ask.getRunId()).isEqualTo(runId);
        assertThat(ask.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(ask.getAskType()).isEqualTo(Approval.AskType.REVIEW_REQUEST);
        // The ask is a kanban HITL entry, NOT a spec gate: it must not be typed
        // SPEC_REVIEW, or every consumer that looks up a run's pending spec
        // approval (SpecReviewCoordinator's idempotency guard, SddWorkflow/
        // GitPipeline IT helpers, sdd-workflow E2E) mistakes it for the gate.
        assertThat(ask.getApprovalType()).isEqualTo(Approval.ApprovalType.TOOL_CALL);
        assertThat(ask.getKanbanItemId()).isEqualTo("c1");
        assertThat(ask.getContent()).contains("COMPLETED").contains("add CSV export");
        assertThat(ask.getContextMd()).contains("dev-agent").contains("add CSV export");
    }

    @Test
    void skipsNonReviewTransitions() {
        creator.onKanbanItemTransitioned(event("TODO", "IN_PROGRESS"));
        creator.onKanbanItemTransitioned(event("REVIEW", "DONE"));
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void skipsWhenPendingAskAlreadyExists() {
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "c1"))
                .thenReturn(List.of(Approval.builder().runId(runId).status(ApprovalStatus.PENDING).build()));
        when(runRepository.findById(runId)).thenReturn(Optional.of(
                Run.builder().id(runId).status(RunStatus.COMPLETED).build()));

        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));

        verify(approvalRepository, never()).save(any());
    }

    @Test
    void skipsWhenNoLinkedRun() {
        card.setLinkedRunId(null);
        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void createsAskWithUnknownStatusWhenRunRowMissing() {
        // Dangling linkedRunId: the run row is gone, but the ask is still created
        // (informational) and must not fabricate run facts it does not have.
        when(approvalRepository.findByStatusAndKanbanItemId(ApprovalStatus.PENDING, "c1"))
                .thenReturn(List.of());
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));

        ArgumentCaptor<Approval> captor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).contains("UNKNOWN");
        assertThat(captor.getValue().getContextMd()).doesNotContain("**Iterations:**");
    }

    @Test
    void skipsWhenLinkedRunIdIsBlank() {
        card.setLinkedRunId("   ");
        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void corruptLinkCreatesNoAsk() {
        // Contract for an unreadable link: no ask, no run lookup, no exception.
        // The creator cannot move a card, so this pins the ask contract only. The
        // "parse precedes the move" property is discriminated by
        // KanbanServiceTest.transitionToReviewWithACorruptLinkIsRejectedAndLeavesTheCardWhereItWas
        // and KanbanListenerTransactionTest.aCorruptRunLinkLeavesTheCardOutOfReviewAndCreatesNoAsk,
        // never by a unit test on this listener (both orderings look identical here).
        card.setLinkedRunId("not-a-uuid");

        assertThatCode(() -> creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW")))
                .doesNotThrowAnyException();

        verify(approvalRepository, never()).save(any());
        verifyNoInteractions(runRepository);
    }

    @Test
    void skipsWhenCardMissing() {
        when(kanbanRepository.findById("c1")).thenReturn(Optional.empty());
        creator.onKanbanItemTransitioned(event("IN_PROGRESS", "REVIEW"));
        verify(approvalRepository, never()).save(any());
    }
}
