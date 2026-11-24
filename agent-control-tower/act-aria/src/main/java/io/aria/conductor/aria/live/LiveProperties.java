package io.aria.conductor.aria.live;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code aria.live.*} configuration for the phone's live loop (spec §Deployment).
 *
 * <p>{@code enabled} is off by default: the endpoint exists, but no bridge child
 * is spawned until a deployment turns the live path on. {@code bridge-script} is
 * repo-relative and {@code credentials-file} is the 0600 env file the bridge
 * reads its provider key from; neither value is ever logged. {@code realtime-url}
 * is the child's Qwen endpoint and is not a secret either; empty (the default)
 * means the child derives it from the workspace region (Task 5).
 *
 * <p>The direct-locator keys (spec D6) are the backup AR path: {@code direct-locator-enabled}
 * is off by default, so no direct provider call is made until a deployment turns it on.
 * {@code direct-base-url} and {@code direct-model} are not secrets; the key itself is read
 * from {@code DEEPSEEK_API_KEY} by {@link DeepSeekLocator} and is deliberately not part of
 * this record, so no property dump can carry it.
 *
 * @param enabled              whether the live bridge may be started
 * @param bridgeScript         path to the Node bridge entry point
 * @param credentialsFile      env file holding DASHSCOPE_API_KEY / DASHSCOPE_WORKSPACE
 * @param realtimeUrl          child's Qwen realtime WSS endpoint; empty → the child derives it
 * @param readyDeadlineMs      how long a socket may stay silent before {@code hello}, closing 4002
 * @param maxSessionSeconds    the {@code ready.maxSessionSec} the socket advertises
 * @param directLocatorEnabled whether the DeepSeek backup locator may run (spec D6)
 * @param directBaseUrl        DeepSeek API origin; {@code /chat/completions} is appended
 * @param directModel          the vision model the locator asks
 * @param directTimeoutMs      the locator's whole-call budget (connect + request + body)
 */
@ConfigurationProperties(prefix = "aria.live")
public record LiveProperties(
        boolean enabled,
        String bridgeScript,
        String credentialsFile,
        String realtimeUrl,
        long readyDeadlineMs,
        int maxSessionSeconds,
        boolean directLocatorEnabled,
        String directBaseUrl,
        String directModel,
        long directTimeoutMs) {

    public static final String DEFAULT_BRIDGE_SCRIPT = "packages/qwen-live-bridge/dist/index.js";
    public static final String DEFAULT_CREDENTIALS_FILE = "~/.aria-conductor/qwen-live.env";
    public static final long DEFAULT_READY_DEADLINE_MS = 5000L;
    public static final int DEFAULT_MAX_SESSION_SECONDS = 7200;
    public static final String DEFAULT_DIRECT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_DIRECT_MODEL = "deepseek-flash";
    public static final long DEFAULT_DIRECT_TIMEOUT_MS = 1500L;

    public LiveProperties {
        bridgeScript = blank(bridgeScript) ? DEFAULT_BRIDGE_SCRIPT : bridgeScript.trim();
        credentialsFile = blank(credentialsFile) ? DEFAULT_CREDENTIALS_FILE : credentialsFile.trim();
        realtimeUrl = blank(realtimeUrl) ? "" : realtimeUrl.trim();
        readyDeadlineMs = readyDeadlineMs <= 0 ? DEFAULT_READY_DEADLINE_MS : readyDeadlineMs;
        maxSessionSeconds = maxSessionSeconds <= 0 ? DEFAULT_MAX_SESSION_SECONDS : maxSessionSeconds;
        directBaseUrl = blank(directBaseUrl) ? DEFAULT_DIRECT_BASE_URL : directBaseUrl.trim();
        directModel = blank(directModel) ? DEFAULT_DIRECT_MODEL : directModel.trim();
        directTimeoutMs = directTimeoutMs <= 0 ? DEFAULT_DIRECT_TIMEOUT_MS : directTimeoutMs;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
