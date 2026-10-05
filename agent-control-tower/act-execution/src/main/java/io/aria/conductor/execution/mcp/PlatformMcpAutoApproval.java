package io.aria.conductor.execution.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * The platform-side auto-approval policy for the read-only tools asked by the
 * Aria assistant's own coordinated runs (operator decisions 2026-09-29 and
 * 2026-10-04; amendment to the Qoder CLI core spec §5.2). The tool is never
 * granted a session-wide escalation by this policy: it settles the ask, once,
 * through the normal one-use path — the grant of a {@code PLATFORM_MCP}
 * delivery, or the native reply of a listed {@code NATIVE_TOOL} ask that offers
 * the single allow-once option its reply will name. Since the 2026-10-04
 * decision the list also names the cores' own read-only tools (the CLI's
 * {@code WebSearch}/{@code WebFetch}); those carry no platform prefix by
 * construction, so the operator's explicit listing is the provenance gate for
 * a bare native name.
 *
 * <p>The allowlist is explicit configuration ({@code aria.mcp.auto-approve-read-tools}),
 * not a naming heuristic: every tool it does not name — mutating, operator-only
 * or unknown — keeps the per-call operator approval, and an empty list disables
 * the policy entirely.
 */
@Slf4j
@Component
public class PlatformMcpAutoApproval {

    /** Normalized tool names are the segment after the last {@code __} of the raw name. */
    private static final String PREFIX_SEPARATOR = "__";

    /**
     * The tool-name namespace a core advertises for the platform's own MCP
     * server: the Qoder core is launched with {@code --worker-mcp-name
     * aria-conductor}, so the CLI reports one of its platform MCP calls as
     * {@code mcp__aria-conductor__<tool>} (bridge evidence,
     * {@code e2e/qoder/slice-a/03-mcp-auth.md}: the stub server's call arrives
     * as {@code mcp__aria-stub__aria_ping}).
     */
    public static final String PLATFORM_MCP_PREFIX = "mcp__aria-conductor__";

    private final McpProperties mcp;

    public PlatformMcpAutoApproval(McpProperties mcp) {
        this.mcp = Objects.requireNonNull(mcp, "mcp");
    }

    /**
     * True when {@code toolName} is on the configured read-only allowlist.
     *
     * <p>The name may be the raw platform-MCP name a core advertises
     * ({@code mcp__aria-conductor__<tool>}) or the bare {@code <tool>} — the
     * segment after the last {@code __} is compared case-insensitively. An empty
     * configured list never allows anything.
     */
    public boolean allows(String toolName) {
        List<String> configured = mcp.getAutoApproveReadTools();
        if (toolName == null || toolName.isBlank() || configured == null || configured.isEmpty()) {
            return false;
        }
        String normalized = normalized(toolName);
        boolean allowed = configured.stream()
                .anyMatch(entry -> entry != null && normalized.equalsIgnoreCase(entry.trim()));
        if (allowed) {
            log.debug("Platform MCP tool '{}' is on the configured read-only auto-approval list", toolName);
        }
        return allowed;
    }

    /**
     * True when {@code toolName} is the live shape of a platform MCP call: the
     * raw name under the platform's own MCP namespace,
     * {@code mcp__aria-conductor__<tool>}. This is a provenance gate, not a
     * list match: it only says the ask names a tool the platform's own wiring
     * advertises, and the allowlist decides whether that tool may be
     * auto-answered. The comparison is exact — only the namespace the platform
     * itself wires counts, so a differently-cased or look-alike name keeps the
     * per-call operator approval.
     */
    public static boolean carriesPlatformMcpPrefix(String toolName) {
        return toolName != null
                && toolName.length() > PLATFORM_MCP_PREFIX.length()
                && toolName.startsWith(PLATFORM_MCP_PREFIX);
    }

    private static String normalized(String toolName) {
        int separator = toolName.lastIndexOf(PREFIX_SEPARATOR);
        return (separator < 0 ? toolName : toolName.substring(separator + PREFIX_SEPARATOR.length())).trim();
    }
}
