package io.aria.conductor.app;

import io.aria.conductor.ActApplication;
import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.UsageSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Cap enforcement of the run admission limit, end to end through the real
 * install wiring: with {@code aria.runs.max-active=1} a run that holds the only
 * admission slot keeps a second run at {@code PENDING} with its mirror card in
 * {@code TODO}; the moment the holder goes terminal the waiting run is admitted
 * automatically (its card moves {@code IN_PROGRESS}) and it runs to completion.
 * Over the whole scenario the coordinator's {@code execute} is never entered by
 * two runs at once.
 *
 * <p>The run is held inside {@code CoreExecutionService.execute} — the seam
 * verified in {@link KanbanTransitionIntegrityIntegrationTest}: a
 * {@code @SpyBean} answers the coordinator's {@code execute} for each run by
 * blocking on that run's own latch until the test releases it, then returns a
 * canned {@link CoreResult}. The agent stores no core, so it is run-owned
 * (the documented default core applies) and its launch passes the engine's
 * admission gate: run 1 holds its slot while parked inside {@code execute},
 * which is exactly what keeps run 2's {@code acquire} blocked. The spy also
 * counts concurrent {@code execute} entries and records entry order, so the cap
 * is proven both through the queue-visible run/card states and directly at the
 * coordinator boundary.
 *
 * <p>Start sequencing: run 2 is created only after run 1 is confirmed held
 * inside {@code execute}. Creating both up front would race the two async start
 * signals ({@code RunStartedEvent} -> {@code @Async RunExecutionListener}) for
 * the single slot; holding run 1 first keeps the createdAt FIFO order of the
 * queue (run 1 is both created and admitted first) while making which run owns
 * the slot a decision of this test instead of a race. Every mandated assertion
 * is kept.
 *
 * <p>Card flow under test: born {@code TODO} on run creation (the
 * {@code RunKanbanAutoCreator} mirror), moved {@code IN_PROGRESS} by the
 * engine's admission start signal ({@code RunIterationEvent}), settled to
 * {@code REVIEW} by the terminal {@code RunCompletedEvent}. While run 2 is
 * queued its card must stay {@code TODO}. Without the engine gate the PENDING/
 * TODO window and the max-concurrent-entries assertion both fail: run 2 would
 * enter {@code execute} immediately.
 *
 * <p>Runs in the Failsafe lane (the class name ends in IntegrationTest, which
 * Surefire excludes).
 */
@SpringBootTest(
        classes = {ActApplication.class, NoopLlmTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:run_admission;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "aria.runs.max-active=1",
                "aria.runs.aria-reserved=0",
                "aria.kanban.auto-dispatch-on-create=false"
        })
@ActiveProfiles({"test", "noop-llm"})
class RunAdmissionIntegrationTest {

    @Autowired
    private RunService runService;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private RunRepository runRepository;
    @Autowired
    private KanbanRepository kanbanRepository;

    @SpyBean
    private CoreExecutionService coreExecutionService;

    /** Per-run hold latches: an execute() call blocks until its run's latch is released. */
    private final Map<UUID, CountDownLatch> holds = new ConcurrentHashMap<>();
    /** Run ids in execute()-entry order — FIFO proof. */
    private final List<UUID> executionStarts = new CopyOnWriteArrayList<>();
    private final AtomicInteger concurrentExecutions = new AtomicInteger();
    private final AtomicInteger maxConcurrentExecutions = new AtomicInteger();

    /**
     * Install the hold seam before any run exists: every coordinated attempt
     * parks at the coordinator's {@code execute} boundary on its own run's
     * latch (created on first entry, so a release racing the entry still wins)
     * and answers with a canned {@link CoreResult} once released. The counters
     * observe the cap directly at the coordinator boundary.
     */
    @BeforeEach
    void installHoldSeam() {
        holds.clear();
        executionStarts.clear();
        concurrentExecutions.set(0);
        maxConcurrentExecutions.set(0);
        doAnswer(inv -> {
            ExecutionSpec spec = inv.getArgument(0);
            UUID runId = spec.runId();
            int now = concurrentExecutions.incrementAndGet();
            maxConcurrentExecutions.updateAndGet(prev -> Math.max(prev, now));
            executionStarts.add(runId);
            try {
                holds.computeIfAbsent(runId, k -> new CountDownLatch(1)).await(60, TimeUnit.SECONDS);
                return new CoreResult("held-in-process", "done", new UsageSnapshot(10L, 5L, null, "held"), false);
            } finally {
                concurrentExecutions.decrementAndGet();
            }
        }).when(coreExecutionService).execute(any(), any(), any());
    }

