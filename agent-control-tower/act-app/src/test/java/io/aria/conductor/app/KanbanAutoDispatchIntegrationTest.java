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
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
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
 * RunKanbanAutoCreator auto-creates a TODO card for every external run; the
 * card carries linkedRunId, and without a guard KanbanAutoDispatchListener
 * dispatched it, creating a duplicate second run for the same agent (double
 * LLM spend, orphaned first run). This test runs with auto-dispatch ENABLED —
 * the exact configuration the act-app test profile normally disables, which is
 * why the bug was invisible to the integration suite.
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

        // Exactly one card, linked to the ORIGINAL run, still sitting in Todo:
        // the card describes the run, it is not an operator dispatch intent.
        // The mirror runs on the kanban mirror executor once the creating
        // transaction has released its connection, so the card is awaited.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<KanbanItem> cards = kanbanRepository.findByLinkedRunId(run.getId().toString());
            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getStatus()).isEqualTo(KanbanStatus.TODO);
        });

        // Exactly one run exists for the agent, with the caller's prompt seed —
        // no "Kanban task: ..." duplicate.
        List<Run> agentRuns = runRepository.findAll().stream()
                .filter(r -> agent.getId().equals(r.getAgentId()))
                .toList();
        assertThat(agentRuns).hasSize(1);
        assertThat(agentRuns.get(0).getPromptSeed()).isEqualTo("external smoke run");
    }
}
