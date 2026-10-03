package io.aria.conductor.app;

import io.aria.conductor.ActApplication;
import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Regression for the auto-dispatch duplication bug (code review, PR #79):
 * RunKanbanAutoCreator auto-creates a mirror card for every external run; the
 * card carries linkedRunId, and without a guard KanbanAutoDispatchListener
 * dispatched it, creating a duplicate second run for the same agent (double
 * LLM spend, orphaned first run). This test runs with auto-dispatch ENABLED —
 * the exact configuration the act-app test profile normally disables, which is
 * why the bug was invisible to the integration suite.
 *
 * <p>The mirror card is born IN_PROGRESS — the run is already dispatched when
 * RunStartedEvent fires, so the board never reads it as an undispatched Todo
 * card — and it settles to REVIEW once the run-owned attempt ends (the test
 * profile points the sandbox core at a closed port, so the attempt fails fast
 * and FAILED settles to REVIEW exactly like COMPLETED). The second test pins
 * the PR #79 guard directly: a TODO card that already carries a linkedRunId
 * describes an existing run and must never be auto-dispatched.
 */
@SpringBootTest(
        classes = {ActApplication.class, NoopLlmTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aria.kanban.auto-dispatch-on-create=true")
@ActiveProfiles({"test", "noop-llm"})
@Sql(scripts = "classpath:db/cleanup-all.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class KanbanAutoDispatchIntegrationTest {

    @Autowired
    private RunService runService;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private RunRepository runRepository;
    @Autowired
    private KanbanRepository kanbanRepository;
    @Autowired
    private KanbanService kanbanService;

    @Test
    void externalRunIsNotDuplicatedByAutoDispatch() {
        Agent agent = agentRepository.save(Agent.builder()
                .name("auto-dispatch-guard-" + UUID.randomUUID())
                .agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY)
                .build());

        RunResponse run = runService.createRun(CreateRunRequest.builder()
                .agentId(agent.getId())
                .promptSeed("external smoke run")
                .build());

        // Exactly one card, linked to the ORIGINAL run. It is born IN_PROGRESS
        // (the run is dispatched the moment it is created — never a Todo card
        // that reads as an undispatched intent) and settles to REVIEW when the
        // run-owned attempt ends: the test profile's sandbox endpoint is closed,
        // so the attempt fails fast and FAILED lands in REVIEW exactly like
        // COMPLETED. The mirror runs on the kanban mirror executor once the
        // creating transaction has released its connection, so both states are
        // awaited — the settlement is the end state asserted here.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<KanbanItem> cards = kanbanRepository.findByLinkedRunId(run.getId().toString());
            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getStatus())
                    .isIn(KanbanStatus.IN_PROGRESS, KanbanStatus.REVIEW);
        });
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<KanbanItem> cards = kanbanRepository.findByLinkedRunId(run.getId().toString());
            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getStatus()).isEqualTo(KanbanStatus.REVIEW);
        });

        // Exactly one run exists for the agent, with the caller's prompt seed —
        // no "Kanban task: ..." duplicate.
        List<Run> agentRuns = runRepository.findAll().stream()
                .filter(r -> agent.getId().equals(r.getAgentId()))
                .toList();
        assertThat(agentRuns).hasSize(1);
        assertThat(agentRuns.get(0).getPromptSeed()).isEqualTo("external smoke run");
    }

    /**
     * The PR #79 guard, pinned directly: the auto-dispatch listener skips a card
     * on its run link alone, even when the card's status is the dispatch intent's
     * TODO. Without the guard this card would create a duplicate second run for
     * the linked agent.
     */
    @Test
    void runLinkedTodoCardIsNeverAutoDispatched() {
        Agent agent = agentRepository.save(Agent.builder()
                .name("linked-todo-guard-" + UUID.randomUUID())
                .agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY)
                .build());
        RunResponse run = runService.createRun(CreateRunRequest.builder()
                .agentId(agent.getId())
                .promptSeed("guard the run link")
                .build());

        // A TODO card naming the existing run — the shape every mirror card had
        // before the IN_PROGRESS birth, and a shape a future regression could
        // reintroduce. The creation publishes KanbanItemCreatedEvent, which the
        // auto-dispatch listener consumes.
        KanbanItem linkedTodo = kanbanService.create(CreateKanbanItemRequest.builder()
                .title("late mirror of " + run.getId())
                .status(KanbanStatus.TODO)
                .linkedRunId(run.getId().toString())
                .linkedAgentId(agent.getId().toString())
                .build());
        assertThat(linkedTodo.getStatus()).isEqualTo(KanbanStatus.TODO);

        // Hold that observation for a settle window: the card must stay in TODO
        // and the agent must still own exactly the one run the caller created.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).until(() ->
                kanbanRepository.findById(linkedTodo.getId())
                        .map(item -> item.getStatus() == KanbanStatus.TODO)
                        .orElse(false)
                        && runRepository.findAll().stream()
                                .filter(r -> agent.getId().equals(r.getAgentId()))
                                .count() == 1);
    }
}
