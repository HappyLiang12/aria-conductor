package io.aria.conductor.dashboard.report;

import io.aria.conductor.agent.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Slf4j
@Data
@ConfigurationProperties(prefix = "act.reports")
public class ReportProperties {

    private int generateMaxTokens = 16384;
    private int amendMaxTokens = 16384;

    @Autowired
    private SystemConfigService systemConfigService;

    @PostConstruct
    void overlayFromDb() {
        try {
            generateMaxTokens = systemConfigService.getInt("report.generate.max.tokens", generateMaxTokens, 4000, 131072);
            amendMaxTokens = systemConfigService.getInt("report.amend.max.tokens", amendMaxTokens, 4000, 131072);
            log.info("Report config loaded from DB: generateMaxTokens={}, amendMaxTokens={}",
                    generateMaxTokens, amendMaxTokens);
        } catch (Exception e) {
            log.warn("Failed to load report config from DB, using YAML defaults", e);
        }
    }
}
