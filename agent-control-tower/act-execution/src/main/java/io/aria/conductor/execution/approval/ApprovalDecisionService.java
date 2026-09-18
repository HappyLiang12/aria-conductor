package io.aria.conductor.execution.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one place an operator decision is dispatched from (C3): the legacy gate path is handed to
 * {@link ApprovalGate} untouched, while an ACP permission ask is decided here and delivered
 * idempotently through {@link AcpPermissionCoordinator#deliverDecision}.
 *
 * <p><b>Dispatch is by {@link Approval#getSource()}.</b> {@code ACP_PERMISSION} takes the ACP
 * path; everything else — including rows written before the column existed (null) — is legacy.
 *
 * <p><b>The ACP path is a single-winner race</b> between the operator decision, the expiry sweep
 * and the run-end sweep: all three move a PENDING ask to a terminal state through one atomic
 * conditional update ({@link ApprovalRepository#decidePendingById}), so exactly one of them wins
 * and none can rewrite a decision that landed first. The winner then records the selected option,
 * issues a one-use write grant for an approval, publishes {@link ApprovalDecidedEvent} and
 * delivers through the coordinator; a loser is reported as a typed rejection (expired, already
 * decided, unsupported ask) or as the recorded state when the operator simply repeated a decision.
 *
 * <p><b>Idempotency and retries.</b> A repeated decision never re-delivers and never re-publishes:
 * it reports the recorded delivery state, except for the one retryable case — a delivery recorded
 * {@code FAILED} while the ask is still live — where the operator's repeat is the retry (fresh
 * grant, one more delivery attempt). Nothing retries in the background: a failure while the run
 * is dead or the deadline has passed stays FAILED, observable on the review surface.
 *
 * <p><b>Transactions.</b> Like the coordinator, this service is called from foreign threads and
 * transactions, so the conditional transition plus its bookkeeping run in one
 * {@code REQUIRES_NEW} transaction ({@link TransactionTemplate}); no bridge call is made from
 * inside a transaction.
 */
@Slf4j
@Service
public class ApprovalDecisionService {

    /**
     * Outcome of a decision for a caller to acknowledge. {@code decision} is the terminal
     * {@link ApprovalStatus} name for an ACP ask and null for a legacy gate decision;
     * {@code deliveryState} is the coordinator's delivery state (null for legacy).
     */
    public record Result(UUID approvalId, boolean approved, String decision, String deliveryState) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApprovalRepository approvalRepository;
    private final AcpPermissionRequestRepository companionRepository;
    private final ApprovalGate approvalGate;
    private final AcpPermissionCoordinator coordinator;
    private final WriteGrantService writeGrantService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public ApprovalDecisionService(ApprovalRepository approvalRepository,
                                   AcpPermissionRequestRepository companionRepository,
                                   ApprovalGate approvalGate,
                                   AcpPermissionCoordinator coordinator,
                                   WriteGrantService writeGrantService,
                                   ApplicationEventPublisher eventPublisher,
                                   PlatformTransactionManager transactionManager) {
        this(approvalRepository, companionRepository, approvalGate, coordinator, writeGrantService,
                eventPublisher, transactionManager, Clock.systemUTC());
    }

    /** Deterministic-collaborator constructor for tests (fake clock). */
    ApprovalDecisionService(ApprovalRepository approvalRepository,
                            AcpPermissionRequestRepository companionRepository,
                            ApprovalGate approvalGate,
                            AcpPermissionCoordinator coordinator,
                            WriteGrantService writeGrantService,
                            ApplicationEventPublisher eventPublisher,
                            PlatformTransactionManager transactionManager,
                            Clock clock) {
        if (approvalRepository == null || companionRepository == null || approvalGate == null
                || coordinator == null || writeGrantService == null || eventPublisher == null
                || transactionManager == null || clock == null) {
            throw new IllegalArgumentException("all collaborators are required");
        }
        this.approvalRepository = approvalRepository;
        this.companionRepository = companionRepository;
        this.approvalGate = approvalGate;
        this.coordinator = coordinator;
        this.writeGrantService = writeGrantService;
        this.eventPublisher = eventPublisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * Decide one approval. Returns the acknowledged outcome; throws
     * {@link IllegalArgumentException} when the approval does not exist (the unchanged contract of
     * the legacy path) and {@link AcpDecisionRejectedException} when an ACP ask cannot take the
     * decision.
     */
    public Result decide(UUID approvalId, boolean approved, String reason) {
        Approval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));
        if (approval.getSource() != ApprovalSource.ACP_PERMISSION) {
            // Legacy rows keep the gate's own semantics — futures, tool-call bookkeeping,
            // workflow events — exactly as before this service existed.
            approvalGate.decideApproval(approvalId, approved, reason);
            return new Result(approvalId, approved, null, null);
        }
        return decideAcp(approval, approved, reason);
    }

    // ------------------------------------------------------------------------------------
    // ACP path
    // ------------------------------------------------------------------------------------

    private Result decideAcp(Approval approval, boolean approved, String reason) {
        UUID approvalId = approval.getId();
        AcpPermissionRequest companion = companionRepository.findById(approvalId)
                .orElseThrow(() -> new AcpDecisionRejectedException(AcpDecisionRejectedException.Code.INCONSISTENT_ASK,
                        "ACP permission ask " + approvalId + " has no companion record"));
        Instant now = clock.instant();
        ApprovalStatus target = approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED;
        // The option echoed back to the CLI is host-derived from the ask the operator saw; an
        // approval without an allow-once option has no answer the bridge can apply.
        String selectedOptionId = approved ? allowOnceOptionId(approval) : rejectOnceOptionId(approval);
        // The rejection pre-checks belong to the winner path only (R21.1): an ask that already
        // carries a terminal decision is reported by state (R22), never rejected as late — its
        // deadline no longer governs anything.
        if (approval.getStatus() == ApprovalStatus.PENDING) {
            if (approval.getExpiresAt() == null || now.isAfter(approval.getExpiresAt())) {
                throw new AcpDecisionRejectedException(AcpDecisionRejectedException.Code.EXPIRED,
                        "ACP permission ask " + approvalId + " expired before the decision");
            }
            if (approved && selectedOptionId == null) {
                throw new AcpDecisionRejectedException(AcpDecisionRejectedException.Code.UNSUPPORTED_OPTIONS,
                        "ACP permission ask " + approvalId + " offers no allow-once option to approve");
            }
        }

        boolean won = Boolean.TRUE.equals(inTransaction(() -> {
            if (approvalRepository.decidePendingById(approvalId, target, reason, now) != 1) {
                return false;
            }
            // Same transaction as the transition: a decision is never recorded without its
            // selected option, and no delivery state is touched here.
            companionRepository.findById(approvalId).ifPresent(row -> {
                row.setSelectedOptionId(selectedOptionId);
                companionRepository.saveAndFlush(row);
            });
            return true;
        }));
        if (!won) {
            return reportLostRace(approvalId, approved, target, reason);
        }

        log.info("ACP permission ask {} decided {} by operator", approvalId, target);
        issueGrant(approvalId, approved, companion);
        // Published only by the winner, after the transition committed: a decision that lost the
        // race has no event to route.
        eventPublisher.publishEvent(new ApprovalDecidedEvent(this, approvalId, target));
        String deliveryState = coordinator.deliverDecision(approvalId, approved, reason);
        return new Result(approvalId, approved, target.name(), deliveryState);
    }

    /**
     * The conditional transition did not match: someone else (an expiry, the run-end sweep or an
     * earlier decision) already made the ask terminal, or the operator repeated the same decision.
     * Every outcome here is a report — never a second transition, a re-delivery or an event.
     */
    private Result reportLostRace(UUID approvalId, boolean approved, ApprovalStatus target, String reason) {
        Approval current = approvalRepository.findById(approvalId).orElseThrow(
                () -> new IllegalArgumentException("Approval not found: " + approvalId));
        if (current.getStatus() == ApprovalStatus.PENDING) {
            // The clock crossed the deadline between the pre-check and the transition (the
            // conditional update checks expiresAt server-side): the decision is simply too late.
            throw new AcpDecisionRejectedException(AcpDecisionRejectedException.Code.EXPIRED,
                    "ACP permission ask " + approvalId + " expired before the decision");
        }
        if (current.getStatus() == ApprovalStatus.EXPIRED) {
            // An expiry sweep (deadline or run end) won the single transition: the ask is gone
            // whatever the operator asked for, so the rejection is EXPIRED, not a conflict.
            throw new AcpDecisionRejectedException(AcpDecisionRejectedException.Code.EXPIRED,
                    "ACP permission ask " + approvalId + " expired before the decision");
        }
        if (current.getStatus() != target) {
            throw new AcpDecisionRejectedException(AcpDecisionRejectedException.Code.ALREADY_DECIDED,
                    "ACP permission ask " + approvalId + " is already " + current.getStatus());
        }
        AcpPermissionRequest row = companionRepository.findById(approvalId).orElse(null);
        if (row == null) {
            return new Result(approvalId, approved, target.name(), AcpPermissionCoordinator.DELIVERY_MISSING);
        }
        String deliveryState = row.getDeliveryState();
        if (AcpPermissionCoordinator.DELIVERY_FAILED.equals(deliveryState)
                && isStillLive(current, row)) {
            // Operator-driven retry of a failed delivery: a fresh one-use grant and one more
            // attempt, with no new decision write and no second event.
            log.info("Retrying the failed delivery of ACP ask {} on operator request", approvalId);
            issueGrant(approvalId, approved, row);
            deliveryState = coordinator.deliverDecision(approvalId, approved, reason);
        }
        return new Result(approvalId, approved, target.name(), deliveryState);
    }

    /** Live per R21: the run must still be active and the ask's deadline must not have passed. */
    private boolean isStillLive(Approval approval, AcpPermissionRequest row) {
        Instant now = clock.instant();
        return approval.getExpiresAt() != null && !now.isAfter(approval.getExpiresAt())
                && coordinator.runIsActive(row.getRunId());
    }

    /**
     * Issue the one-use write grant that lets the approved invocation through the enforcement
     * aspect. The coordinator's binding carries the runtime tool name the enforcement seam consumes
     * and the frozen digest; it is empty when the ask is not grantable (not an MCP tool call,
     * truncated input) — a denied ask never gets a grant.
     */
    private void issueGrant(UUID approvalId, boolean approved, AcpPermissionRequest row) {
        if (!approved) {
            return;
        }
        coordinator.grantBindingForDecision(approvalId).ifPresent(binding ->
                writeGrantService.grant(row.getRunId(), binding.toolName(), binding.digest()));
    }

    /**
     * The allow-once option id of the ask, or null when it has none. The bridge selects an option
     * by id, so this is the only answer an approval may echo back (the kind is the ask's own
     * vocabulary; nothing is invented when it is absent).
     */
    private static String allowOnceOptionId(Approval approval) {
        return optionIdOfKind(approval, "allow_once");
    }

    /**
     * The reject-once option id of the ask, or null when it has none: a denial without one is
     * delivered as the bridge-side cancellation of the ask, not as an invented option.
     */
    private static String rejectOnceOptionId(Approval approval) {
        return optionIdOfKind(approval, "reject_once");
    }

    private static String optionIdOfKind(Approval approval, String kind) {
        for (JsonNode option : options(approval)) {
            if (kind.equals(text(option, "kind"))) {
                return text(option, "optionId");
            }
        }
        return null;
    }

    private static List<JsonNode> options(Approval approval) {
        String json = approval.getOptionsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode parsed = MAPPER.readTree(json);
            if (!parsed.isArray()) {
                return List.of();
            }
            List<JsonNode> options = new ArrayList<>();
            parsed.forEach(options::add);
            return options;
        } catch (Exception e) {
            // An unreadable option list cannot prove an option exists: no option is selectable.
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
