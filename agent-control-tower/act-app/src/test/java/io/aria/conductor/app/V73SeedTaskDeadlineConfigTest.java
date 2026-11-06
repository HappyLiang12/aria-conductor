package io.aria.conductor.app;

import io.aria.conductor.common.config.SystemConfigKeys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards V73: the run deadline must exist as a system_config row, because the
 * deadline failure tells the operator to change it in Configure > System Config.
 */
class V73SeedTaskDeadlineConfigTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFreshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v73seed;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void seedsTheRunDeadlineAtTheDocumentedDefault() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT config_value, description FROM system_config WHERE config_key = ?",
                SystemConfigKeys.TASK_DEADLINE_MINUTES);

        // The seeded row and the runtime/controller constants must agree: a value
        // or range changed on one side alone leaves the operator editing a number
        // the runtime never applies, or reading a range the API rejects.
        assertThat(row.get("config_value"))
                .isEqualTo(String.valueOf(SystemConfigKeys.TASK_DEADLINE_DEFAULT_MINUTES));
        assertThat((String) row.get("description")).contains("range: "
                + SystemConfigKeys.TASK_DEADLINE_MIN_MINUTES + "-"
                + SystemConfigKeys.TASK_DEADLINE_MAX_MINUTES);
    }

    @Test
    void reseedingTheDeadlineNeitherDuplicatesNorOverwritesTheOperatorValue() throws SQLException {
        // The seed is a not-exists insert, never an upsert: a repair/rerun must
        // leave the value the operator set through System Config untouched.
        jdbc.update("UPDATE system_config SET config_value = '7' WHERE config_key = ?",
                SystemConfigKeys.TASK_DEADLINE_MINUTES);
        try {
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/V73__seed_task_deadline_config.sql"));
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/V73__seed_task_deadline_config.sql"));
            }

            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM system_config WHERE config_key = ?",
                    Integer.class, SystemConfigKeys.TASK_DEADLINE_MINUTES);
            assertThat(rows).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT config_value FROM system_config WHERE config_key = ?",
                    String.class, SystemConfigKeys.TASK_DEADLINE_MINUTES)).isEqualTo("7");
        } finally {
            // The in-memory database is shared by this class's tests.
            jdbc.update("UPDATE system_config SET config_value = ? WHERE config_key = ?",
                    String.valueOf(SystemConfigKeys.TASK_DEADLINE_DEFAULT_MINUTES),
                    SystemConfigKeys.TASK_DEADLINE_MINUTES);
        }
    }
}
