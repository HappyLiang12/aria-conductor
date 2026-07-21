package io.aria.conductor.execution.pipeline;

import io.aria.conductor.execution.circuit.CircuitBreakerProperties;
import io.aria.conductor.execution.engine.RunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies that a single {@link RuleVerifier#verify} reads each configuration
 * value exactly once (snapshot per call) — avoiding redundant live DB reads and
 * keeping the deny decision and its reason consistent within one verification.
 */
@ExtendWith(MockitoExtension.class)
class RuleVerifierTest {

    @Mock
    CircuitBreakerProperties properties;

    private RunContext newContext() {
        return new RunContext(UUID.randomUUID(), UUID.randomUUID(), null, null, 50);
    }

    private Action readAction() {
        return new Action("read_file", ActionType.READ, "{}", null);
    }

    private void stubAllLimits() {
        when(properties.getMaxTokensPerRun()).thenReturn(100L);
        when(properties.getMaxIterations()).thenReturn(50);
        when(properties.getErrorRateThreshold()).thenReturn(0.5);
    }

    @Test
    void verify_readsEachConfigValueExactlyOnce_whenTokenBudgetExceeded() {
        stubAllLimits();
        RuleVerifier verifier = new RuleVerifier(properties);
        RunContext ctx = newContext();
        ctx.addTokensUsed(150, 0);

        RuleVerificationResult result = verifier.verify(readAction(), ActionClassification.lowRisk("general"), ctx);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.reason()).contains("100");
        verify(properties, times(1)).getMaxTokensPerRun();
        verify(properties, times(1)).getMaxIterations();
        verify(properties, times(1)).getErrorRateThreshold();
        verifyNoMoreInteractions(properties);
    }

    @Test
    void verify_readsEachConfigValueExactlyOnce_whenMaxIterationsReached() {
        stubAllLimits();
        when(properties.getMaxIterations()).thenReturn(5);
        RuleVerifier verifier = new RuleVerifier(properties);
        RunContext ctx = newContext();
        for (int i = 0; i < 5; i++) {
            ctx.incrementIteration();
        }

        RuleVerificationResult result = verifier.verify(readAction(), ActionClassification.lowRisk("general"), ctx);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.reason()).contains("5");
        verify(properties, times(1)).getMaxTokensPerRun();
        verify(properties, times(1)).getMaxIterations();
        verify(properties, times(1)).getErrorRateThreshold();
        verifyNoMoreInteractions(properties);
    }
}
