package io.aria.conductor.aria.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "aria")
public class AriaProperties {

    private String systemPrompt = "You are Aria, the AI operator assistant for the Aria Conductor.";
}
