package io.aria.conductor.execution.tool;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.RoleToolTemplateRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolResolverTest {
    @Mock ToolDefinitionRepository toolRepo;
    @Mock AgentToolRepository agentToolRepo;
    @Mock RoleToolTemplateRepository roleTemplateRepo;
    @InjectMocks AgentToolResolver resolver;

    @Test
    void shouldReturnAgentAssignedTools() {
        UUID agentId = UUID.randomUUID();
        String toolId = UUID.randomUUID().toString();
        Agent agent = Agent.builder().id(agentId).role("WORKER").build();
        when(agentToolRepo.findToolIdsByAgentId(agentId.toString())).thenReturn(List.of(toolId));
        when(toolRepo.findAllById(List.of(toolId))).thenReturn(List.of(
                ToolDefinition.builder().id(toolId).name("web_search").enabled(true).build()));
        List<ToolDefinition> tools = resolver.resolveForAgent(agent);
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("web_search");
    }

    @Test
    void shouldFallbackToRoleTemplateWhenNoAgentAssignment() {
        UUID agentId = UUID.randomUUID();
        String templateToolId = UUID.randomUUID().toString();
        Agent agent = Agent.builder().id(agentId).role("WORKER").build();
        when(agentToolRepo.findToolIdsByAgentId(agentId.toString())).thenReturn(List.of());
        when(roleTemplateRepo.findDefaultToolIdsByRole("WORKER")).thenReturn(List.of(templateToolId));
        when(toolRepo.findAllById(List.of(templateToolId))).thenReturn(List.of(
                ToolDefinition.builder().id(templateToolId).name("list_agents").enabled(true).build()));
        List<ToolDefinition> tools = resolver.resolveForAgent(agent);
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("list_agents");
    }

    @Test
    void shouldFallbackToWorkerWhenRoleSpecificTemplateEmpty() {
        UUID agentId = UUID.randomUUID();
        String workerToolId = UUID.randomUUID().toString();
        Agent agent = Agent.builder().id(agentId).role("dev").build();
        when(agentToolRepo.findToolIdsByAgentId(agentId.toString())).thenReturn(List.of());
        when(roleTemplateRepo.findDefaultToolIdsByRole("dev")).thenReturn(List.of());
        when(roleTemplateRepo.findDefaultToolIdsByRole("WORKER")).thenReturn(List.of(workerToolId));
        when(toolRepo.findAllById(List.of(workerToolId))).thenReturn(List.of(
                ToolDefinition.builder().id(workerToolId).name("read_file").enabled(true).build()));
        List<ToolDefinition> tools = resolver.resolveForAgent(agent);
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("read_file");
    }
}
