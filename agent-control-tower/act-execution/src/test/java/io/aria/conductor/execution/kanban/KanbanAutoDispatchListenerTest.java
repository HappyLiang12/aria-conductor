package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.KanbanItemCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        listener = new KanbanAutoDispatchListener(kanbanRepository, kanbanTransitionService);
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
                .thenThrow(new IllegalStateException("No healthy agent available for kanban pickup"));

        // The card creation must never fail because the pickup could not start —
        // dispatch() records lastError on the card instead.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> listener.onKanbanItemCreated(created("c4")));
    }
}
