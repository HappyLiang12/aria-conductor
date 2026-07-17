package io.aria.conductor.execution.circuit;

import io.aria.conductor.agent.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerPropertiesTest {

    @Mock
    SystemConfigService systemConfigService;

    @Test
    void overlayFromDb_overridesYamlDefaults() throws Exception {
        when(systemConfigService.getLong("circuit.breaker.max.tokens.per.run", 100000L, 1000, 10_000_000))
                .thenReturn(50_000L);
        when(systemConfigService.getInt("circuit.breaker.max.iterations", 50, 1, 500))
                .thenReturn(25);
        when(systemConfigService.getDouble("circuit.breaker.error.rate.threshold", 0.5, 0.0, 1.0))
                .thenReturn(0.8);
        when(systemConfigService.getLong("circuit.breaker.max.iteration.latency.ms", 300000L, 10000, 3_600_000))
                .thenReturn(120_000L);

        CircuitBreakerProperties props = new CircuitBreakerProperties();
        java.lang.reflect.Field field = CircuitBreakerProperties.class.getDeclaredField("systemConfigService");
        field.setAccessible(true);
        field.set(props, systemConfigService);
        props.overlayFromDb();

        assertThat(props.getMaxTokensPerRun()).isEqualTo(50_000L);
        assertThat(props.getMaxIterations()).isEqualTo(25);
        assertThat(props.getErrorRateThreshold()).isEqualTo(0.8);
        assertThat(props.getMaxIterationLatencyMs()).isEqualTo(120_000L);
    }

    @Test
    void overlayFromDb_keepsDefaults_whenServiceThrows() throws Exception {
        when(systemConfigService.getLong("circuit.breaker.max.tokens.per.run", 100000L, 1000, 10_000_000))
                .thenThrow(new RuntimeException("DB down"));

        CircuitBreakerProperties props = new CircuitBreakerProperties();
        java.lang.reflect.Field field = CircuitBreakerProperties.class.getDeclaredField("systemConfigService");
        field.setAccessible(true);
        field.set(props, systemConfigService);
        // Should not throw, defaults preserved
        props.overlayFromDb();

        assertThat(props.getMaxTokensPerRun()).isEqualTo(100000L);
        assertThat(props.getMaxIterations()).isEqualTo(50);
    }
}
