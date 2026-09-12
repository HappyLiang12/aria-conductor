package io.aria.conductor.app;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanPriority;
import io.aria.conductor.execution.kanban.KanbanRepository;
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
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration coverage for the kanban pickup orchestrator (plan section 7):
 * with real beans (orchestrator, kanban service, run service, listeners) and
 * only the ADK provider mocked, dragging an unassigned TODO card to
 * IN_PROGRESS must pick the healthy agent, create the run, link it onto the
 * card, and land the card in IN_PROGRESS — with EXACTLY ONE card for that run
 * (the pickup's suppressAutoCard flag stops RunKanbanAutoCreator from
 * double-carding the board).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KanbanPickupIntegrationTest extends BaseH2IntegrationTest {

    @Autowired KanbanTransitionService kanbanTransitionService;
    @Autowired KanbanRepository kanbanRepository;
    @Autowired AgentRepository agentRepository;
    @Autowired RunRepository runRepository;

    @MockBean AdkProviderRegistry adkProviderRegistry;
    private AdkProvider adkProvider;
    private CountDownLatch holdExecution;

    @BeforeEach
    void setupAdk() {
        adkProvider = Mockito.mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(adkProvider);
        when(adkProvider.isHealthy(any())).thenReturn(true);
        // Hold the engine inside the LLM call so the run stays RUNNING for the
        // assertions instead of racing to COMPLETED mid-test.
        holdExecution = new CountDownLatch(1);
        when(adkProvider.call(any(), any(), any(), any())).thenAnswer(inv -> {
            holdExecution.await(30, TimeUnit.SECONDS);
            return new LlmResponse("done", 10, 5, "stop", null);
        });
        when(adkProvider.parseActionsFromResponse(any())).thenReturn(List.of());
    }

    @AfterEach
    void releaseExecution() {
        // Let the held engine call finish so no engine thread outlives the class.
        holdExecution.countDown();
    }

    @Test
    void pickup_movesCardToInProgress_linksRunAndCreatesExactlyOneCard() {
        // --- seed: the only healthy agent + an unassigned TODO card ---
        Agent agent = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID()).name("pickup-agent").description("kanban pickup test")
                .agentType(AgentType.NATIVE).role("tester").model("gpt-4o-mini")
                .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
        KanbanItem card = kanbanRepository.save(KanbanItem.builder()
                .title("add CSV export")
                .description("export the quarterly report")
                .status(KanbanStatus.TODO)
                .priority(KanbanPriority.MEDIUM)
                .build());

        // --- act: dispatch intent through the real orchestrator ---
        KanbanItem result = kanbanTransitionService.transition(card.getId(),
                TransitionRequest.builder().status(KanbanStatus.IN_PROGRESS).build());

        // --- card moved, assigned and linked ---
        assertThat(result.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
        assertThat(result.getAssignee()).isEqualTo("pickup-agent");
        assertThat(result.getLinkedAgentId()).isEqualTo(agent.getId().toString());
        assertThat(result.getLinkedRunId()).isNotBlank();
        assertThat(result.getLastError()).isNull();

        // --- exactly ONE card for that agent/run: no duplicate auto-card ---
        List<KanbanItem> cardsForRun = kanbanRepository.findByLinkedRunId(result.getLinkedRunId());
        assertThat(cardsForRun).hasSize(1);
        assertThat(cardsForRun.get(0).getId()).isEqualTo(card.getId());

        // --- the run row exists and is executing (engine starts it post-commit) ---
        UUID runId = UUID.fromString(result.getLinkedRunId());
        await().atMost(Duration.ofSeconds(20))
                .until(() -> runRepository.findById(runId)
                        .map(r -> r.getStatus() == RunStatus.RUNNING
                                || r.getStatus() == RunStatus.INITIALIZING)
                        .orElse(false));
        Run run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isIn(RunStatus.PENDING, RunStatus.INITIALIZING, RunStatus.RUNNING);
        assertThat(run.getAgentId()).isEqualTo(agent.getId());
        assertThat(run.getPromptSeed()).contains("add CSV export");
    }
}
