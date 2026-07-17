package io.aria.conductor.aria;

import java.util.UUID;

/**
 * Shared constants for the Aria platform assistant.
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
