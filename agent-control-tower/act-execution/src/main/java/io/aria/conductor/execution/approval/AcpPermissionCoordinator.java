package io.aria.conductor.execution.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.adk.qoder.QoderBridgeClient;
import io.aria.conductor.execution.adk.qoder.QoderBridgeException;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Host-side coordinator for ACP {@code request_permission} asks (task C2).
 *
 * <p>Every ask the Qoder bridge forwards is turned into a first-class governance record: an
 * {@link Approval} with {@code source = ACP_PERMISSION} and its 1:1 companion
 * {@link AcpPermissionRequest}, persisted in one transaction and published as an
 * {@link ApprovalRequestedEvent} <em>after</em> that transaction commits. The sandbox is
 * untrusted, so nothing it sends is persisted verbatim: the effective tool name and arguments
 * (R3), the authorization digest (R4), the display preview, the option list and the identity
 * checks are all recomputed host-side (R2).
 *
 * <p><b>{@code requestDigest} column semantics.</b> The column holds {@link
 * WriteGrantService#effectiveArgsDigest(Map)} of the effective arguments — the same frozen
 * helper the enforcement aspect uses, so an approved ask binds exactly the invocation it
 * authorizes. Two values are special: an ask whose input the bridge had to truncate (R1) is
 * persisted <em>undecidable</em> — its digest is the SHA-256 of the received truncated string
 * and its {@code displayJson} carries {@code "rawInputTruncated": true}; and an ask that is
 * not an MCP tool call still stores a real digest but is never grantable, because only names
 * matching {@code ^mcp__<server>__<tool>$} can carry a write grant. {@link
 * #digestForDecision(UUID)} is the only reader C3 may use, and it returns empty for both
 * cases.
 *
 * <p><b>Transactions.</b> The coordinator is called from foreign threads and foreign
 * transactions (the bridge event pump, the scheduled expiry sweep, C3's decision path), so
 * every internal database step runs in its own {@code REQUIRES_NEW} transaction through a
 * {@link TransactionTemplate}. Two consequences matter: a bridge call is never made inside a
 * database transaction, and the duplicate-correlation catch can sit outside the transaction
 * boundary (a constraint violation inside a shared transaction would poison it).
 *
 * <p><b>Delivery states.</b> {@code PENDING → DELIVERING → DELIVERED | CANCELLED | FAILED}:
 * {@code DELIVERED} means the decision took effect — the bridge's {@code delivered} outcome or an
 * answer that rules on the ask itself ({@code already_resolved} / {@code ALREADY_RESOLVED}) —
 * {@code CANCELLED} is an ask that was expired or recovered after a restart, and {@code FAILED}
 * is any other delivery outcome — the host's decision was never applied — the only retryable
 * state (R16, R18).
 */
@Slf4j
@Component
public class AcpPermissionCoordinator {

    /** Ask recorded, awaiting a decision. */
    public static final String DELIVERY_PENDING = "PENDING";
    /** A delivery is in flight (the claiming worker owns it). */
    public static final String DELIVERY_DELIVERING = "DELIVERING";
    /** The decision took effect: the bridge applied it or had already ruled on the ask (R16, R18). */
    public static final String DELIVERY_DELIVERED = "DELIVERED";
    /** The ask was expired or interrupted by a restart; a cancel was delivered best-effort. */
    public static final String DELIVERY_CANCELLED = "CANCELLED";
    /** The bridge never applied the host's decision; observable and retryable. */
    public static final String DELIVERY_FAILED = "FAILED";
    /** No companion row exists for the requested approval (legacy or unknown id). */
    public static final String DELIVERY_MISSING = "MISSING";

    /** Expiry reason recorded by the scheduled sweep / C3's expiry path (R8). */
    public static final String REASON_EXPIRED = "expired before decision";
    /** Expiry reason recorded by the startup recovery (R9). */
    public static final String REASON_RESTART_INTERRUPTED = "restart-interrupted (no session replay)";
    /** Expiry reason recorded when the run ended before the operator decided (R23). */
    public static final String REASON_RUN_ENDED = "run ended before decision";

    /** Only tool names in the platform MCP namespace can carry a write grant. */
    private static final Pattern MCP_TOOL_NAME = Pattern.compile("^mcp__[A-Za-z0-9_-]+__[A-Za-z0-9_-]+$");
    /** Causes that mean the bridge ruled on the ask itself: the only non-transport delivery success. */
    private static final List<QoderBridgeException.Cause> ASK_RULED_CAUSES = List.of(
            QoderBridgeException.Cause.ALREADY_RESOLVED);
    /** 200 outcomes that mean the decision took effect: the only such answers counted as delivered (R18). */
    private static final List<QoderBridgeClient.DecisionOutcome> DELIVERED_OUTCOMES = List.of(
            QoderBridgeClient.DecisionOutcome.DELIVERED,
            QoderBridgeClient.DecisionOutcome.ALREADY_RESOLVED);
    /** Bound of a stored preview (the full redacted input stays in {@code displayJson}). */
    private static final int MAX_DISPLAY_PREVIEW_CHARS = 4096;
    /** Bounds of the identity fields, matching the companion's column widths. */
    private static final int MAX_REQUEST_ID_CHARS = 128;
    private static final int MAX_TOOL_CALL_ID_CHARS = 128;
    private static final int MAX_TOOL_NAME_CHARS = 256;
    /** Matches the companion's {@code selected_option_id} column so a decision can echo it. */
    private static final int MAX_OPTION_ID_CHARS = 64;
    private static final int MAX_OPTION_NAME_CHARS = 200;
    /** Host-known secret shapes redacted from stored display text (the bridge redacts its own). */
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)(bearer\\s+)\\S{6,}");
    private static final Pattern WCP_TOKEN = Pattern.compile("wcp_\\S{6,}");
    private static final Pattern SECRET_JSON_VALUE = Pattern.compile(
            "(?i)\"(token|secret|password|passwd|api[-_]?key|authorization|credential|credentials|pat)\""
                    + "\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApprovalRepository approvalRepository;
    private final AcpPermissionRequestRepository companionRepository;
    private final RunRepository runRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transaction;
    private final Duration approvalTimeout;
    private final Clock clock;
    /** Live bridge clients of in-flight runs, so a decision can be delivered to its session. */
    private final ConcurrentMap<UUID, LiveRun> liveRuns = new ConcurrentHashMap<>();

    @Autowired
    public AcpPermissionCoordinator(ApprovalRepository approvalRepository,
                                    AcpPermissionRequestRepository companionRepository,
                                    RunRepository runRepository,
                                    ApplicationEventPublisher eventPublisher,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${approvals.timeout-ms:1800000}") long approvalTimeoutMs) {
        this(approvalRepository, companionRepository, runRepository, eventPublisher, transactionManager,
                approvalTimeoutMs, Clock.systemUTC());
    }

    /** Deterministic-collaborator constructor for tests (fake clock, explicit timeout). */
    AcpPermissionCoordinator(ApprovalRepository approvalRepository,
                             AcpPermissionRequestRepository companionRepository,
                             RunRepository runRepository,
                             ApplicationEventPublisher eventPublisher,
                             PlatformTransactionManager transactionManager,
                             long approvalTimeoutMs,
                             Clock clock) {
        if (approvalRepository == null || companionRepository == null || runRepository == null
                || eventPublisher == null || transactionManager == null || clock == null) {
            throw new IllegalArgumentException("all collaborators are required");
        }
        if (approvalTimeoutMs <= 0) {
            throw new IllegalArgumentException("approvalTimeoutMs must be positive");
        }
        this.approvalRepository = approvalRepository;
        this.companionRepository = companionRepository;
        this.runRepository = runRepository;
        this.eventPublisher = eventPublisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.approvalTimeout = Duration.ofMillis(approvalTimeoutMs);
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------
    // Run binding (R11) — the provider registers its live run before the session starts.
    // ------------------------------------------------------------------------------------

    /**
     * Register a run's live bridge client and deadline, so asks raised by that run can be
     * expired at {@code min(now + approvals.timeout-ms, runDeadline)} and decisions delivered.
     */
    public void bindRun(UUID runId, QoderBridgeClient client, Instant runDeadline) {
        if (runId == null || client == null) {
            return;
        }
        liveRuns.put(runId, new LiveRun(client, runDeadline));
    }

    /** Forget a run's live client (the run ended: nothing can be delivered to it anymore). */
    public void unbindRun(UUID runId) {
        if (runId != null) {
            liveRuns.remove(runId);
        }
    }

    // ------------------------------------------------------------------------------------
    // Event intake
    // ------------------------------------------------------------------------------------

    /**
     * Turn one bridge {@code permission_request} payload into a first-class ask. Never throws:
     * the caller is the run's event pump, whose progress reporting must survive any coordinator
     * failure. Malformed asks are cancelled, never coerced into an allow (R7).
     */
    public void handlePermissionEvent(UUID runId, UUID agentId, String bridgeSessionId, JsonNode payload) {
        try {
            String requestId = text(payload, "requestId");
            if (runId == null || bridgeSessionId == null || bridgeSessionId.isBlank()) {
                log.error("ACP permission governance error: event without run/session identity (requestId={})",
                        requestId);
                return;
            }
            if (!runIsActive(runId)) {
                log.warn("ACP permission event for run {} dropped: run missing or terminal (requestId={})",
                        runId, requestId);
                cancelBestEffort(runId, bridgeSessionId, requestId, "run no longer accepting permissions");
                return;
            }
            String toolCallId = text(payload, "toolCallId");
            if (isBlank(requestId) || requestId.length() > MAX_REQUEST_ID_CHARS
                    || isBlank(toolCallId) || toolCallId.length() > MAX_TOOL_CALL_ID_CHARS) {
                log.error("ACP permission governance error: malformed ask identity for session {}"
                        + " (requestId={}, toolCallId={})", bridgeSessionId, requestId, toolCallId);
                cancelBestEffort(runId, bridgeSessionId, requestId, "malformed permission request");
                return;
            }
            List<PermissionOption> options = parseOptions(payload.path("options"));
            if (!hasSelectableKind(options)) {
                log.error("ACP permission governance error: ask {} for session {} has no selectable option kind",
                        requestId, bridgeSessionId);
                cancelBestEffort(runId, bridgeSessionId, requestId, "no selectable permission option");
                return;
            }
            EffectiveAsk effective = effectiveAsk(payload);
            if (effective.name().length() > MAX_TOOL_NAME_CHARS) {
                log.error("ACP permission governance error: ask {} names a tool longer than {} chars",
                        requestId, MAX_TOOL_NAME_CHARS);
                cancelBestEffort(runId, bridgeSessionId, requestId, "malformed permission request");
                return;
            }

            Optional<AcpPermissionRequest> existing =
                    companionRepository.findByBridgeSessionIdAndBridgeRequestId(bridgeSessionId, requestId);
            if (existing.isPresent()) {
                if (sameAsk(existing.get(), effective)) {
                    log.debug("Identical ACP ask re-delivered for session {} (requestId={}) — no-op",
                            bridgeSessionId, requestId);
                    return;
                }
                log.error("ACP permission governance error: changed payload for correlation {} / {} — rejected",
                        bridgeSessionId, requestId);
                return;
            }

            try {
                insertAsk(runId, agentId, bridgeSessionId, requestId, toolCallId, options, effective);
            } catch (DataIntegrityViolationException e) {
                // Concurrent re-delivery of the same correlation: the unique constraint is the
                // authority, so re-read the winner and compare payloads (R5).
                Optional<AcpPermissionRequest> winner =
                        companionRepository.findByBridgeSessionIdAndBridgeRequestId(bridgeSessionId, requestId);
                if (winner.isPresent() && sameAsk(winner.get(), effective)) {
                    log.debug("Concurrent identical ACP ask for session {} (requestId={}) — no-op",
                            bridgeSessionId, requestId);
                    return;
                }
                log.error("ACP permission governance error: correlation {} / {} was taken by a different payload",
                        bridgeSessionId, requestId);
            }
        } catch (Exception e) {
            // The pump must never see a coordinator failure (R11); the ask is left undecided and
            // the run's own expiry path will clean it up.
            log.error("ACP permission handling failed for run {}: {}", runId, e.getMessage(), e);
        }
    }

    private void insertAsk(UUID runId, UUID agentId, String bridgeSessionId, String requestId,
                           String toolCallId, List<PermissionOption> options, EffectiveAsk effective) {
        String optionsJson = optionsJson(options);
        String displayJson = displayJson(effective, options);
        String content = renderContent(effective, options);
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            Instant expiresAt = expiresAt(now, liveRuns.get(runId));
            Approval approval = Approval.builder()
                    .id(UUID.randomUUID())
                    .runId(runId)
                    // ACP tool-call ids are strings; the UUID column stays null (R5).
                    .toolCallId(null)
                    .status(ApprovalStatus.PENDING)
                    .approvalType(Approval.ApprovalType.TOOL_CALL)
                    .askType(Approval.AskType.APPROVAL)
                    .source(ApprovalSource.ACP_PERMISSION)
                    .reason("ACP permission request: " + sanitizeName(effective.name()))
                    .content(content)
                    .contentKind(Approval.ContentKind.MARKDOWN)
                    .optionsJson(optionsJson)
                    .requestedAt(now)
                    .expiresAt(expiresAt)
                    .build();
            approvalRepository.save(approval);
            AcpPermissionRequest companion = AcpPermissionRequest.builder()
                    .approvalId(approval.getId())
                    .runId(runId)
                    .agentId(agentId)
                    .bridgeSessionId(bridgeSessionId)
                    .bridgeRequestId(requestId)
                    .toolCallId(toolCallId)
                    .toolName(effective.name())
                    .optionsJson(optionsJson)
                    .requestDigest(effective.digest())
                    .displayJson(displayJson)
                    .expiresAt(expiresAt)
                    .deliveryState(DELIVERY_PENDING)
                    .build();
            // Flush inside the transaction so a correlation violation surfaces here, with the
            // catch outside this (independently rolled back) transaction boundary.
            companionRepository.saveAndFlush(companion);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(new ApprovalRequestedEvent(AcpPermissionCoordinator.this,
                            approval.getId(), runId, null,
                            Approval.ApprovalType.TOOL_CALL.name(), ApprovalSource.ACP_PERMISSION.name()));
                }
            });
        });
    }

    private static boolean sameAsk(AcpPermissionRequest row, EffectiveAsk effective) {
        return row.getToolName().equals(effective.name()) && row.getRequestDigest().equals(effective.digest());
    }

    /** Public since C3 gates operator-driven retries on the run's liveness (R23). */
    public boolean runIsActive(UUID runId) {
        return runRepository.findById(runId).map(run -> !isTerminal(run.getStatus())).orElse(false);
    }

    /** Exhaustive over {@link RunStatus}: a new status must have its terminality decided here. */
    private static boolean isTerminal(RunStatus status) {
        return switch (status) {
            case PENDING, INITIALIZING, RUNNING, PAUSED -> false;
            case COMPLETED, FAILED, CANCELLED, ABORTED -> true;
        };
    }

    private Instant expiresAt(Instant now, LiveRun liveRun) {
        Instant timeout = now.plus(approvalTimeout);
        if (liveRun == null || liveRun.deadline() == null) {
            return timeout;
        }
        return liveRun.deadline().isBefore(timeout) ? liveRun.deadline() : timeout;
    }

    // ------------------------------------------------------------------------------------
    // Decision delivery (C3's idempotent primitive)
    // ------------------------------------------------------------------------------------

    /**
     * Deliver an operator decision for a stored ask; idempotent — a terminal delivery state is
     * returned as recorded without a second bridge call. Returns the resulting (or recorded)
     * delivery state: {@code DELIVERED}, {@code CANCELLED}, {@code FAILED} (retryable),
     * {@code MISSING} (no companion), or the in-flight {@code DELIVERING} state of another
     * worker.
     */
    public String deliverDecision(UUID approvalId, boolean approved, String reason) {
        if (approvalId == null) {
            return DELIVERY_MISSING;
        }
        return deliver(approvalId, approved, reason, DELIVERY_DELIVERED);
    }

    private String deliver(UUID approvalId, boolean approved, String reason, String answeredState) {
        Claim claim = inTransaction(() -> claimForDelivery(approvalId));
        if (!claim.owned()) {
            return claim.state();
        }
        boolean delivered = deliverToBridge(claim, approved, reason);
        String state = delivered ? answeredState : DELIVERY_FAILED;
        inTransaction(() -> {
            companionRepository.findById(approvalId).ifPresent(row -> {
                row.setDeliveryState(state);
                if (delivered) {
                    row.setDeliveredAt(clock.instant());
                }
                companionRepository.saveAndFlush(row);
            });
            return null;
        });
        return state;
    }

    /**
     * One delivery attempt to the run's live bridge. The answer counts as delivered only when it
     * rules on the ask itself: a 200 with the {@code delivered} / {@code already_resolved} outcome,
     * or a 409 {@code ALREADY_RESOLVED}. Every other answer — an {@code expired} / {@code unknown}
     * outcome, a 401 / 400 / 413 / 5xx rejection, a transport failure — means the host's decision
     * was never applied: the companion stays {@code FAILED}, observable and retryable (R16, R18).
     */
    private boolean deliverToBridge(Claim claim, boolean approved, String reason) {
        QoderBridgeClient client = liveRuns.getOrDefault(claim.runId(), NO_LIVE_RUN).client();
        if (client == null) {
            log.warn("No live Qoder bridge for run {} — decision for ask {} was not delivered",
                    claim.runId(), claim.bridgeRequestId());
            return false;
        }
        try {
            QoderBridgeClient.DecisionOutcome outcome =
                    client.decide(claim.bridgeSessionId(), claim.bridgeRequestId(), approved, reason);
            boolean applied = outcome != null && DELIVERED_OUTCOMES.contains(outcome);
            if (!applied) {
                log.warn("Decision delivery for ask {} answered with outcome {} — the decision was not applied",
                        claim.bridgeRequestId(), outcome);
            }
            return applied;
        } catch (QoderBridgeException e) {
            log.warn("Decision delivery for ask {} answered with {}: {}",
                    claim.bridgeRequestId(), e.cause(), e.getMessage());
            return ASK_RULED_CAUSES.contains(e.cause());
        } catch (Exception e) {
            log.warn("Decision delivery for ask {} failed: {}", claim.bridgeRequestId(), e.getMessage());
            return false;
        }
    }

    /** Claims the ask for one delivery; a terminal or in-flight state is handed back as-is. */
    private Claim claimForDelivery(UUID approvalId) {
        AcpPermissionRequest row = companionRepository.findById(approvalId).orElse(null);
        if (row == null) {
            return new Claim(false, DELIVERY_MISSING, null, null, null);
        }
        if (isTerminalDelivery(row.getDeliveryState()) || DELIVERY_DELIVERING.equals(row.getDeliveryState())) {
            return new Claim(false, row.getDeliveryState(), null, null, null);
        }
        row.setDeliveryState(DELIVERY_DELIVERING);
        companionRepository.saveAndFlush(row);
        return new Claim(true, DELIVERY_DELIVERING, row.getBridgeSessionId(), row.getBridgeRequestId(),
                row.getRunId());
    }

    private static boolean isTerminalDelivery(String state) {
        return DELIVERY_DELIVERED.equals(state) || DELIVERY_CANCELLED.equals(state);
    }

    // ------------------------------------------------------------------------------------
    // Expiry (the scheduled sweep, C3 and the startup recovery)
    // ------------------------------------------------------------------------------------

    /**
     * Expire a PENDING ask atomically and deliver the cancel outcome through the same
     * primitive. Only the caller whose conditional update matches proceeds, so a decision that
     * landed first is never rewritten; a subsequent call is a no-op returning {@code false}.
     */
    public boolean expire(UUID approvalId) {
        return expireWithReason(approvalId, REASON_EXPIRED);
    }

    /**
     * Restart recovery (R9): every PENDING ACP ask is expired with the restart-interruption
     * reason. There is no session to resume and no replay — the cancel is best-effort and a
     * missing bridge is expected after a restart.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void expireInterruptedPendingApprovals() {
        // The scan runs in its own transaction like every other database step here: a caller's
        // transaction must not cache rows this method is about to expire through a separate
        // REQUIRES_NEW transaction (the caller would keep reading them as PENDING).
        List<Approval> pending = inTransaction(() -> approvalRepository.findByStatusAndSource(
                ApprovalStatus.PENDING, ApprovalSource.ACP_PERMISSION));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        log.info("ACP restart recovery: expiring {} pending permission ask(s) without replay", pending.size());
        for (Approval approval : pending) {
            expireWithReason(approval.getId(), REASON_RESTART_INTERRUPTED);
        }
    }

    private boolean expireWithReason(UUID approvalId, String reason) {
        if (approvalId == null) {
            return false;
        }
        boolean won = Boolean.TRUE.equals(inTransaction(() ->
                approvalRepository.expirePendingById(approvalId, reason, clock.instant()) == 1));
        if (!won) {
            return false;
        }
        String outcome = deliver(approvalId, false, reason, DELIVERY_CANCELLED);
        if (DELIVERY_FAILED.equals(outcome)) {
            // The ask is terminal either way: an expired approval is never retried, so a failed
            // delivery must not leave the companion in a retryable state.
            inTransaction(() -> {
                companionRepository.findById(approvalId).ifPresent(row -> {
                    row.setDeliveryState(DELIVERY_CANCELLED);
                    companionRepository.saveAndFlush(row);
                });
                return null;
            });
        }
        return true;
    }

    /**
     * Expire every PENDING ACP ask of one run (R23): the run is over, its session is gone, so the
     * cancel is delivered best-effort through the same idempotent primitive and a missing bridge
     * is expected. Legacy rows of the run are not this sweep's business. Returns the number of
     * asks this call expired — a call racing a decision (or a second sweep) expires nothing.
     */
    public int cancelPendingForRun(UUID runId, String reason) {
        if (runId == null) {
            return 0;
        }
        List<Approval> pending = inTransaction(() -> approvalRepository.findByRunIdAndStatusAndSource(
                runId, ApprovalStatus.PENDING, ApprovalSource.ACP_PERMISSION));
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int expired = 0;
        for (Approval approval : pending) {
            if (expireWithReason(approval.getId(), reason)) {
                expired++;
            }
        }
        return expired;
    }

    /**
     * Run finished: nothing can decide the run's live asks anymore, and their sessions are being
     * torn down — so every PENDING ask of the run is expired and its cancel delivered best-effort.
     */
    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        if (event == null || event.getRunId() == null) {
            return;
        }
        int expired = cancelPendingForRun(event.getRunId(), REASON_RUN_ENDED);
        if (expired > 0) {
            log.info("ACP run end: expired {} pending permission ask(s) of run {}", expired, event.getRunId());
        }
    }

    /**
     * Restart reconciliation (M1 closure, R23): a companion row still {@code DELIVERING} belongs
     * to a process that died mid-delivery — no worker owns it anymore and no session can be
     * replayed, so the dead delivery is settled as {@code CANCELLED}. The approval's decision
     * record is deliberately left untouched: a decision that was made stays made, and a later
     * operator repeat reports the recorded state instead of delivering again. Order-independent
     * with {@link #expireInterruptedPendingApprovals()}: whichever startup listener runs first,
     * every interrupted ask converges to a terminal approval plus a terminal delivery state.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileStuckDeliveries() {
        List<AcpPermissionRequest> stuck = inTransaction(() ->
                companionRepository.findByDeliveryState(DELIVERY_DELIVERING));
        if (stuck == null || stuck.isEmpty()) {
            return;
        }
        log.warn("ACP restart reconciliation: settling {} interrupted delivery(ies) as cancelled", stuck.size());
        inTransaction(() -> {
            companionRepository.markStuckDeliveringCancelled();
            return null;
        });
    }

    /**
     * The stored authorization digest C3 feeds to {@link WriteGrantService#grant}. Empty when
     * no companion exists, when the ask is undecidable (truncated input, R4) or when it is not
     * an MCP tool call (never grantable, R3).
     */
    public Optional<String> digestForDecision(UUID approvalId) {
        if (approvalId == null) {
            return Optional.empty();
        }
        return companionRepository.findById(approvalId)
                .filter(row -> MCP_TOOL_NAME.matcher(row.getToolName()).matches())
                .filter(row -> !isTruncated(row))
                .map(AcpPermissionRequest::getRequestDigest);
    }

    private static boolean isTruncated(AcpPermissionRequest row) {
        String display = row.getDisplayJson();
        if (display == null || display.isBlank()) {
            // An unreadable display record cannot prove the ask decidable: fail closed.
            return true;
        }
        try {
            return MAPPER.readTree(display).path("rawInputTruncated").asBoolean(true);
        } catch (Exception e) {
            return true;
        }
    }

    private void cancelBestEffort(UUID runId, String bridgeSessionId, String requestId, String reason) {
        LiveRun liveRun = runId == null ? null : liveRuns.get(runId);
        if (liveRun == null || liveRun.client() == null || isBlank(requestId)) {
            // Nothing to talk to (or no request id to address): the ask is simply not persisted.
            return;
        }
        try {
            liveRun.client().decide(bridgeSessionId, requestId, false, reason);
        } catch (Exception e) {
            log.warn("Best-effort cancel for ask {} failed: {}", requestId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------
    // Host-side derivation (R2–R4): name, args, digest, options, display
    // ------------------------------------------------------------------------------------

    /** Effective (host-derived) identity of an ask. */
    private record EffectiveAsk(String name, Map<String, Object> args, String digest, boolean truncated,
                                boolean grantable, String previewInput) { }

    /** One sanitized selectable option as the review surface and C3 will see it. */
    private record PermissionOption(String optionId, String kind, String name) { }

    private record LiveRun(QoderBridgeClient client, Instant deadline) { }

    private static final LiveRun NO_LIVE_RUN = new LiveRun(null, null);

    private record Claim(boolean owned, String state, String bridgeSessionId, String bridgeRequestId,
                         UUID runId) { }

    /**
     * R3 normalization: the L-shape carries the MCP name in the meta tool name, the W-shape in
     * {@code rawInput.toolName}; anything else stays a non-MCP ask that is never grantable.
     */
    private static EffectiveAsk effectiveAsk(JsonNode payload) {
        String metaName = blankToNull(text(payload, "toolName"));
        boolean truncated = payload.path("rawInputTruncated").asBoolean(false);
        String rawInputText = text(payload, "rawInput");
        JsonNode parsed = parseRawInput(payload.get("rawInput"));
        if (truncated) {
            // R4: the truncated input cannot be inspected for arguments, and its digest (the
            // hash of the received string) is not an authorization digest.
            return new EffectiveAsk(metaName == null ? "unknown" : metaName, Map.of(),
                    sha256(rawInputText == null ? "" : rawInputText), true, false, rawInputText);
        }
        if (metaName != null && MCP_TOOL_NAME.matcher(metaName).matches()) {
            Map<String, Object> args = asMap(parsed);
            return new EffectiveAsk(metaName, args, WriteGrantService.effectiveArgsDigest(args),
                    false, true, rawInputText);
        }
        if (parsed != null && parsed.isObject()) {
            JsonNode inner = parsed.path("toolName");
            if (inner.isTextual() && MCP_TOOL_NAME.matcher(inner.asText()).matches()) {
                Map<String, Object> args = asMap(parsed.get("arguments"));
                return new EffectiveAsk(inner.asText(), args, WriteGrantService.effectiveArgsDigest(args),
                        false, true, rawInputText);
            }
        }
        Map<String, Object> args = asMap(parsed);
        return new EffectiveAsk(metaName == null ? "unknown" : metaName, args,
                WriteGrantService.effectiveArgsDigest(args), false, false, rawInputText);
    }

    /** The bridge serializes non-string inputs, so an object arrives as text; accept both. */
    private static JsonNode parseRawInput(JsonNode rawInput) {
        if (rawInput == null || rawInput.isNull()) {
            return null;
        }
        if (rawInput.isObject() || rawInput.isArray()) {
            return rawInput;
        }
        if (rawInput.isTextual() && !rawInput.asText().isBlank()) {
            try {
                return MAPPER.readTree(rawInput.asText());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, Object> asMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static List<PermissionOption> parseOptions(JsonNode options) {
        if (options == null || !options.isArray()) {
            return List.of();
        }
        List<PermissionOption> parsed = new ArrayList<>();
        for (JsonNode node : options) {
            if (!node.isObject()) {
                continue;
            }
            String optionId = text(node, "optionId");
            // An option without a storable id can never be echoed back by a decision, so it is
            // not selectable and is not presented as one.
            if (isBlank(optionId) || optionId.length() > MAX_OPTION_ID_CHARS) {
                continue;
            }
            parsed.add(new PermissionOption(optionId, text(node, "kind"), sanitizeName(text(node, "name"))));
        }
        return List.copyOf(parsed);
    }

    /** Valid ask (R7): at least one selectable kind — an allow-once or a reject-shaped option. */
    private static boolean hasSelectableKind(List<PermissionOption> options) {
        return options.stream().anyMatch(option -> switch (option.kind() == null ? "" : option.kind()) {
            case "allow_once", "reject_once", "reject_always" -> true;
            default -> false;
        });
    }

    /** Control characters stripped, length bounded (R2): the name is display text, never markup. */
    private static String sanitizeName(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && sanitized.length() < MAX_OPTION_NAME_CHARS; i++) {
            char c = name.charAt(i);
            sanitized.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return sanitized.toString().trim();
    }

    private static String optionsJson(List<PermissionOption> options) {
        ArrayNode array = MAPPER.createArrayNode();
        for (PermissionOption option : options) {
            ObjectNode node = array.addObject();
            node.put("optionId", option.optionId());
            if (option.kind() != null) {
                node.put("kind", option.kind());
            }
            if (option.name() != null) {
                node.put("name", option.name());
            }
        }
        return writeJson(array);
    }

    /** The structured record the review surface renders; the redacted input stays available. */
    private static String displayJson(EffectiveAsk effective, List<PermissionOption> options) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("rawInputTruncated", effective.truncated());
        node.put("grantable", effective.grantable());
        node.put("toolName", sanitizeName(effective.name()));
        node.put("rawInput", redact(effective.previewInput()));
        node.set("options", readJson(optionsJson(options)));
        return writeJson(node);
    }

    private static String renderContent(EffectiveAsk effective, List<PermissionOption> options) {
        StringBuilder content = new StringBuilder();
        content.append("**ACP permission request** — tool `").append(sanitizeName(effective.name()))
                .append("`\n\n");
        content.append("Requested input:\n\n");
        for (String line : previewLines(redact(effective.previewInput()))) {
            content.append("    ").append(line).append('\n');
        }
        content.append("\nOptions: ").append(options.stream()
                .map(option -> (option.name() == null || option.name().isBlank()
                        ? option.optionId() : option.name())
                        + " [" + (option.kind() == null ? "unknown" : option.kind()) + "]")
                .collect(Collectors.joining(", "))).append('\n');
        if (effective.truncated()) {
            content.append("Note: the input was truncated by the bridge (undecidable — cannot be granted).\n");
        }
        if (!effective.grantable()) {
            content.append("Note: not an MCP tool call — no write grant can be issued for it.\n");
        }
        return content.toString();
    }

    private static List<String> previewLines(String input) {
        if (input == null || input.isEmpty()) {
            return List.of("(no input)");
        }
        String preview = input.length() > MAX_DISPLAY_PREVIEW_CHARS
                ? input.substring(0, MAX_DISPLAY_PREVIEW_CHARS) + "...(preview truncated)"
                : input;
        StringBuilder clean = new StringBuilder(preview.length());
        for (int i = 0; i < preview.length(); i++) {
            char c = preview.charAt(i);
            clean.append(c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7f) ? c : ' ');
        }
        return List.of(clean.toString().split("\n", -1));
    }

    /**
     * Host-side redaction of stored display text. The bridge already redacts the session
     * secrets it knows; this covers the host's own tokens and secret-shaped values so the
     * operator-facing record never carries them. Redaction changes only these display copies:
     * the authorization digest is computed over the bytes as received (possibly already redacted
     * by the bridge, possibly truncated), so a later genuine write carrying the original secret
     * value no longer matches the digest and fails the grant closed — the safe direction.
     */
    private static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = BEARER_SECRET.matcher(text).replaceAll("$1[redacted]");
        redacted = WCP_TOKEN.matcher(redacted).replaceAll("[redacted]");
        Matcher secretValue = SECRET_JSON_VALUE.matcher(redacted);
        StringBuilder result = new StringBuilder();
        while (secretValue.find()) {
            secretValue.appendReplacement(result, "\"" + secretValue.group(1) + "\": \"[redacted]\"");
        }
        secretValue.appendTail(result);
        return result.toString();
    }

    private static String writeJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize the ACP permission record", e);
        }
    }

    /** Parse host-produced JSON (never sandbox input), which this class always shapes itself. */
    private static JsonNode readJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse the ACP permission record", e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
