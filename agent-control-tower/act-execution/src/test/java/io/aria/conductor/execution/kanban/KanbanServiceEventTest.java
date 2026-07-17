package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.KanbanItemCreatedEvent;
import io.aria.conductor.common.event.KanbanItemTransitionedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link KanbanService} publishes the expected
 * {@link org.springframework.context.ApplicationEvent}s on
 * {@code create} and {@code transition}.
 */
@ExtendWith(MockitoExtension.class)
class KanbanServiceEventTest {

    @Mock
    KanbanRepository repository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    RunRepository runRepository;

    @InjectMocks
    KanbanService service;

    private KanbanItem stored;

    @BeforeEach
    void seed() {
        stored = KanbanItem.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .title("Existing")
                .status(KanbanStatus.TODO)
                .priority(KanbanPriority.MEDIUM)
                .build();
    }

    @Test
    void create_publishesKanbanItemCreatedEvent() {
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> {
            KanbanItem arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId("22222222-2222-2222-2222-222222222222");
            return arg;
        });

        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title("New task")
                .description("desc")
                .priority(KanbanPriority.HIGH)
                .build();

        KanbanItem created = service.create(request);

        ArgumentCaptor<KanbanItemCreatedEvent> captor =
                ArgumentCaptor.forClass(KanbanItemCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        KanbanItemCreatedEvent event = captor.getValue();
        assertThat(event.getItemId()).isEqualTo(created.getId());
        assertThat(event.getTitle()).isEqualTo("New task");
        assertThat(event.getPriority()).isEqualTo("HIGH");
    }

    @Test
    void create_withDefaultPriority_publishesEventWithMedium() {
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> {
            KanbanItem arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId("33333333-3333-3333-3333-333333333333");
            return arg;
        });

        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title("Default Priority")
                .build();

        service.create(request);

        ArgumentCaptor<KanbanItemCreatedEvent> captor =
                ArgumentCaptor.forClass(KanbanItemCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo("MEDIUM");
    }

    @Test
    void transition_publishesKanbanItemTransitionedEvent() {
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        service.transition(stored.getId(), KanbanStatus.IN_PROGRESS, "starting");

        ArgumentCaptor<KanbanItemTransitionedEvent> captor =
                ArgumentCaptor.forClass(KanbanItemTransitionedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        KanbanItemTransitionedEvent event = captor.getValue();
        assertThat(event.getItemId()).isEqualTo(stored.getId());
        assertThat(event.getFromStatus()).isEqualTo("TODO");
        assertThat(event.getToStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void transition_invalid_doesNotPublishEvent() {
        stored.setStatus(KanbanStatus.DONE);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        try {
            service.transition(stored.getId(), KanbanStatus.IN_PROGRESS, null);
        } catch (IllegalArgumentException expected) {
            // expected
        }

        verify(eventPublisher, never()).publishEvent(any());
    }
}
