package io.aria.conductor.execution.adk.opencode;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the OpenCode agent provider ({@code opencode.*} prefix).
 *
 * <p>Mirrors the {@code LangChainAdkProperties} pattern: a plain
 * {@code @ConfigurationProperties} bean with sensible defaults.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "opencode")
public class OpenCodeProperties {

    /** OpenSandbox server base URL (lifecycle server, not the sandbox-internal serve). */
    private String sandboxServerUrl = "http://localhost:8080";

    /** Optional API key for the OpenSandbox server (env: OPENSANDBOX_API_KEY). */
    private String sandboxApiKey = "";

    /** Template image with opencode pre-installed. */
    private String image = "aria-conductor/opencode-sandbox:1.0";

    /** Port the sandbox-internal opencode serve binds to. */
    private int port = 4096;

    /**
     * Env vars injected into every agent sandbox (e.g. LLM model credentials
     * such as DEEPSEEK_API_KEY consumed by opencode inside the sandbox).
     */
    private Map<String, String> sandboxEnv = new LinkedHashMap<>();

    /** Default task timeout in minutes (used when TaskContext.maxDuration is null). */
    private int maxTaskMinutes = 30;
}
