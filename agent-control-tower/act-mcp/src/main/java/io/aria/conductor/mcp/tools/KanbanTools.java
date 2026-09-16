package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.exception.InvalidStateTransitionException;
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
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Kanban board tools. Thin wrappers over the same services the REST controller
 * and the Aria tool handler call, so the board is reachable over MCP/REST at
 * parity. Error types mirror GlobalExceptionHandler's REST status mapping.
 */
@Component
@RequiredArgsConstructor
public class KanbanTools implements McpTool {

    private final KanbanService kanbanService;
    private final KanbanTransitionService kanbanTransitionService;
    private final McpProperties mcpProperties;

    @Tool(name = "create_kanban_item",
            description = "Create a kanban board item. Title is required; priority defaults to MEDIUM and status defaults to TODO. Returns the created item with id, status and priority.")
    public String createKanbanItem(
            @ToolParam(description = "Card title") String title,
            @ToolParam(description = "Card description", required = false) String description,
            @ToolParam(description = "KanbanPriority name (LOW/MEDIUM/HIGH/CRITICAL), or blank for MEDIUM", required = false) String priority,
            @ToolParam(description = "Display assignee", required = false) String assignee,
            @ToolParam(description = "Comma-separated labels", required = false) String labels,
            @ToolParam(description = "Linked run id", required = false) String linkedRunId) {
        try {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title is required");
            }
            CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                    .title(title)
                    .description(blankToNull(description))
                    .priority(priority == null || priority.isBlank() ? null : parsePriority(priority))
                    .assignee(blankToNull(assignee))
                    .labels(blankToNull(labels))
                    .linkedRunId(blankToNull(linkedRunId))
                    .build();
            return ToolResponses.ok(kanbanService.create(request));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KANBAN_CREATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_kanban_items",
            description = "List kanban board items, optionally filtered by status. Each item includes id, title, status, priority, assignee and pendingAskCount (HITL asks waiting on Review cards).")
    public String listKanbanItems(
            @ToolParam(description = "KanbanStatus name (BACKLOG/TODO/IN_PROGRESS/REVIEW/DONE/CANCELLED/BLOCKED), or blank for all", required = false) String status) {
        try {
            KanbanStatus filter = status == null || status.isBlank() ? null : parseFilterStatus(status);
            return ToolResponses.ok(kanbanService.list(filter));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KANBAN_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "update_kanban_item",
            description = "Update an existing kanban item's title, description, priority, assignee or labels. Blank fields are left unchanged; status changes must go through transition_kanban_item.")
    public String updateKanbanItem(
            @ToolParam(description = "Kanban item id") String id,
            @ToolParam(description = "New title", required = false) String title,
            @ToolParam(description = "New description", required = false) String description,
            @ToolParam(description = "KanbanPriority name (LOW/MEDIUM/HIGH/CRITICAL)", required = false) String priority,
            @ToolParam(description = "New assignee", required = false) String assignee,
            @ToolParam(description = "New comma-separated labels", required = false) String labels) {
        try {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id is required");
            }
            UpdateKanbanItemRequest request = UpdateKanbanItemRequest.builder()
                    .title(blankToNull(title))
                    .description(blankToNull(description))
                    .priority(priority == null || priority.isBlank() ? null : parsePriority(priority))
                    .assignee(blankToNull(assignee))
                    .labels(blankToNull(labels))
                    .build();
            return ToolResponses.ok(kanbanService.update(id, request));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KANBAN_UPDATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    /**
     * WARNING: transitions carry run side effects (see {@link KanbanTransitionService}).
     * Moving a card to TODO/IN_PROGRESS can spawn an agent run, REVIEW can create a
     * HITL ask, BACKLOG/TODO can pause the linked run and CANCELLED cancels it.
     */
    @Tool(name = "transition_kanban_item",
            description = "Move a kanban item to a new status. WARNING: this has side effects — it can spawn an agent run (TODO/IN_PROGRESS), resume or pause a linked run (REVIEW/BACKLOG/TODO), create HITL review asks (REVIEW) or cancel a run (CANCELLED). Repeating the current status is a no-op. A refused move answers an error envelope carrying the rejection code in errorType (NO_ELIGIBLE_AGENT, AGENT_NOT_ELIGIBLE, RUN_ALREADY_FINISHED, RUN_NOT_FOUND, CORRUPT_RUN_LINK, LINKED_RUN_ACTIVE) and the reason in message: a tool error here is a governance refusal, not a transport failure.")
    public String transitionKanbanItem(
            @ToolParam(description = "Kanban item id") String id,
            @ToolParam(description = "Target KanbanStatus name (BACKLOG/TODO/IN_PROGRESS/REVIEW/DONE/CANCELLED)") String status,
            @ToolParam(description = "Transition comment", required = false) String comment,
            @ToolParam(description = "Operator feedback for a re-dispatch", required = false) String feedback,
            @ToolParam(description = "Agent template hint for pickup", required = false) String agentTemplateId) {
        try {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id is required");
            }
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("status is required");
            }
            KanbanStatus target = parseTransitionStatus(status);

            // Idempotent no-op guard: repeating the current status must never reach
            // the transition service, so run side effects cannot re-fire.
            if (kanbanService.get(id).getStatus() == target) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", id);
                data.put("status", target.name());
                data.put("transitioned", false);
                data.put("message", "Kanban item " + id + " already " + target.name() + ".");
                return ToolResponses.ok(data);
            }

            TransitionRequest request = TransitionRequest.builder()
                    .status(target)
                    .comment(blankToNull(comment))
                    .feedback(blankToNull(feedback))
                    .agentTemplateId(blankToNull(agentTemplateId))
                    .build();
            KanbanItem item = kanbanTransitionService.transition(id, request);
            return ToolResponses.ok(item);
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (PickupRejectedException e) {
            return ToolResponses.error(e.code(), e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KANBAN_TRANSITION_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    /**
     * The six statuses a card may be moved to; exactly the targets the state
     * machine accepts. BLOCKED is retired (V52 migrated its rows to REVIEW) and
     * is rejected as a target, so it is deliberately absent here — this surface
     * must not advertise it as a legal move.
     */
    private static final List<KanbanStatus> TRANSITION_TARGETS = List.of(
            KanbanStatus.BACKLOG,
            KanbanStatus.TODO,
            KanbanStatus.IN_PROGRESS,
            KanbanStatus.REVIEW,
            KanbanStatus.DONE,
            KanbanStatus.CANCELLED);

    private static final String TRANSITION_TARGETS_TEXT = TRANSITION_TARGETS.stream()
            .map(Enum::name)
            .collect(Collectors.joining(", "));

    /**
     * Filter surface: every value a persisted card may carry. BLOCKED is retired
     * but legacy rows still hold it, and the board filter must keep finding them.
     */
    private static KanbanStatus parseFilterStatus(String raw) {
        try {
            return KanbanStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status '" + raw
                    + "'. Valid: BACKLOG, TODO, IN_PROGRESS, REVIEW, DONE, CANCELLED, BLOCKED");
        }
    }

    /**
     * Transition surface: only the six state-machine targets are moves. The raw
     * value is not echoed and BLOCKED is not listed — a client that asks for the
     * retired status must not read it back inside a "Valid: ..." list and take it
     * for an accepted target (the state machine answers "Unsupported target
     * status", and this message must state the same truth).
     */
    private static KanbanStatus parseTransitionStatus(String raw) {
        String normalized = raw.trim().toUpperCase();
        return TRANSITION_TARGETS.stream()
                .filter(target -> target.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid transition target status. Valid: " + TRANSITION_TARGETS_TEXT));
    }

    private static KanbanPriority parsePriority(String raw) {
        try {
            return KanbanPriority.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid priority '" + raw
                    + "'. Valid: LOW, MEDIUM, HIGH, CRITICAL");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
