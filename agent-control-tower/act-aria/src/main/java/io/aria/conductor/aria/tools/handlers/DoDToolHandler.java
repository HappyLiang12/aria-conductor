package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.dod.dto.DoDStatusResponse;
import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component("dodToolHandler")
public class DoDToolHandler implements ToolHandler {

    private final DoDService dodService;

    public DoDToolHandler(DoDService dodService) {
        this.dodService = dodService;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "init_dod" -> initDod(arguments);
                case "submit_dod_review" -> submitDodReview(arguments);
                case "get_dod_status" -> getDodStatus(arguments);
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("DoDToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    private String initDod(Map<String, Object> args) {
        String taskId = Objects.toString(args.get("taskId"), "");
        if (taskId.isEmpty()) return error("Missing required parameter: taskId");
        String taskType = Objects.toString(args.get("taskType"), null);
        DoDRecord record = dodService.init(taskId, blankToNull(taskType));
        return "DoD initialized for task '" + taskId + "' (id: " + record.getId()
            + ", stage: " + record.getCurrentStage() 
            + ", status: " + record.getOverallStatus() + ")";
    }

    private String submitDodReview(Map<String, Object> args) {
        String taskId = Objects.toString(args.get("taskId"), "");
        String reviewerId = Objects.toString(args.get("reviewerId"), "");
        Boolean passedRaw = null;
        if (args.containsKey("passed")) {
            Object val = args.get("passed");
            if (val instanceof Boolean) passedRaw = (Boolean) val;
            else if (val instanceof String) passedRaw = Boolean.parseBoolean((String) val);
        }
        if (taskId.isEmpty()) return error("Missing required parameter: taskId");
        if (reviewerId.isEmpty()) return error("Missing required parameter: reviewerId");
        if (passedRaw == null) return error("Missing required parameter: passed");
        String reviewerName = Objects.toString(args.get("reviewerName"), null);
        String evidence = Objects.toString(args.get("evidence"), null);
        String comment = Objects.toString(args.get("comment"), null);
        DoDRecord record = dodService.review(taskId, reviewerId, blankToNull(reviewerName), passedRaw, blankToNull(evidence), blankToNull(comment));
        return "DoD review submitted for task '" + taskId + "' (id: " + record.getId()
            + ") at stage '" + record.getCurrentStage() 
            + "'. Decision: " + (passedRaw ? "PASSED" : "FAILED");
    }

    private String getDodStatus(Map<String, Object> args) {
        String taskId = Objects.toString(args.get("taskId"), "");
        if (taskId.isEmpty()) return error("Missing required parameter: taskId");
        DoDStatusResponse response = dodService.buildStatusResponse(taskId);
        StringBuilder sb = new StringBuilder("DoD status for task '" + taskId + "':\n");
        sb.append("  Stage: ").append(response.currentStage()).append("\n");
        sb.append("  Overall Status: ").append(response.overallStatus()).append("\n");
        if (response.stages() != null && !response.stages().isEmpty()) {
            sb.append("  Stage Reviews:\n");
            for (var s : response.stages()) {
                sb.append("    - ").append(s.stage()).append(": ").append(s.status())
                  .append(" (").append(s.reviewCount()).append(" reviews)\n");
            }
        }
        sb.append("  Evidence Count: ").append(response.evidenceCount());
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}