    /** A failed test must never leave a run's engine thread parked on its hold past the method. */
    @AfterEach
    void releaseAllHolds() {
        holds.values().forEach(CountDownLatch::countDown);
    }

    @Test
    void overLimitRunsWaitPendingWithTodoCardsAndStartWhenASlotFrees() {
        Agent holderAgent = agentRepository.save(Agent.builder()
                .name("admission-holder-" + UUID.randomUUID())
                .agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY)
                .build());
        Agent waiterAgent = agentRepository.save(Agent.builder()
                .name("admission-waiter-" + UUID.randomUUID())
                .agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY)
                .build());

        // Run 1 first: its createdAt defines the FIFO head of the queue.
        UUID runId1 = runService.createRun(CreateRunRequest.builder()
                .agentId(holderAgent.getId())
                .promptSeed("admission cap holder")
                .build()).getId();

        // The holder must be genuinely inside execute() — holding the single
        // admission slot — before the over-limit run is created, so which run
        // owns the slot is decided by this test, not raced between two async
        // start signals. RUNNING and its card IN_PROGRESS confirm the engine's
        // admission start signal already moved the mirror card to the live column.
        await().atMost(Duration.ofSeconds(30)).until(() -> executionStarts.contains(runId1));
        await().atMost(Duration.ofSeconds(30)).until(() -> runStatus(runId1) == RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(cardStatuses(runId1)).isEqualTo(List.of(KanbanStatus.IN_PROGRESS)));

        // Run 2 over the limit: its start attempt parks in the admission queue.
        UUID runId2 = runService.createRun(CreateRunRequest.builder()
                .agentId(waiterAgent.getId())
                .promptSeed("admission cap waiter")
                .build()).getId();

        // The queued run stays PENDING with its mirror card in TODO — its
        // RunStartedEvent still creates the card, but no admission start signal
        // ever fires for it while it waits.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(runStatus(runId2)).isEqualTo(RunStatus.PENDING);
            assertThat(cardStatuses(runId2)).isEqualTo(List.of(KanbanStatus.TODO));
        });

        // Hold that observation for a settle window: the start attempt has had
        // ample time to reach the queue, and while the holder owns the only slot
        // the waiter must neither leave PENDING nor enter execute(). A removed
        // gate would have let it through immediately.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).until(() ->
                runStatus(runId2) == RunStatus.PENDING
                        && cardStatuses(runId2).equals(List.of(KanbanStatus.TODO))
                        && !executionStarts.contains(runId2));

        // Release the holder: its execute returns the canned result, the run
        // completes, and its terminal event settles the queue.
        release(runId1);
        await().atMost(Duration.ofSeconds(30)).until(() -> runStatus(runId1) == RunStatus.COMPLETED);

        // The freed slot starts the waiter automatically: it enters execute()
        // (second latch) and its card follows the admission start signal into
        // IN_PROGRESS.
        await().atMost(Duration.ofSeconds(30)).until(() -> executionStarts.contains(runId2));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(cardStatuses(runId2)).isEqualTo(List.of(KanbanStatus.IN_PROGRESS)));

        release(runId2);
        await().atMost(Duration.ofSeconds(30)).until(() -> runStatus(runId2) == RunStatus.COMPLETED);

        // Both runs terminal, both cards settled to REVIEW.
        assertThat(runStatus(runId1)).isEqualTo(RunStatus.COMPLETED);
        assertThat(runStatus(runId2)).isEqualTo(RunStatus.COMPLETED);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(cardStatuses(runId1)).isEqualTo(List.of(KanbanStatus.REVIEW));
            assertThat(cardStatuses(runId2)).isEqualTo(List.of(KanbanStatus.REVIEW));
        });

        // The cap, directly at the coordinator boundary: execute() was entered
        // in createdAt order and never by two runs at once.
        assertThat(executionStarts).containsExactly(runId1, runId2);
        assertThat(maxConcurrentExecutions.get()).isEqualTo(1);
    }

    private void release(UUID runId) {
        holds.computeIfAbsent(runId, k -> new CountDownLatch(1)).countDown();
    }

    private RunStatus runStatus(UUID runId) {
        return runRepository.findById(runId).map(run -> run.getStatus()).orElse(null);
    }

    private List<KanbanStatus> cardStatuses(UUID runId) {
        return kanbanRepository.findByLinkedRunId(runId.toString()).stream()
                .map(KanbanItem::getStatus)
                .toList();
    }
}
