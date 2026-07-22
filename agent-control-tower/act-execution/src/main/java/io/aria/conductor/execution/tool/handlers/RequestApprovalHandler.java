package io.aria.conductor.execution.tool.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.approval.ApprovalDecision;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.execution.pipeline.ActionType;
import io.aria.conductor.execution.tool.ToolHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * HITL tool: allows a worker agent to explicitly request human approval mid-run.
 * Creates an Approval(PENDING), pauses the run, and blocks until the human decides
 * on the existing approval page. Reuses the proven ApprovalGate blocking-future model.
 */
@Slf4j
@Component("requestApprovalHandler")
@RequiredArgsConstructor
public class RequestApprovalHandler implements ToolHandler {

    private final ApprovalGate approvalGate;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String execute(Map<String, Object> arguments) {
        String summary = Objects.toString(arguments.get("summary"), "");
        String reason = Objects.toString(arguments.get("reason"), "");

        if (summary.isEmpty()) {
            return "Error: Missing required parameter: summary";
        }

        Object ctxObj = arguments.get("_runContext");
        if (!(ctxObj instanceof RunContext ctx)) {
            return "Error: request_approval can only be invoked within an agent run context";
        }

        log.info("Agent requests approval: runId={}, summary={}", ctx.getRunId(), summary);

        String actionPayload;
        try {
            actionPayload = OBJECT_MAPPER.writeValueAsString(Map.of("summary", summary, "reason", reason));
        } catch (Exception e) {
            log.error("Failed to serialize approval action payload", e);
            return "Error: Failed to build approval request";
        }

        Action action = new Action(
                "request_approval",
                ActionType.HIGH_RISK,
                actionPayload,
                ctx.getCurrentToolCallId() != null ? ctx.getCurrentToolCallId().toString() : null
        );

        ApprovalDecision decision = approvalGate.requestApproval(action, ctx);

        if (decision.isApproved()) {
            return "APPROVED: " + (decision.reason() != null ? decision.reason() : "Human approved the request.");
        } else {
            return "DENIED: " + (decision.reason() != null ? decision.reason() : "Human denied the request.");
        }
    }
}
