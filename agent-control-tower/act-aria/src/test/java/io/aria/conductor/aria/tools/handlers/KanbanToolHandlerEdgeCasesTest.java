package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanPriority;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.kanban.UpdateKanbanItemRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Invalid-enum, blank-normalization and failure paths of
 * {@link KanbanToolHandler} that KanbanToolHandlerTest does not exercise.
 */
@ExtendWith(MockitoExtension.class)
class KanbanToolHandlerEdgeCasesTest {

    @Mock private KanbanService kanbanService;

    @InjectMocks
    private KanbanToolHandler handler;

    @Test
    void transition_invalidStatusListsValidValues() {
        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item", "id", "k-1", "newStatus", "SHIPPED"));

        assertThat(result).startsWith("Error: Invalid status: SHIPPED")
                .contains("TODO").contains("IN_PROGRESS").contains("DONE");
        verifyNoInteractions(kanbanService);
    }

    @Test
    void transition_passesCommentThroughToService() {
        when(kanbanService.transition("k-1", KanbanStatus.DONE, "verified by QA"))
                .thenReturn(KanbanItem.builder().id("k-1").title("T").status(KanbanStatus.DONE).build());

        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item", "id", "k-1",
                "newStatus", "done", "comment", "verified by QA"));

        verify(kanbanService).transition("k-1", KanbanStatus.DONE, "verified by QA");
        assertThat(result).contains("transitioned to DONE");
    }

    @Test
    void list_invalidStatusFilterReturnsError() {
        String result = handler.execute(Map.of(
                "toolName", "list_kanban_items", "status", "WAITING"));

        assertThat(result).startsWith("Error: Invalid status: WAITING");
        verifyNoInteractions(kanbanService);
    }

    @Test
    void list_reportsWhenBoardIsEmpty() {
        when(kanbanService.list(null)).thenReturn(List.of());

        String result = handler.execute(Map.of("toolName", "list_kanban_items"));

        assertThat(result).isEqualTo("No kanban items found.");
    }

    @Test
    void update_invalidPriorityReturnsError() {
        String result = handler.execute(Map.of(
                "toolName", "update_kanban_item", "id", "k-1", "priority", "URGENT"));

        assertThat(result).startsWith("Error: Invalid priority: URGENT")
                .contains("LOW").contains("CRITICAL");
        verifyNoInteractions(kanbanService);
    }

    @Test
    void update_normalizesBlankFieldsToNull() {
        when(kanbanService.update(eq("k-1"), any(UpdateKanbanItemRequest.class)))
                .thenReturn(KanbanItem.builder().id("k-1").title("Kept").build());

        handler.execute(Map.of(
                "toolName", "update_kanban_item", "id", "k-1",
                "title", "Kept", "description", "  ", "assignee", ""));

        ArgumentCaptor<UpdateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(UpdateKanbanItemRequest.class);
        verify(kanbanService).update(eq("k-1"), captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Kept");
        // blank strings must not overwrite existing values downstream
        assertThat(captor.getValue().getDescription()).isNull();
        assertThat(captor.getValue().getAssignee()).isNull();
        assertThat(captor.getValue().getPriority()).isNull();
    }

    @Test
    void create_parsesPriorityCaseInsensitivelyAndNullsBlankOptionals() {
        when(kanbanService.create(any(CreateKanbanItemRequest.class)))
                .thenReturn(KanbanItem.builder().id("k-9").title("Fix bug")
                        .status(KanbanStatus.TODO).build());

        String result = handler.execute(Map.of(
                "toolName", "create_kanban_item",
                "title", "Fix bug", "priority", "critical", "labels", "   "));

        ArgumentCaptor<CreateKanbanItemRequest> captor =
                ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo(KanbanPriority.CRITICAL);
        assertThat(captor.getValue().getLabels()).isNull();
        assertThat(result).contains("Fix bug").contains("k-9").contains("TODO");
    }

    @Test
    void serviceExceptionIsMappedToErrorString() {
        when(kanbanService.transition("k-1", KanbanStatus.DONE, null))
                .thenThrow(new IllegalStateException("illegal transition BLOCKED -> DONE"));

        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item", "id", "k-1", "newStatus", "DONE"));

        assertThat(result).isEqualTo("Error: illegal transition BLOCKED -> DONE");
    }
}
