package io.aria.conductor.aria.live;

/**
 * Creates the bridge for one live socket (spec §"Bridge process"): one child per socket,
 * spawned after {@code ready} so the 1 s handshake never waits for a provider.
 */
@FunctionalInterface
public interface LiveBridgeFactory {

    /**
     * @param session  the socket's state; its {@code id} is Aria's per-socket id, never a Qwen id
     * @param listener where the child's output goes
     * @return a live bridge; throws {@link LiveBridgeUnavailableException} when the bridge
     *         cannot be started (disabled, credentials missing, script missing)
     */
    LiveBridge open(LiveSession session, LiveBridgeListener listener);
}
