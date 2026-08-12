package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component("approvalToolHandler")
public class ApprovalToolHandler implements ToolHandler {

    private final ApprovalGate approvalGate;
    private final ApprovalRepository approvalRepository;

    public ApprovalToolHandler(ApprovalGate approvalGate, ApprovalRepository approvalRepository) {
        this.approvalGate = approvalGate;
        this.approvalRepository = approvalRepository;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "list_pending_approvals" -> listPending();
                case "decide_approval" -> decide(arguments);
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("ApprovalToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    private String listPending() {
        List<Approval> approvals = approvalRepository.findByStatus(ApprovalStatus.PENDING);
        if (approvals.isEmpty()) return "No pending approvals.";
        StringBuilder sb = new StringBuilder("Pending approvals (" + approvals.size() + " total):\n");
        for (Approval a : approvals) {
            sb.append("  - ").append(a.getId())
                    .append(" | Run: ").append(a.getRunId() != null ? a.getRunId() : "N/A")
                    .append(" | Requested: ").append(a.getRequestedAt() != null ? a.getRequestedAt() : "N/A")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String decide(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        String decision = Objects.toString(args.get("decision"), "");
        String reason = Objects.toString(args.get("reason"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        if (decision.isEmpty()) return error("Missing required parameter: decision");
        UUID approvalId = UUID.fromString(id);
        // Governance: SPEC_REVIEW approvals require a human decision, not an agent's.
        Approval target = approvalRepository.findById(approvalId).orElse(null);
        if (target != null && target.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW) {
            return error("SPEC_REVIEW approvals must be decided by a human via the dashboard, not by an agent.");
        }
        boolean approved = Set.of("approve","approved","yes","true").contains(decision.toLowerCase());
        approvalGate.decideApproval(approvalId, approved, reason);
        return "Approval " + id + " " + (approved ? "approved" : "denied") + ".";
    }

    private String error(String msg) { return "Error: " + msg; }
}
