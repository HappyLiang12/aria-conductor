package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.controller.ApprovalController;
import io.aria.conductor.execution.pipeline.ToolRiskResolver;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-side approval queries shared by the REST controller and MCP ApprovalTools.
 * Mirrors ApprovalController's list/detail assembly (entity -> ApprovalDetail),
 * including the bounded most-recent-first page (F21), the distinct batch
 * ToolCall lookup and the batch ACP companion lookup (N+1 avoidance).
 */
@Service
@RequiredArgsConstructor
public class ApprovalQueryService {

    private final ApprovalRepository approvalRepository;
    private final ToolCallRepository toolCallRepository;
    private final ToolRiskResolver toolRiskResolver;
    private final AcpPermissionRequestRepository acpPermissionRequestRepository;

    @Transactional(readOnly = true)
    public List<ApprovalController.ApprovalDetail> list(ApprovalStatus status) {
        List<Approval> approvals = status != null
                ? approvalRepository.findByStatus(status)
                : approvalRepository.findAll(
                        PageRequest.of(0, 200, Sort.by("requestedAt").descending())).getContent();
        return toDetails(approvals);
    }

    /** Asks surfaced on a kanban card (Review column panel). */
    @Transactional(readOnly = true)
    public List<ApprovalController.ApprovalDetail> listByKanbanItem(String kanbanItemId) {
        return toDetails(approvalRepository.findByKanbanItemId(kanbanItemId));
    }

    private List<ApprovalController.ApprovalDetail> toDetails(List<Approval> approvals) {
        // Batch-load tool calls to avoid N+1; risk tier comes from the cached ToolRiskResolver.
        List<UUID> toolCallIds = approvals.stream()
                .map(Approval::getToolCallId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, ToolCall> toolCalls = toolCallRepository.findAllById(toolCallIds).stream()
                .collect(Collectors.toMap(ToolCall::getId, Function.identity(), (a, b) -> a));
        Map<UUID, AcpPermissionRequest> companions = companionsByApprovalId(approvals);
        return approvals.stream()
                .map(a -> toDetail(a, toolCalls.get(a.getToolCallId()), companions.get(a.getId())))
                .toList();
    }

    /** ACP companion rows are keyed by approval id; loaded in one batch query (never per row). */
    private Map<UUID, AcpPermissionRequest> companionsByApprovalId(List<Approval> approvals) {
        List<UUID> approvalIds = approvals.stream()
                .map(Approval::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (approvalIds.isEmpty()) {
            return Map.of();
        }
        return acpPermissionRequestRepository.findByApprovalIdIn(approvalIds).stream()
                .collect(Collectors.toMap(AcpPermissionRequest::getApprovalId, Function.identity(),
                        (a, b) -> a));
    }

    private ApprovalController.ApprovalDetail toDetail(Approval a, ToolCall tc,
                                                       AcpPermissionRequest companion) {
        // ACP asks have no ToolCall row: the companion's tool name is the only identity there is,
        // and since it is not a registry tool, arguments and riskTier stay null.
        String toolName = tc != null ? tc.getToolName()
                : companion != null ? companion.getToolName() : null;
        String riskTier = tc != null ? toolRiskResolver.resolve(tc.getToolName()).name() : null;
        return new ApprovalController.ApprovalDetail(
                a.getId(), a.getRunId(), a.getToolCallId(), a.getStatus(), a.getReason(),
                a.getRequestedAt(), a.getDecidedAt(), a.getExpiresAt(),
                a.getApprovalType() != null ? a.getApprovalType().name() : "TOOL_CALL",
                a.getContent(),
                a.getContentKind() != null ? a.getContentKind().name() : null,
                a.getKnowledgeItemId(),
                toolName, tc != null ? tc.getArguments() : null, riskTier,
                a.getKanbanItemId(),
                a.getAskType() != null ? a.getAskType().name() : null,
                a.getContextMd(), a.getOptionsJson(), a.getAnswer(),
                a.getSource() != null ? a.getSource().name() : "LEGACY_GATE",
                companion != null ? companion.getDeliveryState() : null,
                companion != null ? companion.getDisplayJson() : null);
    }
}
