package io.aria.conductor.dashboard.agent;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.AgentTemplateDTO;
import io.aria.conductor.agent.dto.ApplyRuntimeRequest;
import io.aria.conductor.agent.dto.ApplyRuntimeResult;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.AgentTemplateService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.test.WebMvcTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc slice coverage for the AgentController CRUD + template + tool-listing endpoints.
 * The tool/skill assignment and role-default endpoints are covered by {@link AgentControllerTest};
 * this class deliberately does not duplicate them. Uses {@link WebMvcTestBase} so the production
 * GlobalExceptionHandler translates service exceptions into 404/400/409 responses.
 */
class AgentControllerCrudTest extends WebMvcTestBase {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String OPERATOR_AUTHORIZATION = "Bearer operator-bearer-secret";
    private static final String WORKER_AUTHORIZATION = "Bearer worker-token";

    private final AgentService agentService = mock(AgentService.class);
    private final AgentTemplateService templateService = mock(AgentTemplateService.class);
    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final OperatorSessionService sessions = new OperatorSessionService(
            "operator-bearer-secret", Duration.ofHours(8), "http://localhost:5173", false,
            Clock.systemUTC());
    private final OperatorAuthorityResolver operatorAuthority =
            new OperatorAuthorityResolver(sessions, actorTokens, "");
    private final MockMvc mvc = mockMvcFor(
            new AgentController(agentService, templateService, operatorAuthority));

    private AgentResponse sampleAgent(UUID id) {
        return AgentResponse.builder()
                .id(id)
                .name("triage-bot")
                .agentType(AgentType.ADK)
                .role("dev")
                .model("ali-copilot")
                .provider("alibaba")
                .healthStatus(HealthStatus.HEALTHY)
                .skills(List.of("triage"))
                .build();
    }

