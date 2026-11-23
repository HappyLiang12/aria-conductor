package io.aria.conductor.aria.live;

/**
 * Creates the bridge for one live socket. Task 1 wires a no-op implementation;
 * Task 2 supplies the stdio child process without changing the handler.
 */
@FunctionalInterface
public interface LiveBridgeFactory {

    /**
     * @param sessionId      Aria's per-socket id (never a Qwen id)
     * @param conversationId the conversation the socket bound itself to
     */
    LiveBridge open(String sessionId, String conversationId);
}
