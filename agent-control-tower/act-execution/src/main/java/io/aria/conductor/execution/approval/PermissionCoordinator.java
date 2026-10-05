package io.aria.conductor.execution.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.mcp.PlatformMcpAutoApproval;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Normalized permission correlation, delivery and operator authorization
 * (spec §6.3). A native permission request is persisted — run, session, request
 * id, tool identity, normalized target, offered options and expiry — before it is
 * displayed, and every decision requires the authenticated operator principal
 * the decision surface resolved from its own transport; a run-scoped worker can
 * therefore never approve itself.
 *
 * <p>Delivery is tracked separately from the approval's status: a decision
 * recorded while the run is manually paused is HELD and delivered only by
 * {@link #deliverPending(UUID)}, after the explicit resume, under a fresh
 * validity check. A {@code NATIVE_TOOL} decision produces the reply the owning
 * core session answers with and hands it to the {@link PermissionReplySink}
 * (the run coordinator that owns the session), so recording a decision and
 * delivering it are one act; a {@code PLATFORM_MCP} allow-once decision issues
 * exactly one {@link WriteGrantService} authorization and produces no native
 * reply. Denials produce no grant.
 *
 * <p>An ask whose tool is on the configured read-only allowlist (operator
 * decisions 2026-09-29 and 2026-10-04; {@link PlatformMcpAutoApproval}) and
 * whose shape is answerable settles at registration through that same delivery
 * path — APPROVED with the policy as its reason, no operator ask and no card.
 * The covered shapes are the platform's own {@code PLATFORM_MCP} delivery (one
 * one-use grant, no native reply) and a {@code NATIVE_TOOL} ask offering the
 * single allow-once option its reply will name: the live shape a core reports
 * for a platform MCP call ({@code mcp__aria-conductor__<tool>}) and, since the
 * 2026-10-04 decision, the cores' own listed read-only tools (e.g. the CLI's
 * {@code WebSearch}/{@code WebFetch} — a bare native name is covered only
 * because the operator listed it explicitly). A native ask not on the list
 * never matches. It is a platform-side policy for the Aria assistant's own
 * coordinated runs; the offered options are never changed and no session-wide
 * grant (allow-always) is ever produced.
 *
 * <p>The sink is resolved lazily because the run coordinator depends on this
 * coordinator to register asks: the lazy lookup is the seam that breaks that
 * construction cycle, and it is only needed at decision time, long after both
 * singletons exist.
 */
@Slf4j
@Service
public class PermissionCoordinator {

    private static final ObjectMapper OPTIONS_MAPPER = new ObjectMapper();

    /**
     * The recorded reason of an ask settled by its own window. The scheduled
     * {@link ApprovalExpiryChecker} sweep records the same text, so an ask
     * expired by the deadline, by a late decision or by the sweep reads
     * identically.
     */
    public static final String EXPIRY_REASON = "Auto-rejected: approval expired";

    /**
     * The recorded reason of a platform MCP ask settled by the configured
     * read-only auto-approval policy (operator decision 2026-09-29). It names
     * the policy, so the decision stays attributable and auditable without an
     * operator acting.
     */
    public static final String AUTO_APPROVE_REASON =
            "auto-approved: read-only platform tool (aria.mcp.auto-approve-read-tools)";

    private final ApprovalGate approvalGate;
    private final ApprovalRepository approvals;
    private final ApprovalDecisionLockRepository decisionLocks;
    private final AcpPermissionRequestRepository permissions;
    private final WriteGrantService writeGrants;
    private final PlatformMcpAutoApproval platformMcpAutoApproval;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    /** The run coordinator that owns the sessions a decided native reply must reach. */
    private final Supplier<PermissionReplySink> replySinks;
    /** Runs whose manual pause holds decided-but-undelivered replies (spec §5.4). */
    private final Set<UUID> manuallyPausedRuns = ConcurrentHashMap.newKeySet();
    /** Serializes the deciders of this instance before they read the approval. */
    private final Object decisionMonitor = new Object();

