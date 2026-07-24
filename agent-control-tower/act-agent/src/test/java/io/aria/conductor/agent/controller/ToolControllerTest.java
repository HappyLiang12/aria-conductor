package io.aria.conductor.agent.controller;

import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.test.TestDataBuilder;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ToolControllerTest extends WebMvcTestBase {

    private final ToolDefinitionRepository toolRepo = mock(ToolDefinitionRepository.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final MockMvc mvc = mockMvcFor(new ToolController(toolRepo, toolRegistry));

    @Test
    void listTools_noFilter_returnsAllEnabled() throws Exception {
        when(toolRepo.findByEnabledTrue()).thenReturn(List.of(
                TestDataBuilder.aToolDefinition().withName("read_file").build()));

        mvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("read_file"));
        verify(toolRepo).findByEnabledTrue();
    }

    @Test
    void listTools_byTier_usesTierQuery() throws Exception {
        when(toolRepo.findByTierAndEnabledTrue("TIER_1")).thenReturn(List.of(
                TestDataBuilder.aToolDefinition().withName("t1").withTier("TIER_1").build()));

        mvc.perform(get("/api/v1/tools").param("tier", "TIER_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("t1"));
        verify(toolRepo).findByTierAndEnabledTrue("TIER_1");
        verify(toolRepo, never()).findByEnabledTrue();
    }

    @Test
    void listTools_byCategory_usesCategoryQuery() throws Exception {
        when(toolRepo.findByCategoryAndEnabledTrue("GIT")).thenReturn(List.of(
                TestDataBuilder.aToolDefinition().withName("git_clone").withCategory("GIT").build()));

        mvc.perform(get("/api/v1/tools").param("category", "GIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("git_clone"));
        verify(toolRepo).findByCategoryAndEnabledTrue("GIT");
    }

    @Test
    void getTool_returns200WhenFound() throws Exception {
        when(toolRepo.findById("t1")).thenReturn(Optional.of(
                TestDataBuilder.aToolDefinition().withId("t1").withName("read_file").build()));

        mvc.perform(get("/api/v1/tools/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t1"))
                .andExpect(jsonPath("$.name").value("read_file"));
    }

    @Test
    void getTool_returns404WhenMissing() throws Exception {
        when(toolRepo.findById("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/tools/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggleTool_flipsEnabledFlagAndPersists() throws Exception {
        ToolDefinition tool = TestDataBuilder.aToolDefinition().withId("t1").withEnabled(true).build();
        when(toolRepo.findById("t1")).thenReturn(Optional.of(tool));
        when(toolRepo.save(any(ToolDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/tools/t1/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        // The in-place mutation must have flipped the flag before saving.
        org.assertj.core.api.Assertions.assertThat(tool.isEnabled()).isFalse();
    }

    @Test
    void toggleTool_returns404WhenMissing() throws Exception {
        when(toolRepo.findById("nope")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/tools/nope/toggle"))
                .andExpect(status().isNotFound());
        verify(toolRepo, never()).save(any());
    }

    @Test
    void getToolsPayload_returnsOpenAiPayloadFromRegistry() throws Exception {
        when(toolRegistry.buildToolsPayloadForOpenAi()).thenReturn(List.of(
                Map.of("type", "function", "function", Map.of("name", "read_file"))));

        mvc.perform(get("/api/v1/tools/payload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("function"))
                .andExpect(jsonPath("$[0].function.name").value("read_file"));
    }
}
