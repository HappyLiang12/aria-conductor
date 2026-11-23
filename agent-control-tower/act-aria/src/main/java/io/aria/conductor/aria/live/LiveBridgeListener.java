package io.aria.conductor.aria.live;

/**
 * What the bridge hands back to the socket. Every callback runs on a bridge reader
 * thread (or on the child's exit), so an implementation must be safe to call from any
 * thread and must never block on the socket.
 */
public interface LiveBridgeListener {

    /** No listener at all: for a bridge opened without a socket behind it. */
    LiveBridgeListener NONE = new LiveBridgeListener() {
        @Override
        public void onControl(String json) {
        }

        @Override
        public void onAudio(byte[] pcm) {
        }

        @Override
        public void onExit(int code) {
        }
    };

    /** One phone event, already unwrapped from {@code control{event:…}}, as JSON. */
    void onControl(String json);

    /** 24 kHz PCM16LE for the phone, in one binary frame. */
    void onAudio(byte[] pcm);

    /** A tool call (spec X7). Task 3/4 consume it; the handler only forwards it. */
    default void onToolCall(String json) {
    }

    /** The child exited with {@code code} without being asked to (a phone close is not an exit). */
    void onExit(int code);
}
