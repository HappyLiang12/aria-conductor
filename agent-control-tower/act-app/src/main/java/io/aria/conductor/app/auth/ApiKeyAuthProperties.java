package io.aria.conductor.app.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deployment-level API-key authentication configuration ({@code app.auth.*}).
 *
 * <p>The operator key is set via the {@code ARIA_API_KEY} environment variable
 * (wired in {@code application.yml} as {@code app.auth.api-key: ${ARIA_API_KEY:}}).
 * When the key is blank enforcement is disabled and the API behaves exactly as
 * before (permissive local/dev default).
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class ApiKeyAuthProperties {

    /** Operator API key (env: {@code ARIA_API_KEY}). Blank = auth disabled. */
    private String apiKey = "";

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
