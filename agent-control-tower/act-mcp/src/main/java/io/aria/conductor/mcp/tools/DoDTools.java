package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.mcp.McpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Definition-of-Done stage-gate tools. Thin wrappers over {@link DoDService},
 * matching the Aria tool handler's argument contract so REST/MCP/Aria agree.
 */
@Component
@RequiredArgsConstructor
public class DoDTools implements McpTool {

    private final DoDService dodService;
    private final McpProperties mcpProperties;

    @Tool(name = "init_dod",
            description = "Initialize the Definition-of-Done stage gate for a task (idempotent — returns the existing record when one exists). Optional taskType is recorded for reporting.")
    public String initDod(
            @ToolParam(description = "Task id the DoD tracks") String taskId,
            @ToolParam(description = "Task type, e.g. bug or feature", required = false) String taskType) {
        try {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId is required");
            }
            DoDRecord record = dodService.init(taskId, blankToNull(taskType));
            return ToolResponses.ok(record);
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("DOD_INIT_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "submit_dod_review",
            description = "Submit a Definition-of-Done stage review for the task's current stage. passed=true advances the stage; passed=false on a required stage (dev/qa) blocks advancement and on an optional stage skips it.")
    public String submitDodReview(
            @ToolParam(description = "Task id the DoD tracks") String taskId,
            @ToolParam(description = "Reviewer identity") String reviewerId,
            @ToolParam(description = "true = passed, false = failed") boolean passed,
            @ToolParam(description = "Evidence for the decision", required = false) String evidence,
            @ToolParam(description = "Review comment", required = false) String comment,
            @ToolParam(description = "Reviewer display name", required = false) String reviewerName) {
        try {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId is required");
            }
            if (reviewerId == null || reviewerId.isBlank()) {
                throw new IllegalArgumentException("reviewerId is required");
            }
            DoDRecord record = dodService.review(taskId, reviewerId, blankToNull(reviewerName),
                    passed, blankToNull(evidence), blankToNull(comment));
            return ToolResponses.ok(record);
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("DOD_REVIEW_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_dod_status",
            description = "Get the Definition-of-Done status for a task: current stage, overall status, per-stage roll-up (PENDING/PASSED/FAILED/SKIPPED) and evidence count.")
    public String getDodStatus(
            @ToolParam(description = "Task id the DoD tracks") String taskId) {
        try {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId is required");
            }
            return ToolResponses.ok(dodService.buildStatusResponse(taskId));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("DOD_STATUS_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
