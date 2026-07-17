package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.execution.kanban.*;
import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component("kanbanToolHandler")
public class KanbanToolHandler implements ToolHandler {

    private final KanbanService kanbanService;

    public KanbanToolHandler(KanbanService kanbanService) {
        this.kanbanService = kanbanService;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "create_kanban_item" -> createKanbanItem(arguments);
                case "list_kanban_items" -> listKanbanItems(arguments);
                case "update_kanban_item" -> updateKanbanItem(arguments);
                case "transition_kanban_item" -> transitionKanbanItem(arguments);
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("KanbanToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    private String createKanbanItem(Map<String, Object> args) {
        String title = Objects.toString(args.get("title"), "");
        if (title.isEmpty()) return error("Missing required parameter: title");

        KanbanPriority priority = null;
        String priorityStr = Objects.toString(args.get("priority"), "");
        if (!priorityStr.isEmpty()) {
            try {
                priority = KanbanPriority.valueOf(priorityStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return error("Invalid priority: " + priorityStr + ". Valid: LOW, MEDIUM, HIGH, CRITICAL");
            }
        }

        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title(title)
                .description(blankToNull(Objects.toString(args.get("description"), "")))
                .priority(priority)
                .assignee(blankToNull(Objects.toString(args.get("assignee"), "")))
                .labels(blankToNull(Objects.toString(args.get("labels"), "")))
                .linkedRunId(blankToNull(Objects.toString(args.get("linkedRunId"), "")))
                .build();
        KanbanItem item = kanbanService.create(request);
        return "Kanban item '" + item.getTitle() + "' created (id: " + item.getId() + ", status: " + (item.getStatus() != null ? item.getStatus().name() : "N/A") + ")";
    }

    private String listKanbanItems(Map<String, Object> args) {
        String statusStr = Objects.toString(args.get("status"), "");
        KanbanStatus status = null;
        if (!statusStr.isEmpty()) {
            try {
                status = KanbanStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return error("Invalid status: " + statusStr + ". Valid: TODO, IN_PROGRESS, DONE, BLOCKED, CANCELLED");
            }
        }

        List<KanbanItem> items = kanbanService.list(status);
        if (items.isEmpty()) return "No kanban items found.";
        StringBuilder sb = new StringBuilder("Kanban items (" + items.size() + " total):\n");
        for (KanbanItem item : items) {
            sb.append("  - ").append(item.getId())
                    .append(" | ").append(item.getTitle())
                    .append(" | Status: ").append(item.getStatus() != null ? item.getStatus().name() : "N/A")
                    .append(" | Priority: ").append(item.getPriority() != null ? item.getPriority().name() : "N/A")
                    .append(" | Assignee: ").append(item.getAssignee() != null ? item.getAssignee() : "N/A")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String updateKanbanItem(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");

        UpdateKanbanItemRequest request = UpdateKanbanItemRequest.builder()
                .title(blankToNull(Objects.toString(args.get("title"), "")))
                .description(blankToNull(Objects.toString(args.get("description"), "")))
                .assignee(blankToNull(Objects.toString(args.get("assignee"), "")))
                .labels(blankToNull(Objects.toString(args.get("labels"), "")))
                .build();

        String priorityStr = Objects.toString(args.get("priority"), "");
        if (!priorityStr.isEmpty()) {
            try {
                request.setPriority(KanbanPriority.valueOf(priorityStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                return error("Invalid priority: " + priorityStr + ". Valid: LOW, MEDIUM, HIGH, CRITICAL");
            }
        }

        KanbanItem item = kanbanService.update(id, request);
        return "Kanban item '" + item.getTitle() + "' updated (id: " + item.getId() + ")";
    }

    private String transitionKanbanItem(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        String statusStr = Objects.toString(args.get("newStatus"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        if (statusStr.isEmpty()) return error("Missing required parameter: newStatus");

        KanbanStatus status;
        try {
            status = KanbanStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return error("Invalid status: " + statusStr + ". Valid: TODO, IN_PROGRESS, DONE, BLOCKED, CANCELLED");
        }

        String comment = Objects.toString(args.get("comment"), null);
        KanbanItem item = kanbanService.transition(id, status, comment);
        return "Kanban item " + id + " transitioned to " + status.name() + ".";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}
