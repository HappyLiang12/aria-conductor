package io.aria.conductor.execution.circuit;

import io.aria.conductor.common.exception.BudgetExceededException;
import io.aria.conductor.execution.engine.RunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies that a single {@link CircuitBreaker#check} reads each configuration
 * value exactly once (snapshot per call) — avoiding redundant live DB reads and
 * keeping the decision, log message and exception consistent within one check.
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
        verifyNoMoreInteractions(properties);
    }
}
