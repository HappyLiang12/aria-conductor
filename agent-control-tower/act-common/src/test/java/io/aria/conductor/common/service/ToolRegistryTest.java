package io.aria.conductor.common.service;

import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    @Mock
    ToolDefinitionRepository toolRepo;

    @InjectMocks
    ToolRegistry toolRegistry;

    @Test
    void shouldBuildOpenAiToolsPayload() {
        ToolDefinition tool = ToolDefinition.builder()
                .name("web_search")
                .description("Search the web")
                .parameters("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}")
                .enabled(true)
                .build();
        when(toolRepo.findAllApprovedAndEnabled()).thenReturn(List.of(tool));

        List<Map<String, Object>> payload = toolRegistry.buildToolsPayloadForOpenAi();

        assertThat(payload).hasSize(1);
        assertThat(payload.get(0)).containsEntry("type", "function");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) payload.get(0).get("function");
        assertThat(function).containsEntry("name", "web_search");
    }

    @Test
    void shouldBuildToolsPayloadForSpecificIds() {
        ToolDefinition tool = ToolDefinition.builder()
                .id("tool-1").name("web_search").description("desc")
                .parameters("{\"type\":\"object\",\"properties\":{}}")
                .enabled(true).build();
        when(toolRepo.findAllById(List.of("tool-1"))).thenReturn(List.of(tool));

        List<Map<String, Object>> payload = toolRegistry.buildToolsPayloadForIds(List.of("tool-1"));
        assertThat(payload).hasSize(1);
    }

    @Test
    void shouldFilterDisabledTools() {
        ToolDefinition disabled = ToolDefinition.builder()
                .id("tool-2").name("disabled_tool").description("desc")
                .parameters("{\"type\":\"object\",\"properties\":{}}")
                .enabled(false).build();
        when(toolRepo.findAllById(List.of("tool-2"))).thenReturn(List.of(disabled));

        List<Map<String, Object>> payload = toolRegistry.buildToolsPayloadForIds(List.of("tool-2"));
        assertThat(payload).isEmpty();
    }
}
