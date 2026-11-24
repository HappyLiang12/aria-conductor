package io.aria.conductor.execution.adk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * System-level ADK configuration shared across all providers.
 *
 * <p>Provider-specific settings live in their own properties classes
 * (e.g. {@code OpenCodeProperties}).
 */
@Data
@Component
@ConfigurationProperties(prefix = "adk")
public class AdkSystemProperties {

    /**
     * Which core {@code GET /api/v1/adk/providers} marks {@code isDefault} in the
     * provider inventory the UI renders. This is a display preselection only: it
     * does not feed admission. An agent created without an explicit core always
     * resolves to {@code opencode} in {@code DefaultAgentExecutionPolicy},
     * whatever this value is.
     */
    private String defaultProvider = "opencode";
}
