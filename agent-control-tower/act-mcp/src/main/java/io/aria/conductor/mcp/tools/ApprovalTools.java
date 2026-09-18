package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.approval.AcpDecisionRejectedException;
import io.aria.conductor.execution.approval.ApprovalDecisionService;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.mcp.McpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ApprovalTools implements McpTool {

    private final ApprovalQueryService approvalQueryService;
    private final ApprovalDecisionService approvalDecisionService;
    private final McpProperties mcpProperties;

    @Tool(name = "list_approvals",
            description = "List approval gates. Optional status (PENDING/APPROVED/DENIED/EXPIRED). SPEC_REVIEW approvals carry markdown content and knowledgeItemId; toolCallId is null for them.")
    public String listApprovals(
            @ToolParam(description = "ApprovalStatus name or blank for recent (max 200)", required = false) String status) {
        try {
            ApprovalStatus s = status == null || status.isBlank() ? null : parseStatus(status);
            return ToolResponses.ok(approvalQueryService.list(s));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("APPROVAL_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "decide_approval",
            description = "Decide an approval (approve or deny). Legacy gates ignore non-PENDING repeats (idempotent). ACP permission asks return a typed error code (EXPIRED, ALREADY_DECIDED, UNSUPPORTED_OPTIONS, INCONSISTENT_ASK) when they cannot take the decision.")
    public String decideApproval(
            @ToolParam(description = "Approval id") UUID approvalId,
            @ToolParam(description = "true = approve, false = deny") boolean approved,
            @ToolParam(description = "Decision reason", required = false) String reason) {
        try {
            approvalDecisionService.decide(approvalId, approved, reason);
            return ToolResponses.ok(java.util.Map.of("approvalId", approvalId.toString(), "approved", approved));
        } catch (AcpDecisionRejectedException e) {
            // An ACP ask that cannot take the decision: the code tells the caller whether the
            // ask is gone (EXPIRED / ALREADY_DECIDED) or can never be approved as it stands.
            return ToolResponses.error(e.code().name(), e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("DECISION_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    private static ApprovalStatus parseStatus(String raw) {
        try {
            return ApprovalStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status '" + raw
                    + "'. Valid: PENDING, APPROVED, DENIED, EXPIRED");
        }
    }
}
