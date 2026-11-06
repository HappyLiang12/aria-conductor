package io.aria.conductor.app;

import io.aria.conductor.ActApplication;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.kanban.KanbanTransitionService;
import io.aria.conductor.execution.kanban.TransitionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The regression test for the reported defect: on a fresh install the only
 * agents are the three seeded SDD role agents plus the reserved Aria assistant,
 * and dragging a card out of Todo used to bounce with a message blaming health.
 * With the eligibility authority in place the seeded agents are eligible, so a
 * dispatch produces a real run.
 *
 * <p>The agent set is produced by the real install path, not a fixture: the
 * class carries its own in-memory database (unique name, no cleanup script) so
 * Flyway runs V1..V57 against an empty schema and
 * {@code AriaDefaultAgentInitializer} creates the Aria row at boot — exactly
 * what an operator's fresh install does. Deleting a seeded role agent, changing
 * one's {@code health_status}, or flipping V56's {@code pickup_enabled} default
 * back to FALSE therefore fails this test instead of passing green.
 * ({@link BaseH2IntegrationTest}'s {@code cleanup-all.sql} cannot be used here:
 * it empties {@code agents} before the class, which would hide the seed's data
 * effect — the same reason {@link V43SeedConfigTest} and
 * {@link V55SeedToolSchemaTest} stand up their own datasource.)
 *
 * <p>Runs in the Failsafe lane (the class name ends in IntegrationTest, which
 * Surefire excludes).
 */
@SpringBootTest(
        classes = {ActApplication.class, NoopLlmTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:kanban_pickup_fresh;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles({"test", "noop-llm"})
class KanbanPickupEligibilityIntegrationTest {

    @Autowired
    KanbanService kanbanService;
    @Autowired
    KanbanTransitionService kanbanTransitionService;
    @Autowired
    RunRepository runRepository;

    @Test
    void seededOnlyInstallDispatchesTodoToInProgress() {
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder()
                .title("fresh install dispatch").build());
        long before = runRepository.count();

        KanbanItem moved = kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        assertThat(moved.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
        assertThat(moved.getLinkedAgentId()).isNotBlank();
        // Aria sorts first if she ever slips into the eligible pool, and her row is
        // pickup_enabled=TRUE, so only the reserved-agent rule keeps her out.
        assertThat(moved.getLinkedAgentId()).isNotEqualTo(AriaConstants.ARIA_AGENT_ID.toString());
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