    @Test
    void createAgent_returns201WithSerializedBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.createAgent(any())).thenReturn(sampleAgent(id));

        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "triage-bot", "agentType", "ADK", "role", "dev"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("triage-bot"))
                .andExpect(jsonPath("$.agentType").value("ADK"))
                .andExpect(jsonPath("$.healthStatus").value("HEALTHY"));
    }

    @Test
    void createAgent_blankName_returns400() throws Exception {
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "", "agentType", "ADK"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAgent_missingType_returns400() throws Exception {
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "no-type"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAgents_returnsAllActiveAgents() throws Exception {
        when(agentService.listAgents()).thenReturn(List.of(sampleAgent(UUID.randomUUID()),
                sampleAgent(UUID.randomUUID())));

        mvc.perform(get("/api/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("triage-bot"));
    }

    @Test
    void getAgent_returnsBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.getAgent(id)).thenReturn(sampleAgent(id));

        mvc.perform(get("/api/v1/agents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.role").value("dev"));
    }

    @Test
    void getAgent_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.getAgent(id)).thenThrow(new ResourceNotFoundException("Agent", id));

        mvc.perform(get("/api/v1/agents/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void updateAgent_returnsUpdatedBody() throws Exception {
        UUID id = UUID.randomUUID();
        AgentResponse updated = sampleAgent(id);
        updated.setModel("gpt-4o");
        when(agentService.updateAgent(eq(id), any())).thenReturn(updated);

        mvc.perform(put("/api/v1/agents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("model", "gpt-4o"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-4o"));
    }

    @Test
    void updateAgent_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.updateAgent(eq(id), any())).thenThrow(new ResourceNotFoundException("Agent", id));

        mvc.perform(put("/api/v1/agents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("model", "gpt-4o"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void retireAgent_returnsRetiredStatus() throws Exception {
        UUID id = UUID.randomUUID();
        AgentResponse retired = sampleAgent(id);
        retired.setHealthStatus(HealthStatus.RETIRED);
        when(agentService.retireAgent(id)).thenReturn(retired);

        mvc.perform(post("/api/v1/agents/" + id + "/retire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthStatus").value("RETIRED"));
        verify(agentService).retireAgent(id);
    }

    // ------------------------------------------------------ retire authority boundary

    /** The actionable refusal shared with MCP retire_agent (same action, same surface). */
    private static final String RETIRE_REFUSAL = "Operator authority required: retiring an agent"
            + " is an operator decision. Retire it on the Crew page.";

    private static RequestPostProcessor remote() {
        return request -> {
            request.setRemoteAddr("203.0.113.7");
            return request;
        };
    }

    @Test
    void retireAgent_workerBearerIsForbidden_andNeverRetires() throws Exception {
        when(actorTokens.resolveBearer(WORKER_AUTHORIZATION)).thenReturn(Optional.of(
                ActorPrincipal.worker(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)))));
        UUID id = UUID.randomUUID();

        mvc.perform(post("/api/v1/agents/" + id + "/retire")
                        .header("Authorization", WORKER_AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(RETIRE_REFUSAL));

        verify(agentService, never()).retireAgent(any());
    }

    @Test
    void retireAgent_anonymousRemoteIsUnauthorized_andNeverRetires() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/api/v1/agents/" + id + "/retire").with(remote()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(RETIRE_REFUSAL));

        verify(agentService, never()).retireAgent(any());
    }

    /** Positive control: the operator bearer credential is honoured from a remote address. */
    @Test
    void retireAgent_operatorBearerFromRemoteSucceeds() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.retireAgent(id)).thenReturn(sampleAgent(id));

        mvc.perform(post("/api/v1/agents/" + id + "/retire")
                        .with(remote())
                        .header("Authorization", OPERATOR_AUTHORIZATION))
                .andExpect(status().isOk());

        verify(agentService).retireAgent(id);
    }

    // ------------------------------------------- fleet runtime authority boundary

    /**
     * {@code PUT /runtime} rewrites the whole fleet's (core, mode) pair, so a sandbox
     * worker that can reach the port must not reach this route: it has no MCP twin to
     * parity with, and the only callers are the dashboard and the fleet-wide error
     * messages the uniformity guard itself points the operator at.
     */
    private static final String RUNTIME_REFUSAL = "Operator authority required: changing the fleet"
            + " runtime is an operator decision. Change it fleet-wide in Configure > Runtime.";

    private static ApplyRuntimeRequest runtimeRequest() {
        return new ApplyRuntimeRequest("qoder", ExecutionMode.HOST);
    }

    /** A live operator session cookie, carrying the Origin and CSRF a mutation needs. */
    private static RequestPostProcessor operatorSessionMutation(OperatorSessionService sessions) {
        OperatorSessionService.OperatorSession session = sessions.createSession();
        return request -> {
            request.setRemoteAddr("203.0.113.7");
            request.setCookies(new Cookie(OperatorSessionService.COOKIE_NAME, session.sessionId()));
            request.addHeader("Origin", "http://localhost:5173");
            request.addHeader(OperatorSessionService.CSRF_HEADER, session.csrfToken());
            return request;
        };
    }

    @Test
    void applyRuntime_workerBearerIsForbidden_andNeverConvergesTheFleet() throws Exception {
        when(actorTokens.resolveBearer(WORKER_AUTHORIZATION)).thenReturn(Optional.of(
                ActorPrincipal.worker(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)))));

        mvc.perform(put("/api/v1/agents/runtime")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", WORKER_AUTHORIZATION)
                        .content(json(runtimeRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(RUNTIME_REFUSAL));

        verify(agentService, never()).applyRuntimeToAll(any(), any());
    }

    @Test
    void applyRuntime_anonymousRemoteIsUnauthorized_andNeverConvergesTheFleet() throws Exception {
        mvc.perform(put("/api/v1/agents/runtime")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(remote())
                        .content(json(runtimeRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(RUNTIME_REFUSAL));

        verify(agentService, never()).applyRuntimeToAll(any(), any());
    }

    /** The dashboard's authenticated path: an operator session cookie from a remote peer. */
    @Test
    void applyRuntime_operatorSessionCookieConvergesTheFleet() throws Exception {
        when(agentService.applyRuntimeToAll("qoder", ExecutionMode.HOST))
                .thenReturn(new ApplyRuntimeResult("qoder", ExecutionMode.HOST, 2));

        mvc.perform(put("/api/v1/agents/runtime")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(operatorSessionMutation(sessions))
                        .content(json(runtimeRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtime.adkProvider").value("qoder"))
                .andExpect(jsonPath("$.runtime.executionMode").value("HOST"))
                .andExpect(jsonPath("$.updated").value(2));

        verify(agentService).applyRuntimeToAll("qoder", ExecutionMode.HOST);
    }

    /** Reads stay open: the dashboard does not authenticate its inventory reads. */
    @Test
    void listAgents_anonymousRemoteIsStillReadable() throws Exception {
        when(agentService.listAgents()).thenReturn(List.of(sampleAgent(UUID.randomUUID())));

        mvc.perform(get("/api/v1/agents").with(remote()))
                .andExpect(status().isOk());
    }

    @Test
    void listTemplates_returnsTemplates() throws Exception {
        when(templateService.listTemplates()).thenReturn(List.of(
                AgentTemplateDTO.builder().id("dev").label("Developer").agentType(AgentType.ADK)
                        .role("dev").model("ali-copilot").provider("alibaba").build(),
                AgentTemplateDTO.builder().id("qa").label("QA").agentType(AgentType.ADK)
                        .role("qa").model("ali-copilot").provider("alibaba").build()));

        mvc.perform(get("/api/v1/agents/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("dev"))
                .andExpect(jsonPath("$[1].role").value("qa"));
    }

    @Test
    void createFromTemplate_returns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.createFromTemplate("dev")).thenReturn(sampleAgent(id));

        mvc.perform(post("/api/v1/agents/from-template/dev"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
        verify(templateService).createFromTemplate("dev");
    }

    @Test
    void createFromTemplate_unknownTemplate_returns400() throws Exception {
        when(templateService.createFromTemplate("ghost"))
                .thenThrow(new IllegalArgumentException("Unknown template: ghost"));

        mvc.perform(post("/api/v1/agents/from-template/ghost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

}
