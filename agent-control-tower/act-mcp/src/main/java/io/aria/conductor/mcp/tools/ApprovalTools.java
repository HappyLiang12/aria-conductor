package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.approval.PermissionChoice;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.mcp.McpActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Approval surfaces of the platform MCP endpoint. {@code decide_approval} is
 * OPERATOR_ONLY in the reviewed {@link ToolPolicyRegistry}: the MCP transports
 * carry run-scoped worker credentials only, so a worker can never approve
 * itself. The tool resolves identity exclusively from the transport context
 * (never from tool JSON) and dispatches the decision to the permission
 * coordinator, which owns the operator check, the state/expiry re-check and the
 * delivery tracking of native permission asks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalTools implements McpTool {

    static final String DECIDE_APPROVAL = "decide_approval";

    private final ApprovalQueryService approvalQueryService;
    private final PermissionCoordinator permissionCoordinator;
    private final ToolPolicyRegistry toolPolicies;
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
            description = "Decide a PENDING approval gate (approve or deny). Operator authority only: the transport-resolved caller must be the authenticated OPERATOR — a run-scoped worker credential is refused with FORBIDDEN and no approval changes state.")
    public String decideApproval(
            @ToolParam(description = "Approval id") UUID approvalId,
            @ToolParam(description = "true = approve, false = deny") boolean approved,
            @ToolParam(description = "Decision reason (audit note; a native permission ask records its own decision reason)",
                    required = false) String reason,
            ToolContext toolContext) {
        try {
            ActorPrincipal actor = McpActorContext.require(toolContext);
            // Reviewed tool policy: decide_approval is OPERATOR_ONLY, and an
            // unclassified tool name stays gated. The MCP surface is the worker
            // surface, so this refuses every run-scoped credential.
            toolPolicies.requireAuthority(DECIDE_APPROVAL, actor);
            log.info("MCP approval decision requested: id={}, approved={}, reason={}", approvalId, approved, reason);
            permissionCoordinator.decide(approvalId,
                    approved ? PermissionChoice.ALLOW_ONCE : PermissionChoice.DENY, actor);
            return ToolResponses.ok(java.util.Map.of(
                    "approvalId", approvalId.toString(), "approved", approved, "status", "processed"));
        } catch (SecurityException e) {
            return ToolResponses.error("FORBIDDEN", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
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
