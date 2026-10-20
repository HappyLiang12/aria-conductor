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
 * Guards V69: the circuit breaker's total run-duration cap must exist as a
 * system_config row. Without it the key cannot be read or updated through
 * System Config, while the breaker's operator guidance points there.
 */
class V69SeedRunDurationConfigTest {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFreshDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v69seed;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void seedsTheRunDurationCapAtTheDocumentedDefault() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT config_value, description FROM system_config WHERE config_key = ?",
                "circuit.breaker.max.run.duration.ms");

        assertThat(row.get("config_value")).isEqualTo("7200000");
        assertThat((String) row.get("description")).contains("range: 60000-86400000");
    }
}
