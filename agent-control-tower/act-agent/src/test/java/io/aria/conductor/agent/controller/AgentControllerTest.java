package io.aria.conductor.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.RoleDefaultsResponse;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.AgentTemplateService;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
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
 * MockMvc slice coverage for the tool/skill assignment + role-defaults endpoints.
 * Uses standaloneSetup (no Spring context / Flyway) so it verifies routing, request
 * binding, validation, and status codes independently of the database.
 */
class AgentControllerTest {

    private AgentService agentService;
    private AgentTemplateService templateService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        templateService = mock(AgentTemplateService.class);
        AgentController controller = new AgentController(agentService, templateService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listAgentSkills_returnsSkills() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.getAgentSkills(id))
                .thenReturn(List.of(new SkillContext("s1", "triage", "d", "t", "SKILL")));

        mockMvc.perform(get("/api/v1/agents/" + id + "/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("s1"))
                .andExpect(jsonPath("$[0].name").value("triage"));
    }

    @Test
    void assignSkill_returns201_andDelegates() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.getAgentSkills(id)).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/agents/" + id + "/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("skillId", "s1"))))
                .andExpect(status().isCreated());
        verify(agentService).assignSkill(eq(id), eq("s1"));
    }

    @Test
    void assignSkill_returns400_whenSkillIdBlank() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/agents/" + id + "/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("skillId", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unassignSkill_returnsOk_andDelegates() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.getAgentSkills(id)).thenReturn(List.of());

        mockMvc.perform(delete("/api/v1/agents/" + id + "/skills/s1"))
                .andExpect(status().isOk());
        verify(agentService).unassignSkill(eq(id), eq("s1"));
    }

    @Test
    void setTools_bulkReplace_delegatesWithIds() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.setTools(eq(id), eq(List.of("t1", "t2")))).thenReturn(List.of());

        mockMvc.perform(put("/api/v1/agents/" + id + "/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ids", List.of("t1", "t2")))))
                .andExpect(status().isOk());
        verify(agentService).setTools(eq(id), eq(List.of("t1", "t2")));
    }

    @Test
    void setSkills_bulkReplace_delegatesWithIds() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentService.setSkills(eq(id), eq(List.of("s1")))).thenReturn(List.of());

        mockMvc.perform(put("/api/v1/agents/" + id + "/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ids", List.of("s1")))))
                .andExpect(status().isOk());
        verify(agentService).setSkills(eq(id), eq(List.of("s1")));
    }

    @Test
    void getRoleDefaults_returnsToolsAndSkills() throws Exception {
        when(agentService.getRoleDefaults("dev")).thenReturn(new RoleDefaultsResponse(
                List.of(ToolDefinition.builder().id("t1").name("read_file").enabled(true).build()),
                List.of(new SkillContext("s1", "triage", "d", "t", "SKILL"))));

        mockMvc.perform(get("/api/v1/agents/role-defaults/dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0].name").value("read_file"))
                .andExpect(jsonPath("$.skills[0].id").value("s1"));
    }
}
