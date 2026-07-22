package io.aria.conductor.execution.circuit;

import io.aria.conductor.common.exception.BudgetExceededException;
import io.aria.conductor.execution.engine.RunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies that a single {@link CircuitBreaker#check} reads each configuration
 * value exactly once (snapshot per call) — avoiding redundant live DB reads and
 * keeping the decision, log message and exception consistent within one check.
 * Also covers the per-iteration latency semantics (#22): human approval/pause
 * wait is excluded from the iteration budget, and a separate total-run cap applies.
 */
@ExtendWith(MockitoExtension.class)
class CircuitBreakerTest {

    @Mock
    CircuitBreakerProperties properties;

    private RunContext newContext() {
        return new RunContext(UUID.randomUUID(), UUID.randomUUID(), null, null, 50);
    }

    private void stubAllLimits() {
        when(properties.getMaxTokensPerRun()).thenReturn(100L);
        when(properties.getMaxIterations()).thenReturn(50);
        when(properties.getErrorRateThreshold()).thenReturn(0.5);
        when(properties.getMaxIterationLatencyMs()).thenReturn(3_600_000L);
        when(properties.getMaxRunDurationMs()).thenReturn(7_200_000L);
    }

    @Test
    void check_readsEachConfigValueExactlyOnce_whenTokenBudgetExceeded() {
        stubAllLimits();
        CircuitBreaker breaker = new CircuitBreaker(properties);
        RunContext ctx = newContext();
        ctx.addTokensUsed(150, 0);

        assertThatThrownBy(() -> breaker.check(ctx)).isInstanceOf(BudgetExceededException.class);

        verify(properties, times(1)).getMaxTokensPerRun();
        verify(properties, times(1)).getMaxIterations();
        verify(properties, times(1)).getErrorRateThreshold();
        verify(properties, times(1)).getMaxIterationLatencyMs();
        verify(properties, times(1)).getMaxRunDurationMs();
        verifyNoMoreInteractions(properties);
    }

    @Test
    void check_readsEachConfigValueExactlyOnce_whenMaxIterationsReached() {
        stubAllLimits();
        when(properties.getMaxIterations()).thenReturn(5);
        CircuitBreaker breaker = new CircuitBreaker(properties);
        RunContext ctx = newContext();
        for (int i = 0; i < 5; i++) {
            ctx.incrementIteration();
        }

        assertThatThrownBy(() -> breaker.check(ctx)).isInstanceOf(BudgetExceededException.class);

        verify(properties, times(1)).getMaxTokensPerRun();
        verify(properties, times(1)).getMaxIterations();
        verify(properties, times(1)).getErrorRateThreshold();
        verify(properties, times(1)).getMaxIterationLatencyMs();
        verify(properties, times(1)).getMaxRunDurationMs();
        verifyNoMoreInteractions(properties);
    }

    @Test
    void freshIteration_doesNotTrip_evenIfRunStartedLongAgo() {
        // Per-iteration latency is measured from iterationStartTime (reset each iteration),
        // NOT from the run start — so a long-running run with a fresh iteration survives.
        when(properties.getMaxTokensPerRun()).thenReturn(100_000L);
        when(properties.getMaxIterations()).thenReturn(50);
        when(properties.getErrorRateThreshold()).thenReturn(0.5);
        when(properties.getMaxIterationLatencyMs()).thenReturn(50L);
        when(properties.getMaxRunDurationMs()).thenReturn(7_200_000L);
        CircuitBreaker breaker = new CircuitBreaker(properties);
        RunContext ctx = newContext();
        ctx.markIterationStart(); // fresh iteration window

        assertThatCode(() -> breaker.check(ctx)).doesNotThrowAnyException();
    }

    @Test
    void slowIteration_tripsLatencyGuard() throws InterruptedException {
        when(properties.getMaxTokensPerRun()).thenReturn(100_000L);
        when(properties.getMaxIterations()).thenReturn(50);
        when(properties.getErrorRateThreshold()).thenReturn(0.5);
        when(properties.getMaxIterationLatencyMs()).thenReturn(20L);
        when(properties.getMaxRunDurationMs()).thenReturn(7_200_000L);
        CircuitBreaker breaker = new CircuitBreaker(properties);
        RunContext ctx = newContext();
        ctx.markIterationStart();
        Thread.sleep(40); // make the current iteration genuinely slow

        assertThatThrownBy(() -> breaker.check(ctx))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("iteration latency");
    }

    @Test
    void blockedApprovalWait_isExcludedFromIterationLatency() throws InterruptedException {
        // Time spent blocked on a human approval must NOT count toward iteration latency.
        when(properties.getMaxTokensPerRun()).thenReturn(100_000L);
        when(properties.getMaxIterations()).thenReturn(50);
        when(properties.getErrorRateThreshold()).thenReturn(0.5);
        when(properties.getMaxIterationLatencyMs()).thenReturn(30L);
        when(properties.getMaxRunDurationMs()).thenReturn(7_200_000L);
        CircuitBreaker breaker = new CircuitBreaker(properties);
        RunContext ctx = newContext();
        ctx.markIterationStart();
        Thread.sleep(40); // would trip on its own...
        ctx.addBlockedWait(Duration.ofMillis(40)); // ...but it was human wait, so excluded

        assertThatCode(() -> breaker.check(ctx)).doesNotThrowAnyException();
    }

    @Test
    void totalRunDurationCap_trips_independently() {
        // Even with a fresh (fast) iteration, the overall wall-clock cap still applies.
        when(properties.getMaxTokensPerRun()).thenReturn(100_000L);
        when(properties.getMaxIterations()).thenReturn(50);
        when(properties.getErrorRateThreshold()).thenReturn(0.5);
        when(properties.getMaxIterationLatencyMs()).thenReturn(3_600_000L);
        when(properties.getMaxRunDurationMs()).thenReturn(1L); // tiny total cap
        CircuitBreaker breaker = new CircuitBreaker(properties);
        RunContext ctx = newContext();
        ctx.markIterationStart();

        assertThatThrownBy(() -> breaker.check(ctx))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("total run duration");
    }
}
