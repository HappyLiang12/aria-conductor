package io.aria.conductor.execution.adk.qoder;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for the Qoder agent provider ({@code qoder.*} prefix).
 *
 * <p>Mirrors the {@code OpenCodeProperties} pattern: a plain
 * {@code @ConfigurationProperties} bean with sensible defaults, so the bean is usable
 * when no {@code qoder:} block is present in {@code application.yml}.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "qoder")
public class QoderProperties {

    /** OpenSandbox server base URL (lifecycle server, not the sandbox-internal bridge). */
    private String sandboxServerUrl = "http://localhost:8080";

    /** Optional API key for the OpenSandbox server (env: OPENSANDBOX_API_KEY). */
    private String sandboxApiKey = "";

    /** Template image with the Qoder CLI, the ACP bridge and the plugin bundle pre-installed. */
    private String image = "aria-conductor/qoder-sandbox:0.1";

    /** Port the sandbox-internal bridge binds to (C0.2: the bridge listens on {@code PORT}). */
    private int port = 4097;

    /** Default task timeout in minutes (used when TaskContext.maxDuration is null). */
    private int maxTaskMinutes = 45;

    /**
     * Model id pinned via {@code session/set_model} at bridge session creation (C0.1).
     * The E2E harness sets {@code efficient}; unknown ids are an explicit provider error.
     */
    private String model = "auto";

    /**
     * Interval between sandbox TTL renewals while a long synchronous task runs
     * (mirrors {@code OpenCodeProperties.sandboxRenewInterval}).
     */
    private Duration sandboxRenewInterval = Duration.ofMinutes(5);
}
