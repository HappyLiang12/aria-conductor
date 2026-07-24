package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.dto.UpdateAgentRequest;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.LlmProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Error paths and argument handling of {@link AgentToolHandler} that the
 * base AgentToolHandlerTest does not cover: update_agent, not-found paths,
 * provider defaulting on create_agent and exception mapping.
 */
@ExtendWith(MockitoExtension.class)
class AgentToolHandlerEdgeCasesTest {

    @Mock private AgentService agentService;
    @Mock private AgentRepository agentRepository;
    @Mock private LlmProviderRepository llmProviderRepository;

    @InjectMocks
    private AgentToolHandler handler;

    @Test
    void updateAgent_delegatesFieldsToService() {
        UUID id = UUID.randomUUID();
        when(agentService.updateAgent(eq(id), any(UpdateAgentRequest.class)))
                .thenReturn(AgentResponse.builder().id(id).name("Renamed").build());

        String result = handler.execute(Map.of(
                "toolName", "update_agent",
                "id", id.toString(),
                "name", "Renamed",
                "role", "reviewer"));

        ArgumentCaptor<UpdateAgentRequest> captor = ArgumentCaptor.forClass(UpdateAgentRequest.class);
        verify(agentService).updateAgent(eq(id), captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Renamed");
        assertThat(captor.getValue().getRole()).isEqualTo("reviewer");
        assertThat(result).contains("Renamed").contains("updated").contains(id.toString());
    }

    @Test
    void updateAgent_missingIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "update_agent", "name", "X"));

        assertThat(result).startsWith("Error").contains("id");
        verifyNoInteractions(agentService);
    }

    @Test
    void updateAgent_malformedUuidIsMappedToError() {
        String result = handler.execute(Map.of(
                "toolName", "update_agent", "id", "not-a-uuid"));

        assertThat(result).startsWith("Error");
        verifyNoInteractions(agentService);
    }

    @Test
    void getAgent_missingIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "get_agent"));

        assertThat(result).startsWith("Error").contains("Missing required parameter: id");
    }

    @Test
    void getAgent_unresolvableNameReturnsNotFound() {
        when(agentRepository.findByName("ghost")).thenReturn(Optional.empty());

        String result = handler.execute(Map.of("toolName", "get_agent", "id", "ghost"));

        assertThat(result).isEqualTo("Error: Agent not found: ghost");
    }

    @Test
    void getAgent_uuidNotInRepositoryReturnsNotFound() {
        UUID id = UUID.randomUUID();
        when(agentRepository.findById(id)).thenReturn(Optional.empty());

        String result = handler.execute(Map.of("toolName", "get_agent", "id", id.toString()));

        assertThat(result).startsWith("Error: Agent not found");
    }

    @Test
    void createAgent_missingRoleReturnsError() {
        String result = handler.execute(Map.of("toolName", "create_agent", "name", "Bot"));

        assertThat(result).startsWith("Error").contains("role");
        verifyNoInteractions(agentService);
    }

    @Test
    void createAgent_withExplicitModelAndProviderSkipsProviderLookup() {
        when(agentService.createAgent(any(CreateAgentRequest.class)))
                .thenReturn(AgentResponse.builder().id(UUID.randomUUID()).name("Bot").build());

        String result = handler.execute(Map.of(
                "toolName", "create_agent",
                "name", "Bot", "role", "coder",
                "model", "gpt-4o", "provider", "openai"));

        verifyNoInteractions(llmProviderRepository);
        ArgumentCaptor<CreateAgentRequest> captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(agentService).createAgent(captor.capture());
        assertThat(captor.getValue().getModel()).isEqualTo("gpt-4o");
        assertThat(captor.getValue().getProvider()).isEqualTo("openai");
        assertThat(result).contains("created").contains("gpt-4o");
    }

    @Test
    void createAgent_defaultsModelAndProviderFromActiveProvider() {
        when(llmProviderRepository.findByActiveTrue()).thenReturn(Optional.of(
                LlmProvider.builder().name("deepseek").defaultModel("deepseek-chat").build()));
        when(agentService.createAgent(any(CreateAgentRequest.class)))
                .thenReturn(AgentResponse.builder().id(UUID.randomUUID()).name("Bot").build());

        handler.execute(Map.of("toolName", "create_agent", "name", "Bot", "role", "coder"));

        ArgumentCaptor<CreateAgentRequest> captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(agentService).createAgent(captor.capture());
        assertThat(captor.getValue().getModel()).isEqualTo("deepseek-chat");
        assertThat(captor.getValue().getProvider()).isEqualTo("deepseek");
        assertThat(captor.getValue().getAdkProvider()).isEqualTo("langchain");
    }

    @Test
    void createAgent_withoutActiveProviderReturnsActionableError() {
        when(llmProviderRepository.findByActiveTrue()).thenReturn(Optional.empty());

        String result = handler.execute(Map.of(
                "toolName", "create_agent", "name", "Bot", "role", "coder"));

        assertThat(result).startsWith("Error: No active LLM provider configured");
        verify(agentService, never()).createAgent(any());
    }

    @Test
    void retireAgent_missingIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "retire_agent"));

        assertThat(result).startsWith("Error").contains("id");
        verifyNoInteractions(agentService);
    }

    @Test
    void serviceExceptionIsMappedToErrorString() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("agent is protected"))
                .when(agentService).retireAgent(id);

        String result = handler.execute(Map.of("toolName", "retire_agent", "id", id.toString()));

        assertThat(result).isEqualTo("Error: agent is protected");
    }

    @Test
    void resolveAgentId_prefersUuidParsingOverNameLookup() {
        UUID id = UUID.randomUUID();

        UUID resolved = AgentToolHandler.resolveAgentId(agentRepository, " " + id + " ");

        assertThat(resolved).isEqualTo(id);
        verifyNoInteractions(agentRepository);
    }

    @Test
    void resolveAgentId_returnsNullForBlankInput() {
        assertThat(AgentToolHandler.resolveAgentId(agentRepository, "  ")).isNull();
        assertThat(AgentToolHandler.resolveAgentId(agentRepository, null)).isNull();
    }

    @Test
    void resolveAgentId_looksUpNameWhenNotAUuid() {
        UUID id = UUID.randomUUID();
        when(agentRepository.findByName("coder-bot"))
                .thenReturn(Optional.of(Agent.builder().id(id).name("coder-bot").build()));

        assertThat(AgentToolHandler.resolveAgentId(agentRepository, "coder-bot ")).isEqualTo(id);
    }
}
