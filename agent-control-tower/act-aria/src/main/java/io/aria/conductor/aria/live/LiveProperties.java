package io.aria.conductor.aria.live;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code aria.live.*} configuration for the phone's live loop (spec §Deployment).
 *
 * <p>{@code enabled} is off by default: the endpoint exists, but no bridge child
 * is spawned until a deployment turns the live path on. {@code bridge-script} is
 * repo-relative and {@code credentials-file} is the 0600 env file the bridge
 * reads its provider key from; neither value is ever logged.
 *
 * @param enabled           whether the live bridge may be started
 * @param bridgeScript      path to the Node bridge entry point
 * @param credentialsFile   env file holding DASHSCOPE_API_KEY / DASHSCOPE_WORKSPACE
 * @param readyDeadlineMs   how long a socket may stay silent before {@code hello}, closing 4002
 * @param maxSessionSeconds the {@code ready.maxSessionSec} the socket advertises
 */
@ConfigurationProperties(prefix = "aria.live")
public record LiveProperties(
        boolean enabled,
        String bridgeScript,
        String credentialsFile,
        long readyDeadlineMs,
        int maxSessionSeconds) {

    public static final String DEFAULT_BRIDGE_SCRIPT = "packages/qwen-live-bridge/dist/index.js";
    public static final String DEFAULT_CREDENTIALS_FILE = "~/.aria-conductor/qwen-live.env";
    public static final long DEFAULT_READY_DEADLINE_MS = 5000L;
    public static final int DEFAULT_MAX_SESSION_SECONDS = 7200;

    public LiveProperties {
        bridgeScript = blank(bridgeScript) ? DEFAULT_BRIDGE_SCRIPT : bridgeScript.trim();
        credentialsFile = blank(credentialsFile) ? DEFAULT_CREDENTIALS_FILE : credentialsFile.trim();
        readyDeadlineMs = readyDeadlineMs <= 0 ? DEFAULT_READY_DEADLINE_MS : readyDeadlineMs;
        maxSessionSeconds = maxSessionSeconds <= 0 ? DEFAULT_MAX_SESSION_SECONDS : maxSessionSeconds;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
