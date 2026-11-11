package io.aria.conductor.execution.pipeline;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.common.tool.ToolCatalogProvider;
import io.aria.conductor.common.tool.ToolDescriptor;
import io.aria.conductor.common.tool.ToolPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the governance risk tier for a tool by name.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>A configured {@code tool_definitions} row always wins, including a
 *       legacy row whose tier is {@code null} (which resolves to READ).</li>
 *   <li>The reviewed tool catalog in act-common classifies the platform MCP
 *       surface (spec §5.3): the {@code mcp__aria-conductor__} prefix is stripped
 *       and the bare name is looked up, so {@code remember_fact} is a WRITE_LOCAL
 *       and {@code decide_memory_proposal} a PUSH instead of every platform tool
 *       being stamped READ.</li>
 *   <li>Native ACP tool names that have no configured row are classified by the
 *       immutable {@link #NATIVE_TOOL_TIERS} map: {@code bash} is DESTRUCTIVE,
 *       {@code write}/{@code edit} are PUSH and the read-only tools are READ.
 *       The native names are compared case-insensitively because the live cores
 *       emit {@code Bash}/{@code Write}/{@code Edit} while others use lower
 *       case.</li>
 *   <li>Any other unknown tool resolves to READ (lowest) to preserve backward
 *       compatibility; {@link #policyOf} returns null for it rather than
 *       inventing an authority class.</li>
 * </ol>
 *
 * <p>A Caffeine cache with 1-minute TTL ensures admin changes propagate within
 * 60s.
 */
@Slf4j
@Component
public class ToolRiskResolver {

    /** The prefix the platform MCP surface binds every tool under. */
    private static final String PLATFORM_MCP_PREFIX = "mcp__aria-conductor__";

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

    /**
     * The reviewed catalog. Null when no provider is wired: act-mcp owns the
     * platform catalog and this module cannot depend on it, so an act-execution-only
     * context (or a module test) simply has no catalog to consult and falls
     * through to the native map - the same seam {@code ToolCatalogContextContributor}
     * uses.
     */
    private final ToolCatalogProvider catalog;

    private final Cache<String, RiskTier> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    private final Cache<String, Optional<ToolPolicy>> policyCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    @Autowired
    public ToolRiskResolver(ToolDefinitionRepository toolRepo, ObjectProvider<ToolCatalogProvider> catalogs) {
        this(toolRepo, catalogs.getIfAvailable());
    }

    /** Test seam: a catalog supplied directly. */
    public ToolRiskResolver(ToolDefinitionRepository toolRepo, ToolCatalogProvider catalog) {
        this.toolRepo = toolRepo;
        this.catalog = catalog;
    }

    public RiskTier resolve(String toolName) {
        // Optional.map treats a null mapper result as empty, which would let a configured row
        // whose tier is null fall through to the native map (bash -> DESTRUCTIVE). Resolve the
        // row's null tier to READ here so the configured row always wins; only an absent row
        // consults the catalog and then nativeTier.
        return cache.get(toolName, name -> toolRepo.findByName(name)
                .map(ToolRiskResolver::configuredTier)
                .orElseGet(() -> catalogTier(name).orElseGet(() -> nativeTier(name))));
    }

    /** The reviewed authority class of a tool, or null when the catalog does not know it. */
    public ToolPolicy policyOf(String toolName) {
        if (toolName == null) {
            return null;
        }
        return policyCache.get(toolName, this::catalogPolicy).orElse(null);
    }

    /** The catalog's tier for a name, or empty when no catalog is wired or it is unknown. */
    private Optional<RiskTier> catalogTier(String name) {
        return catalogPolicy(name).map(ToolRiskResolver::tierOf);
    }

    /** The catalog's policy for a name, keyed by the bare name without the MCP prefix. */
    private Optional<ToolPolicy> catalogPolicy(String name) {
        if (catalog == null || name == null) {
            return Optional.empty();
        }
        String bare = name.startsWith(PLATFORM_MCP_PREFIX)
                ? name.substring(PLATFORM_MCP_PREFIX.length())
                : name;
        return catalog.tools().stream()
                .filter(tool -> tool.name().equals(bare))
                .map(ToolDescriptor::policy)
                .findFirst();
    }

    /**
     * Policy and tier are two axes of the same reviewed classification: the
     * authority class decides the blast radius the approval card shows.
     */
    private static RiskTier tierOf(ToolPolicy policy) {
        return switch (policy) {
            case WORKER_READ -> RiskTier.READ;
            case WORKER_WRITE -> RiskTier.WRITE_LOCAL;
            case OPERATOR_ONLY, GATED -> RiskTier.PUSH;
        };
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
