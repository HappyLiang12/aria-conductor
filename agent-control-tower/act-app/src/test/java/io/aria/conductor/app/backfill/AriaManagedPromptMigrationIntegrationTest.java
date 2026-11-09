package io.aria.conductor.app.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.app.BaseH2IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * V82 is the data-only Aria managed-prompt marker. Proven on a fresh H2 (MODE=MySQL)
 * database with the real Flyway chain, so the statement itself is what runs -- not a copy
 * typed into the test. A fresh database has no Aria row when Flyway runs (the initializer
 * seeds it after startup), so the pre-existing-installation cases replay the migration's
 * exact statements against a row inserted first, and replaying them a second time must
 * change nothing.
 */
class AriaManagedPromptMigrationIntegrationTest extends BaseH2IntegrationTest {

    private static final UUID ARIA_AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String MARKER = "\"managedPromptVersion\":1";
    private static final String OBJECT_LITERAL = "{\"managedPromptVersion\":1}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v82IsPartOfTheRealFlywayChain() {
        MigrationInfo v82 = Arrays.stream(flyway.info().applied())
                .filter(info -> "82".equals(String.valueOf(info.getVersion())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("V82 is missing from the applied Flyway chain"));

        assertThat(v82.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).as("no migration may be left pending").isEmpty();
    }

    @Test
    @Transactional
    void addsTheMarkerOnceToAnUnmarkedAriaConfig() throws IOException {
        insertAria("{\"systemPrompt\":\"stored prompt\",\"maxToolCallRounds\":15,\"taskApprovalRequired\":false}");

        assertThat(replayV82()).as("the unmarked row is updated").isEqualTo(1);

        String config = ariaConfig();
        assertThat(occurrences(config, MARKER)).as(config).isEqualTo(1);
        JsonNode parsed = MAPPER.readTree(config);
        assertThat(parsed.path("managedPromptVersion").asInt()).isEqualTo(1);
        assertThat(parsed.path("systemPrompt").asText()).isEqualTo("stored prompt");
        assertThat(parsed.path("maxToolCallRounds").asInt()).isEqualTo(15);
        assertThat(parsed.path("taskApprovalRequired").asBoolean()).isFalse();

        assertThat(replayV82()).as("a marked row is excluded from the second pass").isZero();
        assertThat(ariaConfig()).as("the second pass is byte-identical").isEqualTo(config);
        assertThat(occurrences(ariaConfig(), MARKER)).isEqualTo(1);
    }

    @Test
    @Transactional
    void leavesAnAlreadyMarkedConfigUntouched() throws IOException {
        String marked = "{\"managedPromptVersion\":1,\"maxToolCallRounds\":15}";
        insertAria(marked);

        assertThat(replayV82()).isZero();

        assertThat(ariaConfig()).isEqualTo(marked);
    }

    @Test
    @Transactional
    void turnsAnEmptyConfigIntoTheObjectLiteral() throws IOException {
        for (String empty : Arrays.asList(null, "", "{}")) {
            jdbc.update("DELETE FROM agents WHERE id = ?", ARIA_AGENT_ID);
            insertAria(empty);

            assertThat(replayV82()).as("config=%s", empty).isEqualTo(1);
            assertThat(ariaConfig()).as("config=%s", empty).isEqualTo(OBJECT_LITERAL);
            assertThat(MAPPER.readTree(ariaConfig()).path("managedPromptVersion").asInt()).isEqualTo(1);

            assertThat(replayV82()).as("replay for config=%s", empty).isZero();
            assertThat(ariaConfig()).isEqualTo(OBJECT_LITERAL);
        }
    }

    @Test
    @Transactional
    void turnsAWhitespaceOnlyObjectIntoTheObjectLiteral() throws IOException {
        insertAria("{ }");

        assertThat(replayV82()).as("an empty object with inner whitespace is still empty").isEqualTo(1);

        assertThat(ariaConfig()).isEqualTo(OBJECT_LITERAL);
        assertThat(MAPPER.readTree(ariaConfig()).path("managedPromptVersion").asInt()).isEqualTo(1);
        assertThat(replayV82()).as("replay is a no-op").isZero();
        assertThat(ariaConfig()).isEqualTo(OBJECT_LITERAL);
    }

    @Test
    @Transactional
    void leavesATrailingCommaConfigUnchanged() throws IOException {
        String malformed = "{\"a\":1,}";
        insertAria(malformed);

        assertThat(replayV82()).as("a shape the statement cannot repair is not selected").isZero();

        assertThat(ariaConfig()).as("no character is dropped and nothing is appended").isEqualTo(malformed);
    }

