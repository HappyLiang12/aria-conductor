package io.aria.conductor.app;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards V74 on a fresh database: the part identity of a progress observation must
 * exist, be nullable, and survive the ordered replay the dashboard reads. Two
 * observations of ONE part are the shape this migration exists for, so they are
 * stored side by side with the same {@code part_id} and different {@code seq} —
 * nothing upserts them, the client merges.
 *
 * <p>The script is also re-applied directly, the way a Flyway {@code repair} after a
 * half-run migration would: a non-idempotent ALTER would block every later startup on
 * a duplicate column (the V72 precedent).
 */
class V74RunProgressPartIdentityMigrationTest {

    private static Flyway flyway;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFreshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v74partidentity;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void v74IsAppliedByAFreshDatabaseAndNothingIsPending() {
        // V74 is no longer the head once a later migration (V75) exists, so this asserts
        // the durable fact a head check stood for: the migration was applied successfully,
        // so an existing database actually reaches it, and nothing is left pending.
        assertThat(flyway.info().applied())
                .anySatisfy(info -> {
                    assertThat(info.getVersion().getVersion()).isEqualTo("74");
                    assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS);
                });
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void partIdColumnExistsNullableAndWideEnoughForANativePartId() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, is_nullable, data_type, character_maximum_length"
                        + " FROM information_schema.columns"
                        + " WHERE table_name = 'RUN_PROGRESS_EVENTS' AND column_name = 'PART_ID'");

        assertThat(columns).as("run_progress_events.part_id exists after V74").hasSize(1);
        Map<String, Object> column = columns.get(0);
        assertThat(column.get("IS_NULLABLE")).as("nullable: pre-V74 rows keep their own identity")
                .isEqualTo("YES");
        assertThat(((Number) column.get("CHARACTER_MAXIMUM_LENGTH")).intValue())
                .as("a native part id is stored whole")
                .isEqualTo(128);
    }

    @Test
    void twoObservationsOfOnePartAreStoredSideBySideAndReplayInSeqOrder() {
        UUID runId = UUID.randomUUID();
        insert(runId, 1, "ANSWER", "Distributed consensus begins with Raft,", "prt_growing");
        insert(runId, 2, "ANSWER", "Distributed consensus begins with Raft, and needs a majority.",
                "prt_growing");
        insert(runId, 3, "TOOL_RESULT", "3 tests passed", "prt_tool");

        assertThat(jdbc.queryForList(
                "SELECT part_id FROM run_progress_events WHERE run_id = ? AND seq = 1", String.class, runId))
                .containsExactly("prt_growing");
        // The ordered replay the REST backlog serves: same part, greater seq, both rows.
        // The projection is two columns, so it comes back as one map per row (a
        // single-column queryForList rejects a two-column result set) and the
        // (part_id, seq) pair is asserted per row in replay order.
        assertThat(jdbc.queryForList(
                "SELECT part_id, seq FROM run_progress_events WHERE run_id = ? ORDER BY seq ASC",
                runId))
                .extracting(row -> row.get("part_id") + "#" + row.get("seq"))
                .containsExactly("prt_growing#1", "prt_growing#2", "prt_tool#3");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT part_id) FROM run_progress_events WHERE run_id = ?",
                Long.class, runId))
                .as("three observations of two parts")
                .isEqualTo(2L);
    }

    /**
     * A row with no part id is the pre-V74 shape and the shape of a core that reports
     * no id: it must insert and read back as NULL, never be rejected by a NOT NULL the
     * migration forgot to leave open.
     */
    @Test
    void aRowWithoutAPartIdStillInsertsAndReadsBackNull() {
        UUID runId = UUID.randomUUID();
        insert(runId, 1, "THINKING", "historical fragment", null);

        assertThat(jdbc.queryForList(
                "SELECT part_id FROM run_progress_events WHERE run_id = ? AND seq = 1", String.class, runId))
                .containsOnlyNulls();
    }

    @Test
    void reapplyingTheScriptIsIdempotent() throws SQLException {
        applyV74();
        applyV74();

        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_name = 'RUN_PROGRESS_EVENTS' AND column_name = 'PART_ID'"))
                .hasSize(1);
    }

    private void insert(UUID runId, long seq, String kind, String content, String partId) {
        jdbc.update("INSERT INTO run_progress_events"
                        + " (id, run_id, agent_id, iteration, kind, seq, content, tool_name, created_at, part_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)",
                UUID.randomUUID(), runId, UUID.randomUUID(), 1, kind, seq, content, null, partId);
    }

    private void applyV74() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V74__run_progress_part_identity.sql"));
        }
    }
}
