package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.execution.pipeline.ActionType;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Phase F concurrency tests for {@link ApprovalGate}: decide-vs-timeout races,
 * decide-vs-cancel races, double-decide races and a 100-waiter stress run.
 *
 * <p>Repository mocks are backed by concurrent in-memory stores (same idiom as
 * {@link ApprovalGateTest}) so racing threads mutate real shared state. The
 * blocking {@code requestApproval} calls are driven from virtual threads and
 * every cross-thread assertion is synchronised with Awaitility/CountDownLatch —
 * never a bare sleep. Approval ids are harvested from the
 * {@link ApprovalRequestedEvent}, which the gate publishes only AFTER the
 * blocking future is registered, so deciders never race the registration itself.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalGateConcurrencyTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private ToolCallRepository toolCallRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final Map<UUID, Approval> approvalStore = new ConcurrentHashMap<>();
    private final Map<UUID, ToolCall> toolCallStore = new ConcurrentHashMap<>();
    /** Approval ids in the order their blocking futures became decidable. */
    private final ConcurrentLinkedQueue<UUID> requestedIds = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        lenient().when(toolCallRepository.save(any(ToolCall.class))).thenAnswer(inv -> {
            ToolCall tc = inv.getArgument(0);
            if (tc.getId() == null) tc.setId(UUID.randomUUID());
            toolCallStore.put(tc.getId(), tc);
            return tc;
        });
        lenient().when(toolCallRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(toolCallStore.get(inv.<UUID>getArgument(0))));
        lenient().when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> {
            Approval a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            approvalStore.put(a.getId(), a);
            return a;
        });
        lenient().when(approvalRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(approvalStore.get(inv.<UUID>getArgument(0))));
        lenient().when(approvalRepository.findByRunId(any(UUID.class))).thenAnswer(inv -> {
            UUID runId = inv.getArgument(0);
            return approvalStore.values().stream()
                    .filter(a -> runId.equals(a.getRunId()))
                    .toList();
        });
        // Publish happens after pendingApprovals.put(), so an id in this queue is safely decidable.
        lenient().doAnswer(inv -> {
            requestedIds.add(inv.<ApprovalRequestedEvent>getArgument(0).getApprovalId());
            return null;
        }).when(eventPublisher).publishEvent(any(ApprovalRequestedEvent.class));
    }

    private ApprovalGate gate(long timeoutMs) {
        return new ApprovalGate(approvalRepository, toolCallRepository, eventPublisher, timeoutMs);
    }

    // ── 1. decideApproval vs timeout race ────────────────────────────────

    @Test
    void decisionLandingBeforeExpiry_winsOverTimeout_neverTimeoutDeny() throws Exception {
        ApprovalGate gate = gate(300);
        CompletableFuture<ApprovalDecision> waiter = startWaiter(gate, ctx(), "deploy");

        UUID approvalId = awaitNextApprovalId();
        gate.decideApproval(approvalId, true, "operator raced the clock");

        ApprovalDecision decision = waiter.get(2, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.reason())
                .isEqualTo("operator raced the clock")
                .doesNotContain("timed out");
        // Even after the 300ms window has long expired, the recorded outcome stays APPROVED.
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(ApprovalStatus.APPROVED));
        assertThat(approvalStore.get(approvalId).getDecidedAt()).isNotNull();
    }

    // ── 2. timeout path (nobody decides) ─────────────────────────────────

    @Test
    void undecidedApproval_timesOut_denyingWaiterAndExpiringEntity() throws Exception {
        ApprovalGate gate = gate(100);
        CompletableFuture<ApprovalDecision> waiter = startWaiter(gate, ctx(), "drop_table");

        UUID approvalId = awaitNextApprovalId();

        await().atMost(Duration.ofSeconds(2)).until(waiter::isDone);
        ApprovalDecision decision = waiter.get(2, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.reason()).contains("timed out");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            Approval expired = approvalStore.get(approvalId);
            assertThat(expired.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
            assertThat(expired.getReason()).isEqualTo("Auto-rejected: approval timed out");
            assertThat(expired.getDecidedAt()).isNotNull();
        });
        assertThat(pendingFutures(gate)).isEmpty();
    }

    // ── 3. decideApproval vs cancelAllPendingForRun race ─────────────────

    @Test
    void decideRacingCancelAllForRun_releasesEveryWaiter_eachApprovalEndsInOneTerminalState() throws Exception {
        ApprovalGate gate = gate(30_000);
        RunContext ctx = ctx();
        CompletableFuture<ApprovalDecision> waiter1 = startWaiter(gate, ctx, "tool_one");
        UUID approval1 = awaitNextApprovalId();
        CompletableFuture<ApprovalDecision> waiter2 = startWaiter(gate, ctx, "tool_two");
        UUID approval2 = awaitNextApprovalId();

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                gate.decideApproval(approval1, true, "approved in the race");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                gate.cancelAllPendingForRun(ctx.getRunId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        go.countDown();
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();

        // No waiter may hang, whichever side won the race.
        await().atMost(Duration.ofSeconds(2)).until(() -> waiter1.isDone() && waiter2.isDone());
        assertThat(waiter1.get(1, TimeUnit.SECONDS)).isNotNull();
        assertThat(waiter2.get(1, TimeUnit.SECONDS).isApproved()).isFalse();

        // Each approval settles in exactly one terminal state with audit fields — no lost update.
        Approval a1 = approvalStore.get(approval1);
        assertThat(a1.getStatus()).isIn(ApprovalStatus.APPROVED, ApprovalStatus.EXPIRED);
        assertThat(a1.getDecidedAt()).isNotNull();
        Approval a2 = approvalStore.get(approval2);
        assertThat(a2.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(a2.getDecidedAt()).isNotNull();
        // Waiter outcome must be consistent with the winning branch for approval #1.
        if (waiter1.get().isApproved()) {
            assertThat(waiter1.get().reason()).isEqualTo("approved in the race");
        } else {
            assertThat(waiter1.get().reason()).isEqualTo("Run cancelled");
        }
        assertThat(pendingFutures(gate)).isEmpty();
    }

    // ── 4. double-decide race ─────────────────────────────────────────────

    @Test
    void twoSimultaneousDecisions_releaseWaiterExactlyOnce_finalStateIsOneDecisionAndStable() throws Exception {
        ApprovalGate gate = gate(30_000);
        CompletableFuture<ApprovalDecision> waiter = startWaiter(gate, ctx(), "deploy");
        UUID approvalId = awaitNextApprovalId();

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                gate.decideApproval(approvalId, true, "racer says yes");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                gate.decideApproval(approvalId, false, "racer says no");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        go.countDown();
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();

        // Waiter is released with exactly one of the two racing decisions.
        await().atMost(Duration.ofSeconds(2)).until(waiter::isDone);
        ApprovalDecision decision = waiter.get(1, TimeUnit.SECONDS);
        assertThat(decision.reason()).isIn("racer says yes", "racer says no");
        assertThat(decision.isApproved()).isEqualTo(decision.reason().equals("racer says yes"));

        // Final entity state is one of the two decisions and remains stable afterwards.
        ApprovalStatus settled = approvalStore.get(approvalId).getStatus();
        assertThat(settled).isIn(ApprovalStatus.APPROVED, ApprovalStatus.DENIED);
        gate.decideApproval(approvalId, settled != ApprovalStatus.APPROVED, "late flip attempt");
        assertThat(approvalStore.get(approvalId).getStatus()).isEqualTo(settled);
        assertThat(pendingFutures(gate)).isEmpty();
    }

    // ── 5. stress: 100 concurrent waiters, one decider ───────────────────

    @Test
    void hundredConcurrentWaiters_allApprovedByRollingDecider_noPendingFutureLeaks() throws Exception {
        ApprovalGate gate = gate(30_000);
        int n = 100;
        List<CompletableFuture<ApprovalDecision>> waiters = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            waiters.add(startWaiter(gate, ctx(), "bulk_tool_" + i));
        }

        // Rolling decider: approve ids as their ApprovalRequestedEvent appears.
        Thread decider = Thread.ofVirtual().start(() -> {
            int decided = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (decided < n && System.nanoTime() < deadline) {
                UUID id = requestedIds.poll();
                if (id == null) {
                    Thread.yield(); // let waiter virtual threads mount and register
                    continue;
                }
                gate.decideApproval(id, true, "bulk approved");
                decided++;
            }
        });

        await().atMost(Duration.ofSeconds(10)).until(() -> waiters.stream().allMatch(CompletableFuture::isDone));
        await().atMost(Duration.ofSeconds(2)).until(() -> !decider.isAlive());

        for (CompletableFuture<ApprovalDecision> waiter : waiters) {
            ApprovalDecision d = waiter.get(1, TimeUnit.SECONDS);
            assertThat(d.isApproved()).isTrue();
            assertThat(d.reason()).isEqualTo("bulk approved");
        }
        assertThat(approvalStore).hasSize(n);
        assertThat(approvalStore.values())
                .allSatisfy(a -> assertThat(a.getStatus()).isEqualTo(ApprovalStatus.APPROVED));
        // No released waiter may leave a future behind in the gate.
        assertThat(pendingFutures(gate)).isEmpty();
    }

    // ---- helpers ----

    private static Action action(String name) {
        return new Action(name, ActionType.HIGH_RISK, "{}", null);
    }

    private RunContext ctx() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Agent agent = TestDataBuilder.anAgent().withId(agentId).build();
        AgentSession session = TestDataBuilder.anAgentSession().withRunId(runId).withAgentId(agentId).build();
        return new RunContext(runId, agentId, agent, session);
    }

    /** Drive the blocking requestApproval from a virtual thread. */
    private CompletableFuture<ApprovalDecision> startWaiter(ApprovalGate gate, RunContext ctx, String tool) {
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(gate.requestApproval(action(tool), ctx));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Await the next safely-decidable approval id (published after future registration). */
    private UUID awaitNextApprovalId() {
        // Tight polling: the decide-vs-timeout race test runs on a 300ms window, so the
        // default 100ms poll delay would eat most of the budget before the decision lands.
        await().atMost(Duration.ofSeconds(5))
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(10))
                .until(() -> !requestedIds.isEmpty());
        return requestedIds.poll();
    }

    /**
     * Leak probe: the gate's private pendingApprovals map must be empty once every
     * waiter has been released (decide/cancel/timeout all remove the future).
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, CompletableFuture<ApprovalDecision>> pendingFutures(ApprovalGate gate) {
        try {
            Field f = ApprovalGate.class.getDeclaredField("pendingApprovals");
            f.setAccessible(true);
            return (Map<UUID, CompletableFuture<ApprovalDecision>>) f.get(gate);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("pendingApprovals field not found on ApprovalGate", e);
        }
    }
}
