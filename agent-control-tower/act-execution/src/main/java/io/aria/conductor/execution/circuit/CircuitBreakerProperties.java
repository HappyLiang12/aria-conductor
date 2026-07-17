package io.aria.conductor.execution.circuit;

import io.aria.conductor.agent.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Slf4j
@Data
@ConfigurationProperties(prefix = "circuit-breaker")
public class CircuitBreakerProperties {

    private long maxTokensPerRun = 100000;
    private int maxIterations = 50;
    private double errorRateThreshold = 0.5;
    private long maxIterationLatencyMs = 300000;

    @Autowired
    private SystemConfigService systemConfigService;

    @PostConstruct
    void overlayFromDb() {
        try {
            maxTokensPerRun = systemConfigService.getLong("circuit.breaker.max.tokens.per.run", maxTokensPerRun, 1000, 10_000_000);
            maxIterations = systemConfigService.getInt("circuit.breaker.max.iterations", maxIterations, 1, 500);
            errorRateThreshold = systemConfigService.getDouble("circuit.breaker.error.rate.threshold", errorRateThreshold, 0.0, 1.0);
            maxIterationLatencyMs = systemConfigService.getLong("circuit.breaker.max.iteration.latency.ms", maxIterationLatencyMs, 10000, 3_600_000);
            log.info("Circuit breaker config loaded from DB: maxTokensPerRun={}, maxIterations={}, errorRateThreshold={}, maxIterationLatencyMs={}",
                    maxTokensPerRun, maxIterations, errorRateThreshold, maxIterationLatencyMs);
        } catch (Exception e) {
            log.warn("Failed to load circuit breaker config from DB, using YAML defaults", e);
        }
    }
}
