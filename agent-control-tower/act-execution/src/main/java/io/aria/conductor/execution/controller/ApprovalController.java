package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.approval.ApprovalAnswerService;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.approval.PermissionChoice;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.pipeline.ToolRiskResolver;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalRepository approvalRepository;
    private final ApprovalGate approvalGate;
    private final ToolCallRepository toolCallRepository;
    private final ToolRiskResolver toolRiskResolver;
    private final ApprovalQueryService approvalQueryService;
    private final ApprovalAnswerService approvalAnswerService;
    private final PermissionCoordinator permissionCoordinator;
    private final OperatorSessionService operatorSessions;
    private final OperatorAuthorityResolver operatorAuthority;

    /**
     * Convenience constructor for direct-instantiation tests: builds its own
     * query service and takes the permission/identity collaborators explicitly.
     */
    public ApprovalController(ApprovalRepository approvalRepository,
                              ApprovalGate approvalGate,
                              ToolCallRepository toolCallRepository,
                              ToolRiskResolver toolRiskResolver,
                              PermissionCoordinator permissionCoordinator,
                              OperatorSessionService operatorSessions,
                              OperatorAuthorityResolver operatorAuthority) {
        this(approvalRepository, approvalGate, toolCallRepository, toolRiskResolver,
                new ApprovalQueryService(approvalRepository, toolCallRepository, toolRiskResolver),
                new ApprovalAnswerService(approvalRepository), permissionCoordinator,
                operatorSessions, operatorAuthority);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalController(ApprovalRepository approvalRepository,
                              ApprovalGate approvalGate,
                              ToolCallRepository toolCallRepository,
                              ToolRiskResolver toolRiskResolver,
                              ApprovalQueryService approvalQueryService,
                              ApprovalAnswerService approvalAnswerService,
                              PermissionCoordinator permissionCoordinator,
                              OperatorSessionService operatorSessions,
                              OperatorAuthorityResolver operatorAuthority) {
        this.approvalRepository = approvalRepository;
        this.approvalGate = approvalGate;
        this.toolCallRepository = toolCallRepository;
        this.toolRiskResolver = toolRiskResolver;
        this.approvalQueryService = approvalQueryService;
        this.approvalAnswerService = approvalAnswerService;
        this.permissionCoordinator = permissionCoordinator;
        this.operatorSessions = operatorSessions;
        this.operatorAuthority = operatorAuthority;
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
            String answer) {}

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
                    return ResponseEntity.ok(toDetail(a, tc));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Decide an approval. This route is operator-only (spec §6.2): the caller's
     * operator authority is resolved by {@link OperatorAuthorityResolver} from
     * the configured operator bearer credential or the operator session cookie
     * (whose mutations must also pass Origin/CSRF validation); an anonymous
     * request from loopback is the local single operator. 401 without a
     * verifiable identity, 403 when a valid worker credential or a failed
     * CSRF/Origin check is presented.
     *
     * <p>A native permission ask is dispatched to the permission coordinator —
     * which re-checks operator authority, state and expiry inside one
     * transaction — while every other (gate) approval keeps its existing
     * decision semantics.
     */
    @PostMapping("/{id}/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(
            @PathVariable UUID id,
            @RequestBody DecideApprovalRequest body,
            HttpServletRequest request) {
        log.info("Approval decision: id={}, approved={}", id, body.approved());

        ActorPrincipal operator;
        try {
            operator = operatorAuthority.resolveOperator(request, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }

        try {
            if (permissionCoordinator.isNativePermissionRequest(id)) {
                permissionCoordinator.decide(id,
                        body.approved() ? PermissionChoice.ALLOW_ONCE : PermissionChoice.DENY, operator);
            } else {
                approvalGate.decideApproval(id, body.approved(), body.reason());
            }
            return ResponseEntity.ok(Map.of(
                    "approvalId", id,
                    "approved", body.approved(),
                    "status", "processed"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
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
                toolName, tc != null ? tc.getArguments() : null, riskTier,
                a.getKanbanItemId(),
                a.getAskType() != null ? a.getAskType().name() : null,
                a.getContextMd(), a.getOptionsJson(), a.getAnswer());
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
