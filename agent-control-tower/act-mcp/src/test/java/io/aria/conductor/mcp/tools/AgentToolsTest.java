package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.mcp.McpActorContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolsTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");

    @Mock AgentService agentService;
    McpProperties mcpProperties;
    AgentTools tools;

    /** The real reviewed policy: retire_agent is OPERATOR_ONLY. */
    private final ToolPolicyRegistry toolPolicies = new ToolPolicyRegistry();

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new AgentTools(agentService, toolPolicies, mcpProperties);
    }

    private ToolContext context(ActorPrincipal actor) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpActorContext.transportContext(actor));
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }

    private ToolContext workerContext() {
        return context(ActorPrincipal.worker(RUN_ID, Instant.parse("2026-11-09T12:10:00Z")));
    }

    private ToolContext operatorContext() {
        return context(ActorPrincipal.operator(null));
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
                .id(id).name("worker").agentType(AgentType.ADK).healthStatus(HealthStatus.HEALTHY).build());

        String json = tools.getAgent(id);

        assertThat(json).contains("\"ok\":true").contains("worker").contains("ADK");
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
    void createAgent_defaultsToAdkType() {
        when(agentService.createAgent(any())).thenReturn(AgentResponse.builder()
                .id(UUID.randomUUID()).name("developer").agentType(AgentType.ADK).build());

        String json = tools.createAgent("developer", "worker", null, "builds things",
                "gpt-4o", "openai", "opencode", null);

        assertThat(json).contains("\"ok\":true").contains("developer");
        ArgumentCaptor<CreateAgentRequest> captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(agentService).createAgent(captor.capture());
        assertThat(captor.getValue().getAgentType()).isEqualTo(AgentType.ADK);
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

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("ADK");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void createAgent_debugOn_stillHasNoStack() {
        // Fix round 7: debug mode logs the cause server-side; the tool answer is
        // always the truthful ok/errorType/message envelope.
        mcpProperties.setDebug(true);

        String json = tools.createAgent("bad", "worker", "quantum", null, null, null, null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("ADK");
        assertThat(json).doesNotContain("stackTrace");
        assertThat(json).doesNotContain("IllegalArgumentException");
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

        String json = tools.retireAgent(id, operatorContext());

        assertThat(json).contains("\"ok\":true").contains("RETIRED");
    }

    @Test
    void retireAgent_mapsNotFoundToErrorType() {
        UUID id = UUID.randomUUID();
        when(agentService.retireAgent(id)).thenThrow(new ResourceNotFoundException("Agent", id));

        String json = tools.retireAgent(id, operatorContext());

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    /**
     * C4/F9: retirement is a catalog OPERATOR_ONLY tool, so a run-scoped worker is refused
     * with an actionable message naming where the operator can retire, and the agent service
     * is never reached.
     */
    @Test
    void aWorkerActorCannotRetireAnAgent() {
        UUID id = UUID.randomUUID();

        String json = tools.retireAgent(id, workerContext());

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("\"message\":\"Operator authority required: retiring an agent is an operator decision. "
                        + "Retire it on the Crew page.\"");
        verifyNoInteractions(agentService);
    }

    @Test
    void anOperatorActorCanStillRetireAnAgent() {
        UUID id = UUID.randomUUID();
        when(agentService.retireAgent(id)).thenReturn(AgentResponse.builder()
                .id(id).name("worker").healthStatus(HealthStatus.RETIRED).build());

        String json = tools.retireAgent(id, operatorContext());

        assertThat(json).contains("\"ok\":true");
        verify(agentService).retireAgent(id);
    }

    /** Fail closed: without a transport actor there is no operator credential to honour. */
    @Test
    void retireAgentWithoutATransportActorIsForbidden() {
        UUID id = UUID.randomUUID();

        String json = tools.retireAgent(id, new ToolContext(Map.of()));

        assertThat(json).contains("\"ok\":false").contains("\"errorType\":\"FORBIDDEN\"");
        verifyNoInteractions(agentService);
    }
}
