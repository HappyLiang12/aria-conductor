package io.aria.conductor.aria.live;

import org.springframework.stereotype.Component;

/**
 * The provider-less bridge factory Task 1 wires (spec §"Bridge process" is
 * Task 2). It exists so the socket lifecycle, {@code hello}/{@code ready} and
 * every refusal can be exercised without a Qwen connection, and so the handler
 * already depends on the seam the real process will plug into.
 */
@Component
class NoopLiveBridgeFactory implements LiveBridgeFactory {

    @Override
    public LiveBridge open(String sessionId, String conversationId) {
        return LiveBridge.NOOP;
    }
}
