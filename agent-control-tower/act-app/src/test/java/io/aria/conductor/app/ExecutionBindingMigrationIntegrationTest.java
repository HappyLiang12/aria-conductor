package io.aria.conductor.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the V59 execution-binding schema end to end:
 * <ul>
 *   <li>the migration itself, applied by Flyway to a fresh database that already
 *       contains legacy rows (agents/hosts and a completed run) -- the one
 *       deliberate backfill is OpenCode &rarr; {@code SANDBOX}; every other
 *       legacy row keeps unknown (NULL) execution metadata and no binding;</li>
 *   <li>the run-keyed binding table contract: columns, insert-once on the run
 *       UUID, and round-tripping of the non-secret settings snapshot;</li>
 *   <li>immutability of the stored snapshot when the owning agent is edited;</li>
 *   <li>secret-free persistence: only non-secret settings and a credential
 *       reference are stored, never a secret environment.</li>
 * </ul>
 */
class ExecutionBindingMigrationIntegrationTest extends BaseH2IntegrationTest {

    private static final String SECRET = "fixture-secret-value";
    private static final String AGENT_COLUMNS_FOR_COMPARISON =
            "SELECT name, description, agent_type, role, model, provider, adk_provider, config, "
                    + "health_status, pickup_enabled, created_at, updated_at, retired_at, last_probed_at "
                    + "FROM agents WHERE id = CAST(? AS UUID)";
    private static final List<String> BINDING_COLUMNS = List.of(
            "RUN_ID", "AGENT_ID", "CORE_ID", "EXECUTION_MODE", "SETTINGS_JSON",
            "WORKSPACE_KIND", "WORKSPACE_LEASE_ID", "WORKSPACE_ROOT", "WORKSPACE_SOURCE_ROOT",
            "WORKSPACE_BASE_COMMIT", "CREDENTIAL_REF", "CONFIGURATION_REVISION", "DEADLINE",
            "RUNTIME_ENVIRONMENT_ID", "RUNTIME_OWNERSHIP_IDENTITY", "RUNTIME_ENDPOINT",
            "RUNTIME_VERSION", "RUNTIME_STATE", "USAGE_INPUT_TOKENS", "USAGE_OUTPUT_TOKENS",
            "USAGE_CREDITS", "OBSERVED_MODEL", "VERSION", "CREATED_AT", "UPDATED_AT");

    @Autowired
    RunExecutionBindingRepository bindingRepository;

    @Autowired
    AgentRepository agentRepository;

    @Autowired
    RunRepository runRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // V59 applied by Flyway to a database that predates it
    // ------------------------------------------------------------------

