package io.aria.conductor.agent.repository;

import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 fix round 1 (m1): {@code total_tokens_used} is nullable in the shipped
 * schema (V1: {@code BIGINT DEFAULT 0}, no NOT NULL), while the entity maps it
 * primitive. A row whose column is NULL must still land in exactly one bucket:
 * counted as unreported when it carries no parts, and summed when it does.
 *
 * <p>The {@code @DataJpaTest} schema is generated from the entity, which makes the
 * column NOT NULL; the test relaxes it to the production schema before inserting
 * the NULL rows. DDL commits, so the context is dirtied after the class to keep
 * the schema change and its rows out of every other slice.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NullTotalUsageAggregateTest extends DataJpaTestBase {

    @Autowired
    RunRepository runs;

    @Autowired
    DataSource dataSource;

    @Test
    void aNullTotalIsCountedWhenSilentAndSummedWhenItCarriesParts() throws Exception {
        relaxTotalColumnToTheShippingSchema();
        insert(UUID.randomUUID(), null, null);   // NULL total, no parts: a silence
        insert(UUID.randomUUID(), 50L, null);    // NULL total, positive part: a report
        flushAndClear();

        assertThat(runs.countWithoutReportedUsage())
                .as("the silent NULL-total row is counted, not dropped")
                .isEqualTo(1L);
        assertThat(runs.sumReportedUsage())
                .as("the parts-only NULL-total row contributes its parts")
                .isEqualTo(50L);
    }

    private void relaxTotalColumnToTheShippingSchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE runs ALTER COLUMN total_tokens_used SET NULL");
        }
    }

    private void insert(UUID id, Long inputTokens, Long outputTokens) {
        entityManager.createNativeQuery("INSERT INTO runs (id, agent_id, status, total_tokens_used, "
                        + "input_tokens, output_tokens, max_iterations, iteration_count, created_at) "
                        + "VALUES (CAST(:id AS UUID), CAST(:agentId AS UUID), 'COMPLETED', NULL, :input, "
                        + ":output, 50, 0, CURRENT_TIMESTAMP)")
                .setParameter("id", id.toString())
                .setParameter("agentId", UUID.randomUUID().toString())
                .setParameter("input", inputTokens)
                .setParameter("output", outputTokens)
                .executeUpdate();
    }
}
