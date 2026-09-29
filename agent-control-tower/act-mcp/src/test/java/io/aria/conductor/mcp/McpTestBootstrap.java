package io.aria.conductor.mcp;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * Test-only bootstrap: scans ONLY io.aria.conductor.mcp so the integration test
 * boots the MCP server auto-configuration without the application's full context.
 * Datasource auto-configuration is excluded because act-common pulls
 * spring-boot-starter-data-jpa onto this module's classpath; production wiring
 * (real datasource, JPA, Flyway) lives in act-app.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
public class McpTestBootstrap {
}
