package io.aria.conductor.dashboard.report;

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
class ReportPropertiesTest {

    @Mock
    SystemConfigService systemConfigService;

    private ReportProperties propsWithService(SystemConfigService svc) throws Exception {
        ReportProperties props = new ReportProperties();
        java.lang.reflect.Field field = ReportProperties.class.getDeclaredField("systemConfigService");
        field.setAccessible(true);
        field.set(props, svc);
        return props;
    }

    @Test
    void getters_returnDbValues() throws Exception {
        when(systemConfigService.getInt("report.generate.max.tokens", 16384, 4096, 131072))
                .thenReturn(32768);
        when(systemConfigService.getInt("report.amend.max.tokens", 16384, 4096, 131072))
                .thenReturn(8192);

        ReportProperties props = propsWithService(systemConfigService);

        assertThat(props.getGenerateMaxTokens()).isEqualTo(32768);
        assertThat(props.getAmendMaxTokens()).isEqualTo(8192);
    }

    @Test
    void getters_fallBackToDefaults_whenServiceThrows() throws Exception {
        when(systemConfigService.getInt("report.generate.max.tokens", 16384, 4096, 131072))
                .thenThrow(new RuntimeException("DB down"));
        when(systemConfigService.getInt("report.amend.max.tokens", 16384, 4096, 131072))
                .thenThrow(new RuntimeException("DB down"));

        ReportProperties props = propsWithService(systemConfigService);

        assertThat(props.getGenerateMaxTokens()).isEqualTo(16384);
        assertThat(props.getAmendMaxTokens()).isEqualTo(16384);
    }

    @Test
    void getters_reflectDbChanges_withoutRestart() throws Exception {
        when(systemConfigService.getInt("report.generate.max.tokens", 16384, 4096, 131072))
                .thenReturn(16384)
                .thenReturn(32768);
        when(systemConfigService.getInt("report.amend.max.tokens", 16384, 4096, 131072))
                .thenReturn(16384)
                .thenReturn(8192);

        ReportProperties props = propsWithService(systemConfigService);

        assertThat(props.getGenerateMaxTokens()).isEqualTo(16384);
        assertThat(props.getAmendMaxTokens()).isEqualTo(16384);
        // Second read reflects the updated DB values — no restart required
        assertThat(props.getGenerateMaxTokens()).isEqualTo(32768);
        assertThat(props.getAmendMaxTokens()).isEqualTo(8192);
    }

    @Test
    void getters_logWarn_whenServiceThrows() throws Exception {
        when(systemConfigService.getInt("report.generate.max.tokens", 16384, 4096, 131072))
                .thenThrow(new RuntimeException("DB down"));
        when(systemConfigService.getInt("report.amend.max.tokens", 16384, 4096, 131072))
                .thenThrow(new RuntimeException("DB down"));

        Logger logger = (Logger) LoggerFactory.getLogger(ReportProperties.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReportProperties props = propsWithService(systemConfigService);

            // Defaults still returned, and each failure is surfaced via a WARN log
            assertThat(props.getGenerateMaxTokens()).isEqualTo(16384);
            assertThat(props.getAmendMaxTokens()).isEqualTo(16384);
            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(2);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
