package io.aria.conductor.mcp;

import io.aria.conductor.execution.mcp.ToolPolicyRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Test-only bootstrap: scans ONLY io.aria.conductor.mcp so the integration test
 * boots the MCP server auto-configuration without the application's full context.
 * Datasource auto-configuration is excluded because act-common pulls
 * spring-boot-starter-data-jpa onto this module's classpath; production wiring
 * (real datasource, JPA, Flyway) lives in act-app.
 *
 * <p>{@link ToolPolicyRegistry} lives in act-execution (shared with the ACP permission
 * intake), outside this bootstrap's scan, so it is imported explicitly — the scanned
 * {@code WorkerGovernanceAspect} requires it.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@Import(ToolPolicyRegistry.class)
public class McpTestBootstrap {
}
