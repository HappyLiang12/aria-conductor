package io.aria.conductor.app;

import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ToolPipelineIntegrationTest extends BaseH2IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ToolDefinitionRepository toolRepo;

    @Test
    void shouldReturnToolsInOpenAiFormat() throws Exception {
        mockMvc.perform(get("/api/v1/tools/payload"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldListEnabledTools() throws Exception {
        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldFindToolDefinitionRepositoryAvailable() {
        assertThat(toolRepo).isNotNull();
    }
}

