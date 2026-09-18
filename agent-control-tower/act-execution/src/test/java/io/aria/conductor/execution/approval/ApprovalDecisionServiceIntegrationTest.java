package io.aria.conductor.execution.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.adk.qoder.QoderBridgeClient;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H2 integration slice for C3's decision dispatch under real contention (R24.5): the real
 * {@link ApprovalDecisionService} and the real {@link AcpPermissionCoordinator} against real
 * repositories, with a mocked bridge client. The two scenarios pin that a decision and an expiry
 * of the same ask can never both win: a deterministic expiry-then-decision sequence, and a
 * genuine two-thread race on one ask.
 *
 * <p>The race test tolerates one side aborting under H2 lock contention — it never asserts a
 * particular interleaving, only the database invariants that must hold for every interleaving
 * (a decided ask is never rewritten by the expiry, and an expired ask is never {@code DELIVERED}).
 */
class ApprovalDecisionServiceIntegrationTest extends DataJpaTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MS = 1_800_000L;
    private static final String SESSION_ID = "bridge-session-c3-it";

    @Autowired ApprovalRepository approvalRepository;
    @Autowired AcpPermissionRequestRepository companionRepository;
    @Autowired RunRepository runRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private final QoderBridgeClient client = mock(QoderBridgeClient.class);
    private final ApprovalGate approvalGate = mock(ApprovalGate.class);
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final Clock clock = Clock.systemUTC();

    private AcpPermissionCoordinator coordinator;
    private WriteGrantService writeGrants;
    private ApprovalDecisionService service;

    @BeforeEach
    void setUp() {
        coordinator = new AcpPermissionCoordinator(approvalRepository, companionRepository, runRepository,
                events::add, transactionManager, TIMEOUT_MS, clock);
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

    private void commit(Runnable work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> work.run());
    }

    /** One live MCP ask created through the coordinator's real intake path. */
    private UUID newAsk(String requestId) {
        UUID runId = UUID.randomUUID();
        commit(() -> runRepository.save(TestDataBuilder.aRun().withId(runId).withStatus(RunStatus.RUNNING).build()));
        coordinator.bindRun(runId, client, clock.instant().plusSeconds(2700));
        coordinator.handlePermissionEvent(runId, UUID.randomUUID(), SESSION_ID, frame(requestId));
        flushAndClear();
        return companionRepository.findByBridgeSessionIdAndBridgeRequestId(SESSION_ID, requestId)
                .orElseThrow().getApprovalId();
    }

    private static ObjectNode frame(String requestId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("requestId", requestId);
        node.put("toolCallId", "call_" + requestId);
        node.put("toolName", "mcp__aria__write_file");
        node.put("rawInput", "{\"path\":\"/workspace/it\"}");
        node.put("rawInputTruncated", false);
        ArrayNode options = node.putArray("options");
        options.addObject().put("optionId", "opt-allow_once").put("kind", "allow_once").put("name", "Allow once");
        options.addObject().put("optionId", "opt-reject_once").put("kind", "reject_once").put("name", "Reject");
        return node;
    }

    private static <T> T attempt(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            // Tolerated: H2 may abort one side of the collision with a lock timeout before it
            // commits anything. The invariants below are read from the database.
            return null;
        }
    }

    // ---- deterministic loser ------------------------------------------------

    @Test
    void expiredBeforeTheDecision_decisionIsRejectedExpired_andTheAllowNeverReachesTheBridge() {
        String requestId = "req-it-deterministic";
        UUID approvalId = newAsk(requestId);
        // The expiry sweep wins first: the ask is terminal before the operator's approval lands.
        assertThat(coordinator.expire(approvalId)).isTrue();

        AcpDecisionRejectedException rejection = assertThrows(AcpDecisionRejectedException.class,
                () -> service.decide(approvalId, true, "operator approved"));

        assertThat(rejection.code()).isEqualTo(AcpDecisionRejectedException.Code.EXPIRED);
        flushAndClear();
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);
        AcpPermissionRequest row = companionRepository.findById(approvalId).orElseThrow();
        assertThat(row.getDeliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
        assertThat(row.getSelectedOptionId()).isNull();
        // Only the cancel went out: the allow was never delivered after the expiry.
        verify(client, never()).decide(SESSION_ID, requestId, true, "operator approved");
    }

    // ---- two-thread race ----------------------------------------------------

    @Test
    void concurrentDecisionAndExpiry_haveExactlyOneTerminalOutcome() throws Exception {
        String requestId = "req-it-race";
        UUID approvalId = newAsk(requestId);
        when(client.decide(eq(SESSION_ID), eq(requestId), anyBoolean(), anyString()))
                .thenReturn(QoderBridgeClient.DecisionOutcome.DELIVERED);
        events.clear();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ApprovalDecisionService.Result> decision = pool.submit(() -> {
                barrier.await(20, TimeUnit.SECONDS);
                return attempt(() -> service.decide(approvalId, true, "operator approved"));
            });
            Future<Boolean> expiry = pool.submit(() -> {
                barrier.await(20, TimeUnit.SECONDS);
                return attempt(() -> coordinator.expire(approvalId));
            });
            decision.get(20, TimeUnit.SECONDS);
            expiry.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        flushAndClear();
        Approval stored = approvalRepository.findById(approvalId).orElseThrow();
        AcpPermissionRequest row = companionRepository.findById(approvalId).orElseThrow();
        if (stored.getStatus() == ApprovalStatus.APPROVED) {
            // The decision won the single conditional transition; the expiry is the no-op loser.
            assertThat(row.getSelectedOptionId()).isEqualTo("opt-allow_once");
            assertThat(row.getDeliveryState()).isIn(
                    AcpPermissionCoordinator.DELIVERY_DELIVERED, AcpPermissionCoordinator.DELIVERY_FAILED);
        } else if (stored.getStatus() == ApprovalStatus.EXPIRED) {
            // The expiry won; the allow must never be observable on the companion afterwards.
            assertThat(row.getDeliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_CANCELLED);
            assertThat(row.getSelectedOptionId()).isNull();
        } else {
            // Degenerate H2 tolerance: both sides aborted before either committed - no effect.
            assertThat(stored.getStatus()).isEqualTo(ApprovalStatus.PENDING);
            assertThat(row.getDeliveryState()).isEqualTo(AcpPermissionCoordinator.DELIVERY_PENDING);
        }
    }
}
