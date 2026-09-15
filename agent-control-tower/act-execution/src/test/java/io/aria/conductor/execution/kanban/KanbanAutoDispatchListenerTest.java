package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.KanbanItemCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Defect D1: creating a card directly in Todo is a dispatch intent (spec D3) —
 * the card must be picked up (Aria assigns an agent, a run starts) instead of
 * silently sitting in the column until someone drags it.
 */
class KanbanAutoDispatchListenerTest {

    private KanbanRepository kanbanRepository;
    private KanbanTransitionService kanbanTransitionService;
    private KanbanAutoDispatchListener listener;

    @BeforeEach
    void setUp() {
        kanbanRepository = mock(KanbanRepository.class);
        kanbanTransitionService = mock(KanbanTransitionService.class);
        listener = new KanbanAutoDispatchListener(kanbanRepository, kanbanTransitionService,
                transactionManager());
    }

    /** The attempt's own transaction boundary is covered end to end by KanbanListenerTransactionTest. */
    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return transactionManager;
    }

    private KanbanItemCreatedEvent created(String itemId) {
        return new KanbanItemCreatedEvent(this, itemId, "title", "MEDIUM");
    }

    @Test
    void todoCardIsDispatchedOnCreate() {
        KanbanItem card = KanbanItem.builder().id("c1").title("t")
                .status(KanbanStatus.TODO).agentTemplateId("dev-agent").build();
        when(kanbanRepository.findById("c1")).thenReturn(Optional.of(card));

        listener.onKanbanItemCreated(created("c1"));

        verify(kanbanTransitionService).dispatch("c1", "dev-agent");
    }

    @Test
    void backlogCardIsNotDispatched() {
        KanbanItem card = KanbanItem.builder().id("c2").title("t")
                .status(KanbanStatus.BACKLOG).build();
        when(kanbanRepository.findById("c2")).thenReturn(Optional.of(card));

        listener.onKanbanItemCreated(created("c2"));

        verify(kanbanTransitionService, never()).dispatch(any(), any());
    }

    @Test
    void runManagedCardIsNotDispatched() {
        // Cards auto-created for a run (RunKanbanAutoCreator) carry linkedRunId.
        // Dispatching them would create a SECOND run for the same agent — the
        // card describes run R1, it is not an operator dispatch intent.
        KanbanItem card = KanbanItem.builder().id("c5").title("t")
                .status(KanbanStatus.TODO).linkedRunId("r-1").linkedAgentId("a-1").build();
        when(kanbanRepository.findById("c5")).thenReturn(Optional.of(card));

        listener.onKanbanItemCreated(created("c5"));

        verify(kanbanTransitionService, never()).dispatch(any(), any());
    }

    @Test
    void missingCardIsANoOp() {
        when(kanbanRepository.findById("c3")).thenReturn(Optional.empty());

        listener.onKanbanItemCreated(created("c3"));

        verify(kanbanTransitionService, never()).dispatch(any(), any());
    }

    @Test
    void dispatchFailureIsSwallowedAndLogged() {
        KanbanItem card = KanbanItem.builder().id("c4").title("t")
                .status(KanbanStatus.TODO).build();
        when(kanbanRepository.findById("c4")).thenReturn(Optional.of(card));
        when(kanbanTransitionService.dispatch("c4", null))
                .thenThrow(new io.aria.conductor.common.exception.PickupRejectedException(
                        "NO_ELIGIBLE_AGENT", "none", java.util.Map.of()));

        // The card creation must never fail because the pickup could not start.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> listener.onKanbanItemCreated(created("c4")));
    }

    @Test
    void dispatchFailureRecordsLastErrorInsteadOfFailingTheCreate() {
        // The card is already committed when the listener runs, so a dispatch
        // failure must be recorded on the card — never propagated back into the
        // creator's transaction, where it would either roll the card back or
        // poison the transaction and surface as a 500.
        when(kanbanRepository.findById(itemId)).thenReturn(java.util.Optional.of(todoCard()));
        when(kanbanTransitionService.dispatch(anyString(), any())).thenThrow(
                new io.aria.conductor.common.exception.PickupRejectedException(
                        "NO_ELIGIBLE_AGENT", "none", java.util.Map.of()));

        listener.onKanbanItemCreated(new KanbanItemCreatedEvent(this, itemId, "title", "MEDIUM"));

        verify(kanbanRepository).save(argThat(item ->
                item.getLastError() != null && item.getLastError().contains("NO_ELIGIBLE_AGENT")));
    }

    private static final String itemId = "c6";

    private KanbanItem todoCard() {
        return KanbanItem.builder().id(itemId).title("t")
                .status(KanbanStatus.TODO).build();
    }
}
