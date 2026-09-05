package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
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
    private final ApprovalGate approvalGate;
    private final McpProperties mcpProperties;

    @Tool(name = "list_approvals",
            description = "List approval gates. Optional status (PENDING/APPROVED/DENIED/EXPIRED). SPEC_REVIEW approvals carry markdown content and knowledgeItemId; toolCallId is null for them.")
    public String listApprovals(
            @ToolParam(description = "ApprovalStatus name or blank for recent (max 200)", required = false) String status) {
        try {
            ApprovalStatus s = status == null || status.isBlank() ? null : ApprovalStatus.valueOf(status.trim().toUpperCase());
            return ToolResponses.ok(approvalQueryService.list(s));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("APPROVAL_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "decide_approval",
            description = "Decide a PENDING approval gate (approve or deny). Non-PENDING approvals are ignored by the gate (idempotent).")
    public String decideApproval(
            @ToolParam(description = "Approval id") UUID approvalId,
            @ToolParam(description = "true = approve, false = deny") boolean approved,
            @ToolParam(description = "Decision reason", required = false) String reason) {
        try {
            approvalGate.decideApproval(approvalId, approved, reason);
            return ToolResponses.ok(java.util.Map.of("approvalId", approvalId.toString(), "approved", approved));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("DECISION_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}
