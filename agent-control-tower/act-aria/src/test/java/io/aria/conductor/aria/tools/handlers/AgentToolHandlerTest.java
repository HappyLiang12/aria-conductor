package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentToolHandlerTest {

    @Mock
    private AgentService agentService;

    @Mock
    private AgentRepository agentRepository;

    @InjectMocks
    private AgentToolHandler handler;

    @Test
    void listAgentsShouldReturnTextList() {
        Agent agent = Agent.builder()
                .id(UUID.randomUUID())
                .name("TestAgent")
                .agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now())
                .build();
        when(agentRepository.findAll()).thenReturn(List.of(agent));

        String result = handler.execute(Map.of("toolName", "list_agents"));

        assertTrue(result.contains("TestAgent"));
        assertTrue(result.contains("Agents"));
        verify(agentRepository).findAll();
    }

    @Test
    void listAgentsShouldExcludeRetired() {
        Agent active = Agent.builder()
                .id(UUID.randomUUID())
                .name("active-agent")
                .agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now())
                .build();
        Agent retired = Agent.builder()
                .id(UUID.randomUUID())
                .name("retired-agent")
                .agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.RETIRED)
                .createdAt(Instant.now())
                .build();
        when(agentRepository.findAll()).thenReturn(List.of(active, retired));

        String result = handler.execute(Map.of("toolName", "list_agents"));

        assertTrue(result.contains("active-agent"));
        assertFalse(result.contains("retired-agent"));
    }

    @Test
    void createAgentShouldReturnCreatedAgentInfo() {
        UUID agentId = UUID.randomUUID();
        AgentResponse response = AgentResponse.builder()
                .id(agentId)
                .name("NewAgent")
                .build();
        when(agentService.createAgent(any(CreateAgentRequest.class))).thenReturn(response);

        String result = handler.execute(Map.of(
                "toolName", "create_agent",
                "name", "NewAgent",
                "role", "test-role"
        ));

        assertTrue(result.contains("NewAgent"));
        assertTrue(result.contains(agentId.toString()));
        verify(agentService).createAgent(any(CreateAgentRequest.class));
    }

    @Test
    void getAgentShouldReturnAgentDetails() {
        UUID id = UUID.randomUUID();
        Agent agent = Agent.builder()
                .id(id)
                .name("FoundAgent")
                .agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY)
                .role("assistant")
                .createdAt(Instant.now())
                .build();
        when(agentRepository.findById(id)).thenReturn(Optional.of(agent));

        String result = handler.execute(Map.of(
                "toolName", "get_agent",
                "id", id.toString()
        ));

        assertTrue(result.contains("FoundAgent"));
        verify(agentRepository).findById(id);
    }

    @Test
    void missingToolNameShouldReturnError() {
        String result = handler.execute(Map.of());

        assertTrue(result.startsWith("Error"));
    }

    @Test
    void deleteAgentAliasShouldCallRetire() {
        UUID id = UUID.randomUUID();

        String result = handler.execute(Map.of(
                "toolName", "delete_agent",
                "id", id.toString()
        ));

        assertTrue(result.contains("retired successfully"));
        verify(agentService).retireAgent(id);
    }

    @Test
    void unknownToolShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "nonexistent_tool"));

        assertTrue(result.startsWith("Error"));
    }
}
