package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.tool.ToolPolicy;
import io.aria.conductor.execution.controller.ApprovalController;
import io.aria.conductor.execution.pipeline.ToolRiskResolver;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.security.CredentialRedactor;
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
        return approvals.stream()
                .map(a -> toDetail(a, toolCalls.get(a.getToolCallId())))
                .toList();
    }

    private ApprovalController.ApprovalDetail toDetail(Approval a, ToolCall tc) {
        // An ACP permission ask has no ToolCall link; the ask itself now carries the
        // tool identity and arguments (V76), with the linked call winning for legacy
        // gate approvals. Arguments are redacted on read as well as on write, so rows
        // persisted before the redaction existed can never leak a credential.
        String toolName = tc != null ? tc.getToolName() : a.getToolName();
        String arguments = CredentialRedactor.redact(tc != null ? tc.getArguments() : a.getArguments());
        String riskTier = toolName != null
                ? toolRiskResolver.resolve(toolName).name()
                : a.getRiskTier();
        // B1 (spec §5.3): authority alongside blast radius, from the one catalog. Null for an
        // unclassified tool name rather than a guessed policy.
        ToolPolicy policy = toolName != null ? toolRiskResolver.policyOf(toolName) : null;
        String toolPolicy = policy != null ? policy.name() : null;
        return new ApprovalController.ApprovalDetail(
                a.getId(), a.getRunId(), a.getToolCallId(), a.getStatus(), a.getReason(),
                a.getRequestedAt(), a.getDecidedAt(), a.getExpiresAt(),
                a.getApprovalType() != null ? a.getApprovalType().name() : "TOOL_CALL",
                a.getContent(),
                a.getContentKind() != null ? a.getContentKind().name() : null,
                a.getKnowledgeItemId(),
                toolName, arguments, riskTier,
                a.getKanbanItemId(),
                a.getAskType() != null ? a.getAskType().name() : null,
                a.getSource() == null ? null : a.getSource().name(),
                a.getContextMd(), a.getOptionsJson(), a.getAnswer(), toolPolicy);
    }
}
