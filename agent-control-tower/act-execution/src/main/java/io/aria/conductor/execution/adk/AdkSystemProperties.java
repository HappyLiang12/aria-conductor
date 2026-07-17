package io.aria.conductor.execution.adk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * System-level ADK configuration shared across all providers.
 *
 * <p>Provider-specific settings live in their own properties classes
 * (e.g. {@code LangChainAdkProperties}).
 */
@Data
@Component
@ConfigurationProperties(prefix = "adk")
public class AdkSystemProperties {

    /** Default provider used when an agent does not specify one. */
    private String defaultProvider = "langchain";
}
