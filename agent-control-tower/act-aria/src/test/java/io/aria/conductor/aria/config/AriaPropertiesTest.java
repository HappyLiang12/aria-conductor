package io.aria.conductor.aria.config;

import io.aria.conductor.agent.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AriaPropertiesTest {

    @Mock
    SystemConfigService systemConfigService;

    @Test
    void overlayFromDb_overridesYamlDefaults() throws Exception {
        when(systemConfigService.getInt("aria.max.history.turns", 20, 1, 100))
                .thenReturn(40);
        when(systemConfigService.getInt("aria.session.ttl.minutes", 60, 5, 1440))
                .thenReturn(120);

        AriaProperties props = new AriaProperties();
        // Inject via reflection to simulate @Autowired
        java.lang.reflect.Field field = AriaProperties.class.getDeclaredField("systemConfigService");
        field.setAccessible(true);
        field.set(props, systemConfigService);
        props.overlayFromDb();

        assertThat(props.getMaxHistoryTurns()).isEqualTo(40);
        assertThat(props.getSessionTtlMinutes()).isEqualTo(120);
    }

    @Test
    void overlayFromDb_keepsDefaults_whenServiceThrows() throws Exception {
        when(systemConfigService.getInt("aria.max.history.turns", 20, 1, 100))
                .thenThrow(new RuntimeException("DB unavailable"));

        AriaProperties props = new AriaProperties();
        java.lang.reflect.Field field = AriaProperties.class.getDeclaredField("systemConfigService");
        field.setAccessible(true);
        field.set(props, systemConfigService);
        props.overlayFromDb();

        assertThat(props.getMaxHistoryTurns()).isEqualTo(20);
        assertThat(props.getSessionTtlMinutes()).isEqualTo(60);
    }

    @Test
    void defaultConstructor_createsWithDefaults() {
        AriaProperties props = new AriaProperties();

        // Defaults should be intact before overlayFromDb()
        assertThat(props.getMaxHistoryTurns()).isEqualTo(20);
        assertThat(props.getSessionTtlMinutes()).isEqualTo(60);
        assertThat(props.getSystemPrompt()).contains("Aria");
    }
}
