package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.approval.AcpDecisionRejectedException;
import io.aria.conductor.execution.approval.ApprovalAnswerService;
import io.aria.conductor.execution.approval.ApprovalDecisionService;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.pipeline.ToolRiskResolver;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalRepository approvalRepository;
    private final ApprovalDecisionService approvalDecisionService;
    private final ToolCallRepository toolCallRepository;
    private final ToolRiskResolver toolRiskResolver;
    private final AcpPermissionRequestRepository acpPermissionRequestRepository;
    private final ApprovalQueryService approvalQueryService;
    private final ApprovalAnswerService approvalAnswerService;

    /** Convenience constructor building its own query service (direct-instantiation tests). */
    public ApprovalController(ApprovalRepository approvalRepository,
                              ApprovalDecisionService approvalDecisionService,
                              ToolCallRepository toolCallRepository,
                              ToolRiskResolver toolRiskResolver,
                              AcpPermissionRequestRepository acpPermissionRequestRepository) {
        this(approvalRepository, approvalDecisionService, toolCallRepository, toolRiskResolver,
                acpPermissionRequestRepository,
                new ApprovalQueryService(approvalRepository, toolCallRepository, toolRiskResolver,
                        acpPermissionRequestRepository),
                new ApprovalAnswerService(approvalRepository));
    }

    @Autowired
    public ApprovalController(ApprovalRepository approvalRepository,
                              ApprovalDecisionService approvalDecisionService,
                              ToolCallRepository toolCallRepository,
                              ToolRiskResolver toolRiskResolver,
                              AcpPermissionRequestRepository acpPermissionRequestRepository,
                              ApprovalQueryService approvalQueryService,
                              ApprovalAnswerService approvalAnswerService) {
        this.approvalRepository = approvalRepository;
        this.approvalDecisionService = approvalDecisionService;
        this.toolCallRepository = toolCallRepository;
        this.toolRiskResolver = toolRiskResolver;
        this.acpPermissionRequestRepository = acpPermissionRequestRepository;
        this.approvalQueryService = approvalQueryService;
        this.approvalAnswerService = approvalAnswerService;
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
            String riskTier,
            // HITL ask fields: the Review panel renders the QUESTION prompt,
            // options and recorded answer from these (kanban card surface).
            String kanbanItemId,
            String askType,
            String contextMd,
            String optionsJson,
            String answer,
            // V60 provenance: source is never null (LEGACY_GATE for rows without one);
            // deliveryState/displayJson come from the ACP companion row when it exists.
            String source,
            String deliveryState,
            String displayJson) {}

    /**
     * List approvals, optionally filtered by {@link ApprovalStatus} or by the kanban card the
     * ask is surfaced on ({@code ?kanbanItemId=} takes precedence over {@code status}). With no
     * filter the endpoint returns a bounded, most-recent-first page (F21) instead of an
     * unbounded {@code findAll()}, so the approvals/history UI keeps working against a large
     * table. Assembly (entity -> {@link ApprovalDetail} enrichment) is shared with the MCP
     * ApprovalTools via {@link ApprovalQueryService}.
     */
    @GetMapping
    public ResponseEntity<List<ApprovalDetail>> listApprovals(
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(required = false) String kanbanItemId) {
        if (kanbanItemId != null && !kanbanItemId.isBlank()) {
            return ResponseEntity.ok(approvalQueryService.listByKanbanItem(kanbanItemId));
        }
        return ResponseEntity.ok(approvalQueryService.list(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApprovalDetail> getApproval(@PathVariable UUID id) {
        return approvalRepository.findById(id)
                .map(a -> {
                    ToolCall tc = a.getToolCallId() != null
                            ? toolCallRepository.findById(a.getToolCallId()).orElse(null)
                            : null;
                    AcpPermissionRequest companion =
                            acpPermissionRequestRepository.findById(a.getId()).orElse(null);
                    return ResponseEntity.ok(toDetail(a, tc, companion));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Decide an approval through {@link ApprovalDecisionService}, which dispatches by the row's
     * source: legacy rows keep the three-key ack they always had, while an ACP permission ask
     * additionally reports its terminal decision and delivery state. An ACP decision that cannot
     * be applied (expired, already decided, unsupported ask shape) answers 409 with its code; an
     * unknown approval stays the legacy 400.
     */
    @PostMapping("/{id}/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(
            @PathVariable UUID id,
            @RequestBody DecideApprovalRequest request) {
        log.info("Approval decision: id={}, approved={}", id, request.approved());

        try {
            ApprovalDecisionService.Result result =
                    approvalDecisionService.decide(id, request.approved(), request.reason());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("approvalId", result.approvalId());
            body.put("approved", result.approved());
            body.put("status", "processed");
            if (result.decision() != null) {
                body.put("decision", result.decision());
                body.put("deliveryState", result.deliveryState());
            }
            return ResponseEntity.ok(body);
        } catch (AcpDecisionRejectedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", e.code().name(),
                    "error", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    private ApprovalDetail toDetail(Approval a, ToolCall tc, AcpPermissionRequest companion) {
        // ACP asks have no ToolCall row: the companion's tool name is the only identity there is,
        // and since it is not a registry tool, arguments and riskTier stay null.
        String toolName = tc != null ? tc.getToolName()
                : companion != null ? companion.getToolName() : null;
        String riskTier = tc != null ? toolRiskResolver.resolve(tc.getToolName()).name() : null;
        return new ApprovalDetail(
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

    /**
     * Records the operator's answer to a HITL QUESTION ask (spec 4.4): free-text
     * answer, optionally marking the ask APPROVED/DENIED. Delegates to
     * {@link ApprovalAnswerService} — lighter than the gate's decide flow (no
     * run resume, no workflow side effects). Gate approvals (APPROVAL /
     * REVIEW_REQUEST asks) must use {@code /decide} instead; only PENDING asks are
     * answerable here and a decided ask is rejected.
     */
    @PostMapping("/{id}/answer")
    public ResponseEntity<Approval> answer(@PathVariable UUID id,
                                           @RequestBody AnswerRequest request) {
        return ResponseEntity.ok(approvalAnswerService.answer(
                id, request.answer(), request.approved(), request.reason()));
    }

    public record AnswerRequest(String answer, Boolean approved, String reason) {}

    public record DecideApprovalRequest(boolean approved, String reason) {}
}
