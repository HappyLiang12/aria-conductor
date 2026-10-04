package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.mcp.PlatformMcpAutoApproval;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        lenient().when(permissionRepository.findByRunId(any(UUID.class))).thenAnswer(inv -> {
            UUID runId = inv.getArgument(0);
            return permissionStore.values().stream()
                    .filter(row -> runId.equals(row.getRunId()))
                    .toList();
        });
        lenient().when(permissionRepository.findByDeliveryStateAndExpiresAtBefore(
                        any(String.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    String deliveryState = inv.getArgument(0);
                    Instant cutoff = inv.getArgument(1);
                    return permissionStore.values().stream()
                            .filter(row -> deliveryState.equals(row.getDeliveryState()))
                            .filter(row -> row.getExpiresAt().isBefore(cutoff))
                            .toList();
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
        return coordinator(now, sink, new McpProperties());
    }

    /** A coordinator whose read-only platform-tool policy is configured by {@code mcp}. */
    private PermissionCoordinator coordinator(Instant now, PermissionReplySink sink, McpProperties mcp) {
        this.clock = new MutableClock(now);
        this.gate = new ApprovalGate(approvalRepository, toolCallRepository, eventPublisher, 30_000L);
        this.writeGrants = new WriteGrantService(permissionRepository, clock);
        return new PermissionCoordinator(gate, approvalRepository, decisionLocks, permissionRepository,
                writeGrants, new PlatformMcpAutoApproval(mcp), eventPublisher, () -> sink, clock);
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
     * A native ask must carry its provenance: the persisted approval is stamped
     * {@code ACP_PERMISSION}, so the run-end sweep and the legacy card sweeps
     * leave it to its own coordinator instead of treating it as a legacy gate
     * row and rewriting it.
     */
    @Test
    void registerStampsNativeAsksWithTheAcpPermissionSource() {
        coordinator = coordinator(T0);

        coordinator.register(permission(PermissionTarget.NATIVE_TOOL));

        ArgumentCaptor<Approval> saved = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(ApprovalSource.ACP_PERMISSION);
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
    // read-only platform-tool auto-approval (operator decision 2026-09-29)
    // ------------------------------------------------------------------

    /**
     * An allowlisted read-only platform tool settles at registration: the
     * approval is recorded APPROVED naming the policy, the one-use grant is
     * written through the same delivery path a manual ALLOW_ONCE decision uses,
     * and no operator-facing ask (no event, no card, no wait) is raised.
     */
    @Test
    void anAllowlistedPlatformAskIsAutoApprovedWithoutAnOperatorCard() {
        coordinator = coordinator(T0);
        String tool = "mcp__aria-conductor__list_agents";
        String arguments = "{\"limit\":10}";

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                tool, PermissionTarget.PLATFORM_MCP, arguments, OFFERED, EXPIRES_AT));

        Approval approval = approvalStore.get(approvalId);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getReason()).isEqualTo(PermissionCoordinator.AUTO_APPROVE_REASON);
        assertThat(approval.getDecidedAt()).isEqualTo(T0);

        // The correlation row is persisted, exactly as for any other ask ...
        AcpPermissionRequest row = permissionRow(approvalId);
        assertThat(row.getKind()).isEqualTo(AcpPermissionRequest.Kind.NATIVE_PERMISSION);
        assertThat(row.getToolName()).isEqualTo(tool);
        assertThat(row.getTarget()).isEqualTo(PermissionTarget.PLATFORM_MCP.name());
        assertThat(row.getDecidedAt()).isEqualTo(T0);
        assertThat(row.getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(row.getDeliveredAt()).isEqualTo(T0);

        // ... and no operator ask is surfaced: no card, no notification, no wait.
        assertThat(requestedEvents).isEmpty();

        // Exactly one one-use grant, bound to this exact call.
        List<AcpPermissionRequest> grants = permissionStore.values().stream()
                .filter(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT)
                .toList();
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).getToolName()).isEqualTo(tool);
        String digest = WriteGrantService.digestOf(arguments);
        assertThat(writeGrants.consume(RUN_ID, tool, digest)).isTrue();
        assertThat(writeGrants.consume(RUN_ID, tool, digest)).isFalse();
        assertThat(writeGrants.consume(OTHER_RUN, tool, digest)).isFalse();
    }

    // --- the live shape: the core reports a platform MCP call as a native ask ---

    /**
     * The live shape of a platform MCP call: the core reports it as a native
     * permission request ({@code NATIVE_TOOL}) whose raw tool name carries the
     * platform-MCP prefix. The policy settles it exactly like a manual
     * ALLOW_ONCE decision, so the owning session receives the reply and the run
     * proceeds — no operator card, no pending ask, no platform grant.
     */
    @Test
    void anAllowlistedLivePlatformAskIsAutoApprovedAndAnsweredToTheOwningSession() {
        List<PermissionReply> delivered = new ArrayList<>();
        coordinator = coordinator(T0, delivered::add);
        String tool = "mcp__aria-conductor__list_agents";
        String arguments = "{\"limit\":10}";

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                tool, PermissionTarget.NATIVE_TOOL, arguments, OFFERED, EXPIRES_AT));

        Approval approval = approvalStore.get(approvalId);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getReason()).isEqualTo(PermissionCoordinator.AUTO_APPROVE_REASON);
        assertThat(approval.getDecidedAt()).isEqualTo(T0);

        // The correlation row records the decision and its delivery, exactly as
        // a manual ALLOW_ONCE decision does: nothing stays pending.
        AcpPermissionRequest row = permissionRow(approvalId);
        assertThat(row.getToolName()).isEqualTo(tool);
        assertThat(row.getTarget()).isEqualTo(PermissionTarget.NATIVE_TOOL.name());
        assertThat(row.getSelectedOptionId()).isEqualTo("proceed_once");
        assertThat(row.getDecidedAt()).isEqualTo(T0);
        assertThat(row.getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(row.getDeliveredAt()).isEqualTo(T0);
        assertThat(coordinator.deliveryState(approvalId)).isEqualTo(PermissionDeliveryState.DELIVERED);
        assertThat(requestedEvents).isEmpty();

        // The owning session received exactly one ALLOW_ONCE reply, naming the
        // option the core offered — and the same reply is never handed over a
        // second time.
        assertThat(delivered).containsExactly(
                new PermissionReply(RUN_ID, SESSION_ID, REQUEST_ID, "proceed_once"));
        assertThat(coordinator.deliverPending(approvalId)).isEmpty();
        assertThat(delivered).hasSize(1);

        // A native reply answered the CLI directly; no platform grant was issued.
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
        assertThat(writeGrants.consume(RUN_ID, tool, WriteGrantService.digestOf(arguments))).isFalse();
    }

    /**
     * A mutating platform tool is never auto-approved, even in the live native
     * shape: the ask keeps the operator card and stays pending until decided.
     */
    @Test
    void aMutatingLivePlatformAskStillSurfacesToTheOperator() {
        List<PermissionReply> delivered = new ArrayList<>();
        coordinator = coordinator(T0, delivered::add);
        String tool = "mcp__aria-conductor__create_agent";

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                tool, PermissionTarget.NATIVE_TOOL, "{\"name\":\"scout\"}", OFFERED, EXPIRES_AT));

        Approval approval = approvalStore.get(approvalId);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approval.getReason()).isEqualTo(
                "Native permission request 0 from session ses_fixture_1 for tool " + tool + " (NATIVE_TOOL)");
        AcpPermissionRequest row = permissionRow(approvalId);
        assertThat(row.getDeliveryState()).isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
        assertThat(row.getSelectedOptionId()).isNull();
        assertThat(requestedEvents).hasSize(1);
        assertThat(requestedEvents.get(0).getApprovalId()).isEqualTo(approvalId);
        assertThat(delivered).isEmpty();
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
    }

    /**
     * The exact boundary spec §6.3 protects: the core's own tools ask as native
     * permission requests without the platform-MCP prefix, so they are never
     * auto-approved — not even while the policy is enabled.
     */
    @Test
    void aWebSearchNativeAskIsNeverCoveredEvenWithThePolicyEnabled() {
        List<PermissionReply> delivered = new ArrayList<>();
        coordinator = coordinator(T0, delivered::add);

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                "WebSearch", PermissionTarget.NATIVE_TOOL, "{\"query\":\"aria\"}", OFFERED, EXPIRES_AT));

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(permissionRow(approvalId).getDeliveryState())
                .isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
        assertThat(permissionRow(approvalId).getSelectedOptionId()).isNull();
        assertThat(requestedEvents).hasSize(1);
        assertThat(delivered).isEmpty();
    }

    /**
     * The {@link PermissionReplySink} is resolved at decision time, not at
     * construction (the production seam that breaks the run coordinator's
     * construction cycle): a coordinator built before its sink can still hand
     * the auto-approved reply to the owning session once the sink resolves.
     */
    @Test
    void anAutoApprovedNativeAskReachesASinkThatOnlyResolvesLater() {
        List<PermissionReply> delivered = new ArrayList<>();
        AtomicReference<PermissionReplySink> sink = new AtomicReference<>();
        this.clock = new MutableClock(T0);
        this.gate = new ApprovalGate(approvalRepository, toolCallRepository, eventPublisher, 30_000L);
        this.writeGrants = new WriteGrantService(permissionRepository, clock);
        PermissionCoordinator coordinator = new PermissionCoordinator(gate, approvalRepository,
                decisionLocks, permissionRepository, writeGrants, new PlatformMcpAutoApproval(new McpProperties()),
                eventPublisher, sink::get, clock);
        // The owning session — and with it the sink — only resolves after the
        // coordinator was constructed.
        sink.set(delivered::add);

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                "mcp__aria-conductor__list_agents", PermissionTarget.NATIVE_TOOL, "{}", OFFERED, EXPIRES_AT));

        assertThat(delivered).containsExactly(
                new PermissionReply(RUN_ID, SESSION_ID, REQUEST_ID, "proceed_once"));
        assertThat(permissionRow(approvalId).getDeliveryState())
                .isEqualTo(PermissionDeliveryState.DELIVERED.name());
    }

    /** A platform tool whose name is not on the allowlist keeps today's operator ask. */
    @Test
    void aNonAllowlistedPlatformAskStillSurfacesToTheOperator() {
        coordinator = coordinator(T0);

        UUID approvalId = coordinator.register(permission(PermissionTarget.PLATFORM_MCP));

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(permissionRow(approvalId).getDeliveryState())
                .isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
        assertThat(requestedEvents).hasSize(1);
        assertThat(requestedEvents.get(0).getApprovalId()).isEqualTo(approvalId);
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
    }

    /**
     * The policy is a platform-ask policy, not a tool-name shortcut: a
     * {@code NATIVE_TOOL} ask keeps the operator flow even when its tool name is
     * on the read-only list.
     */
    @Test
    void anAllowlistedToolNameOfANativeAskKeepsTheOperatorFlow() {
        coordinator = coordinator(T0);

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                "list_agents", PermissionTarget.NATIVE_TOOL, "{}", OFFERED, EXPIRES_AT));

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(permissionRow(approvalId).getDeliveryState())
                .isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
        assertThat(requestedEvents).hasSize(1);
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
    }

    /** An empty configured list disables the policy: the ask keeps today's operator flow. */
    @Test
    void anEmptyConfiguredAllowlistDisablesThePolicyAndKeepsTheAskOperatorFacing() {
        McpProperties disabled = new McpProperties();
        disabled.setAutoApproveReadTools(List.of());
        coordinator = coordinator(T0, null, disabled);

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                "mcp__aria-conductor__list_agents", PermissionTarget.PLATFORM_MCP, "{}", OFFERED, EXPIRES_AT));

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(requestedEvents).hasSize(1);
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
    }

    /**
     * The policy never approves past the ask's own window (spec §5.3): an
     * allowlisted ask whose window already closed is not auto-approved and keeps
     * the operator flow, where it settles as expired like any other late ask.
     */
    @Test
    void anAllowlistedPlatformAskPastItsWindowIsNotAutoApproved() {
        coordinator = coordinator(T0.plus(Duration.ofMinutes(6)));

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                "list_agents", PermissionTarget.PLATFORM_MCP, "{}", OFFERED, EXPIRES_AT));

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(requestedEvents).hasSize(1);
        assertThat(permissionStore.values())
                .noneMatch(r -> r.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT);
    }

    /**
     * A manual pause holds the auto-approved grant like any other decided ask:
     * the one-use authorization is armed only when the resume owner delivers it.
     */
    @Test
    void anAutoApprovedGrantOfAManuallyPausedRunIsHeldUntilResume() {
        coordinator = coordinator(T0);
        String tool = "mcp__aria-conductor__list_agents";
        String digest = WriteGrantService.digestOf("{}");
        coordinator.manualPause(RUN_ID);

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                tool, PermissionTarget.PLATFORM_MCP, "{}", OFFERED, EXPIRES_AT));

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(permissionRow(approvalId).getDeliveryState())
                .isEqualTo(PermissionDeliveryState.HELD_MANUAL_PAUSE.name());
        assertThat(requestedEvents).isEmpty();
        assertThat(writeGrants.consume(RUN_ID, tool, digest)).isEqualTo(false);

        coordinator.manualResume(RUN_ID);
        assertThat(coordinator.deliverPending(approvalId)).isEmpty();
        assertThat(permissionRow(approvalId).getDeliveryState())
                .isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(writeGrants.consume(RUN_ID, tool, digest)).isEqualTo(true);
    }

    /**
     * The pause-hold semantics reach the live native shape too: the
     * auto-approved reply is held until the resume owner delivers it to the
     * session, and this coordinator never hands the same reply over a second
     * time.
     */
    @Test
    void anAutoApprovedNativeAskOfAManuallyPausedRunIsHeldUntilResume() {
        List<PermissionReply> delivered = new ArrayList<>();
        coordinator = coordinator(T0, delivered::add);
        coordinator.manualPause(RUN_ID);

        UUID approvalId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID,
                "mcp__aria-conductor__list_agents", PermissionTarget.NATIVE_TOOL, "{}", OFFERED, EXPIRES_AT));

        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(permissionRow(approvalId).getDeliveryState())
                .isEqualTo(PermissionDeliveryState.HELD_MANUAL_PAUSE.name());
        assertThat(requestedEvents).isEmpty();
        assertThat(delivered).isEmpty();

        // The resume owner hands the released reply to the session itself; this
        // coordinator must not hand the same reply over a second time.
        coordinator.manualResume(RUN_ID);
        assertThat(coordinator.deliverPending(approvalId)).contains(
                new PermissionReply(RUN_ID, SESSION_ID, REQUEST_ID, "proceed_once"));
        assertThat(permissionRow(approvalId).getDeliveryState())
                .isEqualTo(PermissionDeliveryState.DELIVERED.name());
        assertThat(delivered).isEmpty();
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

    /**
     * The run deadline is the ask's own expiry ({@code register} freezes
     * {@code expiresAt = spec.deadline()}), so the deadline enforcement settles
     * every still-PENDING ask of the run by its own window: the recorded expiry
     * reason, settled exactly at the deadline instant, the blocked waiter
     * released like the sweep. An ask still inside its window, another run's ask
     * and an already-decided ask are all left exactly as they are.
     */
    @Test
    void theDeadlineExpiresTheRunsPendingAsksAtTheirOwnWindow() {
        coordinator = coordinator(T0);
        String arguments = "{\"path\":\"notes.txt\",\"content\":\"hello\"}";
        UUID due = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, "0", TOOL,
                PermissionTarget.NATIVE_TOOL, arguments, OFFERED, EXPIRES_AT));
        UUID insideItsWindow = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, "1", TOOL,
                PermissionTarget.NATIVE_TOOL, arguments, OFFERED, EXPIRES_AT.plusSeconds(60)));
        UUID otherRun = coordinator.register(new NativePermission(OTHER_RUN, SESSION_ID, "0", TOOL,
                PermissionTarget.NATIVE_TOOL, arguments, OFFERED, EXPIRES_AT));
        UUID settled = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, "2", TOOL,
                PermissionTarget.NATIVE_TOOL, arguments, OFFERED, EXPIRES_AT));
        coordinator.decide(settled, PermissionChoice.DENY, ActorPrincipal.operator(null));
        CompletableFuture<ApprovalDecision> waiter = new CompletableFuture<>();
        pendingApprovals(gate).put(due, waiter);

        int settledCount = coordinator.expirePendingForRun(RUN_ID, EXPIRES_AT);

        assertThat(settledCount).isEqualTo(1);
        assertThat(approvalStore.get(due).getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(approvalStore.get(due).getReason()).isEqualTo("Auto-rejected: approval expired");
        assertThat(approvalStore.get(due).getDecidedAt()).isEqualTo(EXPIRES_AT);
        assertThat(permissionRow(due).getDeliveryState()).isEqualTo(PermissionDeliveryState.EXPIRED.name());
        assertThat(waiter).isDone();
        assertThat(waiter.getNow(null).isApproved()).isFalse();
        assertThat(waiter.getNow(null).reason()).isEqualTo("Run cancelled");
        assertThat(approvalStore.get(insideItsWindow).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(permissionRow(insideItsWindow).getDeliveryState())
                .isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
        assertThat(approvalStore.get(otherRun).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approvalStore.get(settled).getStatus()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(approvalStore.get(settled).getReason())
                .isEqualTo("Operator denied write_file (native permission request 2)");
    }

    /**
     * The run-end settle (spec §5.3): every still-PENDING native ask of a run
     * whose runtime has ended is adjudicated — EXPIRED with the recorded reason
     * "run ended", the waiter released and the operator told via the expired
     * event carrying the ask's tool name — while an already-settled ask is never
     * rewritten.
     */
    @Test
    void cancelPendingForRunSettlesOnlyPendingAsksWithTheRunEndedReason() {
        coordinator = coordinator(T0);
        UUID pendingId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, "0", "run_agent",
                PermissionTarget.NATIVE_TOOL, "{}", OFFERED, EXPIRES_AT));
        UUID settledId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, "1", "WebSearch",
                PermissionTarget.NATIVE_TOOL, "{}", OFFERED, EXPIRES_AT));
        coordinator.decide(settledId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));

        int settledCount = coordinator.cancelPendingForRun(RUN_ID);

        assertThat(settledCount).isEqualTo(1);
        assertThat(approvalStore.get(pendingId).getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(approvalStore.get(pendingId).getReason()).isEqualTo("run ended");
        assertThat(approvalStore.get(settledId).getStatus()).isEqualTo(ApprovalStatus.APPROVED); // untouched
        ArgumentCaptor<ApprovalExpiredEvent> event = ArgumentCaptor.forClass(ApprovalExpiredEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getToolName()).isEqualTo("run_agent");
    }

    /**
     * Every expiry announces the ask: the settled-by-timeout event carries the
     * ask's tool name, so the operator learns what was skipped.
     */
    @Test
    void expiryPublishesTheExpiredEventWithTheToolName() {
        coordinator = coordinator(T0);
        coordinator.register(new NativePermission(RUN_ID, SESSION_ID, REQUEST_ID, "run_agent",
                PermissionTarget.NATIVE_TOOL, "{}", OFFERED, EXPIRES_AT));

        coordinator.expirePendingForRun(RUN_ID, EXPIRES_AT.plusSeconds(1));

        ArgumentCaptor<ApprovalExpiredEvent> event = ArgumentCaptor.forClass(ApprovalExpiredEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getToolName()).isEqualTo("run_agent");
    }

    /**
     * The scheduled expiry backstop (R-RFUX2): a native ask still
     * {@code AWAITING_DECISION} whose own window has closed is settled by the
     * coordinator sweep — EXPIRED with the recorded expiry reason, the ledger
     * delivery moved to EXPIRED, and {@link ApprovalExpiredEvent} published with
     * the ask's tool name — so the scheduled sweep leaves nothing silently
     * unsettled. An already-decided ask is never rewritten.
     */
    @Test
    void expireOverdueNativeAsksSettlesAwaitingAsksWithTheExpiryReasonAndAnnouncesTheToolName() {
        coordinator = coordinator(T0);
        UUID pendingId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, "0", "run_agent",
                PermissionTarget.NATIVE_TOOL, "{}", OFFERED, EXPIRES_AT));
        UUID settledId = coordinator.register(new NativePermission(RUN_ID, SESSION_ID, "1", "WebSearch",
                PermissionTarget.NATIVE_TOOL, "{}", OFFERED, EXPIRES_AT));
        coordinator.decide(settledId, PermissionChoice.ALLOW_ONCE, ActorPrincipal.operator(null));
        Instant asOf = EXPIRES_AT.plusSeconds(1);

        int settledCount = coordinator.expireOverdueNativeAsks(asOf);

        assertThat(settledCount).isEqualTo(1);
        assertThat(approvalStore.get(pendingId).getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(approvalStore.get(pendingId).getReason()).isEqualTo(PermissionCoordinator.EXPIRY_REASON);
        assertThat(approvalStore.get(pendingId).getDecidedAt()).isEqualTo(asOf);
        assertThat(permissionRow(pendingId).getDeliveryState()).isEqualTo(PermissionDeliveryState.EXPIRED.name());
        // The settled ask keeps its decision: the sweep never rewrites it.
        assertThat(approvalStore.get(settledId).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approvalStore.get(settledId).getReason())
                .isEqualTo("Operator allowed one use of WebSearch (native permission request 1)");
        assertThat(permissionRow(settledId).getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
        ArgumentCaptor<ApprovalExpiredEvent> event = ArgumentCaptor.forClass(ApprovalExpiredEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getToolName()).isEqualTo("run_agent");
    }

    /**
     * The sweep's own settled-skip guard, independent of the finder's
     * delivery-state filter: a row whose approval is missing or no longer
     * PENDING is never rewritten, however the finder handed it back — a settled
     * ask stays the historical fact and no expiry is announced for it.
     */
    @Test
    void expireOverdueNativeAsksNeverRewritesASettledOrOrphanedRow() {
        coordinator = coordinator(T0);
        Approval settled = Approval.builder()
                .runId(RUN_ID).source(ApprovalSource.ACP_PERMISSION)
                .status(ApprovalStatus.APPROVED)
                .reason("Operator allowed one use of run_agent")
                .decidedAt(T0).expiresAt(EXPIRES_AT).build();
        approvalRepository.save(settled);
        AcpPermissionRequest staleAwaiting = AcpPermissionRequest.builder()
                .id(UUID.randomUUID()).approvalId(settled.getId())
                .kind(AcpPermissionRequest.Kind.NATIVE_PERMISSION)
                .runId(RUN_ID).sessionId(SESSION_ID).requestId("0").toolName("run_agent")
                .target(PermissionTarget.NATIVE_TOOL.name())
                .argumentsDigest(WriteGrantService.digestOf("{}"))
                .deliveryState(PermissionDeliveryState.AWAITING_DECISION.name())
                .expiresAt(EXPIRES_AT.minusSeconds(30)).createdAt(T0).build();
        AcpPermissionRequest orphaned = AcpPermissionRequest.builder()
                .id(UUID.randomUUID()).approvalId(UUID.randomUUID())
                .kind(AcpPermissionRequest.Kind.NATIVE_PERMISSION)
                .runId(RUN_ID).sessionId(SESSION_ID).requestId("1").toolName("WebSearch")
                .target(PermissionTarget.NATIVE_TOOL.name())
                .argumentsDigest(WriteGrantService.digestOf("{}"))
                .deliveryState(PermissionDeliveryState.AWAITING_DECISION.name())
                .expiresAt(EXPIRES_AT.minusSeconds(30)).createdAt(T0).build();
        permissionRepository.save(staleAwaiting);
        permissionRepository.save(orphaned);

        int settledCount = coordinator.expireOverdueNativeAsks(EXPIRES_AT.plusSeconds(1));

        assertThat(settledCount).isEqualTo(0);
        assertThat(settled.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(staleAwaiting.getDeliveryState()).isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
        assertThat(orphaned.getDeliveryState()).isEqualTo(PermissionDeliveryState.AWAITING_DECISION.name());
        verify(eventPublisher, never()).publishEvent(any(ApprovalExpiredEvent.class));
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
