package io.aria.conductor.aria.config;

import io.aria.conductor.agent.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Slf4j
@Data
@ConfigurationProperties(prefix = "aria")
public class AriaProperties {

    private String systemPrompt = "You are Aria, the AI operator assistant for the Aria Conductor.";
    private int maxHistoryTurns = 20;
    private int sessionTtlMinutes = 60;

    @Autowired
    private SystemConfigService systemConfigService;

    @PostConstruct
    void overlayFromDb() {
        try {
            maxHistoryTurns = systemConfigService.getInt("aria.max.history.turns", maxHistoryTurns, 1, 100);
            sessionTtlMinutes = systemConfigService.getInt("aria.session.ttl.minutes", sessionTtlMinutes, 5, 1440);
            log.info("Aria config loaded from DB: maxHistoryTurns={}, sessionTtlMinutes={}", maxHistoryTurns, sessionTtlMinutes);
        } catch (Exception e) {
            log.warn("Failed to load Aria config from DB, using YAML defaults", e);
        }
    }
}
