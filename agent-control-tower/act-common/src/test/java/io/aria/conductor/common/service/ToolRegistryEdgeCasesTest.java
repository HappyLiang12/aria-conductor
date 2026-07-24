package io.aria.conductor.common.service;

import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Edge-case coverage for {@link ToolRegistry} beyond the happy path: malformed parameter JSON
 * falls back to an empty object schema, status filtering in {@code buildToolsPayloadForIds},
 * ordering/multiplicity, and simple delegation methods.
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryEdgeCasesTest {

    @Mock
    ToolDefinitionRepository toolRepo;

    @InjectMocks
    ToolRegistry toolRegistry;

    private ToolDefinition tool(String id, String name, String params, boolean enabled, VersionStatus status) {
        return ToolDefinition.builder()
                .id(id).name(name).description("desc-" + name)
                .parameters(params).enabled(enabled).status(status)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> functionOf(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("function");
    }

    @Test
    void malformedParametersJson_fallsBackToEmptyObjectSchema() {
        ToolDefinition broken = tool("t1", "broken", "{ this is not json", true, VersionStatus.APPROVED);
        when(toolRepo.findAllApprovedAndEnabled()).thenReturn(List.of(broken));

        List<Map<String, Object>> payload = toolRegistry.buildToolsPayloadForOpenAi();

        assertThat(payload).hasSize(1);
        Map<String, Object> function = functionOf(payload.get(0));
        assertThat(function).containsEntry("name", "broken");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) function.get("parameters");
        assertThat(params).containsEntry("type", "object");
        assertThat(params).containsKey("properties");
        assertThat((Map<?, ?>) params.get("properties")).isEmpty();
    }

    @Test
    void buildToolsPayloadForIds_filtersOutNonApprovedStatus() {
        ToolDefinition rejected = tool("t2", "rejected", "{\"type\":\"object\",\"properties\":{}}",
                true, VersionStatus.REJECTED);
        when(toolRepo.findAllById(List.of("t2"))).thenReturn(List.of(rejected));

        assertThat(toolRegistry.buildToolsPayloadForIds(List.of("t2"))).isEmpty();
    }

    @Test
    void buildToolsPayloadForIds_keepsNullStatusTools() {
        ToolDefinition nullStatus = tool("t3", "legacy", "{\"type\":\"object\",\"properties\":{}}",
                true, null);
        when(toolRepo.findAllById(List.of("t3"))).thenReturn(List.of(nullStatus));

        assertThat(toolRegistry.buildToolsPayloadForIds(List.of("t3"))).hasSize(1);
    }

    @Test
    void buildToolsPayload_preservesOrderAndParsesValidSchema() {
        ToolDefinition a = tool("a", "alpha",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}", true, VersionStatus.APPROVED);
        ToolDefinition b = tool("b", "beta",
                "{\"type\":\"object\",\"properties\":{}}", true, VersionStatus.APPROVED);
        when(toolRepo.findAllApprovedAndEnabled()).thenReturn(List.of(a, b));

        List<Map<String, Object>> payload = toolRegistry.buildToolsPayloadForOpenAi();

        assertThat(payload).hasSize(2);
        assertThat(functionOf(payload.get(0))).containsEntry("name", "alpha");
        assertThat(functionOf(payload.get(1))).containsEntry("name", "beta");
        assertThat(payload.get(0)).containsEntry("type", "function");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
                ((Map<String, Object>) functionOf(payload.get(0)).get("parameters")).get("properties");
        assertThat(props).containsKey("x");
    }

    @Test
    void buildToolsPayloadForOpenAi_emptyRepo_returnsEmptyList() {
        when(toolRepo.findAllApprovedAndEnabled()).thenReturn(List.of());
        assertThat(toolRegistry.buildToolsPayloadForOpenAi()).isEmpty();
    }

    @Test
    void getByName_delegatesToRepository() {
        ToolDefinition def = tool("t4", "web_search", "{}", true, VersionStatus.APPROVED);
        when(toolRepo.findByName("web_search")).thenReturn(Optional.of(def));

        Optional<ToolDefinition> result = toolRegistry.getByName("web_search");
        assertThat(result).containsSame(def);
    }

    @Test
    void getAllEnabledByTier_delegatesToRepository() {
        ToolDefinition def = tool("t5", "tiered", "{}", true, VersionStatus.APPROVED);
        when(toolRepo.findByTierAndEnabledTrue("TIER_1")).thenReturn(List.of(def));

        assertThat(toolRegistry.getAllEnabledByTier("TIER_1")).containsExactly(def);
    }
}
