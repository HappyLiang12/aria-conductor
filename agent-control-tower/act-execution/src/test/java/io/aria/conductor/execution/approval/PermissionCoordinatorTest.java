package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Native permission correlation, delivery and operator authorization (spec §6.3).
 *
 * <p>The repository mocks are backed by concurrent in-memory stores so the real
 * correlation, delivery-state and option-selection logic is exercised; the
 * {@link ApprovalGate} is real (its repositories are the same stores), so the
 * decision behaviour it owns — status, reason, decidedAt, decided event — is
 * reused rather than re-implemented.
 */
@ExtendWith(MockitoExtension.class)
class PermissionCoordinatorTest {

    private static final Instant T0 = Instant.parse("2026-09-22T12:00:00Z");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID OTHER_RUN = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final String SESSION_ID = "ses_fixture_1";
    private static final String REQUEST_ID = "0";
    private static final String TOOL = "write_file";
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-22T12:05:00Z");

    /** The offered options in the recorded native order: reject first, allow-once last. */
    private static final List<PermissionOption> OFFERED = List.of(
            new PermissionOption("cancel", PermissionChoice.DENY),
            new PermissionOption("proceed_once", PermissionChoice.ALLOW_ONCE));

    /**
     * Mirror of the dashboard's `NativePermissionAsk.NATIVE_PERMISSION_REASON`:
     * group 1 = request id, group 2 = tool name, group 3 = normalized target.
     */
    private static final String UI_NATIVE_REASON_PATTERN =
            "^Native permission request (\\S+) from session \\S+ for tool (\\S+) \\((NATIVE_TOOL|PLATFORM_MCP)\\)$";

    @Mock private ApprovalRepository approvalRepository;
    @Mock private ApprovalDecisionLockRepository decisionLocks;
    @Mock private ToolCallRepository toolCallRepository;
    @Mock private AcpPermissionRequestRepository permissionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final Map<UUID, Approval> approvalStore = new ConcurrentHashMap<>();
    private final Map<UUID, AcpPermissionRequest> permissionStore = new ConcurrentHashMap<>();
    private final List<ApprovalRequestedEvent> requestedEvents = new ArrayList<>();

    /** Orchestration state of the decide-vs-decide race (one test uses it). */
    private final Map<String, String> deciderRoles = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> deciderReads = new ConcurrentHashMap<>();
    private final AtomicBoolean winningDeciderTaken = new AtomicBoolean();
    private final CountDownLatch retryReadPending = new CountDownLatch(1);
    private final CountDownLatch retryAtGate = new CountDownLatch(1);
    private final CountDownLatch releaseRetryDecision = new CountDownLatch(1);

