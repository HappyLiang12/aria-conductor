package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.exception.PickupRejectedException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanPriority;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.kanban.KanbanTransitionService;
import io.aria.conductor.execution.kanban.TransitionRequest;
import io.aria.conductor.execution.kanban.UpdateKanbanItemRequest;
import io.aria.conductor.execution.mcp.McpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KanbanToolsTest {

    @Mock KanbanService kanbanService;
    @Mock KanbanTransitionService kanbanTransitionService;
    McpProperties mcpProperties;
    KanbanTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new KanbanTools(kanbanService, kanbanTransitionService, mcpProperties);
    }

    private KanbanItem item(String id, KanbanStatus status) {
        return KanbanItem.builder()
                .id(id)
                .title("Fix login")
                .status(status)
                .priority(KanbanPriority.MEDIUM)
                .build();
    }

    @Test
    void createKanbanItem_delegatesAndParsesPriority() {
        when(kanbanService.create(any(CreateKanbanItemRequest.class)))
                .thenReturn(item("k1", KanbanStatus.TODO));

        String json = tools.createKanbanItem("Fix login", "Cannot log in", "high", "alice", "bug", null);

        ArgumentCaptor<CreateKanbanItemRequest> captor = ArgumentCaptor.forClass(CreateKanbanItemRequest.class);
        verify(kanbanService).create(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo(KanbanPriority.HIGH);
        assertThat(captor.getValue().getLabels()).isEqualTo("bug");
        assertThat(json).contains("\"ok\":true").contains("k1").contains("Fix login");
    }

    @Test
    void createKanbanItem_blankTitleIsValidationErrorWithoutStack() {
        String json = tools.createKanbanItem(" ", null, null, null, null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("title is required");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void createKanbanItem_invalidPriorityIsValidationError() {
        String json = tools.createKanbanItem("Fix login", null, "URGENT", null, null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        assertThat(json).contains("Valid: LOW, MEDIUM, HIGH, CRITICAL");
    }

    @Test
    void listKanbanItems_filtersByStatus() {
        when(kanbanService.list(KanbanStatus.REVIEW)).thenReturn(List.of(item("k2", KanbanStatus.REVIEW)));

        String json = tools.listKanbanItems("review");

        assertThat(json).contains("\"ok\":true").contains("k2").contains("REVIEW");
    }

    @Test
    void listKanbanItems_blankListsAll() {
        when(kanbanService.list(isNull())).thenReturn(List.of());

        String json = tools.listKanbanItems("");

        assertThat(json).contains("\"ok\":true");
    }

    @Test
    void listKanbanItems_invalidStatusIsValidationError() {
        String json = tools.listKanbanItems("NOPE");

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void updateKanbanItem_delegates() {
        when(kanbanService.update(eq("k1"), any(UpdateKanbanItemRequest.class)))
                .thenReturn(item("k1", KanbanStatus.TODO));

        String json = tools.updateKanbanItem("k1", "New title", null, "critical", null, null);

        assertThat(json).contains("\"ok\":true").contains("k1");
    }

    @Test
    void updateKanbanItem_missingIdIsValidationError() {
        String json = tools.updateKanbanItem("", "New title", null, null, null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("id is required");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void transitionKanbanItem_sameStatusIsSideEffectFreeNoOp() {
        when(kanbanService.get("k1")).thenReturn(item("k1", KanbanStatus.TODO));

        String json = tools.transitionKanbanItem("k1", "TODO", null, null, null);

        assertThat(json).contains("\"ok\":true").contains("already TODO").contains("\"transitioned\":false");
        verifyNoInteractions(kanbanTransitionService);
    }

    @Test
    void transitionKanbanItem_delegatesToTransitionService() {
        when(kanbanService.get("k1")).thenReturn(item("k1", KanbanStatus.BACKLOG));
        when(kanbanTransitionService.transition(eq("k1"), any(TransitionRequest.class)))
                .thenReturn(item("k1", KanbanStatus.TODO));

        String json = tools.transitionKanbanItem("k1", "todo", "pick it up", "retry", "tpl-1");

        assertThat(json).contains("\"ok\":true").contains("TODO");
    }

    @Test
    void transitionRejectionSurfacesTheStructuredCode() {
        when(kanbanService.get("item-1")).thenReturn(item("item-1", KanbanStatus.TODO));
        when(kanbanTransitionService.transition(anyString(), any())).thenThrow(
                new PickupRejectedException("NO_ELIGIBLE_AGENT", "none eligible", Map.of("evaluated", 1)));

        String result = tools.transitionKanbanItem("item-1", "IN_PROGRESS", null, null, null);

        assertThat(result).contains("\"ok\":false");
        assertThat(result).contains("\"errorType\":\"NO_ELIGIBLE_AGENT\"");
    }

    @Test
    void transitionKanbanItem_missingItemMapsNotFound() {
        when(kanbanService.get("missing")).thenThrow(new ResourceNotFoundException("KanbanItem", "missing"));

        String json = tools.transitionKanbanItem("missing", "DONE", null, null, null);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }
}
