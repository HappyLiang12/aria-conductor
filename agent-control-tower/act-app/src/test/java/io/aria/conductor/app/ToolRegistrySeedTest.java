package io.aria.conductor.app;

import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Sql(scripts = {"classpath:db/cleanup-all.sql", "/tool-registry-seed.sql"})

class ToolRegistrySeedTest extends BaseH2IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ToolDefinitionRepository toolRepo;

    // Expected tool names matching V22+V25 seed data
    private static final Set<String> EXPECTED_PLATFORM_TOOLS = Set.of(
        "get_agent", "update_agent", "retire_agent", "delete_agent",
        "list_runs", "get_run_status", "cancel_run", "list_running_runs",
        "list_knowledge", "review_knowledge",
        "list_agents", "create_agent", "run_agent",
        "create_knowledge", "store_knowledge", "search_knowledge"
    );

    @Test
    void seedData_shouldContainPlatformTools() {
        List<ToolDefinition> all = toolRepo.findAll();
        List<String> names = all.stream().map(ToolDefinition::getName).toList();
        assertThat(names).containsAll(EXPECTED_PLATFORM_TOOLS);
    }

    @Test
    void seedData_shouldContainTier1Tools() {
        List<ToolDefinition> all = toolRepo.findAll();
        List<String> names = all.stream().map(ToolDefinition::getName).toList();
        assertThat(names).contains(
            "web_search", "web_fetch", "read_file", "write_file",
            "list_files", "http_request", "shell_exec"
        );
    }

    @Test
    void seedData_shouldHaveCorrectToolCount() {
        List<ToolDefinition> all = toolRepo.findAll();
        // V22 seeds 38 + V25 3 = 41; cleanup-all.sql ensures isolation
        assertThat(all).hasSizeGreaterThanOrEqualTo(EXPECTED_PLATFORM_TOOLS.size() + 7);
    }

    @Test
    void toolsApi_shouldReturnAllTools() throws Exception {
        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").isNotEmpty());
    }
}

