package io.aria.conductor.execution.pipeline;

import io.aria.conductor.execution.circuit.CircuitBreaker;
import io.aria.conductor.execution.circuit.CircuitBreakerProperties;
import io.aria.conductor.execution.engine.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Verifies actions against business rules: budget, rate limits, etc.
 */
@Slf4j
@Component
public class RuleVerifier {

    private final CircuitBreakerProperties circuitBreakerProperties;

    public RuleVerifier(CircuitBreakerProperties circuitBreakerProperties) {
        this.circuitBreakerProperties = circuitBreakerProperties;
    }

    public RuleVerificationResult verify(Action action, ActionClassification classification, RunContext ctx) {
        // Token budget check
        if (ctx.getTotalTokensUsed() > circuitBreakerProperties.getMaxTokensPerRun()) {
            String reason = String.format("Token budget exceeded: %d/%d", ctx.getTotalTokensUsed(),
                    circuitBreakerProperties.getMaxTokensPerRun());
            log.warn("Rule verification denied: {}", reason);
            return RuleVerificationResult.deny(reason);
        }

        // Max iterations check
        if (ctx.getIterationCount() >= circuitBreakerProperties.getMaxIterations()) {
            String reason = String.format("Max iterations reached: %d/%d", ctx.getIterationCount(),
                    circuitBreakerProperties.getMaxIterations());
            log.warn("Rule verification denied: {}", reason);
            return RuleVerificationResult.deny(reason);
        }

        // Error rate check
        if (!ctx.getErrors().isEmpty()) {
            double errorRate = (double) ctx.getErrors().size() / Math.max(ctx.getIterationCount(), 1);
            if (errorRate > circuitBreakerProperties.getErrorRateThreshold()) {
                String reason = String.format("Error rate too high: %.2f > %.2f", errorRate,
                        circuitBreakerProperties.getErrorRateThreshold());
                log.warn("Rule verification denied: {}", reason);
                return RuleVerificationResult.deny(reason);
            }
        }

        // HIGH_RISK actions always require additional scrutiny
        if (classification.riskLevel().equals("HIGH")) {
            log.info("High risk action '{}' passed rule verification but flagged for approval", action.name());
        }

        log.debug("Rule verification passed for action '{}'", action.name());
        return RuleVerificationResult.allow();
    }
}