package io.aria.conductor.app.security;

import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the built-in API-key auth layer for the {@code /api/v1/**} surface.
 *
 * <ul>
 *   <li>Registers {@link ApiKeyAuthFilter} on {@code /api/v1/*} at the highest precedence so it
 *       runs before any controller and rejects unauthorized requests before handler logic.</li>
 *   <li>Fails fast at startup when auth is enabled but no key is configured (AC5).</li>
 *   <li>Provisions a plaintext key to agent sandboxes via {@code opencode.sandbox-env}
 *       ({@code ARIA_API_KEY}) so sandboxed agents can call the API authenticated.</li>
 * </ul>
 */
@Configuration
public class ApiKeySecurityConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
            SecurityProperties securityProperties) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyAuthFilter(securityProperties));
        registration.addUrlPatterns("/api/v1/*");
        registration.setName("apiKeyAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public SecurityStartupValidator securityStartupValidator(SecurityProperties securityProperties,
                                                             OpenCodeProperties openCodeProperties) {
        return new SecurityStartupValidator(securityProperties, openCodeProperties);
    }

    /**
     * Startup validation + sandbox provisioning. Implemented as an {@link InitializingBean} so any
     * misconfiguration (auth enabled without a key) aborts context loading before the service can
     * serve traffic.
     */
    static class SecurityStartupValidator implements InitializingBean {

        private final SecurityProperties properties;
        private final OpenCodeProperties openCodeProperties;

        SecurityStartupValidator(SecurityProperties properties, OpenCodeProperties openCodeProperties) {
            this.properties = properties;
            this.openCodeProperties = openCodeProperties;
        }

        @Override
        public void afterPropertiesSet() {
            // Force parsing now so a malformed sha256: entry also aborts startup with a clear error.
            if (properties.isEnabled()) {
                if (!properties.hasConfiguredKeys()) {
                    throw new IllegalStateException("Authentication is enabled "
                            + "(app.security.enabled=true / mariadb profile) but no API key is "
                            + "configured. Set AUTH_API_KEYS.");
                }
                properties.provisionablePlaintextKey().ifPresent(key ->
                        openCodeProperties.getSandboxEnv().putIfAbsent("ARIA_API_KEY", key));
            }
        }
    }
}
