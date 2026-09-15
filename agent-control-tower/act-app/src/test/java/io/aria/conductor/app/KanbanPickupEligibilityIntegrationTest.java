package io.aria.conductor.app;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.kanban.KanbanTransitionService;
import io.aria.conductor.execution.kanban.TransitionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The regression test for the reported defect: on a fresh install the only
 * agents are the three seeded SDD role agents plus the reserved Aria assistant,
 * and dragging a card out of Todo used to bounce with a message blaming health.
 * With the eligibility authority in place the seeded agents are eligible, so a
 * dispatch produces a real run.
 *
 * <p>{@link BaseH2IntegrationTest} truncates {@code agents} before this class
 * starts, which erases the install's own agent set; {@link #seedFreshInstallAgents}
 * puts exactly that set back so the class runs against the agents an operator's
 * fresh install has — and nothing else.
 *
 * <p>Runs in the Failsafe lane (the class name ends in IntegrationTest, which
 * Surefire excludes).
 */
@ActiveProfiles({"test", "noop-llm"})
class KanbanPickupEligibilityIntegrationTest extends BaseH2IntegrationTest {

    /** Ids, roles and provider are the ones V42__seed_sdd_role_agents.sql writes. */
    private static final UUID SEEDED_BA_AGENT_ID = UUID.fromString("ba000000-0000-0000-0000-000000000001");
    private static final UUID SEEDED_DEV_AGENT_ID = UUID.fromString("de000000-0000-0000-0000-000000000002");
    private static final UUID SEEDED_QA_AGENT_ID = UUID.fromString("aa000000-0000-0000-0000-000000000003");

    /** The config V43__fix_sdd_seed_configs.sql gives the seeded role agents. */
    private static final String TASK_CONFIG = "{\"taskApprovalRequired\": false, \"maxToolCallRounds\": 15}";

    @Autowired
    KanbanService kanbanService;
    @Autowired
    KanbanTransitionService kanbanTransitionService;
    @Autowired
    RunRepository runRepository;
    @Autowired
    AgentRepository agentRepository;

    @BeforeEach
    void seedFreshInstallAgents() {
        seedAgent(SEEDED_BA_AGENT_ID, "SDD BA Agent", "ba");
        seedAgent(SEEDED_DEV_AGENT_ID, "SDD DEV Agent", "dev");
        seedAgent(SEEDED_QA_AGENT_ID, "SDD QA Agent", "qa");
        seedAgent(AriaConstants.ARIA_AGENT_ID, "Aria", "assistant");
    }

    private void seedAgent(UUID id, String name, String role) {
        if (agentRepository.findById(id).isPresent()) {
            return;
        }
        agentRepository.save(Agent.builder()
                .id(id)
                .name(name)
                .role(role)
                .agentType(AgentType.NATIVE)
                .adkProvider("langchain")
                .config(TASK_CONFIG)
                .pickupEnabled(true)
                .healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now())
                .build());
    }

    @Test
    void seededOnlyInstallDispatchesTodoToInProgress() {
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder()
                .title("fresh install dispatch").build());
        long before = runRepository.count();

        KanbanItem moved = kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        assertThat(moved.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
        assertThat(moved.getLinkedAgentId()).isNotBlank();
        assertThat(moved.getLinkedRunId()).isNotBlank();
        assertThat(runRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void createRejectsTerminalBirthStatus() {
        assertThatThrownBy(() -> kanbanService.create(CreateKanbanItemRequest.builder()
                .title("born done").status(KanbanStatus.DONE).build()))
                .hasMessageContaining("INVALID_BIRTH_STATUS");
    }

    @Test
    void createRejectsMalformedRunLink() {
        assertThatThrownBy(() -> kanbanService.create(CreateKanbanItemRequest.builder()
                .title("bad link").linkedRunId("not-a-uuid").build()))
                .hasMessageContaining("INVALID_RUN_LINK");
    }
}
