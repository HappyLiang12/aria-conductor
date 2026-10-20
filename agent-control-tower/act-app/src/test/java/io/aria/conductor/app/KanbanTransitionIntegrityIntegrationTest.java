package io.aria.conductor.app;

import io.aria.conductor.ActApplication;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.exception.PickupRejectedException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.kanban.KanbanTransitionService;
import io.aria.conductor.execution.kanban.TransitionRequest;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.UsageSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 2 of the kanban pickup-eligibility plan: a card must not be able to
 * enter a state that contradicts reality. Each gesture is driven against
 * the real install layout (the class carries its own in-memory database, no
 * cleanup script, so Flyway runs V1..V64 on an empty schema and
 * {@code AriaDefaultAgentInitializer} creates the Aria row) — the same wiring
 * {@link KanbanPickupEligibilityIntegrationTest} explains.
 *
 * <p>The dispatched agent carries the post-cutover core ({@code opencode} — the
 * V64 repair of the V42 SDD seeds), so the run is owned by the run coordinator:
 * {@code AgentLoopEngine} routes it through the real {@code CoreRunLauncher}
 * (admission, frozen binding) into {@code CoreExecutionService.execute}, and a
 * {@code @SpyBean CoreExecutionService} answers that call by blocking on a latch
 * until the test releases it, returning a canned {@link CoreResult}. The hold
 * replaces the run-owned attempt exactly where it would open the runtime and
 * create the sandbox — the Failsafe lane has no sandbox endpoint — so no launch
 * races the assertions and the run stays at {@code RUNNING} for the duration of
 * the test, the same way the now-retired ADK provider double (the pre-cutover
 * seam the V64 repair obsoleted) used to hold it inside the LLM call.
 *
 * <p>No existing act-app IT stubs a coordinated run (the closest patterns are
 * the observe-only {@code @SpyBean CoreRunLauncher} of
 * {@code AgentLoopInjectionIntegrationTest} / {@code OpenCodeTaskExecutionIntegrationTest}
 * and the object-level recording doubles of {@code CoreBindingPersistIntegrationTest}),
 * so this seam is test-local and documented here. Because the held attempt never
 * registers a run-owned runtime, the park's pause takes
 * {@code RunService.pauseRun}'s documented "no runtime owns it -> plain recorded
 * transition" branch deterministically. The verified pause of a coordinated run
 * with an open session is covered by {@code RunServiceTest} and
 * {@code CoordinatedRunRuntimeControlTest} in the unit lanes and end to end by
 * the Playwright kanban spec ({@code act-dashboard/e2e/kanban-board.spec.ts},
 * which waits for the recorded {@code RUNNING/BACKEND_SUSPEND} runtime state
 * before parking).
 *
 * <p>Runs in the Failsafe lane (the class name ends in IntegrationTest, which
 * Surefire excludes).
 */
