package io.aria.conductor.execution.circuit;

import io.aria.conductor.common.exception.BudgetExceededException;
import io.aria.conductor.execution.engine.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Circuit breaker — checks run context against budget/iteration/latency limits.
 * Throws BudgetExceededException if any limit is breached.
 */
@Slf4j
@Component
public class CircuitBreaker {

    private final CircuitBreakerProperties properties;

    public CircuitBreaker(CircuitBreakerProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks the run context against all circuit breaker limits.
     * @throws BudgetExceededException if any limit is breached
     */
    public void check(RunContext ctx) {
        // Snapshot config once per check — each value is read from SystemConfig a single
        // time, so the decision, log message and exception stay consistent and we avoid
        // redundant DB reads on this hot path.
        long maxTokensPerRun = properties.getMaxTokensPerRun();
        int maxIterations = properties.getMaxIterations();
        double errorRateThreshold = properties.getErrorRateThreshold();
        long maxIterationLatencyMs = properties.getMaxIterationLatencyMs();
        long maxRunDurationMs = properties.getMaxRunDurationMs();

        // Token budget check
        if (ctx.getTotalTokensUsed() > maxTokensPerRun) {
            log.error("Circuit breaker tripped: token budget exceeded. used={}, limit={}",
                    ctx.getTotalTokensUsed(), maxTokensPerRun);
            throw new BudgetExceededException(ctx.getTotalTokensUsed(), maxTokensPerRun);
        }

        // Max iterations check
        if (ctx.getIterationCount() >= maxIterations) {
            String msg = String.format("Max iterations reached: %d/%d", ctx.getIterationCount(),
                    maxIterations);
            log.error("Circuit breaker tripped: {}", msg);
            throw new BudgetExceededException(msg);
        }

        // Error rate check
        if (!ctx.getErrors().isEmpty() && ctx.getIterationCount() > 0) {
            double errorRate = (double) ctx.getErrors().size() / ctx.getIterationCount();
            if (errorRate > errorRateThreshold) {
                String msg = String.format("Error rate exceeded: %.2f > %.2f (errors=%d, iterations=%d)",
                        errorRate, errorRateThreshold,
                        ctx.getErrors().size(), ctx.getIterationCount());
                log.error("Circuit breaker tripped: {}", msg);
                throw new BudgetExceededException(msg);
            }
        }

        // Per-iteration latency check (#22): measures compute time for the current iteration,
        // excluding human approval/pause wait (blockedWait). This prevents HITL runs from being
        // killed while waiting for an operator decision (the approval window is far longer).
        long iterationElapsedMs = Math.max(0L,
                Duration.between(ctx.getIterationStartTime(), Instant.now()).toMillis() - ctx.getBlockedWaitMillis());
        if (iterationElapsedMs > maxIterationLatencyMs) {
            String msg = String.format("Max iteration latency exceeded: %dms > %dms", iterationElapsedMs,
                    maxIterationLatencyMs);
            log.error("Circuit breaker tripped: {}", msg);
            throw new BudgetExceededException(msg);
        }

        // Total run duration guard: independent safety cap on overall wall-clock time (includes
        // approval wait). Defaults well above the approval window so legitimate HITL runs survive.
        long totalElapsedMs = Duration.between(ctx.getStartTime(), Instant.now()).toMillis();
        if (totalElapsedMs > maxRunDurationMs) {
            String msg = String.format("Max total run duration exceeded: %dms > %dms", totalElapsedMs,
                    maxRunDurationMs);
            log.error("Circuit breaker tripped: {}", msg);
            throw new BudgetExceededException(msg);
        }

        log.debug("Circuit breaker check passed: tokens={}, iterations={}, errors={}, iterationLatencyMs={}, totalMs={}",
                ctx.getTotalTokensUsed(), ctx.getIterationCount(),
                ctx.getErrors().size(), iterationElapsedMs, totalElapsedMs);
    }
}