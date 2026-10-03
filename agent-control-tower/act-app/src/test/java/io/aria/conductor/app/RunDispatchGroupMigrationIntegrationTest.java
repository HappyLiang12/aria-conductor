package io.aria.conductor.app;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V65 dispatch-group schema end to end:
 * <ul>
 *   <li>the migration itself: {@code runs.dispatched_by_run_id} exists, is nullable
 *       (every pre-existing run reads back NULL) and carries the lookup index;</li>
 *   <li>the linkage round-trips through the entity and the derived finder
 *       {@link RunRepository#findByDispatchedByRunId(UUID)} (the completed-batch
 *       wake-up reads children by the dispatching run);</li>
 *   <li>null-safety: runs written without a dispatcher (as pre-V65 code wrote them)
 *       still read back cleanly with a NULL linkage.</li>
 * </ul>
 * Children NEVER carry conversation_id: the timeline/context select by it, so the
 * dispatch-group linkage is the only column this migration adds.
 */
class RunDispatchGroupMigrationIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    AgentRepository agentRepository;

    @Autowired
    RunRepository runRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void v65AddsNullableDispatchedByRunIdColumnAndIndex() {
        List<String> nullability = jdbcTemplate.queryForList(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = 'RUNS' AND UPPER(COLUMN_NAME) = 'DISPATCHED_BY_RUN_ID'",
                String.class);
        assertThat(nullability)
                .as("runs.dispatched_by_run_id must exist exactly once and be nullable")
                .containsExactly("YES");

        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE UPPER(TABLE_NAME) = 'RUNS' AND UPPER(INDEX_NAME) = 'IDX_RUNS_DISPATCHED_BY'",
                Integer.class);
        assertThat(indexCount)
                .as("index idx_runs_dispatched_by must exist on runs")
                .isEqualTo(1);
    }

    @Test
    void dispatchedByRunIdRoundTripsThroughFinder() {
        Agent agent = createAgent("dispatch-group-roundtrip-agent");
        Run dispatcher = runRepository.saveAndFlush(Run.builder()
                .agentId(agent.getId())
                .status(RunStatus.RUNNING)
                .build());
        Run child = runRepository.saveAndFlush(Run.builder()
                .agentId(agent.getId())
                .status(RunStatus.RUNNING)
                .dispatchedByRunId(dispatcher.getId())
                .build());

        List<Run> found = runRepository.findByDispatchedByRunId(dispatcher.getId());
        assertThat(found).extracting(Run::getId).containsExactly(child.getId());
        assertThat(found.get(0).getDispatchedByRunId()).isEqualTo(dispatcher.getId());

        // The linkage is really in the row, not only in the persistence context.
        Integer persistedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM runs WHERE dispatched_by_run_id = CAST(? AS UUID)",
                Integer.class, dispatcher.getId().toString());
        assertThat(persistedRows).isEqualTo(1);

        // The dispatcher is not its own child, and the child has no children.
        assertThat(runRepository.findByDispatchedByRunId(child.getId())).isEmpty();
    }

    @Test
    void runsWithoutDispatchedByRunIdReadBackNullSafe() {
        Agent agent = createAgent("dispatch-group-null-agent");

        // A row written the way pre-V65 code wrote runs: no dispatched_by_run_id at all.
        UUID legacyRunId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO runs (id, agent_id, status, created_at) "
                        + "VALUES (CAST(? AS UUID), CAST(? AS UUID), 'COMPLETED', CURRENT_TIMESTAMP)",
                legacyRunId.toString(), agent.getId().toString());

        Run legacy = runRepository.findById(legacyRunId).orElseThrow();
        assertThat(legacy.getDispatchedByRunId()).isNull();
        assertThat(runRepository.findByDispatchedByRunId(legacyRunId)).isEmpty();

        // An entity-built run that simply never sets the field also reads back NULL.
        Run plain = runRepository.saveAndFlush(Run.builder()
                .agentId(agent.getId())
                .status(RunStatus.PENDING)
                .build());
        assertThat(runRepository.findById(plain.getId()).orElseThrow().getDispatchedByRunId()).isNull();
    }

    private Agent createAgent(String name) {
        return agentRepository.saveAndFlush(Agent.builder()
                .name(name)
                .agentType(AgentType.NATIVE)
                .adkProvider("opencode")
                .healthStatus(HealthStatus.HEALTHY)
                .pickupEnabled(Boolean.TRUE)
                .build());
    }
}
