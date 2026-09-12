package io.aria.conductor.execution;

import io.aria.conductor.execution.kanban.KanbanService;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test-only bootstrap: act-execution has no {@code @SpringBootApplication} of
 * its own (production wiring lives in act-app), so full-context tests need a
 * discoverable {@code @SpringBootConfiguration} — mirrors act-mcp's
 * {@code McpTestBootstrap}. Boots ONLY the JPA infrastructure plus the service
 * under test; no component scan, so engine/LLM/sandbox beans never load.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("io.aria.conductor")
@EnableJpaRepositories("io.aria.conductor")
@Import(KanbanService.class)
public class ExecutionTestBootstrap {
}
