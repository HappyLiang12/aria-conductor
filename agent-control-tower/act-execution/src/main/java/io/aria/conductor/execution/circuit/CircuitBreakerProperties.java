package io.aria.conductor.execution.circuit;

import io.aria.conductor.agent.service.SystemConfigService;
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

    public long getMaxTokensPerRun() {
        try {
            return systemConfigService.getLong("circuit.breaker.max.tokens.per.run", maxTokensPerRun, 1000, 10_000_000);
        } catch (Exception e) {
            return maxTokensPerRun;
        }
    }

    public int getMaxIterations() {
        try {
            return systemConfigService.getInt("circuit.breaker.max.iterations", maxIterations, 1, 500);
        } catch (Exception e) {
            return maxIterations;
        }
    }

    public double getErrorRateThreshold() {
        try {
            return systemConfigService.getDouble("circuit.breaker.error.rate.threshold", errorRateThreshold, 0.0, 1.0);
        } catch (Exception e) {
            return errorRateThreshold;
        }
    }

    public long getMaxIterationLatencyMs() {
        try {
            return systemConfigService.getLong("circuit.breaker.max.iteration.latency.ms", maxIterationLatencyMs, 10000, 3_600_000);
        } catch (Exception e) {
            return maxIterationLatencyMs;
        }
    }
}