    @Test
    @Transactional
    void leavesAnObjectWithWhitespaceBeforeTheBraceUnchanged() throws IOException {
        String nonCompact = "{\n}";
        insertAria(nonCompact);

        assertThat(replayV82()).as("appending after whitespace would open the object with a comma").isZero();

        assertThat(ariaConfig()).isEqualTo(nonCompact);
    }

    @Test
    @Transactional
    void appendsTheMarkerToACompactObject() throws IOException {
        insertAria("{\"a\":1}");

        assertThat(replayV82()).isEqualTo(1);

        assertThat(ariaConfig()).isEqualTo("{\"a\":1,\"managedPromptVersion\":1}");
        assertThat(occurrences(ariaConfig(), MARKER)).isEqualTo(1);
        assertThat(replayV82()).as("a marked row is excluded from the second pass").isZero();
        assertThat(ariaConfig()).isEqualTo("{\"a\":1,\"managedPromptVersion\":1}");
    }

    @Test
    @Transactional
    void doesNotAppendASecondMarkerWhenTheColonIsSpaced() throws IOException {
        String marked = "{\"managedPromptVersion\" : 2}";
        insertAria(marked);

        assertThat(replayV82()).as("marker detection never assumes the colon's spacing").isZero();

        assertThat(ariaConfig()).isEqualTo(marked);
        assertThat(occurrences(ariaConfig(), "\"managedPromptVersion\"")).isEqualTo(1);
    }

    @Test
    @Transactional
    void keepsANonAsciiConfigIntact() throws IOException {
        insertAria("{\"systemPrompt\":\"\u00c4ria \u2014 op\u00e9rateur \u2713\",\"maxToolCallRounds\":15}");

        assertThat(replayV82()).isEqualTo(1);

        JsonNode parsed = MAPPER.readTree(ariaConfig());
        assertThat(parsed.path("systemPrompt").asText())
                .as("the marker is inserted by character position, never mid-character")
                .isEqualTo("\u00c4ria \u2014 op\u00e9rateur \u2713");
        assertThat(parsed.path("managedPromptVersion").asInt()).isEqualTo(1);
    }

    @Test
    @Transactional
    void leavesEveryOtherAgentRowUntouched() throws IOException {
        UUID otherAgentId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, name, agent_type, config, health_status, created_at) "
                        + "VALUES (?, ?, 'ADK', ?, 'HEALTHY', CURRENT_TIMESTAMP)",
                otherAgentId, "SDD BA Agent", "{\"maxToolCallRounds\":4}");
        insertAria("{\"maxToolCallRounds\":15}");

        replayV82();

        assertThat(ariaConfig()).contains(MARKER);
        assertThat(jdbc.queryForObject("SELECT config FROM agents WHERE id = ?", String.class, otherAgentId))
                .as("the marker belongs to the Aria row only")
                .isEqualTo("{\"maxToolCallRounds\":4}");
    }

    @Test
    @Transactional
    void aSecondBootRunsNoMigrationAndChangesNothing() throws IOException {
        insertAria("{\"maxToolCallRounds\":15}");
        assertThat(replayV82()).isEqualTo(1);
        String afterFirstPass = ariaConfig();

        MigrateResult secondBoot = flyway.migrate();

        assertThat(secondBoot.migrationsExecuted)
                .as("Flyway history already contains V82, so a second boot re-runs nothing")
                .isZero();
        assertThat(ariaConfig()).isEqualTo(afterFirstPass);
        assertThat(occurrences(ariaConfig(), MARKER)).isEqualTo(1);
    }

    private void insertAria(String config) {
        jdbc.update("INSERT INTO agents (id, name, agent_type, config, health_status, created_at) "
                        + "VALUES (?, 'Aria', 'ADK', ?, 'HEALTHY', CURRENT_TIMESTAMP)",
                ARIA_AGENT_ID, config);
    }

    private String ariaConfig() {
        return jdbc.queryForObject("SELECT config FROM agents WHERE id = ?", String.class, ARIA_AGENT_ID);
    }

    /** Executes the migration's exact statements; returns the affected-row count. */
    private int replayV82() throws IOException {
        int affected = 0;
        for (String statement : v82Statements()) {
            affected += jdbc.update(statement);
        }
        return affected;
    }

    private static List<String> v82Statements() throws IOException {
        String sql = new ClassPathResource("db/migration/V82__aria_managed_prompt.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String withoutComments = Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isBlank())
                .toList();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
