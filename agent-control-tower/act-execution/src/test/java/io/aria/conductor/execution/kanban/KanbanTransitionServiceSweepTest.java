package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KanbanTransitionServiceSweepTest {

    private KanbanRepository kanbanRepository;
    private KanbanService kanbanService;
    private ApprovalRepository approvalRepository;
    private KanbanTransitionService service;

    @BeforeEach
    void setUp() {
        kanbanRepository = Mockito.mock(KanbanRepository.class);
        kanbanService = Mockito.mock(KanbanService.class);
        approvalRepository = Mockito.mock(ApprovalRepository.class);
        service = new KanbanTransitionService(kanbanRepository, kanbanService,
                Mockito.mock(RunService.class), Mockito.mock(RunRepository.class),
                Mockito.mock(AgentRepository.class), Mockito.mock(AgentPickerService.class),
                Mockito.mock(AgentPickupEligibility.class), approvalRepository,
                Mockito.mock(ApplicationEventPublisher.class));
    }

    @Test
    void acceptingAReviewCardApprovesItsPendingAsks() {
        KanbanItem card = reviewCard("card-1");
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(card));

        service.transition("card-1", TransitionRequest.builder().status(KanbanStatus.DONE).build());

        verify(approvalRepository).approvePendingByKanbanItemId(eq("card-1"),
                eq("accepted by card decision"), any(Instant.class));
    }

    @Test
    void reworkingAReviewCardDeniesItsPendingAsks() {
        KanbanItem card = reviewCard("card-1");
        when(kanbanRepository.findById("card-1")).thenReturn(Optional.of(card));

        // requestChanges runs first inside transition(TODO); pickup will fail on
        // mocks afterwards — the sweep call must already have happened.
        try {
            service.transition("card-1", TransitionRequest.builder().status(KanbanStatus.TODO).build());
        } catch (RuntimeException ignored) {
            // pickup is mocked out; the assertions below are the contract
        }

        verify(approvalRepository).denyPendingByKanbanItemId(eq("card-1"),
                eq("superseded by request changes"), any(Instant.class));
        verify(approvalRepository, Mockito.never()).markStaleByKanbanItemId(any(), any());
    }

    private static KanbanItem reviewCard(String id) {
        KanbanItem item = new KanbanItem();
        item.setId(id);
        item.setStatus(KanbanStatus.REVIEW);
        return item;
    }
}
