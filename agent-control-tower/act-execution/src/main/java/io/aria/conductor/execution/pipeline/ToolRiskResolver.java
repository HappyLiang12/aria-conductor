package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Resolves the governance risk tier for a tool by name.
 * Returns READ (lowest) for unknown tools to preserve backward compatibility.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRiskResolver {

    private final ToolDefinitionRepository toolRepo;

    @Cacheable(value = "tools", key = "#toolName")
    public RiskTier resolve(String toolName) {
        return toolRepo.findByName(toolName)
                .map(ToolDefinition::getRiskTier)
                .orElse(RiskTier.READ);
    }

    public boolean requiresApproval(String toolName) {
        RiskTier tier = resolve(toolName);
        return tier == RiskTier.PUSH || tier == RiskTier.DESTRUCTIVE;
    }
}
