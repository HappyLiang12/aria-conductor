package io.aria.conductor.execution.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "llm.provider")
public class LlmProperties {

    private String baseUrl;
    private String apiKey;
    private String model;
    private int maxTokens = 4096;
}
