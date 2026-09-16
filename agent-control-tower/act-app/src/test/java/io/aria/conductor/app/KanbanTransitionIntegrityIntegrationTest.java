package io.aria.conductor.app;

import io.aria.conductor.ActApplication;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.exception.PickupRejectedException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.kanban.KanbanTransitionService;
import io.aria.conductor.execution.kanban.TransitionRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.Mockito.when;

/**
 * Phase 2 of the kanban pickup-eligibility plan: a card must not be able to
 * enter a state that contradicts reality. Each gesture is driven against
 * the real install layout (the class carries its own in-memory database, no
 * cleanup script, so Flyway runs V1..V57 on an empty schema and
 * {@code AriaDefaultAgentInitializer} creates the Aria row) — the same wiring
 * {@link KanbanPickupEligibilityIntegrationTest} explains.
 *
 * <p>Only the ADK provider is mocked, held inside the LLM call, so each
 * dispatched run stays at {@code RUNNING} for the duration of the test instead
 * of racing to a terminal state behind the assertions. That is what makes
 * "the parked card's run is actually stopped" observable rather than a timing
 * coincidence; the agent pool, the picker, the run service and the listeners
 * are the real beans.
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
    ApplicationEventPublisher eventPublisher;

    @MockBean
    AdkProviderRegistry adkProviderRegistry;

    private CountDownLatch holdExecution;

    @BeforeEach
    void holdRunExecution() {
        AdkProvider adkProvider = Mockito.mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(adkProvider);
        when(adkProvider.isHealthy(any())).thenReturn(true);
        when(adkProvider.parseActionsFromResponse(any())).thenReturn(List.of());
        holdExecution = new CountDownLatch(1);
        when(adkProvider.call(any(), any(), any(), any())).thenAnswer(inv -> {
            holdExecution.await(30, TimeUnit.SECONDS);
            return new LlmResponse("done", 10, 5, "stop", null);
        });
    }

    @AfterEach
    void releaseRunExecution() {
        holdExecution.countDown();
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
        // points at the iterating run back to IN_PROGRESS.
        eventPublisher.publishEvent(new RunIterationEvent(this, runId,
                UUID.fromString(running.getLinkedAgentId()), 1, 5));

        assertThat(kanbanService.get(card.getId()).getStatus()).isEqualTo(KanbanStatus.TODO);
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
