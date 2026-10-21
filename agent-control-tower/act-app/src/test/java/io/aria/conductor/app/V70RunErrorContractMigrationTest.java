package io.aria.conductor.app;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards V70: the run error identity columns must be re-appliable after a
 * Flyway {@code repair}, when the first run may have added one column before
 * failing. A non-idempotent ALTER would then block startup on a duplicate
 * column.
 */
class V70RunErrorContractMigrationTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFreshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v70contract;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void reapplyingTheScriptIsIdempotent() throws SQLException {
        applyV70();
        applyV70();

        assertThat(jdbc.queryForList("SELECT error_code, error_surface FROM runs")).isEmpty();
    }

    private static void applyV70() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V70__run_error_contract.sql"));
        }
    }
}
