package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.common.tool.ToolDescriptor;
import io.aria.conductor.common.tool.ToolPolicy;
import io.aria.conductor.mcp.catalog.ToolCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/**
 * The reviewed tool policy of the platform MCP surface (spec §6, "explicit
 * policy, not naming heuristics"). Every tool the embedded MCP endpoint
 * registers is classified explicitly in {@link ToolCatalog}; names starting
 * with {@code list_}, {@code get_} or {@code query_} are evidence of nothing,
 * and an unclassified tool is GATED — never automatically allowed.
 *
 * <ul>
 *   <li>{@code WORKER_READ} — no mutation; the only class an execution boundary
 *       may allow automatically.</li>
 *   <li>{@code WORKER_WRITE} — a side-effecting call that requires a run-bound
 *       approve-once/deny decision and a consumed one-use grant at the
 *       execution boundary ({@code WriteGrantService.consume}).</li>
 *   <li>{@code OPERATOR_ONLY} — approval decisions, retirement execution,
 *       destructive batch maintenance and credential/provider management; a
 *       run-scoped worker credential is never sufficient.</li>
 *   <li>{@code GATED} — unclassified: the answer for every tool name that is not
 *       in the reviewed map, including names the registry has never seen.</li>
 * </ul>
 */
@Component
public class ToolPolicyRegistry {

    private final ToolCatalog catalog;

    /** Test convenience: the catalog is stateless, so a fresh instance is equivalent. */
    ToolPolicyRegistry() {
        this(new ToolCatalog());
    }

    @Autowired
    public ToolPolicyRegistry(ToolCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** The reviewed classification; an unknown name is GATED, never guessed. */
    public ToolPolicy policyOf(String toolName) {
        return catalog.find(toolName).map(ToolDescriptor::policy).orElse(ToolPolicy.GATED);
    }

    /** True only for an explicitly classified read-only tool. */
    public boolean isAutomaticallyAllowed(String toolName) {
        return policyOf(toolName) == ToolPolicy.WORKER_READ;
    }

    /** True only for an explicitly classified operator-only tool. */
    public boolean requiresOperator(String toolName) {
        return policyOf(toolName) == ToolPolicy.OPERATOR_ONLY;
    }

    /**
     * Enforces the tool's reviewed policy against the transport-resolved actor.
     *
     * @throws SecurityException when the actor is not the operator for an
     *                           operator-only tool, and for every unclassified
     *                           (GATED) tool name
     */
    public void requireAuthority(String toolName, ActorPrincipal actor) {
        if (actor == null) {
            throw new SecurityException("Missing actor for tool '" + toolName + "'");
        }
        switch (policyOf(toolName)) {
            case OPERATOR_ONLY -> actor.requireOperator();
            case WORKER_READ, WORKER_WRITE -> {
                // Worker authority is transport-enforced; a write additionally
                // consumes a one-use grant at the execution boundary.
            }
            case GATED -> throw new SecurityException("Tool '" + toolName
                    + "' is not classified by the reviewed tool policy; unknown tools stay gated");
        }
    }

    /** The exact set of registered tool names (coverage guard for tests). */
    public Set<String> classifiedToolNames() {
        return catalog.names();
    }
}
