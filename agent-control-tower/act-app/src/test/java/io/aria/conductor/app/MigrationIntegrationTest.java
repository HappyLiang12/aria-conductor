package io.aria.conductor.app;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Flyway migration chain against drift: every migration must be applied and
 * successful on a fresh H2 (MODE=MySQL) database, validation must pass (no checksum
 * mismatches against the classpath scripts), and the core tables the application relies
 * on must actually exist.
 */
@Import(NoopLlmTestConfig.class)
class MigrationIntegrationTest extends BaseH2IntegrationTest {

    private static final List<String> KEY_TABLES = List.of(
            "agents", "runs", "approvals", "knowledge_items", "workflow_chains", "tool_definitions",
            "runtime_credentials", "acp_permission_request");

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void allMigrations_areAppliedAndSuccessful_withNonePending() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).as("at least the baseline schema migration must be applied").isNotEmpty();
        for (MigrationInfo info : applied) {
            assertThat(info.getState())
                    .as("migration %s (%s) must be SUCCESS", info.getVersion(), info.getDescription())
                    .isEqualTo(MigrationState.SUCCESS);
        }
        assertThat(flyway.info().pending())
                .as("no migration may be left pending after startup")
                .isEmpty();
    }

    @Test
    void validation_passes_withoutChecksumMismatches() {
        var result = flyway.validateWithResult();
        assertThat(result.validationSuccessful)
                .as("flyway validate must succeed: %s", result.getAllErrorMessages())
                .isTrue();
        assertThat(result.invalidMigrations).isEmpty();
    }

    @Test
    void keyTables_existAndAreQueryable() {
        for (String table : KEY_TABLES) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count).as("table %s must exist and be queryable", table).isNotNull();
        }
    }

    /**
     * V60: approvals gain a provenance column. Legacy rows (created before the migration) must
     * read back as LEGACY_GATE, so the column is NOT NULL with a database-level default — the
     * entity-side default alone cannot cover rows written by raw SQL or older code.
     */
    @Test
    void approvalsSourceColumn_existsWithLegacyGateDefault() {
        String columnDefault = jdbcTemplate.queryForObject(
                "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE UPPER(TABLE_NAME) = 'APPROVALS' AND UPPER(COLUMN_NAME) = 'SOURCE'",
                String.class);
        assertThat(columnDefault)
                .as("approvals.source must default to LEGACY_GATE for pre-existing rows")
                .contains("LEGACY_GATE");

        String isNullable = jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE UPPER(TABLE_NAME) = 'APPROVALS' AND UPPER(COLUMN_NAME) = 'SOURCE'",
                String.class);
        assertThat(isNullable).as("approvals.source must be NOT NULL").isEqualTo("NO");
    }
}
