package io.aria.conductor.app;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.execution.tool.AgentToolResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the role-based default tool resolution end-to-end against the real schema:
 * an agent with no explicit tool assignment resolves its role's default tools (seeded via
 * the V34 pattern), rather than falling back to the generic WORKER set or resolving nothing.
 */
@Sql(scripts = {"classpath:db/cleanup-all.sql", "classpath:tool-registry-seed.sql", "classpath:role-defaults-seed.sql"})
class RoleDefaultsIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    private AgentToolResolver agentToolResolver;

    @Autowired
    private io.aria.conductor.agent.repository.AgentRepository agentRepository;

    private Agent agentWithRole(String role) {
        return agentRepository.save(Agent.builder()
                .id(UUID.randomUUID()).name("role-defaults-" + role).role(role)
                .agentType(AgentType.NATIVE).healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
    }

    @Test
    void devAgentWithNoAssignment_resolvesRoleDefaultTools() {
        List<ToolDefinition> tools = agentToolResolver.resolveForAgent(agentWithRole("dev"));
        assertThat(tools).extracting(ToolDefinition::getName)
                .contains("write_file", "shell_exec", "http_request");
    }

    @Test
    void baAgentWithNoAssignment_resolvesRoleDefaultTools() {
        List<ToolDefinition> tools = agentToolResolver.resolveForAgent(agentWithRole("ba"));
        assertThat(tools).extracting(ToolDefinition::getName)
                .contains("read_file", "web_search")
                .doesNotContain("shell_exec");
    }

    @Test
    void qaAgentWithNoAssignment_resolvesRoleDefaultTools() {
        List<ToolDefinition> tools = agentToolResolver.resolveForAgent(agentWithRole("qa"));
        assertThat(tools).extracting(ToolDefinition::getName)
                .contains("write_file", "review_knowledge");
    }
}
