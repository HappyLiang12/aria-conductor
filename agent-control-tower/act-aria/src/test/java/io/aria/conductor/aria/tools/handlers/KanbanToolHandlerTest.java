package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.execution.kanban.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KanbanToolHandlerTest {

    @Mock
    private KanbanService kanbanService;

    @InjectMocks
    private KanbanToolHandler handler;

    @Test
    void createKanbanItemShouldReturnJson() {
        KanbanItem saved = KanbanItem.builder()
                .id("kb-1")
                .title("Test Item")
                .status(KanbanStatus.TODO)
                .priority(KanbanPriority.HIGH)
                .assignee("alice")
                .labels("bug")
                .createdAt(Instant.now())
                .build();
        when(kanbanService.create(any(CreateKanbanItemRequest.class))).thenReturn(saved);

        String result = handler.execute(Map.of(
                "toolName", "create_kanban_item",
                "title", "Test Item",
                "priority", "HIGH",
                "assignee", "alice",
                "labels", "bug"
        ));

        assertTrue(result.contains("kb-1"));
        assertTrue(result.contains("Test Item"));
        assertTrue(result.contains("TODO"));
        verify(kanbanService).create(any(CreateKanbanItemRequest.class));
    }

    @Test
    void createKanbanItemMissingTitleShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "create_kanban_item"));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(kanbanService);
    }

    @Test
    void createKanbanItemInvalidPriorityShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "create_kanban_item",
                "title", "X",
                "priority", "BOGUS"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(kanbanService);
    }

    @Test
    void listKanbanItemsShouldReturnAll() {
        KanbanItem a = KanbanItem.builder().id("a").title("A").status(KanbanStatus.TODO).priority(KanbanPriority.MEDIUM).createdAt(Instant.now()).build();
        KanbanItem b = KanbanItem.builder().id("b").title("B").status(KanbanStatus.IN_PROGRESS).priority(KanbanPriority.HIGH).createdAt(Instant.now()).build();
        when(kanbanService.list(null)).thenReturn(List.of(a, b));

        String result = handler.execute(Map.of("toolName", "list_kanban_items"));

        assertTrue(result.contains("a"));
        verify(kanbanService).list(null);
    }

    @Test
    void listKanbanItemsWithStatusFilter() {
        KanbanItem item = KanbanItem.builder().id("c").title("C").status(KanbanStatus.DONE).priority(KanbanPriority.LOW).createdAt(Instant.now()).build();
        when(kanbanService.list(KanbanStatus.DONE)).thenReturn(List.of(item));

        String result = handler.execute(Map.of(
                "toolName", "list_kanban_items",
                "status", "DONE"
        ));

        assertTrue(result.contains("c"));
        verify(kanbanService).list(KanbanStatus.DONE);
    }

    @Test
    void updateKanbanItemShouldCallService() {
        KanbanItem updated = KanbanItem.builder()
                .id("kb-1")
                .title("Updated Title")
                .status(KanbanStatus.TODO)
                .priority(KanbanPriority.MEDIUM)
                .createdAt(Instant.now())
                .build();
        when(kanbanService.update(eq("kb-1"), any(UpdateKanbanItemRequest.class))).thenReturn(updated);

        String result = handler.execute(Map.of(
                "toolName", "update_kanban_item",
                "id", "kb-1",
                "title", "Updated Title"
        ));

        assertTrue(result.contains("Updated Title"));
        verify(kanbanService).update(eq("kb-1"), any(UpdateKanbanItemRequest.class));
    }

    @Test
    void updateKanbanItemMissingIdShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "update_kanban_item"));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(kanbanService);
    }

    @Test
    void transitionKanbanItemShouldCallService() {
        KanbanItem transitioned = KanbanItem.builder()
                .id("kb-1")
                .title("Item")
                .status(KanbanStatus.IN_PROGRESS)
                .priority(KanbanPriority.MEDIUM)
                .createdAt(Instant.now())
                .build();
        when(kanbanService.transition(eq("kb-1"), eq(KanbanStatus.IN_PROGRESS), any())).thenReturn(transitioned);

        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "id", "kb-1",
                "newStatus", "IN_PROGRESS",
                "comment", "starting work"
        ));

        assertTrue(result.contains("IN_PROGRESS"));
        verify(kanbanService).transition(eq("kb-1"), eq(KanbanStatus.IN_PROGRESS), eq("starting work"));
    }

    @Test
    void transitionKanbanItemMissingIdShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "newStatus", "IN_PROGRESS"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(kanbanService);
    }

    @Test
    void transitionKanbanItemMissingStatusShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "id", "kb-1"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(kanbanService);
    }

    @Test
    void unknownToolShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "nonexistent_tool"));

        assertTrue(result.startsWith("Error"));
    }
}
