package io.aria.conductor.aria.live;

/**
 * One socket's Qwen realtime bridge (spec §"Bridge process"). The Java side only
 * ever sees this seam, so the transport (a stdio child process) stays replaceable
 * and a test can run without a provider.
 *
 * <p>Task 1 has no provider, so the only implementation wired is {@link #NOOP};
 * Task 2 replaces the factory with the stdio child process behind the same seam.
 */
public interface LiveBridge extends AutoCloseable {

    /** The provider-less bridge: nothing to start, nothing to send, nothing to reap. */
    LiveBridge NOOP = () -> { };

    /** Reaps the bridge. Called once, when the socket closes. */
    @Override
    void close();
}
