package io.aria.conductor.execution.context.contributor;

import io.aria.conductor.agent.repository.SystemConfigRepository;
import io.aria.conductor.common.config.SystemConfigKeys;
import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.SystemConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The operator's identity and locale (spec §8.1): display name, timezone and
 * language, read from the {@code memory.operator.*} system-config keys. Every
 * value is optional and falls back to a documented default, so the section is
 * always present — identity is required for every agent.
 */
@Component
public class IdentityContextContributor implements ContextContributor {

    static final String DEFAULT_NAME = "Operator";
    static final String DEFAULT_TIMEZONE = "UTC";
    static final String DEFAULT_LANGUAGE = "en";

    private final SystemConfigRepository config;

    public IdentityContextContributor(SystemConfigRepository config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "identity";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        String content = "name: " + value(SystemConfigKeys.MEMORY_OPERATOR_NAME, DEFAULT_NAME)
                + "\ntimezone: " + value(SystemConfigKeys.MEMORY_OPERATOR_TIMEZONE, DEFAULT_TIMEZONE)
                + "\nlanguage: " + value(SystemConfigKeys.MEMORY_OPERATOR_LANGUAGE, DEFAULT_LANGUAGE);
        return List.of(new ContextSection(id(), "Identity", content,
                Sensitivity.INTERNAL, List.of(), false, null));
    }

    private String value(String key, String fallback) {
        try {
            return config.findById(key)
                    .map(SystemConfig::getConfigValue)
                    .filter(found -> found != null && !found.isBlank())
                    .map(String::trim)
                    .orElse(fallback);
        } catch (RuntimeException e) {
            // Identity is required for every agent, so an unreadable config row
            // must not fail the run: the documented defaults are still an identity.
            return fallback;
        }
    }
}