@SpringBootTest(
        classes = {ActApplication.class, NoopLlmTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:kanban_transition_integrity;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles({"test", "noop-llm"})
class KanbanTransitionIntegrityIntegrationTest {

    @Autowired
    KanbanService kanbanService;
    @Autowired
    KanbanTransitionService kanbanTransitionService;
    @Autowired
    RunRepository runRepository;
    @Autowired
    KanbanRepository kanbanRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    ApprovalRepository approvalRepository;
    @Autowired
    ApplicationEventPublisher eventPublisher;

    @SpyBean
    CoreExecutionService coreExecutionService;

    private CountDownLatch holdExecution;

    /**
     * Hold every coordinated attempt the class dispatches at the coordinator's
     * {@code execute} boundary: the engine thread blocks there (the attempt
     * would open the runtime and create the sandbox inside it) while the run
     * stays {@code RUNNING}, and no runtime is registered, so the park's pause
     * deterministically takes the recorded-transition branch.
     */
    @BeforeEach
    void holdRunExecution() {
        holdExecution = new CountDownLatch(1);
        doAnswer(inv -> {
            holdExecution.await(30, TimeUnit.SECONDS);
            return new CoreResult("held-in-process", "done", new UsageSnapshot(10L, 5L, null, "held"), false);
        }).when(coreExecutionService).execute(any(), any(), any());
    }

    @AfterEach
    void releaseRunExecution() throws InterruptedException {
        holdExecution.countDown();
        // The released engine thread must fully unwind through the spied
        // execute before the next test re-stubs it: a stubbing started while
        // the previous invocation is still returning inside the interceptor
        // collides (UnfinishedStubbingException). The run reaching a terminal
        // state is exactly that unwind having finished.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            boolean anyActive = runRepository.findAll().stream().anyMatch(run -> {
                RunStatus status = run.getStatus();
                return status == RunStatus.PENDING || status == RunStatus.INITIALIZING
                        || status == RunStatus.RUNNING || status == RunStatus.PAUSED
                        || status == RunStatus.WAITING_INPUT;
            });
            if (!anyActive) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        // The class shares one agent pool across its tests: a test that made the
        // pool ineligible must leave it eligible again for the others.
        List<Agent> agents = agentRepository.findByHealthStatusNot(HealthStatus.RETIRED);
        agents.forEach(agent -> agent.setPickupEnabled(Boolean.TRUE));
        agentRepository.saveAll(agents);
    }

    @Test
    void parkedCardIsNotDraggedBackByRunIterations() {
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder().title("park me").build());
        KanbanItem running = kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());
        assertThat(running.getLinkedRunId()).isNotBlank();
        UUID runId = UUID.fromString(running.getLinkedRunId());
        awaitRunning(runId);

        KanbanItem parked = kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.TODO).build());

        assertThat(parked.getStatus()).isEqualTo(KanbanStatus.TODO);

        // The chain the parking gesture has to break: RunKanbanAutoCreator
        // resolves cards by linkedRunId and drags a TODO card whose link still
        // points at the iterating run back to IN_PROGRESS. The mirror runs on the
        // kanban mirror executor, so the card has to stay parked for a window
        // rather than merely at the instant after publishing.
        eventPublisher.publishEvent(new RunIterationEvent(this, runId,
                UUID.fromString(running.getLinkedAgentId()), 1, 5));

        await().during(Duration.ofSeconds(3)).until(() ->
                kanbanService.get(card.getId()).getStatus() == KanbanStatus.TODO);
        assertThat(parked.getLinkedRunId()).isNull();
        // A parked card must not leave its run burning behind it.
        assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.PAUSED);
    }

    @Test
    void reviewToInProgressOnAFinishedRunIsRejected() {
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder().title("finished").build());
        KanbanItem running = kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());
        UUID runId = UUID.fromString(running.getLinkedRunId());
        awaitRunning(runId);
        Run run = runRepository.findById(runId).orElseThrow();
        run.setStatus(RunStatus.COMPLETED);
        runRepository.save(run);
        kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.REVIEW).build());

        assertThatThrownBy(() -> kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code())
                        .isEqualTo("RUN_ALREADY_FINISHED"));

        assertThat(kanbanService.get(card.getId()).getStatus()).isEqualTo(KanbanStatus.REVIEW);
    }

    @Test
    void reopeningADoneCardClearsTheStaleRunLink() {
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder().title("redone").build());
        KanbanItem running = kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());
        UUID staleRunId = UUID.fromString(running.getLinkedRunId());
        awaitRunning(staleRunId);
        Run staleRun = runRepository.findById(staleRunId).orElseThrow();
        staleRun.setStatus(RunStatus.COMPLETED);
        runRepository.save(staleRun);
        kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.DONE).build());
        // Legacy blemish on the finished card: the agent link points at Aria, who
        // can never pick up a card. A re-open must re-assign from scratch instead
        // of handing the new attempt to that leftover link.
        KanbanItem finished = kanbanRepository.findById(card.getId()).orElseThrow();
        finished.setLinkedAgentId(AriaConstants.ARIA_AGENT_ID.toString());
        kanbanRepository.save(finished);

        KanbanItem reopened = kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.TODO).build());

        // DONE -> TODO is the documented redo: it re-enters the flow and dispatches
        // the fresh attempt (design 5.5), leaving the finished run as history.
        assertThat(reopened.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
        UUID freshRunId = UUID.fromString(reopened.getLinkedRunId());
        assertThat(freshRunId).isNotEqualTo(staleRunId);
        assertThat(runRepository.findById(freshRunId)).isPresent();
        assertThat(reopened.getLinkedAgentId()).isNotBlank()
                .isNotEqualTo(AriaConstants.ARIA_AGENT_ID.toString());
        assertThat(runRepository.findById(staleRunId).orElseThrow().getStatus())
                .isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void rejectedReopenRollsBackTheDoneCardAndItsLinks() {
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder().title("redo rollback").build());
        KanbanItem running = kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());
        UUID finishedRunId = UUID.fromString(running.getLinkedRunId());
        awaitRunning(finishedRunId);
        Run finishedRun = runRepository.findById(finishedRunId).orElseThrow();
        finishedRun.setStatus(RunStatus.COMPLETED);
        runRepository.save(finishedRun);
        kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.DONE).build());

        KanbanItem done = kanbanRepository.findById(card.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(KanbanStatus.DONE);
        assertThat(done.getLinkedRunId()).isEqualTo(finishedRunId.toString());
        String doneAgentId = done.getLinkedAgentId();
        assertThat(doneAgentId).isNotBlank();

        disablePickupEligibility();
        assertThatThrownBy(() -> kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.TODO).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code())
                        .isIn("NO_ELIGIBLE_AGENT", "AGENT_NOT_ELIGIBLE"));

        // A synchronous rejection must answer 4xx with the state unchanged: the
        // re-open clears the links and moves the card to TODO before it dispatches,
        // so a rollback regression that committed would leave a half-moved card
        // here. Re-read through the repository, never the instance held above.
        KanbanItem after = kanbanRepository.findById(card.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(KanbanStatus.DONE);
        assertThat(after.getLinkedRunId()).isEqualTo(finishedRunId.toString());
        assertThat(after.getLinkedAgentId()).isEqualTo(doneAgentId);
    }

    @Test
    void rejectedRequestChangesRollsBackTheReviewCardAndItsLinks() {
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder().title("changes rollback").build());
        KanbanItem running = kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());
        UUID runId = UUID.fromString(running.getLinkedRunId());
        awaitRunning(runId);
        kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.REVIEW).build());

        KanbanItem review = kanbanRepository.findById(card.getId()).orElseThrow();
        assertThat(review.getStatus()).isEqualTo(KanbanStatus.REVIEW);
        assertThat(review.getLinkedRunId()).isEqualTo(runId.toString());
        String reviewAgentId = review.getLinkedAgentId();
        assertThat(reviewAgentId).isNotBlank();

        disablePickupEligibility();
        assertThatThrownBy(() -> kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.TODO)
                .feedback("redo with the review notes")
                .build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code())
                        .isIn("NO_ELIGIBLE_AGENT", "AGENT_NOT_ELIGIBLE"));

        KanbanItem after = kanbanRepository.findById(card.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(KanbanStatus.REVIEW);
        assertThat(after.getLinkedRunId()).isEqualTo(runId.toString());
        assertThat(after.getLinkedAgentId()).isEqualTo(reviewAgentId);
    }

    @Test
    void acceptingAReviewCardSettlesLegacyAsksButNeverNativeOnes() {
        // Born in REVIEW (a creatable birth state): the TODO -> REVIEW hop does
        // not exist in the state machine, and no run link is needed — the sweep
        // keys on the card id alone.
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder()
                .title("native negative")
                .status(KanbanStatus.REVIEW)
                .build());
        // approvals.run_id carries an FK to runs, so the asks need a real run row.
        Run run = runRepository.save(Run.builder()
                .agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.COMPLETED)
                .build());

        // Two PENDING asks on the card: the legacy gate ask (builder default
        // source) that the card sweep owns, and a V60 ACP permission ask that is
        // owned by AcpPermissionCoordinator and must never be decided here.
        Approval legacyAsk = approvalRepository.save(Approval.builder()
                .runId(run.getId())
                .status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .askType(Approval.AskType.REVIEW_REQUEST)
                .kanbanItemId(card.getId())
                .contextMd("legacy review ask")
                .build());
        Approval nativeAsk = approvalRepository.save(Approval.builder()
                .runId(run.getId())
                .status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .askType(Approval.AskType.APPROVAL)
                .kanbanItemId(card.getId())
                .contextMd("native permission ask")
                .source(ApprovalSource.ACP_PERMISSION)
                .build());

        kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.DONE).build());

        Approval settledLegacy = approvalRepository.findById(legacyAsk.getId()).orElseThrow();
        assertThat(settledLegacy.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(settledLegacy.getReason()).isEqualTo("accepted by card decision");
        assertThat(settledLegacy.getDecidedAt()).isNotNull();

        Approval untouchedNative = approvalRepository.findById(nativeAsk.getId()).orElseThrow();
        assertThat(untouchedNative.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(untouchedNative.getDecidedAt()).isNull();
    }

    /**
     * Make the pickup pool deterministically unable to serve — every non-retired
     * agent loses pickup eligibility — so the dispatch inside a re-open rejects
     * through the real eligibility path instead of depending on timing.
     */
    private void disablePickupEligibility() {
        List<Agent> agents = agentRepository.findByHealthStatusNot(HealthStatus.RETIRED);
        agents.forEach(agent -> agent.setPickupEnabled(Boolean.FALSE));
        agentRepository.saveAll(agents);
    }

    private void awaitRunning(UUID runId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> runRepository.findById(runId)
                .map(run -> run.getStatus() == RunStatus.RUNNING)
                .orElse(false));
    }
}
