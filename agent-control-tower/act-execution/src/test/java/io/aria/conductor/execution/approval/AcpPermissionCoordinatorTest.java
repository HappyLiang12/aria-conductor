package io.aria.conductor.execution.approval;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.aria.conductor.test.DataJpaTestBase;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AcpPermissionCoordinator} on a real H2 slice: real repositories, a
 * mocked {@link QoderBridgeClient} and a fake clock. The coordinator opens its own
 * {@code REQUIRES_NEW} transactions (the pump thread, the scheduled checker and C3 all call
 * it with foreign transaction context), so fixtures are committed explicitly through
 * {@link #commit(Runnable)} and swept in {@link #cleanupCommittedRows()}.
 */
class AcpPermissionCoordinatorTest extends DataJpaTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MS = 1_800_000L;
    private static final String SESSION_ID = "bridge-session-1";
    /** Far in the past: an ask created with a short timeout is already overdue for the real clock. */
    private static final Instant FAKE_NOW = Instant.parse("2025-01-01T00:00:00Z");

    @Autowired ApprovalRepository approvalRepository;
    @Autowired AcpPermissionRequestRepository companionRepository;
    @Autowired RunRepository runRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private final QoderBridgeClient client = mock(QoderBridgeClient.class);
    private final QoderBridgeClient secondClient = mock(QoderBridgeClient.class);
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock(FAKE_NOW);

    private AcpPermissionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = coordinator(TIMEOUT_MS);
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

    private AcpPermissionCoordinator coordinator(long timeoutMs) {
        return new AcpPermissionCoordinator(approvalRepository, companionRepository, runRepository,
                events::add, transactionManager, timeoutMs, clock);
    }

    /** Commit work in its own transaction: the coordinator never reads uncommitted fixtures. */
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

    private void bindRun(UUID runId, QoderBridgeClient bridgeClient, Instant deadline) {
        coordinator.bindRun(runId, bridgeClient, deadline);
    }

    private ObjectNode frame(String requestId, String toolCallId, String toolName, String rawInput,
                             boolean truncated, String... optionKinds) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("requestId", requestId);
        if (toolCallId != null) {
            node.put("toolCallId", toolCallId);
        }
        if (toolName != null) {
            node.put("toolName", toolName);
        }
        if (rawInput != null) {
            node.put("rawInput", rawInput);
        }
        node.put("rawInputTruncated", truncated);
        // Bridge-provided display fields the host must never persist verbatim (R2).
        node.put("title", "sandbox-chosen title");
        node.put("redactedPreview", "sandbox-chosen preview");
        node.put("inputDigest", "deadbeef");
        node.put("expiresAt", "1999-01-01T00:00:00Z");
        ArrayNode options = node.putArray("options");
        for (String kind : optionKinds) {
            options.addObject()
                    .put("optionId", "opt-" + kind)
                    .put("kind", kind)
                    .put("name", "Allow\n" + kind + " once");
        }
        return node;
    }

    /** Normal ask: an L-shape MCP call with both selectable kinds. */
    private ObjectNode standardFrame(String requestId, String rawInput) {
        return frame(requestId, "call_101a69e", "mcp__aria__write_file", rawInput, false,
                "allow_once", "reject_once");
    }

    private static String json(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AcpPermissionRequest companion(UUID runId, String requestId) {
        return companionRepository.findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, requestId).orElseThrow();
    }

    private List<ApprovalRequestedEvent> publishedApprovalEvents() {
        return events.stream().filter(ApprovalRequestedEvent.class::isInstance)
                .map(ApprovalRequestedEvent.class::cast).toList();
    }

    private long approvalCount() {
        return approvalRepository.count();
    }

    // ---- creation, host-side derivation, dedupe -------------------------------

    @Test
    void permissionEvent_persistsHostDerivedApprovalAndCompanion() {
        UUID runId = committedRun(RunStatus.RUNNING);
        UUID agentId = UUID.randomUUID();
        Instant deadline = FAKE_NOW.plus(Duration.ofMinutes(45));
        bindRun(runId, client, deadline);
        String rawInput = json(Map.of("path", "/workspace/x", "content", "hello"));
        JsonNode payload = standardFrame("req-1", rawInput);

        coordinator.handlePermissionEvent(runId, agentId, SESSION_ID, payload);

        Approval approval = approvalRepository.findByRunId(runId).get(0);
        assertThat(approval.getSource()).isEqualTo(ApprovalSource.ACP_PERMISSION);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approval.getAskType()).isEqualTo(Approval.AskType.APPROVAL);
        assertThat(approval.getApprovalType()).isEqualTo(Approval.ApprovalType.TOOL_CALL);
        // ACP tool-call ids are strings; the UUID column stays null (R5).
        assertThat(approval.getToolCallId()).isNull();
        assertThat(approval.getContentKind()).isEqualTo(Approval.ContentKind.MARKDOWN);
        assertThat(approval.getRequestedAt()).isEqualTo(FAKE_NOW);
        assertThat(approval.getExpiresAt()).isEqualTo(FAKE_NOW.plusMillis(TIMEOUT_MS));
        assertThat(approval.getReason()).contains("mcp__aria__write_file");
        // Host-rendered content: the preview, the options — never the sandbox strings (R2).
        assertThat(approval.getContent()).contains("mcp__aria__write_file")
                .contains("/workspace/x")
                .doesNotContain("sandbox-chosen title")
                .doesNotContain("sandbox-chosen preview");
        assertThat(approval.getOptionsJson()).contains("allow_once").contains("reject_once");

        AcpPermissionRequest row = companion(runId, "req-1");
        assertThat(row.getApprovalId()).isEqualTo(approval.getId());
        assertThat(row.getRunId()).isEqualTo(runId);
        assertThat(row.getAgentId()).isEqualTo(agentId);
        assertThat(row.getBridgeRequestId()).isEqualTo("req-1");
        assertThat(row.getToolCallId()).isEqualTo("call_101a69e");
        assertThat(row.getToolName()).isEqualTo("mcp__aria__write_file");
        assertThat(row.getDeliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_PENDING);
        assertThat(row.getExpiresAt()).isEqualTo(FAKE_NOW.plusMillis(TIMEOUT_MS));
        assertThat(row.getRequestDigest()).isEqualTo(
                WriteGrantService.effectiveArgsDigest(Map.of("path", "/workspace/x", "content", "hello")));
        assertThat(row.getOptionsJson()).contains("opt-allow_once").contains("opt-reject_once");
        assertThat(row.getDisplayJson()).contains("\"rawInputTruncated\":false")
                .contains("\\\"path\\\"");
        assertThat(coordinator.digestForDecision(approval.getId()))
                .contains(row.getRequestDigest());

        // Exactly one after-commit event, carrying the ACP source and no tool-call UUID.
        List<ApprovalRequestedEvent> published = publishedApprovalEvents();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).getApprovalId()).isEqualTo(approval.getId());
        assertThat(published.get(0).getRunId()).isEqualTo(runId);
        assertThat(published.get(0).getToolCallId()).isNull();
        assertThat(published.get(0).getApprovalSource()).isEqualTo("ACP_PERMISSION");
    }

    @Test
    void identicalRedelivery_isANoOp_withOneAskAndOneEvent() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        JsonNode payload = standardFrame("req-dup", json(Map.of("path", "/workspace/x")));

        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID, payload);
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID, payload);

        assertThat(approvalCount()).isEqualTo(1);
        assertThat(companionRepository.count()).isEqualTo(1);
        assertThat(publishedApprovalEvents()).hasSize(1);
    }

    @Test
    void changedPayloadForSameCorrelation_isRejectedWithGovernanceError() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-changed", json(Map.of("path", "/workspace/x"))));
        String storedDigest = companion(runId, "req-changed").getRequestDigest();
        String storedContent = approvalRepository.findByRunId(runId).get(0).getContent();

        Logger logger = (Logger) LoggerFactory.getLogger(AcpPermissionCoordinator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // Same correlation, different effective args (a swapped payload must never be
            // silently accepted for an ask the operator already saw).
            coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                    standardFrame("req-changed", json(Map.of("path", "/etc/shadow"))));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(approvalCount()).isEqualTo(1);
        assertThat(companion(runId, "req-changed").getRequestDigest()).isEqualTo(storedDigest);
        assertThat(approvalRepository.findByRunId(runId).get(0).getContent()).isEqualTo(storedContent);
        assertThat(publishedApprovalEvents()).hasSize(1);
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(message -> message.contains("governance"));
    }

    // ---- malformed asks (R7) --------------------------------------------------

    @Test
    void missingToolCallId_isMalformed_noRows_andCancelDelivered() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));

        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-malformed", json(Map.of("path", "/workspace/x")))
                        .put("toolCallId", ""));

        assertThat(approvalCount()).isZero();
        assertThat(companionRepository.count()).isZero();
        assertThat(publishedApprovalEvents()).isEmpty();
        verify(client).decide(eq(SESSION_ID), eq("req-malformed"), eq(false), anyString());
    }

    @Test
    void optionsWithoutASelectableKind_isMalformed_neverCoercedIntoAnAllow() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));

        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                frame("req-noselect", "call_1", "mcp__aria__write_file", json(Map.of("path", "/x")),
                        false, "allow_always", "mystery_kind"));

        assertThat(approvalCount()).isZero();
        assertThat(companionRepository.count()).isZero();
        verify(client).decide(eq(SESSION_ID), eq("req-noselect"), eq(false), anyString());
    }

    @Test
    void oversizeAsk_isPersistedUndecidableWithTheTruncatedStringDigest() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        String truncated = "{\"path\":\"/workspace/x\",\"blob\":\"" + "a".repeat(65_500) + "\"";

        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-big", truncated).put("rawInputTruncated", true));

        Approval approval = approvalRepository.findByRunId(runId).get(0);
        AcpPermissionRequest row = companion(runId, "req-big");
        assertThat(row.getRequestDigest()).isEqualTo(sha256Hex(truncated));
        assertThat(row.getDisplayJson()).contains("\"rawInputTruncated\":true");
        assertThat(approval.getContent()).contains("truncated");
        assertThat(coordinator.digestForDecision(approval.getId())).isEmpty();
    }

    @Test
    void unknownAndTerminalRuns_dropTheEventWithBestEffortCancel() {
        bindRun(UUID.randomUUID(), client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        UUID unknownRun = UUID.randomUUID();
        coordinator.handlePermissionEvent(unknownRun, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-unknown", json(Map.of("path", "/x"))));

        UUID terminalRun = committedRun(RunStatus.COMPLETED);
        bindRun(terminalRun, secondClient, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(terminalRun, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-terminal", json(Map.of("path", "/x"))));

        assertThat(approvalCount()).isZero();
        assertThat(companionRepository.count()).isZero();
        // "req-unknown" has no bound run: the session is unresolvable, so no delivery is attempted.
        assertThat(publishedApprovalEvents()).isEmpty();
        verify(secondClient).decide(eq(SESSION_ID), eq("req-terminal"), eq(false), anyString());
    }

    // ---- normalization, digests, redaction ------------------------------------

    @Test
    void lShapeAndWShape_normalizeToTheMcpNameAndEffectiveArgs() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));

        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-l", json(Map.of("path", "/workspace/l"))));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                frame("req-w", "call_w", "mcp_call",
                        json(Map.of("toolName", "mcp__aria__write_file", "arguments", Map.of("path", "/workspace/w"))),
                        false, "allow_once", "reject_once"));

        assertThat(companion(runId, "req-l").getToolName()).isEqualTo("mcp__aria__write_file");
        assertThat(companion(runId, "req-l").getRequestDigest())
                .isEqualTo(WriteGrantService.effectiveArgsDigest(Map.of("path", "/workspace/l")));
        // W-shape: the wrapper name never becomes the effective tool identity.
        assertThat(companion(runId, "req-w").getToolName()).isEqualTo("mcp__aria__write_file");
        assertThat(companion(runId, "req-w").getRequestDigest())
                .isEqualTo(WriteGrantService.effectiveArgsDigest(Map.of("path", "/workspace/w")));
    }

    @Test
    void nonMcpAsk_isPersistedButNeverGrantable() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));

        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                frame("req-bash", "call_b", "Bash", json(Map.of("command", "ls")), false,
                        "allow_once", "reject_once"));

        UUID approvalId = approvalRepository.findByRunId(runId).get(0).getId();
        assertThat(companion(runId, "req-bash").getToolName()).isEqualTo("Bash");
        assertThat(coordinator.digestForDecision(approvalId)).isEmpty();
    }

    @Test
    void secretLookingStrings_areRedactedInStoredDisplay() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        String rawInput = json(Map.of(
                "path", "/workspace/x",
                "authorization", "Bearer super-secret-value",
                "apiKey", "sk-live-1234567890",
                "note", "wcp_abcdefghijklmnop"));

        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-secret", rawInput));

        String content = approvalRepository.findByRunId(runId).get(0).getContent();
        String display = companion(runId, "req-secret").getDisplayJson();
        for (String stored : List.of(content, display)) {
            assertThat(stored).doesNotContain("super-secret-value")
                    .doesNotContain("sk-live-1234567890")
                    .doesNotContain("wcp_abcdefghijklmnop");
        }
        assertThat(content).contains("[redacted]");
    }

    @Test
    void hostileToolName_isSanitizedInDisplayText_andKeptRawAsTheIdentity() throws Exception {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        String hostileName = "writ\u0007e_file";
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                frame("req-hostile", "call_hostile", hostileName, json(Map.of("path", "/x\u007f")),
                        false, "allow_once", "reject_once"));

        flushAndClear();
        Approval approval = approvalRepository.findByRunId(runId).get(0);
        assertThat(approval.getReason()).isEqualTo("ACP permission request: writ e_file");
        assertThat(approval.getContent()).contains("`writ e_file`").doesNotContain("\u0007", "\u007f");
        assertThat(MAPPER.readTree(companion(runId, "req-hostile").getDisplayJson())
                .path("toolName").asText()).isEqualTo("writ e_file");
        // The identity copy stays raw: a redelivery of the same payload must still dedupe.
        assertThat(companion(runId, "req-hostile").getToolName()).isEqualTo(hostileName);
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                frame("req-hostile", "call_hostile", hostileName, json(Map.of("path", "/x\u007f")),
                        false, "allow_once", "reject_once"));
        assertThat(publishedApprovalEvents()).hasSize(1);
    }

    // ---- expiry, delivery, restart recovery -----------------------------------

    @Test
    void expiresAt_isMinOfApprovalTimeoutAndRunDeadline() {
        UUID timeoutWinsRun = committedRun(RunStatus.RUNNING);
        UUID deadlineWinsRun = committedRun(RunStatus.RUNNING);
        bindRun(timeoutWinsRun, client, FAKE_NOW.plus(Duration.ofHours(4)));
        bindRun(deadlineWinsRun, secondClient, FAKE_NOW.plus(Duration.ofSeconds(5)));

        coordinator.handlePermissionEvent(timeoutWinsRun, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-timeout", json(Map.of("path", "/x"))));
        coordinator.handlePermissionEvent(deadlineWinsRun, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-deadline", json(Map.of("path", "/y"))));

        assertThat(companion(timeoutWinsRun, "req-timeout").getExpiresAt())
                .isEqualTo(FAKE_NOW.plusMillis(TIMEOUT_MS));
        assertThat(companion(deadlineWinsRun, "req-deadline").getExpiresAt())
                .isEqualTo(FAKE_NOW.plus(Duration.ofSeconds(5)));
    }

    @Test
    void overdueAsk_isExpiredByTheSweep_andTheCancelIsDelivered() {
        AcpPermissionCoordinator shortTimeout = coordinator(1_000L);
        UUID runId = committedRun(RunStatus.RUNNING);
        shortTimeout.bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        shortTimeout.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-expired", json(Map.of("path", "/x"))));
        UUID approvalId = approvalRepository.findByRunId(runId).get(0).getId();
        assertThat(companionRepository.findById(approvalId).orElseThrow().getExpiresAt())
                .isEqualTo(FAKE_NOW.plusSeconds(1));

        ApprovalExpiryChecker checker = new ApprovalExpiryChecker(approvalRepository,
                mock(ApprovalGate.class), shortTimeout);
        checker.checkExpiredApprovals();
        // The sweep's writes bypass this test's persistence context: drop the cached fixtures so
        // the assertions below read the committed state instead of the pre-sweep instances.
        flushAndClear();

        Approval expired = approvalRepository.findById(approvalId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(expired.getReason()).isEqualTo(AcpPermissionCoordinator.REASON_EXPIRED);
        assertThat(expired.getDecidedAt()).isNotNull();
        assertThat(companionRepository.findById(approvalId).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
        verify(client).decide(SESSION_ID, "req-expired", false, AcpPermissionCoordinator.REASON_EXPIRED);

        // The sweep is idempotent: nothing left to expire, no second delivery.
        checker.checkExpiredApprovals();
        verify(client, times(1)).decide(eq(SESSION_ID), eq("req-expired"), anyBoolean(), anyString());
    }

    @Test
    void expire_onADecidedAsk_isFalseAndNeverRewritesIt() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-decided", json(Map.of("path", "/x"))));
        UUID approvalId = approvalRepository.findByRunId(runId).get(0).getId();
        commit(() -> approvalRepository.findById(approvalId).ifPresent(a -> {
            a.setStatus(ApprovalStatus.APPROVED);
            a.setReason("approved by operator");
            approvalRepository.saveAndFlush(a);
        }));
        // The decision was committed outside this test's transaction: drop the stale PENDING
        // instance the lookup above cached so the assertions read the committed state.
        flushAndClear();

        assertThat(coordinator.expire(approvalId)).isFalse();
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.APPROVED);
        verify(client, never()).decide(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void deliverDecision_isIdempotentAfterDelivery() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-deliver", json(Map.of("path", "/x"))));
        UUID approvalId = approvalRepository.findByRunId(runId).get(0).getId();
        when(client.decide(SESSION_ID, "req-deliver", true, "operator approved"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);

        assertThat(coordinator.deliverDecision(approvalId, true, "operator approved"))
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        assertThat(coordinator.deliverDecision(approvalId, true, "operator approved"))
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);

        verify(client, times(1)).decide(SESSION_ID, "req-deliver", true, "operator approved");
        AcpPermissionRequest row = companionRepository.findById(approvalId).orElseThrow();
        assertThat(row.getDeliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        assertThat(row.getDeliveredAt()).isEqualTo(FAKE_NOW);
    }

    @Test
    void deliverDecision_transientFailureIsFailed_andRetryable() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-retry", json(Map.of("path", "/x"))));
        UUID approvalId = approvalRepository.findByRunId(runId).get(0).getId();
        when(client.decide(SESSION_ID, "req-retry", false, "operator denied"))
                .thenThrow(new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "bridge down"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.ALREADY_RESOLVED);

        assertThat(coordinator.deliverDecision(approvalId, false, "operator denied"))
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
        assertThat(coordinator.deliverDecision(approvalId, false, "operator denied"))
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);

        verify(client, times(2)).decide(SESSION_ID, "req-retry", false, "operator denied");
    }

    @Test
    void deliverDecision_bridgeRejectionsStayFailed_andOnlyTheAskRulingDelivers() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-rejection", json(Map.of("path", "/x"))));
        UUID approvalId = approvalRepository.findByRunId(runId).get(0).getId();
        // Every non-transport cause that rejects the host's own call (401/400/404/409/413/422/5xx)
        // means the decision was never applied — only the answer that rules on the ask itself
        // (409 ALREADY_RESOLVED) may count as delivered (R16).
        when(client.decide(SESSION_ID, "req-rejection", true, "operator approved")).thenThrow(
                new QoderBridgeException(QoderBridgeException.Cause.UNAUTHORIZED, "bridge token rejected"),
                new QoderBridgeException(QoderBridgeException.Cause.INVALID_REQUEST, "malformed decision"),
                new QoderBridgeException(QoderBridgeException.Cause.NOT_FOUND, "unknown request"),
                new QoderBridgeException(QoderBridgeException.Cause.CONFLICT, "unresolved conflict"),
                new QoderBridgeException(QoderBridgeException.Cause.SESSION_ENDED, "session gone"),
                new QoderBridgeException(QoderBridgeException.Cause.PAYLOAD_TOO_LARGE, "body too large"),
                new QoderBridgeException(QoderBridgeException.Cause.UNSUPPORTED_OPTIONS, "cannot express"),
                new QoderBridgeException(QoderBridgeException.Cause.PROVIDER_ERROR, "cli failed"),
                new QoderBridgeException(QoderBridgeException.Cause.ALREADY_RESOLVED, "already decided"));

        for (int i = 0; i < 8; i++) {
            assertThat(coordinator.deliverDecision(approvalId, true, "operator approved"))
                    .as("rejection attempt %d", i)
                    .isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
            flushAndClear();
            assertThat(companionRepository.findById(approvalId).orElseThrow().getDeliveryState())
                    .isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
            assertThat(companionRepository.findById(approvalId).orElseThrow().getDeliveredAt()).isNull();
        }
        // Retryable by construction: the ninth attempt is the one answer that rules on the ask.
        assertThat(coordinator.deliverDecision(approvalId, true, "operator approved"))
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        flushAndClear();
        assertThat(companionRepository.findById(approvalId).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);

        verify(client, times(9)).decide(SESSION_ID, "req-rejection", true, "operator approved");
    }

    @Test
    void deliverDecision_bridgeOutcomes_narrowTheDeliveredSet() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-outcome", json(Map.of("path", "/x"))));
        UUID approvalId = approvalRepository.findByRunId(runId).get(0).getId();
        // A 200 whose outcome is expired/unknown (or absent) is not a delivery: the CLI never
        // applied the decision, so the companion stays FAILED and retryable (R18).
        when(client.decide(SESSION_ID, "req-outcome", true, "operator approved")).thenReturn(
                QoderBridgeClient.DecisionOutcome.EXPIRED,
                QoderBridgeClient.DecisionOutcome.UNKNOWN,
                null,
                QoderBridgeClient.DecisionOutcome.ALREADY_RESOLVED,
                QoderBridgeClient.DecisionOutcome.DELIVERED);

        for (int i = 0; i < 3; i++) {
            assertThat(coordinator.deliverDecision(approvalId, true, "operator approved"))
                    .as("unapplied outcome attempt %d", i)
                    .isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
            flushAndClear();
            assertThat(companionRepository.findById(approvalId).orElseThrow().getDeliveryState())
                    .isEqualTo(AcpPermissionCoordinator.DELIVERY_FAILED);
            assertThat(companionRepository.findById(approvalId).orElseThrow().getDeliveredAt()).isNull();
        }
        // Retryable by construction: the first answer that took effect settles the ask.
        assertThat(coordinator.deliverDecision(approvalId, true, "operator approved"))
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        flushAndClear();
        assertThat(companionRepository.findById(approvalId).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
        assertThat(companionRepository.findById(approvalId).orElseThrow().getDeliveredAt())
                .isEqualTo(FAKE_NOW);

        // Three unapplied attempts plus the one that took effect: the settling answer settles the
        // ask, so no further bridge call is made (4 invocations total).
        verify(client, times(4)).decide(SESSION_ID, "req-outcome", true, "operator approved");
    }

    @Test
    void restartRecovery_expiresPendingAcpAsksWithoutReplay() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-r1", json(Map.of("path", "/x"))));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-r2", json(Map.of("path", "/y"))));
        UUID legacyId = UUID.randomUUID();
        commit(() -> approvalRepository.saveAndFlush(TestDataBuilder.anApproval()
                .withId(legacyId).withRunId(runId).withStatus(ApprovalStatus.PENDING).build()));

        coordinator.expireInterruptedPendingApprovals();

        for (String requestId : List.of("req-r1", "req-r2")) {
            Approval approval = approvalRepository.findById(companion(runId, requestId).getApprovalId())
                    .orElseThrow();
            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
            assertThat(approval.getReason())
                    .isEqualTo(AcpPermissionCoordinator.REASON_RESTART_INTERRUPTED);
            AcpPermissionRequest row = companionRepository.findById(approval.getId()).orElseThrow();
            assertThat(row.getDeliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
        }
        // R9: the cancel is delivered best-effort through the same idempotent primitive (this
        // test still holds a live client; a real restart starts with an empty live-run map,
        // where the attempt is a no-op and the failed-delivery fallback still records
        // CANCELLED). Only the cancels may reach the bridge — never a fresh ask or an allow —
        // and legacy rows belong to the legacy gate, not to this recovery.
        verify(client).decide(SESSION_ID, "req-r1", false,
                AcpPermissionCoordinator.REASON_RESTART_INTERRUPTED);
        verify(client).decide(SESSION_ID, "req-r2", false,
                AcpPermissionCoordinator.REASON_RESTART_INTERRUPTED);
        verify(client, times(2)).decide(anyString(), anyString(), anyBoolean(), anyString());
        assertThat(approvalRepository.findById(legacyId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void digestForDecision_isEmptyForUnknownRows() {
        assertThat(coordinator.digestForDecision(UUID.randomUUID())).isEmpty();
        assertThat(coordinator.deliverDecision(UUID.randomUUID(), true, "n/a"))
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_MISSING);
    }

    // ---- run end and restart reconciliation (R23) ------------------------------

    @Test
    void runIsActive_isPublicAndFollowsTheRunLifecycle() throws Exception {
        // R23 makes the liveness probe public so C3 can gate operator-driven retries on it.
        assertThat(AcpPermissionCoordinator.class.getMethod("runIsActive", UUID.class)).isNotNull();
        UUID running = committedRun(RunStatus.RUNNING);
        UUID done = committedRun(RunStatus.COMPLETED);
        assertThat(coordinator.runIsActive(running)).isTrue();
        assertThat(coordinator.runIsActive(done)).isFalse();
        assertThat(coordinator.runIsActive(UUID.randomUUID())).isFalse();
    }

    @Test
    void cancelPendingForRun_expiresOnlyThatRunsPendingAcpAsks() {
        UUID runA = committedRun(RunStatus.RUNNING);
        UUID runB = committedRun(RunStatus.RUNNING);
        bindRun(runA, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        bindRun(runB, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runA, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-a", json(Map.of("path", "/a"))));
        coordinator.handlePermissionEvent(runB, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-b", json(Map.of("path", "/b"))));
        UUID legacyId = UUID.randomUUID();
        commit(() -> approvalRepository.saveAndFlush(TestDataBuilder.anApproval()
                .withId(legacyId).withRunId(runA).withStatus(ApprovalStatus.PENDING).build()));
        when(client.decide(SESSION_ID, "req-a", false, AcpPermissionCoordinator.REASON_RUN_ENDED))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);

        int expired = coordinator.cancelPendingForRun(runA, AcpPermissionCoordinator.REASON_RUN_ENDED);
        flushAndClear();

        assertThat(expired).isEqualTo(1);
        UUID askA = companion(runA, "req-a").getApprovalId();
        Approval approvalA = approvalRepository.findById(askA).orElseThrow();
        assertThat(approvalA.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(approvalA.getReason()).isEqualTo(AcpPermissionCoordinator.REASON_RUN_ENDED);
        assertThat(companionRepository.findById(askA).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
        // The other run's ask and the legacy row are not this sweep's business.
        assertThat(approvalRepository.findById(companion(runB, "req-b").getApprovalId()).orElseThrow()
                .getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approvalRepository.findById(legacyId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        verify(client, times(1)).decide(SESSION_ID, "req-a", false,
                AcpPermissionCoordinator.REASON_RUN_ENDED);
    }

    @Test
    void onRunCompleted_cancelsTheRunsPendingAcpAsks_andNoOpsForARunWithoutAsks() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-end", json(Map.of("path", "/end"))));

        coordinator.onRunCompleted(new RunCompletedEvent(this, runId, null, RunStatus.COMPLETED));

        UUID askId = companion(runId, "req-end").getApprovalId();
        Approval ask = approvalRepository.findById(askId).orElseThrow();
        assertThat(ask.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(ask.getReason()).isEqualTo("run ended before decision");
        assertThat(companionRepository.findById(askId).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
        // A run that has no asks left is a silent no-op, not an error.
        UUID cleanRun = committedRun(RunStatus.COMPLETED);
        coordinator.onRunCompleted(new RunCompletedEvent(this, cleanRun, null, RunStatus.COMPLETED));
        assertThat(approvalRepository.count()).isEqualTo(1);
    }

    @Test
    void reconcileStuckDeliveries_marksStuckRowsCancelled_andLeavesSettledRowsAndTheDecisionRecordAlone() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-stuck", json(Map.of("path", "/stuck"))));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-done", json(Map.of("path", "/done"))));
        UUID stuckId = companion(runId, "req-stuck").getApprovalId();
        UUID doneId = companion(runId, "req-done").getApprovalId();
        // A decided approval recorded through the repository's conditional transition (C3's
        // own writer): the reconciliation must not rewrite the decision, only the delivery.
        commit(() -> approvalRepository.decidePendingById(stuckId, ApprovalStatus.APPROVED,
                "operator approved", FAKE_NOW));
        when(client.decide(SESSION_ID, "req-stuck", true, "operator approved"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        when(client.decide(SESSION_ID, "req-done", true, "operator approved"))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        coordinator.deliverDecision(stuckId, true, "operator approved");
        coordinator.deliverDecision(doneId, true, "operator approved");
        // The process died between the DELIVERING claim and the settling write.
        commit(() -> companionRepository.findById(stuckId).ifPresent(row -> {
            row.setDeliveryState(AcpPermissionCoordinator.DELIVERY_DELIVERING);
            companionRepository.saveAndFlush(row);
        }));
        assertThat(companionRepository.findByDeliveryState(AcpPermissionCoordinator.DELIVERY_DELIVERING))
                .hasSize(1);

        coordinator.reconcileStuckDeliveries();
        flushAndClear();

        assertThat(companionRepository.findByDeliveryState(AcpPermissionCoordinator.DELIVERY_DELIVERING))
                .isEmpty();
        assertThat(companionRepository.findById(stuckId).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
        // Only the dead delivery is settled: the decision record still stands.
        assertThat(approvalRepository.findById(stuckId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.APPROVED);
        assertThat(companionRepository.findById(doneId).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_DELIVERED);
    }

    @Test
    void restartReconciliation_first_convergesTheCrashingAskToExpiredAndCancelled() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-crash", json(Map.of("path", "/crash"))));
        UUID askId = stuckMidDelivery(runId, "req-crash");

        coordinator.reconcileStuckDeliveries();
        coordinator.expireInterruptedPendingApprovals();
        flushAndClear();

        assertThat(approvalRepository.findById(askId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(companionRepository.findById(askId).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
    }

    @Test
    void restartRecoveryRemainingFirst_convergesTheCrashingAskToExpiredAndCancelled() {
        UUID runId = committedRun(RunStatus.RUNNING);
        bindRun(runId, client, FAKE_NOW.plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                standardFrame("req-crash", json(Map.of("path", "/crash"))));
        UUID askId = stuckMidDelivery(runId, "req-crash");

        // The C2 recovery first leaves the mid-delivery row DELIVERING (its cancel claim is
        // already owned); the reconciliation then settles it. Order-independent convergence.
        coordinator.expireInterruptedPendingApprovals();
        coordinator.reconcileStuckDeliveries();
        flushAndClear();

        assertThat(approvalRepository.findById(askId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(companionRepository.findById(askId).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
    }

    /** Commits an ask whose companion was left mid-delivery (the process died mid-claim). */
    private UUID stuckMidDelivery(UUID runId, String requestId) {
        UUID askId = companion(runId, requestId).getApprovalId();
        commit(() -> companionRepository.findById(askId).ifPresent(row -> {
            row.setDeliveryState(AcpPermissionCoordinator.DELIVERY_DELIVERING);
            companionRepository.saveAndFlush(row);
        }));
        return askId;
    }

    /** Fake clock the coordinator reads its "now" from; the sweep runs on the real clock. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
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
