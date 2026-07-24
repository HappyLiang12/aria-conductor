package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.AgentTemplateDTO;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.AgentTemplateService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

    private final AgentService agentService = mock(AgentService.class);
    private final AgentTemplateService templateService = mock(AgentTemplateService.class);
    private final MockMvc mvc = mockMvcFor(new AgentController(agentService, templateService));

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
                .tools(List.of("read_file"))
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
                .andExpect(jsonPath("$.healthStatus").value("HEALTHY"))
                .andExpect(jsonPath("$.tools[0]").value("read_file"));
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

    @Test
    void listAgentTools_returnsTools() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.getAgentTools(id)).thenReturn(List.of(
                ToolDefinition.builder().id("t1").name("read_file").enabled(true).build()));

        mvc.perform(get("/api/v1/agents/" + id + "/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t1"))
                .andExpect(jsonPath("$[0].name").value("read_file"));
    }

    @Test
    void assignTool_returns201_andReturnsUpdatedToolList() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.getAgentTools(id)).thenReturn(List.of(
                ToolDefinition.builder().id("t1").name("read_file").enabled(true).build()));

        mvc.perform(post("/api/v1/agents/" + id + "/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("toolId", "t1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].id").value("t1"));
        verify(agentService).assignTool(id, "t1");
    }

    @Test
    void assignTool_notApproved_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("Tool 'shell_exec' is not approved/enabled and cannot be assigned"))
                .when(agentService).assignTool(id, "t1");

        mvc.perform(post("/api/v1/agents/" + id + "/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("toolId", "t1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void unassignTool_returnsOk_andDelegates() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.getAgentTools(id)).thenReturn(List.of());

        mvc.perform(delete("/api/v1/agents/" + id + "/tools/t1"))
                .andExpect(status().isOk());
        verify(agentService).unassignTool(id, "t1");
    }
}