    @Autowired
    public PermissionCoordinator(ApprovalGate approvalGate, ApprovalRepository approvals,
                                 ApprovalDecisionLockRepository decisionLocks,
                                 AcpPermissionRequestRepository permissions, WriteGrantService writeGrants,
                                 PlatformMcpAutoApproval platformMcpAutoApproval,
                                 ApplicationEventPublisher eventPublisher,
                                 ObjectProvider<PermissionReplySink> replySinks) {
        this(approvalGate, approvals, decisionLocks, permissions, writeGrants, platformMcpAutoApproval,
                eventPublisher, replySinks::getIfAvailable, Clock.systemUTC());
    }

    /** Test/override seam: every expiry check uses this clock. No sink is bound. */
    public PermissionCoordinator(ApprovalGate approvalGate, ApprovalRepository approvals,
                                 ApprovalDecisionLockRepository decisionLocks,
                                 AcpPermissionRequestRepository permissions, WriteGrantService writeGrants,
                                 PlatformMcpAutoApproval platformMcpAutoApproval,
                                 ApplicationEventPublisher eventPublisher, Clock clock) {
        this(approvalGate, approvals, decisionLocks, permissions, writeGrants, platformMcpAutoApproval,
                eventPublisher, () -> null, clock);
    }

    /** Test/override seam: the clock and the reply sink are both explicit. */
    public PermissionCoordinator(ApprovalGate approvalGate, ApprovalRepository approvals,
                                 ApprovalDecisionLockRepository decisionLocks,
                                 AcpPermissionRequestRepository permissions, WriteGrantService writeGrants,
                                 PlatformMcpAutoApproval platformMcpAutoApproval,
                                 ApplicationEventPublisher eventPublisher,
                                 Supplier<PermissionReplySink> replySinks, Clock clock) {
        this.approvalGate = Objects.requireNonNull(approvalGate, "approvalGate");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.decisionLocks = Objects.requireNonNull(decisionLocks, "decisionLocks");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.writeGrants = Objects.requireNonNull(writeGrants, "writeGrants");
        this.platformMcpAutoApproval = Objects.requireNonNull(platformMcpAutoApproval, "platformMcpAutoApproval");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.replySinks = Objects.requireNonNull(replySinks, "replySinks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------
    // Correlation
    // ------------------------------------------------------------------

    /**
     * Persists the correlation of one native permission request before it is
     * displayed and returns the persisted approval id.
     *
     * <p>A re-registration of the identical correlation is idempotent and
     * returns the existing approval; a changed payload for the same correlation
     * is rejected rather than coerced.
     *
     * <p>An ask whose tool is on the configured read-only allowlist (operator
     * decisions 2026-09-29 and 2026-10-04) and whose shape is answerable
     * settles here: the approval is recorded APPROVED with the policy as its
     * reason and delivered through the same path a manual {@code ALLOW_ONCE}
     * decision uses — a one-use grant for a {@code PLATFORM_MCP} ask, the reply
     * the owning core session answers with for a listed native ask that offers
     * the single allow-once option its reply will name — no operator ask is
     * surfaced and nothing waits. An ask whose own window has already closed is
     * never approved past it (spec §5.3) and keeps the operator-facing flow, as
     * does a covered native ask that offers no single allow-once option to
     * answer with.
     */
    @Transactional
    public UUID register(NativePermission request) {
        Objects.requireNonNull(request, "request");
        requireCorrelation(request);
        AcpPermissionRequest existing = permissions.findByRunIdAndSessionIdAndRequestId(
                request.runId(), request.sessionId(), request.requestId()).orElse(null);
        if (existing != null) {
            if (!samePayload(existing, request)) {
                throw new IllegalStateException("Permission request " + request.requestId()
                        + " already exists for run " + request.runId()
                        + " with a different payload; a changed request is rejected");
            }
            return existing.getApprovalId();
        }

        Instant now = clock.instant();
        boolean autoApproved = request.expiresAt().isAfter(now) && autoApprovalCovers(request);
        Approval approval = Approval.builder()
                .runId(request.runId())
                .source(ApprovalSource.ACP_PERMISSION)
                .status(autoApproved ? ApprovalStatus.APPROVED : ApprovalStatus.PENDING)
                .reason(autoApproved ? AUTO_APPROVE_REASON : registrationReason(request))
                .decidedAt(autoApproved ? now : null)
                // The offered options belong on the operator-facing row too: the
                // ledger carries them for correlation, and the granted reply must
                // name the option the core actually offered, so the decision UI
                // and the E2E assertions read exactly what the core asked for.
                .optionsJson(optionsJson(request.options()))
                .expiresAt(request.expiresAt())
                .build();
        approvals.save(approval);

        AcpPermissionRequest row = AcpPermissionRequest.builder()
                .id(UUID.randomUUID())
                .approvalId(approval.getId())
                .kind(AcpPermissionRequest.Kind.NATIVE_PERMISSION)
                .runId(request.runId())
                .sessionId(request.sessionId())
                .requestId(request.requestId())
                .toolName(request.toolName())
                .target(request.target().name())
                .argumentsDigest(WriteGrantService.digestOf(request.argumentsJson()))
                .argumentsJson(request.argumentsJson())
                .optionsJson(optionsJson(request.options()))
                .expiresAt(request.expiresAt())
                .deliveryState(PermissionDeliveryState.AWAITING_DECISION.name())
                .createdAt(now)
                .build();
        permissions.save(row);

        if (autoApproved) {
            // Same delivery path as a manual ALLOW_ONCE decision: a native ask
            // is answered with the reply naming the single allow-once option the
            // core offered, a PLATFORM_MCP delivery issues its one-use grant
            // (and yields no native reply); a manually paused run has the
            // decision held. The policy, named by the reason, decided — no
            // operator ask may surface and nothing may wait.
            row.setSelectedOptionId(request.target() == PermissionTarget.NATIVE_TOOL
                    ? singleAllowOnceOptionId(request.options()).orElse(null)
                    : null);
            row.setDecidedAt(now);
            deliverAndHand(row, PermissionChoice.ALLOW_ONCE, now);
            log.info("Run {}: platform MCP tool {} auto-approved by the configured read-only policy ({})",
                    request.runId(), request.toolName(), AUTO_APPROVE_REASON);
            return approval.getId();
        }

        // Only now — the correlation exists — may the ask surface to the operator.
        eventPublisher.publishEvent(new ApprovalRequestedEvent(this, approval.getId(), request.runId(), null));
        return approval.getId();
    }

    /** True when the approval is correlated with a native permission request. */
    @Transactional(readOnly = true)
    public boolean isNativePermissionRequest(UUID approvalId) {
        return permissions.findByApprovalId(Objects.requireNonNull(approvalId, "approvalId")).isPresent();
    }

    /** Where the delivery of this request stands (never its approval status). */
    @Transactional(readOnly = true)
    public PermissionDeliveryState deliveryState(UUID approvalId) {
        return PermissionDeliveryState.valueOf(rowFor(approvalId).getDeliveryState());
    }

    // ------------------------------------------------------------------
    // Decisions
    // ------------------------------------------------------------------

    /**
     * Applies one operator decision. The operator authority is checked first, so
     * a worker call changes nothing; an already-settled or expired request is
     * rejected and never answered.
     *
     * @return the native reply to hand to the owning session, present only when
     *         the decision was valid and delivery is not held
     */
    @Transactional(noRollbackFor = IllegalStateException.class)
    public Optional<PermissionReply> decide(UUID approvalId, PermissionChoice choice, ActorPrincipal actor) {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(actor, "actor");
        // The single approval authority (spec §6.2): a worker credential, of any run, never decides.
        actor.requireOperator();
        Instant now = clock.instant();
        actor.requireActive(now);

        // Two overlapping operator decisions (double-click, client retry) must not
        // both observe PENDING: the loser would re-enter the gate on the settled
        // row and re-issue — and re-arm — the one-use grant of the identical
        // call. The in-process monitor serializes the deciders before they read
        // the approval and the locked finder holds the row write-lock until the
        // deciding transaction commits, so exactly one decision settles and the
        // loser blocks, observes the settled row and refuses.
        synchronized (decisionMonitor) {
            return decideLocked(approvalId, choice, now);
        }
    }

    private Optional<PermissionReply> decideLocked(UUID approvalId, PermissionChoice choice, Instant now) {
        Approval approval = decisionLocks.findByIdForDecision(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));
        AcpPermissionRequest row = permissions.findByApprovalId(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval " + approvalId
                        + " is not a native permission request; it must be decided through the approval gate"));

        // A settled request is never rewritten — not even when its window has
        // since passed — so the recorded decision (and the delivery it produced)
        // stays the historical fact; only a PENDING row may still expire.
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            if (approval.getStatus() == ApprovalStatus.EXPIRED) {
                markDeliveryExpired(row);
            }
            throw new IllegalStateException("Approval " + approvalId + " is already " + approval.getStatus()
                    + "; a decision on a settled request is refused");
        }
        if (!row.getExpiresAt().isAfter(now)) {
            expire(approval, row, now);
            throw new IllegalStateException("Permission request " + row.getRequestId() + " expired at "
                    + row.getExpiresAt() + "; no decision may be delivered");
        }

