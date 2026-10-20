package io.aria.conductor.app;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards V71: the run deadline must exist as a system_config row, because the
 * deadline failure tells the operator to change it in Configure > System Config.
 */
class V71SeedTaskDeadlineConfigTest {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFreshDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v71seed;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
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
                "aria.tasks.deadline-minutes");

        assertThat(row.get("config_value")).isEqualTo("45");
        assertThat((String) row.get("description")).contains("range: 1-1440");
    }
}
