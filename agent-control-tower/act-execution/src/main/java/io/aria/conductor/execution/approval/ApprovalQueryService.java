package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
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
 * including the bounded most-recent-first page (F21) and the distinct batch
 * ToolCall lookup (N+1 avoidance).
 */
@Service
@RequiredArgsConstructor
public class ApprovalQueryService {

    private final ApprovalRepository approvalRepository;
    private final ToolCallRepository toolCallRepository;
    private final ToolRiskResolver toolRiskResolver;

    @Transactional(readOnly = true)
    public List<ApprovalController.ApprovalDetail> list(ApprovalStatus status) {
        List<Approval> approvals = status != null
                ? approvalRepository.findByStatus(status)
                : approvalRepository.findAll(
                        PageRequest.of(0, 200, Sort.by("requestedAt").descending())).getContent();
        // Batch-load tool calls to avoid N+1; risk tier comes from the cached ToolRiskResolver.
        List<UUID> toolCallIds = approvals.stream()
                .map(Approval::getToolCallId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, ToolCall> toolCalls = toolCallRepository.findAllById(toolCallIds).stream()
                .collect(Collectors.toMap(ToolCall::getId, Function.identity(), (a, b) -> a));
        return approvals.stream()
                .map(a -> toDetail(a, toolCalls.get(a.getToolCallId())))
                .toList();
    }

    private ApprovalController.ApprovalDetail toDetail(Approval a, ToolCall tc) {
        String toolName = tc != null ? tc.getToolName() : null;
        String riskTier = toolName != null ? toolRiskResolver.resolve(toolName).name() : null;
        return new ApprovalController.ApprovalDetail(
                a.getId(), a.getRunId(), a.getToolCallId(), a.getStatus(), a.getReason(),
                a.getRequestedAt(), a.getDecidedAt(), a.getExpiresAt(),
                a.getApprovalType() != null ? a.getApprovalType().name() : "TOOL_CALL",
                a.getContent(),
                a.getContentKind() != null ? a.getContentKind().name() : null,
                a.getKnowledgeItemId(),
                toolName, tc != null ? tc.getArguments() : null, riskTier);
    }
}