    @Test
    void v59BackfillsOnlyOpenCodeAgentsLeavesLegacyRowsUnknownAndAddsNoBinding() throws Exception {
        String url = "jdbc:h2:mem:bindings_v59_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway upToV58 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("58"))
                .load();
        upToV58.migrate();
        assertThat(upToV58.info().current().getVersion().getVersion()).isEqualTo("58");

        UUID openCodeAgentId = UUID.randomUUID();
        UUID legacyAgentId = UUID.randomUUID();
        UUID providerlessAgentId = UUID.randomUUID();
        UUID legacyRunId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO agents (id, name, agent_type, adk_provider, model, health_status, created_at) "
                            + "VALUES (CAST(? AS UUID), ?, 'NATIVE', ?, ?, 'HEALTHY', CURRENT_TIMESTAMP)")) {
                insert.setString(1, openCodeAgentId.toString());
                insert.setString(2, "opencode agent");
                insert.setString(3, "opencode");
                insert.setString(4, "deepseek");
                insert.executeUpdate();

                insert.setString(1, legacyAgentId.toString());
                insert.setString(2, "legacy agent");
                insert.setString(3, "langchain");
                insert.setString(4, "mock");
                insert.executeUpdate();

                insert.setString(1, providerlessAgentId.toString());
                insert.setString(2, "provider-less agent");
                insert.setString(3, null);
                insert.setString(4, null);
                insert.executeUpdate();
            }
            try (PreparedStatement insertRun = connection.prepareStatement(
                    "INSERT INTO runs (id, agent_id, status, created_at) "
                            + "VALUES (CAST(? AS UUID), CAST(? AS UUID), 'COMPLETED', CURRENT_TIMESTAMP)")) {
                insertRun.setString(1, legacyRunId.toString());
                insertRun.setString(2, legacyAgentId.toString());
                insertRun.executeUpdate();
            }
        }

        List<String> legacyAgentBefore = namedValues(url, AGENT_COLUMNS_FOR_COMPARISON, legacyAgentId.toString());
        List<String> openCodeAgentBefore = namedValues(url, AGENT_COLUMNS_FOR_COMPARISON, openCodeAgentId.toString());

        // Pinned to V59: this test asserts the exact V59 contract, so later
        // migrations (V60+) must never be able to change its outcome.
        Flyway upToV59 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("59"))
                .load();
        upToV59.migrate();
        assertThat(upToV59.info().current().getVersion().getVersion()).isEqualTo("59");

        assertThat(singleValue(url, "SELECT execution_mode FROM agents WHERE id = CAST(? AS UUID)", openCodeAgentId.toString()))
                .isEqualTo("SANDBOX");
        assertThat(singleValue(url, "SELECT workspace_mode FROM agents WHERE id = CAST(? AS UUID)", openCodeAgentId.toString()))
                .isNull();
        assertThat(singleValue(url, "SELECT workspace_path FROM agents WHERE id = CAST(? AS UUID)", openCodeAgentId.toString()))
                .isNull();
        assertThat(singleValue(url, "SELECT workspace_base_ref FROM agents WHERE id = CAST(? AS UUID)", openCodeAgentId.toString()))
                .isNull();

        assertThat(singleValue(url, "SELECT execution_mode FROM agents WHERE id = CAST(? AS UUID)", legacyAgentId.toString()))
                .isNull();
        assertThat(singleValue(url, "SELECT execution_mode FROM agents WHERE id = CAST(? AS UUID)", providerlessAgentId.toString()))
                .isNull();

        // Non-backfilled rows are byte-for-byte unchanged by V59.
        assertThat(namedValues(url, AGENT_COLUMNS_FOR_COMPARISON, legacyAgentId.toString()))
                .isEqualTo(legacyAgentBefore);
        assertThat(namedValues(url, AGENT_COLUMNS_FOR_COMPARISON, openCodeAgentId.toString()))
                .isEqualTo(openCodeAgentBefore);

        // No purge and no invented historical run core: the legacy run survives
        // without a binding, and the new table starts empty.
        assertThat(singleValue(url, "SELECT status FROM runs WHERE id = CAST(? AS UUID)", legacyRunId.toString()))
                .isEqualTo("COMPLETED");
        assertThat(columnNames(url, "RUN_EXECUTION_BINDINGS")).containsExactlyElementsOf(BINDING_COLUMNS);
        assertThat(singleValue(url, "SELECT COUNT(*) FROM run_execution_bindings", null)).isEqualTo(0L);
    }

    // ------------------------------------------------------------------
    // Run-keyed binding repository behavior
    // ------------------------------------------------------------------

    @Test
    void bindingIsInsertedOncePerRunAndReadBackByRunUuid() {
        Agent agent = createAgent("binding-insert-once-agent", "opencode", ExecutionMode.SANDBOX);
        UUID runId = UUID.randomUUID();
        bindingRepository.saveAndFlush(RunExecutionBinding.builder()
                .runId(runId)
                .agentId(agent.getId())
                .coreId("opencode")
                .executionMode(ExecutionMode.SANDBOX)
                .settingsJson("{\"coreId\":\"opencode\"}")
                .configurationRevision("rev-1")
                .build());

        assertThat(bindingRepository.findById(runId)).hasValueSatisfying(stored -> {
            assertThat(stored.getCoreId()).isEqualTo("opencode");
            assertThat(stored.getExecutionMode()).isEqualTo(ExecutionMode.SANDBOX);
            assertThat(stored.getConfigurationRevision()).isEqualTo("rev-1");
            assertThat(stored.getVersion()).isEqualTo(0L);
        });

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO run_execution_bindings "
                        + "(run_id, agent_id, core_id, execution_mode, settings_json, version, created_at) "
                        + "VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, 0, CURRENT_TIMESTAMP)",
                runId.toString(), agent.getId().toString(), "qoder", "HOST", "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM run_execution_bindings WHERE run_id = CAST(? AS UUID)",
                Integer.class, runId.toString());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void legacyRunWithoutARecordedBindingHasNoBindingRow() {
        Agent agent = createAgent("binding-legacy-agent", "langchain", null);
        Run legacyRun = runRepository.saveAndFlush(Run.builder()
                .agentId(agent.getId())
                .status(RunStatus.COMPLETED)
                .build());

        assertThat(bindingRepository.findById(legacyRun.getId())).isEmpty();
    }

    @Test
    void bindingSnapshotStaysUnchangedAfterAgentEdits() throws Exception {
        Agent agent = createAgent("binding-snapshot-agent", "opencode", ExecutionMode.SANDBOX);
        UUID runId = UUID.randomUUID();
        AgentExecutionSettings snapshot = new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX,
                WorkspaceMode.WORKTREE, "C:/projects/example", "main");
        String frozenJson = new ObjectMapper().writeValueAsString(snapshot);
        bindingRepository.saveAndFlush(RunExecutionBinding.builder()
                .runId(runId)
                .agentId(agent.getId())
                .coreId(snapshot.coreId())
                .executionMode(snapshot.executionMode())
                .settingsJson(frozenJson)
                .build());

        agent.setAdkProvider("qoder");
        agent.setExecutionMode(ExecutionMode.HOST);
        agent.setWorkspaceMode(WorkspaceMode.DIRECT);
        agent.setWorkspacePath("C:/somewhere/else");
        agent.setWorkspaceBaseRef("release");
        agentRepository.saveAndFlush(agent);

        RunExecutionBinding stored = bindingRepository.findById(runId).orElseThrow();
        assertThat(stored.getCoreId()).isEqualTo("opencode");
        assertThat(stored.getExecutionMode()).isEqualTo(ExecutionMode.SANDBOX);
        assertThat(stored.getSettingsJson()).isEqualTo(frozenJson);
        assertThat(new ObjectMapper().readValue(stored.getSettingsJson(), AgentExecutionSettings.class))
                .isEqualTo(snapshot);
    }

    @Test
    void bindingStoresOnlyNonSecretSettingsAndNoSecretEnvironmentMaterial() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentExecutionSettings settings = new AgentExecutionSettings("qoder", ExecutionMode.HOST,
                WorkspaceMode.DIRECT, "C:/projects/example", null);
        String settingsJson = mapper.writeValueAsString(settings);
        assertThat(settingsJson).isEqualTo("{\"coreId\":\"qoder\",\"executionMode\":\"HOST\","
                + "\"workspaceMode\":\"DIRECT\",\"workspacePath\":\"C:/projects/example\","
                + "\"workspaceBaseRef\":null}");

        SecretBundle credentials = new SecretBundle("qoder:operator", Map.of("QODER_PAT", SECRET));
        LaunchProfile launchProfile = new LaunchProfile(List.of("qodercli", "--acp"),
                Map.of("QODER_PAT", SECRET), "C:/work/run-1");

        Agent agent = createAgent("binding-secret-free-agent", "qoder", ExecutionMode.HOST);
        UUID runId = UUID.randomUUID();
        bindingRepository.saveAndFlush(RunExecutionBinding.builder()
                .runId(runId)
                .agentId(agent.getId())
                .coreId("qoder")
                .executionMode(ExecutionMode.HOST)
                .settingsJson(settingsJson)
                .credentialRef(credentials.reference())
                .createdAt(Instant.now())
                .build());

        String persistedRow = allColumnsAsText(runId);
        assertThat(persistedRow)
                .doesNotContain(SECRET)
                .doesNotContain("QODER_PAT")
                .contains("qoder:operator");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT settings_json FROM run_execution_bindings WHERE run_id = CAST(? AS UUID)",
                String.class, runId.toString())).isEqualTo(settingsJson);
        assertThat(credentials.toString()).isEqualTo("SecretBundle[redacted]");
        assertThat(launchProfile.toString()).isEqualTo("LaunchProfile[redacted]");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Agent createAgent(String name, String adkProvider, ExecutionMode executionMode) {
        return agentRepository.saveAndFlush(Agent.builder()
                .name(name)
                .agentType(AgentType.NATIVE)
                .adkProvider(adkProvider)
                .executionMode(executionMode)
                .healthStatus(HealthStatus.HEALTHY)
                .pickupEnabled(Boolean.TRUE)
                .build());
    }

    private String allColumnsAsText(UUID runId) {
        return jdbcTemplate.query(
                "SELECT * FROM run_execution_bindings WHERE run_id = CAST(? AS UUID)",
                rs -> {
                    StringBuilder text = new StringBuilder();
                    ResultSetMetaData meta = rs.getMetaData();
                    while (rs.next()) {
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            text.append(meta.getColumnLabel(i)).append('=').append(rs.getString(i)).append('\n');
                        }
                    }
                    return text.toString();
                },
                runId.toString());
    }

    private static Object singleValue(String url, String sql, String uuidOrNull) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (uuidOrNull != null) {
                statement.setString(1, uuidOrNull);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("expected one row from: %s", sql).isTrue();
                return rs.getObject(1);
            }
        }
    }

    private static List<String> namedValues(String url, String sql, String uuid) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("expected one row from: %s", sql).isTrue();
                ResultSetMetaData meta = rs.getMetaData();
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    values.add(meta.getColumnLabel(i) + "=" + rs.getString(i));
                }
                return values;
            }
        }
    }

    private static List<String> columnNames(String url, String tableName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_name = ? ORDER BY ordinal_position")) {
            statement.setString(1, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (rs.next()) {
                    columns.add(rs.getString(1).toUpperCase(java.util.Locale.ROOT));
                }
                return columns;
            }
        }
    }
}
