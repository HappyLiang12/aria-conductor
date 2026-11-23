package io.aria.conductor.aria.live;

/**
 * One socket's Qwen realtime bridge (spec §"Bridge process"). The Java side only ever
 * sees this seam, so the transport (a stdio child process) stays replaceable and a test
 * can run without a provider.
 *
 * <p>Everything Java writes is one JSON object per line; binary audio is base64 in that
 * line, never a side channel, so there is no second port and no second credential surface.
 * Implementations are called from the socket thread and from bridge reader threads and
 * must tolerate both, and {@link #close()} is idempotent.
 */
public interface LiveBridge extends AutoCloseable {

    /** The provider-less bridge: nothing is sent, nothing is reaped. */
    LiveBridge NOOP = new LiveBridge() {
        @Override
        public void send(String json) {
        }

        @Override
        public void sendBinary(byte[] pcm) {
        }

        @Override
        public void close() {
        }
    };

    /** Writes one already-built JSON line to the child. A closed bridge drops it. */
    void send(String json);

    /** Wraps phone PCM as {@code audio{dir:"in"}} and writes it; never logs the bytes. */
    void sendBinary(byte[] pcm);

    /** Stops the child and reaps it. Called once, when the socket closes. */
    @Override
    void close();
}
