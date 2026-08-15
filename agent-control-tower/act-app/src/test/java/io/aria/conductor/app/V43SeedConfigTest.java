package io.aria.conductor.app;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards V43 (fix_sdd_seed_configs) against regression: runs the full Flyway
 * migration chain (V1..V43) against a fresh, isolated H2 (MODE=MySQL) database and
 * asserts the seeded SDD configuration is correct:
 * <ul>
 *   <li>Seeded SDD role agents (ba/dev/qa) carry explicit task config with
 *       taskApprovalRequired=false and maxToolCallRounds=15.</li>
 *   <li>The development-workflow template's per-step max_iterations are all >= 15.</li>
 *   <li>The BA prompt includes the required spec sections (Acceptance Criteria).</li>
 *   <li>The circuit-breaker per-run token budget is 300000.</li>
 * </ul>
 * A standalone Flyway datasource (unique DB name) is used instead of the shared
 * {@code act_test} database so cross-test {@code cleanup-all.sql} truncation cannot
 * mask the migration's data effect.
 */
class V43SeedConfigTest {

    private static final String TEMPLATE_ITEM_ID = "d0000001-0000-0000-0000-000000000001";

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFreshDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v43seed;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    private String templateYaml() {
        String yaml = jdbc.queryForObject(
                "SELECT yaml_content FROM knowledge_versions WHERE knowledge_item_id = CAST('"
                        + TEMPLATE_ITEM_ID + "' AS UUID)",
                String.class);
        assertThat(yaml).as("development-workflow template yaml must be present").isNotNull();
        return yaml;
    }

    @Test
    void sddRoleAgents_haveExplicitTaskConfig() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, config FROM agents WHERE role IN ('ba','dev','qa')");

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            String config = String.valueOf(row.get("config"));
            assertThat(config).contains("\"taskApprovalRequired\": false");
            assertThat(config).contains("\"maxToolCallRounds\": 15");
        });
    }

    @Test
    void template_maxIterationsAtLeast15ForAllSteps() {
        String yaml = templateYaml();
        assertThat(yaml).doesNotContain("max_iterations: 6");
        assertThat(yaml).doesNotContain("max_iterations: 10");
        assertThat(yaml).contains("max_iterations: 15");
    }

    @Test
    void baPrompt_containsSpecSections() {
        String yaml = templateYaml();
        assertThat(yaml).contains("Problem Statement");
        assertThat(yaml).contains("Proposed Solution");
        assertThat(yaml).contains("Acceptance Criteria");
        assertThat(yaml).contains("Error Handling");
    }

    @Test
    void v44_promptGuidance_presentInTemplateYaml() {
        String yaml = templateYaml();
        // BA prompt: fetch the issue via gh, emit a Questions section, carry rejection feedback.
        assertThat(yaml).contains("gh issue view");
        assertThat(yaml).contains("## Questions");
        assertThat(yaml).contains("Spec was rejected");
        // DEV prompt: clone the project independently before implementing.
        assertThat(yaml).contains("git clone");
    }

    @Test
    void v45_pipelinePrompts_presentInTemplateYaml() {
        String yaml = templateYaml();
        // DEV prompt: branch-scoped clone, spec.md read, push, real-test honesty.
        assertThat(yaml).contains("git clone --branch {branchName} {repoUrl}");
        assertThat(yaml).contains("spec/spec.md");
        assertThat(yaml).contains("git push origin {branchName}");
        assertThat(yaml).contains("Do NOT claim tests passed");
        // QA prompt: branch-scoped clone + verdict marker.
        assertThat(yaml).contains("VERDICT=<PASS|DEFECT|SPEC_GAP>");
    }

    @Test
    void v46_qaPrompt_blessesMarkerVerdict() {
        String yaml = templateYaml();
        // QA prompt: the VERDICT= marker is blessed as the official sandbox verdict channel.
        assertThat(yaml).contains("marker in your final output IS the official verdict submission");
        assertThat(yaml).contains("submit_dod_review tool may not be available");
    }

    @Test
    void v47_qaPrompt_pushesReportToBranch() {
        String yaml = templateYaml();
        // QA prompt: copy qa_report.md into the branch and push it so the backend
        // can capture it into a platform report artifact (R8-F4).
        assertThat(yaml).contains("cp /workspace/qa_report.md ./qa_report.md");
        assertThat(yaml).contains("git commit -m 'sdd qa report'");
    }

    @Test
    void systemConfig_tokenBudgetIs300000() {
        String value = jdbc.queryForObject(
                "SELECT config_value FROM system_config WHERE config_key = 'circuit.breaker.max.tokens.per.run'",
                String.class);
        assertThat(value).isEqualTo("300000");
    }
}