    private MutableClock clock = new MutableClock(T0);
    private ApprovalGate gate;
    private WriteGrantService writeGrants;
    private PermissionCoordinator coordinator;

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceTo(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        lenient().when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> {
            Approval approval = inv.getArgument(0);
            if (approval.getId() == null) {
                approval.setId(UUID.randomUUID());
            }
            approvalStore.put(approval.getId(), approval);
            return approval;
        });
        lenient().when(approvalRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(approvalStore.get(inv.<UUID>getArgument(0))));
        lenient().when(decisionLocks.findByIdForDecision(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(approvalStore.get(inv.<UUID>getArgument(0))));
        lenient().when(toolCallRepository.save(any(ToolCall.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(toolCallRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        lenient().when(permissionRepository.save(any(AcpPermissionRequest.class))).thenAnswer(inv -> {
            AcpPermissionRequest row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            permissionStore.put(row.getId(), row);
            return row;
        });
        lenient().when(permissionRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(permissionStore.get(inv.<UUID>getArgument(0))));
        lenient().when(permissionRepository.findByApprovalId(any(UUID.class))).thenAnswer(inv -> {
            UUID approvalId = inv.getArgument(0);
            return permissionStore.values().stream()
                    .filter(row -> approvalId.equals(row.getApprovalId()))
                    .findFirst();
        });
        lenient().when(permissionRepository.findByRunIdAndSessionIdAndRequestId(
                        any(UUID.class), any(String.class), any(String.class)))
                .thenAnswer(inv -> {
                    UUID runId = inv.getArgument(0);
                    String sessionId = inv.getArgument(1);
                    String requestId = inv.getArgument(2);
                    return permissionStore.values().stream()
                            .filter(row -> runId.equals(row.getRunId()))
                            .filter(row -> sessionId.equals(row.getSessionId()))
                            .filter(row -> requestId.equals(row.getRequestId()))
                            .findFirst();
                });

        lenient().doAnswer(inv -> {
            requestedEvents.add(inv.getArgument(0));
            return null;
        }).when(eventPublisher).publishEvent(any(ApprovalRequestedEvent.class));
    }

    private PermissionCoordinator coordinator(Instant now) {
        return coordinator(now, null);
    }

    /** A coordinator whose decided native replies are handed to the given sink. */
    private PermissionCoordinator coordinator(Instant now, PermissionReplySink sink) {
        this.clock = new MutableClock(now);
        this.gate = new ApprovalGate(approvalRepository, toolCallRepository, eventPublisher, 30_000L);
        this.writeGrants = new WriteGrantService(permissionRepository, clock);
        return new PermissionCoordinator(gate, approvalRepository, decisionLocks, permissionRepository,
                writeGrants, eventPublisher, () -> sink, clock);
    }

    private NativePermission permission(PermissionTarget target) {
        return new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID, TOOL, target,
                "{\"path\":\"notes.txt\",\"content\":\"hello\"}", OFFERED, EXPIRES_AT);
    }

    // ------------------------------------------------------------------
    // registration
    // ------------------------------------------------------------------

    @Test
    void registrationPersistsTheCorrelationBeforeDisplayAndReturnsTheApprovalId() {
        coordinator = coordinator(T0);

        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));

        Approval approval = approvalStore.get(approvalId);
        assertThat(approval.getRunId()).isEqualTo(RUN_ID);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approval.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(approval.getReason())
                .isEqualTo("Native permission request 0 from session ses_fixture_1 for tool write_file (NATIVE_TOOL)");
        assertThat(approval.getApprovalType()).isEqualTo(Approval.ApprovalType.TOOL_CALL);

        AcpPermissionRequest row = permissionRow(approvalId);
        assertThat(row.getKind()).isEqualTo(AcpPermissionRequest.Kind.NATIVE_PERMISSION);
        assertThat(row.getRunId()).isEqualTo(RUN_ID);
        assertThat(row.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(row.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(row.getToolName()).isEqualTo(TOOL);
        assertThat(row.getTarget()).isEqualTo(PermissionTarget.NATIVE_TOOL.name());
        assertThat(row.getDeliveryState()).isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
        assertThat(row.getOptionsJson())
                .isEqualTo("[{\"optionId\":\"cancel\",\"choice\":\"DENY\"}," +
                        "{\"optionId\":\"proceed_once\",\"choice\":\"ALLOW_ONCE\"}]");
        assertThat(row.getCreatedAt()).isEqualTo(T0);
        assertThat(coordinator.deliveryState(approvalId)).isEqualTo(PermissionDeliveryState.AWAITING_DECISION);

        // The dashboard ask is published only after the correlation row exists.
        assertThat(requestedEvents).hasSize(1);
        assertThat(requestedEvents.get(0).getApprovalId()).isEqualTo(approvalId);
        assertThat(requestedEvents.get(0).getRunId()).isEqualTo(RUN_ID);
        assertThat(requestedEvents.get(0).getToolCallId()).isNull();
    }

    /**
     * Contract pin with the Review surface (Task 15 fix round 1): the dashboard
     * recognizes a normalized native ask by parsing the approval's registration
     * reason with the anchored pattern below (see the dashboard's
     * {@code NativePermissionAsk}). A wording change here silently demotes the
     * ask to a plain gate approval — the kind/expiry row disappears and the ask
     * enters the batch approval — so the rendered shape is pinned verbatim and
     * parsed back into exactly the correlation facts the Review surface shows.
     */
    @Test
    void registrationReasonRendersTheExactShapeTheReviewSurfaceParses() {
        coordinator = coordinator(T0);

        UUID nativeAskId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));
        String nativeReason = approvalStore.get(nativeAskId).getReason();
        assertThat(nativeReason).isEqualTo(
                "Native permission request 0 from session ses_fixture_1 for tool write_file (NATIVE_TOOL)");

        NativePermission platformAsk = new NativePermission(OTHER_RUN, "ses_fixture_2", "req-9",
                "shell_exec", PermissionTarget.PLATFORM_MCP, "{}", OFFERED, EXPIRES_AT);
        UUID platformAskId = coordinator.register(platformAsk);
        String platformReason = approvalStore.get(platformAskId).getReason();
        assertThat(platformReason).isEqualTo(
                "Native permission request req-9 from session ses_fixture_2 for tool shell_exec (PLATFORM_MCP)");

        // The exact predicate the dashboard's NativePermissionAsk applies.
        Matcher nativeMatch = Pattern.compile(UI_NATIVE_REASON_PATTERN).matcher(nativeReason);
        assertThat(nativeMatch.matches()).isTrue();
        assertThat(nativeMatch.group(1)).isEqualTo("0");
        assertThat(nativeMatch.group(2)).isEqualTo("write_file");
        assertThat(nativeMatch.group(3)).isEqualTo("NATIVE_TOOL");

        Matcher platformMatch = Pattern.compile(UI_NATIVE_REASON_PATTERN).matcher(platformReason);
        assertThat(platformMatch.matches()).isTrue();
        assertThat(platformMatch.group(1)).isEqualTo("req-9");
        assertThat(platformMatch.group(2)).isEqualTo("shell_exec");
        assertThat(platformMatch.group(3)).isEqualTo("PLATFORM_MCP");
    }

    @Test
    void identicalReRegistrationIsIdempotentAndAChangedPayloadIsRejected() {
        coordinator = coordinator(T0);
        UUID first = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));

