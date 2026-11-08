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
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the governance risk tier for a tool by name.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>A configured {@code tool_definitions} row always wins, including a
 *       legacy row whose tier is {@code null} (which resolves to READ).</li>
 *   <li>Native ACP tool names that have no configured row are classified by the
 *       immutable {@link #NATIVE_TOOL_TIERS} map: {@code bash} is DESTRUCTIVE,
 *       {@code write}/{@code edit} are PUSH and the read-only tools are READ.
 *       The native names are compared case-insensitively because the live cores
 *       emit {@code Bash}/{@code Write}/{@code Edit} while others use lower
 *       case.</li>
 *   <li>Any other unknown tool resolves to READ (lowest) to preserve backward
 *       compatibility.</li>
 * </ol>
 *
 * <p>A Caffeine cache with 1-minute TTL ensures admin changes propagate within
 * 60s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRiskResolver {

    /**
     * Native ACP tools, keyed by their case-normalized name. Every native core
     * ships tools that have no {@code tool_definitions} row; without this map
     * they all fell through to READ, so a Bash/Edit/Write approval was stamped
     * as read-only. The map is immutable and only consulted when no configured
     * row exists.
     */
    private static final Map<String, RiskTier> NATIVE_TOOL_TIERS = Map.of(
            "bash", RiskTier.DESTRUCTIVE,
            "write", RiskTier.PUSH,
            "edit", RiskTier.PUSH,
            "read", RiskTier.READ,
            "glob", RiskTier.READ,
            "grep", RiskTier.READ,
            "ls", RiskTier.READ,
            "webfetch", RiskTier.READ,
            "websearch", RiskTier.READ);

    private final ToolDefinitionRepository toolRepo;

    private final Cache<String, RiskTier> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    public RiskTier resolve(String toolName) {
        // Optional.map treats a null mapper result as empty, which would let a configured row
        // whose tier is null fall through to the native map (bash -> DESTRUCTIVE). Resolve the
        // row's null tier to READ here so the configured row always wins; only an absent row
        // consults nativeTier.
        return cache.get(toolName, name -> toolRepo.findByName(name)
                .map(ToolRiskResolver::configuredTier)
                .orElseGet(() -> nativeTier(name)));
    }

    /** The configured row's tier; a legacy row with no tier is READ. */
    private static RiskTier configuredTier(ToolDefinition row) {
        RiskTier tier = row.getRiskTier();
        return tier == null ? RiskTier.READ : tier;
    }

    /** The native ACP tier for a name, or READ when the name is not a native tool. */
    private static RiskTier nativeTier(String toolName) {
        if (toolName == null) {
            return RiskTier.READ;
        }
        RiskTier tier = NATIVE_TOOL_TIERS.get(toolName.toLowerCase(Locale.ROOT));
        return tier == null ? RiskTier.READ : tier;
    }

    public boolean requiresApproval(String toolName) {
        RiskTier tier = resolve(toolName);
        return tier == RiskTier.PUSH || tier == RiskTier.DESTRUCTIVE;
    }
}
