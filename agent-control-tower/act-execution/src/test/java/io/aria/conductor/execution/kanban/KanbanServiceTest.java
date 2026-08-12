package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KanbanServiceTest {

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
    void initStoredItem() {
        stored = KanbanItem.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .title("Existing")
                .status(KanbanStatus.TODO)
                .priority(KanbanPriority.MEDIUM)
                .build();
    }

    @Test
    void create_persistsItemWithDefaults() {
        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title("New task")
                .description("desc")
                .build();

        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.create(request);

        ArgumentCaptor<KanbanItem> captor = ArgumentCaptor.forClass(KanbanItem.class);
        verify(repository).save(captor.capture());
        KanbanItem saved = captor.getValue();

        assertThat(saved.getTitle()).isEqualTo("New task");
        assertThat(saved.getStatus()).isEqualTo(KanbanStatus.TODO);
        assertThat(saved.getPriority()).isEqualTo(KanbanPriority.MEDIUM);
        assertThat(result).isSameAs(saved);
    }

    @Test
    void create_withLinkedRunId_persistsLink() {
        String runId = "22222222-2222-2222-2222-222222222222";
        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title("Linked")
                .linkedRunId(runId)
                .priority(KanbanPriority.HIGH)
                .build();

        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.create(request);

        assertThat(result.getLinkedRunId()).isEqualTo(runId);
        assertThat(result.getPriority()).isEqualTo(KanbanPriority.HIGH);
    }

    @Test
    void list_withStatusFilter_callsFindByStatus() {
        when(repository.findByStatus(KanbanStatus.TODO)).thenReturn(List.of(stored));

        List<KanbanItem> result = service.list(KanbanStatus.TODO);

        assertThat(result).hasSize(1).containsExactly(stored);
        verify(repository).findByStatus(KanbanStatus.TODO);
        verify(repository, never()).findAll();
    }

    @Test
    void list_withoutStatus_returnsAll() {
        when(repository.findAll()).thenReturn(List.of(stored));

        List<KanbanItem> result = service.list(null);

        assertThat(result).containsExactly(stored);
        verify(repository).findAll();
    }

    @Test
    void transition_validTodoToInProgress_succeeds() {
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.transition(stored.getId(), KanbanStatus.IN_PROGRESS, "starting");

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
    }

    @Test
    void transition_doneToInProgress_throws() {
        stored.setStatus(KanbanStatus.DONE);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.transition(stored.getId(), KanbanStatus.IN_PROGRESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid kanban transition");

        verify(repository, never()).save(any());
    }

    @Test
    void transition_cancelledIsTerminal() {
        stored.setStatus(KanbanStatus.CANCELLED);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.transition(stored.getId(), KanbanStatus.TODO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transition_blockedToTodo_succeeds() {
        stored.setStatus(KanbanStatus.BLOCKED);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.transition(stored.getId(), KanbanStatus.TODO, "unblocked");

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.TODO);
    }

    @Test
    void transition_inProgressToReview_succeeds() {
        stored.setStatus(KanbanStatus.IN_PROGRESS);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.transition(stored.getId(), KanbanStatus.REVIEW, "waiting for approval");

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.REVIEW);
    }

    @Test
    void transition_reviewToInProgress_succeeds() {
        stored.setStatus(KanbanStatus.REVIEW);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.transition(stored.getId(), KanbanStatus.IN_PROGRESS, "approved");

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
    }

    @Test
    void transition_reviewToDone_succeeds() {
        stored.setStatus(KanbanStatus.REVIEW);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KanbanItem result = service.transition(stored.getId(), KanbanStatus.DONE, "approved");

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.DONE);
    }

    @Test
    void transition_todoToReview_isRejected() {
        stored.setStatus(KanbanStatus.TODO);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.transition(stored.getId(), KanbanStatus.REVIEW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid kanban transition");

        verify(repository, never()).save(any());
    }

    @Test
    void get_missingId_throwsNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_partialFields_mergesIntoExisting() {
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any(KanbanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateKanbanItemRequest request = UpdateKanbanItemRequest.builder()
                .assignee("alice")
                .priority(KanbanPriority.HIGH)
                .build();

        KanbanItem result = service.update(stored.getId(), request);

        assertThat(result.getAssignee()).isEqualTo("alice");
        assertThat(result.getPriority()).isEqualTo(KanbanPriority.HIGH);
        // Title unchanged.
        assertThat(result.getTitle()).isEqualTo("Existing");
    }
}
