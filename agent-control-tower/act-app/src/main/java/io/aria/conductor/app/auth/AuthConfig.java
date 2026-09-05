package io.aria.conductor.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the {@link ApiKeyAuthFilter} for every request and emits the one-time
 * startup WARN when authentication is disabled (no {@code ARIA_API_KEY} configured).
 */
@Slf4j
@Configuration
public class AuthConfig {

    public AuthConfig(ApiKeyAuthProperties properties) {
        if (!properties.isEnabled()) {
            log.warn("AUTH DISABLED: ARIA_API_KEY not set. All /api/v1/** endpoints are open. "
                    + "Do not expose this deployment publicly.");
        }
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
            ApiKeyAuthProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(properties, objectMapper));
        registration.addUrlPatterns("/*");
        registration.setName("apiKeyAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
