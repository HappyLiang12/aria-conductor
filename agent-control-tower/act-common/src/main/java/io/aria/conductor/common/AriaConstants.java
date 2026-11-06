package io.aria.conductor.common;

import java.util.UUID;

/**
 * Shared platform-identity constants for the Aria assistant.
 *
 * <p>Lives in act-common so both act-aria (prompt-call attribution) and
 * act-execution (OpenCode sandbox wiring, e.g. the {@code mcp.aria-conductor}
 * block) can reference it without creating a circular module dependency.
 */
public final class AriaConstants {

    /**
     * Stable synthetic agent id used for every Aria-originated prompt call.
     * Aria is a platform assistant rather than a user-managed agent.
     */
    public static final UUID ARIA_AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private AriaConstants() {
        // utility class
    }
}
