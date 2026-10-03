package io.aria.conductor.app;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards V64 (repair_langchain_seeded_agents) against regression: runs the full
 * Flyway migration chain (V1..V64) against a fresh, isolated H2 (MODE=MySQL)
 * database and asserts the three SDD role agents V42 seeded with the retired
 * 'langchain' provider now carry the documented default core 'opencode', so the
 * runtime can resolve them instead of refusing them fail-closed.
 * A standalone Flyway datasource (unique DB name) is used instead of the shared
 * {@code act_test} database so cross-test {@code cleanup-all.sql} truncation cannot
 * mask the migration's data effect (same pattern as {@link V55SeedToolSchemaTest}).
 */
class V64SeedCoreRepairTest {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFreshDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v64seed;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void seededSddRoleAgents_carryOpencode() {
        List<String> providers = jdbc.queryForList(
                "SELECT adk_provider FROM agents WHERE id IN "
                        + "(CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID))",
                String.class,
                "ba000000-0000-0000-0000-000000000001",
                "de000000-0000-0000-0000-000000000002",
                "aa000000-0000-0000-0000-000000000003");

        assertThat(providers)
                .as("the three SDD role agents seeded by V42 must be repaired by V64")
                .hasSize(3)
                .containsOnly("opencode");
    }
}
