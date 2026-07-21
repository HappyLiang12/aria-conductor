package io.aria.conductor.execution.circuit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aria.conductor.agent.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerPropertiesTest {

    @Mock
    SystemConfigService systemConfigService;

    private CircuitBreakerProperties propsWithService(SystemConfigService svc) throws Exception {
        CircuitBreakerProperties props = new CircuitBreakerProperties();
        java.lang.reflect.Field field = CircuitBreakerProperties.class.getDeclaredField("systemConfigService");
        field.setAccessible(true);
        field.set(props, svc);
        return props;
    }

    @Test
    void getters_returnDbValues() throws Exception {
        when(systemConfigService.getLong("circuit.breaker.max.tokens.per.run", 100000L, 1000, 10_000_000))
                .thenReturn(50_000L);
        when(systemConfigService.getInt("circuit.breaker.max.iterations", 50, 1, 500))
                .thenReturn(25);
        when(systemConfigService.getDouble("circuit.breaker.error.rate.threshold", 0.5, 0.0, 1.0))
                .thenReturn(0.8);
        when(systemConfigService.getLong("circuit.breaker.max.iteration.latency.ms", 300000L, 10000, 3_600_000))
                .thenReturn(120_000L);

        CircuitBreakerProperties props = propsWithService(systemConfigService);

        assertThat(props.getMaxTokensPerRun()).isEqualTo(50_000L);
        assertThat(props.getMaxIterations()).isEqualTo(25);
        assertThat(props.getErrorRateThreshold()).isEqualTo(0.8);
        assertThat(props.getMaxIterationLatencyMs()).isEqualTo(120_000L);
    }

    @Test
    void getters_fallBackToDefaults_whenServiceThrows() throws Exception {
        when(systemConfigService.getLong("circuit.breaker.max.tokens.per.run", 100000L, 1000, 10_000_000))
                .thenThrow(new RuntimeException("DB down"));
        when(systemConfigService.getInt("circuit.breaker.max.iterations", 50, 1, 500))
                .thenThrow(new RuntimeException("DB down"));
        when(systemConfigService.getDouble("circuit.breaker.error.rate.threshold", 0.5, 0.0, 1.0))
                .thenThrow(new RuntimeException("DB down"));
        when(systemConfigService.getLong("circuit.breaker.max.iteration.latency.ms", 300000L, 10000, 3_600_000))
                .thenThrow(new RuntimeException("DB down"));

        CircuitBreakerProperties props = propsWithService(systemConfigService);

        assertThat(props.getMaxTokensPerRun()).isEqualTo(100000L);
        assertThat(props.getMaxIterations()).isEqualTo(50);
        assertThat(props.getErrorRateThreshold()).isEqualTo(0.5);
        assertThat(props.getMaxIterationLatencyMs()).isEqualTo(300000L);
    }

    @Test
    void getters_reflectDbChanges_withoutRestart() throws Exception {
        when(systemConfigService.getLong("circuit.breaker.max.tokens.per.run", 100000L, 1000, 10_000_000))
                .thenReturn(100000L)
                .thenReturn(1_000_000L);
        when(systemConfigService.getInt("circuit.breaker.max.iterations", 50, 1, 500))
                .thenReturn(50)
                .thenReturn(100);
        when(systemConfigService.getDouble("circuit.breaker.error.rate.threshold", 0.5, 0.0, 1.0))
                .thenReturn(0.5)
                .thenReturn(0.9);
        when(systemConfigService.getLong("circuit.breaker.max.iteration.latency.ms", 300000L, 10000, 3_600_000))
                .thenReturn(300000L)
                .thenReturn(600000L);

        CircuitBreakerProperties props = propsWithService(systemConfigService);

        assertThat(props.getMaxTokensPerRun()).isEqualTo(100000L);
        assertThat(props.getMaxIterations()).isEqualTo(50);
        assertThat(props.getErrorRateThreshold()).isEqualTo(0.5);
        assertThat(props.getMaxIterationLatencyMs()).isEqualTo(300000L);
        // Second read reflects the updated DB values — no restart required
        assertThat(props.getMaxTokensPerRun()).isEqualTo(1_000_000L);
        assertThat(props.getMaxIterations()).isEqualTo(100);
        assertThat(props.getErrorRateThreshold()).isEqualTo(0.9);
        assertThat(props.getMaxIterationLatencyMs()).isEqualTo(600000L);
    }

    @Test
    void getters_logWarn_whenServiceThrows() throws Exception {
        when(systemConfigService.getLong("circuit.breaker.max.tokens.per.run", 100000L, 1000, 10_000_000))
                .thenThrow(new RuntimeException("DB down"));
        when(systemConfigService.getInt("circuit.breaker.max.iterations", 50, 1, 500))
                .thenThrow(new RuntimeException("DB down"));
        when(systemConfigService.getDouble("circuit.breaker.error.rate.threshold", 0.5, 0.0, 1.0))
                .thenThrow(new RuntimeException("DB down"));
        when(systemConfigService.getLong("circuit.breaker.max.iteration.latency.ms", 300000L, 10000, 3_600_000))
                .thenThrow(new RuntimeException("DB down"));

        Logger logger = (Logger) LoggerFactory.getLogger(CircuitBreakerProperties.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CircuitBreakerProperties props = propsWithService(systemConfigService);

            // Defaults still returned, and each failure is surfaced via a WARN log
            assertThat(props.getMaxTokensPerRun()).isEqualTo(100000L);
            assertThat(props.getMaxIterations()).isEqualTo(50);
            assertThat(props.getErrorRateThreshold()).isEqualTo(0.5);
            assertThat(props.getMaxIterationLatencyMs()).isEqualTo(300000L);
            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(4);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
