package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.execution.mcp.McpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolsTest {

    @Mock AgentService agentService;
    McpProperties mcpProperties;
    AgentTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new AgentTools(agentService, mcpProperties);
    }

    @Test
    void listAgents_delegatesAndWraps() {
        when(agentService.listAgents()).thenReturn(List.of(
                AgentResponse.builder().id(UUID.randomUUID()).name("orchestrator").build()));

        String json = tools.listAgents();

        assertThat(json).contains("\"ok\":true").contains("orchestrator");
    }

    @Test
    void getAgent_returnsAgentJson() {
        UUID id = UUID.randomUUID();
        when(agentService.getAgent(id)).thenReturn(AgentResponse.builder()
                .id(id).name("worker").agentType(AgentType.NATIVE).healthStatus(HealthStatus.HEALTHY).build());

        String json = tools.getAgent(id);

        assertThat(json).contains("\"ok\":true").contains("worker").contains("NATIVE");
    }

    @Test
    void getAgent_mapsNotFoundToErrorType() {
        UUID id = UUID.randomUUID();
        when(agentService.getAgent(id)).thenThrow(new ResourceNotFoundException("Agent", id));

        String json = tools.getAgent(id);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void createAgent_defaultsToNativeType() {
        when(agentService.createAgent(any())).thenReturn(AgentResponse.builder()
                .id(UUID.randomUUID()).name("developer").agentType(AgentType.NATIVE).build());

        String json = tools.createAgent("developer", "worker", null, "builds things",
                "gpt-4o", "openai", "langchain", Map.of("harnessProfile", "weak-model-safe"));

        assertThat(json).contains("\"ok\":true").contains("developer");
        ArgumentCaptor<CreateAgentRequest> captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(agentService).createAgent(captor.capture());
        assertThat(captor.getValue().getAgentType()).isEqualTo(AgentType.NATIVE);
        assertThat(captor.getValue().getRole()).isEqualTo("worker");
    }

    @Test
    void createAgent_parsesExplicitAdkType() {
        when(agentService.createAgent(any())).thenReturn(AgentResponse.builder()
                .id(UUID.randomUUID()).name("adk-worker").agentType(AgentType.ADK).build());

        String json = tools.createAgent("adk-worker", "worker", "adk", null, null, null, null, null);

        assertThat(json).contains("\"ok\":true").contains("ADK");
        ArgumentCaptor<CreateAgentRequest> captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(agentService).createAgent(captor.capture());
        assertThat(captor.getValue().getAgentType()).isEqualTo(AgentType.ADK);
    }

    @Test
    void createAgent_mapsInvalidTypeToValidation() {
        String json = tools.createAgent("bad", "worker", "quantum", null, null, null, null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("NATIVE, ADK");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void createAgent_debugOn_includesStack() {
        mcpProperties.setDebug(true);

        String json = tools.createAgent("bad", "worker", "quantum", null, null, null, null, null);

        assertThat(json).contains("stackTrace").contains("IllegalArgumentException");
    }

    @Test
    void updateAgent_delegatesAllFields() {
        UUID id = UUID.randomUUID();
        when(agentService.updateAgent(eq(id), any())).thenReturn(AgentResponse.builder()
                .id(id).name("renamed").build());

        String json = tools.updateAgent(id, "renamed", null, null, null, null, null, null);

        assertThat(json).contains("\"ok\":true").contains("renamed");
        ArgumentCaptor<io.aria.conductor.agent.dto.UpdateAgentRequest> captor =
                ArgumentCaptor.forClass(io.aria.conductor.agent.dto.UpdateAgentRequest.class);
        verify(agentService).updateAgent(eq(id), captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("renamed");
        assertThat(captor.getValue().getRole()).isNull();
    }

    @Test
    void updateAgent_mapsNotFoundToErrorType() {
        UUID id = UUID.randomUUID();
        when(agentService.updateAgent(eq(id), any())).thenThrow(new ResourceNotFoundException("Agent", id));

        String json = tools.updateAgent(id, "renamed", null, null, null, null, null, null);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void retireAgent_returnsRetiredAgent() {
        UUID id = UUID.randomUUID();
        when(agentService.retireAgent(id)).thenReturn(AgentResponse.builder()
                .id(id).name("worker").healthStatus(HealthStatus.RETIRED).build());

        String json = tools.retireAgent(id);

        assertThat(json).contains("\"ok\":true").contains("RETIRED");
    }

    @Test
    void retireAgent_mapsNotFoundToErrorType() {
        UUID id = UUID.randomUUID();
        when(agentService.retireAgent(id)).thenThrow(new ResourceNotFoundException("Agent", id));

        String json = tools.retireAgent(id);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }
}