        String optionId = selectOption(row, choice);
        boolean approved = choice == PermissionChoice.ALLOW_ONCE;
        // Reuse the gate's decision internals: status, reason, decidedAt, tool-call
        // update, waiter release and ApprovalDecidedEvent stay identical to every
        // other approval decision.
        approvalGate.decideApproval(approvalId, approved, decisionReason(row, choice));

        row.setSelectedOptionId(optionId);
        row.setDecidedAt(now);
        return deliverAndHand(row, choice, now);
    }

    /**
     * Records the decision's delivery — a one-use grant for a {@code PLATFORM_MCP}
     * ask, the native reply for a {@code NATIVE_TOOL} ask (held while the run is
     * manually paused) — and hands a delivered reply to the run's owning core
     * session. Recording the decision is not delivery: the reply is handed to
     * the run coordinator, which pushes it to the run-owned session the ask came
     * from. A run this process does not own has no such session -- the sink
     * ignores it instead of routing the reply anywhere else.
     */
    private Optional<PermissionReply> deliverAndHand(AcpPermissionRequest row, PermissionChoice choice,
                                                     Instant now) {
        Optional<PermissionReply> reply = deliverDecision(row, choice, now);
        reply.ifPresent(this::handToOwningSession);
        return reply;
    }

    /**
     * True when the configured read-only policy settles {@code request}: its
     * tool is on the operator-configured list and its shape is answerable with
     * a single allow-once reply. A {@code PLATFORM_MCP} ask is covered by the
     * list alone (a one-use grant, no native reply); a listed {@code NATIVE_TOOL}
     * ask is covered when it offers the single allow-once option its reply will
     * name — the manual ALLOW_ONCE decision's own fail-closed selection — with
     * the operator's explicit listing as the provenance gate for a bare native
     * name (the CLI's {@code WebSearch}/{@code WebFetch} carries no platform
     * prefix by construction).
     *
     * <p>A native ask NOT on the list is never covered: it keeps the per-call
     * operator approval (spec §6.3), like a listed native ask that offers no
     * single allow-once option to answer with.
     */
    private boolean autoApprovalCovers(NativePermission request) {
        if (!platformMcpAutoApproval.allows(request.toolName())) {
            return false;
        }
        if (request.target() == PermissionTarget.PLATFORM_MCP) {
            return true;
        }
        // A listed NATIVE_TOOL auto-settles only through the single allow-once
        // option its reply will name -- the exact shape the platform-MCP branch
        // already required. The operator's explicit listing is the provenance
        // gate for a bare native name (the CLI's own WebSearch/WebFetch carries
        // no platform prefix by construction).
        return request.target() == PermissionTarget.NATIVE_TOOL
                && singleAllowOnceOptionId(request.options()).isPresent();
    }

    /**
     * The option id a native ask's reply must name when exactly one allow-once
     * option was offered; empty when none or several were offered, because the
     * policy never guesses what the core did not offer (the manual ALLOW_ONCE
     * decision's own fail-closed selection).
     */
    private static Optional<String> singleAllowOnceOptionId(List<PermissionOption> options) {
        List<PermissionOption> matches = options.stream()
                .filter(option -> option.choice() == PermissionChoice.ALLOW_ONCE)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0).optionId()) : Optional.empty();
    }

    /**
     * The delivery step a manual decision and the policy auto-approval share. A
     * decided ask of a manually paused run is HELD (never delivered while the
     * pause holds); otherwise the decision is delivered through {@link #deliver}
     * for this choice.
     */
    private Optional<PermissionReply> deliverDecision(AcpPermissionRequest row, PermissionChoice choice,
                                                      Instant now) {
        if (manuallyPausedRuns.contains(row.getRunId())) {
            // Recorded, held: resume does not approve, it only re-opens delivery.
            row.setDeliveryState(PermissionDeliveryState.HELD_MANUAL_PAUSE.name());
            permissions.save(row);
            return Optional.empty();
        }
        return Optional.ofNullable(deliver(row, choice, now));
    }

    /**
     * Hands one delivered reply to the run's owning session through the bound
     * {@link PermissionReplySink}. The sink is resolved at decision time (the
     * coordinator may not exist yet when this bean is constructed); a refusing
     * or absent sink leaves the decision recorded, which is what the operator
     * already observed, and is reported rather than escalated.
     */
    private void handToOwningSession(PermissionReply delivered) {
        PermissionReplySink sink = replySinks.get();
        if (sink == null) {
            return;
        }
        try {
            sink.deliver(delivered);
        } catch (RuntimeException e) {
            // The decision is recorded; a delivery failure is reported and never
            // turned into a second decision or a rolled-back grant.
            log.warn("The decided reply for request {} was recorded but not delivered to its"
                    + " run-owned session: {}", delivered.requestId(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Delivery
    // ------------------------------------------------------------------

    /**
     * Expires every still-PENDING native ask of one run at its own recorded
     * window. The run's frozen deadline is also the ask's own expiry
     * ({@link #register} persists {@code expiresAt = runtime.spec().deadline()}),
     * so the run-deadline enforcement settles each ask by its own timeout
     * <em>before</em> it stops the run: the ask is EXPIRED with the expiry
     * adjudication ({@value #EXPIRY_REASON}), never rewritten as a consequence
     * of the stop. Only asks whose recorded window is at or before {@code asOf}
     * are touched — an ask still inside its window and an already-settled ask
     * are both left exactly as they are — and each settled ask's blocked waiter
     * is released exactly like the scheduled sweep does.
     *
     * @param runId the run whose pending asks the deadline reached
     * @param asOf  the instant the run deadline fired (the adjudication instant)
     * @return the number of asks this call settled
     */
    @Transactional
    public int expirePendingForRun(UUID runId, Instant asOf) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(asOf, "asOf");
        int settled = 0;
        for (AcpPermissionRequest row : permissions.findByRunId(runId)) {
            Approval approval = approvals.findById(row.getApprovalId()).orElse(null);
            if (approval == null || approval.getStatus() != ApprovalStatus.PENDING) {
                continue; // a settled ask is never rewritten
            }
            if (row.getExpiresAt().isAfter(asOf)) {
                continue; // still inside its own window
            }
            expire(approval, row, asOf);
            settled++;
        }
        return settled;
    }

    /**
     * The scheduled backstop for native asks orphaned past their own window —
     * the crash/restart windows in which no run deadline fired to settle them:
     * every ledger row still {@code AWAITING_DECISION} whose window has closed
     * is adjudicated exactly like the per-run deadline path — EXPIRED with the
     * recorded expiry reason ({@value #EXPIRY_REASON}), the ledger delivery
     * moved to EXPIRED, the blocked waiter released, and
     * {@link ApprovalExpiredEvent} published with the ask's tool name. A row
     * whose approval is missing or no longer PENDING is skipped: a settled ask
     * is never rewritten.
     *
     * @param asOf the sweep instant
     * @return the number of asks this call settled
     */
    @Transactional
    public int expireOverdueNativeAsks(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        int settled = 0;
        for (AcpPermissionRequest row : permissions.findByDeliveryStateAndExpiresAtBefore(
                PermissionDeliveryState.AWAITING_DECISION.name(), asOf)) {
            Approval approval = approvals.findById(row.getApprovalId()).orElse(null);
            if (approval == null || approval.getStatus() != ApprovalStatus.PENDING) {
                continue; // a settled ask is never rewritten
            }
            expireWith(approval, row, asOf, EXPIRY_REASON);
            settled++;
        }
        return settled;
    }

    /**
     * Settles every still-PENDING native ask of a run whose runtime has ended
     * (the run can no longer receive a reply, so the ask is adjudicated, never
     * left behind): EXPIRED with the recorded reason "run ended", the waiter
     * released, and {@link ApprovalExpiredEvent} published. Already-settled
     * asks are never rewritten.
     */
    @Transactional
    public int cancelPendingForRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        int settled = 0;
        for (AcpPermissionRequest row : permissions.findByRunId(runId)) {
            Approval approval = approvals.findById(row.getApprovalId()).orElse(null);
            if (approval == null || approval.getStatus() != ApprovalStatus.PENDING) {
                continue;
            }
            expireWith(approval, row, clock.instant(), "run ended");
            settled++;
        }
        return settled;
    }

    /**
     * Delivers a decision that was held by a manual pause. Re-checks the state
     * inside this transaction: still paused, already delivered, not decided, or
     * expired → nothing is delivered, and an expired delivery is never replayed
     * (the recorded decision itself is not rewritten).
     */
    @Transactional
    public Optional<PermissionReply> deliverPending(UUID approvalId) {
        AcpPermissionRequest row = rowFor(approvalId);
        if (!PermissionDeliveryState.HELD_MANUAL_PAUSE.name().equals(row.getDeliveryState())) {
            return Optional.empty();
        }
        Approval approval = approvals.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));
        Instant now = clock.instant();
        if (manuallyPausedRuns.contains(row.getRunId())) {
            return Optional.empty();
        }
        if (!row.getExpiresAt().isAfter(now)) {
            markDeliveryExpired(row);
            return Optional.empty();
        }
        if (approval.getStatus() != ApprovalStatus.APPROVED && approval.getStatus() != ApprovalStatus.DENIED) {
            return Optional.empty();
        }
        PermissionChoice choice = approval.getStatus() == ApprovalStatus.APPROVED
                ? PermissionChoice.ALLOW_ONCE : PermissionChoice.DENY;
        return Optional.ofNullable(deliver(row, choice, now));
    }

    /**
     * Manual pause of a run (spec §5.4): decided-but-undelivered replies stay
     * held until {@link #manualResume(UUID)}. Permission waiting and the manual
     * pause are separate control reasons; the pause never approves an ask.
     */
    public void manualPause(UUID runId) {
        manuallyPausedRuns.add(Objects.requireNonNull(runId, "runId"));
    }

    /** Clears the delivery hold; held decisions are re-validated by {@link #deliverPending(UUID)}. */
    public void manualResume(UUID runId) {
        manuallyPausedRuns.remove(Objects.requireNonNull(runId, "runId"));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Hands the decision to its boundary: a {@code NATIVE_TOOL} decision yields
     * the reply the owning session answers with, a {@code PLATFORM_MCP}
     * allow-once decision issues exactly one one-use grant instead.
     */
    private PermissionReply deliver(AcpPermissionRequest row, PermissionChoice choice, Instant now) {
        PermissionTarget target = PermissionTarget.valueOf(row.getTarget());
        if (target == PermissionTarget.PLATFORM_MCP && choice == PermissionChoice.ALLOW_ONCE) {
            writeGrants.grant(row.getRunId(), row.getToolName(), row.getArgumentsDigest(), row.getExpiresAt());
        }
        row.setDeliveryState(PermissionDeliveryState.DELIVERED.name());
        row.setDeliveredAt(now);
        permissions.save(row);
        if (target == PermissionTarget.PLATFORM_MCP) {
            return null; // authorizing the call is the delivery; no core session is waiting
        }
        return new PermissionReply(row.getRunId(), row.getSessionId(), row.getRequestId(), row.getSelectedOptionId());
    }

    /**
     * Selects the offered option whose normalized kind matches the choice —
     * never an option's position and never an allow-always substitute. Exactly
     * one matching option must have been offered; anything else fails closed.
     */
    private String selectOption(AcpPermissionRequest row, PermissionChoice choice) {
        List<PermissionOption> options = parseOptions(row);
        List<String> offered = options.stream().map(PermissionOption::optionId).toList();
        List<PermissionOption> matches = options.stream().filter(option -> option.choice() == choice).toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException("Permission request " + row.getRequestId() + " offered no " + choice
                    + " option; offered: " + offered);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Permission request " + row.getRequestId() + " offered more than one "
                    + choice + " option; offered: " + offered);
        }
        return matches.get(0).optionId();
    }

    /**
     * Expires an ask that is still PENDING (the decide path checks the settled
     * state first, so a recorded decision is never rewritten). The blocked
     * waiter is released exactly like the scheduled sweep does, otherwise a
     * session blocked on the ask would wait for its own timeout.
     */
    private void expire(Approval approval, AcpPermissionRequest row, Instant now) {
        expireWith(approval, row, now, EXPIRY_REASON);
    }

    /**
     * The single native settle-by-timeout path. Publishes {@link ApprovalExpiredEvent}
     * with the ask's tool name so the operator learns what was skipped.
     */
    private void expireWith(Approval approval, AcpPermissionRequest row, Instant now, String reason) {
        approval.setStatus(ApprovalStatus.EXPIRED);
        approval.setReason(reason);
        approval.setDecidedAt(now);
        approvals.save(approval);
        markDeliveryExpired(row);
        approvalGate.cancelPendingApproval(approval.getId());
        eventPublisher.publishEvent(new ApprovalExpiredEvent(this, approval.getId(),
                approval.getRunId(), approval.getReason(), row.getToolName()));
    }

    private void markDeliveryExpired(AcpPermissionRequest row) {
        row.setDeliveryState(PermissionDeliveryState.EXPIRED.name());
        permissions.save(row);
    }

    private AcpPermissionRequest rowFor(UUID approvalId) {
        Objects.requireNonNull(approvalId, "approvalId");
        return permissions.findByApprovalId(approvalId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No native permission request is correlated with approval " + approvalId));
    }

    private static void requireCorrelation(NativePermission request) {
        requireText(request.runId() == null ? null : request.runId().toString(), "runId");
        requireText(request.sessionId(), "sessionId");
        requireText(request.requestId(), "requestId");
        requireText(request.toolName(), "toolName");
        Objects.requireNonNull(request.target(), "target");
        Objects.requireNonNull(request.expiresAt(), "expiresAt");
        if (request.options().isEmpty()) {
            throw new IllegalArgumentException("A native permission request must offer at least one decision option");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A native permission request requires a " + field);
        }
    }

    private static boolean samePayload(AcpPermissionRequest row, NativePermission request) {
        return row.getToolName().equals(request.toolName())
                && row.getTarget().equals(request.target().name())
                && row.getArgumentsDigest().equals(WriteGrantService.digestOf(request.argumentsJson()))
                && row.getOptionsJson().equals(optionsJson(request.options()))
                && row.getExpiresAt().equals(request.expiresAt());
    }

    private static String registrationReason(NativePermission request) {
        return "Native permission request " + request.requestId() + " from session " + request.sessionId()
                + " for tool " + request.toolName() + " (" + request.target() + ")";
    }

    private static String decisionReason(AcpPermissionRequest row, PermissionChoice choice) {
        String verdict = choice == PermissionChoice.ALLOW_ONCE
                ? "Operator allowed one use of " + row.getToolName()
                : "Operator denied " + row.getToolName();
        return verdict + " (native permission request " + row.getRequestId() + ")";
    }

    private static String optionsJson(List<PermissionOption> options) {
        try {
            return OPTIONS_MAPPER.writeValueAsString(options);
        } catch (Exception e) {
            throw new IllegalStateException("Offered permission options could not be persisted", e);
        }
    }

    private static List<PermissionOption> parseOptions(AcpPermissionRequest row) {
        try {
            return OPTIONS_MAPPER.readValue(row.getOptionsJson(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Permission request " + row.getRequestId()
                    + " carries no readable option set; the decision is refused", e);
        }
    }
}
