package io.aria.conductor.execution.approval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.adk.qoder.QoderBridgeClient;
import io.aria.conductor.execution.mcp.ToolPolicyRegistry;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.test.DataJpaTestBase;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * H2 integration slice for the ACP permission coordinator: the real coordinator, real
 * repositories and the real {@link ApprovalExpiryChecker} against a fake bridge client.
 * Verifies the persistence round-trip (create + dedupe) and the checker's ACP branch —
 * including that a legacy row keeps the legacy expiry path.
 */
class AcpPermissionCoordinatorIntegrationTest extends DataJpaTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_ID = "bridge-session-it";
    private static final long TIMEOUT_MS = 1_800_000L;

    @Autowired ApprovalRepository approvalRepository;
    /**
     * A spy so a test can force the concurrent-insert race window (the loser's lookup ran
     * before the winner committed); unstubbed calls go through to the real H2 repository.
     */
    @SpyBean AcpPermissionRequestRepository companionRepository;
    @Autowired RunRepository runRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private final QoderBridgeClient client = mock(QoderBridgeClient.class);
    private final ApprovalGate approvalGate = mock(ApprovalGate.class);
    private final List<Object> events = new CopyOnWriteArrayList<>();

    private AcpPermissionCoordinator coordinator;
    private ApprovalExpiryChecker checker;

    @BeforeEach
    void setUp() {
        coordinator = new AcpPermissionCoordinator(approvalRepository, companionRepository, runRepository,
                events::add, transactionManager, new ToolPolicyRegistry(), TIMEOUT_MS, Clock.systemUTC());
        checker = new ApprovalExpiryChecker(approvalRepository, approvalGate, coordinator);
    }

    @AfterEach
    void cleanupCommittedRows() {
        commit(() -> {
            companionRepository.deleteAllInBatch();
            approvalRepository.deleteAllInBatch();
            runRepository.deleteAllInBatch();
        });
    }

    private void commit(Runnable work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> work.run());
    }

    private UUID committedRun() {
        UUID runId = UUID.randomUUID();
        commit(() -> runRepository.save(TestDataBuilder.aRun().withId(runId).withStatus(RunStatus.RUNNING).build()));
        return runId;
    }

    private List<Approval> acpApprovals(UUID runId) {
        return approvalRepository.findByRunId(runId).stream()
                .filter(a -> a.getSource() == ApprovalSource.ACP_PERMISSION)
                .toList();
    }

    /** A permission frame shaped exactly like the bridge's C0.2 + A1 event. */
    private static ObjectNode frame(String requestId, String rawInput) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("requestId", requestId);
        node.put("toolCallId", "call_" + requestId);
        node.put("toolName", "mcp__aria__write_file");
        node.put("rawInput", rawInput);
        node.put("rawInputTruncated", false);
        node.put("expiresAt", Instant.now().plusSeconds(30).toString());
        ArrayNode options = node.putArray("options");
        options.addObject().put("optionId", "allow-1").put("kind", "allow_once").put("name", "Allow once");
        options.addObject().put("optionId", "reject-1").put("kind", "reject_once").put("name", "Reject");
        return node;
    }

    @Test
    void createsTheAskOnce_andTreatsAnIdenticalRedeliveryAsANoOp() {
        UUID runId = committedRun();
        coordinator.bindRun(runId, client, Instant.now().plus(Duration.ofMinutes(45)));
        JsonNode payload = frame("req-it-1", "{\"path\":\"/workspace/x\"}");

        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID, payload);
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID, payload);

        List<Approval> approvals = acpApprovals(runId);
        assertThat(approvals).hasSize(1);
        assertThat(approvals.get(0).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        AcpPermissionRequest row = companionRepository
                .findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, "req-it-1").orElseThrow();
        assertThat(row.getApprovalId()).isEqualTo(approvals.get(0).getId());
        assertThat(row.getDeliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_PENDING);
        // The identical re-delivery never produced a second event.
        assertThat(events.stream().filter(ApprovalRequestedEvent.class::isInstance)).hasSize(1);
    }

    @Test
    void checkerExpiresTheOverdueAcpAsk_andDeliversTheCancel() {
        UUID runId = committedRun();
        // A run deadline in the recent past makes the ask overdue for the real-clock sweep.
        coordinator.bindRun(runId, client, Instant.now().minus(Duration.ofSeconds(1)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                frame("req-it-2", "{\"path\":\"/workspace/y\"}"));
        UUID approvalId = acpApprovals(runId).get(0).getId();

        checker.checkExpiredApprovals();
        // The checker expires the ask through the coordinator's REQUIRES_NEW transaction, which
        // bypasses this test's persistence context: drop the cached PENDING instance that the
        // acpApprovals(runId) lookup above loaded so the assertions read the committed state.
        flushAndClear();

        Approval expired = approvalRepository.findById(approvalId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(expired.getReason()).isEqualTo(AcpPermissionCoordinator.REASON_EXPIRED);
        assertThat(companionRepository.findById(approvalId).orElseThrow().getDeliveryState())
                .isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
        verify(client).decide(SESSION_ID, "req-it-2", false, AcpPermissionCoordinator.REASON_EXPIRED);
    }

    @Test
    void checkerKeepsTheLegacyGatePathForLegacyRows() {
        UUID runId = committedRun();
        UUID legacyId = UUID.randomUUID();
        commit(() -> approvalRepository.saveAndFlush(TestDataBuilder.anApproval()
                .withId(legacyId).withRunId(runId)
                .withStatus(ApprovalStatus.PENDING)
                .withExpiresAt(Instant.now().minusSeconds(30))
                .build()));

        checker.checkExpiredApprovals();

        Approval legacy = approvalRepository.findById(legacyId).orElseThrow();
        assertThat(legacy.getSource()).isEqualTo(ApprovalSource.LEGACY_GATE);
        assertThat(legacy.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(legacy.getReason()).isEqualTo("Auto-rejected: approval expired");
        verify(approvalGate).cancelPendingApproval(legacyId);
        verify(client, never()).decide(anyString(), anyString(), anyBoolean(), anyString());
    }

    // ---- R5 race evidence: one row / one event per correlation under a real collision ----

    /**
     * Drives the forced-miss race once against the real H2 repositories: a committed winner
     * exists for {@code requestId}, then the loser's pre-insert lookup is stubbed to miss
     * exactly once so {@code handlePermissionEvent} takes the insert path and hits the unique
     * constraint. The one-shot stub falls through to the real repository afterwards — that
     * fall-through is exactly the catch's re-read. {@code expectedLog} pins the catch branch.
     */
    private void assertForcedMissRace(String requestId, String winnerRawInput, String loserRawInput,
                                      String expectedLog) {
        UUID runId = committedRun();
        coordinator.bindRun(runId, client, Instant.now().plus(Duration.ofMinutes(45)));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                frame(requestId, winnerRawInput));
        Approval winner = acpApprovals(runId).get(0);
        AcpPermissionRequest winnerRow = companionRepository
                .findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, requestId).orElseThrow();
        String winnerDigest = winnerRow.getRequestDigest();
        String winnerDisplay = winnerRow.getDisplayJson();
        long companionCount = companionRepository.count();
        events.clear();

        Logger logger = (Logger) LoggerFactory.getLogger(AcpPermissionCoordinator.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            // The exact race window: the loser's lookup ran before the winner committed.
            // callRealMethod() cannot answer through this spy (Boot mocks the repository
            // INTERFACE for JDK-proxy beans), so the fall-through reuses the spy's own default
            // answer: the delegatesTo(...) answer Spring wired to the real repository proxy.
            doReturn(Optional.empty())
                    .doAnswer(mockingDetails(companionRepository).getMockCreationSettings().getDefaultAnswer())
                    .when(companionRepository)
                    .findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, requestId);
            coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID,
                    frame(requestId, loserRawInput));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        // Exactly one ask: one approval, one companion row for the correlation, no second
        // event — and the winner's stored record is byte-for-byte untouched.
        flushAndClear();
        assertThat(acpApprovals(runId)).singleElement()
                .satisfies(approval -> assertThat(approval.getId()).isEqualTo(winner.getId()));
        assertThat(companionRepository.count()).isEqualTo(companionCount);
        AcpPermissionRequest stored = companionRepository
                .findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, requestId).orElseThrow();
        assertThat(stored.getApprovalId()).isEqualTo(winner.getId());
        assertThat(stored.getRequestDigest()).isEqualTo(winnerDigest);
        assertThat(stored.getDisplayJson()).isEqualTo(winnerDisplay);
        assertThat(events.stream().filter(ApprovalRequestedEvent.class::isInstance)).isEmpty();
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(expectedLog));
    }

    @Test
    void forcedMissConcurrentInsert_identicalPayloadNoOps_andChangedPayloadIsRejectedWithoutMutation() {
        // Variant 1 (identical payload P): the loser re-reads the winner and no-ops.
        assertForcedMissRace("req-race-same", "{\"path\":\"/workspace/same\"}",
                "{\"path\":\"/workspace/same\"}", "Concurrent identical ACP ask");

        // Variant 2 (changed payload P' → different digest): the loser is rejected with the
        // governance error, and the winner's row is not mutated.
        assertForcedMissRace("req-race-changed", "{\"path\":\"/workspace/same\"}",
                "{\"path\":\"/workspace/shadow\"}", "was taken by a different payload");
    }

    @Test
    void twoThreadRaceOnOneCorrelation_persistsOneAskOneCompanionAndOneEvent() throws Exception {
        UUID runId = committedRun();
        coordinator.bindRun(runId, client, Instant.now().plus(Duration.ofMinutes(45)));
        JsonNode payload = frame("req-it-race", "{\"path\":\"/workspace/race\"}");
        events.clear();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> event = () -> {
                barrier.await(20, TimeUnit.SECONDS);
                coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID, payload);
                return null;
            };
            Future<Void> first = pool.submit(event);
            Future<Void> second = pool.submit(event);
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Whichever thread wins the insert, the unique constraint is the authority: the loser
        // re-reads the winner and no-ops (or is rejected), and when it instead trips an H2 lock
        // timeout its insert transaction rolls back all the same — these invariants do not
        // depend on a particular interleaving, so the test is not flaky by construction.
        flushAndClear();
        List<Approval> approvals = acpApprovals(runId);
        assertThat(approvals).singleElement();
        AcpPermissionRequest row = companionRepository
                .findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, "req-it-race").orElseThrow();
        assertThat(row.getApprovalId()).isEqualTo(approvals.get(0).getId());
        assertThat(approvalRepository.count()).isEqualTo(1);
        assertThat(companionRepository.count()).isEqualTo(1);
        assertThat(events.stream().filter(ApprovalRequestedEvent.class::isInstance)).hasSize(1);
    }
}