        UUID again = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));
        assertThat(again).isEqualTo(first);
        assertThat(permissionStore).hasSize(1);
        assertThat(approvalStore).hasSize(1);
        assertThat(requestedEvents).hasSize(1);

        NativePermission changed = new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID, "shell_exec",
                PermissionTarget.NATIVE_TOOL, "{\"cmd\":\"rm -rf /\"}", OFFERED, EXPIRES_AT);
        assertThatThrownBy(() -> coordinator.register(changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Permission request 0 already exists for run " + RUN_ID
                        + " with a different payload; a changed request is rejected");
        assertThat(permissionRow(first).getToolName()).isEqualTo(TOOL);
    }

    // ------------------------------------------------------------------
    // allow-once / deny / position independence
    // ------------------------------------------------------------------

    @Test
    void allowOnceSelectsTheOfferedOptionByChoiceNotByPosition() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));

        Optional<PermissionReply> reply = coordinator.decide(
                approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        assertThat(reply).contains(new PermissionReply(RUN_ID, SESSION_ID, REQUEST_ID, "proceed_once"));
        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approvalStore.get(approvalId).getReason())
                .isEqualTo("Operator allowed one use of write_file (native permission request 0)");
        AcpPermissionRequest row = permissionRow(approvalId);
        assertThat(row.getSelectedOptionId()).isEqualTo("proceed_once");
        assertThat(row.getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(row.getDecidedAt()).isEqualTo(T0);
        assertThat(row.getDeliveredAt()).isEqualTo(T0);
    }

    @Test
    void allowOnceDecisionIsHandedToTheOwningSessionSinkExactlyOnce() {
        List<PermissionReply> delivered = new ArrayList<>();
        coordinator = coordinator(T0, delivered::add);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));

        Optional<PermissionReply> reply = coordinator.decide(
                approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        // Recording the decision and handing the reply to the run-owned session
        // are one act: a decided ask must never leave its core waiting.
        assertThat(reply).isPresent();
        assertThat(delivered).containsExactly(reply.get());
        assertThat(delivered.get(0).runId()).isEqualTo(RUN_ID);
        assertThat(delivered.get(0).sessionId()).isEqualTo(SESSION_ID);
        assertThat(delivered.get(0).requestId()).isEqualTo(REQUEST_ID);
        assertThat(delivered.get(0).optionId()).isEqualTo("proceed_once");
    }

    @Test
    void aDecisionHeldByAManualPauseIsNotHandedToTheSinkUntilTheResumeOwnerDeliversIt() {
        List<PermissionReply> delivered = new ArrayList<>();
        coordinator = coordinator(T0, delivered::add);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));
        coordinator.manualPause(RUN_ID);

        // Recorded, held: nothing is handed to the session yet.
        assertThat(coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null)))
                .isEmpty();
        assertThat(delivered).isEmpty();

        // The resume owner hands the released reply over itself; this coordinator
        // must not hand the same reply over a second time.
        coordinator.manualResume(RUN_ID);
        assertThat(coordinator.deliverPending(approvalId)).isPresent();
        assertThat(delivered).isEmpty();
    }

    @Test
    void denyDeliversTheRejectOptionAndProducesNoAuthorization() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.PLATFORM_MCP));

        Optional<PermissionReply> reply = coordinator.decide(
                approvalId, PermissionChoice.DENY, ActorPrincipal.operator(null));

        // A platform-MCP decision authorizes a call, not a native session reply.
        assertThat(reply).isEmpty();
        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(approvalStore.get(approvalId).getReason())
                .isEqualTo("Operator denied write_file (native permission request 0)");
        AcpPermissionRequest row = permissionRow(approvalId);
        assertThat(row.getSelectedOptionId()).isEqualTo("cancel");
        assertThat(row.getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(writeGrants.consume(RUN_ID, TOOL, row.getArgumentsDigest())).isEqualTo(false);
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
    }

    @Test
    void platformAllowOnceAuthorizesExactlyOneMatchingToolCall() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.PLATFORM_MCP));

        Optional<PermissionReply> reply = coordinator.decide(
                approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        assertThat(reply).isEmpty();
        AcpPermissionRequest row = permissionRow(approvalId);
        assertThat(row.getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(row.getArgumentsDigest()).isEqualTo(WriteGrantService.digestOf(
                "{\"path\":\"notes.txt\",\"content\":\"hello\"}"));
        assertThat(writeGrants.consume(RUN_ID, TOOL, row.getArgumentsDigest())).isEqualTo(true);
        assertThat(writeGrants.consume(RUN_ID, TOOL, row.getArgumentsDigest())).isEqualTo(false);
        assertThat(writeGrants.consume(OTHER_RUN, TOOL, row.getArgumentsDigest())).isEqualTo(false);
    }

    @Test
    void aNativeAllowOnceIssuesNoPlatformGrantSoTheWriteCannotRepeatThroughMcp() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));

        Optional<PermissionReply> reply = coordinator.decide(
                approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        assertThat(reply).contains(new PermissionReply(RUN_ID, SESSION_ID, REQUEST_ID, "proceed_once"));
        // The native reply answered the core; the platform MCP boundary holds no grant for the same call.
        assertThat(writeGrants.consume(RUN_ID, TOOL,
                WriteGrantService.digestOf("{\"path\":\"notes.txt\",\"content\":\"hello\"}"))).isEqualTo(false);
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
    }

    // ------------------------------------------------------------------
    // authorization
    // ------------------------------------------------------------------

    @Test
    void aWorkerDecisionIsRefusedAndLeavesTheApprovalPending() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));

        assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE,
                new ActorPrincipal(ActorPrincipal.Role.WORKER, OTHER_RUN, T0.plusSeconds(300))))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Operator authority required");

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(permissionRow(approvalId).getDeliveryState()).isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
        assertThat(permissionRow(approvalId).getSelectedOptionId()).isNull();
    }

    @Test
    void aWorkerOfTheOwningRunCannotSelfApproveEither() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.PLATFORM_MCP));

        assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE,
                new ActorPrincipal(ActorPrincipal.Role.WORKER, RUN_ID, T0.plusSeconds(300))))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Operator authority required");

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
    }

    @Test
    void anExpiredOperatorCredentialIsRefusedBeforeAnyStateChange() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));

        assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE,
                new ActorPrincipal(ActorPrincipal.Role.OPERATOR, null, T0.minusSeconds(1))))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Actor credential expired");

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    // ------------------------------------------------------------------
    // late / duplicate / unchanged requests
    // ------------------------------------------------------------------

    @Test
    void aDecisionForAnExpiredRequestIsRejectedAndNothingIsDelivered() {
        coordinator = coordinator(T0.plus(Duration.ofMinutes(6)));
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));

        assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE,
                ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Permission request 0 expired at 2026-09-22T12:05:00Z; no decision may be delivered");

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(permissionRow(approvalId).getDeliveryState()).isEqualTo(PermissionDeliveryState.EXPIRED.name());
        assertThat(coordinator.deliverPending(approvalId)).isEmpty();
    }

    /**
     * The expiry check must never rewrite a settled approval: the ALLOW_ONCE
     * decision that authorized the write stays APPROVED with its own reason and
     * DELIVERED delivery after the ask's window has passed, and the late
     * decision is refused as settled (the gate's idempotent no-op contract for
     * a non-PENDING row is preserved).
     */
    @Test
    void aLateDecisionAfterExpiryNeverRewritesAnApprovedRequest() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));
        coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        Approval approved = approvalStore.get(approvalId);
        Instant decidedAt = approved.getDecidedAt();
        AcpPermissionRequest delivered = permissionRow(approvalId);
        Instant deliveredAt = delivered.getDeliveredAt();
        assertThat(decidedAt).isNotNull();
        assertThat(deliveredAt).isNotNull();

        clock.advanceTo(EXPIRES_AT.plusSeconds(1));

        assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.DENY, ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Approval " + approvalId
                        + " is already APPROVED; a decision on a settled request is refused");

        assertThat(approved.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approved.getReason())
                .isEqualTo("Operator allowed one use of write_file (native permission request 0)");
        assertThat(approved.getDecidedAt()).isEqualTo(decidedAt);
        assertThat(delivered.getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(delivered.getDeliveredAt()).isEqualTo(deliveredAt);
        assertThat(delivered.getSelectedOptionId()).isEqualTo("proceed_once");
    }

    /** The DENIED variant of the same invariant: a late decision never rewrites the refusal. */
    @Test
    void aLateDecisionAfterExpiryNeverRewritesADeniedRequest() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.PLATFORM_MCP));
        coordinator.decide(approvalId, PermissionChoice.DENY, ActorPrincipal.operator(null));

        Approval denied = approvalStore.get(approvalId);
        Instant decidedAt = denied.getDecidedAt();
        AcpPermissionRequest delivered = permissionRow(approvalId);
        Instant deliveredAt = delivered.getDeliveredAt();
        assertThat(decidedAt).isNotNull();
        assertThat(deliveredAt).isNotNull();

        clock.advanceTo(EXPIRES_AT.plusSeconds(1));

        assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE,
                ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Approval " + approvalId
                        + " is already DENIED; a decision on a settled request is refused");

        assertThat(denied.getStatus()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(denied.getReason()).isEqualTo("Operator denied write_file (native permission request 0)");
        assertThat(denied.getDecidedAt()).isEqualTo(decidedAt);
        assertThat(delivered.getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(delivered.getDeliveredAt()).isEqualTo(deliveredAt);
        assertThat(delivered.getSelectedOptionId()).isEqualTo("cancel");
        // The late allow-once is a settled refusal, not a second authorization.
        assertThat(writeGrants.consume(RUN_ID, TOOL, delivered.getArgumentsDigest())).isEqualTo(false);
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
    }

    /**
     * The coordinator's expiry path must release a blocked waiter exactly like
     * the scheduled {@link ApprovalExpiryChecker} does (it calls the same
     * {@link ApprovalGate#cancelPendingApproval}); otherwise a session blocked
     * on the ask waits for its own timeout.
     */
    @Test
    void anExpiredAskReleasesABlockedWaiterLikeTheScheduledSweep() {
        coordinator = coordinator(T0.plus(Duration.ofMinutes(6)));
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));
        CompletableFuture<ApprovalDecision> waiter = new CompletableFuture<>();
        pendingApprovals(gate).put(approvalId, waiter);

        assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE,
                ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Permission request 0 expired at 2026-09-22T12:05:00Z; no decision may be delivered");

        assertThat(waiter.isDone()).isTrue();
        assertThat(waiter.getNow(null)).isNotNull();
        assertThat(waiter.getNow(null).isApproved()).isFalse();
        assertThat(waiter.getNow(null).reason()).isEqualTo("Run cancelled");
        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
    }

    @Test
    void aSecondDecisionOnASettledRequestIsRejectedAndTheFirstDecisionStands() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));
        coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.DENY, ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Approval " + approvalId + " is already APPROVED; a decision on a settled request is refused");

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approvalStore.get(approvalId).getReason())
                .isEqualTo("Operator allowed one use of write_file (native permission request 0)");
        assertThat(permissionRow(approvalId).getSelectedOptionId()).isEqualTo("proceed_once");
    }

    @Test
    void aDecisionForALegacyApprovalWithoutCorrelationIsRejected() {
        coordinator = coordinator(T0);
        Approval legacy = Approval.builder().runId(RUN_ID).status(ApprovalStatus.PENDING)
                .reason("Agent requests approval to execute git_push")
                .expiresAt(EXPIRES_AT).build();
        approvalRepository.save(legacy);

        assertThatThrownBy(() -> coordinator.decide(legacy.getId(), PermissionChoice.ALLOW_ONCE,
                ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Approval " + legacy.getId()
                        + " is not a native permission request; it must be decided through the approval gate");
        assertThat(approvalStore.get(legacy.getId()).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(coordinator.isNativePermissionRequest(legacy.getId())).isEqualTo(false);
    }

    @Test
    void aDecisionWithoutAMatchingOfferedOptionIsRefusedWithoutChangingState() {
        coordinator = coordinator(T0);
        NativePermission allowOnly = new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID, TOOL,
                PermissionTarget.NATIVE_TOOL, "{}",
                List.of(new PermissionOption("proceed_once", PermissionChoice.ALLOW_ONCE)), EXPIRES_AT);
        UUID approvalId = coordinator.register(allowOnly);

        assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.DENY, ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Permission request 0 offered no DENY option; offered: [proceed_once]");

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(permissionRow(approvalId).getDeliveryState()).isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
    }

    // ------------------------------------------------------------------
    // delivery and manual pause
    // ------------------------------------------------------------------

    @Test
    void aDecisionWhileManuallyPausedIsRecordedAndDeliveredOnlyAfterResume() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));
        coordinator.manualPause(RUN_ID);

        Optional<PermissionReply> held = coordinator.decide(
                approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        assertThat(held).isEmpty();
        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(permissionRow(approvalId).getDeliveryState()).isEqualTo(PermissionDeliveryState.HELD_MANUAL_PAUSE.name());
        assertThat(coordinator.deliveryState(approvalId)).isEqualTo(PermissionDeliveryState.HELD_MANUAL_PAUSE);
        assertThat(coordinator.deliverPending(approvalId)).isEmpty();

        coordinator.manualResume(RUN_ID);
        Optional<PermissionReply> delivered = coordinator.deliverPending(approvalId);

        assertThat(delivered).contains(new PermissionReply(RUN_ID, SESSION_ID, REQUEST_ID, "proceed_once"));
        assertThat(permissionRow(approvalId).getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(permissionRow(approvalId).getDeliveredAt()).isEqualTo(T0);
        assertThat(coordinator.deliverPending(approvalId)).isEmpty();
    }

    @Test
    void aPlatformGrantWhilePausedIsIssuedOnlyOnResumeAndNeverReplayedAfterExpiry() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.PLATFORM_MCP));
        String digest = WriteGrantService.digestOf("{\"path\":\"notes.txt\",\"content\":\"hello\"}");
        coordinator.manualPause(RUN_ID);
        coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        // Held: the call is not authorized while the run is paused.
        assertThat(writeGrants.consume(RUN_ID, TOOL, digest)).isEqualTo(false);

        // Resumed after the ask expired: the decision stands, the delivery is expired, nothing replays.
        clock.advanceTo(EXPIRES_AT.plusSeconds(1));
        coordinator.manualResume(RUN_ID);
        assertThat(coordinator.deliverPending(approvalId)).isEmpty();
        assertThat(permissionRow(approvalId).getDeliveryState()).isEqualTo(PermissionDeliveryState.EXPIRED.name());
        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(writeGrants.consume(RUN_ID, TOOL, digest)).isEqualTo(false);
    }

    @Test
    void aManualPauseOfOneRunDoesNotHoldAnotherRunsDelivery() {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.NATIVE_TOOL));
        coordinator.manualPause(OTHER_RUN);

        Optional<PermissionReply> reply = coordinator.decide(
                approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        assertThat(reply).contains(new PermissionReply(RUN_ID, SESSION_ID, REQUEST_ID, "proceed_once"));
        assertThat(permissionRow(approvalId).getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
    }

    @Test
    void deliveryStateOfAnUnknownApprovalIsRejected() {
        coordinator = coordinator(T0);
        UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000399");

        assertThatThrownBy(() -> coordinator.deliveryState(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No native permission request is correlated with approval " + unknown);
        assertThatThrownBy(() -> coordinator.deliverPending(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No native permission request is correlated with approval " + unknown);
    }

    // ------------------------------------------------------------------
    // concurrent decisions (fix round 1)
    // ------------------------------------------------------------------

    /**
     * Two overlapping operator decisions on one PLATFORM_MCP ask (double-click,
     * client retry) must yield exactly one usable authorization. The first
     * decider to arrive settles and delivers its grant; the second is held until
     * that grant has been consumed and then must observe the settled approval
     * and refuse — it must never re-enter the gate and re-arm {@code consumedAt}.
     * The race is orchestrated through the decision reads (deterministic, no
     * sleeps), in the idiom of {@link ApprovalGateConcurrencyTest}.
     */
    @Test
    void concurrentDecisionsOnOnePlatformAskAuthorizeExactlyOneUse() throws Exception {
        coordinator = coordinator(T0);
        UUID approvalId = coordinator.register(permission(PermissionTarget.PLATFORM_MCP));
        String digest = WriteGrantService.digestOf("{\"path\":\"notes.txt\",\"content\":\"hello\"}");

        when(approvalRepository.findById(approvalId))
                .thenAnswer(inv -> orchestratedDecisionRead(approvalId));
        when(decisionLocks.findByIdForDecision(approvalId))
                .thenAnswer(inv -> orchestratedDecisionRead(approvalId));

        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger settledRefusals = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        Runnable decideOnce = () -> {
            try {
                go.await();
                coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));
            } catch (IllegalStateException e) {
                settledRefusals.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
        };
        Thread firstDecider = Thread.ofVirtual().name("decider-one").start(decideOnce);
        Thread secondDecider = Thread.ofVirtual().name("decider-two").start(decideOnce);
        go.countDown();

        // The winner delivers its one-use authorization...
        await().atMost(Duration.ofSeconds(10)).until(() -> permissionStore.values().stream()
                .anyMatch(row -> row.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT));
        // ...the platform boundary consumes it exactly once...
        assertThat(writeGrants.consume(RUN_ID, TOOL, digest)).isTrue();
        // ...and only now may the losing decision proceed.
        releaseRetryDecision.countDown();

        firstDecider.join(10_000);
        secondDecider.join(10_000);
        assertThat(firstDecider.isAlive()).isFalse();
        assertThat(secondDecider.isAlive()).isFalse();
        assertThat(unexpected).isEmpty();

        // Exactly one authorization exists, its single use stays consumed and no second use is armed.
        List<AcpPermissionRequest> grants = permissionStore.values().stream()
                .filter(row -> row.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT)
                .toList();
        assertThat(grants).hasSize(1);
        assertThat(writeGrants.consume(RUN_ID, TOOL, digest)).isFalse();
        assertThat(grants.get(0).getConsumedAt()).isNotNull();
        // The losing decision observed the settled row and refused; the ask keeps the winner's verdict.
        assertThat(settledRefusals.get()).isEqualTo(1);
        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /**
     * Orchestrates the decide-vs-decide race through the decision reads. The
     * first decider becomes the winner and waits until the retry has read the
     * still-PENDING approval; the winner's gate read then waits until the retry
     * has passed its own settled pre-check, and the retry's gate read is held
     * until the test has consumed the winner's grant. Every wait is bounded, so
     * a decision path that serializes the two deciders simply times the unused
     * gates out and the retry then reads the settled row.
     */
    private Optional<Approval> orchestratedDecisionRead(UUID approvalId) throws InterruptedException {
        String decider = Thread.currentThread().getName();
        String role = deciderRoles.get(decider);
        if (role == null) {
            String assigned = winningDeciderTaken.compareAndSet(false, true) ? "winner" : "retry";
            String previous = deciderRoles.putIfAbsent(decider, assigned);
            role = previous != null ? previous : assigned;
        }
        int read = deciderReads.computeIfAbsent(decider, k -> new AtomicInteger()).incrementAndGet();
        if (read == 1) {
            if ("winner".equals(role)) {
                retryReadPending.await(2, TimeUnit.SECONDS);
            } else {
                retryReadPending.countDown();
            }
        } else if (read == 2) {
            if ("winner".equals(role)) {
                retryAtGate.await(2, TimeUnit.SECONDS);
            } else {
                retryAtGate.countDown();
                releaseRetryDecision.await(10, TimeUnit.SECONDS);
            }
        }
        return Optional.ofNullable(approvalStore.get(approvalId));
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, CompletableFuture<ApprovalDecision>> pendingApprovals(ApprovalGate gate) {
        try {
            Field field = ApprovalGate.class.getDeclaredField("pendingApprovals");
            field.setAccessible(true);
            return (Map<UUID, CompletableFuture<ApprovalDecision>>) field.get(gate);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("pendingApprovals field not found on ApprovalGate", e);
        }
    }

    private AcpPermissionRequest permissionRow(UUID approvalId) {
        return permissionStore.values().stream()
                .filter(row -> approvalId.equals(row.getApprovalId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ledger row for approval " + approvalId));
    }
}
