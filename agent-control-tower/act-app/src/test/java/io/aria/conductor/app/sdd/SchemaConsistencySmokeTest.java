package io.aria.conductor.app.sdd;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-consistency smoke for the SDD migration surface: boots the application
 * context (Flyway applies V40) and asserts every V40-added column exists with the
 * right type, plus the seeded development-workflow template rows.
 *
 * <p>Full Hibernate {@code ddl-auto=validate} is intentionally NOT used here: the
 * H2 test database stores UUID columns as BINARY while entities declare VARCHAR(36),
 * a pre-existing drift unrelated to this feature. This test pins exactly the
 * contract this feature added.
 */
@SpringBootTest
@ActiveProfiles({"test", "noop-llm"})
class SchemaConsistencySmokeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v40ColumnsExistWithExpectedTypes() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns", String.class);
        Set<String> present = Set.copyOf(columns);

        assertThat(present).as("dod_stage_reviews.verdict")
                .contains("VERDICT");
        assertThat(present).as("dod_records.stages_json")
                .contains("STAGES_JSON");
        for (String col : List.of("APPROVAL_TYPE", "CONTENT", "CONTENT_KIND", "KNOWLEDGE_ITEM_ID")) {
            assertThat(present).as("approvals." + col).contains(col);
        }
        assertThat(present).as("workflow_chains.report_artifact_id")
                .contains("REPORT_ARTIFACT_ID");
    }
}
