package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.pipeline.ToolRiskResolver;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalRepository approvalRepository;
    private final ApprovalGate approvalGate;
    private final ToolCallRepository toolCallRepository;
    private final ToolRiskResolver toolRiskResolver;

    public ApprovalController(ApprovalRepository approvalRepository,
                              ApprovalGate approvalGate,
                              ToolCallRepository toolCallRepository,
                              ToolRiskResolver toolRiskResolver) {
        this.approvalRepository = approvalRepository;
        this.approvalGate = approvalGate;
        this.toolCallRepository = toolCallRepository;
        this.toolRiskResolver = toolRiskResolver;
    }

    /**
     * Approval view enriched with the underlying tool name, arguments and governance risk tier
     * (#24), so operators can make an informed approve/deny decision instead of approving blind.
     * Superset of the previous raw {@link Approval} payload (backward compatible).
     */
    public record ApprovalDetail(
            UUID id,
            UUID runId,
            UUID toolCallId,
            ApprovalStatus status,
            String reason,
            Instant requestedAt,
            Instant decidedAt,
            Instant expiresAt,
            String approvalType,
            String content,
            String contentKind,
            UUID knowledgeItemId,
            String toolName,
            String arguments,
            String riskTier) {}

    /**
     * List approvals, optionally filtered by {@link ApprovalStatus}. With no status the endpoint
     * returns a bounded, most-recent-first page (F21) instead of an unbounded {@code findAll()},
     * so the approvals/history UI keeps working against a large table.
     */
    @GetMapping
    public ResponseEntity<List<ApprovalDetail>> listApprovals(
            @RequestParam(required = false) ApprovalStatus status) {
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
        List<ApprovalDetail> details = approvals.stream()
                .map(a -> toDetail(a, toolCalls.get(a.getToolCallId())))
                .toList();
        return ResponseEntity.ok(details);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApprovalDetail> getApproval(@PathVariable UUID id) {
        return approvalRepository.findById(id)
                .map(a -> {
                    ToolCall tc = a.getToolCallId() != null
                            ? toolCallRepository.findById(a.getToolCallId()).orElse(null)
                            : null;
                    return ResponseEntity.ok(toDetail(a, tc));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(
            @PathVariable UUID id,
            @RequestBody DecideApprovalRequest request) {
        log.info("Approval decision: id={}, approved={}", id, request.approved());

        try {
            approvalGate.decideApproval(id, request.approved(), request.reason());
            return ResponseEntity.ok(Map.of(
                    "approvalId", id,
                    "approved", request.approved(),
                    "status", "processed"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    private ApprovalDetail toDetail(Approval a, ToolCall tc) {
        String toolName = tc != null ? tc.getToolName() : null;
        String riskTier = toolName != null ? toolRiskResolver.resolve(toolName).name() : null;
        return new ApprovalDetail(
                a.getId(), a.getRunId(), a.getToolCallId(), a.getStatus(), a.getReason(),
                a.getRequestedAt(), a.getDecidedAt(), a.getExpiresAt(),
                a.getApprovalType() != null ? a.getApprovalType().name() : "TOOL_CALL",
                a.getContent(),
                a.getContentKind() != null ? a.getContentKind().name() : null,
                a.getKnowledgeItemId(),
                toolName, tc != null ? tc.getArguments() : null, riskTier);
    }

    public record DecideApprovalRequest(boolean approved, String reason) {}
}
