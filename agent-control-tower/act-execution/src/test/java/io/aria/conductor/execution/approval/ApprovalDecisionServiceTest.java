package io.aria.conductor.execution.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.adk.qoder.QoderBridgeClient;
import io.aria.conductor.execution.adk.qoder.QoderBridgeException;
import io.aria.conductor.execution.mcp.ToolPolicyRegistry;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.test.DataJpaTestBase;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Decision-dispatch tests for {@link ApprovalDecisionService} on a real H2 slice: real
 * repositories, a real {@link AcpPermissionCoordinator} with a mocked bridge client and a fake
 * clock, a real {@link WriteGrantService} and a mocked {@link ApprovalGate} for the legacy path.
 *
 * <p>Fixtures are committed explicitly (the service and the coordinator write through their own
 * transactions), and {@link #flushAndClear()} drops the test persistence context before
 * assertions so they read the committed state instead of stale instances.
 */
class ApprovalDecisionServiceTest extends DataJpaTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MS = 1_800_000L;
    private static final String SESSION_ID = "bridge-session-decide";
    /** Far in the past: an ask created with a short timeout is already overdue for the real clock. */
    private static final Instant FAKE_NOW = Instant.parse("2025-01-01T00:00:00Z");

    @Autowired ApprovalRepository approvalRepository;
    @Autowired AcpPermissionRequestRepository companionRepository;
    @Autowired RunRepository runRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private final QoderBridgeClient client = mock(QoderBridgeClient.class);
    private final ApprovalGate approvalGate = mock(ApprovalGate.class);
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock(FAKE_NOW);

    private AcpPermissionCoordinator coordinator;
    private WriteGrantService writeGrants;
    private ApprovalDecisionService service;

    @BeforeEach
    void setUp() {
        coordinator = new AcpPermissionCoordinator(approvalRepository, companionRepository, runRepository,
                events::add, transactionManager, new ToolPolicyRegistry(), TIMEOUT_MS, clock);
        writeGrants = new WriteGrantService(clock);
        service = new ApprovalDecisionService(approvalRepository, companionRepository, approvalGate,
                coordinator, writeGrants, events::add, transactionManager, clock);
    }

    @AfterEach
    void cleanupCommittedRows() {
        commit(() -> {
            companionRepository.deleteAllInBatch();
            approvalRepository.deleteAllInBatch();
            runRepository.deleteAllInBatch();
        });
    }

    // ---- fixtures and helpers -------------------------------------------------

    /** Commit work in its own transaction: the service never reads uncommitted fixtures. */
    private void commit(Runnable work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> work.run());
    }

    private UUID committedRun(RunStatus status) {
        UUID runId = UUID.randomUUID();
        commit(() -> runRepository.save(TestDataBuilder.aRun().withId(runId).withStatus(status).build()));
        return runId;
    }

    /**
     * Creates one live ACP ask through the coordinator's real intake path and returns its
     * approval id. Events raised by the creation are dropped so decision events can be counted.
     */
    private UUID newAsk(String requestId, String rawInput, String... optionKinds) {
        UUID runId = committedRun(RunStatus.RUNNING);
        coordinator.bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                frame(requestId, rawInput, optionKinds));
        flushAndClear();
        UUID approvalId = companionRepository
                .findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, requestId).orElseThrow()
                .getApprovalId();
        events.clear();
        return approvalId;
    }

    private ObjectNode frame(String requestId, String rawInput, String... optionKinds) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("requestId", requestId);
        node.put("toolCallId", "call_" + requestId);
        node.put("toolName", "mcp__aria__write_file");
        node.put("rawInput", rawInput);
        node.put("rawInputTruncated", false);
        ArrayNode options = node.putArray("options");
        for (String kind : optionKinds) {
            options.addObject()
                    .put("optionId", "opt-" + kind)
                    .put("kind", kind)
                    .put("name", "Option " + kind);
        }
        return node;
    }

    private static String json(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AcpPermissionRequest companion(UUID approvalId) {
        return companionRepository.findById(approvalId).orElseThrow();
    }

    private List<ApprovalDecidedEvent> decidedEvents() {
        return events.stream().filter(ApprovalDecidedEvent.class::isInstance)
                .map(ApprovalDecidedEvent.class::cast).toList();
    }

    private static final String WRITE_FILE = "mcp__aria__write_file";
    private static final String WRITE_FILE_RUNTIME = "write_file";
    private static final String RAWS_ARGS_DIGEST = WriteGrantService.effectiveArgsDigest(
            Map.of("path", "/workspace/x"));
    private static final String RAW_INPUT_X = json(Map.of("path", "/workspace/x"));

    /** The plain MCP ask used by most tests: write_file with both selectable kinds. */
    private UUID standardAsk(String requestId) {
        return newAsk(requestId, RAW_INPUT_X, "allow_once", "reject_once");
    }

    // ---- legacy dispatch (R19, R24.1) ----------------------------------------

    @Test
    void legacyDecision_delegatesToTheGateWithNullAcpFields() {
        UUID runId = committedRun(RunStatus.RUNNING);
        UUID approvalId = UUID.randomUUID();
        commit(() -> approvalRepository.saveAndFlush(TestDataBuilder.anApproval()
                .withId(approvalId).withRunId(runId).withStatus(ApprovalStatus.PENDING).build()));

        ApprovalDecisionService.Result result = service.decide(approvalId, true, "looks safe");

        verify(approvalGate).decideApproval(approvalId, true, "looks safe");
        assertThat(result.approvalId()).isEqualTo(approvalId);
        assertThat(result.approved()).isTrue();
        assertThat(result.decision()).isNull();
        assertThat(result.deliveryState()).isNull();
        assertThat(decidedEvents()).isEmpty();
        verify(client, never()).decide(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void unknownApprovalId_isAnIllegalArgumentAndNeverReachesTheGate() {
        UUID approvalId = UUID.randomUUID();

        assertThatThrownBy(() -> service.decide(approvalId, true, "n/a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Approval not found: " + approvalId);
        verify(approvalGate, never()).decideApproval(any(), anyBoolean(), anyString());
    }

    // ---- ACP winner (R21, R24.2) ---------------------------------------------

    @Test
    void approve_winner_transitionsRecordsOptionIssuesOneGrantDeliversAndPublishes() {
        UUID approvalId = standardAsk("req-approve");
        when(client.decide(SESSION_ID, "req-approve", true, "operator approved"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        UUID runId = companion(approvalId).getRunId();

        ApprovalDecisionService.Result result = service.decide(approvalId, true, "operator approved");

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.deliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        assertThat(result.approved()).isTrue();
        flushAndClear();
        Approval stored = approvalRepository.findById(approvalId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(stored.getReason()).isEqualTo("operator approved");
        assertThat(stored.getDecidedAt()).isEqualTo(FAKE_NOW);
        AcpPermissionRequest row = companion(approvalId);
        assertThat(row.getSelectedOptionId()).isEqualTo("opt-allow_once");
        assertThat(row.getDeliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        // Exactly one bridge delivery and exactly one write grant for the approved triple.
        verify(client, times(1)).decide(SESSION_ID, "req-approve", true, "operator approved");
        assertThat(writeGrants.consume(runId, WRITE_FILE_RUNTIME, RAWS_ARGS_DIGEST)).isTrue();
        assertThat(writeGrants.consume(runId, WRITE_FILE_RUNTIME, RAWS_ARGS_DIGEST)).isFalse();
        // Exactly one ApprovalDecidedEvent, the two-arg TOOL_CALL shape of today.
        assertThat(decidedEvents()).singleElement().satisfies(event -> {
            assertThat(event.getApprovalId()).isEqualTo(approvalId);
            assertThat(event.getDecision()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(event.getApprovalType()).isEqualTo("TOOL_CALL");
        });
        // Nothing workflow-ish rides along: the decision event is the only one published.
        assertThat(events).singleElement().isInstanceOf(ApprovalDecidedEvent.class);
    }

    @Test
    void deny_withoutRejectOnce_recordsNoOptionAndDeliversTheCancellation() {
        UUID approvalId = newAsk("req-deny", RAW_INPUT_X, "allow_once");
        when(client.decide(SESSION_ID, "req-deny", false, "operator denied"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        UUID runId = companion(approvalId).getRunId();

        ApprovalDecisionService.Result result = service.decide(approvalId, false, "operator denied");

        assertThat(result.decision()).isEqualTo("DENIED");
        assertThat(result.deliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        flushAndClear();
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.DENIED);
        // A missing reject_once becomes the bridge-side cancellation response: no option invented.
        assertThat(companion(approvalId).getSelectedOptionId()).isNull();
        assertThat(companion(approvalId).getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        verify(client, times(1)).decide(SESSION_ID, "req-deny", false, "operator denied");
        // Denial leaves no reusable grant behind.
        assertThat(writeGrants.trackedKeyCount()).isZero();
        assertThat(writeGrants.consume(runId, WRITE_FILE_RUNTIME, RAWS_ARGS_DIGEST)).isFalse();
    }

    // ---- rejections (R21.1, R22, R24.3) --------------------------------------

    @Test
    void approve_withoutAllowOnce_isRejectedWithoutAnyTransition() {
        UUID approvalId = newAsk("req-noallow", RAW_INPUT_X, "reject_once");

        AcpDecisionRejectedException rejection = assertThrows(AcpDecisionRejectedException.class,
                () -> service.decide(approvalId, true, "agent wants in"));

        assertThat(rejection.code()).isEqualTo(AcpDecisionRejectedException.Code.UNSUPPORTED_OPTIONS);
        flushAndClear();
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        assertThat(companion(approvalId).getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_PENDING);
        assertThat(companion(approvalId).getSelectedOptionId()).isNull();
        verify(client, never()).decide(anyString(), anyString(), anyBoolean(), anyString());
        assertThat(writeGrants.trackedKeyCount()).isZero();
        assertThat(decidedEvents()).isEmpty();
    }

    @Test
    void approve_afterExpiry_isRejectedExpiredAndNeverTransitions() {
        UUID approvalId = standardAsk("req-late");
        clock.advance(Duration.ofMinutes(31));

        AcpDecisionRejectedException rejection = assertThrows(AcpDecisionRejectedException.class,
                () -> service.decide(approvalId, true, "too late"));

        assertThat(rejection.code()).isEqualTo(AcpDecisionRejectedException.Code.EXPIRED);
        flushAndClear();
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        verify(client, never()).decide(anyString(), anyString(), anyBoolean(), anyString());
        assertThat(decidedEvents()).isEmpty();
    }

    @Test
    void expiredByTheSweepFirst_isRejectedExpiredWithoutAnAllowDelivery() {
        UUID approvalId = standardAsk("req-swept");
        // The expiry sweep wins the single transition before the operator's approval arrives.
        assertThat(coordinator.expire(approvalId)).isTrue();
        flushAndClear();

        AcpDecisionRejectedException rejection = assertThrows(AcpDecisionRejectedException.class,
                () -> service.decide(approvalId, true, "operator approved"));

        assertThat(rejection.code()).isEqualTo(AcpDecisionRejectedException.Code.EXPIRED);
        flushAndClear();
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(companion(approvalId).getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
        assertThat(companion(approvalId).getSelectedOptionId()).isNull();
        // Only the sweep's cancel went out: the allow was never delivered after the expiry.
        verify(client, never()).decide(SESSION_ID, "req-swept", true, "operator approved");
        assertThat(decidedEvents()).isEmpty();
    }

    @Test
    void conflictingDecision_isRejectedAlreadyDecided() {
        UUID approvalId = standardAsk("req-conflict");
        when(client.decide(SESSION_ID, "req-conflict", true, "operator approved"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        service.decide(approvalId, true, "operator approved");
        // The winner wrote through its own transaction: read the committed state next.
        flushAndClear();

        AcpDecisionRejectedException rejection = assertThrows(AcpDecisionRejectedException.class,
                () -> service.decide(approvalId, false, "changed my mind"));

        assertThat(rejection.code()).isEqualTo(AcpDecisionRejectedException.Code.ALREADY_DECIDED);
        flushAndClear();
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.APPROVED);
        // Exactly one terminal outcome stands: the conflicting deny never reaches the bridge.
        verify(client, times(1)).decide(SESSION_ID, "req-conflict", true, "operator approved");
        verifyNoMoreInteractions(client);
    }

    @Test
    void missingCompanion_isRejectedInconsistentAsk() {
        UUID runId = committedRun(RunStatus.RUNNING);
        UUID approvalId = UUID.randomUUID();
        commit(() -> approvalRepository.saveAndFlush(Approval.builder()
                .id(approvalId).runId(runId).status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL).askType(Approval.AskType.APPROVAL)
                .source(ApprovalSource.ACP_PERMISSION)
                .requestedAt(FAKE_NOW).expiresAt(FAKE_NOW.plus(Duration.ofMinutes(30)))
                .build()));

        AcpDecisionRejectedException rejection = assertThrows(AcpDecisionRejectedException.class,
                () -> service.decide(approvalId, false, "deny"));

        assertThat(rejection.code()).isEqualTo(AcpDecisionRejectedException.Code.INCONSISTENT_ASK);
        flushAndClear();
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        verify(client, never()).decide(anyString(), anyString(), anyBoolean(), anyString());
    }

    // ---- idempotency and retry (R22, R24.4) ----------------------------------

    @Test
    void idempotentRepeat_failedAndLive_retriesWithAFreshGrant() {
        UUID approvalId = standardAsk("req-retry");
        when(client.decide(SESSION_ID, "req-retry", true, "operator approved"))
                .thenThrow(new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "bridge down"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        UUID runId = companion(approvalId).getRunId();

        ApprovalDecisionService.Result first = service.decide(approvalId, true, "operator approved");
        assertThat(first.deliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
        assertThat(writeGrants.consume(runId, WRITE_FILE_RUNTIME, RAWS_ARGS_DIGEST)).isTrue();
        flushAndClear();

        // Operator-driven retry while the ask is still live: re-issue the grant, deliver again.
        ApprovalDecisionService.Result second = service.decide(approvalId, true, "operator approved");

        assertThat(second.decision()).isEqualTo("APPROVED");
        assertThat(second.deliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        verify(client, times(2)).decide(SESSION_ID, "req-retry", true, "operator approved");
        assertThat(writeGrants.consume(runId, WRITE_FILE_RUNTIME, RAWS_ARGS_DIGEST)).isTrue();
        assertThat(writeGrants.consume(runId, WRITE_FILE_RUNTIME, RAWS_ARGS_DIGEST)).isFalse();
        flushAndClear();
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.APPROVED);
        assertThat(companion(approvalId).getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        // The retry never re-publishes the decision event.
        assertThat(decidedEvents()).hasSize(1);
    }

    @Test
    void idempotentRepeat_delivered_isReportedWithoutASecondDeliveryOrGrant() {
        UUID approvalId = standardAsk("req-double-click");
        when(client.decide(SESSION_ID, "req-double-click", true, "operator approved"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        UUID runId = companion(approvalId).getRunId();
        service.decide(approvalId, true, "operator approved");
        assertThat(writeGrants.consume(runId, WRITE_FILE_RUNTIME, RAWS_ARGS_DIGEST)).isTrue();
        flushAndClear();

        ApprovalDecisionService.Result repeat = service.decide(approvalId, true, "operator approved");

        assertThat(repeat.decision()).isEqualTo("APPROVED");
        assertThat(repeat.deliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        verify(client, times(1)).decide(SESSION_ID, "req-double-click", true, "operator approved");
        // A UI double-click is harmless: no second delivery, no extra grant, no second event.
        assertThat(writeGrants.trackedKeyCount()).isZero();
        assertThat(decidedEvents()).hasSize(1);
    }

    @Test
    void idempotentRepeat_failedPastDeadline_isReportedWithoutRetry() {
        UUID approvalId = standardAsk("req-dead-failed");
        when(client.decide(SESSION_ID, "req-dead-failed", true, "operator approved"))
                .thenThrow(new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "bridge down"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        assertThat(service.decide(approvalId, true, "operator approved").deliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
        clock.advance(Duration.ofMinutes(31));
        flushAndClear();

        ApprovalDecisionService.Result repeat = service.decide(approvalId, true, "operator approved");

        assertThat(repeat.decision()).isEqualTo("APPROVED");
        assertThat(repeat.deliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
        verify(client, times(1)).decide(SESSION_ID, "req-dead-failed", true, "operator approved");
    }

    @Test
    void idempotentRepeat_failedOnDeadRun_isReportedWithoutRetry() {
        UUID approvalId = standardAsk("req-dead-run");
        when(client.decide(SESSION_ID, "req-dead-run", true, "operator approved"))
                .thenThrow(new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "bridge down"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        assertThat(service.decide(approvalId, true, "operator approved").deliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
        UUID runId = companion(approvalId).getRunId();
        commit(() -> runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(RunStatus.COMPLETED);
            runRepository.saveAndFlush(run);
        }));
        flushAndClear();
        assertThat(coordinator.runIsActive(runId)).isFalse();

        ApprovalDecisionService.Result repeat = service.decide(approvalId, true, "operator approved");

        assertThat(repeat.deliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
        // A run that ended can never be delivered to again: no operator-driven retry either.
        verify(client, times(1)).decide(SESSION_ID, "req-dead-run", true, "operator approved");
    }

    // ---- grants (R21.4, R24.6) ------------------------------------------------

    @Test
    void nonMcpAsk_approve_issuesNoGrant() {
        UUID runId = committedRun(RunStatus.RUNNING);
        coordinator.bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        ObjectNode payload = frame("req-bash", json(Map.of("command", "ls")), "allow_once", "reject_once")
                .put("toolName", "Bash");
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID, payload);
        flushAndClear();
        UUID approvalId = companionRepository
                .findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, "req-bash").orElseThrow()
                .getApprovalId();
        when(client.decide(SESSION_ID, "req-bash", true, "operator approved"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);

        assertThat(service.decide(approvalId, true, "operator approved").deliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);

        // Not an MCP tool call: the approval stands but no write grant may be issued (R3/R4).
        assertThat(writeGrants.trackedKeyCount()).isZero();
        assertThat(writeGrants.consume(runId, "Bash", companion(approvalId).getRequestDigest())).isFalse();
    }

    /**
     * R81: the enforcement seam (WorkerGovernanceAspect.java:89) consumes the grant with the Spring
     * AI @Tool name — the bare runtime name. Minting under the ACP-qualified name left every
     * approved worker write denied GRANT_REQUIRED in the live run (20:19:11, ask f6867117).
     */
    @Test
    void mcpAsk_approve_issuesTheGrantUnderTheRuntimeToolName_theEnforcementSeamConsumes() {
        UUID runId = committedRun(RunStatus.RUNNING);
        coordinator.bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                frame("req-runtime-name", RAW_INPUT_X, "allow_once", "reject_once"));
        flushAndClear();
        UUID approvalId = companionRepository
                .findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, "req-runtime-name").orElseThrow()
                .getApprovalId();
        when(client.decide(SESSION_ID, "req-runtime-name", true, "operator approved"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);

        assertThat(service.decide(approvalId, true, "operator approved").deliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);

        assertThat(writeGrants.consume(runId, WRITE_FILE_RUNTIME, RAWS_ARGS_DIGEST))
                .as("the runtime tool name must open the approved grant").isTrue();
        assertThat(writeGrants.consume(runId, WRITE_FILE, RAWS_ARGS_DIGEST))
                .as("the ACP-qualified name must NOT open the grant").isFalse();
        assertThat(writeGrants.trackedKeyCount())
                .as("no grant may linger under the qualified name").isZero();
    }

    // ---- bridge outcomes (R18, R24.8) ----------------------------------------

    @Test
    void bridgeAlreadyResolved_countsAsDeliveredThroughTheAck() {
        UUID approvalId = standardAsk("req-race-bridge");
        when(client.decide(SESSION_ID, "req-race-bridge", true, "operator approved"))
                .thenThrow(new QoderBridgeException(QoderBridgeException.Cause.ALREADY_RESOLVED,
                        "already resolved by the CLI"));

        ApprovalDecisionService.Result result = service.decide(approvalId, true, "operator approved");

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.deliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        flushAndClear();
        assertThat(companion(approvalId).getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        assertThat(companion(approvalId).getDeliveredAt()).isEqualTo(FAKE_NOW);
        verify(client, times(1)).decide(SESSION_ID, "req-race-bridge", true, "operator approved");
    }

    // ---- restart reconciliation (R23, R24.10) --------------------------------

    @Test
    void decide_afterStuckDeliveryReconciliation_reportsCancelledWithoutDelivery() {
        UUID approvalId = standardAsk("req-stuck");
        when(client.decide(SESSION_ID, "req-stuck", true, "operator approved"))
                .thenThrow(new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "bridge down"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        assertThat(service.decide(approvalId, true, "operator approved").deliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
        // The process died mid-delivery: the companion is left DELIVERING.
        commit(() -> companionRepository.findById(approvalId).ifPresent(row -> {
            row.setDeliveryState(AcpPermissionCoordinator.DELIVERY_DELIVERING);
            companionRepository.saveAndFlush(row);
        }));
        flushAndClear();

        coordinator.reconcileStuckDeliveries();

        flushAndClear();
        // The decision record is untouched; only the dead delivery is recorded as cancelled.
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.APPROVED);
        assertThat(companion(approvalId).getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);

        ApprovalDecisionService.Result repeat = service.decide(approvalId, true, "operator approved");

        assertThat(repeat.decision()).isEqualTo("APPROVED");
        assertThat(repeat.deliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
        verify(client, times(1)).decide(SESSION_ID, "req-stuck", true, "operator approved");
    }

    /** Fake clock the service and the coordinator read their "now" from. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
