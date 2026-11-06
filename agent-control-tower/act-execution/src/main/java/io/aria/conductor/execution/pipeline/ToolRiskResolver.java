package io.aria.conductor.execution.pipeline;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Resolves the governance risk tier for a tool by name.
 * Returns READ (lowest) for unknown tools to preserve backward compatibility.
 * Caffeine cache with 1-minute TTL ensures admin changes propagate within 60s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRiskResolver {

    private final ToolDefinitionRepository toolRepo;

    private final Cache<String, RiskTier> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    public RiskTier resolve(String toolName) {
        return cache.get(toolName, name -> toolRepo.findByName(name)
                .map(ToolDefinition::getRiskTier)
                .orElse(RiskTier.READ));
    }

    public boolean requiresApproval(String toolName) {
        RiskTier tier = resolve(toolName);
        return tier == RiskTier.PUSH || tier == RiskTier.DESTRUCTIVE;
    }
}
