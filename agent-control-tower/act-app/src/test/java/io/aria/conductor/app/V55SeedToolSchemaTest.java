package io.aria.conductor.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards V55 (kanban_transition_tool_schema) against regression: runs the full
 * Flyway migration chain (V1..V55) against a fresh, isolated H2 (MODE=MySQL)
 * database and asserts the seeded {@code transition_kanban_item} tool definition
 * now matches the HITL transition contract:
 * <ul>
 *   <li>required is exactly [id, status] (the legacy newStatus key is gone).</li>
 *   <li>properties include the optional comment, feedback and agentTemplateId
 *       params V22's seeded schema hid from the LLM.</li>
 *   <li>status is an enum of the six kanban states with the pickup/pause hint.</li>
 * </ul>
 * A standalone Flyway datasource (unique DB name) is used instead of the shared
 * {@code act_test} database so cross-test {@code cleanup-all.sql} truncation cannot
 * mask the migration's data effect (same pattern as {@link V43SeedConfigTest}).
 */
class V55SeedToolSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFreshDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v55seed;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    private JsonNode parameters() throws Exception {
        String json = jdbc.queryForObject(
                "SELECT parameters FROM tool_definitions WHERE name = 'transition_kanban_item'",
                String.class);
        assertThat(json).as("transition_kanban_item parameters must be present").isNotNull();
        return MAPPER.readTree(json);
    }

    @Test
    void required_isExactlyIdAndStatus() throws Exception {
        List<String> names = new ArrayList<>();
        parameters().get("required").forEach(n -> names.add(n.asText()));
        assertThat(names).containsExactly("id", "status");
    }

    @Test
    void legacyNewStatusProperty_isGone() throws Exception {
        assertThat(parameters().get("properties").has("newStatus")).isFalse();
    }

    @Test
    void hitlOptionalParams_presentAsStrings() throws Exception {
        JsonNode properties = parameters().get("properties");
        assertThat(properties.get("comment").get("type").asText()).isEqualTo("string");
        assertThat(properties.get("feedback").get("type").asText()).isEqualTo("string");
        assertThat(properties.get("agentTemplateId").get("type").asText()).isEqualTo("string");
        assertThat(properties.get("id").get("type").asText()).isEqualTo("string");
    }

    @Test
    void status_isSixStateEnum_withInformativeDescription() throws Exception {
        JsonNode status = parameters().get("properties").get("status");
        assertThat(status.get("type").asText()).isEqualTo("string");

        List<String> values = new ArrayList<>();
        status.get("enum").forEach(n -> values.add(n.asText()));
        assertThat(values).containsExactly("BACKLOG", "TODO", "IN_PROGRESS", "REVIEW", "DONE", "CANCELLED");

        assertThat(status.get("description").asText()).contains("Todo entry triggers agent pickup");
        assertThat(status.get("description").asText()).contains("pauses the linked run");
    }
}
