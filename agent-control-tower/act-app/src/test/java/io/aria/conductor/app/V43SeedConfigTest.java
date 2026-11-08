package io.aria.conductor.app;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
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
 *   <li>V69 retires the circuit-breaker configuration it no longer has a reader for.</li>
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
        assertThat(yaml).contains("git -c core.longpaths=true clone");
    }

    @Test
    void v45_pipelinePrompts_presentInTemplateYaml() {
        String yaml = templateYaml();
        // DEV prompt: branch-scoped clone, spec.md read, push, real-test honesty.
        assertThat(yaml).contains("git -c core.longpaths=true clone --branch {branchName} {repoUrl}");
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
        // QA prompt: write the report inside the checkout and commit ONLY it, so the
        // backend can capture it into a platform report artifact (R8-F4).
        assertThat(yaml).contains("Write your findings to qa_report.md inside the checkout");
        assertThat(yaml).contains("git commit -m 'sdd qa report' -- qa_report.md");
    }

    @Test
    void v77_prompts_useAParameterisedWorkspaceRoot() {
        String yaml = templateYaml();
        // F11: no prompt may name the sandbox-only absolute path; the checkout root is
        // the {workspaceRoot} placeholder WorkflowService resolves per step.
        assertThat(yaml).contains("{workspaceRoot}");
        assertThat(yaml).doesNotContain("/workspace");
        // F14: never claim a token the environment may not provide.
        assertThat(yaml).doesNotContain("GH_TOKEN is already configured");
        assertThat(yaml).doesNotContain("GH_TOKEN is configured for private repos");
        assertThat(yaml).contains("only if this environment actually provides them");
        // F19: the report is committed with an explicit pathspec and the pushed diff is
        // verified first.
        assertThat(yaml).contains("git diff --stat HEAD~1");
        // F23: test errors are not a green run.
        assertThat(yaml).contains("is not a green run");
        // F22: a PASS cannot coexist with an unverified acceptance criterion.
        assertThat(yaml).contains("if any criterion is NOT VERIFIED, report VERDICT=DEFECT instead");
        // F15: the BA prompt names the bound checkout instead of claiming the
        // working directory starts empty (the run now gets a shallow clone).
        assertThat(yaml).contains("a shallow clone of its default branch is provisioned at {workspaceRoot}/");
    }

    @Test
    void v70_baPrompt_writesFullSpecToTheRunWorkspace() {
        String yaml = templateYaml();
        // BA prompt: write the FULL spec to spec.md in the run working directory so
        // the backend can read the exported file; finalOutput carries only a short
        // summary plus the SPEC_ID marker.
        assertThat(yaml).contains("Write the FULL spec text to the file spec.md in your working directory");
        assertThat(yaml).doesNotContain("/workspace/spec.md");
        assertThat(yaml).contains("short summary alone is NOT enough");
    }

    @Test
    void v79_devAndQaPrompts_cloneWithCoreLongPaths() {
        String yaml = templateYaml();
        // R1: on Windows the agent-side clone died with "Filename too long" and left a
        // broken checkout, because only the backend clone set core.longpaths. Both the
        // DEV and the QA prompt must prefix their clone with it, and no bare clone may
        // survive. The checkout root that follows {repoUrl} is pinned independently.
        assertThat(yaml).doesNotContain("git clone --branch");
        assertThat(StringUtils.countOccurrencesOf(yaml,
                "git -c core.longpaths=true clone --branch {branchName} {repoUrl} "))
                .as("DEV and QA prompts must each clone with core.longpaths")
                .isEqualTo(2);
    }

    @Test
    void v80_existingAriaPrompt_dropsTheDecideApprovalClaim() throws Exception {
        // R4: the sync chat path reads the assistant prompt from agents.config, and the
        // create-only initializer never rewrites an existing row. A row created before
        // the prompt correction must lose the decide_approval claim, keep a read-only
        // approvals line, and leave the rest of the operator-visible prompt intact.
        String oldConfig = "{\"systemPrompt\":\"You are Aria.\\n\\n**Approvals:**\\n"
                + "- list_pending_approvals: List actions waiting for human approval\\n"
                + "- decide_approval: Approve or reject a pending action (requires id, decision, reason)\\n"
                + "\\n**Knowledge:**\\n- query_knowledge: Search APPROVED knowledge only\"}";
        jdbc.update("INSERT INTO agents (id, name, agent_type, config, health_status, created_at) "
                        + "VALUES (CAST('00000000-0000-0000-0000-000000000001' AS UUID), 'Aria', 'ADK', ?,"
                        + " 'HEALTHY', CURRENT_TIMESTAMP)",
                oldConfig);
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V80__aria_prompt_operator_only_approvals.sql"));
        }

        String config = jdbc.queryForObject(
                "SELECT config FROM agents WHERE id = CAST('00000000-0000-0000-0000-000000000001' AS UUID)",
                String.class);
        assertThat(config).doesNotContain("decide_approval");
        assertThat(config).contains("you cannot decide them, and no tool lets you");
        assertThat(config).contains("**Knowledge:**");
    }

    @Test
    void systemConfig_retiredCircuitBreakerKeysAreGone() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_config WHERE config_key LIKE 'circuit.breaker.%'",
                Integer.class);
        assertThat(count).isZero();
    }
}
