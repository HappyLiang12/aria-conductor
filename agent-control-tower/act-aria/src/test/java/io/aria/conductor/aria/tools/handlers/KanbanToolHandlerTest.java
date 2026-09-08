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

    @Mock
    private KanbanTransitionService kanbanTransitionService;

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
    void transitionDelegatesToOrchestratorWithStatusKey() {
        when(kanbanService.get("c1")).thenReturn(
                KanbanItem.builder().id("c1").title("Card").status(KanbanStatus.BACKLOG).build());
        when(kanbanTransitionService.transition(eq("c1"), any(TransitionRequest.class)))
                .thenReturn(KanbanItem.builder().id("c1").title("Card").status(KanbanStatus.TODO).build());

        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "id", "c1",
                "status", "TODO"
        ));

        assertTrue(result.contains("transitioned to TODO"));
        ArgumentCaptor<TransitionRequest> captor = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("c1"), captor.capture());
        assertEquals(KanbanStatus.TODO, captor.getValue().getStatus());
        verify(kanbanService).get("c1");
    }

    @Test
    void transitionLegacyNewStatusKeyStillWorks() {
        when(kanbanService.get("kb-1")).thenReturn(
                KanbanItem.builder().id("kb-1").title("Item").status(KanbanStatus.TODO).build());
        when(kanbanTransitionService.transition(eq("kb-1"), any(TransitionRequest.class)))
                .thenReturn(KanbanItem.builder().id("kb-1").title("Item").status(KanbanStatus.IN_PROGRESS).build());

        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "id", "kb-1",
                "newStatus", "IN_PROGRESS",
                "comment", "starting work"
        ));

        assertTrue(result.contains("IN_PROGRESS"));
        ArgumentCaptor<TransitionRequest> captor = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("kb-1"), captor.capture());
        assertEquals(KanbanStatus.IN_PROGRESS, captor.getValue().getStatus());
        assertEquals("starting work", captor.getValue().getComment());
    }

    @Test
    void transitionMapsFeedbackAndAgentTemplateId() {
        when(kanbanService.get("c1")).thenReturn(
                KanbanItem.builder().id("c1").title("Card").status(KanbanStatus.BACKLOG).build());
        when(kanbanTransitionService.transition(eq("c1"), any(TransitionRequest.class)))
                .thenReturn(KanbanItem.builder().id("c1").title("Card").status(KanbanStatus.TODO).build());

        handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "id", "c1",
                "status", "TODO",
                "feedback", "use streaming",
                "agentTemplateId", "ba-agent"
        ));

        ArgumentCaptor<TransitionRequest> captor = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("c1"), captor.capture());
        assertEquals("use streaming", captor.getValue().getFeedback());
        assertEquals("ba-agent", captor.getValue().getAgentTemplateId());
    }

    @Test
    void transitionDualKeysStatusWinsOverLegacyNewStatus() {
        // The TS MCP contract sends "status"; the legacy Aria contract sends
        // "newStatus". When both keys arrive, the modern "status" key must win.
        when(kanbanService.get("c1")).thenReturn(
                KanbanItem.builder().id("c1").title("Card").status(KanbanStatus.TODO).build());
        when(kanbanTransitionService.transition(eq("c1"), any(TransitionRequest.class)))
                .thenReturn(KanbanItem.builder().id("c1").title("Card").status(KanbanStatus.REVIEW).build());

        handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "id", "c1",
                "status", "REVIEW",
                "newStatus", "DONE"
        ));

        ArgumentCaptor<TransitionRequest> captor = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(kanbanTransitionService).transition(eq("c1"), captor.capture());
        assertEquals(KanbanStatus.REVIEW, captor.getValue().getStatus());
    }

    @Test
    void transitionNoOpReportsAlreadyInsteadOfTransitioned() {
        // Same-status request is an orchestrator no-op: no delegation and an
        // honest "already" message instead of a claimed transition.
        when(kanbanService.get("c1")).thenReturn(
                KanbanItem.builder().id("c1").title("Card").status(KanbanStatus.REVIEW).build());

        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "id", "c1",
                "status", "REVIEW"
        ));

        assertEquals("Kanban item c1 already REVIEW.", result);
        verify(kanbanService).get("c1");
        verifyNoInteractions(kanbanTransitionService);
    }

    @Test
    void transitionInvalidStatusMentionsBacklog() {
        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "id", "c1",
                "status", "SHIPPED"
        ));

        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("BACKLOG"));
        verifyNoInteractions(kanbanTransitionService, kanbanService);
    }

    @Test
    void transitionKanbanItemMissingIdShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "newStatus", "IN_PROGRESS"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(kanbanService, kanbanTransitionService);
    }

    @Test
    void transitionKanbanItemMissingStatusShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "transition_kanban_item",
                "id", "kb-1"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(kanbanService, kanbanTransitionService);
    }

    @Test
    void unknownToolShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "nonexistent_tool"));

        assertTrue(result.startsWith("Error"));
    }
}
