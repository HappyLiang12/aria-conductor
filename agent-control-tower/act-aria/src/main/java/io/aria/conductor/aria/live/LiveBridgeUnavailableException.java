package io.aria.conductor.aria.live;

/**
 * The bridge could not be started: {@code aria.live.enabled} is false, the credentials
 * file is absent/unsafe/incomplete, or the bridge script is missing. The message names
 * the class of failure only — never a key, a workspace or a payload.
 */
public class LiveBridgeUnavailableException extends RuntimeException {

    public LiveBridgeUnavailableException(String reason) {
        super(reason);
    }
}
