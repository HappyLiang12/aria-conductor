package io.aria.conductor.app;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
            "core_credentials", "acp_permission_request");

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
     * V69 retires the legacy execution surface. The migration is replayed against
     * seeded rows inside a rolled-back transaction so the postconditions are proven,
     * not inferred from an empty fresh database. Critically, live operator state
     * (REVIEW_REQUEST, CLARIFICATION and ACP asks) must survive the upgrade.
     */
    @Test
    @Transactional
    void v69_retiresTheLegacySurface_andKeepsLiveAsks() throws IOException {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String toolId = UUID.randomUUID().toString();
        String skillId = UUID.randomUUID().toString();
        UUID toolKnowledgeId = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO agents (id, name, agent_type, tool_ids, skill_ids, created_at) "
                + "VALUES (?, ?, 'NATIVE', ?, ?, CURRENT_TIMESTAMP)",
                agentId, "v69-agent-" + agentId, toolId, skillId);
        jdbcTemplate.update("INSERT INTO runs (id, agent_id, status, created_at) "
                + "VALUES (?, ?, 'PENDING', CURRENT_TIMESTAMP)", runId, agentId);
        jdbcTemplate.update("INSERT INTO knowledge_items (id, name, type, status, sensitivity, created_at) "
                        + "VALUES (?, ?, 'TOOL', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP)",
                toolKnowledgeId, "v69-knowledge-" + toolKnowledgeId);
        jdbcTemplate.update("INSERT INTO tool_definitions (id, name, description, category, parameters, enabled) "
                + "VALUES (?, ?, 'legacy tool', 'ops', '{}', TRUE)", toolId, "v69-tool-" + toolId);
        jdbcTemplate.update("INSERT INTO skill_definitions (id, name, description, created_at) "
                + "VALUES (?, ?, 'legacy skill', CURRENT_TIMESTAMP)", skillId, "v69-skill-" + skillId);
        jdbcTemplate.update("INSERT INTO agent_tools (agent_id, tool_id) VALUES (?, ?)", agentId, toolId);
        jdbcTemplate.update("INSERT INTO role_tool_templates (role, tool_id, is_default) "
                + "VALUES ('v69-role', ?, TRUE)", toolId);
        jdbcTemplate.update("INSERT INTO skill_tools (skill_id, tool_id) VALUES (?, ?)", skillId, toolId);

        UUID legacyAsk = insertAsk(runId, "APPROVAL", "LEGACY_GATE");
        UUID reviewAsk = insertAsk(runId, "REVIEW_REQUEST", "LEGACY_GATE");
        UUID clarificationAsk = insertAsk(runId, "QUESTION", "CLARIFICATION");
        UUID acpAsk = insertAsk(runId, "APPROVAL", "ACP_PERMISSION");

        jdbcTemplate.update("INSERT INTO system_config (config_key, config_value) VALUES ('harness.selfVerify', '1')");
        jdbcTemplate.update("INSERT INTO system_config (config_key, config_value) "
                + "VALUES ('circuit.breaker.max.tokens.per.run', '300000')");
        jdbcTemplate.update("INSERT INTO system_config (config_key, config_value) "
                + "VALUES ('adk.health.check.interval.ms', '30000')");
        jdbcTemplate.update("INSERT INTO system_config (config_key, config_value) "
                + "VALUES ('v69.survivor.config', 'keep')");

        for (String statement : v69Statements()) {
            jdbcTemplate.execute(statement);
        }

        assertThat(count("SELECT COUNT(*) FROM agents WHERE agent_type = 'NATIVE'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM agents WHERE id = ? AND agent_type = 'ADK' "
                + "AND tool_ids IS NULL AND skill_ids IS NULL", agentId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tool_definitions WHERE enabled = TRUE")).isZero();
        assertThat(count("SELECT COUNT(*) FROM agent_tools")).isZero();
        assertThat(count("SELECT COUNT(*) FROM role_tool_templates")).isZero();
        assertThat(count("SELECT COUNT(*) FROM skill_tools")).isZero();
        assertThat(count("SELECT COUNT(*) FROM knowledge_items WHERE id = ? AND status = 'RETIRED' "
                + "AND retired_at IS NOT NULL", toolKnowledgeId)).isEqualTo(1);

        assertThat(approvalStatus(legacyAsk)).isEqualTo("EXPIRED");
        assertThat(approvalStatus(reviewAsk)).as("a live review ask must survive the upgrade").isEqualTo("PENDING");
        assertThat(approvalStatus(clarificationAsk)).as("a live clarification ask must survive").isEqualTo("PENDING");
        assertThat(approvalStatus(acpAsk)).as("native ACP asks own their lifecycle").isEqualTo("PENDING");

        assertThat(count("SELECT COUNT(*) FROM system_config WHERE config_key LIKE 'harness.%' "
                + "OR config_key LIKE 'circuit.breaker.%' OR config_key LIKE 'adk.%'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM system_config WHERE config_key = 'v69.survivor.config'"))
                .as("unrelated configuration must be preserved").isEqualTo(1);
    }

    private UUID insertAsk(UUID runId, String askType, String source) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO approvals (id, run_id, status, requested_at, "
                + "approval_type, ask_type, source) VALUES (?, ?, 'PENDING', CURRENT_TIMESTAMP, "
                + "'TOOL_CALL', ?, ?)", id, runId, askType, source);
        return id;
    }

    private String approvalStatus(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM approvals WHERE id = ?", String.class, id);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private List<String> v69Statements() throws IOException {
        String sql = new ClassPathResource("db/migration/V69__retire_legacy_execution_surface.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String withoutComments = Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isBlank())
                .toList();
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
