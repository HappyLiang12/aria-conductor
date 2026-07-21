package io.aria.conductor.common.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRegistry {

    private final ToolDefinitionRepository toolRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Map<String, Object>> buildToolsPayloadForOpenAi() {
        List<ToolDefinition> tools = toolRepo.findAllApprovedAndEnabled();
        return buildToolsPayload(tools);
    }

    public List<Map<String, Object>> buildToolsPayloadForIds(List<String> toolIds) {
        List<ToolDefinition> tools = toolRepo.findAllById(toolIds).stream()
                .filter(ToolDefinition::isEnabled)
                .filter(t -> t.getStatus() == null || t.getStatus() == VersionStatus.APPROVED)
                .toList();
        return buildToolsPayload(tools);
    }

    private List<Map<String, Object>> buildToolsPayload(List<ToolDefinition> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition def : tools) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", def.getName());
            function.put("description", def.getDescription());

            try {
                Map<String, Object> params = objectMapper.readValue(
                        def.getParameters(), new TypeReference<Map<String, Object>>() {});
                function.put("parameters", params);
            } catch (Exception e) {
                log.error("Failed to parse parameters JSON for tool {}", def.getName(), e);
                Map<String, Object> emptyParams = new LinkedHashMap<>();
                emptyParams.put("type", "object");
                emptyParams.put("properties", Map.of());
                function.put("parameters", emptyParams);
            }

            tool.put("function", function);
            result.add(tool);
        }
        return result;
    }

    public Optional<ToolDefinition> getByName(String name) {
        return toolRepo.findByName(name);
    }

    public List<ToolDefinition> getAllEnabledByTier(String tier) {
        return toolRepo.findByTierAndEnabledTrue(tier);
    }
}
